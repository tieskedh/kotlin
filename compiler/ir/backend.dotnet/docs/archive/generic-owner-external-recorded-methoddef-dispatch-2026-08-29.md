# External recorded MethodDef dispatch checkpoint (2026-08-29)

This archive record preserves the bounded rehearsal checkpoint which publishes
final natural-interface MethodDef authority to separately compiled Kotlin
consumers. It is evidence for one root/edge-free declaration grammar, not a
general generic-owner ABI and not a production cutover.

## Authority split

Physical library ABI 63 adds one declaration-level `N` record for each admitted
natural producer or split-nullable producer slot. The record is constructed from
final emission observations and atomically seals:

- the final natural TypeDef row, path, flags, generic arity, variance, and
  binder-owned constraints;
- the final natural MethodDef row, name, flags, dispatch, method-generic rows,
  complete physical signature, and binders; and
- the KLIB-owned logical parameter domains and orthogonal direct or
  split-nullable result layout.

`N` is self-sealing declaration authority. It requires no implementation class,
semantic hook, dispatcher, or MethodImpl. The existing implementation-level
`J` record remains optional. When a `J` record exists, its complete natural-slot
projection—including TypeDef, MethodDef, logical domains, and result layout—
must equal `N`; `J` never creates `N`, and an interface-only producer may
publish `N` without `J`.

The generic-owner family artifact schema is 21. Compiler-runtime surface level
remains 60.

## Complete library-index join

The codec accepts an `N` record only when the same producer index establishes:

- `C/H` agreement on logical owner, natural TypeDef path, generic arity, and
  final physical variance;
- `F/N` agreement on physical owner, MethodDef name, instance/static shape, and
  method-generic arity;
- `G/H/N` agreement on logical member and owning interface family;
- agreement between the `H` member role and direct versus split-nullable result
  layout; and
- exact `J/N` natural-slot equality when optional implementation evidence is
  present.

Orphan, cross-wired, cross-owner, malformed, result-layout-mismatched, and stale
records fail closed. Logical IR or a partial older physical index cannot fill a
missing declaration seal. Duplicate physical TypeDef or MethodDef claims are
detected before logical parameter-domain and nullability annotations are
compared: two logical members cannot acquire the same CLR declaration merely by
describing its Kotlin view differently.

The separately compiled consumer independently authenticates the sealed
physical declaration against its logical KLIB projection before selecting the
route. The recorded slot must remain an instance slot, its ordinary parameter
carriers must exactly equal the KLIB-derived declaration-independent carriers,
its direct result must name the same owner-parameter index, and its split-
nullable bit must equal the logical nullable-result shape. This join can reject
a stale or cross-wired `N`; it cannot create a MethodDef identity or reconstruct
a missing physical signature.

## Producer-PE validation

The dependency loader validates every `N` record against the objective metadata
of its containing producer DLL before exposing it to the backend. Validation
checks the selected TypeDef path, category, flags, ancestry, GenericParam rows,
variance and constraints, then the complete MethodDef signature, visibility,
dispatch flags, method GenericParam rows, ordinary parameters, and result
layout. A split-nullable result additionally requires the final `[out] bool&`
parameter row.

Same-name candidates are filtered by the complete physical signature. Zero or
multiple full matches are rejected; method name and regular arity never become
slot authority.

## External consumer route

A separately compiled consumer uses `N` to emit the exact open interface
`ldtoken method`, selects an already-guaranteed closed construction implemented
by the receiver, and binds both handles with the mandatory two-handle
`MethodBase.GetMethodFromHandle` overload. CLR interface dispatch continues to
select the ordinary or explicit implementation MethodDef/MethodImpl. The route
does not use `InvokeUniqueMember`, a public method scan, a generated marker, an
interface enumeration order, or a logical-signature reconstruction.

The bounded executable corpus includes:

- direct `CompleteNaturalContract<T>.fetch(): T` calls from a downstream
  assembly, including an inherited child view;
- split-nullable `NullableSource<T>.read(Boolean): T?` calls represented as
  `!T read(bool, [out] bool&)`; and
- hostile `OverloadedNullableSource<T>.read(Boolean): T?` and
  `read(Int): T?` slots with the same source name and regular arity.

Downstream IL asserts distinct recorded tokens containing `bool` and `int32`,
respectively, and asserts the absence of name-string and legacy unique-member
selection. Their distinct behavior closes the bounded hostile overload gate.

The final C# execution additionally compiles a generator-free, natural-only
`OverloadedNullableSource<string>` implementation. C# calls the public
functions in the final separately compiled Kotlin consumer through a covariant
`OverloadedNullableSource<object>` view and exercises hit and null branches of
both overloads. This forces external index loading, the KLIB/`N` join, recorded
token emission, helper binding, and ordinary CLR interface dispatch; the C#
class implements no Kotlin compiler capability ABI.

## Admitted boundary

The first portable `N` grammar is intentionally limited to directly declared
`PRODUCER` and `SPLIT_NULLABLE_PRODUCER` members on a public, abstract,
AUTO/ANSI-layout natural root interface. The natural slot is a public abstract
virtual newslot instance hidebysig MethodDef whose receiver is the exact self
construction. Every ordinary input is declaration-independent and the direct
payload is a strict owner-parameter result. The declaration additionally has:

- no direct base/interface edges;
- no owner- or method-generic constraints; and
- no carrier which names a physical TypeDef outside the declaration-local
  owner/method binder graph.

An unsupported form publishes no `N`. It is not reconstructed from KLIB,
inferred from `J`, widened to `object`, or silently treated as another physical
construction.

## Verification

- Hostile same-name/same-regular-arity recorded-token gate: **closed**.
- Complete PSI/LightTree x Framework 4.8/.NET 10 candidate matrix: 4 suites,
  8 tests, zero failures, errors, or skips.
- Complete production-erased inverse over the same matrix: 4 suites, 8 tests,
  zero failures, errors, or skips.
- Fresh unqualified production-erased aggregate: 204 suites, 2,568 tests, zero
  failures, errors, or skips.
- Promoted checkpoint identity: the feature commit containing this record; Git
  supplies its immutable hash.

Candidate command:

```text
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --tests "*testGenericOwnerSplitNullableResultSeparateCompilation" --tests "*testGenericOwnerCompleteNaturalInterfaceSeparateCompilation" --no-daemon -q
```

Production-erased inverse command:

```text
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --tests "*testGenericOwnerSplitNullableResultSeparateCompilation" --tests "*testGenericOwnerCompleteNaturalInterfaceSeparateCompilation" --no-daemon -q
```

Full aggregate command:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

Direct JUnit XML audit:

| Root | Suites | Tests | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: | ---: |
| backend | 14 | 183 | 0 | 0 | 0 |
| `dotnet.ir` | 1 | 6 | 0 | 0 | 0 |
| FIR2IR | 187 | 2,251 | 0 | 0 | 0 |
| integration | 2 | 128 | 0 | 0 | 0 |
| **Total** | **204** | **2,568** | **0** | **0** | **0** |

## Production and remaining boundary

Production Kotlin-owned generic classes and interfaces remain on the accepted
erased ABI and must publish no candidate `N` or `J` records. The eventual atomic
cutover and exact inverse remain unchanged.

This checkpoint does not close inherited or edge-bearing natural declarations,
generic constraints, wider callable roles, natural generic-class state,
retained/static/global declaration authority, trimming, NativeAOT, or the
complete Runtime/Stdlib selected-family gate. The next bounded rehearsal may
retire the old comparison surface only for the family covered by this external
MethodDef proof.
