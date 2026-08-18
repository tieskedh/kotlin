# Generic-owner foreign override deployment closure (2026-08-19)

## Question

The separate-compilation proof established the managed IL shape and ordinary
JIT behavior of the last-Kotlin foreign-override probe. It did not establish
that ReadyToRun, full trimming, or NativeAOT preserve the
`ldvirtftn`/`ldftn` comparison and its two semantic outcomes.

## Closed verifier

`tools/verify-generic-owner-foreign-override-deployment.ps1` regenerates the
actual PSI rehearsal product unless an existing closed export is supplied. It
requires exactly these six producer entries:

```text
lib.dll
lib.il
middle.dll
middle.il
main-genericOwnerForeignOverrideSeparateCompilation.dll
main-genericOwnerForeignOverrideSeparateCompilation.il
```

Before publishing, it rejects a base/middle pair that does not share one probe
name, a middle probe that opens a `newslot`, or either Kotlin owner that loses
the allocation-free `ldvirtftn` plus `ldftn` body. It fingerprints the closed
source/project/assembly input and checks those hashes again after every
publication.

The C# oracle declares only the natural source override:

```csharp
public override string read()
```

It neither calls nor overrides the protected Kotlin compiler hook or probe.
One ordinary Kotlin middle instance must keep the semantic path, including an
`Int` construction, while a C# subclass after that Kotlin override must make a
base-DLL widened Kotlin reader observe the C# result. Thus every execution
checks both outcomes of the managed-function comparison rather than merely
starting a published application.

## Results

The self-producing JIT control regenerated the focused Kotlin product and
passed. Its `lib.dll` and `middle.dll` SHA-256 values were respectively:

```text
c3439b6232ceefa134521f5bb6e455fe66730e8ffac3bdc4df9f8f8f8daaf653
1aa68e3e1764c61139c77c7947618412b4ca3837db97e39e9066192b72e5cb42
```

The final deployment run used SDK 10.0.100 and the same freshly generated
product. Every mode printed exactly `OK` and exited zero:

| Mode | Files | Published bytes | Native execution |
|---|---:|---:|---|
| JIT | 8 | 3,177,814 | no |
| ReadyToRun | 8 | 6,726,998 | no |
| full trim | 30 | 20,287,504 | no |
| NativeAOT | 1 | 955,392 | yes |

The NativeAOT lane used the signed Microsoft linker version 14.44.35228.0
with SHA-256
`ca11e6c45debd34bf652dfe984c5360a531a005ed78bf72852330c9c2590cf0d`.
It completed native code generation, produced a real Windows x64 executable,
and executed that file. No publish log contained compiler, linker, IL2026, or
IL3050 diagnostics. The published byte counts describe different packaging
modes and are not a performance or size comparison.

The verifier SHA-256 for the recorded run was
`1ab85494ab4bc616736619a914ae74fbc371c3610dbf38f3af0acf0e999aabbc`.
The repository head was `64bc7f1cd327943036da92071fbda1c7c1e96eb7`; the
dirty flag truthfully records the verifier and documentation being committed.

## Boundary

This closes the deployment gate for the concrete, open, no-input generic-owner
output family. It confirms that C# authors can override only the natural typed
member and that the compiler-owned semantic ABI survives ReadyToRun, full
trimming, and NativeAOT.

It does not admit broad inputs, abstract semantic obligations, interface
families, or method-generic entries, and it does not authorize a per-owner or
production migration. Those families still require their own hostile
semantic, separate-compilation, C# surface, and deployment evidence before the
atomic owner decision.
