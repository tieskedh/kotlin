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
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFileClass
import org.jetbrains.kotlin.ir.util.isNullConst
import org.jetbrains.kotlin.name.FqName

/**
 * Registry of function calls compiled directly to IL, keyed on owner/receiver/name/parameter
 * FqNames. Mirrors the JVM backend's `IrIntrinsicMethods`: arithmetic, comparisons and number
 * conversions are registered programmatically by looping over the supported primitive types
 * (the JVM loops over all of `PrimitiveType.entries`; here the loop is restricted to
 * {Int, Long, Double} plus Char special cases while Byte/Short/Float support is deferred).
 */
internal class DotNetIlIntrinsicMethods(
    irBuiltIns: IrBuiltIns,
) {
    private val kotlinFqn = StandardNames.BUILT_INS_PACKAGE_FQ_NAME
    private val kotlinIoFqn = FqName("kotlin.io")

    private val anyFqn = StandardNames.FqNames.any.toSafe()
    private val stringFqn = StandardNames.FqNames.string.toSafe()
    private val intFqn = StandardNames.FqNames._int.toSafe()
    private val longFqn = StandardNames.FqNames._long.toSafe()
    private val doubleFqn = StandardNames.FqNames._double.toSafe()
    private val charFqn = StandardNames.FqNames._char.toSafe()
    private val booleanFqn = StandardNames.FqNames._boolean.toSafe()

    /**
     * The numeric types binary operators and conversions are generated over, keyed by builtin
     * FqName. Promotion order is Int32 < Int64 < Float64 (see [promoteNumeric]), matching the
     * Kotlin stdlib operator signatures (`Int.plus(Long): Long`, `Long.plus(Double): Double`, ...).
     */
    private val numericTypes: Map<FqName, DotNetIlValueType> = mapOf(
        intFqn to DotNetIlValueType.Int32,
        longFqn to DotNetIlValueType.Int64,
        doubleFqn to DotNetIlValueType.Float64,
    )

    private val intrinsics = listOf(
        irBuiltIns.eqeqSymbol.toKey()!! to DotNetIlEqualityIntrinsic(referenceEquality = false),
        irBuiltIns.eqeqeqSymbol.toKey()!! to DotNetIlEqualityIntrinsic(referenceEquality = true),
        // fir2ir routes `==` over operands statically known to be Double/Float through
        // `irBuiltIns.ieee754equalsFunByOperandType` (see OperatorExpressionGenerator), NOT
        // through `eqeqSymbol`; the JVM backend registers these symbols separately to its
        // Ieee754Equals intrinsic. CIL `ceq` on float64 *is* IEEE 754 equality (NaN != NaN,
        // -0.0 == 0.0), which is exactly the required semantics. The Float entry is registered
        // only so that Float equality fails explicitly inside the intrinsic (Float is deferred)
        // instead of falling through to generic call handling.
        irBuiltIns.ieee754equalsFunByOperandType.getValue(irBuiltIns.doubleClass).toKey()!!
                to DotNetIlEqualityIntrinsic(referenceEquality = false),
        irBuiltIns.ieee754equalsFunByOperandType.getValue(irBuiltIns.floatClass).toKey()!!
                to DotNetIlEqualityIntrinsic(referenceEquality = false),
        irBuiltIns.booleanNotSymbol.toKey()!! to DotNetIlBooleanNotIntrinsic,
        Key(kotlinIoFqn, null, "println", emptyList()) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(stringFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(intFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(longFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(doubleFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(charFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(booleanFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(anyFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinFqn, stringFqn, "plus", listOf(anyFqn)) to DotNetIlStringPlusIntrinsic,
        Key(stringFqn, null, "plus", listOf(anyFqn)) to DotNetIlStringPlusIntrinsic,
        Key(kotlinFqn, anyFqn, "toString", emptyList()) to DotNetIlToStringIntrinsic,
        Key(anyFqn, null, "toString", emptyList()) to DotNetIlToStringIntrinsic,
        Key(intFqn, null, "toString", emptyList()) to DotNetIlToStringIntrinsic,
        Key(longFqn, null, "toString", emptyList()) to DotNetIlToStringIntrinsic,
        Key(doubleFqn, null, "toString", emptyList()) to DotNetIlToStringIntrinsic,
        Key(charFqn, null, "toString", emptyList()) to DotNetIlToStringIntrinsic,
        Key(booleanFqn, null, "toString", emptyList()) to DotNetIlToStringIntrinsic,
    ) + comparisonIntrinsics(irBuiltIns) + numericOperatorIntrinsics() + charOperatorIntrinsics() +
            conversionIntrinsics() + exceptionMemberIntrinsics()

    /**
     * `Throwable.message`/`Throwable.cause` on every [mapped exception type][DotNetMappedExceptions],
     * compiled to `System.Exception::get_Message()`/`get_InnerException()` (both callvirt
     * signatures ilasm-probe-verified). A key is registered per mapped FqName because the
     * accessor call site's owner is the static receiver class: on a subtype receiver
     * (`e: IllegalStateException`) the getter arrives as a fake override owned by the subclass,
     * not by `kotlin.Throwable` (the registration-per-FqName option of the JVM's
     * resolve-fake-overrides-then-look-up precedent, chosen to leave [getIntrinsic] dispatch
     * untouched). Rejected exception types need no keys: any receiver of such a type already
     * fails signature mapping with the registry's per-type reason.
     */
    private fun exceptionMemberIntrinsics(): List<Pair<Key, DotNetIlIntrinsicMethod>> = buildList {
        for ((fqName, entry) in DotNetMappedExceptions.entries) {
            if (entry !is DotNetMappedExceptions.Entry.Mapped) continue
            add(Key(fqName, null, "<get-message>", emptyList()) to DotNetIlExceptionMessageIntrinsic)
            add(Key(fqName, null, "<get-cause>", emptyList()) to DotNetIlExceptionCauseIntrinsic)
        }
    }

    /**
     * `<`, `<=`, `>`, `>=` over {Int, Long, Double, Char}. fir2ir converts `a < b` and friends
     * over these types to calls of the IrBuiltIns comparison functions
     * (`kotlin.internal.ir.less` etc.) keyed by operand classifier, not to `compareTo`; the JVM
     * backend registers the same symbols in `primitiveComparisonIntrinsics`.
     *
     * IL only has `clt`/`cgt` (plus their `.un` forms), so `<=`/`>=` are the [negated][DotNetIlComparisonIntrinsic]
     * opposite comparison compared to `0`. Int/Long/Char use the signed `clt`/`cgt` (chars are
     * non-negative int32 values, so signed compare is correct). Double follows Roslyn's
     * NaN-correct scheme: `<=` negates `cgt.un` and `>=` negates `clt.un` — the `.un` forms are
     * true for unordered operands, so after negation any comparison with NaN stays false.
     * Negating the plain `cgt` instead would make `NaN <= x` evaluate to true.
     *
     * The `Char` entries are defensive registration only: fir2ir currently routes `Char`
     * comparisons through `Char.compareTo(Char)` + the Int32 builtin (see
     * [charOperatorIntrinsics]), not through the `Char`-keyed builtins. The emission would be
     * correct if that routing ever changed, so the entries stay (registry-shape design rule)
     * even though no golden exercises them.
     */
    private fun comparisonIntrinsics(irBuiltIns: IrBuiltIns): List<Pair<Key, DotNetIlIntrinsicMethod>> = buildList {
        val comparableTypes = listOf(
            irBuiltIns.intClass to DotNetIlValueType.Int32,
            irBuiltIns.longClass to DotNetIlValueType.Int64,
            irBuiltIns.doubleClass to DotNetIlValueType.Float64,
            irBuiltIns.charClass to DotNetIlValueType.Char,
        )
        for ((classSymbol, operandType) in comparableTypes) {
            val isFloatingPoint = operandType == DotNetIlValueType.Float64
            add(
                irBuiltIns.lessFunByOperandType.getValue(classSymbol).toKey()!!
                        to DotNetIlComparisonIntrinsic("clt", negated = false, operandType)
            )
            add(
                irBuiltIns.greaterFunByOperandType.getValue(classSymbol).toKey()!!
                        to DotNetIlComparisonIntrinsic("cgt", negated = false, operandType)
            )
            add(
                irBuiltIns.lessOrEqualFunByOperandType.getValue(classSymbol).toKey()!!
                        to DotNetIlComparisonIntrinsic(if (isFloatingPoint) "cgt.un" else "cgt", negated = true, operandType)
            )
            add(
                irBuiltIns.greaterOrEqualFunByOperandType.getValue(classSymbol).toKey()!!
                        to DotNetIlComparisonIntrinsic(if (isFloatingPoint) "clt.un" else "clt", negated = true, operandType)
            )
        }
    }

    /**
     * Member operators of {Int, Long, Double} including all mixed-type overloads
     * (`Int.plus(Long): Long` etc.), following the JVM backend's
     * `binaryFunForPrimitivesAcrossPrimitives` loop. Operands are widened to the promoted
     * computation type by the emitting intrinsic (see [emitWidenedOperand]).
     */
    private fun numericOperatorIntrinsics(): List<Pair<Key, DotNetIlIntrinsicMethod>> = buildList {
        for ((receiverFqn, receiverType) in numericTypes) {
            for ((argumentFqn, argumentType) in numericTypes) {
                val resultType = promoteNumeric(receiverType, argumentType)
                for ((name, instruction) in listOf("plus" to "add", "minus" to "sub", "times" to "mul")) {
                    add(
                        Key(receiverFqn, null, name, listOf(argumentFqn))
                                to DotNetIlNumericBinaryOperatorIntrinsic(instruction, receiverType, argumentType, resultType)
                    )
                }
                add(
                    Key(receiverFqn, null, "div", listOf(argumentFqn))
                            to DotNetIlNumericDivRemIntrinsic(isDivision = true, receiverType, argumentType, resultType)
                )
                add(
                    Key(receiverFqn, null, "rem", listOf(argumentFqn))
                            to DotNetIlNumericDivRemIntrinsic(isDivision = false, receiverType, argumentType, resultType)
                )
            }
            add(Key(receiverFqn, null, "unaryMinus", emptyList()) to DotNetIlNumericUnaryOperatorIntrinsic("neg", receiverType))
            add(Key(receiverFqn, null, "unaryPlus", emptyList()) to DotNetIlNumericUnaryOperatorIntrinsic(null, receiverType))
            add(Key(receiverFqn, null, "inc", emptyList()) to DotNetIlNumericIncrementIntrinsic("add", receiverType))
            add(Key(receiverFqn, null, "dec", emptyList()) to DotNetIlNumericIncrementIntrinsic("sub", receiverType))
        }
    }

    /**
     * `Char` arithmetic (the stdlib only declares these shapes): `Char.plus(Int): Char`,
     * `Char.minus(Int): Char`, `Char.minus(Char): Int`, `Char.inc`/`Char.dec`.
     *
     * `Char.compareTo(Char)` must be intrinsified too: fir2ir routes `a < b` through the
     * `lessFunByOperandType` builtins only for *numeric* operand types, so a Char comparison
     * arrives as `less(a.compareTo(b), 0)` — the outer `less` is the registered Int32 comparison,
     * and the inner `compareTo` call would otherwise be an unsupported callee. The JVM backend
     * registers primitive `compareTo` in its intrinsic registry the same way; like there, the
     * Char implementation is a plain `sub` of the code units (16-bit values cannot overflow the
     * 32-bit subtraction, and `compareTo` only promises the sign, which the difference provides).
     */
    private fun charOperatorIntrinsics(): List<Pair<Key, DotNetIlIntrinsicMethod>> = listOf(
        Key(charFqn, null, "plus", listOf(intFqn)) to DotNetIlCharPlusMinusIntIntrinsic("add"),
        Key(charFqn, null, "minus", listOf(intFqn)) to DotNetIlCharPlusMinusIntIntrinsic("sub"),
        Key(charFqn, null, "minus", listOf(charFqn)) to DotNetIlCharMinusCharIntrinsic,
        Key(charFqn, null, "compareTo", listOf(charFqn)) to DotNetIlCharMinusCharIntrinsic,
        Key(charFqn, null, "inc", emptyList()) to DotNetIlCharIncrementIntrinsic("add"),
        Key(charFqn, null, "dec", emptyList()) to DotNetIlCharIncrementIntrinsic("sub"),
    )

    /**
     * `to<Type>()` conversions between the supported primitives, following the JVM backend's
     * `numberConversionMethods`/`NumberCast` (JVM registers every `NUMBER_CONVERSIONS` name on
     * every number type; here only conversions between supported types are registered, so
     * `toByte`/`toShort`/`toFloat` fall through to regular call handling and fail as
     * unsupported callees).
     *
     * The deprecated `Long.toChar()`/`Double.toChar()` are registered as explicitly unsupported
     * (registry entry now, explicit failure) rather than silently compiled: Kotlin deprecated
     * them precisely because their two-step truncation semantics surprise users, and this
     * backend has no legacy code to stay compatible with.
     *
     * `Char.code` is `Char.toInt()` under an extension-property hat (`@InlineOnly` in the real
     * stdlib, a plain property in the fake .NET stdlib because this backend does not run an IR
     * inliner); its getter call is intercepted here so no property access is ever emitted.
     */
    private fun conversionIntrinsics(): List<Pair<Key, DotNetIlIntrinsicMethod>> = buildList {
        val conversionNamesToTargets = listOf(
            "toInt" to DotNetIlValueType.Int32,
            "toLong" to DotNetIlValueType.Int64,
            "toDouble" to DotNetIlValueType.Float64,
        )
        val sourceTypes = numericTypes + (charFqn to DotNetIlValueType.Char)
        for ((fromFqn, fromType) in sourceTypes) {
            for ((name, toType) in conversionNamesToTargets) {
                add(Key(fromFqn, null, name, emptyList()) to conversionIntrinsicFor(fromType, toType))
            }
        }
        add(
            Key(intFqn, null, "toChar", emptyList())
                    to DotNetIlNumberConversionIntrinsic(DotNetIlValueType.Int32, DotNetIlValueType.Char, listOf("conv.u2"))
        )
        add(
            Key(charFqn, null, "toChar", emptyList())
                    to DotNetIlNumberConversionIntrinsic(DotNetIlValueType.Char, DotNetIlValueType.Char, emptyList())
        )
        add(
            Key(longFqn, null, "toChar", emptyList())
                    to DotNetIlUnsupportedIntrinsic("'Long.toChar()' is deprecated in Kotlin; use 'toInt().toChar()'")
        )
        add(
            Key(doubleFqn, null, "toChar", emptyList())
                    to DotNetIlUnsupportedIntrinsic("'Double.toChar()' is deprecated in Kotlin; use 'toInt().toChar()'")
        )
        add(
            Key(kotlinFqn, charFqn, "<get-code>", emptyList())
                    to DotNetIlNumberConversionIntrinsic(DotNetIlValueType.Char, DotNetIlValueType.Int32, emptyList())
        )
    }

    private fun conversionIntrinsicFor(fromType: DotNetIlValueType, toType: DotNetIlValueType): DotNetIlIntrinsicMethod {
        val instructions = when {
            // Identity conversions (`Int.toInt()` etc.) and Char -> Int (the char is already a
            // zero-extended int32 on the evaluation stack, like on the JVM).
            fromType == toType || (fromType == DotNetIlValueType.Char && toType == DotNetIlValueType.Int32) -> emptyList()
            toType == DotNetIlValueType.Int64 && fromType != DotNetIlValueType.Float64 -> listOf("conv.i8")
            toType == DotNetIlValueType.Float64 -> listOf("conv.r8")
            toType == DotNetIlValueType.Int32 && fromType == DotNetIlValueType.Int64 -> listOf("conv.i4")
            fromType == DotNetIlValueType.Float64 -> return DotNetIlDoubleToIntegralIntrinsic(toType)
            else -> error("Internal .NET backend error: no conversion from $fromType to $toType")
        }
        return DotNetIlNumberConversionIntrinsic(fromType, toType, instructions)
    }

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

/** Numeric promotion for mixed-type operators: Int32 < Int64 < Float64, like Kotlin/JVM. */
private fun promoteNumeric(left: DotNetIlValueType, right: DotNetIlValueType): DotNetIlValueType = when {
    left == DotNetIlValueType.Float64 || right == DotNetIlValueType.Float64 -> DotNetIlValueType.Float64
    left == DotNetIlValueType.Int64 || right == DotNetIlValueType.Int64 -> DotNetIlValueType.Int64
    else -> DotNetIlValueType.Int32
}

/**
 * Emits [operand] as [operandType], then widens the stack value to [computationType] when the
 * two differ. This is how mixed-type stdlib operators (`Int.plus(Long): Long`) are compiled;
 * the JVM backend does the same through StackValue coercion (`i2l`/`i2d`/`l2d`), whose CLR
 * equivalents are `conv.i8` (sign-extend) and `conv.r8` (exact for every int32; for int64 the
 * usual IEEE round-to-nearest, same as `l2d`). Only widening conversions are legal here.
 */
private fun DotNetIlExpressionCodegen.emitWidenedOperand(
    operand: IrExpression,
    operandType: DotNetIlValueType,
    computationType: DotNetIlValueType,
) {
    emitExpression(operand, operandType)
    if (operandType == computationType) return
    when {
        computationType == DotNetIlValueType.Int64 && operandType == DotNetIlValueType.Int32 ->
            emit("conv.i8", pops = 1, pushes = 1)
        computationType == DotNetIlValueType.Float64 &&
                (operandType == DotNetIlValueType.Int32 || operandType == DotNetIlValueType.Int64) ->
            emit("conv.r8", pops = 1, pushes = 1)
        else -> error(
            "Internal .NET backend error: no widening from ${operandType.nameInSignature} to ${computationType.nameInSignature}"
        )
    }
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

/**
 * `==`/`===`/`ieee754equals`. All value primitives use `ceq`: for `int32`-backed values
 * (Boolean/Int/Char) and `int64` it is bitwise equality, and for `float64` it is IEEE 754
 * equality (NaN != NaN, -0.0 == 0.0) — exactly the contract of `ieee754equals`, mirroring the
 * JVM backend's Ieee754Equals intrinsic. User-class instances support reference equality
 * (`===`, a `ceq` on the object references) and `==` against the `null` literal, which Kotlin
 * defines as a pure reference check that never calls `equals` (the JVM backend's `Equals`
 * intrinsic special-cases `isNullConst` operands into an `ifnull` check the same way); general
 * `==` between two instances is rejected until an Any.equals model exists.
 */
private class DotNetIlEqualityIntrinsic(
    private val referenceEquality: Boolean,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Boolean || call.arguments.size != 2) return false
        val operandType = call.dotNetEqualityOperandType(codegen)
            ?: dotNetUnsupported("equality comparison of unsupported operand types")
        val left = call.arguments[0]
            ?: dotNetUnsupported("missing left operand of an equality comparison")
        val right = call.arguments[1]
            ?: dotNetUnsupported("missing right operand of an equality comparison")

        codegen.emitExpression(left, operandType)
        codegen.emitExpression(right, operandType)
        when (operandType) {
            DotNetIlValueType.Boolean,
            DotNetIlValueType.Int32,
            DotNetIlValueType.Int64,
            DotNetIlValueType.Float64,
            DotNetIlValueType.Char,
                -> codegen.emit("ceq", pops = 2, pushes = 1)
            DotNetIlValueType.String -> {
                if (referenceEquality) {
                    codegen.emit("ceq", pops = 2, pushes = 1)
                } else {
                    codegen.emit("call bool ${CORE_LIB_REF}System.String::op_Equality(string, string)", pops = 2, pushes = 1)
                }
            }
            is DotNetIlValueType.UserClass, is DotNetIlValueType.MappedClass -> {
                // Reference equality on object references is a plain `ceq` (probe-verified).
                // `x == null` shares it: Kotlin defines a null-literal comparison as a pure
                // reference check that never calls `equals` (JVM precedent: the Equals intrinsic
                // rewrites `isNullConst` operands to an `ifnull` check), so no Any.equals model
                // is involved. General instance `==` needs that model and is rejected loudly —
                // never silently downgraded to a reference comparison. Mapped exception types
                // follow the same rules; `===` on them is what makes rethrow identity observable.
                if (referenceEquality || left.isNullConst() || right.isNullConst()) {
                    codegen.emit("ceq", pops = 2, pushes = 1)
                } else {
                    dotNetUnsupported(
                        "'==' between class instances is not supported yet (requires the Any.equals model); '===' compares references"
                    )
                }
            }
        }
        return true
    }
}

/**
 * A binary numeric member operator (`plus`, `minus`, `times`) mapped to a single IL instruction,
 * with both operands widened to the promoted computation type first.
 */
private class DotNetIlNumericBinaryOperatorIntrinsic(
    private val instruction: String,
    private val receiverType: DotNetIlValueType,
    private val argumentType: DotNetIlValueType,
    private val resultType: DotNetIlValueType,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != resultType || call.arguments.size != 2) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing receiver of a numeric '$instruction' operator")
        val argument = call.arguments[1]
            ?: dotNetUnsupported("missing argument of a numeric '$instruction' operator")
        codegen.emitWidenedOperand(receiver, receiverType, resultType)
        codegen.emitWidenedOperand(argument, argumentType, resultType)
        codegen.emit(instruction, pops = 2, pushes = 1)
        return true
    }
}

/**
 * `div`/`rem` over the numeric types.
 *
 * Integral (`int32`/`int64`) results: CIL `div`/`rem` truncate toward zero like Kotlin, but they
 * throw `System.OverflowException` for `MIN_VALUE / -1` and `MIN_VALUE % -1`, where Kotlin
 * (matching the JVM `idiv`/`ldiv`) defines the results as `MIN_VALUE` and `0`. A `-1` divisor
 * therefore bypasses `div`/`rem`: `a / -1` is `neg a` (IL `neg` is plain two's-complement
 * negation and does not overflow-check) and `a % -1` is `0`. The guard is emitted at runtime
 * unless the divisor is a constant that decides it statically; the `int64` guard compares
 * against `-1` widened with `conv.i8`. The guard stays load-bearing with the exception model in
 * place: `System.OverflowException` IS-A `System.ArithmeticException` (probe-verified), so
 * without it a `catch (e: ArithmeticException)` would observably catch an overflow Kotlin
 * defines as a plain result.
 *
 * A zero divisor raises the CLR's `System.DivideByZeroException`, which IS-A
 * `System.ArithmeticException` (probe-verified) — the target of `kotlin.ArithmeticException` in
 * [DotNetMappedExceptions] — so `catch (e: ArithmeticException)` catches it, matching the JVM at
 * the type level. The remaining divergence is message text only: `"Attempted to divide by
 * zero."` instead of the JVM's `"/ by zero"`, the platform message kept verbatim (JVM precedent:
 * `"/ by zero"` IS the JVM's platform message).
 *
 * `float64` results need no guard: CIL float `div` is plain IEEE 754 division (`x / 0.0` is an
 * infinity, no exceptions) and CIL float `rem` is the remainder after truncated division — the
 * same operation as JVM `drem`/Kotlin `Double.rem`, NOT `System.Math.IEEERemainder`.
 */
private class DotNetIlNumericDivRemIntrinsic(
    private val isDivision: Boolean,
    private val receiverType: DotNetIlValueType,
    private val argumentType: DotNetIlValueType,
    private val resultType: DotNetIlValueType,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        val operatorName = if (isDivision) "div" else "rem"
        if (expectedType != resultType || call.arguments.size != 2) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing receiver of a numeric '$operatorName' operator")
        val argument = call.arguments[1]
            ?: dotNetUnsupported("missing argument of a numeric '$operatorName' operator")

        codegen.emitWidenedOperand(receiver, receiverType, resultType)

        if (resultType == DotNetIlValueType.Float64) {
            codegen.emitWidenedOperand(argument, argumentType, resultType)
            codegen.emit(operatorName, pops = 2, pushes = 1)
            return true
        }

        val zeroLoad = if (resultType == DotNetIlValueType.Int64) "ldc.i8 0" else "ldc.i4.0"
        val constantDivisor: Long? = when (val value = (argument as? IrConst)?.value) {
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }
        when (constantDivisor) {
            -1L -> if (isDivision) {
                codegen.emit("neg", pops = 1, pushes = 1)
            } else {
                codegen.emit("pop", pops = 1)
                codegen.emit(zeroLoad, pushes = 1)
            }
            null -> {
                codegen.emitWidenedOperand(argument, argumentType, resultType)
                val normalLabel = codegen.nextLabel("${operatorName}Normal")
                val endLabel = codegen.nextLabel("${operatorName}End")
                codegen.emit("dup", pops = 1, pushes = 2)
                codegen.emit("ldc.i4.m1", pushes = 1)
                if (resultType == DotNetIlValueType.Int64) {
                    codegen.emit("conv.i8", pops = 1, pushes = 1)
                }
                codegen.emitBranch("bne.un", normalLabel, pops = 2)
                if (isDivision) {
                    codegen.emit("pop", pops = 1)
                    codegen.emit("neg", pops = 1, pushes = 1)
                } else {
                    codegen.emit("pop", pops = 1)
                    codegen.emit("pop", pops = 1)
                    codegen.emit(zeroLoad, pushes = 1)
                }
                codegen.emitGoto(endLabel)
                codegen.emitLabel(normalLabel)
                codegen.emit(operatorName, pops = 2, pushes = 1)
                codegen.emitLabel(endLabel)
            }
            else -> {
                codegen.emitWidenedOperand(argument, argumentType, resultType)
                codegen.emit(operatorName, pops = 2, pushes = 1)
            }
        }
        return true
    }
}

/** A unary numeric member operator: `neg` for `unaryMinus`, no instruction for `unaryPlus`. */
private class DotNetIlNumericUnaryOperatorIntrinsic(
    private val instruction: String?,
    private val operandType: DotNetIlValueType,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != operandType || call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing receiver of a unary numeric operator")
        codegen.emitExpression(receiver, operandType)
        if (instruction != null) {
            codegen.emit(instruction, pops = 1, pushes = 1)
        }
        return true
    }
}

/** `inc`/`dec` of {Int, Long, Double}: the receiver plus/minus a constant `1` of the operand type. */
private class DotNetIlNumericIncrementIntrinsic(
    private val instruction: String,
    private val operandType: DotNetIlValueType,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != operandType || call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing receiver of a numeric increment operator")
        codegen.emitExpression(receiver, operandType)
        val oneLoad = when (operandType) {
            DotNetIlValueType.Int64 -> "ldc.i8 1"
            DotNetIlValueType.Float64 -> "ldc.r8 1.0"
            is DotNetIlValueType.UserClass, is DotNetIlValueType.MappedClass ->
                dotNetUnsupported("numeric increment of a class instance is not supported")
            else -> "ldc.i4.1"
        }
        codegen.emit(oneLoad, pushes = 1)
        codegen.emit(instruction, pops = 2, pushes = 1)
        return true
    }
}

/**
 * `Char.plus(Int): Char` / `Char.minus(Int): Char`. Like the JVM backend, char arithmetic runs
 * on the plain int stack value and the result is wrapped back to a 16-bit code unit; the CLR
 * equivalent of JVM `i2c` is `conv.u2` (zero-extending 16-bit truncation).
 */
private class DotNetIlCharPlusMinusIntIntrinsic(
    private val instruction: String,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Char || call.arguments.size != 2) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing receiver of a Char '$instruction' operator")
        val argument = call.arguments[1]
            ?: dotNetUnsupported("missing argument of a Char '$instruction' operator")
        codegen.emitExpression(receiver, DotNetIlValueType.Char)
        codegen.emitExpression(argument, DotNetIlValueType.Int32)
        codegen.emit(instruction, pops = 2, pushes = 1)
        codegen.emit("conv.u2", pops = 1, pushes = 1)
        return true
    }
}

