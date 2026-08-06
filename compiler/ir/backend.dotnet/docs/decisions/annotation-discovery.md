# KLIB-first class annotation discovery

- Status: Accepted (pre-ABI)
- Scope: runtime-retained annotations exposed by `KClass.annotations` for
  Kotlin-produced and foreign CLR classes
- Depends on:
  [`valued-annotation-classes.md`](valued-annotation-classes.md) and
  [`kclass-and-class-literals.md`](kclass-and-class-literals.md)
- Does not enable: callable/property/parameter reflection, built-in Kotlin
  meta-annotation objects, arbitrary CLR attribute classes in FIR, annotation
  constructor reflection, or a complete `kotlin-reflect` product

## Context

The valued-annotation decision gives every supported Kotlin annotation one
concrete runtime value and keeps its complete application in KLIB. CLR custom
attributes remain only an exact, narrower projection. Annotation discovery
must preserve that direction of authority: reconstructing Kotlin annotations
from projected CLR rows would lose `KClass`, Kotlin enum, nested annotation,
primitive-array-wrapper, retention, and use-site information.

The Common `KClass` contract deliberately has no annotation property. JVM
adds `KAnnotatedElement` and obtains class annotations from Java reflection;
its full-reflection implementation filters JVM infrastructure and implements
Java inheritance/repeatable rules. JS and Wasm expose no corresponding
`KClass` annotation surface. Native has a `KAnnotatedElement` marker but no
annotation-list member. There is therefore no cross-platform runtime carrier
to copy mechanically.

CLR reflection can instantiate runtime custom attributes, including attributes
on a foreign class whose Kotlin source declaration is unavailable. It cannot
recover a Kotlin-produced application that deliberately had no CLR row. A
Kotlin/.NET implementation must use separate producer and foreign paths.

## Decision

### Platform KClass surface

The .NET actual `KClass` additionally implements `KAnnotatedElement`, matching
the useful JVM source shape. `KAnnotatedElement.annotations` returns a
read-only `List<Annotation>`. The list contains class-level runtime annotations
only; member discovery remains unavailable until member reflection has its own
declaration identity and lookup model.

The runtime owns the erased collection interfaces already used by the Common
stdlib. A private runtime list view over the discovered object array implements
their complete read-only contract. It is reflection transport, not a second
stdlib collection identity or a public BCL adapter.

### Kotlin-produced classes

After KLIB serialization, the backend derives a private annotation factory
from the class's authoritative IR/KLIB applications. The factory constructs
the already selected concrete annotation classes and returns one value for
each runtime-retained application in declaration order. Defaults, nested
annotations, arrays, enums, and `KClass` values use the ordinary executable
lowerings; no custom-attribute decoder participates.

The factory lives on a compiler-reserved static holder below the annotated
class. An assembly-private marker type identifies output produced by this
backend. At runtime, the marker means that absence of a factory is an empty
Kotlin annotation list, not permission to reinterpret CLR projection or
compiler attributes as Kotlin applications.

The factory is derived runtime support and is excluded from KLIB identity and
ordinary C# API. Disabling CLR custom-attribute projection must not change its
result. If an annotation has no executable runtime class, that application is
omitted from this bounded runtime view without changing the KLIB application
or evicting the annotated declaration.

### Foreign CLR classes

When the reflected `System.Type` comes from an assembly without the Kotlin
producer marker, CLR metadata is the only authority. The runtime therefore
uses inherited CLR custom-attribute discovery and exposes the resulting
`System.Attribute` objects through the accepted physical `kotlin.Annotation`
carrier.

This is runtime enumeration, not general FIR import. A foreign attribute can
be inspected through `Annotation` and `annotationClass`; typed Kotlin source
use still requires a separately admitted CLR attribute-class importer. No
foreign row is merged into a Kotlin-produced class, so a derived row can never
duplicate its KLIB-produced value.

