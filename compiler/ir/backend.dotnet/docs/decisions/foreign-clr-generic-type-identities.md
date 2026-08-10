# Foreign CLR generic interfaces retain their native TypeDef identity

- Status: **Accepted (pre-ABI)**
- Scope: type-owned CLR generic parameters on admitted foreign interfaces

## Context

A C# interface such as:

```csharp
public interface Box<T> {
    T Value { get; set; }
    T First(params T[] values);
    U Select<U>(T ownerValue, U selected);
}
```

is already one reified CLR TypeDef named ``Box`1``. Its members use owner parameters
as `!n`, method parameters as `!!n`, and constructed uses as
``class Box`1<string>`` or ``class Box`1<int32>``. Kotlin needs an ordinary source
name, declaration-site variance, bounds, platform nullability, substitution,
override checking, callable reflection, and the exact same runtime slots.

This is not the representation problem solved by Kotlin-owned generic-class and
generic-interface erasure. The foreign TypeDef belongs to .NET and is the
authoritative runtime identity. Replacing it with the target's Kotlin-owned
erased ABI would make Kotlin incapable of faithfully consuming or implementing
normal C# libraries.

## Mature-target precedent

Kotlin/JVM imports a Java generic owner into one semantic class symbol while
retaining the class-file owner and signature as physical linkage. Java platform
types remain flexible through FIR and FIR2IR; the backend does not reinterpret an
enhanced Kotlin view as a different JVM member descriptor. Kotlin/Native follows
the same direction for platform interop and owns target-specific override rules
in its FIR session.

The reusable architecture is therefore:

```text
foreign metadata -> one enhanced semantic declaration -> retained host binding
```

The CLR-specific result is deliberately more direct than Kotlin-owned erasure:
CLR generic TypeDefs are reified and support value-type constructions, so an
admitted `Box<Int>` remains the actual `Box<int32>` capability.

## Decision

### One semantic owner and one physical owner

The importer removes only the metadata arity suffix from the Kotlin source name.
It creates owner type-parameter symbols before bounds, receiver types, properties,
and methods. Every `!n` use resolves to that graph; method-owned `!!n` continues
to use the method-generic graph. Declaration-site CLR variance becomes the
corresponding Kotlin variance.

The selected assembly and exact TypeDef, MethodDef, Property, and MethodSemantics
rows remain attached to the imported declarations. The backend checks semantic
and physical arity and emits the original metadata name and constructed CLR
identity. It never routes this owner through the Kotlin-owned erased generic
interface ABI, invents a split interface, or rediscovers a slot by display name.
Its class literal uses the selected open generic TypeDef as CLR evidence, while a
constructed `KType` retains the logical Kotlin arguments.

### Platform flexibility survives FIR2IR

Oblivious foreign uses remain platform types. The .NET FIR2IR pipeline preserves
the same synthetic flexible-nullability marker used by JVM FIR2IR. For a flexible
primitive construction such as `Box<Int!>`, physical mapping uses the non-null
CLR scalar carrier (`int32`), not `System.Nullable<int32>`. An explicit rigid
Kotlin `Int?` still uses `System.Nullable<int32>`.

This marker is semantic input to physical mapping, not a CLR annotation contract
and not a second nullability authority. Kotlin-produced declarations remain
KLIB-authoritative.

### Kotlin implementations fill the native slots

A Kotlin class may implement an admitted constructed interface directly. The
target FIR override checker delegates to the standard Kotlin checker first and
adds one narrow CLR rule: a rigid primitive parameter can fill the lower scalar
carrier of a flexible imported primitive parameter. Every other name, receiver,
arity, method-parameter, vararg, visibility, property, and type rule remains
standard.

When substitution turns a foreign `params T[]` slot into `int32[]` but Common
Kotlin requires an implementing `vararg Int` body to receive `IntArray`, a private
MethodImpl adapter projects the same CLR vector storage into the canonical Kotlin
primitive-array wrapper before calling the Kotlin body. The reverse call boundary
projects wrapper storage back to the vector. These are boundary adapters, not
alternative object identities; storage association preserves the established
primitive-array identity rules.

### Closed admitted grammar

The first type-owned slice admits an interface only when:

- it is public, top-level, abstract, unambiguous, and has no inherited interface;
- GenericParam rows are contiguous, have valid variance, and have no `class`,
  `struct`, `new()`, or by-ref-like special constraint;
- owner and method uses are direct `!n`/`!!n`, supported signed primitive,
  `string`, `object`, or SZARRAY forms already supported by physical codegen;
- explicit bounds are relative generic parameters or exact admitted nominal
  non-generic interfaces from the selected graph;
- every variance occurrence is valid for its declaration position; and
- no owner- or method-generic leaf has explicit nullable evidence.

Unsigned CLR scalars remain outside this slice until Kotlin unsigned value-class
carriers are implemented end to end. Constructed member types and bounds, generic
interface inheritance, nested GenericInstance signatures, pointers, byrefs,
general arrays, special constraints, and explicit nullable generic leaves reject
the complete classifier. No public member is silently omitted.

## Design attack

Erasing an imported `Box<T>` would discard native C# identity, value-type
instantiation, variance, constraints, overloads, and reverse implementation.
Giving it both an erased Kotlin owner and a typed CLR owner would recreate the
dual-runtime model deliberately rejected for Kotlin-owned types. Treating flexible
`Int!` as `Int?` would manufacture a physical Nullable carrier absent from the
MethodDef. Relabelling `int32[]` as `IntArray` without a boundary adapter would
make calls appear correct while implementations receive the wrong object shape.

The exact native route is therefore the smaller semantic model: foreign CLR
generics stay CLR generics; Kotlin-owned generics keep the separately accepted
Kotlin ABI; only proven boundary differences receive adapters.

## Consequences

- Kotlin can consume and implement admitted `I<T>` constructions for reference,
  primitive, and explicitly nullable primitive arguments without wrappers around
  the foreign object.
- Owner and method generic parameters, arrays, `params`, properties, variance,
  bounds, constructed `KType` reflection, and separate compilation share one
  declaration graph and one retained physical identity.
- Imported CLR generic interfaces never acquire Kotlin implementation manifests
  or canonical erased sibling TypeDefs.
- Broader foreign constructed types and inheritance require later exact slices;
  this decision forbids approximating them but does not forbid implementing them.

## Verification obligations

Coverage must retain both Framework CLR and current CoreCLR profiles and prove:

- source-name/metadata-name separation and exact constructed TypeRefs;
- open generic class-literal identity and constructed `KType` arguments;
- String, Int, and `Int?` owner constructions, mutable properties, owner plus
  method parameters, vectors, `params`, variance, and admitted bounds;
- Kotlin-to-C# and C#-to-Kotlin dispatch through the original interface,
  including primitive-vararg storage projection;
- inferred and explicit method arguments inside a constructed owner;
- separate producer/consumer compilation and exact physical MethodImpl rows; and
- complete rejection of inheritance, nested constructed signatures, unsigned
  scalar carriers, special constraints, and explicit nullable generic leaves.
