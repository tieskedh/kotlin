# Generic-owner direct-supertype metadata (2026-08-17)

## Result

Physical-family schema 20 closes the base/interface nullable-reference
migration condition. The producer artifact now records every future generic
owner's exact `TypeDef.BaseType` and direct `InterfaceImpl` rows, including the
complete constructed type expression and its physical nullable transform.
Constructor delegation, semantic capability ownership, reflection ancestry,
and separately compiled consumers all use those records instead of rebuilding
an ancestry edge from names or constructor shapes.

This remains production-inert architecture evidence. Kotlin-owned generic
classes still use the erased production ABI; schema 20 does not publish a CLR
`C<T>` owner or authorize a per-class rollout.

## The two nullable shapes are not equivalent

The hostile corpus deliberately contains both of these cases:

```kotlin
class Conditional<T> : Base<T?>()

class Exact<T> :
    ReferenceBase<TypedStore<T>?>(),
    Marker<AbstractPropertyStorage<T>?>
```

For an unconstrained CLR type parameter, Kotlin `T?` is conditional: a value
substitution needs `Nullable<T>`, while a reference substitution remains `T`.
One `Conditional<T>` TypeDef therefore cannot encode one truthful fixed base
for every substitution. The producer classification now retains the exact
blocked base classifier and owner-parameter index and deterministically keeps
that owner out of the physical family.

The nullable arguments in `Exact<T>` apply to known reference classifiers.
They have one fixed CLR representation for every `T`, so flattening or
excluding them would throw away useful and truthful .NET metadata. Schema 20
admits this owner and binds the exact nested class/interface constructions.

## Logical nullability versus physical ancestry metadata

Live Kotlin IR reports the logical transform for both exact edges as:

```text
non-null constructed root / nullable nested classifier / non-null T
1 / 2 / 1
```

Roslyn's physical ancestry metadata uses its sentinel at the constructed
`BaseType` or `InterfaceImpl` root:

```text
oblivious ancestry root / nullable nested classifier / non-null T
0 / 2 / 1
```

These are deliberately different layers. The prototype retains Kotlin's
logical `1,2,1`; the bound physical-family record retains the actual CLR/Roslyn
`0,2,1`. A raw `System.Reflection.Metadata` reader, rather than reflection's
nullable projections, established this encoding on both target profiles. The
record validator requires root sentinel `0`, exact structural length, and
rejects unknown flags.

## One authoritative TypeDef graph

Schema 20 adds producer-owned generic interface TypeDefs to the detached
artifact and resolves every producer type appearing in ancestry against either
one recorded class, one recorded generic interface, or one hidden non-generic
semantic capability interface. Missing, colliding, self-referential, duplicate,
consumer-owned, or method-parameter-dependent edges fail closed.

Every owner has exactly one base row. A base-delegating constructor must target
that same physical type expression. An owner which declares capability
dispatchers has exactly one direct capability `InterfaceImpl`; a derived owner
which merely inherits those dispatchers must not repeat it. Method-free owners
do not acquire an empty synthetic capability interface.

This distinction matters for C# honesty. The generated candidate now exposes:

```csharp
HostileNullableReferenceDerived<T> :
    HostileNullableReferenceBase<HostileTypedStore<T>?>,
    HostileNullableMarker<HostileAbstractPropertyStorage<T>?>
```

as real constructed CLR ancestry, while the bare-`T?` owner remains an
explicitly explained erased-only classification rather than receiving a
plausible but false base.

## Evidence

The hostile ordinary and separate Kotlin programs prove that the constructed
base and interface views preserve one object identity. The record-driven C#
producer compiles with nullable warnings as errors. A separate raw metadata
program checks the exact base signature, the single ordinary interface row,
the generic marker TypeDef, and both `0,2,1` nullable transforms in the emitted
DLL. It runs against a .NET 10 producer and a Framework 4.8 producer.

The general recursive OctoTree metadata inspector also stopped deriving the
base from constructor delegation or assuming an implicit single interface. It
now consumes the recorded direct-supertype list and checks each base/interface
signature and nullable blob. Its separate candidate products pass on both CLR
profiles.

Schema-negative coverage rejects a missing base, constructor/base mismatch,
non-sentinel ancestry root, omitted or class-colliding generic interface,
unknown serialized supertype nullability, removed direct capability, duplicate
inherited capability, missing conditional-supertype authority, and an
out-of-range conditional owner parameter.

The focused hostile matrix passes under PSI and LightTree in ordinary and
separate compilation on both .NET 10 and the real Framework 4.8 host: four
suites, eight products, and zero failures, errors, or skips. The route corpus is
unchanged because the new owners add ancestry and metadata evidence rather
than callable sites. The closed-application verifier regenerates PSI and
LightTree bundles for both profiles, proves their executable/KLIB/artifact
equivalence, and executes every candidate, erased Kotlin, and erased C#
product. The four-lane dynamic route verifier observes exactly 18 exact, 12
semantic-capability, 24 erased-owner, and one intentional missing route: 55
producer events plus 11 unrelated events.

The final strict target aggregate exited successfully.
After explicitly refreshing the otherwise up-to-date `dotnet.ir` result, the
direct audit covers 190 freshly written XML suites and 2,238 tests with zero
failures, errors, skips, or stale result files.

## Remaining boundary

Base/interface metadata is no longer an unresolved reason to preserve the
erased public owner. What remains is the atomic public-owner migration decision
itself: revalidate the complete hostile family and representative application
checkpoint as one ABI, cast, reflection, state, inheritance, interop, and
performance product. Until that checkpoint explicitly selects the cutover,
production Kotlin-owned generic owners remain erased and no easy owner may be
published independently.
