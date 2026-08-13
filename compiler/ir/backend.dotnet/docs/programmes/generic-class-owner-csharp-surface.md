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
compiler prototype still has to prove Kotlin-produced MethodImpl rows and a
C# subclass in another assembly.

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
