# Draft ADR: Kotlin-owned generic interface ABI on CLR

- Status: **POC representation decision; implementation incomplete**
- Date: 2026-07-17
- Scope: Kotlin-owned generic interfaces, declaration-site and use-site variance, erased identity,
  typed CLR execution, and C# consumption

This is a repository-local design record for the experimental .NET backend. The entire `dotnet`
branch is a proof of concept. This document is neither a public Kotlin commitment nor a KEEP.

## Context

Kotlin's interface type system is broader than CLR generic variance:

- declaration parameters may be invariant, `out`, or `in`, in any combination and arity;
- use sites may independently project invariant parameters with `out`, `in`, or `*`;
- value types and open type parameters participate in the same Kotlin subtype relations as
  reference types;
- `@UnsafeVariance` may deliberately put a declaration parameter in an otherwise forbidden
  position; and
- generic arguments are erased for Kotlin runtime type tests even when a target has reified
  generics.

The CLR can mark interface and delegate parameters covariant or contravariant, but its variance
conversions apply only to reference-type arguments. It has no use-site projections. Its variance
validator also examines the complete physical member signature recursively, so a Kotlin-valid
member can be invalid on a CLR-variant interface when a parameter occurs through an invariant CLR
type. CLR generic constructions are therefore useful typed views, but cannot own Kotlin interface
identity or Kotlin runtime type semantics.

`Collection<out E>` demonstrates that this is not only a primitive-boxing problem. Kotlin's
`contains(@UnsafeVariance E)`, `List.indexOf(@UnsafeVariance E)`, and
`Map.containsValue(@UnsafeVariance V)` cannot be declared on a CLR interface which marks the same
parameter `out`. Conversely, making the whole CLR interface invariant would unnecessarily discard
native C# covariance for getters, iterators, and other output-only operations.

The existing erased callable and iterator ABIs are concrete instances of the same identity versus
execution separation. A general interface ABI must preserve that invariant without hard-coding
stdlib declaration names.

## Decision drivers

The representation must:

1. preserve the same object and `===` across every legal Kotlin interface conversion;
2. cover value, reference, nullable, value-class, and open-generic arguments;
3. support arbitrary numbers and mixtures of invariant, `in`, and `out` parameters;
4. preserve Kotlin use-site projections, star projections, and erased `is`/`as` behavior;
5. support stdlib and user-authored interfaces through one compiler rule;
6. expose the strongest legal typed CLR surface without making it Kotlin's semantic ABI;
7. give C# callers an explicit typed bridge with an erased fallback;
8. keep old, foreign, and canonical-only implementations usable;
9. preserve identity across assemblies through explicit KLIB/physical-ABI metadata; and
10. run without default-interface-method dependencies on both .NET Framework 4.8 and modern
    CoreCLR.

## Invariants

The following rules are non-negotiable for this POC:

1. **The canonical interface is the only Kotlin identity ABI.** All physical typed views are
   execution or interop capabilities on the same object.
2. **A legal Kotlin subtype conversion never allocates an adapter.** It is a reference copy or a
   change to another interface already implemented by the same object.
3. **Typed capabilities never define Kotlin casts.** Kotlin `is D<*>` and an unchecked
   `as D<T>` test only the canonical identity, following Kotlin erasure. A CLR `is D<int>` is a C#
   capability test, not the lowering of Kotlin `is`.
4. **KLIB metadata is authoritative.** CLR signatures do not reconstruct Kotlin type arguments,
   projections, nullability, bounds, or `@UnsafeVariance`.
5. **Imported CLR interfaces are not rewritten.** Their native CLR identity and variance
   restrictions remain visible. Any adapter either into a Kotlin-owned interface or between two
   otherwise-incompatible foreign constructions is explicit and changes identity; `===` is never
   redefined to hide it.
6. **Typed and canonical slots are one logical contract.** An implementation which exposes more
   than one physical view must make every typed/exact member observationally equivalent to the
   corresponding canonical bridge. A foreign implementation which violates that rule violates
   the Kotlin-owned interface contract; capability-dependent behavior is not permitted semantics.

## Implemented POC checkpoint

The general canonical/declared/exact bridge and call-routing machinery is implemented for local
and separately compiled Kotlin-owned generic interfaces. Iterator, ListIterator, and Iterable use
canonical plus covariant declared views. `Collection<out E>` is the first common-stdlib
declaration to exercise all three roles:

```text
Kotlin.Collections.Collection
    get_Size(), IsEmpty(), ContainsErased(object), GetIterator(), ContainsAll(Collection)

Kotlin.Collections.Collection<E> : Collection, Iterable<E>       // E is covariant
    get_Size(), IsEmpty(), GetIterator(), ContainsAll(Collection)

Kotlin.Collections.Collection__KotlinExact<E> : Collection<E>    // E is invariant
    Contains(E)
```

