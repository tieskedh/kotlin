# Natural CLR-language surface for generic owners

- Status: **Architecture design artifact — not an accepted export ABI**
- Programme:
  [`generic-class-owner-reopening.md`](generic-class-owner-reopening.md)
- Class candidate:
  [`../decisions/draft-adr-reified-generic-class-owner.md`](../decisions/draft-adr-reified-generic-class-owner.md)
- Interface candidate:
  [`../decisions/draft-adr-reified-generic-interface-owner.md`](../decisions/draft-adr-reified-generic-interface-owner.md)
- CLR-language authoring contract:
  [`../decisions/adr-csharp-interface-source-authoring.md`](../decisions/adr-csharp-interface-source-authoring.md)
- Optional export conveniences:
  [`../decisions/draft-adr-explicit-csharp-export-surface.md`](../decisions/draft-adr-explicit-csharp-export-surface.md)

This artifact states the host-language surface which the generic-owner
rehearsal must prove. Git and the dated archive own the earlier split-surface,
protected-hook, generator, and performance experiments. Those experiments are
evidence, not additional authoring rules.

## Boundary

Three cases are deliberately separate.

1. An existing CLR library keeps its native TypeDefs, MethodDefs, variance,
   constraints, and MethodImpls. Kotlin does not retrofit Kotlin-owned ABI.
2. A CLR language implementing a Kotlin-owned interface satisfies one complete,
   statically checkable natural CLR interface.
3. C# export may add names, overloads, delegates, properties, or an explicit
   adapter. It does not replace a truthful natural owner.

The ordinary contract must work equally for C#, F#, VB, Reflection.Emit, and
hand-authored valid IL. A C#-only source generator therefore cannot define who
implements a Kotlin interface.

## Required ordinary surface

Where a Kotlin-owned declaration is admitted to the candidate ABI, its normal
host view is an ordinary CLR generic owner:

```kotlin
open class Cell<T>(initial: T) {
    open var value: T = initial
    open fun write(next: T) { value = next }
}
```

```csharp
public class Cell<T>
{
    public Cell(T initial);
    public virtual T Value { get; set; }
    public virtual void Write(T next);
}
```

The C# object is the Kotlin object. There is one receiver identity and one
authoritative state. C# does not construct an erased twin, wrapper, proxy, or
shadow store merely to use the natural members.

For a Kotlin-owned interface, every member that a foreign implementation must
provide appears as a real obligation on its one natural interface. If an input
member makes CLR declaration-site covariance illegal, the affected physical
parameter becomes invariant; the member is not moved to a hidden exact sibling.
Kotlin logical variance remains in KLIB and is handled by Kotlin call routing.

Consequently, this must be a complete implementation when the declaration is
admitted:

```csharp
public sealed class Items : Kotlin.Collections.Collection<string>
{
    public int Count { get; }
    public Kotlin.Collections.Iterator<string> Iterator();
    public bool Contains(string candidate);
    public bool ContainsAll(Kotlin.Collections.Collection<string> candidates);
}
```

Omitting a required member is a host-compiler or verifier error. It is not
discovered later by a Kotlin runtime lookup for a similarly named public method.

## Compiler-owned semantic routing

Kotlin may use the same object through a star, projection, declaration-variant
widening, or another view which has no truthful constructed CLR spelling. The
Kotlin compiler owns that routing.

It may derive behavior from:

- the actual constructed natural interface and its MethodDefs;
- retained or producer-recorded MethodImpl and inheritance rows;
- producer-recorded Kotlin special-bridge policy;
- an inherited concrete Kotlin body; and
- an authority-recorded semantic capability on a Kotlin-produced object.

Typical derived routes are:

```text
compatible widened input
    -> convert to the actual construction's parameter carrier
    -> call the real interface/virtual MethodDef

known Common wrong-shape candidate
    -> return the recorded Common result

exact output from a widened receiver
    -> call the actual construction's output MethodDef
    -> widen only the result
```

The compiler must not replace those rules with a concrete-method name/arity
probe. Explicit interface implementations, overloads, inheritance, and
separate compilation make such a convention neither complete nor statically
honest.

A compiler-private capability may remain on Kotlin-produced objects where the
CLR cannot name a Kotlin view. It is an implementation route, not a second
implementation obligation. A foreign object which implements the complete
natural interface is admitted without implementing that capability.

## The hard non-derivable boundary

