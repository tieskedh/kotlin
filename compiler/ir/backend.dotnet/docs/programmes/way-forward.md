# Kotlin/.NET execution programme

- Status: **Living pre-ABI route map**
- Current branch and verification: [`../../STATUS.md`](../../STATUS.md)
- Normative decisions: [`../decisions`](../decisions)

This document orders work and sets release gates. It does not own representation or ABI details,
repeat implementation history, or report test counts.

## Target-author working position

This is the target authors' position, informed by Kotlin Common and mature targets. It is not a
decision or endorsement by the Kotlin core team.

The backend continues as a high-quality pre-ABI prototype. No Kotlin/.NET binary, generated-name,
runtime, metadata, or public source-annotation compatibility has been promised. Before an explicit
freeze, an unsound prototype representation is replaced atomically rather than preserved through
dual readers or migration aliases.

That freedom does not permit Kotlin semantic drift or incompatible meanings across target
profiles. Common remains authoritative; the CLR justifies a target-specific representation only
where its physical model requires one.

## Fixed product premises

### Target profiles

| Profile | Product | Purpose |
| --- | --- | --- |
| `net48` | applications and libraries | Established .NET Framework ecosystem |
| `netstandard2.0` | libraries only | Portable asset consumed by both supported runtimes |
| `net10.0` | applications and libraries | Modern LTS runtime and CLR capabilities |

`.NET Standard` is not an executable runtime. A target profile is selected before lowerings and
controls reference assemblies, legal CLR capabilities, runtime/stdlib variants, and packaging.
The same Common declaration keeps one meaning; physical output may differ where profiles genuinely
differ.

One application deploys one profile-selected runtime/stdlib pair. Runtime-specific variants must
remain compatible supersets of the portable product needed by `netstandard2.0` libraries.

### Semantic and artifact authority

- Common declarations and stdlib generators own Kotlin source semantics.
- Stronger CLR runtime checks are admitted only for the exact operation and
  observation which the Kotlin specification leaves platform- or
  implementation-dependent. Warnings and suppression annotations are not
  semantic waivers; see the
  [platform-freedom ADR](../decisions/kotlin-semantic-authority-and-platform-freedom.md).
- Kotlin-produced libraries are self-describing DLLs whose embedded KLIB owns logical identity.
- CLR metadata owns executable shape and the useful foreign-language view.
- Prefer the native CLR/BCL identity or operation whenever the complete Kotlin
  contract is observationally equivalent. A compatible user `actual` should
  bind an imported CLR type directly, including through `actual typealias`,
  rather than require a wrapper merely because its declaration originated in
  C#.
- Standard CLR attributes are consumed or emitted where they express an exact interoperable fact.
- Compiler-only physical members are marked as such and do not become an idiomatic C# API merely
  because linkage requires public metadata.
- C# exports are deliberate facades for Kotlin-owned or shape-mismatched APIs;
  they do not redefine ordinary Kotlin ABI or displace an exact direct CLR
  binding.

### Pre-publication correction policy

An ABI-affecting correction changes compiler, runtime, stdlib, metadata, tooling, tests, and
packaging together. Prototype schema/runtime levels are bumped and stale artifacts fail clearly.
A correction must not silently accept old and new unpublished identities.

## Established foundations

The following decisions constrain new work; their ADRs own the detail:

- [self-describing DLL identity](../decisions/adr-self-describing-dotnet-library-dll.md);
- [target profiles and platform identity](../decisions/dotnet-platform-and-target-frameworks.md);
- [compiler and Gradle integration](../decisions/compiler-and-gradle-integration.md);
- [`System.Object` as the physical `Any` foundation](../decisions/system-object-any.md);
- [classified CLR exceptions](../decisions/classified-clr-exceptions.md);
- [primitive scalar carriers](../decisions/primitive-scalars.md);
- [Kotlin-owned primitive arrays](../decisions/primitive-arrays.md);
- [exact-vector projected array joining](../decisions/projected-generic-array-join-fast-path.md);
- [single-field value classes](../decisions/value-classes.md);
- [Common continuation ABI and explicit CIL coroutine state machines](../decisions/kotlin-coroutines.md);
- [runtime and stdlib ownership](../decisions/runtime-and-stdlib-ownership.md);
- [generic nullability and covariant returns](../decisions/adr-hybrid-generic-nullability-and-covariant-returns.md);
- [profile-aware interface defaults](../decisions/adr-profile-aware-interface-default-implementations.md);
- [companion placement and initialization](../decisions/adr-companion-static-placement-and-initialization.md);
- [static-initialization failures](../decisions/adr-kotlin-static-initialization-failures.md);
- [reified inline call-site substitution](../decisions/reified-inline-functions.md);
- [logical `KType` and `typeOf`](../decisions/ktype-and-typeof.md); and
- [Common delegated-property semantics over ordinary CLR state](../decisions/delegated-properties.md);
- [CIL/PE production direction](../decisions/cil-and-pe-production.md).

New features build on these decisions or amend them explicitly. They do not restate a competing
local version.

## Current execution order

The current bounded work order is intentional. It may interleave small slices, but a later item
must not pull an earlier responsibility back into the backend or publish a shape whose prerequisites
are still undecided.

### 1. Preserve the completed selected-graph inline foundation

[`inline-functions.md`](inline-functions.md) records the completed component-aware embedded KLIB
loading, target IR serialization, shared first-/second-stage inliner phases, Common shared-variable
ABI, and all existing KLIB inliner modes. New work must keep its separate-DLL, friend/compiler-ABI,
main/prepared IR, and cross-profile matrix green.

Ordinary and reified inline are available for exact Common-source adoption.
The selected coroutine foundation now admits suspend-inline calls through the
same shared inliner plus the continuation/state-machine pipeline. It does not
make suspend callables a CLR delegate/export surface or imply that every
coroutine-dependent library is available.

The selected-graph breadth is now pinned explicitly: a body from library A binds declarations in an
explicitly selected library B through the existing non-linking deserializer and frontend-owned IR
symbol finder. Preserve that boundary without introducing a general IR linker or transitive
dependency discovery.

Cross-module IR inlining is now the upstream production default for stdlib and
`kotlin-test`; mature-target suites are deleting runners whose only purpose was
to force that mode. Keep the three .NET modes only as explicit compiler-ABI
compatibility evidence. New Common semantic coverage uses the production
default rather than multiplying the complete matrix for an already selected
inliner setting.

The completed reified programme composes its independently truthful non-generic reference,
boxed-scalar, type-test/cast, array, declaration-erased generic-class/interface, `KClass`, enum,
and physical-remainder foundations through shared call-site substitution. Preserve the three KLIB
inliner modes, the bodyless-intrinsic pipeline boundary, and the rule that no physical remainder
belongs to Kotlin ABI or explicit C# export. The completed `KType`/`typeOf` graph composes that
substitution after inlining; neither `KClass` nor CLR `System.Type` substitutes for it.

### 2. Preserve the completed `KType` and `typeOf` reflection foundation

Common `KType`, `KTypeProjection`, `KVariance`, `KTypeParameter`, and `typeOf` now form one logical
Kotlin graph following the mature-target compiler-intrinsic architecture. Preserve nested type
arguments, stars, declaration/use-site variance, nullability, equality/hash, recursive parameter
bounds, and separate-library identity. `System.Type` remains optional `KClass` evidence only.
Logical-only classifiers use a distinct KLIB-mangled key, never a display name.

The product matrix covers static creation, reified substitution, declaration parameters and
recursive bounds, erased Kotlin classifiers, arrays, nullable relative bounds, production-pipeline
separate compilation, both CLR profiles, and direct C# graph inspection. Ordinary Kotlin-produced
  class-member enumeration now composes this graph with the completed callable invocation surface.
  The generated catalog admits mapped `String`, all sixteen built-in collection
  interfaces, and the complete current Kotlin-owned collection implementation
  family: the read-only and mutable abstract bases plus `ArrayList`, `HashMap`,
  and `HashSet`. Linked hash collections retain their actual-typealias identity.
  Remaining mapped/Stdlib and foreign classifier families remain follow-on
  closures.
Valued annotation classes now use the Common member generator and embedded KLIB
as their complete semantic representation; exact runtime-retained
scalar/string/vector values receive an additive CLR custom-attribute row,
while unsupported values fail closed to KLIB-only without weakening Kotlin
construction.

Class and callable-reference annotation discovery now enumerate authoritative
KLIB applications and reconstruct the existing annotation runtime values.
Foreign classes use the separately admitted unmarked-assembly path; imported
foreign callable references use exact MethodDef/Property tokens. Neither path
infers Kotlin applications from derived CLR rows or requires broad member
enumeration/invocation. `KCallable.returnType` now follows Native's exact
reflection-target rule and reuses the same logical graph producer as `typeOf`;
Kotlin declarations come from KLIB-derived IR and imported CLR declarations
from importer-enhanced semantic IR. Runtime owns the minimal physical `KType`
interface needed by its typed callable slot, while Stdlib retains Common graph
behavior. Runtime/Stdlib surface 35 now follows JVM's platform extension by
making `KType` a `KAnnotatedElement`: declaration-derived return, parameter,
receiver, nested-argument, and upper-bound nodes retain only their exact
runtime KLIB/IR annotations. `typeOf` remains annotation-empty as on JVM, and
CLR nullable or nearby custom attributes never become Kotlin annotation
objects. Structural `KType` equality, hashing, and rendering remain unchanged.
`KCallable.typeParameters` now takes JVM's declaration-owned rule:
functions and generic extension properties exclude enclosing class parameters,
while constructors expose the constructed class's own parameters. Return
types, exposed parameters, recursive bounds, and reachable enclosing parameters
are allocated in one identity graph. `KCallable.parameters` now extends that
same graph with JVM's owner, ordering, captured-receiver omission, reindexing,
default, vararg, and equality rules. Kotlin parameter applications retain their
exact declaration owners; admitted foreign callables use exact CLR Param rows
  without turning CLR optional flags into Kotlin default-call semantics. The
  selected mapped/Stdlib catalog now reuses the same graphs for one- and
  two-parameter collection owners, read-only/mutable interfaces, nested map
  entries, concrete classes, and abstract bases; foreign and remaining
  classifier families remain separate tranches.
Positional `KCallable.call` now consumes that exact
parameter order through the existing erased `FunctionN` capability. Runtime
surface level 22 validates count and dispatches without CLR member discovery;
defaults remain explicit, a vararg is one array argument, property call means
getter, and target exceptions keep identity. The `callBy` default-mask/omission
tranche is now complete for the fixed `KFunction0` through `KFunction22`
closure. Runtime surface 27 adds the remaining erased execution interfaces;
named invocation uses exact parameter identity, JVM omission/error behavior,
fresh exact empty varargs, and the shared static class/default-dispatch ABI
across separate libraries. Library ABI 22 keeps
Kotlin-owned class parameters erased on that helper while retaining genuine
method generics. The tranche introduces neither CLR member lookup nor a
reflection-owned mask ABI.

The five JVM-shaped `KFunction` declaration flags now form one property
capability inherited by every admitted `KFunction0` through `KFunction22`.
KLIB/importer IR owns inline, external, operator, infix, and suspend status;
generated invoke adapters and runtime CLR reflection do not reconstruct it.
Constructors and ordinary admitted CLR interface methods report false. Runtime
surface 24 publishes the erased `Kotlin.KFunction` and shared-base getters,
while library ABI 23 rejects already-materialized old references that lack the
declaration bits. Suspend
callable references and external linkage remain independent execution
features, not consequences of publishing their declaration facts.

JVM-shaped direct-callable visibility and modality are now complete. Logical
FIR/IR/KLIB or importer facts populate function, property, constructor, and
local-reference tokens; emitted CLR MethodDef and bridge flags are never used
as semantic evidence. Runtime surface 26 publishes the ordinary Kotlin
`KVisibility` reference enum and shared callable getters, while library ABI 25
rejects already-materialized references without the declaration bits. To keep
Runtime independent of Stdlib, Runtime also owns the physical member-free
`EnumEntries` interface; the Common generic declaration and all list behavior
remain Stdlib-owned. Constructors and reflective invocation beyond the
admitted callable/member closure remain independent tranches.

Direct property declaration facts and accessor objects are now complete for
`KProperty0` through `KProperty2` and their mutable counterparts. JVM supplies
the public reflection contract; the existing Native/Wasm-shaped property
wrapper supplies the one execution identity. Getter and setter objects are
cached `KFunction` capabilities that call the owning property's `Get`/`Set`
path, so bound receivers, virtual dispatch, mutation, exception identity, and
separate libraries cannot diverge. KLIB/importer IR owns `isConst`,
`isLateinit`, accessor signatures, annotations, visibility, modality, and
function flags. Runtime CLR reflection owns none of them. Runtime surface and
library ABI 29 publish this closure. The later Common-owned `lateinit`
foundation makes the already published positive declaration bit observable
without changing that callable ABI; `getDelegate` remains a separate tranche.
Type-use annotations were selected later under their own declaration-owned
ADR.

JVM-shaped `KClass.members` is now executable for Kotlin-produced user/library
classifiers whose producer explicitly opts in with `-Xdotnet-reflection`,
through an optional `Kotlin.Reflection.dll`. Ordinary producers emit no member
factory. The backend emits only private producer-owned callable/property
factories derived from the post-KLIB logical class scope; the optional product
owns lookup and Runtime owns only exact delegation and per-`KClass` caching. Enumerated values
are the established references, so annotations, parameter/type graphs,
accessors, invocation, `callBy`, exception identity, and equality do not gain a
second implementation. Interfaces, default members, inheritance, generic
erasure, nested classes, objects, enums, overloads, mutable properties, and
  member extensions are in the first closure. Runtime and Stdlib retain no
  static reflection-product dependency. Local/anonymous and foreign
  classifiers fail closed rather than publishing a partial member set;
constructors and declared-member convenience APIs remain separate. Preserve
the owner split and the path-independent, `visibleCrossFile` callable identity
rule in the [class-member reflection ADR](../decisions/class-member-reflection.md).
Private protocol 2 now retains that semantic reuse with one generated
dispatcher TypeDef per reflected producer class and shared exact-arity Runtime
carriers. Direct ordinary-IR thunks continue through the normal default,
suspend, virtual-dispatch, and value-representation lowerings; no CLR
reflection path was introduced. Runtime/library surface 32 versions the new
  factory. Runtime/library surface 33 adds a Stdlib-owned catalog generated
  after KLIB serialization from complete Kotlin scopes. Its admitted entries
  cover the eight concrete Common scalar built-ins, mapped `String`, the
  complete built-in collection-interface family, and the current Kotlin-owned
  collection implementation family; adding those complete scopes does not
  version the catalog protocol. Runtime/library surface 34 adds `Number` as its
  own complete classified-carrier family, derived directly from
  `IrBuiltIns.numberClass` rather than inferred from concrete scalar entries.
  Built-in
  entries come from `IrBuiltIns`, never their boxed CLR carriers or canonical/
  exact constructed interface capabilities. Lookup remains optional-product
  policy, arbitrary BCL members never enter the result, and inherited fake
  overrides retain
  declaration identity while their resolved overrides supply execution only.
  Abstract skeletal-class members
  still require a real subclass receiver; sharing a collection interface does
  not invent a class-inheritance edge. The catalog also forced authoritative
  Common `@IgnorableReturnValue` into the Stdlib source closure instead of
  dropping a reflected annotation. The ordinary user/library producer remains
  explicitly opted in: default enablement still
  requires product-size, trimming, NativeAOT, startup, and invocation evidence,
  plus the remaining mapped/foreign/Stdlib authority paths.

The first bounded size correction completed at runtime surface and library
ABI 31. `FunctionReferenceBase` owns common callable reflection bodies once,
as JVM `CallableReference`/`FunctionReference` and Wasm `KFunctionImpl` do;
generated references retain only declaration-specific execution hooks. The
base deliberately does not implement `KFunction`, preserving the JVM boundary
for adapted `FunctionN`-only references. Five generated-IL baselines lost 693
repeated lines. The following protocol-2 closure completes the structural
correction at surface/ABI 32: member count now grows dispatcher cases and
MethodDefs, not callable TypeDefs. Treat later representation work as measured
product trade-offs; do not rebuild a second callable implementation.

Future member enumeration must still cover member extension properties with
multiple callable-owned type parameters and, once context parameters are
admitted, nested/member properties with multiple context-owned types. The
second-stage `IrTypeParameterScopeChecker` remains enabled: a failure is
evidence of an invalid graph or deserialization boundary, not a target-specific
checker to disable.

### 3. Close the selected Kotlin coroutine foundation

Use [`../decisions/kotlin-coroutines.md`](../decisions/kotlin-coroutines.md).
Common `Result`, `Continuation`, `CoroutineContext`, coroutine intrinsics, and
inline helpers are authoritative. The .NET target supplies a target-owned
ordinary-IR state machine shaped after JS/Wasm plus the CLR-atomic
`SafeContinuation` mechanism; it does not use `Task<T>` or `ValueTask<T>` as
Kotlin runtime identity.

The initial executable closure covers immediate and suspended completion,
tail delegation, exception and `finally` control flow, suspend lambdas,
suspend callable references, interception/release, suspend-inline execution
across a producer/consumer DLL boundary, value-class result carriers,
repeated loop suspension, nullable-reference, null, array-element, and mutable
reference spills, nullable-`Int` value classes through generic suspend overrides,
local/two-receiver extensions, ordinary and virtual/`super` members, suspend
operators, private top-level/member state machines, receiver dispatch, Common
context composition and identity across suspension, balanced interceptor
release, cross-thread duplicate-resume rejection, both FIR parsers, and
Framework/CoreCLR execution. Continue from that one architecture, not from
stdlib allowlists. The complete Kotlin primitive family now executes through
typed state-machine fields plus the erased continuation boundary. Callable
arities match Common/JVM across the fixed `Function22`/vararg `FunctionN`
boundary, including a real logical-suspend-arity-22 park/resume path. A final
Common-validator-derived .NET phase now rejects residual suspend declarations,
calls, references, suspension pseudo-IR, and compiler-only coroutine
intrinsics before emission, while the emitter retains its production guard.
Embedded producer schemas and the selected runtime's standard
`Kotlin.RuntimeSurfaceLevel` metadata now reject missing, malformed, stale,
future, and duplicate contracts before FIR. Coroutine-specific physical-ABI
evidence now pins the appended erased continuation, `Object` result, private
sealed state-machine base, and public-in-private constructor boundary through
one portable producer consumed and executed by both CLR profiles. Shared member-default,
suspending-default-lambda, generic-interface, adapted-reference, reference
identity, and suspend/reference cast tests now run unchanged. Prefer
unchanged shared coroutine tests and add target-owned tests only for CLR
threading, metadata, assembly, or physical CIL facts. Shared tests whose
assertions use `String.trimIndent` remain behind the authoritative Common
Strings/Indent dependency closure rather than receiving target-specific copies.

The complete Common Sequence builder state machine is now a consumer of this
foundation and retains the same continuation/sentinel ABI. Coroutine
scheduling, `kotlinx.coroutines`, debugger metadata, suspend callable
reflection/export, and explicit C# async adapters remain future consumers.
None may introduce a second state-machine representation.

The completed language foundation selected after delegated properties is Kotlin
fun-interface/SAM conversion. Common `SingleAbstractMethodLowering` owns the
wrapper semantics, as on JVM, JS, Wasm, and Native; .NET retains an ordinary
Kotlin interface and an ordinary generated implementation class rather than a
CLR delegate identity. This selection precedes the next collection tranche
because Common `Comparator<T>` is itself a fun interface and therefore blocks
the sorting/comparator substrate. The complete representation, equality,
runtime capability, interop boundary, and evidence gate are owned by
[`../decisions/fun-interfaces.md`](../decisions/fun-interfaces.md).

The completed first consumer after that foundation is the dependency-closed
Comparator and non-mutating order-selection tranche. It publishes the exact
Common ordinary fun interface, complete Common comparison combinators,
comparator min/max selection, and Iterable sortedness traversal without
claiming a stable platform sort. The semantic/physical/C# boundary and the
separate stable-sort prerequisite are owned by
[`../decisions/comparator-and-selection-foundation.md`](../decisions/comparator-and-selection-foundation.md).

