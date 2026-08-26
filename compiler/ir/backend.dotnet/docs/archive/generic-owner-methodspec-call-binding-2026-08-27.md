# Generic-owner MethodSpec call binding

Date: 2026-08-27

## Context

The preceding checkpoint sealed complete binder-owned GenericParam rows for a
bounded owner-plus-method-generic producer family, but deliberately stopped at
the physical declaration. It did not prove that a call could retain that
MethodDef as open metadata, construct the correct MethodSpec, and substitute
owner and method parameters through different binders on both the natural and
semantic routes. See
[`generic-owner-methoddef-genericparam-sealed-emission-2026-08-27.md`](generic-owner-methoddef-genericparam-sealed-emission-2026-08-27.md).

This checkpoint closes that executable boundary and the classifier-input
composition exposed by it. It does not advance a Runtime/Stdlib owner or make
the rehearsal production ABI.

## Physical MethodDef arity is producer authority

Physical-library ABI 60 records the exact generic arity of every selected
producer-emitted MethodDef rather than deriving it from a call or reconstructed
IR. The arity now travels with:

- ordinary Function records;
- paired generic-owner classifier-input entries;
- capability, default-capability, semantic-hook, and foreign-override-probe
  MethodDefs in a generic-owner member family;
- interface-default helpers and default-argument dispatchers; and
- value-class primary-constructor, box, and unbox helpers.

External declaration reconstruction and portable physical-ABI comparison
require the retained MethodDef signature to agree with that producer record.
Missing, negative, stale, or disagreeing arity is rejected. A MethodSpec vector
is call-site data and can never establish the selected MethodDef's declaration
arity.

Imported CLR MethodDefs likewise retain the generic arity from their selected
metadata signature for ordinary call binding. This does not yet make retained
foreign metadata a sealed generic-owner family adapter; that remains a separate
authority gate.

## Independent TypeDef and MethodDef substitution

One call-site binding operation now receives:

```text
selected open MethodDef signature
+ constructed owner arguments       -> !n
+ exact MethodSpec arguments         -> !!n
------------------------------------------------
verifier-visible parameters/result
+ MethodSpec suffix for the call token
```

The declaration signature remains open. Owner arguments substitute only
TypeDef parameters and method arguments substitute only parameters owned by
the selected MethodDef. An open caller method parameter is a valid MethodSpec
argument while its own binder is in scope. An omitted or extra argument, an
out-of-range parameter, or a parameter borrowed from another MethodDef fails
closed. The executable family remains unconstrained. Its sealed GenericParam
rows are declaration authority; this checkpoint does not add a general call-
site constraint solver.

The same structural substitution applies inside arrays, by-reference carriers,
and result layouts. In the physical-operation model, both `Direct(slot)` and
`SplitNullable(payloadSlot, out bool&)` receive the substituted payload. This
proves composition of the model; it does not yet select the custom two-
parameter lookup family or `Map.get`.

## Executable natural and semantic MethodSpecs

The bounded family remains:

```kotlin
interface MethodGenericProducer<out T> {
    fun <R> produce(marker: R): T
}
```

Every natural and semantic MethodDef retains its own `R`. Exact calls construct
the MethodSpec on the natural `MethodGenericProducer<T>` slot and return `!T`.
Widened calls construct the MethodSpec on the non-generic semantic capability
slot and return `object`. Both routes receive the same logical method argument:
`Int`, `String`, or the caller's own open `R`. No route erases the method
parameter or reuses an equally numbered parameter from a sibling MethodDef.

The emitted IL and execution fixture cover independent `T = Int` and
`T = String` implementations, independent marker substitutions, exact and
widened receivers, and two separately sealed implementation families sharing
the interface slots without sharing implementation-side binders or MethodImpls.

## Bounded classifier-input composition

A method-generic callable can truthfully expose this natural source entry:

```kotlin
fun <T> retain(producer: MethodGenericProducer<T>): Any?
```

Its ordinary MethodDef keeps `MethodGenericProducer<!!0>`. One paired compiler
MethodDef keeps the same generic arity and body contract but carries the
selected parameter as `object`. The natural entry is selected only from
positive physical evidence:

