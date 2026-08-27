# ADR: Explicit Kotlin-breaking target changes

- Status: **Accepted — pre-ABI**
- Date: 2026-08-19
- Scope: intentional Kotlin-language compatibility differences in the
  experimental .NET target

This file is the mandatory ledger for behavior which intentionally differs
from the Kotlin specification or from the portable behavior expected by other
Kotlin targets. Silence is not permission: a target difference which is not
listed here remains a bug.

The target is pre-ABI. Each entry must still identify its source boundary,
observable result, unaffected Kotlin behavior, physical rationale, tests, and
rollback boundary. A CLR convenience or performance opportunity is never
enough by itself.

## BK-1: runtime-check unchecked parameterized generic-owner casts

### Source boundary

For a true CLR-generic Kotlin-owned owner, an explicit parameterized `as` or
`as?` with at least one non-star argument uses the runtime generic construction
to check Kotlin subtyping when that argument relation is not already proved by
the source type. An admitted parameterized `is` check uses the same predicate;
the only currently general `is` form, `Producer<*>`, remains classifier-only.
A star target such as `Producer<*>` is otherwise not in this entry. Neither are
ordinary assignments, implicit conversions, declaration-site variance, or
use-site projections.

This boundary is structural. It does not depend on whether the compiler happens
to render an `UNCHECKED_CAST` diagnostic, whether diagnostics evolve, or whether
the warning is suppressed. The diagnostic is required evidence that the source
operation is unsafe; `@Suppress` is not runtime input.

The current bounded implementation covers admitted covariant producer owners,
including recursively nested one-parameter instances and the parentless
multiple-parameter producer-property vector. Every argument is checked with
the declaration's recorded variance. Unsupported owner/argument shapes remain
outside the reified-owner proof; this entry does not authorize guessing their
subtyping.

### Observable rule

Given `interface Producer<out T>`:

```kotlin
val ints: Producer<Int> = ...

ints as Producer<String>  // throws ClassCastException at this cast
ints as? Producer<String> // returns null

ints as Producer<Any>     // succeeds, same object
ints as? Producer<Any>    // succeeds, same object
```

The last two operations must succeed even when the physical object implements
`Producer<int>` and CLR variance alone cannot convert it to
`Producer<object>`. The predicate therefore applies Kotlin declaration-site
variance and recursively compares the physical argument graph. It does not use
constructed CLR equality as Kotlin subtyping.

`as`, `as?`, and an admitted parameterized `is` use the same compatibility
predicate. Only their mismatch result differs. A successful cast preserves
object identity and may retain an `object`/semantic carrier when the Kotlin view
has no truthful constructed CLR type.

### Intentional incompatibility

The Kotlin cast-expression rules state that generic arguments of a
parameterized safe-cast target are not checked with respect to subtyping. This
entry deliberately chooses a stricter .NET result for the structurally
unchecked parameterized operation described above. Such an operation is
expected to carry Kotlin's unchecked-cast warning unless it is suppressed, but
the warning does not select runtime behavior. Code which relied on
`Producer<Int> as? Producer<String>` returning a non-null semantic view is not
portable to this target.

This is not generalized permission to make warning-free Kotlin stricter.
These behaviors remain authoritative and unchanged:

- `Producer<Int> -> Producer<Any>` through ordinary Kotlin covariance;
- `value is Producer<*>`, nullable/negated forms, and star casts through the
  declaration classifier;
- successful widened calls, broad candidate inputs, identity, dispatch, and
  one authoritative state; and
- delayed failure at the first genuinely typed use after a legal semantic
  transition.

### Rationale and physical contract

Admitting an unchecked incompatible view makes representation instability
contagious through generic state such as `Box<Producer<String>>`. Rejecting the
view at its already-warning cast boundary reduces that instability without
erasing `Box<T>` or `List<T>` globally.

The Runtime owns one cached interface-vector inspection and a recursive
Kotlin-variance predicate. It compares compatible natural constructions and
returns the original object. It never creates a wrapper, copied store, shadow
state, or fabricated `Producer<object>` value.

Runtime surface level 39 owns this predicate. The behavior remains behind the
generic-owner rehearsal epoch until the complete owner migration is admitted.

### Evidence and rollback

The separate-compilation hostile probe covers Kotlin-owned and ordinary
precompiled C# `Producer<int>`/`Producer<string>` implementations, throwing
and safe mismatch, `Int -> Any` covariance, recursive nested covariance,
stars, identity, member dispatch, both FIR parsers, Framework 4.8, and .NET 10.

Before public ABI freeze this entry may be removed atomically with its runtime
predicate, object-carrier routing, tests, and generic-owner cast records. After
freeze, changing it requires an explicit language-compatibility migration; a
silent runtime change is forbidden.
