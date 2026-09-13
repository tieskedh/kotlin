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

## Observable selection contract

First distinguish three domains. They must not acquire a common rule merely
because the CLR probe uses the same `ISource<T>` spelling.

1. **Coherent Kotlin family.** One well-formed logical implementation and its
   override/bridge family. Kotlin's [supertype consistency rule][kotlin-types]
   excludes inheriting the same generic classifier with different arguments,
   including covariant arguments. The shared FIR checker and existing
   `GenericArgumentConsistency.kt` diagnostic corpus enforce this rule.
   This does not mean there is only one implemented interface on an object,
   or that compiler-generated physical bridges are extra logical supertypes.
2. **Imported native CLR family.** Retained metadata and the selected native
   interface contract own dispatch. Distinct constructed interfaces can have
   distinct implementations under [CLR interface mapping][clr-interfaces].
   [Native variance][clr-variance] is reference-only; the accepted importer
   decision already rejects Kotlin-only value/open variance at this boundary.
3. **Foreign implementation of a Kotlin-owned family with conflicting
   constructions.** C# can express this physical graph, but Kotlin cannot
   declare its direct equivalent. Common semantics alone therefore do not
   specify which conflicting implementation a widened Kotlin view denotes.
   This is an unresolved interop extension, not evidence that every Kotlin
   value has a historical selector. It is also not permission to reject an
   entire existing foreign library or every multi-interface class.

“Preserve” below means preserving the receiver and the selected logical
operation/override family, including effects and exception behavior. It does
not require successive calls on mutable objects to return equal results, nor
override ordinary overload resolution. A physical selector is necessary only
if distinct permitted dispatch choices cannot otherwise be recovered.

| Transition | Required contract and subsequent dispatch |
| --- | --- |
| Same-view assignment, pure forwarding, capture, or unchanged ordinary property storage | **Preserve.** Copying an interface value does not select a different member family. An exact native `ISource<int>` alias still calls its int slot. A custom accessor's declared behavior remains authoritative. |
| Kotlin-owned `Producer<Int> -> Producer<Any>` | **Preserve the Kotlin family.** A coherent int producer still executes its int-producing implementation; widen the result if necessary. No `Producer<object>` construction is invented. The conflicting foreign case below is not settled by this rule. |
| Native CLR `ISource<string> -> ISource<object>` | **Establish the target native view.** On a receiver with distinct exact implementations the target object slot may differ from the source string slot. Keeping historical string dispatch would change the native operation. |
| Separate `Box<T>` storing and returning its value | **Preserve the logical stored value.** This obligation is independent of compilation units and of whether the library internally uses `T` or `Any?` plus a correct `as T`. An actual `Box<SelectedReference>` CLR experiment proves neither Kotlin's generic construction choice nor its natural C# API. |
| Mutable local/container replacement and joins | **Preserve the chosen incoming value**, not a selector remembered from a previous write. Static facts join without fabricating a shared construction. No object-global selector mutation is permitted. |
| Reference interface value converted to `Any` or `Any?` | **Preserve the receiver; historical interface selection is unavailable in the accepted carrier.** `Any` operations observe the original receiver. A later cast/type test follows its own target contract. The coherent Kotlin family remains recoverable without remembering the path taken to `Any`. |
| Non-reified `genericErase<T>(x): Any?` | The same receiver requirement applies when `T` is instantiated with a reference interface value. Boxing a compiler pair is not a transparent implementation. Local knowledge at a concrete caller cannot redefine an independently compiled generic body. |
| Concrete explicit `as` / `as?`, including after `Any` or a foreign object call | **Establish/check the requested target**, not reconstruct a previous path. Native targets use real CLR membership. Kotlin-owned targets use the accepted classifier/BK-1 rules and may remain semantic when ordinary Kotlin variance has no CLR construction. On mismatch, preserve the respective exception/null outcome. |
| Non-reified `as T` inside generic forwarding | The operation has only its declared generic contract, not a newly invented concrete interface target. It cannot recover a lost per-value selection merely because the caller once knew one. Do not assume every cast is an explicit, fully determined reselection. |
| `as Producer<*>`, `as? Producer<*>`, or `is Producer<*>` | Classifier/star behavior remains authoritative. A star does not mean `Producer<object>`. Coherent-family calls use that family; a conflicting foreign receiver can pass a classifier check without that check selecting one of its distinct implementations. |
| Parameterized checks within BK-1 | Use the existing compatibility predicate, including Kotlin variance; BK-1 does not select an arbitrary implementation. Compatibility and invocation selection are separate questions. A predicate hit is not proof that a later broad invocation is unambiguous. |
| Unchanged C# `object Echo(object)` | **Preserve the receiver; no historical selector is returned.** A later concrete native cast establishes its target view. A Kotlin star/widened return still needs the logical family or an explicit interop policy, not guessed history. |

