# ADR: ordinary Kotlin enums as reference classes on CLR

- Status: **Accepted for the ordinary enum and non-reified `EnumEntries` phase**
- Scope: Kotlin-owned enum classes, enum entries, synthetic `values`,
  `valueOf`, and `entries`, plus the non-reified Common `EnumEntries` core
- Does not enable: reified `enumValues`, `enumValueOf`, or `enumEntries`, CLR
  value-type enum identity, broad valued annotations, or enum reflection

## Decision

A Kotlin-owned enum is one Kotlin reference-class hierarchy. Its physical CLR
owner is an ordinary class, not a `System.Enum` value type. Every entry is one
reference stored in a producer-owned static readonly field on the enum class.
An entry with its own body is an ordinary private derived implementation class;
an entry without a body is an instance of the enum class itself.
An enum declaring an abstract member is physically an abstract base whose
private entry classes supply the implementations; source-level Kotlin
finality remains in KLIB and its non-public constructors/derived types.

CLR nesting does not grant an enclosing type access to private members of its
nested type. Therefore a private entry implementation class has one
assembly-visible compiler-generated constructor so the enclosing enum's class
initializer can instantiate it. The class remains private, the constructor is
not Kotlin or C# API, and no accessor wrapper or second entry object is added.

The Common declaration remains authoritative:

```text
Enum<E : Enum<E>> : Comparable<E>
```

`kotlin.Enum<E>` and `kotlin.enums.EnumEntries<E>` retain complete logical
generic information in KLIB. Their Kotlin-owned CLR TypeDefs follow the
accepted generic-class and generic-interface erasure rules. A concrete enum
such as `Color` is non-generic and may independently expose the truthful
`System.IComparable<Color>` capability selected by the general Comparable ADR.

## Why a CLR value-type enum is not the Kotlin representation

CLR enums are value types with an integral storage field. Kotlin enum entries
are reference objects: they may own constructor state, override members, have
anonymous entry subclasses, participate in identity equality, and inherit the
Common `Enum<E>` implementation. Mapping a Kotlin enum to `System.Enum` would
lose those semantics or require a second object representation.

This is not a CLR limitation that justifies changing Common semantics. The CLR
supports the required reference-class shape directly.

## Cross-target evidence

- JVM emits reference entry objects, static entry fields, synthetic values and
  entries storage, and constructor-supplied name/ordinal values.
- JS emits reference entry instances and generates the synthetic enum members
  over those instances.
- Native allocates reference entry instances, initializes them in declaration
  order, stores values/entries arrays, and lowers entry reads to stable access.
- Wasm retains the same logical enum object model with target-specific entry
  access and enum intrinsics.

The .NET lowering follows this shared semantic model. Its target-specific work
is limited to CLR field ownership, class initialization, exact vector types,
and separate-assembly binding.

## Physical shape

For:

```kotlin
enum class Color(val code: Int) {
    RED(1),
    GREEN(2) { override fun text() = "green" }
}
```

the relevant physical shape is conceptually:

```text
class Color : Kotlin.Enum {
    public static readonly Color RED
    public static readonly Color GREEN
    private static readonly Kotlin.Enums.EnumEntries $ENTRIES

    public static Color[] values()
    public static Color valueOf(string name)
    public static Kotlin.Enums.EnumEntries get_entries()

    private class GREEN$Entry : Color
}
```

The exact field and member spellings remain compiler ABI rather than a C#
source contract. KLIB identifies the logical declarations. The physical ABI
records the owner path and field name for every enum entry so a later Kotlin
consumer never guesses a producer spelling.

Entry fields are initialized in source declaration order. The existing .NET
static-initialization graph and failure classifier own first-use ordering,
recursive access, failure identity, and separate-library activation. The enum
lowering does not introduce a second initialization mechanism.

## Synthetic members

`values()` returns a new exact `E[]` on every call. Mutating one result cannot
change later results or the `entries` view.

`valueOf(name)` compares the requested name with the exact source entry names
and returns the corresponding singleton. A missing name throws the Kotlin
`IllegalArgumentException` form used by the other reference-object targets.
It does not use CLR `System.Enum.Parse` because the physical type is not a CLR
enum and CLR parsing has different naming and conversion semantics.

`entries` returns one stable Common `EnumEntries<E>` instance. The generated
getter uses the authoritative internal Common factory over an exact `E[]`.
The `EnumEntriesList` algorithms for `size`, indexed access, identity-based
`contains`, and ordinal-based index lookup remain the Common source bodies.

