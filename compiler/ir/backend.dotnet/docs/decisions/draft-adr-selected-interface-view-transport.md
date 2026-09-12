# Draft ADR: bounded selected-interface-view transport candidate

- Status: **Draft — experimental candidate only; not an admitted storage ABI**
- Scope: preserving an operationally significant interface selection when a
  natural CLR carrier is insufficient
- Parent: [physical authority and provenance](draft-adr-generic-owner-physical-authority.md)
- Existing roots: [System.Object / Any](system-object-any.md) and
  [natural generic interfaces](draft-adr-reified-generic-interface-owner.md)

## Decision boundary

Investigate a receiver-plus-checked-selection layout. Do not add it to the
compiler's admitted layout grammar, relax an admission guard, change `Any`, or
publish a new constructor/member/state ABI on the strength of the CLR probe.
Production remains erased. The existing constructor experiments stay parked.

Natural CLR representation remains the first choice whenever it carries the
complete required operation. This candidate is for a demonstrated loss of
dispatch information, not a default representation for interface references.
It creates extra runtime information with storage, copying, lifetime and
synchronization costs. It is not merely a serialized diagnostic annotation.

The candidate must preserve one receiver identity and the receiver's one
authoritative user state. Its selector belongs to the transported value, not
to a new receiver or a global mutable table indexed by the receiver. Two
different selections of the same receiver may coexist. No proxy implementing
the logical interface, duplicate field graph, or shadow store repairs a gap.
Whether a compound value can satisfy every required storage and interop
obligation is an open result of this experiment, not an assumption.

## Candidate facts, not a new source of authority

Conceptually, the produced or stored layout could be:

```text
SelectedReference(domain, receiver, witness)
    null: receiver = null, no witness
    non-null: receiver supports witness.physicalConstruction
              witness belongs to domain's recorded declaration family
```

`domain` identifies the allowed operation family and logical argument/result
policies; it is not necessarily a third runtime word. A concrete encoding is
unselected. A CLR `Type` is sufficient for the narrow probe, not necessarily
for the complete Kotlin contract, deployment model or efficient dispatch.

Witness creation requires an independently established physical construction:
a genuine selected natural entry or a successful physical membership check.
It cannot create `I<object>` for an `I<int>`-only receiver. Operations bind the
existing producer-recorded or retained foreign MethodDef in that construction
and preserve CLR virtual/MethodImpl dispatch. A witness does not authorize
extra members, a different body, or a narrower input domain. Broad-candidate
checks and result layouts remain independent callable-contract facts.

Static `selectedViewLineage` remains a selector over statically guaranteed
views, never a proof source. At a join of different selections it still
disappears. A runtime compound join may instead preserve the relational fact
that *this receiver supports this witness* on every incoming path, provided
the whole pair is transported coherently and its domains agree. It does not
add either arm's construction to the intersection of static guaranteed views.
Do not use a runtime existential fact as permission for an unguarded exact call.
An unknown or unpaired input supplies no valid witness by logical typing alone.

Produced layout and destination storage stay independent. Fields, parameters,
results and captures must explicitly admit the complete transport contract;
local provenance cannot alter an existing FieldDef or MethodDef. A read produces
the chosen storage layout, not the layout of an earlier initializer. Aliases,
joins, inlining and generated captures need ordinary dataflow evidence, not
names, origins, packages or collection recognizers.

## Preserve, establish, and discard are distinct operations

The following is the candidate's investigation contract, not a new Kotlin
language rule:

| Transition | Required treatment |
| --- | --- |
| Assignment, copy, forwarding, property storage or capture of an already selected value within a selection-carrying contract | Preserve receiver and witness together, including through mutable replacement and joins. |
| Kotlin widening which is defined to retain the selected operation | Preserve that selection; never replace it with an arbitrary implemented construction. |
| Entry through a genuine natural CLR signature | Establish the view selected by that physical signature, under its recorded Kotlin operation policy. Do not infer a previous view. |
| Native CLR interface conversion | Respect the actual target CLR interface and dispatch rules; a reference-variance conversion is not automatically a promise to retain the source interface's dispatch choice. |
| Explicit new selection from a raw object plus a target view | Check that target physically exists and is logically permitted. Success establishes a new selection, not recovery of lost history. |
| Raw `Any`/`Any?` or unchanged foreign `object` transport | The accepted carrier contains only the original receiver. Identity can survive; extra selection does not. Whether a later operation may establish a new view must be decided independently. |
| Boxing the compound carrier | Transports its bits but creates a different object identity. Not an implementation of reference upcast to Kotlin `Any`. |

In particular, no optimization may silently substitute “establish” for
“preserve” because the chosen storage loses the witness. If the operation
requires preservation and the endpoint cannot carry it, the candidate is
unavailable at that boundary. Keep the existing owner/route admission guard;
do not turn an internal limitation into an undocumented restriction on Common
code or ordinary C# libraries.

### The raw-object information limit

Let one receiver implement two constructions with observably different reads:

```text
(dual, ISource<object>) -> "object"
(dual, ISource<int>)    -> 7
```

Exporting either as the original `object` supplies the identical reference to
an independently compiled `object Echo(object)` method. It receives no selector.
No deterministic reconstruction based on that reference alone can recover both
original selections. A global per-object selector, interface enumeration order,
or an assumption that there is only one simultaneous view cannot fix this.

