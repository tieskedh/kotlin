package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isNullableNothing
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFileClass
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isNullConst
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.FqName

/**
 * Registry of function calls compiled directly to IL, keyed on owner/receiver/name/parameter
 * FqNames. Mirrors the JVM backend's `IrIntrinsicMethods`: arithmetic, comparisons and number
 * conversions are registered programmatically by looping over the supported primitive types
 * (the JVM loops over all of `PrimitiveType.entries`; here the loop is restricted to the
 * landed signed Common scalar set, with nonnumeric cases registered separately).
 */
internal class DotNetIlIntrinsicMethods(
    irBuiltIns: IrBuiltIns,
    emissionScope: DotNetIlEmissionScope,
) {
    private val kotlinFqn = StandardNames.BUILT_INS_PACKAGE_FQ_NAME
    private val kotlinInternalFqn = FqName("kotlin.internal")
    private val kotlinIoFqn = FqName("kotlin.io")
    private val kotlinCollectionsFqn = FqName("kotlin.collections")
    private val kotlinTextFqn = FqName("kotlin.text")
    private val kotlinReflectDotNetInternalFqn = FqName("kotlin.reflect.dotnet.internal")
    private val safeContinuationFqn = FqName("kotlin.coroutines.SafeContinuation")

    private val anyFqn = StandardNames.FqNames.any.toSafe()
    private val arrayFqn = StandardNames.FqNames.array.toSafe()
    private val iterableFqn = FqName("kotlin.collections.Iterable")
    private val listFqn = FqName("kotlin.collections.List")
    private val stringFqn = StandardNames.FqNames.string.toSafe()
    private val throwableFqn = StandardNames.FqNames.throwable
    private val byteFqn = StandardNames.FqNames._byte.toSafe()
    private val shortFqn = StandardNames.FqNames._short.toSafe()
    private val intFqn = StandardNames.FqNames._int.toSafe()
    private val longFqn = StandardNames.FqNames._long.toSafe()
    private val floatFqn = StandardNames.FqNames._float.toSafe()
    private val doubleFqn = StandardNames.FqNames._double.toSafe()
    private val charFqn = StandardNames.FqNames._char.toSafe()
    private val booleanFqn = StandardNames.FqNames._boolean.toSafe()

    private data class PrimitiveArrayIntrinsicInfo(
        val arrayFqn: FqName,
        val elementFqn: FqName,
        val arrayOfName: String,
        val arrayType: DotNetIlValueType.PrimitiveArray,
    )

    /** The complete signed Common primitive-array family, keyed by its exact selected scalar carrier. */
    private val primitiveArrays = listOf(
        PrimitiveArrayIntrinsicInfo(
            FqName("kotlin.BooleanArray"), booleanFqn, "booleanArrayOf",
            DotNetIlValueType.PrimitiveArray(DotNetIlValueType.Boolean),
        ),
        PrimitiveArrayIntrinsicInfo(
            FqName("kotlin.ByteArray"), byteFqn, "byteArrayOf",
            DotNetIlValueType.PrimitiveArray(DotNetIlValueType.Int8),
        ),
        PrimitiveArrayIntrinsicInfo(
            FqName("kotlin.ShortArray"), shortFqn, "shortArrayOf",
            DotNetIlValueType.PrimitiveArray(DotNetIlValueType.Int16),
        ),
        PrimitiveArrayIntrinsicInfo(
            FqName("kotlin.IntArray"), intFqn, "intArrayOf",
            DotNetIlValueType.PrimitiveArray(DotNetIlValueType.Int32),
        ),
        PrimitiveArrayIntrinsicInfo(
            FqName("kotlin.LongArray"), longFqn, "longArrayOf",
            DotNetIlValueType.PrimitiveArray(DotNetIlValueType.Int64),
        ),
        PrimitiveArrayIntrinsicInfo(
            FqName("kotlin.FloatArray"), floatFqn, "floatArrayOf",
            DotNetIlValueType.PrimitiveArray(DotNetIlValueType.Float32),
        ),
        PrimitiveArrayIntrinsicInfo(
            FqName("kotlin.DoubleArray"), doubleFqn, "doubleArrayOf",
            DotNetIlValueType.PrimitiveArray(DotNetIlValueType.Float64),
        ),
        PrimitiveArrayIntrinsicInfo(
            FqName("kotlin.CharArray"), charFqn, "charArrayOf",
            DotNetIlValueType.PrimitiveArray(DotNetIlValueType.Char),
        ),
    )

    /**
     * The numeric types binary operators and conversions are generated over, keyed by builtin
     * FqName. Byte/Short computations promote to Int32; above that the order is
     * Int32 < Int64 < Float32 < Float64 (see [promoteNumeric]), matching the Kotlin stdlib operator
     * signatures (`Byte.plus(Byte): Int`, `Int.plus(Long): Long`, ...).
     */
    private val numericTypes: Map<FqName, DotNetIlValueType> = mapOf(
        byteFqn to DotNetIlValueType.Int8,
        shortFqn to DotNetIlValueType.Int16,
        intFqn to DotNetIlValueType.Int32,
        longFqn to DotNetIlValueType.Int64,
        floatFqn to DotNetIlValueType.Float32,
        doubleFqn to DotNetIlValueType.Float64,
    )

    private val intrinsics = listOf(
        irBuiltIns.eqeqSymbol.toKey()!! to DotNetIlEqualityIntrinsic(referenceEquality = false),
        irBuiltIns.eqeqeqSymbol.toKey()!! to DotNetIlEqualityIntrinsic(referenceEquality = true),
        // fir2ir routes `==` over operands statically known to be Double/Float through
        // `irBuiltIns.ieee754equalsFunByOperandType` (see OperatorExpressionGenerator), NOT
        // through `eqeqSymbol`; the JVM backend registers these symbols separately to its
        // Ieee754Equals intrinsic. CIL `ceq` on float32/float64 *is* IEEE 754 equality (NaN !=
        // NaN, -0.0 == 0.0), which is exactly the required primitive semantics for both entries.
        irBuiltIns.ieee754equalsFunByOperandType.getValue(irBuiltIns.doubleClass).toKey()!!
                to DotNetIlEqualityIntrinsic(referenceEquality = false, ieee754FloatingPoint = true),
        irBuiltIns.ieee754equalsFunByOperandType.getValue(irBuiltIns.floatClass).toKey()!!
                to DotNetIlEqualityIntrinsic(referenceEquality = false, ieee754FloatingPoint = true),
        irBuiltIns.booleanNotSymbol.toKey()!! to DotNetIlBooleanNotIntrinsic,
        // Masked default-argument stubs test one bit at a time with the common IR builtin. JVM
        // intrinsifies the same Int.and operation; CLR has the direct stack `and` instruction.
        irBuiltIns.intAndSymbol.toKey()!! to DotNetIlNumericBinaryOperatorIntrinsic(
            "and",
            DotNetIlValueType.Int32,
            DotNetIlValueType.Int32,
            DotNetIlValueType.Int32,
        ),
        // `a!!` arrives as a call to the CHECK_NOT_NULL builtin (`kotlin.internal.ir`), exactly
        // like on the JVM, whose backend intrinsifies it as checkNotNull (Intrinsics.checkNotNull
        // at runtime); here the null test + throw is emitted inline (see the intrinsic's KDoc).
        irBuiltIns.checkNotNullSymbol.toKey()!! to DotNetIlCheckNotNullIntrinsic,
        // MethodsFromAnyGeneratorForLowerings uses these synthetic builtins only for array-backed
        // data-class properties. Match the JVM intrinsic-registry boundary: equality remains the
        // array's ordinary identity equality, while generated hashCode/toString use content.
        irBuiltIns.dataClassArrayMemberHashCodeSymbol.toKey()!!
                to DotNetIlDataClassArrayMemberHashCodeIntrinsic,
        irBuiltIns.dataClassArrayMemberToStringSymbol.toKey()!!
                to DotNetIlDataClassArrayMemberToStringIntrinsic,
        // fir2ir appends this synthetic call as the final branch of an exhaustive `when`
        // without a source `else`, exactly the symbol the JVM backend intrinsifies.
        irBuiltIns.noWhenBranchMatchedExceptionSymbol.toKey()!! to DotNetIlNoWhenBranchMatchedIntrinsic,
        irBuiltIns.illegalArgumentExceptionSymbol.toKey()!! to DotNetIlIllegalArgumentExceptionIntrinsic,
        Key(kotlinInternalFqn, null, "throwKotlinNothingValueException", emptyList())
                to DotNetIlThrowKotlinNothingValueExceptionIntrinsic,
        Key(kotlinInternalFqn, null, "annotationFloatEquals", listOf(floatFqn, floatFqn))
                to DotNetIlAnnotationFloatingEqualsIntrinsic(DotNetIlValueType.Float32),
        Key(kotlinInternalFqn, null, "annotationDoubleEquals", listOf(doubleFqn, doubleFqn))
                to DotNetIlAnnotationFloatingEqualsIntrinsic(DotNetIlValueType.Float64),
        Key(kotlinInternalFqn, null, "captureStaticInitializationFailure", listOf(throwableFqn))
                to DotNetIlCaptureStaticInitializationFailureIntrinsic,
        Key(kotlinInternalFqn, null, "observeStaticInitializationFailure", listOf(anyFqn))
                to DotNetIlObserveStaticInitializationFailureIntrinsic,
        Key(kotlinInternalFqn, null, "staticInitializationFailure", listOf(throwableFqn, stringFqn))
                to DotNetIlStaticInitializationFailureIntrinsic,
        Key(kotlinFqn, null, "dotNetStackTraceToString", listOf(throwableFqn))
                to DotNetIlThrowableStackTraceToStringIntrinsic,
        Key(kotlinFqn, null, "dotNetPrintStackTrace", listOf(throwableFqn))
                to DotNetIlThrowablePrintStackTraceIntrinsic,
        Key(kotlinFqn, null, "dotNetAddSuppressed", listOf(throwableFqn, throwableFqn))
                to DotNetIlThrowableAddSuppressedIntrinsic,
        Key(kotlinFqn, null, "dotNetSuppressedExceptions", listOf(throwableFqn))
                to DotNetIlThrowableSuppressedExceptionsIntrinsic,
        Key(safeContinuationFqn, null, "compareAndSetResult", listOf(anyFqn, anyFqn))
                to DotNetIlSafeContinuationCompareAndSetIntrinsic,
        Key(kotlinIoFqn, null, "dotNetReadLine", emptyList()) to DotNetIlReadLineIntrinsic,
        Key(
            kotlinReflectDotNetInternalFqn,
            null,
            "dotNetGetGeneratedMembersV1",
            listOf(FqName("kotlin.reflect.KClass")),
        ) to DotNetIlGeneratedMembersIntrinsic,
        Key(
            kotlinReflectDotNetInternalFqn,
            null,
            "dotNetGetStdlibMembersV1",
            listOf(FqName("kotlin.reflect.KClass")),
        ) to DotNetIlStdlibMembersIntrinsic,
        Key(kotlinIoFqn, null, "print", listOf(anyFqn)) to DotNetIlPrintIntrinsic,
        Key(kotlinIoFqn, null, "println", emptyList()) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(stringFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(intFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(longFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(floatFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(doubleFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(charFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(booleanFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinIoFqn, null, "println", listOf(anyFqn)) to DotNetIlPrintlnIntrinsic,
        Key(kotlinFqn, stringFqn, "plus", listOf(anyFqn)) to DotNetIlStringPlusIntrinsic,
        Key(stringFqn, null, "plus", listOf(anyFqn)) to DotNetIlStringPlusIntrinsic,
        Key(kotlinFqn, anyFqn, "toString", emptyList()) to DotNetIlToStringIntrinsic,
        Key(anyFqn, null, "toString", emptyList()) to DotNetIlAnyToStringIntrinsic,
        Key(intFqn, null, "toString", emptyList()) to DotNetIlToStringIntrinsic,
        Key(longFqn, null, "toString", emptyList()) to DotNetIlToStringIntrinsic,
        Key(floatFqn, null, "toString", emptyList()) to DotNetIlToStringIntrinsic,
        Key(doubleFqn, null, "toString", emptyList()) to DotNetIlToStringIntrinsic,
        Key(charFqn, null, "toString", emptyList()) to DotNetIlToStringIntrinsic,
        Key(booleanFqn, null, "toString", emptyList()) to DotNetIlToStringIntrinsic,
    ) + comparisonIntrinsics(irBuiltIns) + booleanOperatorIntrinsics() +
            numericOperatorIntrinsics() + charOperatorIntrinsics() +
            primitiveRangeToIntrinsics() +
            conversionIntrinsics() + exceptionMemberIntrinsics() + primitiveArrayIntrinsics() +
            genericArrayIntrinsics() + arrayCopyIntrinsics() + arrayContentIntrinsics() +
            arrayAsIterableIntrinsics() + stringBuilderStorageIntrinsics() +
            if (emissionScope == DotNetIlEmissionScope.USER) stdlibFunctionIntrinsics() else emptyList()

    /** Private storage mechanics for the Kotlin-owned `kotlin.text.StringBuilder` wrapper. */
    private fun stringBuilderStorageIntrinsics(): List<Pair<Key, DotNetIlIntrinsicMethod>> = listOf(
        Key(kotlinTextFqn, null, "dotNetStringBuilderCreate", emptyList()) to
                DotNetIlStringBuilderConstructorIntrinsic(emptyList()),
        Key(kotlinTextFqn, null, "dotNetStringBuilderCreate", listOf(intFqn)) to
                DotNetIlStringBuilderConstructorIntrinsic(listOf(DotNetIlValueType.Int32)),
        Key(kotlinTextFqn, null, "dotNetStringBuilderCreate", listOf(stringFqn)) to
                DotNetIlStringBuilderConstructorIntrinsic(listOf(DotNetIlValueType.String)),
        Key(kotlinTextFqn, null, "dotNetStringBuilderThrowCapacityOverflow", emptyList()) to
                DotNetIlStringBuilderCapacityOverflowIntrinsic,
        Key(kotlinTextFqn, null, "dotNetStringBuilderLength", listOf(anyFqn)) to
                DotNetIlStringBuilderValueIntrinsic(
                    resultType = DotNetIlValueType.Int32,
                    parameterTypes = emptyList(),
                    memberReference = "callvirt instance int32 %sSystem.Text.StringBuilder::get_Length()",
                ),
        Key(kotlinTextFqn, null, "dotNetStringBuilderGet", listOf(anyFqn, intFqn)) to
                DotNetIlStringBuilderValueIntrinsic(
                    resultType = DotNetIlValueType.Char,
                    parameterTypes = listOf(DotNetIlValueType.Int32),
                    memberReference = "callvirt instance char %sSystem.Text.StringBuilder::get_Chars(int32)",
                ),
        Key(kotlinTextFqn, null, "dotNetStringBuilderCapacity", listOf(anyFqn)) to
                DotNetIlStringBuilderValueIntrinsic(
                    resultType = DotNetIlValueType.Int32,
                    parameterTypes = emptyList(),
                    memberReference = "callvirt instance int32 %sSystem.Text.StringBuilder::get_Capacity()",
                ),
        Key(kotlinTextFqn, null, "dotNetStringBuilderSubstring", listOf(anyFqn, intFqn, intFqn)) to
                DotNetIlStringBuilderValueIntrinsic(
                    resultType = DotNetIlValueType.String,
                    parameterTypes = listOf(DotNetIlValueType.Int32, DotNetIlValueType.Int32),
                    memberReference = "callvirt instance string %sSystem.Text.StringBuilder::ToString(int32, int32)",
                ),
        Key(kotlinTextFqn, null, "dotNetStringBuilderToString", listOf(anyFqn)) to
                DotNetIlStringBuilderValueIntrinsic(
                    resultType = DotNetIlValueType.String,
                    parameterTypes = emptyList(),
                    memberReference = "callvirt instance string %sSystem.Text.StringBuilder::ToString()",
                ),
        Key(kotlinTextFqn, null, "dotNetStringBuilderSet", listOf(anyFqn, intFqn, charFqn)) to
                DotNetIlStringBuilderVoidIntrinsic(
                    parameterTypes = listOf(DotNetIlValueType.Int32, DotNetIlValueType.Char),
                    memberReference = "callvirt instance void %sSystem.Text.StringBuilder::set_Chars(int32, char)",
                ),
        Key(kotlinTextFqn, null, "dotNetStringBuilderAppend", listOf(anyFqn, charFqn)) to
                DotNetIlStringBuilderVoidIntrinsic(
                    parameterTypes = listOf(DotNetIlValueType.Char),
                    memberReference = "callvirt instance class %sSystem.Text.StringBuilder %sSystem.Text.StringBuilder::Append(char)",
                    discardResult = true,
                ),
        Key(kotlinTextFqn, null, "dotNetStringBuilderAppend", listOf(anyFqn, stringFqn)) to
                DotNetIlStringBuilderVoidIntrinsic(
                    parameterTypes = listOf(DotNetIlValueType.String),
                    memberReference = "callvirt instance class %sSystem.Text.StringBuilder %sSystem.Text.StringBuilder::Append(string)",
                    discardResult = true,
                ),
        Key(kotlinTextFqn, null, "dotNetStringBuilderInsert", listOf(anyFqn, intFqn, charFqn)) to
                DotNetIlStringBuilderVoidIntrinsic(
                    parameterTypes = listOf(DotNetIlValueType.Int32, DotNetIlValueType.Char),
                    memberReference = "callvirt instance class %sSystem.Text.StringBuilder %sSystem.Text.StringBuilder::Insert(int32, char)",
                    discardResult = true,
                ),
        Key(kotlinTextFqn, null, "dotNetStringBuilderInsert", listOf(anyFqn, intFqn, stringFqn)) to
                DotNetIlStringBuilderVoidIntrinsic(
                    parameterTypes = listOf(DotNetIlValueType.Int32, DotNetIlValueType.String),
                    memberReference = "callvirt instance class %sSystem.Text.StringBuilder %sSystem.Text.StringBuilder::Insert(int32, string)",
                    discardResult = true,
                ),
        Key(kotlinTextFqn, null, "dotNetStringBuilderSetLength", listOf(anyFqn, intFqn)) to
                DotNetIlStringBuilderVoidIntrinsic(
                    parameterTypes = listOf(DotNetIlValueType.Int32),
                    memberReference = "callvirt instance void %sSystem.Text.StringBuilder::set_Length(int32)",
                ),
        Key(kotlinTextFqn, null, "dotNetStringBuilderEnsureCapacity", listOf(anyFqn, intFqn)) to
                DotNetIlStringBuilderVoidIntrinsic(
                    parameterTypes = listOf(DotNetIlValueType.Int32),
                    memberReference = "callvirt instance int32 %sSystem.Text.StringBuilder::EnsureCapacity(int32)",
                    discardResult = true,
                ),
        Key(kotlinTextFqn, null, "dotNetStringBuilderRemove", listOf(anyFqn, intFqn, intFqn)) to
                DotNetIlStringBuilderVoidIntrinsic(
                    parameterTypes = listOf(DotNetIlValueType.Int32, DotNetIlValueType.Int32),
                    memberReference = "callvirt instance class %sSystem.Text.StringBuilder %sSystem.Text.StringBuilder::Remove(int32, int32)",
                    discardResult = true,
                ),
    )

    /**
     * The same constructor/member registry shape as JVM `IrIntrinsicMethods.arrayMethods`, plus
     * the primitive `*ArrayOf` calls that JVM expects VarargLowering to remove. DotNet emits the
     * literal vararg directly because its lowering pipeline intentionally remains small. Entries
     * for initializer constructors and clone values are present now and fail with their
     * feature-specific reasons instead of falling into generic call handling. Escaping iterator
     * values cross the shared Kotlin.Runtime iterator boundary; direct for-loops are still lowered.
     */
    private fun primitiveArrayIntrinsics(): List<Pair<Key, DotNetIlIntrinsicMethod>> = buildList {
        val function1Fqn = FqName("kotlin.Function1")
        for (info in primitiveArrays) {
            add(
                Key(info.arrayFqn, null, "<init>", listOf(intFqn))
                        to DotNetIlPrimitiveArrayConstructorIntrinsic(info.arrayType)
            )
            add(
                Key(info.arrayFqn, null, "<init>", listOf(intFqn, function1Fqn))
                        to DotNetIlUnsupportedIntrinsic(
                            "primitive-array initializer constructors are not supported yet; " +
                                    "use the unary size constructor or ${info.arrayOfName}"
                        )
            )
            add(
                Key(info.arrayFqn, null, "<get-size>", emptyList())
                        to DotNetIlPrimitiveArraySizeIntrinsic(info.arrayType)
            )
            add(
                Key(info.arrayFqn, null, "get", listOf(intFqn))
                        to DotNetIlPrimitiveArrayGetIntrinsic(info.arrayType)
            )
            add(
                Key(info.arrayFqn, null, "set", listOf(intFqn, info.elementFqn))
                        to DotNetIlPrimitiveArraySetIntrinsic(info.arrayType)
            )
            add(
                Key(kotlinFqn, null, info.arrayOfName, listOf(info.arrayFqn))
                        to DotNetIlPrimitiveArrayOfIntrinsic(info.arrayType, info.arrayOfName)
            )
            add(
                Key(info.arrayFqn, null, "iterator", emptyList())
                        to DotNetIlArrayIteratorIntrinsic(info.arrayType)
            )
            add(
                Key(info.arrayFqn, null, "clone", emptyList())
                        to DotNetIlUnsupportedIntrinsic("primitive-array clone is not supported; use copyOf")
            )
        }
    }

    /**
     * Kotlin `Array<E>` uses the same registry surface as the JVM backend's generic-array arm.
     * The actual CLR vector type is resolved from each call's receiver/result so open `!n`/`!!n`
     * elements and concrete reference elements share these entries without erasure.
     */
    private fun genericArrayIntrinsics(): List<Pair<Key, DotNetIlIntrinsicMethod>> {
        val function1Fqn = FqName("kotlin.Function1")
        val typeParameterFqn = FqName("T")
        return listOf(
            Key(arrayFqn, null, "<init>", listOf(intFqn, function1Fqn)) to
                    DotNetIlUnsupportedIntrinsic(
                        "generic-array initializer constructors are not supported yet; " +
                                "use arrayOf, emptyArray, or arrayOfNulls"
                    ),
            Key(arrayFqn, null, "<get-size>", emptyList()) to DotNetIlGenericArraySizeIntrinsic,
            Key(arrayFqn, null, "get", listOf(intFqn)) to DotNetIlGenericArrayGetIntrinsic,
            Key(arrayFqn, null, "set", listOf(intFqn, typeParameterFqn)) to DotNetIlGenericArraySetIntrinsic,
            Key(kotlinFqn, null, "arrayOf", listOf(arrayFqn)) to DotNetIlGenericArrayOfIntrinsic,
            Key(kotlinFqn, null, "emptyArray", emptyList()) to DotNetIlGenericEmptyArrayIntrinsic,
            Key(kotlinFqn, null, "arrayOfNulls", listOf(intFqn)) to DotNetIlGenericArrayOfNullsIntrinsic,
            Key(kotlinCollectionsFqn, null, "dotNetArrayOfNulls", listOf(arrayFqn, intFqn)) to
                    DotNetIlArrayOfNullsLikeIntrinsic,
            Key(kotlinCollectionsFqn, null, "dotNetExactArrayOrNull", listOf(arrayFqn)) to
                    DotNetIlExactGenericArrayOrNullIntrinsic,
            Key(kotlinCollectionsFqn, null, "dotNetErasedArrayOfNulls", listOf(arrayFqn, intFqn)) to
                    DotNetIlArrayOfNullsLikeIntrinsic,
            Key(kotlinCollectionsFqn, null, "dotNetErasedArraySet", listOf(arrayFqn, intFqn, anyFqn)) to
                    DotNetIlErasedArraySetIntrinsic,
            Key(arrayFqn, null, "iterator", emptyList()) to
                    DotNetIlArrayIteratorIntrinsic(fixedArrayType = null),
            Key(arrayFqn, null, "clone", emptyList()) to
                    DotNetIlUnsupportedIntrinsic("generic-array clone is not supported; use copyOf"),
        )
    }

    /**
     * The injected `kotlin.collections.copyInto`/`copyOf` declarations follow the JVM stdlib's
     * platform-operation boundary: calls are registry-owned and never emit their resolution-only
     * declarations. CLR `System.Array.Copy` supplies the overlap-safe bulk move, while
     * `copyInto` first crosses the Kotlin-owned range-check helper because the raw CLR method
     * reports destination range failures as `ArgumentException` (mapped IllegalArgumentException)
     * instead of Kotlin's required IndexOutOfBoundsException category.
     */
    private fun arrayCopyIntrinsics(): List<Pair<Key, DotNetIlIntrinsicMethod>> = buildList {
        for (info in primitiveArrays) {
            add(
                Key(
                    kotlinCollectionsFqn,
                    info.arrayFqn,
                    "copyInto",
                    listOf(info.arrayFqn, intFqn, intFqn, intFqn),
                ) to DotNetIlArrayCopyIntoIntrinsic(info.arrayType)
            )
            add(
                Key(kotlinCollectionsFqn, info.arrayFqn, "copyOf", emptyList())
                        to DotNetIlArrayCopyOfIntrinsic(info.arrayType, resized = false)
            )
            add(
                Key(kotlinCollectionsFqn, info.arrayFqn, "copyOf", listOf(intFqn))
                        to DotNetIlArrayCopyOfIntrinsic(info.arrayType, resized = true)
            )
        }
        add(
            Key(
                kotlinCollectionsFqn,
                arrayFqn,
                "copyInto",
                listOf(arrayFqn, intFqn, intFqn, intFqn),
            ) to DotNetIlArrayCopyIntoIntrinsic(fixedArrayType = null)
        )
        add(
            Key(kotlinCollectionsFqn, arrayFqn, "copyOf", emptyList())
                    to DotNetIlArrayCopyOfIntrinsic(fixedArrayType = null, resized = false)
        )
        add(
            Key(kotlinCollectionsFqn, arrayFqn, "copyOf", listOf(intFqn))
                    to DotNetIlArrayCopyOfIntrinsic(fixedArrayType = null, resized = true)
        )
        add(
            Key(kotlinCollectionsFqn, arrayFqn, "fill", listOf(FqName("T"), intFqn, intFqn))
                    to DotNetIlGenericArrayFillIntrinsic
        )
        add(
            Key(kotlinCollectionsFqn, null, "terminateCollectionToArray", listOf(intFqn, arrayFqn))
                    to DotNetIlTerminateCollectionToArrayIntrinsic
        )
    }

    /** Array-backed Iterable views are ordinary target-stdlib objects, not BCL adapters. */
    private fun arrayAsIterableIntrinsics(): List<Pair<Key, DotNetIlIntrinsicMethod>> = buildList {
        for (info in primitiveArrays) {
            add(
                Key(kotlinCollectionsFqn, info.arrayFqn, "asIterable", emptyList())
                        to DotNetIlArrayAsIterableIntrinsic(info.arrayType)
            )
        }
        add(
            Key(kotlinCollectionsFqn, arrayFqn, "asIterable", emptyList())
                    to DotNetIlArrayAsIterableIntrinsic(fixedArrayType = null)
        )
    }

    /** Physical calls to executable Kotlin.Stdlib functions, never inline reimplementations. */
    private fun stdlibFunctionIntrinsics(): List<Pair<Key, DotNetIlIntrinsicMethod>> = listOf(
        Key(kotlinCollectionsFqn, iterableFqn, "first", emptyList())
                to DotNetIlStdlibCollectionElementIntrinsic("first", DotNetRuntimeTypes.iterableType),
        Key(kotlinCollectionsFqn, iterableFqn, "last", emptyList())
                to DotNetIlStdlibCollectionElementIntrinsic("last", DotNetRuntimeTypes.iterableType),
        Key(kotlinCollectionsFqn, listFqn, "first", emptyList())
                to DotNetIlStdlibCollectionElementIntrinsic("first", DotNetRuntimeTypes.listType),
        Key(kotlinCollectionsFqn, listFqn, "last", emptyList())
                to DotNetIlStdlibCollectionElementIntrinsic("last", DotNetRuntimeTypes.listType),
    )

    /** Kotlin-owned array content operations; CLR vector identity/collection helpers are not used. */
    private fun arrayContentIntrinsics(): List<Pair<Key, DotNetIlIntrinsicMethod>> = buildList {
        for (info in primitiveArrays) {
            add(
                Key(kotlinCollectionsFqn, info.arrayFqn, "contentEquals", listOf(info.arrayFqn))
                        to DotNetIlArrayContentEqualsIntrinsic(info.arrayType)
            )
            add(
                Key(kotlinCollectionsFqn, info.arrayFqn, "contentHashCode", emptyList())
                        to DotNetIlArrayContentHashCodeIntrinsic(info.arrayType)
            )
            add(
                Key(kotlinCollectionsFqn, info.arrayFqn, "contentToString", emptyList())
                        to DotNetIlArrayContentToStringIntrinsic(info.arrayType)
            )
        }
        add(
            Key(kotlinCollectionsFqn, arrayFqn, "contentEquals", listOf(arrayFqn))
                    to DotNetIlArrayContentEqualsIntrinsic(fixedArrayType = null)
        )
        add(
            Key(kotlinCollectionsFqn, arrayFqn, "contentDeepEquals", listOf(arrayFqn))
                    to DotNetIlArrayContentDeepEqualsIntrinsic
        )
        add(
            Key(kotlinCollectionsFqn, arrayFqn, "contentHashCode", emptyList())
                    to DotNetIlArrayContentHashCodeIntrinsic(fixedArrayType = null)
        )
        add(
            Key(kotlinCollectionsFqn, arrayFqn, "contentDeepHashCode", emptyList())
                    to DotNetIlArrayContentDeepHashCodeIntrinsic
        )
        add(
            Key(kotlinCollectionsFqn, arrayFqn, "contentToString", emptyList())
                    to DotNetIlArrayContentToStringIntrinsic(fixedArrayType = null)
        )
        add(
            Key(kotlinCollectionsFqn, arrayFqn, "contentDeepToString", emptyList())
                    to DotNetIlArrayContentDeepToStringIntrinsic
        )
    }

    /**
     * `Throwable.message`/`Throwable.cause` on every [mapped exception type][DotNetMappedExceptions],
     * compiled to `System.Exception::get_Message()`/`get_InnerException()` (both callvirt
     * signatures ilasm-probe-verified). A key is registered per mapped FqName for direct and
     * stdlib-owned call-site owners. Source-defined subclasses need the structural fake-override
     * fallback in [getIntrinsic]: their inherited accessor is owned by the source class, whose
     * FqName cannot be registered in advance. Rejected exception types need no keys: any receiver
     * of such a type already fails signature mapping with the registry's per-type reason.
     */
    private fun exceptionMemberIntrinsics(): List<Pair<Key, DotNetIlIntrinsicMethod>> = buildList {
        for ([fqName, entry] in DotNetMappedExceptions.entries) {
            if (entry !is DotNetMappedExceptions.Entry.Mapped) continue
            add(Key(fqName, null, "<get-message>", emptyList()) to DotNetIlExceptionMessageIntrinsic)
            add(Key(fqName, null, "<get-cause>", emptyList()) to DotNetIlExceptionCauseIntrinsic)
        }
    }

    /**
     * `<`, `<=`, `>`, `>=` over the landed numeric scalars and Char. fir2ir converts `a < b` and friends
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
            irBuiltIns.byteClass to DotNetIlValueType.Int8,
            irBuiltIns.shortClass to DotNetIlValueType.Int16,
            irBuiltIns.intClass to DotNetIlValueType.Int32,
            irBuiltIns.longClass to DotNetIlValueType.Int64,
            irBuiltIns.floatClass to DotNetIlValueType.Float32,
            irBuiltIns.doubleClass to DotNetIlValueType.Float64,
            irBuiltIns.charClass to DotNetIlValueType.Char,
        )
        for ([classSymbol, operandType] in comparableTypes) {
            val isFloatingPoint = operandType == DotNetIlValueType.Float32 ||
                    operandType == DotNetIlValueType.Float64
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

    /** Boolean's eager Common operators, matching the JVM primitive `and`/`or`/`xor` registry. */
    private fun booleanOperatorIntrinsics(): List<Pair<Key, DotNetIlIntrinsicMethod>> =
        listOf("and", "or", "xor").map { name ->
            Key(booleanFqn, null, name, listOf(booleanFqn)) to
                    DotNetIlBooleanBinaryOperatorIntrinsic(name)
        }

    /**
     * Member operators of the landed numeric scalars including all mixed-type overloads
     * (`Int.plus(Long): Long` etc.), following the JVM backend's
     * `binaryFunForPrimitivesAcrossPrimitives` loop. Operands are widened to the promoted
     * computation type by the emitting intrinsic (see [emitWidenedOperand]).
     */
    private fun numericOperatorIntrinsics(): List<Pair<Key, DotNetIlIntrinsicMethod>> = buildList {
        for ([receiverFqn, receiverType] in numericTypes) {
            for ([argumentFqn, argumentType] in numericTypes) {
                val resultType = promoteNumeric(receiverType, argumentType)
                add(
                    Key(receiverFqn, null, "compareTo", listOf(argumentFqn))
                            to DotNetIlNumericCompareToIntrinsic(receiverType, argumentType, resultType)
                )
                for ([name, instruction] in listOf("plus" to "add", "minus" to "sub", "times" to "mul")) {
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
            val unaryResultType = if (
                receiverType == DotNetIlValueType.Int8 || receiverType == DotNetIlValueType.Int16
            ) DotNetIlValueType.Int32 else receiverType
            add(
                Key(receiverFqn, null, "unaryMinus", emptyList())
                        to DotNetIlNumericUnaryOperatorIntrinsic("neg", receiverType, unaryResultType)
            )
            add(
                Key(receiverFqn, null, "unaryPlus", emptyList())
                        to DotNetIlNumericUnaryOperatorIntrinsic(null, receiverType, unaryResultType)
            )
            add(Key(receiverFqn, null, "inc", emptyList()) to DotNetIlNumericIncrementIntrinsic("add", receiverType))
            add(Key(receiverFqn, null, "dec", emptyList()) to DotNetIlNumericIncrementIntrinsic("sub", receiverType))
        }
        for (integralEntry in listOf(
            intFqn to DotNetIlValueType.Int32,
            longFqn to DotNetIlValueType.Int64,
        )) {
            val integralFqn = integralEntry.first
            val integralType = integralEntry.second
            for (operatorEntry in listOf("and" to "and", "or" to "or", "xor" to "xor")) {
                val name = operatorEntry.first
                val instruction = operatorEntry.second
                add(
                    Key(integralFqn, null, name, listOf(integralFqn)) to
                            DotNetIlNumericBinaryOperatorIntrinsic(
                                instruction,
                                integralType,
                                integralType,
                                integralType,
                            )
                )
            }
            add(
                Key(integralFqn, null, "inv", emptyList()) to
                        DotNetIlNumericUnaryOperatorIntrinsic("not", integralType, integralType)
            )
            for (operatorEntry in listOf("shl" to "shl", "shr" to "shr", "ushr" to "shr.un")) {
                val name = operatorEntry.first
                val instruction = operatorEntry.second
                add(
                    Key(integralFqn, null, name, listOf(intFqn)) to
                            DotNetIlIntegralShiftIntrinsic(instruction, integralType)
                )
            }
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

    /** Materializes the signed primitive ranges whose loop-only form uses the shared lowering. */
    private fun primitiveRangeToIntrinsics(): List<Pair<Key, DotNetIlIntrinsicMethod>> = buildList {
        val integralTypes = listOf(
            byteFqn to DotNetIlValueType.Int8,
            shortFqn to DotNetIlValueType.Int16,
            intFqn to DotNetIlValueType.Int32,
            longFqn to DotNetIlValueType.Int64,
        )
        for ([receiverFqName, receiverType] in integralTypes) {
            for ([argumentFqName, argumentType] in integralTypes) {
                val elementType = if (
                    receiverType == DotNetIlValueType.Int64 || argumentType == DotNetIlValueType.Int64
                ) {
                    DotNetIlValueType.Int64
                } else {
                    DotNetIlValueType.Int32
                }
                val rangeClassName = if (elementType == DotNetIlValueType.Int64) {
                    "Kotlin.Ranges.LongRange"
                } else {
                    "Kotlin.Ranges.IntRange"
                }
                add(
                    Key(receiverFqName, null, "rangeTo", listOf(argumentFqName)) to
                            DotNetIlPrimitiveRangeToIntrinsic(
                                receiverType,
                                argumentType,
                                elementType,
                                rangeClassName,
                            )
                )
            }
        }
        add(
            Key(charFqn, null, "rangeTo", listOf(charFqn)) to
                    DotNetIlPrimitiveRangeToIntrinsic(
                        DotNetIlValueType.Char,
                        DotNetIlValueType.Char,
                        DotNetIlValueType.Char,
                        "Kotlin.Ranges.CharRange",
                    )
        )
    }

    /**
     * `to<Type>()` conversions between the supported primitives, following the JVM backend's
     * `numberConversionMethods`/`NumberCast` (JVM registers every `NUMBER_CONVERSIONS` name on
     * every number type; here only conversions between supported types are registered). The
     * deprecated signed-number `toChar()` declarations remain physical built-in members and JVM
     * reflection can invoke them, so their historic sign-extension/truncation semantics remain
     * executable even though current Kotlin source cannot call most of them directly.
     *
     * `Char.code` is `Char.toInt()` under an extension-property hat. The target admits the exact
     * Common `@InlineOnly` declaration and its assembly-visible physical getter, while this
     * intrinsic keeps ordinary Kotlin call sites on the direct conversion path.
     */
    private fun conversionIntrinsics(): List<Pair<Key, DotNetIlIntrinsicMethod>> = buildList {
        val conversionNamesToTargets = listOf(
            "toByte" to DotNetIlValueType.Int8,
            "toShort" to DotNetIlValueType.Int16,
            "toInt" to DotNetIlValueType.Int32,
            "toLong" to DotNetIlValueType.Int64,
            "toFloat" to DotNetIlValueType.Float32,
            "toDouble" to DotNetIlValueType.Float64,
        )
        val sourceTypes = numericTypes + (charFqn to DotNetIlValueType.Char)
        for ([fromFqn, fromType] in sourceTypes) {
            for ([name, toType] in conversionNamesToTargets) {
                add(Key(fromFqn, null, name, emptyList()) to conversionIntrinsicFor(fromType, toType))
            }
        }
        add(
            Key(byteFqn, null, "toChar", emptyList())
                    to DotNetIlNumberConversionIntrinsic(
                        DotNetIlValueType.Int8, DotNetIlValueType.Char, listOf("conv.u2"),
                    )
        )
        add(
            Key(shortFqn, null, "toChar", emptyList())
                    to DotNetIlNumberConversionIntrinsic(
                        DotNetIlValueType.Int16, DotNetIlValueType.Char, listOf("conv.u2"),
                    )
        )
        add(
            Key(intFqn, null, "toChar", emptyList())
                    to DotNetIlNumberConversionIntrinsic(
                        DotNetIlValueType.Int32, DotNetIlValueType.Char, listOf("conv.u2"),
                    )
        )
        add(
            Key(longFqn, null, "toChar", emptyList())
                    to DotNetIlNumberConversionIntrinsic(
                        DotNetIlValueType.Int64, DotNetIlValueType.Char, listOf("conv.u2"),
                    )
        )
        add(
            Key(floatFqn, null, "toChar", emptyList())
                    to DotNetIlFloatingToIntegralIntrinsic(
                        DotNetIlValueType.Float32,
                        DotNetIlValueType.Int32,
                        DotNetIlValueType.Char,
                        listOf("conv.u2"),
                    )
        )
        add(
            Key(doubleFqn, null, "toChar", emptyList())
                    to DotNetIlFloatingToIntegralIntrinsic(
                        DotNetIlValueType.Float64,
                        DotNetIlValueType.Int32,
                        DotNetIlValueType.Char,
                        listOf("conv.u2"),
                    )
        )
        add(
            Key(charFqn, null, "toChar", emptyList())
                    to DotNetIlNumberConversionIntrinsic(DotNetIlValueType.Char, DotNetIlValueType.Char, emptyList())
        )
        add(
            // The exact Common InlineOnly declaration is emitted in Kotlin.StandardKt for the
            // selected physical ABI. Ordinary call sites still use the direct conversion path.
            Key(kotlinFqn, charFqn, "<get-code>", emptyList())
                    to DotNetIlNumberConversionIntrinsic(
                DotNetIlValueType.Char, DotNetIlValueType.Int32, emptyList(),
            )
        )
    }

    private fun conversionIntrinsicFor(fromType: DotNetIlValueType, toType: DotNetIlValueType): DotNetIlIntrinsicMethod {
        val instructions = when {
            // Identity conversions (`Int.toInt()` etc.) and Char -> Int (the char is already a
            // zero-extended int32 on the evaluation stack, like on the JVM).
            fromType == toType || (fromType == DotNetIlValueType.Char && toType == DotNetIlValueType.Int32) -> emptyList()
            fromType in setOf(DotNetIlValueType.Float32, DotNetIlValueType.Float64) &&
                    toType in setOf(
                        DotNetIlValueType.Int8,
                        DotNetIlValueType.Int16,
                        DotNetIlValueType.Int32,
                        DotNetIlValueType.Int64,
                    ) -> return DotNetIlFloatingToIntegralIntrinsic(fromType, toType)
            toType == DotNetIlValueType.Int8 -> listOf("conv.i1")
            toType == DotNetIlValueType.Int16 -> listOf("conv.i2")
            toType == DotNetIlValueType.Int64 -> listOf("conv.i8")
            toType == DotNetIlValueType.Float32 -> listOf("conv.r4")
            toType == DotNetIlValueType.Float64 -> listOf("conv.r8")
            toType == DotNetIlValueType.Int32 && fromType == DotNetIlValueType.Int64 -> listOf("conv.i4")
            toType == DotNetIlValueType.Int32 &&
                    (fromType == DotNetIlValueType.Int8 || fromType == DotNetIlValueType.Int16) -> emptyList()
            else -> error("Internal .NET backend error: no conversion from $fromType to $toType")
        }
        return DotNetIlNumberConversionIntrinsic(fromType, toType, instructions)
    }

    private val intrinsicsMap = hashMapOf<String, MutableMap<FqName?, MutableMap<Key, DotNetIlIntrinsicMethod>>>()

    init {
        @Suppress("ReplacePutWithAssignment")
        for ([key, intrinsic] in intrinsics) {
            intrinsicsMap.getOrPut(key.name) { hashMapOf() }
                .getOrPut(key.receiverParameterTypeName) { hashMapOf() }
                .put(key, intrinsic)
        }
    }

    fun getIntrinsic(symbol: IrFunctionSymbol): DotNetIlIntrinsicMethod? {
        val function = symbol.owner
        val name = function.name.asString()
        val registered = intrinsicsMap[name]?.let { byReceiverName ->
            val receiverFqName = function.computeExtensionReceiverFqName()
            val ownerFqName = function.computeOwnerFqName() ?: return@let null
            byReceiverName[receiverFqName]
                ?.get(Key(ownerFqName, receiverFqName, name, function.computeValueParameterFqNames()))
        }
        if (registered != null) return registered

        // Common Number is an abstract class, but CLR has no physical base shared by its six
        // built-in boxes and Kotlin-written subclasses. Calls whose declaration is the Common
        // slot therefore cross the one classified object boundary. Calls to a user override are
        // deliberately left to ordinary CLR virtual dispatch.
        (function as? IrSimpleFunction)?.dotNetNumberIntrinsicOrNull()?.let { return it }

        // Comparable is physically backed by the BCL interfaces, but CLR String and floating
        // comparison do not uniformly implement Kotlin ordering. Calls through the logical
        // interface (including a type-parameter fake override) therefore share one semantic
        // object boundary. Exact user implementations still call their own virtual member.
        (function as? IrSimpleFunction)?.dotNetComparableIntrinsicOrNull()?.let { return it }

        // The logical CharSequence members are also declared/overridden on String, but sealed
        // System.String cannot implement the runtime capability interface. Match those two
        // builtin owners structurally and send both through the one classified operation
        // boundary. User implementations keep ordinary direct calls on their concrete type.
        (function as? IrSimpleFunction)?.dotNetCharSequenceIntrinsicOrNull()?.let { return it }

        // A source-defined exception subclass owns fake overrides of Throwable.message/cause,
        // so its FqName cannot appear in the static registry. Resolve only inherited standard
        // exception slots to System.Exception. A real user override in the chain deliberately
        // rejects this fallback and retains ordinary Kotlin virtual dispatch.
        (function as? IrSimpleFunction)
            ?.dotNetInheritedExceptionMemberIntrinsicOrNull()
            ?.let { return it }

        // Calls through fake overrides and user overrides are owned by that class rather than by
        // kotlin.Any, so an exact registry key cannot name them. The JVM resolves those calls to
        // java.lang.Object slots; select the CLR counterpart from the transitive override chain.
        when ((function as? IrSimpleFunction)?.dotNetAnyMethodOrNull()) {
            DotNetAnyMethod.EQUALS -> DotNetIlAnyEqualsIntrinsic
            DotNetAnyMethod.HASH_CODE -> DotNetIlAnyHashCodeIntrinsic
            DotNetAnyMethod.TO_STRING -> DotNetIlAnyToStringIntrinsic
            null -> null
        }?.let { return it }

        return null
    }

    data class Key(
        val owner: FqName,
        val receiverParameterTypeName: FqName?,
        val name: String,
        val valueParameterTypeNames: List<FqName?>,
    )
}

private fun IrSimpleFunction.dotNetNumberIntrinsicOrNull(): DotNetIlIntrinsicMethod? {
    val owner = parent as? IrClass ?: return null
    if (!owner.isDotNetNumberClass()) return null
    return when (name.asString()) {
        "toByte" -> DotNetIlNumberCarrierConversionIntrinsic("NumberToByte", DotNetIlValueType.Int8)
        "toShort" -> DotNetIlNumberCarrierConversionIntrinsic("NumberToShort", DotNetIlValueType.Int16)
        "toInt" -> DotNetIlNumberCarrierConversionIntrinsic("NumberToInt", DotNetIlValueType.Int32)
        "toLong" -> DotNetIlNumberCarrierConversionIntrinsic("NumberToLong", DotNetIlValueType.Int64)
        "toFloat" -> DotNetIlNumberCarrierConversionIntrinsic("NumberToFloat", DotNetIlValueType.Float32)
        "toDouble" -> DotNetIlNumberCarrierConversionIntrinsic("NumberToDouble", DotNetIlValueType.Float64)
        "toChar" -> DotNetIlNumberCarrierConversionIntrinsic("NumberToChar", DotNetIlValueType.Char)
        else -> null
    }
}

private fun IrSimpleFunction.dotNetInheritedExceptionMemberIntrinsicOrNull(): DotNetIlIntrinsicMethod? {
    if (
        !isFakeOverride ||
        parameters.firstOrNull()?.kind != IrParameterKind.DispatchReceiver ||
        parameters.size != 1
    ) {
        return null
    }
    val intrinsic = when (name.asString()) {
        "<get-message>" -> DotNetIlExceptionMessageIntrinsic
        "<get-cause>" -> DotNetIlExceptionCauseIntrinsic
        else -> return null
    }
    val terminalOverrides = allOverridden().filterNot { overridden -> overridden.isFakeOverride }.toList()
    val inheritsThrowableSlot = terminalOverrides.any { overridden ->
        (overridden.parent as? IrClass)?.fqNameWhenAvailable == StandardNames.FqNames.throwable
    }
    val hasOnlyStandardExceptionSlots = terminalOverrides.all { overridden ->
        val ownerFqName = (overridden.parent as? IrClass)?.fqNameWhenAvailable
        ownerFqName != null &&
                DotNetMappedExceptions.entries[ownerFqName] is DotNetMappedExceptions.Entry.Mapped
    }
    return intrinsic.takeIf { inheritsThrowableSlot && hasOnlyStandardExceptionSlots }
}

private fun IrSimpleFunction.dotNetComparableIntrinsicOrNull(): DotNetIlIntrinsicMethod? {
    if (name.asString() != "compareTo") return null
    val owner = parent as? IrClass ?: return null
    val ownerFqName = owner.fqNameWhenAvailable?.asString()
    val isBuiltinCarrier = ownerFqName in setOf(
        "kotlin.Boolean",
        "kotlin.Byte",
        "kotlin.Short",
        "kotlin.Int",
        "kotlin.Long",
        "kotlin.Float",
        "kotlin.Double",
        "kotlin.Char",
        "kotlin.String",
    )
    val isComparableSlot = owner.isDotNetComparableClass() ||
            isFakeOverride && allOverridden().any { overridden ->
                (overridden.parent as? IrClass)?.isDotNetComparableClass() == true
            }
    return DotNetIlComparableCompareToIntrinsic.takeIf { isBuiltinCarrier || isComparableSlot }
}

private fun IrSimpleFunction.dotNetCharSequenceIntrinsicOrNull(): DotNetIlIntrinsicMethod? {
    val ownerFqName = (parent as? IrClass)?.fqNameWhenAvailable ?: return null
    if (ownerFqName != StandardNames.FqNames.charSequence.toSafe() &&
        ownerFqName != StandardNames.FqNames.string.toSafe()
    ) {
        return null
    }
    val propertyName = correspondingPropertySymbol?.owner?.name?.asString()
    return when {
        propertyName == "length" -> DotNetIlCharSequenceLengthIntrinsic
        name.asString() == "get" -> DotNetIlCharSequenceGetIntrinsic
        name.asString() == "subSequence" -> DotNetIlCharSequenceSubSequenceIntrinsic
        else -> null
    }
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

    /** The CLR stack type produced when it is more exact than the unsubstituted IR result. */
    open fun naturalReturnType(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
    ): DotNetIlValueType? = null

    open fun tryEmitConstructorAsExpression(
        call: IrConstructorCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean = false

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

/**
 * The only mutable transition primitive of Common `SafeContinuation`. The Kotlin declaration is
 * deliberately a non-executable placeholder: .NET lowers it directly to the CLR's atomic
 * object-reference compare/exchange, while the actual state protocol remains readable Common-
 * shaped Kotlin code in the stdlib source shard.
 */
private object DotNetIlSafeContinuationCompareAndSetIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Boolean || call.arguments.size != 3) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing SafeContinuation receiver for compareAndSetResult")
        val expected = call.arguments[1]
            ?: dotNetUnsupported("missing expected SafeContinuation state")
        val update = call.arguments[2]
            ?: dotNetUnsupported("missing replacement SafeContinuation state")
        val owner = call.symbol.owner.parent as? IrClass
            ?: dotNetUnsupported("SafeContinuation compareAndSetResult has no class owner")
        val resultField = owner.declarations
            .filterIsInstance<IrProperty>()
            .singleOrNull { property -> property.name.asString() == "result" }
            ?.backingField
            ?: dotNetUnsupported("SafeContinuation compareAndSetResult cannot find its result field")
        codegen.emitObjectFieldCompareExchange(receiver, resultField, expected, update)
        return true
    }
}

private fun IrCall.dataClassArrayArgument(
    codegen: DotNetIlExpressionCodegen,
    memberName: String,
): Pair<IrExpression, DotNetIlValueType> {
    if (arguments.size != 1) {
        dotNetUnsupported("data-class array $memberName intrinsic requires exactly one argument")
    }
    val argument = arguments.single()
        ?: dotNetUnsupported("missing array argument of data-class '$memberName'")
    val argumentType = codegen.toDotNetIlValueType(argument.type)
    if (argumentType !is DotNetIlValueType.PrimitiveArray &&
        argumentType !is DotNetIlValueType.GenericArray
    ) {
        dotNetUnsupported(
            "data-class array $memberName has unsupported argument type ${argument.type.render()}"
        )
    }
    return argument to argumentType
}

/** Emits the physical `System.Array` storage consumed by shared runtime array algorithms. */
private fun DotNetIlExpressionCodegen.emitSystemArrayStorage(
    expression: IrExpression,
    arrayType: DotNetIlValueType,
) {
    emitExpression(expression, arrayType)
    if (arrayType is DotNetIlValueType.PrimitiveArray) {
        emit(
            DotNetPrimitiveArrays.getStorageFromObjectCallInstruction(coreLibraryReference),
            pops = 1,
            pushes = 1,
        )
    }
}

/** JVM `Arrays.hashCode` semantics for the array-member builtin emitted into data classes. */
private object DotNetIlDataClassArrayMemberHashCodeIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Int32) return false
        val [argument, argumentType] = call.dataClassArrayArgument(codegen, "hashCode")
        codegen.emitSystemArrayStorage(argument, argumentType)
        codegen.emit(
            DotNetRuntimeLibraryHelpers.dataClassArrayHashCodeCallInstruction(codegen.coreLibraryReference),
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/** JVM `Arrays.toString` semantics for the array-member builtin emitted into data classes. */
private object DotNetIlDataClassArrayMemberToStringIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.String) return false
        val [argument, argumentType] = call.dataClassArrayArgument(codegen, "toString")
        codegen.emitSystemArrayStorage(argument, argumentType)
        codegen.emit(
            DotNetRuntimeLibraryHelpers.dataClassArrayToStringCallInstruction(codegen.coreLibraryReference),
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/** Emits a dynamic-size vector allocation with the Kotlin negative-size exception boundary. */
private fun emitGuardedArrayAllocation(
    size: IrExpression,
    newArrayInstruction: String,
    codegen: DotNetIlExpressionCodegen,
) {
    // CLR newarr(-1) throws OverflowException, which is an ArithmeticException. Kotlin's
    // negative-array-size failure is a RuntimeException instead, so branch first and construct
    // the exact compiler-owned identity. Keep the size on the stack across the test so the
    // non-negative path feeds newarr without a synthetic local.
    codegen.emitExpression(size, DotNetIlValueType.Int32)
    emitGuardedArrayAllocationFromStack(newArrayInstruction, codegen)
}

/** The stack-input form shared by constructors and `copyOf(newSize)`. */
private fun emitGuardedArrayAllocationFromStack(
    newArrayInstruction: String,
    codegen: DotNetIlExpressionCodegen,
) {
    val nonNegativeLabel = codegen.nextLabel("arraySizeNonNegative")
    codegen.emit("dup", pops = 1, pushes = 2)
    codegen.emit("ldc.i4.0", pushes = 1)
    codegen.emit("clt", pops = 2, pushes = 1)
    codegen.emitBranch("brfalse", nonNegativeLabel, pops = 1)
    codegen.emit("pop", pops = 1)
    codegen.emitParameterlessExceptionThrow(
        exceptionTypeRef = DotNetRuntimeLibrary.negativeArraySizeExceptionTypeRef,
        valuePosition = true,
    )
    codegen.emitLabel(nonNegativeLabel)
    codegen.emit(newArrayInstruction, pops = 1, pushes = 1)
}

/** `IntArray(size)` and peers -> guarded vector storage wrapped in the canonical runtime type. */
private class DotNetIlPrimitiveArrayConstructorIntrinsic(
    private val arrayType: DotNetIlValueType.PrimitiveArray,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitConstructorAsExpression(
        call: IrConstructorCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != arrayType || call.arguments.size != 1) return false
        val size = call.arguments.single()
            ?: dotNetUnsupported("missing primitive-array size")
        emitGuardedArrayAllocation(size, arrayType.newStorageInstruction, codegen)
        codegen.emit(arrayType.wrapStorageInstruction, pops = 1, pushes = 1)
        return true
    }
}

/** Shared literal-vector emission for primitive `*ArrayOf` and generic `arrayOf`. */
private fun emitArrayLiteral(
    call: IrCall,
    codegen: DotNetIlExpressionCodegen,
    expectedType: DotNetIlValueType,
    arrayType: DotNetIlValueType,
    storageType: DotNetIlValueType,
    elementType: DotNetIlValueType,
    newArrayInstruction: String,
    storeElementInstruction: String,
    functionName: String,
    wrapStorageInstruction: String? = null,
): Boolean {
    if (expectedType != arrayType || call.arguments.size != 1) return false
    val vararg = call.arguments.single()
    val varargElements = when (vararg) {
        null -> emptyList()
        is IrVararg -> vararg.elements
        else -> {
            // General vararg lowering has already materialized spread-bearing calls as a fresh
            // vector. Keep the builtin `arrayOf`/`*ArrayOf` call as an identity boundary so the
            // optimized no-spread literal path remains unchanged.
            codegen.emitExpression(vararg, arrayType)
            return true
        }
    }
    val elements = varargElements.mapIndexed { index, element ->
        when (element) {
            is IrSpreadElement -> dotNetUnsupported(
                "spread element at index $index in $functionName is not supported yet"
            )
            is IrExpression -> element
            else -> error("Internal .NET backend error: unknown IrVarargElement ${element.javaClass.simpleName}")
        }
    }

    emitArrayLiteralElements(
        elements,
        codegen,
        storageType,
        elementType,
        newArrayInstruction,
        storeElementInstruction,
        wrapStorageInstruction,
    )
    return true
}

/** One physical vector-literal path for builtin calls and residual Common [IrVararg] nodes. */
internal fun emitArrayLiteralElements(
    elements: List<IrExpression>,
    codegen: DotNetIlExpressionCodegen,
    storageType: DotNetIlValueType,
    elementType: DotNetIlValueType,
    newArrayInstruction: String,
    storeElementInstruction: String,
    wrapStorageInstruction: String? = null,
) {
    codegen.emit("ldc.i4 ${elements.size}", pushes = 1)
    codegen.emit(newArrayInstruction, pops = 1, pushes = 1)
    val arraySlot = codegen.spillToSyntheticLocal(storageType, "<arrayOfStorage>")
    var elementSlot: DotNetIlSlot.Local? = null
    for ([index, element] in elements.withIndex()) {
        // Evaluate each element with an otherwise empty stack. A supported expression may
        // itself contain a CLR protected region (`try`), whose entry requires stack depth
        // zero; keeping array/index operands below it would produce invalid IL. The vector
        // was allocated first, and the reused value temp preserves source evaluation order.
        codegen.emitExpression(element, elementType)
        val storedElementSlot = elementSlot?.also { slot ->
            codegen.emit(storeLocalInstruction(slot.index), pops = 1)
        } ?: codegen.spillToSyntheticLocal(elementType, "<arrayElement>")
        elementSlot = storedElementSlot
        codegen.emit(loadLocalInstruction(arraySlot.index), pushes = 1)
        codegen.emit("ldc.i4 $index", pushes = 1)
        codegen.emit(loadLocalInstruction(storedElementSlot.index), pushes = 1)
        codegen.emit(storeElementInstruction, pops = 3)
    }
    codegen.emit(loadLocalInstruction(arraySlot.index), pushes = 1)
    wrapStorageInstruction?.let { codegen.emit(it, pops = 1, pushes = 1) }
}

/** Literal `intArrayOf(a, b)` and peers -> one vector plus ordered typed stores. */
private class DotNetIlPrimitiveArrayOfIntrinsic(
    private val arrayType: DotNetIlValueType.PrimitiveArray,
    private val functionName: String,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean = emitArrayLiteral(
        call, codegen, expectedType, arrayType, arrayType.storageType, arrayType.elementType,
        arrayType.newStorageInstruction, arrayType.storageType.storeElementInstruction, functionName,
        arrayType.wrapStorageInstruction,
    )
}

/** Primitive-array `size` through the wrapper's marked compiler ABI. */
private class DotNetIlPrimitiveArraySizeIntrinsic(
    private val arrayType: DotNetIlValueType.PrimitiveArray,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Int32 || call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing primitive-array receiver for 'size'")
        codegen.emitExpression(receiver, arrayType)
        codegen.emit(arrayType.sizeCallInstruction, pops = 1, pushes = 1)
        return true
    }
}

/** Primitive-array indexed read through the wrapper's marked compiler ABI. */
private class DotNetIlPrimitiveArrayGetIntrinsic(
    private val arrayType: DotNetIlValueType.PrimitiveArray,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != arrayType.elementType || call.arguments.size != 2) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing primitive-array receiver for 'get'")
        val index = call.arguments[1]
            ?: dotNetUnsupported("missing primitive-array index for 'get'")
        codegen.emitExpression(receiver, arrayType)
        val receiverSlot = codegen.spillToSyntheticLocal(arrayType, "<arrayGet>")
        codegen.emitExpression(index, DotNetIlValueType.Int32)
        val indexSlot = codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<arrayIndex>")
        codegen.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(indexSlot.index), pushes = 1)
        codegen.emit(arrayType.getCallInstruction, pops = 2, pushes = 1)
        return true
    }
}

/** Primitive-array indexed write through the wrapper's marked compiler ABI. */
private class DotNetIlPrimitiveArraySetIntrinsic(
    private val arrayType: DotNetIlValueType.PrimitiveArray,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsStatement(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
    ): Boolean {
        if (call.arguments.size != 3) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing primitive-array receiver for 'set'")
        val index = call.arguments[1]
            ?: dotNetUnsupported("missing primitive-array index for 'set'")
        val value = call.arguments[2]
            ?: dotNetUnsupported("missing primitive-array value for 'set'")
        codegen.emitExpression(receiver, arrayType)
        val receiverSlot = codegen.spillToSyntheticLocal(arrayType, "<arraySet>")
        codegen.emitExpression(index, DotNetIlValueType.Int32)
        val indexSlot = codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<arrayIndex>")
        codegen.emitExpression(value, arrayType.elementType)
        val valueSlot = codegen.spillToSyntheticLocal(arrayType.elementType, "<arrayValue>")
        codegen.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(indexSlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(valueSlot.index), pushes = 1)
        codegen.emit(arrayType.setCallInstruction, pops = 3)
        return true
    }
}

/** Generic `arrayOf(a, b)` -> one reified vector plus ordered typed stores. */
private object DotNetIlGenericArrayOfIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        val arrayType = expectedType as? DotNetIlValueType.GenericArray ?: return false
        return emitArrayLiteral(
            call, codegen, expectedType, arrayType, arrayType, arrayType.elementType,
            arrayType.newArrayInstruction, arrayType.storeElementInstruction, "arrayOf",
        )
    }
}

/** Reified `emptyArray<E>()` -> `ldc.i4.0; newarr E`. */
private object DotNetIlGenericEmptyArrayIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        val arrayType = expectedType as? DotNetIlValueType.GenericArray ?: return false
        if (call.arguments.isNotEmpty()) return false
        codegen.emit("ldc.i4.0", pushes = 1)
        codegen.emit(arrayType.newArrayInstruction, pops = 1, pushes = 1)
        return true
    }
}

