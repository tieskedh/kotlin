# Prepared external inherited owner-relative implementation checkpoint (2026-08-21)

## Question

Can a later Kotlin artifact add `Producer<String>` to a class which inherits
`<R : String>(R): String` from an external base, when an earlier producer
artifact has already prepared that base-owned semantic family, without copying
the body or exposing compiler ABI to an ordinary C# subclass?

## Fail-first evidence

The local inherited proof deliberately rejected the external base. Opening the
producer-prepared case exposed two independent failures before emission:

1. Publishing the non-generic semantic capability copied method `R : T` after
   owner `T` had ceased to be in scope. KLIB ABI mangling rejected the detached
   type parameter. Erasing an owner-relative constraint now removes only that
   owner-dependent bound, retains all independent bounds, and uses `Any?` when
   none remain.
2. A consumer initially tried to reuse the deserialized typed declaration
   itself. That changed how unrelated closed families mapped `String` and
   demonstrated that external logical IR is not a safe place to encode a new
   physical view.

The selected fix leaves producer KLIB and every external declaration
unchanged. It reconstructs exact physical references from records the producer
already published.

## Selected representation

The `lib` artifact owns the entire family once:

```text
PreparedBase
  public virtual string produce<R>(R)
  protected virtual object semantic<R>(R)
  protected virtual bool probe<R>()
  private final object classCapability<R>(R)
  Kotlin body and private dispatcher
```

A local publisher in `lib` first binds that family to `Producer<string>`, which
causes the public Function identity and semantic family to be recorded. The
later `middle` artifact declares open and final binding owners over the same
external base. For each consumer-side lowering, un-emitted typed, semantic,
and probe prototypes point to the producer's assembly-qualified MethodDefs.
Only the middle classes' natural and interface-capability MethodImpls are new.

The consumer therefore does not:

- move or copy the external Kotlin body;
- add a MethodDef to the external base;
- infer a compiler-generated method name;
- copy the producer's hook, probe, capability, or state; or
- call the producer-private class-capability dispatcher.

Two binding siblings reuse the same producer family. An ordinary separately
compiled C# grandchild derives from the open middle binding and overrides only
the inherited public generic method. Direct C#, natural-interface, and widened
Kotlin calls all reach that override with the actual `R`. Reflection proves
the public method remains declared on `PreparedBase`, public, virtual,
non-final, unconstrained, and `string`-returning.

The direct foreign-override emitter resolves local members from emitted
MethodDefs and producer members from exact MethodRefs. It compares their
physical CLR owner tokens rather than resolver-local class-info object
identity; both may describe the same external TypeDef.

## Closed corpus

PSI and LightTree execute the candidate, explicit epoch-off inverse, and
property-absent inverse on .NET Framework 4.8 and .NET 10: twelve lanes, zero
failures, errors, or skips.

The full `dotNetTest` aggregate exits zero. Direct XML audit covers 190 suites
and 2,287 tests with zero failures, errors, or skips: 187 FIR suites with 2,155
tests and two integration suites with 126 tests were freshly written; the
unchanged `dotnet.ir` model root retained its six green tests as up-to-date.
The feature test appears in all four PSI/LightTree by Framework/.NET box
suites.

## Boundary

The external base must be non-generic, open, and already publish the exact
typed Function plus open semantic hook/probe family. The new binding owner is
non-generic and open or final. The proof covers one method type parameter, one
input, one direct owner-relative bound, one reified interface, and two
consumer-side binding owners. An unprepared external base fails closed.
Generic bases or binding owners, overloads, multiple method/value parameters,
nested or multiple owner-relative bounds, and mixed member families require
separate proofs.
