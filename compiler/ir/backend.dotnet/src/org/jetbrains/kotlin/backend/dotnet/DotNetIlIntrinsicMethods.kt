package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFileClass
import org.jetbrains.kotlin.ir.util.isNullConst
import org.jetbrains.kotlin.name.FqName

internal class DotNetIlIntrinsicMethods(
    irBuiltIns: IrBuiltIns,
) {
    private val kotlinFqn = StandardNames.BUILT_INS_PACKAGE_FQ_NAME
    private val kotlinIoFqn = FqName("kotlin.io")

    private val anyFqn = StandardNames.FqNames.any.toSafe()
    private val stringFqn = StandardNames.FqNames.string.toSafe()
    private val intFqn = StandardNames.FqNames._int.toSafe()
    private val booleanFqn = StandardNames.FqNames._boolean.toSafe()

    private val intrinsics = listOf(
        irBuiltIns.eqeqSymbol.toKey()!! to DotNetIlEqualityIntrinsic(referenceEquality = false),
        irBuiltIns.eqeqeqSymbol.toKey()!! to DotNetIlEqualityIntrinsic(referenceEquality = true),
        irBuiltIns.booleanNotSymbol.toKey()!! to DotNetIlBooleanNotIntrinsic,
        // fir2ir converts `a < b` and friends over Ints to calls of these IrBuiltIns comparison
        // functions (`kotlin.internal.ir.less` etc.), not to `Int.compareTo`.
        irBuiltIns.lessFunByOperandType.getValue(irBuiltIns.intClass).toKey()!!
                to DotNetIlIntComparisonIntrinsic("clt", negated = false),
        irBuiltIns.lessOrEqualFunByOperandType.getValue(irBuiltIns.intClass).toKey()!!
                to DotNetIlIntComparisonIntrinsic("cgt", negated = true),
        irBuiltIns.greaterFunByOperandType.getValue(irBuiltIns.intClass).toKey()!!
                to DotNetIlIntComparisonIntrinsic("cgt", negated = false),
        irBuiltIns.greaterOrEqualFunByOperandType.getValue(irBuiltIns.intClass).toKey()!!
                to DotNetIlIntComparisonIntrinsic("clt", negated = true),
        Key(intFqn, null, "plus", listOf(intFqn)) to DotNetIlIntBinaryOperatorIntrinsic("add"),
        Key(intFqn, null, "minus", listOf(intFqn)) to DotNetIlIntBinaryOperatorIntrinsic("sub"),
        Key(intFqn, null, "times", listOf(intFqn)) to DotNetIlIntBinaryOperatorIntrinsic("mul"),
        // IL `div`/`rem` truncate toward zero like Kotlin's `Int.div`/`Int.rem`, but throw
        // System.OverflowException for Int.MIN_VALUE / -1 where Kotlin defines the result, so the
        // divisor is guarded against -1 (see DotNetIlIntDivRemIntrinsic).
        Key(intFqn, null, "div", listOf(intFqn)) to DotNetIlIntDivRemIntrinsic(isDivision = true),
        Key(intFqn, null, "rem", listOf(intFqn)) to DotNetIlIntDivRemIntrinsic(isDivision = false),
        Key(intFqn, null, "unaryMinus", emptyList()) to DotNetIlIntUnaryOperatorIntrinsic("neg"),
        Key(intFqn, null, "unaryPlus", emptyList()) to DotNetIlIntUnaryOperatorIntrinsic(instruction = null),
        Key(intFqn, null, "inc", emptyList()) to DotNetIlIntIncrementIntrinsic("add"),
        Key(intFqn, null, "dec", emptyList()) to DotNetIlIntIncrementIntrinsic("sub"),
        Key(kotlinIoFqn, null, "println", emptyList()) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(stringFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(intFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(booleanFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(anyFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinFqn, stringFqn, "plus", listOf(anyFqn)) to DotNetIlStringPlusIntrinsic,
        Key(stringFqn, null, "plus", listOf(anyFqn)) to DotNetIlStringPlusIntrinsic,
        Key(kotlinFqn, anyFqn, "toString", emptyList()) to DotNetIlToStringIntrinsic,
        Key(anyFqn, null, "toString", emptyList()) to DotNetIlToStringIntrinsic,
        Key(intFqn, null, "toString", emptyList()) to DotNetIlToStringIntrinsic,
        Key(booleanFqn, null, "toString", emptyList()) to DotNetIlToStringIntrinsic,
    )

    private val intrinsicsMap = hashMapOf<String, MutableMap<FqName?, MutableMap<Key, DotNetIlIntrinsicMethod>>>()

    init {
        @Suppress("ReplacePutWithAssignment")
        for ((key, intrinsic) in intrinsics) {
            intrinsicsMap.getOrPut(key.name) { hashMapOf() }
                .getOrPut(key.receiverParameterTypeName) { hashMapOf() }
                .put(key, intrinsic)
        }
    }

    fun getIntrinsic(symbol: IrFunctionSymbol): DotNetIlIntrinsicMethod? {
        val function = symbol.owner
        val name = function.name.asString()
        val byName = intrinsicsMap[name] ?: return null
        val receiverFqName = function.computeExtensionReceiverFqName()
        val byReceiver = byName[receiverFqName] ?: return null
        val ownerFqName = function.computeOwnerFqName() ?: return null
        return byReceiver[Key(ownerFqName, receiverFqName, name, function.computeValueParameterFqNames())]
    }

    data class Key(
        val owner: FqName,
        val receiverParameterTypeName: FqName?,
        val name: String,
        val valueParameterTypeNames: List<FqName?>,
    )
}

/**
 * A function call the backend compiles directly to IL instead of a regular `call` to a
 * Kotlin-declared method.
 *
 * The `tryEmit*` methods return `false` when the call shape does not match the intrinsic at all,
 * in which case the caller falls through to regular call handling. When the shape matches but an
 * argument cannot be compiled, they throw [DotNetIlUnsupportedException].
 */
internal abstract class DotNetIlIntrinsicMethod {
    open val excludesDeclarationFromCodegen: Boolean = false

    open fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean = false

    open fun tryEmitAsStatement(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
    ): Boolean = false
}

private object DotNetIlBooleanNotIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Boolean || call.arguments.size != 1) return false
        val argument = call.arguments.single()
            ?: dotNetUnsupported("missing argument of the '!' operator")
        codegen.emitExpression(argument, DotNetIlValueType.Boolean)
        codegen.emit("ldc.i4.0", pushes = 1)
        codegen.emit("ceq", pops = 2, pushes = 1)
        return true
    }
}