/**
 * `arrayOfNulls<E>(size)` or the array-initializer lowering's private zeroed buffer -> a guarded,
 * zero-initialized reified vector. A concrete nullable primitive element uses the exact closed
 * `Nullable<V>[]` carrier; open `T?` remains rejected by the type mapper. `Array<Int>(size) { ... }`
 * likewise uses this builtin as an unobservable fully-filled `int32[]` allocation buffer.
 */
private object DotNetIlGenericArrayOfNullsIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        val arrayType = expectedType as? DotNetIlValueType.GenericArray ?: return false
        if (call.arguments.size != 1) return false
        val size = call.arguments.single() ?: dotNetUnsupported("missing generic-array size")
        emitGuardedArrayAllocation(size, arrayType.newArrayInstruction, codegen)
        return true
    }
}

/**
 * Returns the original projected vector when it is physically compatible with the method's exact
 * `T[]`, or null otherwise. The latter case is required for Kotlin-valid widened value-array views
 * such as `Array<Int>` observed as `Array<out Any?>`; it must retain the System.Array fallback.
 */
private object DotNetIlExactGenericArrayOrNullIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        val exactArrayType = expectedType as? DotNetIlValueType.GenericArray ?: return false
        if (call.arguments.size != 1) return false
        val array = call.arguments.single()
            ?: dotNetUnsupported("missing projected array for 'dotNetExactArrayOrNull'")
        val arrayType = codegen.toDotNetIlValueType(array.type)
        if (arrayType !is DotNetIlValueType.GenericArray &&
            arrayType !is DotNetIlValueType.ErasedGenericArray
        ) {
            dotNetUnsupported("exact-array probe has unsupported type ${array.type.render()}")
        }
        codegen.emitExpression(array, arrayType)
        codegen.emit("isinst ${exactArrayType.nameInSignature}", pops = 1, pushes = 1)
        return true
    }
}