The completed next consumer is the stable MutableList plus generic/signed-array
sorting closure. It reuses the exact Native/Wasm snapshot, stable merge-sort,
and seven per-wrapper quicksort lineages, then admits the complete
dependency-closed eager Iterable/MutableList/object-array/signed-primitive-array
`sorted*`/`sort*`/range/reverse inventory. Unsigned arrays, binary search, and
random ordering remain separate. Sequence sorting subsequently landed inside
the complete Sequence foundation, whose Common builder/window closure is now
also published. The stable algorithm, range and
failure timing, arbitrary-list, open generic snapshot, physical, and C#
boundaries are owned by
[`../decisions/stable-list-and-array-sorting.md`](../decisions/stable-list-and-array-sorting.md).

### 4. Expand Common collections by exact dependency closure

Use [`common-collections.md`](common-collections.md). Its builder and Common abstract-base
foundation now composes with the selected erased generic-class ABI without a target-authored
algorithm or collection-specific bridge.

The collection work provides ordinary user value and the foundation now consumed by enums, while exercising
generic interfaces, arrays, separate products, and profile-compatible stdlib publication.

The mutable iterator/collection/list and Set/Map contracts, Common abstract mutable bases,
ordinary Kotlin `ArrayList`, Native/Wasm-derived open-addressed `HashMap`/`HashSet`,
factories/builders, separate-product ABI, truthful C# boundary, staged Common `kotlin.test`
product, and dependency-closed Iterable/List/Map/Set generator families now form one completed
architectural unit. Preserve its owner-erased class/interface identity, method-generic vararg and
relative-constraint ABI, projected-array boundary, foreign physical-signature authority, local
stdlib-helper binding, and unchanged upstream test path.

Further work remains foundation-first rather than allowlist-count-first. The
largest coherent dependency releases selected by the last graph audits are
now complete: the Kotlin-owned Sequence identity and builder/window closure,
plus the complete Common Grouping aggregate/factory closure, are published
under their
[`Sequence`](../decisions/sequence-foundation.md) and
[`Grouping`](../decisions/grouping-foundation.md) ADRs. Recompute the remaining
Common generator/source graph around primitive/unsigned/range sorting, random
operations, and dependency-blocked reified variants. The narrow open-nullable-
array foundation is now complete:
`Array<out T?>` uses an identity-preserving `System.Array` read view, Kotlin-owned
`vararg T?` uses a fresh declaration-stable `object[]`, and the bounded release restores
authoritative `setOfNotNull(vararg T?)` plus object-array nullable filtering.
Invariant/input method-owned open nullable arrays remain excluded except for
Common `RingBuffer.toArray`'s one immutable conditionally initialized local
view over its resized-or-supplied exact vector. The complete signed
primitive-array and remaining object-array range-sorting closure is now
published from the exact Common generator families and Native/Wasm algorithm
lineage. It includes all seven naturally ordered signed wrappers, stable object
ranges, snapshots, descending/reverse/selector consumers, and Boolean's
explicit-comparator/reversal subset without `System.Array.Sort` or unsigned
spillover. Open producer-generic snapshots preserve the source vector's exact
runtime component type rather than allocating `object[]`. The complete Common
Sequence builder, running, and windowing closure is now published without a
target-authored algorithm. Random-dependent operations, the unsigned value-
class/range product, and dependency-blocked reified variants remain separate.
Recompute that remaining graph before selecting the next Common
closure; completion of signed sorting is not authority to choose one of those
independent representations implicitly.
Loose one-function growth and implicit BCL collection identity remain excluded.

The semantically erased generic-class route is selected in
[`../decisions/generic-class-erased-identity.md`](../decisions/generic-class-erased-identity.md).
The disproven primary-typed/exceptional-canonical model has been replaced by one canonical
non-generic Kotlin owner, one erased virtual hierarchy, and one authoritative state. Explicit typed
C# export remains a later fail-closed product. A second observable class ABI, wrappers as Kotlin
identity, and visibility-dependent ABI remain excluded. Removable scalar replacement, private
CLR-generic helpers, typed storage with correct deoptimization, and AOT-specific specialization are
future optimization work rather than forbidden representations. Defer them until core
language/stdlib coverage, the concurrency/memory model, and representative benchmarks can justify
their permanent complexity.

Swift-style devirtualization and Kotlin/Native Variable Type Analysis may later supply a
closed-world proof for direct calls or private CLR/BCL specialization. The analysis must treat
public and cross-assembly entry points conservatively and retain the canonical erased fallback.
It cannot supply missing CLR `!T` identity for a public Kotlin-owned owner; changing that TypeDef,
its casts, reflection, inheritance, or C# signature remains the coherent ABI reopening below.

A materially different true CLR-generic owner with a complete erased Kotlin
capability ABI and early failure of physically incompatible unchecked casts is
now the explicit long-term direction in
[`generic-class-owner-reopening.md`](generic-class-owner-reopening.md). It is
not the rejected primary-typed/rare-fallback design, but neither is it an
optimization: it changes observable ABI and cast timing. Current work continues
against the accepted erased owner while the hostile semantic model and atomic
migration plan are designed. That gate blocks only reintroduction or freeze of
a Kotlin-owned generic class TypeDef as CLR `C<T>`; it does not block Common
stdlib, callables/reflection, CLI IR, imported CLR generics, generic methods,
separate explicit exports, or removable private specialization. The first
spike must combine open mutable invariant state, stars/projections, value and
nullable substitutions, candidate-accepting erased methods, Kotlin/C#
inheritance, casts, reflection, nested carriers, and separate assemblies. Do
not publish easy final/read-only owners first and switch the representation as
harder cases arrive.

The spike must distinguish construction-time choice from metadata-fixed
TypeDef edges. Runtime construction can close standalone `C<T?>` objects, but
it cannot make one `D<T> : C<T?>` TypeDef alternate its base between
`C<Nullable<T>>` and `C<T>`. That inheritance shape requires one tested,
C#-honest fixed fallback or deterministic exclusion from reified admission;
factory success is not a substitute for the override, `super`, state, cast,
reflection, and ancestry proof.

The first fail-closed analysis seam now runs over normal lowered Kotlin IR
before erased generic-interface bridge construction. It records every local
generic class, member authority (strict typed, shared Kotlin barrier, or
general semantic body), explicit `T?` metadata-fixed supertype edges, direct
semantic writes to owner-dependent fields, and open owner-dependent outputs.
It has no admitted/reified state, is not consumed by physical emission, and
requires the following erased lowering to account for every planned class.
This is architecture evidence gathered now, not a partial owner migration:
absence of a directly observed write still means that a complete cross-body
and cross-assembly field-access graph is required.

The seam now constructs detached typed/semantic/capability member IR and
returns immutable test snapshots of explicit signature domains, state-carrier
requirements, selected defaults, direct `super` calls, and producer logical
keys. The hostile Kotlin fixture validates those snapshots in every lane, and
a test-owned CLR physicalizer validates corresponding GenericParam,
InterfaceImpl/MethodImpl, field, virtual-slot, and override facts. Prototype
members remain outside every class declaration, codegen ignores the snapshots,
and the structured CLI model remains unchanged until a real production slice
is authorized.

Producer physical-family construction now consumes those compiler facts
without a fixture-name ABI table. Fixture names remain scenario/body labels.
Lowered IR is mapped through a bounded structural carrier
grammar for built-ins, owner and method parameters, and arrays; owner-dependent
semantic array positions deliberately become `System.Array`. Exact constructor
records include independent primitive slots, and the real lowered static
default helper supplies its receiver-independent signature tail and masks.
Anything outside that grammar remains without a physical proof; `Unit` in a
parameter, a star/unsupported projection or classifier/carrier, or an unexpected
default-helper shape cannot be repaired by guessing `object`. Uniform role
families extend the compiler-selected base MethodDef name with a stable digest
of their logical override roots. Masked-default helpers use their declaration
key. State routes are joined through recorded transitive field reads/writes,
not source member names. The artifact rejects both physical MethodDef and C#
source collisions and never allocates a name only after a collision appears.
When external binding changes a consumer's roles or domains, its pre-binding
local signatures are invalidated and the producer artifact remains sole
physical authority. A hostile regression sentinel rewrites every diagnostic
producer source label in-memory and requires the resulting artifact to be
identical. Captured inner parameters whose pre-normalization slot domain does
not match the structural carrier remain without an exact proof. This is
still production-inert evidence, not authority to emit `C<T>`.

The field proof now uses one module-wide graph rather than a direct-body scan.
It includes functions, constructors, general function-access edges, field and
anonymous initializers, and lifted/nested producer helpers; each generic owner
projects its fields from that shared graph. Private helpers are strict nodes,
but an exposed semantic entry propagates semantic reachability through them.
The hostile write therefore records `writeUnsafe -> installUnchecked ->
<set-stored> -> stored`. Write values are now traced through typed/semantic
callable boundaries, call arguments, local definitions and assignments,
returns, and casts. A cast preserves its producer domain: exact `T -> Any? ->
T` through a private helper proves typed, while widened `T -> Any? -> T`
remains semantic. Any unsupported or source-free path remains unresolved and
cannot select typed storage. Non-private state remains cross-assembly
incomplete. Those are carrier facts, never owner admission.

The hostile test facade also now materializes the exact snapshot into a
temporary generic producer assembly, compiles a separate C# subclass/consumer,
and executes its typed, semantic, paired-output, one-state, GenericParam, and
MethodImpl contract on both supported runtimes. This closes the snapshot-to-CLR
test seam without making the production emitter or structured CLI consume the
prototype. The following architecture slice is Kotlin-produced subclass
override families and their producer/consumer binding identities—not an easy-
owner production rollout. The local subclass half is detached and explicit:
typed entries override typed entries, inherited semantic hooks remain separate
semantic overrides, and private dispatchers never override. External bases
initially remain blocked behind a producer logical-key requirement. A first
versioned, deterministic, producer-fingerprinted physical-family artifact now
resolves that exact obligation in the architecture test channel. It records
implementation/capability paths, arity, disposition, state requirements,
complete roles/reasons, selected MethodDef names, and slot dispatch; the whole
artifact must validate before typed and semantic bindings are exposed. Stale,
truncated, wrong-producer, duplicate, incomplete, and missing-member inputs are
rejected, and separately compiled C# subclasses execute the resolved family on
both runtimes. Production artifacts remain erased and contain no such record.
Version 3 further retains the exact implementation MethodDef owner, complete
slot-domain vector, and neutral structural signature for every typed, semantic,
dispatcher, direct-super, and masked-default entry. The dispatcher separately
names the exact non-generic capability MethodDef it implements. Structural
types preserve owner/method parameters, named generic instances, and nested SZ
arrays without IL spelling; the hostile `echo(Array<out T>)` family records
`!T[]` for its typed entry and `System.Array` for its semantic/capability path.
Producer reflection and a separately compiled record-driven C# consumer verify
the same identities on both runtimes. Broad candidate inputs reach a fixed
point across local override roots and are inherited from the producer record by
a separate consumer; a locally strict declaration cannot narrow that semantic
authority. Version 4 adds the exact target profile and open-TypeDef runtime
classification, admits only statically exact construction, and records every
constructor MethodDef/visibility/constructed owner and `this`/`base` edge.
Each selected state field now has exact paired typed/semantic read/write
MethodDefs and explicit boundary conversions; the record-driven C# consumer
uses those identities for its immediate base construction and state paths.
Runtime-selected/fallback construction remains unproven. Version 5 now maps
each exact producer open TypeDef to its existing KLIB classifier key, retains
logical type arguments only in KLIB, hides capability TypeDefs and physical
member-family details from Kotlin reflection, and records one logical callable
plus its selected typed/semantic-dispatch invocation entry. Exact closed/open
normalization and ancestry-based instance classification are distinct, and
capability or foreign subclass TypeDefs cannot normalize to the recorded
Kotlin classifier. Version 6 adds one ordered CLR GenericParam constraint row
for every producer parameter. The bounded external Kotlin-subclass physicalizer
now combines that decoded producer record with compiler-derived consumer
visibility, modality, exact constructor shape, override roots, and direct
`super` calls. The caller supplies only a distinct current-compilation TypeDef
path. The delegated constructor—not a member's declaring owner—selects the
immediate `Base<!T>` construction, and every child slot retains the exact
producer MethodDef name/signature/declaring owner. Fake overrides resolve to
their real KLIB declaration, matching slot domains cannot substitute for an
exact child constructor signature, delegation must forward every child
parameter positionally without transformation, and producer artifacts reject
a current-compilation type reference. Producer and child constraint rows must
be exactly equal in the bounded grammar; arity alone is insufficient. A public
open non-inner child with one base/constructor and no extra interface, field,
initializer, nested type, state, or non-fake member is the only admitted owner
shape. Inherited fake overrides/default helpers remain inherited. The
constructor contains only identity delegation and empty initializer
scaffolding. A record-generated C# grandchild proves multi-level typed/semantic
dispatch on both runtimes. This remains test-only; runtime exact/fallback
construction now has a separate finite consumer-site proof. The final
compilation supplies concrete runtime roots, while the producer record supplies
the unconstrained owner, capability, and public strict constructor. Exact
branches are statically rooted, already-nullable values normalize idempotently,
and one mandatory
`C<object>` route handles all unlisted types through the semantic capability.
Invalid nullable roots and constrained owners fail closed. The generated
factory contains no `MakeGenericType` or `Activator` closure.
Both CLRs execute the table. A later explicit, signed MSVC toolchain run is
warning-clean and completes the finite table's native link and execution.
Representative application comparison remains an acceptance gate.

Version 7 separates producer candidate classification from optional physical
family publication. Every logically bindable generic owner records its logical
owner key, arity, disposition, and complete constructor/member key sets; each
published family must match that catalog exactly. A metadata-fixed
`D<T> : C<T?>` candidate is serialized as a deterministic erased-only
exclusion, so consumers cannot mistake absence for permission to infer a CLR
family. This closes the architecture-level exclusion-recording proof, not the
future production DLL/KLIB binding migration.

The exact same compiler-record-driven finite factory now exports a pinned,
fingerprinted six-file net10 measurement bundle. One versioned hostile
workload executes exact and fallback construction, shared state, typed and
semantic arrays, multi-level dispatch, and delayed typed failure. JIT,
ReadyToRun, full trimming, and NativeAOT agree on its checksum and report
startup, throughput, allocation, peak working set, publish cost, and footprint.
Workload version 2 holds the child only after its protocol so even the fast
native process yields a live working-set sample. The explicit native route
validates and records a signed Microsoft linker and its CRT/SDK libraries; any
link or run failure leaves proof false. This is a reproducible bounded-corpus
baseline, not representative product evidence or authorization for production
`C<T>`. See
[`../archive/generic-owner-native-aot-measurement-2026-08-13.md`](../archive/generic-owner-native-aot-measurement-2026-08-13.md).

The next product prerequisite is now closed by one paired application corpus,
not by another handwritten benchmark. It captures the exact hostile Kotlin
multi-module source, the actual production-erased producer and Kotlin
consumer, a direct C# consumer/two-level subclass of that erased assembly, and
the snapshot-derived candidate producer/consumer. Arbitrary framework and user
structs, nullable and mixed state, arrays, method generics, and multi-level
dispatch execute on PSI/LightTree and both CLR profiles. The current direct C#
surface is recorded honestly: owner generics are erased to arity zero and
`object`/`System.Array`, while owner-independent method generics remain native.
Closed manifests fingerprint every product. Relative KLIB paths remove random
test-root leakage, and the cross-frontend audit requires exact CLR code/ABI,
non-KLIB resources, non-body KLIB content, binding records, and all downstream
products. Raw manifests retain the parser-specific IR body hash; the verifier
does not claim body-blob equality or replace a future semantic KLIB-body
canonicalizer. Framework products use deterministic Roslyn with explicit CLR
4 references and execute on CLR 4. This supplies the paired inputs for the
representative measurement gate; it is not itself representative performance
evidence or migration authority. See
[`../archive/generic-owner-application-corpus-2026-08-13.md`](../archive/generic-owner-application-corpus-2026-08-13.md).

The first bounded paired measurement now runs those inputs independently on
Framework CLR 4 and .NET 10 JIT, ReadyToRun, full trimming, and NativeAOT. One
checksum closes workload equivalence, but the current candidate takes
1.62–2.96 times the erased workload time and allocates 6.89–7.52% more. The
candidate's three typed versus 24 semantic regular routes make this evidence
against the current bridge cost, not against true CLR-generic identity itself.
The full-trim lane additionally forced canonical interface reimplementation on
a class which rebuilds inherited external MethodImpl bridges; the verifier now
pins those direct edges for CoreCLR, CLR 4, and ILLink. Because the candidate
is not a complete Kotlin product, no published-byte or end-to-end compile win
is claimed. See
[`../archive/generic-owner-paired-application-measurement-2026-08-14.md`](../archive/generic-owner-paired-application-measurement-2026-08-14.md).

The bounded semantic-route attribution is now complete. It proves that the
candidate's true generic owner still has compiler-required semantic object
state: direct typed value entry retains erased-equivalent boxing, while
compatible capability value entry adds one re-box per iteration. Allocation-
free reference and semantic-array capability paths remain materially slower,
isolating dispatch and runtime compatibility testing as well as boxing.
Owner-independent method generics remain near parity, and NativeAOT makes
typed arrays/compatible overrides competitive, so the evidence is against the
current capability/object-state mix rather than against CLR generics. The
equal-layout fallback struct prevents payload size from being misreported as a
representation win. Framework 4.8 and .NET 10 also differ materially on the
hostile failure route and remain independent gates. Keep production owners
erased. Representative applications on Framework 4.8 and every .NET 10
deployment lane are now the next reopening gate before selecting the atomic
migration. See
[`../archive/generic-owner-route-attribution-2026-08-14.md`](../archive/generic-owner-route-attribution-2026-08-14.md).

The same graph's `TYPED_STORAGE_PRODUCER_GRAPH_PROVEN` outcome now drives a
bounded one-field physicalization. Exact `HostileTypedStore<T>` access uses a
real `!T` field and bypasses the non-generic capability; strict widened/star
entry checks before mutation and widens or boxes after read. Int, non-trivial
struct, and nullable routes remove every per-iteration exact-path allocation on
Framework CLR 4 and all .NET 10 deployment modes. The capability still pays two
object-domain conversions plus a compatibility check, and large-struct exact
timing remains runtime-sensitive. This closes typed-storage causal feasibility,
not representative-product breadth or production owner admission. See
[`../archive/generic-owner-typed-storage-attribution-2026-08-14.md`](../archive/generic-owner-typed-storage-attribution-2026-08-14.md).

The next foundation removes handwritten route weights from the reopening
decision. A production-inert fixed-point census now follows generic-owner
receivers through constructors, aliases, branches, closed-call arguments,
returns, fields, casts, and lowered default helpers. Exact invariant public
signatures retain construction proof; star/projected/variant or unresolved
views require a capability. Separate consumers resolve only exact logical
member keys claimed by the decoded producer catalog and distinguish physical,
producer-erased, unrelated, and missing-capability outcomes. The hostile
separate corpus has 42 producer-owned static sites: 24 producer-erased, 13
exact typed candidates, four capability routes, and one missing capability.
The volatile publish/observe calls add two exact sites to the initial 40-site
checkpoint. These are structural sites, not dynamic frequency. The next
reopening work is to run this compiler-derived census over complete applications
and collect execution weights on Framework 4.8 and all .NET 10 deployment
lanes. See
[`../archive/generic-owner-call-route-census-2026-08-14.md`](../archive/generic-owner-call-route-census-2026-08-14.md).

That census now crosses the compiler/tool boundary without handwritten route
selection. Application bundle schema 2 fingerprints a canonical route artifact
whose records retain the original compilation site index and KLIB logical
member identity but omit diagnostic/physical names. PSI, LightTree, Framework
4.8, and net10 produce byte-identical route bytes. The hostile artifact has 42
records over indices 0 through 50; the nine gaps are unrelated external owners,
not renumbered producer sites. This creates the exact instrumentation join but
does not make the hostile application representative. The linked archive
records the initial 40-record checkpoint; schema 14 retains the grammar with
the two additional exact records. See
[`../archive/generic-owner-call-route-manifest-2026-08-14.md`](../archive/generic-owner-call-route-manifest-2026-08-14.md).