/**
 * `Char.minus(Char): Int` and `Char.compareTo(Char): Int`: a plain `sub` of the two code units
 * with no `conv.u2` wrap — the result type is `Int` and may be negative, exactly like JVM `isub`
 * on two chars. The same emission serves `compareTo` (see the registration site): the difference
 * of two 16-bit code units cannot overflow int32, so it is a valid three-way comparison value.
 */
private object DotNetIlCharMinusCharIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Int32 || call.arguments.size != 2) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing receiver of 'Char.minus'")
        val argument = call.arguments[1]
            ?: dotNetUnsupported("missing argument of 'Char.minus'")
        codegen.emitExpression(receiver, DotNetIlValueType.Char)
        codegen.emitExpression(argument, DotNetIlValueType.Char)
        codegen.emit("sub", pops = 2, pushes = 1)
        return true
    }
}

/** `Char.inc`/`Char.dec`: int add/sub of `1` wrapped back to a code unit with `conv.u2` (JVM `i2c`). */
private class DotNetIlCharIncrementIntrinsic(
    private val instruction: String,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Char || call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing receiver of a Char increment operator")
        codegen.emitExpression(receiver, DotNetIlValueType.Char)
        codegen.emit("ldc.i4.1", pushes = 1)
        codegen.emit(instruction, pops = 2, pushes = 1)
        codegen.emit("conv.u2", pops = 1, pushes = 1)
        return true
    }
}