/**
 * Allocates a zeroed vector with the supplied vector's exact runtime element type. A static
 * `newarr !!T` is insufficient across CLR reference-array covariance because the physical input
 * may be more specific than the Kotlin/CLR generic parameter visible at this call site.
 */
private object DotNetIlArrayOfNullsLikeIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType !is DotNetIlValueType.GenericArray &&
            expectedType !is DotNetIlValueType.ErasedGenericArray
        ) return false
        if (call.arguments.size != 2) return false
        val reference = call.arguments[0]
            ?: dotNetUnsupported("missing reference array for 'dotNetArrayOfNulls'")
        val referenceType = codegen.toDotNetIlValueType(reference.type)
        if (referenceType !is DotNetIlValueType.GenericArray &&
            referenceType !is DotNetIlValueType.ErasedGenericArray
        ) {
            dotNetUnsupported("array-of-nulls-like reference has unsupported type ${reference.type.render()}")
        }
        val size = call.arguments[1]
            ?: dotNetUnsupported("missing size for 'dotNetArrayOfNulls'")

        codegen.emitExpression(reference, referenceType)
        val referenceSlot = codegen.spillToSyntheticLocal(referenceType, "<arrayTypeReference>")
        codegen.emitExpression(size, DotNetIlValueType.Int32)
        val sizeSlot = codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<arrayTypeSize>")

        val nonNegativeLabel = codegen.nextLabel("arrayTypeSizeNonNegative")
        codegen.emit(loadLocalInstruction(sizeSlot.index), pushes = 1)
        codegen.emit("ldc.i4.0", pushes = 1)
        codegen.emit("clt", pops = 2, pushes = 1)
        codegen.emitBranch("brfalse", nonNegativeLabel, pops = 1)
        codegen.emitParameterlessExceptionThrow(
            exceptionTypeRef = DotNetRuntimeLibrary.negativeArraySizeExceptionTypeRef,
            valuePosition = false,
        )
        codegen.emitLabel(nonNegativeLabel)

        val coreLibraryReference = codegen.coreLibraryReference
        codegen.emit(loadLocalInstruction(referenceSlot.index), pushes = 1)
        codegen.emit(
            "call instance class ${coreLibraryReference}System.Type " +
                    "${coreLibraryReference}System.Object::GetType()",
            pops = 1,
            pushes = 1,
        )
        codegen.emit(
            "callvirt instance class ${coreLibraryReference}System.Type " +
                    "${coreLibraryReference}System.Type::GetElementType()",
            pops = 1,
            pushes = 1,
        )
        codegen.emit(loadLocalInstruction(sizeSlot.index), pushes = 1)
        codegen.emit(
            "call class ${coreLibraryReference}System.Array ${coreLibraryReference}" +
                    "System.Array::CreateInstance(class ${coreLibraryReference}System.Type, int32)",
            pops = 2,
            pushes = 1,
        )
        if (expectedType is DotNetIlValueType.GenericArray) {
            codegen.emit("castclass ${expectedType.nameInSignature}", pops = 1, pushes = 1)
        }
        return true
    }
}

/**
 * Writes one value through a projected `System.Array` carrier. Kotlin source correctly projects
 * `Array<*>` writes out, while this target-private stable-sort primitive has already retained each
 * value from that same array. `SetValue` preserves the runtime vector's component-type check for
 * both reference and value vectors without manufacturing an `object[]` or open `T[]` cast.
 */
private object DotNetIlErasedArraySetIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsStatement(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
    ): Boolean {
        if (call.arguments.size != 3) return false
        val array = call.arguments[0]
            ?: dotNetUnsupported("missing array for 'dotNetErasedArraySet'")
        val index = call.arguments[1]
            ?: dotNetUnsupported("missing index for 'dotNetErasedArraySet'")
        val value = call.arguments[2]
            ?: dotNetUnsupported("missing value for 'dotNetErasedArraySet'")
        val arrayType = codegen.toDotNetIlValueType(array.type) as? DotNetIlValueType.ErasedGenericArray
            ?: dotNetUnsupported(
                "'dotNetErasedArraySet' has unsupported array type ${array.type.render()}"
            )

        codegen.emitExpression(array, arrayType)
        val arraySlot = codegen.spillToSyntheticLocal(arrayType, "<erasedArraySet>")
        codegen.emitExpression(index, DotNetIlValueType.Int32)
        val indexSlot = codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<erasedArrayIndex>")
        codegen.emitExpression(value, DotNetIlValueType.Object)
        val valueSlot = codegen.spillToSyntheticLocal(DotNetIlValueType.Object, "<erasedArrayValue>")

        codegen.emit(loadLocalInstruction(arraySlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(valueSlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(indexSlot.index), pushes = 1)
        codegen.emit(
            "callvirt instance void ${codegen.coreLibraryReference}System.Array::SetValue(object, int32)",
            pops = 3,
        )
        return true
    }
}

private fun IrCall.genericArrayReceiver(
    codegen: DotNetIlExpressionCodegen,
    memberName: String,
): Pair<IrExpression, DotNetIlValueType> {
    val receiver = arguments.firstOrNull()
        ?: dotNetUnsupported("missing generic-array receiver for '$memberName'")
    val arrayType = codegen.genericArrayPhysicalType(receiver)
    if (arrayType !is DotNetIlValueType.GenericArray &&
        arrayType !is DotNetIlValueType.ErasedGenericArray
    ) {
        dotNetUnsupported("receiver of generic-array '$memberName' has unsupported type ${receiver.type.render()}")
    }
    return receiver to arrayType
}

/** Generic-array `size` -> `ldlen; conv.i4`. */
private object DotNetIlGenericArraySizeIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Int32 || call.arguments.size != 1) return false
        val [receiver, arrayType] = call.genericArrayReceiver(codegen, "size")
        codegen.emitExpression(receiver, arrayType)
        if (arrayType is DotNetIlValueType.ErasedGenericArray) {
            codegen.emit(
                "callvirt instance int32 ${codegen.coreLibraryReference}System.Array::get_Length()",
                pops = 1,
                pushes = 1,
            )
        } else {
            codegen.emit("ldlen", pops = 1, pushes = 1)
            codegen.emit("conv.i4", pops = 1, pushes = 1)
        }
        return true
    }
}

