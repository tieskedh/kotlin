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

The first defaulted contravariant root is now closed without reverting to an
erased interface owner. `DefaultConsumer<in T>.consumeDefault(T)` remains the
one natural CLR `I<T>` slot. Framework 4.8 moves the single Kotlin body to the
recorded top-level digest-named helper and gives Kotlin and generated C#
implementations the required natural-slot forwarder. .NET 10 emits the same
logical body as a DIM. A `DefaultConsumer<object>` used through Kotlin's legal
`DefaultConsumer<Int>` view reaches that same body and preserves identity even
though CLR variance cannot convert reference-constructed `I<object>` to
value-constructed `I<int>`.

The semantic capability remains an operation boundary, not an alternative C#
contract. A Kotlin override and an ordinary C# override author only the natural
`consumeDefault` member; exact and narrowed Kotlin calls observe that virtual
override. Ordinary C# needs no semantic member: the portable authoring tool
generates its helper forwarder, while .NET 10 inherits the DIM directly. This
closes one default shape only; multiple defaults, properties, generic methods,
diamonds, reabstraction, and broader inheritance remain independent gates.

That default also composes through one hostile three-product inheritance
chain. The interface/default owner is in the first Kotlin DLL. A second Kotlin
DLL defines `OpenDefaultConsumer<T>` and overrides only the natural `!T`
member. An ordinary non-partial C# class derives from
`OpenDefaultConsumer<object>` and overrides only the ordinary
`consumeDefault(object)` virtual. The inherited compiler capability converts
the Kotlin-valid contravariant input at the operation boundary and invokes the
natural slot virtually, so both an exact `DefaultConsumer<object>` call and a
value-type-narrowed `DefaultConsumer<Int>` call observe the C# override. The
middle Kotlin body is not invoked and identity is unchanged.

This class-subclass case does not require the interface authoring generator:
the generic Kotlin base already owns the semantic obligation. Generated source
must not mention the C# subclass, and C# neither names nor overrides the hidden
capability. This adds no probe, runtime helper, or physical ABI record. It does
not admit changed-argument, multiple/deeper-parent, unsafe broad-input, or
other default families.

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

A synthesized local capability remains an ordinary CLR interface for shape
validation, but its logical Kotlin supertype may already have a target-owned
runtime carrier. Interface and class validation must use one runtime-interface
mapping predicate for that case. In particular, `SuspendFunctionN` is a
logical builtin whose established physical edge is the continuation-shaped
`FunctionN+1`; it is neither a missing module interface nor a producer-recorded
external TypeDef. Validation must admit that logical edge and leave the shared
type mapper to select its existing Runtime carrier.

This early declaration fact is deliberately distinct from final call/value
routing. It is sufficient for ordinary `Iterator` calls which
`ForLoopsLowering` creates after generic-owner materialization, including a
widened class view crossing a producer DLL. Later body-generated operations
whose exact/capability decision depends on value provenance are now handled by
a final idempotent router after every current body-producing lowering which can
introduce a generic operation. The router consumes the early contract,
propagates generated value-class carrier provenance, and adds routes to a
monotone fixpoint. It cannot materialize a new family or weaken an earlier
planner route. See
[`../archive/generic-owner-capability-superinterface-closure-2026-08-20.md`](../archive/generic-owner-capability-superinterface-closure-2026-08-20.md).
The final-router proof is archived in
[`../archive/generic-owner-final-call-value-routing-2026-08-20.md`](../archive/generic-owner-final-call-value-routing-2026-08-20.md).

The consumer proof includes `Consumer<object>` and `Consumer<int>` C# source
implementations. The manifest records contravariance and the paired natural/
semantic input signatures; the generator supplies the object-to-natural
adapter without requiring the author to name the capability. This remains
production-inert and deliberately narrow. Only one child-owned member with the
admitted producer-output shape and one independent consumer root are proven.
Broader member surfaces or changed variance require separate complete proofs.

An explicit Kotlin method bound `<R : Any>` is physically unconstrained on the
CLR, consistently with
[`non-null-generic-upper-bound.md`](non-null-generic-upper-bound.md). Kotlin
source and KLIB retain the non-null rule. A CLR `class` constraint would reject
valid Kotlin value substitutions, while `struct`, `new()`, or a nominal
`System.Object` constraint would reject other valid substitutions or state a
different contract. Natural and semantic interface slots, portable helpers,
Kotlin overrides, and ordinary C# overrides therefore all use the same
unconstrained CLR method GenericParam. The semantic capability changes only
owner-dependent carriers; it must not invent a stronger constraint on `R`.

The same physical rule applies to a direct nullable owner-relative method bound
`<R : T?>`. Kotlin source and KLIB retain both the owner relation and its
nullability. The CLR family must not emit `R : T`, because that constraint is
strictly stronger: Kotlin admits `T = Int`, `R = Int?`, while the CLR row does
not. Natural and semantic slots, the portable helper, and Kotlin and ordinary
C# implementations therefore keep a genuine unconstrained CLR `R`. If FIR
narrows a final `R : Primitive?` value to the non-null primitive within the
body, codegen recovers that proven use from the open slot with
`box !!R; unbox.any primitive`. It does not change the declaration constraint
or the representation of unrelated generic values.

An admitted owner-relative abstract method `<R : T>(R): T` erases its
executable CLR `R : T` constraint while KLIB retains that relationship. For a
final non-generic Kotlin implementation with a closed owner argument, the
class's one source body is not lowered through a closed `R -> T` input cast.
It moves to one private unconstrained `<R>(R): object` semantic twin. The
closed public class method remains the direct C# entry and casts only its
result; a private natural MethodImpl for `I<closed T>` and the non-generic
semantic capability bridge both forward the actual method `R` to the same
body. The natural MethodImpl alone adapts the result to closed `T`.

That split is target- versus slot-scoped: one semantic twin exists per Kotlin
implementation target, while each independent reified interface slot receives
its own MethodImpl. It preserves a single body when one override implements
two roots. This proof applies only to a final, locally declared, non-generic
class with one method parameter and one direct owner-relative bound.

An open, locally declared, non-generic implementation uses a distinct override-
family form. Its public class entry is an unconstrained virtual `<R>(R): closed
T`, and the one Kotlin body moves to a protected virtual `<R>(R): object`
semantic hook. A protected virtual method-generic probe detects a later ordinary
C# override of the public entry without reflection or allocation. The class
also owns a non-generic compiler-ABI capability interface and private final
dispatcher, while the reified interface retains its independent semantic
capability and dispatcher. Both dispatchers call the C# typed override when the
probe changes and otherwise preserve the raw Kotlin semantic hook. The natural
`I<closed T>` MethodImpl targets the public virtual entry.

The class-owned capability is necessary separate-compilation evidence, not a
second object or state: the generic interface capability belongs to the
interface producer, while a later consumer of the open class must also recover
that class's semantic hook and probe MethodDefs. C# subclasses override only the
public generic method and neither implement nor name either capability. The
final private-twin representation above remains unchanged. This open proof is
limited to one locally declared method with one direct owner-relative bound. A
body inherited from an ordinary non-generic base uses the distinct rule below.

