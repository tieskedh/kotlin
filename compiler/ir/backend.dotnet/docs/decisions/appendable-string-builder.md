# ADR: Kotlin-owned `Appendable` and `StringBuilder`

- Status: **Accepted — pre-ABI**
- Date: 2026-08-01
- Scope: Common `Appendable`/`StringBuilder`, builder storage, `buildString`,
  generated `joinTo`/`joinToString`, physical ownership, and `CharSequence`
  interaction

This is the selected direction for the experimental target. It is not a
public KEEP or an official Kotlin target commitment.

## Context

Common Kotlin owns the complete `Appendable` interface, `StringBuilder`
expect class, builder extensions, and `buildString` functions. The Common
collection generator owns `Iterable.joinTo` and `Iterable.joinToString`.
Admitting the latter requires the complete public builder contract rather
than a target-local append-only substitute.

The mature targets preserve that logical surface:

- JVM actualizes `Appendable` and `StringBuilder` as type aliases to
  `java.lang.Appendable` and `java.lang.StringBuilder`. The host classes
  already have the required physical relationship.
- JavaScript owns Kotlin `Appendable` and `StringBuilder` identities and uses
  a JavaScript string as mutable-by-replacement private storage.
- Wasm JS owns the same Kotlin identities over private `JsString` storage.
- Native and Wasm WASI own the Kotlin identities over a growable character
  array.

All four compile the complete Common declaration family and keep generated
collection rendering in the Common generator. Storage differs only behind
the public Kotlin identity.

CLR does not have the JVM relationship. `System.Text.StringBuilder` is sealed,
inherits only `System.Object`, and implements no interface equivalent to
Common `Appendable` or the selected `Kotlin.CharSequence` capability. It also
cannot be retrofitted with either Kotlin-owned interface. Mapping the Common
class directly to it would therefore falsify both physical interface maps and
the already accepted classified `CharSequence` model.

## Decision

### Logical and physical identity

`kotlin.text.Appendable` is an ordinary Kotlin-owned interface in
`Kotlin.Stdlib.dll`. `kotlin.text.StringBuilder` is an ordinary Kotlin-owned
class in the same assembly and physically implements both that interface and
the runtime `Kotlin.CharSequence` capability.

KLIB remains authoritative for the complete Common surface. The class is not
an alias for, subclass of, or classified alternative alongside
`System.Text.StringBuilder`. Its public CLR identity remains stable across
target-framework profiles.

Because every legal Kotlin `Appendable` physically implements the selected
interface, `T : Appendable` retains a truthful CLR interface constraint. This
differs deliberately from `T : CharSequence`, whose constraint is omitted so
that raw `System.String` remains a legal substitution.

### Private CLR storage

Each Kotlin builder owns one private `System.Text.StringBuilder` instance.
The field is represented through private implementation detail and never
appears in a public or protected Kotlin signature. The original BCL builder
does not escape through casts, return values, equality, reflection contracts,
or interop metadata.

Narrow target-private external operations create the storage and perform its
irreducible character, length, capacity, append, insert, remove, substring,
and string-conversion mechanics. The backend compiles those calls directly to
profile-portable BCL member references and omits the external declarations.
They are compiler implementation, not runtime services or ordinary stdlib
algorithms, and do not expand `Kotlin.Runtime` surface level.

The Kotlin actual owns every semantic policy above those mechanics:

- null renders as the four characters `null`;
- arbitrary `CharSequence` values are consumed through their logical indexed
  operations rather than through `toString`;
- `Any`, Boolean, and numeric values use Kotlin `toString` semantics rather
  than culture-sensitive CLR overloads;
- Common range validation and exception categories precede host operations;
- search, surrogate-preserving reversal, and range-copy loops remain Kotlin
  algorithms; and
- every mutating operation returns the same Kotlin wrapper where Common says
  it does.

The storage choice is therefore a CLR implementation detail, not a transfer
of semantic authority to the BCL.

### Exact Common source closure

The completed product will compile the authoritative Common `Appendable.kt`
and `StringBuilder.kt` files with narrow .NET actuals. `StringBuilder.kt`'s
`buildString` body calls the Common inline scope function `apply`, so the exact
Common `Standard.kt` file is part of this source-level dependency closure. Its
declarations must be admitted together rather than replacing `apply` or
rewriting `buildString` in target source.

Both Common files also declare contracts. Their exact dependency closure is
the public `kotlin.contracts` source family, not a compiler-private stub. That
family includes effect interfaces, binary-retained annotation classes, and
the public `InvocationKind` enum. Kotlin/.NET must support and publish those
declarations truthfully before publishing this builder surface. Resolution-
only copies, omitted physical declarations, target-local contract shims, or a
one-enum codegen exception would make the stdlib's own KLIB broader than its
executable product and are forbidden.

Once that prerequisite is complete, the bootstrap collection generator admits the exact Common
`Iterable.joinTo` and `Iterable.joinToString` template variants. It does not
copy their bodies, call LINQ, or introduce a .NET-only overload. The ordinary
and packaged-source stdlib paths compile the same files into one
self-describing `Kotlin.Stdlib.dll`.

