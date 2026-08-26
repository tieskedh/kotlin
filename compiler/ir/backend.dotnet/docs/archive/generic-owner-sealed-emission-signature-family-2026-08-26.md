# Generic-owner sealed emission-signature family

Date: 2026-08-26

## Context

The preceding checkpoint compared a complete four-TypeDef, six-MethodDef,
two-MethodImpl implementation-family manifest with final-emission structure.
It deliberately stopped before `SEALED_EMISSION_SIGNATURE_INDEX`: an additive
advance from BOUND could retain an expected declaration for which the final
emitter had produced no row, and exact final names, paths, and CLI flags were
not yet authority.

This checkpoint closes that boundary for the same bounded direct-producer
family. The seal is fresh and actual-only. BOUND supplies stable opaque keys
and the expected structural manifest used to correlate and validate final
rows, but no BOUND row is copied, overlaid, or advanced into the result. An
expected row without corresponding final evidence therefore remains
unavailable instead of appearing sealed.

## Fresh family-scoped certificate

One successful certificate contains exactly the already-bounded family:

```text
four TypeDefs
    natural generic interface
    interface semantic capability
    implementation generic class
    class semantic capability

six MethodDefs
    natural producer slot
    interface semantic producer slot
    typed implementation entry
    class-capability producer slot
    class-capability dispatcher
    interface-capability dispatcher

two MethodImpls
    the two explicit semantic dispatcher bindings
```

The ordinary typed implementation satisfies the natural interface through
implicit CLR interface mapping, so the certificate does not fabricate a third
MethodImpl. The family adapter instead requires the actual natural slot and
typed implementation entry to retain the same exact physical name. That
implicit-mapping policy is deliberately outside the general sealed-index
identity model.

The immutable sealed index is queryable only by an already selected opaque
TypeDef or MethodDef key. It exposes no enumeration or physical-name lookup
from which a caller could invent authority. Its rows are frozen copies of the
successful final observations; the expected structural manifest is used only
for comparison.

## Exact final facts

Each actual TypeDef row combines the complete structural row with its final,
non-empty physical path and the supported raw CLI TypeDef flag vector:

```text
visibility
layout
string format
interface
abstract
sealed
beforefieldinit
```

Paths must be unique inside the family. Their nesting depth must agree with
the exact nested/non-nested visibility, and the interface flag must agree with
the structural class/interface category. Alias groups, generic arity and
variance, GenericParam constraints, and complete direct BaseType/InterfaceImpl
sets remain subject to the preceding structural comparison.

Each actual MethodDef row combines its structural signature with its exact
physical name and full supported MethodDef flag decisions:

```text
visibility
instance/static convention
virtual
newslot
abstract
final
hidebysig
specialname
rtspecialname
```

The exact flags must agree with the structural visibility and dispatch
contract, and `rtspecialname` is invalid without `specialname`. The structural
signature continues to retain owner identity and category, receiver carrier,
method generic arity, explicit parameter carriers, and result layout. The two
MethodImpl rows are the actual rows retained by final emission rather than
logical relations reconstructed from BOUND.

CLR overload identity is checked independently from result layout. A sealed
MethodDef coordinate is:

```text
owner
+ exact physical name
+ hasThis
+ method generic arity
+ printed explicit parameter carriers
```

The implicit receiver and return type are excluded because neither
distinguishes a CLI MethodDef overload. Owner and method parameter binders are
normalized to their printed `!n` and `!!n` coordinates. A split-nullable
result contributes its hidden trailing `bool&` to the parameter coordinate,
so it remains distinct from an otherwise equal direct-result method without
making the return carrier part of overload identity.

## Transactional fail-closed behavior

Final evidence has three outcomes:

- `Known` carries the complete enriched TypeDef, MethodDef, and MethodImpl
  row sets;
- `Unavailable` records missing or unsupported final evidence; and
- `Conflict` records contradictory physical evidence.

