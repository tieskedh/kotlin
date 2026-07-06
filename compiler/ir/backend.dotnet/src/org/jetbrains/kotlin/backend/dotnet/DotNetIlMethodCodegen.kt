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
    private val expressionCodegen = DotNetIlExpressionCodegen(methodContext, availableFunctions, intrinsicMethods, typeMapper)

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
        methodContext.registerLoop(loop, DotNetIlLoopLabels(breakLabel = endLabel, continueLabel = conditionLabel))

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
        methodContext.registerLoop(loop, DotNetIlLoopLabels(breakLabel = endLabel, continueLabel = conditionLabel))

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
        methodContext.emitGoto(if (jump is IrBreak) labels.breakLabel else labels.continueLabel)
    }

    private fun emitDiscardableExpression(expression: IrExpression) {
        when (expression) {
            is IrContainerExpression -> emitBlockStatement(expression)
            is IrGetValue -> Unit
            is IrBreakContinue -> emitBreakContinue(expression)
            // A discarded throw produces no value to pop: `throw` terminates the emission point.
            is IrThrow -> expressionCodegen.emitThrow(expression)
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
}