Read-only List composes those same roles rather than inventing a collection-specific
representation:

```text
Kotlin.Collections.List : Collection
    Get(int), IndexOfErased(object), LastIndexOfErased(object),
    GetListIterator([int]), SubList(int, int), plus redeclared Collection slots

Kotlin.Collections.List<E> : List, Collection<E>                 // E is covariant
    E Get(int), GetListIterator([int]), SubList(int, int),
    plus declaration-safe redeclared Collection slots

Kotlin.Collections.List__KotlinExact<E>
    : List<E>, Collection__KotlinExact<E>                         // E is invariant
    Contains(E), IndexOf(E), LastIndexOf(E)
```

Both ListIterator views physically redeclare the common declaration's Iterator members. This is
not redundant implementation code: each logical owner has a real CLR slot, including when a base
class supplies `next` and `hasNext` and a derived class adds ListIterator.

The canonical `contains` MethodImpl uses the common `SpecialBridgeMethods` identity to return
`false` before narrowing a wrong-shaped value. The generated test works for concrete primitives,
references, nullable primitives, nullable references, and an open `T`; an unrelated user
`@UnsafeVariance` member is separately pinned to retain ordinary cast failure. List reuses that
false barrier and the common `-1` policy for wrong-shaped `indexOf`/`lastIndexOf` arguments.
Same-module IL/box, KLIB/DLL consumption, and raw CLR calls cover the Collection, ListIterator, and
List views, including nested canonical results and List's exact Collection super-view. Generated
C# helper facades and minimal per-operation capability types remain pending, so this checkpoint
validates the identity/execution representation rather than finalizing the public interop surface.

An ordinary C# implementation now pins the manual foreign-implementor contract against one
`netstandard2.0` Kotlin library on both Framework CLR 4 and CoreCLR 10. The same foreign object
implements `Collection__KotlinExact<int>` and therefore the inherited canonical identity; its
typed `Contains(int)` and erased `ContainsErased(object)` are observationally coordinated, and the
erased member returns `false` for wrong reference and null shapes. A second foreign object
implements an ordinary user `@UnsafeVariance` interface and deliberately retains the normal CLR
cast failure instead of receiving the collection barrier. Kotlin catches that original
`InvalidCastException` as `ClassCastException`. This validates the current public view spelling,
same-object contract, and barrier distinction; generated C# adapters/analyzers and the broader
foreign-implementor matrix remain pending.

Library publication also now fails on three independently replayed physical collisions: a
property accessor and user method mapping to the same declared-view slot, the corresponding clash
which exists only on the invariant exact view, and a user declaration occupying the generated
exact-view TypeDef identity. Each failure names the affected physical view or generated-type
collision and produces neither KLIB nor DLL. This pins the current whole-declaration rejection
policy without declaring the broader overload, inheritance, and reserved-member collision matrix
complete.

A separately compiled 65-parameter interface now validates that the canonical/declared/exact
representation has no 32- or 64-bit capability-mask limit. A portable producer and Kotlin/C#
consumers on both application profiles execute same-object widening, high-index typed reads through
canonical fallback, exact-capability discovery, and wrong-shape failure at the high-index unsafe
operation. This adds evidence for the selected positional representation; it does not add a new
physical view or change the ABI decision.

That portable fixture also completes the one-through-four parameter matrix with
`Quad<in I, out O, X, out N>`. It combines simultaneous contravariant/covariant widening,
invariant state, a primitive result, a nullable result, an exact-only unsafe operation, and an
open generic pass-through. Kotlin and C# consumers execute the same canonical fallback and
identity rules on both profiles while reflection pins the declared and exact variance vectors.
The nullable result is concretely `Int?`: the exact construction retains `Nullable<int>`, while
the logical widening to `Any?` cannot use CLR value-type variance and therefore exercises the
boxed canonical path on the same interface object.

A raw CLR provider now implements only a portable producer's non-generic canonical `Source`
identity and recorded erased member slot. It exposes neither the declared nor exact generic
capability. The producer's separately compiled Kotlin `readAsAny` function executes that same
object on Framework CLR 4 and CoreCLR 10. This validates capability-absent fallback on both
profiles; it does not claim compatibility with the pre-canonical experimental representation.

## Physical views

One logical Kotlin declaration may have up to four physical roles. Type and helper names below are
descriptive; their final public spelling remains a compatibility decision. The POC does already
reserve deterministic canonical member names so the test ABI does not depend on return-type-only
hiding.

### 1. Canonical identity

Every Kotlin-owned generic interface has a fully erased, non-generic canonical interface. It owns
the complete logical member set in recursively erased physical form.

```text
Producer<out T>       -> Producer
Consumer<in T>        -> Consumer
Source<X, out T>      -> Source
MutableBox<T>         -> MutableBox
```

