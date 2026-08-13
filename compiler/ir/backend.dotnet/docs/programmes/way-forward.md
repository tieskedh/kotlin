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

Coroutine scheduling, `kotlinx.coroutines`, sequence builders, debugger
metadata, suspend callable reflection/export, and explicit C# async adapters
remain consumers of this foundation. None may change its continuation/sentinel
ABI or introduce a second state-machine representation.

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

The completed next consumer is the stable MutableList and generic-array
sorting closure. It reuses the exact Native/Wasm snapshot and stable merge-sort
lineage, then admits only the dependency-closed eager Iterable/MutableList
`sorted*`/`sort*` consumers. Primitive and unsigned arrays, ranges, Sequences,
binary search, and random ordering remain separate. The stable algorithm,
failure timing, arbitrary-list, physical, and C# boundaries are owned by
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

Further work remains foundation-first rather than allowlist-count-first. Recompute the remaining
Common generator/source dependency graph around the actual missing substrates: Sequence,
Grouping aggregates, primitive/unsigned/range sorting and random operations, and
dependency-blocked reified variants. The narrow open-nullable-array foundation is now complete:
`Array<out T?>` uses an identity-preserving `System.Array` read view, Kotlin-owned
`vararg T?` uses a fresh declaration-stable `object[]`, and the bounded release restores
authoritative `setOfNotNull(vararg T?)` plus object-array nullable filtering. Invariant/input
method-owned open nullable arrays remain excluded. Select and document one remaining substrate
with the largest coherent release before admitting its generated family. Loose one-function
growth and implicit BCL collection identity remain excluded.

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
suffixes extend the compiler-selected base MethodDef name. State routes are
joined through recorded transitive field reads/writes, not source member names.
The artifact rejects any physical MethodDef collision, including a user name
which occupies a generated role identity; a later name allocator may improve
that rejection but may never publish ambiguous metadata.
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
- coroutine scheduling, `kotlinx.coroutines`, sequence builders, debugger
  metadata, broad suspend-callable reflection, and `Task`/`ValueTask` exports;
- concurrency, volatility, synchronization, and atomics;
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
