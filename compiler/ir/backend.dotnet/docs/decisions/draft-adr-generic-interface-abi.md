# Draft ADR: Kotlin-owned generic interface ABI on CLR

- Status: **Draft — implemented candidate, frozen pending reassessment**
- Date: 2026-08-04
- Scope: generic interfaces, declaration/use-site variance, erased identity,
  typed capabilities, inheritance, iteration/collections, and C# consumption

## Context

Kotlin's interface type system is broader than CLR generic variance:

- declaration parameters may be invariant, `in`, or `out` in any combination;
- use sites may add `in`, `out`, or star projections;
- primitives, nullable values, and open parameters participate in the same
  Kotlin subtype relations as references;
- `@UnsafeVariance` may place a parameter in an otherwise forbidden position;
  and
- Kotlin generic interface casts are erased even on a reified runtime.

CLR variance conversions apply only to reference arguments and it has no
use-site projections. CLR also validates variance through complete nested
member signatures, so Kotlin-valid unsafe or invariantly nested members may be
illegal on a variant CLR interface.

`Collection<out E>` illustrates both problems: output operations are naturally
covariant, while `contains(@UnsafeVariance E)` cannot inhabit that same CLR
variant surface. Making the whole interface invariant would discard useful C#
variance; using only a generic identity would break Kotlin conversions through
value types.

## Reopened after generic-class erasure

This candidate predates the decision to give every Kotlin-owned generic class
one non-generic physical owner. That decision materially weakens the blanket
case for typed interface siblings. A physical erased implementation of:

```kotlin
class Values<T> : Source<T>
```

cannot truthfully implement one closed CLR `Source<T>` per logical Kotlin
construction. It can universally implement only the erased `Source` identity;
`Source<object>` is not an equivalent substitute for `Source<Int>` or
`Source<String>`.

Typed interface views can still be useful for a non-generic implementation
with a concrete logical argument, a deliberately selected BCL mapping, or an
explicit C# export. Those are bounded capabilities, however, rather than proof
that every Kotlin-owned generic interface needs two or three TypeDefs and a
bridge family. Boxing is already the normal Kotlin ABI cost once the owning
class and canonical interface are erased.

The class-erasure migration now enforces that boundary. An erased generic
class whose interface edge mentions one of its own logical parameters (for
example `Values<T> : Source<T>`) implements only the canonical interface and
receives only canonical `MethodImpl` bridges. It does not invent
`Source<object>`. A closed edge independent of the erased owner parameter (for
example `Values<T> : Source<String>`) may still publish the existing truthful
typed capability. This is a class-ABI correctness rule, not a decision to keep
or remove the split interface declaration candidate itself.

The same boundary applies to DIM dispatch. A default physically hosted by
typed `I<T>` is not in the ancestry of canonical-only `C<T>`, so the erased
class needs the existing helper materialized as one canonical `MethodImpl`.
This does not add `I<T>` to the class. A closed, truthfully implemented typed
edge continues to inherit its native DIM directly.

The reassessment therefore compares:

1. one erased interface as the default Kotlin-owned ABI, with typed CLR views
   admitted only by an explicit mapping/export contract; and
2. the implemented canonical/declared/exact split below.

It must measure actual typed consumers and unboxed execution against TypeDef,
bridge, `MethodImpl`, default-body, metadata, C#-authoring, and maintenance
cost. Imported CLR generic interfaces are outside this choice and retain their
native identity. Special mappings such as Common `Comparable<T>` to the BCL
must be evaluated as mappings, not used to infer a blanket source-declaration
rule.

No new feature may expand the implemented split candidate while this question
is open. This reopening does not itself authorize deleting the existing views;
that requires one producer/consumer migration and the full interface/default-
method gate.

## Non-negotiable invariants

1. One non-generic canonical interface is the only Kotlin identity ABI.
2. A legal Kotlin subtype/projection conversion never allocates an adapter.
3. Typed CLR views are capabilities on the same object, never Kotlin cast
   identity.
