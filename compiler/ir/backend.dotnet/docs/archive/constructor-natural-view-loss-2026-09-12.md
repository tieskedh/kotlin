# Natural constructor view lost at a semantic boundary

Reviewed base: `bd0f943a245f5610781dada2ca3fd24a8aaa033e`. Its completed
recursive-state repair has 48 candidate, 414 backend and 48 inverse tests.
The inherited full production checkpoint remains `3724aeab9c` (2,932 tests).
The fresh source-built census is exactly 215 diagnostic lines, unchanged
from `f132c354f5`. This investigation does not advance that census.

## Reproducer and failed proposal

The separate Kotlin producer declares a covariant `Source<T>` and:

```kotlin
open class MixedInput<T>(private val value: T, source: Source<Any?>) {
    init { check(source.read() == 7) }
    fun read(): T = value
}
```

The baseline keeps this owner erased because its public natural/semantic
constructor contract is unclosed. An unpromoted experiment preserved `!T`
state and generated a typed constructor which delegated to the original
object-domain constructor. Both endpoints remained on the same CLR object.
The semantic endpoint retained its producer-recorded `L` signature.

Earlier probes caught two useful failures: a general early variance plan did
not prove actual interface admission, and a later occurrence heuristic narrowed
closed-reference semantic inputs back to natural signatures. The experiment
reused the existing root-admission predicate and made the chosen parameter
domains explicit. This allowed the new owner, field and constructor metadata
assertions to pass. It did not establish executable correctness.

The hostile C# consumer implements both `Source<object>` and `Source<int>` on
one object. Its public `object read()` returns 7; its explicit integer member
throws, making wrong-view dispatch observable. Ordinary C# construction selects
the typed `MixedInput<int>(int, Source<object>)` overload. Execution then fails:

```text
MixedInput<T>..ctor(T, Source<object>)
  -> MixedInput<T>..ctor(T, object)
  -> GenericInterfaceDispatch.InvokeRecordedMember
  -> InvalidOperationException:
     A foreign Kotlin generic-interface view has multiple CLR constructions
```

The runtime implementation confirms the cause: `InvokeRecordedMember` searches
the receiver's interfaces by open generic definition and rejects a second
distinct construction. It receives no witness for the natural entry's selected
`Source<object>` view. Its refusal to guess is correct; the proposed forwarding
entry discarded information needed to preserve a valid typed C# call.

The reported missing `box()` is a secondary harness failure after the C#
consumer threw. The expanded Kotlin behavior and initialization-count assertions
therefore have not passed and must not be presented as positive evidence.

## Architectural consequence

A natural entry's verifier-proven input cannot be degraded to object and then
reconstructed by requiring the runtime object to have only one construction.
The existing constructor admission guard remains in place. This is a failure
of the proposed forwarding strategy, not a proof against CLR-generic owners.

Two alternatives require investigation before reopening that guard:

- retain natural typed execution, using representation-specific physical bodies
  from one logical Kotlin body where necessary; and
- preserve a selected-view witness across genuinely semantic boundaries.

Body specialization must account for transitive calls, `this`/`base` delegation
and retained state, not just inline one `read()`. Witness transport must remain
a selector over independently guaranteed views, never a source of TypeDef
authority. Neither approach may introduce receiver wrappers, shadow state,
fabricated constructions, or hidden implementation duties for C# authors.
Stars/projections, mixed-view joins, stored inputs and separate assemblies need
hostile tests before claiming the general boundary is closed. Merely removing
the runtime ambiguity check or banning the C# reproducer is not this fix.

## Recovery and evidence

The seven-file experiment is preserved in stash
`8f9f5068424b3f269260e059c260db8061f7e1c2`, based on the reviewed base above.
No existing stash was altered or dropped. The implementation and its provisional
ADR extension were removed from the integration worktree; only this evidence
and the current-status update are promoted.

`D:\CodexTemp\generic-owner-constructor-pair-20260912` contains the complete
`construction-experiment.patch`, `baseline-test.patch`, baseline and intermediate
XML, and `expanded-first.xml` with the executable C# failure. The earlier
recursive-state evidence directory contains `post-bd0f943a24-census.xml`.
No full compiler gate is claimed for this Markdown-only checkpoint.