The first compiler-indexed execution profile closed that join for the bounded
hostile application. An explicit test-only recorder wraps the exact analyzed
call after single ordered receiver/argument evaluation and immediately before
invocation. Its exact per-site oracle includes one unexecuted typed site and
one site executed twice; aggregate totals cannot substitute for that identity.
See
[`../archive/generic-owner-call-route-trace-2026-08-14.md`](../archive/generic-owner-call-route-trace-2026-08-14.md).

That collection path is now suitable for large call counts. The instrumented
executable owns one private exact-sized `Int64[]`; every event performs one
linearizable `Interlocked.Increment`, and a post-`box()` flush atomically reads
the table and prints only visited sites. Trace schema 2 names the
`FINAL_FLUSH` protocol. PSI/LightTree and Framework 4.8/net10 retain identical
route/count bytes. The current schema-14 hostile vector has 42 producer plus
nine unrelated events; its two additions to the original 49-event checkpoint
are typed volatile publish/observe calls. All 34 normal bundle-file comparisons
remain byte-identical to the pre-feature baseline.
The workload must join its own workers before returning. Collection output is
O(visited sites), but the counter run still measures no performance: collect
representative route/state distributions independently, then time clean
erased/candidate products on Framework 4.8 and every .NET 10 deployment lane.
See
[`../archive/generic-owner-call-route-counter-flush-2026-08-15.md`](../archive/generic-owner-call-route-counter-flush-2026-08-15.md).

The first repository-owned application census now uses that path without the
hostile physicalizer. The test fixture stages the exact Kotlin/Native
`ArrayCopyBenchmark.kt` source as a declared build input and a bounded driver
executes `CustomArray<Int>.add` 512 times. Sixteen local static routes receive
5,664 events; every one is an exact typed-entry candidate on PSI, LightTree,
Framework CLR 4, and .NET 10. One unrelated external site contributes another
512 events. Route and count bytes agree across all four lanes. This does not
prove typed state: the source deliberately initializes `Array<T?>` by an
unchecked cast from `Array<Any?>`, so the compiler correctly selects semantic
array state while retaining typed-entry plus capability member roles.
`copyInto` now crosses either open `T[]` or that existing `System.Array`
capability without changing identity or Common range semantics. The result is
the first real call/state distribution, not candidate timing or representative
breadth; broaden the source set and add paired candidate/C# products before
the reopening decision. See
[`../archive/generic-owner-array-copy-application-census-2026-08-15.md`](../archive/generic-owner-array-copy-application-census-2026-08-15.md).

The second exact repository input is Kotlin/Native's recursive OctoTree. Its
25 local exact sites receive 5,941 events and nine semantic-capability sites
receive 3,096 events; PSI/LightTree and Framework 4.8/net10 retain identical
route/count evidence. The state result also makes the physical boundary more
precise. Direct `Array<T>` remains `System.Array` in an erased owner, while
`Array<Node<T>?>` truthfully uses `Node[]` because erased Kotlin `Node<T>` has
one declaration-stable CLR classifier. The exact initializer proves typed
candidate provenance without reifying `T`; `root` remains semantic and the
production `Leaf.value` remains object-backed. This removes avoidable array
capability traffic without changing owner identity. It still does not close
representative breadth, candidate/C# products, or timing. See
[`../archive/generic-owner-octo-tree-application-census-2026-08-15.md`](../archive/generic-owner-octo-tree-application-census-2026-08-15.md).

The projected generic-array join path now demonstrates the same incremental
rule at a public Common boundary. `Array<out T>.joinTo` retains `System.Array`
as its physical receiver so widened value views remain valid, then performs
one non-throwing `isinst T[]` to select an equivalent typed read loop when the
actual vector permits it. Exact `Int` loads remove one 24-byte box per element
on both Framework CLR 4 and .NET 10; the widened fallback remains unchanged.
This is private method-generic physicalization, not a stronger Kotlin cast,
reified owner, representative application result, or production `C<T>`
admission. Arbitrary projected-array user loops remain a separate shared-
lowering problem. See
[`../decisions/projected-generic-array-join-fast-path.md`](../decisions/projected-generic-array-join-fast-path.md)
and
[`../archive/projected-generic-array-join-fast-path-2026-08-15.md`](../archive/projected-generic-array-join-fast-path-2026-08-15.md).

The candidate planner now retains the exact state carrier needed to cross the
next product boundary. A bounded path-unbound tree composes owner parameters,
invariant Kotlin-owned generic classifiers, and arrays; classifier leaves use
pre-lowering logical producer keys and cannot bind until the artifact selects
their TypeDef paths. Missing paths, projections, unsupported classifiers, and
open nullable `T?` fail closed. In a real separate OctoTree producer this
records `Node<T>[]` for `Branch.nodes`, `T` for `Leaf.value`, and the structural
`Node<T>` candidate for semantic `root`. The producer census has 21 exact,
nine capability, and nine external static routes; four additional exact sites
belong only to the consumer. Schema 12 now serializes and binds the complete
four-owner recursive physical family. It includes atomic TypeDef/MethodDef
closure, private semantic root access, exact `Node<T>[]` initialization, and
the exact constructor parameter which initializes `Leaf.value: !T`. This
schema also fixes the Kotlin-sealed CLR shape before the product grows:
`Node<T>` is abstract rather than CLI-sealed and every base constructor is
`FamilyAndAssembly`, so only a derived TypeDef in the producer assembly can
invoke it. A decoded record first drove a real `Node<T>`/`Leaf<T>` C# producer;
external C# constructs and reflects `Leaf<int>` with true `T` state, while an
external subclass fails compilation on both runtime profiles. The same product
now adds final `Branch<T> : Node<T>`, both exact base/this constructors, and a
true private `Node<T>[8]` initializer on only the base root. External C# proves
the open `Node<T>[]` and closed `Node<int>[]` field plus empty and populated
constructor behavior. This remains production-inert: it changes no emitted
production field, KLIB/DLL schema, or public owner. The product now also emits
the recorded public abstract typed `Node.set` slot, ordinary non-final virtual
Leaf/Branch overrides on final TypeDefs, and the exact typed Leaf read/write
plus Branch read accessors over those same fields. Direct C# proves real base-
reference dispatch and field identity. The strict family now also emits each
recorded non-generic capability interface and private-final object-to-`!T`
dispatcher. Direct C# proves inherited and owner-specific routes reach Branch's
most-derived typed override, while incompatible input fails before state
mutation. The Leaf state capability now reads/writes the same true `T` field
through `object`, and the Branch state capability returns the same `Node<T>[]`
through `System.Array`. Direct C# proves boxing only at the Leaf object boundary,
incompatible pre-mutation failure, vector identity, and private/final
interface-map targets. The outer open Tree now materializes its public
constructor/members over the semantic object root. Its three state-to-child
calls are joined to the compiler census as semantic-capability routes, and
direct C# proves typed/capability state identity plus inherited dispatchers on
an external subclass. Whole-family metadata/reflection normalization is now
closed by a classifier-contextual inverse MethodDef join and an exhaustive raw
ECMA-335 reader over each separately compiled candidate. Context is required
because the hostile derived owner shares its base capability TypeDef and
dispatcher. The reader found and closed previously omitted Leaf/Branch
rendering MethodDefs which execution alone had hidden behind Object rendering.
The next gate composes this generic-owner family with the complete ordinary
declaration/body closure before any representative timing.
See
[`../archive/generic-owner-structural-state-carrier-2026-08-15.md`](../archive/generic-owner-structural-state-carrier-2026-08-15.md).

The existing physical member-signature grammar has also been corrected at its
non-exact carrier boundary. An unconstrained open nullable `T?` position uses
`object`, which represents both reference nullability and CLR's boxed nullable-
value form. An open-nullable or projected array position uses `System.Array`;
`!T[]` and `object[]` each reject Kotlin-valid vectors. Direct non-null `T`,
invariant `Array<T>`, and independent method-generic `Array<R>` retain their
native GenericParam forms. OctoTree `get(): T?`, a nullable-array constructor/
result pair, and the hostile C# producer/subclass projected-echo family prove
the boundary on Framework 4.8 and .NET 10. This changes only production-inert
records and their test physicalizer. See
[`../archive/generic-owner-nonexact-call-carriers-2026-08-15.md`](../archive/generic-owner-nonexact-call-carriers-2026-08-15.md).

The recursive callable grammar now shares one path-unbound tree with state.
It adds void, method-GenericParam, and explicit `System.Array` leaves without
adding a second classifier representation. Constructor/member/default-helper
signatures retain logical producer keys, enforce their slot and GenericParam
invariants before binding, and become physical records only after one complete
TypeDef-path selection. The separate OctoTree `nodes` getter proves typed
`Node<T>[]` and capability `System.Array`; its typed return binds identically to
the field and rejects a missing Node path. Unknown classifiers and generic
value classes remain unavailable. The field now independently retains an exact
fixed zeroed `Node<T>[8]` initializer recipe; ArrayCopy's unchecked object
vector explicitly fails that grammar. Physical-family schema 8 serializes
that bounded recipe with its exact base-delegating constructor roots and lets
it satisfy typed state's WRITE obligation without a fictitious setter. The
Schema 9 additionally represents exact producer-private typed identity state
methods without inventing a logical KLIB callable or reflection member, while
retaining the complete paired matrix whenever a semantic path or conversion
exists. Schema 10 records exact owner visibility/dispatch and member-slot
visibility, including protected semantic hooks and private/final explicit
capability dispatchers. Schema 11 completes the logical-keyed recursive
OctoTree family, adds exact positional constructor-to-state initialization,
and rejects phantom producer TypeDefs or MethodDef owners atomically. Schema
12 adds the abstract/non-CLI-sealed base plus `FamilyAndAssembly` constructor
rule and proves it with a record-driven true-`T` Leaf product, positive C#
consumer, and rejected external subclass. That same product now includes the
exact recursive Branch `Node<T>[]` carrier, fixed length-eight initializer,
both constructor edges, and open/closed direct C# evidence. Its typed callable
slice now consumes the exact abstract/override MethodDefs and typed identity
state accessors; final child TypeDefs retain the recorded non-final virtual
overrides. The matching strict capability interfaces and private-final
dispatchers now preserve most-derived typed override routing with an object
input carrier. The matching Leaf/Branch state-access dispatchers now preserve
one true generic state while exposing the recorded `object`/`System.Array`
capability carriers. The outer Tree now consumes that family through its
recorded semantic object root, exact member/capability slots, and compiler-
census call routes. Exact raw TypeDef/GenericParam/InterfaceImpl/MethodImpl/
field/method rows and classifier-contextual callable normalization close the
whole-family metadata/reflection gate. Schema 13 then projects every direct
field, records declaration-independent exact carriers and init-only state, and
adds private-final non-KLIB implementation MethodDefs outside capabilities and
logical reflection. The same decoded family now executes the complete original
OctoTree algorithms with real depth/actual state and no bounded scenario-body
substitute. The complete candidate now has a closed schema-3 paired corpus
through PSI/LightTree and Framework 4.8/.NET 10. At 200,000 iterations its
aggregate candidate/erased ratios are 2.41x Framework, 1.05x JIT, 1.29x
ReadyToRun, 1.23x trimmed, and 1.06x NativeAOT, with 23.0%-27.3% more
allocation. Route attribution separates expensive typed/capability crossings
from clusterization wins on JIT/NativeAOT. Rendering is explicitly
lowering-confounded because the candidate is generated C#, not a complete
Kotlin product. A later audit also found that its generic equality used CLR
`EqualityComparer<T>.Default` instead of Kotlin Runtime equality, so these
performance values are superseded and cannot select the public owner ABI.

That material classification defect is now closed. A null literal is
representation-neutral only for a proven local non-value generic-class
reference carrier with exact invariant arguments; bare unconstrained `T?`,
`C<T?>`, external/projected/unresolved classifiers, and missing producer keys
remain semantic. Every non-null constructor/write still requires physically
typed provenance. The separate OctoTree therefore stores its private root as
`Node<T>` and uses private typed identity access internally, while the
non-generic semantic export remains on the same object graph. The exact
five-lane rerun initially appeared strongly favorable, but the large-only
audit found that generated C# had replaced Kotlin open-`T` equality with CLR
`EqualityComparer<T>.Default`. The corrected product first called the same
Runtime `AreEqual(object, object)` as production Kotlin and pinned signed-zero
and NaN behavior. Its aggregate candidate/erased ratios were 1.68x Framework,
0.91x JIT, 1.10x ReadyToRun, 0.92x trimmed, and 0.89x NativeAOT, with
72.3%-76.6% more allocation. Runtime surface 38 now supplies the selected
production-used generic entry: it removes the open-`T` receiver box for
semantically safe value types while retaining the universal reference, null,
floating, and nullable-floating path. It invokes constrained left-biased
`Object.Equals`, not `IEquatable<T>` or a comparer; a deliberately conflicting
struct runs in all five deployment lanes. Final aggregate candidate/erased
ratios are 1.65x Framework, 0.87x JIT, 1.02x ReadyToRun, 0.84x trimmed, and
0.73x NativeAOT. Managed allocation excess falls to about 34%, while
NativeAOT allocates 11.25% less. Capability allocation and Framework dispatch
remain material, so equality is closed without selecting the owner ABI.
See
[`../archive/generic-owner-path-unbound-member-signatures-2026-08-16.md`](../archive/generic-owner-path-unbound-member-signatures-2026-08-16.md).
The initializer proof is recorded in
[`../archive/generic-owner-state-initializer-recipes-2026-08-16.md`](../archive/generic-owner-state-initializer-recipes-2026-08-16.md).
Schema 8 is recorded in
[`../archive/generic-owner-physical-state-initializers-2026-08-16.md`](../archive/generic-owner-physical-state-initializers-2026-08-16.md).
Schema 9 is recorded in
[`../archive/generic-owner-producer-private-state-access-2026-08-16.md`](../archive/generic-owner-producer-private-state-access-2026-08-16.md).
Schema 10 is recorded in
[`../archive/generic-owner-physical-visibility-dispatch-2026-08-16.md`](../archive/generic-owner-physical-visibility-dispatch-2026-08-16.md).
Schema 11 is recorded in
[`../archive/generic-owner-complete-octo-tree-family-2026-08-17.md`](../archive/generic-owner-complete-octo-tree-family-2026-08-17.md).
Schema 12 is recorded in
[`../archive/generic-owner-sealed-construction-closure-2026-08-17.md`](../archive/generic-owner-sealed-construction-closure-2026-08-17.md).
The Branch product is recorded in
[`../archive/generic-owner-octo-tree-branch-product-2026-08-17.md`](../archive/generic-owner-octo-tree-branch-product-2026-08-17.md).
The typed callable product is recorded in
[`../archive/generic-owner-octo-tree-typed-callables-2026-08-17.md`](../archive/generic-owner-octo-tree-typed-callables-2026-08-17.md).
The strict capability product is recorded in
[`../archive/generic-owner-octo-tree-strict-capability-2026-08-17.md`](../archive/generic-owner-octo-tree-strict-capability-2026-08-17.md).
The state-access capability product is recorded in
[`../archive/generic-owner-octo-tree-state-capabilities-2026-08-17.md`](../archive/generic-owner-octo-tree-state-capabilities-2026-08-17.md).
The outer-root product is recorded in
[`../archive/generic-owner-octo-tree-root-product-2026-08-17.md`](../archive/generic-owner-octo-tree-root-product-2026-08-17.md).
The metadata/reflection product is recorded in
[`../archive/generic-owner-octo-tree-metadata-reflection-product-2026-08-17.md`](../archive/generic-owner-octo-tree-metadata-reflection-product-2026-08-17.md).
The schema-13 ordinary-body closure is recorded in
[`../archive/generic-owner-octo-tree-ordinary-body-closure-2026-08-17.md`](../archive/generic-owner-octo-tree-ordinary-body-closure-2026-08-17.md).
The paired schema-3 measurement and route attribution are recorded in
[`../archive/generic-owner-octo-tree-paired-measurement-2026-08-17.md`](../archive/generic-owner-octo-tree-paired-measurement-2026-08-17.md).
The typed private-root proof and five-lane remeasurement are recorded in
[`../archive/generic-owner-octo-tree-typed-private-root-2026-08-17.md`](../archive/generic-owner-octo-tree-typed-private-root-2026-08-17.md).
The Kotlin-equality correction and superseding five-lane measurement are
recorded in
[`../archive/generic-owner-octo-tree-kotlin-equality-measurement-2026-08-17.md`](../archive/generic-owner-octo-tree-kotlin-equality-measurement-2026-08-17.md).
The production-used lower-boxing equality helper and final five-lane evidence
are recorded in
[`../archive/generic-open-equality-lower-boxing-2026-08-17.md`](../archive/generic-open-equality-lower-boxing-2026-08-17.md).
The one-state concurrency/memory-model condition is now closed at schema 14.
The hostile owner keeps its ordinary `stored: T` as true `T` storage while its
owner-dependent volatile `published: T` uses one reference-safe volatile
`object` field. Typed initialization/write widening and read narrowing are
explicit record facts; typed and semantic capability paths share that field,
and failed capability writes cannot mutate it. Multi-threaded handoff runs in
separately compiled products on Framework 4.8 and CoreCLR. This does not select
the production owner ABI or implement the parked public concurrency/atomic
surface. Continue with the next complete hostile migration condition rather
than an easy-owner rollout or a micro-optimization. See
[`../archive/generic-owner-one-state-memory-model-2026-08-17.md`](../archive/generic-owner-one-state-memory-model-2026-08-17.md).

The direct C# PropertyDef condition is now closed at physical-family schema 15.
Prototype accessors carry a compiler-derived getter/setter kind and physical
Property name. The family artifact binds that name and physical type to the
existing typed-entry MethodDefs through their logical getter/setter KLIB keys;
it introduces neither a second property key nor a second state carrier.
Semantic hooks and capability dispatchers cannot become ordinary property
accessors. The complete OctoTree product now publishes `depth: int`,
`value: T`, and `nodes: Node<T>[]` as real properties, with hidden distinct
backing-field names selected after Roslyn exposed the otherwise-colliding C#
source shape. Separate consumers use property syntax on Framework 4.8 and
.NET 10, while the raw metadata inspector verifies Property/PropertyMap and
getter/setter MethodSemantics rows. Duplicate names, partial codec accessors,
foreign/capability accessors, mismatched signatures, and fake-override
republication fail closed. This is still production-inert: nullable-reference
annotations, overload/name collision policy, broad property semantics, and
the atomic public owner migration remain open. Continue with the next hostile
migration condition rather than rolling out easy owners. See
[`../archive/generic-owner-direct-property-surface-2026-08-17.md`](../archive/generic-owner-direct-property-surface-2026-08-17.md).

The direct nullable-reference surface is now closed at physical-family schema
16. Nullable transforms are per-position compiler facts, captured from live IR
before the generic-owner prototype becomes detached and preserved through the
complete producer record. They are not a property of the physical type: exact
`T`, nullable semantic `object`, `Node<T>?`, and `Node<T>?[]` retain distinct
truthful shapes even where CLR signatures otherwise share a carrier. Property
and identity state joins require exact transform equality with their MethodDef
accessors, and the codec rejects unknown or structurally incomplete vectors.
The record-driven C# producer compiles under `#nullable enable` with warnings
as errors. A raw metadata consumer verifies effective nullable attributes on
fields, properties, returns, and parameters on Framework 4.8 and .NET 10. The
work also corrects the existing export encoding of an unmarked exact CLR type
parameter from oblivious flag `0` to Roslyn-compatible non-null flag `1`; this
adds no CLR constraint and therefore does not prohibit nullable type arguments.
Open Kotlin `T?` still selects the nullable semantic `object` carrier. This
closes the nullable direct-surface condition, not base/interface transforms,
the collision/overload matrix, broad property semantics, or the atomic public
owner migration. Continue with the next complete hostile condition. See
[`../archive/generic-owner-nullable-surface-2026-08-17.md`](../archive/generic-owner-nullable-surface-2026-08-17.md).

