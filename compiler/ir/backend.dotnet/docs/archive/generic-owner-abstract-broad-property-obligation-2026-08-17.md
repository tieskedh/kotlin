# Generic-owner abstract broad-property obligation (2026-08-17)

## Decision

Physical-family schema 18 makes every abstract broad property a complete
two-domain obligation.

The natural CLR property remains typed for ordinary C# use. Kotlin's wider
candidate domain is represented by protected semantic hooks and a hidden
non-generic capability. A concrete foreign subclass must define both domains;
the compiler may not invent the missing Kotlin behavior or narrow the
capability to the CLR property type.

This is production-inert architecture evidence. Kotlin-owned generic classes
continue to use the accepted erased production owner.

## Hostile source

The corpus adds an abstract covariant property and a concrete Kotlin
implementation:

```kotlin
abstract class HostileAbstractProperty<out T> {
    abstract var exposed: @UnsafeVariance T
}

class HostileAbstractPropertyStorage<T>(initial: T) :
    HostileAbstractProperty<T>() {
    private var stored: T = initial

    override var exposed: T
        get() = stored
        set(value) {
            stored = value
        }
}
```

A widened base view may write a value incompatible with the concrete closed
argument:

```kotlin
val exact = HostileAbstractPropertyStorage(17)
val widened: HostileAbstractProperty<Any?> = exact
widened.exposed = "abstract-property-widened"
```

The widened read returns that exact string. A typed read through `exact`
fails at its checked cast/unbox boundary. A later compatible widened write
restores typed reads. The ordinary and separate-compilation erased Kotlin
oracles both execute this sequence.

## Missing planner fact

Schema 17 derived a concrete raw property getter from semantic state. An
abstract declaration has no body and no field, so that inference is
unavailable. Looking only at the getter would therefore publish an abstract
typed `T` getter plus a capability which could not return a raw incompatible
value.

Schema 18 adds the compiler fact:

```text
ABSTRACT_BROAD_PROPERTY_OBLIGATION
```

It is attached to an abstract owner-dependent getter when the same abstract
property has a general broad setter. The getter family then contains:

```text
public abstract T get_exposed()
protected abstract object semantic_get_exposed()
private final object capability_get_exposed()
```

The setter family analogously contains an abstract typed setter, an abstract
protected semantic setter, and a concrete private-final capability dispatcher.
The PropertyDef binds only the two typed accessors.

## State-ordering defect found by the gate

The first complete oracle exposed a deeper defect. The concrete Kotlin
override inherited the producer's semantic method roles, but that merge
happened after its private field had already been accepted as true `T` state.
That ordering is unsound:

```text
widened base capability
        |
        v
derived semantic setter
        |
        v
derived private state
```

The state graph must therefore know the inherited logical semantic obligation
before choosing a carrier. The planner now walks override roots for general
semantic bodies and abstract broad-property getters, adds the overriding body
to semantic reachability, and only then classifies its fields. The concrete
implementation consequently records one `object` field. Typed and semantic
access paths join that same field; no cache, shadow, or copied object exists.

This is logical Kotlin analysis. It does not infer a foreign physical
MethodDef or capability name. External physical binding still requires the
decoded producer family record.

## Fail-closed schema rules

The new reason is serialized under schema 18. Decoding an older schema or an
unknown reason fails before any family is resolved.

An abstract obligation requires both the typed entry and semantic hook slots
to be abstract. Property routing additionally requires their abstractness to
match for both getter and setter. Negative construction tests reject:

- an abstract typed getter with a concrete semantic getter;
- an abstract typed setter with a concrete semantic setter;
- two concrete slots claiming the abstract obligation;
- a missing role or capability dispatcher;
- an unknown serialized obligation; and
- a concrete override whose state/access paths do not join the property
  families.

The capability dispatcher remains private/final and never becomes an override
target.

## Record-driven CLR product

The generated producer contains an abstract `HostileAbstractProperty<T>` and
a concrete `HostileAbstractPropertyStorage<T>`. The base publishes:

- one natural abstract `T exposed { get; set; }` property;
- protected abstract raw getter and setter hooks;
- one explicit two-method non-generic capability implementation; and
- no instance field.

A separately compiled complete C# subclass implements all four abstract
accessors over one `object` field. Compatible capability writes dispatch
through its typed property override. Incompatible writes dispatch through its
semantic setter override. The raw getter returns the actual state; the typed
getter checks only when used.

A second C# source implements only the typed property. Both the Framework 4.8
and .NET 10 compilers reject it because the protected semantic getter and
setter remain unimplemented. This is a compiler rejection, not a runtime
reflection convention.

Reflection pins:

- the base as abstract and field-free;
- the PropertyDef type to the owner GenericParam;
- both typed accessors as abstract virtual MethodDefs with their recorded
  names;
- both protected semantic hooks as abstract;
- both explicit capability targets as inherited private/final methods;
- one `object` field in the complete C# subclass; and
- one `object` field in the generated concrete Kotlin candidate.

## Static route census

The separate Kotlin sequence adds exactly five producer-owned calls:

```text
EXACT_TYPED_ENTRY   +2
SEMANTIC_CAPABILITY +3
MISSING_CAPABILITY   0
```

The two exact routes are the failing typed read and the final recovered typed
read. The three semantic routes are widened write, raw read, and recovery
write. The complete hostile totals become 24 producer-erased, 16 exact, 10
semantic-capability, and one intentionally missing-capability route.

## Verification

The focused matrix covers:

- the ordinary hostile Kotlin oracle and CLR product;
- the separate Kotlin producer/consumer, schema codec, and CLR product; and
- the recursive OctoTree separate-compilation property regression.

FIR PSI and LightTree run all three on .NET 10 and Framework 4.8: four suites,
12 tests, zero failures, errors, or skips.

The final strict `:compiler:backend.dotnet:dotNetTest` aggregate also exited
successfully. Its direct XML audit covers 187 suites and 2,107 tests with zero
failures, errors, or skips.

## Remaining work

This feature closes abstract broad-property obligations and their derived
state propagation. It does not close:

- overload and generated-name collision allocation;
- nullable transforms across complete base/interface graphs;
- arbitrary multiple public property aliases over one state proof;
- the remaining cast/reflection/dispatch application matrix; or
- the atomic production migration to canonical CLR `C<T>`.

No production owner, cast timing, or emitted Kotlin ABI changes in this gate.
