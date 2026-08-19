# Draft ADR: CLR-generic Kotlin interface with declaration-semantic capability

- Status: **Draft — test-only reopening hypothesis**
- Date: 2026-08-19
- Scope: Kotlin-owned generic interfaces, Kotlin runtime identity, projected and
  widened calls, Kotlin and C# implementations, and Framework 4.8 portability

## Context

The accepted production ABI gives every Kotlin-owned generic interface one
non-generic CLR TypeDef. Its strongest implementation argument assumed that an
ordinary Kotlin implementation such as `Values<T> : Source<T>` was itself a
non-generic CLR class and therefore could not truthfully implement
`Source<T>`.

The generic-class-owner rehearsal changes that premise. An admitted
`Values<T>` now has real CLR GenericParams and can name an exact
`Source<T>` edge. The accepted erased-interface ABI remains binding in
production, but its representation must be reassessed before the class-owner
rehearsal treats nested `Set<K>`, `Map<K, V>`, `Sequence<T>`, or user
interfaces as permanently erased carriers.

## Reopening hypothesis

A Kotlin-owned generic interface may become one physical family:

```text
Kotlin Source<out T>
    public natural interface:       Source<out T>
    declaration-semantic capability: SourceSemantic

Source<T> : SourceSemantic
```

The natural interface owns truthful exact CLR calls and the C# surface. The
non-generic capability owns Kotlin operations whose receiver cannot be named
as one honest constructed CLR interface: stars, use-site projections,
value-type variance, unchecked classifier-only views, or broad/unsafe member
domains. It is a Kotlin declaration-semantic domain, not a claim that all
Kotlin execution is erased.

Every Kotlin-produced implementation occupies all required views on the same
object. No adapter, shadow object, duplicate state, or representation-dependent
Kotlin identity is permitted. Exact operations use the natural generic view;
only a route whose Kotlin contract requires the semantic domain crosses the
capability.

This is a general compiler representation rule. `Map`, `Set`, collections,
and `Sequence` may still require Common-owned special member bridges, but they
must not invent separate generic-interface representations.

## Why the semantic capability remains necessary

CLR reference variance cannot represent all legal Kotlin views. In particular,
`Source<int>` is not convertible to `Source<object>` even when `Source` is
covariant, while Kotlin permits `Source<Int>` to be observed as
`Source<Any?>`. Invariant interfaces, stars, projections, and classifier-only
unchecked casts have the same absence of one universal constructed CLR type.

The widened value therefore travels as `SourceSemantic`; it must never be
fabricated as `Source<object>`. KLIB remains authoritative for the logical
construction, variance, projection, nullability, and later typed-use checks.

## Foreign C# implementation boundary

A direct C# implementation naturally supplies only the typed member:

```csharp
public sealed partial class CsSource : Source<string>
{
    public string Read() => "hello";
}
```

Because `Source<T>` requires `SourceSemantic`, a concrete object must also
provide its abstract semantic slots. ECMA-335 requires a concrete implementor
to supply every abstract method of every required interface. A MethodImpl can
select a body but cannot manufacture the object-to-`T` or `T`-to-object
adapter body. A default interface body would supply that adapter on modern
runtimes, but default interface implementations require .NET Core 3.0 or
.NET 5+ and therefore cannot define the Framework 4.8 ABI.

The portable source-authoring rule is producer-recorded automatic bridge
generation. The Kotlin DLL records the natural and semantic MethodDefs in its
versioned C# implementation manifest. The existing Roslyn generator adds the
semantic interface implementation to another partial declaration and forwards
to the user's typed body, including required boxing, unboxing, barriers, and
default-helper selection. The user neither names nor manually implements the
compiler capability.

This is a supported C# source-authoring convenience, not a universal CLR
mechanism. It cannot modify a precompiled type, a non-partial C# type, or a type
owned by another CLR language. Such producers need an explicit implementation
of the complete physical contract or a future language-neutral interop tool.
The compiler must not conceal that limitation with reflection, runtime
proxies, wrappers, or identity-changing adapters.

## Compiler-emitted evidence

The one test-only generic-owner epoch now admits two structural root families.
A public top-level covariant interface with one unbounded owner parameter and
one abstract public no-input member returning that parameter directly publishes
the natural `Source<out T>`, its non-generic semantic capability, and their
complete member family. Exact final substitutions use the natural CLR
interface. Stars, use-site projections, owner parameters, open class arguments,
and widened value-type views use the capability.

