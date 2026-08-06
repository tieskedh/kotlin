# Kotlin annotation classes and exact CLR value projection

- Status: Accepted (pre-ABI)
- Supersedes: the value-shape restriction in
  [`marker-annotation-classes.md`](marker-annotation-classes.md)
- Scope: annotation-class declaration and construction, defaults, Common value
  semantics, KLIB applications, and fail-closed CLR custom-attribute values
- Does not enable: annotation discovery, broad member reflection, arbitrary
  foreign attribute import, or a CLR approximation of Kotlin-only values

## Context

Kotlin annotation values are language values and serialized Kotlin metadata.
CLR custom attributes are a useful additional view, but their fixed-argument
grammar is narrower: an attribute constructor cannot faithfully accept every
Kotlin `KClass`, Kotlin enum, nested annotation, primitive-array wrapper, open
type, or generic annotation construction.

Common owns legal annotation members, constructor/default behavior, targets,
retention, and generated equality/hash/string semantics. KLIB already carries
the complete logical declaration and application. The target therefore needs
complete Kotlin annotation values before it needs a complete CLR projection.

## Mature-target and platform position

The JVM creates implementation objects behind annotation interfaces. JS
generates the Common value members on the original annotation class. Native
and Wasm use thin target adapters over the same Common generator. The existing
.NET marker floor follows the JS single-class direction because a CLR custom
attribute must itself be one concrete `System.Attribute` subtype.

That physical constraint does not make CLR metadata authoritative. As with
Kotlin/JVM annotation metadata and Kotlin/Native export, the Kotlin declaration
remains complete independently of the foreign-language view.

## Decision

### One Kotlin runtime value

Every supported Kotlin annotation declaration is one concrete sealed CLR
class derived from `System.Attribute`. Ordinary Kotlin construction and a
metadata-applied value use that same class; no wrapper, shadow interface, or
translated object is introduced.

The target runs the shared annotation-member generator on the original class.
It therefore inherits Common `equals`, `hashCode`, and `toString`, including
content equality/hash for arrays and total floating equality where NaNs compare
equal and signed zeroes remain distinct.

Executable `IrAnnotation` nodes become ordinary constructor calls to that same
class. Attached metadata annotations remain metadata nodes. Defaults are
materialized while declaration default bodies still exist, and a deserialized
array constant is normalized from the declared annotation-member type rather
than inferred from its elements. This preserves exact empty and nested array
types across separate KLIBs.

### KLIB authority and retention

Every legal Kotlin declaration, constructor value, default, and application
uses the normal KLIB path. `SOURCE` and `BINARY` applications receive no CLR
custom-attribute row. A runtime-retained application may receive an additional
row only on an already admitted exact CLR metadata parent.

Removing custom-attribute rows from a Kotlin-produced DLL must not change what
a Kotlin compiler learns from its embedded KLIB. Conversely, a C#-authored row
is foreign CLR evidence; it does not manufacture a Kotlin-produced KLIB
application.

### Exact CLR fixed-argument subset

The emitter encodes a complete row only when the constructor signature and
every fixed value have an exact ECMA-335 representation. The admitted scalar
carriers are `Boolean`, `Byte`, `Short`, `Int`, `Long`, `Float`, `Double`,
`Char`, and `String`; exact generic-array vectors recursively use that admitted
element grammar. Current executable and interop evidence includes
`Array<String>`. Raw floating bits, UTF-8 `SerString`, little-endian scalar
encoding, array lengths/elements, the custom-attribute prolog, and zero named
arguments are emitted directly.

Arguments must already be constants or annotation-array literals. Defaults
are encoded only after materialization from the authoritative declaration. If
one parameter, value, default, target, or physical signature is not exact, the
whole CLR row is omitted while the KLIB application remains intact. Partial
rows, stringification, placeholder values, and target-specific shadow state
are forbidden.

The following currently remain KLIB-only:

- Kotlin primitive arrays, because their physical values are nominal runtime
  wrappers rather than the raw vectors required by custom-attribute blobs;
- `KClass`, because Kotlin's nominal/classified value is not `System.Type`;
- ordinary Kotlin enums, because they are reference classes rather than CLR
  `System.Enum` value types;
- nested annotation values, which are not CLR custom-attribute constants;
- open, erased, unsupported, or non-constant values; and
- applications of generic annotation classes, because their erased physical
  class cannot record the logical construction in a CLR constructor owner.

Those shapes still support Kotlin construction and Common value semantics when
their underlying language features are available.

### C# authoring

An exact public annotation class is an ordinary C# attribute. C# can construct
and apply the admitted typed constructor directly, and reflection reads the
same fields/properties and values. A class whose constructor contains a
Kotlin-only shape can still be a valid Kotlin runtime class, but C# cannot
author that invalid CLR attribute argument and Kotlin emits no misleading row.

## Design attack

### Delay Kotlin values until every CLR value is representable

Rejected. It would make a foreign metadata grammar constrain Common language
semantics and block useful Kotlin-only annotations.

### Map `KClass` to `System.Type` or Kotlin enums to CLR enums

Rejected. Both would contradict their accepted runtime identities and make an
annotation application depend on a second representation.

### Wrap unsupported values or emit only the representable arguments

Rejected. Reflection would observe a different annotation from KLIB, and
constructor identity or argument order would no longer match.

### Use CLR attributes as the round-trip store

Rejected. Retention, use sites, generic identity, Kotlin-only values, and
logical declarations are not recoverable from that store. CLR rows are an
additive interop projection only.

## Invariants

1. Common and KLIB are authoritative for every Kotlin annotation fact.
2. One concrete object implements each Kotlin annotation value; no CLR export
   creates a second annotation identity.
3. Runtime-retained applications receive a CLR row only when the complete row
   is exact.
4. Unsupported projection is omission of the derived row, never omission or
   mutation of the KLIB application.
5. Declared member types, not literal contents, restore deserialized default
   array types.
6. CLR metadata stripping cannot change Kotlin compilation semantics.
7. Annotation discovery and foreign attribute import require their own
   reflection/import decisions.

## Verification

The feature gate covers:

- both FIR parsers on Framework CLR and CoreCLR;
- upstream Common construction, defaults, nested annotations, arrays,
  equality/hash/string behavior, NaNs, signed zero, files, and separate KLIBs;
- exact scalar/string/string-array blobs and deliberate absence for unsupported
  values and non-runtime retention;
- portable Kotlin producer/consumer construction with cross-library defaults;
- C# application and reflection of typed Kotlin-produced attributes on both
  supported runtime profiles; and
- KLIB-only construction plus absence of a CLR row for `KClass` values.

The full target gate and JUnit XML audit remain mandatory before publication.
