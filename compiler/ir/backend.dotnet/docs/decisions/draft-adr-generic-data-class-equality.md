# Draft ADR: Generic data-class equality on CLR

- Status: **Draft candidate; implemented in the prototype for evaluation**
- Date: 2026-07-15
- Scope: Generated equality for Kotlin data classes with CLR-reified type parameters

This is a repository-local decision record for the experimental .NET backend. The entire `dotnet`
branch is a proof of concept; this document keeps that POC internally coherent while evidence is
collected. It does not claim acceptance by the Kotlin project and is not a public KEEP.

## Context

Kotlin data-class equality first checks that the other value has the same data-class identity and
then compares the primary-constructor properties. Type arguments do not participate in that
runtime class-identity check. On the JVM, for example, both `Box<String>` and `Box<Any>` have the
same erased runtime class. They may compare equal when their property values compare equal.

The .NET backend deliberately maps ordinary generic classes to real CLR generics. Fields,
parameters, returns, constructors, inheritance, and member calls retain `C<T>` in metadata and the
CLR reifies concrete instantiations. Emitting the common generated `other is C<T>` body literally
would therefore use `isinst C<T>` and reject `C<U>`, even when Kotlin requires the two data-class
instances to compare their values. That would make equality depend on the static type through
which it was invoked and could also break symmetry.

The representation must preserve Kotlin's erased equality identity without erasing the backend's
otherwise useful and already established CLR generic ABI.

## Decision drivers

The POC representation must:

1. make all instantiations of one Kotlin data class share the same equality identity;
2. keep different data-class declarations distinct, even when their property layouts match;
3. preserve ordinary reified `C<T>` storage, signatures, construction, dispatch, and copying;
4. compare property values through the established Kotlin `Any?` equality semantics;
5. add no public runtime type or global registry for a class-local implementation detail;
6. expose no compiler bridge through the class's public reflection surface; and
7. assemble and execute on both .NET Framework 4.8 and modern CoreCLR.

## Considered alternatives

### Emit `isinst C<T>` directly

Rejected. CLR generic identity includes the concrete arguments, while Kotlin data-class identity
does not. `Box<Any?>(null).equals(Box<String?>(null))` would incorrectly return false.

### Erase the complete data class to one non-generic CLR class

Rejected. This would solve only the equality test by discarding real generic metadata everywhere
else. Fields and members would become object-shaped, value instantiations would box, and the class
would no longer compose with the backend's established generic inheritance and call model.

### Use one public runtime interface for every data class

Rejected. A shared interface would still need a declaration-specific discriminator and a generic
property-access protocol or reflection. That would turn a generated member implementation detail
into runtime ABI, increase collision risk, and make unrelated data classes participate in one
global mechanism.

### Compare CLR generic type definitions through reflection

Rejected. Reflection adds runtime cost and a larger platform dependency to every generated
`Equals` call. It also obscures the declaration identity that IL metadata can encode directly.

### Add one private erased view per data-class declaration

Selected. A non-generic interface nested in the generic class definition has one CLR identity
across all constructed `C<T>` types, while a different data class owns a different nested
interface. CLR MethodImpl metadata permits the implementation methods to remain private.

## Candidate decision

For a generic data class with compiler-generated `equals`, the .NET lowering adds a private,
non-generic nested interface. It contains one abstract object-returning method per
primary-constructor property. The data class implements those slots with private final methods and
explicit `.override method` entries.

Conceptually, this source:

```kotlin
data class Box<T>(val value: T)
```

has this private physical equality view:

```text
Box<T> implements Box<T>.<DataClassErasedView>

private interface <DataClassErasedView> {
    object <DataClassComponent0>()
}
```

The nested interface itself has no generic parameters. CLR nested types do not implicitly capture
their enclosing type's arguments, so `Box<Int>` and `Box<String>` implement the same interface
identity. The interface is nevertheless declaration-local: `Other<T>` has a different nested
type and cannot pass `Box<T>`'s equality check.

Generated `Equals(object)` is rewritten to:

1. return true for reference identity;
2. return false unless `other` implements this data class's private erased view;
3. cast `other` once to that view;
4. read each current property value from `this`, box or widen it to `object`, and retrieve the
   corresponding object value through the view; and
5. compare each pair through `Kotlin.Runtime.Internal.Intrinsics.AreEqual`.

