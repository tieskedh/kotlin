# Generic class owner direct C# surface

- Status: **Architecture design artifact — not an accepted export ABI**
- Date: 2026-08-12
- Programme:
  [`generic-class-owner-reopening.md`](generic-class-owner-reopening.md)
- Carrier and dispatch rules:
  [`generic-class-owner-carrier-matrix.md`](generic-class-owner-carrier-matrix.md)
- Explicit export programme:
  [`../decisions/draft-adr-explicit-csharp-export-surface.md`](../decisions/draft-adr-explicit-csharp-export-surface.md)

This document separates direct use of a truthful Kotlin-owned CLR `C<T>`
implementation from optional C# export conveniences. Reification is worthwhile
only if ordinary C# code can construct, inherit, override, and call the native
owner without a wrapper for representable contracts. It does not make every
Kotlin declaration automatically ergonomic C# source.

## Intended ordinary surface

For a representable Kotlin declaration such as:

```kotlin
open class Cell<T>(initial: T) {
    open var value: T = initial
    open fun write(next: T) { value = next }
    open fun contains(candidate: @UnsafeVariance T): Boolean = value == candidate
}
```

the intended everyday C# view is structurally:

```csharp
public class Cell<T>
{
    public Cell(T initial);
    public virtual T Value { get; set; }
    public virtual void Write(T next);
    public virtual bool Contains(T candidate);
}
```

That is the same implementation object and state used by Kotlin. C# does not
construct an adapter, exported twin, or erased `Cell` merely to use these
typed members.

The precise names remain subject to Kotlin overload/collision and export-name
rules. A truthful CLR signature does not excuse an ambiguous or unstable C#
identifier. Where the ordinary physical name is compiler-mangled, explicit
export may still supply a curated source name without replacing owner identity.

## Compiler semantic capability

Every construction also implements the recorded non-generic semantic
capability required by star/projection/widened Kotlin calls. Cross-assembly
Kotlin consumers need that interface to be metadata-public, but it is compiler
ABI rather than the everyday source API.

The candidate surface rules are:

- the capability is implemented explicitly, so its members do not appear as
  duplicate public methods on `Cell<T>`;
- the producer binding, not a C#-visible naming convention, identifies it;
- Kotlin import/reflection filters it from logical members;
- C# tooling may mark the capability as advanced/compiler infrastructure, but
  accessibility attributes are not relied on for correctness; and
- arbitrary foreign implementations of the capability do not become Kotlin
  `Cell<*>`; runtime classification uses the recorded open `Cell<>` TypeDef
  ancestry.

A determined C# caller can cast to compiler ABI, just as it can call other
metadata-public implementation details. That is not the supported ergonomic
path and cannot grant a second Kotlin identity.

## Broad candidate members

`contains` is not semantically just `Contains(T)` after a Kotlin covariant or
widened view: a physical `Cell<int>` may legally receive the candidate
`"wrong"` through Kotlin's semantic capability and must return the Common
result rather than throw during a cast.

The proposed direct C# surface therefore adds a protected semantic hook only
for member families whose slot-domain analysis requires a semantic body:

```csharp
public virtual bool Contains(T candidate);
protected virtual bool ContainsCandidate(object candidate);
```

The explicit capability dispatcher behaves as follows:

- a candidate compatible with physical `T` calls the public typed virtual, so
  an ordinary C# override is observed;
- an incompatible candidate calls the protected object-domain hook without
  first narrowing; and
- a Kotlin override emits a coherent typed/hook family so exact and widened
  Kotlin calls observe the same source override.

A C# subclass that overrides only `Contains(T)` gets natural C# behavior for
every compatible value. Incompatible Kotlin-only candidates use the inherited
semantic behavior. A C# subclass which intentionally customizes that widened
contract can override the documented protected hook. It never has to replace
state or implement a wrapper.

For a Kotlin implementation with a general widened body, the protected
semantic hook owns the one Kotlin algorithm and the public typed virtual is a
carrier-converting wrapper into it. That direction is intentionally visible
to C# subclass authors: compatible capability calls still reach a C# typed
override, while incompatible calls retain the inherited Kotlin semantic body.
The compiler must not implement the hook by narrowing and calling the typed
body.

Natural typed properties likewise do not promise a public `T` backing field.
If Kotlin widened mutation can store a value incompatible with physical `T`,
the one backing state must use the semantic object carrier so Kotlin retains
its delayed exact-use conversion failure; the public C# getter/setter remain typed wrappers.
Publishing a second typed field or rejecting the semantic write early would
be less interoperable with Kotlin semantics, not more.

