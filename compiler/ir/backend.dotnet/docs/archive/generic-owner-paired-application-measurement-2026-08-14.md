# Generic-owner paired application measurement — 2026-08-14

## Outcome

The closed paired application corpus now has one reproducible, five-mode
erased-versus-candidate measurement. The same hostile logical workload runs
against the production-erased Kotlin owner and the compiler-record-derived,
test-owned `C<T>` physicalization. Framework CLR 4, .NET 10 JIT,
ReadyToRun, full trimming, and NativeAOT all produced checksum `-365770154`.

The result does **not** authorize production CLR-generic owners. In this
workload the candidate took 1.62–2.96 times the erased workload time and
allocated 6.89–7.52% more. That is not evidence that CLR generics are
intrinsically slower. The candidate deliberately exercises the complete
semantic capability/bridge architecture: only three regular routes per
iteration use a typed entry, while 24 use the semantic capability. The result
therefore says that typed owner identity alone does not pay for the current
semantic routing cost.

This remains a bounded hostile application, not the representative real-app
corpus required by the reopening programme. The candidate is generated C#
physicalization rather than a complete Kotlin product and does not carry the
production Runtime, Stdlib, or embedded KLIB. Published byte totals and
end-to-end compile costs are recorded for audit, but are explicitly not a
product-size comparison.

## Independent runtime evidence

Framework and .NET 10 are separate measurement lanes. They are not treated as
one target label and their absolute startup times are not compared.

- The Framework application is compiled by SDK 10.0.100 Roslyn against the
  explicit CLR 4 `mscorlib`, `System`, and `System.Core` assemblies, then
  executed by 64-bit Windows PowerShell on CLR `4.0.30319.42000`. The recorded
  framework assembly versions are 4.8.9337.0, 4.8.9340.0, and 4.8.9344.0.
- The JIT, ReadyToRun, trimmed, and NativeAOT lanes use .NET 10.0.10 with SDK
  10.0.100. NativeAOT uses the explicitly validated Microsoft 14.44 linker and
  Windows SDK 10.0.26100 import libraries.
- Framework startup includes its PowerShell/CLR 4 reflection host. It is valid
  only for candidate-versus-erased comparison within that lane.

Keeping those lanes independent matters when CoreCLR changes optimization of
`object`, boxing, generic sharing, or interface dispatch. A .NET 10 result can
neither prove nor dismiss behavior on .NET Framework 4.8.

## Workload and protocol

Each measured iteration composes the same logical owner values:

- `Int`, nullable `Int`, `String`, and the producer's known struct;
- `Guid`, `DateTime`, `decimal`, an enum, a tuple, and a consumer struct; and
- one reference-type fallback.

It also keeps a persistent owner, passes typed primitive/reference/struct
arrays, uses a method-generic relay, and every 64 iterations constructs a
hostile subclass and observes incompatible semantic mutation followed by a
delayed typed-read `InvalidCastException`. Startup runs use zero measured
iterations after a bounded warm-up. The final run used 200,000 iterations,
seven startup runs, seven throughput runs, and three clean compile/publish runs
per representation and mode.

The candidate protocol records `iterations * 3 + periodicRoutes` typed entry
calls and `iterations * 24 + periodicRoutes * 2` semantic capability calls.
The erased protocol records `iterations * 27 + periodicRoutes * 2` erased
virtual calls, where `periodicRoutes = ceil(iterations / 64)`. Route counts,
iteration count, representation, workload version, checksum, elapsed ticks,
timer frequency, and allocation must all parse exactly.

## Input inventory

| Item | Candidate | Production-erased producer |
|---|---:|---:|
| PE bytes | 5,120 | 26,624 |
| TypeDefs | 4 | 14 |
| MethodDefs | 25 | 71 |
| fields / properties | 1 / 0 | 4 / 5 |
| MethodImpls | 3 | 20 |
| GenericParams | 3 | 3 |
| TypeSpecs | 3 | 2 |
| assembly references | 1 | 4 |
| executable IL bytes | 343 | 945 |
| managed resource bytes | 0 | 16,448 |
| embedded Kotlin metadata bytes | 0 | 16,322 |

The candidate's schema-7 physical-family artifact is 37,275 bytes. The erased
application additionally consumes the 79,360-byte Runtime and 2,094,592-byte
Stdlib. The three candidate GenericParams are owner parameters; the erased
producer retains only method-generic parameters. These inventories describe
different product completeness and must not be converted into a size win.

## Runtime results

Times are medians in milliseconds; allocation and peak working set are bytes.
`C` is candidate and `E` is erased.

| Mode | Compile C / E | Startup C / E | Workload C / E | Allocation C / E | Peak C / E |
|---|---:|---:|---:|---:|---:|
| Framework CLR 4 | 683.266 / 703.527 | 152.761 / 155.172 | 271.012 / 91.616 | 194,725,208 / 181,100,208 | 70,946,816 / 70,807,552 |
| .NET 10 JIT | 3,225.814 / 3,293.070 | 42.374 / 41.590 | 200.170 / 113.427 | 195,050,280 / 182,475,280 | 30,695,424 / 30,003,200 |
| .NET 10 ReadyToRun | 3,406.186 / 4,990.461 | 41.026 / 40.504 | 241.656 / 124.484 | 195,050,208 / 182,475,208 | 30,035,968 / 27,987,968 |
| .NET 10 full trim | 8,288.025 / 8,116.603 | 87.716 / 91.158 | 220.498 / 135.981 | 195,136,944 / 182,475,280 | 28,372,992 / 27,893,760 |
| .NET 10 NativeAOT | 5,783.857 / 5,504.591 | 21.926 / 21.516 | 88.260 / 38.863 | 192,925,168 / 180,350,128 | 16,486,400 / 16,322,560 |

