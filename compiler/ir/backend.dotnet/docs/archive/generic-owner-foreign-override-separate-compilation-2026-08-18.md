# Generic-owner foreign override separate compilation (2026-08-18)

## Question

The first foreign-override proof compiled a Kotlin owner and Kotlin override
into one DLL. It did not prove that a Kotlin base DLL -> Kotlin override DLL ->
C# subclass DLL preserves both Kotlin semantic dispatch and the ordinary C#
typed override without making C# implement compiler ABI.

## Verification correction

The first attempted separate-compilation run passed the rehearsal switch as a
JVM system property (`-Dkotlin.dotnet.genericOwnerRehearsal=true`). The Gradle
test task forwards this switch only as a project property (`-P`). That run
therefore exercised the erased production epoch and its green result did not
prove the CLR-generic architecture. The conclusion that ABI 36 needed no new
binding is withdrawn.

The corrected `-P` run exposed two independent producer/consumer gaps:

1. `lib.dll` physically emitted
   `Reader.read(IStoreKotlinSemantic)`, but the ordinary function ABI recorded
   only the owner and MethodDef name. The consumer reconstructed the logical
   `Store<Any?>` parameter as invariant `Store<object>` and rejected the call.
2. Once that parameter was bound correctly, the base last-Kotlin probe saw the
   middle-DLL Kotlin override as foreign. The middle DLL had created neither an
   override of the inherited semantic hook nor an override of the inherited
   probe, so the base dispatcher selected the typed `Middle<int>.read()` and
   unboxed incompatible semantic state too early.

## Physical ABI 37

ABI 37 adds two producer-authoritative facts:

- a supplemental record lists only the parameters/result of an otherwise
  ordinary function that the producer actually emitted through a non-generic
  generic-owner capability; and
- a generic-owner member-family record includes the protected foreign-override
  probe MethodDef when that concrete no-input output family owns one.

The consumer applies the first record only to its named signature slots. It
does not infer capability representation from `Any?`, declaration-site
variance, or a projected-looking type. Exact `C<T>` slots therefore remain
ordinary constructed CLR generics.

For an external semantic override family, the consumer binds detached
prototypes to the producer hook and probe MethodDefs. The local Kotlin hook and
probe then emit as ordinary virtual overrides (no `newslot`). The public typed
method remains the sole natural C# override entry.

## Closed corpus

The oracle produces three actual Kotlin assemblies/consumers:

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

The warnings-as-errors C# consumer overrides only public `read(): string`.
Both its direct typed call and the base-DLL widened Kotlin reader observe the
C# result. The Kotlin executable also writes a `String` through
`Store<Any?>` onto a physical middle-DLL `Store<Int>`. The base-DLL reader
returns that exact object; only a later real `Int` operation throws, and a
compatible widened write restores the state.

FIR PSI and LightTree execute the product on .NET Framework 4.8 and .NET 10:
four tests, zero failures, errors, or skips. Exported IL additionally confirms
that the middle semantic hook and probe reuse their producer virtual slots,
while the typed `read(): !T` remains the normal public method.

## Boundary

This closes the concrete no-input owner-dependent output case. Broad inputs,
method-generic entries, interfaces, and abstract semantic obligations remain
separate proof gates. ReadyToRun, trimming, and NativeAOT must still validate
the allocation-free `ldvirtftn`/`ldftn` comparison before the mechanism can be
selected for the atomic production migration.
