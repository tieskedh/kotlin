# ADR: `@InlineOnly` uses an assembly-visible CLR body

- Status: **Accepted — pre-ABI**
- Date: 2026-08-03
- Scope: the physical CLR visibility and consumption contract of Kotlin
  declarations annotated with `kotlin.internal.InlineOnly`

This is the target authors' working decision for the experimental target. It
is not a public KEEP or an official Kotlin target commitment.

## Context

The authoritative Common annotation says that an `@InlineOnly` function must
not be called directly without inlining. The declaration remains part of the
Kotlin source and metadata API: overload resolution, type parameters,
nullability, contracts, operator status, and its executable inline body all
belong to Kotlin and must survive in embedded KLIB for separate compilation.

JVM retains a MethodDef-equivalent body but changes its bytecode visibility to
package-private. It applies the same rule to an annotated property's accessors,
and can make a generated file class package-private when all of its members are
private or inline-only. Java therefore cannot use the method as public API,
while the Kotlin inliner can still read its body.

JS, Wasm, and Native consume the same declarations through KLIB. They do not
publish a separately callable host-library method for every declaration before
the final program is linked: whole-program inlining and reachability remove the
need for a public foreign-language fallback. Their shared post-inlining
validation rejects call sites whose required body did not become available.

Kotlin/.NET combines those two shapes. Like the KLIB targets, embedded KLIB is
the authority for a separate Kotlin consumer. Like JVM, a producer DLL is an
executable library artifact created before that consumer exists and therefore
retains an ordinary physical method body. The CLR has no package visibility;
its direct assembly-level equivalent is `assembly` (`internal` in C#).

## Decision

An inline function annotated with `kotlin.internal.InlineOnly`, or an accessor
whose corresponding property carries that annotation, emits an
assembly-visible CLR method. Its containing type may remain public when it also
owns ordinary public declarations. The method remains recorded in the
self-describing library's physical declaration index, but it is not a callable
cross-assembly Kotlin fallback and is not a C# source-authoring surface.

Every separate Kotlin consumer must obtain and inline the authoritative body
from the producer's embedded KLIB. The existing Common all-functions inliner
and post-inlining validation own this rule: a missing external body is a
compilation failure, not permission to emit a call to the inaccessible method.
Intra-assembly code may still call the physical body where the selected
pipeline intentionally retains such a call.

The target's bootstrap box harness is not an external-consumer proof for this
rule. It feeds stdlib and test sources through one frontend module, then
partitions their physical output into `Kotlin.Stdlib` and a user assembly after
the point at which a real consumer would have loaded and inlined the producer
KLIB. Making that artificial path callable would weaken the product ABI.
Inline-only cross-assembly behavior is therefore exercised through the real
self-describing stdlib and separate/installed KLIB consumer tests; the box
harness may cover the ordinary public overloads that do not require that
library boundary.

The first admitted Common collection batch also exercises a pre-existing
generic-interface representation boundary. Inlined Common loops can store an
erased canonical iterator result in an object local and later read it as the
function's open non-null `T`. That read uses the narrowly specified
`unbox.any !n`/`!!n` rule in the
[generic-interface ABI](generic-interface-erased-identity.md). It is independent
of inline-only visibility: the same Common body must behave identically when
materialized inside its assembly or inside a separate KLIB consumer.

Physical assembly visibility does not remove CLR overload rules. When two
logical inline-only declarations erase to the same CLR receiver and
`Function1` parameter, their MethodDefs still require distinct stable names.
The first such admitted declaration is `Iterable<T>.sumOf((T) -> Int)`: KLIB
keeps the logical name and selector type, while the physical body uses the
Common generator's explicit `sumOfInt` platform spelling. This is the bounded
stdlib projection selected by the generic-interface ABI, not general .NET
meaning for `@JvmName`.

Prepared inline IR can also refer directly to compiler-owned built-in
operators such as `kotlin.internal.ir.EQEQ`. The shared non-linking
deserializer binds those operations back to `IrBuiltIns`: exact signature
first, then a unique compiler-owned `CallableId` only when there is no overload
ambiguity. It does not classify an unresolved library function as a built-in.
The inliner can leave an external `GET_OBJECT kotlin.Unit` in a fallthrough
tail; the emitter maps only that built-in object to the existing runtime
`Unit.INSTANCE`. Arbitrary surviving object expressions remain unsupported.

Do not attach `KotlinCompilerAbiAttribute` or `EditorBrowsable(Never)` merely
because a method is inline-only. Those attributes identify a physically public
cross-assembly compiler ABI surface. An inline-only body is physically
non-public and needs no completion-hiding substitute. Reflection may still
discover it through non-public binding flags, as it can discover any internal
CLR method; that does not make direct invocation part of Kotlin or C# API.

## Rejected alternatives

### Keep the method public and add `EditorBrowsable(Never)`

Rejected. EditorBrowsable is advisory and reflection/source code can still
invoke the method directly. It would contradict the Common contract and JVM's
foreign-language surface without a CLR constraint requiring the deviation.

### Remove the MethodDef entirely

Rejected. JVM keeps a physical body, and the independently produced CLR
assembly still needs a valid owner for same-assembly calls, physical identity,
and library conformance. Method absence would create a target-specific
declaration-eviction model rather than implementing inline-only visibility.

### Emit a private method

Rejected. CLR `private` is type-scoped, while top-level declarations from
different Kotlin files and generated facades may need same-assembly access.
`assembly` is the truthful JVM package-level analogue for this target.

### Let an external Kotlin consumer fall back to the method

Rejected. Cross-assembly access to an assembly-visible method is illegal, and
more importantly would violate Kotlin's requirement that the declaration be
inlined. A missing or malformed KLIB body must fail during compilation.

### Interpret arbitrary CLR attributes as `@InlineOnly`

Rejected. This is a Kotlin compiler contract carried by Kotlin metadata and
KLIB. Foreign CLR declarations remain ordinary foreign callables unless an
explicit interop rule says otherwise.

## Invariants and verification

- KLIB retains the exact logical public declaration, annotation, types, and
  inline body in every supported KLIB inliner mode.
- The producer DLL contains one assembly-visible method, never a public or
  missing method, for each selected inline-only declaration.
- A separate and installed Kotlin consumer contains the inlined behavior and
  no call to the producer's inline-only method.
- A C# consumer cannot bind the method as public source API.
- Annotated property accessors follow the same physical rule when that source
  family is admitted.
- Same-module and cross-library non-local return, evaluation order, exception
  identity, and all declaration-specific Common behavior remain unchanged.
- Tests that claim external inline-only coverage consume an actual producer
  KLIB; a same-frontend bootstrap source injection is not treated as one.
- Compiler-owned equality and `Unit` fallthrough nodes from prepared inline IR
  bind without weakening the missing-library declaration check.
- Framework CLR and CoreCLR consume the same portable KLIB/library product.

## Consequences

The physical DLL is intentionally a weaker general-purpose fallback than for
an ordinary Kotlin inline function. That is not lost interop: `@InlineOnly`
explicitly opts the declaration out of direct foreign-language invocation.
Public C# conveniences, if useful, must be distinct wrappers with their own
interop design rather than visibility widening of the Kotlin declaration.
