# Generic-owner overload-family names (2026-08-17)

## Result

Physical-family schema 19 closes the overload/generated-name migration
condition without giving up natural C# overloads. Typed entries retain the
compiler-selected source name. Compiler-generated semantic hooks and capability
slots use stable logical-family identities, and masked-default helpers use the
logical declaration identity. The producer artifact remains the only consumer
binding authority.

This is production-inert architecture evidence. Kotlin-owned generic classes
still emit through the erased production ABI; schema 19 does not admit a public
CLR `C<T>` owner and does not add a source-level `DotNetName` annotation.

## The hostile collision

The hostile covariant store now declares two legal Kotlin overloads:

```kotlin
open fun collide(value: HostileTypedStore<@UnsafeVariance T>): String
open fun collide(value: HostileAbstractPropertyStorage<@UnsafeVariance T>): String
```

Their natural typed CLR entries are valid C# overloads because the parameter
types remain distinct constructed generic classifiers. Both semantic roles,
however, must accept the full Kotlin candidate domain and therefore have an
`object` parameter. The old fixed `collide__KotlinSemantic` spelling made the
two MethodDefs identical and the family correctly failed construction before
schema 19.

That failure rules out three tempting policies:

- erasing the public typed names would make ordinary C# worse;
- allocating a suffix only when a collision is observed would rename an
  already published slot when an overload is added later; and
- asking a separate consumer to reconstruct the suffix would make producer and
  override binding depend on duplicated lowering knowledge.

## Stable allocation contract

For each role the producer now computes:

```text
typed entry       = compiler-selected natural name
semantic hook     = typed name + semantic label + digest(sorted override roots)
capability slot   = typed name + capability label + digest(sorted override roots)
default helper    = typed name + default label + digest(logical declaration key)
```

The complete sorted unique override-root set is part of the precondition. An
override consequently receives the same family name as its root even in a
different assembly, while unrelated overload families remain distinct. The
digest is unconditional: collision discovery is validation, never allocation.

The family record validates the exact generated name for every semantic,
capability, and default role. A consumer which substitutes the old reconstructible
suffix is rejected. The explicit interface implementation name remains the
recorded capability owner plus the exact generated capability method name.

This contract is deliberately separate from user-facing export naming. A later
`DotNetName` or `CSharpName` proposal may control an explicit public export
surface, analogous in purpose to other target naming annotations. It must not
control compiler-owned semantic ABI slots or become necessary for ordinary
overload correctness.

## CLR identity is not C# identity

ECMA-335 can distinguish MethodDefs that C# cannot declare or call as overloads.
Schema 19 therefore retains full CLR MethodDef uniqueness and additionally
validates the C# source identity:

```text
(declaring owner, method name, generic arity, parameter physical types)
```

Return type, instance/static distinction, and nullable-reference metadata are
intentionally excluded. The validation also rejects a method, property, or
field with the same C# source name on one physical owner. Negative oracles cover
return-only method duplication and CLR-valid method/property and property/field
collisions.

## End-to-end proof

The record-driven producer exposes both natural typed `collide` overloads and
distinct protected semantic hooks. Its non-generic capability interface owns
two distinct slots and both exact explicit MethodImpl mappings. A separately
compiled C# subclass overrides the typed entry and protected semantic hook of
one overload. A compatible Kotlin call observes the typed override; the same
logical operation through an incompatible constructed owner observes the
semantic override.

Reflection pins both open constructed typed parameter types, both generated
hooks, and the seven-entry capability interface map. A raw metadata reader
checks the same recorded MethodDef and MethodImpl identities without depending
on reflection name lookup. Ordinary and separately compiled Kotlin oracles run
both overloads through exact and widened views.

The compiler-derived hostile route corpus now has 55 producer events:

| Route | Static sites / dynamic producer events |
|---|---:|
| production erased owner | 24 |
| exact typed entry | 18 |
| semantic capability | 12 |
| missing capability | 1 |

There are additionally 11 unrelated runtime events, for 66 total. PSI and
LightTree and Framework 4.8/.NET 10 produce byte-identical route and count
manifests. The four-lane verifier executes the same 55 producer events and 11
unrelated events on their appropriate hosts. Updating the overload oracle
exposed that both verification tools still encoded the older schema-16/17 site
set and counts; those tool contracts now use the complete schema-19 sparse site
set instead of silently accepting stale coverage.

## Verification

The focused ordinary hostile, separate hostile, and recursive OctoTree matrix
passes under PSI and LightTree on both .NET 10 and the real Framework 4.8 host:
four suites, 12 products, and zero failures, errors, or skips. Individual
separate-assembly, external C# subclass, raw metadata, reflection, and
interface-map paths execute in that matrix.

The closed application verifier regenerated PSI and LightTree candidate/erased
bundles for both target profiles and checked their producer fingerprints,
family artifacts, call-route manifests, and C# consumers. The final strict
`:compiler:backend.dotnet:dotNetTest` aggregate exited successfully. All 187
result suites were freshly written by that run; their 2,107 tests contain zero
failures, errors, or skips.

## Remaining boundary

Schema 19 removes overload/generated-name collision policy from the reopening
gate. Base/interface nullable-reference transforms and the one atomic public
owner migration remain open. Generated-member naming is no longer a reason to
introduce an erased public owner or require every Kotlin/C# user to author a
bridge.
