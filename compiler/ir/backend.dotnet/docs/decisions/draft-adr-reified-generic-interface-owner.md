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
warning-bearing parameterized cast or member call. Kotlin `is Source<*>` tests
only the declaration classifier and therefore succeeds when the object has the
capability or any closed natural `Source<T>` construction, including more than
one construction. Under breaking entry BK-1, parameterized `as` and `as?`
share a recursive Kotlin-aware argument-subtyping predicate. `Source<Int>` is
therefore compatible with covariant `Source<Any>` in both forms, including the
CLR-unnameable `Source<int> -> Source<object>` view, but is incompatible with
`Source<String>`. A successful cast returns the original object on the broad
`object` carrier. It does not select a construction for later broad dispatch,
create an adapter, or fabricate a constructed CLR interface. A following
member call still requires the capability or one unique natural construction.

This fallback changes no object identity and creates no proxy, wrapper, or
third public canonical type. It is currently admitted for the structural
no-input producer family with either covariant or invariant declaration-site
variance, where the required call is derivable from the open interface and
declared member. Input-bearing, overloaded, defaulted, and otherwise non-
derivable foreign implementations remain gates.
Trimming and NativeAOT also remain separate gates: runtime interface metadata
and reflective invocation must not be assumed retained merely because both JIT
profiles execute the fallback.

## Compiler-emitted evidence

The one test-only generic-owner epoch now admits three structural root
families. A public top-level covariant or invariant interface with one
unbounded owner parameter and one abstract public no-input member returning
that parameter directly publishes the natural `Source<T>`, its non-generic
semantic capability, and their complete member family. Exact final
substitutions use the natural CLR interface. Covariant stars, projections,
open arguments, and widened value-type views use a broad object carrier;
Kotlin/generated objects take the capability fast path from that carrier.
Invariant exact/open substitutions remain natural because they have no legal
sibling widening. Only an invariant star read enters the object operation
boundary.

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
nullable `is`, smart-cast use, and warning-bearing parameterized `as`/`as?`.
An ordinary `Source<int>` succeeds as `Source<Any>` in both cast forms with
the same identity, but `as? Source<String>` returns null and throwing
`as Source<String>` fails at the cast boundary. Recursive
`Source<Source<Int>> -> Source<Source<Any>>` succeeds while the corresponding
`String` target fails. An ordinary capability-free multi-construction object
passes the classifier and a cast for a construction it actually satisfies,
while its broad member call remains deterministically ambiguous. The compiler
caches the runtime type's interface vector once and creates no wrapper or fake
constructed interface for these Kotlin operations.

A classifier-derived view may also cross a separately compiled callable
result without becoming a false natural construction. For example,
`safeView(Any?): Source<String>? = value as? Source<String>` keeps the logical
KLIB result but conservatively publishes CLR `object`; this cast-derived
carrier does not assert a constructed CLR identity. Incompatible constructions
return null before the boundary. The producer records that physical selection
in ABI 39. The existing generic-owner function-carrier record now identifies
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

The paired input boundary keeps that ordinary exact API rather than changing
its sole MethodDef to `object`. For each admitted public final function with
one exact-looking classifier input, ABI 40 may publish one additional compiler
MethodDef whose selected parameter is `object`. Calls use it only when producer
provenance says that the argument came from the classifier path. Exact Kotlin
and C# calls continue to use the natural MethodDef and direct source body. The
alternate entry deep-copies the same compiler IR body, is named by the logical
function digest, and is bound by an explicit producer record across separate
compilation. `CHECK_NOT_NULL` is carrier-neutral, so it and an immutable local
cannot turn the foreign object into a fabricated `Source<string>`.

This is a callable boundary, not permission to erase all parameters of that
logical type. Open/overridable functions, multiple selected inputs, defaults,
varargs, function generics, generic owners, custom property accessors, and
unproven control flow remain outside the proof.

The first open nested-construction proof adds a distinct rule for a generic
callable whose type parameter occurs inside a variant interface and then an
invariant generic class. Neither `Box<Producer<!!T>>` nor `Box<object>` is a
universal parameter: the former rejects Kotlin-unnameable variant views, while
the latter rejects a caller's already constructed exact
`Box<Producer<string>>`. The physical callable boundary is therefore `object`.
It preserves the caller's actual box and selects that same object's class
capability only when a member is invoked. A factory may still construct the
concrete `Box<object>` required by its open body; its result crosses the same
object boundary. A value read from the box enters the producer/consumer
capability at the subsequent operation, not at storage or identity use.

This object boundary does not alter the generic class TypeDef or all of its
constructions. `Box<T>` retains one `!T` field; closed stable constructions stay
typed; and the control `<T>(Box<Box<T>>) -> Box<Box<T>>` remains the ordinary
`Box<Box<!!T>>` MethodDef. The rule is structural over an admitted nested
variant owner with an open argument. It contains no collection or declaration-
name switch and creates no wrapper or second object identity.

The invariant construction control is stronger. One generic MethodDef over
`Box<InvariantProducer<T>>` can truthfully name
`Box<InvariantProducer<!!T>>` for every substitution because Kotlin cannot
widen that declaration to a sibling argument. The input/result and nested
field therefore remain typed. A public star parameter is instead `object`, so
ordinary C# implementations can enter without naming the capability. A
non-partial C# class implementing only the natural invariant interface is
complete: exact calls use that slot and star output calls use the unique-
construction fallback. The authoring generator remains active only when a
variant contract in the same class genuinely requires a generated capability.

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
2. mutable/broader invariant members, mixed, multi-parameter, and input-
   bearing child/interface compositions beyond the admitted roots;
3. nullable-value, open-nullable, bounded, and value-class substitutions beyond
   the proven reference and `Int` input routes;
4. broad and `@UnsafeVariance` inputs, parameterized casts beyond the bounded
   warning-bearing covariant producer proof, mixed-control-flow cast returns,
   classifier-derived fields, and broader input parameters crossing separately
   compiled exact-looking boundaries;
5. Kotlin/C# properties, defaults, generic methods, hostile inheritance, and
   ordinary foreign implementations beyond the no-input covariant producer;
6. same-object identity and dispatch across deeper separate Kotlin and C#
   assembly graphs, including classifier-derived fields and non-final or
   multi-input parameters;
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