There is a corresponding override limit: after an incompatible semantic
write, widened Kotlin read and typed `Read(): T` are genuinely different
physical entries. A C# subclass overriding only the typed read cannot define
the wider raw-object result. Such an owner may require a documented paired
semantic-output hook, a truthfully sealed/narrower direct surface, or rejection
from reified admission. The compiler may not claim full direct C# override
interop while allowing that override to vanish from widened Kotlin dispatch.

An abstract broad Kotlin member with no concrete semantic default is the hard
exception: implementing only `Contains(T)` cannot define behavior for an
incompatible candidate which Kotlin is still allowed to pass. A concrete C#
subclass must therefore implement the semantic hook as well, or remain
abstract. Supplying an invented `false`, narrowing first, or silently ignoring
the C# override would each lie about one side of the contract. Explicit export
may expose a narrower host-only abstraction when that is the desired API.

The separate-assembly CLR 4/CoreCLR probe now proves this rule against actual
C# producer/consumer compilation. Typed-only overrides work for compatible
values when an inherited semantic body exists; a consumer may separately
override the protected semantic hook; and a subclass of an abstract broad
member is rejected until it implements both obligations. The physical shape
is viable across assemblies, but Kotlin compiler emission and binding remain
the admission gate.

Kotlin's shared special-bridge table makes familiar collection candidates a
better case. `contains`, candidate `remove`, map lookup/removal, and list index
searches have specified incompatible results. Their dispatcher can return the
shared barrier value and needs no protected hook merely to reject the wrong
runtime type. `containsAll` is not such a case: its Common body must inspect
the nested collection and retain visit and failure behavior. This distinction
keeps the ordinary C# surface small without weakening Kotlin semantics.

The name and visibility of this hook are acceptance questions. If broad
members would produce an unintelligible protected surface or unavoidable
collisions, the declaration remains erased or uses explicit export; the
compiler must not hide narrowing behind a pleasant signature.

## Strict members, properties, and constructors

Strict input/output positions expose ordinary typed CLR members:

- `T` constructor parameters and fields/properties use physical `T`;
- a strict setter or `write(T)` narrows only at its true typed-use boundary;
- a getter/read returns physical `T` and boxes only through the semantic
  capability; and
- C# virtual overrides are reached by exact and capability calls.

Closed reference, value, nullable-value, and user-struct constructions are
ordinary C# types such as `Cell<string>`, `Cell<int>`, `Cell<int?>`, and
`Cell<MyStruct>`. A physically incompatible exact cast may fail immediately;
no adapter is created to delay an invalid write.

Kotlin default arguments remain Kotlin dispatch semantics. They do not become
C# optional parameters merely because a typed owner exists. Explicit export
may generate overloads after collision and versioning analysis.

## Nullability boundary

Roslyn nullable-reference metadata is additive and does not own Kotlin
nullability. Closed nullable value arguments use `Nullable<V>` and closed
nullable references use their reference type plus metadata.

An unconstrained Kotlin `T?` cannot be published as one statically truthful
CLR construction. C#'s annotated unconstrained `T?` likewise does not create
the conditional `Nullable<T>`/reference construction required by Kotlin's
logical type. A generic Kotlin factory may therefore return the semantic
capability physically even if it creates an exact closed owner at runtime.
That boundary is less ergonomic for C# and may need an explicit closed export;
it must not lie by returning `Cell<T>`.

NativeAOT may force semantic fallback or a stricter admission set for this
case. JIT success does not alter the public signature rule.

The same limitation is stricter on inheritance. One CLR `D<T>` cannot change
its metadata base from `C<T>` to `C<Nullable<T>>` according to whether a later
substitution is a reference or value type. Such a declaration must use one
tested fixed fallback or remain wholly erased/unadmitted. If a fixed
`C<object>`-like fallback is selected, ordinary C# must see that honest base;
an export facade cannot rewrite CLR ancestry or advertise `C<T?>` as though it
were one physical open construction.

The CLR 4/CoreCLR fallback probe confirms that the fixed base can keep one
state and correct virtual/`super` behavior. It also confirms that C# and
reflection necessarily see `C<object>` and reject assignment to either
`C<Nullable<int>>` or `C<string>`. That outcome is honest but not the intended
natural reified surface, so this declaration remains in the erased/fallback
admission class rather than being advertised as a direct `D<T>` success.

