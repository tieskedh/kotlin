# Generic-owner foreign override dispatch (2026-08-18)

## Decision

The test-only CLR-generic-owner rehearsal now preserves an ordinary C# typed
override when Kotlin invokes a concrete no-input owner-dependent output through
the non-generic semantic capability. C# overrides only the natural source
member. It does not implement the protected semantic hook or another bridge.

Production generic owners remain erased.

## Hostile source shape

The oracle uses one covariant mutable owner whose state must remain `object`:

```kotlin
open class Store<out T>(initial: T) {
    private var value: T = initial

    open fun read(): T = value

    fun write(value: @UnsafeVariance T) {
        this.value = value
    }
}

fun widenedRead(store: Store<Any?>): Any? = store.read()
```

A separately compiled C# class supplies only:

```csharp
sealed class CsStore : Store<string> {
    public override string read() => "csharp-override";
}
```

Before this change, the direct C# call returned `"csharp-override"` while
`widenedRead(new CsStore())` returned the Kotlin base value. The private
capability dispatcher called the separately virtual semantic hook and thereby
bypassed the typed C# slot.

## Physical dispatch

Every admitted concrete open no-input strict output with a semantic hook now
receives one protected virtual compiler probe. Its stable name uses the same
complete logical override-root digest as the semantic/capability family. A
Kotlin override overrides both its typed entry and this probe.

The probe is allocation-free:

```text
ldarg.0
ldvirtftn exact Kotlin typed MethodDef
ldftn     exact Kotlin typed MethodDef
ceq
not
```

Thus it returns true only when a subclass later than that Kotlin declaration
overrode the typed slot. Virtual invocation of the probe first selects the
most-derived Kotlin declaration, so this remains correct for:

```text
Kotlin base -> C# subclass
Kotlin base -> Kotlin override -> C# subclass
```

The capability dispatcher calls the natural typed virtual and boxes/widens its
result when the probe returns true. Otherwise it calls the raw semantic hook.
No reflection cache, wrapper, copied field, exception retry, or C#-authored
compiler member is involved.

## Semantic negative proof

The typed route cannot be used unconditionally. The oracle writes a `String`
through `Store<Any?>` onto a physical `Store<Int>`. Its widened read must return
that exact string. Only a later actual `Int` operation may fail, after which a
compatible widened write restores the same state. The unchanged-probe path
therefore retains the semantic hook and preserves this sequence on both the
erased production epoch and the CLR-generic rehearsal.

Broad inputs, methods with explicit parameters, abstract semantic obligations,
interfaces, final/private members, and method-generic entries do not receive
this probe.

## Evidence

The actual Kotlin-emitted rehearsal DLL is consumed by warnings-as-errors C#.
Both the direct typed call and Kotlin widened call observe the C# override,
including after an intervening Kotlin override. The same test executes the raw
incompatible write/read, typed-use failure, and compatible recovery sequence.

FIR PSI and LightTree run this product on .NET 10 and .NET Framework 4.8. The
emitted IL additionally confirms that the base probe is `newslot virtual`, the
Kotlin override reuses that slot, the capability dispatcher is private/final,
and only open non-private output families receive a probe.

## Remaining work

The follow-up three-assembly proof establishes that Physical ABI 36 does not
need to serialize this probe. A separately compiled Kotlin override emits its
own local probe and private dispatcher. Raw widened state therefore retains
the semantic path, while a subsequent C# subclass changes the typed target
observed by that local probe. ReadyToRun, trimming, and NativeAOT must still
validate the managed-function-pointer comparison before it is selected for
the atomic production migration.
