# Reified closed owner-relative implementation checkpoint (2026-08-21)

## Question

Can a final non-generic Kotlin class implement the admitted abstract
`<R : @UnsafeVariance T>(R): T` interface while retaining a closed typed C#
entry and allowing a legal Kotlin-widened view to call the body with an `R`
which is not the class's physical owner argument?

## Fail-first evidence

The first closed implementation exposed four distinct composition failures:

1. The existing interface bridge adapted `R` to the class's closed `String`
   parameter before calling the body, so a widened call with `Int` failed at
   the bridge rather than preserving the actual method construction.
2. Moving the body to an unconstrained semantic method fixed that input but
   left the natural `I<String>` slot without an implementation, and the CLR
   rejected the class at load time.
3. Binding the natural slot through the typed wrapper reintroduced the same
   early `R -> String` cast. Binding it to the semantic body avoided that cast,
   but an ordinary override origin still did not emit the required closed
   `I<String>` MethodImpl row.
4. Once the closed MethodImpl was emitted, diff review found that the semantic
   cache and MethodImpl cache had accidentally been given the same lifetime.
   One Kotlin method implementing two roots would have received only the first
   natural MethodImpl.

These failures locate the problem after logical override selection: the body
and actual method `R` were available, but the closed class entry, semantic
entry, and physical interface slots needed different adapters.

## Selected representation

For the deliberately narrow final, local, non-generic class shape, the compiler
moves the one Kotlin body to a private final semantic twin:

```text
public closed-T produce<R>(closed-T)       direct class/C# entry
private object semanticBody<R>(R)          one authoritative Kotlin body
private closed-T naturalMethodImpl<R>(R)   .override I<closed-T>
private object semanticMethodImpl<R>(R)    semantic capability
```

Every adapter forwards the same runtime method `R`. The public wrapper and
natural MethodImpl adapt only the result to the closed owner result. The
semantic capability returns the body result as `object`, so a legal
`Producer<String> -> Producer<Any?>` Kotlin view can call the same object with
`R = Int` and receive that `Int` without an early cast or a wrapper object.

The body cache is keyed by the Kotlin implementation method. Natural bridges
are independently deduplicated by physical interface slot. A single Kotlin
override of two admitted roots therefore owns one body, two natural
MethodImpls, and two semantic capability MethodImpls.

## Closed corpus

The separate `lib` -> `middle` -> C# consumer corpus covers:

- a closed `String` implementation, its exact typed interface call, widened
  `Int` call, receiver identity, public class signature, and both interface
  maps;
- a closed `Int` implementation, proving scalar exact calls and boxed widened
  reference results;
- a closed nullable `String` implementation, proving exact null and widened
  value results; and
- one method implementing two independent reified roots, proving both natural
  and both semantic slots reach the single body.

PSI and LightTree execute the candidate, explicit epoch-off inverse, and
property-absent inverse on .NET Framework 4.8 and .NET 10: twelve lanes, zero
failures, errors, or skips. The final normal production aggregate directly
audits 190 XML suites and 2,287 tests: 187 FIR suites/2,155 tests, two
integration suites/126 tests, and the unchanged six-test `dotnet.ir` root,
with zero failures, errors, or skips.

## Boundary

This closes one locally declared final non-generic implementation with one
method parameter and one direct owner-relative bound. It does not admit open
non-generic implementations, inherited bodies, overloads, multiple method
parameters or method type parameters, nested/multiple relative bounds, or
mixed member families. An open implementation needs a protected semantic
override family and ordinary C# subclass proof; it must not be obtained by
making this private twin virtual or by degrading the natural interface to
`object`.