4. Kotlin `is D<*>` and unchecked `as D<T>` test only canonical identity.
5. KLIB, not CLR signatures, owns logical arguments, projections, bounds,
   nullability, and `@UnsafeVariance`.
6. Multiple physical slots for one Kotlin member are observationally one
   logical contract.
7. Imported CLR interfaces retain their native identity and variance limits;
   crossing incompatible foreign/Kotlin contracts requires an explicit
   identity-changing adapter.
8. Canonical-only and older implementations remain valid providers through
   the universal fallback.

## Candidate representation

### Canonical identity

Each Kotlin-owned generic interface declaration gets one non-generic canonical
CLR interface. It contains erased member slots needed for universal storage,
dispatch, and Kotlin runtime type checks.

Every Kotlin type construction and projection of that declaration maps to the
same canonical identity for fields, parameters, returns, ordinary upcasts,
`is`, `as`, and `as?`. Logical construction and projection remain in IR/KLIB.

Canonical member identities derive deterministically from logical owner and
callable signature rather than declaration order or the current overload set.
Exact physical spellings are compiler ABI pinned by metadata/tests, not source
API.

### Top-level overloads over a canonical carrier

Ordinary fields, parameters, and returns use canonical identity. Consequently,
top-level overloads that differ only in a Kotlin-owned interface's logical type
argument can share one CLR signature. Substituting a constructed declared or
exact capability solely to preserve the overload would violate canonical
identity, reject value/open covariance, and exclude canonical-only providers.

The physical name must instead be deterministic from authoritative declaration
metadata and independent of the current overload set. When an admitted Common
stdlib template already supplies an explicit platform name, the .NET stdlib
projection pins that spelling and records it in the self-describing library's
physical binding. `Iterable<Byte>.sum()` through `Iterable<Double>.sum()` are
the first case: their logical names remain `sum`, while the existing Common
template names their erased physical methods `sumOfByte` through
`sumOfDouble`.

The selector-return overload family uses the same rule on the part of the
logical signature that distinguishes its Common declarations. The admitted
`Iterable<T>.sumOf((T) -> Int)` declaration remains logical `sumOf`, while its
physical CLR method is `sumOfInt`. This spelling comes from the same Common
template's explicit platform name; it is not inferred from the currently
admitted overload set or from CLR return-type overloading.

The current source generator renders that platform-name record as `@JvmName`
because JVM was the only mature target with this collision when the template
was designed. On a non-JVM target `@JvmName` is an optional expectation and is
erased before IR, so Kotlin/.NET cannot truthfully consume it as retained
metadata. Instead, the backend owns one exact projection table keyed by the
logical `Iterable` element type and pinned to the six spellings from the Common
template. It applies only to compiler-owned generated stdlib implementations.
It does not assign general .NET semantics to user `@JvmName`, infer logical
identity from an annotation, or replace the KLIB/manifest binding. A
declaration with no entry in this bounded authoritative projection remains
subject to atomic collision rejection until a general versioned naming codec
is designed.

### Declared generic view

The compiler also emits a generic sibling carrying the declaration's legal CLR
variance vector. It inherits the canonical identity and exposes only members
whose complete physical signatures satisfy CLR variance recursively.

This gives C# and exact Kotlin call sites natural typed access for safe output
or input operations. It does not own Kotlin identity and may be absent on a
canonical-only foreign provider.

Logical widening may change to another declared interface already implemented
by the same object only where CLR reference variance admits it. Value/open
variance falls back through canonical identity without an adapter.

### Complete invariant operation view

When declaration-safe variance cannot expose every Kotlin member, emit an
invariant generic exact sibling. It carries unsafe/invariantly nested member
signatures and inherits the declared/canonical views as appropriate.

An implementation may expose canonical, declared, and exact views on one
object. Guarded call routing prefers the strongest truthful capability and
falls back to canonical dispatch. Failure of a typed probe is not a Kotlin cast
failure.

Optional per-operation capabilities may be added only when required by a
public export/stdlib contract or justified by measured benefit. They never
become canonical identity or required implementation surface by accident.