/**
 * A primitive comparison (`kotlin.internal.ir.less` and friends). IL only has `clt`/`cgt`
 * (plus `.un`), so `<=` and `>=` are emitted as the [negated] opposite comparison compared to
 * `0`; see the registration site for the per-type instruction choice (Double uses the `.un`
 * forms to stay NaN-correct).
 */
private class DotNetIlComparisonIntrinsic(
    private val instruction: String,
    private val negated: Boolean,
    private val operandType: DotNetIlValueType,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Boolean || call.arguments.size != 2) return false
        val left = call.arguments[0]
            ?: dotNetUnsupported("missing left operand of a comparison")
        val right = call.arguments[1]
            ?: dotNetUnsupported("missing right operand of a comparison")
        codegen.emitExpression(left, operandType)
        codegen.emitExpression(right, operandType)
        codegen.emit(instruction, pops = 2, pushes = 1)
        if (negated) {
            codegen.emit("ldc.i4.0", pushes = 1)
            codegen.emit("ceq", pops = 2, pushes = 1)
        }
        return true
    }
}

/**
 * A `to<Type>()` conversion mapped to zero or more 1-pop/1-push IL instructions, following the
 * JVM backend's `NumberCast` intrinsic:
 * - Int -> Long: `conv.i8` (sign-extend, = `i2l`); Long -> Int: `conv.i4` (unchecked wrap to the
 *   low 32 bits, = `l2i`; the non-`.ovf` `conv.*` opcodes never throw)
 * - Int/Long/Char -> Double: `conv.r8` (= `i2d`/`l2d`)
 * - Int -> Char: `conv.u2` (16-bit zero-extending truncation, = `i2c`); Char -> Int/`Char.code`:
 *   no instruction, the char already sits on the evaluation stack as a zero-extended int32
 * - identity conversions (`Int.toInt()` etc.): no instruction
 *
 * Double -> Int/Long saturating conversions live in [DotNetIlDoubleToIntegralIntrinsic].
 */