A public top-level contravariant interface with one unbounded owner parameter
and one abstract public `consume(T): Unit` member publishes natural
`Consumer<in T>.consume(!T)` plus semantic `void consume(object)`. Exact
implementations and exact local views keep the natural construction. Ordinary
reference conversions such as `Consumer<object> -> Consumer<string>` remain
native CLR contravariance. CLR variance does not apply to value-type generic
constructions, so Kotlin's legal `Consumer<Any?> -> Consumer<Int>` view uses
the capability on that same object. Its `Int` argument is boxed at the
semantic call and the implementation bridge casts or unboxes only when its
natural `T` body requires that carrier.

Declaration selection is provenance-aware. A public or mutable
`Consumer<Int>` boundary may receive a physically different legal Kotlin
construction and therefore uses the capability, while a final local whose
producer graph proves `Consumer<int>` retains the natural route. The general
semantic fallback may not overwrite that stronger exact evidence.

Kotlin implementations and calls preserve exact results, required boxing, and
same-object identity in one product and across a producer/implementation/
consumer compilation chain. A transparent covariant subinterface declared in
the same producer product or in a separately compiled downstream product,
`Child<out T> : Source<T>`, is reified at a fixpoint and reuses the parent
capability. It does not create a second semantic slot or fall back to an erased
child TypeDef. A member-free child which intersects multiple independent
admitted roots instead receives one memberless non-generic capability alias.
The alias inherits every root capability and carries widened child values; it
does not own slots, bodies, state, or a fabricated generic construction. If the
child declares one abstract no-input member returning its own `T`, it receives
a child capability even over one semantic domain. That capability inherits its
parents and owns exactly the new member's object-result slot. Inherited member
families remain owned by the roots. The downstream physical ABI records the
capability TypeDef's producer assembly as well as its owner path; no alias is
inferred from a name. Selecting that downstream library without the recorded
self-describing producer dependency fails before emission.

A reified generic-interface MethodDef is again an ordinary physical slot for
the covariant-return lowering. If an inherited class member returns a narrower
CLR carrier than the substituted natural interface member, the compiler emits
one typed MethodImpl adapter. Equal signatures retain implicit/direct natural
mapping; the semantic bridge does not become the normal typed route.

The actual producer manifest is read back from the emitted DLL and consumed by
the supported Roslyn generator. Partial C# implementations contain only their
natural typed source members, including the child-owned and consumer members.
Each manifest
contract records only the member declared by that interface; inherited root
contracts are composed rather than copied into the child family. Generated
explicit implementations satisfy all semantic capabilities, and Kotlin
widened calls reach every authored C# member on Framework 4.8 and .NET 10. No
default interface method, runtime proxy, reflection dispatch, wrapper, or
declaration-name/stdlib exception participates.

The consumer proof includes `Consumer<object>` and `Consumer<int>` C# source
implementations. The manifest records contravariance and the paired natural/
semantic input signatures; the generator supplies the object-to-natural
adapter without requiring the author to name the capability. This remains
production-inert and deliberately narrow. Only one child-owned member with the
admitted producer-output shape and one independent consumer root are proven.
Broader member surfaces or changed variance require separate complete proofs.

## Remaining gates

Before this draft may replace the erased-interface ADR, one atomic rehearsal
must cover:

1. general member-declaring children beyond one producer-output slot, including
   multiple members, overloads, and deeper inheritance;
2. invariant, mixed, multi-parameter, and input-bearing child/interface
   compositions beyond the admitted one-input consumer root;
3. nullable-value, open-nullable, bounded, and value-class substitutions beyond
   the proven reference and `Int` input routes;
4. broad and `@UnsafeVariance` inputs, delayed typed-use failure, parameterized
   `as`, and classifier-only `as?`;
5. Kotlin and generated C# properties, defaults, generic methods, and hostile
   inheritance;
6. same-object identity and dispatch across deeper separate Kotlin and C#
   assembly graphs;
7. Runtime and Stdlib owners including collection special bridges without a
   collection-specific representation; and
8. exact inverse rollback plus Framework 4.8, .NET 10, trimming, and NativeAOT
   products.

The rehearsal must fail closed by complete interface family. Production may
not switch individual interfaces or expose a mixed erased/generic ABI.

## References

- [ECMA-335](https://docs.ecma-international.org/ecma-335/Ecma-335-part-i-iv.pdf),
  interface implementation and MethodImpl rules
- [Microsoft runtime feature requirements](https://learn.microsoft.com/dotnet/csharp/misc/cs1617),
  including the .NET Core 3.0 / .NET 5+ minimum for default interface
  implementations
- [C# interface implementation specification](https://learn.microsoft.com/dotnet/csharp/language-reference/language-specification/interfaces)