Some logical operations admit a value outside the exact construction's CLR
parameter domain while defining no Common wrong-shape result and providing no
concrete inherited body. A normal foreign implementation of `M<T>` cannot
invent the behavior of `M<int>` for an arbitrary `object` merely by implementing
`M(int)`.

That gap is not repaired by secretly requiring:

- a protected raw semantic hook;
- a generated compiler interface;
- a `partial` declaration;
- a specially named public method; or
- reflection-based structural dispatch.

The declaration or operation must instead do one of the following:

1. remain outside the natural generic-owner admission grammar;
2. expose a visible, statically checkable adapter contract selected explicitly
   by the user; or
3. produce a precise unsupported-interop diagnostic.

An explicit adapter may be generated as authoring convenience, but it remains
an explicit semantic boundary. Generated code may not silently upgrade an
otherwise incomplete type into an implementation of the natural Kotlin
interface.

## Overrides and inheritance

An ordinary CLR subclass overrides only the natural surface. Kotlin exact and
compiler-derived widened calls must observe that override whenever the widened
operation can be expressed by a compatible argument, a recorded Common policy,
or another mechanically derivable route.

If a Kotlin semantic call would require an independent raw-object result or
input behavior which cannot be derived from the natural override, the compiler
must not silently dispatch to an unrelated base hook. The owner is constrained,
explicitly adapted, or unadmitted for that interop shape.

This requirement applies through:

- multiple Kotlin and CLR inheritance levels;
- explicit MethodImpls and reabstraction;
- defaults, diamonds, and direct `super` calls;
- separately compiled producer and consumer assemblies; and
- reference, value, nullable-value, and value-class substitutions.

## Defaults and optional tooling

Kotlin defaults do not automatically become C# optional parameters. CLR
default-interface support also differs by target profile. An analyzer or source
generator may:

- emit ordinary source members which forward to producer-recorded default
  helpers;
- provide diagnostics and IntelliSense guidance;
- generate an explicitly requested adapter or export facade.

It may not:

- be required for interface admission;
- implement hidden semantic ABI needed for correctness;
- implement compiler-private semantic ABI merely as an optimization;
- change object identity or authoritative state; or
- make Framework 4.8, F#, VB, or valid IL observe a weaker contract.

## Nullability, arrays, and variance

Kotlin nullability remains logical authority. Roslyn nullable metadata is an
additive host annotation, not a replacement type system. A direct open `T?`
result may use the producer-recorded split-nullable convention `T + out bool`
when that layout is admitted; it must not be boxed merely for C# symmetry.

Raw `System.Array` is not automatically Kotlin `Array<*>`: every foreign entry
must prove an SZ array, and bounded projected arrays must also validate their
element domain.

Imported CLR variance remains native and reference-only physically. Kotlin may
logically widen more cases, but implicit physical conversion of `IOut<int>` to
`IOut<object>` is rejected rather than repaired by boxing or fabricated
construction.

## Direct surface versus explicit export

| Need | Natural owner | Explicit export/adapter |
| --- | --- | --- |
| Construct, call, implement, or subclass truthful `C<T>` | primary path | must not add a twin |
| Curated names and overloads | ordinary names where unambiguous | additive |
| Kotlin default convenience | ordinary/default helper contract | generated overloads or forwarders |
| Delegate, Task, or BCL projection | only when already the real type | explicit conversion surface |
| CLR-unnameable Kotlin semantics | compiler-derived route where possible | visible adapter when not derivable |

The goal is not “no adapters anywhere.” It is no adapter where actual CLR
metadata already expresses the complete contract, and no hidden adapter where
it does not.

## Required hostile proofs

Before promotion, the rehearsal must prove at least:

- a complete non-partial C#, F#, or IL implementation with no generated ABI;
- explicit-interface and overload-hostile implementations routed by MethodDef,
  not public names;
- exact and widened reference/value calls on the same object;
- known wrong-shape and general non-derivable broad inputs;
- C# overrides observed from exact and mechanically derivable semantic calls;
- defaults, reabstraction, diamonds, and multi-level Kotlin/C# inheritance;
- separate producer/consumer assemblies and stale-manifest rejection;
- Framework 4.8 and .NET 10 JIT, ReadyToRun, trimming, and NativeAOT; and
- Kotlin reflection showing one logical declaration while raw CLR reflection
  reports only truthful physical infrastructure.

Failure of a hard case constrains admission or selects explicit adaptation. It
does not authorize a hidden foreign-author obligation.