private class DotNetIlNumberConversionIntrinsic(
    private val fromType: DotNetIlValueType,
    private val toType: DotNetIlValueType,
    private val instructions: List<String>,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != toType || call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing receiver of a '${toType.nameInSignature}' conversion")
        codegen.emitExpression(receiver, fromType)
        for (instruction in instructions) {
            codegen.emit(instruction, pops = 1, pushes = 1)
        }
        return true
    }
}

/**
 * `Double.toInt()`/`Double.toLong()` with JVM `d2i`/`d2l` semantics: NaN -> 0, above the target
 * MAX (including +Inf) -> MAX, below MIN (including -Inf) -> MIN, otherwise truncation toward
 * zero. A bare `conv.i4`/`conv.i8` must NOT be used: ECMA-335 III leaves float->int conversion
 * of out-of-range values undefined (runtimes differ: legacy CLR wraps, .NET Core saturates or
 * traps per platform), so the bounds are checked explicitly and only in-range values reach the
 * `conv` opcode.
 *
 * Bound constants:
 * - int32: `2147483647.0` and `-2147483648.0` are exact doubles. `d > MAX` is the overflow test
 *   because every double in (MAX, MAX+1) truncates to MAX anyway; `d < MIN` symmetrically, and
 *   MIN itself converts exactly.
 * - int64: 2^63-1 is NOT representable as a double (it rounds to 2^63), so the overflow test is
 *   `d >= 2^63` using the exact raw-bit constant `float64(0x43E0000000000000)`; every
 *   representable double below 2^63 converts in range. `-2^63` (`float64(0xC3E0000000000000)`)
 *   is exact, so only `d < -2^63` underflows.
 */
