# Draft ADR: Kotlin-owned variant interface ABI

- Status: **representation candidate; not implemented**
- Date: 2026-07-16
- Scope: Kotlin declaration-site `out`/`in` interfaces on CLR

This is a repository-local design record for the experimental .NET backend. It is not a public
Kotlin/.NET commitment.

## Invariant

Do not use CLR `castclass` to implement Kotlin variance. Every legal Kotlin variance view of one
Kotlin-owned object must preserve the same physical identity, including when a differing logical
argument is primitive-shaped or an unconstrained type parameter.

The current reified CLR-generic representation remains valid only where CLR variance itself is
valid: differing arguments must be statically reference-shaped. Other conversions stay rejected
until a Kotlin-owned representation implements them; emitting a checked cast would defer a known
representation error to runtime.

## Candidate representation

Follow the established callable and erased collection split:

```text
Kotlin logical declaration       canonical identity       optional exact capability
Producer<out T>                  Producer$Kotlin           Producer$Exact<T>
Consumer<in T>                   Consumer$Kotlin           Consumer$Exact<T>
Source<X, out T>                 Source$Kotlin<X>           Source$Exact<X, T>
```

Only declaration-site variant parameters disappear from the canonical CLR identity. Invariant
parameters remain because Kotlin subtype conversion cannot change them. Logical arguments remain
in Kotlin metadata. Each declaration owns its generated exact capability beside the canonical
interface; it is not another shared runtime `FunctionN` family.

A Kotlin implementation supplies both views on the same object. Its typed implementation member
serves the exact capability and a compiler-generated erased bridge serves the canonical identity:

```text
IntProducer : Producer$Kotlin, Producer$Exact<int>
    int ProduceExact()
    object ProduceErased() = box ProduceExact()
```

Kotlin fields, parameters, returns, mutable storage, and subtype conversions use only the
canonical view. Therefore `Producer<Int>` to `Producer<Any>` is an instruction-free reference
copy and preserves `===`. Exact calls may use the typed capability when the static logical shape
or bounded immutable provenance is trustworthy; otherwise the erased member is universal.
Foreign CLR implementations and C#-friendly typed surfaces belong to an explicit interop/export
adaptation boundary. Ordinary Kotlin subtype conversion must not allocate wrappers.

## Required evidence before adoption

Prototype `Producer<out T>`, `Consumer<in T>`, and `Source<X, out T>` with primitive, nullable,
reference, and open arguments. Cover exact and widened invocation, immutable and mutable storage,
parameters, returns, explicit Kotlin implementations, separate KLIB/DLL modules, identity, and
both CoreCLR and .NET Framework. Report separately:

- canonical interface and bridge metadata cost;
- exact-capability member cost;
- boxing on exact, provenance-recovered, and erased calls;
- inherited/multiple variant-interface behavior and erased overload collisions; and
- C# discoverability and whether exact capabilities are suitable as public interop surface.

Properties, mixed variance, default members, callable-valued members, and cross-module capability
identity must be validated before generalizing this into the stdlib. `List<out T>` or another broad
variant stdlib API must not be committed to the current reified-identity representation first.

## Non-decision

This draft does not choose final generated type names, metadata visibility, or whether the exact
capability is a permanent ABI. It records the representation direction and prevents a conservative
`IMPLICIT_CAST` repair from being mistaken for full Kotlin-common variance support.
