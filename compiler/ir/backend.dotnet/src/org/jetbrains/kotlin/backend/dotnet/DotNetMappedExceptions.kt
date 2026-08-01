/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.Variance

/**
 * Stable runtime ids for Kotlin's logical exception classes. These integers are embedded in
 * generated assemblies and consumed by Kotlin.Runtime, so changing an assigned value is an ABI
 * break even though the enum itself is compiler-internal. New ids are append-only once the target
 * reaches its first ABI gate.
 */
internal enum class DotNetKotlinExceptionTypeId(val abiValue: Int) {
    THROWABLE(1),
    EXCEPTION(2),
    RUNTIME_EXCEPTION(3),
    ERROR(4),
    ILLEGAL_ARGUMENT_EXCEPTION(5),
    ILLEGAL_STATE_EXCEPTION(6),
    UNSUPPORTED_OPERATION_EXCEPTION(7),
    NO_SUCH_ELEMENT_EXCEPTION(8),
    INDEX_OUT_OF_BOUNDS_EXCEPTION(9),
    ARITHMETIC_EXCEPTION(10),
    NUMBER_FORMAT_EXCEPTION(11),
    NULL_POINTER_EXCEPTION(12),
    CLASS_CAST_EXCEPTION(13),
    CANCELLATION_EXCEPTION(14),
    EXCEPTION_IN_INITIALIZER_ERROR(15),
    NO_CLASS_DEF_FOUND_ERROR(16),
    NO_WHEN_BRANCH_MATCHED_EXCEPTION(17),
    CONCURRENT_MODIFICATION_EXCEPTION(18),
    ASSERTION_ERROR(19),
    UNINITIALIZED_PROPERTY_ACCESS_EXCEPTION(20),
    KOTLIN_NOTHING_VALUE_EXCEPTION(21),
}

/**
 * The compiler side of the classified CLR exception model selected by
 * `classified-clr-exceptions.md`.
 *
 * One Kotlin exception type can have three deliberately different CLR representations:
 *
 * - [Entry.Mapped.carrierTypeRef] is used in fields, locals, parameters, and returns. Broad
 *   logical categories use `System.Exception`, preserving every foreign exception object.
 * - [Entry.Mapped.constructorTypeRef] is the exact class allocated by a Kotlin constructor call.
 *   `RuntimeException()` and `Error()` therefore retain Kotlin-owned exact identities even though
 *   values typed as those broad categories are carried as `System.Exception`. The same exact type
 *   is the physical base of a user-defined Kotlin subclass: CLR metadata must expose the real
 *   inheritance chain used by construction and ordinary .NET tooling.
 * - [Entry.Mapped.typedCatchTypeRefOrNull] is present only when one CLR typed handler is proven
 *   equivalent to the runtime classifier. Other catches use a filter over `System.Exception`.
 *
 * Keeping these roles separate is fundamental. Reusing one mapped CLR type for all four jobs
 * either wraps foreign exceptions, collapses Kotlin's Exception/Error distinction, or makes a
 * RuntimeException catch miss mapped BCL children.
 */
internal object DotNetMappedExceptions {
    /** The IL reference of `System.Exception` in one emission's selected core-library profile. */
    fun exceptionTypeRef(coreLibraryReference: String): String = "${coreLibraryReference}System.Exception"

    internal sealed class PhysicalTypeRef {
        abstract fun render(coreLibraryReference: String): String

        class CoreLibrary(private val fullName: String) : PhysicalTypeRef() {
            override fun render(coreLibraryReference: String): String = "$coreLibraryReference$fullName"
        }

        class Exact(private val ilTypeRef: String) : PhysicalTypeRef() {
            override fun render(coreLibraryReference: String): String = ilTypeRef
        }
    }