private class DotNetIlDoubleToIntegralIntrinsic(
    private val targetType: DotNetIlValueType,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != targetType || call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing receiver of a Double to ${targetType.nameInSignature} conversion")
        codegen.emitExpression(receiver, DotNetIlValueType.Float64)

        val isLongTarget = targetType == DotNetIlValueType.Int64
        val nanLabel = codegen.nextLabel("d2iNaN")
        val maxLabel = codegen.nextLabel("d2iMax")
        val minLabel = codegen.nextLabel("d2iMin")
        val endLabel = codegen.nextLabel("d2iEnd")

        // NaN check: `bne.un` branches when the operands are unordered, and `d != d` only for NaN.
        codegen.emit("dup", pops = 1, pushes = 2)
        codegen.emit("dup", pops = 1, pushes = 2)
        codegen.emitBranch("bne.un", nanLabel, pops = 2)

        if (isLongTarget) {
            codegen.emit("dup", pops = 1, pushes = 2)
            codegen.emit("ldc.r8 float64(0x43E0000000000000)", pushes = 1) // 2^63
            codegen.emit("clt", pops = 2, pushes = 1)
            codegen.emitBranch("brfalse", maxLabel, pops = 1) // NOT (d < 2^63), i.e. d >= 2^63
            codegen.emit("dup", pops = 1, pushes = 2)
            codegen.emit("ldc.r8 float64(0xC3E0000000000000)", pushes = 1) // -2^63
            codegen.emit("clt", pops = 2, pushes = 1)
            codegen.emitBranch("brtrue", minLabel, pops = 1)
            codegen.emit("conv.i8", pops = 1, pushes = 1)
        } else {
            codegen.emit("dup", pops = 1, pushes = 2)
            codegen.emit("ldc.r8 2147483647.0", pushes = 1)
            codegen.emit("cgt", pops = 2, pushes = 1)
            codegen.emitBranch("brtrue", maxLabel, pops = 1)
            codegen.emit("dup", pops = 1, pushes = 2)
            codegen.emit("ldc.r8 -2147483648.0", pushes = 1)
            codegen.emit("clt", pops = 2, pushes = 1)
            codegen.emitBranch("brtrue", minLabel, pops = 1)
            codegen.emit("conv.i4", pops = 1, pushes = 1)
        }
        codegen.emitGoto(endLabel)
        codegen.emitLabel(maxLabel)
        codegen.emit("pop", pops = 1)
        codegen.emit(if (isLongTarget) "ldc.i8 9223372036854775807" else "ldc.i4 2147483647", pushes = 1)
        codegen.emitGoto(endLabel)
        codegen.emitLabel(minLabel)
        codegen.emit("pop", pops = 1)
        codegen.emit(if (isLongTarget) "ldc.i8 -9223372036854775808" else "ldc.i4 -2147483648", pushes = 1)
        codegen.emitGoto(endLabel)
        codegen.emitLabel(nanLabel)
        codegen.emit("pop", pops = 1)
        codegen.emit(if (isLongTarget) "ldc.i8 0" else "ldc.i4.0", pushes = 1)
        codegen.emitLabel(endLabel)
        return true
    }
}

