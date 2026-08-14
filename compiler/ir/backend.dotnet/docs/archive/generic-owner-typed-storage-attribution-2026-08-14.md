# Generic-owner typed-storage attribution — 2026-08-14

## Outcome

The schema-7 hostile generic-owner family now physicalizes the compiler's
existing `TYPED_STORAGE_PRODUCER_GRAPH_PROVEN` decision. The test-owned
`HostileTypedStore<T>` has one private field whose metadata type is the owner
GenericParam `!T`. Its exact typed read and write entries access that field by
identity and do not cross the non-generic semantic capability.

The capability remains part of the same object. Its object input is checked and
narrowed to `!T` before the field write, and its output widens or boxes the value
read from `!T`. An incompatible input throws `InvalidCastException` without
mutating the field. There is no object fallback, typed cache, wrapper, copy, or
second authoritative state.

This closes a bounded feasibility question left by the preceding route
attribution: a compiler-proven typed producer graph can obtain genuine CLR
generic field storage while retaining a strict widened/star entry on the same
owner. It does not prove that every hostile owner can use typed state. The
separate `HostileUnsafeStore<T>` still has
`SEMANTIC_OBJECT_REQUIRED` state and keeps its complete semantic path.

Production generic-owner emission remains erased. The candidate is a
test-owned physicalization, not a complete Kotlin product, and the
representative-application and atomic migration gates remain open.

## Physical and semantic boundary

The artifact, generated producer, reflection oracle, and direct C# consumer
jointly enforce this shape:

```text
HostileTypedStore<T>
  private !T stored

  exact write(!T) -> identity field write
  exact read() !T -> identity field read

  IHostileTypedStoreSemantic.write(object)
      -> checked cast/unbox to !T
      -> exact write

  IHostileTypedStoreSemantic.read() object
      -> exact read
      -> widen/box from !T
```

The capability dispatchers are private, virtual, and final explicit interface
implementations. They cannot become override slots. Metadata reflection proves
that the field, exact write parameter, and exact read result all use the same
owner GenericParam. The codec rejects object storage or a missing/semantic
exact access path for this compiler-proven state.

The direct consumer also writes a string through the capability of
`HostileTypedStore<int>`. Both CLR families reject it at the capability entry,
and a subsequent exact read proves that the prior integer state was not
changed. This is strict input validation for a producer graph which accepts
only physically compatible values; it is not permission to narrow ordinary
Kotlin covariance, star projection, or a broad override family.

## Paired routes

Six new routes compare the same logical owner operations against the actual
production-erased `generic.owner.oracle.HostileTypedStore`:

| Value shape | Exact candidate route | Capability candidate route |
|---|---|---|
| `Int32` | 2 typed entries, no conversions/checks | 2 capability entries, 2 conversions, 1 check |
| `Int32 + Guid` struct | 2 typed entries, no conversions/checks | 2 capability entries, 2 conversions, 1 check |
| `Nullable<Int32>` | 2 typed entries, no conversions/checks | 2 capability entries, 2 conversions, 1 check |

Every erased counterpart performs two erased virtual entries and two
box/unbox-domain conversions per iteration. Nullable input is null every eighth
iteration, which exercises true `Nullable<Int32>` storage rather than a
non-null-only value path. The struct route validates both fields after every
round trip so a smaller payload cannot imitate a representation win.

All candidate routes report
`TYPED_STORAGE_PRODUCER_GRAPH_PROVEN`; all erased routes report
`ERASED_OBJECT`. Exact and capability pairs use identical values and produce
the same per-route checksum in all five runtime/deployment modes.

## High-resolution results

The final run used 2,000,000 iterations, seven startup runs, seven throughput
runs, and three clean compile/publish runs per representation and mode. Values
below are candidate workload time divided by erased workload time. They are
bounded micro-workload medians, not application speedups.

| Route | Framework 4.8 | .NET 10 JIT | ReadyToRun | Full trim | NativeAOT |
|---|---:|---:|---:|---:|---:|
| exact `Int32` | 0.677× | 0.430× | 0.282× | 0.464× | 0.134× |
| capability `Int32` | 3.768× | 2.497× | 1.943× | 2.437× | 2.124× |
| exact `Int32 + Guid` | 1.245× | 1.323× | 0.909× | 1.547× | 0.107× |
| capability `Int32 + Guid` | 2.077× | 2.931× | 2.114× | 4.215× | 3.376× |
| exact `Nullable<Int32>` | 0.157× | 0.320× | 0.347× | 0.186× | 0.048× |
| capability `Nullable<Int32>` | 2.044× | 2.259× | 2.077× | 2.439× | 2.331× |

Exact typed storage removes all per-iteration allocation:

| Value shape | Exact candidate / erased bytes | Capability candidate / erased bytes |
|---|---:|---:|
| `Int32` | 24 / 48,000,048 | 96,000,024 / 48,000,048 |
| `Int32 + Guid` | 40 / 80,000,064 | 160,000,040 / 80,000,064 |
| `Nullable<Int32>` | 24 / 42,000,024 | 84,000,024 / 42,000,024 |

Those are Framework/JIT-family figures; NativeAOT reports zero fixed candidate
allocation on exact routes and 24–40 fewer erased setup bytes. The nullable
erased total is lower because boxing a nullable value with no value produces a
null reference; seven eighths of the iterations contain values.

The allocation result is deterministic and is the strongest conclusion. An
exact `!T` field obtains the missing `value -> value field -> value result`
path. The strict capability necessarily re-enters the object domain and, for a
non-null value, boxes once on entry and once on output. It therefore allocates
twice the erased baseline in these routes even though the typed field itself is
not boxed.