/** Generic-array indexed read -> `ldelem E`, including an open `!n`/`!!n` token. */
private object DotNetIlGenericArrayGetIntrinsic : DotNetIlIntrinsicMethod() {
    override fun naturalReturnType(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
    ): DotNetIlValueType? {
        if (call.arguments.size != 2) return null
        val receiver = call.arguments.firstOrNull() ?: return null
        return when (val arrayType = codegen.toDotNetIlValueType(receiver.type)) {
            is DotNetIlValueType.GenericArray -> when (val elementType = arrayType.elementType) {
                // Shared loop lowering can represent Array<*> reads through the Array
                // declaration's captured owner parameter. Its `!n` token belongs to Array,
                // not to the method currently being emitted, and is therefore not a legal
                // stack type here. The classified read is object. Method parameters (`!!n`)
                // and concrete exact vector elements do belong to the current signature.
                is DotNetIlValueType.TypeParameter ->
                    if (elementType.isMethodParameter) elementType else DotNetIlValueType.Object
                else -> elementType
            }
            is DotNetIlValueType.ErasedGenericArray ->
                // A concrete imported/use-site result (for example String) must fall through so
                // normal checked narrowing still runs. Only a captured Array-owner `!n`, which
                // cannot exist in this method's signature, is the fixed Array<*> object read.
                (codegen.toDotNetIlValueType(call.type) as? DotNetIlValueType.TypeParameter)
                    ?.takeUnless { it.isMethodParameter }
                    ?.let { DotNetIlValueType.Object }
            else -> null
        }
    }

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (call.arguments.size != 2) return false
        val [receiver, arrayType] = call.genericArrayReceiver(codegen, "get")
        val elementType = when (arrayType) {
            is DotNetIlValueType.GenericArray -> arrayType.elementType
            is DotNetIlValueType.ErasedGenericArray -> DotNetIlValueType.Object
            else -> error("Internal .NET backend error: non-array receiver ${arrayType.nameInSignature}")
        }
        val index = call.arguments[1]
            ?: dotNetUnsupported("missing generic-array index for 'get'")
        codegen.emitExpression(receiver, arrayType)
        val receiverSlot = codegen.spillToSyntheticLocal(arrayType, "<arrayGet>")
        codegen.emitExpression(index, DotNetIlValueType.Int32)
        val indexSlot = codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<arrayIndex>")
        codegen.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(indexSlot.index), pushes = 1)
        if (arrayType is DotNetIlValueType.ErasedGenericArray) {
            codegen.emit(
                "callvirt instance object ${codegen.coreLibraryReference}System.Array::GetValue(int32)",
                pops = 2,
                pushes = 1,
            )
            if (expectedType != DotNetIlValueType.Object) {
                val narrowing = expectedType.dotNetObjectNarrowingInstructionOrNull(codegen.coreLibraryReference)
                    ?: dotNetUnsupported(
                        "projected generic-array element cannot be recovered as ${expectedType.nameInSignature}"
                    )
                codegen.emit(narrowing, pops = 1, pushes = 1)
            }
        } else {
            codegen.emit((arrayType as DotNetIlValueType.GenericArray).loadElementInstruction, pops = 2, pushes = 1)
            if (!elementType.isDotNetAssignableTo(expectedType)) {
                val widening = dotNetWideningCoercionOrNull(
                    elementType,
                    expectedType,
                    codegen.coreLibraryReference,
                )
                if (widening != null) {
                    widening.instructions.forEach { instruction ->
                        codegen.emit(instruction, pops = 1, pushes = 1)
                    }
                } else {
                    // Projected and captured Array.get calls can expose two logically equivalent
                    // open type parameters that occupy different physical CLR slots. Kotlin's
                    // type checker already proved the result view. Route only that view change
                    // through object: reference instantiations preserve identity, value
                    // instantiations box and are immediately recovered, and malformed foreign
                    // input still fails at the checked cast/unbox boundary.
                    if (elementType != DotNetIlValueType.Object) {
                        val toObject = dotNetWideningCoercionOrNull(
                            elementType,
                            DotNetIlValueType.Object,
                            codegen.coreLibraryReference,
                        ) ?: dotNetUnsupported(
                            "generic-array element ${elementType.nameInSignature} cannot be viewed as " +
                                    expectedType.nameInSignature
                        )
                        toObject.instructions.forEach { instruction ->
                            codegen.emit(instruction, pops = 1, pushes = 1)
                        }
                    }
                    val narrowing = expectedType.dotNetObjectNarrowingInstructionOrNull(codegen.coreLibraryReference)
                        ?: dotNetUnsupported(
                            "generic-array element object cannot be recovered as ${expectedType.nameInSignature}"
                        )
                    codegen.emit(narrowing, pops = 1, pushes = 1)
                }
            }
        }
        return true
    }
}

/**
 * Generic-array indexed write. Exact vectors use `stelem E`; an owner-erased `Array<T>` uses
 * `System.Array.SetValue` and lets the original runtime vector enforce its component type.
 */
private object DotNetIlGenericArraySetIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsStatement(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
    ): Boolean {
        if (call.arguments.size != 3) return false
        val [receiver, arrayType] = call.genericArrayReceiver(codegen, "set")
        val index = call.arguments[1]
            ?: dotNetUnsupported("missing generic-array index for 'set'")
        val value = call.arguments[2]
            ?: dotNetUnsupported("missing generic-array value for 'set'")
        codegen.emitExpression(receiver, arrayType)
        val receiverSlot = codegen.spillToSyntheticLocal(arrayType, "<arraySet>")
        codegen.emitExpression(index, DotNetIlValueType.Int32)
        val indexSlot = codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<arrayIndex>")
        val elementType = when (arrayType) {
            is DotNetIlValueType.GenericArray -> arrayType.elementType
            is DotNetIlValueType.ErasedGenericArray -> {
                if (!codegen.permitsErasedGenericArrayElementWrite(receiver, value)) {
                    dotNetUnsupported("erased generic-array set has no source-legal write contract")
                }
                DotNetIlValueType.Object
            }
            else -> error("Internal .NET backend error: non-array receiver ${arrayType.nameInSignature}")
        }
        codegen.emitExpression(value, elementType)
        val valueSlot = codegen.spillToSyntheticLocal(elementType, "<arrayValue>")
        codegen.emit(loadLocalInstruction(receiverSlot.index), pushes = 1)
        if (arrayType is DotNetIlValueType.ErasedGenericArray) {
            // Preserve Kotlin's receiver/index/value evaluation order through the spills, then
            // reorder only the reloads to the CLR instance-method stack order.
            codegen.emit(loadLocalInstruction(valueSlot.index), pushes = 1)
            codegen.emit(loadLocalInstruction(indexSlot.index), pushes = 1)
            codegen.emit(
                "callvirt instance void ${codegen.coreLibraryReference}System.Array::SetValue(object, int32)",
                pops = 3,
            )
        } else {
            codegen.emit(loadLocalInstruction(indexSlot.index), pushes = 1)
            codegen.emit(loadLocalInstruction(valueSlot.index), pushes = 1)
            codegen.emit((arrayType as DotNetIlValueType.GenericArray).storeElementInstruction, pops = 3)
        }
        return true
    }
}

/**
 * An explicit array `iterator()` value -> the target-stdlib's compiler-facing iterator factory.
 *
 * The generic factory retains the vector element type (`int32`, `string`, `!n`/`!!n`) while
 * keeping the private implementation class and constructor out of the compiler/stdlib ABI.
 * Receiver mapping still rejects open nullable type parameters plus input/star projections before
 * this intrinsic runs, so accepting the producer does not broaden those array families.
 */