That inherited proof is now closed for a local non-generic base body. When an
open or final derived class first adds the reified interface, the physical
family is attached to the real base declaration: one public unconstrained
typed entry, one protected semantic hook and probe, and one base-owned
capability plus private dispatcher. The derived binding owner receives only
its natural MethodImpl and its own private interface-capability dispatcher; it
does not copy the body, hook, probe, or base capability. Multiple derived
binding owners reuse the same family and its recorded pre-lowering owner-bound
proof. They must never call one another's or the base's private dispatcher.

This rule also preserves ordinary C# subclassing through the inherited public
entry. A later artifact may now add a new reified-interface binding when the
producer already prepared and published that exact open base family. The
consumer binds an un-emitted IR prototype to the producer's assembly-qualified
public Function record and the recorded semantic-hook/probe MethodDefs. It
emits only its own natural and interface-capability MethodImpls: the external
body, typed entry, hook, probe, class capability, and private dispatcher remain
producer-owned and are neither copied nor inferred by generated name. Semantic
dispatch compares the three producer MethodRefs by physical CLR owner, because
separate resolver instances may legitimately materialize distinct class-info
objects for that same owner.

An ordinary C# subclass of the new binding owner still overrides only the
inherited public typed method. Its exact, natural-interface, and Kotlin
semantic calls all observe that override. An unprepared external base remains
closed: the consumer does not mutate an external IR declaration or synthesize a
new family from KLIB shape alone. Generic bases or binding owners, overloads,
multiple method/value parameters, broader bounds, and mixed member families
remain independent gates.

A general multi-member covariant root is now admitted for the first
`Iterator<T>`-like grammar. It contains exactly one abstract no-input member
returning the owner parameter directly and one or more abstract no-input
non-null primitive queries whose signatures do not mention that parameter.
The natural CLR interface owns every ordinary member. Its declaration-semantic
capability duplicates the query with the same primitive carrier and widens
only the producer result to `object`. The duplicate query is a receiver-domain
slot, not an erased value representation: exact execution continues to call
the natural member and no state becomes `object` because the semantic sibling
exists.

ABI 43 records an `OWNER_INDEPENDENT_QUERY` role alongside the producer role.
A separate consumer conjunctively validates KLIB shape, the published family,
both member-family records, and capability ownership. Ordinary partial C#
implements only the natural query and producer; generated compiler ABI adapts
a Kotlin widened view on the same object. Admission is independent of source
names, packages, and library ownership. See
[`../archive/reified-generic-interface-owner-independent-query-family-2026-08-22.md`](../archive/reified-generic-interface-owner-independent-query-family-2026-08-22.md).

The first constructed owner-dependent result is now admitted. A structural
covariant root may return one already-admitted covariant interface constructed
invariantly over the outer owner parameter. The natural interface retains that
exact nested `I<!T>` result. Its declaration-semantic slot returns `object`,
not the nested compiler capability: an ordinary foreign implementation may
return only a natural nested `I<T>`, and converting it to a capability would
require a forbidden adapter or identity change.

This does not make every enclosing generic slot semantic. An exact nested
construction remains typed. Only a state slot whose concrete producer graph
admits a Kotlin-legal but CLR-unnameable covariant nested view joins to the
object domain. `PAIRED_SEMANTIC_STATE_OUTPUT` records that body/state fact, and
an exact-receiver `SEMANTIC_RESULT_CAPABILITY` route reads the raw value before
the natural typed entry performs its CLR-view check. The new route is distinct
from a semantic receiver: a downstream fallback may not erase an already-
proven typed route merely because a semantic sibling exists. ABI/runtime
surface 44 publishes the structural `CONSTRUCTED_INTERFACE_PRODUCER` role.
See
[`../archive/reified-generic-interface-constructed-result-family-2026-08-22.md`](../archive/reified-generic-interface-constructed-result-family-2026-08-22.md).

That result route does not imply that every semantic member needs an interface
slot. A private member is not callable through an external Kotlin view and
therefore deliberately owns no capability dispatcher. When an exact same-owner
call needs the raw semantic result of such a member, representation planning
binds that call directly to the member's private semantic hook. It must not
widen the member, manufacture a capability slot, or fall back through its
natural typed wrapper. Public and protected members continue to require their
planned capability dispatcher; absence of that dispatcher remains a hard
failure rather than permission to call a protected hook directly.

Producer-recorded generic-owner function facts—member families, result carriers,
and input entries—are consumer authority only for metadata-deserialized external
declarations. The external resolver must not derive an ABI key for a declaration
which belongs to a local `IrFile`, including a generated or default accessor
after lowering. Such declarations remain under the current compilation's
representation plan, whose rewritten type graph need not be a valid producer
signature. If a local override needs producer authority, the compiler must
resolve and query its external overridden source; it must not mangle the local
post-lowering declaration or recover by swallowing a mangler failure.

The first broad-input family is now materialized from its atomic producer
record. ABI/runtime surface 45 adds `BROAD_FIXED_BARRIER_INPUT` and
`BROAD_NESTED_SEMANTIC_INPUT` member roles plus the invariant exact TypeDef's
physical owner path. Either role without that owner, or that owner without
either role, is invalid; natural, semantic, and exact owners must be distinct,
and the exact metadata arity must match the logical owner. Consumers therefore
never reconstruct or guess the third view. The exact TypeDef inherits the
natural covariant view and owns only CLR-illegal input members. Its nested
typed signature continues to name the natural `I<!T>`, while only the
non-generic semantic capability accepts the object-domain input. Kotlin
implementations retain one producer-proven `!T` field and implement all three
views on one object. See
[`../archive/reified-generic-interface-exact-input-materialization-2026-08-22.md`](../archive/reified-generic-interface-exact-input-materialization-2026-08-22.md).

ABI/runtime surface 46 closes the matching public and ordinary foreign
boundary. The Kotlin class source member remains the public typed
`acceptsAll(I<!T>)` entry; a later semantic-routing pass may not replace its
parameter with `object`. Its protected semantic hook is a separate compiler
ABI MethodDef. A separately compiled non-partial C# class may implement only
the natural interface and provide that compatible operation as an ordinary
public typed method. The bounded foreign path selects exactly one natural
construction and resolves the concrete method by the exact constructed
parameter type, so an `object` overload cannot be substituted. Absence of that
method fails closed, and the compiler never attributes an arbitrary semantic
body to the raw class. See
[`../archive/reified-generic-interface-precompiled-exact-input-2026-08-22.md`](../archive/reified-generic-interface-precompiled-exact-input-2026-08-22.md).

