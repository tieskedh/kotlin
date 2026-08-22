# Reified generic-interface precompiled exact input

Date: 2026-08-22

## Question

Can the first exact-input generic-interface family expose a truthful typed C#
surface and accept an ordinary precompiled or non-partial C# implementation
without requiring that foreign source to know the invariant exact sibling or
the non-generic Kotlin semantic capability?

The hostile control deliberately places two overloads on the raw C# class:
`acceptsAll(ExactInputFamily<string>)` and `acceptsAll(object)`. Kotlin must
select the exact typed overload. A second raw class supplies every natural
interface member but omits `acceptsAll`; Kotlin must fail closed rather than
inventing a semantic body.

## Physical presentation

The source member on a Kotlin implementation is now the sole natural C# entry:

```text
public bool acceptsAll(ExactInputFamily<T>)
```

Its paired Kotlin semantic hook remains a distinct compiler-ABI member:

```text
protected bool acceptsAll__KotlinSemantic(object)
```

Later value-routing may not degrade the producer-planned typed source
signature. The same rule is reconstructed from producer ABI by a separate
consumer, including every parameter position rather than only constructed
results. The implementation keeps its producer-proven `!T` field; this feature
does not introduce object storage or a shadow field.

For a semantic prototype, a nested application of the variant interface owner
uses the object carrier directly. Constructing `Family<object>` would be a
false physical claim: a Kotlin-wide value may instead be one capability-bearing
implementation or an ordinary foreign `Family<T>` with no such CLR
construction.

## Ordinary foreign implementation

A separately compiled, sealed, non-partial C# class declares only the natural
covariant interface and ordinary public typed members. No source generator is
run over that assembly, and it names neither `__KotlinExact` nor
`KotlinSemantic`.

Kotlin semantic dispatch keeps the compiler capability as its fast path. For
the bounded exact one-input Boolean member, the foreign path:

1. selects exactly one closed natural interface construction;
2. resolves the concrete public method with that exact constructed parameter
   type;
3. caches the resulting `MethodInfo` independently from interface-member
   cache entries; and
4. invokes it on the original object without a wrapper or identity change.

The exact signature lookup prevents an adjacent `object` overload from being
selected. A missing concrete method raises `MissingMethodException`. An
arbitrary incompatible widened nested input is not attributed to a raw C# body
which cannot accept it; only a real Kotlin/generated semantic capability owns
that behavior.

The inverse argument direction is part of the same proof. C# calls the public
typed member on a Kotlin `ExactInputValue<string>` with a raw precompiled
`ExactInputFamily<string>` argument. When the moved Kotlin semantic body calls
that argument's producer, it retains the capability fast path but uses unique-
natural foreign dispatch for the capability-less raw value. A public typed
entry therefore does not hide a generated-capability obligation on its
arguments.

The same fallback now accepts any natural value result, so the raw family's
constructed `ExactInputCursor<string>` producer is also callable through a
separate Kotlin library. Boolean capability results are boxed before joining
the runtime helper's object-result branch.

## ABI and verification

ABI/runtime surface 46 owns the additional compiler-runtime dispatch helper.
The exact-input proof executes through PSI and LightTree on Framework 4.8 and
.NET 10. The pre-existing foreign-subclass rehearsal remains green on both
profiles, proving that an ordinary C# subclass still overrides only the public
typed entry while Kotlin semantic dispatch observes it.

The final complete `dotNetTest` aggregate exits zero. Direct XML audit reports
191 suites and 2,293 tests with zero failures, errors, or skips: 187 FIR suites/
2,159 tests, two integration suites/127 tests, one backend resolver test, and
the unchanged six-test `dotnet.ir` model root.

## Boundary and next gate

The natural covariant interface still cannot legally declare the exact input
member; the invariant exact sibling remains compiler ABI. Raw C# interoperability
therefore follows the ordinary typed method convention on the concrete class,
not a false claim that the natural interface owns that slot. Defaults,
overloads beyond the exact-signature control, arbitrary semantic bodies, and
multi-input families remain closed.

Runtime/Stdlib collection mappings remain gated. The next general gate is
property composition and the remaining member grammar needed by
`Collection<T>` and `Set<T>`, without a library, package, or declaration-name
special case.
