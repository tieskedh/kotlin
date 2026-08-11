# Parameterless Kotlin marker annotation classes

- Status: Superseded by
  [`valued-annotation-classes.md`](valued-annotation-classes.md)
- Scope: general parameterless annotation-class declarations, construction,
  Kotlin value semantics, KLIB applications, retention, use-site placement, and
  exact CLR custom-attribute projection
- Does not enable: annotation constructor values, enums, annotation arrays,
  annotation discovery through Kotlin reflection, arbitrary foreign CLR
  attribute import, or public .NET-specific source annotations

This document records the first parameterless floor. Its identity, retention,
parent-mapping, and no-wrapper rules remain in force; the later decision lifts
only its value-shape restriction and defines exact fail-closed CLR projection.

## Context

The Common contracts, builders, modern enums, and abstract-collections sources
form a bootstrap cluster which declares and consumes annotation classes. The
target must therefore establish annotation classes as a language feature, not
add a contracts-only compiler exception.

Kotlin annotations have two distinct roles which must not be conflated:

1. the logical Kotlin annotation declaration and each Kotlin application are
   part of the KLIB contract; and
2. a runtime-retained application may also have a truthful physical CLR
   custom-attribute view for reflection and C# interoperability.

The CLR constrains every custom-attribute class to derive from
`System.Attribute`, constrains constructor arguments and named values to its
custom-attribute value grammar, and has no runtime-invisible custom-attribute
channel corresponding to Kotlin `BINARY` retention. Those are physical CLR
constraints, not reasons to weaken Common semantics.

## Common and mature-target authority

Common defines annotation declarations, constructor calls, member-based
equality/hash/toString, targets, retention, and repeatability. The existing
KLIB serializer owns logical annotation declarations and applications.

The mature backends share the common annotation-member generator but adapt
physical construction to their host:

- JVM retains the annotation interface and creates a synthetic runtime
  implementation;
- JS uses the original annotation class as its implementation and generates
  Common value members directly;
- Wasm and Native use thin target adapters over the same common machinery.

The CLR requires one concrete `System.Attribute` subtype for a natively
applicable custom attribute. The .NET target therefore follows the JS
single-class shape while reusing the common member generator. This is the
smallest CLR-required deviation from the mature-target architecture and avoids
creating two physical identities for one parameterless marker.

## Decision

### Admitted declaration shape

The first annotation-class tranche admits a general declaration only when:

- its primary constructor has no value parameters;
- it declares no annotation value properties or type parameters;
- it is top-level or named and nested inside another admitted metadata class;
  and
- every application has no value arguments.

The shape gate rejects valued, generic, local, anonymous, or otherwise
unsupported annotation classes explicitly. This rule applies uniformly to
user, stdlib, and compiler-authored Kotlin source.

### Physical class and construction

An admitted Kotlin annotation class is emitted as one public or non-public
concrete sealed CLR class deriving from `System.Attribute`. Its Kotlin
constructor calls `System.Attribute::.ctor`; ordinary Kotlin construction such
as `Marker()` returns an instance of that same class.

The target uses the shared `AnnotationImplementationMemberGenerator` to
generate Common annotation `equals`, `hashCode`, and `toString` bodies on the
original class. No wrapper, translated copy, target-private annotation
interface, or second implementation class is introduced.

Logical `kotlin.Annotation` remains authoritative in KLIB. At the physical CLR
boundary it is represented by the `System.Attribute` base class, just as the
physical annotation declaration derives from that base.

### Retention and applications

Every declaration and application remains serialized in the embedded KLIB
according to the repository-wide Kotlin serializer contract. CLR projection
is additional and follows retention:

| Kotlin retention | KLIB | CLR custom attribute |
| --- | --- | --- |
| `SOURCE` | retained as required by compiler serialization | none |
| `BINARY` | yes | none |
| `RUNTIME` or omitted | yes | emitted on an exact physical metadata parent |

The CLR has no truthful runtime-invisible carrier for `BINARY`; emitting it as
a runtime-visible custom attribute would strengthen observability and is
forbidden.

The first physical-parent mapping covers exact class, constructor, method,
property, field, and value-parameter rows. Kotlin-only targets without an
exact CLR metadata parent, including expression, local-variable, file, type,
and typealias applications, remain KLIB-only. Getter and setter applications
are emitted on their exact accessor MethodDefs.

The emitted annotation declaration also receives `AttributeUsageAttribute`:

- `Inherited` is always `false`, because Common annotations are not
  implicitly inherited;
- `AllowMultiple` follows Common `@Repeatable`; and
- `ValidOn` is the conservative intersection that C# can author without
  admitting a broader Kotlin use. A target distinction which
  `AttributeTargets` cannot express receives no extra C#-authorable bit.