ABI/runtime surface 47 admits one owner-independent read-only primitive
property getter inside that same broad-input family. Its producer role is
distinct from a method query. The natural covariant interface and Kotlin
implementation own the ordinary CLR Property row and typed getter; the
semantic capability owns only a method slot with the same primitive carrier.
Generated and non-partial precompiled C# implementations author one ordinary
property. A widened Kotlin call boxes the capability result only for the local
object join or invokes the raw getter through its unique natural construction,
then restores the primitive result. No owner state becomes `object`. See
[`../archive/reified-generic-interface-owner-independent-property-2026-08-22.md`](../archive/reified-generic-interface-owner-independent-property-2026-08-22.md).

ABI/runtime surface 48 admits the optional fixed-barrier member in the same
family. The rule is not “a direct `T` input returns false”: one directly
declared member must resolve through Common `SpecialBridgeMethods` to exactly
one canonical-only Runtime generic-interface parent. That parent remains
ordinary KLIB and CLR inheritance and is not copied into the reified-family
ancestry record. Separate consumers revalidate the logical parent, the exact
provider, and the upstream policy.

The exact sibling retains the typed `!T` parameter. The Kotlin generic-class
capability dispatcher checks the upstream barrier before its first
`object -> T` narrowing. The C# manifest carries the same policy to generated
adapters. For an ordinary precompiled C# implementation, the bounded runtime
fallback selects one natural construction, resolves the concrete method by
that construction's first type argument, and applies the upstream result
before invoking the typed method. None of these widened boundaries changes
the owner's producer-proven `!T` field or its normal typed call route. See
[`../archive/reified-generic-interface-fixed-barrier-composition-2026-08-22.md`](../archive/reified-generic-interface-fixed-barrier-composition-2026-08-22.md).

ABI/runtime surface 49 instantiates the first two Runtime-owned members of the
future collection graph without changing the production mapping. The Runtime
contains additive covariant `Iterator<T>` and `Iterable<T>` identities; the
generic-owner rehearsal alone exposes those declared views to the compiler.
Every truthful compiler-emitted implementation directly owns its constructed
InterfaceImpl and private typed MethodImpl bundle in addition to the erased
semantic bundle. A typed `Iterable<T>` signature may retain its nested
`Iterator<T>` result because this Runtime mapping explicitly promises that
implementation closure. Other split interfaces remain canonicalized when that
promise is absent.

The compiler semantic capability is not a second typed owner. It may inherit
erased semantic parents but must never acquire `Iterator<object>`,
`Iterable<object>`, or another constructed generic interface. Both the
assignability pre-pass and final TypeDef render enforce this. The real object
owns `Iterator<!T>`/`Iterable<!T>` and keeps producer-proven state as `!T`;
only an unnameable widened Kotlin operation uses the erased slot.

Natural Runtime authoring is independently valid C#: a non-partial class
implements only `Iterator<T>` or `Iterable<T>` and their ordinary members. No
generator, wrapper, erased interface, or compiler capability is part of that
contract. Exported Kotlin type-use remains on the erased ABI outside the
rehearsal, however, and must migrate coherently with the atomic collection
graph before natural-only C# values are promised across every Kotlin API
boundary. See
[`../archive/runtime-reified-iterator-foundation-2026-08-23.md`](../archive/runtime-reified-iterator-foundation-2026-08-23.md).

ABI/runtime surface 50 applies that dependency foundation to the first atomic
input-bearing Runtime family. Natural covariant `Collection<T>` and `Set<T>`
own only CLR-legal output/query members. Their invariant exact siblings own
`Contains(T)` and `ContainsAll(Collection<T>)`; the accepted arity-zero owners
remain declaration-semantic capabilities and are never inherited by a pure C#
implementation. Kotlin implementations carry all selected views on one object
and retain producer-proven fields as `!T`.

An ordinary non-partial C# implementation authors only the natural interface
plus public input methods. A unique-construction runtime fallback invokes
those methods when neither exact nor semantic Kotlin view exists. `Contains`
applies the upstream fixed false barrier before typed invocation. A compatible
`ContainsAll` preserves the ordinary C# method; an incompatible construction
uses the original natural Iterable/Iterator objects and applies `Contains`
element by element. This is a semantic requirement rather than a convenience:
the empty incompatible input returns true. Open nested producer results use
`object` as the common carrier of canonical and natural reference views until
the next member dispatch, preserving identity without an adapter. See
[`../archive/runtime-reified-collection-set-family-2026-08-23.md`](../archive/runtime-reified-collection-set-family-2026-08-23.md).

ABI/runtime surface 51 extends that same representation to the complete
read-only List closure. Natural covariant `ListIterator<T>` and `List<T>` own
their output-safe and declaration-independent members. The invariant exact
List sibling owns candidate inputs and index queries. The runtime dispatcher
resolves the two `listIterator` forms by name plus arity and carries the
recorded fixed wrong-shape value, including `-1`, without a List-specific
compiler branch. Kotlin implementations retain `!T` fields and ordinary C#
implements only the natural interfaces. See
[`../archive/runtime-reified-list-family-2026-08-23.md`](../archive/runtime-reified-list-family-2026-08-23.md).

ABI/runtime surface 52 adds natural covariant `MutableIterator<T>` and
`MutableIterable<T>` over the existing read-only dependency graph. The
declaration-independent `remove()` Unit member uses the general natural-only
foreign dispatcher. The narrowed `MutableIterable<T>` result and inherited
`Iterable<T>` result remain two honest natural CLR slots; portable C# writes
one explicit natural base forwarder but no compiler capability. Kotlin
implementations retain one identity and `!T` fields. See
[`../archive/runtime-reified-mutable-iterator-foundation-2026-08-23.md`](../archive/runtime-reified-mutable-iterator-foundation-2026-08-23.md).

ABI/runtime surface 53 adds natural invariant `MutableListIterator<T>` over the
existing two natural parent interfaces. Its `T` result and `T` inputs live on
one natural construction and need no exact sibling. Star reads and input-
projected writes use the semantic capability only at the operation; exact/open
calls and Kotlin implementation state remain typed. An ordinary non-partial C#
implementation names only `MutableListIterator<T>`. See
[`../archive/runtime-reified-mutable-list-iterator-2026-08-23.md`](../archive/runtime-reified-mutable-list-iterator-2026-08-23.md).

ABI/runtime surface 54 adds natural invariant `MutableCollection<T>`. Exact
element mutation uses `T`; bulk inputs use a physical method parameter
`<U : T>(Collection<U>)`. This is the first selected nested-input shape which
retains Kotlin value-type subtyping without CLR interface covariance, owner
erasure, or an object field. The Kotlin/KLIB member stays non-generic, and the
relative parameter is a compiler-recorded physical slot fact.

Both the Runtime slot and Kotlin MethodImpl carry `U : T`. Ordinary C# names
only `MutableCollection<T>` and its natural parents, and Roslyn statically
checks every selected mutation member. A natural-only foreign object behind an
unnameable projected Kotlin receiver may use the bounded generic-method
fallback for that operation. This does not close the earlier inherited
Collection candidate-input protocol, trimming, NativeAOT, performance, or
tooling gates. See
[`../archive/runtime-reified-mutable-collection-2026-08-23.md`](../archive/runtime-reified-mutable-collection-2026-08-23.md).