This applies to invariant declarations as well as declaration-site variant declarations. An
invariant `MutableBox<T>` can still be used as `MutableBox<out Any>`, `MutableBox<in String>`, or
`MutableBox<*>`; retaining `T` in the only identity would leave those Kotlin views without a stable
cross-module CLR representation.

Canonical erasure is recursive and type directed:

- concrete primitive and non-dependent reference types remain concrete;
- an occurrence of a declaration type parameter becomes its canonical carrier, normally
  `System.Object`;
- a Kotlin-owned generic interface occurrence uses that declaration's canonical identity;
- a generic class occurrence uses its class projection/facade ABI when one exists, otherwise the
  enclosing member cannot receive a stronger public typed promise than `object`;
- method-local type parameters remain method parameters when their bounds are representable; and
- property accessors, extension/context receivers, and lowered suspend parameters follow the same
  rules as ordinary parameters and returns.

Canonical and typed slots are keyed by stable logical declaration/member ABI identities rather
than source names or their current erased signatures. When two logical overloads erase to the same
CLR signature, differ only by a return type, or unify after generic substitution, the emitter uses
stable declaration-derived physical names and explicit `MethodImpl` records. The same registry
resolves collisions between a generated canonical `D`, a source-declared non-generic name, and
reserved capability/helper names. Adding a later overload must not rename an existing physical
slot.

The current POC spells a canonical method as
`<source-name>__KotlinErased__<128-bit-logical-slot-digest>`. The digest is derived from the public
Kotlin `IdSignature` (or an equivalent structural identity for non-exported declarations), never
from declaration order, offsets, or a process-local hash. Canonical property metadata receives the
same reserved suffix and points at reserved accessor names. Declared and exact capabilities retain
ordinary source member names for C# use. This particular spelling remains provisional, but the
requirements for deterministic per-slot names and an indexed physical mapping do not.

### 2. Declared CLR view

The compiler also emits a generic view with the logical declaration's complete parameter list and
declaration-site variance vector:

```text
Producer<out T>           -> Producer<T> : Producer
Consumer<in T>            -> Consumer<T> : Consumer
Source<X, out T>          -> Source<X, T> : Source
Mixed<in A, out B, X>     -> Mixed<A, B, X> : Mixed
```

The generic view inherits the canonical interface. A C# implementation of `Producer<int>` must
therefore also satisfy `Producer`; it cannot become a typed-only object with no Kotlin identity.

Only members whose final physical signatures are valid under CLR variance appear on this view.
Validity is computed recursively after physical type mapping, not inferred from source syntax.
The analysis composes these positions:

- a method result is positive;
- a value parameter, extension receiver, or context receiver is negative;
- a mutable property is both positive and negative;
- nested `out` preserves the sign, nested `in` reverses it, and invariant nesting requires both;
- by-reference CLR parameters are invariant; and
- base-interface instantiations and generic constraints obey the CLR's own recursive validity
  rules.

`@UnsafeVariance` suppresses Kotlin's source diagnostic only. It does not relax CLR metadata
validation, so an unsafe/opposite-position member is omitted from the declared CLR view.

A Kotlin-owned generic interface nested in such a member signature still uses its own canonical
identity unless that API is an explicit host-language export which separately guarantees the
nested closed capability. A typed outer view must not imply that an arbitrary canonical-only
nested provider implements `Nested<T>`.

### 3. Complete invariant operation view

When the declared view cannot contain every logical member, the compiler emits one complete typed
operation capability with all declaration parameters invariant:

```text
CollectionExact<E> : Collection<E>
MapExact<K, V> : Map<K, V>
MixedExact<A, B, X> : Mixed<A, B, X>
```

It contains the typed forms of every otherwise representable member omitted from the declared CLR
view. Every member with a CLR-representable exact signature therefore has a typed home: either the
declared view or this complete operation view. The all-invariant parameter vector makes
opposite-position and invariantly nested signatures legal. It also gives C# one discoverable
interface to implement when it wants the complete typed Kotlin contract.

Every newly compiled concrete Kotlin implementation supplies the canonical and declared views and,
when the interface has omitted representable members, its complete exact view on the same object.
An abstract implementation may defer those slots to its first concrete descendant. Modules built
against an earlier version of this canonical ABI, foreign providers, and C# types may be
canonical-only or may implement the declared view without the complete operation view. Calls must
therefore retain the canonical fallback.

### 4. Minimal operation capabilities

One complete exact view is sufficient for correctness, but it can miss a typed call after an
unrelated parameter was widened. For example:

```kotlin
interface Bi<out A, out B> {
    fun containsFirst(value: @UnsafeVariance A): Boolean
}
```

An object may expose `BiExact<String, Int>`. A call through `Bi<String, Any>` cannot cast to that
full exact construction even though `containsFirst` depends only on `A`.

