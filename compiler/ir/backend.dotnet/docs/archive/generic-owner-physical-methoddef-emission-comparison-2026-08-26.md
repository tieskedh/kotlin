# Generic-owner physical MethodDef emission comparison

Date: 2026-08-26

## Context

The preceding operation-route shadow could select the natural or semantic
MethodDef from one opaque BOUND callable family and prove the required receiver
view. It still compared only lowering-owned route maps. A MethodDef omitted by
the emitter, moved to another TypeDef, or rendered with a different physical
signature could therefore remain invisible to that proof.

The declaration index already names a future
`SEALED_EMISSION_SIGNATURE_INDEX`, but advancing its additive state with only
the two MethodDefs in this first family would be unsound. Any other BOUND fact
without final evidence would survive the transition and appear sealed. This
checkpoint instead adds an explicitly partial, read-only final-emission
overlay.

## Final-fixpoint observation

Simple-method rendering now computes one structured header decision which is
also used to print the IL. It contains the actual physical owner, visibility,
instance/static convention, exact virtual/newslot/abstract/final flags,
method-generic arity, implicit receiver, printed parameters, return carrier,
and split-nullable marker. The method name is retained only for diagnostics.

Raw observations are values of successful rendered methods and classes. They
are discarded with every failed or superseded emission-fixpoint round, folded
through nested and exact-interface views, and collected only from the final
surviving render maps. Capture is enabled only in the generic-owner rehearsal;
both emitter and backend assert that production emission publishes no raw or
normalized header observation.

Normalization independently maps final emitter `ClassInfo` identities to
local physical TypeDefs. It does not seed the map from an expected BOUND
endpoint. The normalized evidence retains actual TypeDef identity, arity and
class/interface category, scoped owner/method parameters, constructed local
types, arrays, managed pointers, the explicit implicit-receiver carrier, and
unavailable/conflicting evidence. A known carrier outside the bounded
structural vocabulary retains diagnostic text, but that text never becomes
physical identity.

## Atomic family comparison

The comparison iterates only opaque callable families already admitted by
BOUND authority. For the current direct-producer grammar it compares both
physical endpoints atomically:

```text
natural interface MethodDef
semantic capability-interface slot
```

Covered structural facts are declaring TypeDef identity, actual/expected
TypeDef arity and category, visibility, BOUND dispatch category,
instance/receiver convention, method-generic arity, ordinary parameter
carriers, and `Void`, `Direct`, or `SplitNullable` result layout/carrier. The
pure comparator receives invocation-local opaque type/method keys; no IR
symbol or diagnostic name crosses into structural equality.

No observation is `UNAVAILABLE`. Duplicate evidence conflicts even when the
headers are identical. A structural mismatch conflicts. The family status is
the fail-closed join of both endpoints. Evidence for the same selected
function on an unexpected owner also conflicts, so one correct MethodDef
cannot hide a rogue duplicate. The sole bounded exception is the natural
source symbol rendered on its independently observed invariant exact sibling:
that owner is ignored only when it has the same logical owner, matching arity,
and actual interface category. The semantic endpoint receives no such
exception, and a selected endpoint observed in another emission scope
conflicts.

The exact emitted virtual/newslot/abstract/final vector and physical method
name remain diagnostic in this partial overlay because BOUND authority does
not yet seal them. The integration fixture independently requires the exact
public abstract interface flag vector on both actual endpoints. `MATCH`
therefore means that all facts covered by this bounded comparison agree; it is
not a claim that the complete MethodDef metadata row is sealed.

## Hostile and product evidence

Pure comparator tests cover missing evidence, identical and different
duplicates, every covered header drift, diagnostic-name independence,
`Direct`/`SplitNullable` disagreement, and the independent composition of an
owner-dependent `!K` input with `SplitNullable(!V, out bool&)`. Direct owner-
classification tests admit only the expected endpoint and the legitimate
natural exact sibling; different logical owners, canonical siblings, wrong
arity/category, and semantic exact siblings are rejected.

The existing inline-producer fixture requires exactly one USER-scope family
with two matching final observations. The natural endpoint is the declared
`InlineProducer<!0>` abstract slot returning `!0`; the semantic endpoint is
the non-generic capability-interface abstract slot returning `object`. Both
PSI parsers execute both Framework 4.8 and .NET 10 products. The exact
production inverse publishes no comparison and emits neither the natural
generic interface nor its semantic capability.

The final normal aggregate exits zero. Direct XML audit covers 195 suites and
2,449 tests with zero failures, errors, or skips: five backend suites/77 tests,
187 FIR suites/2,239 tests, and two integration suites/127 tests are current;
the unchanged up-to-date `dotnet.ir` root retains one suite/six green tests.

## Boundary

This checkpoint does not advance declaration authority beyond
`BOUND_DECLARATION_INDEX`, mutate routing or emission, remove a recognizer, or
change a production product. Production remains atomically erased.

The observation does not yet retain an explicit physical MethodDef role, so a
future family with multiple same-symbol roles on one owner requires richer
identity before admission. Canonical/declared aliases which share one physical
TypeDef also require an explicit alias relation before a full seal. Exact-
sibling MethodDefs, complete TypeDef/BaseType/InterfaceImpl sets, MethodImpl
rows, retained foreign/core constructions, broader input-bearing callable
families, and complete live split-nullable families remain outside this
overlay. A complete sealed epoch must observe every fact in its scope rather
than carrying missing BOUND facts forward.

## Next step

Extend final-emission identity from function-plus-owner to the producer's
actual physical MethodDef role and model physical TypeDef aliases explicitly.
Then close complete-set TypeDef/edge/MethodDef/MethodImpl liveness for one
bounded family before publishing any `SEALED_EMISSION_SIGNATURE_INDEX` or
removing an existing routing recognizer. Broader callable and split-nullable
composition follows the same structural contract; it must not acquire a Map,
stdlib, package, member-name, or IR-origin exception.
