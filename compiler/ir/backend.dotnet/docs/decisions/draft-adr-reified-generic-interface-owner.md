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

Use-site projection is deliberately distinct from that exact invariant
control. Kotlin permits `InvariantProducer<String>` to flow as
`InvariantProducer<out Any?>`, but CLR invariance does not provide an
`InvariantProducer<string> -> InvariantProducer<object>` conversion. A public
projected interface occurrence therefore uses `object`; the output operation
then selects the compiler capability or exactly one natural CLR construction.
If code constructs `Box<InvariantProducer<out Any?>>`, the generic-argument
mapper chooses `Box<object>` for that construction only. It does not erase
`Box<T>`, change its `!T` field, or replace the complete box with the box's
non-generic semantic capability. Exact construction provenance outranks that
general fallback. Kotlin may store different compatible projected producers
through the same box, and a capability-free ordinary C# producer crosses both
the operation and storage paths without a wrapper or identity change.

The first multi-direction invariant contract follows the same rule rather
than falling back to an erased owner. An admitted `Cell<T>` contains exactly
one abstract `T` output and one abstract `T` input/`Unit` member. The natural
CLR `Cell<T>` is the normal API, and an exact Kotlin implementation stores its
state in `!T`. Star/output-projected reads and input-projected writes use the
semantic capability only at the operation. For an ordinary non-partial C#
implementation, runtime surface 40 generalizes the cached unique-construction
fallback to invoke either a zero-input producer or a one-input consumer. It
does not require C# to implement the compiler capability. Exact/open nested
`Box<Cell<!!T>>` stays typed; only a concrete projected
`Box<Cell<out Any?>>` selects `Box<object>`.

Property syntax follows the member-family proof rather than creating a second
representation. An admitted `PropertyCell<T> { var value: T }` is one natural
invariant CLR interface with one real mutable `Property` row: its getter returns
`!T`, its setter accepts `!T`, and an exact Kotlin implementation retains a
single `!T` backing field. Star/output reads and input-projected writes use the
same operation-local capability or unique-natural-construction fallback as the
method cell. The authoring manifest keeps the natural accessor pair under one
property name and leaves the semantic MethodDefs outside any Property row, so
an ordinary non-partial C# implementation supplies one normal property and no
compiler ABI member. Read-only, open-nullable, mixed method/property, and
multi-property families remain fail-closed.

The exact property family composes across one exact inheritance edge. For
`Child<T> : Parent<T>`, where both levels declare one admitted mutable `T`
property, the natural CLR child inherits `Parent<T>` and owns only its new
Property row. Its semantic capability inherits the parent capability and owns
only the child accessor slots. Implementations retain separate `!T` fields for
the two source properties. Inherited FIR fake-property declarations are not
owner declarations and may neither block the child nor become duplicated ABI.
This rule does not admit deeper, changed-argument, multi-parent, mixed-member,
or multiple-property inheritance.

One direct owner-parameter input also composes above that exact property root.
`ConsumerChild<T> : PropertyCell<T>` owns a natural `Consume(!T)` method while
its one-method semantic capability inherits the parent's accessor capability.
Exact/open receivers remain the natural child; only input-projected calls use
an object receiver. A C# `ConsumerChild<string>` or `ConsumerChild<object>`
implements the inherited property and natural method without a partial class
or compiler interface. The authoring tool treats the manifest's structurally
exact invariant consumer-only contract as a complete child fragment only when
the constructed CLR child inherits a bound complete producer contract. A
standalone invariant consumer retains its generated object adapter. This does
not generalize to multiple inputs, changed arguments, or arbitrary mixed-
member children.

One further direct consumer composes as a bounded second edge. The natural CLR
grandchild inherits the consumer child and property root, owns only its new
`Consume(!T)` MethodDef, and adds one semantic input slot above the inherited
two-plus-one capability methods. Kotlin implementations retain one `!T` state
field; ordinary non-partial C# grandchildren provide one property and two
methods. The authoring proof uses the transitive constructed root contract.
Admission reconstructs exactly the first property-root consumer child and does
not recursively admit a third edge.

That same bounded edge now crosses three Kotlin producer assemblies. The
property root, first consumer, and second consumer each own their natural
TypeDef and corresponding capability in a different DLL. A consumer may not
reapply the local-`IrFile` ownership test to an external parent: external
admission instead requires the exact logical KLIB parent/root shapes, the
producer's full-arity generic-owner records, and producer-recorded member
families for the child consumer and both root property accessors. Reflection
proves the natural and 2-to-1-to-1 capability chains retain those three owners
without copied slots. This physical evidence does not broaden the admitted
declaration family.

ABI 41 replaces that loose conjunction with one typed published-family
contract containing family kind, direct parent/root keys, identity parameter
mapping, bounded depth, declared member roles, and capability binding. Local
declaration analysis and external ABI decoding produce the same immutable
contract and pass it to one child-admission validator. The KLIB remains
authoritative for the logical edge; the physical record remains authoritative
for the producer-selected family. The external index atomically validates the
contract against its Class, capability, member-family, and parent-contract
records before exposing it. See
[`../archive/generic-interface-published-family-contract-2026-08-20.md`](../archive/generic-interface-published-family-contract-2026-08-20.md).

ABI 42 makes the physical capability relationship equally producer-owned.
Every generic-class capability records the complete non-generic physical
interface closure which is valid for all closed constructions of the class.
A reified Kotlin interface contributes its semantic capability; a Kotlin
interface which still has one erased physical identity contributes that
identity. A constructed imported CLR generic contributes neither, because
`C<int> : IFoo<int>` cannot justify `IFoo<object>` on a non-generic
capability. Local materialization and separate consumers therefore agree
without reconstructing physical inheritance from consumer KLIB.

This early declaration fact is deliberately distinct from final call/value
routing. It is sufficient for ordinary `Iterator` calls which
`ForLoopsLowering` creates after generic-owner materialization, including a
widened class view crossing a producer DLL. It does not choose routes for
later body-generated operations whose exact/capability decision depends on
value provenance; the idempotent final router remains a separate gate. See
[`../archive/generic-owner-capability-superinterface-closure-2026-08-20.md`](../archive/generic-owner-capability-superinterface-closure-2026-08-20.md).

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

1. general member-declaring children beyond one producer-output slot, the
   exact one-level invariant-property child, or its one-consumer sibling,
   and the bounded second consumer edge, including multiple members, overloads,
   changed arguments, multiple parents, and deeper inheritance;
2. invariant member families beyond the admitted one-producer/one-consumer
   method root, exact mutable-property root, exact one-level property child,
   and exact property-root consumer child, mixed or multiple type parameters,
   and broader input-bearing child/interface compositions;
3. nullable-value, open-nullable, bounded, and value-class substitutions beyond
   the proven reference and `Int` input routes;
4. broad and `@UnsafeVariance` inputs, parameterized casts beyond the bounded
   warning-bearing covariant producer proof, mixed-control-flow cast returns,
   classifier-derived fields, and broader input parameters crossing separately
   compiled exact-looking boundaries;
5. Kotlin/C# properties beyond the exact mutable invariant cell, defaults,
   generic methods, hostile inheritance, and ordinary foreign implementations
   beyond the proven producer, consumer, and invariant-cell shapes;
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