The representation is positional and supports arbitrary generic-parameter
counts; it must not encode capabilities in fixed 32- or 64-bit masks.

## Member placement and calls

The view builder analyzes each member's full physical signature, including
nested generic types, method parameters, constraints, property accessors,
returns, and substituted supertypes.

- variance-safe members inhabit the declared view;
- unsafe or otherwise invalid members inhabit the exact view;
- every necessary member has a canonical erased fallback; and
- one property may place accessors on different views only while a complete
  legal property row remains available on the exact view.

Calls use the receiver's logical Kotlin type to select a possible declared or
exact capability, guard it at runtime, and otherwise use canonical dispatch.
Arguments/results box, cast, or unbox only at the selected boundary. The
receiver and arguments evaluate once.

A canonical member result may pass through an object-typed compiler local
before the frontend uses that local again where an open non-null type
parameter is required. That local read is also an erased boundary: codegen
emits `unbox.any !n`/`!!n` for that exact expected token. ECMA-335 defines the
instruction for both reference and value-type instantiations; it is the CLR
counterpart of the JVM backend's checkcast/unbox at a generic erased read. A
bad value fails at that use, as Kotlin's erased model requires. The rule is
limited to object-backed local reads whose concrete consumer requires an open
type parameter. It is not a general object-to-arbitrary-type coercion and does
not optimize or prevalidate a failing cast.

Collection special bridge semantics remain Common-owned: wrong-shaped
`contains` returns false and `indexOf`/`lastIndexOf` return `-1`. An ordinary
user `@UnsafeVariance` member retains ordinary cast failure unless Common
defines a barrier. The generic-interface machinery selects views; it does not
invent library behavior.

## Implementations and bridges

Kotlin-compiled classes implement every required view on the same object.
Compiler-generated forwarding members and explicit `MethodImpl` rows connect
canonical, declared, exact, inherited, and source implementations.

A base class that owns a complete bridge set supplies it to descendants while
forwarding virtually to overrides. An abstract obligation without a body does
not manufacture a bridge; the first concrete implementation owns it.

Generated/source physical collisions are checked after all views, accessors,
helpers, and substitutions are known. A real same-owner duplicate atomically
rejects the declaration and library publication. Reserved-looking source names
at different owners remain legal; explicit MethodImpl ownership, not blanket
name bans, prevents accidental slot capture.

Whole-declaration rejection is the safe temporary policy where stable
disambiguation has not been designed. No declaration or bridge is silently
dropped.

## Inheritance and intersections

Generic supertypes are substituted recursively through the Kotlin graph before
physical views are built. Repeated, permuted, diamond, open, and cross-module
paths must converge on one set of logical obligations.

When several parents contribute same-named slots and Kotlin selects one
derived logical member, the derived view owns an explicit physical
intersection slot. Its versioned metadata records:

- logical owner/member identity;
- declared or exact physical view;
- complete CLR signature and constraints; and
- the sorted contributing logical members.

The slot is an implementation obligation, not proof that a body already
exists. A class bridge/MethodImpl attaches the chosen body. A descendant reuses
an inherited selected slot rather than creating another. Name equality alone
never justifies an intersection; incompatible overloads or unresolved
constraints reject publication.

Selected default bodies follow the profile-aware default-interface ADR rather
than being recast as bodyless intersections.

## Use-site projections and casts

Use-site `out`, `in`, and star projections retain canonical storage identity.
The compiler may call a typed capability only where the projected logical
operation is sound. A projection never causes wrapper allocation or changes
`===`.

Kotlin runtime checks ignore generic arguments and test canonical identity.
CLR checks of constructed typed views are C# capability tests, not Kotlin
`is`. Casts to reified CLR classes remain a separate representation problem and
must not silently strengthen Kotlin erasure.

## Cross-module metadata

The self-describing DLL records, by stable logical Kotlin identity:

- canonical, declared, exact, and optional capability TypeDefs;
- physical member signatures and slot mappings;
- bridges and intersection obligations; and
- versioned naming/representation grammar.

