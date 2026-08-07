# Declaration-owned callable type parameters

- Status: Accepted (pre-ABI)
- Scope: `KCallable.typeParameters` on function, constructor, property, and
  local delegated-property reference objects
- Depends on:
  [`ktype-and-typeof.md`](ktype-and-typeof.md),
  [`callable-return-types.md`](callable-return-types.md), and
  [`draft-adr-callable-and-reference-abi.md`](draft-adr-callable-and-reference-abi.md)
- Does not enable: `KParameter`, `KClass.typeParameters`, callable/member
  enumeration, `call`/`callBy`, accessor objects, or foreign CLR generic-method
  import

## Context and target precedent

Common `KCallable` exposes only `name`. JS and Wasm retain that floor, while
Native adds only `returnType`. JVM is the sole mature target that also exposes
`typeParameters`, and it builds the list together with every callable
parameter and return type through one declaration-owned type-parameter table.
The table's own list excludes parameters of enclosing classes, but those
enclosing parameters remain reachable wherever a return, parameter, or bound
refers to them. Bound and unbound references use the unbound declaration as
the type-parameter owner.

This is therefore a deliberate JVM-shaped .NET platform extension rather than
a Common or Native requirement. It is selected now because `KParameter` cannot
be implemented correctly on top of independently materialized types. The
smaller type-parameter slice first fixes the shared signature graph and owner
identity that later parameter types must reuse.

## Decision

### Reflect the exact declaration target

For a function reference, the own list is the exact rich reference target's
declared type parameters in declaration order. As on JVM, a constructor uses
the constructed class's own parameters: its return type and constructor value
parameters must resolve through that class-owned table. For a property
reference, the list comes from the original getter target's declared type
parameters, which includes the logical parameters of a generic extension
property. A local delegated-property token has no own type parameters.

Class or outer-function parameters are not included merely because a callable
uses them. They are still allocated in the same graph when reachable from the
return type or an own parameter's bound. Fake overrides and imported
declarations use the semantic target already selected before this lowering;
the generated invocation adapter and physical CLR signature remain
non-authoritative.

### Build one callable signature graph

The shared `DotNetKTypeIrBuilder` allocates the union of:

- every declaration parameter reachable from the return type;
- every callable-owned declared parameter, including an otherwise unused one;
- every parameter recursively reachable from their upper bounds.

All objects are allocated before any bound is initialized. The return `KType`,
the exposed own list, recursive bounds, and reachable enclosing parameters are
then built from that one allocation table. Consequently a return classifier
or bound classifier that denotes an exposed parameter is the same object as
the corresponding `typeParameters` element, not merely an equal reconstruction.

Generated code transports the completed graph as one private compiler/runtime
array: element zero is the return `KType`, and the remainder is the own
`KTypeParameter` list. Runtime stores the return object and a read-only list
view over that same array. This is not a Kotlin metadata format, CLR-reflection
decoder, or second signature authority; it is the indivisible construction
value required to preserve object identity across two public properties.

The transport is physically erased and private to compiler/runtime ABI. It
does not expose Kotlin-owned declaration generics as CLR generics and cannot
affect casts, object identity, dispatch, or the erased generic-class decision.

### Keep foreign generic methods fail-closed

Supported imported CLR declarations already contribute importer-enhanced
semantic IR. The current importer deliberately rejects an interface containing
a foreign generic method, so callable reflection does not decode ECMA-335
generic parameters as a shortcut. When full foreign generic-method import is
implemented, its admitted method-owned parameters and bounds must feed this
same graph.

## Design attack

### Materialize `returnType` and `typeParameters` independently

Rejected. Stable equality would hide the defect in simple tests, but classifier
object identity would diverge and later `KParameter.type` would introduce a
third copy. Recursive and enclosing bounds would become especially fragile.

### Enumerate physical CLR generic parameters

Rejected. Kotlin-owned declarations are KLIB-authoritative and generic classes
are physically erased. CLR parameters also cannot recover Kotlin nullability,
variance, projections, logical owners, or parameters absent from the emitted
signature.

### Expose every reachable parameter

Rejected. A member's own list excludes enclosing class parameters on JVM and
in Kotlin metadata. Reachability in a bound or return type is not declaration
ownership.

### Add `KParameter` in the same change

Rejected. `KParameter` additionally requires stable instance/context/
extension/value order, names, optional and vararg flags, bound-reference
reindexing, annotations, equality, and future `callBy` ownership. The shared
graph is its prerequisite, not permission to publish a partial parameter API.

### Reuse a mutable stdlib list as the carrier

Rejected. Runtime must not depend on Stdlib, and a separately built list would
again risk copied parameter objects. The Runtime-owned read-only view retains
the one array without exposing it as Kotlin `Array` identity.

## Consequences

- `.NET KCallable` intentionally advances from Native's return-type surface
  toward JVM's declaration-signature surface.
- Callable return types and own type parameters now have one construction and
  identity boundary; later `KParameter.type` must extend this graph rather
  than instantiate another one.
- Manual implementations of .NET reflection interfaces must implement the new
  property, matching JVM's abstract platform surface.
- Backend symbols feature-detect the property. Diagnostic compilations without
  the current Stdlib surface must retain their intended result instead of
  failing during backend-context construction.
- Runtime surface level advances to 20; the physical Kotlin declaration-index
  schema is unchanged.
- No CLR-generic Kotlin class, member enumeration, or reflective invocation is
  implied.

## Invariants

1. KLIB-derived or importer-enhanced semantic IR is signature authority.
2. `typeParameters` contains only parameters declared by that callable, in
   declaration order.
3. Every exposed parameter is the exact classifier object reused by return and
   bound types in that callable graph.
4. Reachable enclosing parameters participate in the graph but never leak into
   the own list.
5. Bound and unbound references preserve declaration ownership and equality.
6. Runtime CLR reflection and physical generic arguments never reconstruct the
   Kotlin callable signature.
7. Absence of the platform property disables this slice; it never preempts a
   `-no-stdlib` or malformed-library diagnostic with an internal error.

## Verification

The gate covers declared order, unused/reachable parameters, recursive and
enclosing bounds, generic constructors, exact object identity, list stability,
cross-site equality and hashing, bound/unbound functions and generic extension
properties, ordinary properties with generic enclosing classes, invocation on
the same objects, separate KLIB consumption, both FIR parsers, both CLR
profiles, exact IL, manual reflection-interface implementations, and the full
XML-audited aggregate.