The broad direct-property condition is now closed at physical-family schema
17. A covariant `var exposed: @UnsafeVariance T` deliberately permits a
widened Kotlin view to place an incompatible value into the owner's one
semantic `object` field. C# still receives the natural virtual `T` property,
but the producer record now separately states the complete semantic routing:
a compatible capability write invokes the typed property and observes its C#
override; an incompatible write invokes the protected semantic hook without
narrowing; widened read invokes the paired raw hook; and typed read performs
the delayed checked cast/unbox. An external C# subclass overrides both sides
on Framework 4.8 and .NET 10. No shadow state, wrapper, early rejection, or
invented fallback result is admitted. Getter/setter route enums are joined to
the recorded member roles, PropertyDef accessors, and state access paths;
wrong or unknown routes fail closed. The Kotlin source corpus proves the same
hazardous sequence on today's erased backend, while the external route census
adds one exact and three semantic calls with no new missing capability. This
closes broad property semantics, not overload/name collisions, base/interface
nullable transforms, or the atomic public owner migration. Continue with the
next complete hostile condition. See
[`../archive/generic-owner-broad-property-routing-2026-08-17.md`](../archive/generic-owner-broad-property-routing-2026-08-17.md).

The abstract broad-property condition is now closed at physical-family schema
18. An abstract covariant property cannot derive a raw read obligation from a
local backing field because neither body nor field exists yet. The planner now
records `ABSTRACT_BROAD_PROPERTY_OBLIGATION` on its getter whenever the paired
abstract setter has a general widened domain. The physical family consequently
requires an abstract typed getter/setter and abstract protected semantic
getter/setter together; its concrete private-final capability dispatchers call
the typed property for compatible values and the semantic hooks otherwise.
Roslyn rejects a concrete typed-only C# subclass and accepts a complete one on
Framework 4.8 and .NET 10.

This work also closed the more dangerous derived-state bug found by that
oracle. A concrete Kotlin override previously inherited the semantic method
roles after its field had already been classified as true `T` storage.
Inherited logical semantic obligations now enter reachability before field
classification, so the overriding body and private setter taint the one field
to `object`. The Kotlin erased oracle and the record-driven candidate both
prove incompatible widened write, raw read, delayed typed failure, and
recovery. The closed census adds two exact and three capability property calls
without a missing route. Schema validation rejects mismatched abstract
dispatch, a concrete family claiming the abstract reason, and an unknown
serialized obligation. This closes abstract broad properties, not overload/
generated-name collisions, base/interface nullable transforms, or the atomic
public owner migration. See
[`../archive/generic-owner-abstract-broad-property-obligation-2026-08-17.md`](../archive/generic-owner-abstract-broad-property-obligation-2026-08-17.md).

The overload/generated-name condition is now closed at physical-family schema
19. Two valid Kotlin overloads retain the same natural C# name and distinct
typed CLR parameter types, while their semantic hooks deliberately erase those
parameters to the same `object` signature. Compiler-generated semantic and
capability names are therefore derived unconditionally from a digest of the
complete sorted logical override-root set. A later overload cannot rename an
already published slot, and an override in another assembly derives the same
family identity. Masked-default helpers use the logical declaration key for
the same reason. The producer record remains the sole physical binding
authority; consumers do not reconstruct suffixes.

Schema validation now applies both CLR MethodDef uniqueness and C# source
overload rules. Return type, instance/static distinction, and nullable
metadata cannot rescue otherwise identical C# methods, while methods,
properties, and fields cannot occupy one source name on the same owner. A
separate C# subclass overrides one natural typed overload and its corresponding
protected semantic hook; exact Kotlin calls observe the typed override and an
incompatible constructed owner observes the semantic override on Framework
4.8 and .NET 10. Raw metadata, reflection, and interface-map oracles pin both
natural overloads and all distinct hidden slots. This is compiler-owned ABI
allocation, not a public `DotNetName` annotation: explicit user-facing export
naming remains a separate proposal. Base/interface nullable transforms and the
atomic public owner migration remain open. See
[`../archive/generic-owner-overload-family-names-2026-08-17.md`](../archive/generic-owner-overload-family-names-2026-08-17.md).

The direct-supertype condition is now closed at physical-family schema 20.
Every admitted generic owner records one exact `TypeDef.BaseType` and all direct
`InterfaceImpl` rows, including their complete constructed type expression and
physical nullable-reference transform. Producer-owned generic interface
TypeDefs are part of the same catalog. Base-delegating constructors must target
the recorded base, dispatcher-owning TypeDefs implement their semantic
capability exactly once, inherited dispatchers do not duplicate that interface,
and method-free owners do not acquire an empty capability.

The hostile pair distinguishes representable nested reference nullability from
unrepresentable conditional open nullability. A derived owner with
`ReferenceBase<TypedStore<T>?>` and
`Marker<AbstractPropertyStorage<T>?>` has one truthful construction for every
`T` and is admitted. Live IR retains its logical `1,2,1` nullable vector; the
bound ancestry record retains Roslyn's actual physical `0,2,1` root sentinel.
A raw metadata reader pins both layers on Framework 4.8 and .NET 10. In
contrast, `Derived<T> : Base<T?>` cannot choose one fixed CLR base for both
value and reference substitutions. Its producer classification records the
blocked classifier and owner-parameter index and keeps it erased instead of
inventing a false TypeSpec. The recursive metadata inspector now consumes the
same recorded ancestry rather than deriving it from constructors. This closes
the final named hostile representation condition; production owners remain
erased until the complete family and representative application evidence select
one atomic public-ABI migration. See
[`../archive/generic-owner-direct-supertype-metadata-2026-08-17.md`](../archive/generic-owner-direct-supertype-metadata-2026-08-17.md).

The following atomic public-owner checkpoint records **no-go for now**. The
schema-20 products prove the intended CLR representation, but the complete
candidate is still generated C# rather than normal Kotlin-emitted CIL with its
self-describing DLL/KLIB binding. Temporary uncommitted rehearsal probes showed
why changing only TypeDef arity is not a migration. A global switch makes the
existing erased Runtime/Stdlib bodies, bridges, static owners, open-nullable
carriers, and classifier tests disagree with constructed owners. Even a
bounded `Box<T>` switch reaches an owner-dependent covariant-return bridge
formed for the erased contract and fails converting concrete `int32`/`string`
to open `!0`.

The target therefore retains the erased production epoch while ordinary
language and real-application breadth continue to grow. Reopen the owner ABI
only for one complete rehearsal which materializes typed/semantic families
before ordinary bridges, changes emission and the physical binding epoch with
all consumers, compiles Runtime/Stdlib and representative applications as real
Kotlin-produced `C<T>` products on both profiles, repeats every deployment
measurement, and proves the exact inverse rollback. A per-owner switch or a
`DotNetName`-style naming annotation cannot satisfy this boundary. See
[`../archive/generic-owner-atomic-cutover-checkpoint-2026-08-17.md`](../archive/generic-owner-atomic-cutover-checkpoint-2026-08-17.md).

The selected ordinary-stdlib breadth interlude is now closed by the complete
Map min/max adapter family. The next major work is the one coherent rehearsal
named by that checkpoint: replace the generated C# physicalizer with normal
Kotlin lowering/CIL emission, migrate Runtime/Stdlib and self-describing
bindings in the rehearsal epoch, execute representative products on both
profiles, and prove the exact inverse rollback. Do not select another
erased-owner leaf family or an easy Sequence/Map owner switch before this
rehearsal reaches its next go/no-go result.

The first incremental checkpoint of that coherent rehearsal is now Kotlin-
emitted rather than C#-physicalized. Behind one test-only epoch flag, admitted
ordinary owners become real `C<T>` TypeDefs, producer-proven ordinary state is
stored as `!T`, object-domain semantic state remains on the same owner, and
typed/semantic/capability member families, inner-owner parameters, generic
value-class carriers, reflection, and ABI-38 separate-library bindings compose
on bounded hostile products. Production emission remains erased. The next
slice is not another owner selection: it must close the complete source-built
Runtime/Stdlib graph, whose remaining failures are later canonical/capability
joins, covariant-return adapters, and intrinsic field-shape requirements. Only
then do the representative products and exact inverse rollback advance the
atomic go/no-go checkpoint.

That source-built Stdlib rehearsal has now reached the generic-interface
boundary at `AbstractMap<K, V>.keys`. The natural owner wants a truthful
`Set<K>`, while production still maps every Kotlin-owned `Set<T>` construction
to the same non-generic TypeDef. Treating those identical physical carriers as
a sufficient direct override would only make the old representation constraint
permanent. The next rehearsal slice therefore reopens generic interfaces as a
general compiler family, not as a collection exception: a natural CLR `I<T>`
and its non-generic declaration-semantic capability are sibling interfaces.
The capability is used only when a projected, widened, value-variant, or
classifier-only Kotlin view has no honest constructed CLR interface; ordinary
foreign implementations need implement only `I<T>`.

The first compiler-emitted tranche of that family is green on Framework 4.8
and .NET 10. Under the one test-only generic-owner epoch, a structural public
top-level covariant producer with one abstract no-input `T` result emits the
natural CLR `I<out T>` and one non-generic declaration-semantic capability.
Exact final constructions stay natural; stars, projections, owner parameters,
open arguments, and widened value-type views select the capability. Kotlin
implementations, calls, boxing, and same-object identity compose across
separate assemblies. The producer's real public authoring manifest drives the
supported Roslyn generator, so partial C# implementations author only the
natural member while Kotlin widened calls still reach that member on both
profiles.

The first required general multi-member root is now green without a stdlib
exception. A structural covariant owner may combine exactly one abstract
no-input direct `T` producer with one or more abstract owner-independent
no-input non-null primitive queries. The natural CLR interface retains `!T`
and primitive results; its semantic sibling widens only the producer result to
`object`. ABI/runtime surface 43 publishes the owner-independent-query member
role, and the C# authoring contract requires an ordinary partial C# class to
write only the natural members. Kotlin exact and widened calls plus identity
execute through a separate producer on Framework 4.8 and .NET 10. This closes
the direct `Iterator<T>` member grammar, not the stdlib owner graph.

The constructed owner-dependent result needed by
`Iterable<T>.iterator(): Iterator<T>` is now green without a stdlib exception.
A structural covariant root may return one already-admitted covariant
interface constructed over its own parameter. The natural result remains the
truthful `Iterator<!T>` construction. Its declaration-semantic slot is
`object`, so one boundary can preserve either Kotlin's sibling capability or
an ordinary C# `Iterator<T>` with no compiler capability and no wrapper. Exact
nested state remains typed. Only a concrete state slot which can actually
receive a Kotlin-legal, CLR-unnameable nested covariant view becomes semantic,
and a dedicated exact-receiver semantic-result route reads it without globally
widening either generic owner.

The CLR-legality gate for the first broader input composition is now closed.
Both C# compilers reject direct or nested `T` inputs on one covariant
interface. A name-independent executable proof instead composes one covariant
read view, one invariant exact-input view, and one non-generic semantic view.
The same object supplies all views. Compatible calls retain typed virtual
dispatch, an incompatible fixed-barrier candidate returns the authoritative
result, and an incompatible nested candidate reaches the semantic body without
wrapping or changing identity on Framework 4.8 and .NET 10.

ABI/runtime surface 45 now records the third physical identity atomically. The
family artifact names the invariant exact TypeDef and distinguishes fixed-
barrier direct input from nested semantic input. Missing, unsolicited, aliased,
or arity-mismatched exact owners fail closed. No consumer derives this TypeDef
from a name, and no existing family silently acquires it. The separate-
consumer resolver now binds that producer-recorded path in the producer
assembly with invariant parameters of the recorded arity and exposes it as the
exact type-mapping view. This closes identity reconstruction, not TypeDef
emission or member binding.

Those roles are now encoded in the generic-interface planner and materialized
by normal Kotlin lowering/CIL emission. The producer-recorded invariant exact
TypeDef inherits the natural covariant view and owns only the members which are
illegal on that view. Its typed nested signatures continue to name the natural
generic family, while the non-generic capability alone accepts Kotlin-wide
object-domain arguments. Kotlin implementations retain producer-proven `!T`
state and implement the natural, exact, and semantic MethodImpls on one object
across separate compilation. A later semantic lowering may not degrade these
proven typed signatures.

The real C# authoring manifest now selects a declared or exact typed slot per
member and records the exact owner. The supported Roslyn generator adds both
compiler-owned interfaces to a partial implementation, while authored C# names
only the ordinary typed members. That path executes on Framework 4.8 and .NET
10, including reference covariance and a value-type exact construction. The
Runtime built-in collection mapping remains explicitly excluded, so this
checkpoint cannot accidentally half-migrate `Collection` or `Set`.

The exact-input export and ordinary precompiled/non-partial C# boundary is now
closed. A Kotlin implementation exposes the ordinary typed class member as its
sole natural C# entry; its object-domain semantic hook is a separate compiler
ABI member and cannot degrade that public signature. A raw C# class which
implements only the natural covariant interface may supply the compatible
exact-input operation as an ordinary public typed method. Kotlin selects its
unique natural construction and the exact concrete parameter signature, so an
adjacent `object` overload cannot win. A missing method fails closed, and a raw
class is never treated as if it supplied an arbitrary hidden semantic body.
ABI/runtime surface 46 owns this bounded fallback.

The first property composition is now closed without a built-in, package, or
declaration-name exception. ABI/runtime surface 47 records an abstract read-
only owner-independent non-null primitive property getter separately from a
method query. The natural covariant interface and Kotlin implementation retain
one real CLR Property row and public typed getter; the non-generic semantic
capability owns only its compiler method slot. Generated and precompiled/non-
partial C# implementations author ordinary property syntax. Widened Kotlin
dispatch preserves the capability fast path and otherwise invokes the raw C#
getter through its unique natural construction, boxing only at the local
reflection join before returning the primitive result.

The optional fixed-barrier direct input is now materialized in that same
general family. One canonical-only Runtime generic-interface parent may
supply the authoritative `SpecialBridgeMethods` policy, but only when the
direct member resolves to that exact parent. It remains ordinary KLIB/CLR
inheritance rather than a fabricated reified-family ancestor. ABI/runtime
surface 48 carries the policy to Kotlin capability dispatch, generated C#
adapters, and a bounded precompiled-C# fallback. Exact calls and owner state
remain `!T`; only widened candidate input crosses `object`, and a wrong shape
returns the upstream result before the typed body is called. See
[`../archive/reified-generic-interface-fixed-barrier-composition-2026-08-22.md`](../archive/reified-generic-interface-fixed-barrier-composition-2026-08-22.md).

The collection dependency graph now has its first source-built Runtime
foundation. ABI/runtime surface 49 adds covariant natural `Iterator<T>` and
`Iterable<T>` TypeDefs alongside their erased semantic identities. In the
generic-owner rehearsal, compiler-emitted implementations carry the natural
and erased MethodImpl bundles on one object, retain `!T` state, and preserve
the constructed `Iterator<T>` result of `Iterable<T>`. Exact calls use the CLR-
typed route; only a Kotlin-legal construction which the CLR cannot name crosses
the local erased fallback.

An ordinary non-partial C# class independently implements either natural
interface with only `HasNext`, `Next`, or `GetIterator`. The executable proof
requires neither generator nor compiler capability. A loader failure also
closed a general composition hole: a non-generic compiler semantic capability
must not inherit a constructed generic interface in either the emitter's
structural graph or its final `implements` list. The real implementation object
owns that construction directly. Framework 4.8, .NET 10, both frontends,
explicit-off products, and the preceding exact-interface family are green.
The final aggregate exits zero; direct XML audit covers 190 freshly written
suites/2,291 tests with zero failures, errors, or skips, plus the unchanged
green six-test `dotnet.ir` model root for 191 suites/2,297 tests target-wide.
See
[`../archive/runtime-reified-iterator-foundation-2026-08-23.md`](../archive/runtime-reified-iterator-foundation-2026-08-23.md).

That atomic collection gate is now closed at ABI/runtime surface 50.
`Collection<out T>` and `Set<out T>` are natural covariant Runtime interfaces
whose output/query members remain directly CLR-typed. Their invariant exact
siblings own only `contains(T)` and `containsAll(Collection<T>)`; the existing
arity-zero interfaces remain semantic capabilities and are not parents of the
natural C# surface. Kotlin implementations keep one identity and true `!T`
fields. Exact calls and CLR reference covariance are natural, while value-type
widening and incompatible nested inputs cross semantic routing only at the
operation which needs it.

A separately compiled non-partial C# implementation names only the natural
interface and ordinary public input methods. Compatible `containsAll` calls
that C# method directly. An incompatible physical construction iterates the
original elements and applies the Kotlin `contains` barrier, so an empty mixed
collection still returns `true`; no wrapper or compiler interface is imposed
on C#. Open iterator results join through `object` until their next actual
member use selects the semantic or natural view. PSI/LightTree and Framework
4.8/.NET 10 Kotlin/C# execution are green. See
[`../archive/runtime-reified-collection-set-family-2026-08-23.md`](../archive/runtime-reified-collection-set-family-2026-08-23.md).

ABI/runtime surface 51 now closes the next complete read-only dependency
family. Natural covariant `ListIterator<T>` extends `Iterator<T>`. Natural
covariant `List<T>` extends `Collection<T>` with typed indexed reads, both
`listIterator` overloads, and recursive `subList`; its invariant exact sibling
owns candidate inputs and index queries. Kotlin implementations retain real
`!T` fields, while Kotlin-only value-type widening crosses the existing
semantic view only at the operation which needs it.

Natural-only C# implementations name no exact or semantic compiler interface.
The general foreign dispatcher resolves overloads by name plus arity and uses
the recorded fixed wrong-shape result (`false` or `-1`) before typed invocation.
Canonical and natural nested results join without a wrapper or identity change.
See
[`../archive/runtime-reified-list-family-2026-08-23.md`](../archive/runtime-reified-list-family-2026-08-23.md).

ABI/runtime surface 52 selects the smallest closed mutable dependency
foundation. Natural covariant `MutableIterator<T>` extends `Iterator<T>` with
declaration-independent `remove()`. Natural covariant `MutableIterable<T>`
extends `Iterable<T>` and narrows its iterator result to
`MutableIterator<T>`. Kotlin implementations preserve one identity and true
`!T` fields; a value-type widening uses the existing semantic capability only
at the operation whose constructed view the CLR cannot name.

The general foreign dispatcher now admits declaration-independent Unit
members, allowing a natural-only C# `Remove()` without a compiler interface.
The narrowed mutable iterator and inherited read-only iterator results are two
ordinary CLR slots. Portable C# implements the public narrowed member and one
explicit natural base-interface forwarder; no generator or erased/exact/
semantic contract is involved. See
[`../archive/runtime-reified-mutable-iterator-foundation-2026-08-23.md`](../archive/runtime-reified-mutable-iterator-foundation-2026-08-23.md).

ABI/runtime surface 53 selects the next smallest complete Common family.
Invariant natural `MutableListIterator<T>` inherits the two existing natural
parents and owns typed `Next(): T`, `Set(T)`, and `Add(T)` slots on one CLR
construction. It needs no exact sibling. Exact/open operations and the Kotlin
implementation's `!T` field stay typed; star reads and input-projected writes
cross the semantic capability only for the individual unnameable operation.

An ordinary non-partial C# implementation names only the natural invariant
interface. The existing unique-construction dispatcher admits its star and
projected operations without a generator or wrapper. This remains subject to
the production gates for static protocol description, trimming, NativeAOT,
and tooling visibility; a passing rehearsal does not by itself freeze an
unrestricted reflective ABI. See
[`../archive/runtime-reified-mutable-list-iterator-2026-08-23.md`](../archive/runtime-reified-mutable-list-iterator-2026-08-23.md).

ABI/runtime surface 54 selects invariant natural `MutableCollection<T>` over
the existing natural `Collection<T>` and `MutableIterable<T>` graph. Exact
element operations remain typed. Each bulk input is a physical CLR generic
method `<U : T>(Collection<U>)`; this admits Kotlin value-type widening without
making the owner, nested input, or implementation field an object carrier.
The source/KLIB member remains non-generic.

The compiler represents this as a general relative-generic-input member fact.
Ordinary Kotlin and C# calls use the natural generic interface directly. A
capability-free C# object behind an unnameable projected Kotlin receiver uses
the bounded unique-generic-method fallback only for that operation. Runtime
and Kotlin implementation MethodDefs both retain `U : T`; C# needs no partial
class, generator, exact/semantic interface, wrapper, or adapter. The earlier
inherited Collection candidate-input protocol and this projected fallback
remain trimming, NativeAOT, static-protocol, performance, and tooling gates.
See
[`../archive/runtime-reified-mutable-collection-2026-08-23.md`](../archive/runtime-reified-mutable-collection-2026-08-23.md).