Array properties keep the mature-target data-class rule: this equality path observes ordinary
array reference identity. The separate generated hash and string paths continue to use content
for arrays. Primitive, nullable, reference, array, constrained, and open-type property values all
cross the same object equality boundary, preserving the existing Boolean, Double, Char, null, and
virtual-dispatch normalizations.

Everything outside generated equality remains the ordinary CLR generic representation. The class
is still `C<T>`; its fields, accessors, `componentN`, constructors, `copy`, `copy$default`, return
types, and call tokens retain their open CLR generic slots and concrete owner instantiations.
Values box only while crossing the private equality view.

The common default-argument lowering may leave a generated member call's IR result written with
the declaration's open owner type. A `copy$default` call through `C<Int>` therefore has IR result
`C<T>` but produces `C<Int>` on the CLR stack. Call resolution, rather than a data-class special
case, supplies the substituted CLR return type to coercion and implicit-cast decisions. This keeps
default copying composable with all generic member-return shapes and avoids an invalid
`C<!0>`-to-`C<int32>` cast.

## Visibility and ABI boundary

The view and its component methods are compiler-generated layout inside the produced class. They
are not Kotlin callable identity, Kotlin runtime ABI, or a CLR export surface. The interface is a
private nested type and each implementation is a private explicit interface method. The emitter
uses a MethodImpl `.override` row so the CLR can dispatch the private implementation without
making it public.

This metadata is necessarily present in the generated assembly and is therefore observable to
privileged reflection, just like other private compiler artifacts. Ordinary public reflection
does not enumerate the component bridges. Their special names and exact layout are not promised
to source or binary consumers.

## Consequences

Benefits:

- equality has Kotlin's declaration identity across CLR generic instantiations;
- normal generic metadata and unboxed value storage remain intact;
- unrelated data classes cannot compare equal through a shared structural protocol;
- no global runtime type, reflection lookup, wrapper allocation, or equality registry is added;
  and
- the CLR verifier owns interface dispatch through standard MethodImpl metadata.

Costs:

- every affected class gains one private nested interface and one private bridge per equality
  property;
- value-shaped properties box during equality comparison across the erased view; and
- the generated IL is more elaborate than the naturally erased JVM shape.

The boxing is confined to generated equality, whose semantic fallback is already object equality.
An exact typed fast path could be considered later only if it preserves the erased declaration
identity and falls back to this view for cross-instantiation comparisons.

## Validation

Exact-IL pins show that normal class signatures remain reified, the equality test names the
non-generic private nested view, bridge methods are private, and every bridge has an explicit
MethodImpl entry. Runtime pins cover equality symmetry across nullable, primitive, reference,
constrained, array-backed, nested, and multiple-type-parameter instantiations; declaration
separation; signed-zero and NaN behavior; hash/toString consistency; and constructor/copy default
arguments.

Probe series `generic_data_probe_s1` assembled both exact goldens with modern 10.0.9 and .NET
Framework 4.8 ILAsm. Framework-selected builds of every new runtime box also executed on CoreCLR.
Reflection over the two-property exact pin found no public component bridge or public erased-view
type, two private bridges, one private nested view, and an unchanged public property type `T`. The
fresh full PSI/LightTree .NET matrix is 434 tests with no failures, errors, or skips across eight
suites.

The repository pins are:

- `compiler/testData/codegen/dotnet/ilText/genericDataClasses.kt`;
- `compiler/testData/codegen/dotnet/ilText/genericDataClassDefaults.kt`;
- `compiler/testData/codegen/dotnet/box/genericDataClasses.kt`;
- `compiler/testData/codegen/dotnet/box/genericDataClassArrays.kt`;
- `compiler/testData/codegen/dotnet/box/genericDataClassShapes.kt`;
- `compiler/testData/codegen/dotnet/box/genericDataClassMultipleTypeParameters.kt`; and
- `compiler/testData/codegen/dotnet/box/genericDataClassDefaults.kt`.

## Deliberate boundaries

This draft changes only a generic data class whose `equals` body was compiler-generated. A data
class with a user-written equality implementation keeps that implementation and receives no
erased view. This draft does not define local data classes, data-object equality, unsupported
property types, general generic `is`/`as` operations, Kotlin metadata serialization, or CLR-facing
export projections. Data objects are handled by a separate singleton slice and do not consume the
private equality view. Later features must not weaken the reified generic representation or
repurpose this private equality mechanism as a public protocol.