The physical ABI may therefore expose a minimal operation capability keyed by the member ABI and
the declaration parameters actually required by its typed signature:

```text
Bi.ContainsFirstCapability<A> : Bi
```

All declaration parameters of an operation capability are invariant. Its parameter list contains
only declaration parameters which occur in the member's final typed physical signature; a generic
member retains its own method type parameters. Capabilities with the same required parameter set
and non-conflicting physical signatures may be grouped. The compiler never emits the power set of
declaration parameters speculatively; the number of capabilities is bounded by the distinct sets
used by actual members.

Minimal capabilities are execution and C# bridge mechanisms, not identities. A stdlib or exported
member whose typed signature depends on a strict subset of its declaration's parameters must
materialize the corresponding minimal capability. Otherwise an unrelated projection or widening
would unnecessarily destroy the promised typed host-language path. Non-exported members may defer
that metadata surface during the POC and remain semantically correct through the complete exact
view and canonical fallback.

Minimal capabilities recover only changes to unrelated parameters. If a parameter actually used
by an operation is itself widened—for example `Int` to `Any`—the original invariant capability is
not the widened capability. The canonical boundary, including any required boxing, is then the
correct general path unless bounded provenance proves and deliberately invokes the original shape.

## Example lowering

```kotlin
interface Mixed<in I, out O, X> {
    fun run(input: I, state: X): O

    fun acceptsOutput(value: @UnsafeVariance O): Boolean
}
```

becomes conceptually:

```text
Mixed {
    object RunErased(object input, object state)
    bool AcceptsOutputErased(object value)
}

Mixed<in I, out O, X> : Mixed {
    O Run(I input, X state)
}

MixedExact<I, O, X> : Mixed<I, O, X> {
    bool AcceptsOutputExact(O value)
}

optional MixedAcceptsOutput<O> : Mixed {
    bool AcceptsOutputExact(O value)
}
```

A generated implementation owns one typed source body. Compiler-generated methods forward the
other physical slots to it, with casts/unboxing on typed input and boxing/widening on erased
output. There is no adapter object.

## `Any`, value types, and open parameters

The canonical carrier of Kotlin `Any`/`Any?` is `System.Object`, as decided by the Any foundation.
The object implementing an interface never changes during a Kotlin widening, but values crossing
the member boundary may change representation:

| Kotlin value | Exact CLR path | Canonical `Any` path |
| --- | --- | --- |
| `Int` | `int32` | boxed `System.Int32` |
| `String` | `string` | the same reference as `object` |
| reference class | class reference | the same reference as `object` |
| value class | supported unboxed carrier | boxed value-class representation |
| open `T` | CLR `T` | conditional boxing to `object` |

Thus `Producer<Int>` and `Producer<String>` both become `Producer<Any>` by viewing the same
producer as canonical `Producer`. Producing through that `Any` view returns `object`; boxing an
`Int` there is required by the semantic type boundary. A caller which still knows `Int` may probe
`Producer<int>` and avoid the box.

The backend must never attempt to make `Producer<int>` a CLR subtype of `Producer<object>`. When a
C# API specifically requires the latter closed construction, an explicit wrapper is unavoidable
and its distinct identity must remain observable.

`Any` and `Any?` deliberately share the `System.Object` carrier, but they remain distinct Kotlin
types. A complete C# import/export surface must use nullable metadata plus the platform-type policy
to recover that distinction where possible; a bare CLR `object` signature alone cannot encode it.

## Imported CLR generic interfaces

An interface imported from a C# library remains a foreign CLR type. The importer preserves its
generic arity, variance flags, constraints, and member signatures; it does not synthesize the
canonical Kotlin identity described in this ADR. Calls and conversions therefore obey the CLR
type relation, including its reference-only treatment of generic variance.

For example, given a covariant C# interface and consumer:

```csharp
public interface IProducer<out T> { T Produce(); }
public static void Consume(IProducer<object> producer) { /* ... */ }
```

Kotlin may pass an imported `IProducer<String>` to `Consume`, because the CLR has the corresponding
reference conversion. It must reject an imported `IProducer<Int>` at that call site: `Int` maps to
`int32`, and `IProducer<int32>` is not a CLR subtype of `IProducer<object>`. The fact that Kotlin
`Int` is a subtype of Kotlin `Any`, whose carrier is `System.Object`, does not create a CLR generic
conversion.

An open CLR type parameter is not uniformly rejected. A special reference-type constraint such as
`where T : class` is sufficient for the CLR variance conversion from `IProducer<T>` to
`IProducer<object>`. An unconstrained or merely interface-bounded `T` is not, because it may be
instantiated with a value type. Kotlin's ordinary `T : Any` bound is likewise not reference-shape
proof: Kotlin `Int` satisfies it. The current POC type mapper conservatively rejects all such open
foreign conversions until CLR special constraints are imported.

