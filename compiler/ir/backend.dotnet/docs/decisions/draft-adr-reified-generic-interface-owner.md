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

## First executable evidence

The bounded reopening proof constructs `Source<out T> : SourceSemantic` and a
versioned authoring manifest, then compiles C# reference- and value-substituted
implementors which contain only `Read(): T`. The existing source generator
adds `ReadSemantic(): object`. Direct typed calls and semantic calls return the
same reference or the correctly boxed value, and both views retain the exact
same object identity on Framework 4.8 and .NET 10.

This closes only the previously open foreign direct-source implementation
mechanism. The proof is synthetic and production-inert: the Kotlin emitter
does not yet publish the interface family or its manifest record.

## Remaining gates

Before this draft may replace the erased-interface ADR, one atomic rehearsal
must cover:

1. compiler-emitted natural and semantic interface TypeDefs and MethodDefs;
2. invariant, `in`, `out`, mixed, star, and use-site-projected interfaces;
3. reference, value, nullable-value, open-nullable, bounded, and value-class
   substitutions;
4. broad and `@UnsafeVariance` inputs, delayed typed-use failure, parameterized
   `as`, and classifier-only `as?`;
5. Kotlin and generated C# implementations, inheritance, intersections,
   properties, defaults, and generic methods;
6. same-object identity and dispatch across separate Kotlin and C# assemblies;
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
