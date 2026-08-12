# Open-nullable projected arrays and Kotlin varargs use distinct CLR carriers

- Status: **Accepted pre-ABI**
- Date: 2026-08-12
- Scope: Kotlin `Array<out T?>` read views and Kotlin-owned
  method-generic `vararg T?`
- Does not enable: invariant or input-projected open `Array<T?>`, foreign CLR
  signature rewriting, or another Kotlin array identity

## Decision

An ordinary `Array<out T?>` keeps the original array object and uses
`System.Array` as its physical read-only view. Its logical element type and
projection remain authoritative in KLIB. `size`, indexed reads, and iteration
use the already classified `System.Array` operations; each `GetValue` result is
the accepted boxed-value, reference, or null `object` carrier for logical
`T?`. The frontend projection prohibits writes.

A Kotlin-owned declaration `fun <T> f(vararg values: T?)` has a different
physical rule. The compiler materializes one fresh `object[]` for every
expanded call and emits `object[]` for the declaration parameter, independent
of the call-site substitution. Values are boxed when required and null remains
null. Spread operands are read from their original exact arrays in source
order, then copied into that fresh vector. Omitted varargs produce a fresh
empty `object[]`.

The producer signature mapper recognizes the logical vararg marker before
target lowering. The vararg lowering then changes both the executable
declaration and every Kotlin call-site vararg expression to the same
`Array<Any?>` physical IR. A separately compiled consumer therefore binds the
same `object[]` MethodDef that the producer emits; substituting `T` at the call
site must never change the vector to `string[]`, `Nullable<int>[]`, or `T[]`.
KLIB and callable reflection retain the source-level `Array<out T?>` vararg
contract.

This rule is limited to Kotlin-owned nullable generic varargs. An imported CLR
`T[]` or `params T[]` declaration retains its exact selected foreign signature
and native substitution behavior. Closed invariant Kotlin arrays also retain
their existing exact carriers: for example `Array<String?>` remains a CLR
reference vector and `Array<Int?>` remains `Nullable<int>[]`.

An invariant array over a method-owned open `T?` remains unsupported. It is
writable, so one declaration-stable carrier would have to preserve both the
component identity and store checks of reference substitutions and nullable
value substitutions. Neither `object[]`, `T[]`, `Nullable<T>[]`, nor
`System.Array` provides that contract. Input projections retain the same
unresolved write obligation. The already accepted declaration-erased array
rule for an owner parameter of a Kotlin generic class or interface belongs to
the separate generic-owner ABI and is not selected or changed by this decision.

## Why the two carriers differ

An output projection is an identity-preserving capability view over an array
that already exists. CLR supplies exactly one base identity shared by every SZ
vector: `System.Array`. Copying it would change aliasing, runtime component
type, mutation visibility, and failure behavior.

A vararg expansion creates a new private argument vector. Kotlin `T?` must
admit null even when `T` is substituted with a non-null value type, while also
admitting an ordinary reference when `T` is a reference type. The existing
boxed-or-null `object` slot is the one uniform CLR representation. An
`object[]` vector of those slots is therefore truthful and directly usable
from C#; no wrapper or Kotlin-owned bridge object is needed.

This is consistent with mature-target semantics. JVM erases the generic
nullable vararg element to `Object`, while JS, Native, and Wasm use their
uniform generic-array storage. All allocate a fresh expanded vararg array and
retain the logical projected type in Kotlin metadata. The CLR adaptation is
only the carrier needed to preserve the same values and evaluation rules.

## CLR and C# boundary

C# sees an ordinary projected parameter as `System.Array` and a Kotlin-owned
nullable generic vararg parameter as `object[]`. Both are truthful native CLR
types. They are intentionally broader than the KLIB element relationship;
ordinary Kotlin calls recover that relationship from the embedded KLIB.

An explicit export may add names, overloads, or other C# conveniences, but it
must not replace either carrier, copy a projected array, or claim an exact
generic vector that fails for value substitutions. Exact imported CLR
declarations continue to bind directly rather than being routed through this
Kotlin-owned rule.

## Alternatives rejected

- **Use `T[]` for `vararg T?`.** A substitution `T = Int` cannot store null in
  `int[]`, while `T = Int?` is not the source contract.
- **Use `Nullable<T>[]`.** CLR forbids `Nullable<T>` for unconstrained `T`, and
  reference substitutions are not nullable value types.
- **Choose a closed vector after call-site substitution.** That makes the
  consumer call a different MethodDef signature from the producer and breaks
  separate compilation.
- **Use `object[]` for every open nullable array.** That would copy or reject
  exact value vectors and would make invariant writes untruthful.
- **Wrap projected arrays or varargs.** No semantic gap requires another
  identity; the CLR already supplies the necessary carriers.
- **Rewrite imported CLR varargs.** Foreign physical metadata remains the slot
  authority and may have different nullability semantics.

## Consequences and completion gates

The bounded Common-source release is the object-array `filterNotNull` and
`filterNotNullTo` pair plus `setOfNotNull(vararg T?)`. No Sequence, primitive,
unsigned, random, or unrelated generated family follows from this decision.

Completion must prove direct and spread calls with reference, value, nullable,
null, widened, empty, and multiple elements; fresh-array and evaluation-order
behavior; exact filtering and destination identity; same-module and separate
Kotlin producer/consumer binding; unchanged exact closed-array carriers;
continued rejection of invariant/input open nullable arrays; Framework CLR
and CoreCLR execution; physical `System.Array`/`object[]` signatures visible
from C#; absence of placeholder helpers and unsafe `T[]` casts; stale physical
schema rejection where the signature change requires it; and the complete
strict target aggregate.