Crossing that boundary requires an explicitly requested adapter, conceptually:

```kotlin
val ints: IProducer<Int> = csharpLibrary.producer()
csharpLibrary.consume(ints.boxedProducer())
```

The adapter implements `IProducer<object>`, delegates to `IProducer<int32>`, and boxes each produced
value. Its allocation and distinct reference identity are observable; ordinary Kotlin subtype
conversion never inserts it. An imported invariant interface remains invariant even for reference
arguments, and an imported contravariant interface follows the symmetric CLR rules.

This differs from a C# class deliberately implementing a Kotlin-owned interface. Such a class may
implement the generated canonical and typed views on the same object and thereby participate in
the Kotlin ABI without an adapter. Merely importing an arbitrary CLR interface never grants that
identity.

## Use-site projections and runtime casts

Kotlin logical projections stay in IR and KLIB metadata. Ordinary Kotlin fields, parameters,
returns, mutable locals, and cross-module signatures always use the canonical identity, including
when their current arguments happen to be CLR-reference-compatible. This keeps one storage ABI and
prevents a later value/open instantiation or projection from changing a declaration's physical
signature.

Codegen may transiently view a proven object through the declared generic interface for one typed
call. Immutable local provenance may recover such a capability, but never changes the stored
semantic identity. An explicit C# export is the other place where a declared generic view may
appear in a public signature, because that export is a separate host-language projection rather
than Kotlin-to-Kotlin ABI.

Runtime tests remain Kotlin-erased:

```text
value is D<*>        -> test canonical D
value as D<String>   -> unchecked logical argument; cast canonical D
value as? D<out Any> -> safe cast canonical D
```

The compiler must not strengthen these checks to `D<string>` or `D<object>`. A later call performs
an exact capability probe and otherwise invokes the canonical slot. C# may deliberately test a
closed generic capability, but that result is not a Kotlin type test.

The failure/null rules follow the mature targets. A failed non-null `as` surfaces the CLR
`InvalidCastException` mapped as Kotlin `ClassCastException`; a null value cast to a non-null
target is rejected by the backend's mapped Kotlin NPE check because CLR `castclass` alone accepts
null. A nullable hard-cast target permits null, and `as?` returns null for either null or a
classifier mismatch. For `is D<*>?`, null is a successful match; codegen therefore distinguishes
the null-input case before `isinst`, which by itself returns null for both absence and mismatch.

## `@UnsafeVariance` and type-safe barriers

`@UnsafeVariance` is processed by the member-placement algorithm for stdlib and user declarations;
there is no collection-name special case in interface generation. The typed member goes to an
invariant operation view, while the canonical interface keeps the erased slot.

The behavior of a wrong-shaped erased argument is declaration specific:

- designated Kotlin collection/map bridges use the shared special-bridge policy (`contains` and
  `remove` return `false`, `indexOf`/`lastIndexOf` return `-1`, and selected map operations return
  `null` or their default argument);
- ordinary user-authored `@UnsafeVariance` merely accepts Kotlin's intentional unsafety and may
  fail during the generated cast/unbox; and
- the backend must not generalize collection defaults to every annotated member.

The special policy is recorded by member ABI identity in metadata and shared backend logic, not
rediscovered from a source name alone.

## C# surface

The CLR export surface distinguishes identity from typed execution. A factory which constructs a
known exact implementation may return the declared view directly:

```csharp
Producer<T> ProducerOf<T>(T value);

Producer<int> ints = ProducerOf(42);
Producer<string> strings = ProducerOf("value");
Producer anyInts = ints;
Producer anyStrings = strings;
```

The last two assignments are inherited-interface conversions and preserve
`ReferenceEquals`; an `AsAny` helper would only be convenience syntax for the same upcast. The
factory type parameter is unconstrained unless the Kotlin declaration has a faithfully mappable
bound. In particular, `where T : struct` is not a representation of Kotlin `T : Any`: it would
exclude strings, reference classes, and nullable cases.

A canonical receiver can recover a requested exact shape through a C#-usable generated helper:

```csharp
T ProduceAs<T>(Producer receiver)
{
    if (receiver is Producer<T> typed)
        return typed.Produce();
    return (T)receiver.ProduceErased();
}

void ConsumeAs<T>(Consumer receiver, T value)
{
    if (receiver is Consumer<T> typed)
        typed.Consume(value);
    else
        receiver.ConsumeErased(value);
}
```

`ProduceAs<int>` on an integer producer takes the unboxed declared-view path.
`ProduceAs<object>` on that same producer cannot make `Producer<int>` into `Producer<object>` and
therefore uses the canonical path, boxing the result. `ProduceAs<object>` on a string producer may
use native CLR reference covariance and returns the same string reference. The consumer helper is
symmetric: a `Consumer<object>` may consume an `int` through the canonical path when CLR value-type
contravariance cannot express the conversion. Crossing an actual value through `object` boxes the
value; preserving the interface object's identity does not and cannot remove that value-boundary
cost.

