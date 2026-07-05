# DotNet (CIL) backend — design rules and working notes

Prototype Kotlin → .NET CIL target. Code lives in `compiler/ir/backend.dotnet/` (backend) and
`compiler/cli/cli-dotnet/` (CLI, K2 phased pipeline only). IL-text golden tests are the primary
validation: test data in `compiler/testData/codegen/dotnet/ilText/`, runners generated from
`compiler/fir/fir2ir/testFixtures/.../codegen/AbstractDotNetIlTextTest.kt` (`./gradlew generateTests`).
CLI tests in `compiler/testData/cli/dotnet/`. Box tests exist but executing generated unsigned
`.exe` files may be blocked by local OS policy; "ilasm assembles cleanly" is the executable bar.

## Design rules

- Before implementing DotNet backend behavior, inspect how the mature JVM/JS/WASM/Native targets
  solve the same problem, then make an explicit .NET-specific decision. Do not invent a separate
  approach unless the CLR platform model gives a concrete reason.
- When reporting an implemented DotNet backend feature, state which mature target it follows. If the
  implementation deviates, state the target it deviates from and the CLR-specific reason.
- If JVM uses an intrinsic registry for a behavior, DotNet wires that behavior through
  `DotNetIlIntrinsicMethods` too, unless there is a concrete platform reason not to. "Needed later"
  is not a valid reason to skip the registry shape; add the registry entry now and let unsupported
  cases fail explicitly.
- IL codegen fails on unsupported IR (`dotNetUnsupported()`) instead of emitting fallback IL such as
  empty strings or zero values. `DotNetIlEmitter` skips uncompilable functions to a fixpoint
  (callers of skipped functions are skipped too) and errors only when the entry point is affected.

## Established decisions

- IL codegen split: `DotNetIlEmitter` (module orchestration), `DotNetIlClassCodegen` (class shell,
  method dispatch), `DotNetIlMethodCodegen` (bodies/statements), `DotNetIlExpressionCodegen`
  (expressions), `DotNetIlMethodContext` (slots/labels/maxstack/stack verification),
  `DotNetIlCodegenSupport` (type mapping, signatures, escaping), `DotNetIlType` (value types vs
  return types).
- String concatenation follows the mature target shape: `FlattenStringConcatenationLowering`, then
  `DotNetStringConcatenationLowering`, then IL codegen handles `String.plus`/`toString` intrinsics.
  Avoid ad-hoc IrWhen/boolean handling inside string emission.
- Lowerings run through `NamedCompilerPhase`/`PhaseEngine`, measured as `PhaseType.IrLowering`.
- Main selection uses `DotNetMainFunctionDetector`. No wrapper is generated when the selected
  Kotlin `main` shape already maps to a valid CLR `.entrypoint` method (ECMA-335 allows
  parameterless or `string[]` entry points); add a wrapper only when a supported source shape needs one.
- Kotlin `Unit` is not an IL value type. CLR `void` is only a return encoding; Unit-returning
  functions are emitted as `void`, and `IMPLICIT_COERCION_TO_UNIT` discards values with `pop`.
- Local `val`/`var` follows the JVM/WASM model conceptually: the method context maps IR value
  symbols to slots. CLR keeps argument slots (`ldarg`) separate from `.locals init` slots
  (`ldloc`/`stloc`).
- `if`/`when` follows JVM/WASM `IrWhen` handling: evaluate conditions, `brfalse` to next branch,
  `br` to the end label after a matched branch.
- Equality follows JVM's intrinsic-registry shape: `Int`/`Boolean` use `ceq`, `String ==` uses
  `System.String::op_Equality`, `String ===` uses reference `ceq`. Boxed/object equality fallback is
  deferred until the backend has an object/runtime model.
- The fake stdlib (`DotNetStdlibSource`) is a map of injected source files, one per package
  (`kotlin.io` for `println`, `kotlin` for `Char.code`), filtered through the intrinsic registry and
  never emitted as classes of their own. Injected declarations must compile without any diagnostics,
  including warnings: the FIR test infrastructure maps every reported diagnostic back to a test
  file and crashes on diagnostics in injected files (suppress e.g. deprecations locally).
- Generics stance: the type representation stays structural so that future generics can target real
  CLR reified generics (Roslyn shape), not JVM-style erasure. Unsupported generic shapes are
  rejected, never erased.
- Shared runtime code (e.g. the Kotlin-parity `Double.toString` rendering) is hand-written IL on the
  synthetic module-private `'<KotlinIl>'` class (`DotNetIlRuntimeHelpers`) — the CLR-side stand-in
  for the JVM's `kotlin.jvm.internal.Intrinsics` runtime until a real .NET stdlib exists. The class
  is emitted at most once per module and only when a rendered method required one of its helpers.
  Every mscorlib member signature used in helper IL must be verified by assembling and running an
  ilasm probe before it lands in codegen.

## Modern .NET toolchain

- A durable, per-user (no admin) modern toolchain lives at `%LOCALAPPDATA%\kotlinc-dotnet\toolchain\`:
  `dotnet\dotnet.exe` (.NET runtime, pinned 10.0.9) and `ilasm\ilasm.exe` (self-contained modern
  CoreCLR assembler from the NuGet package `runtime.win-x64.microsoft.netcore.ilasm`, pinned 10.0.9).
  Provision or repair it with the idempotent script
  `compiler/ir/backend.dotnet/tools/provision-dotnet-toolchain.ps1` (parameters: `-InstallDir`,
  `-RuntimeVersion`, `-IlasmVersion`).
- Discovery contract (for the assembler/test runner; implement lookups in this order):
  1. `KOTLIN_DOTNET_ILASM` — full path to an `ilasm.exe`; takes precedence for the assembler.
  2. `KOTLIN_DOTNET_ROOT` — a toolchain root containing `dotnet\` and `ilasm\` subdirs
     (i.e. `<root>\ilasm\ilasm.exe`, `<root>\dotnet\dotnet.exe`).
  3. The default durable location above.
  4. Legacy .NET Framework ilasm (`C:\Windows\Microsoft.NET\Framework64\v4.0.30319\ilasm.exe`).
- The modern ilasm accepts both the legacy flag spelling (`/nologo /quiet /exe /output:x.exe`) and
  the modern one (`-DLL -OUTPUT=x.dll`; quote `-OUTPUT=...` when calling from PowerShell, which
  otherwise mangles the `=`). It reads UTF-8 IL with or without BOM, so existing emitter output
  assembles unchanged.
- Running an assembled dll on CoreCLR requires `x.runtimeconfig.json` next to it:
  `{"runtimeOptions":{"tfm":"net10.0","framework":{"name":"Microsoft.NETCore.App","version":"10.0.0"}}}`
  then `<toolchain>\dotnet\dotnet.exe exec x.dll`. Without the runtimeconfig, `dotnet exec` fails
  with a hostpolicy.dll error. Prefer dll + `dotnet exec` over direct `.exe` execution: the signed
  `dotnet.exe` host avoids the Smart App Control blocking of freshly assembled unsigned exes.
- Known semantic delta vs. .NET Framework: raw .NET formatting renders `-0.0` as `"-0"` on CoreCLR
  but `"0"` on Framework. The backend's own `DoubleToString` helper makes this moot for compiled
  Kotlin programs, but raw formatting probes differ.
