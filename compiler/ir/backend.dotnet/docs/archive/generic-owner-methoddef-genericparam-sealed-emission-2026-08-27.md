# Generic-owner MethodDef GenericParam sealed emission

Date: 2026-08-27

## Context

The preceding family-scoped seal retained each final MethodDef's generic
arity, but deliberately rejected every nonzero arity because it did not yet
capture the corresponding GenericParam rows. Arity alone cannot certify a
generic MethodDef: its ordered parameters, exact binder ownership, metadata
names, and constraints are part of the physical CLR declaration.

This checkpoint extends that same actual-only, non-additive certificate. It
does not create a second authority model. See
[`generic-owner-sealed-emission-signature-family-2026-08-26.md`](generic-owner-sealed-emission-signature-family-2026-08-26.md)
for the underlying 4-TypeDef/6-MethodDef/2-MethodImpl transaction.

## Binder-owned GenericParam rows

Every physical MethodDef description now carries one complete ordered
GenericParam vector in addition to its arity. Row position supplies the CLR
parameter number. A method parameter is symbolic and bound to its exact
physical MethodDef until final signature binding; an equally numbered
parameter from another MethodDef is not interchangeable.

The successful final emitter transaction observes the rows from the same
structured decision which renders the MethodDef header. It retains each exact
raw metadata name and normalizes every constraint against the declaring
TypeDef and owning MethodDef binders. ILAsm identifier quoting occurs only
while rendering text and cannot become part of the sealed metadata name.

Method GenericParams are invariant. Their constraint rows have set semantics:
order is metadata-incidental and duplicates are invalid. The general seal can
validate ordinary owner-relative and same-MethodDef-relative constraints. The
integrated local family below remains narrower and admits no constraints or
special `class`, `struct`, or `new()` flags.

## Bounded method-generic family

The local direct-producer grammar now admits either its original arity-zero
member or exactly this additional shape:

```kotlin
interface MethodGenericProducer<out T> {
    fun <R> produce(marker: R): T
}
```

`R` is one non-reified, unconstrained invariant method parameter, and its sole
ordinary value parameter is declaration-independent. All six physical
MethodDefs in each implementation family independently declare their own
`R` and consume that MethodDef's own `!!0`:

```text
natural interface slot                    !!0 -> !0
interface semantic slot                   !!0 -> object
typed implementation entry                !!0 -> !0
class-capability slot                      !!0 -> object
class-capability dispatcher                !!0 -> object
interface-capability dispatcher            !!0 -> object
```

The family remains coherent as a unit: every selected MethodDef has the same
zero-or-one arity shape. Two implementations of the same interface produce
two isolated 4/6/2 certificates. They share the interface-owned natural and
semantic TypeDefs and MethodDefs; neither implementation can claim the other's
implementation-side MethodDefs, MethodImpls, or method-parameter binders.

## Transactional hostile boundary

BOUND supplies the ordered expected rows. Final emission independently
supplies the actual rows and physical names. Missing final rows remain
unavailable. Arity or variance drift, changed constraints, duplicate
constraints, an out-of-range `!!n`, or a constraint/value carrier bound to a
sibling MethodDef conflicts and publishes no sealed family. Constraint-row
reordering alone remains a match.

The sealed index freezes the successful row vectors and exact physical names.
Callers may query them only through an already selected opaque MethodDef key;
a name, `!!n` spelling, or actual emission observation cannot manufacture a
declaration identity.

## Boundary

This is declaration evidence only. The fixture certifies the emitted
MethodDefs, GenericParam rows, TypeDefs, and MethodImpls, but deliberately does
not claim that a closed call already constructs and emits the corresponding
CLR MethodSpec. In particular, this checkpoint does not prove substitution of
method arguments through the natural and semantic call routes.

The change is rehearsal-only. It selects no new operation route, removes no
recognizer, changes no state or KLIB record, and advances no source-built
Stdlib family. With the rehearsal disabled, sealed-family output remains empty
and the production owner remains erased. No wrapper, proxy, shadow state, or
second receiver identity is introduced.

## Evidence

The final target aggregate exits zero. Direct XML audit covers 198 suites and
2,496 tests with zero failures, errors, or skips. Focused
evidence includes the candidate, explicit-off, and property-absent
PSI/LightTree by Framework 4.8/.NET 10 lanes, two isolated method-generic
families, complete GenericParam-row comparison and sealing, constraint-order
independence, and hostile arity, variance, constraint, cross-binder, and
out-of-range rejection.

## Next boundary

Before producer-recorded or retained-foreign sealed adapters are added, close
method-generic MethodSpec and call-value routing for this same family. The
physical MethodDef must remain open, the call must carry the exact method
instantiation, and owner plus method parameters must be substituted through
their respective TypeDef and MethodDef binders before verification or
emission. Natural and semantic routes must receive the same logical method
arguments, with value- and reference-type substitutions executing on both CLR
profiles. Only after that executable boundary is green should independent
producer/foreign evidence and overlapping or compiler-wide family ownership
join the seal.