- the corresponding exact parameter of the current source MethodDef; or
- a local, non-generic, non-local-declaration, non-value class whose direct
  flat-closed InterfaceImpl is exactly the required construction.

An implicit representation-preserving cast may carry that evidence. Logical
Kotlin ancestry, variance, `Nothing` subtyping, or an exact-looking later
substitution may not create it. Value-type widening, open-nullable, foreign,
transitive, generic-owner, and otherwise unknown producers therefore use the
paired object-input entry. The same rule keeps a closed nested source-MethodDef
parameter natural while refusing to derive a more precise construction from
an open nested or generic class edge.

This is a temporary conservative admission until the shared physical-value
model owns the complete query. It contains no collection, package, declaration,
or member-name exception.

Application-only paired entries are assembly-local compiler details. A
producer-recorded Kotlin entry or portable interface-default helper is public
compiler ABI, marked `KotlinCompilerAbi` and `EditorBrowsable(Never)`, and is
located only through producer authority. The natural source MethodDef remains
the sole ordinary C# entry.

## C# implementation and portable-helper boundary

A precompiled, non-partial C# class may implement only the natural interface
and its ordinary generic method. A widened Kotlin call discovers the unique
natural construction and invokes that same generic MethodDef with the exact
value, reference, or open method argument. It neither requires the C# class to
implement the Kotlin semantic capability nor changes receiver identity.

When a portable method-generic default helper has its own paired object-input
entry, the schema-9 C# implementation manifest records that materialized helper
as the semantic locator. Generated C# instantiates the recorded helper and
forwards the same method argument. It does not call the natural helper through
a fabricated `I<object>` receiver. The manifest shape does not change and its
schema remains 9.

## Fail-closed boundary

The executable proof deliberately rejects or routes conservatively around:

- default value parameters and their generated default dispatchers;
- `@InlineOnly`, reified-inline, and intrinsic physical remainders;
- nullable or projected interface inputs;
- nested-open arguments and owner parameters of a generic containing class;
- more than one selected classifier input;
- logical variance or subtype reasoning without a recorded physical edge; and
- incomplete MethodSpec vectors or mismatched physical generic arity.

`MethodGenericProducer<Int>` viewed as `MethodGenericProducer<Number>`, a
`Nothing` producer viewed through `MethodGenericProducer<String>`, and an
open-nullable producer all retain the original object and use the paired entry.
An exact concrete `MethodGenericProducer<Int>` and an exact source-MethodDef
parameter use the natural entry. No case creates `MethodGenericProducer<object>`,
a wrapper, proxy, shadow field, or second authoritative state.

## Evidence

Eight focused execution lanes are green: the local method-generic sealed-
emission fixture and the separate natural-only C# implementation fixture, each
through PSI and LightTree on Framework 4.8 and .NET 10.

The selected backend gates are green for call-site signature binding, the
generic-owner physical-value model, external declarations, and producer
generic-interface physical authority. The selected integration gates are green
for Kotlin classifier physical-owner round-trip and rejection of missing or
changed portable physical bindings.

The full target aggregate exits zero. Direct JUnit XML audit covers 199 suites
and 2,502 tests with no failures, errors, or skips: one `dotnet.ir` suite/six
tests, 187 FIR suites/2,243 tests, two integration suites/127 tests, and nine
backend suites/126 tests. The explicit-off inverse is independently green in
four suites/eight tests; the full aggregate also proves the property-absent
production inverse.

## Boundary and next work

Production generic-interface owners remain atomically erased. The rehearsal
adds no wrapper, proxy, duplicate state, or representation-dependent receiver
identity. ABI 60 is a pre-ABI producer/consumer physical-binding update, not an
atomic generic-interface production cutover.

Next add independently sourced producer-recorded and retained-foreign sealed
adapters, including static/file-facade operation authority, and then close
overlapping/compiler-wide family ownership. Only after those gates should the
second split-nullable/covariant-result selection be proven on the custom lookup
family and then composed with Map. The source-built Stdlib census remains
paused.