private class DotNetIlArrayIteratorIntrinsic(
    private val fixedArrayType: DotNetIlValueType?,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing array receiver for 'iterator'")
        val arrayType = fixedArrayType ?: codegen.toDotNetIlValueType(receiver.type)
            ?.takeIf {
                it is DotNetIlValueType.GenericArray || it is DotNetIlValueType.ErasedGenericArray
            }
            ?: dotNetUnsupported("'iterator' has unsupported array receiver ${receiver.type.render()}")
        if (arrayType is DotNetIlValueType.ErasedGenericArray) {
            if (expectedType != DotNetRuntimeTypes.iteratorType) {
                dotNetUnsupported(
                    "erased Array<*>.iterator produces ${DotNetRuntimeTypes.iteratorType.nameInSignature}, " +
                            "not ${expectedType.nameInSignature}"
                )
            }
            codegen.emitExpression(receiver, arrayType)
            codegen.stdlibAssemblyName?.let(codegen::recordAssemblyReference)
            codegen.emit(
                DotNetStdlibLibrary.erasedArrayIteratorFactoryCallInstruction(
                    codegen.coreLibraryReference,
                    codegen.stdlibAssemblyName,
                ),
                pops = 1,
                pushes = 1,
            )
            return true
        }
        if (arrayType is DotNetIlValueType.PrimitiveArray) {
            val expectedIteratorName =
                "Kotlin.Collections.${arrayType.abi.wrapperSimpleName.removeSuffix("Array")}Iterator"
            if ((expectedType as? DotNetIlValueType.UserClass)?.classInfo?.ilClassName != expectedIteratorName) {
                dotNetUnsupported(
                    "primitive ${arrayType.abi.wrapperSimpleName}.iterator produces $expectedIteratorName, " +
                            "not ${expectedType.nameInSignature}"
                )
            }
            codegen.emitExpression(receiver, arrayType)
            codegen.stdlibAssemblyName?.let(codegen::recordAssemblyReference)
            codegen.emit(
                DotNetStdlibLibrary.primitiveArrayIteratorFactoryCallInstruction(
                    arrayType,
                    expectedType,
                    codegen.stdlibAssemblyName,
                ),
                pops = 1,
                pushes = 1,
            )
            return true
        }
        if (expectedType != DotNetRuntimeTypes.iteratorType) return false
        val elementType = arrayType.elementTypeForArrayProducer("iterator")
        codegen.emitExpression(receiver, arrayType)
        codegen.stdlibAssemblyName?.let(codegen::recordAssemblyReference)
        codegen.emit(
            DotNetStdlibLibrary.arrayIteratorFactoryCallInstruction(elementType, codegen.stdlibAssemblyName),
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/** Array.asIterable -> the target-stdlib's compiler-facing Iterable factory. */
private class DotNetIlArrayAsIterableIntrinsic(
    private val fixedArrayType: DotNetIlValueType?,
) : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetRuntimeTypes.iterableType || call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing array receiver for 'asIterable'")
        val arrayType = fixedArrayType ?: codegen.toDotNetIlValueType(receiver.type)
            ?.takeIf {
                it is DotNetIlValueType.GenericArray || it is DotNetIlValueType.ErasedGenericArray
            }
            ?: dotNetUnsupported("'asIterable' has unsupported array receiver ${receiver.type.render()}")
        if (arrayType is DotNetIlValueType.ErasedGenericArray) {
            codegen.emitExpression(receiver, arrayType)
            codegen.stdlibAssemblyName?.let(codegen::recordAssemblyReference)
            codegen.emit(
                DotNetStdlibLibrary.erasedArrayIterableFactoryCallInstruction(
                    codegen.coreLibraryReference,
                    codegen.stdlibAssemblyName,
                ),
                pops = 1,
                pushes = 1,
            )
            return true
        }
        val elementType = arrayType.elementTypeForArrayProducer("asIterable")
        codegen.emitExpression(receiver, arrayType)
        if (arrayType is DotNetIlValueType.PrimitiveArray) {
            codegen.emit(arrayType.getStorageCallInstruction, pops = 1, pushes = 1)
        }
        codegen.stdlibAssemblyName?.let(codegen::recordAssemblyReference)
        codegen.emit(
            DotNetStdlibLibrary.arrayIterableFactoryCallInstruction(elementType, codegen.stdlibAssemblyName),
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/**
 * An `Iterable<T>/List<T> -> T` operation -> its ordinary generic implementation in Kotlin.Stdlib.
 *
 * This intrinsic selects a physical cross-assembly method reference only; it does not reproduce
 * the library algorithm in user code. The stdlib emitter deliberately omits this intrinsic and
 * compiles the injected Kotlin body onto the stable CollectionsKt facade.
 */
private class DotNetIlStdlibCollectionElementIntrinsic(
    private val functionName: String,
    private val receiverType: DotNetIlValueType,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (call.arguments.size != 1 || call.typeArguments.size != 1) return false
        val elementIrType = call.typeArguments.single()
            ?: dotNetUnsupported("'$functionName' has an unsupported missing type argument")
        val elementType = codegen.toDotNetIlValueType(elementIrType)
            ?: dotNetUnsupported("'$functionName' has unsupported element type ${elementIrType.render()}")
        if (expectedType != elementType) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing collection receiver for '$functionName'")
        codegen.emitExpression(receiver, receiverType)
        codegen.stdlibAssemblyName?.let(codegen::recordAssemblyReference)
        codegen.emit(
            DotNetStdlibLibrary.collectionElementFunctionCallInstruction(
                functionName,
                receiverType,
                elementType,
                codegen.stdlibAssemblyName,
            ),
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

private fun DotNetIlValueType.elementTypeForArrayProducer(operationName: String): DotNetIlValueType = when (this) {
    is DotNetIlValueType.PrimitiveArray -> elementType
    is DotNetIlValueType.GenericArray -> elementType
    else -> dotNetUnsupported("'$operationName' has unsupported CLR array representation $nameInSignature")
}

/** Kotlin Iterator.hasNext -> the erased Kotlin.Runtime execution slot. */
private object DotNetIlIteratorHasNextIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Boolean || call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing iterator receiver for 'hasNext'")
        codegen.emitExpression(receiver, DotNetRuntimeTypes.iteratorType)
        codegen.emit(
            "callvirt instance bool [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                    "${"Kotlin.Collections.Iterator".toIlIdentifier()}::${"HasNext".toIlIdentifier()}()",
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/** Kotlin Iterator.next/primitive nextX -> erased Next plus a logical result narrowing. */
private object DotNetIlIteratorNextIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing iterator receiver for 'next'")
        codegen.emitExpression(receiver, DotNetRuntimeTypes.iteratorType)
        codegen.emit(
            "callvirt instance object [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
                    "${"Kotlin.Collections.Iterator".toIlIdentifier()}::${"Next".toIlIdentifier()}()",
            pops = 1,
            pushes = 1,
        )
        if (expectedType != DotNetIlValueType.Object) {
            val narrowing = expectedType.dotNetObjectNarrowingInstructionOrNull(codegen.coreLibraryReference)
                ?: dotNetUnsupported(
                    "erased iterator result cannot be converted from object to ${expectedType.nameInSignature}"
                )
            codegen.emit(narrowing, pops = 1, pushes = 1)
        }
        return true
    }
}

/**
 * `copyInto` for primitive vectors and every admitted exact or erased generic-array carrier.
 *
 * Arguments are evaluated and spilled in Kotlin order before the helper call. Omitted external
 * defaults remain null in IR: the two zero defaults are materialized directly, while the default
 * `endIndex = size` reads the already-evaluated source once. The runtime helper owns validation
 * because raw `System.Array.Copy` exposes the wrong Kotlin exception category for destination
 * range failures; after validation it delegates to that overlap-safe CLR primitive.
 */
private class DotNetIlArrayCopyIntoIntrinsic(
    private val fixedArrayType: DotNetIlValueType?,
) : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (call.arguments.size != 5) return false
        val source = call.arguments[0]
            ?: dotNetUnsupported("missing array receiver for 'copyInto'")
        val destination = call.arguments[1]
            ?: dotNetUnsupported("missing destination array for 'copyInto'")
        fun mappedGenericArrayType(expression: IrExpression, role: String): DotNetIlValueType =
            codegen.toDotNetIlValueType(expression.type)?.takeIf { type ->
                type is DotNetIlValueType.GenericArray || type is DotNetIlValueType.ErasedGenericArray
            } ?: dotNetUnsupported("'copyInto' has unsupported $role type ${expression.type.render()}")

        val sourceType = fixedArrayType ?: mappedGenericArrayType(source, "source")
        val destinationType = fixedArrayType ?: mappedGenericArrayType(destination, "destination")
        if (expectedType != destinationType) return false

        codegen.emitExpression(source, sourceType)
        val sourceSlot = codegen.spillToSyntheticLocal(sourceType, "<copySource>")
        codegen.emitExpression(destination, destinationType)
        val destinationSlot = codegen.spillToSyntheticLocal(destinationType, "<copyDestination>")

        val destinationOffset = call.arguments[2]
        if (destinationOffset == null) {
            codegen.emit("ldc.i4.0", pushes = 1)
        } else {
            codegen.emitExpression(destinationOffset, DotNetIlValueType.Int32)
        }
        val destinationOffsetSlot = codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<copyDestinationOffset>")

        val startIndex = call.arguments[3]
        if (startIndex == null) {
            codegen.emit("ldc.i4.0", pushes = 1)
        } else {
            codegen.emitExpression(startIndex, DotNetIlValueType.Int32)
        }
        val startIndexSlot = codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<copyStartIndex>")

        val endIndex = call.arguments[4]
        if (endIndex == null) {
            codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
            if (sourceType is DotNetIlValueType.PrimitiveArray) {
                codegen.emit(sourceType.sizeCallInstruction, pops = 1, pushes = 1)
            } else {
                codegen.emit("ldlen", pops = 1, pushes = 1)
                codegen.emit("conv.i4", pops = 1, pushes = 1)
            }
        } else {
            codegen.emitExpression(endIndex, DotNetIlValueType.Int32)
        }
        val endIndexSlot = codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<copyEndIndex>")

        codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
        if (sourceType is DotNetIlValueType.PrimitiveArray) {
            codegen.emit(sourceType.getStorageCallInstruction, pops = 1, pushes = 1)
        }
        codegen.emit(loadLocalInstruction(destinationSlot.index), pushes = 1)
        if (destinationType is DotNetIlValueType.PrimitiveArray) {
            codegen.emit(destinationType.getStorageCallInstruction, pops = 1, pushes = 1)
        }
        codegen.emit(loadLocalInstruction(destinationOffsetSlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(startIndexSlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(endIndexSlot.index), pushes = 1)
        codegen.emit(
            DotNetRuntimeLibraryHelpers.arrayCopyIntoCallInstruction(codegen.coreLibraryReference),
            pops = 5,
        )
        codegen.emit(loadLocalInstruction(destinationSlot.index), pushes = 1)
        return true
    }
}

/** Common `Array<T>.fill` with typed exact-vector stores and Kotlin range validation. */
private object DotNetIlGenericArrayFillIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsStatement(call: IrCall, codegen: DotNetIlExpressionCodegen): Boolean {
        if (call.arguments.size != 4) return false
        val array = call.arguments[0]
            ?: dotNetUnsupported("missing array receiver for 'fill'")
        val arrayType = codegen.toDotNetIlValueType(array.type)?.takeIf {
            it is DotNetIlValueType.GenericArray || it is DotNetIlValueType.ErasedGenericArray
        } ?: dotNetUnsupported("'fill' has unsupported array type ${array.type.render()}")
        val element = call.arguments[1]
            ?: dotNetUnsupported("missing element for 'fill'")

        val exactArrayType = arrayType as? DotNetIlValueType.GenericArray
        val elementType = exactArrayType?.elementType ?: DotNetIlValueType.Object

        codegen.emitExpression(array, arrayType)
        val arraySlot = codegen.spillToSyntheticLocal(arrayType, "<fillArray>")
        codegen.emitExpression(element, elementType)
        val elementSlot = codegen.spillToSyntheticLocal(elementType, "<fillElement>")

        val fromIndex = call.arguments[2]
        if (fromIndex == null) {
            codegen.emit("ldc.i4.0", pushes = 1)
        } else {
            codegen.emitExpression(fromIndex, DotNetIlValueType.Int32)
        }
        val fromIndexSlot = codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<fillFromIndex>")

        val toIndex = call.arguments[3]
        if (toIndex == null) {
            codegen.emit(loadLocalInstruction(arraySlot.index), pushes = 1)
            if (exactArrayType != null) {
                codegen.emit("ldlen", pops = 1, pushes = 1)
                codegen.emit("conv.i4", pops = 1, pushes = 1)
            } else {
                codegen.emit(
                    "callvirt instance int32 ${codegen.coreLibraryReference}System.Array::get_Length()",
                    pops = 1,
                    pushes = 1,
                )
            }
        } else {
            codegen.emitExpression(toIndex, DotNetIlValueType.Int32)
        }
        val toIndexSlot = codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<fillToIndex>")

        if (exactArrayType != null) {
            emitTypedFill(
                arraySlot,
                elementSlot,
                fromIndexSlot,
                toIndexSlot,
                exactArrayType,
                codegen,
            )
            return true
        }

        codegen.emit(loadLocalInstruction(arraySlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(elementSlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(fromIndexSlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(toIndexSlot.index), pushes = 1)
        codegen.emit(
            DotNetRuntimeLibraryHelpers.arrayFillCallInstruction(codegen.coreLibraryReference),
            pops = 4,
        )
        return true
    }

    private fun emitTypedFill(
        arraySlot: DotNetIlSlot.Local,
        elementSlot: DotNetIlSlot.Local,
        fromIndexSlot: DotNetIlSlot.Local,
        toIndexSlot: DotNetIlSlot.Local,
        arrayType: DotNetIlValueType.GenericArray,
        codegen: DotNetIlExpressionCodegen,
    ) {
        val outOfBoundsLabel = codegen.nextLabel("fillOutOfBounds")
        val reversedLabel = codegen.nextLabel("fillReversed")
        val doneLabel = codegen.nextLabel("fillDone")

        // Match Common's checkRangeIndexes precedence exactly. Arguments have already been
        // evaluated and spilled in source order before any validation or write occurs.
        codegen.emit(loadLocalInstruction(fromIndexSlot.index), pushes = 1)
        codegen.emit("ldc.i4.0", pushes = 1)
        codegen.emitBranch("blt", outOfBoundsLabel, pops = 2)
        codegen.emit(loadLocalInstruction(toIndexSlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(arraySlot.index), pushes = 1)
        codegen.emit("ldlen", pops = 1, pushes = 1)
        codegen.emit("conv.i4", pops = 1, pushes = 1)
        codegen.emitBranch("bgt", outOfBoundsLabel, pops = 2)
        codegen.emit(loadLocalInstruction(fromIndexSlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(toIndexSlot.index), pushes = 1)
        codegen.emitBranch("bgt", reversedLabel, pops = 2)

        if (codegen.coreLibraryProfile == DotNetCoreLibraryProfile.NET10_0 &&
            !arrayType.elementType.isDotNetReferenceShaped()
        ) {
            // CoreCLR's generic BCL fill is materially faster for value vectors and open T. A
            // statically known reference vector instead keeps the direct loop: paired lengths
            // show it beating both BCL Fill and SetValue. The MemberRef is unavailable on the
            // Framework/netstandard API floor, which always uses the portable typed loop.
            codegen.emit(loadLocalInstruction(arraySlot.index), pushes = 1)
            codegen.emit(loadLocalInstruction(elementSlot.index), pushes = 1)
            codegen.emit(loadLocalInstruction(fromIndexSlot.index), pushes = 1)
            codegen.emit(loadLocalInstruction(toIndexSlot.index), pushes = 1)
            codegen.emit(loadLocalInstruction(fromIndexSlot.index), pushes = 1)
            codegen.emit("sub", pops = 2, pushes = 1)
            codegen.emit(
                "call void ${codegen.coreLibraryReference}System.Array::Fill<" +
                        "${arrayType.elementType.nameInSignature}>(!!0[], !!0, int32, int32)",
                pops = 4,
            )
            codegen.emitGoto(doneLabel)
        } else {
            val loopLabel = codegen.nextLabel("fillLoop")
            codegen.emit(loadLocalInstruction(fromIndexSlot.index), pushes = 1)
            val indexSlot = codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<fillIndex>")
            codegen.emitLabel(loopLabel)
            codegen.emit(loadLocalInstruction(indexSlot.index), pushes = 1)
            codegen.emit(loadLocalInstruction(toIndexSlot.index), pushes = 1)
            codegen.emitBranch("bge", doneLabel, pops = 2)
            codegen.emit(loadLocalInstruction(arraySlot.index), pushes = 1)
            codegen.emit(loadLocalInstruction(indexSlot.index), pushes = 1)
            codegen.emit(loadLocalInstruction(elementSlot.index), pushes = 1)
            codegen.emit(arrayType.storeElementInstruction, pops = 3)
            codegen.emit(loadLocalInstruction(indexSlot.index), pushes = 1)
            codegen.emit("ldc.i4.1", pushes = 1)
            codegen.emit("add", pops = 2, pushes = 1)
            codegen.emit(storeLocalInstruction(indexSlot.index), pops = 1)
            codegen.emitGoto(loopLabel)
        }

        codegen.emitLabel(outOfBoundsLabel)
        codegen.emitParameterlessExceptionThrow(
            exceptionTypeRef = "${codegen.coreLibraryReference}System.IndexOutOfRangeException",
            valuePosition = false,
        )
        codegen.emitLabel(reversedLabel)
        codegen.emitParameterlessExceptionThrow(
            exceptionTypeRef = "${codegen.coreLibraryReference}System.ArgumentException",
            valuePosition = false,
        )
        codegen.emitLabel(doneLabel)
    }
}

/** The .NET actual is an identity operation; retain argument evaluation and the exact vector. */
private object DotNetIlTerminateCollectionToArrayIntrinsic : DotNetIlIntrinsicMethod() {
    override fun naturalReturnType(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
    ): DotNetIlValueType? = call.arguments.getOrNull(1)?.let(codegen::genericArrayPhysicalType)

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (call.arguments.size != 2) return false
        val collectionSize = call.arguments[0]
            ?: dotNetUnsupported("missing collection size for 'terminateCollectionToArray'")
        val array = call.arguments[1]
            ?: dotNetUnsupported("missing array for 'terminateCollectionToArray'")
        val arrayType = codegen.genericArrayPhysicalType(array)
            ?: dotNetUnsupported(
                "'terminateCollectionToArray' has unsupported array type ${array.type.render()}"
            )
        if (arrayType != expectedType && !arrayType.isDotNetAssignableTo(expectedType)) return false

        codegen.emitExpression(collectionSize, DotNetIlValueType.Int32)
        codegen.emit("pop", pops = 1)
        codegen.emitExpression(array, arrayType)
        return true
    }
}

/**
 * `copyOf()` and `copyOf(newSize)` with an exact typed-vector result.
 *
 * Generic arrays allocate from the source vector's runtime element type. This preserves CLR
 * reference covariance and exact value vectors for open/output-projected Kotlin array views;
 * a statically spelled `newarr !T` or `object[]` would lose that contract.
 */
private class DotNetIlArrayCopyOfIntrinsic(
    private val fixedArrayType: DotNetIlValueType?,
    private val resized: Boolean,
) : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        val expectedArgumentCount = if (resized) 2 else 1
        if (call.arguments.size != expectedArgumentCount) return false
        val source = call.arguments[0]
            ?: dotNetUnsupported("missing array receiver for 'copyOf'")
        val arrayType = fixedArrayType ?: codegen.toDotNetIlValueType(source.type)?.takeIf {
            it is DotNetIlValueType.GenericArray || it is DotNetIlValueType.ErasedGenericArray
        } ?: dotNetUnsupported("'copyOf' has unsupported source type ${source.type.render()}")

        if (fixedArrayType == null) {
            if (expectedType !is DotNetIlValueType.GenericArray &&
                expectedType !is DotNetIlValueType.ErasedGenericArray
            ) return false

            codegen.emitExpression(source, arrayType)
            val sourceSlot = codegen.spillToSyntheticLocal(arrayType, "<copySource>")
            val sizeSlot = if (resized) {
                val newSize = call.arguments[1]
                    ?: dotNetUnsupported("missing new size for 'copyOf'")
                codegen.emitExpression(newSize, DotNetIlValueType.Int32)
                codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<copySize>")
            } else {
                codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
                codegen.emit(
                    "callvirt instance int32 ${codegen.coreLibraryReference}System.Array::get_Length()",
                    pops = 1,
                    pushes = 1,
                )
                codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<copySize>")
            }

            if (resized) {
                val nonNegativeLabel = codegen.nextLabel("copySizeNonNegative")
                codegen.emit(loadLocalInstruction(sizeSlot.index), pushes = 1)
                codegen.emit("ldc.i4.0", pushes = 1)
                codegen.emit("clt", pops = 2, pushes = 1)
                codegen.emitBranch("brfalse", nonNegativeLabel, pops = 1)
                codegen.emitParameterlessExceptionThrow(
                    exceptionTypeRef = DotNetRuntimeLibrary.negativeArraySizeExceptionTypeRef,
                    valuePosition = false,
                )
                codegen.emitLabel(nonNegativeLabel)
            }

            val sourceElementType = source.type.dotNetReadableArrayElementTypeOrNull()
                ?.let(codegen::toDotNetIlGenericArgumentType)
            val nullableDestinationElement = call.type.dotNetReadableArrayElementTypeOrNull()
                ?.let(codegen::toDotNetIlGenericArgumentType) as? DotNetIlValueType.NullableValue
            val sourceArrayType = sourceElementType?.let(DotNetIlValueType::GenericArray)
            val destinationArrayType = nullableDestinationElement?.let(DotNetIlValueType::GenericArray)
            val nullableValueCopy = resized && sourceArrayType != null && destinationArrayType != null &&
                    nullableDestinationElement.elementType == sourceElementType &&
                    (destinationArrayType == expectedType ||
                            destinationArrayType.isDotNetAssignableTo(expectedType))
            if (nullableValueCopy) {
                // `Array<V>.copyOf(newSize)` returns `Array<V?>`. A CLR `V[]` cannot be cast to
                // `Nullable<V>[]`, and zero-padding a retained value vector would expose V's
                // default instead of Kotlin null. Copy element-by-element into the truthful
                // nullable vector without boxing or System.Array reflection.
                val typedSourceSlot = when (arrayType) {
                    sourceArrayType -> sourceSlot
                    is DotNetIlValueType.ErasedGenericArray -> {
                        // An `Array<out V>` local is a System.Array capability over the same exact
                        // vector. Recover its statically bounded V[] view once for typed reads;
                        // this is not an invariant open-array declaration or a replacement copy.
                        codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
                        codegen.emit(
                            "castclass ${sourceArrayType.nameInSignature}",
                            pops = 1,
                            pushes = 1,
                        )
                        codegen.spillToSyntheticLocal(sourceArrayType, "<typedCopySource>")
                    }
                    else -> dotNetUnsupported(
                        "nullable value-array copy has unsupported source ${arrayType.nameInSignature}"
                    )
                }
                emitNullableValueResizedCopy(
                    typedSourceSlot,
                    sizeSlot,
                    sourceArrayType,
                    destinationArrayType,
                    nullableDestinationElement,
                    codegen,
                )
                return true
            }

            val openNullableValueDoneLabel = if (
                resized && arrayType is DotNetIlValueType.ErasedGenericArray &&
                expectedType is DotNetIlValueType.ErasedGenericArray &&
                source.type.isDotNetOutProjectedGenericArray()
            ) {
                val preserveExactLabel = codegen.nextLabel("copyOpenNullablePreserveExact")
                val doneLabel = codegen.nextLabel("copyOpenNullableDone")
                val coreLibraryReference = codegen.coreLibraryReference

                // Reference vectors and vectors which already store Nullable<V> can preserve
                // their exact runtime component: their new suffix is null. A non-null V[]
                // instead needs a uniform output-only object[] so padding is null for every
                // runtime substitution. System.Array.Copy boxes its copied value prefix.
                // A truncating/equal-sized copy has no new suffix and keeps the exact value
                // vector as well.
                codegen.emit(loadLocalInstruction(sizeSlot.index), pushes = 1)
                codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
                codegen.emit(
                    "callvirt instance int32 ${coreLibraryReference}System.Array::get_Length()",
                    pops = 1,
                    pushes = 1,
                )
                codegen.emitBranch("ble", preserveExactLabel, pops = 2)

                codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
                codegen.emit(
                    "call instance class ${coreLibraryReference}System.Type " +
                            "${coreLibraryReference}System.Object::GetType()",
                    pops = 1,
                    pushes = 1,
                )
                codegen.emit(
                    "callvirt instance class ${coreLibraryReference}System.Type " +
                            "${coreLibraryReference}System.Type::GetElementType()",
                    pops = 1,
                    pushes = 1,
                )
                codegen.emit(
                    "callvirt instance bool ${coreLibraryReference}System.Type::get_IsValueType()",
                    pops = 1,
                    pushes = 1,
                )
                codegen.emitBranch("brfalse", preserveExactLabel, pops = 1)

                codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
                codegen.emit(
                    "call instance class ${coreLibraryReference}System.Type " +
                            "${coreLibraryReference}System.Object::GetType()",
                    pops = 1,
                    pushes = 1,
                )
                codegen.emit(
                    "callvirt instance class ${coreLibraryReference}System.Type " +
                            "${coreLibraryReference}System.Type::GetElementType()",
                    pops = 1,
                    pushes = 1,
                )
                codegen.emit(
                    "call class ${coreLibraryReference}System.Type " +
                            "${coreLibraryReference}System.Nullable::GetUnderlyingType(" +
                            "class ${coreLibraryReference}System.Type)",
                    pops = 1,
                    pushes = 1,
                )
                codegen.emitBranch("brtrue", preserveExactLabel, pops = 1)

                val objectArrayType = DotNetIlValueType.GenericArray(DotNetIlValueType.Object)
                codegen.emit(loadLocalInstruction(sizeSlot.index), pushes = 1)
                codegen.emit(objectArrayType.newArrayInstruction, pops = 1, pushes = 1)
                val destinationSlot =
                    codegen.spillToSyntheticLocal(objectArrayType, "<openNullableCopyDestination>")
                codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
                codegen.emit("ldc.i4.0", pushes = 1)
                codegen.emit(loadLocalInstruction(destinationSlot.index), pushes = 1)
                codegen.emit("ldc.i4.0", pushes = 1)
                codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
                codegen.emit(
                    "callvirt instance int32 ${coreLibraryReference}System.Array::get_Length()",
                    pops = 1,
                    pushes = 1,
                )
                codegen.emit(loadLocalInstruction(sizeSlot.index), pushes = 1)
                codegen.emit(
                    "call int32 ${coreLibraryReference}System.Math::Min(int32, int32)",
                    pops = 2,
                    pushes = 1,
                )
                codegen.emit(
                    "call void ${coreLibraryReference}System.Array::Copy(" +
                            "class ${coreLibraryReference}System.Array, int32, " +
                            "class ${coreLibraryReference}System.Array, int32, int32)",
                    pops = 5,
                )
                codegen.emit(loadLocalInstruction(destinationSlot.index), pushes = 1)
                codegen.emitGoto(doneLabel)
                codegen.emitLabel(preserveExactLabel)
                doneLabel
            } else {
                null
            }

            val coreLibraryReference = codegen.coreLibraryReference
            codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
            codegen.emit(
                "call instance class ${coreLibraryReference}System.Type " +
                        "${coreLibraryReference}System.Object::GetType()",
                pops = 1,
                pushes = 1,
            )
            codegen.emit(
                "callvirt instance class ${coreLibraryReference}System.Type " +
                        "${coreLibraryReference}System.Type::GetElementType()",
                pops = 1,
                pushes = 1,
            )
            codegen.emit(loadLocalInstruction(sizeSlot.index), pushes = 1)
            codegen.emit(
                "call class ${coreLibraryReference}System.Array ${coreLibraryReference}" +
                        "System.Array::CreateInstance(class ${coreLibraryReference}System.Type, int32)",
                pops = 2,
                pushes = 1,
            )
            if (expectedType is DotNetIlValueType.GenericArray) {
                codegen.emit("castclass ${expectedType.nameInSignature}", pops = 1, pushes = 1)
            }
            val destinationSlot = codegen.spillToSyntheticLocal(expectedType, "<copyDestination>")

            codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
            codegen.emit("ldc.i4.0", pushes = 1)
            codegen.emit(loadLocalInstruction(destinationSlot.index), pushes = 1)
            codegen.emit("ldc.i4.0", pushes = 1)
            if (resized) {
                codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
                codegen.emit(
                    "callvirt instance int32 ${coreLibraryReference}System.Array::get_Length()",
                    pops = 1,
                    pushes = 1,
                )
                codegen.emit(loadLocalInstruction(sizeSlot.index), pushes = 1)
                codegen.emit(
                    "call int32 ${coreLibraryReference}System.Math::Min(int32, int32)",
                    pops = 2,
                    pushes = 1,
                )
            } else {
                codegen.emit(loadLocalInstruction(sizeSlot.index), pushes = 1)
            }
            codegen.emit(
                "call void ${coreLibraryReference}System.Array::Copy(" +
                        "class ${coreLibraryReference}System.Array, int32, " +
                        "class ${coreLibraryReference}System.Array, int32, int32)",
                pops = 5,
            )
            codegen.emit(loadLocalInstruction(destinationSlot.index), pushes = 1)
            openNullableValueDoneLabel?.let(codegen::emitLabel)
            return true
        }

        if (expectedType != arrayType) return false
        val newArrayInstruction = when (arrayType) {
            is DotNetIlValueType.PrimitiveArray -> arrayType.newStorageInstruction
            is DotNetIlValueType.GenericArray -> arrayType.newArrayInstruction
            else -> error("Internal .NET backend error: non-array copy type ${arrayType.nameInSignature}")
        }

        codegen.emitExpression(source, arrayType)
        val sourceSlot = codegen.spillToSyntheticLocal(arrayType, "<copySource>")
        val sizeSlot = if (resized) {
            val newSize = call.arguments[1]
                ?: dotNetUnsupported("missing new size for 'copyOf'")
            codegen.emitExpression(newSize, DotNetIlValueType.Int32)
            codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<copySize>")
        } else {
            codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
            if (arrayType is DotNetIlValueType.PrimitiveArray) {
                codegen.emit(arrayType.sizeCallInstruction, pops = 1, pushes = 1)
            } else {
                codegen.emit("ldlen", pops = 1, pushes = 1)
                codegen.emit("conv.i4", pops = 1, pushes = 1)
            }
            codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<copySize>")
        }

        codegen.emit(loadLocalInstruction(sizeSlot.index), pushes = 1)
        if (resized) {
            emitGuardedArrayAllocationFromStack(newArrayInstruction, codegen)
        } else {
            codegen.emit(newArrayInstruction, pops = 1, pushes = 1)
        }
        if (arrayType is DotNetIlValueType.PrimitiveArray) {
            codegen.emit(arrayType.wrapStorageInstruction, pops = 1, pushes = 1)
        }
        val destinationSlot = codegen.spillToSyntheticLocal(arrayType, "<copyDestination>")

        codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
        if (arrayType is DotNetIlValueType.PrimitiveArray) {
            codegen.emit(arrayType.getStorageCallInstruction, pops = 1, pushes = 1)
        }
        codegen.emit("ldc.i4.0", pushes = 1)
        codegen.emit(loadLocalInstruction(destinationSlot.index), pushes = 1)
        if (arrayType is DotNetIlValueType.PrimitiveArray) {
            codegen.emit(arrayType.getStorageCallInstruction, pops = 1, pushes = 1)
        }
        codegen.emit("ldc.i4.0", pushes = 1)
        if (resized) {
            codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
            if (arrayType is DotNetIlValueType.PrimitiveArray) {
                codegen.emit(arrayType.sizeCallInstruction, pops = 1, pushes = 1)
            } else {
                codegen.emit("ldlen", pops = 1, pushes = 1)
                codegen.emit("conv.i4", pops = 1, pushes = 1)
            }
            codegen.emit(loadLocalInstruction(sizeSlot.index), pushes = 1)
            codegen.emit(
                "call int32 ${codegen.coreLibraryReference}System.Math::Min(int32, int32)",
                pops = 2,
                pushes = 1,
            )
        } else {
            codegen.emit(loadLocalInstruction(sizeSlot.index), pushes = 1)
        }
        codegen.emit(
            "call void ${codegen.coreLibraryReference}System.Array::Copy(" +
                    "class ${codegen.coreLibraryReference}System.Array, int32, " +
                    "class ${codegen.coreLibraryReference}System.Array, int32, int32)",
            pops = 5,
        )
        codegen.emit(loadLocalInstruction(destinationSlot.index), pushes = 1)
        return true
    }

    private fun emitNullableValueResizedCopy(
        sourceSlot: DotNetIlSlot.Local,
        sizeSlot: DotNetIlSlot.Local,
        sourceType: DotNetIlValueType.GenericArray,
        destinationType: DotNetIlValueType.GenericArray,
        destinationElementType: DotNetIlValueType.NullableValue,
        codegen: DotNetIlExpressionCodegen,
    ) {
        codegen.emit(loadLocalInstruction(sizeSlot.index), pushes = 1)
        codegen.emit(destinationType.newArrayInstruction, pops = 1, pushes = 1)
        val destinationSlot = codegen.spillToSyntheticLocal(destinationType, "<copyDestination>")

        codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
        codegen.emit("ldlen", pops = 1, pushes = 1)
        codegen.emit("conv.i4", pops = 1, pushes = 1)
        codegen.emit(loadLocalInstruction(sizeSlot.index), pushes = 1)
        codegen.emit(
            "call int32 ${codegen.coreLibraryReference}System.Math::Min(int32, int32)",
            pops = 2,
            pushes = 1,
        )
        val copyCountSlot = codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<copyCount>")
        codegen.emit("ldc.i4.0", pushes = 1)
        val indexSlot = codegen.spillToSyntheticLocal(DotNetIlValueType.Int32, "<copyIndex>")

        val loopLabel = codegen.nextLabel("copyNullableValueLoop")
        val doneLabel = codegen.nextLabel("copyNullableValueDone")
        codegen.emitLabel(loopLabel)
        codegen.emit(loadLocalInstruction(indexSlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(copyCountSlot.index), pushes = 1)
        codegen.emitBranch("bge", doneLabel, pops = 2)

        codegen.emit(loadLocalInstruction(destinationSlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(indexSlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(sourceSlot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(indexSlot.index), pushes = 1)
        codegen.emit(sourceType.loadElementInstruction, pops = 2, pushes = 1)
        codegen.emit(destinationElementType.ctorInstruction, pops = 1, pushes = 1)
        codegen.emit(destinationType.storeElementInstruction, pops = 3)

        codegen.emit(loadLocalInstruction(indexSlot.index), pushes = 1)
        codegen.emit("ldc.i4.1", pushes = 1)
        codegen.emit("add", pops = 2, pushes = 1)
        codegen.emit(storeLocalInstruction(indexSlot.index), pops = 1)
        codegen.emitGoto(loopLabel)
        codegen.emitLabel(doneLabel)
        codegen.emit(loadLocalInstruction(destinationSlot.index), pushes = 1)
    }
}

/**
 * Nullable, shallow `contentEquals` for primitive and generic arrays. Receiver and argument are
 * emitted exactly once in source order. Open `Array<T>` is intentionally supported: no element
 * opcode is selected in consumer IL because the runtime traverses the vector through System.Array.
 */
private class DotNetIlArrayContentEqualsIntrinsic(
    private val fixedArrayType: DotNetIlValueType?,
) : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Boolean || call.arguments.size != 2) return false
        val left = call.arguments[0]
            ?: dotNetUnsupported("missing array receiver for 'contentEquals'")
        val right = call.arguments[1]
            ?: dotNetUnsupported("missing other array for 'contentEquals'")

        fun arrayTypeOrNull(expression: IrExpression): DotNetIlValueType? =
            codegen.toDotNetIlValueType(expression.type)?.takeIf {
                it is DotNetIlValueType.PrimitiveArray || it is DotNetIlValueType.GenericArray
            }

        val leftMappedType = arrayTypeOrNull(left)
        val rightMappedType = arrayTypeOrNull(right)
        val leftType = fixedArrayType ?: leftMappedType ?: rightMappedType
            ?: dotNetUnsupported("'contentEquals' has unsupported receiver type ${left.type.render()}")
        val rightType = fixedArrayType ?: rightMappedType ?: leftMappedType
            ?: dotNetUnsupported("'contentEquals' has unsupported argument type ${right.type.render()}")

        codegen.emitSystemArrayStorage(left, leftType)
        codegen.emitSystemArrayStorage(right, rightType)
        codegen.emit(
            DotNetRuntimeLibraryHelpers.arrayContentEqualsCallInstruction(codegen.coreLibraryReference),
            pops = 2,
            pushes = 1,
        )
        return true
    }
}

/**
 * Nullable `contentDeepEquals` for generic arrays. The runtime owns recursive shape dispatch:
 * reference vectors recurse, supported primitive-vector pairs use shallow content equality, and
 * scalar elements use Kotlin equality. The common stdlib leaves self-containing arrays undefined,
 * so this path deliberately adds no cycle detector or alternative observable contract.
 */
private object DotNetIlArrayContentDeepEqualsIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Boolean || call.arguments.size != 2) return false
        val left = call.arguments[0]
            ?: dotNetUnsupported("missing array receiver for 'contentDeepEquals'")
        val right = call.arguments[1]
            ?: dotNetUnsupported("missing other array for 'contentDeepEquals'")

        fun genericArrayTypeOrNull(expression: IrExpression): DotNetIlValueType.GenericArray? =
            codegen.toDotNetIlValueType(expression.type) as? DotNetIlValueType.GenericArray

        val leftMappedType = genericArrayTypeOrNull(left)
        val rightMappedType = genericArrayTypeOrNull(right)
        val leftType = leftMappedType ?: rightMappedType
            ?: dotNetUnsupported("'contentDeepEquals' has unsupported receiver type ${left.type.render()}")
        val rightType = rightMappedType ?: leftMappedType
            ?: dotNetUnsupported("'contentDeepEquals' has unsupported argument type ${right.type.render()}")

        codegen.emitExpression(left, leftType)
        codegen.emitExpression(right, rightType)
        codegen.emit(
            DotNetRuntimeLibraryHelpers.arrayContentDeepEqualsCallInstruction(codegen.coreLibraryReference),
            pops = 2,
            pushes = 1,
        )
        return true
    }
}

/** Nullable shallow content hash for primitive and generic arrays; nested arrays retain identity. */
private class DotNetIlArrayContentHashCodeIntrinsic(
    private val fixedArrayType: DotNetIlValueType?,
) : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Int32 || call.arguments.size != 1) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing array receiver for 'contentHashCode'")
        val receiverType = fixedArrayType
            ?: codegen.toDotNetIlValueType(receiver.type)?.takeIf {
                it is DotNetIlValueType.PrimitiveArray || it is DotNetIlValueType.GenericArray
            }
            ?: dotNetUnsupported(
                "'contentHashCode' has unsupported receiver type ${receiver.type.render()}"
            )

        codegen.emitSystemArrayStorage(receiver, receiverType)
        codegen.emit(
            DotNetRuntimeLibraryHelpers.arrayContentHashCodeCallInstruction(codegen.coreLibraryReference),
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/** Nullable recursive content hash for generic arrays; self-containing arrays remain undefined. */
private object DotNetIlArrayContentDeepHashCodeIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Int32 || call.arguments.size != 1) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing array receiver for 'contentDeepHashCode'")
        val receiverType = codegen.toDotNetIlValueType(receiver.type) as? DotNetIlValueType.GenericArray
            ?: dotNetUnsupported(
                "'contentDeepHashCode' has unsupported receiver type ${receiver.type.render()}"
            )

        codegen.emitSystemArrayStorage(receiver, receiverType)
        codegen.emit(
            DotNetRuntimeLibraryHelpers.arrayContentDeepHashCodeCallInstruction(codegen.coreLibraryReference),
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/** Nullable shallow content text for primitive and generic arrays; nested arrays retain identity. */
private class DotNetIlArrayContentToStringIntrinsic(
    private val fixedArrayType: DotNetIlValueType?,
) : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.String || call.arguments.size != 1) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing array receiver for 'contentToString'")
        val receiverType = fixedArrayType
            ?: codegen.toDotNetIlValueType(receiver.type)?.takeIf {
                it is DotNetIlValueType.PrimitiveArray || it is DotNetIlValueType.GenericArray
            }
            ?: dotNetUnsupported(
                "'contentToString' has unsupported receiver type ${receiver.type.render()}"
            )

        codegen.emitSystemArrayStorage(receiver, receiverType)
        codegen.emit(
            DotNetRuntimeLibraryHelpers.arrayContentToStringCallInstruction(codegen.coreLibraryReference),
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/** Nullable recursive content text for generic arrays with active-path cycle detection. */
private object DotNetIlArrayContentDeepToStringIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.String || call.arguments.size != 1) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing array receiver for 'contentDeepToString'")
        val receiverType = codegen.toDotNetIlValueType(receiver.type) as? DotNetIlValueType.GenericArray
            ?: dotNetUnsupported(
                "'contentDeepToString' has unsupported receiver type ${receiver.type.render()}"
            )

        codegen.emitExpression(receiver, receiverType)
        codegen.emit(
            DotNetRuntimeLibraryHelpers.arrayContentDeepToStringCallInstruction(codegen.coreLibraryReference),
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/** Numeric promotion: Byte/Short -> Int32, then Int32 < Int64 < Float32 < Float64, like Kotlin/JVM. */
private fun promoteNumeric(left: DotNetIlValueType, right: DotNetIlValueType): DotNetIlValueType = when {
    left == DotNetIlValueType.Float64 || right == DotNetIlValueType.Float64 -> DotNetIlValueType.Float64
    left == DotNetIlValueType.Float32 || right == DotNetIlValueType.Float32 -> DotNetIlValueType.Float32
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
        computationType == DotNetIlValueType.Int32 &&
                (operandType == DotNetIlValueType.Int8 || operandType == DotNetIlValueType.Int16) -> Unit
        computationType == DotNetIlValueType.Int64 &&
                operandType in setOf(DotNetIlValueType.Int8, DotNetIlValueType.Int16, DotNetIlValueType.Int32) ->
            emit("conv.i8", pops = 1, pushes = 1)
        computationType == DotNetIlValueType.Float32 &&
                operandType in setOf(
                    DotNetIlValueType.Int8,
                    DotNetIlValueType.Int16,
                    DotNetIlValueType.Int32,
                    DotNetIlValueType.Int64,
                ) ->
            emit("conv.r4", pops = 1, pushes = 1)
        computationType == DotNetIlValueType.Float64 &&
                operandType in setOf(
                    DotNetIlValueType.Int8,
                    DotNetIlValueType.Int16,
                    DotNetIlValueType.Int32,
                    DotNetIlValueType.Int64,
                    DotNetIlValueType.Float32,
                ) ->
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

/** Kotlin Boolean's eager (non-short-circuiting) `and`, `or`, and `xor` members. */
private class DotNetIlBooleanBinaryOperatorIntrinsic(
    private val instruction: String,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Boolean || call.arguments.size != 2) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing receiver of Boolean '$instruction'")
        val argument = call.arguments[1]
            ?: dotNetUnsupported("missing argument of Boolean '$instruction'")
        codegen.emitExpression(receiver, DotNetIlValueType.Boolean)
        codegen.emitExpression(argument, DotNetIlValueType.Boolean)
        codegen.emit(instruction, pops = 2, pushes = 1)
        return true
    }
}

/**
 * The legacy synthetic `noWhenBranchMatchedException` builtin fir2ir appends to an exhaustive
 * `when` without a source `else` when the subject-aware Kotlin 2.5 language feature is disabled
 * or its stdlib symbol is unavailable. Current language mode instead calls the ordinary
 * `kotlin.internal.throwNoWhenBranchMatchedException(subject)` stdlib helper.
 *
 * Roslyn throws `System.Runtime.CompilerServices.SwitchExpressionException`, but that type is
 * scoped through `System.Runtime` and is absent from the .NET Framework facade. The DotNet runtime
 * instead owns exact `Kotlin.NoWhenBranchMatchedException : Kotlin.RuntimeException`; this is
 * target-independent, preserves the supported Kotlin `Throwable`/`Exception` catch edges, and
 * does not create a false sibling edge to mapped `IllegalStateException`. `whenprobe_s1` verified
 * the Roslyn limitation; `exceptionabi_s1` verified the Kotlin-owned hierarchy and cross-runtime
 * assembly identity.
 */
private object DotNetIlNoWhenBranchMatchedIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        requireNoArguments(call)
        codegen.emitParameterlessExceptionThrow(
            exceptionTypeRef = DotNetRuntimeLibrary.noWhenBranchMatchedExceptionTypeRef,
            valuePosition = true,
        )
        return true
    }

    override fun tryEmitAsStatement(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
    ): Boolean {
        requireNoArguments(call)
        codegen.emitParameterlessExceptionThrow(
            exceptionTypeRef = DotNetRuntimeLibrary.noWhenBranchMatchedExceptionTypeRef,
            valuePosition = false,
        )
        return true
    }

    private fun requireNoArguments(call: IrCall) {
        if (call.arguments.isNotEmpty()) {
            dotNetUnsupported("noWhenBranchMatchedException has an unsupported argument shape")
        }
    }
}