    internal sealed class Entry {
        /**
         * A Kotlin exception class with separate carrier, construction/base, and optional typed-
         * catch CLR roles, either in corelib or `Kotlin.Runtime`. [hasMessageCauseCtor] and
         * [hasCauseCtor] mirror the Kotlin stdlib's declared constructor surface.
         * [physicalSupertypeRefs] lists the additional mapped CLR types to which values widen
         * without an instruction; every mapping includes `System.Exception`, and a runtime-owned
         * child of a mapped parent includes that parent's physical type too.
         */
        class Mapped(
            private val carrierPhysicalTypeRef: PhysicalTypeRef,
            private val constructorPhysicalTypeRef: PhysicalTypeRef,
            private val typedCatchPhysicalTypeRef: PhysicalTypeRef?,
            val classifierTypeId: DotNetKotlinExceptionTypeId,
            val hasMessageCauseCtor: Boolean,
            val hasCauseCtor: Boolean,
            private val physicalSupertypeRefs: Set<PhysicalTypeRef>,
            val hasAnyMessageCtor: Boolean = false,
        ) : Entry() {
            fun carrierTypeRef(coreLibraryReference: String): String =
                carrierPhysicalTypeRef.render(coreLibraryReference)

            fun constructorTypeRef(coreLibraryReference: String): String =
                constructorPhysicalTypeRef.render(coreLibraryReference)

            fun subclassBaseTypeRef(coreLibraryReference: String): String =
                constructorPhysicalTypeRef.render(coreLibraryReference)

            fun typedCatchTypeRefOrNull(coreLibraryReference: String): String? =
                typedCatchPhysicalTypeRef?.render(coreLibraryReference)

            fun physicalSupertypeRefs(coreLibraryReference: String): Set<String> =
                physicalSupertypeRefs.mapTo(linkedSetOf()) { it.render(coreLibraryReference) }

            /**
             * Checks one source constructor/delegating-constructor signature against the exact CLR
             * constructor surface selected by this entry. Keeping this in the mapping registry prevents
             * `newobj` and subclass base chaining from drifting into two different ABIs.
             */
            fun checkConstructorShape(
                className: String,
                parameterTypes: List<DotNetIlValueType>,
                coreLibraryReference: String,
            ) {
                val causeType = DotNetIlValueType.MappedClass(exceptionTypeRef(coreLibraryReference))
                when {
                    parameterTypes.isEmpty() -> {}
                    parameterTypes == listOf(DotNetIlValueType.String) -> {}
                    parameterTypes == listOf(DotNetIlValueType.String, causeType) && hasMessageCauseCtor -> {}
                    parameterTypes == listOf(causeType) && hasCauseCtor -> {}
                    parameterTypes == listOf(DotNetIlValueType.Object) && hasAnyMessageCtor -> {}
                    parameterTypes == listOf(causeType) -> dotNetUnsupported(
                        "constructor '$className(cause)' has no mapped CLR overload; " +
                                "construct with (message) or (message, cause)"
                    )
                    else -> dotNetUnsupported(
                        "constructor of '$className' has no matching overload on the mapped CLR type " +
                                "'${constructorTypeRef(coreLibraryReference)}'"
                    )
                }
            }
        }

        /** A Kotlin exception class that resolves but has no honest CLR mapping; any codegen use fails with [reason]. */
        class Rejected(val reason: String) : Entry()
    }

