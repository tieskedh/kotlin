# Canonical reference state without enclosing-owner erasure

- Date: 2026-09-11
- Reviewed predecessor: `3a2384f636`, on `dotnet`
- Scope: bounded generic-owner rehearsal state proof and physical emission
- Authority: [physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md#bounded-stage-6-fielddef-authority)
- Production: unchanged erased ABI 71; artifact schema 22; Runtime surface 62

## Cause and correction

The source-built stdlib census exposed derived owners left erased by unresolved
reference-field write proofs while their bases were already generic. One real
case is a field whose logical type has arguments/projections but whose local
declaration is independently fixed on a non-generic canonical CLR TypeDef.
Rejecting that reference as an unknown construction needlessly rejects the
containing owner's generic representation.

The custom proof has this physical shape:

```text
LibraryData<K,V>  -> LibraryData        (existing constructor exclusion)
Observer<T>      -> Observer<T> : Parent<T>
                    LibraryData source
                    !T value
```

The planner now recognizes the actual nominal reference only when an intrinsic
declaration-wide exclusion fixes its canonical TypeDef. Merely unresolved
state is not enough: it may still resolve to a generic owner in the fixpoint.
The BOUND index records that local zero-arity class. Recursive carrier binding
uses its physical arity, not its logical arguments, while real generic
constructions continue to require complete invariant argument vectors.

Two later assumptions also had to follow the same authority. A parameter's
logical semantic-view request is not a capability TypeDef: if the selected
canonical class has no such capability, its parameter remains that nominal
class. Actual capability/object/foreign-dispatch entries remain barriers.
The symbolic-to-CIL projection now renders a zero-arity reference as the real
class, never as an empty generic instantiation. Final FieldDef and every typed
writer-parameter observation must agree, including referenced TypeDef arity.

There is no wrapper, new state object, duplicate field, collection-name rule,
or new inheritance mapping. Logical Kotlin types remain untouched. Existing
erased-parameter method-name disambiguation is also unchanged.

## Executable proof

`genericOwnerCanonicalStateReference.kt` builds three Kotlin assemblies: the
owner library, an independently compiled generic child, and its consumer.
The fixture-local validator additionally compiles and executes ordinary C#.

It proves:

- exactly two private instance fields on the owner, with actual PE signatures
  `LibraryData` and `!0`, and two completed BOUND-to-final state seals;
- canonical reference replacement across different logical helper arguments,
  plus independent `Int` and `String` typed writes/reads;
- truthful `Observer<T> : Parent<T>` and separate child inheritance;
- ordinary C# construction, typed writes, a natural virtual override observed
  from Kotlin, and a plain C# implementation of the input interface;
- unchanged receiver and source-reference identity, without child shadow state;
- production-erased execution of the same Kotlin modules, erased TypeDefs,
  empty state seals, and absence of every rehearsal epoch record.

The model tests prove canonical projections can preserve the same nominal
carrier inside another exact construction. They reject stars/projections for
the corresponding physically generic declaration, missing declaration
authority, a capability-interface substitute, and incoherent canonical role
arity/view. Final state sealing also rejects a changed referenced TypeDef
arity even when its alias and supplied argument vector still match.

## Verification

Direct JUnit audits found zero failures, errors, or skips:

| Lane | Suites | Tests |
| --- | ---: | ---: |
| Candidate PSI/LightTree, Framework 4.8/.NET 10 | 4 | 16 |
| Same production-erased inverse | 4 | 16 |
| Complete backend suite | 22 | 402 |

The four fixtures in each executable matrix are:

```text
GenericOwnerCanonicalStateReference
GenericOwnerStateAuthoritySeparateCompilation
GenericOwnerCompleteNaturalInterfaceSeparateCompilation
GenericOwnerPhysicalValueShadow
```

Run `:compiler:fir:fir2ir:dotNetTest --rerun` with one
`--tests '*DotNet*BoxTestGenerated$Box.test<Fixture>'` per fixture,
`--max-workers=1 --no-configuration-cache -q`, and the quoted
`"-Pkotlin.dotnet.genericOwnerRehearsal=true"` property for the candidate.
Repeat without that property for the inverse. The complete backend suite is
`:compiler:backend.dotnet:test --rerun`. Generated runners contain the new
fixture in all four parser/runtime classes. The nine changed Kotlin source
and test files retained identical SHA-256 hashes throughout the final gates.

This uses the rehearsal-physical lane. The planner remains read-only in the
production epoch; physical authority selection/materialization is property-
guarded. The backend passes no local physical authority to production emission,
and the state projection consumes only that authority. The validator is
fixture-local. There are no Common, Runtime/Stdlib, production mapper, retained
foreign metadata, schema, or toolchain changes.

The full production-erased base remains the predecessor's 2,821-test gate,
recorded in the [SAM archive](generic-owner-invariant-nullable-sam-2026-09-11.md).
This is not a new target-wide aggregate. Compact raw JUnit archives are retained
under `D:\CodexTemp\canonical-state-reference-20260911\evidence\`.
No worktree or build-tree copy was created.

## Rejected probe and remaining boundary

A separate diagnostic attempt materialized a fixed `Base<object>` ancestry for
an erased owner logically extending `Base<T?>`. Local calls could pass, but
passing the derived value through an ordinary `Base<Int?>` public parameter
failed: that parameter required `Base<Nullable<int>>`, not `Base<object>`.
The attempted mapper bypass was removed completely. Its source/diff remains
locally in `D:\CodexTemp\nullable-base-edge-20260911\rejected-experiment.txt`.
The feature instead preserves truthful generic owners where state permits it.

This proof does not close conditional generic ancestry, arbitrary capture or
writer graphs, nullable/value-class state, external-state authority, or the
entire Runtime/Stdlib census. The detached diagnostic snapshot grammar is not
extended or treated as portable state authority. Resume the census from the
promoted checkpoint; passing this custom shape is not proof that all earlier
237 cascading diagnostic lines represent resolved independent bugs.