ABI/runtime surface 55 adds natural invariant `MutableSet<T>` above natural
`Set<T>` and `MutableCollection<T>`. Its source-redeclared iterator and
mutation slots remain MethodDefs on the child. Both the child and mutable-
collection parent bulk slots retain `<U : T>(Collection<U>)`; CLR interface
maps prove their Kotlin MethodImpls and the one-method ordinary C# binding.

No exact sibling is added for the invariant child and no MutableSet-specific
compiler rule is introduced. Calls through a widened covariant Set parent keep
the already admitted candidate-input route because that parent still cannot
honestly place `T` in its natural CLR input surface. See
[`../archive/runtime-reified-mutable-set-2026-08-23.md`](../archive/runtime-reified-mutable-set-2026-08-23.md).

ABI/runtime surface 56 adds natural invariant `MutableList<T>` over the
existing natural `List<T>` and `MutableCollection<T>` graph. Its direct
positional inputs and results remain `T`; its narrowed iterator and recursive
sublist results remain the natural `MutableListIterator<T>` and
`MutableList<T>` constructions. The child owns only its Common-redeclared
slots and does not copy candidate-input members from the covariant List parent.

Both bulk overloads use the same physical `<U : T>(Collection<U>)` rule. The
relative input is selected as the unique nested covariant construction over
the owner parameter, so an independent prefix such as the insertion index does
not create a declaration-specific overload exception. The bounded foreign
dispatcher likewise admits one owner-dependent input among independently
representable parameters and may return Unit, a declaration-independent value,
or the same owner parameter. Name plus complete argument count keeps the two `Add`
and two `AddAll` forms unambiguous. Kotlin/KLIB members remain non-generic and
one object retains one state.

The cut advances the provisional Runtime/manifest epoch atomically. A library
which can name the MutableCollection, MutableSet, or MutableList natural
families must not be accepted with a Runtime predating those TypeDefs. Map,
broader properties/defaults, extra producer members, and mixed/multiple type
parameters remain separate gates. See
[`../archive/runtime-reified-mutable-list-2026-08-24.md`](../archive/runtime-reified-mutable-list-2026-08-24.md).

ABI/runtime surface 57 selects the first multiple-owner-parameter root as one
structural family. A public parentless interface with two or more covariant,
nullable-`Any`-bounded owner parameters may expose exactly one abstract
read-only property for each parameter. Every getter must return one direct,
non-null owner parameter, and the getter-to-parameter relation must be a
bijection. No method, setter, default, repeated parameter, unused parameter,
changed argument, or inherited member is admitted by this rule. The natural
CLR owner retains every declared variance and getter result as its matching
`!n`; the single non-generic semantic sibling widens only those direct results
to `object`.

`Map.Entry<out K, out V>` is the first Runtime instantiation. Its accepted
arity-zero nested `Map.Entry` remains the declaration-semantic identity. The
additive natural `Map.Entry<K, V>` is a distinct nested arity-two TypeDef under
the same accepted arity-zero `Map` metadata container. It is deliberately not
nested under a speculative natural `Map<K, V>`: an entry value has no physical
dependency on one enclosing map construction, and selecting it must not
partially select the still-erased Map family. Ordinary non-partial C# may
implement only the natural nested interface and its two properties. Kotlin
implementations retain two independent typed fields, while a star, projection,
open argument, or value-type widening crosses the semantic sibling only at the
getter operation. This closes multiple covariant owner parameters and their
Runtime nesting; it does not admit mixed variance, inputs, mutable entries, or
Map itself. Warning-bearing parameterized `as` and `as?` derive the requested
natural construction independently from that semantic carrier and use the
same recursive compatibility predicate for all owner arguments. See
[`../archive/runtime-reified-map-entry-2026-08-24.md`](../archive/runtime-reified-map-entry-2026-08-24.md).

ABI/runtime surface 58 selects the first multiple-parameter invariant child.
A public owner with two or more invariant nullable-`Any`-bounded parameters
may extend exactly one already selected covariant producer-property root only
through an equal-arity identity substitution. It may declare exactly one
abstract non-null input/output member whose argument and result are the same
direct owner parameter. The natural child inherits every parent `!n` getter
and retains the mutation as `!n -> !n`; its owned semantic capability inherits
the parent capability and widens only that direct operation to
`object -> object`. Any changed/reordered/fixed argument, second parent,
additional member, property, default, nullable slot, or deeper lineage is
outside this family.

`MutableMap.MutableEntry<K,V> : Map.Entry<K,V>` is the Runtime instantiation.
Its natural invariant arity-two TypeDef is nested under the accepted arity-zero
MutableMap metadata container and implements the natural covariant Entry with
the same `!0,!1` arguments. This does not select `MutableMap<K,V>`. Kotlin
implementations retain typed key and mutable value fields; ordinary non-partial
C# implements only the natural inherited getters and `SetValue(V): V`.
The exact implementation checkpoint and the dual-entry/relative-input
regressions it closed are recorded in
[`../archive/runtime-reified-mutable-map-entry-2026-08-24.md`](../archive/runtime-reified-mutable-map-entry-2026-08-24.md).

ABI/runtime surface 59 selects the first parentless mixed-variance,
multiple-parameter lookup family. It has exactly one invariant and one
covariant parameter, no parents, two invariant-parameter barriers (one Boolean
and one nullable covariant result), one covariant-parameter Boolean barrier,
one owner-independent primitive property, one primitive query, and three
read-only constructed-interface properties whose recursively collected owner
parameter vectors are invariant, covariant, and their ordered pair. Every
constructed result owner must already publish a fully covariant natural family.
The rule is derived from variance, member types, properties, and published
dependencies; names and packages are not inputs.

`Map<K,out V>` is the Runtime instantiation. Its natural interface owns
`ContainsKey(!K)` and typed Set/Collection/Entry results. Its invariant exact
sibling owns `ContainsValue(!V)`. The natural `Get(!K)` returns object because
Kotlin `V?` has no single honest unconstrained CLR representation across
reference and value V substitutions. This is a local call carrier, not an
erased-owner decision: Kotlin implementation key/value fields remain `!0` and
`!1`. Semantic dispatch is selected only for the concrete operation whose
Kotlin view CLR cannot name, and all views retain one object identity.

This selects the honest single-slot carrier, not the final performance optimum.
A general pre-ABI proof may represent an open-nullable method result as a typed
payload plus an outer-presence flag, for example `V` plus `bool`, so an exact
value-type construction need not box. Such a convention must be declaration-
independent and close overrides, function references, reflection, separate
compilation, C# implementation, Framework 4.8, and modern AOT lanes before it
can replace the one-slot rule. The flag denotes absence of the outer payload;
it is not a Map-specific missing-key protocol. No such multi-slot ABI is
selected by surface 59.

