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
 * `JavaToKotlinClassMap` — built-in throwables are TYPE-MAPPED onto the platform hierarchy, never
 * emitted as Kotlin-owned classes, so exceptions thrown by other .NET code remain catchable).
 * `kotlin.Throwable` maps to `System.Exception`, the root of everything the CLR throws.
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
 * - `kotlin.Error` is REJECTED: the CLR has no fatal-error branch of the hierarchy at all.
 * - `kotlin.NumberFormatException` is REJECTED: the only CLR candidate `System.FormatException`
 *   IS-NOT-A `System.ArgumentException` (probe-verified), so Kotlin's
 *   `NumberFormatException IS-A IllegalArgumentException` catch edge would silently break.
 * - `kotlin.ArithmeticException` -> `System.ArithmeticException` closes the divide-by-zero debt:
 *   the CLR's `DivideByZeroException` IS-A `System.ArithmeticException` (probe-verified), so
 *   `catch (e: ArithmeticException)` catches a CLR division fault. The message stays the CLR's
 *   verbatim (`"Attempted to divide by zero."`) — JVM precedent: the platform message is used
 *   as-is (JVM's `"/ by zero"` IS its platform message).
 * - `message` keeps its Kotlin type `String?` but is never null at runtime for mapped exceptions:
 *   a no-arg `Exception()` yields the CLR default message
 *   `"Exception of type 'System.Exception' was thrown."` (probe-verified verbatim). Accepted
 *   platform delta, same class as the `-0.0` rendering note in AGENTS.md.
 * - Broadened catches (accepted): `catch (IllegalArgumentException)` also catches
 *   `ArgumentNullException`/`ArgumentOutOfRangeException`; `catch (IllegalStateException)` also
 *   catches `ObjectDisposedException`; a BCL `ArgumentOutOfRangeException` lands in
 *   `IllegalArgumentException`, not `IndexOutOfBoundsException`.
 * - Constructor whitelist: `()` and `(String?)` map on every entry; `(String?, Throwable?)` maps
 *   where [Entry.Mapped.hasMessageCauseCtor] is set. That flag mirrors the Kotlin stdlib's
 *   declared constructor surface, NOT CLR availability — the CLR `(string, Exception)` overload
 *   exists on every mapped type (probe-verified) — so it is `false` exactly for the Kotlin
 *   classes that declare only `()`/`(String?)` and can never resolve a `(message, cause)` call.
 *   The cause-only `(Throwable?)` constructor has NO CLR overload anywhere and is rejected.
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
         * A Kotlin exception class mapped onto the CLR type [clrTypeRef] (a corelib-qualified IL
         * type reference). [hasMessageCauseCtor] gates the `(String?, Throwable?)` constructor
         * and mirrors the Kotlin stdlib's declared constructor surface: every mapped CLR type
         * has the `(string, Exception)` overload (probe-verified), but the classes flagged
         * `false` declare only `()`/`(String?)` in the Kotlin stdlib (and in the injected
         * declarations), so the gate can never fire for them — it is a defensive mirror of the
         * Kotlin surface, not a record of CLR overload availability.
         */
        class Mapped(val clrTypeRef: String, val hasMessageCauseCtor: Boolean) : Entry()

        /** A Kotlin exception class that resolves but has no honest CLR mapping; any codegen use fails with [reason]. */
        class Rejected(val reason: String) : Entry()
    }

    val entries: Map<FqName, Entry> = buildMap {
        fun mapped(kotlinName: String, clrName: String, hasMessageCauseCtor: Boolean) {
            put(FqName("kotlin.$kotlinName"), Entry.Mapped("${CORE_LIB_REF}System.$clrName", hasMessageCauseCtor))
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
            FqName("kotlin.RuntimeException"),
            Entry.Rejected(
                "'kotlin.RuntimeException' has no CLR exception mapping (the CLR has no " +
                        "Exception/RuntimeException split); its Kotlin.Runtime type is reserved for exact " +
                        "Kotlin-owned identities, but source use stays rejected until mapped-child catches are coherent"
            )
        )
        put(
            FqName("kotlin.Error"),
            Entry.Rejected("'kotlin.Error' has no CLR fatal-error hierarchy to map onto; rejected")
        )
        put(
            FqName("kotlin.NumberFormatException"),
            Entry.Rejected(
                "'kotlin.NumberFormatException' cannot map to System.FormatException without breaking " +
                        "'catch (e: IllegalArgumentException)' (FormatException is not an ArgumentException on the CLR); rejected"
            )
        )
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
 * - every [DotNetIlValueType.MappedClass] is assignable to the `System.Exception` reference
 *   type — the CLR-verified common supertype of all mapped exception types, and the target of
 *   both Kotlin supertypes (`Throwable`, `Exception`) that can appear as the expected type of a
 *   mapped-exception value inside the supported subset;
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
    this is DotNetIlValueType.MappedClass ->
        expected == DotNetIlValueType.MappedClass(DotNetMappedExceptions.EXCEPTION_TYPE_REF)
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
