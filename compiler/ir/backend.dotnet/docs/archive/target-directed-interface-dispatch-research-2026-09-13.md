# Operation-directed interface dispatch and generic storage

## Recommendation

**GO for an operation-contract-first investigation. NOT YET for a general
receiver-plus-selector storage ABI, a new conflicting-foreign dispatch policy,
or removal of the current admission guards.** The evidence supports making
the problem smaller before changing the value representation.

There are two independent questions: which implementation an operation must
invoke, and which physical storage can contain every legal value. Historical
selection is not a demonstrated universal requirement of ordinary Kotlin.
Removing that requirement does not make all logically compatible interface
values CLR-assignable to the same constructed field.

The most important new findings are:

1. A native CLR target can be valid without an identical implemented-interface
   row, even with **multiple** variant source constructions. Native invocation
   must retain CLR dispatch, not a Kotlin resolver's uniqueness restriction.
2. One construction does not imply one implementation method: a C# descendant
   can explicitly reimplement that same interface. Invoke the authoritative
   interface MethodDef, not a guessed base-class method or inherited hook.
3. For conflicting foreign implementations of a Kotlin-owned interface,
   preferring any CLR-supported target is a **stronger policy** than preferring
   an exact metadata construction. A mixed int/string receiver exposes the
   difference. Neither policy is accepted by this review.
4. Nested generic storage has a real lower bound. An existing
   `Box<Producer<object>>` cannot acquire an int-only producer in its `!T`
   field while preserving that FieldDef, receiver and state. This does not
   require erasing every `Box<T>` field, but it does require an honest boundary.

The smallest next compiler-model experiment supplies the **current operation's
contract** to selection. It does not serialize historical lineage into every
value, redesign `Any`, or resume the stdlib census merely to reduce diagnostics.

## Evidence and scope

Reviewed compiler base: `5413260aa6e50d789975f207b29d9f75f6c5049c` on `dotnet`.
This archive records source inspection, primary-source review and the standalone
probe delta accompanying this report. It does not claim a Kotlin/.NET dispatch
implementation, a production generic-owner switch, or Kotlin core-team approval.

The published Kotlin specification identifies itself as `1.9-rfc+0.1`.
Its rules are cross-checked with the current shared checker and a separately
compiled JVM baseline. CLR behavior is checked against both installed runtime
profiles. Claims below distinguish language rules, physical limits, executed
mechanisms and proposed interop policy.

### New executable results

| Evidence | Result | Limit |
| --- | --- | --- |
| Common-source JVM library and separate consumer | Exact/wide/star calls, effects, identity, generic and object-backed storage, casts, null and bottom producers pass | Existing `2.5.255-SNAPSHOT` distribution, not rebuilt from reviewed HEAD |
| Inconsistent Kotlin supertype graph | Fails with `INCONSISTENT_TYPE_PARAMETER_VALUES`; no rejected jar emitted | Does not define foreign CLR extension semantics |
| CLR probe against net48 references | Eight assertion groups pass on installed serviced CLR4 | Framework installation `4.8.09221`, release `533509`; not an original unserviced 4.8 runtime |
| CLR probe against net10 references | Same eight groups pass on runtime `10.0.9` | No trimming, NativeAOT, throughput or allocation claim |

Reproducible entry points are
[verify-kotlin-selection-contract.ps1](../../tools/verify-kotlin-selection-contract.ps1)
and
[verify-selected-interface-view-storage.ps1](../../tools/verify-selected-interface-view-storage.ps1),
each with a fresh `-OutputDirectory`. Evidence is preserved at:

- `D:\CodexTemp\kotlin-selection-dispatch-20260913`;
- `D:\CodexTemp\target-directed-dispatch-20260913`.

Each `verification.json` records frozen source hashes, separately compiled
library hashes, actual runtimes, assertion output and exclusions. Compilation
uses warnings as errors. The CLR runner reuses the existing ordinary generic
library; it does not introduce a second runner or build a new Kotlin compiler.

The JVM delta closes a weakness in the earlier baseline: passing everything
to `verify(Producer<*>, ...)` only executed the star operation inside that
helper. The new checks also invoke exact and widened values directly and from
distinct functions in the separate library, verifying exactly one receiver
effect per call. A `Producer<Nothing>` travels through `Producer<String>`,
typed/object-backed containers, generic forwarding and an `Any` cast; reads
throw the original exception object once. Normal string replacement remains
valid afterwards.

