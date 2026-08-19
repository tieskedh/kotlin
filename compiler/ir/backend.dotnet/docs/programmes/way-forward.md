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
inherits one non-generic declaration-semantic capability used only when a
projected, widened, value-variant, or classifier-only Kotlin view has no honest
constructed CLR interface.

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
profile. This remains a source-generation contract, not support for arbitrary
precompiled or non-partial CLR implementors.

Continue with default, property, broader/multiple member, invariant, and
mixed-variance gates, then the Runtime/Stdlib graph. The authoring generator
still cannot retrofit precompiled, non-partial, or other-language implementors.
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
