# Runtime reified Map.Entry (2026-08-24)

## Scope

ABI/runtime surface 57 selects the first complete multiple-owner-parameter
family:

```text
interface Entry<out K, out V>
    -> K Key
    -> V Value
```

The compiler rule is declaration-name independent. A public, parentless
interface with two or more covariant nullable-`Any`-bounded owner parameters
is admitted only when it has exactly one abstract read-only property for each
parameter. Every getter must return one direct non-null owner parameter, and
the getter-to-parameter relation must be a bijection. Methods, setters,
defaults, repeated or unused parameters, inputs, and inherited members reject
the complete family.

## Runtime placement

The accepted arity-zero nested `Kotlin.Collections.Map.Entry` remains the
Kotlin declaration-semantic capability. Runtime adds the distinct natural CLR
interface:

```text
Kotlin.Collections.Map.Entry`2<+K,+V>
```

It is nested under the existing arity-zero Map metadata container. Surface 57
does not create or imply `Map`2`: an entry value has no physical dependency on
one enclosing map construction, and Map's own family is not yet selected. The
nested placement is explicit Runtime mapping data; compiler admission and
member planning contain no Map, Entry, stdlib, or package-name exception.

## Typed state and semantic boundary

The hostile Kotlin implementation retains one object and two independent
generic fields:

```text
RuntimeEntryValue<K,V>
    field !0 keyState
    field !1 valueState
```

Exact constructions and ordinary CLR covariance use the natural interface.
Stars, open arguments, projections, and value-type widening may cross the
non-generic semantic capability only for the getter operation whose Kotlin
view cannot be named honestly by a CLR construction. Neither field is widened
to `object`; no wrapper, proxy, copied state, or speculative Map construction
is introduced.

An ordinary sealed non-partial C# class implements only the natural nested
`Entry<K,V>` and its two properties. Roslyn warnings-as-errors provides the
static contract. Kotlin exact, widened, and star calls reach the same object
without requiring a generator, partial declaration, exact sibling, semantic
interface, wrapper, or adapter from the C# author.

## Cast coherence and discard provenance

Warning-bearing parameterized `as` and `as?` now derive the requested natural
construction independently from the expression's semantic `object` carrier.
Both use the same recursive Kotlin-aware compatibility predicate. A legal
covariant `Entry<Int,String> -> Entry<Any,Any>` cast therefore succeeds with
the same identity even though CLR value-type variance cannot name the view;
an incompatible `Entry<Int,String> -> Entry<String,String>` cast throws or
returns null respectively. Classifier-only `is Entry<*,*>` remains a
classifier test, as Kotlin source does not permit an arbitrary parameterized
`is` target.

The first hostile statement-position cast exposed a separate carrier bug.
Discard code remapped the logical result type and emitted a physical
`Entry<K,V>` pop contract even when the cast deliberately returned the broad
semantic carrier. Statement discard now consumes the expression codegen's
recorded natural carrier. This preserves the same `as` result whether it is
returned, assigned, or discarded and does not broaden any other expression.

## Verification

The hostile three-module Kotlin product and separately compiled C# consumer
run under PSI and LightTree on .NET Framework 4.8 and .NET 10. The four focused
rehearsal lanes and four focused production-erased inverse lanes pass. They
cover the admitted vector, rejected repeated/unused and extra-member shapes,
reference covariance, value-type widening, stars, classifier tests, both BK-1
cast outcomes, receiver identity, natural-only C# authoring, exact nested
reflection, both interface maps, and two reflected `!n` Kotlin fields.

The strict normal aggregate exits zero. Direct XML audit covers 191 suites and
2,329 tests: 187 FIR suites/2,195 tests, two integration suites/127 tests, the
one-test backend resolver suite, and the unchanged six-test `dotnet.ir` model
root. There are zero failures, errors, or skips.

A diagnostic global rehearsal was also attempted. It failed broadly because
that switch reinterprets every currently eligible interface and production
golden at once, including unselected Comparator and Runtime families. It did
not expose a Map.Entry-specific failure. This is deliberately not recorded as
a target gate: it confirms that an atomic full-corpus production switch remains
open and must not be approximated by enabling candidate mode globally.

## Boundary and next gate

Production Kotlin-owned generic-interface mapping remains atomically erased
outside the rehearsal. Surface 57 closes only the covariant parentless
producer-property vector and Runtime Entry placement. MutableEntry, Map, mixed
variance, inputs, defaults, inheritance, trimming, NativeAOT, static foreign
protocol, tooling presentation, and inverse rollback remain separate gates.
The next family must again be recomputed from the Common dependency/member
graph and preserve natural typed CLR storage as the default.