That Common dependency closure currently introduces `Array.getOrNull`, whose
generated Common body spells its guard as `index in indices`. The bootstrap
projection retains that body exactly. Until the complete public
`Array.indices`/`IntRange` product is admitted, one private generated
resolution marker supplies only the symbol needed by FIR; the repository's
shared `RangeContainsLowering` must erase every use to primitive array-size
comparisons. The marker has no MethodDef, no public Kotlin or CLR ABI, and a
surviving call is a backend error. It must disappear when the full Common
range dependency closure lands.

Common's internal `@WasExperimental(ExperimentalStdlibApi::class)` and
`@IntrinsicConstEvaluation` metadata remain exact. Their authoritative
stdlib annotation declarations participate in FIR and KLIB through the
existing resolution-only bootstrap category, but emit no CLR TypeDef. This
does not open valued runtime annotations: both are compiler metadata with
binary retention, while public runtime attribute construction and reflection
remain governed by the annotation programme.

The temporary same-compilation stdlib source product needs one frontend-only
projection that an installed KLIB does not: FIR deserialization always adds
synthetic default placeholders to the two `kotlin.Enum` constructor
parameters, whereas a source expect declaration receives them only during a
complete stdlib compilation. The generated .NET Common projection spells
those placeholders as `""` and `-1`. Every enum constructor call is then
rewritten to its exact entry name and ordinal before emission. No default
constructor overload or stub may enter physical ABI, and this projection must
be removed once all consumers use the installed stdlib/KLIB boundary.

## Generic boundary

“Non-reified `EnumEntries`” means that this phase admits the interface, its
Common implementation, the internal ordinary generic array factory, and each
concrete enum's `entries` property. It does not mean that `EnumEntries<E>` loses
`E` in Kotlin.

The following stay parked because they select an enum solely from a reified
type parameter:

```kotlin
enumValues<T>()
enumValueOf<T>(name)
enumEntries<T>()
```

Concrete `Color.entries`, `Color.values()`, and `Color.valueOf(name)` need no
general reified operation because the enum classifier is already explicit.

## C# and CLR consumption

Raw CLR consumers see reference fields and methods, not integral enum literals.
That view is truthful: entry identity, state, overrides, and arrays behave as
the Kotlin objects they are. An explicit future C# export may add a typed
facade or host-native projection, but it must not redefine Kotlin identity or
pretend that stateful entry objects are CLR enum values.

`Color[]`, `System.IComparable<Color>`, and a future typed
`EnumEntries<Color>`/read-only-list export are independently truthful CLR
capabilities. The erased internal `EnumEntries` TypeDef is not the supported
typed C# export surface.

## Alternatives rejected

### Emit `System.Enum` value types

Rejected. This loses Kotlin entry objects, entry subclasses, constructor state,
identity, and the Common base-class contract.

### Emit both a CLR enum and Kotlin entry objects

Rejected. It creates two values and two identities for one Kotlin declaration,
with unavoidable ambiguity in casts, equality, reflection, mutation, and
foreign round-trips.

### Make `EnumEntries<E>` a special reified CLR interface

Rejected. It would reopen the removed dual generic-interface ABI for one
consumer, while `EnumEntries<E>` extends the same erased Kotlin `List<E>` as
ordinary collections. Enum entries are reference values, so it provides no
value-type boxing benefit. Typed C# use belongs to explicit export.

### Implement a target-owned enum-entry list

Rejected. Common already owns the immutable list algorithms. The .NET product
must compile their exact source dependency closure rather than copy or rewrite
it around the target.

### Enable all reified enum helpers with ordinary enums

Rejected. Concrete synthetic enum members and reified type-parameter lookup are
different features. Opening the latter would silently decide the wider
reified/reflection ABI.

## Completion evidence

The feature gate must cover both FIR frontends and both runtime profiles, plus
separate producer/consumer assemblies:

- declaration order, name, ordinal, identity, `toString`, equality, hash, and
  Comparable behavior;
- constructor arguments, init order, previous-entry references, companions,
  and first-use initialization;
- entries with and without bodies, abstract members, overrides, and dispatch;
- fresh `values()` arrays, stable immutable `entries`, indexing, iteration,
  contains/index operations, and hostile mutations of returned arrays;
- exact `valueOf` success and failure behavior;
- nullable, widened, `Enum<*>`, and `EnumEntries<*>` uses;
- entry annotations on the physical entry field without changing KLIB
  retention;
- producer-recorded entry-field binding and version-skew rejection;
- raw CIL and C# consumption of the truthful reference-class surface; and
- continued rejection of reified enum helpers and CLR value-type identity.
