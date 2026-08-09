# Draft ADR: Kotlin callable and callable-reference ABI on CLR

- Status: **Draft — implemented candidate under pre-ABI evaluation**
- Dates: 2026-07-15 through 2026-07-16
- Scope: function values, execution capabilities, function references,
  property references, local property tokens, and future reflection

## Context

Kotlin function types are generic and variant even when arguments or results
are primitives, nullable primitives, or open type parameters. Conversions such
as `() -> Int` to `() -> Any` must preserve the same object and therefore
Kotlin `===`.

CLR generic-interface and delegate variance applies only to reference-type
arguments. `System.Func` and `System.Action` also split value- and void-return
shapes. Adapters can bridge unsupported cases but create another observable
object. A reference-only fast representation and value-type wrapper
representation would give one Kotlin rule two identities.

Property references add a reflective identity, getter and optional setter, and
function-type invocation. Local delegated-property tokens are weaker: they
carry truthful name/mutability but have no standalone callable declaration.

JVM uses specialized reflection classes because its runtime already owns that
hierarchy. Native and Wasm compose references from ordinary callable objects;
that is the closer precedent for a target without full reflection.

## Decision drivers

The candidate must:

1. preserve every Kotlin function-type variance conversion without allocation;
2. use one cross-module identity for primitives, references, and open types;
3. keep typed execution and foreign delegates optional layers;
4. preserve reference identity and structural callable-reference semantics;
5. support Framework CLR and modern CoreCLR; and
6. leave room for reflection without making CLR signatures authoritative for
   Kotlin logical types.

## Rejected canonical representations

### `System.Func` and `System.Action`

Rejected as Kotlin identity. Value-type variance is incomplete, Unit uses a
different family, and adapters break ordinary Kotlin `===`.

### Generic Kotlin-owned function interfaces

Rejected as canonical. CLR variance still fails through value-type
instantiations and open substitutions.

### A representation selected per closed type

Rejected. Storage and module boundaries would need conversion rules, and some
legal subtype conversions would allocate.

## Candidate decision

### One erased Kotlin callable identity model

Kotlin-owned non-generic execution interfaces are the only canonical storage
and invocation identity. The Common `Function<R>` view maps to a non-invokable
marker; a fixed function type maps to its execution interface solely by arity. The fixed
range is derived from Common's `BuiltInFunctionArity.BIG_ARITY`: Kotlin/.NET
publishes `Function0` through `Function22`, matching the JVM boundary. Arity 23
and above uses the accepted arity-classified vararg `FunctionN`
representation and is not approximated by adding target-specific fixed
interfaces. Logical parameter/result types remain in IR and KLIB. See
[`big-arity-callables.md`](big-arity-callables.md).

An ordinary function-type subtype conversion is an instruction-free reference
copy. It never creates an adapter, including for primitives, nullable values,
or open type parameters.

Generated callable objects implement one erased invocation bridge:

- reference arguments cast on entry;
- value/open arguments unbox from the universal carrier;
- value results box on exit; and
- Unit bodies execute as void and return the canonical Unit object from the
  erased bridge.

Captures, mutable-reference cells, and bound receivers are fields of generated
objects, not alternative callable identities. Non-capturing singleton caching
is an allocation optimization and not ABI.

Explicit user implementations and older modules may expose only the erased
contract. It remains the universal fallback permanently.

### Reflection is an orthogonal view on the same object

A direct function-reference object implements both a non-invokable
`KFunction`/`KCallable` view and exactly one `FunctionN` execution view.
Invocation or widening changes only the interface view of the existing object.
Lambdas and adapted references without a KFunction source type may remain
FunctionN-only.

The currently durable reflection facts are the callable name,
declaration-owned runtime annotations and logical signature, and positional
invocation through the callable's existing execution capability. Annotation discovery is governed by
[`callable-annotation-discovery.md`](callable-annotation-discovery.md); it uses
the reference's existing exact target without defining member lookup. Owner
lookup, member enumeration, accessor objects, and named/default reflective call
still require a separate reflection model and Kotlin metadata contract.
Positional invocation is governed by
[`callable-positional-invocation.md`](callable-positional-invocation.md).

### Function-reference `Any` behavior is structural

Generated rich function references use one runtime implementation base that
records stable logical target identity, arity/adaptation flags, and bound
values. Equality and hashing compare those facts with Kotlin structural
semantics; rendering follows Kotlin/Native-style callable text.

Two expressions for one declaration may therefore compare equal despite
different generated classes. Overloads, different adaptations, and different
bound values remain distinct. Lambdas and explicit user implementations retain
ordinary identity behavior unless their class overrides `Any` members.