## Variance and projections

CLR classes are invariant. Kotlin declaration-site `out`/`in` and use-site
projections do not turn `Cell<T>` into a variant CLR class or manufacture a
`Cell<object>` base.

An additive CLR variant interface is allowed only when its complete exposed
member set is legal and semantically complete. For example, a genuinely
read-only producer capability may be covariant for reference substitutions,
but value-type variance still has CLR limitations and the interface cannot
stand in for mutable `Cell<T>` identity. Star/projected Kotlin uses retain the
non-generic semantic capability.

## C# inheritance contract

A supported direct C# subclass must preserve:

1. one inherited `Cell<T>` state and open-TypeDef ancestry;
2. normal override dispatch for constructors, strict members, and compatible
   candidate members;
3. protected semantic-hook dispatch for deliberately customized incompatible
   candidates;
4. Kotlin `is Cell<*>`, casts, KClass normalization, and callable dispatch;
5. default and `super` behavior selected by the producer binding; and
6. separate-assembly use without a generated per-consumer bridge.

The permanent direct CLR probe already proves the core compatible/incompatible
dispatcher shape and multi-level typed C# overrides on CLR 4 and CoreCLR. The
compiler prototype and raw metadata product now also prove producer-selected
MethodImpl rows plus a C# subclass in another assembly. This remains
production-inert admission evidence rather than the accepted public owner ABI.

## Imported CLR actuals and expect/actual

This model does not require a Kotlin-owned implementation when a platform
library already supplies the complete contract. A compatible imported CLR
class/interface can be the `actual` implementation directly when FIR, IR,
binding, overrides, nullability, and reflection all retain that exact foreign
identity. Users do not write a bridge merely because the declaration began as
`expect`.

When the contracts differ—iterator state, equality, mutation, candidate
domain, defaults, exception behavior, variance, or reflection—a bridge or
adapter represents a real semantic boundary. Reifying Kotlin-owned `C<T>`
reduces accidental bridges; it cannot erase genuine language differences.

## Direct surface versus explicit export

Direct owner interop and export solve different problems:

| Need | Direct `C<T>` owner | Explicit export |
| --- | --- | --- |
| construct/subclass/call truthful typed owner | primary path | should not add a twin |
| curated names and overloads | only when ordinary physical names suffice | yes |
| Kotlin default-argument convenience | compiler dispatcher remains | generated overloads possible |
| delegate/Task/BCL collection projection | not implied | adapter/facade when selected |
| unconstrained open-nullable return | semantic physical carrier | closed/fail-closed facade only when truthful |
| incompatible host/Kotlin contract | no fiction | explicit adapter documents the mismatch |

The ideal end state is therefore not “no adapters anywhere.” It is “no
adapter where CLR already expresses the complete Kotlin contract; explicit
adapters only where they carry real semantics or source ergonomics.”

## Acceptance tests

The architecture channel now proves one important slice of this surface. A
compiler-derived, record-driven open Kotlin subclass of an external generic
producer exposes the exact producer-selected typed/semantic overrides and
constructor, and a further C# generic grandchild overrides both paths. Roslyn
and both runtimes verify open generic ancestry, exact constructor shape,
ultimate MethodDef ownership, compatible typed dispatch, incompatible semantic
dispatch, direct `super`, and delayed typed-read failure. No member or signature
is selected by a C# naming convention. This is production-inert evidence, not
yet the accepted export surface.

The same consumer now renders an open-nullable construction factory from a
finite compiler record. Listed value/reference types use statically visible
exact `C<P(T?)>` constructions, while unlisted struct/reference types return
an honestly observable `C<object>` through the same semantic capability. The
factory contains no `MakeGenericType` or `Activator` closure. This is an AOT-
analyzer-clean internal construction mechanism, not permission to present a
fallback object as an exact typed C# return.

A paired application corpus now runs the current production-erased surface
beside that candidate on both CLR profiles and FIR parsers. C# directly
constructs and subclasses the actual Kotlin assembly with framework and user
structs, nullable/mixed state, arrays, a method generic, and two override
levels. Reflection records the present tradeoff without an adapter: the owner
has arity zero and `object`/`System.Array` member positions, while the
owner-independent `relay<R>` is a normal CLR method generic. The candidate
products remain record-driven and separate. This supplies a stable correctness
input for the following ergonomic and performance comparison; it does not yet
satisfy all acceptance cases below. See
[`../archive/generic-owner-application-corpus-2026-08-13.md`](../archive/generic-owner-application-corpus-2026-08-13.md).