Consumers never reconstruct these facts from generated names or reflection.
They use KLIB logical identity plus the physical binding records and reject
unknown incompatible schemas. Producer and consumer compiler versions must
preserve canonical-only fallback unless an explicit pre-ABI schema break moves
all participants together.

## Imported CLR interfaces

Foreign CLR interfaces remain native CLR types. Reference-variance conversions
are accepted only where CLR permits them. Value-type conversions are rejected,
and open parameters require sufficient CLR reference constraints.

The importer does not fabricate canonical Kotlin identities for arbitrary
foreign types. An explicit adapter into a Kotlin-owned interface may exist as
interop but changes object identity and is never hidden behind ordinary Kotlin
subtyping.

## C# authoring and consumption

C# may use declared or exact interfaces as honest typed capabilities and the
canonical interface as the universal fallback. A foreign implementation that
exposes several views must keep their operations observationally equivalent.

Generated partial-type authoring, manifests, helper/default calls, and analyzer
diagnostics are owned by the
[C# interface-authoring ADR](adr-csharp-interface-source-authoring.md). They do
not redefine canonical Kotlin identity.

## Iterator, Iterable, Collection, and List cases

`Iterator<out T>` and `Iterable<out T>` are ordinary instances of this model:
each has a canonical identity and covariant declared sibling. Kotlin
covariance across `Int` and `Any` preserves one object and cursor state.

An exact `Iterator<T>` call may use its typed next operation without boxing;
a widened or canonical-only provider uses the erased fallback. Kotlin
`hasNext`/`next` and `NoSuchElementException` semantics remain distinct from
CLR `IEnumerator<T>` state/exception rules.

The typed `Iterable<T>` operation returns canonical Iterator identity because
a canonical-only provider cannot promise a nested typed iterator capability.
A future C# convenience may probe that nested capability explicitly.

Generic iterator subinterfaces build their own canonical/declared views and
inherit the corresponding base views. Redeclared logical members own real CLR
slots and bridges; they are not removed as redundant.

`Collection<out E>` and `List<out E>` add invariant exact views for unsafe
input operations while retaining covariant declared output views. `List`
composes `Collection` views rather than introducing a bespoke ABI. Mutable
families, Set, Map, Sequence, Comparable, Continuation, ranges, and reflection
interfaces must use the same general algorithm when their complete constraints
are supported.

Array iterators/iterables are ordinary private stdlib implementations and
receive the same bridge lowering as user classes. Direct array loops may stay
indexed intrinsics; escaping iteration calls a stdlib factory. BCL
`IEnumerable<T>`/`IEnumerator<T>` adapters are explicit foreign projections,
not canonical Kotlin identity.

## Consequences

- Every legal Kotlin interface view has one durable identity, including value
  and open projections.
- C# receives native variance and typed capabilities wherever truthful.
- User and stdlib interfaces use one lowering/metadata algorithm.
- Exact execution and optimization can evolve without revising Kotlin casts or
  `===`.
- Each declaration may add several physical interface types, bridges, and
  guarded calls.
- KLIB physical-view metadata is required for separate compilation.

## Promotion conditions and open decisions

Before promotion, validate:

- mixed variance with reference, primitive, nullable, value-class, and open
  arguments across arbitrary parameter counts;
- exact, widened, projected, star, mutable-storage, canonical-only, and
  capability-absent paths;
- unsafe members, Common collection barriers, properties, generic methods,
  recursive bounds, and inherited intersections;
- generic/default/suspend/fun interfaces and the remaining stdlib families;
- Kotlin and C# implementations across modules and both runtime profiles;
- version-skew behavior, collisions, atomic rejection, and stale-schema
  diagnostics; and
- bridge/metadata size and measured typed-path benefit without weakening
  erased fallback.

Reserved physical spelling, generic-class projection, additional optional
capabilities, and final helper/attribute encoding remain open. None may violate
canonical identity, erased casts, same-object conversions, KLIB authority, or
explicit foreign-adapter boundaries.
