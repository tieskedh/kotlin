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
 * The curated map of Kotlin exception classes onto the CLR exception hierarchy (JVM precedent:
 * `JavaToKotlinClassMap` — built-in throwables are TYPE-MAPPED onto either a faithful platform
 * type or an exact type in `Kotlin.Runtime`; the injected frontend declarations are never emitted
 * into the consumer module). `kotlin.Throwable` maps to `System.Exception`, the root of everything
 * the CLR throws.
 *
 * Decisions and consciously accepted platform deltas (each verified by ilasm probe):
 * - `kotlin.Exception` also maps to `System.Exception` — the CLR has no Throwable/Exception
 *   split, so `catch (e: Exception)` is equivalent to `catch (e: Throwable)`; the only pure-Kotlin
 *   drift is a literal `throw Throwable(...)` being caught by `catch (e: Exception)`. Interop is
 *   decisive: C# throws `new Exception()` directly, which must stay catchable.
 * - `kotlin.RuntimeException` is REJECTED, not mapped: Kotlin.Runtime now owns its durable physical
 *   root for exact Kotlin-only identities, but enabling source use before migrating or translating
 *   its BCL-mapped logical children would make a parent catch miss those children. The CLR itself
 *   has no honest mapping: `System.SystemException` is deprecated and would catch
 *   OOM/StackOverflow while missing plain Exceptions, while `System.Exception` is too broad.
 * - `kotlin.Error` maps to exact runtime-owned `Kotlin.Error : System.Exception`. The CLR has no
 *   faithful fatal-error root: deprecated `System.SystemException` contains non-fatal failures and
 *   misses plain exceptions, so Kotlin-created Error values get exact identity while foreign
 *   OutOfMemoryException/StackOverflowException values remain distinct. Because Throwable and
 *   Exception already collapse to System.Exception, the existing accepted delta means a Kotlin
 *   Error is also caught by `catch (Exception)` on this target.
 * - `kotlin.NumberFormatException` is the first source-visible exact runtime-owned mapping. It
 *   extends `System.ArgumentException`, not `System.FormatException`: the latter IS-NOT-A
 *   `System.ArgumentException` (probe-verified), while the selected physical parent preserves
 *   Kotlin's `NumberFormatException IS-A IllegalArgumentException` catch and value-conversion
 *   edge. A foreign `System.FormatException` deliberately remains a distinct type until an
 *   interop or parsing intrinsic explicitly translates it.
 * - `kotlin.ArithmeticException` -> `System.ArithmeticException` closes the divide-by-zero debt:
 *   the CLR's `DivideByZeroException` IS-A `System.ArithmeticException` (probe-verified), so
 *   `catch (e: ArithmeticException)` catches a CLR division fault. The message stays the CLR's
 *   verbatim (`"Attempted to divide by zero."`) — JVM precedent: the platform message is used
 *   as-is (JVM's `"/ by zero"` IS its platform message).
 * - `message` keeps its Kotlin type `String?` but is never null at runtime for BCL-mapped
 *   exceptions: a no-arg `Exception()` yields the CLR default message
 *   `"Exception of type 'System.Exception' was thrown."` (probe-verified verbatim). Accepted
 *   platform delta, same class as the `-0.0` rendering note in AGENTS.md.
 * - Broadened catches (accepted): `catch (IllegalArgumentException)` also catches
 *   `ArgumentNullException`/`ArgumentOutOfRangeException`; `catch (IllegalStateException)` also
 *   catches `ObjectDisposedException`; a BCL `ArgumentOutOfRangeException` lands in
 *   `IllegalArgumentException`, not `IndexOutOfBoundsException`.
 * - Constructor whitelist: `()` and `(String?)` map on every entry; `(String?, Throwable?)` maps
 *   where [Entry.Mapped.hasMessageCauseCtor] is set. Cause-only `(Throwable?)` maps only where
 *   [Entry.Mapped.hasCauseCtor] is set. Those flags mirror the Kotlin stdlib's
 *   declared constructor surface, NOT CLR availability — the CLR `(string, Exception)` overload
 *   exists on every BCL-mapped type (probe-verified); runtime-owned mappings provide exactly the
 *   constructor surface declared by Kotlin. Both flags are `false` for Kotlin classes that
 *   declare only `()`/`(String?)`; the exact Kotlin.Error mapping enables both.
 *
 * The injected stdlib declarations of these classes (see [DOTNET_STDLIB_SOURCES]) exist only so
 * the frontend resolves them; [DotNetIlEmitter] excludes them from codegen entirely — the
 * class-level parallel of an intrinsic's `excludesDeclarationFromCodegen`. Rejected entries are
 * still declared so uses RESOLVE and then fail loudly here with a specific reason ("register the
 * entry now, fail explicitly" design rule).
 */
internal object DotNetMappedExceptions {
    /** The IL reference of `System.Exception`, the CLR type both `kotlin.Throwable` and `kotlin.Exception` map to. */
    const val EXCEPTION_TYPE_REF = "${CORE_LIB_REF}System.Exception"

    internal sealed class Entry {
        /**
         * A Kotlin exception class mapped onto the physical CLR type [clrTypeRef], either in
         * corelib or `Kotlin.Runtime`. [hasMessageCauseCtor] and [hasCauseCtor] mirror the Kotlin
         * stdlib's declared constructor surface. [physicalSupertypeRefs] lists the additional
         * mapped CLR types to which values widen without an instruction; every mapping includes
         * `System.Exception`, and a runtime-owned child of a mapped parent includes that parent's
         * physical type too.
         */
        class Mapped(
            val clrTypeRef: String,
            val hasMessageCauseCtor: Boolean,
            val hasCauseCtor: Boolean,
            val physicalSupertypeRefs: Set<String>,
        ) : Entry()

        /** A Kotlin exception class that resolves but has no honest CLR mapping; any codegen use fails with [reason]. */
        class Rejected(val reason: String) : Entry()
    }

    val entries: Map<FqName, Entry> = buildMap {
        fun mapped(kotlinName: String, clrName: String, hasMessageCauseCtor: Boolean) {
            put(
                FqName("kotlin.$kotlinName"),
                Entry.Mapped(
                    clrTypeRef = "${CORE_LIB_REF}System.$clrName",
                    hasMessageCauseCtor = hasMessageCauseCtor,
                    hasCauseCtor = false,
                    physicalSupertypeRefs = setOf(EXCEPTION_TYPE_REF),
                )
            )
        }
        mapped("Throwable", "Exception", hasMessageCauseCtor = true)
        mapped("Exception", "Exception", hasMessageCauseCtor = true)
        mapped("IllegalArgumentException", "ArgumentException", hasMessageCauseCtor = true)
        mapped("IllegalStateException", "InvalidOperationException", hasMessageCauseCtor = true)
        mapped("UnsupportedOperationException", "NotSupportedException", hasMessageCauseCtor = true)
        mapped("ArithmeticException", "ArithmeticException", hasMessageCauseCtor = false)
        mapped("IndexOutOfBoundsException", "IndexOutOfRangeException", hasMessageCauseCtor = false)
        mapped("NullPointerException", "NullReferenceException", hasMessageCauseCtor = false)
        mapped("ClassCastException", "InvalidCastException", hasMessageCauseCtor = false)
        put(
            FqName("kotlin.NumberFormatException"),
            Entry.Mapped(
                clrTypeRef = DotNetRuntimeLibrary.numberFormatExceptionTypeRef,
                hasMessageCauseCtor = false,
                hasCauseCtor = false,
                physicalSupertypeRefs = setOf("${CORE_LIB_REF}System.ArgumentException", EXCEPTION_TYPE_REF),
            )
        )
        put(
            FqName("kotlin.Error"),
            Entry.Mapped(
                clrTypeRef = DotNetRuntimeLibrary.errorTypeRef,
                hasMessageCauseCtor = true,
                hasCauseCtor = true,
                physicalSupertypeRefs = setOf(EXCEPTION_TYPE_REF),
            )
        )
        put(
            FqName("kotlin.RuntimeException"),
            Entry.Rejected(
                "'kotlin.RuntimeException' has no CLR exception mapping (the CLR has no " +
                        "Exception/RuntimeException split); its Kotlin.Runtime type is reserved for exact " +
                        "Kotlin-owned identities, but source use stays rejected until mapped-child catches are coherent"
            )
        )
    }

    fun isMappedTypeAssignableTo(actualTypeRef: String, expectedTypeRef: String): Boolean =
        entries.values.asSequence()
            .filterIsInstance<Entry.Mapped>()
            .filter { it.clrTypeRef == actualTypeRef }
            .any { expectedTypeRef in it.physicalSupertypeRefs }

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
    this is DotNetIlValueType.MappedClass && expected is DotNetIlValueType.MappedClass ->
        DotNetMappedExceptions.isMappedTypeAssignableTo(ilTypeRef, expected.ilTypeRef)
    this is DotNetIlValueType.GenericInstance && expected is DotNetIlValueType.GenericInstance &&
            isDotNetVariantInstantiationAssignableTo(expected) -> true
    (this is DotNetIlValueType.UserClass || this is DotNetIlValueType.GenericInstance) &&
            (expected is DotNetIlValueType.UserClass || expected is DotNetIlValueType.GenericInstance) ->
        dotNetAllSupertypes().any { superType ->
            superType.nameInSignature == expected.nameInSignature ||
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
