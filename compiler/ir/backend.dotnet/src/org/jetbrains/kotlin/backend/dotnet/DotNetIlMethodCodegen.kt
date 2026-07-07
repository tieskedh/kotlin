package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrBreakContinue
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrCatch
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDoWhileLoop
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.types.isAny
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isAccessor
import org.jetbrains.kotlin.ir.util.isFalseConst
import org.jetbrains.kotlin.ir.util.isTrueConst
import org.jetbrains.kotlin.ir.util.render

/**
 * A successfully rendered method: its IL text plus the [runtime helpers][DotNetIlRuntimeHelper]
 * the body called, which [DotNetIlEmitter] aggregates to decide whether to emit the shared
 * helper class.
 */
internal class DotNetIlRenderedMethod(
    val ilText: String,
    val requiredRuntimeHelpers: Set<DotNetIlRuntimeHelper>,
)

/**
 * Renders a single function — a top-level `static` one, a user-class constructor, or an instance
 * member method/accessor — into IL text. The body is rendered into its own fresh buffer first, so
 * `.maxstack` and the `.locals init` block are computed from what was actually emitted; any
 * unsupported construct aborts the render with [DotNetIlUnsupportedException].
 *
 * For an [IrConstructor] the implicit `this` is CLR argument slot 0 and the declared parameters
 * shift up by one ([DotNetIlMethodContext]'s `firstArgumentIndex`); the constructor's
 * [functionInfo] carries the owning class's IL name as its class name and a `void` signature.
 * An instance member method needs no offset at all: its dispatch receiver IS `parameters[0]`, so
 * the plain zip already assigns it slot 0 (probe-confirmed CLR argument numbering).
 */