private class DotNetIlEqualityIntrinsic(
    private val referenceEquality: Boolean,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Boolean || call.arguments.size != 2) return false
        val operandType = call.dotNetEqualityOperandType()
            ?: dotNetUnsupported("equality comparison of unsupported operand types")
        val left = call.arguments[0]
            ?: dotNetUnsupported("missing left operand of an equality comparison")
        val right = call.arguments[1]
            ?: dotNetUnsupported("missing right operand of an equality comparison")

        codegen.emitExpression(left, operandType)
        codegen.emitExpression(right, operandType)
        when (operandType) {
            DotNetIlValueType.Boolean,
            DotNetIlValueType.Int32 -> codegen.emit("ceq", pops = 2, pushes = 1)
            DotNetIlValueType.String -> {
                if (referenceEquality) {
                    codegen.emit("ceq", pops = 2, pushes = 1)
                } else {
                    codegen.emit("call bool [mscorlib]System.String::op_Equality(string, string)", pops = 2, pushes = 1)
                }
            }
        }
        return true
    }
}

/** A binary `kotlin.Int` member operator (`plus`, `minus`, ...) mapped to a single IL instruction. */
private class DotNetIlIntBinaryOperatorIntrinsic(
    private val instruction: String,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Int32 || call.arguments.size != 2) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing receiver of an Int '$instruction' operator")
        val argument = call.arguments[1]
            ?: dotNetUnsupported("missing argument of an Int '$instruction' operator")
        codegen.emitExpression(receiver, DotNetIlValueType.Int32)
        codegen.emitExpression(argument, DotNetIlValueType.Int32)
        codegen.emit(instruction, pops = 2, pushes = 1)
        return true
    }
}