The first such bounded comparison now records Framework CLR 4 and all .NET 10
deployment modes separately. The candidate is easier and more truthful for C#
owner construction and subclassing, but its present semantic-capability-heavy
workload is 1.62–2.96× the erased time and allocates 6.89–7.52% more. Because it
is a test-owned C# physicalization rather than a complete Kotlin product, its
published bytes and compile cost cannot select the surface. The result keeps
direct `C<T>` as the destination where the full contract works, but keeps
route attribution and representative Kotlin/C# applications as separate gates. See
[`../archive/generic-owner-paired-application-measurement-2026-08-14.md`](../archive/generic-owner-paired-application-measurement-2026-08-14.md).

The bounded route gate is now closed. It shows that the present direct C#
owner surface does not by itself eliminate object-state boxing, and that the
non-generic semantic capability plus compatibility check is the main cost even
for reference/array routes with no allocation. Method generics remain near
parity and NativeAOT can make typed arrays/compatible overrides competitive,
so the destination remains plausible. Acceptance now depends on
representative application call mixes and on preserving every valid Kotlin
widened operation, not on narrowing the semantic surface for speed. See
[`../archive/generic-owner-route-attribution-2026-08-14.md`](../archive/generic-owner-route-attribution-2026-08-14.md).

The direct surface now also has a true typed-state control. C# observes
`HostileTypedStore<T>` with a private `T` field and exact `T` read/write
signatures; direct calls never cross the capability. Its explicit non-generic
capability remains private in ordinary member discovery, rejects an
incompatible value before mutation, and provides the same-object widened
boundary. Exact Int, struct, and nullable routes remove per-iteration boxing,
while capability routes retain the measured object-domain cost. This proves
that truthful field generics are possible for compiler-proven owners, not that
the remaining acceptance matrix or representative product gate is complete.
See
[`../archive/generic-owner-typed-storage-attribution-2026-08-14.md`](../archive/generic-owner-typed-storage-attribution-2026-08-14.md).

The recursive product now has a real CLR property surface as well. Physical-
family schema 15 records each Property name and type together with the exact
logical getter/setter keys and their existing typed-entry MethodDefs. It does
not invent a property KLIB key or attach a semantic hook/capability dispatcher
to ordinary C# discovery. The record-driven producer exposes getter-only
`OctoTree<T>.depth: int`, get/set `Leaf<T>.value: T`, and getter-only
`Branch<T>.nodes: Node<T>[]`; a separately compiled C# consumer uses property
syntax on Framework 4.8 and .NET 10. A raw ECMA-335 reader checks the Property,
PropertyMap, and getter/setter MethodSemantics associations rather than relying
on reflection alone. The product also found a concrete authoring collision:
private state and a property cannot share one C# source name, so the producer
selects hidden physical backing-field names while retaining exactly one typed
field and the natural public property name. This closes the bounded direct-
property migration condition, not nullability annotations, collision policy,
or the complete acceptance matrix. See
[`../archive/generic-owner-direct-property-surface-2026-08-17.md`](../archive/generic-owner-direct-property-surface-2026-08-17.md).

The Kotlin-emitter rehearsal now proves the corresponding first inherited
interface surface directly. An exact invariant `Child<T> : Parent<T>` owns one
new natural `Property<T>` row and inherits the parent's row; ordinary non-
partial C# `Child<string>` and `Child<object>` implementations provide only
the two expected auto-properties. Kotlin projected parent and child operations
reach those same properties through the unique-construction fallback. The
compiler capability is neither a C# author obligation nor copied into CLR
Property metadata, and the separate producer manifest contains only the
child-owned accessor pair. This is one exact edge, not proof of arbitrary
foreign generic inheritance. See
[`../archive/generic-owner-invariant-property-child-2026-08-20.md`](../archive/generic-owner-invariant-property-child-2026-08-20.md).

The next inherited surface adds one direct `T` input above that property root.
An ordinary non-partial C# `ConsumerChild<string>` or
`ConsumerChild<object>` implements the inherited auto-property and one normal
`Consume(T)` method. Kotlin input projection reaches that method through the
unique-construction fallback; C# still implements no semantic interface. This
found and repaired an authoring analyzer defect: it evaluated the child-owned
consumer manifest without its inherited producer context and demanded
`partial`. The structurally exact invariant one-consumer fragment is now a
no-adapter child shape only when the concrete CLR child inherits a bound
complete producer manifest. Standalone consumers retain their generated object
adapter. See
[`../archive/generic-owner-invariant-consumer-child-2026-08-20.md`](../archive/generic-owner-invariant-consumer-child-2026-08-20.md).

