# Draft ADR: Hybrid Kotlin and CLR exception identity

- Status: **Draft candidate; first Kotlin-owned identity implemented in the prototype**
- Date: 2026-07-15
- Scope: Kotlin exception ownership, CLR fault interop, and runtime hierarchy

This is a repository-local decision record for the experimental .NET backend. It keeps the POC
internally coherent while evidence is collected; it is not a public KEEP or an accepted Kotlin
project decision.

## Context

The CLR supplies one throwable root, `System.Exception`, and many VM/BCL operations throw its
existing subclasses directly. Kotlin has a different logical hierarchy: `Throwable` and
`Exception` are distinct, `RuntimeException` owns most ordinary language exceptions, and some
Kotlin identities such as `NoWhenBranchMatchedException` have no faithful BCL counterpart.

The POC already maps `Throwable` and `Exception` to `System.Exception` and maps several Kotlin
classes to BCL types so native faults remain catchable. Examples include CLR divide-by-zero under
Kotlin `ArithmeticException`, null dereference under `NullPointerException`, and vector bounds
faults under `IndexOutOfBoundsException`. That interoperability is valuable, but the mapping
cannot express every Kotlin subtype edge. In particular no honest BCL type represents
`RuntimeException`, and Roslyn's `SwitchExpressionException` is absent from the .NET Framework
compatibility floor.

## Decision drivers

The exception foundation must:

1. keep raw CLR faults catchable through the Kotlin types that deliberately model them;
2. provide stable Kotlin-owned identities where no faithful BCL type exists;
3. preserve the logical Kotlin hierarchy for every runtime-owned type;
4. avoid broadening a Kotlin catch silently or allowing a logical child to escape its parent;
5. preserve the established `Kotlin.Runtime` assembly identity across both CLR runtimes; and
6. keep default message and cause behavior as close to Kotlin as the System.Exception base allows.

## Rejected alternatives

### Map every Kotlin exception directly to a BCL type

Rejected. `System.SystemException` is not `RuntimeException`: it is deprecated, contains fatal
runtime faults such as stack overflow, and excludes ordinary `System.Exception` subclasses. No
BCL type provides `NoWhenBranchMatchedException`, and `System.FormatException` is not a subtype of
`System.ArgumentException`, so it cannot represent Kotlin `NumberFormatException` faithfully.

### Replace every existing mapping with Kotlin-owned classes immediately

Rejected. CLR division, null dereference, invalid cast, and vector access would continue throwing
BCL types. Kotlin-specific catches would stop seeing those faults unless codegen first added
explicit guards or catch lowering learned union/filter semantics. Changing only the class table
would therefore be observably wrong.

### Map RuntimeException to System.Exception

Rejected. `catch (RuntimeException)` would catch plain checked `Exception` values and every foreign
CLR exception, collapsing a distinction Kotlin source is allowed to observe.

## Candidate decision

Use a hybrid physical hierarchy.

`kotlin.Throwable` and `kotlin.Exception` remain mapped to `[mscorlib]System.Exception`, and the
existing curated BCL mappings remain in place while they are responsible for catching native CLR
faults. Kotlin-owned types live in the `Kotlin` namespace of `Kotlin.Runtime` when no faithful BCL
identity exists.

The first runtime-owned root is:

```text
Kotlin.RuntimeException : System.Exception
```

It has the four Kotlin constructor shapes: no argument, nullable message, nullable message plus
cause, and cause only. A private nullable message field plus a reused virtual `get_Message` slot
preserves `RuntimeException().message == null`; the System.Exception default getter would invent
platform text. The cause-only constructor uses `cause?.toString()` as its message and preserves the
same cause object in `InnerException`, following the mature JVM/Native contract.

Source-level `RuntimeException` remains rejected for now. Existing mapped logical children such as
`IllegalStateException -> System.InvalidOperationException` are not physical subclasses of the new
root, so enabling the parent alone would make a legal parent catch miss its child. The runtime root
is present now only to establish the durable base of exact Kotlin-owned exception types.

The first such type is:

```text
Kotlin.NoWhenBranchMatchedException : Kotlin.RuntimeException
```

It exposes the same four constructor shapes. The exhaustive-when intrinsic now constructs that
type instead of plain System.Exception. Generated consumers reference it through the existing
versioned Kotlin.Runtime AssemblyRef. It remains catchable by the currently supported
`Exception`/`Throwable` mapping, is not accidentally a mapped `IllegalStateException`, and has an
exact identity available to future reflection/type-test support.

Every later exception requires its own interop audit. A BCL-mapped child can move under the
Kotlin-owned root only together with explicit fault translation or catch-union/filter lowering.
No entry may migrate merely because a similarly named runtime class exists.

## Validation

Probe series `exceptionabi_s1` assembled runtime and consumer artifacts with modern 10.0.9 and
.NET Framework 4.8 ILAsm. All four consumer/runtime pairings observed the Kotlin-owned catch,
kept a foreign InvalidOperationException outside RuntimeException, preserved cause identity, and
returned a null default message through a System.Exception-typed call.

The compiler exact-IL pin covers both value and statement exhaustive-when throws and the
Kotlin.Runtime type reference. The existing two-handler test keeps the sibling
IllegalStateException boundary. A rejection pin keeps RuntimeException, Error, and
NumberFormatException unavailable until their distinct hierarchy problems are solved.

## Consequences and boundaries

- Kotlin-specific identities can grow without sacrificing deliberate CLR fault mappings.
- The public CLR runtime surface gains a real Kotlin.RuntimeException base before source mapping
  is enabled; this is intentional ABI groundwork, not a claim that the hierarchy migration is
  complete.
- Exception/Throwable remain physically collapsed at System.Exception for CLR interoperability.
- RuntimeException source use, Kotlin-owned mapped children, Error, NumberFormatException,
  negative-array-size identity, raw-fault translation, catch filters/unions, stack traces, and
  non-Exception throw wrapping remain separate slices.