/**
 * A call the backend rejects explicitly. Follows the design rule (and the JVM backend's
 * `IntrinsicShouldHaveBeenLowered` shape) of registering the entry now and failing explicitly
 * instead of leaving the call to fall through to generic — and less precise — failure paths.
 */
private class DotNetIlUnsupportedIntrinsic(
    private val reason: String,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean = dotNetUnsupported(reason)

    override fun tryEmitAsStatement(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
    ): Boolean = dotNetUnsupported(reason)
}

/**
 * `kotlin.io.println` overloads, mapped to `System.Console::WriteLine` the same way the JVM
 * target maps them to `System.out.println` overloads (there via the `PrintStream` overload
 * resolved by the frontend; here the fake-stdlib overload picks the `WriteLine` shape).
 *
 * Overload dispatch on the declared parameter type:
 * - `Char` calls `WriteLine(char)` directly: it writes the single UTF-16 code unit without any
 *   formatting, identical to Kotlin's `Char.toString()` rendering.
 * - `Int`/`Long` must NOT use `WriteLine(int32)`/`WriteLine(int64)`: those format with the
 *   *current* culture, whose `NumberFormat.NegativeSign` is user-customizable in the Windows
 *   regional settings, so `println(-5)` could print `"!5"` where Kotlin prints `"-5"` (verified
 *   on the targeted runtime). They funnel through
 *   [DotNetIlExpressionCodegen.emitStringValueExpression], whose integer branches render via
 *   `IFormattable::ToString(null, InvariantCulture)`.
 * - `Double` must NOT use `WriteLine(float64)` for the same reason (e.g. `1,5` under a German
 *   locale) and because it prints CLR shapes (`1`, `1E+20`) instead of Kotlin's (`1.0`,
 *   `1.0E20`). It funnels through
 *   [DotNetIlExpressionCodegen.emitStringValueExpression], whose Double branch calls the shared
 *   [DotNetIlRuntimeHelper.DoubleToString] runtime helper — Kotlin-parity rendering, with the
 *   divergences documented on that helper.
 * - `String`/`Boolean`/`Any?` funnel through the Kotlin string rendering of the value. In
 *   particular `Console.WriteLine(bool)` must NOT be used: it prints `"True"`/`"False"` while
 *   Kotlin prints `"true"`/`"false"`.
 */
