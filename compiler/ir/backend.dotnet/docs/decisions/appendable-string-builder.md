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

### Physical overload names and non-observable capacity policy

The classified CLR `object` carrier makes three Common overload pairs collide
after erasure. Their bounded physical naming table is:

- `StringBuilder.append(Any?)` becomes `appendAny`;
- `StringBuilder.insert(Int, Any?)` becomes `insertAny`; and
- top-level `StringBuilder.appendLine(Any?)` becomes `appendLineAny`.

KLIB retains the authoritative Kotlin names. The `CharSequence` overloads and
`Appendable` interface slots keep their ordinary physical names. This table is
not a general declaration-order or collision-suffix scheme; it gives only
these fixed Common overloads stable, C#-addressable CLR identities.

Common specifies no observable effect for `trimToSize`, so the .NET actual may
truthfully implement it as a no-op. Capacity remains an implementation hint.
Range validation, self-append/self-insert snapshots, `CharArray` copying,
search, surrogate-preserving reversal, and rendering remain Kotlin policy and
cannot be delegated wholesale to BCL overload behavior.

### Exact Common source closure

The first product phase compiles authoritative Common `Appendable.kt` and an
exact fail-closed projection of `StringBuilder.kt`. That projection contains
the complete expect class and every non-contract top-level extension; it
omits only the two top-level `buildString` declarations. Those two bodies are
the only declarations in the file that call Common `apply` and the public
contracts DSL. Changed, missing, duplicated, or ambiguous extraction markers
must fail generation, following the existing bootstrap-source policy.

This is not a partial builder class or a target-authored substitute. KLIB
publishes exactly the declarations physically present in the stdlib product,
and all extracted bodies remain byte-for-byte Common-owned. The deprecated
`append(CharArray, offset, len)` extension brings its exact Common
`NotImplementedError` dependency rather than a target exception substitute.

In the same phase, the bootstrap collection generator admits the exact Common
`Iterable.joinTo` and `Iterable.joinToString` template variants. It does not
copy their bodies, call LINQ, or introduce a .NET-only overload. Common
`AbstractCollection` and `AbstractList` may then consume that rendering
closure through the collections programme.

The later `buildString` phase compiles the two omitted declarations together
with exact Common `Standard.kt` and the complete public `kotlin.contracts`
source family. That family includes effect interfaces, parameterless
annotation classes, and the public `InvocationKind` enum. Resolution-only
contract copies, target-local shims, rewritten `buildString`, or a one-enum
exception remain forbidden. Once the dependency exists, the temporary
projection is replaced by the complete ordinary Common file.

That later phase is now complete under the
[Common-contracts ADR](common-contracts-product.md): the generator projects the
whole Common `StringBuilder.kt`, including both `buildString` declarations,
beside the authoritative contracts and `Standard.kt` source product.

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

- Kotlin callers first see the complete Common builder class and non-contract
  extension surface with stable identity on every profile; the later contracts
  phase adds the two exact Common `buildString` declarations without changing
  that class ABI.
- C# sees a truthful Kotlin `Appendable` interface and wrapper class rather
  than a false claim about the sealed BCL builder. Its public constructors are
  CLR-public; the constructor accepting private storage remains CLR-private.
- The wrapper adds one allocation beside its private BCL storage. This is the
  cost of preserving Kotlin identity on a host without the JVM relationship.
- Storage can change before or after ABI freeze without changing public
  identity, provided behavior and private implementation metadata remain
  unobservable.
- `NotImplementedError` enters the first stdlib phase as the exact dependency
  of the deprecated append extension. `Standard.kt` scope functions enter
  later with Common `buildString`; neither is a .NET-specific substitute.

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

This ADR does not admit other generated string or array `joinTo` families, add
BCL collection adapters, or define general reflection over private CLR
implementation fields. Common abstract collection bases consume this surface
under the collections programme. The typed collection-to-array representation
is selected independently by [its own ADR](collection-to-array.md). This ADR
also does not own the general enum or contract representations used by the
later `buildString` phase. Those remain separate language products and are now
completed under the ordinary-enum and Common-contracts ADRs.
