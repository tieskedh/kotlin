# Generic-owner route attribution — 2026-08-14

## Outcome

The paired hostile application now has a fail-closed route-attribution mode.
It separates typed owner entry, semantic capability dispatch, object-carried
state, finite exact/fallback construction, typed and semantic arrays,
owner-independent method generics, compatible overrides, and hostile delayed
typed failure. The same generated sources compile and execute on the installed
.NET Framework 4.8 family runtime and independently under .NET 10 JIT,
ReadyToRun, full trimming, and NativeAOT.

This evidence confirms the central current risk more precisely. The candidate
has genuine CLR-generic owner TypeDefs and typed method signatures, but the
hostile covariant owner's compiler-derived state requirement remains
`SEMANTIC_OBJECT_REQUIRED`. Direct typed entry therefore still boxes value
state on write and unboxes it on read. A generic TypeDef alone is not typed
storage and does not remove the semantic carrier cost.

The result does **not** reject CLR generics. Owner-independent method-generic
arrays stay close to parity, and NativeAOT makes the typed-array and compatible
override routes equal to or slightly faster than erased. The material costs
are concentrated in the complete semantic capability path, its runtime
compatibility check, exact/fallback construction, and the extra re-box needed
when a compatible value enters object-carried state.

Production generic owners therefore remain erased. The route attribution
closes the bounded causal investigation requested by the preceding paired
measurement; it does not close the representative-application gate or
authorize a partial owner migration.

## Physical boundary measured

The candidate protocol reports the compiler-derived
`SEMANTIC_OBJECT_REQUIRED` owner state carrier. The erased protocol reports
`ERASED_OBJECT`. Route counters count explicit workload entries, not internal
override frames. Value-conversion counters exclude setup and count each loop
box or unbox, including a failed hostile unbox. Compatibility checks and
expected failures are independent counters.

| Route | Candidate operations per iteration | Purpose |
|---|---|---|
| `typed-entry-object-state` | 2 typed entries, 2 value conversions | typed `C<int>` ABI over object-carried state |
| `capability-value-state` | 2 capability entries, 4 value conversions, 1 compatibility check | compatible value through the semantic capability and typed fast path |
| `capability-reference-state` | 2 capability entries, 1 compatibility check | capability/check cost without boxing |
| `fallback-struct-state` | 2 capability entries, 2 value conversions, 1 check | equal-layout `Int32 + Guid` struct through finite `C<object>` fallback |
| `exact-value-construction` | 1 owner construction, 2 capability entries, 4 value conversions, 1 check | finite exact nullable-value construction on every iteration |
| `typed-array` | 1 typed entry | owner-dependent `int[]` typed path |
| `semantic-array` | 1 capability entry, 1 compatibility check | incompatible `string[]` through the `System.Array` semantic path |
| `method-generic-array` | 1 typed entry | owner-independent `R[]` method generic baseline |
| `compatible-override-object-state` | 2 typed entries, 2 value conversions | multi-level compatible typed override over one object state |
| `hostile-override-state` | 1 typed and 2 capability entries, 1 conversion, 1 check, 1 expected failure | broad string write/read followed by delayed failed typed read |

Candidate and erased routes use the same values and checksums. The first
diagnostic run exposed that their fallback structs originally differed in
layout. Its result was discarded. The final corpus gives both representations
one `Int32 + Guid` struct, verifies both fields after every roundtrip, and
allocates the same 40 bytes per iteration on Framework and JIT. This prevents a
smaller payload from masquerading as a generic-owner or boxing improvement.

## Independent runtime evidence

Framework and .NET 10 remain independent evidence lanes. Absolute timings are
not compared between them.

- Framework products are compiled by SDK 10.0.100 Roslyn against the installed
  CLR 4 assemblies and executed by 64-bit Windows PowerShell on CLR
  `4.0.30319.42000`.
- The measurement now also fails unless the registered Framework release is at
  least 528040. This host reports release `533509`, product version
  `4.8.09221`, and Framework assembly versions 4.8.9337.0, 4.8.9340.0, and
  4.8.9344.0.
- .NET 10 lanes use runtime 10.0.10 and SDK 10.0.100. NativeAOT uses the
  explicitly validated Microsoft 14.44 linker and Windows SDK 10.0.26100
  libraries.