    val entries: Map<FqName, Entry> = buildMap {
        val exceptionType = PhysicalTypeRef.CoreLibrary("System.Exception")

        fun exactlyMapped(
            kotlinName: String,
            clrName: String,
            classifierTypeId: DotNetKotlinExceptionTypeId,
            hasMessageCauseCtor: Boolean,
        ) {
            val physicalType = PhysicalTypeRef.CoreLibrary("System.$clrName")
            put(
                FqName("kotlin.$kotlinName"),
                Entry.Mapped(
                    carrierPhysicalTypeRef = physicalType,
                    constructorPhysicalTypeRef = physicalType,
                    typedCatchPhysicalTypeRef = physicalType,
                    classifierTypeId = classifierTypeId,
                    hasMessageCauseCtor = hasMessageCauseCtor,
                    hasCauseCtor = false,
                    physicalSupertypeRefs = setOf(exceptionType),
                )
            )
        }
        fun classifiedCategory(
            kotlinName: String,
            constructorType: PhysicalTypeRef,
            classifierTypeId: DotNetKotlinExceptionTypeId,
            hasMessageCauseCtor: Boolean,
            hasCauseCtor: Boolean,
        ) {
            put(
                FqName("kotlin.$kotlinName"),
                Entry.Mapped(
                    carrierPhysicalTypeRef = exceptionType,
                    constructorPhysicalTypeRef = constructorType,
                    typedCatchPhysicalTypeRef = null,
                    classifierTypeId = classifierTypeId,
                    hasMessageCauseCtor = hasMessageCauseCtor,
                    hasCauseCtor = hasCauseCtor,
                    physicalSupertypeRefs = setOf(exceptionType),
                )
            )
        }
        fun runtimeExactlyMapped(
            kotlinName: String,
            runtimeTypeRef: String,
            classifierTypeId: DotNetKotlinExceptionTypeId,
            physicalParentTypeRef: String,
            hasMessageCauseCtor: Boolean,
            hasCauseCtor: Boolean,
            hasAnyMessageCtor: Boolean = false,
        ) {
            val physicalType = PhysicalTypeRef.Exact(runtimeTypeRef)
            put(
                FqName("kotlin.$kotlinName"),
                Entry.Mapped(
                    carrierPhysicalTypeRef = physicalType,
                    constructorPhysicalTypeRef = physicalType,
                    typedCatchPhysicalTypeRef = physicalType,
                    classifierTypeId = classifierTypeId,
                    hasMessageCauseCtor = hasMessageCauseCtor,
                    hasCauseCtor = hasCauseCtor,
                    hasAnyMessageCtor = hasAnyMessageCtor,
                    physicalSupertypeRefs = setOf(
                        PhysicalTypeRef.Exact(physicalParentTypeRef),
                        exceptionType,
                    ),
                )
            )
        }

        // Throwable is the one broad category exactly expressible as a CLR typed catch.
        put(
            FqName("kotlin.Throwable"),
            Entry.Mapped(
                carrierPhysicalTypeRef = exceptionType,
                constructorPhysicalTypeRef = exceptionType,
                typedCatchPhysicalTypeRef = exceptionType,
                classifierTypeId = DotNetKotlinExceptionTypeId.THROWABLE,
                hasMessageCauseCtor = true,
                hasCauseCtor = false,
                physicalSupertypeRefs = setOf(exceptionType),
            )
        )
        classifiedCategory(
            "Exception",
            exceptionType,
            DotNetKotlinExceptionTypeId.EXCEPTION,
            hasMessageCauseCtor = true,
            hasCauseCtor = false,
        )
        classifiedCategory(
            "RuntimeException",
            PhysicalTypeRef.Exact(DotNetRuntimeLibrary.runtimeExceptionTypeRef),
            DotNetKotlinExceptionTypeId.RUNTIME_EXCEPTION,
            hasMessageCauseCtor = true,
            hasCauseCtor = true,
        )
        put(
            FqName("kotlin.NoWhenBranchMatchedException"),
            Entry.Mapped(
                carrierPhysicalTypeRef =
                    PhysicalTypeRef.Exact(DotNetRuntimeLibrary.noWhenBranchMatchedExceptionTypeRef),
                constructorPhysicalTypeRef =
                    PhysicalTypeRef.Exact(DotNetRuntimeLibrary.noWhenBranchMatchedExceptionTypeRef),
                typedCatchPhysicalTypeRef =
                    PhysicalTypeRef.Exact(DotNetRuntimeLibrary.noWhenBranchMatchedExceptionTypeRef),
                classifierTypeId = DotNetKotlinExceptionTypeId.NO_WHEN_BRANCH_MATCHED_EXCEPTION,
                hasMessageCauseCtor = true,
                hasCauseCtor = true,
                physicalSupertypeRefs = setOf(
                    PhysicalTypeRef.Exact(DotNetRuntimeLibrary.runtimeExceptionTypeRef),
                    exceptionType,
                ),
            )
        )
        runtimeExactlyMapped(
            "ConcurrentModificationException",
            DotNetRuntimeLibrary.concurrentModificationExceptionTypeRef,
            DotNetKotlinExceptionTypeId.CONCURRENT_MODIFICATION_EXCEPTION,
            DotNetRuntimeLibrary.runtimeExceptionTypeRef,
            hasMessageCauseCtor = true,
            hasCauseCtor = true,
        )
        runtimeExactlyMapped(
            "UninitializedPropertyAccessException",
            DotNetRuntimeLibrary.uninitializedPropertyAccessExceptionTypeRef,
            DotNetKotlinExceptionTypeId.UNINITIALIZED_PROPERTY_ACCESS_EXCEPTION,
            DotNetRuntimeLibrary.runtimeExceptionTypeRef,
            hasMessageCauseCtor = true,
            hasCauseCtor = true,
        )
        runtimeExactlyMapped(
            "KotlinNothingValueException",
            DotNetRuntimeLibrary.kotlinNothingValueExceptionTypeRef,
            DotNetKotlinExceptionTypeId.KOTLIN_NOTHING_VALUE_EXCEPTION,
            DotNetRuntimeLibrary.runtimeExceptionTypeRef,
            hasMessageCauseCtor = true,
            hasCauseCtor = true,
        )
        classifiedCategory(
            "Error",
            PhysicalTypeRef.Exact(DotNetRuntimeLibrary.errorTypeRef),
            DotNetKotlinExceptionTypeId.ERROR,
            hasMessageCauseCtor = true,
            hasCauseCtor = true,
        )
        runtimeExactlyMapped(
            "AssertionError",
            DotNetRuntimeLibrary.assertionErrorTypeRef,
            DotNetKotlinExceptionTypeId.ASSERTION_ERROR,
            DotNetRuntimeLibrary.errorTypeRef,
            hasMessageCauseCtor = true,
            hasCauseCtor = false,
            hasAnyMessageCtor = true,
        )
        put(
            FqName("kotlin.ExceptionInInitializerError"),
            Entry.Mapped(
                carrierPhysicalTypeRef =
                    PhysicalTypeRef.Exact(DotNetRuntimeLibrary.exceptionInInitializerErrorTypeRef),
                constructorPhysicalTypeRef =
                    PhysicalTypeRef.Exact(DotNetRuntimeLibrary.exceptionInInitializerErrorTypeRef),
                typedCatchPhysicalTypeRef =
                    PhysicalTypeRef.Exact(DotNetRuntimeLibrary.exceptionInInitializerErrorTypeRef),
                classifierTypeId = DotNetKotlinExceptionTypeId.EXCEPTION_IN_INITIALIZER_ERROR,
                hasMessageCauseCtor = false,
                hasCauseCtor = true,
                physicalSupertypeRefs = setOf(
                    PhysicalTypeRef.Exact(DotNetRuntimeLibrary.errorTypeRef),
                    exceptionType,
                ),
            )
        )
        put(
            FqName("kotlin.NoClassDefFoundError"),
            Entry.Mapped(
                carrierPhysicalTypeRef =
                    PhysicalTypeRef.Exact(DotNetRuntimeLibrary.noClassDefFoundErrorTypeRef),
                constructorPhysicalTypeRef =
                    PhysicalTypeRef.Exact(DotNetRuntimeLibrary.noClassDefFoundErrorTypeRef),
                typedCatchPhysicalTypeRef =
                    PhysicalTypeRef.Exact(DotNetRuntimeLibrary.noClassDefFoundErrorTypeRef),
                classifierTypeId = DotNetKotlinExceptionTypeId.NO_CLASS_DEF_FOUND_ERROR,
                hasMessageCauseCtor = false,
                hasCauseCtor = false,
                physicalSupertypeRefs = setOf(
                    PhysicalTypeRef.Exact(DotNetRuntimeLibrary.errorTypeRef),
                    exceptionType,
                ),
            )
        )
        exactlyMapped(
            "IllegalArgumentException", "ArgumentException",
            DotNetKotlinExceptionTypeId.ILLEGAL_ARGUMENT_EXCEPTION,
            hasMessageCauseCtor = true,
        )
        classifiedCategory(
            "IllegalStateException",
            PhysicalTypeRef.CoreLibrary("System.InvalidOperationException"),
            DotNetKotlinExceptionTypeId.ILLEGAL_STATE_EXCEPTION,
            hasMessageCauseCtor = true,
            hasCauseCtor = false,
        )
        exactlyMapped(
            "UnsupportedOperationException", "NotSupportedException",
            DotNetKotlinExceptionTypeId.UNSUPPORTED_OPERATION_EXCEPTION,
            hasMessageCauseCtor = true,
        )
        exactlyMapped(
            "ArithmeticException", "ArithmeticException",
            DotNetKotlinExceptionTypeId.ARITHMETIC_EXCEPTION,
            hasMessageCauseCtor = false,
        )
        exactlyMapped(
            "IndexOutOfBoundsException", "IndexOutOfRangeException",
            DotNetKotlinExceptionTypeId.INDEX_OUT_OF_BOUNDS_EXCEPTION,
            hasMessageCauseCtor = false,
        )
        exactlyMapped(
            "NullPointerException", "NullReferenceException",
            DotNetKotlinExceptionTypeId.NULL_POINTER_EXCEPTION,
            hasMessageCauseCtor = false,
        )
        exactlyMapped(
            "ClassCastException", "InvalidCastException",
            DotNetKotlinExceptionTypeId.CLASS_CAST_EXCEPTION,
            hasMessageCauseCtor = false,
        )
        put(
            FqName("kotlin.NumberFormatException"),
            Entry.Mapped(
                carrierPhysicalTypeRef = PhysicalTypeRef.Exact(DotNetRuntimeLibrary.numberFormatExceptionTypeRef),
                constructorPhysicalTypeRef = PhysicalTypeRef.Exact(DotNetRuntimeLibrary.numberFormatExceptionTypeRef),
                typedCatchPhysicalTypeRef = PhysicalTypeRef.Exact(DotNetRuntimeLibrary.numberFormatExceptionTypeRef),
                classifierTypeId = DotNetKotlinExceptionTypeId.NUMBER_FORMAT_EXCEPTION,
                hasMessageCauseCtor = false,
                hasCauseCtor = false,
                physicalSupertypeRefs = setOf(
                    PhysicalTypeRef.CoreLibrary("System.ArgumentException"),
                    exceptionType,
                ),
            )
        )
        put(
            FqName("kotlin.NoSuchElementException"),
            Entry.Mapped(
                carrierPhysicalTypeRef = PhysicalTypeRef.Exact(DotNetRuntimeLibrary.noSuchElementExceptionTypeRef),
                constructorPhysicalTypeRef = PhysicalTypeRef.Exact(DotNetRuntimeLibrary.noSuchElementExceptionTypeRef),
                typedCatchPhysicalTypeRef = PhysicalTypeRef.Exact(DotNetRuntimeLibrary.noSuchElementExceptionTypeRef),
                classifierTypeId = DotNetKotlinExceptionTypeId.NO_SUCH_ELEMENT_EXCEPTION,
                hasMessageCauseCtor = false,
                hasCauseCtor = false,
                physicalSupertypeRefs = setOf(exceptionType),
            )
        )
        val operationCanceledType = PhysicalTypeRef.CoreLibrary("System.OperationCanceledException")
        put(
            FqName("kotlin.coroutines.cancellation.CancellationException"),
            Entry.Mapped(
                carrierPhysicalTypeRef = operationCanceledType,
                constructorPhysicalTypeRef = operationCanceledType,
                typedCatchPhysicalTypeRef = operationCanceledType,
                classifierTypeId = DotNetKotlinExceptionTypeId.CANCELLATION_EXCEPTION,
                hasMessageCauseCtor = true,
                hasCauseCtor = false,
                physicalSupertypeRefs = setOf(exceptionType),
            )
        )
    }