The new [CLR fixture](../../tools/fixtures/TargetDirectedDispatchProbe.cs)
checks real interface MethodDef invocation against direct native calls. On
both profiles, declaring cat/dog constructions in opposite orders yields
`Cat/Dog` native winners for an animal target. The test intentionally compares
against each runtime's direct call rather than freezing this observed ordering
as Kotlin policy. The fixture's additional producer-only classifier is explicitly
a bounded hypothesis, not the actual Kotlin runtime or a general subtype checker.

## Language and interop domains

### Coherent Kotlin implementation families

Kotlin requires a consistent declared supertype graph: one generic classifier
cannot occur with different arguments in that graph. Covariance does not waive
this condition. The shared
[FIR consistency checker](../../../../fir/checkers/src/org/jetbrains/kotlin/fir/analysis/checkers/FirInconsistentTypeParameterHelpers.kt)
and [diagnostic corpus](../../../../testData/diagnostics/tests/GenericArgumentConsistency.kt)
provide repository evidence alongside the specification. [K1]

This does not forbid several valid views of one implementation:

```kotlin
interface Producer<out T> { fun read(): T }
class IntProducer : Producer<Int> { override fun read(): Int = 7 }

val exact: Producer<Int> = IntProducer()
val wide: Producer<Any> = exact
val opaque: Any = wide
```

There is one logical implementation family to recover. Normal assignment,
forwarding or generic storage must not change it. Result widening may box the
int, but it must not replace the receiver or invoke a different method merely
because the logical result is now `Any`.

The previous [selection-contract review](selected-interface-selection-contract-2026-09-13.md)
also inspected JVM, JS, Wasm and Native type-operator lowerings. Their
target-oriented checks and erased generic representations support separating
logical operations from storage history. Only the JVM baseline was executed;
none of those targets defines the new CLR dual-construction extension.

### Imported native CLR interfaces

The retained interface construction and MethodDef are the operation contract.
Native variance concerns reference conversions; boxing `int` does not convert
`ISource<int>` to `ISource<object>`. CLR interface mapping also allows explicit
implementations and reimplementation in a descendant. [C1][C2]

Three physical cases must be distinguished:

| Physical fact | Example | Native behavior |
| --- | --- | --- |
| Exact construction is present | Object implements both `ISource<string>` and `ISource<object>` | Invoke the requested target slot; it can differ from the original string slot |
| Target is supported through CLR variance | Object implements only `ISource<string>`; operation uses `ISource<object>` | Valid target call without an identical interface row |
| Several variant source constructions support the target | Object implements `ISource<Cat>` and `ISource<Dog>`; operation uses `ISource<Animal>` | Leave implementation selection to CLR dispatch; uniqueness of source rows is not required |

“No fabricated construction” means no unsupported claimed runtime view. It
does **not** mean that every valid target must appear literally in
`GetInterfaces()`. An authenticated conversion or a genuine runtime membership
check can establish a supported native target. Conversely, checking only a
classifier or logical Kotlin variance does not establish native membership.

These rules apply to **admitted constructed native operations**. They do not
magically supply an argument vector for an unknown/open or star-only imported
view. Such forms still need their separately specified projection/admission
contract.

### Conflicting foreign implementations of Kotlin-owned interfaces

A C# object may implement two physical constructions of a Kotlin-owned
interface with different bodies. The equivalent Kotlin supertype declaration
is illegal. This identifies an interop policy boundary; it does not make every
foreign object invalid or decide which widened call should run.

Two distinct target-oriented policies deserve separate names:

- **Exact-row priority:** prefer the exact target construction, then require
  authoritative family dispatch or a unique Kotlin-compatible route.
- **Native-supported-target priority:** also prefer a target supported only
  through CLR variance, delegating its dispatch to the CLR before considering
  other Kotlin-compatible constructions.

Consider `Mixed : Producer<int>, Producer<string>` with different results,
viewed logically as Kotlin `Producer<Any>`. There is no exact
`Producer<object>` row, but the string construction supports that native target.
Native-supported-target priority can choose string, while an unprioritized
Kotlin candidate set contains both int and string. The new CLR fixture executes
this distinction. Its feasibility does not prove that it is the right Kotlin
interop rule.

For a coherent Kotlin family neither shortcut may bypass established semantic
behavior. A compiler-generated natural bridge, or the presence of an inherited
capability, is not by itself authority to choose a different implementation.

