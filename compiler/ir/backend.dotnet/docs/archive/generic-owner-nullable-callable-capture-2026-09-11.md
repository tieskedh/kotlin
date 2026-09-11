# Nullable invocation carriers with exact owner captures

This investigation starts from `9cd35f160c`. Current integration belongs in
[`../../STATUS.md`](../../STATUS.md); the durable contract belongs in the
[callable ABI ADR](../decisions/draft-adr-callable-and-reference-abi.md).

## Reproduced cause

The separate producer declares `Plain<T>` with private immutable `stored: T`
and returns a lambda from `maybe(Boolean): () -> T?`. A new focused test on the
reviewed base reproduces the earlier `Plain.<get-stored>` failure in late
semantic routing. It is not caused by the getter's own state proof.

A temporary planner trace showed:

- `Plain.stored`: `TYPED_STORAGE_PRODUCER_GRAPH_PROVEN`;
- lambda `this$0`: `TYPED_STORAGE_PRODUCER_GRAPH_PROVEN`;
- lambda owner: `BLOCKED_METADATA_FIXED_CONDITIONAL_SUPERTYPE`;
- its supertype list: `Any`, `Function0<T?>`, `ExactFunction0<T?>`.

The nullable-input lambda was blocked for the same reason. An expanded mixed
capture then exposed a second cause: `this$0` remained producer-proven typed,
but `$other: T?` was `TYPED_WRITE_VALUE_PROVENANCE_REQUIRED`. Its constructor
input had incorrectly been seeded as an attempted exact owner-parameter fact
instead of the already fixed boxed-or-null object entry. This left the whole
lambda unavailable. Both temporary traces and imports were removed after
recording their results. No getter, property, capture-origin, source-name, or
state-placement exception was added.

## Physical correction

In rehearsal admission, a fixed non-generic Runtime TypeDef cannot acquire a
conditional InterfaceImpl from logical arguments it does not physically own.
Compiler-owned exact/typed-arguments interfaces are different from ordinary
Kotlin classifiers: their arguments denote invocation carriers. A direct open
`T?` uses the existing fixed `object` carrier in that calling convention.
Nested Kotlin/foreign constructions retain their own obligations.

Prototype supertype diagnostics now consume the planner's selected conditional
edge decision instead of repeating the broader syntax-only classification.
The existing rehearsal InterfaceImpl mapping binds individual compiler-ABI
arguments through the generic-slot mapper, without the ordinary generic
construction guard which intentionally rejects unresolved `Base<T?>` edges.
Actual InterfaceImpl/MethodImpl signature validation remains mandatory.

The entry-fact correction seeds direct nullable owner inputs as object-domain
when their actual single-slot calling convention is boxed-or-null. Separately
mapped primitive-bound inputs are excluded. Existing producer-wide writer
rules then select object storage for that one nullable field; unrelated exact
receiver/state evidence survives unchanged. The change does not select state
from observed construction sites or narrow a broad value to `!T`.

The intended result is one generated generic lambda with a `Plain<!T>` capture,
implementing canonical `Function0` and actual `ExactFunction0<object>`. This is
not `Plain<object>`, not a fabricated `Source<object>`, and not a split-nullable
MethodDef. Its Runtime invocation slot remains one `object` return; the
independent receiver/state remains typed.

Common callable/local-declaration lowerings continue to own generated objects
and capture creation. No shared compiler, Runtime surface, library schema,
ordinary construction mapper, or production admission rule changes. The
production prototype receives the same syntactic conditional edges as before;
only the rehearsal plan selects the narrower physical decision.

## Hostile verification

The expanded fixture covers nullable inputs/results, exact controls, mixed
nullable/exact captures, mutable widened callable locals, identity, Int,
reference, nullable Int, and nominal value-class substitutions across separate
Kotlin assemblies. Its C# consumer checks real InterfaceMaps, one typed owner
field, exactly one typed receiver capture, no shadow object field, and the
absence of a fabricated typed Int interface for an object-returning MethodDef.

The final candidate matrix passed four suites and 24 tests at 18:35:56 local
time. The backend passed 22 suites and 402 tests at 18:36:01. Both have zero
failures, errors, or skips; all five compiler/test source hashes stayed fixed.
The production-erased inverse passed the same four suites and 24 tests at
18:39:19, also with zero failures, errors, or skips and unchanged source hashes.
Evidence lives in
`D:\CodexTemp\nullable-callable-capture-20260911` (`baseline.xml`,
`planner-trace.xml`, `initial-candidate-green.xml`,
`expanded-candidate-24-red.zip`, and `mixed-capture-planner-trace.xml`). The
expanded matrix had 20 green cases and the new fixture failed in all four
lanes before the entry-fact correction. That fixture remains intact. Final
candidate/backend/inverse XML is in `candidate-24.zip`, `backend-402.zip`,
and `inverse-24.zip`.

The selected matrix is:

```text
--tests '*testGenericOwnerNullableCallableCapture'
--tests '*testGenericOwnerCallableSemanticCapture'
--tests '*testGenericOwnerHardestModelOracle'
--tests '*testGenericOwnerHardestModelOracleSeparateCompilation'
--tests '*testGenericOwnerCallableCompositionSeparateCompilation'
--tests '*testValueClassCallableResult'
```

These use `:compiler:fir:fir2ir:dotNetTest --rerun` with
`--max-workers=1 --no-configuration-cache -q`. Only the candidate adds
`"-Pkotlin.dotnet.genericOwnerRehearsal=true"`. The two unchanged hardest-model
fixtures retain the ordinary open-nullable base exclusion. The backend gate
is `:compiler:backend.dotnet:test --rerun`. The inherited full production checkpoint is
`17676a90e1` (2,863 tests); this bounded rehearsal delta is not a new full gate,
Stdlib closure, or production cutover.