A canonical-receiver helper is a guarded operation, not a conversion which attaches a trustworthy
logical `T` to the receiver. For example, `ConsumeAs<int>` on a canonical provider whose erased
contract actually expects strings may fail in the erased bridge. Only a successful closed
capability test, or an API contract which already guarantees that capability, proves the typed
shape.

For a member omitted from the declared view, the declaring assembly emits a C#-usable static or
extension facade with a distinct typed name:

```csharp
bool ContainsTyped<T>(Collection receiver, T value)
{
    if (receiver is CollectionOperation<T> operation)
        return operation.ContainsExact(value);
    if (receiver is CollectionExact<T> complete)
        return complete.ContainsExact(value);
    return receiver.ContainsErased(value);
}
```

An overload whose receiver is `Collection<T>` provides normal generic inference. A canonical
receiver overload permits C# to request a known original shape explicitly. The helper remains
statically typed even when it reaches the erased fallback; a value boxes only on that fallback.
Every helper evaluates its receiver and arguments exactly once. The general probe order is the
call-site-shaped declared view when that view owns the member, then the minimal operation
capability, the complete exact view, and finally the canonical slot. Failed capability probes are
not proof that the canonical provider is invalid.

Canonical physical member names must not shadow the intended C# helper accidentally. In
particular, an inherited instance `Contains(object)` would prevent C# from considering a
same-named extension method. The POC therefore gives the canonical slot its deterministic reserved
erased name; a typed bridge can use a source name or a distinct helper name such as
`ContainsTyped`. Prototype names such as `$Exact` are not acceptable as the only public C# access
path.

Every Kotlin-to-Kotlin ABI field, parameter, and return uses the canonical identity. An explicit
C#-friendly export may instead promise `Producer<T>` only when the implementation or input
contract guarantees that capability. Known stdlib factories can make that promise. Arbitrary
canonical-only or foreign values require a guarded helper or an explicit adapter.

## Inheritance and implementation

The physical hierarchy is built by logical member ABI identity:

1. canonical `D` inherits canonical identities of Kotlin-owned superinterfaces;
2. declared `D<T...>` inherits canonical `D` and each CLR-valid declared super-view;
3. complete exact `DExact<T...>` inherits declared `D<T...>` and compatible complete super-views;
4. a concrete implementation directly lists every representable declared, complete, and minimal
   super-capability required by the logical supertype closure but not inherited through those
   physical edges; and
5. diamond paths deduplicate the same logical slot before generating `MethodImpl` bridges.

If a Kotlin supertype instantiation is not CLR-variance-valid, the declared view inherits only its
canonical superidentity. The logical Kotlin supertype remains in KLIB metadata. The emitter must
detect CLR generic-interface unification conflicts rather than relying on unspecified runtime
dispatch.

New concrete Kotlin classes implement every required view automatically, including user-authored
interfaces. An abstract class may defer an unimplemented logical slot; the first concrete
descendant owns the forwarding bridge. No annotation, runtime-specific base class, or adapter is
required. A C# class may:

- implement the complete exact view for the full typed Kotlin contract;
- implement the declared view plus canonical erased members and rely on helper fallback for
  exact-only operations; or
- implement canonical identity only as an erased provider.

When it implements several views, its typed/exact methods and canonical methods must implement one
logical behavior. Generated Kotlin implementations enforce this by forwarding all physical slots
to one source body. C# tooling should eventually offer a generated base/adapter or an analyzer to
make the rule easy to satisfy; until then, inconsistent results are a foreign contract breach in
the same sense as an ordinary C# implementation violating any Kotlin interface invariant.

Default-body placement is owned by the accepted
[`adr-profile-aware-interface-default-implementations.md`](adr-profile-aware-interface-default-implementations.md).
Every generic default has one canonical semantic body and one stable helper ABI identity. On
`net48` and `netstandard2.0`, the helper owns the moved body while canonical, declared, and
operation slots remain abstract; class and view methods are forwarding or representation adapters
only. On `net10.0`, one strongly typed DIM owns the body. The complete exact view is the normal
strongly typed C# surface and reaches that DIM without an erased-result cast. Declared-variance
and erased views virtually adapt to the canonical typed slot, with explicit `MethodImpl` mappings
where their physical slots differ. The retained helper selects the canonical DIM nonvirtually for
qualified-super calls, compatibility, and portable-default promotion. No view, promotion, class
bridge, or implementing-class forwarder contains another lowered copy of the Kotlin body.

