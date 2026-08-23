# Runtime reified Collection/Set family (2026-08-23)

## Scope

ABI/runtime surface 50 completes the first atomic input-bearing Runtime
collection slice behind the generic-owner rehearsal:

```text
Collection<out T>
    -> int Size
    -> bool IsEmpty()
    -> Iterator<T> GetIterator()

Collection__KotlinExact<T> : Collection<T>
    -> bool Contains(T)
    -> bool ContainsAll(Collection<T>)

Set<out T> : Collection<T>

Set__KotlinExact<T> : Set<T>, Collection__KotlinExact<T>
    -> bool Contains(T)
    -> bool ContainsAll(Collection<T>)
```

The accepted arity-zero `Collection` and `Set` TypeDefs remain the Kotlin
semantic capabilities for views which the CLR cannot name. They are not the
normal typed route, and neither natural interface inherits them.

## Physical and semantic result

The natural covariant interfaces own only CLR-legal output and query members.
The invariant exact siblings own the two input-bearing members. A Kotlin
implementation carries the natural, exact, and semantic views on one object,
while its ordinary generic state remains real CLR storage:

```text
RuntimeCollectionValue<T>
    field !T first
    field !T second
    implements Collection<T>
    implements Collection__KotlinExact<T>
    implements Collection

RuntimeSetValue<T>
    field !T first
    field !T second
    implements Set<T>
    implements Set__KotlinExact<T>
    implements Set
```

Exact calls select the typed sibling. Reference covariance uses the CLR's
normal constructed-interface conversion. A Kotlin-legal value-type widening,
or an input collection with an incompatible physical construction, crosses the
arity-zero semantic capability only for that operation. It does not widen the
fields, create shadow state, wrap the receiver, or change identity.

Moving `containsAll` into a generic class's object-domain semantic hook exposed
a lowering-composition defect: its nested call to `contains` still targeted
the typed source method and unboxed a mixed `String` candidate as `Int`. The
planner now preserves the existing capability dispatcher for such a call.
This is the same monotone rule used by producer-proven getters: a later generic
rewrite must not replace a more appropriate already-planned route.

## Ordinary C# implementations

A sealed, non-partial C# class may implement only `Collection<T>` or `Set<T>`.
It writes the natural `Size`, `IsEmpty`, and `GetIterator` members plus ordinary
public `Contains(T)` and `ContainsAll(Collection<T>)` methods. It does not name
the arity-zero Kotlin capability, the invariant exact sibling, a generated
semantic interface, or a source-generator bridge.

Kotlin dispatch uses this order:

```text
Kotlin implementation
    exact sibling -> semantic capability

natural-only C# implementation
    unique natural construction
        -> public Contains(T)
        -> public ContainsAll(Collection<T>)
```

For `contains`, an incompatible candidate returns the authoritative Kotlin
fixed-barrier result before reflection invokes the typed C# method. For
`containsAll`, a physically compatible argument calls the C# method directly.
An incompatible construction is evaluated element by element through natural
`Iterable<T>`/`Iterator<T>` and the same `Contains(T)` barrier. This distinction
is required because an empty incompatible collection must still return `true`;
returning `false` solely from the CLR construction mismatch would change
Kotlin semantics.

The runtime resolver cache is keyed by the receiver runtime type, selected open
owner, method name, resolution kind, and—where needed—the open parameter owner.
It therefore distinguishes `Collection<T>.ContainsAll(Collection<T>)` from
`Set<T>.ContainsAll(Collection<T>)` without a Set-specific compiler bridge.

## Joined producer results

`GetIterator()` may return the arity-zero semantic `Iterator` from a Kotlin
implementation or a natural-only C# `Iterator<T>`. Their common physical
carrier at an open/widened boundary is `object`. A FIR implicit cast must not
reconstruct the semantic interface before the next member use; that use again
selects capability or natural dispatch. The join preserves one object and does
not allocate an adapter.

The implementation also fixed three adjacent composition boundaries revealed
by the real Runtime graph:

- a canonical bridge takes its observable MethodDef signature from the actual
  canonical slot while retaining the implementing-class receiver for its body;
- a separate consumer trusts the producer's recorded natural carrier instead
  of re-planning an external stub from later widened uses; and
- the canonical `containsAll` slot accepts `object`, allowing the original
  nested collection identity to reach the semantic body.

The full aggregate exposed two more physical-ABI edges before the checkpoint
could close. The hand-written Runtime `ReflectionAnnotationList` is both a
`List` and a `Collection`; it now retains its existing
`List.ContainsAll(Collection)` implementation and owns a distinct private
MethodImpl for `Collection.ContainsAll(object)`. A canonical-origin value-class
static `-impl` helper is a different case: value-class lowering deliberately
removes every MethodImpl arrow, so with no remaining overridden slot its own
fully lowered signature is authoritative. Exactly one remaining slot selects
that slot's signature, while several incompatible physical slots still fail
closed.

## Verification

The focused Kotlin and warnings-as-errors C# product is green under PSI and
LightTree on .NET Framework 4.8 and .NET 10. It executes exact and widened
Collection/Set calls, reference covariance, value-type Kotlin widening,
compatible direct C# `ContainsAll`, incompatible and empty element-wise
fallbacks, iterator producer joins, identity, inherited C# method discovery,
and reflection of both Kotlin implementation fields as owner GenericParams.

The final full target aggregate exits zero. Direct XML audit covers 190 freshly
written suites/2,295 tests with zero failures, errors, or skips: 187 FIR suites/
2,167 tests, two integration suites/127 tests, and the one-test backend resolver
suite. The unchanged green six-test `dotnet.ir` root makes the complete target
inventory 191 suites/2,301 tests.

## Boundary and next gate

This slice does not migrate `List<T>`, mutable collection interfaces, Map,
defaults, overload families, multiple owner parameters, or concrete Stdlib
collection storage. Their existing canonical mappings remain authoritative.
The next generic-owner dependency gate must be recomputed from the Common
declaration graph. It should extend the same natural-first rule only as one
complete family, preserving typed CLR calls/state and using semantic routing
solely where Kotlin behavior is not representable by a single CLR
construction.