internal class DotNetIlMethodCodegen(
    private val function: IrFunction,
    functionInfo: DotNetIlFunctionInfo,
    private val isEntryPoint: Boolean,
    availableFunctions: Map<IrSimpleFunction, DotNetIlFunctionInfo>,
    private val intrinsicMethods: DotNetIlIntrinsicMethods,
    private val typeMapper: DotNetIlTypeMapper,
) {
    private val signature = functionInfo.signature
    private val methodContext = DotNetIlMethodContext(
        function.parameters,
        signature.parameterTypes,
        typeMapper,
        firstArgumentIndex = if (function is IrConstructor) 1 else 0,
    ).apply {
        if (function is IrConstructor) {
            registerThis(
                function.constructedClass.thisReceiver!!.symbol,
                DotNetIlValueType.UserClass(functionInfo.className),
            )
        }
    }
    private val expressionCodegen =
        DotNetIlExpressionCodegen(methodContext, availableFunctions, intrinsicMethods, typeMapper, ::emitTryExpression)

    /**
     * The join label of returns that crossed protected regions and its synthetic return-value
     * local, both created lazily by the first such return; see [emitReturnAcrossRegions].
     */
    private var returnJoinLabel: String? = null
    private var returnValueSlot: DotNetIlSlot.Local? = null

    fun render(): DotNetIlRenderedMethod {
        emitBody()
        val ilText = buildString {
            // The printed parameter list never contains the implicit `this` of an instance
            // method: the dispatch-receiver pair of the zip is dropped (an IrConstructor's
            // parameter list carries no dispatch receiver to begin with).
            val parameters = function.parameters.zip(signature.parameterTypes)
                .drop(if (signature.hasThis) 1 else 0)
                .joinToString(", ") { (parameter, type) ->
                    "${type.nameInSignature} ${parameter.name.asString().toIlIdentifier()}"
                }
            if (function is IrConstructor) {
                // `.ctor` is a bare keyword, not a quoted identifier; the spelling including the
                // specialname/rtspecialname flags is ilasm-probe-verified.
                appendLine("  .method public hidebysig specialname rtspecialname instance void .ctor($parameters) cil managed")
            } else {
                // Instance member methods differ from static ones only in the `instance` flag;
                // property accessors additionally carry `specialname`, binding them to the
                // `.property` metadata (both spellings are ilasm-probe-verified).
                val specialname = if (function.isAccessor) "specialname " else ""
                val dispatch = if (signature.hasThis) "instance" else "static"
                val methodName = (function as IrSimpleFunction).dotNetIlMethodName()
                appendLine(
                    "  .method public hidebysig $specialname$dispatch ${signature.returnType.nameInSignature} " +
                            "${methodName.toIlIdentifier()}($parameters) cil managed"
                )
            }
            appendLine("  {")
            if (isEntryPoint) {
                appendLine("    .entrypoint")
            }
            appendLine("    .maxstack ${methodContext.maxStack}")
            appendLocals()
            append(methodContext.renderBody())
            appendLine("  }")
        }
        return DotNetIlRenderedMethod(ilText, methodContext.requiredRuntimeHelpers)
    }

    private fun StringBuilder.appendLocals() {
        val locals = methodContext.locals
        if (locals.isEmpty()) return

        appendLine("    .locals init (")
        for ((index, local) in locals.withIndex()) {
            val separator = if (index == locals.lastIndex) "" else ","
            appendLine("      [${local.index}] ${local.type.nameInSignature} ${local.name.toIlIdentifier()}$separator")
        }
        appendLine("    )")
    }

    private fun emitBody() {
        when (val body = function.body) {
            is IrBlockBody -> {
                body.statements.forEach { emitStatement(it) }
                // A dead trailing ret after a mid-body return is harmless.
                if (signature.returnType == DotNetIlReturnType.Void) {
                    methodContext.emitReturn()
                }
            }
            is IrExpressionBody -> when (val returnType = signature.returnType) {
                is DotNetIlReturnType.Value -> {
                    expressionCodegen.emitExpression(body.expression, returnType.type)
                    methodContext.emitReturn(pops = 1)
                }
                DotNetIlReturnType.Void -> {
                    emitVoidExpression(body.expression)
                    methodContext.emitReturn()
                }
            }
            null -> dotNetUnsupported("function has no body")
            else -> dotNetUnsupported("unsupported function body shape ${body.javaClass.simpleName}")
        }
        emitReturnJoinEpilogue()
    }

    /**
     * The join point of returns that crossed protected regions (see [emitReturnAcrossRegions]):
     * reloads the drained return value and returns, once, after the rendered body (dead trailing
     * instructions before the label are harmless, probe-verified). No epilogue exists when no
     * return crossed a region.
     */
    private fun emitReturnJoinEpilogue() {
        val label = returnJoinLabel ?: return
        methodContext.emitLabel(label)
        val slot = returnValueSlot
        if (slot == null) {
            methodContext.emitReturn()
        } else {
            methodContext.emit(loadLocalInstruction(slot.index), pushes = 1)
            methodContext.emitReturn(pops = 1)
        }
    }

    private fun emitStatement(statement: IrStatement) {
        when (statement) {
            is IrVariable -> emitVariable(statement)
            is IrExpression -> emitVoidExpression(statement)
            else -> dotNetUnsupported("unsupported statement ${statement.javaClass.simpleName}")
        }
    }

    private fun emitVariable(variable: IrVariable) {
        val slot = methodContext.declareLocal(variable)
        val initializer = variable.initializer ?: return
        expressionCodegen.emitExpression(initializer, slot.type)
        methodContext.emit(storeLocalInstruction(slot.index), pops = 1)
    }

    private fun emitVoidExpression(expression: IrExpression) {
        when {
            expression is IrReturn -> emitReturn(expression)
            // Calls in statement position get the same handling as explicitly discarded values,
            // so intrinsic-only callees work identically in both shapes.
            expression is IrCall -> emitDiscardableExpression(expression)
            expression is IrSetValue -> emitSetValue(expression)
            expression is IrSetField -> expressionCodegen.emitSetField(expression)
            // An instantiation in statement position (`Point(5)`) is created and discarded.
            expression is IrConstructorCall -> emitDiscardableExpression(expression)
            expression is IrDelegatingConstructorCall -> emitDelegatingConstructorCall(expression)
            expression is IrInstanceInitializerCall ->
                dotNetUnsupported("internal: IrInstanceInitializerCall survived InitializersLowering")
            expression is IrThrow -> expressionCodegen.emitThrow(expression)
            expression is IrTry -> emitTryStatement(expression)
            expression is IrWhen -> emitWhenStatement(expression)
            expression is IrWhileLoop -> emitWhileLoop(expression)
            expression is IrDoWhileLoop -> emitDoWhileLoop(expression)
            expression is IrBreakContinue -> emitBreakContinue(expression)
            expression is IrTypeOperatorCall && expression.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT ->
                emitDiscardableExpression(expression.argument)
            expression is IrGetObjectValue && expression.type.isUnit() -> Unit
            expression is IrContainerExpression -> emitBlockStatement(expression)
            // A side-effect-free value read in statement position (e.g. the trailing `<unary>`
            // read of a desugared `x++` block) compiles to nothing.
            expression is IrGetValue -> Unit
            else -> dotNetUnsupported("unsupported statement expression ${expression.javaClass.simpleName}")
        }
    }

    private fun emitReturn(expression: IrReturn) {
        if (expression.returnTargetSymbol != function.symbol) {
            dotNetUnsupported("non-local return is not supported")
        }
        if (methodContext.ehDepth > 0) {
            // A return inside a `finally` body would have to leave the finally region, and the
            // CLR's only legal exit from one is `endfinally` — even `leave` may not cross it.
            if (methodContext.crossesFinallyRegion(0)) {
                dotNetUnsupported("'return' inside a 'finally' block is not supported")
            }
            emitReturnAcrossRegions(expression)
            return
        }
        when (val returnType = signature.returnType) {
            is DotNetIlReturnType.Value -> {
                expressionCodegen.emitExpression(expression.value, returnType.type)
                methodContext.emitReturn(pops = 1)
            }
            DotNetIlReturnType.Void -> {
                emitVoidExpression(expression.value)
                methodContext.emitReturn()
            }
        }
    }

    /**
     * A `return` inside a protected region: `ret` there assembles silently but throws
     * `InvalidProgramException` at runtime, so the return value is drained into a lazily created
     * synthetic local and control leaves to a shared return-join label whose epilogue reloads it
     * and returns (`stloc`/`leave`/`ldloc`/`ret`, the probe-verified pattern — the same shape
     * Roslyn emits for returns crossing protected regions). A single `leave` legally crosses any
     * number of nested regions in one hop, so the depth never matters.
     */
    private fun emitReturnAcrossRegions(expression: IrReturn) {
        when (val returnType = signature.returnType) {
            is DotNetIlReturnType.Value -> {
                expressionCodegen.emitExpression(expression.value, returnType.type)
                if (methodContext.isTerminated) return // the value itself threw; nothing returns
                val slot = returnValueSlot
                    ?: methodContext.declareSyntheticLocal(returnType.type, "<return>").also { returnValueSlot = it }
                methodContext.emit(storeLocalInstruction(slot.index), pops = 1)
            }
            DotNetIlReturnType.Void -> {
                emitVoidExpression(expression.value)
                if (methodContext.isTerminated) return
            }
        }
        val label = returnJoinLabel
            ?: methodContext.nextLabel("returnJoin").also { returnJoinLabel = it }
        methodContext.emitLeave(label)
    }

    /**
     * The delegation statement of a constructor body: `ldarg.0`, the arguments, then a plain
     * (non-virtual) `call` to either `System.Object::.ctor()` — the `kotlin.Any` supertype
     * constructor, `kotlin.Any` having no IL class of its own — or the sibling constructor of a
     * `this(...)` delegation. Both call shapes, including code before and after the delegation,
     * are ilasm-probe-verified.
     */
    private fun emitDelegatingConstructorCall(call: IrDelegatingConstructorCall) {
        if (function !is IrConstructor) {
            dotNetUnsupported("delegating constructor call outside a constructor body")
        }
        val target = call.symbol.owner
        val targetClass = target.constructedClass
        methodContext.emit("ldarg.0", pushes = 1)
        if (targetClass.defaultType.isAny()) {
            methodContext.emit("call instance void ${CORE_LIB_REF}System.Object::.ctor()", pops = 1)
            return
        }
        val classInfo = typeMapper.classInfoOrNull(targetClass)
            ?: dotNetUnsupported("delegating call to a constructor of unsupported class '${targetClass.name.asString()}'")
        val parameterTypes = target.dotNetSignature(typeMapper).parameterTypes
        expressionCodegen.emitArguments(call.arguments, parameterTypes, "constructor of '${targetClass.name.asString()}'")
        methodContext.emit("call ${classInfo.renderConstructorReference(parameterTypes)}", pops = 1 + parameterTypes.size)
    }

    private fun emitSetValue(expression: IrSetValue) {
        val slot = methodContext.reference(expression.symbol) as? DotNetIlSlot.Local
            ?: dotNetUnsupported("assignment to unsupported target '${expression.symbol.owner.name.asString()}'")
        expressionCodegen.emitExpression(expression.value, slot.type)
        methodContext.emit(storeLocalInstruction(slot.index), pops = 1)
    }

    private fun emitWhenStatement(expression: IrWhen) {
        val endLabel = methodContext.nextLabel("whenEnd")

        for (branch in expression.branches) {
            if (branch.condition.isFalseConst()) continue

            if (branch.condition.isTrueConst()) {
                emitVoidExpression(branch.result)
                break
            }

            val nextBranchLabel = methodContext.nextLabel("whenNext")
            expressionCodegen.emitBranchIfFalse(branch.condition, nextBranchLabel)
            emitVoidExpression(branch.result)
            if (!methodContext.isTerminated) {
                methodContext.emitGoto(endLabel)
            }
            methodContext.emitLabel(nextBranchLabel)
        }

        // The end label is skipped when every branch returned: an unreferenced label at the very
        // end of a method would leave a branch target past the last instruction.
        if (methodContext.isLabelReferenced(endLabel)) {
            methodContext.emitLabel(endLabel)
        }
    }

    /**
     * A block (or composite) in statement position, e.g. a loop body or the desugaring of `x++`:
     * every child is a statement; the block's value, if any, is a trailing side-effect-free value
     * read that [emitVoidExpression] drops. Any [IrVariable] declared inside gets its local slot
     * lazily through [DotNetIlMethodContext.declareLocal], so shadowing locals in sibling scopes
     * simply occupy distinct slots (per-symbol) with deduplicated names.
     */
    private fun emitBlockStatement(block: IrContainerExpression) {
        block.statements.forEach { emitStatement(it) }
    }

    /**
     * `while (cond) body`:
     * ```
     * condLabel: cond; brfalse endLabel; body; br condLabel; endLabel:
     * ```
     * `continue` jumps to `condLabel`, `break` to `endLabel`.
     */
    private fun emitWhileLoop(loop: IrWhileLoop) {
        val conditionLabel = methodContext.nextLabel("whileCond")
        val endLabel = methodContext.nextLabel("whileEnd")
        methodContext.registerLoop(
            loop,
            DotNetIlLoopLabels(breakLabel = endLabel, continueLabel = conditionLabel, ehDepth = methodContext.ehDepth),
        )

        methodContext.emitLabel(conditionLabel)
        expressionCodegen.emitBranchIfFalse(loop.condition, endLabel)
        loop.body?.let { emitVoidExpression(it) }
        // The back edge is dead when the body ends with return/break/continue.
        if (!methodContext.isTerminated) {
            methodContext.emitGoto(conditionLabel)
        }
        methodContext.emitLabel(endLabel)

        methodContext.unregisterLoop(loop)
    }

    /**
     * `do body while (cond)`:
     * ```
     * bodyLabel: body; condLabel: cond; brtrue bodyLabel; endLabel:
     * ```
     * `continue` jumps to `condLabel`, `break` to `endLabel`. The end label is only emitted when
     * a `break` referenced it: execution otherwise just falls through past `brtrue`.
     */
    private fun emitDoWhileLoop(loop: IrDoWhileLoop) {
        val bodyLabel = methodContext.nextLabel("doWhileBody")
        val conditionLabel = methodContext.nextLabel("doWhileCond")
        val endLabel = methodContext.nextLabel("doWhileEnd")
        methodContext.registerLoop(
            loop,
            DotNetIlLoopLabels(breakLabel = endLabel, continueLabel = conditionLabel, ehDepth = methodContext.ehDepth),
        )

        methodContext.emitLabel(bodyLabel)
        loop.body?.let { emitVoidExpression(it) }
        // Like the end label below, the condition label is only emitted when a `continue`
        // referenced it; execution otherwise just falls through from the body.
        if (methodContext.isLabelReferenced(conditionLabel)) {
            methodContext.emitLabel(conditionLabel)
        }
        expressionCodegen.emitExpression(loop.condition, DotNetIlValueType.Boolean)
        methodContext.emitBranch("brtrue", bodyLabel, pops = 1)
        if (methodContext.isLabelReferenced(endLabel)) {
            methodContext.emitLabel(endLabel)
        }

        methodContext.unregisterLoop(loop)
    }

    private fun emitBreakContinue(jump: IrBreakContinue) {
        val keyword = if (jump is IrBreak) "break" else "continue"
        val labels = methodContext.loopLabelsOrNull(jump.loop)
            ?: dotNetUnsupported(
                "'$keyword${jump.label?.let { "@$it" }.orEmpty()}' targets a loop outside the function being compiled"
            )
        val targetLabel = if (jump is IrBreak) labels.breakLabel else labels.continueLabel
        // A break/continue at a deeper exception-region depth than its loop crosses protected
        // regions and must exit via `leave` — legal toward any label of an enclosing scope,
        // forward or backward, crossing nested regions in one hop (probe-verified).
        when {
            methodContext.ehDepth == labels.ehDepth -> methodContext.emitGoto(targetLabel)
            methodContext.ehDepth > labels.ehDepth -> {
                // A `leave` may cross any number of `.try`/`catch` regions, but never a
                // `finally` body: its only legal exit is `endfinally`.
                if (methodContext.crossesFinallyRegion(labels.ehDepth)) {
                    dotNetUnsupported("'$keyword' crossing out of a 'finally' block is not supported")
                }
                methodContext.emitLeave(targetLabel)
            }
            else -> error("Internal .NET backend error: '$keyword' at a shallower exception-region depth than its loop")
        }
    }

    private fun emitDiscardableExpression(expression: IrExpression) {
        when (expression) {
            is IrContainerExpression -> emitBlockStatement(expression)
            is IrGetValue -> Unit
            is IrBreakContinue -> emitBreakContinue(expression)
            // A discarded throw produces no value to pop: `throw` terminates the emission point.
            is IrThrow -> expressionCodegen.emitThrow(expression)
            is IrTry -> emitDiscardedTry(expression)
            is IrCall -> {
                val intrinsic = intrinsicMethods.getIntrinsic(expression.symbol)
                if (intrinsic != null) {
                    if (intrinsic.tryEmitAsStatement(expression, expressionCodegen)) return
                    val valueType = typeMapper.toDotNetIlValueType(expression.type)
                    if (valueType != null && intrinsic.tryEmitAsExpression(expression, expressionCodegen, valueType)) {
                        methodContext.emit("pop", pops = 1)
                        return
                    }
                }
                emitCallStatement(expression)
            }
            is IrGetObjectValue -> {
                if (!expression.type.isUnit()) {
                    dotNetUnsupported("unsupported object discard: ${expression.symbol.owner.name.asString()}")
                }
            }
            else -> {
                val valueType = typeMapper.toDotNetIlValueType(expression.type)
                    ?: dotNetUnsupported("cannot discard value of unsupported type ${expression.javaClass.simpleName}")
                expressionCodegen.emitExpression(expression, valueType)
                methodContext.emit("pop", pops = 1)
            }
        }
    }

    private fun emitCallStatement(call: IrCall) {
        if (expressionCodegen.emitCall(call) is DotNetIlReturnType.Value) {
            methodContext.emit("pop", pops = 1)
        }
    }

    /**
     * A `try`/`catch` in statement position — following the JVM backend, [IrTry] maps 1:1 onto
     * the platform exception table (a `.try` block with consecutive typed `catch` handlers) with
     * no lowering machinery. Every branch that completes normally exits with `leave` to the join
     * label after the construct; the label is skipped when every branch terminated (returned or
     * threw), exactly like [emitWhenStatement]'s end label.
     */
    private fun emitTryStatement(expression: IrTry) {
        val endLabel = methodContext.nextLabel("tryEnd")
        emitTryCatchRegions(expression) { branchResult ->
            emitVoidExpression(branchResult)
            if (!methodContext.isTerminated) {
                methodContext.emitLeave(endLabel)
            }
        }
        if (methodContext.isLabelReferenced(endLabel)) {
            methodContext.emitLabel(endLabel)
        }
    }

    /**
     * A `try`/`catch` in value position (Kotlin's `IrTry` has a value): `leave` discards the
     * evaluation stack (ECMA-335), so the branch results cannot cross the region boundary on the
     * stack — each branch drains its value into a synthetic result local and the join label
     * reloads it (probe-verified template). The CLR additionally requires an empty evaluation
     * stack at `.try` entry, so a `try` expression with operands already on the stack (e.g. as a
     * non-first call argument) is rejected — a stated deviation from the JVM backend, whose
     * platform has no such restriction (operand spilling is deferred).
     */
    private fun emitTryExpression(expression: IrTry, expectedType: DotNetIlValueType) {
        if (methodContext.stackDepth != 0) {
            dotNetUnsupported("'try' expression with operands already on the evaluation stack is not supported")
        }
        val endLabel = methodContext.nextLabel("tryEnd")
        val resultSlot = methodContext.declareSyntheticLocal(expectedType, "<try>")
        emitTryCatchRegions(expression) { branchResult ->
            emitValueExpression(branchResult, expectedType)
            if (!methodContext.isTerminated) {
                methodContext.emit(storeLocalInstruction(resultSlot.index), pops = 1)
                methodContext.emitLeave(endLabel)
            }
        }
        methodContext.emitLabel(endLabel)
        methodContext.emit(loadLocalInstruction(resultSlot.index), pushes = 1)
    }

    /**
     * A non-Unit `try` in statement position (it arrives under `IMPLICIT_COERCION_TO_UNIT`; a
     * Unit-typed `try` statement arrives bare in [emitVoidExpression]). When the try's type
     * maps, it is emitted in expression form and the reloaded result is popped — the branch
     * values are expressions (e.g. a trailing constant) that statement emission cannot handle.
     * A `Nothing`-typed (or otherwise unmapped) `try` uses statement form instead, whose
     * branches are emitted as statements.
     */
    private fun emitDiscardedTry(expression: IrTry) {
        val valueType = typeMapper.toDotNetIlValueType(expression.type)
        if (valueType == null) {
            emitTryStatement(expression)
        } else {
            emitTryExpression(expression, valueType)
            methodContext.emit("pop", pops = 1)
        }
    }

    /**
     * The region structure shared by both `try` forms. Without a `finally` this is `.try {`
     * around the try branch, then one `catch <clrTypeRef> {` per Kotlin catch clause in source
     * order — the CLR matches handlers strictly in declaration order (probe-verified), and
     * Kotlin source order is authoritative (the frontend owns unreachable-catch diagnostics).
     * Each handler first binds its catch parameter with a `stloc` of the exception object the
     * CLR pushes at handler entry.
     *
     * A `finally` wraps that whole try/catch construct in an OUTER `.try { } finally { }`: a
     * `.try` may carry either catch handlers or one `finally`, never both — combining them on
     * one `.try` assembles silently but throws `InvalidProgramException` at runtime
     * (probe-verified) — and with no catches the single `.try { } finally { }` region suffices.
     * Branch `leave`s keep targeting the join label after the WHOLE construct; the CLR runs the
     * finally automatically on every exit — normal leaves (including `break`/`continue` and
     * return-join ones) and the exceptional path alike — with NO JVM-style finally
     * inlining/duplication, a CLR-forced deviation from the JVM backend, whose platform has no
     * finally handlers to delegate to. The finally body is emitted as void and exits through
     * `endfinally`, its only legal exit.
     */
    private fun emitTryCatchRegions(expression: IrTry, emitBranchResult: (IrExpression) -> Unit) {
        val finallyExpression = expression.finallyExpression
        if (finallyExpression == null) {
            emitTryCatches(expression, emitBranchResult)
            return
        }
        methodContext.beginTry()
        if (expression.catches.isEmpty()) {
            emitBranchResult(expression.tryResult)
        } else {
            emitTryCatches(expression, emitBranchResult)
        }
        methodContext.beginFinally()
        emitVoidExpression(finallyExpression)
        methodContext.emitEndFinally()
        methodContext.endEhBlock()
    }

    /** The `.try` block plus its consecutive `catch` handlers; see [emitTryCatchRegions]. */
    private fun emitTryCatches(expression: IrTry, emitBranchResult: (IrExpression) -> Unit) {
        methodContext.beginTry()
        emitBranchResult(expression.tryResult)
        for (irCatch in expression.catches) {
            methodContext.beginCatch(catchTypeRef(irCatch))
            val slot = methodContext.declareLocal(irCatch.catchParameter)
            methodContext.emit(storeLocalInstruction(slot.index), pops = 1)
            emitBranchResult(irCatch.result)
        }
        methodContext.endEhBlock()
    }

    /**
     * The `catch` clause operand: the bare corelib-qualified reference of the caught type, which
     * must be a [mapped exception type][DotNetIlValueType.MappedClass] — `catch (e: Throwable)`
     * becomes a `catch` of the corelib `System.Exception`, catching everything the CLR throws.
     */
    private fun catchTypeRef(irCatch: IrCatch): String {
        val parameter = irCatch.catchParameter
        val type = typeMapper.toDotNetIlValueType(parameter.type)
        if (type !is DotNetIlValueType.MappedClass) {
            dotNetUnsupported("catch of a non-exception-mapped type ${parameter.type.render()} is not supported")
        }
        return type.ilTypeRef
    }

    /**
     * A value expression that may be a block: `try` branch bodies arrive as [IrContainerExpression]s
     * whose trailing expression is the branch value, preceded by arbitrary statements. A trailing
     * [IrReturn]/[IrBreakContinue] terminates the branch without producing a value (the caller's
     * `isTerminated` check skips the drain); everything else is emitted against [expectedType].
     */
    private fun emitValueExpression(expression: IrExpression, expectedType: DotNetIlValueType) {
        if (expression !is IrContainerExpression) {
            expressionCodegen.emitExpression(expression, expectedType)
            return
        }
        val last = expression.statements.lastOrNull()
            ?: dotNetUnsupported("empty block in value position")
        expression.statements.dropLast(1).forEach { emitStatement(it) }
        when (last) {
            is IrReturn -> emitReturn(last)
            is IrBreakContinue -> emitBreakContinue(last)
            is IrExpression -> emitValueExpression(last, expectedType)
            else -> dotNetUnsupported("unsupported trailing statement ${last.javaClass.simpleName} in a block in value position")
        }
    }
}