The accepted `System.Object` foundation is not amended here. Do not silently
change Kotlin-only `Any` storage either: that would also affect generic calls,
fields, reflection and separate compilation. Before compiler integration,
classify actual Kotlin casts, tests, assignments and generic forwarding through
`Any` from the logical/interop contract. A cast with a definite target may be a
new-selection operation, but a star or widened target need not identify one
physical construction. BK-1 is not permission to choose an arbitrary view or
weaken valid Kotlin variance. If an unavoidable contract requires both raw-object
interop and preserved historical selection, this candidate cannot satisfy it
under the current invariants; record that result instead of adding hidden ABI.

### Identity and universal operations

Generated `===` on reference-valued selected operands must compare receivers,
including mixed natural/compound operands and null. `equals`, `hashCode` and
`toString` must use the existing Kotlin universal-operation rules on the
receiver, not the struct's default operations or the selector. Evaluation count,
left-biased virtual dispatch and exceptions must remain unchanged.

Extracting `.Target` in hand-written C# is only a mechanism demonstration.
Required compiler tests include conversions through `Any`, generic functions,
arrays, properties, captures, reflection and foreign calls, not just direct
reference comparisons. Identity of an originally boxed foreign value-type
receiver must not be lost through unbox/rebox. A null receiver and a non-null
receiver whose operation returns null are distinct cases.

## State, concurrency and generic composition

Ordinary reference-slot atomicity does not extend automatically to a compound
struct. Every shared read must observe one correlated receiver/witness pair.
Two separately volatile fields do not meet this requirement. Neither a
single-threaded test nor a successful stress run proves atomicity.

The bounded CLR experiment uses a private lock and one pair slot. All reads
copy the whole pair under the same lock; all writes replace it under that
lock. Dispatch operates on the local snapshot after releasing the lock. The
lock is additional synchronization state and has a cost. This tests one
explicit protocol; it is not permission to add implicit locks to arbitrary
Kotlin fields or an implementation of Kotlin `volatile`, atomics or CAS.
Safe publication, `this` escape, reentrancy, byrefs, exceptions and races must
be closed for any proposed compiler-owned storage. Lock-free alternatives
which allocate an immutable holder must not be smuggled in as cost-free or
identity-neutral reference slots.

A separately compiled ordinary CLR `Box<T>` can transport `SelectedReference`
when its actual physical `T` is that compound type. This leaves `Box<int>` and
`Box<string>` typed; it does **not** prove that a natural
`Box<ISource<object>>` can store an `ISource<int>`-only receiver, or that Kotlin
`Box<Source<Any?>>` may silently be exposed as a different C# construction.
That is a separate public construction/state contract. It must cover all legal
writes, multiple assemblies, helper/results, inheritance and C# authors without
requiring a hidden implementation interface or erasing unrelated generic state.

Split-nullable results remain orthogonal. Their physical payload expression
comes from the selected MethodDef and its physical substitutions. A selection
witness is not a null flag, and `T + out bool` cannot carry an arbitrary lost
interface selection. Composing a compound payload with nullability, nominal
value-class carriers, `Nullable<T>`, or nested generic inputs requires explicit
layout and substitution proofs; do not remap a logical `T` to rewrite an
existing physical signature.

## Evidence and promotion sequence

1. Keep a standalone CLR probe of checked selection, simultaneous views of one
   receiver, null, separate ordinary generic containers, raw-object information
   loss, boxing identity and one coherent storage protocol on both profiles.
   This proves mechanisms and counterexamples, not Kotlin admission.
2. Resolve the preserve/new-selection contract through Kotlin `Any` and native
   interop, then design the complete generic storage/entry composition. Stop
   integration if this requires an unapproved language/interop restriction.
3. Only after that boundary is settled, add production-inert model facts and
   hostile Kotlin-generated tests: mutable mixed constructions, unknown joins,
   stars/projections, broad inputs, mixed captures, overridden properties,
   deeper Kotlin/C# inheritance and independently compiled producers/consumers.
   Prove receiver identity and one authoritative state, not just checksums.
4. Close shared-memory semantics, physical-record validation, null/value-class
   substitutions, reflection, trimming, NativeAOT and the exact erased inverse.
   A runtime `Type` witness and reflective invocation do not close those gates.
5. Measure a concrete implementation before storage admission or ABI freeze.
   Freeze source, emitted artifacts, toolchains, workload, runtime and protocol;
   measure dispatch time, allocations, retained storage and copying separately.

Measurements must include direct natural CLR, ordinary erased, the **actual
current compiler-generated** semantic route and the candidate wherever their
observable work is equivalent. Do not label a hand-written reflective helper
as the current Kotlin runtime. Use both a single-construction comparable workload
and a selection-essential dual-view workload. An existing route that rejects
the dual view is an unavailable baseline, not a performance number; equalizing
the two method bodies merely to obtain a checksum hides the issue being tested.
Include fixed-view and changing-view state, value/reference results, cold
selection, warmed dispatch and shared-storage costs. Report profile-specific
runtime versions and allocation measurement limitations. The CLR probe's
reflection calls are not a speedup claim or the selected dispatch algorithm.

Proceed with bounded experiments; **not yet** with general compiler storage,
public ABI, a blanket interop restriction, or the next stdlib blocker.