These rules settle coherent Kotlin and admitted native CLR cases, but they do
not silently settle conflicting foreign implementations of Kotlin-owned
interfaces. No optimization may substitute “establish” for required
“preserve”, or vice versa. Recomputing a coherent family's operation from the
receiver is not loss of behavior; selecting a different conflicting foreign
body can be.

### The distinguishing dual receiver

For `Dual : Producer<object>, Producer<int>` with different implementations:

- In the native CLR domain, same-view aliases, exact containers and casts to
  the exact int view invoke the int slot; the object counterparts invoke the
  object slot. `object` roundtrips preserve identity but not previous selection.
- If this is a **Kotlin-owned** `Producer`, requiring a Kotlin widening from
  the selected int view to keep invoking int is one possible interop policy.
  Selecting an existing exact target object view is another, observably
  different policy. Neither is implied by the source-illegal Kotlin `Dual`
  declaration, by `===`, or by the fact that one implementation is faster.
- A star exposes no exact argument vector. If no producer-authoritative
  canonical family or unique policy-valid route exists, its subsequent call
  remains outside current admission. Do not convert that ambiguity into a
  false classifier test or choose the first InterfaceImpl row.

Retained native interfaces are not made semantic to accommodate the third
domain. Conversely, their reference-only variance restriction is not applied
to ordinary Kotlin-owned `Producer<Int> -> Producer<Any>`.

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

The [cast specification][kotlin-casts] describes checking against a requested
target, not recovering a historical interface selection. Its generic safe-cast
rules and the separately accepted BK-1 exception still govern success; they do
not supply a conflicting foreign object's dispatch policy. The accepted
`System.Object` foundation is not amended here. If an added interop guarantee
requires both unchanged raw-object transport and preservation of two distinct
historical selections, this candidate cannot satisfy it under the current
invariants. Record that result instead of adding hidden ABI.

### Why generic storage is not automatically a local repair

These ordinary generic implementations also belong in the contract:

```kotlin
fun <T> genericErase(value: T): Any? = value

@Suppress("UNCHECKED_CAST")
fun <T> throughObject(value: T): T {
    val stored: Any? = value
    return stored as T
}
```

For coherent Kotlin reference values they preserve identity and the logical
implementation without extra view state. An `ObjectBox<T>` can use the same
storage technique. If the target adds historical selection as part of an
arbitrary `T` value's meaning, these helpers must handle it too: either the
`Any?` transition intentionally ends that guarantee under an explicit contract,
or it must transport more information. Ordinary struct boxing retains the pair
but violates reference identity observable through `genericErase`.

Therefore a promise to preserve selection through every generic container is
not justified by showing only `Box<SelectedReference>`. Requiring it through
every `Any`, generic body and unchanged object API is not a bounded local ABI.
Solving ambiguous selection also does not solve the independent problem of
fitting a coherent `Producer<int>` into a physical `Producer<object>` nested
field. That generic-state construction question remains open.

### Recommendation before storage design

**GO for contract/model investigation; NOT YET for compound compiler storage.**
Do not add historical view selection to the meaning of all Kotlin references.
Prefer recoverable Kotlin-family authority for coherent implementations and
ordinary target-directed native CLR contracts. This preserves the existing
`Any` root and keeps witness transport from becoming an unjustified universal
requirement.

The next decision for conflicting foreign implementations of Kotlin-owned
families is explicit, not an emitter optimization:

- **Target-directed proposal:** a permitted conversion can select a real exact
  target view; otherwise it requires producer-authoritative semantic dispatch
  or a unique policy-valid route. This can avoid historical selection, but
  would allow a widened call to use a different conflicting implementation.
  Stars and multiple compatible constructions still need a specified outcome.
- **History-preserving proposal:** a widened selected view keeps its source
  implementation. This requires transport through every boundary that promises
  preservation, with explicit limits at raw object and unconstrained generic
  boundaries. A pair alone does not close that contract.

Investigate the first proposal before broadening the value model. It is **not
accepted or implemented here**: changing existing selected-view behavior or
adding a foreign-route restriction needs a deliberate interop decision and
Kotlin/C# hostile tests. Keep existing admission guards meanwhile. A whole
foreign library/class rejection is not an acceptable shortcut to that decision.

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

[kotlin-types]: https://kotlinlang.org/spec/type-system.html#parameterized-classifier-types
[kotlin-casts]: https://kotlinlang.org/spec/expressions.html#cast-expressions
[clr-interfaces]: https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/language-specification/interfaces#1965-interface-mapping
[clr-variance]: https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/language-specification/interfaces#19233-variance-conversion
