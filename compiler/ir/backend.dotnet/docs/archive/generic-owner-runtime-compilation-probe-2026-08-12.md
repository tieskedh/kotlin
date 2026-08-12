# Generic-owner runtime compilation probe — 2026-08-12

- Evidence date: 2026-08-12
- Environment: Windows x64, pinned .NET SDK 10.0.100, CoreCLR 10.0.x
- Related architecture:
  [`../programmes/generic-class-owner-carrier-matrix.md`](../programmes/generic-class-owner-carrier-matrix.md)

This is a reproducibility snapshot, not architecture authority. The accepted
erased-owner ADR remains binding.

## Probe shape

The bounded C# program mirrors the dynamic part of
`DotNetLibraryIntegrationTest.testOpenNullableGenericOwnerRuntimeConstructionOptions`:

- one open `DynamicBox<T>` TypeDef and one non-generic semantic interface;
- `MakeNullable<T>` selects `Nullable<T>` for a value-type runtime token and
  `T` for a reference token via `Type.MakeGenericType`;
- `DynamicBox<>` is closed over that selected argument and constructed through
  `Activator.CreateInstance`; and
- `int` and `string` substitutions verify physical type, initial null, write,
  read, and return to null.

The source deliberately does not add trimming roots or suppressions. This
tests the mechanism's default toolchain classification rather than making the
two examples pass through hand-authored metadata.

## Results

| Mode | Result | Exact observation |
| --- | --- | --- |
| ordinary CoreCLR JIT | pass | the permanent integration test constructs and mutates exact `DynamicBox<Nullable<int>>` and `DynamicBox<string>` |
| ReadyToRun, framework-dependent win-x64 | build and execution pass | `dotnet publish` with `PublishReadyToRun=true` completed; the published program returned 0 |
| full trimming, self-contained win-x64 | build and execution pass | `dotnet publish` with `PublishTrimmed=true`, `TrimMode=full` completed; the published executable returned 0 |
| NativeAOT, self-contained win-x64 | execution not reached | the AOT analyzer emitted IL3050 for `Type.MakeGenericType`: native code for the requested instantiation might be unavailable |
| NativeAOT native link | environment-blocked | publication stopped because the Visual C++ platform linker/Desktop C++ workload is not installed |

The AOT restore and managed compilation completed before the missing-linker
failure. There is no NativeAOT runtime result from this machine. The analyzer
warning is evidence that arbitrary runtime closure cannot be assumed AOT-safe;
it is not proof that every generated/rooted finite construction must fail.

## Commands

The probe used a temporary `net10.0` console project under the target build
directory. The relevant property sets were:

```text
dotnet publish -c Release -r win-x64 -p:PublishReadyToRun=true --self-contained false
dotnet publish -c Release -r win-x64 -p:PublishTrimmed=true -p:TrimMode=full --self-contained true
dotnet publish -c Release -r win-x64 -p:PublishAot=true --self-contained true
```

Generated binaries and the temporary project were not retained as repository
inputs. The permanent JIT/runtime semantic coverage remains in the integration
test; ReadyToRun, trimming, and NativeAOT become representative application
gates before production migration rather than network/toolchain-heavy strict
unit tests.

## Consequence for the candidate

Runtime exact construction remains viable on the supported JIT and survived
the bounded ReadyToRun and trimming probes. It is not yet an accepted
open-nullable mechanism. NativeAOT needs a machine with the complete native
toolchain and a hostile set covering externally supplied structs, separate
assemblies, finite rooting, and truly open generic producers. If that cannot
avoid an unbounded dynamic-code requirement, the candidate must use a
semantic fallback for those positions or constrain admission; it may not
silently drop NativeAOT or claim exact construction everywhere.