| Mode | Candidate / erased workload time | Candidate allocation excess | Startup delta C − E |
|---|---:|---:|---:|
| Framework CLR 4 | 2.958× | 13,625,000 / 7.523% | -2.411 ms |
| .NET 10 JIT | 1.765× | 12,575,000 / 6.891% | +0.784 ms |
| .NET 10 ReadyToRun | 1.941× | 12,575,000 / 6.891% | +0.522 ms |
| .NET 10 full trim | 1.622× | 12,661,664 / 6.939% | -3.442 ms |
| .NET 10 NativeAOT | 2.271× | 12,575,040 / 6.973% | +0.410 ms |

Startup differences are small relative to process variance and do not select a
representation. The material signal is the repeated semantic-routing workload
and allocation excess. A subsequent experiment must isolate typed entry,
compatible capability, incompatible-candidate, and override routes before
claiming a specific optimizer or bridge as the cause. Representative
applications must then decide whether that cost persists in real call mixes.

## Deployment defect found by the comparison

The first full run found a deterministic full-trimming failure in the erased
application. `HostileCell` inherits collection interfaces through external
`AbstractMutableCollection`, but the backend rebuilt canonical collection
bridge `MethodImpl` rows on `HostileCell` without naming those canonical
interfaces directly. CoreCLR and CLR 4 loaded that shape and produced working
interface maps; ILLink rejected it because the implementing type did not
directly or transitively expose the interface in the map it was constructing.

The repair preserves one Kotlin object and one state:

- class-owned canonical bridge families are now written to the physical
  library index as well as interface-owned families when the pre-lowering KLIB
  graph gives the class a producer-visible cross-module identity, so new
  consumers can bind an external producer's complete inherited bridge bundle;
- lowering-created continuations, adapters, and other synthetic classes do not
  receive physical-index records merely because they need a local bridge; and
- when a class nevertheless rebuilds a canonical bridge from an older or
  bootstrap external index, emission adds the corresponding direct
  `InterfaceImpl`. This is ordinary CLR interface reimplementation, not a
  wrapper, copied state, or a second Kotlin identity.

The application verifier now requires `HostileCell` to directly reimplement
`Kotlin.Collections.Collection`, `Iterable`, `MutableCollection`, and
`MutableIterable`. The formerly failing trimmed publish and both runtime
executions pass with the repaired metadata.

## Reproduction

The source-controlled measurement command was:

```powershell
& compiler/ir/backend.dotnet/tools/measure-generic-owner-applications.ps1 `
  -Modes framework,jit,ready-to-run,trimmed,native-aot `
  -Iterations 200000 -StartupRuns 7 -ThroughputRuns 7 -CompileRuns 3 `
  -ExistingCorpus compiler/ir/backend.dotnet/build/generic-owner-applications/20260814-final-scoped-bridges `
  -OutputDirectory compiler/ir/backend.dotnet/build/generic-owner-application-measurement/20260814-final-scoped-bridges `
  -NativeLinker "$env:LOCALAPPDATA/kotlinc-dotnet/native-toolchain/msvc/Contents/VC/Tools/MSVC/14.44.35207/bin/Hostx64/x64/link.exe" `
  -NativeLibraryDirectories `
    "$env:LOCALAPPDATA/kotlinc-dotnet/native-toolchain/msvc/Contents/VC/Tools/MSVC/14.44.35207/lib/x64", `
    "$env:LOCALAPPDATA/kotlinc-dotnet/native-toolchain/winsdk/Windows Kits/10/Lib/10.0.26100.0/um/x64", `
    "$env:LOCALAPPDATA/kotlinc-dotnet/native-toolchain/winsdk/Windows Kits/10/Lib/10.0.26100.0/ucrt/x64"
```

The measurement-tool SHA-256 was
`9ace8ec0d0c45fee8bab3c97a4a5ce025b6dc0d6111e7087050dc4d219d310c5`.
The net10 and net48 manifest hashes were respectively
`f8bb7f4df6c292453af8bf9d135c253aba2b7831cb7478c56095617c21224ccd`
and
`c255d62989f4ae3ce7688a58d85d4b5e1575a67850aaac21c8ec348ebdcb6757`.
The run was made from the reviewed dirty worktree based on
`29c216c8971ebd629d8e6d8d9154fc76f9db48c4`; the result records that state
rather than presenting it as a clean released binary.

## Verification and remaining gate

- backend and test-fixture compilation passed;
- a previously failing Framework coroutine synthetic-owner regression passed
  from a forced clean task run: 1 test, 0 failures, errors, or skips;
- the focused CoreCLR separate-compilation application test passed;
- a fresh PSI/LightTree × net10/net48 corpus was generated, fully fingerprinted,
  frontend-audited, and all candidate/erased Kotlin/direct C# products executed;
- the exact trimmed publish that originally failed now publishes and executes;
- all five paired measurement modes and 140 measured application executions
  (70 startup and 70 throughput) completed with one checksum; and
- the strict aggregate completed in 1,751.2 seconds; its 190 XML files contain
  2,204 tests and zero failures, errors, or skips. The six policy-free physical
  CLI model/serializer tests were then explicitly refreshed with
  `--rerun-tasks` in 327.1 seconds and remained green.

This closes the bounded paired-measurement foundation and the ILLink metadata
defect it exposed. It does not close the representative-application gate. The
next candidate investigation must attribute semantic bridge costs without
weakening broad Kotlin calls, then validate any improvement in real
applications on Framework CLR 4.8 and every .NET 10 deployment mode separately.