This separation is observable, not ceremonial. The hostile route allocates an
extra 336 bytes per expected failure in the candidate on Framework, no extra
bytes under .NET 10 JIT/ReadyToRun/NativeAOT, and about 23 extra bytes per
failure after full trimming. NativeAOT also gives both hostile representations
far smaller absolute exception allocation than either JIT runtime. A .NET 10
`object`, boxing, exception, or interface-dispatch optimization cannot be used
as Framework 4.8 evidence.

## High-resolution regular-route results

The nine non-failing routes used 2,000,000 iterations, seven startup runs,
seven throughput runs, and three clean compile/publish runs per representation
and mode. Values below are candidate workload time divided by erased workload
time. They are bounded micro-workload medians, not application speedups.

| Route | Framework 4.8 | .NET 10 JIT | ReadyToRun | Full trim | NativeAOT |
|---|---:|---:|---:|---:|---:|
| typed entry / object state | 2.315× | 1.740× | 1.623× | 2.295× | 1.701× |
| capability / value state | 6.339× | 3.984× | 2.673× | 5.287× | 3.181× |
| capability / reference state | 9.311× | 12.807× | 2.846× | 14.634× | 6.456× |
| fallback equal-layout struct | 4.742× | 3.182× | 2.666× | 6.524× | 4.026× |
| exact value construction | 14.709× | 5.316× | 3.422× | 9.485× | 4.329× |
| typed array | 1.601× | 3.474× | 1.223× | 3.891× | 0.994× |
| semantic array | 7.130× | 4.680× | 2.260× | 4.814× | 2.608× |
| method-generic array | 1.180× | 1.121× | 0.998× | 0.909× | 1.138× |
| compatible override / object state | 1.663× | 1.803× | 1.183× | 1.458× | 0.862× |

Allocation is deterministic enough to identify the value-carrier cost:

- typed entry and compatible override allocate the same 48,000,048 bytes in
  candidate and erased because both box one `int` per iteration into object
  state;
- compatible capability value entry allocates 96,000,048 candidate bytes
  versus 48,000,048 erased bytes. The extra 24-byte box per iteration is the
  typed-fast-path value being re-boxed into semantic object state;
- exact construction has the same 48,000,000-byte candidate excess while also
  constructing the selected owner every iteration;
- the equal-layout fallback struct allocates 80,000,064 bytes in both
  representations on Framework/JIT, proving its remaining time difference is
  not a payload-size or allocation-volume win;
- reference, array, and method-generic routes have no per-iteration allocation.
  NativeAOT has only fixed 16–24-byte setup differences on some zero-allocation
  routes.

The allocation-free reference capability route remains 2.846–14.634 times the
erased baseline. That isolates semantic interface dispatch, compatibility
testing, and typed/semantic routing from boxing. The allocation-free semantic
array route shows the same direction. Conversely, the method-generic route is
0.909–1.180 times erased, which is strong evidence that CLR generic machinery
itself is not the general cause.

Seven-run medians reduce but do not eliminate process/JIT noise; some very
short or tiered routes retain outliers. The result therefore attributes broad
cost categories and deterministic allocation, not a particular JIT pass or a
stable percentage for a future compiler.

## Hostile failure route

The exception-heavy route used 200,000 iterations with the same seven/seven/
three run counts. Times are candidate/erased medians in milliseconds.

| Mode | Time C / E | Ratio | Allocation C / E | Candidate excess |
|---|---:|---:|---:|---:|
| Framework 4.8 | 3,121.928 / 2,628.139 | 1.188× | 187,200,048 / 120,000,048 | 67,200,000 |
| .NET 10 JIT | 2,582.451 / 2,394.970 | 1.078× | 206,400,048 / 206,400,048 | 0 |
| ReadyToRun | 2,669.223 / 2,513.382 | 1.062× | 206,400,048 / 206,400,048 | 0 |
| Full trim | 2,471.076 / 2,326.161 | 1.062× | 211,063,896 / 206,400,048 | 4,663,848 |
| NativeAOT | 143.360 / 98.209 | 1.460× | 70,400,048 / 70,400,048 | 0 |

Every iteration performs and observes the same delayed `InvalidCastException`.
The route establishes runtime/deployment sensitivity; it does not authorize
changing Kotlin failure behavior or generalizing one runtime's exception cost.