Ordinary non-partial C# implements only the natural Map and supplies the
accepted public value-candidate convention; it need not name compiler ABI.
Warning-bearing BK-1 `as`/`as?` checks apply the same recursive compatibility
predicate to K and V, preserving legal V covariance. `MutableMap<K,V>` is not
selected. See
[`../archive/runtime-reified-map-2026-08-24.md`](../archive/runtime-reified-map-2026-08-24.md).

Post-representation override closure uses the inherited physical MethodDef as
authority rather than reconstructing it from a later logical substitution. An
open nullable owner parameter therefore retains the declaration's `object`
slot even when a leaf fixes the parameter to a reference type; the leaf keeps
its natural typed method and receives one private final forwarding MethodImpl.
This declaration-carrier rule does not override retained foreign CLR metadata:
an imported reified `!T` MethodDef is substituted through its actual
construction, regardless of nullable/flexible Kotlin import types.
The same rule admits the synthetic `ExactFunctionN.InvokeExact` declarations
as real Runtime generic slots. Their closed MethodImpl owner is derived from
the typed callable target and the canonical capability carrier, while ordinary
call references remain unchanged. Rehearsal-only carrier selection is epoch
guarded, so production-erased emission cannot acquire a mismatched Runtime
construction. This closes a physical correctness boundary; selectively
naturalizing stable closed nested callable results remains a separate pre-ABI
optimization proof. See
[`../archive/generic-owner-post-representation-covariant-slots-2026-08-25.md`](../archive/generic-owner-post-representation-covariant-slots-2026-08-25.md).

Same-module bootstrap reconstruction is bound by physical owner arity. An
arity-zero TypeDef may retain a closed interface edge, but it may not link a
natural construction which references logical owner parameters absent from
CLR metadata. See
[`../archive/generic-owner-erased-bootstrap-interface-edge-2026-08-25.md`](../archive/generic-owner-erased-bootstrap-interface-edge-2026-08-25.md).

A closed non-generic implementation does not acquire permission to narrow a
declaration-semantic generic-interface input merely because its source member
has one exact closed Kotlin signature. In the rehearsal epoch, a directly
declared body on a physically final class may instead receive one paired
compiler object-input entry when exactly one nested admitted-interface
parameter differs, the result is unchanged, and no upstream fixed wrong-shape
barrier owns the call. The canonical bridge targets that object entry; the
natural source MethodDef/body remains the normal exact Kotlin and C# entry.
The rule is structural and excludes open/inherited bodies, defaults, varargs,
method generics, properties, multiple mismatches, and production emission. See
[`../archive/generic-owner-closed-semantic-input-bridge-2026-08-25.md`](../archive/generic-owner-closed-semantic-input-bridge-2026-08-25.md).

## Physical-value provenance consolidation

The post-representation slot, closed semantic-input, split-nullable, inline
temporary, receiver-helper, result-chain, and generated-capture checkpoints
establish three related but distinct kinds of authority. They must not be
collapsed into one exact/semantic/object ranking:

1. Kotlin IR and KLIB remain the logical authority for types, variance,
   nullability, overrides, projections, stars, and operation semantics.
2. Producer-selected or retained CLR declarations are physical authority for
   TypeDefs, MethodDefs, fields, InterfaceImpls, MethodImpls, and calling
   conventions.
3. Per-value provenance records which already-existing physical views remain
   available after local flow and representation-preserving Kotlin view
   changes. It does not select public declarations or state.

Physical declaration authority is staged through three explicit evidence
epochs. The early representation plan selects parametric owner/member/state
relationships and bridge obligations. The bound-declaration index owns the
materialized TypeDef, MethodDef, InterfaceImpl, and MethodImpl identities and
the structural contracts expected for them. The sealed emission-signature
index is a fresh certificate over final-live physical rows after the relevant
lowerings; it is not an additive copy or overlay of BOUND. Identity lineage and
opaque keys may persist between epochs for correlation, but a BOUND row can
never supply a sealed path, name, CLI flag, signature, hidden carrier, or
MethodImpl. Missing actual evidence is unavailable and disagreement is a
conflict. Retained foreign CLR metadata may become sealed authority only
through its own retained-actual-evidence adapter, never through a BOUND
overlay. A later epoch may bind an earlier physical type expression; it may
not reinterpret an already selected declaration family.

Physical declaration identity is separate from its epoch-specific description.
Arity, class/interface/value-type category, and null encoding are admitted by
one conflict-checked declaration index; they never participate in TypeDef or
MethodDef identity inside value flow. Two descriptions of the same identity
either agree or produce `Conflict`--they cannot become two apparently unrelated
declarations. A later evidence epoch may reuse an already selected identity,
but it must construct its own description from that epoch's authoritative
source. An independent final-emission certificate is therefore required to
prevent an omitted or evicted BOUND declaration from surviving as falsely
sealed; it is not an alternative identity and cannot conceal an authority
disagreement.

Body-local carriers are symbolic until that final binding. A current-
compilation TypeDef is identified by its IR declaration identity plus its
selected canonical, natural, or exact physical view; it is not identified by a
provisional emitted path. A producer TypeDef is identified by its complete
library artifact identity and recorded path. A foreign CLR TypeDef or MethodDef
is identified by its retained assembly metadata and metadata handle, never by
re-resolving a name or enhanced Kotlin type. Emitter binding is a pure one-way
query over the current live indexes:

```text
symbolic carrier + live emission indexes
    -> bound physical type | unavailable | declaration conflict
```

An unavailable local declaration participates in normal emitter eviction. It
does not authorize the binder to reinterpret the value as semantic or object,
and a bound IL type is not cached across eviction/fixpoint rounds.

Generic parameters in a symbolic carrier are scoped to their physical binder.
`OwnerParameter(ownerA, 0)` and `OwnerParameter(ownerB, 0)` are distinct, as
are method parameters from different MethodDefs. Only final signature binding
may render them as `!0` or `!!0`, after checking the selected owner or method
or applying an explicit recorded substitution. A bare context-relative `!0`
must never circulate as a cross-body value fact.

Per-value analysis uses a product rather than a scalar lattice. At minimum it
distinguishes:

- the carrier produced by the current definition or expression;
- the carrier already selected for a destination local, parameter, field, or
  result slot;
- the real constructed CLR views guaranteed on every reaching non-null value;
- optional selected-view lineage;
- direct, null, bottom, and split-nullable value layouts; and
- the physical evidence from which each guaranteed view was obtained.

`ProducedCarrier` and `StorageCarrier` are deliberately different facts. A
definition can produce `Producer<int>` while a destination selected by the
join of several definitions stores `object`; reading that destination then
produces its already selected `object` carrier. Conversely, local placement
may select the one exact carrier of a closed immutable flow. Value analysis
supplies constraints to placement but may not assume the placement result as
its own input. Fields, public parameters, and MethodDef results are fixed by
their producer-wide declaration plan rather than local flows.