Binding is all-or-nothing. Missing rows remain unavailable. Duplicate or extra
structural rows, duplicate TypeDef paths, split aliases, duplicate CLR
MethodDef coordinates, contradictory paths/flags/signatures, invalid special-
name flags, and wrong or duplicate MethodImpl endpoints conflict. A conflict
dominates an unavailable component, and either failure publishes diagnostics
but no sealed physical rows.

This also prevents an additive sentinel failure: adding a BOUND TypeDef which
has no actual final observation cannot make that row appear in the sealed
index. Conversely, actual aliases or physical names cannot manufacture an
expected key or merge two expected identities.

## Two-family isolation

The product fixture contains two final Kotlin implementations of the same
`InlineProducer<T>.produce` root. Each implementation receives its own
successful 4/6/2 certificate. The two certificates share only the natural and
interface-semantic TypeDef paths. Their implementation-class and class-
semantic-capability paths remain distinct, and neither family's private
MethodDefs or MethodImpls can be claimed by the other.

For every family, the fixture checks the exact final paths and TypeDef flag
vectors, the public abstract/non-sealed interface shape, the private sealed
implementation-class shape, all six exact MethodDef names and supported flag
vectors, signature/flag agreement, and natural-to-implementation name
equality. The second implementation also executes through the widened
semantic view, so isolation is not only a diagnostic snapshot property.

## Production and authority boundary

This certificate is family-scoped, read-only rehearsal evidence. It changes
no operation route, recognizer, field or state decision, logical IR/KLIB
contract, emitted production owner, or ABI. With the rehearsal disabled, the
backend and pipeline require the sealed-family product to be empty and the
ordinary production owner remains erased. No wrapper, proxy, shadow state, or
second receiver identity is introduced.

The checkpoint does not yet authorize or seal:

- MethodDef GenericParam rows or nonzero method generic arity;
- producer-recorded separate-compilation evidence or retained foreign CLR
  metadata, each of which needs an independent actual-evidence adapter;
- compiler-wide or arbitrarily overlapping family ownership;
- members outside the selected direct-producer implementation family;
- carriers outside the bounded complete structural grammar;
- broader input-bearing, inherited, defaulted, diamond, multi-parameter, or
  Stdlib interface families;
- a shared final router, recognizer removal, source-built Stdlib advance, or
  atomic production generic-interface cutover; or
- the eventual composition of owner-dependent inputs with split-nullable
  results, such as a generic lookup operation.

Retained foreign metadata remains physical authority for imported
declarations, but it is not silently admitted by this local adapter. Likewise,
the split-nullable `bool&` participates in exact signature and coordinate
validation here; this checkpoint does not broaden which logical declarations
may select that result layout.

## Focused evidence

The focused backend gate is green across eight suites and 109 tests. Its pure
sealed-index tests cover actual-only sentinel absence, missing final rows,
duplicate paths, nesting/flag disagreement, return-type-only CLR coordinate
collisions, split-nullable `bool&` coordinates, invalid runtime-special-name
flags, exact final name/flag retention, actual MethodImpl retention, and
key-only unavailable queries.

The candidate product is green in all four PSI/LightTree by net48/net10 lanes
(4/4). The production-erased inverse is independently green in the same four
lanes (4/4), including the requirement that it publish no sealed family and no
rehearsal-only natural or semantic owner.

The final normal target aggregate exits zero. Direct XML audit covers 198
suites and 2,481 tests with zero failures, errors, or skips: eight backend
suites/109 tests, 187 FIR suites/2,239 tests, two integration suites/127 tests,
and one `dotnet.ir` suite/six tests.

## Next boundary

Keep the current certificate local until producer-recorded and retained-
foreign actual adapters, MethodDef GenericParam rows, and overlapping/global
family ownership obey the same non-additive transaction. Only then should the
programme consider a shared sealed-emission authority or removal of an
existing recognizer. The custom split-nullable lookup proof remains a later
composition gate; advancing the Stdlib census is not evidence for this
authority boundary.