/** Common IR synthetic used by shared lowerings and by enum `valueOf`. */
private object DotNetIlIllegalArgumentExceptionIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        codegen.emitIllegalArgumentExceptionThrow(message(call), valuePosition = true)
        return true
    }

    override fun tryEmitAsStatement(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
    ): Boolean {
        codegen.emitIllegalArgumentExceptionThrow(message(call), valuePosition = false)
        return true
    }

    private fun message(call: IrCall) = call.arguments.singleOrNull()
        ?: dotNetUnsupported("illegalArgumentException has an unsupported argument shape")
}

/** Mature-backend guard for a call whose statically impossible `Nothing` result returned. */
private object DotNetIlThrowKotlinNothingValueExceptionIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        requireNoArguments(call)
        codegen.emitParameterlessExceptionThrow(
            exceptionTypeRef = DotNetRuntimeLibrary.kotlinNothingValueExceptionTypeRef,
            valuePosition = true,
        )
        return true
    }

    override fun tryEmitAsStatement(call: IrCall, codegen: DotNetIlExpressionCodegen): Boolean {
        requireNoArguments(call)
        codegen.emitParameterlessExceptionThrow(
            exceptionTypeRef = DotNetRuntimeLibrary.kotlinNothingValueExceptionTypeRef,
            valuePosition = false,
        )
        return true
    }

    private fun requireNoArguments(call: IrCall) {
        if (call.arguments.isNotEmpty()) {
            dotNetUnsupported("throwKotlinNothingValueException has an unsupported argument shape")
        }
    }
}

/** Records an original initializer exception in one runtime-owned, atomically observed state. */
private object DotNetIlCaptureStaticInitializationFailureIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Object || call.arguments.size != 1) return false
        val reason = call.arguments.single()
            ?: dotNetUnsupported("captureStaticInitializationFailure has no reason")
        val exceptionCarrier = DotNetIlValueType.MappedClass(
            DotNetMappedExceptions.exceptionTypeRef(codegen.coreLibraryReference)
        )
        codegen.emitExpression(reason, exceptionCarrier)
        codegen.emit(
            DotNetRuntimeLibraryHelpers.captureStaticInitializationFailureCallInstruction(
                codegen.coreLibraryReference
            ),
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/** Atomically returns the original failure to one observer and null to every later observer. */
private object DotNetIlObserveStaticInitializationFailureIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        val exceptionCarrier = DotNetIlValueType.MappedClass(
            DotNetMappedExceptions.exceptionTypeRef(codegen.coreLibraryReference)
        )
        if (expectedType != exceptionCarrier || call.arguments.size != 1) return false
        val state = call.arguments.single()
            ?: dotNetUnsupported("observeStaticInitializationFailure has no failure state")
        codegen.emitExpression(state, DotNetIlValueType.Object)
        codegen.emit(
            DotNetRuntimeLibraryHelpers.observeStaticInitializationFailureCallInstruction(
                codegen.coreLibraryReference
            ),
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/** Emits the existing Common non-JVM `staticInitializationFailure(reason, className)` contract. */
private object DotNetIlStaticInitializationFailureIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsStatement(call: IrCall, codegen: DotNetIlExpressionCodegen): Boolean {
        if (call.arguments.size != 2) return false
        val reason = call.arguments[0]
            ?: dotNetUnsupported("staticInitializationFailure has no reason")
        val className = call.arguments[1]
            ?: dotNetUnsupported("staticInitializationFailure has no class name")
        val exceptionCarrier = DotNetIlValueType.MappedClass(
            DotNetMappedExceptions.exceptionTypeRef(codegen.coreLibraryReference)
        )
        codegen.emitExpression(reason, exceptionCarrier)
        codegen.emitExpression(className, DotNetIlValueType.String)
        codegen.emit(
            DotNetRuntimeLibraryHelpers.throwStaticInitializationFailureCallInstruction(
                codegen.coreLibraryReference
            ),
            pops = 2,
        )
        return true
    }
}

/**
 * `==`/`===`/`ieee754equals`. Integral value primitives use `ceq`. Floating-point equality has
 * the same two-symbol split as JVM and Wasm: the explicitly typed `ieee754equals` builtin uses
 * CIL `ceq` (NaN != NaN, -0.0 == 0.0), while generic `eqeq` uses Kotlin's reflexive boxed/value
 * equality (all NaNs equal, signed zero distinct). User-class instances support reference equality
 * (`===`, a `ceq` on the object references) and `==` against the `null` literal, which Kotlin
 * defines as a pure reference check that never calls `equals` (the JVM backend's `Equals`
 * intrinsic special-cases `isNullConst` operands into an `ifnull` check the same way). General
 * reference-shaped `==` calls the runtime's null-safe, left-biased `AreEqual(object, object)`, the
 * direct CLR counterpart of JVM `Intrinsics.areEqual(Object, Object)`.
 *
 * NULLABLE-PRIMITIVE operands (`Int?` and friends, the hybrid `Nullable<T>` representation) get
 * null-aware structural `==` without any boxing — the JVM precedent is the
 * `Intrinsics.areEqual` specializations (`areEqual(Double, Double)` compares unboxed values the
 * same way), the emitted IL is the exact Roslyn lifted-equality shape (probe-verified incl. the
 * (none, some(0)) corner, boxprobe_s5):
 * - `T? == T?`: `GetValueOrDefault()` values `ceq` ANDed with `get_HasValue()` flags `ceq`;
 * - `T? == T` / `T == T?`: value `ceq` ANDed with the nullable side's `get_HasValue()`;
 * - `T? == null` / `null == T?`: negated `get_HasValue()`.
 * `Double?`/`Float?` retain that same symbol-dependent distinction in the lifted shapes.
 * Cross-primitive
 * pairs (`Int? == Long?`) stay rejected like `Int == Long`. Identity against a null-like operand
 * (`x === null` / `x !== null`, either order) is the same HasValue test as structural equality and
 * needs no boxing. Identity between two nullable-primitive values remains REJECTED loudly: the
 * operands would have to box, and reference identity of separately boxed values is unrelated to
 * value equality (probe-verified False for equal payloads, boxprobe_s6; Kotlin deprecates identity
 * checks on boxed primitives for this reason).
 *
 * `Any?`-typed operands are reference-shaped storage: `===` (and `== null`) is the type-agnostic
 * reference `ceq`; general `==` uses `AreEqual`. A nullable primitive compared through an Any
 * boundary boxes to the CLR's boxed-underlying-or-null representation before the same helper,
 * while exact nullable-primitive pairs retain their existing unboxed lifted comparison.
 * Array operands are a closed exception to that general reference rule: Kotlin primitive and
 * generic arrays inherit identity-based `Any.equals`, so both `==` and `===` use reference
 * `ceq`. Structural comparison remains the separate, currently unsupported `contentEquals`
 * operation.
 */
private class DotNetIlEqualityIntrinsic(
    private val referenceEquality: Boolean,
    private val ieee754FloatingPoint: Boolean = false,
) : DotNetIlIntrinsicMethod() {
    init {
        check(!referenceEquality || !ieee754FloatingPoint)
    }

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Boolean || call.arguments.size != 2) return false
        val left = call.arguments[0]
            ?: dotNetUnsupported("missing left operand of an equality comparison")
        val right = call.arguments[1]
            ?: dotNetUnsupported("missing right operand of an equality comparison")
        // Compare the values each expression physically produces. In the generic-owner
        // rehearsal a property getter may return the declaration-erased semantic capability
        // even though its logical Kotlin result is C<T>; `x == null` must retain that carrier
        // rather than reconstructing a constructed CLR owner merely for the null check.
        val leftType = codegen.genericOwnerCapabilityNaturalTypeOrNull(left)
            ?: codegen.toDotNetIlValueType(left.type)
        val rightType = codegen.genericOwnerCapabilityNaturalTypeOrNull(right)
            ?: codegen.toDotNetIlValueType(right.type)
        // Structural equality between the exact same open T uses the runtime's cached generic
        // entry. It keeps reference/null and Kotlin floating behavior on the universal object
        // path, but uses constrained Object.Equals for other value-type instantiations so an
        // overriding struct avoids the receiver box. Different open parameters, or an open T
        // against another physical carrier, still need the universal object fallback.
        // For identity, one open T against one stable reference has an exact
        // representation too: `box !T` preserves a reference instantiation and creates a fresh,
        // necessarily non-identical object for a value instantiation. Common AbstractCollection's
        // `element === this` recursion guard is the canonical shape. Two open operands remain
        // rejected because independently manufactured value boxes cannot model source identity.
        if (leftType is DotNetIlValueType.TypeParameter || rightType is DotNetIlValueType.TypeParameter) {
            if (referenceEquality) {
                val exactlyOneOpenOperand =
                    (leftType is DotNetIlValueType.TypeParameter) !=
                            (rightType is DotNetIlValueType.TypeParameter)
                val otherType = if (leftType is DotNetIlValueType.TypeParameter) rightType else leftType
                if (exactlyOneOpenOperand && otherType?.isDotNetReferenceShaped() == true) {
                    codegen.emitExpression(left, DotNetIlValueType.Object)
                    codegen.emitExpression(right, DotNetIlValueType.Object)
                    codegen.emit("ceq", pops = 2, pushes = 1)
                    return true
                }
                dotNetUnsupported(
                    "identity comparison with a type-parameter-typed operand is not supported " +
                            "(a CLR value-type instantiation has no stable reference identity)"
                )
            }
            if (leftType is DotNetIlValueType.TypeParameter && leftType == rightType) {
                codegen.emitExpression(left, leftType)
                codegen.emitExpression(right, leftType)
                codegen.emit(
                    DotNetRuntimeLibraryHelpers.areEqualGenericCallInstruction(leftType),
                    pops = 2,
                    pushes = 1,
                )
            } else {
                emitObjectEquality(codegen, left, right)
            }
            return true
        }
        if (leftType is DotNetIlValueType.NullableValue || rightType is DotNetIlValueType.NullableValue) {
            val exactLiftedShape = left.isDotNetNullLike() || right.isDotNetNullLike() ||
                    (leftType is DotNetIlValueType.NullableValue && leftType == rightType) ||
                    (leftType is DotNetIlValueType.NullableValue && rightType == leftType.elementType) ||
                    (rightType is DotNetIlValueType.NullableValue && leftType == rightType.elementType)
            if (!referenceEquality && !exactLiftedShape && leftType != null && rightType != null) {
                emitObjectEquality(codegen, left, right)
                return true
            }
            emitNullablePrimitiveEquality(codegen, left, right, leftType, rightType)
            return true
        }
        // A null-only operand (the null literal, or a `Nothing?`-typed value such as a
        // when-subject temporary the frontend narrowed to definitely-null) against a PLAIN
        // primitive: statically false — a shape smartcast when-subjects routinely produce
        // (`when (x) { null -> ... }` after `x` narrowed to Int). Both operands are still
        // evaluated in order for their side effects; the JVM compiles the same comparison to a
        // constant-false with the operand evaluated.
        if ((left.isDotNetNullLike() && rightType.isDotNetPrimitiveValue()) ||
            (right.isDotNetNullLike() && leftType.isDotNetPrimitiveValue())
        ) {
            for ([operand, operandType] in listOf(left to leftType, right to rightType)) {
                if (operand.isNullConst()) continue
                codegen.emitExpression(operand, operandType!!)
                codegen.emit("pop", pops = 1)
            }
            codegen.emit("ldc.i4.0", pushes = 1)
            return true
        }
        val operandType = call.dotNetEqualityOperandType(codegen) ?: if (
            !referenceEquality && leftType != null && rightType != null
        ) {
            emitObjectEquality(codegen, left, right)
            return true
        } else {
            dotNetUnsupported(
                "equality comparison of unsupported operand types " +
                        "${left.type.render()} (${leftType?.nameInSignature ?: "unmapped"}) and " +
                        "${right.type.render()} (${rightType?.nameInSignature ?: "unmapped"})"
            )
        }

        if (!referenceEquality && !ieee754FloatingPoint &&
            (operandType == DotNetIlValueType.Float32 || operandType == DotNetIlValueType.Float64)
        ) {
            codegen.emitExpression(left, operandType)
            val leftSlot = codegen.spillToSyntheticLocal(operandType, "<eqFloatLeft>")
            codegen.emitExpression(right, operandType)
            val rightSlot = codegen.spillToSyntheticLocal(operandType, "<eqFloatRight>")
            emitTotalOrderFloatingEquality(codegen, operandType, leftSlot, rightSlot, "eqFloat")
            return true
        }

        codegen.emitExpression(left, operandType)
        codegen.emitExpression(right, operandType)
        when (operandType) {
            DotNetIlValueType.Boolean,
            DotNetIlValueType.Int8,
            DotNetIlValueType.Int16,
            DotNetIlValueType.Int32,
            DotNetIlValueType.Int64,
            DotNetIlValueType.Float32,
            DotNetIlValueType.Float64,
            DotNetIlValueType.Char,
                -> codegen.emit("ceq", pops = 2, pushes = 1)
            DotNetIlValueType.String -> {
                if (referenceEquality) {
                    codegen.emit("ceq", pops = 2, pushes = 1)
                } else {
                    codegen.emit(
                        "call bool ${codegen.coreLibraryReference}System.String::op_Equality(string, string)",
                        pops = 2,
                        pushes = 1,
                    )
                }
            }
            // Kotlin primitive arrays inherit identity-based Any.equals on the mature JVM target.
            // Their CLR representation is a Kotlin-owned reference wrapper with the same identity
            // behavior; generic arrays remain System.Array references. Therefore both `==` and
            // `===` are `ceq` here (structural comparison remains contentEquals).
            is DotNetIlValueType.PrimitiveArray,
            is DotNetIlValueType.GenericArray,
            is DotNetIlValueType.ErasedGenericArray,
                -> codegen.emit("ceq", pops = 2, pushes = 1)
            is DotNetIlValueType.UserClass, is DotNetIlValueType.MappedClass, DotNetIlValueType.Object,
            is DotNetIlValueType.GenericInstance,
                -> {
                // Reference equality on object references is a plain `ceq` (probe-verified;
                // `object`-typed operands included, nullprobe_s8). `x == null` shares it: Kotlin
                // defines a null-literal comparison as a pure reference check that never calls
                // `equals` (JVM precedent: the Equals intrinsic rewrites `isNullConst` operands
                // to an `ifnull` check). General `==` routes through the Kotlin runtime helper,
                // which null-checks the left side before virtual System.Object::Equals dispatch.
                // Mapped exceptions and instantiated generic classes follow the same rules.
                if (referenceEquality || left.isNullConst() || right.isNullConst()) {
                    codegen.emit("ceq", pops = 2, pushes = 1)
                } else {
                    codegen.emit(DotNetRuntimeLibraryHelpers.areEqualCallInstruction, pops = 2, pushes = 1)
                }
            }
            is DotNetIlValueType.NullableValue ->
                error("Internal .NET backend error: nullable-primitive equality operands handled above")
            is DotNetIlValueType.TypeParameter ->
                error("Internal .NET backend error: type-parameter equality operands rejected above")
        }
        return true
    }

    /** Boxes/widens two operands to System.Object and calls the null-safe runtime helper. */
    private fun emitObjectEquality(
        codegen: DotNetIlExpressionCodegen,
        left: IrExpression,
        right: IrExpression,
    ) {
        codegen.emitExpression(left, DotNetIlValueType.Object)
        codegen.emitExpression(right, DotNetIlValueType.Object)
        codegen.emit(DotNetRuntimeLibraryHelpers.areEqualCallInstruction, pops = 2, pushes = 1)
    }

    /** The nullable-primitive `==` shapes; see the class KDoc. Pushes the `bool` result. */
    private fun emitNullablePrimitiveEquality(
        codegen: DotNetIlExpressionCodegen,
        left: IrExpression,
        right: IrExpression,
        leftType: DotNetIlValueType?,
        rightType: DotNetIlValueType?,
    ) {
        fun usesTotalOrder(type: DotNetIlValueType): Boolean =
            !ieee754FloatingPoint &&
                    (type == DotNetIlValueType.Float32 || type == DotNetIlValueType.Float64)

        when {
            left.isDotNetNullLike() || right.isDotNetNullLike() -> {
                // `T? == null` / `T? === null` (or against a Nothing?-typed definitely-null
                // value): a negated get_HasValue. Operands stay evaluated left-to-right; the
                // null-only side emits nothing when it is the bare null literal.
                val leftIsNull = left.isDotNetNullLike()
                val nullableOperand = if (leftIsNull) right else left
                val nullableType = (if (leftIsNull) rightType else leftType) as? DotNetIlValueType.NullableValue
                    ?: dotNetUnsupported(
                        "equality comparison between ${left.type.render()} and ${right.type.render()} is not supported"
                    )
                val slot: DotNetIlSlot.Local
                if (leftIsNull) {
                    emitDiscardedNullLikeOperand(codegen, left, leftType)
                    codegen.emitExpression(nullableOperand, nullableType)
                    slot = codegen.spillToSyntheticLocal(nullableType, "<eq>")
                } else {
                    codegen.emitExpression(nullableOperand, nullableType)
                    slot = codegen.spillToSyntheticLocal(nullableType, "<eq>")
                    emitDiscardedNullLikeOperand(codegen, right, rightType)
                }
                codegen.emit(loadLocalAddressInstruction(slot.index), pushes = 1)
                codegen.emit(nullableType.hasValueInstruction, pops = 1, pushes = 1)
                codegen.emit("ldc.i4.0", pushes = 1)
                codegen.emit("ceq", pops = 2, pushes = 1)
            }
            referenceEquality -> dotNetUnsupported(
                "identity comparison between nullable-primitive values is not supported: the operands box " +
                        "at the object boundary and reference identity of separately boxed values is unrelated " +
                        "to value equality (Kotlin deprecates identity checks on boxed primitives)"
            )
            leftType is DotNetIlValueType.NullableValue && leftType == rightType -> {
                codegen.emitExpression(left, leftType)
                val leftSlot = codegen.spillToSyntheticLocal(leftType, "<eqLeft>")
                codegen.emitExpression(right, leftType)
                val rightSlot = codegen.spillToSyntheticLocal(leftType, "<eqRight>")
                if (usesTotalOrder(leftType.elementType)) {
                    codegen.emit(loadLocalAddressInstruction(leftSlot.index), pushes = 1)
                    codegen.emit(leftType.getValueOrDefaultInstruction, pops = 1, pushes = 1)
                    val leftValueSlot = codegen.spillToSyntheticLocal(leftType.elementType, "<eqFloatLeft>")
                    codegen.emit(loadLocalAddressInstruction(rightSlot.index), pushes = 1)
                    codegen.emit(leftType.getValueOrDefaultInstruction, pops = 1, pushes = 1)
                    val rightValueSlot = codegen.spillToSyntheticLocal(leftType.elementType, "<eqFloatRight>")
                    emitTotalOrderFloatingEquality(
                        codegen,
                        leftType.elementType,
                        leftValueSlot,
                        rightValueSlot,
                        "eqNullableFloat",
                    )
                } else {
                    codegen.emit(loadLocalAddressInstruction(leftSlot.index), pushes = 1)
                    codegen.emit(leftType.getValueOrDefaultInstruction, pops = 1, pushes = 1)
                    codegen.emit(loadLocalAddressInstruction(rightSlot.index), pushes = 1)
                    codegen.emit(leftType.getValueOrDefaultInstruction, pops = 1, pushes = 1)
                    codegen.emit("ceq", pops = 2, pushes = 1)
                }
                codegen.emit(loadLocalAddressInstruction(leftSlot.index), pushes = 1)
                codegen.emit(leftType.hasValueInstruction, pops = 1, pushes = 1)
                codegen.emit(loadLocalAddressInstruction(rightSlot.index), pushes = 1)
                codegen.emit(leftType.hasValueInstruction, pops = 1, pushes = 1)
                codegen.emit("ceq", pops = 2, pushes = 1)
                codegen.emit("and", pops = 2, pushes = 1)
            }
            leftType is DotNetIlValueType.NullableValue && rightType == leftType.elementType -> {
                codegen.emitExpression(left, leftType)
                val slot = codegen.spillToSyntheticLocal(leftType, "<eq>")
                if (usesTotalOrder(leftType.elementType)) {
                    codegen.emit(loadLocalAddressInstruction(slot.index), pushes = 1)
                    codegen.emit(leftType.getValueOrDefaultInstruction, pops = 1, pushes = 1)
                    val leftValueSlot = codegen.spillToSyntheticLocal(leftType.elementType, "<eqFloatLeft>")
                    codegen.emitExpression(right, rightType)
                    val rightValueSlot = codegen.spillToSyntheticLocal(rightType, "<eqFloatRight>")
                    emitTotalOrderFloatingEquality(
                        codegen,
                        leftType.elementType,
                        leftValueSlot,
                        rightValueSlot,
                        "eqNullableFloat",
                    )
                } else {
                    codegen.emit(loadLocalAddressInstruction(slot.index), pushes = 1)
                    codegen.emit(leftType.getValueOrDefaultInstruction, pops = 1, pushes = 1)
                    codegen.emitExpression(right, rightType)
                    codegen.emit("ceq", pops = 2, pushes = 1)
                }
                codegen.emit(loadLocalAddressInstruction(slot.index), pushes = 1)
                codegen.emit(leftType.hasValueInstruction, pops = 1, pushes = 1)
                codegen.emit("and", pops = 2, pushes = 1)
            }
            rightType is DotNetIlValueType.NullableValue && leftType == rightType.elementType -> {
                // Kotlin evaluates left-to-right, so the plain left value is spilled too: the
                // nullable right side must be fully evaluated before any of its members are read.
                codegen.emitExpression(left, leftType)
                val valueSlot = codegen.spillToSyntheticLocal(leftType, "<eqLeft>")
                codegen.emitExpression(right, rightType)
                val nullableSlot = codegen.spillToSyntheticLocal(rightType, "<eqRight>")
                if (usesTotalOrder(rightType.elementType)) {
                    codegen.emit(loadLocalAddressInstruction(nullableSlot.index), pushes = 1)
                    codegen.emit(rightType.getValueOrDefaultInstruction, pops = 1, pushes = 1)
                    val rightValueSlot = codegen.spillToSyntheticLocal(rightType.elementType, "<eqFloatRight>")
                    emitTotalOrderFloatingEquality(
                        codegen,
                        rightType.elementType,
                        valueSlot,
                        rightValueSlot,
                        "eqNullableFloat",
                    )
                } else {
                    codegen.emit(loadLocalInstruction(valueSlot.index), pushes = 1)
                    codegen.emit(loadLocalAddressInstruction(nullableSlot.index), pushes = 1)
                    codegen.emit(rightType.getValueOrDefaultInstruction, pops = 1, pushes = 1)
                    codegen.emit("ceq", pops = 2, pushes = 1)
                }
                codegen.emit(loadLocalAddressInstruction(nullableSlot.index), pushes = 1)
                codegen.emit(rightType.hasValueInstruction, pops = 1, pushes = 1)
                codegen.emit("and", pops = 2, pushes = 1)
            }
            else -> dotNetUnsupported(
                "equality comparison between ${left.type.render()} and ${right.type.render()} is not supported " +
                        "(nullable-primitive '==' requires both operands to share one primitive type)"
            )
        }
    }

    /**
     * Evaluates a null-only operand for its side effects: the bare null literal emits nothing;
     * a `Nothing?`-typed expression is emitted through the `Kotlin.Nothing` carrier and popped.
     */
    private fun emitDiscardedNullLikeOperand(
        codegen: DotNetIlExpressionCodegen,
        operand: IrExpression,
        operandType: DotNetIlValueType?,
    ) {
        if (operand.isNullConst()) return
        codegen.emitExpression(operand, operandType ?: DotNetIlValueType.Object)
        codegen.emit("pop", pops = 1)
    }
}