/**
 * `Int.div`/`Int.rem`. CIL `div`/`rem` truncate toward zero like Kotlin, but they throw
 * `System.OverflowException` for `Int.MIN_VALUE / -1` and `Int.MIN_VALUE % -1`, where Kotlin
 * (matching the JVM) defines the results as `Int.MIN_VALUE` and `0`. A `-1` divisor therefore
 * bypasses `div`/`rem`: `a / -1` is `neg a` (IL `neg` is plain two's-complement negation and
 * does not overflow-check) and `a % -1` is `0`. The guard is emitted at runtime unless the
 * divisor is a constant that decides it statically.
 */
private class DotNetIlIntDivRemIntrinsic(
    private val isDivision: Boolean,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        val operatorName = if (isDivision) "div" else "rem"
        if (expectedType != DotNetIlValueType.Int32 || call.arguments.size != 2) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing receiver of an Int '$operatorName' operator")
        val argument = call.arguments[1]
            ?: dotNetUnsupported("missing argument of an Int '$operatorName' operator")

        codegen.emitExpression(receiver, DotNetIlValueType.Int32)
        val constantDivisor = (argument as? IrConst)?.value as? Int
        when (constantDivisor) {
            -1 -> if (isDivision) {
                codegen.emit("neg", pops = 1, pushes = 1)
            } else {
                codegen.emit("pop", pops = 1)
                codegen.emit("ldc.i4.0", pushes = 1)
            }
            null -> {
                codegen.emitExpression(argument, DotNetIlValueType.Int32)
                val normalLabel = codegen.nextLabel("${operatorName}Normal")
                val endLabel = codegen.nextLabel("${operatorName}End")
                codegen.emit("dup", pops = 1, pushes = 2)
                codegen.emit("ldc.i4.m1", pushes = 1)
                codegen.emitBranch("bne.un", normalLabel, pops = 2)
                if (isDivision) {
                    codegen.emit("pop", pops = 1)
                    codegen.emit("neg", pops = 1, pushes = 1)
                } else {
                    codegen.emit("pop", pops = 1)
                    codegen.emit("pop", pops = 1)
                    codegen.emit("ldc.i4.0", pushes = 1)
                }
                codegen.emitGoto(endLabel)
                codegen.emitLabel(normalLabel)
                codegen.emit(operatorName, pops = 2, pushes = 1)
                codegen.emitLabel(endLabel)
            }
            else -> {
                codegen.emitExpression(argument, DotNetIlValueType.Int32)
                codegen.emit(operatorName, pops = 2, pushes = 1)
            }
        }
        return true
    }
}

/** A unary `kotlin.Int` member operator: `neg` for `unaryMinus`, no instruction for `unaryPlus`. */
private class DotNetIlIntUnaryOperatorIntrinsic(
    private val instruction: String?,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Int32 || call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing receiver of a unary Int operator")
        codegen.emitExpression(receiver, DotNetIlValueType.Int32)
        if (instruction != null) {
            codegen.emit(instruction, pops = 1, pushes = 1)
        }
        return true
    }
}

/** `Int.inc`/`Int.dec`: the receiver plus/minus a constant `1`. */
private class DotNetIlIntIncrementIntrinsic(
    private val instruction: String,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Int32 || call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing receiver of an Int increment operator")
        codegen.emitExpression(receiver, DotNetIlValueType.Int32)
        codegen.emit("ldc.i4.1", pushes = 1)
        codegen.emit(instruction, pops = 2, pushes = 1)
        return true
    }
}

/**
 * An `Int` comparison (`kotlin.internal.ir.less` and friends). IL only has `clt`/`cgt`, so
 * `<=` and `>=` are emitted as the [negated] opposite comparison (`cgt`/`clt`) compared to `0`.
 */
private class DotNetIlIntComparisonIntrinsic(
    private val instruction: String,
    private val negated: Boolean,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Boolean || call.arguments.size != 2) return false
        val left = call.arguments[0]
            ?: dotNetUnsupported("missing left operand of an Int comparison")
        val right = call.arguments[1]
            ?: dotNetUnsupported("missing right operand of an Int comparison")
        codegen.emitExpression(left, DotNetIlValueType.Int32)
        codegen.emitExpression(right, DotNetIlValueType.Int32)
        codegen.emit(instruction, pops = 2, pushes = 1)
        if (negated) {
            codegen.emit("ldc.i4.0", pushes = 1)
            codegen.emit("ceq", pops = 2, pushes = 1)
        }
        return true
    }
}

