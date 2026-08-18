# Generic-owner foreign override separate compilation (2026-08-18)

## Question

The first foreign-override proof compiled a Kotlin owner and Kotlin override
into one DLL. Physical ABI 36 records the capability and semantic-hook
identities, but not the protected last-Kotlin probe. It was therefore unclear
whether Kotlin base DLL -> Kotlin override DLL -> C# subclass DLL required an
ABI revision for that probe.

## Closed corpus

The new oracle produces three actual assemblies:

```text
lib.dll
  RehearsalSeparateStore<T>
  RehearsalSeparateReader.read(Store<Any?>)

middle.dll
  RehearsalSeparateKotlinOverrideStore<T> : Store<T>
  override read(): T

C# consumer
  RehearsalSeparateCSharpOverrideStore
    : RehearsalSeparateKotlinOverrideStore<string>
  override read(): string
```

The warnings-as-errors C# consumer proves both its direct typed call and the
base-DLL widened Kotlin reader observe the C# result. The Kotlin executable
also writes a `String` through `Store<Any?>` onto a physical middle-DLL
`Store<Int>`. The base-DLL reader returns that exact object; only a later real
`Int` operation throws, and a compatible widened write restores the state.

Both halves execute through PSI and LightTree on .NET Framework 4.8 and .NET
10. The same Kotlin negative sequence also remains valid in the erased normal
epoch.

## Result

No producer probe binding is needed. Every participating Kotlin declaration
already emits:

```text
local protected virtual last-Kotlin probe
local private final capability dispatcher -> that local probe
```

ABI-36 binds the inherited capability/semantic family. The later Kotlin DLL's
dispatcher directly names its own probe, so an unchanged Kotlin instance uses
the raw semantic hook. A still-later C# subclass overrides only the typed
entry; `ldvirtftn` then differs from the local Kotlin `ldftn`, and the same
dispatcher selects the natural typed virtual.

Serializing the probe would therefore add ABI surface without closing an
observed semantic or interop gap. Deployment-mode validation of the managed
function-pointer comparison is the next gate.