ABI/runtime surface 55 selects invariant natural `MutableSet<T>`. It extends
the existing natural `Set<T>` and `MutableCollection<T>` parents and redeclares
the mutable iterator and complete mutation family. Kotlin emits MethodImpls for
both MutableSet and MutableCollection bulk slots; CLR interface maps prove the
diamond. Ordinary C# names only `MutableSet<T>`, and one generic bulk method
satisfies both natural contracts.

The family reuses the relative-generic-input grammar without a declaration
switch and retains a real `!T` field. Widened read-only `Set<T>` candidate
inputs remain on the earlier exact/semantic or bounded foreign path; the
invariant child does not retroactively make CLR covariance accept inputs. See
[`../archive/runtime-reified-mutable-set-2026-08-23.md`](../archive/runtime-reified-mutable-set-2026-08-23.md).

ABI/runtime surface 56 selects invariant natural `MutableList<T>` after the
post-surface-55 dependency recomputation. Its List, MutableCollection,
MutableListIterator, and recursive MutableList result dependencies are now
closed. Direct positional mutation and results remain typed. Indexed and
non-indexed bulk inputs reuse one structural relative-generic rule which finds
the unique nested `Collection<T>` input instead of assuming parameter zero.

The bounded natural-only foreign grammar now permits that one owner-dependent
input beside declaration-independent parameters and a Unit,
declaration-independent value, or same-`T` result. This covers the positional
`add`/`set` family without a
MutableList switch; name plus complete arity distinguishes its overloads.
Inherited covariant List candidate inputs retain their existing exact/semantic
path and are not copied onto the invariant child. The provisional ABI/Runtime
epoch advances to 56 to reject the missed surface-54/55 skew. See
[`../archive/runtime-reified-mutable-list-2026-08-24.md`](../archive/runtime-reified-mutable-list-2026-08-24.md).

Next recompute the smallest complete Common dependency family after surface
56. Keep Map, defaults, multiple owner parameters, and any still broader
overload family on their current mappings unless that dependency proof selects
one atomically. The natural CLR route and typed state remain the default; a
semantic capability is an evidence-backed escape hatch, not the first
implementation choice.

That recomputation selects `Map.Entry<out K, out V>` as ABI/runtime surface 57,
not Map itself. Entry has no parent dependency and its two read-only properties
form the smallest complete multiple-owner-parameter family. Admission is a
general bijective producer-property-vector rule: two or more covariant
nullable-`Any`-bounded parameters, exactly one abstract direct getter per
parameter, and no other member. The natural CLR interface keeps both `!0` and
`!1`; the semantic sibling widens only an operation whose Kotlin view has no
honest CLR construction.

The Runtime natural Entry TypeDef remains nested under the accepted arity-zero
Map metadata container. It is not placed under a not-yet-selected `Map<K,V>`:
entries are independent values, and using the erased container prevents this
leaf prerequisite from silently making a partial Map ABI decision. This
physical placement is Runtime mapping data, while the compiler admission and
member treatment remain declaration-name independent. The completed gate
proves two typed implementation fields, reference covariance, value-type
widening, stars/open arguments, separate Kotlin products, ordinary non-partial
C# implementation, one object identity, and exact arity-two reflection on both
Framework 4.8 and .NET 10. It also closes coherent warning-bearing `as`/`as?`
construction checks for this multi-parameter owner. MutableEntry, Map, mixed
variance, inputs, defaults, and inherited multiple-parameter families remain
unselected. See
[`../archive/runtime-reified-map-entry-2026-08-24.md`](../archive/runtime-reified-map-entry-2026-08-24.md).

The post-surface-57 recomputation selected and completed
`MutableMap.MutableEntry<K,V> : Map.Entry<K,V>` as surface 58 before Map. It is
the smallest dependency-closed child and forces the first invariant
multiple-owner-parameter mutation without introducing Map's much larger
mixed-variance contract. The structural rule requires two or more invariant
nullable-`Any`-bounded parameters, exactly one identity-substituted covariant
producer-property parent of equal arity, and exactly one abstract direct
input/output member whose non-null argument and result are the same owner
parameter. Reordering, fixed arguments, changed input/result parameters,
additional parents or members, defaults, and properties reject the family.

The natural child retains all owner parameters, inherits the parent's typed
getters, and keeps its direct mutation as `!V SetValue(!V)`. Its semantic
capability inherits the parent's capability and adds only the operation-local
`object SetValue(object)` slot. The Runtime TypeDef remains nested under the
accepted arity-zero MutableMap metadata container; this gate must not select a
speculative `MutableMap<K,V>`. Kotlin implementations must keep independent
typed key/value state, and ordinary non-partial C# must implement only natural
Entry getters plus SetValue. Map, MutableMap, mixed variance, nullable
input/output mutation, properties, defaults, and deeper multi-parameter
inheritance remain separate gates.
The complete checkpoint also closes the general paired-input and inherited
relative-input regressions exposed by the full Runtime family: natural closed
and method-generic entries remain typed, widened calls use the separate object
entry, identical inherited MethodImpl slots coalesce, and late semantic
reachability cannot erase producer-proven typed fields. The strict aggregate
is 191 suites/2,333 tests, all green. See
[`../archive/runtime-reified-mutable-map-entry-2026-08-24.md`](../archive/runtime-reified-mutable-map-entry-2026-08-24.md).

The post-surface-58 recomputation selected and completed `Map<K,out V>` as
surface 59. It is the next dependency-closed Runtime family now that Set,
Collection, and Map.Entry have natural generic identities. The structural
grammar admits exactly one invariant and one covariant parameter, no parents,
two invariant-parameter barriers (Boolean membership and nullable covariant
lookup), one covariant-parameter Boolean barrier, one owner-independent
primitive property, one primitive query, and three read-only constructed
results covering K, V, and their ordered pair. Those result classifiers must
already be published covariant natural families. This is not a Map-name rule.

The natural `Map<K,out V>` owns typed key input plus natural Set/Collection/
Entry results. Its invariant exact sibling owns `ContainsValue(!V)`, which CLR
variance forbids on the natural interface. `Get(!K): object` is an intentionally
honest member-local carrier for Kotlin `V?`: unconstrained CLR V cannot use one
signature for nullable references and `Nullable<V>`. Kotlin implementations
still retain independent `!0`/`!1` state; neither Map nor nested generic state
is globally erased. Stars, projections, value-type widening, and ordinary
natural-only C# fallback select semantic dispatch only for the unnameable
operation and preserve one object identity.

The gate proves all eight members, typed fields and result constructions,
exact/widened/star calls, wrong-shaped Common policies, coherent BK-1 checks
over both arguments (including an explicit cast used as a lookup receiver),
separately compiled Kotlin products, and ordinary sealed non-partial C# on
Framework 4.8 and .NET 10 under both FIR parsers. The complete eleven-family
rehearsal and production-erased inverse selections are green. The strict
target aggregate covers 191 suites/2,337 tests with zero failures, errors, or
skips. It deliberately does not select `MutableMap<K,V>`. See
[`../archive/runtime-reified-map-2026-08-24.md`](../archive/runtime-reified-map-2026-08-24.md).

The first dependency recomputation after surface 59 ran the actual source-built
Stdlib product before admitting another declaration and closed its first
plan-to-materialization gap. Exact same-owner calls whose private result member
requires an object-domain read now bind directly to that member's private
semantic hook. The compiler does not add a capability interface slot, widen
visibility, pass through the checked natural wrapper, or introduce a Sequence/
collection exception. A focused Iterator-shaped regression fails at the
planner when that direct binding is removed and executes on both profiles when
it is present.

The full source product consequently passes all ten former missing private
routes. The next final-routing failure is closed at the resolver authority
boundary: producer-recorded generic-owner member families, result carriers, and
input entries are queried only for metadata-deserialized external declarations.
A local or generated function remains under the current compilation's plan and
is rejected before public ABI key computation, even when lowering has made its
type graph unsuitable for the Kotlin mangler. A focused hostile unit proof
covers the complete function-fact query surface without a Comparator, accessor,
or stdlib exception.

The source product now reaches CIL materialization. Its first repeated
super-interface blocker is closed by correcting an initial misclassification:
`SuspendFunctionN` is a logical builtin mapped to the continuation-shaped
Runtime `FunctionN+1`, not a producer-recorded external TypeDef. Class and
interface validation now use one Runtime-interface carrier predicate, removing
the duplicated callable checks which let the two validators diverge. An
explicit generic suspend-callable class proves its synthesized semantic
capability and continuation-shaped execution without a source-name or stdlib
exception.

The post-representation covariant-return blocker is closed. The inherited
physical MethodDef remains authoritative for an open nullable class result,
and the IR-only ExactFunction declarations bind to their real Runtime generic
slots. Typed source methods remain the natural entries and private final
MethodImpl adapters bridge only the physical mismatch. The two rules have
independent ablation proofs and retain the production-erased inverse. Retained
foreign CLR MethodDefs remain a stronger authority than nullable/flexible
Kotlin import views and are substituted through their real construction.

The erased physical-owner edge is closed at same-module bootstrap
reconstruction: arity-zero owners retain closed edges but never link logical
owner `!n` parameters absent from metadata. The source product now reaches its
normal unsupported-shape census.

Its first semantic-to-natural conversion family is now closed structurally. A
direct body on a physically final non-generic class may receive one paired
object-input compiler entry when exactly one nested admitted-interface
parameter is broader on the canonical slot and no fixed wrong-shape policy owns
that input. Natural Kotlin/C# calls retain the original MethodDef and body; the
canonical bridge calls the copied object-input body without first narrowing to
one closed CLR construction. A custom separate-compilation owner proves exact
and widened calls plus identity on both runtimes, and the production-erased
inverse remains unchanged. The full target aggregate covers 191 suites/2,351
tests with zero failures, errors, or skips. The source product loses the former
`SuppressedExceptionList` family and at that checkpoint reached the independent
open generic-owner self-view conversion in `AbstractMutableList.indexOf`.

That downstream typed-route degradation is now closed structurally. When
Common inference widens a covariant inline receiver and the inliner introduces
immutable aliases, those compiler-owned slots may retain the exact natural CLR
generic construction already supplied by the producer. Alias reads propagate
that physical fact through the compiler-generated chain instead of
materializing a false `I<object>` sibling. The rule applies only to compiler
temporaries and for-loop iterators, rejects object or semantic-capability
producers, and does not narrow source or mutable locals. A hostile proof keeps
an inlined generic-owner self-view typed while a mutable source
`Producer<Any?>` still alternates value and reference implementations on the
same semantic carrier. The source-built Stdlib loses
`AbstractMutableList.indexOf` and at that checkpoint reached the independent
semantic-body self conversion in `AbstractMutableList.removeAll`. That boundary
still had to be resolved without an AbstractMutableList, MutableList,
collection, stdlib, or member-name exception: a semantic body must not
fabricate `MutableList<object>` from an exact open `this`, but an ordinary
source-level widened mutable view must remain semantic.

That semantic-body boundary is now closed structurally. A generic
extension helper may preserve an owner-dependent method argument derived from
the exact current hook receiver only when every other occurrence is output-
only, including the callback shape `(T) -> Boolean`. A helper which accepts a
`T`, invariant `C<T>`, or any other input occurrence remains in the semantic
domain. Compiler-generated callable implementation classes also avoid an
otherwise unused second generic-owner capability: their source-visible Kotlin
identity is `FunctionN`, and route analysis must prove a direct semantic class
call before adding a class capability. This lets a callback keep its one
construction-relative exact interface and semantic captured state without
imposing both `ExactFunctionN<T, R>` and `ExactFunctionN<object, R>` on one
TypeDef.

The source-built Stdlib consequently loses both
`AbstractMutableList.removeAll` and `retainAll`. Its first remaining owner
failure was `AbstractMutableMap.remove`, where blanket semantic-body remapping
degraded an independently exact iterator/entry result chain to
`MutableMap.MutableEntry<object, object>`.

That result-chain boundary is now closed structurally. A parameterless producer
on the semantic hook's exact current receiver retains its natural CLR
construction, and the proof propagates only through immutable locals with the
same invariant type and further parameterless producer calls. Input-bearing
calls, `super`, mutable/source-widened locals, and nested variant results which
require a semantic route remain excluded. The broad key candidate therefore
stays semantic while the iterator, entry, key, and value remain exact. The
compiler Runtime graph also mirrors the already-emitted
`MutableMap.MutableEntry<K,V> : Map.Entry<K,V>` declared edge, so the physical
base view can be recovered without inventing a semantic conversion.

The source-built Stdlib no longer reports `AbstractMutableMap.remove`. Its
first remaining owner failure is `AbstractMutableMap.get_keys`, where the
semantic getter constructs an anonymous view object whose constructor expects
`AbstractMutableMap<object, object>` even though current `this` correctly
remains `AbstractMutableMap<!K, !V>`. Classify that captured-self/anonymous-
object construction next without an AbstractMutableMap, keys, anonymous-class,
package, or stdlib exception. Do not weaken exact current-receiver authority or
globally remap generated owner constructions merely to admit this one body.

The first general split-result experiment is now implemented for a
producer-recorded direct `T?` interface result: the natural MethodDef returns
the typed `T` payload and appends `[out] bool& isNull`, while Kotlin IR/KLIB and
the declaration-semantic sibling retain `T?` and `object` respectively. Exact
constructed calls use the natural slot and reconstruct the nullable value at
the call site. Payload selection occurs before applying the outer nullable
marker, so `T = Int?` remains `Nullable<Int32>` and is not collapsed to
`Int32`. Ordinary C# implements the natural method directly; the optional
partial-class generator consumes manifest schema 9, calls the same typed
method, and joins to null only in its semantic bridge. The rule is structural,
producer-published, and independent of interface, member, package, Map, or
stdlib names.

This closes Framework 4.8 and .NET 10 execution, both FIR parsers, separate
Kotlin products, reflected `[out] bool&`, ordinary non-partial C#, and the
Roslyn generator path. It does not yet select Map lookup: that member combines
an owner-dependent key input, a fixed Kotlin barrier, and the nullable result,
whereas the admitted first family permits only declaration-independent regular
inputs. Extend the same convention to that composition only after its own
general proof. Trimming and NativeAOT remain freeze gates, not premises for
globally retaining the object-result representation. Fields and ordinary
generic state remain a separate representation decision.

The physical choice is also closed over a transparent same-product covariant
subinterface fixpoint. `Child<out T> : Parent<T>` remains a real `Child<T>` and
reuses the parent's capability and member family; it does not acquire a second
semantic interface. Both Kotlin and generated partial C# child implementations
execute through exact child views and widened parent views without changing
identity. This is one general shape rule and contains no Map, Set, Sequence,
stdlib, package, or declaration-name exception.

Reified generic-interface MethodDefs also re-enter the ordinary covariant-
return lowering. A class member whose inherited result is narrower than the
substituted natural slot receives one typed MethodImpl; equal signatures keep
the direct natural route. The prior rule which excluded every Kotlin generic
interface remains correct only for production-erased owners.

Production remains erased. The external-parent ABI gate is now closed without
changing the structural admission rule. Assembly B may declare a transparent
`Child<out T> : Parent<T>` above the admitted root in assembly A. The fixpoint
retains A as the semantic provider, ABI 38 publishes both its assembly and
TypeDef path, and downstream consumers reconstruct exactly that capability.
B emits only the natural covariant `Child<T>` edge: no erased child, duplicate
capability, local alias, or state is introduced. Missing producer capability
assemblies reject the selected self-describing library graph before emission.
Kotlin exact/widened calls and direct generated C# root/child implementations
execute through A -> B -> C on PSI and LightTree, .NET 10 and Framework 4.8.

The independent-interface intersection gate is now closed, including the
first child-owned member. `Child<out T> : Primary<T>, Secondary<T>` remains a
natural CLR `Child<T>`. A member-free child implements one memberless
non-generic capability which inherits both root capabilities. If the child
adds one abstract no-input `T`-result member, that same general fixpoint gives
it a child capability which inherits the roots and owns exactly one new
object-result slot. Inherited members remain on their root families; no slot,
body, state, or wrapper is copied into the child.

The roots and child may share one producer or the child may be compiled in B
over roots from A. A consumer reconstructs the capability graph from logical
KLIB supertypes and producer-recorded physical identities; no ABI schema
addition or name inference is needed. Exact Kotlin calls use the natural child
MethodDef, widened child calls use its one semantic slot, and root calls retain
their respective capabilities. Kotlin and generated partial C# implementations
execute all routes on the same object on PSI and LightTree, .NET 10 and
Framework 4.8. The C# manifest records only the member declared by each
interface, so the generator composes inherited contracts without duplicating
their member families.

The first independent input-bearing gate is now closed without turning the
capability into the normal path. A structural public
`Consumer<in T> { fun consume(value: T) }` emits a natural CLR
`Consumer<in T>.consume(!T)` and one semantic `consume(object)` slot. Exact
Kotlin implementations store one `!T` field, exact `Consumer<int>` calls stay
natural, and reference-only `Consumer<object> -> Consumer<string>` uses CLR
contravariance directly. Because CLR variance does not convert value-type
constructions, Kotlin's legal `Consumer<Any?> -> Consumer<Int>` view is carried
by the same object's capability and boxes only the semantic argument. A small
provenance check preserves the stronger exact source construction instead of
classifying every `Consumer<Int>` declaration as semantic.

The rule composes across a separate Kotlin implementation assembly and the
producer-recorded input-slot family. The public authoring manifest retains
`in` variance, natural `void consume(!0)`, and semantic
`void consume(object)`. Generated partial C# implementations author only
`consume(object)` or `consume(int)` on their selected natural construction;
Kotlin semantic calls reach both bodies without changing identity on either
profile. This remains a source-generation contract for the input adapter, not
support for arbitrary precompiled or non-partial CLR consumers.

The no-input covariant producer now has a narrower, language-neutral foreign
fallback. The natural `Producer<T>` and its compiler semantic capability are
siblings, so ordinary precompiled/non-partial CLR code implements only the
natural interface. A broad Kotlin boundary carries the same object as
`object`: capability-bearing Kotlin/generated objects dispatch directly,
while a foreign object resolves and caches exactly one closed natural
construction. Zero constructions fail as a cast and multiple distinct
constructions fail as ambiguous; neither enumeration order nor a wrapper may
select a view. A real `Producer<*>` field retains identity, and reflection's
invocation wrapper is removed before an authored exception escapes. This proof
executes on Framework 4.8 and .NET 10 JIT.

The adjacent classifier and cast gate is now closed for that same admitted
producer family. Kotlin `is Producer<*>`, nullable and negated tests, and
smart-cast member use accept either the capability or any natural closed
construction. They remain classifier-only. Warning-bearing parameterized
`as` and `as?` instead share a recursive Kotlin-aware argument-subtyping
predicate under breaking entry BK-1. Thus a natural `Producer<int>` succeeds
as `Producer<Any>` in both forms, retains the same object even though CLR
value-type variance cannot name `Producer<object>`, and fails as
`Producer<String>` at the cast boundary. Nested covariant producer arguments
follow the same rule. Multiple constructions still pass the star/classifier
check, but a capability-free foreign object remains ambiguous when a broad
member call must choose one. The interface vector is cached per runtime type,
and no wrapper or fictitious constructed generic view is created. It does not
yet authorize foreign input/member families, trimming, or NativeAOT.

The first classifier-derived callable boundary is now closed without globally
erasing exact-looking APIs. `safeView(Any?): Producer<String>?` retains its
logical KLIB result but conservatively publishes CLR `object`; the cast result
carrier does not fabricate a constructed CLR identity. ABI 39 extends the
producer-owned function-carrier record with distinct semantic-capability and
object kinds per signature slot. The separate consumer uses that record
through FIR's synthetic safe-call local, retains object identity, and invokes
a compatible plain foreign producer. An incompatible foreign construction now
returns null before that boundary. Alongside it,
`exactView(Producer<String>): Producer<String>` remains a natural
`Producer<string>` API. C# reflection and both Kotlin frontends prove this on
Framework 4.8 and .NET 10. The bounded producer proof currently accepts one
authoritative classifier-derived return; arbitrary control-flow results,
fields, and unproven input graphs remain fail-closed gates.