    fun mappedEntry(typeFqName: FqName?): Entry.Mapped? =
        typeFqName?.let(entries::get) as? Entry.Mapped

    /**
     * Whether this logical Kotlin type shares the universal `System.Exception` signature
     * carrier with another distinct Kotlin exception type.
     *
     * These categories are intentionally non-injective at the CLR type level. Callable
     * names must therefore retain their logical distinction when one occurs in a physical
     * parameter position; exact mapped exception classes do not need that additional identity.
     */
    fun hasSharedSignatureCarrier(typeFqName: FqName?): Boolean =
        when (mappedEntry(typeFqName)?.classifierTypeId) {
            DotNetKotlinExceptionTypeId.THROWABLE,
            DotNetKotlinExceptionTypeId.EXCEPTION,
            DotNetKotlinExceptionTypeId.RUNTIME_EXCEPTION,
            DotNetKotlinExceptionTypeId.ERROR,
            DotNetKotlinExceptionTypeId.ILLEGAL_STATE_EXCEPTION,
                -> true
            else -> false
        }

    fun isMappedTypeAssignableTo(actualTypeRef: String, expectedTypeRef: String): Boolean =
        entries.values.asSequence()
            .filterIsInstance<Entry.Mapped>()
            .any { entry ->
                DotNetCoreLibraryProfile.entries.any { profile ->
                    entry.carrierTypeRef(profile.reference) == actualTypeRef &&
                            expectedTypeRef in entry.physicalSupertypeRefs(profile.reference)
                }
            }

