# Callable carrier constructions across semantic captures

This snapshot records the bounded correction developed from `40ef9b75f2`,
then reverified on the separately repaired production base `17676a90e1`.
Current integration belongs in [`../../STATUS.md`](../../STATUS.md); the
callable contract belongs in the
[callable ABI ADR](../decisions/draft-adr-callable-and-reference-abi.md).

## Reproduced boundary

After private overload naming was repaired, the source-built census reached an
existing lambda rejection: the generated object lacked the `ExactFunction1`
construction required by its physical MethodImpl. A separate Kotlin producer
and consumer reproduced both input and output variants without collection
code. Their captured owner was canonical, and the nested logical `Source<T>`
referred to its erased parameter. Ordinary value mapping consequently returned
`object` for the whole optional callable capability, leaving no InterfaceImpl.

The synthetic exact and typed-arguments declarations have a different contract
from a Kotlin generic classifier: their arguments denote physical invocation
carriers. The correction maps that declared construction through the existing
per-argument generic-slot binder before ordinary value-level owner erasure can
discard the entire capability. This path is limited to the compiler-created
ABI symbols, is rehearsal-gated, and changes no Runtime metadata. The same
interface-mapping query supplies both the linked physical graph and the emitted
InterfaceImpl. Existing MethodImpl signature and owner-view validation remains
unchanged and mandatory.

The initial emitted producer demonstrates:

```text
consumer: ExactFunction1<object, int32>
producer: ExactFunction0<object>
```

That is a real implementation of the compiler's physical carrier contract,
not a fabricated `Source<object>` identity. The same receiver and stored source
object remain authoritative. Ordinary Kotlin/foreign generic declarations do
not enter this mapping path.

## Hostile evidence

The expanded fixture retains:

- separate Kotlin producer/consumer assemblies and ordinary C# source objects;
- canonical input and result captures with identity-preserving widening;
- mixed `object`/`int32` inputs and an `int64` result, plus the same object's
  typed-arguments capability;
- nominal value-class callable results, nullable/value-class source payloads,
  mutable callable locals, and independently generic `Plain<T>` captures;
- actual `!T` fields and exact value/reference/nullable/value-class controls;
- uncaptured open-nullable callable input and null-result controls; and
- C# InterfaceMap verification of the complete physical parameter/result
  vectors, with no hidden ABI requirement on the C# source implementation.

An attempted open-nullable result capturing `Plain<T>.stored` failed earlier
in semantic getter routing, before this InterfaceImpl boundary. Its source and
diagnostic are preserved rather than bypassed: this slice does not claim that
broader captured-getter composition. Callable-reference Runtime annotation
epoch closure also remains open. No full Stdlib success is implied.

## Verification

Evidence is under `D:\CodexTemp\callable-semantic-capture-20260911`:
`baseline.xml`, initial candidate IL/DLLs, the candidate development XML,
`nullable-captured-getter-probe.kt`, and
`open-nullable-before-binding.xml` for the independent getter-routing failure.
The original 24-case candidate was green, but its production inverse exposed
an existing nominal value-class callable result defect on all four profiles/
parsers. The feature was parked as owned stash
`9925190c497a151f1cd8cbcec2d1e9e3a5391311`; its hostile fixture was not weakened.
The independent [production repair](value-class-callable-nominal-result-2026-09-11.md)
was committed and pushed first as `17676a90e1`, after a full 2,863-test gate.
The stash then reapplied without conflicts.

The final candidate on that repaired base is green: four suites, 28 tests,
with no failures, errors, or skips. The backend is green: 22 suites, 402 tests.
Their XML is archived as `final-candidate-28.zip` and `final-backend-402.zip`;
the identical production inverse passed four suites and 28 tests, with zero
failures, errors, or skips (`final-inverse-28.zip`). The candidate finished at
16:19:48 and inverse at 16:22:58 local time. All three changed compiler/test
source hashes were unchanged across the two runs. The inverse verifies absence
of rehearsal epoch records and generic owner TypeDefs in the fixture family.
Production remains erased and structurally outside the new mapping branch.
The inherited full checkpoint is `17676a90e1` (2,863 tests), not a new full
aggregate, Runtime ABI, or production cutover.

The diagnostic `post-correction-census.xml` no longer reports the
`AbstractMap.toString` lambda's missing exact callable view. The containing
class now reaches its anonymous keys object's unavailable generic base
construction. The overall census still has 234 cascading error lines; neither
that count nor this advancement is a complete Stdlib gate.

The generator is `:compiler:fir:fir2ir:generateTests`; all four parser/profile
runners register the new fixture. The backend gate is
`:compiler:backend.dotnet:test --rerun`. Both matrices use
`:compiler:fir:fir2ir:dotNetTest --rerun` with:

```text
--tests '*testGenericOwnerCallableSemanticCapture'
--tests '*testGenericOwnerCallableCompositionSeparateCompilation'
--tests '*testGenericOwnerSemanticOverloads'
--tests '*testGenericOwnerSemanticBodyExactResultChain'
--tests '*testGenericOwnerSemanticIteratorResult'
--tests '*testValueClassSeparateCompilation'
--tests '*testValueClassCallableResult'
```

Commands use `--max-workers=1 --no-configuration-cache -q`. Only the candidate
adds `"-Pkotlin.dotnet.genericOwnerRehearsal=true"`. The seven fixtures therefore
produce 28 cases per matrix across both parsers and executable profiles.
