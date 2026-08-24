# Runtime reified Map (2026-08-24)

## Scope

ABI/runtime surface 59 selects the first parentless mixed-variance,
multiple-owner-parameter lookup family:

```text
interface Lookup<K, out V>
    -> Int size
    -> Boolean isEmpty()
    -> Boolean containsKey(K key)
    -> Boolean containsValue(V value)
    -> V? get(K key)
    -> ReadOnlySet<K> keys
    -> ReadOnlyCollection<V> values
    -> ReadOnlySet<Entry<K,V>> entries
```

The admission rule is structural and declaration-name independent. The owner
has exactly one invariant and one covariant nullable-`Any`-bounded parameter,
no parents, and exactly eight abstract members. Its graph must contain two
invariant-parameter fixed barriers, one returning Boolean and one returning
the nullable covariant parameter; one covariant-parameter Boolean barrier; one
owner-independent primitive property; one owner-independent primitive query;
and three read-only constructed-interface properties covering the invariant
parameter, covariant parameter, and their ordered pair. Every constructed
result classifier must already have a published covariant natural family.
Additional, missing, argument-reordered, mutable, defaulted, or non-abstract
members reject the complete family.

## Runtime contract

Runtime instantiates the rule as:

```text
Kotlin.Collections.Map`2<K,+V>
    -> int32 Size
    -> bool IsEmpty()
    -> bool ContainsKey(!K key)
    -> object Get(!K key)
    -> Set`1<!K> Keys
    -> Collection`1<!V> Values
    -> Set`1<Map.Entry`2<!K,!V>> Entries

Kotlin.Collections.Map__KotlinExact`2<K,V>
    implements Map`2<K,V>
    -> bool ContainsValue(!V value)
```

`ContainsValue(V)` cannot legally occur on the covariant natural interface, so
the invariant exact sibling owns that slot. `ContainsKey(K)` remains natural
because K is invariant. The accepted arity-zero Map remains the same-object
semantic capability for stars, projections, value-type widening, and foreign
natural-only fallback; it is not a base interface of the natural Map.

`Get(K): V?` deliberately returns `object` on the natural interface. A single
unconstrained CLR `V` cannot express Kotlin `V?` as both a nullable reference
and `Nullable<V>` for value substitutions. This is an honest member-local
carrier, not global owner erasure: the key parameter and every constructed
view remain typed.

## Typed state and operation-local semantic routing

Compiler-emitted Kotlin implementations retain independent state:

```text
RuntimeMapValue<K,V>
    field !0 keyState
    field !1 valueState
```

Exact and representable calls use the natural or exact MethodDef. Only the
particular star, projected, value-type-widened, or otherwise CLR-unnameable
operation crosses the arity-zero semantic capability. The constructed results
remain natural `Set<K>`, `Collection<V>`, and `Set<Map.Entry<K,V>>` values.
No map field is changed to `object`, and there is no wrapper, proxy, shadow
state, or duplicated object identity.

The structural producer analysis now records constructed-interface results
recursively across all owner parameters. Published member roles and semantic
slot discovery likewise locate the unique applicable owner parameter instead
of assuming an arity-one owner. These general rules contain no Map, collection,
stdlib, package, or declaration-name branch. Common's existing special-bridge
policies remain authoritative for wrong-shaped `containsKey`, `containsValue`,
and `get` calls.

## C# and cast boundary

An ordinary sealed non-partial C# class implements only
`Map<string,int>`. It implements the natural CLR members and supplies a public
`ContainsValue(int)` method for the accepted natural-only foreign convention;
it names no exact or semantic interface and needs no generator, partial class,
wrapper, or adapter. Kotlin exact, widened, and star calls all execute against
that same object. Reflection verifies the natural variance, the exact-only
value input, the object lookup result, the three typed constructed results,
and both implementation fields.

BK-1 applies the same warning-bearing constructed-generic compatibility rule
to both owner arguments. `Map<string,int> as? Map<string,string>` and
`Map<string,int> as? Map<object,int>` fail at the cast boundary, while ordinary
legal covariance of the V parameter is retained. The classifier-only
`is Map<*,*>` test remains a Kotlin classifier check; no unrelated parameterized
`is` syntax is invented. A separate hostile operation uses the hard cast as
the immediate lookup receiver, proving that receiver routing does not peel an
explicit source cast while it does ignore FIR's non-overlapping generated
dispatch cast.

## Verification

The hostile three-module Kotlin product and separately compiled natural-only
C# consumer execute under PSI and LightTree on .NET Framework 4.8 and .NET 10.
Four focused rehearsal lanes and four production-erased inverse lanes pass.
The complete eleven-family Runtime selection also passes under both modes,
covering every previously selected Iterator, collection, list, mutable, Entry,
and MutableEntry family.

The rehearsal is a Gradle project property. On PowerShell the feature gate is
invoked with the quoted native argument
`'-Pkotlin.dotnet.genericOwnerRehearsal=true'`; `-D` is not equivalent and
would exercise the production-erased inverse instead.

The dependency-wide integration and strict aggregate gates are recorded in
[`../../STATUS.md`](../../STATUS.md); current counts belong there rather than
in this immutable design snapshot.

## Boundary and next gate

Production Kotlin-owned generic-interface mapping remains atomically erased
outside the rehearsal. Surface 59 selects Map only; it neither creates nor
implies `MutableMap<K,V>`. Mixed multi-parameter children, defaults, diamonds,
static foreign protocol, trimming, NativeAOT, tooling presentation, and final
atomic rollback remain separate gates. The next family must be recomputed from
the complete Common dependency/member graph.
