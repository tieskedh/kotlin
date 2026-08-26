# Generic-owner physical operation-route shadow

Date: 2026-08-26

## Context

The prior checkpoint could prove that a local `C<!T>` value possessed the
recorded `I<!T>` InterfaceImpl view, select that natural view for an immutable
alias, and compare its local plus one emitted natural call operand. It could
not describe which physical MethodDef a logical call selected. In particular,
value provenance could not itself decide between the natural generic-interface
entry and the semantic capability-interface entry without becoming a second
callable-family router.

This slice needed one read-only operation proof which starts from declaration
authority, treats provenance only as evidence for the already requested
receiver view, and cannot repair a missing endpoint by crossing to its sibling.

## BOUND callable authority

The symbolic declaration model now describes the route-relevant MethodDef
facts: declaring TypeDef, visibility, dispatch, instance/static shape, method
generic arity, regular value slots, and an independent result layout. These are
not final-live raw CLR MethodDef headers. The receiver is not an ordinary
parameter slot. A result is `Void`, `Direct(slot)`, or
`SplitNullable(payloadSlot, out bool& isNull)`; the hidden flag is physical and
never becomes a Kotlin value parameter.

The local BOUND callable adapter admits only a public abstract, parameterless,
non-suspend direct producer on an existing ROOT/OWNED interface family. The
currently exercised receiver graph is the narrower no-exact family from the
prior class-edge checkpoint. The callable adapter derives two MethodDef
identities from the selected logical member and its exact generated
capability-interface slot:

```text
natural  = Local(logical member, TYPED_ENTRY)
semantic = Local(capability-interface member, no member-family role)
```

The natural endpoint must return the exact natural-owner parameter under
`STRICT_OWNER_OUTPUT`; the semantic endpoint must return `object` under the
same logical output domain. Both must be distinct public abstract instance
slots with zero method parameters and no ordinary value parameters. The
result is stored behind an opaque logical-member family. The live selector asks
that authority for one endpoint and passes only the selected MethodDef identity
to the pure operation query; the query cannot construct, pair, or cross-fallback
between raw MethodDefs. Duplicate callable families and contradictory
declaration descriptions conflict, while identical duplicate MethodDef
descriptions coalesce. Unsupported and retained-foreign descriptions without a
complete metadata adapter remain unavailable.

## Logical selection and physical proof

For the admitted single-parameter covariant producer, logical receiver shape
selects the endpoint before provenance is consulted:

```text
I<T>     -> natural MethodDef on I<!T>
I<Any?>  -> semantic capability-interface MethodDef
I<T?>    -> semantic capability-interface MethodDef
```

The outer call receiver type supplies that logical policy. Looking through a
local to its exact initializer is allowed only afterward, to prove that the
selected MethodDef's required physical owner view already exists. The desired
view is a selector, never evidence. Selected-view lineage remains non-
evidentiary and is not consulted by this operation slice. Thus exact provenance
supports `I<T>` but cannot narrow `I<Any?>` or `I<T?>`; a missing natural edge
or selected MethodDef is unavailable rather than a semantic fallback.

The pure query instantiates the already selected MethodDef header with the
proven receiver construction and checks each regular argument
identity-preservingly. Broad candidate inputs remain outside this first live
slice. The result layout is independent of those input policies: a model-level
two-parameter lookup combines an exact `!K` input with
`SplitNullable(!V, out bool&)`, retains `int32` payload and argument carriers,
and rejects an `object` argument without a Map or member-name rule.

## Live shadow evidence

The shadow runs after the existing generic-interface routing fixpoint. It reads
the stable final natural/semantic route maps and the POST physical-value facts
for the same physical function and local symbol. Calls without one unique
successful POST storage fact are omitted rather than counted as coverage;
conflicting declaration facts remain conflicts. Nested functions and classes
are traversal barriers.

The existing fixture exercises an exact natural alias, a broad alias, an open-
nullable alias, and an inlined broad outer view for value and reference
substitutions. Exact selection matches the direct natural route. Broad and
open-nullable selection matches the semantic capability slot, including the
existing guarded semantic route whose natural fallback remains visible in the
recorded actual-route kind. A nested-lambda call does not become an operation
of its containing function. Production/off publishes no value or operation
shadow.

Pure hostile tests independently remove or replace the receiver edge and each
selected MethodDef, exchange endpoint/view combinations, supply unproven
receivers or wrong argument counts, reject suspend producers from the ordinary
grammar, and exercise declaration binder/arity/duplicate conflicts. None of
those failures authorizes endpoint fallback.

## Executable evidence

The focused backend model suite and all four PSI/LightTree plus Framework 4.8/
.NET 10 fixture lanes pass. The fixture validator also retains the prior
isolated emitted-IL assertion for the natural `I<!0>::produce` operand and
executes the corresponding profile-specific assembly. This matters because
the new comparison observes the final router maps, not every inference made
inside emission.

The final normal aggregate exits zero. Direct XML audit covers 194 suites and
2,439 tests with zero failures, errors, or skips. Backend, FIR, and integration
freshly wrote four suites/67 tests, 187 suites/2,239 tests, and two suites/127
tests respectively; the unchanged up-to-date `dotnet.ir` root retains one
suite/six green tests.

## Boundary

This remains behavior-neutral BOUND shadow architecture. It does not select or
rewrite a call, local, field, MethodDef, MethodImpl, InterfaceImpl, state path,
or ABI record. Production remains atomically erased and publishes empty
authority/value/operation evidence. The capability-interface slot is distinct
from the private final class capability dispatcher; a MethodImpl body cannot
stand in for a missing abstract interface MethodDef.

The comparison does not yet prove final MethodDef liveness or its actual
emitted header. The emitter may rebuild a class-local owner/signature during
its retrying render fixpoint. Advancing the existing declaration index
additively with a partial observed set would be unsound: an evicted BOUND fact
would survive and be falsely labelled sealed. Retained foreign metadata,
separate products, input-bearing live families, split-nullable emission,
MethodImpl rows, and complete TypeDef/InterfaceImpl observations remain open.

## Next step

Capture structured raw MethodDef headers from the successfully rendered
method itself and retain observations only from the final successful emitter
fixpoint round. Compare both direct-producer endpoints for exact liveness,
owner, visibility, dispatch, arity, receiver shape, parameters, and Direct
result carrier. Keep the shared authority epoch BOUND: this first comparison
is an explicitly partial sealed-emission overlay. A later atomic
`SEALED_EMISSION_SIGNATURE_INDEX` transition must cover every retained
TypeDef, complete edge set, MethodDef, split-nullable hidden carrier, and
MethodImpl in its scope without additive carry-over.