/**
 * Annotation floating equality follows the JVM annotation contract rather than Kotlin's ordinary
 * IEEE `==`: every NaN payload compares equal, while positive and negative zero stay distinct.
 * The generated comparison remains local to the annotation member and introduces no runtime ABI.
 */
private class DotNetIlAnnotationFloatingEqualsIntrinsic(
    private val operandType: DotNetIlValueType,
) : DotNetIlIntrinsicMethod() {
    init {
        check(operandType == DotNetIlValueType.Float32 || operandType == DotNetIlValueType.Float64)
    }

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Boolean || call.arguments.size != 2) return false
        val left = call.arguments[0] ?: dotNetUnsupported("missing left annotation floating value")
        val right = call.arguments[1] ?: dotNetUnsupported("missing right annotation floating value")
        codegen.emitExpression(left, operandType)
        val leftSlot = codegen.spillToSyntheticLocal(operandType, "<annotationFloatLeft>")
        codegen.emitExpression(right, operandType)
        val rightSlot = codegen.spillToSyntheticLocal(operandType, "<annotationFloatRight>")
        emitTotalOrderFloatingEquality(codegen, operandType, leftSlot, rightSlot, "annotationFloat")
        return true
    }
}

/**
 * Kotlin's generic floating equality, shared by generated value/data members and annotation
 * values. This is deliberately distinct from the `ieee754equals` builtin: it is reflexive for
 * every NaN payload and distinguishes signed zero, matching JVM `NonIEEE754FloatComparison` and
 * Wasm's generic `eqeq` fallback. Both operands have already been evaluated and spilled, so the
 * control-flow expansion cannot disturb source evaluation order.
 */
private fun emitTotalOrderFloatingEquality(
    codegen: DotNetIlExpressionCodegen,
    operandType: DotNetIlValueType,
    leftSlot: DotNetIlSlot.Local,
    rightSlot: DotNetIlSlot.Local,
    labelStem: String,
) {
    check(operandType == DotNetIlValueType.Float32 || operandType == DotNetIlValueType.Float64)
    val nanLabel = codegen.nextLabel("${labelStem}NaN")
    val equalLabel = codegen.nextLabel("${labelStem}Equal")
    val endLabel = codegen.nextLabel("${labelStem}End")
    val zero = if (operandType == DotNetIlValueType.Float32) "ldc.r4 0.0" else "ldc.r8 0.0"
    val one = if (operandType == DotNetIlValueType.Float32) "ldc.r4 1.0" else "ldc.r8 1.0"

    // Ordinary equal nonzero values are done. Equal zero values need reciprocal signs to
    // distinguish +0.0 from -0.0; unordered values take the canonical all-NaNs-equal path.
    codegen.emit(loadLocalInstruction(leftSlot.index), pushes = 1)
    codegen.emit(loadLocalInstruction(rightSlot.index), pushes = 1)
    codegen.emit("ceq", pops = 2, pushes = 1)
    codegen.emitBranch("brfalse", nanLabel, pops = 1)
    codegen.emit(loadLocalInstruction(leftSlot.index), pushes = 1)
    codegen.emit(zero, pushes = 1)
    codegen.emit("ceq", pops = 2, pushes = 1)
    codegen.emitBranch("brfalse", equalLabel, pops = 1)
    codegen.emit(one, pushes = 1)
    codegen.emit(loadLocalInstruction(leftSlot.index), pushes = 1)
    codegen.emit("div", pops = 2, pushes = 1)
    codegen.emit(one, pushes = 1)
    codegen.emit(loadLocalInstruction(rightSlot.index), pushes = 1)
    codegen.emit("div", pops = 2, pushes = 1)
    codegen.emit("ceq", pops = 2, pushes = 1)
    codegen.emitGoto(endLabel)

    codegen.emitLabel(nanLabel)
    for (slot in listOf(leftSlot, rightSlot)) {
        codegen.emit(loadLocalInstruction(slot.index), pushes = 1)
        codegen.emit(loadLocalInstruction(slot.index), pushes = 1)
        codegen.emit("ceq", pops = 2, pushes = 1)
        codegen.emit("ldc.i4.0", pushes = 1)
        codegen.emit("ceq", pops = 2, pushes = 1)
    }
    codegen.emit("and", pops = 2, pushes = 1)
    codegen.emitGoto(endLabel)

    codegen.emitLabel(equalLabel)
    codegen.emit("ldc.i4.1", pushes = 1)
    codegen.emitLabel(endLabel)
}

/**
 * Whether this expression can only ever evaluate to `null`: the null literal itself, or any
 * `Nothing?`-typed value (the frontend narrows definitely-null values — e.g. a when-subject
 * temporary initialized from a known-null val — to `Nothing?`).
 */
private fun IrExpression.isDotNetNullLike(): Boolean =
    isNullConst() || type.isNullableNothing()

/** The plain primitive value types of the supported subset. */
private fun DotNetIlValueType?.isDotNetPrimitiveValue(): Boolean = when (this) {
    DotNetIlValueType.Boolean,
    DotNetIlValueType.Int8,
    DotNetIlValueType.Int16,
    DotNetIlValueType.Int32,
    DotNetIlValueType.Int64,
    DotNetIlValueType.Float32,
    DotNetIlValueType.Float64,
    DotNetIlValueType.Char,
        -> true
    else -> false
}

/**
 * The CHECK_NOT_NULL builtin — Kotlin `!!` (JVM precedent: the checkNotNull intrinsic backed by
 * `Intrinsics.checkNotNull`, whose NPE carries no message):
 * - on a [nullable primitive][DotNetIlValueType.NullableValue]: a `get_HasValue` branch past a
 *   throw of the mapped Kotlin NPE, then `GetValueOrDefault` extraction
 *   ([emitNullableUnwrapOrThrowNpe][DotNetIlExpressionCodegen.emitNullableUnwrapOrThrowNpe]) —
 *   `get_Value` is never used, its InvalidOperationException would surface as the wrong Kotlin
 *   exception type;
 * - on a reference (nullable String/user class/interface/exception/`Any?`): `dup`/`brtrue` past
 *   the same throw, the value flows through unchanged
 *   ([emitReferenceNotNullOrThrowNpe][DotNetIlExpressionCodegen.emitReferenceNotNullOrThrowNpe]);
 * - on an already non-null primitive (`x!!` where `x: Int` — legal, frontend-warned Kotlin):
 *   the bare value, no runtime representation of null exists to check.
 * The throw is `newobj System.NullReferenceException::.ctor()` + `throw` (probe-verified,
 * boxprobe_s4), catchable as `catch (e: NullPointerException)` through the existing
 * [DotNetMappedExceptions] registry mapping.
 */
private object DotNetIlCheckNotNullIntrinsic : DotNetIlIntrinsicMethod() {
    override fun naturalReturnType(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
    ): DotNetIlValueType? = call.arguments.singleOrNull()
        ?.let(codegen::genericOwnerCapabilityNaturalTypeOrNull)

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (call.arguments.size != 1) return false
        val argument = call.arguments.single()
            ?: dotNetUnsupported("missing operand of the '!!' operator")
        val argumentType = codegen.genericOwnerCapabilityNaturalTypeOrNull(argument)
            ?: codegen.toDotNetIlValueType(argument.type)
            ?: dotNetUnsupported("'!!' on a value of unsupported type ${argument.type.render()}")
        when {
            argumentType is DotNetIlValueType.NullableValue -> {
                if (!argumentType.elementType.isDotNetAssignableTo(expectedType)) {
                    dotNetUnsupported(
                        "'!!' produces ${argumentType.elementType.nameInSignature} " +
                                "where ${expectedType.nameInSignature} is expected"
                    )
                }
                codegen.emitExpression(argument, argumentType)
                codegen.emitNullableUnwrapOrThrowNpe(argumentType)
            }
            argumentType.isDotNetReferenceShaped() -> {
                if (
                    argumentType == DotNetIlValueType.Object &&
                    expectedType is DotNetIlValueType.TypeParameter
                ) {
                    // Open `T?` has the declaration-stable boxed-or-null object carrier. Check
                    // Kotlin nullability before recovering the reified non-null `T`; unbox.any
                    // is correct for both value-type and reference-type substitutions.
                    codegen.emitExpression(argument, argumentType)
                    codegen.emitReferenceNotNullOrThrowNpe()
                    codegen.emit(
                        "unbox.any ${expectedType.nameInSignature}",
                        pops = 1,
                        pushes = 1,
                    )
                } else if (
                    argumentType == DotNetIlValueType.Object &&
                    expectedType == DotNetIlValueType.String
                ) {
                    // `T?` with `T : String` still enters through the uniform object carrier,
                    // while non-null T keeps the established string slot. Null-check first, then
                    // recover that statically guaranteed bound without changing the generic ABI.
                    codegen.emitExpression(argument, argumentType)
                    codegen.emitReferenceNotNullOrThrowNpe()
                    codegen.emit("castclass string", pops = 1, pushes = 1)
                } else if (!argumentType.isDotNetAssignableTo(expectedType)) {
                    dotNetUnsupported(
                        "'!!' produces ${argumentType.nameInSignature} " +
                                "where ${expectedType.nameInSignature} is expected"
                    )
                } else {
                    codegen.emitExpression(argument, argumentType)
                    codegen.emitReferenceNotNullOrThrowNpe()
                }
            }
            else -> codegen.emitExpression(argument, expectedType)
        }
        return true
    }
}

/** Constructs the exact signed range object for a materialized primitive `rangeTo` call. */
private class DotNetIlPrimitiveRangeToIntrinsic(
    private val receiverType: DotNetIlValueType,
    private val argumentType: DotNetIlValueType,
    private val elementType: DotNetIlValueType,
    private val rangeClassName: String,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        val rangeType = expectedType as? DotNetIlValueType.UserClass ?: return false
        if (rangeType.classInfo.ilClassName != rangeClassName || call.arguments.size != 2) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing receiver of primitive 'rangeTo'")
        val argument = call.arguments[1]
            ?: dotNetUnsupported("missing argument of primitive 'rangeTo'")
        codegen.emitWidenedOperand(receiver, receiverType, elementType)
        codegen.emitWidenedOperand(argument, argumentType, elementType)
        codegen.emit(
            "newobj instance void ${rangeType.classInfo.ilTypeRef}::.ctor(" +
                    "${elementType.nameInSignature}, ${elementType.nameInSignature})",
            pops = 2,
            pushes = 1,
        )
        return true
    }
}

/**
 * Numeric `compareTo` across every Common primitive overload. Integer comparisons return
 * normalized -1/0/1 without subtraction overflow. Floating comparisons delegate to the runtime
 * bit-aware total ordering, distinct from relational IEEE comparison around NaN and signed zero.
 */
private class DotNetIlNumericCompareToIntrinsic(
    private val receiverType: DotNetIlValueType,
    private val argumentType: DotNetIlValueType,
    private val computationType: DotNetIlValueType,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Int32 || call.arguments.size != 2) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing receiver of numeric 'compareTo'")
        val argument = call.arguments[1]
            ?: dotNetUnsupported("missing argument of numeric 'compareTo'")

        codegen.emitWidenedOperand(receiver, receiverType, computationType)
        codegen.emitWidenedOperand(argument, argumentType, computationType)
        when (computationType) {
            DotNetIlValueType.Float32 -> codegen.emit(
                DotNetRuntimeLibraryHelpers.compareFloatCallInstruction,
                pops = 2,
                pushes = 1,
            )
            DotNetIlValueType.Float64 -> codegen.emit(
                DotNetRuntimeLibraryHelpers.compareDoubleCallInstruction,
                pops = 2,
                pushes = 1,
            )
            DotNetIlValueType.Int32, DotNetIlValueType.Int64 -> {
                val rightSlot = codegen.spillToSyntheticLocal(computationType, "<cmpRight>")
                val leftSlot = codegen.spillToSyntheticLocal(computationType, "<cmpLeft>")
                val lessLabel = codegen.nextLabel("compareLess")
                val greaterLabel = codegen.nextLabel("compareGreater")
                val endLabel = codegen.nextLabel("compareEnd")
                codegen.emit(loadLocalInstruction(leftSlot.index), pushes = 1)
                codegen.emit(loadLocalInstruction(rightSlot.index), pushes = 1)
                codegen.emit("clt", pops = 2, pushes = 1)
                codegen.emitBranch("brtrue", lessLabel, pops = 1)
                codegen.emit(loadLocalInstruction(leftSlot.index), pushes = 1)
                codegen.emit(loadLocalInstruction(rightSlot.index), pushes = 1)
                codegen.emit("cgt", pops = 2, pushes = 1)
                codegen.emitBranch("brtrue", greaterLabel, pops = 1)
                codegen.emit("ldc.i4.0", pushes = 1)
                codegen.emitGoto(endLabel)
                codegen.emitLabel(lessLabel)
                codegen.emit("ldc.i4.m1", pushes = 1)
                codegen.emitGoto(endLabel)
                codegen.emitLabel(greaterLabel)
                codegen.emit("ldc.i4.1", pushes = 1)
                codegen.emitLabel(endLabel)
            }
            else -> error(
                "Internal .NET backend error: unsupported compareTo computation type " +
                        computationType.nameInSignature
            )
        }
        return true
    }
}

/**
 * Logical `Comparable<T>.compareTo` at the erased boundary. Both operands are boxed exactly once
 * and the runtime distinguishes Kotlin's built-in carriers before falling through to the same
 * object's canonical `System.IComparable` slot. This is semantic dispatch, not an adapter: the
 * helper must preserve String's ordinal order and Kotlin's Float/Double NaN and signed-zero order.
 */
private object DotNetIlComparableCompareToIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Int32 || call.arguments.size != 2) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing receiver of 'Comparable.compareTo'")
        val argument = call.arguments[1]
            ?: dotNetUnsupported("missing argument of 'Comparable.compareTo'")
        codegen.emitExpression(receiver, DotNetIlValueType.Object)
        codegen.emitExpression(argument, DotNetIlValueType.Object)
        codegen.emit(
            DotNetRuntimeLibraryHelpers.comparableCompareToCallInstruction,
            pops = 2,
            pushes = 1,
        )
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
 * Kotlin `Int`/`Long` shifts. ECMA-335 `shl`/`shr`/`shr.un` mask the count to five or six bits,
 * respectively, which is the same modulo-32/modulo-64 rule as Kotlin/JVM. The count remains an
 * int32 even for a long receiver; widening it with the ordinary numeric binary path would produce
 * an invalid CIL stack shape.
 */
private class DotNetIlIntegralShiftIntrinsic(
    private val instruction: String,
    private val receiverType: DotNetIlValueType,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != receiverType || call.arguments.size != 2) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing receiver of an integral '$instruction' operator")
        val count = call.arguments[1]
            ?: dotNetUnsupported("missing count of an integral '$instruction' operator")
        codegen.emitExpression(receiver, receiverType)
        codegen.emitExpression(count, DotNetIlValueType.Int32)
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

        if (resultType == DotNetIlValueType.Float32 || resultType == DotNetIlValueType.Float64) {
            codegen.emitWidenedOperand(argument, argumentType, resultType)
            codegen.emit(operatorName, pops = 2, pushes = 1)
            return true
        }

        val zeroLoad = if (resultType == DotNetIlValueType.Int64) "ldc.i8 0" else "ldc.i4.0"
        val constantDivisor: Long? = when (val value = (argument as? IrConst)?.value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
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
    private val resultType: DotNetIlValueType,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != resultType || call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing receiver of a unary numeric operator")
        codegen.emitExpression(receiver, operandType)
        if (instruction != null) {
            codegen.emit(instruction, pops = 1, pushes = 1)
        }
        return true
    }
}

/** `inc`/`dec`: receiver plus/minus one, narrowed back for Byte/Short as Common requires. */
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
            DotNetIlValueType.Float32 -> "ldc.r4 1.0"
            DotNetIlValueType.Float64 -> "ldc.r8 1.0"
            is DotNetIlValueType.UserClass, is DotNetIlValueType.MappedClass ->
                dotNetUnsupported("numeric increment of a class instance is not supported")
            else -> "ldc.i4.1"
        }
        codegen.emit(oneLoad, pushes = 1)
        codegen.emit(instruction, pops = 2, pushes = 1)
        when (operandType) {
            DotNetIlValueType.Int8 -> codegen.emit("conv.i1", pops = 1, pushes = 1)
            DotNetIlValueType.Int16 -> codegen.emit("conv.i2", pops = 1, pushes = 1)
            else -> Unit
        }
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
 * - Byte/Short use `conv.i1`/`conv.i2` for unchecked narrowing and otherwise their sign-extended
 *   int32 stack value; Int -> Long: `conv.i8` (sign-extend, = `i2l`); Long -> Int: `conv.i4` (unchecked wrap to the
 *   low 32 bits, = `l2i`; the non-`.ovf` `conv.*` opcodes never throw)
 * - Int/Long/Char -> Double: `conv.r8` (= `i2d`/`l2d`)
 * - Int -> Char: `conv.u2` (16-bit zero-extending truncation, = `i2c`); Char -> Int/`Char.code`:
 *   no instruction, the char already sits on the evaluation stack as a zero-extended int32
 * - identity conversions (`Int.toInt()` etc.): no instruction
 *
 * Float/Double -> Byte/Short/Int/Long saturating-then-narrowing conversions live in
 * [DotNetIlFloatingToIntegralIntrinsic].
 */
private class DotNetIlNumberConversionIntrinsic(
    private val fromType: DotNetIlValueType,
    private val toType: DotNetIlValueType,
    private val instructions: List<String>,
    override val excludesDeclarationFromCodegen: Boolean = false,
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

/** One Common `Number` virtual conversion through the classified object carrier. */
private class DotNetIlNumberCarrierConversionIntrinsic(
    private val runtimeMethodName: String,
    private val resultType: DotNetIlValueType,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != resultType || call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing receiver of a Common Number conversion")
        if (call.superQualifierSymbol?.owner?.isDotNetNumberClass() == true) {
            if (runtimeMethodName != "NumberToChar" || resultType != DotNetIlValueType.Char) {
                dotNetUnsupported("abstract Common Number conversion cannot be called through super")
            }
            codegen.emitExpression(receiver, DotNetRuntimeTypes.numberImplementationType)
            codegen.emit(
                "call instance char ${DotNetRuntimeTypes.numberImplementationType.ilTypeRef}::toChar()",
                pops = 1,
                pushes = 1,
            )
            return true
        }
        codegen.emitExpression(receiver, DotNetIlValueType.Object)
        codegen.emit(
            DotNetRuntimeLibraryHelpers.numberConversionCallInstruction(runtimeMethodName, resultType),
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/**
 * `Float`/`Double` integral conversions with Common/JVM semantics: narrow targets first
 * use the Int result and then wrap exactly like `toInt().toByte()/toShort()`; NaN -> 0, above the target
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
private class DotNetIlFloatingToIntegralIntrinsic(
    private val sourceType: DotNetIlValueType,
    private val targetType: DotNetIlValueType,
    private val resultType: DotNetIlValueType = targetType,
    private val resultInstructions: List<String> = emptyList(),
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != resultType || call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported(
                "missing receiver of a ${sourceType.nameInSignature} to ${targetType.nameInSignature} conversion"
            )
        check(sourceType == DotNetIlValueType.Float32 || sourceType == DotNetIlValueType.Float64)
        codegen.emitExpression(receiver, sourceType)
        // A Float promotes exactly to Double; sharing one guarded conversion path keeps the
        // profile-independent saturation boundaries literal and avoids rounded float32 bounds.
        if (sourceType == DotNetIlValueType.Float32) {
            codegen.emit("conv.r8", pops = 1, pushes = 1)
        }

        val isLongTarget = targetType == DotNetIlValueType.Int64
        check(
            isLongTarget || targetType in setOf(
                DotNetIlValueType.Int8,
                DotNetIlValueType.Int16,
                DotNetIlValueType.Int32,
            )
        ) { "unsupported floating integral conversion target ${targetType.nameInSignature}" }
        // Preserve the established Double label stem so adding Float does not churn unrelated
        // IL goldens; the new Float path gets its own descriptive local-label namespace.
        val labelStem = if (sourceType == DotNetIlValueType.Float32) "fp2i" else "d2i"
        val nanLabel = codegen.nextLabel("${labelStem}NaN")
        val maxLabel = codegen.nextLabel("${labelStem}Max")
        val minLabel = codegen.nextLabel("${labelStem}Min")
        val endLabel = codegen.nextLabel("${labelStem}End")

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
        when (targetType) {
            DotNetIlValueType.Int8 -> codegen.emit("conv.i1", pops = 1, pushes = 1)
            DotNetIlValueType.Int16 -> codegen.emit("conv.i2", pops = 1, pushes = 1)
            else -> Unit
        }
        for (instruction in resultInstructions) {
            codegen.emit(instruction, pops = 1, pushes = 1)
        }
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
    override val excludesDeclarationFromCodegen: Boolean = false,
) : DotNetIlIntrinsicMethod() {
    override fun tryEmitConstructorAsExpression(
        call: IrConstructorCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean = dotNetUnsupported(reason)

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

/** Constructors for the private BCL storage of the Kotlin-owned StringBuilder wrapper. */
private class DotNetIlStringBuilderConstructorIntrinsic(
    private val parameterTypes: List<DotNetIlValueType>,
) : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Object || call.arguments.size != parameterTypes.size) return false
        for (index in parameterTypes.indices) {
            codegen.emitExpression(
                call.arguments[index]
                    ?: dotNetUnsupported("missing private StringBuilder constructor argument"),
                parameterTypes[index],
            )
        }
        val parameters = parameterTypes.joinToString(", ") { it.nameInSignature }
        codegen.emit(
            "newobj instance void ${codegen.coreLibraryReference}System.Text.StringBuilder::.ctor($parameters)",
            pops = parameterTypes.size,
            pushes = 1,
        )
        return true
    }
}

