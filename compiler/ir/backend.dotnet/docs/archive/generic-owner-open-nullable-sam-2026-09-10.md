# Open-nullable contravariant SAM proof — 2026-09-10

This is the dated evidence for the feature committed with this document, based
on `b260a31cb1d9f6c44f0431770d18b71807ed3e62`. Current state belongs in
[`STATUS.md`](../../STATUS.md); representation authority belongs in the
[SAM decision](../decisions/fun-interfaces.md#open-nullable-contravariant-conversion)
and [interface draft](../decisions/draft-adr-reified-generic-interface-owner.md).
This checkpoint uses physical library ABI 70, generic-owner schema 22, and
Runtime surface 61. Production Kotlin-owned generic owners remain erased.

## Proven boundary

The existing Common SAM conversion object now has two rehearsal plans:
natural `Wrapper<W> : Sink<W>` for exact operands, and a semantic-only
contravariant plan for open `Sink<T?>` with logical `T : Any`. The latter's
invariant binder witnesses the underlying `T`; its marker does not assert
a natural nullable InterfaceImpl. Both keep one raw function field.

The bounded proof covers:

- local and separately compiled interface producers and wrapper factories;
- exact caller MethodDef binder positions, including a preceding unrelated
  method parameter, and closed reference/value/nullable-value operands;
- null and non-null calls, ordinary contravariance, star views, and identity;
- one shared BK-1 predicate for compatible and incompatible `as`, `as?`,
  and reified parameterized `is`, with the opposite erased-epoch expectations
  checked explicitly where the old unchecked cast remains classifier-only;
- symmetric Common wrapper equality/hash across natural and semantic plans;
- semantic result carriers through Elvis and mixed conditional result arms;
- PE GenericParam, field, constructor, InterfaceImpl, and MethodImpl evidence;
  no false natural interface edge or candidate identity in the erased inverse;
- separately compiled C# consumers on Framework 4.8 and .NET 10, without a
  foreign obligation to implement the marker or other hidden compiler ABI.

The failure-driven corrections are structural: external natural TypeDefs bind
through the existing producer-authority query; constructor allocation uses its
actual owner rather than a different logical result view; only value-producing
result paths participate in semantic joins. No stdlib declaration exception
was added.

## Verification

All listed runs exited successfully. Direct JUnit XML audits found no failures,
errors, or skipped tests.

| Lane | Suites | Tests |
| --- | ---: | ---: |
| Focused candidate | 4 | 12 |
| Same focused production-erased inverse | 4 | 12 |
| JVM Common SAM regression boundary | 4 | 20 |
| Fresh full production-erased target gate | 212 | 2,817 |

The focused matrix uses these three existing fixtures, each through PSI and
LightTree on Framework 4.8 and .NET 10:

```text
genericOwnerGenericSamWrapperSeparateCompilation
genericOwnerRuntimeMapEntrySeparateCompilation
genericOwnerCompleteNaturalInterfaceSeparateCompilation
```

The first fixture contains the expanded Kotlin and C# hostile cases. The other
two guard adjacent producer/consumer interface routing. The following compact
PowerShell filters reproduce the candidate and inverse matrix:

```powershell
$fixtureFilters = @(
    'GenericOwnerGenericSamWrapperSeparateCompilation',
    'GenericOwnerRuntimeMapEntrySeparateCompilation',
    'GenericOwnerCompleteNaturalInterfaceSeparateCompilation'
)
$focusedArgs = @(':compiler:fir:fir2ir:dotNetTest', '--rerun',
                 '--no-configuration-cache', '-q')
foreach ($fixture in $fixtureFilters) {
    $focusedArgs += @('--tests',
        ('org.jetbrains.kotlin.test.runners.codegen.Fir*DotNet*BoxTestGenerated$Box.test' + $fixture))
}
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" @focusedArgs
.\gradlew.bat @focusedArgs
```

The JVM boundary ran `:compiler:fir:fir2ir:test --rerun` with the LightTree and
PSI `Fir*BlackBoxCodegenTestGenerated$Box$Sam$Constructors` and
`Fir*BlackBoxCodegenTestGenerated$Box$CallableReference$FunInterfaceConstructor`
classes. Each of the four selected classes ran five tests; the class-based SAM
fixtures also guard unchanged default wrapper caching.

The final unfiltered gate explicitly reran the real FIR2IR Test task after the
filtered runs; rerunning only the empty aggregate would not have sufficed:

```text
.\gradlew.bat --max-workers=1 --no-configuration-cache -q :compiler:fir:fir2ir:dotNetTest --rerun :compiler:backend.dotnet:dotNetTest
```

| XML root | Suites | Tests |
| --- | ---: | ---: |
| `compiler/ir/backend.dotnet/build/test-results/test` | 22 | 400 |
| `dotnet/dotnet.ir/build/test-results/test` | 1 | 6 |
| `compiler/fir/fir2ir/build/test-results/dotNetTest` | 187 | 2,283 |
| `compiler/tests-integration/build/test-results/dn` | 2 | 128 |

No rehearsal property was set for the full gate. The focused counts overlap
the full corpus and must not be added as distinct target coverage.

## Remaining boundary

This is not a general nullable generic-owner ABI, a production cutover, or a
performance claim. Wider bounds, variance/member forms, projected operands,
mixed generic state, value-class substitutions, and AOT/trimming still need
their own complete proofs. Resume the source-built Runtime/Stdlib census from
this checkpoint; do not infer those cases from this bounded SAM result.
