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

Kotlin/generated implementation: Source<T> + SourceSemantic
ordinary foreign implementation:  Source<T>
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

For a Kotlin-produced implementation the widened value therefore travels on
the `SourceSemantic` fast path; it must never be fabricated as
`Source<object>`. A broad public boundary which can also receive ordinary CLR
implementations uses `object` as its physical carrier and selects the semantic
capability at the call. KLIB remains authoritative for the logical
construction, variance, projection, nullability, and later typed-use checks.

## Foreign C# implementation boundary

A direct C# implementation naturally supplies only the typed member:

```csharp
public sealed class CsSource : Source<string>
{
    public string Read() => "hello";
}
```

The natural `Source<T>` and the optional semantic capability are siblings.
Making the capability a base interface would turn a Kotlin compiler detail
into a mandatory CLR implementation contract and would make the ordinary class
above fail C# compilation. Kotlin-emitted implementations name both siblings
on the same object. The producer-recorded Roslyn generator may do the same for
a partial C# type as an allocation-free fast path, but that tooling is an
optimization rather than the admission requirement.

At a broad producer call the compiler uses two-level dispatch:

1. if the object implements `SourceSemantic`, call that slot directly;
2. otherwise inspect its CLR interfaces for the natural open `Source<>` and
   invoke the member only when exactly one closed construction exists.

The successful structural resolution is cached per runtime type, open
interface, and member. Invocation occurs outside the cache lock. Reflection's
`TargetInvocationException` is never observable: the original member
exception is rethrown with its dispatch information. A value with no matching
construction fails as an invalid cast. A value implementing two distinct
`Source<T>` constructions is ambiguous at a star/projected semantic call and
fails deterministically; interface enumeration order is never semantic
evidence. Exact CLR calls to either construction remain ordinary typed calls.

Classifier operations deliberately have a different admission rule from a
member call. Kotlin `is Source<*>` and parameterized `as? Source<String>` test
the declaration classifier and ignore the constructed CLR arguments. They
therefore succeed when the object has the capability or any closed natural
`Source<T>` construction, including more than one construction. A successful
safe cast returns the original object on the broad `object` carrier. It does
not select one construction, create an adapter, or fabricate
`Source<string>`. A following member call still requires the capability or one
unique natural construction, and a following concrete `String` use checks the
member result at that typed-use boundary.

This fallback changes no object identity and creates no proxy, wrapper, or
third public canonical type. It is currently admitted only for the structural
no-input covariant producer family, where the required call is derivable from
the open interface and declared member. Input-bearing, invariant, overloaded,
defaulted, and otherwise non-derivable foreign implementations remain gates.
Trimming and NativeAOT also remain separate gates: runtime interface metadata
and reflective invocation must not be assumed retained merely because both JIT
profiles execute the fallback.

## Compiler-emitted evidence

The one test-only generic-owner epoch now admits two structural root families.
A public top-level covariant interface with one unbounded owner parameter and
one abstract public no-input member returning that parameter directly publishes
the natural `Source<out T>`, its non-generic semantic capability, and their
complete member family. Exact final substitutions use the natural CLR
interface. Stars, use-site projections, owner parameters, open class arguments,
and widened value-type views use a broad object carrier; Kotlin/generated
objects take the capability fast path from that carrier.

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
Each manifest contract records only the member declared by that interface;
inherited root contracts are composed rather than copied into the child family.
Generated explicit implementations satisfy all semantic capabilities, and
Kotlin widened calls reach every authored C# member on Framework 4.8 and
.NET 10.

The covariant producer proof also compiles a separate ordinary C# DLL without
the authoring generator. Its non-partial `Source<int>` implementation has only
the natural `int` member. Kotlin exact and broad calls, a real `Source<*>`
field, and `===` all retain the original object. Repeated calls exercise the
cached foreign path. A throwing implementation exposes its original exception,
not reflection's wrapper, and an object implementing both `Source<int>` and
`Source<string>` is rejected at the broad call as ambiguous. No default
interface method, runtime proxy, identity-changing wrapper, or declaration-
name/stdlib exception participates.

The same precompiled product now proves classifier-erased `is`, `!is`,
nullable `is`, smart-cast use, and parameterized `as?`. An ordinary
`Source<int>` passes `as? Source<String>` and preserves identity; its later
`String` result use throws `InvalidCastException`. An ordinary capability-free
multi-construction object passes the classifier and safe cast because the
declaration classifier is present, while its member call remains
deterministically ambiguous. The compiler caches the runtime type's interface
vector once and uses no constructed-generic `isinst` for these Kotlin
operations.

A classifier-derived view may also cross a separately compiled callable
result without becoming a false natural construction. For example,
`safeView(Any?): Source<String>? = value as? Source<String>` keeps the logical
KLIB result but publishes CLR `object`, because a successful call may return a
plain foreign `Source<int>`. The producer records that physical selection in
ABI 39. The existing generic-owner function-carrier record now identifies
`SEMANTIC_CAPABILITY` and `OBJECT` independently for every selected result or
parameter slot. A consumer obeys that producer record and propagates object
provenance through aliases and FIR's safe-call temporary before routing a
member call. It does not infer the carrier from the exact-looking logical type.

This rule is producer-evidence based and does not erase an ordinary sibling
such as `exactView(Source<String>): Source<String>`: that API retains natural
`Source<string>` input and result signatures. The current proof admits a
single authoritative classifier-derived return expression. Mixed or otherwise
unproven control flow fails closed rather than publishing object by type alone.
The separate Kotlin consumer and a C# reflection oracle prove both signatures,
same-object return, one actual foreign member invocation, and the later
`String` result check on Framework 4.8 and .NET 10 with both FIR parsers.

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
4. broad and `@UnsafeVariance` inputs, parameterized throwing `as`, mixed-
   control-flow classifier returns, and classifier-derived fields and input
   parameters crossing separately compiled exact-looking boundaries;
5. Kotlin/C# properties, defaults, generic methods, hostile inheritance, and
   ordinary foreign implementations beyond the no-input covariant producer;
6. same-object identity and dispatch across deeper separate Kotlin and C#
   assembly graphs, including classifier-derived fields and parameters;
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