/** Deterministic CLR capacity-overflow failure without publishing a .NET-only Kotlin exception. */
private object DotNetIlStringBuilderCapacityOverflowIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (call.arguments.isNotEmpty()) return false
        codegen.emitParameterlessExceptionThrow(
            exceptionTypeRef = "${codegen.coreLibraryReference}System.OutOfMemoryException",
            valuePosition = true,
        )
        return true
    }

    override fun tryEmitAsStatement(call: IrCall, codegen: DotNetIlExpressionCodegen): Boolean {
        if (call.arguments.isNotEmpty()) return false
        codegen.emitParameterlessExceptionThrow(
            exceptionTypeRef = "${codegen.coreLibraryReference}System.OutOfMemoryException",
            valuePosition = false,
        )
        return true
    }
}

/** Value-returning members on the private BCL StringBuilder storage. */
private class DotNetIlStringBuilderValueIntrinsic(
    private val resultType: DotNetIlValueType,
    private val parameterTypes: List<DotNetIlValueType>,
    private val memberReference: String,
) : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != resultType || call.arguments.size != parameterTypes.size + 1) return false
        emitPrivateStringBuilderReceiver(call, codegen)
        for (index in parameterTypes.indices) {
            codegen.emitExpression(
                call.arguments[index + 1]
                    ?: dotNetUnsupported("missing private StringBuilder member argument"),
                parameterTypes[index],
            )
        }
        codegen.emit(
            memberReference.replace("%s", codegen.coreLibraryReference),
            pops = parameterTypes.size + 1,
            pushes = 1,
        )
        return true
    }
}

/** Effect-only members on the private BCL StringBuilder storage. */
private class DotNetIlStringBuilderVoidIntrinsic(
    private val parameterTypes: List<DotNetIlValueType>,
    private val memberReference: String,
    private val discardResult: Boolean = false,
) : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsStatement(call: IrCall, codegen: DotNetIlExpressionCodegen): Boolean {
        if (call.arguments.size != parameterTypes.size + 1) return false
        emitPrivateStringBuilderReceiver(call, codegen)
        for (index in parameterTypes.indices) {
            codegen.emitExpression(
                call.arguments[index + 1]
                    ?: dotNetUnsupported("missing private StringBuilder member argument"),
                parameterTypes[index],
            )
        }
        codegen.emit(
            memberReference.replace("%s", codegen.coreLibraryReference),
            pops = parameterTypes.size + 1,
            pushes = if (discardResult) 1 else 0,
        )
        if (discardResult) codegen.emit("pop", pops = 1)
        return true
    }
}

private fun emitPrivateStringBuilderReceiver(call: IrCall, codegen: DotNetIlExpressionCodegen) {
    val storage = call.arguments.firstOrNull()
        ?: dotNetUnsupported("missing private StringBuilder storage receiver")
    codegen.emitExpression(storage, DotNetIlValueType.Object)
    codegen.emit(
        "castclass ${codegen.coreLibraryReference}System.Text.StringBuilder",
        pops = 1,
        pushes = 1,
    )
}

/**
 * The target-private input primitive used by the ordinary Kotlin.Stdlib `readlnOrNull` body.
 * `Console.ReadLine` already implements the Common contract: it removes LF/CRLF and returns null
 * only when no input remains. The exact MemberRef was assembled and executed on Framework CLR 4,
 * CoreCLR 10, and from a netstandard2.0 library on both runtimes.
 */
private object DotNetIlReadLineIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (call.arguments.isNotEmpty() || expectedType != DotNetIlValueType.String) return false
        codegen.emit(
            "call string ${codegen.coreLibraryReference}System.Console::ReadLine()",
            pushes = 1,
        )
        return true
    }
}

/** Exact optional-reflection provider call into Runtime's generated-factory bootstrap. */
private object DotNetIlGeneratedMembersIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        val kClass = call.arguments.singleOrNull()
            ?: dotNetUnsupported("generated-member reflection bootstrap requires one KClass argument")
        val kClassType = codegen.toDotNetIlValueType(kClass.type)
            ?: dotNetUnsupported("generated-member reflection bootstrap has an unmapped KClass argument")
        codegen.emitExpression(kClass, kClassType)
        codegen.emit(
            "call class [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]Kotlin.Collections.Collection " +
                    "[${DotNetRuntimeLibrary.ASSEMBLY_NAME}]Kotlin.Runtime.Internal.KClassFactory::" +
                    "'GetGeneratedMembersV1'(class [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]Kotlin.KClass)",
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/** Exact optional-reflection call into Stdlib's KLIB-derived mapped/member catalog. */
private object DotNetIlStdlibMembersIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        val kClass = call.arguments.singleOrNull()
            ?: dotNetUnsupported("Stdlib-member reflection catalog requires one KClass argument")
        val kClassType = codegen.toDotNetIlValueType(kClass.type)
            ?: dotNetUnsupported("Stdlib-member reflection catalog has an unmapped KClass argument")
        val stdlibAssemblyName = codegen.stdlibAssemblyName
            ?: dotNetUnsupported("Stdlib-member reflection catalog requires Kotlin.Stdlib")
        codegen.recordAssemblyReference(stdlibAssemblyName)
        codegen.emitExpression(kClass, kClassType)
        codegen.emit(
            "call class [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]Kotlin.KCallable[] " +
                    "[$stdlibAssemblyName]${DotNetStdlibLibrary.MEMBER_REFLECTION_CATALOG_FACADE_IL_NAME.toIlIdentifier()}::" +
                    "'${DotNetStdlibLibrary.MEMBER_REFLECTION_CATALOG_FUNCTION_NAME}'(" +
                    "class [${DotNetRuntimeLibrary.ASSEMBLY_NAME}]Kotlin.KClass)",
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/**
 * Common `kotlin.io.print(Any?)`: first render through Kotlin's value-to-string rules, then use
 * the CLR string overload. Calling `Console.Write(object)` or numeric/Boolean overloads would
 * reintroduce CLR culture, floating-point, Boolean-casing, and null-rendering semantics.
 */
private object DotNetIlPrintIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsStatement(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
    ): Boolean {
        val argument = call.arguments.singleOrNull()
            ?: dotNetUnsupported("missing argument in a call to 'print'")
        codegen.emitStringValueExpression(argument)
        codegen.emit(
            "call void ${codegen.coreLibraryReference}System.Console::Write(string)",
            pops = 1,
        )
        return true
    }
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
 *   [DotNetRuntimeLibraryHelpers] runtime helper — Kotlin-parity rendering, with the
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
                codegen.emit("call void ${codegen.coreLibraryReference}System.Console::WriteLine()")
                true
            }
            1 -> {
                val argument = call.arguments.single()
                    ?: dotNetUnsupported("missing argument in a call to 'println'")
                val parameterType = call.symbol.owner.parameters.singleOrNull()?.type?.let(codegen::toDotNetIlValueType)
                when (parameterType) {
                    DotNetIlValueType.Char -> {
                        codegen.emitExpression(argument, DotNetIlValueType.Char)
                        codegen.emit("call void ${codegen.coreLibraryReference}System.Console::WriteLine(char)", pops = 1)
                    }
                    else -> {
                        // Int, Long, Double, String, Boolean and Any? — see the class KDoc for
                        // why the direct WriteLine(int32)/WriteLine(int64)/WriteLine(float64)/
                        // WriteLine(bool) overloads must not be used.
                        codegen.emitStringValueExpression(argument)
                        codegen.emit("call void ${codegen.coreLibraryReference}System.Console::WriteLine(string)", pops = 1)
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
        codegen.emit("call string ${codegen.coreLibraryReference}System.String::Concat(string, string)", pops = 2, pushes = 1)
        return true
    }
}

/** Common `CharSequence.length`, including the `String` override, through the classified carrier. */
private object DotNetIlCharSequenceLengthIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Int32 || call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing receiver of 'CharSequence.length'")
        codegen.emitExpression(receiver, DotNetIlValueType.Object)
        codegen.emit(DotNetRuntimeLibraryHelpers.charSequenceLengthCallInstruction, pops = 1, pushes = 1)
        return true
    }
}

/** Common `CharSequence.get`, including the `String` override, through the classified carrier. */
private object DotNetIlCharSequenceGetIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Char || call.arguments.size != 2) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing receiver of 'CharSequence.get'")
        val index = call.arguments[1]
            ?: dotNetUnsupported("missing index of 'CharSequence.get'")
        codegen.emitExpression(receiver, DotNetIlValueType.Object)
        codegen.emitExpression(index, DotNetIlValueType.Int32)
        codegen.emit(DotNetRuntimeLibraryHelpers.charSequenceGetCallInstruction, pops = 2, pushes = 1)
        return true
    }
}

/** Common `CharSequence.subSequence`, preserving either String or implementation identity. */
private object DotNetIlCharSequenceSubSequenceIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Object || call.arguments.size != 3) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing receiver of 'CharSequence.subSequence'")
        val startIndex = call.arguments[1]
            ?: dotNetUnsupported("missing start index of 'CharSequence.subSequence'")
        val endIndex = call.arguments[2]
            ?: dotNetUnsupported("missing end index of 'CharSequence.subSequence'")
        codegen.emitExpression(receiver, DotNetIlValueType.Object)
        codegen.emitExpression(startIndex, DotNetIlValueType.Int32)
        codegen.emitExpression(endIndex, DotNetIlValueType.Int32)
        codegen.emit(
            DotNetRuntimeLibraryHelpers.charSequenceSubSequenceCallInstruction,
            pops = 3,
            pushes = 1,
        )
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
 * An explicit Kotlin `Any.equals` call, including calls through fake/user overrides. Kotlin Any
 * is physically System.Object, so the ordinary path uses the runtime's null-safe object-boundary
 * helper; that helper preserves Kotlin boxed-Double semantics before falling through to the
 * existing virtual slot. A `super.equals` call deliberately uses non-virtual `call`, matching the
 * backend's established superclass dispatch rule.
 */
private object DotNetIlAnyEqualsIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Boolean || call.arguments.size != 2) return false
        val receiver = call.arguments[0]
            ?: dotNetUnsupported("missing receiver of 'Any.equals'")
        val argument = call.arguments[1]
            ?: dotNetUnsupported("missing argument of 'Any.equals'")
        codegen.emitExpression(receiver, DotNetIlValueType.Object)
        codegen.emitExpression(argument, DotNetIlValueType.Object)
        if (call.superQualifierSymbol == null) {
            codegen.emit(DotNetRuntimeLibraryHelpers.areEqualCallInstruction, pops = 2, pushes = 1)
        } else {
            codegen.emit(
                "call instance bool ${codegen.coreLibraryReference}System.Object::Equals(object)",
                pops = 2,
                pushes = 1,
            )
        }
        return true
    }
}

/** Kotlin `Any.hashCode`, with boxed primitive differences normalized by the runtime helper. */
private object DotNetIlAnyHashCodeIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.Int32 || call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing receiver of 'Any.hashCode'")
        codegen.emitExpression(receiver, DotNetIlValueType.Object)
        if (call.superQualifierSymbol == null) {
            codegen.emit(DotNetRuntimeLibraryHelpers.hashCodeCallInstruction, pops = 1, pushes = 1)
        } else {
            codegen.emit(
                "call instance int32 ${codegen.coreLibraryReference}System.Object::GetHashCode()",
                pops = 1,
                pushes = 1,
            )
        }
        return true
    }
}

/** Kotlin `Any.toString` bound to the existing virtual `System.Object::ToString()` slot. */
private object DotNetIlAnyToStringIntrinsic : DotNetIlIntrinsicMethod() {
    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.String || call.arguments.size != 1) return false
        val receiver = call.arguments.single()
            ?: dotNetUnsupported("missing receiver of 'Any.toString'")
        if (call.superQualifierSymbol == null) {
            // FIR can keep the Any symbol even when the statically known receiver is primitive.
            // Reuse the type-directed Kotlin conversion so Boolean stays lowercase and Double
            // keeps Kotlin's formatting; reference-shaped receivers still dispatch through
            // System.Object::ToString via the runtime's null-safe StringValueOf helper.
            codegen.emitStringValueExpression(receiver)
        } else {
            codegen.emitExpression(receiver, DotNetIlValueType.Object)
            codegen.emit(
                "call instance string ${codegen.coreLibraryReference}System.Object::ToString()",
                pops = 1,
                pushes = 1,
            )
        }
        return true
    }
}

/**
 * `Throwable.message` -> a `callvirt` of the corelib `System.Exception::get_Message()`
 * (probe-verified). Kotlin's `message` keeps its `String?` type. BCL-mapped exceptions inherit the
 * CLR's non-null default text (an accepted platform delta); exact runtime-owned exceptions can
 * reuse this virtual slot and return their Kotlin-owned nullable backing value.
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
            "callvirt instance string ${DotNetMappedExceptions.exceptionTypeRef(codegen.coreLibraryReference)}::get_Message()",
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
        val exceptionType = DotNetIlValueType.MappedClass(
            DotNetMappedExceptions.exceptionTypeRef(codegen.coreLibraryReference)
        )
        if (expectedType != exceptionType || call.arguments.size != 1) return false
        val receiver = call.dotNetMappedExceptionReceiver(codegen, "cause")
        codegen.emitExpression(receiver.first, receiver.second)
        codegen.emit(
            "callvirt instance ${exceptionType.nameInSignature} " +
                    "${DotNetMappedExceptions.exceptionTypeRef(codegen.coreLibraryReference)}::get_InnerException()",
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/** Common Throwable operations backed by the identity-associated Kotlin.Runtime service. */
private object DotNetIlThrowableStackTraceToStringIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        if (expectedType != DotNetIlValueType.String || call.arguments.size != 1) return false
        val exception = call.arguments.single()
            ?: dotNetUnsupported("missing exception in a call to 'dotNetStackTraceToString'")
        val exceptionType = DotNetIlValueType.MappedClass(
            DotNetMappedExceptions.exceptionTypeRef(codegen.coreLibraryReference)
        )
        codegen.emitExpression(exception, exceptionType)
        codegen.emit(
            DotNetThrowableRuntime.stackTraceToStringCallInstruction(codegen.coreLibraryReference),
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

private object DotNetIlThrowablePrintStackTraceIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsStatement(call: IrCall, codegen: DotNetIlExpressionCodegen): Boolean {
        if (call.arguments.size != 1) return false
        val exception = call.arguments.single()
            ?: dotNetUnsupported("missing exception in a call to 'dotNetPrintStackTrace'")
        val exceptionType = DotNetIlValueType.MappedClass(
            DotNetMappedExceptions.exceptionTypeRef(codegen.coreLibraryReference)
        )
        codegen.emitExpression(exception, exceptionType)
        codegen.emit(
            DotNetThrowableRuntime.printStackTraceCallInstruction(codegen.coreLibraryReference),
            pops = 1,
        )
        return true
    }
}

private object DotNetIlThrowableAddSuppressedIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsStatement(call: IrCall, codegen: DotNetIlExpressionCodegen): Boolean {
        if (call.arguments.size != 2) return false
        val owner = call.arguments[0]
            ?: dotNetUnsupported("missing owner in a call to 'dotNetAddSuppressed'")
        val exception = call.arguments[1]
            ?: dotNetUnsupported("missing exception in a call to 'dotNetAddSuppressed'")
        val exceptionType = DotNetIlValueType.MappedClass(
            DotNetMappedExceptions.exceptionTypeRef(codegen.coreLibraryReference)
        )
        codegen.emitExpression(owner, exceptionType)
        codegen.emitExpression(exception, exceptionType)
        codegen.emit(
            DotNetThrowableRuntime.addSuppressedCallInstruction(codegen.coreLibraryReference),
            pops = 2,
        )
        return true
    }
}

private object DotNetIlThrowableSuppressedExceptionsIntrinsic : DotNetIlIntrinsicMethod() {
    override val excludesDeclarationFromCodegen: Boolean = true

    override fun tryEmitAsExpression(
        call: IrCall,
        codegen: DotNetIlExpressionCodegen,
        expectedType: DotNetIlValueType,
    ): Boolean {
        val exceptionType = DotNetIlValueType.MappedClass(
            DotNetMappedExceptions.exceptionTypeRef(codegen.coreLibraryReference)
        )
        val snapshotType = DotNetIlValueType.GenericArray(exceptionType)
        if (expectedType != snapshotType || call.arguments.size != 1) return false
        val exception = call.arguments.single()
            ?: dotNetUnsupported("missing exception in a call to 'dotNetSuppressedExceptions'")
        codegen.emitExpression(exception, exceptionType)
        codegen.emit(
            DotNetThrowableRuntime.getSuppressedCallInstruction(codegen.coreLibraryReference),
            pops = 1,
            pushes = 1,
        )
        return true
    }
}

/** The dispatch receiver of an exception member access, widened to the universal CLR carrier. */
private fun IrCall.dotNetMappedExceptionReceiver(
    codegen: DotNetIlExpressionCodegen,
    memberName: String,
): Pair<IrExpression, DotNetIlValueType.MappedClass> {
    val receiver = arguments.single()
        ?: dotNetUnsupported("missing receiver of 'Throwable.$memberName'")
    val receiverType = codegen.toDotNetIlValueType(receiver.type)
    val exceptionCarrier = DotNetIlValueType.MappedClass(
        DotNetMappedExceptions.exceptionTypeRef(codegen.coreLibraryReference)
    )
    if (receiverType?.isDotNetAssignableTo(exceptionCarrier) != true) {
        dotNetUnsupported("reading '$memberName' of a receiver not assignable to System.Exception is not supported")
    }
    return receiver to exceptionCarrier
}

private fun IrCall.dotNetEqualityOperandType(codegen: DotNetIlExpressionCodegen): DotNetIlValueType? {
    val left = arguments.getOrNull(0) ?: return null
    val right = arguments.getOrNull(1) ?: return null
    val leftType = codegen.genericOwnerCapabilityNaturalTypeOrNull(left)
        ?: codegen.toDotNetIlValueType(left.type)
    val rightType = codegen.genericOwnerCapabilityNaturalTypeOrNull(right)
        ?: codegen.toDotNetIlValueType(right.type)
    return when {
        leftType != null && leftType == rightType -> leftType
        // A `null` constant compared against a reference type (string, a user class, a primitive
        // array, or `Any?`-typed object storage) takes the reference type: `ldnull` satisfies any
        // reference-shaped operand slot.
        left.isNullConst() && rightType.isDotNetReferenceType() -> rightType
        right.isNullConst() && leftType.isDotNetReferenceType() -> leftType
        // A reference-shaped operand next to an `Any?`-typed one widens to `object` — the
        // instruction-free root-reference widening (probe-verified, nullprobe_s8); the
        // type-agnostic reference `ceq` then serves `===` (and `== null`), while general `==`
        // still lands in the Object rejection arm of the intrinsic. A PRIMITIVE (or nullable-
        // primitive) operand next to `Any?` deliberately does NOT widen: it would need boxing,
        // and identity of boxed values is unrelated to value equality (boxprobe_s6).
        (leftType == DotNetIlValueType.Object || rightType == DotNetIlValueType.Object) &&
                leftType?.isDotNetReferenceShaped() == true && rightType?.isDotNetReferenceShaped() == true ->
            DotNetIlValueType.Object
        // An exact generic vector and its star-projected System.Array view compare through that
        // identity-preserving erased view. This is a no-instruction CLR upcast; both `==` and
        // `===` remain array reference identity, never content comparison or boxing/copying.
        leftType is DotNetIlValueType.GenericArray && rightType is DotNetIlValueType.ErasedGenericArray ->
            rightType
        rightType is DotNetIlValueType.GenericArray && leftType is DotNetIlValueType.ErasedGenericArray ->
            leftType
        // Two differently-mapped exception operands (e.g. `caught === original` where one side
        // is typed `Throwable` and the other `IllegalStateException`) compare through their
        // common CLR supertype: every mapped exception widens to `System.Exception`, and the
        // reference `ceq` is type-agnostic. General `==` on that pair still lands in the
        // MappedClass rejection arm below, exactly like same-typed instances.
        leftType is DotNetIlValueType.MappedClass && rightType is DotNetIlValueType.MappedClass ->
            DotNetIlValueType.MappedClass(
                DotNetMappedExceptions.exceptionTypeRef(codegen.coreLibraryReference)
            )
        // A Kotlin-owned exception subclass is a normal CLR user class whose physical base
        // chain reaches one of the mapped exception classes. Compare it with a Throwable-typed
        // (or another mapped-category-typed) reference through that mapped ancestor. This is
        // deliberately an assignability check, rather than an exception-specific name test:
        // the class-shape pre-pass is the single source of truth for the emitted CLR ancestry.
        leftType.isDotNetUserClassLike() && rightType is DotNetIlValueType.MappedClass &&
                leftType!!.isDotNetAssignableTo(rightType) -> rightType
        rightType.isDotNetUserClassLike() && leftType is DotNetIlValueType.MappedClass &&
                rightType!!.isDotNetAssignableTo(leftType) -> leftType
        // Base/derived-typed user-class operands (expressible since the inheritance model)
        // compare through the ancestor type: the reference `ceq` is type-agnostic and the
        // derived-typed side widens by the established no-instruction upcast, so the wider
        // static type is the operand slot's type — the user-class analogue of the mapped
        // exception arm above. General `==` on such a pair still lands in the UserClass
        // rejection arm of the intrinsic, exactly like same-typed instances. Since the generics
        // model, an INSTANTIATED generic class participates like any other class type (a
        // derived `IntBox` next to a `Box<Int>`-typed operand widens to `Box<Int>`; the
        // assignability walk is invariant, so unrelated instantiations still fall through to
        // the loud rejection).
        leftType.isDotNetUserClassLike() && rightType.isDotNetUserClassLike() &&
                leftType!!.isDotNetAssignableTo(rightType!!) -> rightType
        leftType.isDotNetUserClassLike() && rightType.isDotNetUserClassLike() &&
                rightType!!.isDotNetAssignableTo(leftType!!) -> leftType
        // Sibling classes sharing a supertype (since the interface model this includes a common
        // implemented interface, the shape a smartcast routinely produces: after
        // `if (!(s === r)) return` an interface-typed `s` is narrowed to `r`'s class, so a later
        // comparison sees two sibling classes) widen to the FIRST common supertype of the left
        // operand's supertype walk — deterministic (allSupertypes' breadth-first walk: direct
        // base class, then direct interfaces in declaration order, then the next level) and
        // free for both sides (reference upcasts, ifaceprobe_s7). With the Any foundation, two
        // unrelated reference-shaped interface views fall back to System.Object, Kotlin Any's
        // physical root; the widening is still instruction-free and identity remains `ceq`.
        leftType.isDotNetUserClassLike() && rightType.isDotNetUserClassLike() ->
            leftType!!.dotNetAllSupertypes()
                .firstOrNull { rightType!!.isDotNetAssignableTo(it) }
                ?: DotNetIlValueType.Object
        else -> null
    }
}

private fun DotNetIlValueType?.isDotNetUserClassLike(): Boolean =
    this is DotNetIlValueType.UserClass || this is DotNetIlValueType.GenericInstance

private fun DotNetIlValueType?.isDotNetReferenceType(): Boolean =
    this?.isDotNetReferenceShaped() == true

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