Logical method bounds remain in Kotlin metadata. A method constraint which depends on an owner
parameter of a split Kotlin generic interface is omitted from executable CLR method metadata:
placing it on a variant declared view load-poisons that CLR interface, while placing it only on an
invariant exact DIM rejects valid Kotlin calls through widened variance views. Arguments and
results remain exact; this narrowly weakens only the unrepresentable physical constraint. A future
C# export facade may restate such a constraint for ergonomics, but cannot become the Kotlin
dispatch slot. All other representable constraints remain physical. Kotlin override resolution
remains authoritative in every profile.

## Cross-module ABI metadata

Physical views must be exported explicitly in the Kotlin/.NET KLIB-to-assembly index. Consumers
must not derive a capability name by appending `$Exact` or by assuming an arity. Each logical
interface record includes:

- the full logical declaration and member signatures, including bounds, nullability, projections,
  method type parameters, and `@UnsafeVariance` occurrences;
- canonical physical identity;
- declared generic view and variance vector;
- complete and minimal operation capability identities;
- logical-member ABI key to canonical/typed physical slot mappings;
- inherited final view-adapter bundles on derived non-generic interfaces, keyed by owning logical
  interface, inherited logical member, and physical view;
- capability type-parameter index mappings;
- logical override-group identities and emitted declared-superview edges;
- erased bridge behavior, including any special type-safe barrier policy; and
- representation version and optional-capability flags.

Kotlin import and reflection reconstruct one logical `D<T...>` declaration from this record. They
do not publish the canonical, declared, complete, and minimal physical views as competing Kotlin
declarations. CLR reflection may observe those physical interfaces because cross-assembly callers
must be able to implement and probe them.

When the arbitrary CLR assembly importer is added, a foreign signature mentioning the generated
declared `D<T...>` or complete exact sibling maps back to the one logical Kotlin `D<T...>` type.
A signature mentioning only canonical `D` maps to `D<*>`, never silently to `D<Any?>`; it proves
identity but carries no logical arguments. Exact and minimal capability classifiers remain hidden
as separate Kotlin declarations.

A module already built against this canonical representation remains callable when it exposes only
canonical identity and no optional capabilities. A newer consumer probes an advertised capability
only when the producing ABI records it or the runtime object is being tested guardedly. This
permits capability growth without changing Kotlin identity.

Pre-ADR binaries which used reified `D<T>` as Kotlin identity do not implement the new canonical
`D`. They require an ABI-version rejection or an explicit migration adapter; this draft does not
claim accidental binary compatibility with that experimental representation.

## Imported CLR interfaces

This representation applies only to Kotlin-owned declarations. Imported CLR interfaces retain
their native generic identity and CLR reference-only variance. A Kotlin conversion which CLR
cannot represent is diagnosed unless the program explicitly requests an adapter into a
Kotlin-owned identity or into another foreign closed construction. The adapter is a real object
and therefore has its own reference identity.

The compiler must not synthesize hidden wrappers, redefine `===`, or make foreign interfaces
pretend to implement canonical Kotlin identities.

A C# class which explicitly implements the generated Kotlin `D<T>` view is not such a foreign
interface: because `D<T> : D`, it participates in the Kotlin-owned identity contract on the same
object. The explicit-adapter rule applies to unrelated CLR interface identities.

## Dependencies on generic classes and other language features

An interface member can mention a Kotlin variant class such as `Pair<out A, out B>` or the
`Result<out T>` value class used by `Continuation<in T>`. CLR classes and structs cannot declare
generic variance. The recursive member-validity analysis therefore uses the actual physical class
projection ABI. Until that ABI exists, affected members remain canonical/exact-only and cannot be
claimed as part of the CLR-declared-variance surface.

Complete stdlib support also requires defined lowering for nullable primitives, value classes,
`Unit`, suspend signatures, generic methods and bounds, default interface bodies, and reflection.
The first `Nothing` dependency is now defined: both bottom nullabilities use the runtime-owned
uninstantiable reference carrier, which permits `EmptyIterator`/`EmptyList` typed capabilities
without changing canonical identity. The remaining features consume this identity decision; they
do not create alternative interface identities.

A generic Kotlin `fun interface` follows this interface identity decision. SAM conversion and a
`System.Delegate`/`Func`/`Action` projection are explicit callable/export adaptations and do not
create another Kotlin interface identity.

## Implementation sequence

The current POC now implements the canonical non-generic identity, declared generic sibling,
complete invariant exact sibling, stable canonical member keys, generated same-object bridges,
projection/star storage erasure, classifier-only `is`/`as`/`as?` against the canonical identity,
guarded typed calls, and an initial cross-module physical-view index. It remains a provisional
implementation rather than a landed ABI: minimal operation capabilities, C# helpers/exports,
arbitrary CLR assembly import and explicit adapter generation, nullable host metadata, complete
version-skew metadata, and several generic-language shapes are still absent. Casts to reified CLR
generic classes remain rejected: using their closed construction would silently strengthen
Kotlin's erased runtime check.