The bounded grandchild proof confirms that this authoring rule composes one
level further. A non-partial C# `ConsumerGrandchild<string>` or
`ConsumerGrandchild<object>` implements the inherited property plus two normal
`Consume(T)` methods. Its transitive constructed interface list binds the
complete producer root, so neither consumer fragment requires an adapter.
Kotlin projected dispatch reaches the secondary natural method and the same
property state. This is exact depth-two evidence, not permission to accept an
arbitrary inheritance graph. See
[`../archive/generic-owner-invariant-consumer-grandchild-2026-08-20.md`](../archive/generic-owner-invariant-consumer-grandchild-2026-08-20.md).

The same bounded family now survives a real three-producer Kotlin assembly
chain: the property root is in `lib.dll`, the first consumer in `middle.dll`,
and the second consumer in `leaf.dll`. The fail-first leaf compile showed that
local declaration ownership cannot be reconstructed from an external IR
parent. Admission now joins the exact KLIB shape with the producer-recorded
full-arity owner and member families. Reflection proves every natural and
semantic TypeDef stays in its declaring DLL, while ordinary non-partial C#
grandchildren retain the same one-property/two-method surface. See
[`../archive/generic-owner-three-assembly-consumer-chain-2026-08-20.md`](../archive/generic-owner-three-assembly-consumer-chain-2026-08-20.md).

Physical-family schema 16 now closes the nullable-reference part of that
direct surface. Every MethodDef value slot, property, and physical state carries
the exact Roslyn preorder transform captured from the original IR type while
that type is still available. The transforms deliberately do not live on the
physical type identity: a semantic `object` carrier for open `T` must admit a
nullable substitution, while an exact `T` position is encoded non-null without
adding a CLR type-parameter constraint. The recursive product distinguishes
`Node<T>?` as `[2,1]` and `Node<T>?[]` as `[1,2,1]`; its exact `T` field/property
is `[1]`, and its widened nullable `object` result is `[2]`. Property accessors
and identity state accessors must carry the identical transform, and schema
decoding fails on unknown flags or the wrong structural length.

The generated producer uses `#nullable enable` and warnings-as-errors. Its raw
metadata oracle reads `NullableAttribute` scalar/vector blobs plus effective
`NullableContextAttribute` fallback directly from ECMA-335 rows on both
Framework 4.8 and .NET 10. This also fixed the older direct-export treatment of
an unmarked CLR type parameter from oblivious flag `0` to Roslyn's non-null flag
`1`; nullable substitutions remain legal because the signature gains metadata,
not a `class`/`notnull` CLR constraint. Open `T?` remains `object?`, since the
current bounded representation cannot truthfully encode it as unconstrained
CLR `T?`. Base/interface nullability and the remaining acceptance matrix stay
open. See
[`../archive/generic-owner-nullable-surface-2026-08-17.md`](../archive/generic-owner-nullable-surface-2026-08-17.md).

Physical-family schema 17 now closes the corresponding broad-property
condition. The hostile source adds a covariant
`var exposed: @UnsafeVariance T` over semantic object state. Its ordinary C#
surface is still the natural virtual `T exposed { get; set; }`, but the family
record also fixes the widened route for each accessor. Getter routing is either
the typed entry or a paired raw semantic hook. Setter routing is absent, typed,
or compatible-typed-else-semantic. A broad setter must use the last form:
compatible objects reach the virtual C# property; incompatible objects reach
the protected Kotlin semantic hook without a cast. Once semantic state holds
an incompatible value, widened read returns that exact object and typed
property read throws at its checked cast/unbox boundary.

The separate C# consumer overrides the typed property and the protected
semantic setter. It proves both override paths, recovery after an incompatible
write, one private `object` field, the exact property accessor MethodDef names,
and the five-entry explicit capability map on Framework 4.8 and .NET 10. The
Kotlin corpus executes the same widened-write/delayed-read-failure sequence on
the current erased backend. The static cross-library census attributes one
exact and three semantic property calls without a missing capability. Schema
validation rejects typed-only routing for either broad accessor, unknown route
names, and property/state disagreement. This supplies the paired semantic-
output hook required above without making C# users build an adapter or own a
second state object. See
[`../archive/generic-owner-broad-property-routing-2026-08-17.md`](../archive/generic-owner-broad-property-routing-2026-08-17.md).

