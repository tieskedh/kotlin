# ADR: declaration-owned callable visibility and modality

- Status: **Accepted for direct function and property references**
- Scope: JVM-shaped `KCallable.visibility`, `isFinal`, `isOpen`, and
  `isAbstract`; `KVisibility`; Kotlin-owned and admitted foreign direct
  callable references; Runtime/Stdlib artifact ownership
- Does not enable: callable or constructor enumeration, property accessor
  reflection objects, broad foreign CLR member import, `KClass` visibility or
  modality, or reflection over arbitrary runtime methods

## Decision

Kotlin/.NET exposes the JVM-shaped visibility and modality properties on every
admitted direct `KCallable` object. Their value is a fact of the reflected
logical declaration:

- Kotlin declarations use the visibility and modality retained by FIR/IR and
  KLIB;
- imported CLR declarations use the Kotlin visibility and modality assigned by
  the .NET FIR importer; and
- CLR MethodDef accessibility and virtual flags are not a second source of
  Kotlin reflection truth.

The callable-reference lowering encodes those declaration facts once in the
immutable reference object. Runtime getters decode that payload. One payload
format is shared by function and property references. It is compiler/runtime
ABI, not library metadata and not a public C# authoring contract.

The supported visibility conversion follows JVM exactly:

```text
public          -> KVisibility.PUBLIC
protected       -> KVisibility.PROTECTED
internal        -> KVisibility.INTERNAL
private         -> KVisibility.PRIVATE
private-to-this -> KVisibility.PRIVATE
local/unknown   -> null
```

Exactly one of `isFinal`, `isOpen`, and `isAbstract` is true for an admitted
non-local callable. Constructors are final. Local delegated-property tokens
have `visibility == null` and are final. Unsupported or internally inconsistent
modality is a compiler error rather than a guessed runtime answer.

## Cross-target evidence

Common, JS, and Wasm expose only `KCallable.name`. Native adds `returnType` but
does not expose visibility or modality. JVM owns the complete platform surface
and is therefore the behavioral precedent for this optional .NET extension.

JVM reflection reads Kotlin callable facts from descriptors or Kotlin metadata.
It reads Java facts from the Java declaration, but returns `null` when a Java
visibility has no exact Kotlin meaning: Java protected and package-private are
the canonical examples. JVM derives the three modality booleans from one
modality value rather than reporting independent host flags.

.NET follows the same dependency direction. Today the CLR importer admits only
public abstract interface members, so those foreign direct references report
`PUBLIC` and `ABSTRACT`. Future importer expansion must first assign an exact
Kotlin FIR visibility/modality. The backend must not reinterpret raw CLR flags
at the reflection use site.

## Runtime ownership forced by the CLR artifact graph

`KCallable` and callable-reference implementations are physically owned by
`Kotlin.Runtime`. Its typed `visibility` slot therefore requires the one
physical `Kotlin.KVisibility` in Runtime too; placing that enum in Stdlib would
create the forbidden Runtime-to-Stdlib edge.

`KVisibility` remains an ordinary Kotlin reference enum extending
`Kotlin.Enum`. Its authoritative declaration is the existing JVM stdlib source,
reused without a target-owned duplicate. Runtime supplies the physical enum,
its four entry singletons, `values`, `valueOf`, and stable `entries` view.

Modern enum `entries` exposes `kotlin.enums.EnumEntries`. Consequently the
physical erased `EnumEntries` **interface only** also moves to Runtime. This is
the smallest truthful closure of the already accepted Runtime-owned enum base:

```text
Kotlin.Runtime
    Kotlin.Enum
    Kotlin.Enums.EnumEntries (erased interface)
    Kotlin.KVisibility and its private entries-list carrier

Kotlin.Stdlib
    authoritative Common EnumEntries source/KLIB
    Common EnumEntriesList implementation and enumEntries factories
```

The Common implementation remains in Stdlib and implements the Runtime-owned
interface. Ordinary user enums continue to construct that Common
implementation. Only the no-member physical interface changes owner. Runtime's
private KVisibility entries list reuses the runtime read-only object-list
carrier already needed by reflection; it does not copy the Common algorithms
for ordinary enums.

This narrowly supersedes the sentence in the ordinary-enum ADR that moving the
enum base did not move `EnumEntries`: the logical declaration and Common
implementation still do not move, but the physical erased interface must move
to close the Runtime-owned `KVisibility` type graph.

## Why not infer the answer from CLR flags

One Kotlin declaration can have physical bridges, default helpers, erased
slots, typed interface capabilities, or export adapters with different CLR
flags. Those artifacts do not change whether the declaration was Kotlin
`internal`, `open`, or `abstract`. Reading the selected MethodDef would make
reflection depend on which bridge happened to execute and would lose `internal`
entirely.

Foreign declarations are not an exception to the rule. The importer is the
semantic boundary that decides which Kotlin declaration a CLR member becomes.
Once admitted, its FIR/IR declaration is authoritative. This mirrors JVM's
metadata/descriptor path while leaving room for exact CLR-only evidence in the
importer.

## Alternatives rejected

### Keep `KVisibility` in Stdlib

Rejected. Runtime owns the typed `KCallable` slot and its implementations, so
this creates a cyclic physical product graph.

### Return `int`, `object`, or a Runtime-only visibility token

Rejected. These weaken the typed Kotlin and C# surface or create a second enum
identity. `KVisibility` is one ordinary Kotlin enum.

### Omit `KVisibility.entries`

Rejected. Reusing the authoritative enum source while publishing only selected
synthetics creates a class that looks complete in KLIB but fails at runtime.

### Move all `EnumEntries` implementation into Runtime

Rejected. Common owns the ordinary enum-list algorithms. Only the artifact-cycle
closing interface and the four-element Runtime-private carrier belong below
Stdlib.

### Add a general runtime metadata/reflection engine first

Rejected for this phase. Direct callable references already know their exact
declaration during lowering. Capturing four immutable facts is sufficient and
does not pre-commit member enumeration, runtime KLIB loading, or accessor
objects. A later reflection engine may reconstruct equivalent objects, but it
must produce the same declaration facts.

## Required evidence

- direct bound and unbound functions and properties across public, protected,
  internal, private, final, open, and abstract declarations;
- constructors are final and retain their source visibility;
- local function references and delegated-property tokens return null visibility and final modality;
- inherited/fake-override references report the resolved Kotlin declaration,
  not a physical bridge;
- admitted foreign CLR interface functions and properties report public and
  abstract from importer IR;
- producer-created and consumer-created references agree across separate DLLs;
- `KVisibility` entry identity, name, ordinal, `values`, `valueOf`, `entries`,
  enum comparison, and hostile mutation attempts retain ordinary enum rules;
- Runtime has no reference to Stdlib; Stdlib's Common `EnumEntriesList`
  implements the Runtime interface;
- C# sees a typed `KVisibility` property plus three booleans, without seeing an
  integral `System.Enum` lie; and
- stale Runtime and library surface levels fail closed.
