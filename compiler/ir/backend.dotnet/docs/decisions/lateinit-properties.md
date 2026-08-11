# ADR: Common `lateinit` lowering over nullable CLR storage

- Status: **Accepted pre-ABI**
- Scope: member, top-level, and local `lateinit` properties/variables,
  `KProperty0.isInitialized`, property references, separate Kotlin libraries,
  and the truthful CLR property surface
- Does not enable: delegated properties, general foreign-parameter null
  assertions, reflective backing-field access as Kotlin API, or a second
  initialization-state representation

## Decision

Kotlin/.NET uses the repository's Common `LateinitLowering` as the semantic
owner of `lateinit`. The logical declaration remains an ordinary non-null
Kotlin property in KLIB. After inline-body serialization, the executable IR
uses the mature-target representation:

- the backing field or local carrier is nullable and starts as `null`;
- a write stores the value in that one carrier;
- every ordinary Kotlin read uses the generated getter/read check;
- a non-null value is returned unchanged;
- `null` calls Common
  `kotlin.internal.throwUninitializedPropertyAccessException(name)`; and
- `::property.isInitialized` tests that exact carrier for non-null without
  invoking the throwing getter.

The physical exception is the existing Kotlin
`UninitializedPropertyAccessException`, with the Common message:

```text
lateinit property <name> has not been initialized
```

No target-owned exception translation, wrapper, Boolean flag, or alternate
field is introduced.

## Why `null` is a truthful sentinel

The frontend admits `lateinit` only where the logical Kotlin type is non-null
and can use a reference carrier. Consequently a valid Kotlin assignment cannot
store `null`, and the CLR reference default is an unambiguous uninitialized
state. The physical field/local is nullable only after lowering; KLIB and the
public getter/setter retain the source non-null type.

This is representation lowering, not weakened Kotlin nullability. Nullable
metadata on the private field describes its real storage capability; the
public property continues to publish the logical non-null contract.

## Cross-target architecture

Common owns the getter, local-read, field-read, and `isInitialized` rewrites.
JS and Wasm run `LateinitLowering` immediately before shared-variable and
local-class lowering in both KLIB stages. Native uses the same ordering and
applies the same lowering to resolved inline bodies. JVM subclasses the Common
lowering only for JVM field-visibility needs; it does not change the sentinel
or failure semantics.

.NET follows that phase boundary exactly:

```text
upgrade callable references
        -> Common LateinitLowering
        -> shared variables / closure conversion
        -> inlining and later target lowerings
```

The first KLIB phase lowers serializable inline bodies. The second-stage
prefix handles source/dependency bodies when the modern first-stage inliner is
not active. The lowering must not be repeated on the same IR tree.

## Ownership and read paths

### Member properties

An instance property keeps one private CLR backing field. The Common-generated
getter reads it once into a temporary, returns that exact object if non-null,
and otherwise throws. Inheritance and virtual dispatch continue through the
ordinary property accessors; no subclass-specific state or bridge is added.

### Top-level and static properties

The existing file facade or class static owner keeps one private static field.
There is no initializer statement or `.cctor` assignment: the CLR's initial
`null` value is the selected sentinel. The ordinary static getter performs the
same Common check.

### Local variables and captures

A local `lateinit` variable becomes a nullable mutable local initialized to
`null`. Each read is checked. If closure conversion captures it, the established
shared-variable lowering captures that one transformed carrier, so the local
and closure observe the same writes and initialization state.

### Property references and reflection

An executable property reference invokes the same getter/setter path as direct
Kotlin code. It may not read the private field to avoid the check.
`KProperty.isLateinit` remains the declaration fact already carried from exact
IR. `KProperty0.isInitialized` is different: it is a compile-time-only intrinsic
which Common lowers to a direct state test for an accessible exact property
literal. It does not become a general runtime reflection operation.

## CLR and C# interop

C# sees the ordinary emitted CLR property. Calling its setter initializes the
same storage; calling its getter before initialization throws the physical
Kotlin exception with the Common message. The private backing field is not a
supported C# API and cannot be mistaken for the Kotlin property.

Roslyn nullable metadata is additive interop information. It neither records
nor determines `lateinit` state. A hostile CLR caller can still use reflection
or unverifiable IL to place `null` in private/non-null storage. A later getter
then observes the only representable state truthfully and reports the property
as uninitialized. General runtime validation of foreign calls to non-null
Kotlin parameters, including property setters, is a separate boundary-wide
feature and must not be implemented only for `lateinit`.

## Separate compilation

KLIB retains `isLateinit`, the source type, visibility, accessors, and logical
property identity. The producer DLL owns its private nullable field and
throwing accessor bodies. A consumer binds only to the producer's ordinary
accessors and never reconstructs the sentinel, helper name, or field spelling.

Inline bodies are lowered through the shared pre-serialization phase so a
consumer never needs target-specific `lateinit` reconstruction. The helper
call remains an ordinary versioned Stdlib ABI edge.

## Alternatives rejected

### A separate Boolean initialization field

Rejected. Legal `lateinit` values cannot be `null`, so another field would add
state combinations, object size, synchronization questions, and ABI without
representing any additional Kotlin value.

### Infer state from CLR nullable attributes

Rejected. Nullable attributes describe static foreign-language nullability;
they do not contain per-object runtime state and cannot implement
`isInitialized`.

### Emit a target-owned getter check in CIL

Rejected. It would duplicate the Common lowering, drift from inline/local
semantics, and make property references or future code generators easy to
bypass.

### Expose the backing field for C# convenience

Rejected. A direct field read would return the sentinel instead of enforcing
Kotlin property semantics. C# consumption uses the truthful CLR property.

### Treat a CLR default value as initialized

Rejected. For reference storage the default is `null`, which is precisely the
uninitialized sentinel. Kotlin state begins only after a valid assignment.

## Completion evidence

The feature gate covers both FIR parsers and both runtime profiles, including:

- initialized and uninitialized member, top-level, local, and captured-local
  reads;
- exact exception type, message, and recovery after assignment;
- inherited storage, overrides, inner/local classes, and virtual access;
- false/true `isInitialized` transitions and positive `isLateinit` reflection;
- property-reference get/set behavior without a field bypass;
- generic non-null reference bounds and stable object identity;
- separate producer/consumer KLIB and DLL binding;
- a private nullable physical field plus non-null public property metadata;
- C# setter/getter consumption and physical Kotlin exception identity; and
- absence of a Boolean sentinel or target-specific duplicate getter check.