ABI 40 closes the paired final input boundary. A function declared as
`same(Producer<String>, Any?)` or `read(Producer<String>)` retains its natural
typed MethodDef and direct source body. The producer additionally publishes a
stable compiler-owned MethodDef whose selected parameter is `object`; only a
call carrying classifier-derived foreign provenance targets it. The ABI record
contains the exact physical name and parameter index, so a separate consumer
does not guess from the logical signature. FIR's carrier-neutral
`CHECK_NOT_NULL` and one immutable alias preserve that provenance. Exact C#
calls remain ordinary `Producer<string>` calls; a compatible plain foreign
producer crosses the alternate entry as the same object. A future CLR-
unnameable but Kotlin-compatible view, such as `Producer<int>` viewed as
`Producer<Any>`, uses that object carrier rather than pretending to implement
`Producer<object>`.

The first hard nested-storage gate is closed by construction substitution, not
global owner erasure. `Box<T>(var value: T)` retains one open `!T` field.
`Box<Int>`, `Box<String>`, and `Box<Producer<String>>` close it as `int`,
`string`, and `Producer<string>`. Only the concrete logical
`Box<Producer<Any?>>` closes as `Box<object>`, because the Kotlin view may be
the same physical `Producer<int>`, `Producer<string>`, or an ordinary foreign
producer and no `Producer<object>` construction contains those possibilities.
Identity, mutation, and semantic dispatch cross that object slot without a
wrapper or shadow state. The same shape is consumed from a separate KLIB and
reflected from C# on both profiles. This is a structural admitted-owner rule,
not a `Box`, `List`, or `Producer` name exception.

The construction-stability proof now extends beyond the universal covariant
`Any?` argument. It asks whether any of the eight admitted signed Common CLR
value carriers is a proper Kotlin subtype of the logical argument. Therefore
`Producer<Int> -> Producer<Comparable<Int>>` selects `Box<object>` even though
the target argument itself maps to the reference construction
`IComparable<int>`. The paired `Producer<Cat> -> Producer<Animal>` control
retains `Box<Producer<Animal>>`; reference-only CLR covariance is not
unnecessarily erased. The predicate is Kotlin-type-system based and cached,
not a `Number`/`Comparable` or declaration-name exception.

The dual contravariant nested construction is now closed. A Kotlin object
which naturally implements `Consumer<Any?>` may legally be viewed as
`Consumer<Int>`, but CLR variance does not convert `Consumer<object>` to
`Consumer<int>` because constructed-generic variance applies only to reference
arguments. The concrete enclosing `Box<Consumer<Int>>` therefore becomes
`Box<object>` and crosses the same object's capability only at `consume`.
Conversely, `Consumer<Animal> -> Consumer<Cat>` is reference-only CLR
contravariance, so `Box<Consumer<Cat>>` and its `Consumer<Cat>` field remain
typed. Exact natural locals stay on `I<T>` while only compiler-proven Kotlin
implementations may select their sibling capability; an arbitrary C# `I<T>`
implementation is never assumed to implement it.

This gate also makes representation-aware interface routing authoritative over
an earlier conservative owner route and keeps the natural interface MethodDef's
dispatch receiver typed. The proper-value-subtype predicate is cached in every
rehearsal emitter, including a separate consumer with no locally owned
capability, so a producer cannot publish `Box<object>` while that consumer
reconstructs a false typed return. Epoch-off emitters do not construct this
type-system/cache. The rules contain no `Box`, `Consumer`, stdlib, or
declaration-name exception.

The nested open-argument gate now carries the same decision through one
generic MethodDef. A factory over `Producer<T>` or `Consumer<T>` accepts the
open interface value as `object`, constructs its concrete `Box<object>`, and
publishes the enclosing open result as `object`. More importantly, an identity
boundary over `Box<Producer<T>>` or `Box<Consumer<T>>` is `object -> object`:
that single MethodDef can therefore accept both an existing exact
`Box<Producer<string>>` and an already semantic `Box<object>` without changing
either object. Calls on the object-carried box use its existing class
capability; calls on a value read from it enter the interface capability or
the admitted ordinary-producer fallback only at the operation. No wrapper,
shadow state, or fabricated invariant construction is introduced.

The widening is structural and stops at the open boundary. Closed exact and
reference-only constructions remain typed, the open `Box<T>` TypeDef still has
one `!T` field, and the negative control `<T>(Box<Box<T>>) -> Box<Box<T>>`
retains `Box<Box<!!T>>`. Thus unrelated stable nesting and `List<T>` state are
not erased merely because the compiler supports semantic variant views.

The first invariant gate is now closed for a public top-level interface with
one unbounded invariant parameter and one abstract no-input member returning
that parameter. Exact `InvariantProducer<int>`/`InvariantProducer<string>`
calls are natural CLR calls. A star parameter is `object` and uses the existing
capability-or-unique-natural-construction producer dispatcher only at the
read. Because declaration-invariant owners have no legal sibling widening,
`<T>(Box<InvariantProducer<T>>) -> Box<InvariantProducer<T>>` remains the
fully typed `Box<InvariantProducer<!!T>>` MethodDef. This is the explicit
non-contagion control: admitting semantic variant views does not erase stable
invariant nesting or the enclosing `!T` field.

An ordinary non-partial C# implementation of the natural invariant interface
crosses both same-product and separate-KLIB star reads. The Roslyn authoring
tool consequently ignores a class whose Kotlin contracts are all admitted
invariant owners whose broad operations have the unique-construction fallback;
generated partial capability adapters remain required when any admitted
variant contract needs them.

Use-site projection is now closed as its own boundary rather than being
mistaken for declaration invariance. Kotlin may view
`InvariantProducer<String>` as `InvariantProducer<out Any?>`, but the CLR
cannot convert the same invariant construction to
`InvariantProducer<object>`. The projected callable carrier is therefore
`object`, with capability-or-unique-natural selection delayed until the
producer operation. When that projected value is materialized inside
`Box<InvariantProducer<out Any?>>`, only this concrete nested construction
becomes `Box<object>`. The `Box<T>` TypeDef and field remain typed, exact/open
invariant nesting remains `Box<InvariantProducer<!!T>>`, and an ordinary
non-partial C# implementation retains identity through the projected call and
box. An exact constructor origin is proof of its complete already-selected
physical construction; a downstream generic capability fallback may not
replace the outer owner with its non-generic capability merely because one
nested logical argument is projected.

The first mutable/broader invariant member family closes without changing
that priority. `InvariantCell<T>` has exactly one abstract `T` result and one
abstract `T` input/`Unit` member. Its normal CLR and C# contract is the single
natural invariant `InvariantCell<T>`; exact/open calls, the Kotlin
implementation's `!T` field, and `Box<InvariantCell<!!T>>` stay typed. A star
or output-projected read and an input-projected write cross the non-generic
capability only for that operation. An ordinary non-partial C#
`InvariantCell<string>` or `InvariantCell<object>` needs no hidden interface:
the same cached foreign fallback invokes the uniquely selected natural member
with zero or one argument. `Box<InvariantCell<out Any?>>` is still the construction-local
`Box<object>` hostile case and does not contaminate other boxes or generic
state. Runtime surface 40 owns the argument-bearing dispatcher; the original
producer entry remains as an ABI-compatible wrapper.

The equivalent exact property family is now closed without changing that
representation. `InvariantPropertyCell<T> { var value: T }` emits one natural
invariant interface with a real CLR `Property<T>` row, `!T` getter/setter
slots, and a `!T` implementation field. Star/out reads and in writes use the
same operation-local capability-or-unique-natural-construction path. Exact/open
nesting stays `Box<InvariantPropertyCell<!!T>>`; only the projected construction
is `Box<object>`. An ordinary non-partial C# implementation supplies one normal
auto-property. The manifest proves that the natural getter and setter share one
property name while neither semantic slot fabricates a Property row.

That invariant root may now contain one or more complete abstract mutable
properties. Each property remains an independent natural CLR `Property<T>` row
with `!T` getter/setter slots, and a Kotlin implementation retains one physical
`!T` field per property. Projected operations cross the semantic boundary per
accessor, not by replacing the owner or its state with an object representation.
The authoring analyzer permits an ordinary non-partial C# implementation only
when the complete manifest partitions into exact abstract getter/setter pairs
by property name. This does not widen method, mixed-member, defaulted,
inherited, nullable, covariant, or constrained multi-property shapes.

One inheritance edge now composes that same proof. An exact
`Child<T> : Parent<T>` whose root and child each declare one admitted mutable
`T` property remains a natural CLR generic hierarchy. The child owns only its
new Property row and two semantic slots; the parent Property/capability slots
remain inherited. A concrete Kotlin implementation retains two `!T` fields,
and an ordinary non-partial C# child supplies two auto-properties without a
compiler interface. Exact/open nesting remains `Box<Child<!!T>>`; projected
parent/child operations retain their operation-local boundary and only the
materialized projected box becomes `Box<object>`. FIR fake property overrides
are not declarations and cannot be copied into child ABI.

The first input-bearing composition is also closed above that exact property
root. `ConsumerChild<T> : PropertyCell<T>` owns one natural `Consume(!T)`
method and inherits the parent's Property row. Its capability owns one object-
input slot and inherits the parent capability, while a Kotlin implementation
retains one `!T` field. Exact/open child access remains natural; an input-
projected call alone uses the object receiver boundary. Ordinary non-partial C#
string/object implementations supply the property and method with no compiler
interface. The authoring analyzer recognizes the child-owned one-consumer
manifest fragment only when its constructed CLR child inherits a bound
complete producer manifest, rather than demanding a local producer already
supplied by the parent contract. A standalone invariant consumer retains its
generated object adapter.

One further consumer edge is now closed as an explicit bounded depth proof.
`ConsumerGrandchild<T> : ConsumerChild<T> : PropertyCell<T>` stays a natural
CLR generic hierarchy with one Property row at the root, one `Consume(!T)`
method per descendant, one Kotlin `!T` state field, and a 2-to-1-to-1 inherited
capability chain. Exact/open access remains typed and only the projected
secondary operation has an object receiver. Ordinary non-partial C# string/
object grandchildren implement the property and two methods. The authoring
tool finds the transitive complete producer root. Admission reconstructs the
exact first child and permits one further direct consumer; it is deliberately
not unbounded recursion.

The bounded family now also crosses three Kotlin producer DLLs. `lib.dll`
owns the property root, `middle.dll` owns the first consumer, and `leaf.dll`
owns the second consumer. External admission joins the KLIB declaration shape
with full-arity producer records and exact member-family evidence rather than
reapplying a local-file ownership predicate. Natural and semantic TypeDefs
remain in their declaring assemblies; no slot is copied downstream.

ABI 41 now normalizes those predicates into one versioned published-family
contract. Local declaration analysis and external ABI decoding produce the
same family kind, root/parent relation, identity parameter mapping, bounded
depth, declared roles, and capability binding for one admission consumer.
The external index validates Class, capability, member-family, and parent-
contract closure atomically. `hasReifiedGenericInterface` remains a cheap
existence query, not proof of a particular family. See
[`../archive/generic-interface-published-family-contract-2026-08-20.md`](../archive/generic-interface-published-family-contract-2026-08-20.md).

ABI 42 extends the physical half of that contract with the complete non-
generic interface closure of every generic-class capability. This is the
universally valid portion of the owner relationship: for example, every
construction of `C<T> : Iterator<T>` may expose the erased Kotlin `Iterator`
TypeDef through its capability, but no non-generic capability may claim one
arbitrary constructed imported CLR interface. The producer derives and sorts
that closure; separate consumers rebuild assignability solely from the
published record. A hostile widened `C<Int> -> C<Any?>` `for`-loop proves that
the ordinary `Iterator` calls introduced by `ForLoopsLowering` work locally
and across a producer DLL without `C<object>` or owner erasure. See
[`../archive/generic-owner-capability-superinterface-closure-2026-08-20.md`](../archive/generic-owner-capability-superinterface-closure-2026-08-20.md).

This does not admit arbitrary multi-member interfaces. The structural gates
are exactly one producer, one producer plus one consumer method, or one or more
complete mutable properties with that same producer/consumer shape, plus the
exact one-level single-property child above, one direct consumer child above
the same single-property root, one further exact consumer edge, and one exact
covariant child adding one abstract read-only `T` property above an exact
single-property covariant parent. Open-nullable properties, defaulted or
multiple read-only properties, broader mixed method/property bundles,
defaults, overloads, constraints, intersections, changed arguments, multiple
parents, and deeper inheritance remain excluded. The authoring tool applies
the same exact manifest-shape test before deciding that a non-partial C#
implementation needs no generated capability.

That gate must preserve the now-explicit operation boundary. Star/classifier
tests such as `is Producer<*>` ask only whether the logical classifier is
present. Warning-bearing parameterized `as` and `as?` use one Kotlin-aware
argument-subtyping predicate and differ only on mismatch: classified throw or
null. `Producer<Int> -> Producer<Any>` therefore succeeds in both forms;
`Producer<Int> -> Producer<String>` fails in both forms. Every success
preserves the original object and its semantic carrier. The deliberate safe-
cast incompatibility with the Kotlin specification is limited to BK-1 in the
breaking-change ledger and must not leak into ordinary variance, projections,
or warning-free operations.

The first reified generic-interface default gate is now closed for one
contravariant input member. The natural `I<T>` MethodDef remains the only
ordinary C# entry. Framework uses the recorded top-level digest-named helper
and generated natural-slot forwarder; .NET 10 uses the DIM. Exact and
value-type-narrowed Kotlin views retain one object and one body, while Kotlin
and ordinary C# overrides of the natural member are observed through the
semantic route. The generic helper is top-level because C# cannot source-name
a type nested inside a generic interface; non-generic helpers remain nested.
See
[`../archive/reified-generic-interface-defaults-2026-08-20.md`](../archive/reified-generic-interface-defaults-2026-08-20.md).

The first hostile default-inheritance chain is also closed across three
products. A real generic Kotlin `OpenDefaultConsumer<T>` in a second DLL
overrides that external default. A non-partial C# subclass of the
`<object>` construction overrides only the natural member; exact and
value-type-narrowed Kotlin calls both reach it without generated source or a
C#-authored semantic member. The inherited Kotlin body is not invoked and the
receiver retains one identity on both profiles and frontends. This required no
new physical ABI: valid contravariant input remains a checked conversion at
the operation boundary followed by ordinary virtual dispatch. See
[`../archive/reified-generic-interface-default-hostile-inheritance-2026-08-20.md`](../archive/reified-generic-interface-default-hostile-inheritance-2026-08-20.md).

The first default-property gate is now also closed for a covariant read-only
root. Its natural `Property<T>` is the sole ordinary C# API; Framework uses the
recorded helper and .NET 10 the getter DIM. The non-generic semantic capability
remains method-backed compiler ABI. Authoring tooling therefore groups
property syntax by the physical Property locator and emits an explicit method
adapter only for a method-backed semantic slot. Both adapters converge on one
C# source property or the same Kotlin helper/DIM, so exact and widened reads
retain one body and one object. See
[`../archive/reified-generic-interface-default-property-2026-08-20.md`](../archive/reified-generic-interface-default-property-2026-08-20.md).

The first owner-plus-method-generic default gate is now closed for the exact
covariant root `<R>(R): T` with one unconstrained method parameter. The natural
and semantic interface MethodDefs both retain method-generic arity one; only
the owner-dependent result becomes `object` on the semantic slot. Framework's
helper retains owner `T` plus method `R`, while .NET 10 keeps the natural DIM.
The C# authoring bridge forwards the same `R` to an inherited helper/default
or an ordinary C# generic-method override, so exact and widened calls retain
one body and one object across separate compilation. See
[`../archive/reified-generic-interface-method-generic-default-2026-08-20.md`](../archive/reified-generic-interface-method-generic-default-2026-08-20.md).

The same exact `<R>(R): T` family is now admitted as an abstract root. A
generic Kotlin implementation in a second product and an ordinary C#
implementation each supply only the natural generic method. Separate exact
and Kotlin-widened consumers reach that same method and preserve identity;
the compiler or authoring generator supplies the semantic capability without
making it part of normal C# source. See
[`../archive/reified-generic-interface-abstract-method-generic-2026-08-21.md`](../archive/reified-generic-interface-abstract-method-generic-2026-08-21.md).

The first constructed method-constraint gate is now closed for the abstract
root `<R : Consumer<R>>(R): T`, where the bound owner is independently proven
as the admitted one-member contravariant consumer root. Natural and semantic
MethodDefs both retain the exact `Consumer<!!R>` GenericParamConstraint and
every local, external, or implementation bridge remaps it to that MethodDef's
own method parameter. C# authors still implement only the natural generic
method; authoring tooling compares the source and metadata constraints alpha-
equivalently by method-parameter ordinal rather than requiring the two Roslyn
symbols to be identical. See
[`../archive/reified-generic-interface-constrained-method-generic-2026-08-21.md`](../archive/reified-generic-interface-constrained-method-generic-2026-08-21.md).

That same direct self-bound is now closed for a Kotlin interface default. Its
natural and semantic slots and the portable helper all retain
`Consumer<!!R>`; the helper keeps owner `T` before method `R`. A concrete
Framework implementation maps its closed helper-forwarder result to the
natural `I<int>` slot after owner substitution, while .NET 10 retains the DIM.
Ordinary C# can either inherit that one Kotlin body or override only the
natural constrained generic method; exact and Kotlin-widened calls converge
on the selected body and preserve identity. See
[`../archive/reified-generic-interface-constrained-method-generic-default-2026-08-21.md`](../archive/reified-generic-interface-constrained-method-generic-default-2026-08-21.md).

That self-bound can now compose with one or more direct public non-generic
nominal interface bounds without weakening the physical method parameter.
Natural and semantic slots plus the portable helper retain the complete exact
constraint set after local and separate-compilation remapping. C# may spell
the independent `where` constraints in another order: the authoring matcher
compares their recursive types as an exact multiset, so Framework's portable
bridge and .NET 10 DIM both reach the same ordinary C# override. See
[`../archive/reified-generic-interface-multiple-method-constraints-2026-08-21.md`](../archive/reified-generic-interface-multiple-method-constraints-2026-08-21.md).

The method-generic producer gate now also admits a nonempty set of direct
public non-generic nominal bounds without requiring the constructed self-bound:
one or more interfaces and at most one non-final class. The first proof uses
`R : Marker, R : Base`; natural and semantic slots plus the portable helper all
retain the exact `Base` and `Marker` constraints, while ordinary Kotlin and C#
implementations inherit or override the one natural constrained method. This is
structural rather than library-specific: erased, nullable, generic, final, and
non-public classifiers remain outside the proof. See
[`../archive/reified-generic-interface-nominal-method-constraints-2026-08-21.md`](../archive/reified-generic-interface-nominal-method-constraints-2026-08-21.md).

The first owner-relative constraint is now closed for one abstract covariant
root `<R : @UnsafeVariance T>(R): T`. KLIB retains `R : T`, while the natural
variant CLR slot, semantic capability slot, and Kotlin implementation overrides
omit that physically illegal or stronger GenericParamConstraint. Both slots
remain generic in the original method `R`. An ordinary C# implementation writes
one unconstrained generic method, and generated source forwards that same `R`;
`typeof(R)` proves that widened calls do not substitute owner `T`. Schema 7's
normalized method/owner-parameter pair explains the weakened CLR boundary to
tooling. Nested/multiple or nullable relative bounds, mixed members, and
inherited owner-relative forms remain closed; the direct default form is
closed below. See
[`../archive/reified-generic-interface-owner-relative-method-constraint-2026-08-21.md`](../archive/reified-generic-interface-owner-relative-method-constraint-2026-08-21.md).

