# Reified open owner-relative implementation checkpoint (2026-08-21)

## Question

Can a locally declared open non-generic Kotlin class implement the admitted
`<R : @UnsafeVariance T>(R): T` interface so that a legal Kotlin-widened call
preserves its actual `R`, while an ordinary C# subclass overrides only the
natural public generic method and remains authoritative for semantic dispatch?

## Fail-first evidence

The first open implementation exposed three separate composition boundaries:

1. The final-class private semantic twin could preserve widened method `R`, but
   could not participate in a virtual override family or represent a later C#
   typed override.
2. Making only that twin protected and virtual was insufficient. An ordinary
   C# subclass overrides the public typed MethodDef, not a separate hidden
   MethodDef, so semantic dispatch could still bypass the C# body.
3. Reusing the generic interface producer's capability for the open class left
   no local capability MethodDef for the class's member-family ABI. The library
   collector correctly rejected a family whose capability slot belonged to a
   different producer assembly.

The failure was therefore not in owner-relative analysis. It was the physical
composition of one public C# slot, one Kotlin semantic override family, and two
separate-compilation capability owners.

## Selected representation

For the deliberately narrow local open class shape, the compiler emits:

```text
public virtual string produce<R>(R)       ordinary Kotlin/C# typed entry
protected virtual object semantic<R>(R)  one authoritative Kotlin body
protected virtual bool probe<R>()         detects a later C# typed override
private final string natural<R>(R)        .override I<string>
private final object interfaceCap<R>(R)   interface-owned semantic slot
private final object classCap<R>(R)       class-owned semantic slot
```

The public entry and both capabilities retain the actual method `R`; no path
substitutes the closed owner argument for it. The natural MethodImpl calls the
public virtual entry. Each semantic dispatcher compares the most-derived probe
with the Kotlin typed slot. If C# has overridden that public method, the
dispatcher calls it and converts its typed result to `object`. Otherwise it
calls the raw protected semantic hook, allowing a legal widened Kotlin view to
receive a result outside the closed public result type.

The class-owned capability is a compiler ABI for later Kotlin consumers. It is
not a second Kotlin object, wrapper, state carrier, or C# authoring obligation.
The class also implements the interface producer's capability independently;
the two explicit MethodImpls avoid assuming that a same-signature method on a
new CLR interface implements an inherited or unrelated interface MethodDef.

The prior final-class representation remains unchanged: its public parameter is
still the closed owner type, its semantic twin is private final, and it does not
publish the open class capability or probe.

## Closed corpus

The existing separate `lib` -> `middle` -> C# consumer corpus now also covers:

- an open Kotlin `Producer<String>` implementation and exact natural call;
- a widened Kotlin reader using `R=Int`, proving the base semantic hook keeps
  the actual method construction and receiver identity;
- an ordinary sealed C# subclass overriding only `public string produce<R>(R)`;
- direct, natural-interface, and Kotlin-widened calls reaching that same C#
  override with `R=Int`; and
- reflection proving the Kotlin class entry is public, virtual, non-final,
  method-generic, unconstrained, `R`-parameterized, and `String`-returning.

PSI and LightTree execute the candidate, explicit epoch-off inverse, and
property-absent inverse on .NET Framework 4.8 and .NET 10: twelve lanes, zero
failures, errors, or skips.

The full `dotNetTest` aggregate exits zero. Direct XML audit reports 190 suites
and 2,287 tests with zero failures, errors, or skips: six model tests, 2,155 FIR
tests, and 126 integration tests. The aggregate regenerated the integration
root and retained the already-green model and FIR roots as up-to-date. The
feature test is explicitly present in all four PSI/LightTree by Framework/.NET
box suites.

## Boundary

This closes one locally declared open non-generic implementation with one
method parameter, one method type parameter, and one direct owner-relative
bound. It does not admit an inherited non-generic body, overloads, multiple
method parameters or method type parameters, nested/multiple or nullable
relative bounds, mixed member families, or a generic class shape which bypasses
the existing generic-owner planner. Those require their own complete family and
separate-compilation proofs; they must not be approximated by objectifying the
public natural method or requiring C# to override compiler ABI.