Physical-family schema 18 now closes the abstract form rather than silently
narrowing it to the attractive C# property. For an abstract
`var exposed: @UnsafeVariance T` on a covariant owner, the public abstract
`T exposed { get; set; }` and the protected raw getter/setter hooks are all
real obligations. The base alone owns the two concrete explicit capability
dispatchers. A concrete C# subclass which supplies only the typed property is
therefore rejected by both supported C# toolchains; the compiler does not
invent a raw result or an incompatible setter result on its behalf.

A complete external C# subclass implements the typed property and protected
hooks over one `object` field. Compatible capability writes observe its typed
override, incompatible writes observe its semantic override, widened reads
return the raw state, and typed reads check at the use boundary. A generated
concrete Kotlin override uses the same shape. The latter exposed an ordering
bug in the architecture planner: inherited semantic roles were merged after
typed storage proof. Logical inherited semantic reachability now taints the
body graph before storage selection, making that field semantic without a
shadow. Reflection pins the abstract accessor/hook flags, the inherited
private-final interface map, and the one-field concrete implementations on
Framework 4.8 and .NET 10. See
[`../archive/generic-owner-abstract-broad-property-obligation-2026-08-17.md`](../archive/generic-owner-abstract-broad-property-obligation-2026-08-17.md).

Physical-family schema 19 closes the generated-name half of the overload
surface. Kotlin overloads keep one natural C# method name whenever their typed
CLR parameter lists are distinct. Their hidden semantic hooks and explicit
capability slots can erase to identical `object` signatures, so those compiler
members receive an unconditional digest of the complete sorted logical
override-root set. The name is consequently stable before any collision is
observed, stable when another overload is added later, and shared by a
separately compiled override. A masked-default helper similarly uses the
logical declaration key. Consumers bind the producer record rather than
reconstructing either name.

The record validates C# source identity in addition to CLR metadata identity:
return type, static/instance shape, and nullable annotations do not distinguish
C# overloads, and a method/property/field name collision is rejected. A
record-driven producer, external C# subclass, Kotlin consumers, raw metadata,
reflection, and interface maps prove the two-overload shape on Framework 4.8
and .NET 10. These generated names are internal ABI and intentionally do not
create a public `DotNetName` annotation; user-selected export names still need
their own proposal. See
[`../archive/generic-owner-overload-family-names-2026-08-17.md`](../archive/generic-owner-overload-family-names-2026-08-17.md).

The first repository application census sharpens that distinction. ArrayCopy
executes 5,664 local owner calls and every one has exact-entry provenance, but
its source-authored unchecked object-array initialization still requires
semantic array state. Direct `C<T>` construction would therefore improve the
owner/call surface without licensing a fictitious `T?[]` field. A paired C#
consumer remains part of the open representative-product gate. See
[`../archive/generic-owner-array-copy-application-census-2026-08-15.md`](../archive/generic-owner-array-copy-application-census-2026-08-15.md).

Recursive OctoTree now adds a second exact input and shows that useful CLR
typing need not wait for public owner reification. Its `Array<Node<T>?>` state
is already an honest `Node[]` because the production erased `Node<T>` has one
CLR classifier; direct `T` arrays and `T` value fields remain erased. The
application executes 5,941 exact and 3,096 semantic-capability local events.
That narrowed where canonical adapters may be needed. The later paired direct
C# product now proves its bounded typed state, callable, capability, and real
property surface, while the wider acceptance matrix below remains open. See
[`../archive/generic-owner-octo-tree-application-census-2026-08-15.md`](../archive/generic-owner-octo-tree-application-census-2026-08-15.md).

Before this surface is accepted, Roslyn must compile and execute:

- direct construction and typed property/method calls for reference, primitive,
  nullable primitive, enum, tuple, framework struct, and user struct arguments;
- one- and multi-level subclasses overriding strict and broad typed members;
- a subclass overriding the semantic hook intentionally;
- Kotlin calls through exact, star, projected, widened, default, and `super`
  paths into those subclasses;
- separate portable producer, Kotlin consumer, and C# consumer/subclass
  assemblies;
- nullable annotations and overload/collision behavior in generated C# source;
  and
- proof that capability members are absent from Kotlin reflection and ordinary
  C# member discovery while remaining bindable compiler ABI.

Failure of an ergonomic test may select explicit export. Failure of identity,
state, dispatch, or Kotlin semantics rejects the reified owner for that
declaration shape.
