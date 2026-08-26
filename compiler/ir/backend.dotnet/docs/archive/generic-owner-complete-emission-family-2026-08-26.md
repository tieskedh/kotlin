# Generic-owner complete emission family

Date: 2026-08-26

## Context

The preceding checkpoints gave the BOUND declaration authority physical
TypeDef and MethodDef identities, then compared two selected MethodDef headers
against the final emitter. That was not yet a complete physical certificate.
A missing `InterfaceImpl`, an extra semantic TypeDef, an evicted dispatcher,
or a different `.override` row could remain invisible while the two endpoint
headers still matched.

Advancing the shared declaration index additively would have made that gap
worse: BOUND rows without final evidence would have survived and appeared
sealed. This checkpoint therefore closes one deliberately bounded family as a
transactional, read-only final-emission manifest. It does not publish
`SEALED_EMISSION_SIGNATURE_INDEX`.

## Bounded complete family

The admitted grammar is one final generic class directly implementing one
root, output-only, Kotlin-owned reified interface producer. The family is
selected from creation-site relations already recorded by lowering; package,
declaration, generated-member, and emitted IL names do not participate.

The projection contains four physical TypeDefs:

```text
natural interface I<out T>
    aliases: { CANONICAL, DECLARED }
    GenericParam: +T, no constraints
    direct edges: empty

interface semantic capability ISemantic
    GenericParams: empty
    direct edges: empty

implementation class C<T>
    GenericParam: invariant T, no constraints
    direct edges:
        BaseType System.Object
        InterfaceImpl I<!0>
        InterfaceImpl CSemantic
        InterfaceImpl ISemantic

class semantic capability CSemantic
    GenericParams: empty
    direct edges:
        InterfaceImpl ISemantic
```

These are complete direct-edge and TypeDef-GenericParam sets for these four
selected TypeDefs. An absent row is not treated as an empty set. Edge order is
metadata-incidental; duplicate rows conflict. Generic parameters remain
positional and their variance must match exactly, while each parameter's CLR
constraint rows have set semantics: reordering is accepted and duplicates are
rejected.

The same projection contains six physical MethodDefs:

```text
I<T> producer slot                         typed !0 result
ISemantic producer slot                   object result
C<T> typed producer entry                 typed !0 result
CSemantic producer slot                   object result
C<T> class-capability dispatcher          object result
C<T> interface-capability dispatcher      object result
```

Every row has an independently allocated physical MethodDef key. Source-family
entries retain their lowering-recorded role; a generated interface-capability
dispatcher uses its own IR symbol as the exact role-less MethodDef identity.
It must not be identified by the shared logical interface member, because each
implementation emits a distinct private dispatcher. The comparison covers the
structural header facts already represented by BOUND authority: physical
owner, owner arity/category, visibility, dispatch category, instance
convention and receiver, method arity, ordinary parameter carriers, and result
layout/carrier.

There are exactly two explicit MethodImpl rows. The two private semantic
dispatchers explicitly implement the class-capability and interface-capability
slots. The public typed entry satisfies `I<!0>` through ordinary CLR implicit
interface mapping; because no `.override` row is emitted for that mapping, the
manifest must not fabricate a third MethodImpl merely to restate the logical
relation.

This is “complete” only for each selected producer implementation family.
Unrelated MethodDefs on `C<T>` are outside the projection and are not silently
claimed as observed or sealed. The executable proof admits two implementation
classes of the same producer interface at once. Their shared semantic
declaration slot does not make either implementation's MethodImpl belong to
the other family: a row is selected by its family body, or by the selected
implementing TypeDef together with the selected declaration endpoint.

## Atomic TypeDef aliases

Canonical and declared natural-interface identities intentionally denote one
physical TypeDef in this grammar. Expected authority now registers the entire
alias group atomically and assigns one opaque physical TypeKey to all members.
It rejects a group registered only after one member has already escaped under
an independently allocated key, rather than silently merging structural
identity after carrier shapes have been built.

Actual evidence remains one-way: an emitted TypeDef may bind to that
pre-registered expected key only through a matching observed alias and
matching arity/category. Actual aliases cannot manufacture or merge expected
authority. Final-emitter claimed aliases are deduplicated by full local
identity—owner-symbol identity plus view—so two logical owners which happen to
use the same view cannot hide one another during conflict scoping.

The two semantic capabilities and implementation class each retain one
independent identity. Exact, semantic, canonical, and declared views are not
made generally interchangeable by this bounded natural alias group.