Placement in this analysis is identity-preserving only. Storing a reference
carrier in `object` retains the same runtime object and its independently proven
views. Boxing a scalar or value-type carrier, unboxing it, adapting a semantic
capability, or materializing a nullable value is an explicit operation which
produces a new fact; a permissive storage predicate must not smuggle those
operations into provenance. Alternative reaching writes join their non-null
guarantees, whereas a sequential overwrite kills the earlier contents. Reading
a fixed constructed storage carrier independently guarantees that carrier's
own physical view.

Selected-view lineage is only a selector over already-proven views. It can
remember that a value implementing both `Source<int>` and `Source<string>` was
first selected as `Source<int>` before a logical star projection, but it may
never prove that `Source<int>` exists. Every selected lineage entry must be a
member of the value's guaranteed physical views. A join retains a lineage
entry only when every incoming non-bottom path selects the same physical view;
different or absent selections remove it even when the incoming values happen
to share one concrete runtime class.

Joins select a verifier-valid destination carrier independently from logical
Kotlin type joining. Guaranteed view sets intersect after closing only over
real producer-recorded or retained CLR edges. Two known incompatible
constructions are ordinary precision loss, not a declaration conflict. They
may join at a truthful common physical view, a capability guaranteed by every
incoming object, or `object`; they never manufacture a construction such as
`I<object>`. Unknown flow remains unknown. A successful checked barrier may
add path-local view evidence, while a logical, unchecked, or implicit cast may
only preserve evidence already held by its input.

Evidence labels are diagnostic provenance, not an additional lattice
dimension. Two facts guaranteeing the same view set and lineage compare equal
even when one learned a view from a storage read and another from an
identity-preserving transfer. This keeps evidence accumulation from perturbing
fixpoint convergence or shadow-result equality.

`null` is an explicit produced layout, not `object` with a special logical
type. Guaranteed views quantify over reaching non-null values, so joining a
known-null arm with an exact `Source<int>` arm preserves the latter view and
changes only the null state. Placement may put that null directly in any
already-selected compatible reference carrier; it must not claim that null was
produced as `object` and thereby force an object join. A maybe-null unknown arm
can still contain an unknown non-null value and therefore degrades exact
provenance normally.

The declaration index also selects a carrier's null encoding. A reference
carrier accepts carrierless `ldnull`; an inline nullable value can represent
logical null only after explicit materialization; a non-null scalar cannot
represent it; and an unconstrained physical `!T` is substitution-dependent.
The latter may carry an already typed maybe-null `!T` value and store it
unchanged in the same `!T` slot, but it does not authorize injecting
carrierless null before the construction is known. Joining carrierless null
with split or inline value layouts therefore becomes unknown until an explicit
layout conversion is chosen.

Operation routing remains a separate decision. It starts from the logical
member family, its selected physical slots, and its semantic policy, then asks
whether receiver and argument facts satisfy an allowed route. Exact receiver
provenance cannot override a broad-candidate barrier, semantic-result route,
direct-super target, explicit MethodImpl, or retained foreign override slot.
An object-carried value may recover one already-guaranteed exact view with the
required CLR conversion, but an exact-looking logical type cannot create such
evidence. The emitter consumes the resulting route and carrier decisions; it
does not rediscover representation from member names, packages, IR origins, or
logical supertype substitution.

Semantic body variants receive explicit entry environments. The current exact
receiver can remain on its selected owner construction while broad hook
parameters start in their semantic/object carriers. Broadness follows real
definitions and uses; it does not erase unrelated receiver-derived fields,
producer results, helper arguments, or captures. Immutable aliases, calls,
and generated captures follow the same transfer rules regardless of whether
their IR declaration originated in source or a compiler lowering. Mutable
values join every reaching definition.

State selection is not delegated to per-value provenance. One owner field is
chosen from every legal constructor, write, semantic entry, override, escape,
memory-semantic obligation, and separate-compilation boundary. A convenient
local `Box<Producer<int>>` allocation cannot specialize a logical mutable
`Box<Producer<Any?>>` whose legal later writes may carry another construction.
After the producer-wide field carrier is fixed, value provenance may route
reads and writes around that one authoritative state. It may not introduce a
wrapper, proxy, duplicate field, or shadow state.

Guaranteed-view closure uses recorded physical supertype edges. A logical IR
or KLIB supertype does not by itself prove that the corresponding InterfaceImpl
survived physical admission. The representation plan records each admitted
symbolic edge, and later epochs bind rather than reconstruct it. Until such an
edge exists, a shadow analysis may retain an exact concrete carrier but must
report an interface-call view as unknown.

The first executable edge authority records one complete direct set per
physical TypeDef. Missing and recorded-empty sets are distinct; positive views
survive an incomplete downstream closure but the result remains explicitly
incomplete. Targets use source-TypeDef-scoped parameters, reject duplicate,
self, cyclic, category-invalid, or incomplete class-base rows, and substitute
only through recorded physical constructions. Detached generic-class family
artifacts provide their exact `directSupertypes`. Existing published generic-
interface contracts provide natural/exact TypeDef identities but deliberately
omit canonical-only parents, so their ancestry remains unavailable until it is
joined with complete producer or retained `InterfaceImpl` authority. Core
`System.Object` is normalized to its one canonical leaf carrier. No adapter
walks logical supertypes or adds an unrecorded capability edge.
See
[`../archive/generic-owner-physical-supertype-authority-2026-08-25.md`](../archive/generic-owner-physical-supertype-authority-2026-08-25.md).

The first integrated value-flow slice runs exactly once after final routing has
reached its fixpoint and only in the generic-owner rehearsal. An admitted local
generic-class plan supplies the early physical self construction
`C<OwnerParameter(...)>`; a regular `Any`/`Any?` hook parameter starts in the
object carrier with unknown views. Sequential immutable object locals may read
those fixed parameter/local slots and cross implicit representation-preserving
wrappers. Thus `val x: Any? = this` records a constructed produced carrier, an
object storage carrier, and the independently guaranteed self view, while
`val y: Any? = broadCandidate` remains object plus unknown. Reading `x` again
produces object because that is its storage carrier but retains the guaranteed
self view. No view is selected merely because it is guaranteed.

This slice deliberately rejects mutable locals, explicit casts, unsupported
initializers, and every carrier or view which its diagnostic schema cannot
render. Snapshot projection is complete or `UNSUPPORTED`; it never silently
drops a foreign/interface view while claiming a known result. The early local
self adapter may not be generalized into ancestry, MethodDef, retained-foreign,
or later-epoch authority. Snapshots are IR-free in-memory evidence and cannot
enter the routing fixpoint, architecture plan, emitter, library ABI, or state
selection. Existing recognizers remain authoritative. See
[`../archive/generic-owner-physical-value-shadow-first-slice-2026-08-26.md`](../archive/generic-owner-physical-value-shadow-first-slice-2026-08-26.md).

The next slice observes the same engine immediately after `moveBodyTo`, before
semantic remapping, as well as after final routing. A generic local contributes
only Deferred storage; it receives the initializer's exact carrier only when
that value is already a direct null-reference carrier with an independently
guaranteed matching construction. Source and compiler-owned immutable aliases
therefore follow one origin-independent rule. Object storage remains fixed and
cannot be narrowed by a retained guarantee.

