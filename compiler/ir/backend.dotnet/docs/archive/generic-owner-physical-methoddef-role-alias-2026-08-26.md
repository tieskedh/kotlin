# Generic-owner physical MethodDef roles and TypeDef aliases

Date: 2026-08-26

## Context

The first final-fixpoint MethodDef comparison correlated one selected IR
function with one observed physical owner and header. That was sufficient for
the bounded direct-producer family, but it did not distinguish multiple
physical MethodDefs derived from one logical Kotlin member. It also reduced
one final emitter `ClassInfo` to a single logical TypeDef identity even when
the canonical and declared natural-interface views intentionally share that
same physical TypeDef.

Neither gap may be repaired by names, IR origins, or by allowing actual
emission to manufacture BOUND authority. This checkpoint adds the missing
identity facts while retaining the previous partial final-emission boundary.

## Per-emission MethodDef identity

Each concrete `DotNetIlFunctionInfo` can now carry one lowering-selected local
generic-owner MethodDef identity:

```text
outer null                         outside the recorded family
Local(slot, role = null)           role-less semantic capability slot
Local(source, TYPED_ENTRY)         natural typed entry
Local(source, SEMANTIC_HOOK)       generated semantic hook
Local(source, CAPABILITY_DISPATCHER) generated class dispatcher
```

The distinction between outer null and an explicit null role is structural.
Reconstructing a recorded emission instance preserves its nullable identity
verbatim; a global rehearsal binding is consulted only when no prior function
information exists. The natural member rendered on an invariant exact sibling
retains the same typed-entry identity because it is a second physical emission
of that endpoint, not a new logical member role.

The binding snapshot is derived from the lowering-selected capability,
semantic-hook, and dispatcher maps. It is attached to the exact header
decision which prints the IL and survives only successful final-fixpoint
rendered products. It does not infer a role from a method name or IR origin,
and production emission is required to receive an empty binding map.

Role-aware correlation accepts an exact identity and ignores another endpoint
of the same selected family while comparing its sibling. The per-emission
identity remains authoritative even if future lowering emits two legitimate
family roles from one IR function; the raw function symbol cannot turn the
other selected endpoint into conflict. A non-family identity on the currently
expected raw function conflicts, while a selected function with no identity is
unavailable. A generated hook or dispatcher with the same logical source is
irrelevant to a natural or capability-slot endpoint unless explicitly selected
as an endpoint. Evidence for the selected identity in another emitter
transaction is scope contamination.

## Independently observed TypeDef aliases

Final normalization now assigns an opaque physical key to each actual emitter
`ClassInfo` and records every logical local TypeDef view which independently
points to it. The only admitted multi-view set is:

```text
{ CANONICAL, DECLARED }
```

for one logical owner, one physical arity/category, and one actual
`ClassInfo`. Single canonical, declared, exact, semantic/null, or ordinary
local identities remain valid. Exact never aliases natural; semantic/null
never aliases declared; different logical owners and contradictory
arity/category facts conflict. If one logical alias is attached to two
independently observed physical keys, both physical definitions conflict.

`DECLARED` is the preferred diagnostic view of the legal natural alias pair,
not a new authority source. The complete alias vector is retained in actual
snapshots.

## Expected-first one-way binding

Every expected BOUND TypeDef identity in the atomic family is registered
before any actual final-emission carrier is converted. An actual physical
TypeDef may then:

- reuse exactly one already registered expected key when one of its observed
  aliases matches and arity/category agree;
- receive an opaque actual-only key when no expected alias matches, which
  guarantees structural mismatch; or
- conflict when it matches multiple expected identities or contradicts their
  physical description.

Actual aliases therefore cannot create, merge, or rename BOUND identity. This
one-way rule lets the expected declared natural view truthfully compare with
an actual canonical/declared alias pair without pretending that canonical and
declared are universally interchangeable.

Method-generic parameter binders now use the complete physical MethodDef
identity, including role. Owner parameters and local constructions use the
same expected-first TypeDef binding. Diagnostic method and owner names still
do not participate in structural equality.

## Hostile and product evidence

Pure tests cover explicit role-less versus missing identity, typed/hook/
dispatcher separation, a selected function carrying a foreign identity, a
role-less slot carrying a named role, method-key separation by role, and
header-role drift. TypeDef tests cover order-independent canonical/declared
normalization, canonical/exact and semantic/declared rejection, different
logical owners, arity disagreement, one actual alias set matching two expected
keys, actual-only non-authority keys, and one logical alias claimed by two
physical keys.

The existing four-lane inline-producer product now requires:

```text
expected natural owner aliases = [DECLARED]
actual natural owner aliases   = [CANONICAL, DECLARED]
natural MethodDef role         = TYPED_ENTRY

expected semantic aliases      = [null]
actual semantic aliases        = [null]
semantic MethodDef role        = explicit null
```

Both PSI parsers execute Framework 4.8 and .NET 10 products. The production-
erased inverse remains empty and cannot receive the rehearsal binding map.
The final aggregate exits zero. Direct XML audit covers 195 suites and 2,453
tests with zero failures, errors, or skips: five backend suites/81 tests, 187
FIR suites/2,239 tests, and two integration suites/127 tests are current; the
unchanged `dotnet.ir` suite retains six green tests.

## Boundary

This is still an explicitly partial final-emission overlay over
`BOUND_DECLARATION_INDEX`. It does not publish
`SEALED_EMISSION_SIGNATURE_INDEX`, change emitted ABI, select a route, remove
a recognizer, or advance the source-built stdlib census. Production remains
atomically erased.

The role is observed at the lowering-to-emitter handoff, not rediscovered from
the assembled PE. The alias relation is independently observed from final
emitter `ClassInfo` identity. Complete TypeDef/BaseType/InterfaceImpl,
MethodDef, and MethodImpl liveness; exact-sibling header accounting; retained
foreign/core metadata; global single-claim protection across multiple BOUND
families; and broader callable grammars remain open. A sealed epoch may be
published only when the complete selected scope is observed, not when missing
facts can still be inherited from BOUND authority.

## Next step

Close complete-set TypeDef/edge/MethodDef/MethodImpl liveness for one bounded
family using these role and alias identities. Only after that full set has an
atomic final-emission comparison may the rehearsal publish a first sealed
scope or remove an existing routing recognizer.
