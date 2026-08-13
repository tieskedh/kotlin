# Record-driven generic-owner measurement corpus — 2026-08-13

- Evidence date: 2026-08-13
- Environment: Windows x64, 8 logical processors, pinned .NET SDK 10.0.100
- Programme:
  [`../programmes/generic-class-owner-reopening.md`](../programmes/generic-class-owner-reopening.md)
- Tool: [`../../tools/measure-generic-owner.ps1`](../../tools/measure-generic-owner.ps1)

This is a reproducibility snapshot, not representation authority or a
production-migration decision. Normal Kotlin generic-class emission remains
on the accepted erased owner.

## One compiler-derived corpus

The measurement source is not a second handwritten generic-owner model. The
tool reruns
`testGenericOwnerHardestModelOracleSeparateCompilation`, which builds the
temporary net10 producer, decodes its version-6 physical-family record, and
generates the same finite record-driven C# factory and hostile consumer used
by the correctness oracle. Only the exported project defines
`GENERIC_OWNER_MEASUREMENT`; the ordinary test still compiles and executes its
original correctness entry point.

The clean-room Gradle invocation passes the export directory as an explicit
test JVM property. The directory must be empty and the resulting bundle must
contain exactly these six files:

- generated `RecordedFamilyConsumer.cs`;
- exact `SnapshotProducer.dll`;
- exact `SnapshotProducer.generic-owner-families` record;
- pinned `RecordedFamilyMeasurement.csproj`;
- pinned `global.json`; and
- the closed-shape fingerprint manifest.

Publication writes `obj`, `bin`, publish products, logs, and results outside
that bundle. Before and after publication the tool rechecks its exact entries
and all five content hashes. This run recorded:

| Content | SHA-256 |
| --- | --- |
| producer DLL | `cc053eba77eb83a9609e57c482784f6e3761486b4204ea83bffbf45c443d474f` |
| generated source | `e7b7a205c59aad3e696572fe3427e349443f704f26c8caa181cec9cb8f1e5564` |
| measurement project | `cf530180f877ce784499da95e7568ef4f40424bb9496d769a517dc0da6feb53f` |
| SDK selector | `050bd5d0a1d0fc580fc0a33c5e5a473660d6bd498a26d4c244ce8e27e054f2d9` |
| physical-family record | `3827769901f7a70e1773af92bced2da14f18bac71ef6d74d569a091967099ad7` |

The manifest also records construction key
`ConsumerUnsafeLeaf#openNullableConstruction`, net10, SDK 10.0.100, schema 1,
and workload version 1. `MakeGenericType` and `Activator.CreateInstance` are
rejected before publication.

## Workload and protocol

Each iteration composes the finite factory and the hostile dispatch graph:

- exact `int`, already-nullable `int?`, `string`, and consumer-owned struct
  routes;
- unlisted struct and reference routes through the mandatory `C<object>`
  semantic fallback;
- typed and semantic capability writes/reads;
- typed `T[]` and semantic `System.Array` identity;
- multi-level C#/Kotlin-like override dispatch; and
- an incompatible broad-candidate write, verified first through the semantic
  override and then through its delayed failing typed read.

The executable performs a bounded warm-up, full GC, and 50,000 measured
iterations. Five zero-iteration processes measure startup and three workload
processes measure throughput. It reports its own elapsed ticks and current-
thread allocation; the host records wall time, a sampled process peak working
set, publish duration, file count, and footprint. Every reported protocol
field and iteration count is exact, and checksums must agree within and across
deployment modes.

## Local results

All three available modes produced checksum `2027804433`:

| Mode | Deployment | Publish ms | Files | Bytes | Startup median ms | Workload median ms | Allocation median bytes | Peak working-set median bytes |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| JIT | framework-dependent | 3,539.1 | 5 | 178,023 | 37.957 | 30.976 | 19,963,480 | 26,177,536 |
| ReadyToRun | framework-dependent | 3,825.3 | 5 | 213,351 | 34.339 | 29.937 | 19,963,408 | 25,313,280 |
| trimmed | self-contained, full trim | 8,070.2 | 29 | 20,276,983 | 63.886 | 43.660 | 20,050,144 | 24,625,152 |

These are one local baseline, not stable product performance promises. The
framework-dependent footprints exclude the shared runtime while the trimmed
footprint includes it, so their byte totals are not direct size comparisons.
The workload deliberately allocates factory results; its allocation number is
not an isolated bridge-overhead measurement. Machine load, process sampling,
and three throughput observations also make small timing differences
descriptive rather than conclusive.

The useful result is architectural: the same statically rooted exact/fallback
family survived ordinary JIT, ReadyToRun, and full trimming without checksum,
dispatch, state, or array-identity drift. It does not yet compare the candidate
against representative erased-owner applications, arbitrary real structs,
compiler cost, or concurrency behavior.

## NativeAOT remains open

The tool has an explicit `native-aot` mode. It publishes the same project with
`PublishAot=true`, requires a real self-contained executable, runs the full
protocol, and treats any restore, analysis, native link, execution, checksum,
or counter failure as fatal. ReadyToRun or trimming can never set
`nativeAotProven`.

That mode was not run for this snapshot because this host still lacks the
Visual C++ platform linker. The result therefore records
`nativeAotProven=false`. Earlier managed-analysis evidence remains useful, but
only a successful native link and execution of this exact bundle on a complete
toolchain can close that part of the construction gate.

## Reproduction

From the repository root, the default command regenerates and verifies the
bundle, then runs the three currently available modes:

```text
pwsh compiler/ir/backend.dotnet/tools/measure-generic-owner.ps1
```

After installing the required native toolchain, NativeAOT must be run against
the same verified bundle rather than a substitute project:

```text
pwsh compiler/ir/backend.dotnet/tools/measure-generic-owner.ps1 -Modes native-aot -ExistingBundle C:\path\to\verified\bundle
```

Generated bundles and JSON results remain ignored build outputs; this snapshot
retains the protocol, exact content fingerprints, and observed baseline.