That direct owner-relative family may now own one Kotlin default body. Framework
and .NET 10 share the recorded helper as the sole body; the modern natural DIM
is a typed wrapper, while the semantic bridge closes owner `T` with `object`
and preserves the actual method `R`. Ordinary C# inherits that default or
overrides only the natural generic method. Generic Kotlin implementations and
their later ordinary C# subclasses use a method-generic allocation-free
foreign-override probe, including through a three-Kotlin-product chain, so a
widened Kotlin call cannot bypass the C# override. The corresponding
final non-generic Kotlin implementor of the abstract sibling is now closed as
a separate composition gate. One private unconstrained semantic twin owns the
body; the closed public class entry, one natural MethodImpl per reified root,
and each semantic capability forward to it without substituting owner `T` for
method `R`. Reference, value, nullable, and dual-root implementations cross a
producer DLL and ordinary C# consumer on Framework and .NET 10.

The corresponding locally declared open non-generic implementation is now
closed without generalizing that private-final representation. Its public
virtual class entry remains genuinely generic in unconstrained method `R`; one
protected virtual semantic hook owns the Kotlin body, and a protected generic
probe detects an ordinary C# override of only that public entry. The reified
interface capability and a class-owned separate-compilation capability each
have a private final dispatcher. Both choose the C# typed override when present
and otherwise retain the raw Kotlin semantic body, with one object and no
shadow state. Final implementors keep their prior closed C# entry.

A local inherited non-generic body is now closed as a base-owned family. The
ordinary base method receives the typed entry, semantic hook/probe, and
class-owned capability once; open and final derived classes which first add the
reified interface receive only their own natural and interface-capability
MethodImpls. Multiple binding owners share the original pre-lowering owner-
bound proof and never call a private dispatcher declared on another TypeDef.
A separately compiled C# grandchild overrides only the inherited public entry
and remains authoritative for exact and widened Kotlin dispatch.

That family may now cross one producer boundary when the earlier artifact
already prepared and published it. The consumer resolves the producer's exact
typed Function record and semantic hook/probe family, creates no new external
body or family member, and emits only the new binding owner's MethodImpls. An
ordinary C# grandchild still overrides only the inherited public typed entry;
the compiler-owned semantic route observes that override. An unprepared
external base remains closed rather than being mutated or reconstructed from
names. Generic bases or binding owners, broader parameter graphs, and mixed
families remain separate gates.
See
[`../archive/reified-generic-interface-owner-relative-method-default-2026-08-21.md`](../archive/reified-generic-interface-owner-relative-method-default-2026-08-21.md)
and
[`../archive/reified-generic-interface-closed-owner-relative-implementation-2026-08-21.md`](../archive/reified-generic-interface-closed-owner-relative-implementation-2026-08-21.md)
and
[`../archive/reified-generic-interface-open-owner-relative-implementation-2026-08-21.md`](../archive/reified-generic-interface-open-owner-relative-implementation-2026-08-21.md)
and
[`../archive/reified-generic-interface-inherited-owner-relative-implementation-2026-08-21.md`](../archive/reified-generic-interface-inherited-owner-relative-implementation-2026-08-21.md)
and
[`../archive/reified-generic-interface-prepared-external-inherited-owner-relative-implementation-2026-08-21.md`](../archive/reified-generic-interface-prepared-external-inherited-owner-relative-implementation-2026-08-21.md).

The first special Kotlin method bound is now closed for a reified-interface
default. `<R : Any>(R): T` retains its authoritative non-null Kotlin/KLIB bound
but emits an unconstrained CLR method parameter on the natural and semantic
slots, portable helper, and implementations. Neither `class` nor `struct` can
represent a Kotlin bound which admits both reference and value substitutions.
Ordinary C# therefore authors the same unconstrained generic method; exact and
widened calls select inherited Kotlin defaults and Kotlin/C# overrides on
Framework 4.8 and .NET 10. Reflection pins zero special flags and zero nominal
constraints throughout the family. See
[`../archive/reified-generic-interface-non-null-method-constraint-2026-08-21.md`](../archive/reified-generic-interface-non-null-method-constraint-2026-08-21.md).

The direct nullable owner-relative bound is now closed for a reified-interface
default. `<R : T?>(R): T` retains its exact Kotlin/KLIB relation but emits no
CLR `R : T` row, because `T = Int`, `R = Int?` is a valid Kotlin substitution.
Natural and semantic slots, the portable helper, and Kotlin and ordinary C#
overrides retain the actual unconstrained CLR `R`. A final nullable-primitive
body narrowing is recovered from that open slot without erasing the method
token. Exact and widened Kotlin/C# calls exercise non-null and null values on
Framework 4.8 and .NET 10. See
[`../archive/reified-generic-interface-nullable-owner-relative-method-constraint-2026-08-22.md`](../archive/reified-generic-interface-nullable-owner-relative-method-constraint-2026-08-22.md).

The invariant property root now composes any nonempty number of complete
abstract mutable `T` properties. Two-property Kotlin and ordinary non-partial
C# implementations prove independent typed Property rows, `!T` Kotlin fields,
projected reads/writes, receiver identity, and exact four-member manifest
grouping on Framework 4.8 and .NET 10. The natural-fallback analyzer groups by
source property name rather than assuming a two-member contract. See
[`../archive/reified-generic-interface-multiple-invariant-properties-2026-08-21.md`](../archive/reified-generic-interface-multiple-invariant-properties-2026-08-21.md).

A covariant read-only property root now also composes one exact inheritance
edge. `Child<out T> : Parent<T>` owns one new natural CLR Property/getter and
one child semantic getter while inheriting both parent slots from the producer
assembly. A Kotlin implementation retains two independent `!T` fields. An
ordinary partial C# class implements only the two natural properties; generated
compiler-ABI adapters serve both Kotlin-widened views without changing object
identity. See
[`../archive/reified-generic-interface-read-only-property-child-2026-08-21.md`](../archive/reified-generic-interface-read-only-property-child-2026-08-21.md).

With that typed contract consolidated, continue with constructed
owner-dependent member results, then other constructed method
constraints, multiple read-only property
inheritance, diamonds, reabstraction, changed-
argument and deeper/multiple inheritance, broader input-bearing inheritance,
broader and mixed method/property families, and mixed-variance gates,
including derivability rules for ordinary foreign implementations. Then close
classifier-derived field and broader-input boundaries and deployment behavior
before the Runtime/Stdlib graph.
Before that graph opens, split stable declaration-family publication from
concrete call/value routing. The family contract, MethodDef ownership, and
override/capability slots must be fixed early enough for subsequent lowerings;
the final route selection must run after every body-producing lowering that can
introduce another generic operation. In particular, a call to `Iterator<T>`
generated by `ForLoopsLowering` must consume the same published relationships
as a source call rather than escaping an earlier `IrCall`-identity table.
ABI 42 closes the universal-superinterface part of that example: ordinary
erased `Iterator` calls need no call-site rerouting. The separate final-router
gate is now also closed after every current body-producing lowering which can
introduce a generic operation. It consumes the fixed early declaration-family
contract, propagates value provenance through generated value-class carrier
operations, and scans generated calls in post-order to a monotone fixpoint. It
can add a proven capability/foreign route, but cannot create a declaration
family or remove an authoritative earlier route. A newly added body-producing
lowering which can introduce such an operation must remain before this final
router or provide an equivalent reviewed completeness proof; the emitter may
not infer representation ad hoc. See
[`../archive/generic-owner-final-call-value-routing-2026-08-20.md`](../archive/generic-owner-final-call-value-routing-2026-08-20.md).
Keep the authoring generator as an optional fast path where it can add the
semantic sibling, and as the required path only where no sound language-
neutral adapter has yet been proven.
See
[`../decisions/draft-adr-reified-generic-interface-owner.md`](../decisions/draft-adr-reified-generic-interface-owner.md)
and the external-child, intersection, and child-owned-member evidence in
[`../archive/reified-generic-interface-external-child-2026-08-19.md`](../archive/reified-generic-interface-external-child-2026-08-19.md)
and
[`../archive/reified-generic-interface-intersection-2026-08-19.md`](../archive/reified-generic-interface-intersection-2026-08-19.md)
and
[`../archive/reified-generic-interface-member-child-2026-08-19.md`](../archive/reified-generic-interface-member-child-2026-08-19.md).
The input-bearing evidence is in
[`../archive/reified-generic-interface-consumer-2026-08-19.md`](../archive/reified-generic-interface-consumer-2026-08-19.md).
The ordinary foreign producer classifier evidence is in
[`../archive/reified-generic-interface-foreign-classifier-2026-08-19.md`](../archive/reified-generic-interface-foreign-classifier-2026-08-19.md).
The separately compiled classifier-result evidence is in
[`../archive/reified-generic-interface-classifier-result-boundary-2026-08-19.md`](../archive/reified-generic-interface-classifier-result-boundary-2026-08-19.md).
The separately compiled classifier-input evidence is in
[`../archive/reified-generic-interface-classifier-input-boundary-2026-08-19.md`](../archive/reified-generic-interface-classifier-input-boundary-2026-08-19.md).
The nested construction evidence is in
[`../archive/generic-owner-nested-construction-carrier-2026-08-19.md`](../archive/generic-owner-nested-construction-carrier-2026-08-19.md).
The proper-value-subtype extension is in
[`../archive/generic-owner-value-subtype-construction-stability-2026-08-19.md`](../archive/generic-owner-value-subtype-construction-stability-2026-08-19.md).
The contravariant extension is in
[`../archive/generic-owner-contravariant-construction-stability-2026-08-19.md`](../archive/generic-owner-contravariant-construction-stability-2026-08-19.md).
The nested open-argument evidence is in
[`../archive/generic-owner-open-nested-construction-boundary-2026-08-20.md`](../archive/generic-owner-open-nested-construction-boundary-2026-08-20.md).
The mutable invariant method and property evidence is in
[`../archive/generic-owner-mutable-invariant-cell-2026-08-20.md`](../archive/generic-owner-mutable-invariant-cell-2026-08-20.md)
and
[`../archive/generic-owner-invariant-property-cell-2026-08-20.md`](../archive/generic-owner-invariant-property-cell-2026-08-20.md).
The exact invariant-property inheritance evidence is in
[`../archive/generic-owner-invariant-property-child-2026-08-20.md`](../archive/generic-owner-invariant-property-child-2026-08-20.md).
The exact invariant consumer-child evidence is in
[`../archive/generic-owner-invariant-consumer-child-2026-08-20.md`](../archive/generic-owner-invariant-consumer-child-2026-08-20.md).
The bounded invariant consumer-grandchild evidence is in
[`../archive/generic-owner-invariant-consumer-grandchild-2026-08-20.md`](../archive/generic-owner-invariant-consumer-grandchild-2026-08-20.md).
The three-producer assembly evidence is in
[`../archive/generic-owner-three-assembly-consumer-chain-2026-08-20.md`](../archive/generic-owner-three-assembly-consumer-chain-2026-08-20.md).
The invariant use-site-projection evidence is in
[`../archive/generic-owner-invariant-projection-boundary-2026-08-20.md`](../archive/generic-owner-invariant-projection-boundary-2026-08-20.md).
The mutable invariant operation-boundary evidence is in
[`../archive/generic-owner-mutable-invariant-cell-2026-08-20.md`](../archive/generic-owner-mutable-invariant-cell-2026-08-20.md).

The first whole-Stdlib composition correction makes typed proof precedence an
explicit rehearsal invariant. A downstream semantic rewrite may not degrade a
producer-proven typed field or its private final default accessor without new
evidence that invalidates the proof. Anonymous and field initializers likewise
execute on the exact newly constructed physical owner; this makes the recursive
OctoTree constructor state read exact and removes the AbstractList/ArrayList
failure cascade, reducing the source-built Stdlib diagnostics from 216 to 92.
This rule does not cover custom or overridable accessors, setters, semantic
object state, or projected/widened receivers.

The first foreign-subclass dispatch gate is now closed for a concrete
no-input owner-dependent output. A Kotlin capability call reaches an ordinary
C# override of the natural typed virtual entry without requiring C# to
override the protected semantic hook. Every participating Kotlin override
emits a protected virtual probe for its exact typed MethodDef; the most-derived
Kotlin probe compares `ldvirtftn` with `ldftn`, so a still-later C# typed
override is detected without reflection, allocation, or duplicate state. If
no foreign override exists, the dispatcher retains the raw semantic hook: an
incompatible `@UnsafeVariance` value remains observable through the widened
view and fails only at a real typed use. The actual Kotlin product and a
separately compiled warnings-as-errors C# subclass, including C# after Kotlin,
run through PSI/LightTree on Framework 4.8 and .NET 10.

Do not generalize this proof to broad inputs or abstract semantic obligations.
The actual Kotlin base DLL -> Kotlin override DLL -> C# subclass DLL gate also
establishes that both ordinary function carriers and hidden override-family
slots are producer ABI. The first attempted proof used `-D` rather than the
required Gradle `-P` property and therefore did not execute the rehearsal.
The corrected run exposed two real gaps: the consumer reconstructed a
producer-emitted capability parameter as `C<object>`, and the later Kotlin DLL
created new semantic/probe slots instead of overriding the producer slots.
ABI-37 records the producer-selected capability indices and the protected
probe MethodDef. Consumers apply those records narrowly; exact `C<T>` slots
remain typed. A raw incompatible widened value therefore uses the inherited
semantic family, while a C# subclass after the Kotlin override changes only
the typed target and is detected. A closed self-producing verifier now audits
the allocation-free comparison and executes both its unchanged-Kotlin and
later-C# outcomes under JIT, ReadyToRun, full trimming, and a real Windows x64
NativeAOT link/run. The deployment gate for this concrete no-input output
family is closed. Broad inputs, abstract semantic obligations, interfaces, and
method-generic entries still need their own proofs before this can become a
migration-wide mechanism. C# must never be required to author the protected
compiler ABI merely to override a normal Kotlin method.

Supporting evidence for that reopening may land incrementally: exact imported
generic actuals, method generics, closed constructed interface capabilities,
typed exports, classifier normalization, shared non-generic static-state
holders, and removable private reification are independently useful. They
must identify which open Kotlin-owner cases still require an adapter. They may
not introduce competing Kotlin object identities or silently alternate a
public class between erased and reified forms. If the target later selects a
canonical CLR `C<T>` owner, that public ABI change is one explicit pre-ABI
migration with complete cast, reflection, dispatch, state, value-type, and
separate-compilation evidence—not a per-class rollout. Maximizing direct,
idiomatic .NET interop is an acceptance criterion for that reopening, not
merely a performance optimization.

The source-level builder/contracts bootstrap cycle is complete. Common `Appendable`, the complete
`StringBuilder` file including both `buildString` declarations, generated
`joinTo`/`joinToString`, the Common contracts DSL/effects, and Common abstract collection bases
ship in one self-describing product. `Standard.kt` is now complete: its final `repeat` declaration
uses the real admitted `Int.until`/range/progression closure. No admitted body is a target stub or
rewritten algorithm.

Modern enums plus the non-reified `EnumEntries` core and the ordinary `InvocationKind` enum are now
complete as coherent language/product phases. They use
Kotlin-owned reference classes, the general Comparable mapping, producer-recorded entry-field
binding, and the existing static-initialization machinery; they are not CLR value-type enums. The
contracts/`Standard.kt`/`buildString` closure is complete, including `repeat`. Common
`enumValues`, `enumValueOf`, and `enumEntries` now use the completed shared reified-substitution
path and the existing enum synthetic members; no CLR reflection lookup was added.

The completed ordinary signed range/progression and primitive-iterator closure compiles the shared
stdlib classes, removes the temporary counted-loop resolution markers, and
replaces the target's bounded loop matcher with the shared
`ForLoopsLowering`. It admits Common `repeat`, array `indices`, and the signed
non-random generated range operations. `Random`, unsigned ranges, and reified
helpers remain independent closures. See
[`../decisions/ordinary-ranges-and-progressions.md`](../decisions/ordinary-ranges-and-progressions.md).

The exact eager `Iterable.windowed`/`chunked` closure is now complete as one
generated classifier family: both ordinary and transforming variants compose
the already completed Common sliding-window machinery with the exact
`List(size, init)`/`MutableList(size, init)` factory pair. RandomAccess and
iterator/RingBuffer paths retain their Common behavior on Framework CLR 4 and
.NET 10. The next generator census must independently classify Sequence-valued
eager operations, comparison/all-equality families, Random/entropy, unsigned,
CharSequence/array variants, and reified dependencies rather than treating
this closure as authority for any of them.

The next generator census has also completed the exact eager Iterable family
whose removed dependency is Kotlin Sequence: four Sequence-result `flatMap*`
overloads, `minus(Sequence)`, and both Iterable/Collection `plus(Sequence)`
overloads. The four CLR Function-carrier collisions preserve all pre-existing
Iterable-result method names and give only the new siblings their source-
aligned `...Sequence...` physical names. This bounded stdlib rule is neither a
general `@JvmName` interpretation nor a public `DotNetName`. KLIB remains
logically generic. The physical erased `Sequence` owner must still wait for the
atomic generic-interface cutover, while a typed C# adapter/export may be
selected independently and additively.

The complete generated equality aggregate family is now published for every
currently supported classifier: `allEqual` and `allEqualBy` over Iterable,
generic object arrays, all eight signed primitive-array wrappers, and the
already completed Sequence variants. Common's selector-count, short-circuit,
nullable-key, exception, and floating equality rules remain exact on both
runtime profiles. The matching `allDistinct`/`allDistinctBy` family is also
complete. Its shared 256-bit byte-domain helper now accepts normalized Int
indices instead of a public UByte carrier, preserving both signed and upstream
unsigned algorithms while allowing .NET to publish no unsigned scalar, array,
or range surface. Keep duplicate short-circuit, singleton selector elision,
nullable-key, exception, and floating equality behavior exact; neither a
partial classifier family nor a target HashSet substitute is valid.

The natural generated `min`/`max` family is now complete as its own
dependency-homogeneous release. It contains all 52 generic/Float/Double
Iterable and object-array throwing/nullable variants plus the same four forms
for the seven upstream naturally ordered signed primitive arrays; Boolean is
absent by source authority. Iterable/object-array return-only collisions reuse
the bounded logical-element-derived physical naming already proven for
Sequence, while KLIB retains the ordinary Kotlin names. This is not a general
`@JvmName`/`DotNetName` policy and does not reopen the physical Sequence or
generic-owner ABI. Keep selector/comparator min/max, Random, unsigned, and
other dependency families independent until their complete graphs are proven;
the selector family is closed by the separately recorded tranche below.

The selector-generated `minBy`/`maxBy` family is now complete as the next
independent 40-declaration collection release: throwing and nullable forms over
Iterable, object arrays, and all eight signed primitive arrays. Boolean belongs
here because selector result `R` supplies ordering. Empty/singleton selector
elision, first ties, callback stopping, and generic Float/Double Comparable
ordering are exact. The tranche also fixed the general loop-entry stack
baseline required when an inlined local return becomes a synthetic loop break
inside a later expression operand. Installed Kotlin inlines all bodies; public
fallbacks remain directly callable from C# through the truthful erased
`Kotlin.Function1` interface. This does not claim implicit C# lambda/delegate
conversion. Keep selector-result `minOf`/`maxOf`, comparator,
Map/CharSequence, Random, and unsigned families separate until their complete
graphs are selected; the selector-result family is closed by the separately
recorded tranche below and the comparator family by the tranche after it.

The selector-result generated `minOf`/`maxOf` family is now complete as the
next independent 120-declaration release. It contains generic Comparable,
Float, and Double result overloads for throwing and nullable min/max over
Iterable, object arrays, and all eight signed primitive arrays. Boolean is
present because only the result is ordered. Empty/singleton selector counts,
first-result identity, callback stopping, nullable value results, and Kotlin
Float/Double NaN/signed-zero ordering are exact on Framework CLR 4 and .NET
10. A FIR-proven generic result physically exposed through a reference upper
bound may now recover its concrete nullable scalar representation from boxed
`R` or null; this is an implicit substitution rule and does not alter explicit
cast semantics. The twelve bounded physical names solve CLR return-only
collisions without introducing a public `DotNetName`. Installed Kotlin inlines
all `@InlineOnly` bodies, and their assembly-visible fallbacks are deliberately
not a direct C# API. This release does not migrate Sequence or any generic
owner to a constructed CLR TypeDef.