    /**
     * Whether [irClass] is one of the exception class declarations of the injected stdlib
     * (mapped AND rejected): these exist only for frontend resolution and must never be emitted,
     * shape-gated, or allowed to reserve a facade name — the class-level parallel of an
     * intrinsic's `excludesDeclarationFromCodegen`.
     */
    fun isExceptionStdlibDeclaration(irClass: IrClass): Boolean =
        irClass.fqNameWhenAvailable?.let { it in entries } == true
}

/**
 * Whether a value of this IL type can be used where [expected] is required. Beyond exact
 * equality this admits exactly three widenings, all instruction-free (a widening that needs an
 * IL instruction — `T -> T?` wraps, `-> object` boxing of value types — must NEVER be modeled
 * here; it belongs to [DotNetIlExpressionCodegen]'s coercion layer):
 * - every [DotNetIlValueType.MappedClass] follows the registry's physical-supertype table. All
 *   map to `System.Exception`; an exact runtime-owned class additionally maps to the faithful BCL
 *   parent used by a supported Kotlin supertype (for example NumberFormatException to
 *   System.ArgumentException). These are CLR-verified reference widenings with no instruction;
 * - a [DotNetIlValueType.UserClass] or [DotNetIlValueType.GenericInstance] is assignable to
 *   every proper supertype of its [supertype DAG][DotNetIlClassInfo.allSupertypes]: the
 *   [base-type chain][DotNetIlClassInfo.baseType] of the inheritance model — including an
 *   INSTANTIATED generic base (closed `class D : Box<Int>()` or open/composed
 *   `class D<T> : Box<T>()`, widening only to that exact view; probe-verified
 *   `genprobe_s5`/`geninheritprobe_s1`) — plus every transitively
 *   [implemented interface][DotNetIlClassInfo.interfaces]
 *   of the interface model — pure reference upcasts needing no IL instruction at all
 *   (probe-verified: `inheritprobe_s1` for base-typed positions; `ifaceprobe_s7` for
 *   interface-typed fields, parameters, returns and locals, plus the type-agnostic reference
 *   `ceq`; `ifaceprobe_s6` for the interface→super-interface widening). The comparison runs on
 *   the RENDERED type tokens. Generic classes remain structurally invariant. Generic interfaces
 *   additionally apply their declaration-site CLR variance when every differing argument on
 *   both sides is reference-shaped: `Producer<Derived>` widens to `Producer<Base>` and
 *   `Consumer<Base>` widens to `Consumer<Derived>`, while value-type and open type-parameter
 *   arguments remain invariant because CLR variance conversions do not apply to value-type
 *   instantiations. The JVM backend never
 *   performs this check itself — the JVM verifier's assignability subsumes it — while this
 *   backend verifies emitted stack types structurally, so the widening is spelled out here;
 * - CLR vectors are covariant only when both element tokens are reference-shaped. This physical
 *   rule serves Kotlin `Array<out E>` call boundaries while Kotlin metadata remains authoritative
 *   for projection legality. Value-element vectors stay invariant, so a legal Kotlin widening
 *   such as `Array<Int> -> Array<out Any>` is rejected until an identity-preserving boxed carrier
 *   exists rather than emitted as the invalid `int32[] -> object[]`;
 * - every [reference-shaped][isDotNetReferenceShaped] type is assignable to
 *   [DotNetIlValueType.Object] (`kotlin.Any`/`Any?` storage): CLR `object` is the root
 *   reference type and the widening is instruction-free in every position (probe-verified,
 *   `nullprobe_s8`). Value types — the primitives and [DotNetIlValueType.NullableValue] — are
 *   deliberately NOT assignable to `object`: they need a `box` instruction (coercion layer).
 *   A [DotNetIlValueType.TypeParameter] is instruction-free assignable only to ITSELF (the
 *   `this == expected` arm — positional identity): even an interface-bound `T` may instantiate
 *   to a value type, so widening to a bound or `object` belongs to the `box !n` coercion layer.
 */
