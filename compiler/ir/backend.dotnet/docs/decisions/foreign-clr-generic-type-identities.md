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

public interface DerivedBox<T> : Box<T> {}
public interface Reordered<L, R> : Pair<R, L> {}
public interface Fixed<T> : Pair<string?, T> {}
public interface NullableUse<T> : Box<T?> {}
public interface NestedBox<T> {
    Box<T> Nested();
}

public interface NullableValueApi {
    int? Echo(int? value);
    Box<int?> Nested(int? value);
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
retaining the class-file owner and signature as physical linkage. Its FIR Java
class keeps the resolved foreign supertypes and enhances their nullability as
class-header types; type-parameter bounds and supertype uses remain separate
evidence. Java platform types survive FIR and FIR2IR, while the backend does not
reinterpret an enhanced Kotlin view as a different JVM member descriptor.
Kotlin/Native follows the same direction for platform interop and owns target-
specific override rules in its FIR session.

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

The selected assembly and exact TypeDef, resolved hierarchy, MethodDef, Property,
MethodSemantics rows, and resolved member signature remain attached to the
imported declarations. All declaration carriers from one provider share one
validated selected-graph object. It retains admitted classifier owners and the
exact selected physical core identities needed to classify value carriers;
carrier construction neither retains the whole unselected classpath nor repeats
graph validation per member. The backend checks
semantic and physical arity and emits the original metadata name and constructed
CLR identity directly from that graph. It never routes this owner through the
Kotlin-owned erased generic interface ABI, invents a split interface, rediscovers
a slot by display name, or resolves a second nominal graph after FIR enhancement.
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

### Physical `System.Nullable<V>` has a Kotlin nullable-scalar view

An exact selected `valuetype System.Nullable<V>` in a foreign signature maps to
the logical Kotlin type `V?` when `V` is one of the eight admitted signed Common
primitive carriers. The nullable owner consumes no Roslyn reference-nullability
flag; any enclosing reference construction and its children retain their normal
preorder. Thus `Box<int?>` becomes `Box<Int?>`, while the retained signature still
emits the original `Box<Nullable<int32>>` MethodDef slot.

Recognition uses the selected core TypeDef retained in the declaration graph,
never the namespace or metadata-name spelling. FIR2IR compares Kotlin overrides
with that same identity and the backend lowers it through the target profile's
existing `NullableValue` carrier. A malformed class-encoded nullable, nested
nullable, nullable reference, open `T? where T : struct`, user struct, or lookalike
TypeDef rejects the complete classifier in this slice. Roslyn's unconstrained
annotated `T?` remains a generic nullability problem; it is not physical
`System.Nullable<T>` and is not admitted by this mapping.

### InterfaceImpl owns inherited use-site nullability

An inherited generic interface is retained as one exact selected InterfaceImpl
row plus its resolved TypeSpec. Roslyn emits the same physical TypeSpec for
`Box<string>` and `Box<string?>`; their difference is a `NullableAttribute` on
the InterfaceImpl row. Its preorder begins with structural root `0`, followed by
the generic arguments: `[0, 1]` for the non-null use and `[0, 2]` for the nullable
use. Reordered, mixed fixed/open, and nested constructions extend that same
preorder rather than changing physical type identity.

The shared nullable declarationsite resolver therefore treats InterfaceImpl as
its own local attribute parent and uses the implementing TypeDef for containing
context, effective accessibility, and `NullablePublicOnly`. The selected graph
validates the physical row, implementing owner, target TypeDef, and selected
assembly by identity. FIR consumes the structural root without making a nullable
supertype and enhances only its arguments; the backend continues to emit the
unchanged resolved TypeSpec.

Oblivious concrete reference arguments remain platform types. An owner type
parameter in a supertype is different: absent/`0` and `1` both retain `T`,
while `2` produces `T?`. Turning the first case into `T!` would manufacture a
nullable branch after substitution (for example around `Int`) and break an
otherwise exact inherited override. The type-parameter declaration and actual
construction already determine whether `T` itself denotes a nullable type.

Within the existing admitted type grammar, this permits closed, reordered,
mixed fixed/open, and explicitly nullable owner-parameter InterfaceImpl uses.
It does not admit a constrained constructed target, an unsupported carrier, or
an incomplete target graph.

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

The admitted type-owned slice accepts an interface only when:

- it is public, top-level, abstract, and unambiguous;
- GenericParam rows are contiguous, have valid variance, and have no `class`,
  `struct`, `new()`, or by-ref-like special constraint;
- every direct inherited interface is another exact selected public top-level
  interface; its fixed, reordered, duplicated, or open arguments must satisfy
  this same closed physical grammar, and the selected inheritance graph is
  complete and cycle-free;
- owner and method uses may be direct `!n`/`!!n`, supported signed primitive,
  `string`, `object`, supported SZARRAY, exact selected non-generic interface, or
  a recursively constructed exact selected interface; an exact selected
  `System.Nullable<V>` is additionally admitted only for one of the eight signed
  Common primitive carriers and may occur recursively inside those constructions;
- every constructed target has matching arity and no special or nominal owner
  constraints in this slice, and every nominal node survives the same complete
  classifier-selection fixpoint;
- explicit bounds are relative generic parameters or exact admitted nominal
  non-generic interfaces from the selected graph;
- every variance occurrence is valid for its declaration position; and
- no declared member owner- or method-generic leaf has explicit nullable
  evidence. An InterfaceImpl use may project an owner parameter as `T?` because
  its row supplies the complete use-site evidence and physical substitution.

Nullable-reference flags for a constructed signature are consumed in Roslyn's
preorder across the complete resolved type tree. Kotlin reflection keeps the
declaration-owned type (`Box<T>` on `NestedBox<T>.Nested`), while an invocation on
`NestedBox<String>` emits the substituted physical `Box<string>` signature.

Unsigned CLR scalars remain outside this slice until Kotlin unsigned value-class
carriers are implemented end to end. Constructed bounds, constrained constructed
targets, pointers, byrefs, general arrays, special constraints, and explicit
nullable generic leaves on declared members likewise reject the complete
classifier. No public member or inherited contract is silently omitted.

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
  bounds, open generic inheritance, recursively constructed member types,
  declaration-owned `KType` reflection, and separate compilation share one
  declaration graph and one retained physical identity.
- Foreign `Nullable<V>` scalar parameters, returns, properties, and recursively
  constructed arguments use logical `V?` semantics and the exact original CLR
  carrier without a wrapper or name-based rebinding.
- Fixed, reordered, mixed open/fixed, and nullable-parameter InterfaceImpl uses
  receive their exact Kotlin supertype arguments while retaining one unchanged
  CLR TypeSpec and its inherited physical slots.
- Imported CLR generic interfaces never acquire Kotlin implementation manifests
  or canonical erased sibling TypeDefs.
- Constrained constructed targets require a later exact slice; this decision
  forbids approximating them but does not forbid implementing them.

## Verification obligations

Coverage must retain both Framework CLR and current CoreCLR profiles and prove:

- source-name/metadata-name separation and exact constructed TypeRefs;
- open generic class-literal identity and constructed `KType` arguments;
- String, Int, and `Int?` owner constructions, mutable properties, owner plus
  method parameters, vectors, `params`, variance, and admitted bounds;
- all eight exact foreign `System.Nullable<V>` scalar method carriers, nullable
  values, mutable properties, nested `Box<V?>` constructions, and rejection of
  unsupported nullable user structs;
- Kotlin-to-C# and C#-to-Kotlin dispatch through the original interface,
  including primitive-vararg storage projection;
- direct open generic inheritance and both Kotlin and C# implementations of its
  inherited physical slots;
- closed, reordered, fixed/open, and inherited `T?` InterfaceImpl substitution,
  including nullable versus oblivious evidence and reverse implementations;
- recursively constructed member returns such as `Producer<Box<T>>`, including
  exact call-site substitution and reverse Kotlin implementation dispatch;
- declaration-owned callable reflection (`Box<T>`) remaining distinct from the
  substituted CLR invocation carrier (`Box<string>`);
- inferred and explicit method arguments inside a constructed owner;
- separate producer/consumer compilation and exact physical MethodImpl rows; and
- complete rejection of constrained constructed targets, unsigned scalar
  carriers, special constraints, and explicit nullable generic leaves on
  declared members.
