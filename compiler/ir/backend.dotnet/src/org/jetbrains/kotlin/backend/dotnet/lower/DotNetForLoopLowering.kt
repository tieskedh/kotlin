package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.IrBuildingTransformer
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.common.lower.irIfThen
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.dotNetInvariantArrayElementTypeOrNull
import org.jetbrains.kotlin.backend.dotnet.isSupportedDotNetPrimitiveArray
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNotEquals
import org.jetbrains.kotlin.ir.builders.irSet
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBreakContinue
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDoWhileLoopImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhileLoopImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getPropertyGetter
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.util.OperatorNameConventions

/**
 * Rewrites `for (i in a..b)` over `Int` ranges into an induction-variable loop over constructs
 * the IL emitter already supports, so no `IntRange`/`IntIterator` object ever exists at runtime.
 *
 * Follows the mature targets' `ForLoopsLowering` (backend.common, used by JVM/JS/Native/WASM),
 * restricted to the counted header shapes this backend can profit from today:
 * `Int.rangeTo(Int)` and the private stdlib-bootstrap `Int.until(Int)`, `Int.downTo(Int)`, and
 * generic `Array.indices` resolution markers.
 * fir2ir desugars every `for` loop into
 *
 * ```
 * BLOCK origin=FOR_LOOP
 *   VAR FOR_LOOP_ITERATOR <iterator> = <range expression>.iterator()
 *   WHILE origin=FOR_LOOP_INNER_WHILE (<iterator>.hasNext())
 *     BLOCK origin=FOR_LOOP_INNER_WHILE
 *       VAR FOR_LOOP_VARIABLE i = <iterator>.next()
 *       <body statements>
 * ```
 *
 * and this pass pattern-matches exactly that shape with `<range expression>` being a call to
 * `kotlin.Int.rangeTo(Int)` (both bounds resolve against builtins metadata — `IntRange` and
 * `IntIterator` need no fake-stdlib stubs). The match is rewritten in place to
 * `ForLoopsLowering`'s overflow-safe `canOverflow` form:
 *
 * ```
 * BLOCK origin=FOR_LOOP
 *   VAR inductionVariable = a
 *   VAR last = b
 *   if (inductionVariable <= last) {                    // guards the empty range: b < a runs 0 times
 *     DO_WHILE origin=FOR_LOOP_INNER_WHILE
 *       BLOCK origin=FOR_LOOP_INNER_WHILE
 *         VAR FOR_LOOP_VARIABLE i = inductionVariable   // the original variable, references intact
 *         inductionVariable = inductionVariable + 1
 *         <body statements>
 *       while (i != last)
 *   }
 * ```
 *
 * The increment happens *before* the body and the exit condition compares the already-consumed
 * value `i` (not the induction variable) against `last`: a naive `while (index <= last)` would
 * loop forever when `b == Int.MAX_VALUE`, because the increment past `MAX_VALUE` wraps to
 * `MIN_VALUE <= last`. Here the final iteration's wrapped `inductionVariable` is never read —
 * the loop exits on `i == last` first. `continue` correctly re-enters at the `i != last` check
 * since its `i` copy predates the jump and the increment already happened at the top of the body.
 *
 * Public or user-defined `until`/`downTo`/`indices` calls remain non-matching. The private markers are
 * consumed into counted loops before codegen so the bootstrap product does not publish
 * `IntRange`/`IntProgression`. Other headers (`step`, `Long` ranges, general iterables) are
 * left untouched; the emitter then reports the containing function as unsupported through its
 * regular skip path ("local '<iterator>' has unsupported type ...").
 *
 * Like `ForLoopsLowering`, `break`/`continue` targets are retargeted from the removed
 * `IrWhileLoop` to the replacement `IrDoWhileLoop` in a second pass over the file, after all
 * loops (including nested ones) have been rewritten.
 */