internal fun DotNetIlValueType.isDotNetAssignableTo(expected: DotNetIlValueType): Boolean = when {
    this == expected -> true
    expected == DotNetIlValueType.Object -> isDotNetReferenceShaped()
    this is DotNetIlValueType.GenericArray && expected is DotNetIlValueType.ErasedGenericArray -> true
    this is DotNetIlValueType.GenericArray && expected is DotNetIlValueType.GenericArray &&
            elementType.isDotNetReferenceShaped() &&
            expected.elementType.isDotNetReferenceShaped() ->
        elementType.isDotNetAssignableTo(expected.elementType)
    this is DotNetIlValueType.MappedClass && expected is DotNetIlValueType.MappedClass ->
        DotNetMappedExceptions.isMappedTypeAssignableTo(ilTypeRef, expected.ilTypeRef)
    this is DotNetIlValueType.GenericInstance && expected is DotNetIlValueType.GenericInstance &&
            isDotNetVariantInstantiationAssignableTo(expected) -> true
    (this is DotNetIlValueType.UserClass || this is DotNetIlValueType.GenericInstance) &&
            (expected is DotNetIlValueType.UserClass ||
                    expected is DotNetIlValueType.GenericInstance ||
                    expected is DotNetIlValueType.MappedClass) ->
        dotNetAllSupertypes().any { superType ->
            superType.nameInSignature == expected.nameInSignature ||
                    superType is DotNetIlValueType.MappedClass &&
                    expected is DotNetIlValueType.MappedClass &&
                    superType.isDotNetAssignableTo(expected) ||
                    superType is DotNetIlValueType.GenericInstance &&
                    expected is DotNetIlValueType.GenericInstance &&
                    superType.isDotNetVariantInstantiationAssignableTo(expected)
        }
    else -> false
}