Actual definitions, not `isVar` alone, guard mutable flow. Stars and non-
invariant projections are rejected recursively. Implicit wrappers preserve
identity only for proven reference carriers and known object/current-owner
reference targets. Container evaluation rejects opaque/control-flow prefixes
instead of trusting a lexical last expression. Both epochs exercise broad
Deferred flow, different constructions, projections, cast laundering, and an
inline early return. These are shadow storage predictions only: the old emitter
recognizer remains unchanged and no snapshot controls a local slot. See
[`../archive/generic-owner-physical-value-pre-remap-alias-2026-08-26.md`](../archive/generic-owner-physical-value-pre-remap-alias-2026-08-26.md).

Actual local placement is observed through a one-way post-emission diagnostic
boundary. Each successful emitter scope publishes only ordinary `IrVariable`
slots emitted through the variable-local path in its final surviving render
products, plus their existing selection reasons; catch parameters and other
separately declared IL locals remain outside this slice. Failed renders and
superseded fixpoint rounds publish nothing.
Function/local IR-symbol identity correlates the two sides. Raw emitter
identities are normalized structurally, including the physical TypeDef view,
the scoped type-parameter binder, and the physical MethodDef owner. Missing,
ambiguous, unsupported, and unbindable correlations stay explicit.

Comparison uses the final-routing storage prediction. The pre-remap fact
establishes continuity only, and diagnostic evidence labels are not part of
value equality. A matching local proves only the selected `StorageCarrier`.
It does not prove the initializer's produced carrier, a conversion, guaranteed
interface ancestry, a selected logical view, an operation route, state, or
MethodImpl. `DIFFERENT` is likewise neutral and cannot by itself authorize
either carrier. Neither the prediction nor the comparison is an input to the
other side.

The shadow may enter a sequential block/composite expression container to
observe nested aliases without making the surrounding call/branch tree
transparent. An implicit wrapper may preserve an already direct reference
carrier when the logical target's reference shape is independently known from
a non-value class/interface declaration in the current IR module. It adds no
target construction to guaranteed views. External and foreign targets remain
unavailable until retained physical category authority exists. Thus the
current exact compiler-alias override can match an origin-independent
prediction while an independent source-alias probe remains a recorded
semantic-policy contrast. The old recognizer remains authoritative. See
[`../archive/generic-owner-physical-value-local-placement-comparison-2026-08-26.md`](../archive/generic-owner-physical-value-local-placement-comparison-2026-08-26.md).

The first local natural-interface selection uses one context-owned authority
lineage. `EARLY_REPRESENTATION_PLAN` contains only admitted local generic-class
TypeDefs. Once generic-interface bridge selection has chosen a complete
bounded family, `BOUND_DECLARATION_INDEX` adds the selected natural, class-
owner capability, and interface-family capability TypeDefs plus the recorded
class edge template. It does not re-read logical supertypes. The initial
executable adapter is intentionally restricted to an unconstrained class with
Object base and exactly one ROOT/OWNED/no-exact natural family. Its three
direct rows must be exactly natural `I<T>`, the class-owner capability, and the
interface-family capability. Every natural argument must be the invariant,
non-null default type of one source owner parameter. Any other physical row or
mapping makes the adapter unavailable.

A logical destination `I<T>` may construct a desired selector, but the
selector contributes no authority. The produced `C<!T>` fact must first obtain
`I<!T>` from closure over the recorded InterfaceImpl edge. Only then may
selected lineage choose that view and storage placement use its carrier.
Internal lineage is keyed by physical TypeDef identity; the diagnostic
projection records family kind and TypeDef view, so two physical interfaces
normalized to one Kotlin owner cannot collide. Removing the edge while leaving
the logical destination unchanged makes selection fail. `I<Any?>`, `I<T?>`,
stars, projections, and bound-remapped parameters cannot select a natural
construction.

The integration proof compares the selected storage with the unchanged
emitter's declared local and inspects the isolated emitted
`sourceAliasMatches` MethodDef body for exactly one natural
`I<!0>::produce` call. Each CLR profile then assembles and executes its own
corresponding emitted IL. Both the class-owner and interface-family semantic
capabilities are present as separately recorded views. Broad and open-nullable
controls retain empty lineage while their unchanged emitter locals remain
`object`.

This authority is `BOUND`, not sealed emission truth. It cannot control
emitter liveness, interface rows, calls, MethodDefs, MethodImpls, fields, state,
or serialized ABI. The BOUND index and callable query remain non-authoritative
until they are joined by a sealed-emission cross-check. The legacy class-owner
route analyzer does not classify direct natural-interface calls, so the local-
view slice alone makes no static-route claim. Production remains atomically
erased. See
[`../archive/generic-owner-local-physical-interface-view-2026-08-26.md`](../archive/generic-owner-local-physical-interface-view-2026-08-26.md).

The first callable route now extends that same BOUND lineage with one opaque
logical-member family. For the admitted parameterless direct producer, the
family derives and validates the natural interface MethodDef and the semantic
capability-interface MethodDef from their selected logical and capability
members. It does not accept an arbitrary pair of MethodDef identities. Both
endpoints must be distinct public abstract instance slots with zero method
parameters and no ordinary value parameters; the natural result is the exact
owner parameter under `STRICT_OWNER_OUTPUT`, while the semantic result is the
corresponding `object` slot. Suspend callables are outside this ordinary
signature grammar. A malformed or duplicate family conflicts, and an
unsupported family is unavailable.

For the admitted single-parameter covariant producer, logical receiver policy
selects one endpoint before value provenance is consulted. Exact `I<T>` selects
the natural MethodDef; `I<Any?>` and open `I<T?>` select the semantic capability
slot. The selected MethodDef is then an input to a pure operation query, never a
result guessed by that query. The query proves that the receiver already
guarantees the selected MethodDef's physical owner view and then substitutes
that construction through the recorded parameter and result layout. A required
view is a proof goal, not evidence. Exact provenance may support an exact
logical request, but may never narrow a genuinely broad request or trigger
fallback to another endpoint.

The live shadow runs after the existing routing fixpoint and compares this
prediction with the stable final natural/semantic route maps. Calls without
one unique successful POST storage fact are omitted rather than counted as
covered. Guarded semantic routing with a natural fallback is compatible with
the selected semantic entry, but remains distinguishable in the recorded
actual-route field. Nested functions and classes are traversal barriers. This
comparison neither mutates routing nor proves the final emitted MethodDef:
the existing isolated IL operand assertion remains independent evidence.
Production still publishes no authority or operation snapshots. See
[`../archive/generic-owner-physical-operation-route-shadow-2026-08-26.md`](../archive/generic-owner-physical-operation-route-shadow-2026-08-26.md).

Final emitter evidence is a separate one-way authority input. A simple
MethodDef's structured header decision is shared by IL rendering and
rehearsal observation; it is not reconstructed from rendered text. Raw
observations belong to successful render products and only the final surviving
fixpoint maps may publish them. Normalization maps actual emitter-owned
TypeDefs independently and retains their physical identity, arity/category,
explicit receiver, scoped generic parameters, parameter carriers, and result
layout. Diagnostic names never establish identity.

