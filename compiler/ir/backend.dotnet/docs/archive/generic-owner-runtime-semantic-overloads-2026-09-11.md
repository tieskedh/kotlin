# Runtime-declared authority in private overload naming

This snapshot records the bounded correction developed from `186b997b33`.
Current integration belongs in [`../../STATUS.md`](../../STATUS.md); the
durable declaration-authority rule belongs in the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md).

## Finding

The private custom-interface overload proof was green, but a fresh source-built
Stdlib census still reported the analogous private helper collision. The prior
change had not closed that census blocker. Extending the same separate-module
fixture with `Iterator<T>` and `Map.Entry<K, V>` independently reproduced two
`render(object)` collisions on the promoted base.

The discriminator callback recognized current and producer-recorded reified
interfaces, but omitted already selected Runtime natural-interface descriptors.
Those descriptors are not new representation exceptions: they are the existing
physical authority for the Runtime's natural and semantic sibling identities.
The naming rule needs their selected natural-owner fact, not the origin of the
declaration record.

## Correction and boundary

Feed both existing authority queries to the same private name-selection rule.
No declaration, package, collection, nested-owner, or member name appears in
that decision. The Runtime catalogue itself is unchanged. Current logical
signatures, selected parameter carriers, and existing bound/foreign/virtual
MethodDefs retain their previous authority. No Runtime family is newly
admitted, and production remains structurally outside this rehearsal branch.

The extension uses the original private overload fixture and its inverse,
rather than creating a parallel representation or naming algorithm. PE checks
require private object signatures with distinct complete logical discriminators.
Ordinary C# iterator and entry implementations exercise the private Kotlin calls
and preserve the stored object's identity without implementing compiler ABI.
The earlier custom, star, declaration-order, overload-set, default-argument,
public-name, and exact-reference controls remain in that fixture.

This is still not a public-overload or virtual-family naming contract. It does
not close the remaining inheritance, callable-reference, or Runtime epoch
compositions of the source-built census.

## Evidence

`D:\CodexTemp\runtime-semantic-overloads-20260911\baseline.xml` contains the
two independent Runtime-interface collisions before the correction.
The preceding census is preserved at
`D:\CodexTemp\semantic-overloads-20260911\post-186b997b33-census.xml`.
It had 234 cascading error lines, not 234 independent defects.

The post-correction census is preserved as `post-correction-census.xml` in the
new evidence directory. The private overload collision is gone. The containing
class now reaches its lambda's already-known missing `ExactFunction1` view and
is still rejected. The census still has 234 error lines, illustrating why the
aggregate number of cascading diagnostics is not a useful progress metric.
Public/top-level overload collisions and the other physical-boundary failures
remain explicit; no complete Stdlib success is claimed.

The final candidate is green: four suites, 24 tests, no failures/errors/skips.
The complete backend suite is green: 22 suites, 402 tests, no failures/errors/
skips. The identical inverse is green: four suites, 24 tests, no failures/
errors/skips. Evidence is stored as `candidate-24.zip`, `inverse-24.zip`, and
`backend-402.zip` in the new evidence directory. The final compiled source
hashes are unchanged through the candidate and inverse gates.
The inherited production-erased full checkpoint remains `3a2384f636`
(2,821 tests); this is not a new full aggregate or a production cutover.

The candidate and inverse use `:compiler:fir:fir2ir:dotNetTest --rerun` with:

```text
--tests '*testGenericOwnerSemanticOverloads'
--tests '*testGenericOwnerRuntimeIteratorSeparateCompilation'
--tests '*testGenericOwnerRuntimeMapEntrySeparateCompilation'
--tests '*testGenericOwnerSemanticBodyExactResultChain'
--tests '*testGenericOwnerSemanticIteratorResult'
--tests '*testGenericOwnerCallableCompositionSeparateCompilation'
```

Every command uses `--max-workers=1 --no-configuration-cache -q`; the candidate
adds `"-Pkotlin.dotnet.genericOwnerRehearsal=true"`. The backend task is
`:compiler:backend.dotnet:test --rerun`. The six fixtures run through both
parsers and both executable runtime profiles. No generated runner shape changed;
the existing generator retained all four registrations of the expanded fixture.