private object DotNetIlPrintlnIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsStatement(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
    ): Boolean {
        return when (call.arguments.size) {
            0 -> {
                codegen.emit("call void [mscorlib]System.Console::WriteLine()")
                true
            }
            1 -> {
                val argument = call.arguments.single()
                    ?: dotNetUnsupported("missing argument in a call to 'println'")
                val parameterType = call.symbol.owner.parameters.singleOrNull()?.type?.toDotNetIlValueType()
                if (parameterType == DotNetIlValueType.Int32) {
                    // Console.WriteLine(int32) renders identically to Kotlin's Int.toString().
                    codegen.emitExpression(argument, DotNetIlValueType.Int32)
                    codegen.emit("call void [mscorlib]System.Console::WriteLine(int32)", pops = 1)
                } else {
                    // String, Boolean and Any? arguments funnel through the Kotlin string
                    // rendering of the value. In particular Console.WriteLine(bool) must NOT be
                    // used: it prints "True"/"False" while Kotlin prints "true"/"false".
                    codegen.emitStringValueExpression(argument)
                    codegen.emit("call void [mscorlib]System.Console::WriteLine(string)", pops = 1)
                }
                true
            }
            else -> false
        }
    }
}

private object DotNetIlStringPlusIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.String || call.arguments.size != 2) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing receiver of 'String.plus'")
        val argument = call.arguments[1]
            ?: dotNetUnsupported("missing argument of 'String.plus'")

        codegen.emitStringValueExpression(receiver)
        codegen.emitStringValueExpression(argument)
        codegen.emit("call string [mscorlib]System.String::Concat(string, string)", pops = 2, pushes = 1)
        return true
    }
}

private object DotNetIlToStringIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.String || call.arguments.size != 1) return false
        codegen.emitStringValueExpression(call.arguments.single())
        return true
    }
}

private fun IrCall.dotNetEqualityOperandType(): DotNetIlValueType? {
    val left = arguments.getOrNull(0) ?: return null
    val right = arguments.getOrNull(1) ?: return null
    val leftType = left.type.toDotNetIlValueType()
    val rightType = right.type.toDotNetIlValueType()
    return when {
        leftType != null && leftType == rightType -> leftType
        left.isNullConst() && rightType == DotNetIlValueType.String -> DotNetIlValueType.String
        right.isNullConst() && leftType == DotNetIlValueType.String -> DotNetIlValueType.String
        else -> null
    }
}

private fun IrFunctionSymbol.toKey(): DotNetIlIntrinsicMethods.Key? =
    owner.toKey()

private fun IrFunction.toKey(): DotNetIlIntrinsicMethods.Key? {
    return DotNetIlIntrinsicMethods.Key(
        computeOwnerFqName() ?: return null,
        computeExtensionReceiverFqName(),
        name.asString(),
        computeValueParameterFqNames(),
    )
}

private fun IrFunction.computeOwnerFqName(): FqName? {
    return when (val parent = parent) {
        is IrClass -> {
            if (parent.isFileClass) (parent.parent as IrPackageFragment).packageFqName
            else parent.fqNameWhenAvailable
        }
        is IrPackageFragment -> parent.packageFqName
        else -> null
    }
}

private fun IrFunction.computeExtensionReceiverFqName(): FqName? =
    computeParameterFqName(parameters.singleOrNull { it.kind == IrParameterKind.ExtensionReceiver })

private fun computeParameterFqName(parameter: IrValueParameter?): FqName? =
    computeParameterFqName(parameter?.type?.classifierOrNull)

private fun computeParameterFqName(parameter: IrClassifierSymbol?): FqName? =
    parameter?.owner?.let {
        when (it) {
            is IrClass -> it.fqNameWhenAvailable
            is IrTypeParameter -> FqName(it.name.asString())
            else -> null
        }
    }

private fun IrFunction.computeValueParameterFqNames(): List<FqName?> =
    parameters.filter { it.kind == IrParameterKind.Regular }.map { computeParameterFqName(it) }