This usage attribute is a foreign-language authoring view. FIR and KLIB remain
the authority for Kotlin target checks.

When a newly selected Common/Stdlib declaration exposes an admitted
runtime-retained marker, the marker's authoritative shared source joins the
.NET Stdlib source closure. The backend must not drop the application, invent a
target-private substitute, or special-case reflection merely because the
previous source subset did not need that declaration. `ReturnValue.kt` is the
first such dependency: it supplies the real `IgnorableReturnValue` and
`MustUseReturnValues` declarations used by collection and builder APIs.

### Separate compilation and import

An admitted annotation declaration and its applications survive Kotlin
producer/consumer compilation through the embedded KLIB. The consumer binds
the producer-recorded CLR TypeDef when it must emit a runtime custom attribute;
it does not reconstruct the declaration from `AttributeUsageAttribute`.

The objective CLR custom-attribute decoder remains in shared .NET metadata
infrastructure. This tranche does not make arbitrary CLR attributes into
Kotlin annotation classes. Later foreign import may reuse the same exact
value grammar under the importer ADR.

## Design attack

### Emit every Kotlin annotation only in KLIB

Rejected for runtime-retained markers. It is semantically sufficient for a
Kotlin-only consumer but needlessly hides a fully exact CLR and C# view.

### Treat CLR attributes as the Kotlin declaration authority

Rejected. CLR metadata cannot carry all Kotlin targets, retention distinctions,
logical identities, or future Kotlin-only values. Attribute stripping must not
destroy the Kotlin library contract.

### Emit `BINARY` annotations as CLR custom attributes

Rejected. CLR custom attributes are runtime-visible through metadata APIs;
that would turn a non-runtime Kotlin annotation into a runtime annotation.
This controls Kotlin-produced applications. A public concrete
`System.Attribute` subtype remains authorable by C#; such an application is
foreign runtime-visible CLR metadata, not a Kotlin-produced application and
does not change KLIB retention. Roslyn accepts even `AttributeUsage(0)`, so
preventing this foreign authoring would require a split physical declaration
or non-Attribute representation. Both would add permanent identity complexity
only to constrain foreign code and are rejected for the marker floor.

### Add annotation values immediately

Rejected for this tranche. A truthful implementation must jointly map
`KClass` to `System.Type`, Kotlin arrays to raw custom-attribute vectors,
enums to exact CLR enum constants, defaults, named values, nested annotations,
and the importer/writer value algebra. Nested annotation values are not CLR
custom-attribute constants at all. Partial admission would either lie in CLR
metadata or create a second Kotlin value store.

### Use an annotation interface plus a separate CLR attribute class

Rejected. It follows the JVM physical constraint rather than the CLR one,
duplicates physical identities, complicates C# application, and would require
wrapping or translation. One concrete marker class satisfies both the Kotlin
runtime value and CLR custom-attribute roles exactly.

### Infer Kotlin reflection from CLR custom attributes

Rejected. `KClass` currently supplies nominal class identity only. Annotation
enumeration, use-site reconstruction, retention filtering, constructor values,
and inherited CLR attributes require a separate reflection programme.

## Invariants

1. Common declarations and embedded KLIB remain authoritative for annotation
   identity, target, retention, and Kotlin applications.
2. Every admitted annotation value is one concrete CLR object; construction
   and metadata use the same annotation class.
3. Only runtime-retained applications are projected into CLR custom-attribute
   rows.
4. CLR projection never broadens Kotlin source applicability.
5. Unsupported annotation values fail explicitly; they are never dropped or
   stringified.
6. The target reuses the common annotation member generator through one thin
   .NET lowering.
7. This feature does not imply annotation discovery, `KType`, enums, or broad
   foreign attribute import.
8. Expanding the admitted Stdlib source graph preserves supported annotation
   applications by compiling their shared declarations, never by filtering or
   synthesizing them.

## Verification

The feature gate must cover both FIR parsers and both runtime profiles, plus
portable separate compilation where applicable:

- adopt compatible shared `codegen/box/annotations` tests directly in every
  .NET box runner;
- top-level and nested marker construction, Common equality, hash, and string
  behavior;
- exact class, constructor, method, property, field, parameter, getter, and
  setter physical application rows;
- default/runtime, binary, and source retention, repeatability, and
  conservative `AttributeUsage` flags;
- C# application and CLR reflection over a portable Kotlin-produced marker;
- Kotlin producer/consumer identity through embedded KLIB;
- explicit rejection of valued and otherwise unsupported declarations; and
- absence of any annotation-reflection or arbitrary foreign-import claim.

The strict target gate and JUnit XML audit remain mandatory before this
pre-ABI decision is recorded as implemented.