Timing is deliberately not generalized. Exact `Int32` and nullable routes are
materially faster in every lane, but the larger struct is slower on Framework,
JIT, and full trim despite eliminating allocation; copying and code-generation
costs remain runtime/deployment sensitive. ReadyToRun is near parity and
NativeAOT strongly favors the exact struct route. Capability routes remain
1.943–4.215 times erased. Representative call mixes must determine how often a
real application remains exact and whether capability crossings can be proved
unnecessary without changing Kotlin semantics.

## Independent runtime evidence

Framework and .NET 10 are separate evidence lanes; absolute values are not
compared between runtime families.

- Framework products were compiled by SDK 10.0.100 Roslyn against explicit
  installed Framework assemblies and executed by 64-bit Windows PowerShell on
  CLR `4.0.30319.42000`. The registered runtime reports release `533509` and
  product version `4.8.09221`.
- .NET 10 lanes used runtime 10.0.10 and SDK 10.0.100 for JIT, ReadyToRun, full
  trimming, and NativeAOT.
- NativeAOT used the explicitly validated signed Microsoft 14.44 linker and
  Windows SDK 10.0.26100 libraries.

This separation matters: .NET 10 object, boxing, generic-sharing, or
devirtualization improvements are not evidence for Framework 4.8. The common
allocation direction holds on both families, while exact struct timing already
shows that deployment-specific code generation can reverse a time result.

## Architectural consequence

The evidence supports a narrow compiler rule rather than a blanket switch:

1. use physical `!T` state only after the complete producer graph proves every
   initializer and transitive write physically typed;
2. keep exact calls on identity access paths and do not route them through the
   capability merely for uniformity;
3. retain one strict capability on the same owner wherever valid Kotlin
   widened/star behavior requires the object domain;
4. reject an incompatible capability input before mutation;
5. retain semantic object state for any object-domain, unsupported,
   source-free, externally writable, or otherwise unresolved producer; and
6. never add a typed cache beside canonical object state.

This makes the future optimization question measurable. The important count is
not how many owners are emitted as `C<T>`, but how many state operations remain
on exact typed paths after whole-producer analysis. Capability crossings are
likely limited for some owners, but that must be demonstrated with complete
applications and open-world boundaries rather than assumed from this fixture.

## Reproduction and provenance

The final command was:

```powershell
$routes = @(
  'typed-entry-typed-state', 'capability-value-typed-state',
  'typed-entry-struct-typed-state', 'capability-struct-typed-state',
  'typed-entry-nullable-typed-state', 'capability-nullable-typed-state'
)
& compiler/ir/backend.dotnet/tools/measure-generic-owner-applications.ps1 `
  -Modes framework,jit,ready-to-run,trimmed,native-aot `
  -AttributionRoutes $routes `
  -Iterations 2000000 -StartupRuns 7 -ThroughputRuns 7 -CompileRuns 3 `
  -ExistingCorpus compiler/ir/backend.dotnet/build/generic-owner-applications/20260814-typed-state-corpus-v2 `
  -OutputDirectory compiler/ir/backend.dotnet/build/generic-owner-application-measurement/20260814-typed-state-v2-final `
  -NativeLinker <validated-link.exe> `
  -NativeLibraryDirectories <msvc-lib>,<windows-sdk-um>,<windows-sdk-ucrt>
```

- repository head at measurement:
  `5a5948eea489d29f54dae63219af992fde147149` (reviewed dirty tree)
- measurement tool SHA-256:
  `c86a4d4d6f5e37852aa35d71bbc2beac194260e498031a66fc457ac71aba99a2`
- net10 manifest SHA-256:
  `18d6927fb63ca4fa63241fc83c4bb78a8e2fef56df525770a5315f123bcf0f06`
- net48 manifest SHA-256:
  `6625e9ee42b1be9bca7c47759483f62f3aebee7d7dd3ef8e4058a969fd441ef7`
- final result SHA-256:
  `c558c34f38bd69b31e77e9bd314fb91052ce1a671f3b54607cc12044ee43b5c8`
- NativeAOT linker version/hash: 14.44.35228.0 /
  `ca11e6c45debd34bf652dfe984c5360a531a005ed78bf72852330c9c2590cf0d`

Published byte counts and compile-time ratios are not end-to-end comparable:
the candidate is a generated physical family while the erased side is a
complete Kotlin product with Runtime, Stdlib, and embedded metadata.

## Verification and remaining gate

- PSI and LightTree direct and separate-compilation consumers pass on net10 and
  net48; the Framework lane uses the real CLR 4 host.
- The closed corpus verifier accepts both profile bundles and proves
  PSI/LightTree executable, KLIB, binding, and manifest equivalence.
- Direct C# execution pins exact/capability behavior, incompatible-write failure
  before mutation, the `!T` field/method metadata, and private/final capability
  dispatchers.
- All route protocol fields, counters, carrier requirements, iterations, and
  candidate/erased/cross-mode checksums fail closed.
- JIT, ReadyToRun, full trim, and NativeAOT publish and execute successfully;
  trimming/AOT warning checks remain active.

The strict aggregate completed in 849.5 seconds. Direct audit of its three
result roots covers 190 XML files and 2,216 tests with zero failures, errors, or
skips. This feature closes typed-storage causal feasibility only.
Representative complete Kotlin applications and C# consumers/subclasses on
Framework 4.8 and all .NET 10 deployment modes remain the next reopening gate
before any atomic production migration decision.