The first comparison is intentionally an overlay on the existing BOUND
direct-producer family. Both its natural and semantic interface MethodDefs
must have unique final evidence and the family joins failure atomically.
Missing evidence is unavailable; duplicate, wrong-owner, cross-scope, or
structurally different evidence conflicts. A source symbol rendered on the
natural and invariant-exact interface views may ignore the latter only when
the actual owner independently proves the same logical declaration, matching
physical arity, and interface category. That exception cannot apply to the
semantic endpoint or manufacture an exact view.

At that checkpoint this overlay was not
`SEALED_EMISSION_SIGNATURE_INDEX`. A partial set could not enter the sealed
epoch because an absent final declaration would otherwise survive from BOUND.
The comparison covered owner, arity/category, visibility, dispatch category,
instance/receiver shape, method arity, parameters, and result layout, while
exact CLI flags and physical names remained diagnostic. See
[`../archive/generic-owner-physical-methoddef-emission-comparison-2026-08-26.md`](../archive/generic-owner-physical-methoddef-emission-comparison-2026-08-26.md).

### First family-scoped sealed certificate

The first local seal now covers one already complete direct-producer
implementation family. Its actual evidence is constructed afresh from one
successful final-emission transaction: four TypeDefs with exact physical paths
and complete supported flag decisions, six MethodDefs with exact names,
supported flag decisions and signatures, and two explicit MethodImpl rows.
BOUND supplies the opaque keys and complete expected structural manifest used
to select, correlate, and validate those rows; none of its rows are copied into
the sealed index. The index is immutable and queryable only by those keys.

Binding is one transaction with `Known`, `Unavailable`, and `Conflict`
outcomes. Missing final rows remain unavailable. Duplicate or extra rows,
cross-scope evidence, path/nesting or category/flag disagreement, structural
drift, duplicate CLR coordinates, invalid special-name masks, and wrong
MethodImpl endpoints conflict and publish no authoritative rows. A CLR
MethodDef coordinate contains the final owner, exact name, instance convention,
generic arity, and printed explicit parameter carriers. It excludes the result
and implicit receiver; a split-nullable result contributes its hidden
`bool&` output parameter. The direct natural interface slot and typed
implementation entry must have equal final names for ordinary implicit CLR
mapping, but that is a rule of this family adapter rather than of the generic
sealed index.

This certificate is deliberately family-scoped and rehearsal-only. It changes
no route, recognizer, field/state decision, KLIB record, or production ABI;
production/off publishes no sealed families and remains erased. MethodDef
GenericParam rows, producer-recorded and retained-foreign adapters,
overlapping/compiler-wide family ownership, arbitrary members outside the
selected family, and broader callable grammars remain open. See
[`../archive/generic-owner-sealed-emission-signature-family-2026-08-26.md`](../archive/generic-owner-sealed-emission-signature-family-2026-08-26.md).

Open-nullable results compose through an independent physical result layout,
not a mutually exclusive member role. A callable contract records its
parameter domains, semantic input policies, virtual/MethodImpl identities,
and one of:

```text
Direct(resultSlot)
SplitNullable(payloadSlot, out bool& isNull)
```

The auxiliary flag is part of the physical MethodDef signature/ABI but not a
Kotlin value parameter. Consequently a future structural lookup can combine an
owner-dependent or broad-checked `!K` input with
`SplitNullable(!V, out bool&)` without a Map, member-name, or combined-role
exception. Split payload substitution follows the producer-recorded physical
type expression and the actual constructed generic argument; it must not
round-trip through a later logical `IrType` mapper. The method-result layout
does not by itself authorize split fields or state.

The first implementation of this consolidation is production-inert. It adds
shared physical-value vocabulary and a final-epoch shadow analysis, compares
that analysis with the existing bounded proofs, and changes neither emitted
routes nor the erased production inverse. Existing shape restrictions remain
until their individual hostile tests are explained by the shared model. If
the model needs declaration names, packages, stdlib identities, IR-origin
whitelists, logical-supertype reconstruction, serialized per-value facts, or a
general target-owned optimizer to reproduce those proofs, the consolidation
fails and the bounded implementation remains authoritative.

## Remaining gates

The local actual-only final-emission certificate is complete for its bounded
direct-producer family. Before that authority can be shared across families or
replace a recognizer, independently sourced producer-recorded and
retained-foreign adapters, MethodDef GenericParam rows, and overlapping/global
ownership must join the same fail-closed model. BOUND cannot fill any missing
final fact in those adapters.

Before another source-built Stdlib blocker is implemented, the rehearsal must
continue consolidating the local carrier proofs behind the model above in
shadow mode. It must compute product value facts without declaration, package,
stdlib, member-name, collection, or IR-origin exceptions; and explain the
existing immutable-alias, exact-helper, result-chain, generated-capture,
closed-semantic-input, MethodDef-authority, and split-nullable behavior without
changing emitted products. The hostile matrix must include nullable joins,
scoped owner and method parameters, mutable multi-construction joins, stars and
projections, value-type variance, mixed captures, deeper inheritance, separate
assemblies, and nullable/value-class substitutions.

Existing bounded recognizers remain fail-closed until the shadow model explains
both their positive behavior and their hostile negatives. They are removed one
by one. Production remains erased and its inverse is exercised throughout.

Before this draft may replace the erased-interface ADR, one atomic rehearsal
must cover:

1. general member-declaring children beyond one producer-output slot, the
   exact one-level invariant-property child, or its one-consumer sibling,
   and the bounded second consumer edge, including multiple child members,
   overloads, changed arguments, multiple parents, and deeper inheritance;
2. invariant member families beyond the admitted one-producer/one-consumer
   method root, exact mutable-property root, exact one-level property child,
    and exact property-root consumer child, mixed multiple-parameter families
    beyond the admitted parentless lookup family, and broader input-bearing
    child/interface compositions;
3. nullable-value, open-nullable, bounded, and value-class substitutions beyond
   the proven reference and `Int` input routes;
4. broad and `@UnsafeVariance` inputs beyond the upstream-defined fixed
   one-argument barrier and nested-input family, parameterized casts beyond the
   bounded warning-bearing covariant producer proof, mixed-control-flow cast
   returns, classifier-derived fields, and broader input parameters crossing
   separately compiled exact-looking boundaries;
5. Kotlin/C# properties beyond the exact mutable invariant cell, the broad-
   family owner-independent read-only primitive getter, and the covariant
   multiple-parameter producer-property vector, broader
   default families (including multiple members, properties, generic methods,
   diamonds, and reabstraction), hostile inheritance beyond the proven
   external default -> generic Kotlin override -> ordinary C# subclass chain,
   and ordinary foreign implementations beyond the proven producer, consumer,
   invariant-cell, exact-input method convention, and one contravariant default
   shapes;
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