## Architectural consequence

The earlier aggregate slowdown is now attributable rather than mysterious:

1. a true generic owner which retains semantic object state still pays the
   same value boxing as erased on direct typed state access;
2. compatible value capability entry adds a runtime check, an unbox, and a
   second box into that state;
3. reference and array capability routes are expensive even without
   allocation, so removing boxes alone cannot close the gap;
4. exact/fallback construction magnifies the capability cost; and
5. ordinary method generics and some closed-world NativeAOT typed/override
   routes remain competitive, so no blanket anti-generic conclusion follows.

The next production decision must therefore compare representative call mixes,
not simply count generic TypeDefs. A future canonical CLR `C<T>` model becomes
materially stronger only when more ordinary calls remain on typed paths and a
typed-storage/deoptimization or equivalent one-state design preserves every
valid Kotlin widened operation. Broad Kotlin calls may not be removed or made
stricter to improve these numbers.

## Reproduction and provenance

The high-resolution command was:

```powershell
$routes = @(
  'typed-entry-object-state', 'capability-value-state',
  'capability-reference-state', 'fallback-struct-state',
  'exact-value-construction', 'typed-array', 'semantic-array',
  'method-generic-array', 'compatible-override-object-state'
)
& compiler/ir/backend.dotnet/tools/measure-generic-owner-applications.ps1 `
  -Modes framework,jit,ready-to-run,trimmed,native-aot `
  -AttributionRoutes $routes `
  -Iterations 2000000 -StartupRuns 7 -ThroughputRuns 7 -CompileRuns 3 `
  -ExistingCorpus compiler/ir/backend.dotnet/build/generic-owner-applications/20260814-route-attribution-corpus-v4 `
  -OutputDirectory compiler/ir/backend.dotnet/build/generic-owner-application-measurement/20260814-route-attribution-fast-final-v4 `
  -NativeLinker <validated-link.exe> `
  -NativeLibraryDirectories <msvc-lib>,<windows-sdk-um>,<windows-sdk-ucrt>
```

The hostile result came from the same command and route corpus with all ten
routes and `-Iterations 200000`; only its hostile row is used above.

- repository head at measurement: `ee51ae01481a2b284ae39657e3106869e2493b2e`
- measurement tool SHA-256:
  `ee1da22b8c6620cdd5aca682ecd2332d05d0d99c93689dbc0ba9da5e93ec54be`
- net10 manifest SHA-256:
  `89659855a6438caf1c53f01e698cee289ccb52d76b3c62ad6247c892f3bc656b`
- net48 manifest SHA-256:
  `06eec08a73465b0978f9c3a395ed0cd2c9fc4855023fb731de22b7e0eea5b839`
- high-resolution result SHA-256:
  `8e39be515efe198d7236ce0f73c3ac36a01b24cb80fa1dddd8797778bc372466`
- hostile-containing result SHA-256:
  `99adf41f16d8a95b7888a23aef02b90af4cc33caa42db5ba16b8efd4bd868301`
- NativeAOT linker version/hash: 14.44.35228.0 /
  `ca11e6c45debd34bf652dfe984c5360a531a005ed78bf72852330c9c2590cf0d`

Both results record the reviewed dirty worktree rather than presenting it as a
released binary. Published byte counts remain non-comparable because the
candidate is a generated physicalization, not a complete Kotlin product.

## Verification and remaining gate

- Kotlin test-fixture compilation and PowerShell parsing passed;
- the final PSI/LightTree × net10/net48 corpus is closed, fingerprinted, and
  executable in every candidate/erased/direct-C# lane;
- all route-protocol fields, counters, carrier requirements, iterations, and
  candidate/erased/cross-mode checksums fail closed;
- the pre-existing aggregate measurement mode passed a Framework/JIT
  regression smoke after the driver extension;
- Framework registration, CLR host, and assembly provenance are recorded;
- JIT, ReadyToRun, full trim, and NativeAOT publish/execute successfully, with
  trimming/AOT warning checks intact; and
- the strict aggregate completed in 793.3 seconds; its three result roots
  contain 190 XML files and 2,204 tests with zero failures, errors, or skips.

This closes bounded semantic-route attribution. Representative applications
on Framework 4.8 and every .NET 10 deployment mode are the next reopening gate.