## Transactional final-fixpoint observation

TypeDef observations are produced from the same final class-rendering
decisions that print GenericParams, BaseType, and InterfaceImpl rows. MethodDef
observations come from the same structured header decisions that print each
method. MethodImpl observations are attached only when the emitter actually
prints an explicit `.override`.

Raw observations live inside rendered products. Failed or superseded emitter
fixpoint attempts lose their observations with those products; nested and
exact-interface products are folded only into their successful enclosing
render, and normalization occurs from the final surviving per-scope maps. A
selected family observed in another emission scope conflicts. Duplicate or
extra physical rows conflict, structurally different rows conflict, and a
missing expected row is unavailable. The family result is the fail-closed join
of TypeDef, MethodDef, and MethodImpl results, with conflict dominating
unavailable and unavailable dominating match.

Capture and comparison are rehearsal-only. Production emission asserts that
raw and normalized TypeDef/MethodImpl evidence is empty, the backend publishes
no complete-family comparison, and the production-erased inverse remains
silent.

## Incidental route repair and executable evidence

Expanding the BOUND family exposed two pre-existing authority gaps. A clean
focused run from baseline `f3b1579a41` failed the same operation-route
assertion, proving that the route failure predated this complete-manifest
checkpoint rather than being caused by its emitter comparison.

First, an executable-only producer does not publish a pre-lowering linkage
key. The bounded selector now uses the already recorded source-symbol/
capability-slot relation and the contract's single producer role; it does not
infer a member from its name. A future multi-member grammar must publish an
explicit relation instead of extending this one-member restriction.

Second, open-nullable `T?` recognition had compared the complete IR type with
the owner's non-null default type. It now identifies the owner parameter by
classifier-symbol identity and handles nullability as the independent route
policy. Consequently the `T?` logical view selects its semantic route, while
the exact natural route still retains the source owner's physical type
parameter. These repairs restore the existing operation proof but do not
broaden the complete-family grammar.

Pure hostile tests cover missing, duplicate, extra, and structurally changed
TypeDef, MethodDef, and MethodImpl rows; order-independent TypeDef aliases,
edges, MethodImpls, and GenericParam constraints; positional variance drift;
component status joins; and the absence of a fabricated natural MethodImpl.
The focused product fixture requires two isolated implementation-family
comparisons, each with four TypeDefs, six MethodDefs, two explicit semantic
MethodImpls, natural canonical/declared aliasing, and a matching final-fixpoint
manifest in all four parser/runtime rehearsal combinations. The second
implementation is also called through the widened semantic view, so its
private MethodImpl body participates at runtime. The production inverse
requires no complete-emission comparison.

The final aggregate exits zero. Direct XML audit covers 197 suites/2,472
tests with no failures, errors, or skips: seven backend suites/100 tests, 187
FIR suites/2,239 tests, and two integration suites/127 tests are current; the
unchanged `dotnet.ir` suite retains six green tests.

## Boundary

This checkpoint is a bounded final-emission projection over
`BOUND_DECLARATION_INDEX`, not a sealed compiler-wide emission epoch. Apart
from the rehearsal-only shadow-route correctness repair described above, it
does not change authoritative/live routing or ABI, remove a recognizer, or
advance the source-built stdlib census. Production remains atomically erased.

In particular, the certificate does not yet seal:

- physical TypeDef or MethodDef names;
- complete CLR visibility/layout/special-name and virtual/newslot/final flag
  vectors beyond the structural dispatch facts already compared;
- MethodDef GenericParam constraints;
- retained foreign CLR metadata or producer-recorded separate-compilation
  metadata;
- arbitrary members outside the selected families, overlapping grammars
  beyond the two same-interface implementation families proved here, or
  compiler-wide uniqueness;
- broader input-bearing, split-nullable, multi-parameter, inheritance,
  default, diamond, or stdlib grammars.

Diagnostic names and current snapshots may expose some of these details for
tests, but diagnostic visibility is not physical authority. Missing facts may
not be inherited from BOUND and relabelled sealed.

## Next step

The next checkpoint should seal final signature/name/flag truth
**non-additively** for one complete scope: construct the sealed result only
from the successful final-emission transaction and fail if any required BOUND
row lacks final evidence. It must not copy the BOUND index and overlay the rows
which happened to be observed.

After that local sealing contract is proven, add producer-recorded and retained
foreign adapters with the same physical-identity discipline. Do not advance a
recognizer or the source-built stdlib census merely because this bounded local
family is green.