internal class DotNetForLoopLowering(
    context: DotNetBackendContext,
) : FileLoweringPass, IrBuildingTransformer(context) {
    private val irBuiltIns = context.irBuiltIns
    private val intPlusInt = irBuiltIns.intClass.owner.functions
        .single { function ->
            function.name == OperatorNameConventions.PLUS &&
                    function.parameters.singleOrNull { it.kind == IrParameterKind.Regular }?.type?.isInt() == true
        }.symbol
    private val intMinusInt = irBuiltIns.intClass.owner.functions
        .single { function ->
            function.name == OperatorNameConventions.MINUS &&
                    function.parameters.singleOrNull { it.kind == IrParameterKind.Regular }?.type?.isInt() == true
        }.symbol
    private val intLessOrEqual = irBuiltIns.lessOrEqualFunByOperandType.getValue(irBuiltIns.intClass)
    private val intLess = irBuiltIns.lessFunByOperandType.getValue(irBuiltIns.intClass)

    private val oldLoopToNewLoop = hashMapOf<IrLoop, IrLoop>()

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(this)

        if (oldLoopToNewLoop.isEmpty()) return
        irFile.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitBreakContinue(jump: IrBreakContinue): IrExpression {
                oldLoopToNewLoop[jump.loop]?.let { jump.loop = it }
                return jump
            }
        })
        oldLoopToNewLoop.clear()
    }

    override fun visitBlock(expression: IrBlock): IrExpression {
        // Children first: nested for-loops inside this loop's body are rewritten before the
        // enclosing one, matching ForLoopsLowering's transformer order.
        expression.transformChildrenVoid(this)
        lowerArrayForLoop(expression)
        lowerIntRangeForLoop(expression)
        return expression
    }

    /**
     * Rewrites direct iteration over a supported primitive or invariant generic array to
     * backend.common's indexed-get loop shape: the receiver is evaluated once, its immutable
     * size is cached, and the index is incremented before the user body so `continue` cannot
     * skip it.
     */
    private fun lowerArrayForLoop(block: IrBlock) {
        if (block.origin != IrStatementOrigin.FOR_LOOP || block.statements.size != 2) return

        val iteratorVariable = block.statements[0] as? IrVariable ?: return
        if (iteratorVariable.origin != IrDeclarationOrigin.FOR_LOOP_ITERATOR) return
        val iteratorCall = iteratorVariable.initializer as? IrCall ?: return
        if (iteratorCall.symbol.owner.name != OperatorNameConventions.ITERATOR) return
        val arrayExpression = iteratorCall.arguments.singleOrNull() ?: return
        val arrayClass = arrayExpression.type.classifierOrNull as? IrClassSymbol ?: return
        val elementType = if (arrayExpression.type.isSupportedDotNetPrimitiveArray()) {
            irBuiltIns.primitiveArrayElementTypes[arrayClass]
        } else {
            arrayExpression.type.dotNetInvariantArrayElementTypeOrNull()
        } ?: return

        val whileLoop = block.statements[1] as? IrWhileLoop ?: return
        val hasNextCall = whileLoop.condition as? IrCall ?: return
        if (hasNextCall.symbol.owner.name != OperatorNameConventions.HAS_NEXT) return
        if (!hasNextCall.arguments.singleOrNull().isGetOf(iteratorVariable)) return
        val whileBody = whileLoop.body as? IrBlock ?: return
        val loopVariable = whileBody.statements.firstOrNull() as? IrVariable ?: return
        if (loopVariable.origin != IrDeclarationOrigin.FOR_LOOP_VARIABLE || loopVariable.type != elementType) return
        val nextCall = loopVariable.initializer as? IrCall ?: return
        if (nextCall.symbol.owner.name != OperatorNameConventions.NEXT) return
        if (!nextCall.arguments.singleOrNull().isGetOf(iteratorVariable)) return

        val sizeGetter = arrayClass.getPropertyGetter("size") ?: return
        val getFunction = arrayClass.owner.functions.singleOrNull { function ->
            function.name == OperatorNameConventions.GET &&
                    function.parameters.singleOrNull { it.kind == IrParameterKind.Regular }?.type?.isInt() == true
        }?.symbol ?: return

        with(builder) {
            at(block)
            val indexedObject = scope.createTemporaryVariable(
                arrayExpression, nameHint = "indexedObject", inventUniqueName = false,
            )
            val inductionVariable = scope.createTemporaryVariable(
                irInt(0), nameHint = "inductionVariable", isMutable = true, inventUniqueName = false,
            )
            val lastVariable = scope.createTemporaryVariable(
                irCall(sizeGetter).apply { arguments[0] = irGet(indexedObject) },
                nameHint = "last",
                inventUniqueName = false,
            )

            loopVariable.initializer = irCall(getFunction).apply {
                arguments[0] = irGet(indexedObject)
                arguments[1] = irGet(inductionVariable)
            }
            val increment = irSet(
                inductionVariable,
                irCall(intPlusInt).apply {
                    arguments[0] = irGet(inductionVariable)
                    arguments[1] = irInt(1)
                },
            )
            val newBody = IrBlockImpl(
                whileBody.startOffset, whileBody.endOffset, whileBody.type, whileBody.origin,
                listOf(loopVariable, increment) + whileBody.statements.drop(1),
            )
            val newLoop = IrWhileLoopImpl(
                whileLoop.startOffset, whileLoop.endOffset, whileLoop.type, whileLoop.origin,
            ).apply {
                label = whileLoop.label
                condition = irCall(intLess).apply {
                    arguments[0] = irGet(inductionVariable)
                    arguments[1] = irGet(lastVariable)
                }
                body = newBody
            }
            oldLoopToNewLoop[whileLoop] = newLoop

            block.statements.clear()
            block.statements += indexedObject
            block.statements += inductionVariable
            block.statements += lastVariable
            block.statements += newLoop
        }
    }

    /** Rewrites [block]'s statements in place when it is a `for` loop over `Int.rangeTo(Int)`. */
    private fun lowerIntRangeForLoop(block: IrBlock) {
        if (block.origin != IrStatementOrigin.FOR_LOOP || block.statements.size != 2) return

        val iteratorVariable = block.statements[0] as? IrVariable ?: return
        if (iteratorVariable.origin != IrDeclarationOrigin.FOR_LOOP_ITERATOR) return
        val iteratorCall = iteratorVariable.initializer as? IrCall ?: return
        if (iteratorCall.symbol.owner.name != OperatorNameConventions.ITERATOR) return
        val rangeCall = iteratorCall.arguments.singleOrNull() as? IrCall ?: return
        val isClosedRange = rangeCall.isIntRangeTo()
        val arrayIndicesReceiver = rangeCall.dotNetBootstrapArrayIndicesReceiverOrNull()
        val isEndExclusiveMarker = rangeCall.isDotNetBootstrapIntUntil() || arrayIndicesReceiver != null
        val isDescendingMarker = rangeCall.isDotNetBootstrapIntDownTo()
        if (!isClosedRange && !isEndExclusiveMarker && !isDescendingMarker) return
        val first = if (arrayIndicesReceiver == null) rangeCall.arguments[0] ?: return else null
        val end = if (arrayIndicesReceiver == null) rangeCall.arguments[1] ?: return else null
        val arraySizeGetter = arrayIndicesReceiver?.let { receiver ->
            (receiver.type.classifierOrNull as? IrClassSymbol)?.getPropertyGetter("size")
        }
        if (arrayIndicesReceiver != null && arraySizeGetter == null) return

        val whileLoop = block.statements[1] as? IrWhileLoop ?: return
        val hasNextCall = whileLoop.condition as? IrCall ?: return
        if (hasNextCall.symbol.owner.name != OperatorNameConventions.HAS_NEXT) return
        if (!hasNextCall.arguments.singleOrNull().isGetOf(iteratorVariable)) return
        val whileBody = whileLoop.body as? IrBlock ?: return
        val loopVariable = whileBody.statements.firstOrNull() as? IrVariable ?: return
        if (loopVariable.origin != IrDeclarationOrigin.FOR_LOOP_VARIABLE || !loopVariable.type.isInt()) return
        val nextCall = loopVariable.initializer as? IrCall ?: return
        if (nextCall.symbol.owner.name != OperatorNameConventions.NEXT) return
        if (!nextCall.arguments.singleOrNull().isGetOf(iteratorVariable)) return

        with(builder) {
            at(block)
            // The descriptive names are kept as-is (inventUniqueName = false), following
            // ProgressionLoopHeader; the IL method context deduplicates same-named locals of
            // sibling/nested loops with @slot suffixes.
            val inductionVariable = scope.createTemporaryVariable(
                arrayIndicesReceiver?.let { irInt(0) } ?: first!!,
                nameHint = "inductionVariable", isMutable = true, inventUniqueName = false,
            )
            val endVariable = scope.createTemporaryVariable(
                arrayIndicesReceiver?.let { receiver ->
                    irCall(arraySizeGetter!!).apply { arguments[0] = receiver }
                } ?: end!!,
                nameHint = if (isEndExclusiveMarker) "endExclusive" else "last",
                inventUniqueName = false,
            )

            // Reusing the original loop variable keeps every reference in the body valid.
            loopVariable.initializer = irGet(inductionVariable)
            val increment = irSet(
                inductionVariable,
                irCall(if (isDescendingMarker) intMinusInt else intPlusInt).apply {
                    arguments[0] = irGet(inductionVariable)
                    arguments[1] = irInt(1)
                },
            )
            val newBody = IrBlockImpl(
                whileBody.startOffset, whileBody.endOffset, whileBody.type, whileBody.origin,
                listOf(loopVariable, increment) + whileBody.statements.drop(1),
            )
            val newLoop = if (!isEndExclusiveMarker) {
                IrDoWhileLoopImpl(
                    whileLoop.startOffset, whileLoop.endOffset, whileLoop.type, whileLoop.origin,
                ).apply {
                    label = whileLoop.label
                    condition = irNotEquals(irGet(loopVariable), irGet(endVariable))
                    body = newBody
                }
            } else {
                IrWhileLoopImpl(
                    whileLoop.startOffset, whileLoop.endOffset, whileLoop.type, whileLoop.origin,
                ).apply {
                    label = whileLoop.label
                    condition = irCall(intLess).apply {
                        arguments[0] = irGet(inductionVariable)
                        arguments[1] = irGet(endVariable)
                    }
                    body = newBody
                }
            }
            oldLoopToNewLoop[whileLoop] = newLoop

            block.statements.clear()
            block.statements += inductionVariable
            block.statements += endVariable
            if (!isEndExclusiveMarker) {
                val notEmptyGuard = irCall(intLessOrEqual).apply {
                    if (isDescendingMarker) {
                        arguments[0] = irGet(endVariable)
                        arguments[1] = irGet(inductionVariable)
                    } else {
                        arguments[0] = irGet(inductionVariable)
                        arguments[1] = irGet(endVariable)
                    }
                }
                block.statements += irIfThen(notEmptyGuard, newLoop)
            } else {
                block.statements += newLoop
            }
        }
    }

    /** Matches `kotlin.Int.rangeTo(Int)` and not e.g. the `rangeTo(Long): LongRange` overload. */
    private fun IrCall.isIntRangeTo(): Boolean {
        val function = symbol.owner
        return function.name == OperatorNameConventions.RANGE_TO &&
                (function.parent as? IrClass)?.symbol == irBuiltIns.intClass &&
                function.parameters.singleOrNull { it.kind == IrParameterKind.Regular }?.type?.isInt() == true &&
                arguments.size == 2
    }

    /** Matches only the private marker emitted by the .NET Common-collections bootstrap source. */
    private fun IrCall.isDotNetBootstrapIntUntil(): Boolean {
        val function = symbol.owner
        val file = function.parent as? IrFile ?: return false
        return function.name.asString() == "until" &&
                file.packageFqName.asString() == "kotlin.collections" &&
                file.fileEntry.name.replace('\\', '/').substringAfterLast('/') ==
                    "_DotNetBootstrapCollections.kt" &&
                function.parameters.size == 2 &&
                function.parameters.all { parameter -> parameter.type.isInt() } &&
                arguments.size == 2
    }

    /** Matches only the private marker emitted by the .NET mutable-collections bootstrap source. */
    private fun IrCall.isDotNetBootstrapIntDownTo(): Boolean {
        val function = symbol.owner
        val file = function.parent as? IrFile ?: return false
        return function.name.asString() == "downTo" &&
                file.packageFqName.asString() == "kotlin.collections" &&
                file.fileEntry.name.replace('\\', '/').substringAfterLast('/') ==
                    "_DotNetBootstrapMutableCollections.kt" &&
                function.parameters.size == 2 &&
                function.parameters.all { parameter -> parameter.type.isInt() } &&
                arguments.size == 2
    }

    /** Matches only the private generic-array marker in the .NET Common-collections shard. */
    private fun IrCall.dotNetBootstrapArrayIndicesReceiverOrNull(): IrExpression? {
        val function = symbol.owner
        val file = function.parent as? IrFile ?: return null
        if (
            function.name.asString() != "<get-indices>" ||
            file.packageFqName.asString() != "kotlin.collections" ||
            file.fileEntry.name.replace('\\', '/').substringAfterLast('/') !=
                "_DotNetBootstrapCollections.kt" ||
            function.parameters.size != 1 ||
            function.parameters.single().type.classifierOrNull != irBuiltIns.arrayClass ||
            arguments.size != 1
        ) {
            return null
        }
        return arguments.single()
    }

    private fun IrExpression?.isGetOf(variable: IrVariable): Boolean =
        this is IrGetValue && symbol == variable.symbol
}