## Current implementation audit

The current model is not fundamentally a history-storage architecture. It
already separates declaration authority, value facts, operation routing and
producer-wide state. Several apparent conflicts are missing information at a
particular operation boundary, not evidence against that decomposition.

| Current boundary at reviewed base | Finding | Consequence |
| --- | --- | --- |
| `DotNetGenericInterfaceRuntime.kt`, `InvokeRecordedMember`, lines 1090–1198 | Receives an open definition and MethodDef handle, but no requested constructed/projection view; rejects a second distinct construction before checking arguments | A dual rejection establishes insufficiency of this bounded helper's inputs, not necessity of a universal stored selector |
| `DotNetGenericOwnerPhysicalValueModel.kt`, `selectClrReferenceVarianceViewOrError`, line 2891 | A proved native conversion selects target lineage while retaining the source produced carrier | Lineage can mean the current established view, not permanent source history |
| `DotNetRetainedForeignGenericOwnerPhysicalAuthorityTest.kt`, line 394 | The model test selects widened object MethodDef ownership from a string produced carrier | Preserve this distinction; no native emitter regression is established by this audit |
| `DotNetIlExpressionCodegen.kt`, lines 4898–4965 | Capability dispatch precedes the natural foreign fallback | Do not globally replace it with target-first dispatch; semantic equivalence and foreign override handling still matter |
| `DotNetGenericInterfaceRuntime.kt`, `IsKotlinGenericConstructionAssignable`, line 280 | Bounded compatibility reads physical generic-parameter variance | Not a general replacement for producer-recorded logical variance when physical variance is weakened |

Source owners: [runtime](../../src/org/jetbrains/kotlin/backend/dotnet/DotNetGenericInterfaceRuntime.kt),
[value model](../../src/org/jetbrains/kotlin/backend/dotnet/DotNetGenericOwnerPhysicalValueModel.kt),
[retained-authority tests](../../test/org/jetbrains/kotlin/backend/dotnet/DotNetRetainedForeignGenericOwnerPhysicalAuthorityTest.kt),
[expression codegen](../../src/org/jetbrains/kotlin/backend/dotnet/DotNetIlExpressionCodegen.kt).
The native reselection functions inspected here have model/test consumers;
that is not a claim that every emitter path already uses them.

The [natural-interface ADR](../decisions/draft-adr-reified-generic-interface-owner.md)
allows physical variance to become invariant to provide a complete CLR-legal
member contract. Kotlin variance remains logical authority. Therefore a new
candidate filter cannot simply treat CLR `GenericParameterAttributes` as the
complete Kotlin subtype relation. This is an expansion hazard, not a reproduced
failure of the currently admitted bounded helper.

Some legacy special-surface paths in expression codegen still use older
selectors while the durable direction requires recorded declaration identity.
They are consolidation debt to retire by affected-boundary proofs, not a
precedent for new name/arity dispatch or a reason to expand this research into
unrelated rewrites.

## Minimal operation model

The following is a proposed decomposition, not a new serialized schema:

```text
OperationContract
    domain: coherent Kotlin | retained native | foreign Kotlin policy
    logical declaration family and selected member
    invocation kind: interface virtual | class virtual | super/nonvirtual
    target intent: established native construction
                   | logical argument/projection descriptor
                   | classifier-only / not yet available
    producer-recorded or retained physical MethodDef
    physical owner/method substitutions
    independent parameter-domain policies
    independent result layout
    policy/authority epoch

ReceiverEvidence
    original receiver
    guaranteed physical views and supported conversions
    current selected view, if established
    producer family/override evidence, if available
```

A target intent is a request, never physical proof. An operation can know its
logical Kotlin target while having no corresponding CLR `Type`. Runtime type
arguments may be supplied from actual constructed owner/method context where
that context represents them faithfully. Non-reified generic bodies do not
gain concrete historical information merely because their callers once had it.

Selection should yield one of several distinguishable outcomes: a supported
native call, an authoritative Kotlin semantic family call, a uniquely justified
foreign semantic route, ambiguity, unavailable evidence, or contradictory
authority. Ordinary multiple candidates are not corrupt metadata. Failed
compatibility and ambiguous invocation are not the same outcome.

### Selection and invocation order

For retained native operations, first establish the admitted target conversion
or cast, then bind the retained MethodDef in that target and let CLR dispatch
run. Do not substitute a hand-chosen variant source row even when several are
compatible. Do not introduce Kotlin semantic variance for an invalid native
conversion.