### `CharSequence` interaction

The Kotlin wrapper implements the existing runtime `Kotlin.CharSequence`
capability on the same object. Widening it to `CharSequence`, calling its
members, testing or casting it, or passing it through a generic bound uses the
already accepted classifier and never exposes its BCL storage.

The positive classifier remains exactly raw `System.String` plus values
implementing `Kotlin.CharSequence`. Raw `System.Text.StringBuilder` is not
added as a third arm. Doing so merely because it is useful private storage
would make unrelated foreign builder instances silently acquire Kotlin
identity and would make the classifier depend on an implementation choice.

## Alternatives rejected

### Type-alias or map directly to `System.Text.StringBuilder`

Rejected. The sealed host class implements neither required Kotlin
capability, so the JVM precedent is not physically available. Pretending
otherwise breaks interface dispatch, generic constraints, KLIB-to-CLR
binding, and `CharSequence` casts and tests.

### Add `System.Text.StringBuilder` to the `CharSequence` classifier

Rejected. Public logical identity must not be inferred from private storage.
It would widen the accepted classifier, admit arbitrary foreign builders,
and make a storage substitution into a runtime ABI change.

### Wrap only when a builder reaches a Kotlin boundary

Rejected. Conditional wrapping changes reference identity, permits two Kotlin
objects for one host builder, and makes mutation visibility depend on the
path used to cross the boundary.

### Use an immutable `String` as private storage

Rejected for CLR. JavaScript uses that design because its host string
operations are its native storage mechanism. Repeated append would be
quadratic on CLR even though the BCL provides an exact private mutable
mechanism. This would be a current-implementation shortcut, not a CLR
constraint.

### Duplicate the Native/Wasm character-array implementation

Rejected. It is semantically valid but would introduce target-owned growth,
copy, and memory policy despite a profile-portable BCL storage primitive. The
Kotlin wrapper already prevents the host type from becoming logical Kotlin
identity.

### Publish only the members needed by `joinToString`

Rejected. Common declares one complete expect class, and every mature actual
implements it. A partial target-authored class would produce a false stdlib
surface and make later completion an ABI correction rather than ordinary
feature growth.

### Put builder operations in `Kotlin.Runtime`

Rejected. The runtime owns generated-code identities and services;
`StringBuilder` is ordinary stdlib policy. Direct BCL intrinsics are sufficient
for the irreducible mechanics and avoid a new versioned runtime service.

## Ownership

- Common Kotlin: logical declarations, builder extensions, `buildString`,
  null/range/rendering semantics, and contracts.
- Common stdlib generator: `joinTo` and `joinToString` bodies and overloads.
- .NET stdlib actual: Kotlin wrapper, semantic algorithms, and private
  external-operation declarations.
- .NET backend: direct, declaration-suppressing BCL operation intrinsics and
  physical stdlib binding.
- `Kotlin.Stdlib.dll`: public Kotlin interface/class/facades and all ordinary
  implementations.
- `Kotlin.Runtime.dll`: unchanged `CharSequence` capability and classifier;
  no builder storage or algorithm service.

## Consequences

- Kotlin callers see the complete Common builder surface and stable Kotlin
  identity on every profile.
- C# sees a truthful Kotlin `Appendable` interface and wrapper class rather
  than a false claim about the sealed BCL builder.
- The wrapper adds one allocation beside its private BCL storage. This is the
  cost of preserving Kotlin identity on a host without the JVM relationship.
- Storage can change before or after ABI freeze without changing public
  identity, provided behavior and private implementation metadata remain
  unobservable.
- `Standard.kt` scope functions and `NotImplementedError` enter the supported
  stdlib source product as the exact dependency closure of Common
  `buildString`; they are not .NET-specific substitutes.

## Freeze conditions and boundaries

Before this surface freezes, tests must pin:

- all constructors and every Common member/extension family, including empty
  and invalid boundaries;
- null, primitive, Boolean, arbitrary object, `CharArray`, `String`, raw
  string, and hostile custom-`CharSequence` rendering;
- surrogate-pair reversal, mutation identity, capacity, length growth, range
  replacement/deletion/copying, and search boundaries;
- custom Kotlin and handwritten C# `Appendable` implementations plus truthful
  generic constraints and interface maps;
- `buildString`, `apply`, `joinTo`, and `joinToString` lambda/control-flow,
  limit, truncation, transformation, and traversal behavior;
- wrapper classification through direct, widened, nullable, generic, and
  separate-library `CharSequence` views;
- direct, fallback, installed, and portable stdlib products on Framework CLR
  and CoreCLR; and
- absence of `System.Text.StringBuilder` from public/protected signatures and
  from the runtime classifier.

This ADR does not admit other generated string or array `joinTo` families,
compile Common abstract collection bases, add BCL collection adapters, or
define general reflection over private CLR implementation fields. The typed
collection-to-array representation is selected independently by
[its own ADR](collection-to-array.md). This ADR also does not select the general
enum or annotation-class representations required by the contract DSL; those
hard-to-reverse language decisions remain separate prerequisites.