Runtime-owned `Iterator<T>`, `ListIterator<T>`, `Iterable<T>`, `Collection<T>`, and `List<T>` now
use this same representation. Their CLR-generic siblings are same-object capabilities, and
stdlib/user implementations use the general bridge lowering. The former collection-specific
bridge table and call path have been removed. Primitive iterator classes remain a bounded
bootstrap exception until the target stdlib produces them normally.
The target stdlib's non-generic `EmptyIterator : ListIterator<Nothing>` and
`EmptyList : List<Nothing>` objects validate that one physical object can expose the precise
bottom-typed capabilities while arbitrary `List<T>` views continue through canonical fallback.

Implementation proceeds in dependency order:

1. add explicit physical-view metadata and a fully erased canonical root for Kotlin-owned generic
   interfaces;
2. make the declared generic view inherit canonical and emit only recursively CLR-valid members;
3. emit complete invariant operation views, bridges, and guarded call routing;
4. preserve erased Kotlin casts and add use-site/star-projection storage lowering;
5. add C# helpers, required stdlib/export minimal capabilities, and measured opt-in minimal
   capabilities for non-exported declarations;
6. generalize inheritance, generic methods, properties, bounds, nullability, and cross-module
   implementations; and
7. migrate stdlib interfaces from bespoke bootstrap contracts after their matrices pass;
   Iterator/ListIterator/Iterable and read-only Collection/List are complete POC slices, while
   Set, Map, and the mutable families remain pending.

At every step an unsupported shape is rejected before IL emission. The backend must never emit
known-invalid CLR variance metadata as a temporary fallback.

## Required validation

Promotion requires generated IL, Kotlin execution, C# compilation, cross-module, and both-runtime
coverage for at least:

- one through four mixed invariant/`in`/`out` parameters are covered, and a generated 65-parameter
  interface proves the current ABI does not depend on a fixed 32- or 64-bit machine-word mask;
- simultaneous widening/narrowing of several reference, primitive, nullable, value-class, and
  open parameters;
- exact, partially widened, fully canonical, mutable-storage, parameter, field, and return paths;
- direct and recursively nested `@UnsafeVariance` on every parameter position;
- collection special barriers and an ordinary user unsafe member which throws on a wrong shape;
- `D<*>`, independent `out`/`in` use-site projections, and `is`/`as`/`as?` erasure;
- inherited, permuted, repeated, and diamond generic superinterfaces;
- erased overload collisions, return-type-only physical collisions, generic/non-generic source
  name collisions, reserved generated-name collisions, and properties with independently placed
  getters and setters;
- generic methods, recursive bounds, default bodies, fun interfaces, and suspend members;
- `Iterator`, collections/maps, `Comparable`, `Continuation`, property delegates, reflection
  interfaces, ranges, and marker-only interfaces;
- Kotlin and C# implementations; a raw canonical-only provider and capability-absent fallback now
  execute on both profiles, while actual old/new compiler-version directions and explicit adapters
  remain required;
- imported CLR reference-variance successes, value-variance rejections, reference-constrained
  open successes, and unconstrained/interface-bounded open rejections;
- C# `Producer<int>`, `Producer<string>`, canonical `Producer`, `ProduceAs<object>`, exact and
  fallback `ContainsTyped`, and `ReferenceEquals` paths;
- same-object identity across every ordinary Kotlin conversion; and
- metadata/bridge size, exact-path boxing, partial-capability benefit, and erased fallback cost.

## Consequences

Benefits:

- all legal Kotlin interface views have one durable identity, including value/open projections;
- C# receives native `in`/`out` where CLR can honor it and typed helpers where it cannot;
- user interfaces and stdlib interfaces share one lowering algorithm;
- exact execution and future optimization can evolve without revising Kotlin casts or `===`; and
- canonical-only implementations remain valid across module and compiler-version boundaries.

Costs:

- each generic interface gains a non-generic identity plus at least one generic metadata type;
- unsafe or physically invalid members add an invariant operation view and bridges;
- canonical value paths box and typed calls add guarded capability probes;
- stable physical member/capability metadata is required in KLIB; and
- a complete C# surface requires generated helpers rather than relying only on instance members.

## Non-decisions

This ADR does not finalize the spelling of reserved physical names, attribute encoding, helper
naming, signing or versioning of the eventual runtime/stdlib assemblies, generic-class projection ABI, or which
additional non-exported minimal operation capabilities meet the benchmark threshold for permanent
publication. Required stdlib/export dependency-minimal capabilities are part of the selected C#
surface, not this non-decision. The remaining choices may change during the POC, but they may not
violate the identity, cast, ownership, and explicit-adapter invariants above.