/**
 * The CLR variant conversion between two instantiations of ONE generic interface definition.
 * The class shape gate keeps every generic class parameter invariant, so consulting the
 * declaration's recorded variances is sufficient to exclude the ECMA-forbidden class case.
 * Exact arguments always pass (including primitives and open `!n`/`!!n`); a differing covariant
 * or contravariant pair passes only when BOTH sides are statically reference-shaped, matching
 * the CLR rule that variance does not apply to value-type instantiations. Nested variant
 * interfaces compose through [isDotNetAssignableTo].
 */
private fun DotNetIlValueType.GenericInstance.isDotNetVariantInstantiationAssignableTo(
    expected: DotNetIlValueType.GenericInstance,
): Boolean {
    if (classInfo.ilTypeRef != expected.classInfo.ilTypeRef || arguments.size != expected.arguments.size) {
        return false
    }
    return arguments.indices.all { index ->
        val actualArgument = arguments[index]
        val expectedArgument = expected.arguments[index]
        if (actualArgument == expectedArgument) return@all true
        if (!actualArgument.isDotNetReferenceShaped() || !expectedArgument.isDotNetReferenceShaped()) {
            return@all false
        }
        when (classInfo.typeParameterVariances[index]) {
            Variance.OUT_VARIANCE -> actualArgument.isDotNetAssignableTo(expectedArgument)
            Variance.IN_VARIANCE -> expectedArgument.isDotNetAssignableTo(actualArgument)
            Variance.INVARIANT -> false
        }
    }
}