The current source importer admits complete CLR interfaces, not arbitrary CLR
classes. Kotlin source can therefore name and inspect an admitted annotated
foreign interface. A C# or other dynamic consumer can also obtain a `KClass`
for an otherwise unimported foreign concrete class and use the same runtime
enumeration. Ordinary foreign-class authoring in Kotlin is a separate importer
feature, not a reflection prerequisite hidden inside this decision.

Mapped Kotlin classifiers backed by BCL types, such as `String`, primitives,
arrays, and classified exceptions, return the Kotlin view rather than foreign
BCL implementation attributes.

### Retention, inheritance, and repetition

Kotlin-produced factories include only `AnnotationRetention.RUNTIME`.
`SOURCE` and `BINARY` applications remain available to compilation through
KLIB but are absent at runtime. Kotlin annotations are not inherited by this
target's current `AttributeUsage` projection. Repeated Kotlin applications are
returned as their individual KLIB values without a synthetic container.

Foreign classes follow `Type.GetCustomAttributes(inherit = true)`, including
the foreign attribute's own `AttributeUsage` inheritance and multiplicity
rules. Those rules never flow back into Kotlin-produced annotation semantics.

## Design attack

### Decode the emitted custom-attribute rows

Rejected. The rows intentionally omit valid Kotlin-only values and retention
sites, so this would make an optional foreign projection the round-trip store.

### Read and deserialize the embedded KLIB at runtime

Rejected for this tranche. It would move compiler serialization, symbol
linking, and constructor binding into Kotlin.Runtime. The producer already has
resolved IR and can emit a small executable factory without a second metadata
interpreter.

### Return CLR attributes for every physical Type

Rejected. Kotlin-produced rows are derived and incomplete, and compiler/Roslyn
attributes would appear beside duplicate Kotlin values. The assembly marker
keeps the Kotlin and foreign authority paths disjoint.

### Add broad member reflection first

Rejected. Class annotations need only an exact classifier and one producer
factory. Callable identity, overrides, use-site mapping, invocation, and
parameter ownership are materially larger decisions.

### Treat this as a Common KClass feature

Rejected. Common, JS, Wasm, and Native do not promise this member. It is a
truthful platform capability shaped like JVM reflection, not a new Common
contract invented by the .NET target.

## Deferred extensions

- Physical runtime values for built-in `Target`, `Retention`, `Repeatable`,
  and `MustBeDocumented` meta-annotations.
- A typed FIR importer for foreign CLR attribute declarations, constructors,
  and named arguments.
- `kotlin.reflect.full` convenience operations and repeated-annotation queries.
- Class-annotation inheritance involving a future imported CLR superclass.
- Callable, property, field, accessor, parameter, and type-use annotations.

Each extension can add factories or lookup owners without changing the class
annotation list ABI or making CLR projection authoritative.

## Invariants

1. Kotlin-produced runtime annotations are constructed from KLIB-derived IR,
   never decoded from their CLR projection.
2. A Kotlin-produced class and a foreign CLR class use disjoint authority
   paths, so one application cannot appear twice.
3. Discovery returns the existing annotation object representation; it adds no
   wrapper, clone, or alternate annotation identity.
4. Source and binary retention remain compile-time KLIB facts and are absent
   from the runtime list.
5. Removing ordinary CLR projection rows cannot change Kotlin-produced
   `KClass.annotations`.
6. The private factory and list transport are compiler/runtime ABI, not public
   Kotlin declarations or implicit C# export.
7. Member reflection and typed foreign attribute import remain separately
   fail-closed.

## Verification

The feature gate must cover:

- direct and separate-library Kotlin classes with scalar, array, nested,
  `KClass`, enum, defaulted, repeated, and empty runtime applications;
- absence of source and binary applications and of CLR-row duplicates;
- class literals and dynamically obtained `value::class` values;
- foreign C# attributes discovered through a value whose concrete C# class is
  not imported into Kotlin source;
- mapped BCL classifiers without leaked implementation attributes;
- empty, singleton, and multiple list behavior; and
- both FIR parsers, Framework CLR, CoreCLR, and the full XML-audited gate.
