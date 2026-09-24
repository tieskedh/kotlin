# Coherent Kotlin interface dispatch through bottom views

## Scope and reproduced defect

This rehearsal-selected representation correction starts from full production checkpoint
`96e600ca1188af20bcf68f3a610c90ec0b01abe5`. It exercises coherent Kotlin-owned
interface families, not conflicting foreign implementations. Both parked
constructor experiments remain untouched. There is no pair carrier, wrapper,
shadow state, new runtime helper, Common change, or stdlib census. Physical
library ABI advances from 71 to 72 for the alternate result and source signature;
the version change applies to all produced libraries, so it requires a fresh
full production-erased gate. Generic-owner artifact schema 22 and runtime
surface 62 remain unchanged.

The existing `foreignKotlinInterfaceResult` fixture previously tested a bottom
producer's identity without invoking its operation through the widened views.
Adding real calls reproduced a compiler rejection on both parsers and runtimes:

```text
value 'bottom' has type BottomSource where Source`1<string> is expected
```

The legal Kotlin assignment `Source<Nothing> -> Source<String>` reached a
natural CLR local without proof that the receiver had that construction. The
old negative policy excluded closed reference arguments without a proper CLR
value subtype. That exclusion was not positive physical authority: the
`Kotlin.Nothing` carrier is not CLR `string`.

All four original failure XML files and the original fixture are preserved at
`D:\CodexTemp\coherent-kotlin-interface-dispatch-20260924\before-fix`.
The first corrected four-lane run is preserved under `first-candidate` in the
same directory; it predates the nullable and exact-string metadata additions.

## Structural correction

The shared negative carrier policy now treats an unproved Kotlin-owned
covariant occurrence as requiring the semantic boundary. Exact producer,
authoritative field-plan and natural MethodDef evidence still takes precedence.
The change does not manufacture a CLR view or add an emitter cast.

The reified-interface lowering returns before this policy when rehearsal is
disabled. Constructor planning already retained every output-variance hazard
before consulting the shared policy; its admission boundary is unchanged.
Contravariant reference inputs, invariant arguments, stars, projections and
existing open-parameter checks retain their previous decisions. Closed nullable
output arguments intentionally gain the same bottom-view protection. The now-unused
primitive-subtype callback is removed from the two policy callers.

Natural interface slots, typed MethodImpl adapters and paired public input
entries retain their selected signatures. Separate consumers still read the
producer's carrier records rather than re-plan external stubs. Retained foreign
metadata is unchanged. The owning rule is in the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md#definitions-conversions-and-calls).

The expanded 32-test regression run initially passed 28 tests and failed the
four copies of `genericOwnerForeignOverrideSeparateCompilation`: the existing
natural C# `exactView` identity function lost its typed result. The initial
policy change exposed that the paired classifier-input grammar excluded
interface results and its `Q` record described only inputs. This was not repaired
by casting in C# or changing that natural-surface expectation.

The bounded paired-entry grammar now also admits a complete single-return
producer which forwards its selected input unchanged with the same interface
construction. The source remains typed; the twin returns the same receiver
through object. The `Q` record independently encodes the alternate result from
the actual emitted MethodDefs. Imports reconstruct that result, not a new
logical approximation. The mandatory ABI-72 payload rejects the old input-only
record. A PE validator checks the relation between both physical endpoints,
including unmodified parameters, generic constraints, dispatch and result.
The copied nonvirtual entry also requires a non-overridable source: a
nonvirtual method, a final virtual method, or a method on a sealed owner.
Otherwise a foreign override could be bypassed. Validation cannot select an
otherwise ambiguous source overload merely because one candidate is final.

An additional read-only audit found a real regression in the first validator:
the weak source `F` endpoint plus the unmodified parameter/result relation
cannot distinguish `render(Source<String>): String` from
`render(Any?): String`. Both natural overloads already have distinct physical
signatures, even in the erased inverse; this is not the parked problem of
colliding erased public overloads. Rejecting their library was not an acceptable
restriction. The full run was deliberately stopped before claiming a gate.
The amended `Q` record therefore also carries the complete source signature
from final emission and uses it to bind the exact MethodDef before checking
the alternate relation. The existing separate-module fixture now exercises
these public overloads from Kotlin and C#, including a bottom producer.
The pre-amendment failure was established by source review, not a claimed
executed red test.

The source signature uses the existing physical-signature codec. It is recorded
from the final emitter carrier graph, including implicit-receiver separation,
method binders and split results, without remapping a logical Kotlin type.
Producer type categories come from final TypeDef observations. Nested external
types retain their enclosing AssemblyRef, and an explicit slash-separated
metadata-name chain distinguishes nesting from namespaces. Validation walks
the exact TypeRef scope chain. Other scalar leaves retain core value-type
identity; this is not a new source mapping or a generic-owner schema revision.
The overload fixture also executes a paired method whose unrelated slots are
`Long`, `Long?` and a `Long` result, preventing the complete-header requirement
from accidentally reducing the existing scalar grammar.

The complete return scan also fixes a related false proof: an earlier nested
return must not be ignored merely because the final top-level return produces
an exact object. Multiple source-target returns stay outside the existing
single-producer result proof. No new join inference is introduced.

## Executable observations

The Kotlin library and consumer are compiled separately. The fixture checks:

- A `Source<Nothing>` implementation increments its counter and throws the
  original exception through separate Int and String views, ordinary library
  forwarding, a broad stored result, `Any`/star recovery and `ValueBox<Any>`.
  Separate operations require exact cumulative call counts and the same exception.
- A `Source<Nothing?>` receiver returns null through `Source<String?>` after
  executing its body exactly once. A fabricated null fallback cannot pass.
- Int and String returning controls preserve values and receiver identity
  through widened operations, forwarding, star recovery and generic object
  transport. Existing mutable replacement checks remain.
- A separately proven String producer keeps its private `Source<string>` field
  and public natural result. A separate C# executable inspects both physical
  types and verifies state/read identity and behavior. The erased inverse
  expects the corresponding non-generic `Source`.
- Fixed and method-generic interface forwarding retain typed C# results while
  separately compiled Kotlin bottom views use the independently recorded object
  result. A mixed-return negative keeps the branch-selected value without
  inferring a typed identity function from only its final return.
- A bottom producer passes through the separately compiled closed
  `Source<String>` overload of `ExactRenderer`. Its private broad physical entry
  is semantic and has a stable distinct overload name; the public natural
  `Source<string>` entry remains callable by ordinary C# String implementations.

Identity checks before dispatch use a non-inline, contract-free helper. Direct
successful `===` checks could teach FIR that an interface variable is the
concrete bottom receiver and accidentally collapse the intended interface call.
Exception checks happen after dispatch. Counter assertions additionally guard
against premature conversion failures and duplicate execution.

The six policy unit tests cover covariant reference/nullability hazards,
reference contravariance and physical invariance, primitive/open arguments,
unchanged invariant boundaries, mismatched arity, stars and projections.
Seven codec tests cover independent result/source-signature round trips and
malformed/old/duplicate records. Twenty physical-metadata tests cover both endpoints, instance
receiver offsets, object results, preserved parameters and constraints,
same-module aliases, exact overload binding, scalar/nullable and nested external
carriers, genuine ambiguity, and final-method/sealed-owner guards. Seven
signature-recording tests cover emitter-owned headers, physical binders, split
results, both core profiles, primitive/nullable/array carriers and nested scopes.
The CLI/library integration test injects `Q` alone into an ordinary producer's
embedded KLIB, with either a missing alternate MethodDef or a missing source
`F`. It requires the precise frontend descriptor/DLL diagnostic, not a later
production-epoch rejection. Positive separate-library candidate tests also
traverse the actual CLI frontend before their rehearsal backend is configured.

## Verification boundary

The final focused invocation passed on 2026-09-24:

```text
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:backend.dotnet:test :compiler:fir:fir2ir:dotNetTest --tests "*testForeignKotlinInterfaceResult" --tests "*testGenericOwnerSemanticOverloads" --tests "*testGenericOwnerMethodGenericSealedEmission" --tests "*testGenericOwnerForeignOverrideSeparateCompilation" --tests "*testGenericOwnerRehearsalStateCarriers" --tests "*testGenericOwnerSplitNullableResultSeparateCompilation" --tests "*testGenericOwnerAbstractForeignOutput" --tests "*testGenericOwnerClosedConstructorInput" :compiler:tests-integration:dn --tests "*testRejectsEmbeddedGenericOwnerInputEntryWithoutPhysicalEndpoint" --tests "*testKotlinClassifierPhysicalOwnersRoundTrip" -q
```

Direct XML audit found 28 backend suites / 460 tests, four FIR2IR suites /
32 tests and one CLI suite / two tests, with no failures, errors or skips.
The FIR matrix runs all eight
fixtures on PSI and LightTree, Framework 4.8 and .NET 10. Its candidate XML is
preserved under
`D:\CodexTemp\coherent-kotlin-interface-dispatch-20260924\signature-candidate`.
The earlier `final-candidate` subdirectory contains the pre-overload-amendment
445/32-test evidence, not the final feature checkpoint.

The full production-erased gate is required because the physical library ABI
changed. It explicitly replaces both filtered FIR and CLI task outputs and
serializes the external-tool lanes:

```text
.\gradlew.bat --no-parallel :compiler:fir:fir2ir:dotNetTest --rerun :compiler:tests-integration:dn --rerun :compiler:backend.dotnet:dotNetTest -q
```

The first full invocation was intentionally interrupted after the overload
review; its nonzero worker exit is not a compiler failure and supplies no full
gate evidence. The next invocation completed all 2,383 FIR2IR tests and exposed
four failures in the new fixture's erased C# consumer: `keepSource<T>(Source)`
still has a method binder, but the consumer omitted `<int>` in the erased epoch,
where its argument cannot infer `T`. The test now supplies `<int>` in both
epochs. This changes no compiler code and renders identical candidate C#.
The complete failing XML is preserved under `erased-fixture-inference-failure`.
The corrected focused inverse passed all four parser/runtime copies, and the
complete CLI/library suite passed 130 tests in two suites, without failures,
errors or skips:

```text
.\gradlew.bat --no-parallel :compiler:fir:fir2ir:dotNetTest --tests "*testForeignKotlinInterfaceResult" :compiler:tests-integration:dn --rerun -q
```

Their XML is preserved under `corrected-erased-inverse` and
`final-production/cli`. Source hashes confirm that only the one-line C# test
correction changed since the earlier full FIR invocation. The replacement full
gate explicitly reruns the now-filtered FIR task and reuses the freshly green,
unfiltered CLI outputs:

```text
.\gradlew.bat --no-parallel :compiler:fir:fir2ir:dotNetTest --rerun :compiler:backend.dotnet:dotNetTest -q
```

This replacement full gate passed on 2026-09-24. Direct XML audit found:

| Lane | Suites | Tests |
| --- | ---: | ---: |
| Backend | 28 | 460 |
| Physical CLI model | 1 | 6 |
| Full FIR2IR | 187 | 2,383 |
| Full CLI/library integration | 2 | 130 |
| **Full production total** | **218** | **2,979** |

All declared counts equal the actual testcase counts; there are no failures,
errors or skips. Each of the eight focused fixtures has all four erased
parser/runtime copies in the full output. FIR and CLI were explicitly rerun
unfiltered. Backend and physical-model tests were up to date and their complete
XML was audited; this does not claim that every dependency freshly executed.
The physical-model XML is unchanged from 2026-09-11. Full evidence is under
`final-production`, including the frozen semantic input hashes in `inputs.json`.
No semantic source changed during the final full invocation. The only change
since the final candidate invocation is the test correction above, which leaves
the candidate C# text unchanged.

## Explicit exclusions

`ValueBox<Any>` proves ordinary generic object transport. It does not prove that
an already allocated `ValueBox<Source<string>>` can hold every Kotlin bottom
view. Nested generic state, existing foreign allocations and public constructor
composition retain their independent guards. The exact nested-state regression
fixtures must not be changed to hide a new representation mismatch.

This checkpoint neither selects a policy for conflicting foreign implementations
of Kotlin-owned interfaces nor promotes historical-selector transport. It makes
no performance, trimming, NativeAOT, complete-candidate or production-cutover
claim. The Common/JVM baseline and broader architectural limits remain in the
[selection-contract](selected-interface-selection-contract-2026-09-13.md) and
[operation/storage research](target-directed-interface-dispatch-research-2026-09-13.md)
archives.