private object DotNetIlPrintlnIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsStatement(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
    ): Boolean {
        return when (call.arguments.size) {
            0 -> {
                codegen.emit("call void ${CORE_LIB_REF}System.Console::WriteLine()")
                true
            }
            1 -> {
                val argument = call.arguments.single()
                    ?: dotNetUnsupported("missing argument in a call to 'println'")
                val parameterType = call.symbol.owner.parameters.singleOrNull()?.type?.let(codegen::toDotNetIlValueType)
                when (parameterType) {
                    DotNetIlValueType.Char -> {
                        codegen.emitExpression(argument, DotNetIlValueType.Char)
                        codegen.emit("call void ${CORE_LIB_REF}System.Console::WriteLine(char)", pops = 1)
                    }
                    else -> {
                        // Int, Long, Double, String, Boolean and Any? — see the class KDoc for
                        // why the direct WriteLine(int32)/WriteLine(int64)/WriteLine(float64)/
                        // WriteLine(bool) overloads must not be used.
                        codegen.emitStringValueExpression(argument)
                        codegen.emit("call void ${CORE_LIB_REF}System.Console::WriteLine(string)", pops = 1)
                    }
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
        codegen.emit("call string ${CORE_LIB_REF}System.String::Concat(string, string)", pops = 2, pushes = 1)
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

/**
 * `Throwable.message` -> a `callvirt` of the corelib `System.Exception::get_Message()`
 * (probe-verified). Documented platform delta: Kotlin's `message` keeps its `String?` type, but
 * the CLR `Message` property is never null for mapped exceptions — a no-arg `Exception()` yields
 * the CLR default text `"Exception of type 'System.Exception' was thrown."` (probe-verified
 * verbatim), where Kotlin/JVM would yield null.
 */
private object DotNetIlExceptionMessageIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.String || call.arguments.size != 1) return false
        val receiver = call.dotNetMappedExceptionReceiver(codegen, "message")
        codegen.emitExpression(receiver.first, receiver.second)
        codegen.emit(
            "callvirt instance string ${DotNetMappedExceptions.EXCEPTION_TYPE_REF}::get_Message()",
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/**
 * `Throwable.cause` -> a `callvirt` of the corelib `System.Exception::get_InnerException()`
 * (probe-verified, including the null result of a cause-less exception). The Kotlin result type
 * `Throwable?` maps to the same `System.Exception` reference the getter returns.
 */
private object DotNetIlExceptionCauseIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        val exceptionType = DotNetIlValueType.MappedClass(DotNetMappedExceptions.EXCEPTION_TYPE_REF)
        if (expectedType != exceptionType || call.arguments.size != 1) return false
        val receiver = call.dotNetMappedExceptionReceiver(codegen, "cause")
        codegen.emitExpression(receiver.first, receiver.second)
        codegen.emit(
            "callvirt instance ${exceptionType.nameInSignature} " +
                    "${DotNetMappedExceptions.EXCEPTION_TYPE_REF}::get_InnerException()",
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/** The dispatch receiver of an exception member access, together with its mapped IL type. */
private fun IrCall.dotNetMappedExceptionReceiver(
    codegen: DotNetIlExpressionCodegen,
    memberName: String,
): Pair<IrExpression, DotNetIlValueType.MappedClass> {
    val receiver = arguments.single()
        ?: dotNetUnsupported("missing receiver of 'Throwable.$memberName'")
    val receiverType = codegen.toDotNetIlValueType(receiver.type) as? DotNetIlValueType.MappedClass
        ?: dotNetUnsupported("reading '$memberName' of a non-exception-mapped receiver is not supported")
    return receiver to receiverType
}

private fun IrCall.dotNetEqualityOperandType(codegen: DotNetIlExpressionCodegen): DotNetIlValueType? {
    val left = arguments.getOrNull(0) ?: return null
    val right = arguments.getOrNull(1) ?: return null
    val leftType = codegen.toDotNetIlValueType(left.type)
    val rightType = codegen.toDotNetIlValueType(right.type)
    return when {
        leftType != null && leftType == rightType -> leftType
        // A `null` constant compared against a reference type (string or a user class) takes
        // the reference type: `ldnull` satisfies any class-typed operand slot.
        left.isNullConst() && rightType.isDotNetReferenceType() -> rightType
        right.isNullConst() && leftType.isDotNetReferenceType() -> leftType
        // Two differently-mapped exception operands (e.g. `caught === original` where one side
        // is typed `Throwable` and the other `IllegalStateException`) compare through their
        // common CLR supertype: every mapped exception widens to `System.Exception`, and the
        // reference `ceq` is type-agnostic. General `==` on that pair still lands in the
        // MappedClass rejection arm below, exactly like same-typed instances.
        leftType is DotNetIlValueType.MappedClass && rightType is DotNetIlValueType.MappedClass ->
            DotNetIlValueType.MappedClass(DotNetMappedExceptions.EXCEPTION_TYPE_REF)
        else -> null
    }
}

private fun DotNetIlValueType?.isDotNetReferenceType(): Boolean =
    this == DotNetIlValueType.String || this is DotNetIlValueType.UserClass || this is DotNetIlValueType.MappedClass

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