The implementation base is metadata-public only for cross-assembly generated
subclasses. It is not a callable interface, storage type, or user API. Its
exact field/member layout remains compiler/runtime implementation ABI.

### Typed execution is an optional capability

Eligible generated callables may additionally expose closed exact execution
capabilities. Codegen probes a logically safe closed shape and calls it when
present; otherwise it invokes the erased bridge. This follows JVM's typed body
plus erased bridge pattern without making the typed member canonical storage.

A narrower typed-arguments capability is admitted for non-Unit arity-one and
arity-two callables with primitive-shaped arguments and object-shaped logical
results. It avoids argument boxing across module boundaries while erasing only
the result. Higher arities, Unit, and broader partial shapes require separate
evidence before adding metadata-public runtime contracts.

Every optional call path:

- evaluates receiver and arguments once;
- is guarded by runtime interface capability;
- may use immutable-local provenance only when the recovered logical shape is
  proven stable;
- performs only legal Kotlin argument/result widening; and
- retains erased fallback for fields, parameters, returns, mutable locals,
  user implementations, and older modules.

Consumers may not assume widening causes a callable to expose the widened
exact interface. Optional execution never changes object identity or adds
required members to `FunctionN`.

### Property references reuse callable identity

Non-generic Kotlin-owned `KPropertyN` and `KMutablePropertyN` identities carry
name/mutability and erased `Get`/`Set` operations. An immutable property
reference also implements the corresponding `FunctionN`; a mutable setter is
an ordinary `FunctionN+1` callable. No property-specific execution identity is
introduced.

Following Native/Wasm, a private runtime wrapper stores:

- property name;
- a lowered getter callable; and
- an optional lowered setter callable.

It delegates `Get`, `Set`, and inherited invocation to those callables. The
common lowering evaluates a non-trivial bound receiver once and shares it
between getter and setter objects.

Generated modules construct wrappers through compiler-internal runtime
factories. Wrapper class names are private implementation details. Explicit
user `KPropertyN` implementations use the same erased slots but do not inherit
private-wrapper equality or optional exact capabilities.

### Property-reference equality follows contained references

Two runtime-owned property wrappers are equal only when wrapper kind, name,
getter reference, and optional setter reference match. Hashing uses the same
components; bound receivers participate through the contained structural
function references. Rendering states the property name and that Kotlin
reflection is unavailable.

This adds no public KProperty member, wrapper identity, or alternative callable
shape.

### Local delegated-property tokens are name-only

A local delegated `val` receives a private `KProperty0` token and a `var`
receives a `KMutableProperty0` token. Name, mutability, and rendering are
truthful. `Get`, `Set`, and invocation fail with the mature-target unsupported
local-reference behavior because no standalone declaration exists to invoke.

Tokens store no getter/setter callable and retain ordinary object identity.
They are not structurally equal to full property references.

## Identity boundary

Identity preservation applies to ordinary Kotlin function/property subtype
conversions. Semantic adaptation—SAM conversion, adapted callable references,
or foreign delegate projection—may create another object because it is not a
Kotlin type upcast.

Typed CLR delegates and C# facades are owned by the
[explicit C# export draft](draft-adr-explicit-csharp-export-surface.md). They
may not replace or leak into canonical Kotlin signatures.

## Reflection boundary

This draft does not define full `KCallable` metadata, reflective lookup,
accessor objects, coroutine-aware named suspend invocation, or getter/setter back-links.
The separately accepted annotation slice retains declaration annotations on
the same reference object but intentionally adds none of those capabilities.
In particular, exposing private stored FunctionN values as JVM-like property
accessors would not satisfy the required KFunction/property contract.

Any later reflection layer must consume KLIB logical signatures, preserve one
canonical callable object, and keep erased invocation as the universal
fallback.

## Consequences

- Every legal Kotlin function variance conversion preserves `===`.
- Function and property references share one erased callable foundation.
- Optional typed execution can evolve independently of storage identity.
- Logical callable types cannot be reconstructed from CLR signatures; KLIB is
  mandatory across Kotlin modules.
- Erased fallback may box/cast, and guarded optional paths add probes.
- Private wrapper/capture layouts remain replaceable implementation details.

## Promotion conditions and open decisions

Before promotion, validate:

- cross-module KLIB preservation of logical callable/property types;
- every supported arity, variance, Unit, capture, bound receiver, and explicit
  user fallback across both runtimes;
- stable structural reference identity across modules;
- representative application benchmarks for retained optional capabilities;
- adapted references, SAM/suspend callables, fuller KCallable metadata, and
  future property accessors without a second identity; and
- typed foreign projections, delegate equality/registration/removal,
  round-trips, nullability, and exceptions through the separate export layer.

Revise the draft if those cannot coexist with one erased identity and
allocation-free ordinary Kotlin subtype conversion.