The remaining comparator min/max closure is now complete. The already
published eight Iterable declarations are joined by 72 object-/primitive-array
declarations, yielding `minWith`, `maxWith`, their nullable forms, and the four
selector-result `minOfWith`/`maxOfWith` forms over all ten supported receivers.
They consume only the completed Kotlin-owned Comparator, iterator, array, and
inline foundations. Empty/singleton call counts, first ties, callback stopping,
nullable selector results, contravariant comparator input, and explicit
Float/Double comparison are exact on Framework CLR 4 and .NET 10. No collision
mapper or public naming annotation is required. Ordinary element-selection
fallbacks remain directly callable from C# through an implemented erased
`Kotlin.Comparator`; the selector-result methods are `@InlineOnly` and remain
assembly-visible only. This is truthful current interop, not an implicit
delegate/`IComparer<T>` conversion or a generic-owner cutover.

The complete 28-declaration CharSequence min/max classifier family is now
published separately from Map. It includes natural, selector,
generic/Float/Double selector-result, element-comparator, and comparator-result
throwing/nullable forms. The first compile selected the exact Common
`CharSequence.lastIndex` prerequisite rather than rewriting the indexed
algorithms. Real `System.String` and Kotlin/foreign implementations of the
erased `Kotlin.CharSequence` capability retain one classified identity and the
same behavior on Framework CLR 4 and .NET 10. The bounded return-only collision
mapper now admits exact façade/package/receiver triples for CollectionsKt and
StringsKt; this remains compiler-owned stdlib naming, not `DotNetName`.
Installed Kotlin preserves upstream inline visibility, and direct C# calls
work on both classifier arms without wrapping strings. Map aggregates remain
their own source-façade/delegation closure and are closed immediately below.

The complete 24-declaration Map min/max adapter family is now published on
`Kotlin.Collections.MapsKt`. Every selected declaration is the Common
`@InlineOnly` adapter over `entries`; Map has no natural entry min/max family.
Selector, generic/Float/Double result, comparator-element, and comparator-
result throwing/nullable forms retain exact Common evaluation, tie, callback,
nullable, NaN, and signed-zero behavior. The bounded return-only mapper admits
only the exact MapsKt/package/Map triple. All 24 MethodDefs remain assembly-
visible and installed Kotlin inlines them; direct C# access is rejected. This
does not migrate the erased physical Map owner. It closes the current leaf-
family interlude before the complete generic-owner emitter/rollback rehearsal.

Common `Comparable<T>` is now selected independently of enums: KLIB identity maps to canonical
`System.IComparable` plus the truthful typed `System.IComparable<T>` capability, while Kotlin
interface calls retain ordinal String and Kotlin floating ordering through one semantic helper.
The completed `Enum<E>` work consumes this general representation rather than publishing an
enum-only substitute. See
[`../decisions/comparable-clr-views.md`](../decisions/comparable-clr-views.md).

The bounded general annotation-class foundation is now selected: every supported parameterless
marker is one concrete sealed `System.Attribute` subtype, retains authoritative Kotlin identity
in KLIB, and projects only runtime-retained applications onto exact CLR metadata parents. The
implementation follows the shared annotation member generator and covers separate compilation;
valued constructors and Kotlin reflection discovery remain separate. See
[`../decisions/marker-annotation-classes.md`](../decisions/marker-annotation-classes.md).

The contracts product is now public Common API rather than a compiler-private cycle breaker.
Compiler-consumed effects belong to KLIB/Common semantics. The first exact CodeAnalysis export
slice is complete through a versioned neutral FIR-to-export carrier and remains limited to
explicit exports on profiles that physically supply the standard attributes. It neither
rediscovers contracts from lowered IR nor makes CLR attributes authoritative. The importer may
continue accepting those standard attributes as foreign evidence under Kotlin stability rules.

### 5. Retain and enforce the completed declaration architecture seam

Use [`compiler-architecture.md`](compiler-architecture.md). The versioned neutral carrier is now
shared by the foreign FIR provider and backend binding, and Kotlin-facing provider policy lives in
the FIR-owned .NET module while objective CLR loading stays below FIR and IR binding stays in the
backend. Preserve and validate that dependency direction as the importer grows.

Further extraction still requires concrete independent consumers. It is not a request to split
large classes or create layers for their own sake.

The post-rebase upstream architecture pass adds two explicit placement guards.
Runtime KLIB decoding, member enumeration, and reflective lookup may not grow
inside `backend.dotnet`; JVM keeps descriptor-less reflection in its dedicated
reflection owner while the backend only supplies executable artifacts.
Likewise, broader C# export must first separate selector resolution,
complete-family admission, and its reusable host-facing plan from
`DotNetIlEmitter`, following Swift Export's provider/model versus backend
binding split. The completed callable-parameter graph remained compile-time
lowering over an exact declaration target rather than runtime member discovery;
future reflective invocation and enumeration must preserve that owner split.

Before generic, member, inheritance, or coroutine export expands, that owner
also acquires an explicit selected input-module graph. Modules are full,
transitive, or excluded; a referenced excluded declaration becomes a validated
stub only where the complete host type relation remains truthful, otherwise
the dependent export fails. Fully qualified Kotlin identity owns generated
names, and every non-null host input receives an executable Kotlin boundary
check in addition to Roslyn metadata.

Build Tools API and incremental work follows only after a real .NET operation
model and build-session lifetime exist. Functional coverage must distinguish
producer artifact inputs from output roots, argument ownership, friends,
missing dependencies, diagnostics, logging, and metrics. The operation consumes
one self-describing DLL and stays project-isolation safe; it does not copy the
JS compile/link split or JVM snapshot protocol merely to reuse an API shape.

The physical writer boundary now follows
[`structured-cli-ir.md`](structured-cli-ir.md). `backend.dotnet` chooses Kotlin
representation, ABI, export, and target-profile policy and lowers those choices
to concrete ECMA-335 forms. `:dotnet:dotnet.ir` alone owns migrated physical
CLI vocabulary, structural validation, and deterministic text serialization;
later it also owns the already-decided JVM-hosted PE sink. Grow that module
only through complete production slices, remove each superseded string path,
and do not use CLI generic capability to reopen Kotlin-owned erased runtime
identity.

### 6. Broaden foreign CLR interoperability only through exact mappings

Use [`clr-annotations.md`](clr-annotations.md) and the
[importer ADR](../decisions/draft-adr-clr-importer-boundary.md). Admit complete declaration families
and standard CLR attributes only when Kotlin type, contract, stability, call, and backend-binding
semantics are all specified.

Do not flatten property/ref/out state, bypass Common smart-cast stability, or infer a declaration
role from an attribute name.

The first exact foreign method-generic slice is complete: method-owned parameters,
relative, nominal, and admitted constructed-interface bounds, MethodSpec calls,
overrides, vectors/`params`, overload resolution, and callable reflection share
one FIR/IR declaration and one retained MethodDef. Continue only by extending
[its closed grammar](../decisions/foreign-clr-generic-methods.md). Special CLR
constraints, unsupported constructed bounds, and explicit nullable generic
leaves remain fail-closed; do not turn them into approximate Kotlin bounds or
partially valid calls.

The exact foreign generic-TypeDef programme now includes direct owner parameters,
declaration variance, admitted bounds, properties, owner-plus-method substitution,
vectors/`params`, open generic interface inheritance, recursively constructed
member signatures, Kotlin implementations, and primitive-vararg boundary adapters.
They retain one native CLR TypeDef graph and its exact constructed slots through a
shared versioned carrier. Extend only
[its closed grammar](../decisions/foreign-clr-generic-type-identities.md).
The same graph now retains exact selected physical core identities: foreign
`System.Nullable<V>` methods and properties map all eight signed primitive
carriers to Kotlin `V?`, compose inside admitted constructed interfaces, and
preserve the original CLR slots in both call directions. This is a selected-
identity mapping, not recognition of a type name or of Roslyn's unconstrained
annotated `T?`.
Inherited foreign views now retain their exact InterfaceImpl row and apply its
Roslyn preorder independently from the physical TypeSpec. Closed, reordered,
mixed fixed/open, and inherited `T?` owner-parameter substitutions therefore
compose through FIR, overrides, CIL, and reverse Kotlin implementations. Concrete
oblivious references remain platform types, while an oblivious supertype owner
parameter stays `T` rather than manufacturing `T!`.
Nominally constrained constructed members and InterfaceImpls now reuse the shared
declaration-qualified CLR constraint proof. TypeSpec-backed interface bounds are
structural Kotlin bounds with aligned child nullability while their original
GenericParamConstraint remains the physical ABI; Kotlin implementations and open
generic bound dispatch use the same constructed carrier. Unsigned carriers,
special constraints, unsupported or nullable-root bounds, nullable user structs,
open constrained nullable parameters, and explicit unconstrained nullable generic
leaves on declared members remain fail-closed. Never route an imported generic
owner through the Kotlin-owned erased-interface ABI.

### 7. Close the remaining draft ABI decisions before wider breadth

The accepted runtime decisions must be frozen and the remaining drafts accepted, revised, or
explicitly excluded before third-party binary publication:

- [erased generic-interface identity](../decisions/generic-interface-erased-identity.md);
- [callable and callable-reference ABI](../decisions/draft-adr-callable-and-reference-abi.md);
- [explicit C# export surface](../decisions/draft-adr-explicit-csharp-export-surface.md); and
- [structured CLR importer boundary](../decisions/draft-adr-clr-importer-boundary.md).

Concrete feature slices may supply evidence for those drafts, but may not silently freeze them.

## Cross-cutting implementation gate

Every semantic feature follows this sequence:

1. identify the authoritative Common declaration or compiler rule;
2. document JVM, JS, Wasm, and Native precedent relevant to the feature;
3. isolate the exact CLR constraint that requires a different representation;
4. attack the preferred design and select the Kotlin-aligned target choice;
5. update the owning ADR or programme before implementation;
6. implement one complete vertical slice; and
7. test adversarially across logical semantics, physical metadata, separate modules, foreign
   producers/consumers, and all compatible target profiles.

A test or library producer must fail rather than silently evict an unsupported declaration. Exact
IL text alone is not semantic evidence, and a local probe is not a committed invariant.

## Continuous evidence requirements

The target gate must retain:

- symmetric semantic execution on `net48` and `net10.0` for both FIR parser paths;
- `netstandard2.0` library production and consumption on both runtimes;
- Roslyn compilation/execution for observable C# boundaries;
- assembly and metadata validation for every accepted CIL product;
- separate producer/consumer binding through self-describing DLLs;
- malformed, duplicate, ambiguous, stale-schema, and wrong-profile rejection;
- direct visibility and compiler-ABI inspection; and
- no skipped test hidden by an unavailable required toolchain in the strict lane.

The strict aggregate remains one supported entry point even if its internals
are partitioned. A partition is justified only by an independent local/CI
consumer, measurable cache or scheduling value, and a disjoint exhaustive
grouping whose union is checked by the repository test-lifecycle machinery.
The current aggregate already separates FIR/IL/box from CLI/library
integration; splitting the short-path `dn` task is on hold until it can avoid
duplicated setup and has a cross-process strategy for Framework ILAsm/CLR4
resources. Wasm's tagged task split is precedent for that proof, not evidence
that more Gradle tasks alone make the full local gate faster.

The platform producer/consumer boundary is now selected independently of task
partitioning: ordinary codegen tests consume reusable exact-profile
runtime/stdlib pairs, source production is explicit, every IL golden retains
canonical Framework validation, and cross-writer validation uses a bounded
shape-based class. The remaining dedicated-test-module move is architectural
ownership work, not a prerequisite for these cache and process-count gains.

Emitter throughput follows the same context-local ownership used by mature
targets. Wasm caches declaration-derived class/interface metadata for one
module; JS keeps reusable class facts in its backend context; JVM maps physical
names directly from the declaration-parent chain and writes through a binary
visitor; Native attaches reusable enum-entry analysis to the lowered IR class
and keeps later codegen caches generation-local. Kotlin/.NET now caches only
declaration-stable classifier facts for one lowered compilation and keeps the
live selected codegen set authoritative.
Resolution-only or previously rejected local declarations may not be revived
through Stdlib/external fallback, and a no-progress diagnostic fixpoint fails
immediately. Physical type mapping remains uncached because nullability,
generic view, target profile, assembly-reference collection, and live ABI
state can change its answer.

Logical library binding follows the shared KLIB declaration-table pattern at a
narrower lifetime. One `DotNetExternalDeclarations` index caches the final
kind-prefixed rendered public signature by IR identity, including misses. An
exact publication profile removed all sampled ABI-key recomputation beneath
the external interface-default, generic-interface, and covariant-bridge lookup
paths (three samples/53.9 MB to zero); recording duration changed from 67 to 62
seconds and total sampled allocation from about 15.30 to 14.42 GB. Treat the
global figures as indicative rather than guaranteed. Do not widen the cache
across lowerings until external-only declaration provenance makes stale local
IR impossible; JVM's phase-local bridge-signature cache is the relevant
counterexample to an over-broad lifetime. Producer-index and canonical-slot
identity rendering remain separate profile candidates.

Performance work remains profile-first, but the current tranche is closed. The
shared report now has an honest `DotNet` platform identity and sequential
serialization/lowering/backend ownership. Installed-stdlib 25/50/100 and
100/200/400 generic/interface publication probes showed approximately linear
variable cost rather than an emitter/lowering complexity defect. Re-measure an
exact cold producer and the aggregate after a future material compiler change;
do not keep turning knobs in the absence of a new hotspot. A direct PE writer,
streaming text sink, parallel emitter, or worklist replacement for the current
partial-support fixpoint remains a separate architectural slice: adopt it only
with measured material value plus determinism, failure eviction, resource
embedding, and both ILAsm compatibility lanes preserved. Test partitioning
must continue to avoid rebuilding complete Runtime/Stdlib products for ordinary
small modules, but may not share mutable/freshness-sensitive integration
products merely to reduce wall time.

The foreign CLR reader follows the corresponding input pattern. JVM class
readers consume bounded class bytes and Native deserializers work over bounded
in-memory metadata inputs; a CLR assembly instead has one PE-owned metadata
directory shared by its tables and heaps. Repeated `RandomAccessFile.seek`
calls for every table scalar and every string byte are therefore a CLR-reader
implementation accident, not a semantic requirement. Buffer the already
range-checked CLI metadata directory once per read when it is at most 64 MiB,
and retain the exact random-access fallback for larger images. Do not turn
this into a global assembly cache: selected graph identity, target profile,
file freshness, and compilation lifetime need an explicit shared owner first.

The accepted buffer changed the exact physical-metadata/signature testcase
from 123.031 seconds to 3.417 seconds. Real importer cases also improved:
foreign interface binding across both profiles changed from 208.84 to 18.953
seconds, Obsolete/deprecation enhancement from 234.61 to 11.576 seconds, and
the hostile `NotNullWhen` case from 31.98 to 5.814 seconds. Profile again after
the aggregate; do not add string/blob memoization until a new profile proves
that it is the next material cost.

The current verified count and command belong only in [`../../STATUS.md`](../../STATUS.md).

## Explicitly parked feature families

Parking means “fail clearly and do not constrain a future ABI,” not “approximate now.”

- wider declaration/field/accessor-object annotation use-site targets and
  unsupported CLR-value projections; valued construction, defaults, KLIB
  applications, exact class/callable discovery, and declaration-owned
  `KType` type-use discovery are selected;
- default, mapped/Stdlib/foreign, and convenience-API member reflection; the
  opt-in ordinary Kotlin-producer closure, nominal `KClass` floor,
  logical `KType`/`typeOf` graph, callable annotations, callable return
  types/type parameters/parameters, and positional plus named/default
  invocation plus direct property accessor objects are complete for the
  admitted callable/property arities;
- foreign CLR generic-method shapes beyond the accepted exact slice, including
  special constraints, constructed types outside the admitted interface grammar,
  nullable-root bounds, and explicit unconstrained nullable generic leaves;
- foreign CLR generic-TypeDef shapes beyond the accepted exact slice, including
  unsigned carriers, special constraints, constructed bounds outside the admitted
  interface grammar, nullable constraint roots, and explicit nullable generic
  leaves on declared members;
- multi-field value classes; the single-field box/carrier architecture is
  accepted and implemented, but does not select a layout or ABI for multiple
  underlying fields;
- reflection-dependent inline operations beyond the completed reified
  type/class/array/enum/`typeOf` closure;
- coroutine scheduling, `kotlinx.coroutines`, debugger metadata, broad suspend-
  callable reflection, and `Task`/`ValueTask` exports;
- the public/full concurrency, volatility, synchronization, and atomic surface;
- collection/stdlib families outside admitted Common dependency closures; and
- broad Gradle/KMP distribution integration beyond the current target model.

An adjacent feature must not assume a parked representation. In particular, a future
multi-field value-class model must extend rather than bypass the accepted single-field
generic/interface boundaries; coroutines constrain callables and cancellation; annotation
discovery must consume the completed KLIB value authority rather than re-decoding derived CLR
rows; enums consume collection identity.

## Post-core wrapper minimization

After the supported core language and stdlib feature closure is complete, run
one dedicated wrapper-reduction programme. Do not interleave speculative
wrapper micro-optimizations with unfinished semantic foundations: first make
the complete object, generic, collection, reflection, enum, annotation,
coroutine, and interop rules observable and testable; then measure the actual
boundary costs.

The programme must inventory runtime carriers, collection/array adapters,
foreign-import bridges, callable adapters, and explicit C# export facades. For
each wrapper, record allocation frequency, lifetime, identity behavior,
dispatch path, reflection surface, and cross-assembly role. Eliminate, fuse,
or cache it whenever that change can be disabled without changing Kotlin
`is`, `as`, `===`, mutation, virtual dispatch, reflection, separate
compilation, or DLL signatures. Retain wrappers that carry a real semantic
boundary, and document why they are irreducible rather than hiding them as an
implementation accident.

This programme may use CLR generics, structs, delegates, interface views, and
escape analysis as implementation optimizations. It may not introduce a
second observable representation for a Kotlin-owned object or make C# export
shape determine Kotlin runtime identity. Benchmark allocation, steady-state
runtime, ReadyToRun/NativeAOT size, and wrapper crossings before and after each
accepted reduction.

## Release gates

### Gate A — viable internal experimental backend

- build and publication fail on evicted or unbound declarations;
- logical keys, physical bindings, source visibility, friend access, and profile selection are
  explicit and cross-module safe;
- all three profiles and their interface/default premises are represented deliberately;
- strict target tests execute the supported semantic corpus and separate-product matrix; and
- no half-landed feature or undocumented prototype identity is treated as stable.

### Gate B — third-party experimental binaries

- all public or compiler ABI drafts needed by the supported surface are accepted;
- KLIB, physical ABI, runtime/stdlib, importer, and C# tooling version skew fails predictably;
- signing, assembly versioning, and distribution-owned runtime/stdlib artifacts are specified;
- public Kotlin ABI, compiler ABI, and C# export surfaces are mechanically distinguishable;
- CLR importer and foreign implementation paths have committed producer/consumer coverage; and
- every supported feature has freeze-level semantic, layout, profile, and foreign-language tests.

### Gate C — official experimental target discussion

- module/package ownership follows reviewable mature-target dependency directions;
- dedicated .NET FIR/session and KLIB platform integration are ready for repository-wide use;
- the supported runtime/profile matrix runs in CI;
- shared semantic and multi-module coverage approaches mature-target scale;
- diagnostics and CIL/metadata validation are structured; and
- every parked language area has an explicit inclusion schedule or supported-surface exclusion.

## Change-review checklist

Every change answers:

1. Which Kotlin semantic invariant is authoritative?
2. If a stronger CLR check is observable, which exact Kotlin specification
   clause permits that outcome or failure point, and which negative tests
   protect ordinary Kotlin behavior?
3. How do mature targets represent or layer the invariant?
4. What exact CLR constraint requires target-specific treatment?
5. Which layer owns the logical fact, physical representation, and validation?
6. Does the change affect public Kotlin ABI, compiler ABI, C# export, runtime, or tooling only?
7. How do stale producers, consumers, runtime/stdlib pairs, schemas, and target profiles fail?
8. Can C# call, implement, reflect, or pass the value without redefining Kotlin semantics?
9. Does unsupported or malformed input fail at a useful location without shrinking an artifact?
10. Which semantic, layout, separate-module, foreign-language, and hostile tests prove the claim?
11. Which ADR owns the lasting decision?
