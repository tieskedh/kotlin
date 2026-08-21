# Reified inherited owner-relative implementation checkpoint (2026-08-21)

## Question

Can an ordinary local non-generic base method `<R : String>(R): String` become
the implementation of `Producer<String>` only when a derived class declares
that interface, while widened Kotlin calls preserve the actual method `R` and
an ordinary C# grandchild overrides only the inherited public method?

## Fail-first evidence

The existing direct-owner rule rejected the fake-override shape because the
implementation body and interface binding belonged to different classes.
Opening that boundary exposed three further composition failures:

1. The base method had no override root of its own, so physical-family naming
   initially received an illegal empty root set. The newly binding interface
   slot is now the deterministic fallback root.
2. A second derived binding owner observed the already registered private base
   capability dispatcher and called it directly. Both CLRs correctly rejected
   that access with `MethodAccessException`.
3. After the first binding reopened public method `R` from `String` to
   unconstrained, the second binding rechecked the mutated IR and rejected the
   family it should have reused.

These were family-composition defects, not evidence that inherited Kotlin
bodies require objectification or copying.

## Selected representation

The one real body owner receives the open family once:

```text
Base
  public virtual string produce<R>(R)
  protected virtual object semantic<R>(R)
  protected virtual bool probe<R>()
  private final object classCapability<R>(R)
```

Each class which first binds that method to the reified interface receives
only its own explicit slots:

```text
OpenDerived : Base, Producer<string>
  private final string natural<R>(R)
  private final object interfaceCapability<R>(R)

FinalDerived : Base, Producer<string>
  private final string natural<R>(R)
  private final object interfaceCapability<R>(R)
```

Both interface dispatchers reference the protected base hook/probe family
directly. They never call the base's private class-capability dispatcher. The
family record retains the original instantiated `R : String` proof before the
public method is reopened, so later local binding owners validate against the
same evidence instead of mutated IR.

A separately compiled C# class derives from `OpenDerived` and overrides only
`public string produce<R>(R)`. Direct C#, natural-interface, and widened Kotlin
calls all reach that override with `R=Int32`; the base implementation also
returns the raw integer on a widened call. Reflection proves the public source
method remains declared on `Base`, unconstrained, virtual, and non-final. The
final sibling proves one family can serve multiple binding owners without body,
hook, probe, or public-entry duplication.

## Closed corpus

PSI and LightTree execute the candidate, explicit epoch-off inverse, and
property-absent inverse on .NET Framework 4.8 and .NET 10: twelve lanes, zero
failures, errors, or skips.

The full `dotNetTest` aggregate exits zero. Direct XML audit covers 190 suites
and 2,287 tests with zero failures, errors, or skips: 187 FIR suites with 2,155
tests, two integration suites with 126 tests, and one model suite with six
tests. The aggregate regenerated the integration root and retained the
already-green FIR and model roots as up-to-date. The feature test is present in
all four PSI/LightTree by Framework/.NET box suites.

## Boundary

The body owner, both binding owners, and their interface relationship are all
compiled in one module and are non-generic. The proof covers one method type
parameter, one input, one direct owner-relative bound, one reified interface,
and open/final binding owners. A base from an earlier artifact is still closed,
whether it is unprepared or already publishes a candidate class-owned family.
Generic bases or binding owners, overloads, multiple parameters or method type
parameters, nested/multiple bounds, and mixed member families require separate
proofs.