For coherent Kotlin operations, retain the logical virtual family and its
parameter/result contract. Use a natural route only when it preserves that
contract. A natural-only foreign implementation with one applicable physical
construction can often be invoked through its real method and have the result
widened. Future foreign descendants must still obey their actual interface map
and typed overrides; a Kotlin-produced base does not close an open subclass graph.

For conflicting foreign Kotlin-owned families, experiment with the two explicit
target-priority policies above. Otherwise form candidates from receiver evidence,
logical argument/projection compatibility and producer-recorded callable policy.
Invoke only a justified unique route or an independently established equivalent
family. Multiple candidates remain unresolved at that operation, not grounds
for deleting the class from the import graph.

**Never select a construction by testing which one accepts this call's runtime
argument.** For a broad-candidate member, an incompatible string may require
`false` on the selected int family. Switching to a string construction because
it accepts that argument turns interface invocation into dynamic overload
resolution. Select the operation's implementation first, then apply its recorded
input barrier, result layout and exception contract.

Similarly, do not invoke candidates to see whether their results agree. Shared
names, open tokens, equal current results or similar method bodies do not prove
equivalence of substitutions, effects, exceptions or future virtual overrides.
Method-level equivalence must preserve the logical family across related members.

### Identity, joins and caches

When dispatch is reconstructible from the receiver and operation, ordinary
reference storage carries only the original receiver. Assignment and joins
continue to use normal value/provenance rules; loss of static precision does
not require manufacturing a historical runtime selector.

Static lineage remains a selector over already guaranteed views. Different
lineages disappear at a join; a native conversion can establish a new current
view. Source history must neither create membership nor override the current
native target.

An immutable dispatch cache may be keyed by runtime receiver type, declaration
identity, current target/projection contract, relevant substitutions and policy
epoch. It must not be keyed only by receiver type, or store a mutable current
view per receiver object. Separate target/member operations on the same object
can legitimately require different cached routes. Cache publication and
collectible-assembly lifetime remain deployment concerns, not user state.

## Any and the information boundary

Reference identity and selected implementation are separate observables.
The accepted [System.Object foundation](../decisions/system-object-any.md)
keeps Kotlin interface references as the original receiver through `Any`.
Native reference conversions likewise preserve the referenced object. Boxing
a compound struct creates a different object; extracting its receiver manually
in a probe does not prove Kotlin identity lowering. [C2]

If two values are `(dual, selection A)` and `(dual, selection B)`, exporting both
to an unchanged `object Echo(object)` as the original receiver erases the only
distinguishing information. No deterministic inverse from that same reference
can recover both histories. The existing CLR probe still demonstrates this
negative result.

