# Open-nullable invariant SAM proof — 2026-09-11

This is dated evidence for the feature committed with this document, based on
`cca68334a23b00edda50cb50ba3124aac78dc5c2`. Current state belongs in
[`STATUS.md`](../../STATUS.md); representation authority belongs in the
[SAM decision](../decisions/fun-interfaces.md#open-nullable-invariant-and-contravariant-conversion)
and [interface draft](../decisions/draft-adr-reified-generic-interface-owner.md).
This checkpoint uses physical library ABI 71, generic-owner schema 22, and
Runtime surface 62. Production Kotlin-owned generic owners remain erased.

## Failure and correction

The pristine source-built stdlib census stopped in Common `nullsFirst` at
`Comparator<T?>`. Common and the .NET actual declare `Comparator<T>`
invariant; use-site `in` projections do not make that declaration contravariant.
The preceding SAM proof only admitted matching logical/physical contravariance.

An independent `PairInput<T>` fun interface with two inputs reproduced the
same rejection before the fix. The bounded open-nullable SAM plan now accepts
matching logical/physical invariance or contravariance. It retains the one
Common conversion object, one raw `Function2` field, and an invariant underlying
non-null type witness; it never fabricates a natural nullable InterfaceImpl.
One generalized Runtime marker replaces the unpublished contravariant-only
marker. Invariant matching requires the exact reference witness or the closed
nullable form of a value witness; it must not borrow contravariant matching.

The hostile fixture also exposed a missing result-analysis fact. Invariance
does not make open `T?` a verifier-nameable CLR argument. The existing result
closure now records that semantic carrier before a closed MethodSpec is bound,
so a closed forwarding function cannot advertise a natural construction absent
from its factory's physical result. The fixture retains this forwarding case.

## Proven boundary

- Local and separate-producer interface/wrapper factories, with an unrelated
  leading method parameter before the witness parameter.
- Null/value/reference forwarding, exact compatible casts, and same-object
  identity through return, local, star, projected, and mixed conditional views.
- Exact invariant `PairInput<Int>` and `PairInput<Derived?>` rejection, while
  legal `in Int` and `in Derived?` projections continue to operate.
- Agreement of `as`, `as?`, and reified parameterized `is`, with the opposite
  production-erased expectations checked explicitly from separate C# callers.
- Natural and semantic wrapper cache entries encountered in reverse order,
  symmetric equality/hash over the same stored function, and natural closed
  reference/nullable-value constructions.
- PE owner variance, invariant unconstrained wrapper binder, sole raw function
  field/constructor, absence of a fabricated natural InterfaceImpl, and actual
  same-object capability InterfaceMap/MethodImpl execution from C#.
- No published rehearsal record or witness/generic wrapper in the erased inverse.
  No Common compiler or stdlib source change; ordinary C# remains free of hidden
  compiler-ABI authoring obligations.

## Verification

All successful gate runs below exited zero. Direct JUnit XML audits found no
failures, errors, or skips.

| Lane | Suites | Tests |
| --- | ---: | ---: |
| Focused candidate | 4 | 16 |
| Same focused production-erased inverse | 4 | 16 |
| Complete backend suite | 22 | 400 |
| Fresh full production-erased target gate | 212 | 2,821 |

The focused candidate and inverse select these fixtures across PSI and
LightTree on Framework 4.8 and .NET 10:

```text
genericOwnerInvariantOpenNullableSamSeparateCompilation
genericOwnerGenericSamWrapperSeparateCompilation
genericOwnerRuntimeMapEntrySeparateCompilation
genericOwnerCompleteNaturalInterfaceSeparateCompilation
```

The first is the new hostile fixture. The remaining fixtures guard the existing
contravariant witness policy, natural interface authoring, and adjacent
producer/consumer routing. The backend suite includes the directly changed
physical-ABI codec tests. The focused counts overlap the full corpus and are
not distinct additional target coverage.

For the focused matrix, run `:compiler:fir:fir2ir:dotNetTest --rerun` with
`--tests '*DotNet*BoxTestGenerated$Box.test<Fixture>'` for each fixture above
(capitalizing its initial letter), `--max-workers=1 --no-configuration-cache -q`,
and the quoted `"-Pkotlin.dotnet.genericOwnerRehearsal=true"` project property.
Run the same filters without that property for the inverse.

The final production-erased gate explicitly reruns the actual FIR2IR Test task
after the focused runs:

```text
.\gradlew.bat --max-workers=1 --no-configuration-cache -q :compiler:fir:fir2ir:dotNetTest --rerun :compiler:backend.dotnet:dotNetTest
```

The full gate ran from 07:44 to 08:33 local time. The changed Kotlin files and
new fixture retained identical SHA-256 hashes throughout that run.

| XML root | Suites | Tests |
| --- | ---: | ---: |
| `compiler/ir/backend.dotnet/build/test-results/test` | 22 | 400 |
| `dotnet/dotnet.ir/build/test-results/test` | 1 | 6 |
| `compiler/fir/fir2ir/build/test-results/dotNetTest` | 187 | 2,287 |
| `compiler/tests-integration/build/test-results/dn` | 2 | 128 |

Compact raw JUnit evidence is retained locally under
`D:\CodexTemp\invariant-nullable-sam-20260911\`. The candidate and inverse
archives contain their four focused suites; the separate census archive
contains the intentionally unsuccessful source-built candidate census.
The full archive contains all four target roots. No additional worktree or
copy of the build tree was created.

## Next census boundary

The source-built stdlib census now reaches emission instead of failing at SAM
admission. Its saved diagnostic contains 237 error lines, many cascading from
earlier failures; this is not a count of independent bugs or a successful
stdlib build. No diagnostic in that run names `kotlin.comparisons`.

The first visible group rejects a derived physical receiver with no graph view
of the non-generic `AbstractCollection` MethodDef owner. Other groups include
return-slot adapters, erased-signature collisions, and generic input/result
construction gaps. Resume by isolating the next structural cause in a custom
inheritance fixture; do not weaken physical-owner validation or add collection
exceptions.

This proof does not close nullable generic state, additional SAM variance or
member shapes, value-class substitutions, full Runtime/Stdlib closure, or
AOT/trimming. It is neither production cutover nor a performance claim.
