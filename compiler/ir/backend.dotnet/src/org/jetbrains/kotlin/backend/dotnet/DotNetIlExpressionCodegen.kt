package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.util.isFalseConst
import org.jetbrains.kotlin.ir.util.isTrueConst

/**
 * Emits value-producing expressions into the method's [DotNetIlMethodContext]. Any construct
 * outside the supported subset aborts the enclosing method render with [DotNetIlUnsupportedException].
 */
internal class DotNetIlExpressionCodegen(
    private val methodContext: DotNetIlMethodContext,
    private val availableFunctions: Map<IrSimpleFunction, DotNetIlFunctionInfo>,
    private val intrinsicMethods: DotNetIlIntrinsicMethods,
) {
    fun emit(instruction: String, pops: Int = 0, pushes: Int = 0) {
        methodContext.emit(instruction, pops, pushes)
    }

    fun nextLabel(prefix: String): String = methodContext.nextLabel(prefix)

    fun emitBranch(instruction: String, targetLabel: String, pops: Int = 0) {
        methodContext.emitBranch(instruction, targetLabel, pops)
    }

    fun emitGoto(targetLabel: String) {
        methodContext.emitGoto(targetLabel)
    }

    fun emitLabel(label: String) {
        methodContext.emitLabel(label)
    }

    fun emitExpression(expression: IrExpression?, expectedType: DotNetIlValueType) {
        when (expression) {
            null -> dotNetUnsupported("missing ${expectedType.nameInSignature} expression value")
            is IrConst -> emitConstant(expression, expectedType)
            is IrGetValue -> emitGetValue(expression, expectedType)
            is IrWhen -> emitWhenExpression(expression, expectedType)
            is IrCall -> {
                val intrinsic = intrinsicMethods.getIntrinsic(expression.symbol)
                if (intrinsic == null || !intrinsic.tryEmitAsExpression(expression, this, expectedType)) {
                    emitCallExpression(expression, expectedType)
                }
            }
            else -> dotNetUnsupported("unsupported ${expectedType.nameInSignature} expression ${expression.javaClass.simpleName}")
        }
    }

    fun emitBranchIfFalse(condition: IrExpression, targetLabel: String) {
        emitExpression(condition, DotNetIlValueType.Boolean)
        methodContext.emitBranch("brfalse", targetLabel, pops = 1)
    }

    /**
     * Emits [expression] as a non-null string suitable for printing or concatenation: constants
     * are rendered through their string representation, nullable strings are coalesced to the
     * `"null"` literal, and non-string values are converted with Kotlin `toString` semantics
     * ([emitBooleanToString] keeps Kotlin's lowercase `"true"`/`"false"` rendering; `Int` values
     * are boxed and converted through `Object::ToString`, which matches `Int.toString()`).
     */
    fun emitStringValueExpression(expression: IrExpression?) {
        when {
            expression == null -> methodContext.emit("ldstr ${"null".toIlStringLiteral()}", pushes = 1)
            expression is IrConst -> methodContext.emit("ldstr ${expression.value.toString().toIlStringLiteral()}", pushes = 1)
            else -> when (expression.type.toDotNetIlValueType()) {
                DotNetIlValueType.Boolean -> {
                    emitExpression(expression, DotNetIlValueType.Boolean)
                    emitBooleanToString()
                }
                DotNetIlValueType.Int32 -> {
                    emitExpression(expression, DotNetIlValueType.Int32)
                    methodContext.emit("box [mscorlib]System.Int32", pops = 1, pushes = 1)
                    methodContext.emit("callvirt instance string [mscorlib]System.Object::ToString()", pops = 1, pushes = 1)
                }
                // A `null` mapping (unsupported type) also lands here so that emitExpression
                // reports the standard unsupported-construct diagnostic.
                DotNetIlValueType.String, null -> {
                    emitExpression(expression, DotNetIlValueType.String)
                    if (expression.type.isDotNetNullableStringType()) {
                        emitNullStringAsStringLiteral()
                    }
                }
            }
        }
    }

    /**
     * Converts the `bool` on top of the stack to Kotlin's string rendering. `Boolean.ToString()`
     * must NOT be used here: it yields `"True"`/`"False"` while Kotlin prints `"true"`/`"false"`.
     * Net stack effect: pop 1, push 1.
     */
    private fun emitBooleanToString() {
        val trueLabel = methodContext.nextLabel("boolStrTrue")
        val endLabel = methodContext.nextLabel("boolStrEnd")
        methodContext.emitBranch("brtrue", trueLabel, pops = 1)
        methodContext.emit("ldstr ${"false".toIlStringLiteral()}", pushes = 1)
        methodContext.emitGoto(endLabel)
        methodContext.emitLabel(trueLabel)
        methodContext.emit("ldstr ${"true".toIlStringLiteral()}", pushes = 1)
        methodContext.emitLabel(endLabel)
    }

    /**
     * Emits the arguments and the `call` instruction for a call to a top-level Kotlin function.
     * Throws [DotNetIlUnsupportedException] when the callee is not available (not compilable,
     * already skipped, or not a top-level function of this module).
     */
    fun emitCall(call: IrCall): DotNetIlReturnType {
        val callee = call.symbol.owner
        val calleeName = callee.name.asString()
        val info = availableFunctions[callee]
            ?: dotNetUnsupported("call to unsupported function '$calleeName'")
        if (call.arguments.size != info.signature.parameterTypes.size) {
            dotNetUnsupported("call to '$calleeName' has an unsupported argument shape")
        }
        for ((argument, parameterType) in call.arguments.zip(info.signature.parameterTypes)) {
            if (argument == null) {
                dotNetUnsupported("call to '$calleeName' relies on default argument values")
            }
            emitExpression(argument, parameterType)
        }
        methodContext.emit(
            info.renderCallInstruction(calleeName),
            pops = info.signature.parameterTypes.size,
            pushes = if (info.signature.returnType is DotNetIlReturnType.Value) 1 else 0,
        )
        return info.signature.returnType
    }

    private fun emitCallExpression(call: IrCall, expectedType: DotNetIlValueType) {
        val returnType = emitCall(call)
        if ((returnType as? DotNetIlReturnType.Value)?.type != expectedType) {
            dotNetUnsupported(
                "call to '${call.symbol.owner.name.asString()}' produces ${returnType.nameInSignature} " +
                        "where ${expectedType.nameInSignature} is expected"
            )
        }
    }

    private fun emitConstant(expression: IrConst, expectedType: DotNetIlValueType) {
        when (expectedType) {
            DotNetIlValueType.Boolean -> {
                val value = expression.value as? Boolean
                    ?: dotNetUnsupported("unsupported bool constant: ${expression.value}")
                methodContext.emit("ldc.i4.${if (value) "1" else "0"}", pushes = 1)
            }
            DotNetIlValueType.Int32 -> {
                val value = expression.value as? Int
                    ?: dotNetUnsupported("unsupported int32 constant: ${expression.value}")
                methodContext.emit("ldc.i4 $value", pushes = 1)
            }
            DotNetIlValueType.String -> when (val value = expression.value) {
                null -> methodContext.emit("ldnull", pushes = 1)
                is String -> methodContext.emit("ldstr ${value.toIlStringLiteral()}", pushes = 1)
                else -> dotNetUnsupported("unsupported string constant: $value")
            }
        }
    }

    private fun emitGetValue(expression: IrGetValue, expectedType: DotNetIlValueType) {
        val slot = methodContext.reference(expression.symbol)
        if (slot.type != expectedType) {
            dotNetUnsupported(
                "value '${expression.symbol.owner.name.asString()}' has type ${slot.type.nameInSignature} " +
                        "where ${expectedType.nameInSignature} is expected"
            )
        }
        when (slot) {
            is DotNetIlSlot.Parameter -> methodContext.emit(loadArgumentInstruction(slot.index), pushes = 1)
            is DotNetIlSlot.Local -> methodContext.emit(loadLocalInstruction(slot.index), pushes = 1)
        }
    }

    private fun emitWhenExpression(expression: IrWhen, expectedType: DotNetIlValueType) {
        val endLabel = methodContext.nextLabel("whenEnd")
        var hasElse = false

        for (branch in expression.branches) {
            if (branch.condition.isFalseConst()) continue

            if (branch.condition.isTrueConst()) {
                emitExpression(branch.result, expectedType)
                hasElse = true
                break
            }

            val nextBranchLabel = methodContext.nextLabel("whenNext")
            emitBranchIfFalse(branch.condition, nextBranchLabel)
            emitExpression(branch.result, expectedType)
            methodContext.emitBranch("br", endLabel)
            methodContext.emitLabel(nextBranchLabel)
        }

        if (!hasElse) {
            dotNetUnsupported("when expression without an else branch")
        }

        methodContext.emitLabel(endLabel)
    }

    private fun emitNullStringAsStringLiteral() {
        val notNullLabel = methodContext.nextLabel("stringValueNotNull")
        methodContext.emit("dup", pops = 1, pushes = 2)
        methodContext.emitBranch("brtrue", notNullLabel, pops = 1)
        methodContext.emit("pop", pops = 1)
        methodContext.emit("ldstr ${"null".toIlStringLiteral()}", pushes = 1)
        methodContext.emitLabel(notNullLabel)
    }
}

internal fun loadArgumentInstruction(index: Int): String =
    if (index in 0..3) "ldarg.$index" else "ldarg $index"

internal fun loadLocalInstruction(index: Int): String =
    if (index in 0..3) "ldloc.$index" else "ldloc $index"

internal fun storeLocalInstruction(index: Int): String =
    if (index in 0..3) "stloc.$index" else "stloc $index"
