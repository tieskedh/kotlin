# Semantic interface owner-parameter result transfer

This snapshot records the bounded correction developed from `e3c4e79a88`.
Current integration belongs in [`../../STATUS.md`](../../STATUS.md); the
durable transfer rule belongs in the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md#definitions-conversions-and-calls).

## Reproduced failure

An expanded rehearsal matrix found that
`genericOwnerSemanticBodyExactResultChain` failed across both parsers and
runtimes. An open producer returned its iterator through the semantic result
contract. `Iterator.Next()` then returned physical `object`, but a downstream
local reconstructed `MutableEntry<object, object>` from the remapped Kotlin
type. The actual object implemented `MutableEntry<int, string>`; the generated
cast threw before the broad candidate comparison could execute.

The same failure was reproduced with the emitter restored byte-for-byte to the
base commit. It was not caused by the pending private-overload naming change.
That change was parked as owned stash
`e5d88e60c46d66feb7fc2b939e4c78a67b4a9583`, with its evidence under
`D:\CodexTemp\semantic-overloads-20260911`, while this repair was isolated.
This experiment identifies a failure on the reviewed base; it does not assign
the original regression to a particular earlier commit without a bisect.

## Missing transfer and correction

The value-routing query already understood eagerly selected semantic slots,
whose physical result projections usually expose `Any?` directly. A retained
canonical interface call can instead be selected only at emission. Its logical
source still returns its owner's `T`, so the absence of an eager call target
incorrectly allowed the substituted result type to regain apparent exactness.

For an admitted Kotlin-owned interface with a semantic receiver, a result which
is directly that interface's owner parameter stays in the canonical object
domain. Owner identity is checked by the actual parameter symbol, not index
coincidence. An independent method parameter or fixed return is not such a
result. An explicitly selected callable result retains its existing authority.
The returned object can be an ordinary foreign implementation, so it also
cannot be assumed to implement a Kotlin semantic capability.

The correction changes no MethodDef, field, constructor, Runtime/Stdlib source,
artifact schema, foreign metadata, or production-selected route. The entire
lowering is rehearsal-gated. It grants no generic construction and introduces
no wrapper or duplicate state. The original example now retains an `object`
local only for the genuinely semantic result, while its authoritative
`SingleMutableEntry<!K, !V>` field remains typed.

This follows the shared distinction between logical result and physical call
signature. JVM's return-signature mapper separates generic signature writing
from the executable carrier, Native derives the LLVM return convention from
its binary type, JS lowers calls without CLR constructed generic identities,
and Wasm uses erased upper bounds for its reference mapping. Their erased
classifiers do not justify creating a constructed CLR interface at this
boundary.

## Hostile scope

The new separate-assembly `genericOwnerSemanticIteratorResult` fixture covers:

- nested iterator results and sequentially different source-local constructions;
- value, reference, nullable-value, and Kotlin value-class payloads;
- an invariant nested interface exposed through an open covariant producer;
- actual object identity through Kotlin and foreign result chains;
- an ordinary C# subclass overriding only the natural abstract entry;
- ordinary C# iterator and atom implementations, including boxed structs,
  without generated semantic-interface implementations;
- retained `!T` state in the producer classes and an exact
  `Iterator<Atom<string>>` parameter on the natural entry; and
- an independently generic `!!R` parameter/result on the canonical control
  interface. That control interface remains erased; this is not a claim that
  its whole multi-member shape has become a natural generic interface.

The older result-chain fixture remains the executable regression. Its comment
now distinguishes exact state from an open producer's semantic result instead
of promising an exact nested construction merely from the exact outer receiver.

## Verification and evidence

The complete backend suite is green: 22 suites, 402 tests, no failures/errors/
skips. The candidate matrix is green: four suites, 36 tests, no failures/errors/
skips. The identical production-erased inverse is also green: four suites,
36 tests, no failures/errors/skips. The inherited full production-erased
checkpoint is `3a2384f636`
(2,821 tests); this bounded rehearsal correction is not a new full aggregate.

Evidence is under `D:\CodexTemp\result-chain-regression-20260911`:
`baseline.xml`, the baseline/candidate emitted IL and DLLs, the route trace,
the nested producer exports, `backend-402.zip`, `candidate-36.zip`, and
`inverse-36.zip`. The baseline contains the
invalid constructed cast; the corrected IL contains the object local and the
unchanged typed field. These diagnostics are evidence, not a published ABI.

The generator was `:compiler:fir:fir2ir:generateTests`; all four parser/profile
runners contained the new fixture. The final commands used `--max-workers=1`,
`--no-configuration-cache`, and `-q`. The backend task was
`:compiler:backend.dotnet:test --rerun`; both matrices used
`:compiler:fir:fir2ir:dotNetTest --rerun` with these filters:

```text
--tests '*testGenericOwnerSemanticIteratorResult'
--tests '*testGenericOwnerSemanticBodyExact*'
--tests '*testGenericOwnerAbstractForeignOutput'
--tests '*testGenericOwnerCompleteNaturalInterfaceSeparateCompilation'
--tests '*testGenericOwnerForeignScalarResult'
--tests '*testGenericOwnerSplitNullableResultSeparateCompilation'
--tests '*testGenericOwnerCallableCompositionSeparateCompilation'
```

Only the candidate supplied `"-Pkotlin.dotnet.genericOwnerRehearsal=true"`.
The wildcard selects three exact-body fixtures, giving nine fixtures per
parser/profile. Compiled source hashes were unchanged through the final gates.

The wider Runtime/Stdlib census, general nested carrier binding, AOT/trimming,
and complete callable-reference closure remain open. No production cutover or
broader exact-result proof is implied by this correction.