It matters only where the chosen contract requires those histories. For a
coherent Kotlin family they are unnecessary to recover the family. For an
admitted concrete native cast the target establishes the current view. For an
ambiguous star or non-reified generic operation, no concrete target can be
invented. The [prior contract matrix](../decisions/draft-adr-selected-interface-view-transport.md#observable-selection-contract)
keeps these cases separate.

Portable generic safe casts do not generally check every type argument. BK-1
is the repository's separately recorded stronger target policy; neither its
success predicate nor a classifier-only star test selects an implementation.
This review does not broaden or remove it. [K2]

## Storage remains a separate architecture boundary

### A fixed-field impossibility

Let an existing object have the physical type `Box<Producer<object>>`, with
one authoritative field `!T`. Suppose an admitted Kotlin view exposes it as
an unrestricted mutable `Box<Producer<Any>>`. Kotlin can legally write an
int-only producer to that logical field, but that producer does not support
the physical field's `Producer<object>` construction.

The following demands cannot all be met for this object:

1. accept that complete mutable Kotlin view and all its legal writes;
2. retain the existing exact FieldDef and CLR construction;
3. retain one receiver and one authoritative state;
4. add no wrapper, shadow state or representation-changing replacement; and
5. store the int-only producer as the original receiver.

This is a verifier/assignability contradiction, not dispatch ambiguity.
BK-1 cannot reject a warning-free legal assignment to solve it. Calling the
correct int-producing method cannot change the field's capacity. An out-null
flag transports nullness, not a receiver of an incompatible field type.
Even choosing a wider authoritative field in a redesigned producer does not
make its natural C# getter able to return that int-only receiver as
`Producer<object>`. The state design and the outward call contract must both
be truthful; a failing typed export is an explicit interop consequence, not
successful complete natural representation.

The same problem can originate entirely in Kotlin through a separately compiled
generic constructor or a later mutable write. Foreign C# construction is the
strongest counterexample because the physical object already exists and cannot
be replanned. It forces an explicit truthful boundary even if Kotlin-owned
allocations eventually admit more flexible construction choices.

### What this does not imply

It does not imply that `Box<Int>`, `Box<String>` or `MutableList<Int>` must use
object state. It does not imply that placing `List<Int>` and `List<String>`
inside `List<Any>` erases those inner lists: that outer slot intentionally stores
their references as `Any`.

An open field declared physically as `!T` also differs from the actual argument
chosen for a particular construction. A newly allocated broad construction
could keep the open `!T` field while deliberately using a wider physical argument.
For example, a proposed physical `Box<object>` can store the int-only producer
without changing `Box<int>` or `Box<string>` fields.

That is an **unaccepted construction/ABI option**, not a cast of an existing
`Box<Producer<int>>` or `Box<Producer<object>>` to `Box<object>`. Its C# surface
is genuinely broader. A source initializer proving one current value does not
authorize specializing a public mutable object whose future writes are wider.

The composition test is an independently compiled generic function:

```kotlin
fun <T> make(value: T): Box<T> = Box(value)
fun <T> makeNested(value: Producer<T>): Box<Producer<T>> = Box(value)
```

The first can use a caller-supplied physical generic argument when that argument
faithfully carries its whole contract. The second cannot automatically choose
different result constructions at different closed calls while retaining one
ordinary `Box<Producer<T>>` CLR MethodDef. CLR signature substitution does not
evaluate an arbitrary type-level function meaning "carrier of this logical
type expression". A broader fixed boundary, explicit representation parameter
or separately recorded route would be additional design, not local provenance.
This is why a successful closed allocation is insufficient evidence for a
general allocation mapping.

Nor is every reference-looking nested type uniformly CLR-representable.
`Producer<Nothing>` is a legal non-null object whose method never returns;
it can be stored as `Producer<String>`. `Nothing` being uninhabited does not
make `Producer<Nothing>` uninhabited. The new JVM negative-return/effect test
confirms the Common obligation. The existing
[constructor fixture](../../../../testData/codegen/dotnet/box/genericOwnerClosedConstructorInput.kt)
already records this bottom-view boundary. [K1]

### Candidate storage strategies and their costs

| Strategy | What it can preserve | Unresolved cost or limit |
| --- | --- | --- |
| Keep exact `!T` under a complete representability proof | Natural fields, exact calls and honest C# substitutions | Proof must cover bottom/nullability, variance, all writes and external callers, not observed initializers |
| Choose a wider actual physical argument for affected new constructions | One state and receiver; unrelated scalar constructions remain typed | New construction mapping, public signatures, generic forwarding and logical/physical argument identity must compose |
| Use one wider authoritative field where the whole producer requires it | Legal broad writes on the same object | That field is genuinely wider; do not claim `!T` storage or split fast/slow shadow state |
| Add a methodless family carrier implemented through a natural interface base edge | Potentially a family-specific reference carrier without hidden C# methods | New physical ABI type/edge; no logical arguments encoded; cannot retrofit native CLR interfaces or repair an existing narrow Box |
| Store a receiver-plus-selection value | Can preserve otherwise required per-value selection in admitted slots | Does not solve ordinary object identity, existing narrow fields, or arbitrary generic/Any transport by itself |

None is promoted by this report. A method-bearing semantic capability cannot
simply become a natural base interface: ordinary C# implementors would then
inherit hidden implementation obligations. The methodless-marker alternative
avoids that particular problem, not the others.

A broader physical argument mapping is not necessarily injective. Several
logical types can map to `object`, so `Box<object>` alone cannot distinguish
their actual logical arguments. BK-1 checks, logical constraints and separate
generic construction/forwarding need an explicit answer if they rely on that
distinction. A caller/owner dictionary or descriptor is different from a
historical selector attached to every interface value, but is still new ABI
and possibly state work.

Do not overstate reflection as a reason for such descriptors: the accepted
[`KType` model](../decisions/ktype-and-typeof.md) constructs logical graphs from
declarations and reified substitution; a non-reified parameter is not an
automatic promise to recover an object's concrete generic argument. Each
consumer must establish what information its own contract actually requires.

## Split-nullable and call composition

Operation selection and result layout remain independent. For a recorded
direct nullable generic result, `SplitNullable(OwnerParameter(1))` is substituted
through the selected physical owner construction; it is not remapped from a
later logical `IrType`.

An exact custom `Lookup<K,V>.get(K): V?` can therefore use a truthful
`!V Get(!K, out bool isNull)` contract where its producer proves it. Owner-input
policy and output nullness need no combined member role and no collection-name
rule. An exact value-type caller must retain the physical payload rather than
route through an object-returning reflective helper. A broadened semantic call
may need boxing or a different documented layout.

This does not solve storing a mismatched nested interface receiver in `!T`, or
choose between conflicting implementations. A nominal value-class argument
must use its actual physical generic carrier, not an opportunistic unboxed
payload chosen from its Kotlin underlying type. Open-nullable and bottom
substitutions require separate positive/negative tests under the same layout
authority. The current
[split-nullable ADR](../decisions/draft-adr-split-nullable-callable-result.md)
remains unchanged.

## Hostile acceptance matrix

Before trusting an operation-directed compiler route, the matrix must cover
more than a one-member producer without inputs:

| Attack | Required observation | Evidence here |
| --- | --- | --- |
| Exact/wide/star coherent Kotlin calls through separate generic storage | Same receiver/family, one call effect | JVM baseline, not .NET integration |
| Native exact target differs from source selection | Target body runs through forwarding and object roundtrip | CLR proof on both profiles |
| Several native reference variants, no exact target row | Direct CLR and bound interface MethodDef agree | CLR proof on both profiles; no portable winner assumed |
| Mixed value/reference constructions | Distinguish exact-row priority from supported-target priority | Bounded CLR hypothesis only |
| One construction, child explicit reimplementation | Interface map wins over base public virtual method; same exception object | CLR proof on both profiles |
| Stars and multiple Kotlin-compatible constructions | Classifier success separate from operation ambiguity | Classifier/selection distinction demonstrated only in bounded CLR model |
| Broad candidate inputs and multiple members | No argument-driven reselection, no inappropriate early typed cast; preserve recorded wrong-shape result | Required compiler proof |
| Deep Kotlin/C# overrides, defaults, diamonds and `super` | Actual virtual/nonvirtual contract, no inherited-hook bypass | Required separate-assembly proof |
| Mutable locals, fields, mixed captures and source-declared broad returns | Preserve incoming values without narrowing their contract or specializing public API | Required compiler proof |
| `Producer<Nothing>`, nullable results, `Nullable<T>`, nominal value classes | Correct effects/nullness and producer-recorded payload substitution | JVM bottom/null subset only |
| Existing C# narrow Box and later Kotlin broad write | Honest admission/surface or wider state; never an invalid store | Physical negative exists; complete Kotlin boundary remains unselected |
| Cache alternates target contracts, MethodDefs and MethodSpecs | No cross-operation contamination on the same runtime type | Required model/runtime proof |
| Trimming, NativeAOT, reflection and collectible assemblies | Rooted metadata/code, stable dispatch, no unbounded lifetime retention | Not run |

The runtime currently uses reflection for its bounded foreign fallback and may
call `MakeGenericMethod`. The documented API carries dynamic-code and trimming
warnings, so a JIT demonstration is not an AOT proof. Metadata preservation,
reachable instantiations, generated or runtime-supported thunks and cache
lifetime need explicit deployment evidence. This is not a claim that every
reflective interface call is inherently incompatible with AOT. [N1]

## Staged next work and stop conditions

1. **Keep the current authority decomposition and guards.** Treat this report
   and its probes as a completed research checkpoint, not a new storage model.
   Both parked constructor experiments remain parked.
2. **Add a production-inert operation-contract model.** Explain the new native
   and coherent-family observations using existing declaration facts. Distinguish
   exact metadata row, supported native view and Kotlin-only semantic view.
   Test target/member/policy cache keys and unavailable/ambiguous outcomes.
   Start the independent storage model/probes in step 5 alongside this work;
   their fundamental constraints must inform any later ABI-bearing slice.
3. **Make the conflicting-foreign policy decision explicitly.** Compare
   exact-row priority, native-supported-target priority and source-preserving
   behavior with mixed constructions, stars and multiple related members.
   Prefer the smallest rule consistent with normal C# and Kotlin contracts,
   but do not present the current one-member hypothesis as that decision.
4. **Integrate one complete operation slice after its policy is closed.** Carry
   authenticated target intent to the appropriate runtime boundary, preserve
   actual interface MethodDefs and independent input/result policies, and prove
   separate Kotlin/C# use, effects, exceptions and the erased inverse. No
   name-based selection and no blanket removal of the multiple-view guard.
   Coherent/native-only work can retain conflicting-case guards; it need not
   wait for an unrelated foreign extension policy.
5. **Run the independent storage go/no-go proof.** Include separately compiled
   `Box<T>`, public generic forwarding, Kotlin allocations, unchanged foreign
   allocations, mutable replacements and bottom views. Reject any solution that
   quietly weakens legal Common writes or changes natural C# signatures without
   recording it. Do not solve this by broadening every unrelated field.
6. **Only then revisit residual selector transport.** A pair is justified only
   for an operation whose accepted contract genuinely requires irrecoverable
   selection and whose full storage/interop path can carry it. An unchanged
   raw-object boundary still cannot preserve two discarded histories.
7. **Resume the stdlib rehearsal after these boundaries are closed.** A final
   candidate needs the complete compiler/profile/deployment gates, performance
   measurements and exact inverse/rollback before an atomic production cutover.

Stop and request an explicit architecture choice if a remaining requirement
forces a changed `Any` representation, arbitrary hidden foreign obligations,
source-illegal narrowing of legitimate Kotlin values, or a publicly misleading
construction. Do not disguise such a choice as an optimization.

The bounded mechanism results are positive. They justify **less new runtime
value machinery**, not a claim that generic storage is solved or that foreign
dispatch can always be inferred without an operation contract.

## Sources and verification boundary

- **[K1]** Marat Akhin and Mikhail Belyaev, *Kotlin language specification*,
  version `1.9-rfc+0.1`, [Type system][K1], especially parameterized classifier
  consistency, variance, stars and `Nothing`. Consulted 2026-09-13; current FIR
  source and recorded-version JVM execution provide separate corroboration.
- **[K2]** Same specification, [Cast expressions][K2]. The earlier contract
  archive also records assignment, RTTI and reference-equality sources. BK-1
  authority is the local [breaking-change ledger](../decisions/breaking-kotlin-changes.md),
  not an inferred change to the published Kotlin specification.
- **[C1]** Microsoft, *C# language specification*, [Interfaces][C1], sections
  19.2.3.3, 19.6.2, 19.6.3, 19.6.5 and 19.6.7. Consulted 2026-09-13; dispatch
  outcomes are independently executed on the two recorded CLR profiles.
- **[C2]** Microsoft, *C# language specification*, [Conversions][C2], sections
  10.2.8 and 10.2.9. Consulted 2026-09-13; reference identity and boxing.
- **[N1]** Microsoft, [.NET 10 `MethodInfo.MakeGenericMethod` documentation][N1].
  Consulted 2026-09-13; dynamic-code and trimming annotations, not performance
  measurements or proof that a specific future route fails AOT.
- **[N2]** Microsoft, [Covariance and contravariance in generics][N2]. Consulted
  2026-09-13; reference-argument variance and invariant generic classes.

Local code, accepted decisions, candidate ADRs and executable fixtures are
linked next to their claims. The governing
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md)
and [selected-view candidate](../decisions/draft-adr-selected-interface-view-transport.md)
remain pre-ABI architecture documents, not upstream language endorsements.

No compiler, Common, Runtime/Stdlib source, physical schema or admission guard
changes accompany this research. No stdlib census or full Kotlin/.NET aggregate
was run. Full production-erased checkpoint `3724aeab9c` and the subsequent
focused compiler evidence remain inherited; these standalone assertions do
not increase the full aggregate's reported test count.

[K1]: https://kotlinlang.org/spec/type-system.html#parameterized-classifier-types
[K2]: https://kotlinlang.org/spec/expressions.html#cast-expressions
[C1]: https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/language-specification/interfaces#1965-interface-mapping
[C2]: https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/language-specification/conversions#1028-implicit-reference-conversions
[N1]: https://learn.microsoft.com/en-us/dotnet/api/system.reflection.methodinfo.makegenericmethod?view=net-10.0
[N2]: https://learn.microsoft.com/en-us/dotnet/standard/generics/covariance-and-contravariance
