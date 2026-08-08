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
- Kotlin-produced libraries are self-describing DLLs whose embedded KLIB owns logical identity.
- CLR metadata owns executable shape and the useful foreign-language view.
- Standard CLR attributes are consumed or emitted where they express an exact interoperable fact.
- Compiler-only physical members are marked as such and do not become an idiomatic C# API merely
  because linkage requires public metadata.
- C# exports are deliberate facades; they do not redefine ordinary Kotlin ABI.

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
- [runtime and stdlib ownership](../decisions/runtime-and-stdlib-ownership.md);
- [generic nullability and covariant returns](../decisions/adr-hybrid-generic-nullability-and-covariant-returns.md);
- [profile-aware interface defaults](../decisions/adr-profile-aware-interface-default-implementations.md);
- [companion placement and initialization](../decisions/adr-companion-static-placement-and-initialization.md);
- [static-initialization failures](../decisions/adr-kotlin-static-initialization-failures.md);
- [reified inline call-site substitution](../decisions/reified-inline-functions.md);
- [logical `KType` and `typeOf`](../decisions/ktype-and-typeof.md); and
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

Ordinary and reified inline are available for exact Common-source adoption. Suspend inline
functions remain a separate programme and must continue to fail clearly.

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
separate compilation, both CLR profiles, and direct C# graph inspection. Member enumeration and
invocation remain follow-on features. Valued annotation classes now use the Common member
generator and embedded KLIB as their complete semantic representation; exact runtime-retained
scalar/string/vector values receive an additive CLR custom-attribute row, while unsupported values
fail closed to KLIB-only without weakening Kotlin construction.

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
behavior. `KCallable.typeParameters` now takes JVM's declaration-owned rule:
functions and generic extension properties exclude enclosing class parameters,
while constructors expose the constructed class's own parameters. Return
types, exposed parameters, recursive bounds, and reachable enclosing parameters
are allocated in one identity graph. `KCallable.parameters` now extends that
same graph with JVM's owner, ordering, captured-receiver omission, reindexing,
default, vararg, and equality rules. Kotlin parameter applications retain their
exact declaration owners; admitted foreign callables use exact CLR Param rows
without turning CLR optional flags into Kotlin default-call semantics. General
members, accessor objects, and type-use annotations remain separate tranches.
Positional `KCallable.call` now consumes that exact
parameter order through the existing erased `FunctionN` capability. Runtime
surface level 22 validates count and dispatches without CLR member discovery;
defaults remain explicit, a vararg is one array argument, property call means
getter, and target exceptions keep identity. The `callBy` default-mask/omission
tranche is now complete at runtime surface 23 for the admitted `KFunction0`
through `KFunction3` closure: it uses exact parameter identity, JVM
omission/error behavior, fresh exact empty varargs, and the shared static
class/default-dispatch ABI across separate libraries. Library ABI 22 keeps
Kotlin-owned class parameters erased on that helper while retaining genuine
method generics. The tranche introduces neither CLR member lookup nor a
reflection-owned mask ABI.

The five JVM-shaped `KFunction` declaration flags now form one property
capability inherited by every admitted `KFunction0` through `KFunction3`.
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
remain Stdlib-owned. Member enumeration, accessor objects, type-use
annotations, and reflective invocation beyond the admitted callable closure
remain independent tranches.

Future member enumeration must include member extension properties with
multiple callable-owned type parameters and, once context parameters are
admitted, nested/member properties with multiple context-owned types. The
second-stage `IrTypeParameterScopeChecker` remains enabled: a failure is
evidence of an invalid graph or deserialization boundary, not a target-specific
checker to disable.

### 3. Expand Common collections by exact dependency closure

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
Grouping aggregates, sorting/comparators/random, dependency-blocked reified variants, and open nullable projected
arrays. Select and document one substrate with the largest coherent release before admitting its
generated family. The narrow `Array<out T?>` boundary may land
independently because it restores authoritative `setOfNotNull(vararg T?)` and object-array nullable
filtering without changing collection identity. Loose one-function growth and implicit BCL
collection identity remain excluded.

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

A materially different true CLR-generic owner with a complete erased Kotlin
capability ABI and early failure of physically incompatible unchecked casts is
now explicitly on hold in
[`generic-class-owner-reopening.md`](generic-class-owner-reopening.md). It is
not the rejected primary-typed/rare-fallback design, but neither is it an
optimization: it changes observable ABI and cast timing. Current work continues
against the accepted erased owner. The parked question blocks only
reintroduction or freeze of a Kotlin-owned generic class TypeDef as CLR
`C<T>`; it does not block Common stdlib, callables/reflection, CLI IR, imported
CLR generics, generic methods, separate explicit exports, or removable private
specialization.

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

### 4. Retain and enforce the completed declaration architecture seam

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

The physical writer boundary now follows
[`structured-cli-ir.md`](structured-cli-ir.md). `backend.dotnet` chooses Kotlin
representation, ABI, export, and target-profile policy and lowers those choices
to concrete ECMA-335 forms. `:dotnet:dotnet.ir` alone owns migrated physical
CLI vocabulary, structural validation, and deterministic text serialization;
later it also owns the already-decided JVM-hosted PE sink. Grow that module
only through complete production slices, remove each superseded string path,
and do not use CLI generic capability to reopen Kotlin-owned erased runtime
identity.

### 5. Broaden foreign CLR interoperability only through exact mappings

Use [`clr-annotations.md`](clr-annotations.md) and the
[importer ADR](../decisions/draft-adr-clr-importer-boundary.md). Admit complete declaration families
and standard CLR attributes only when Kotlin type, contract, stability, call, and backend-binding
semantics are all specified.

Do not flatten property/ref/out state, bypass Common smart-cast stability, or infer a declaration
role from an attribute name.

### 6. Close the remaining draft ABI decisions before wider breadth

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

Continue performance work profile-first. Re-measure the exact cold product
producer and the aggregate after each accepted change, including allocation
and asymptotic scaling, before touching the next hotspot. A direct PE writer,
streaming text sink, parallel emitter, or worklist replacement for the current
partial-support fixpoint is a separate architectural slice: adopt it only with
determinism, failure eviction, resource embedding, and both ILAsm compatibility
lanes preserved. Test partitioning must continue to avoid rebuilding complete
Runtime/Stdlib products for ordinary small modules.

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

- wider annotation use-site targets, type-use owners, and unsupported CLR-value
  projections; valued construction, defaults, KLIB applications, and exact
  class/callable runtime discovery are selected;
- broad member reflection and accessor objects; the nominal `KClass` floor,
  logical `KType`/`typeOf` graph, callable annotations, callable return
  types/type parameters/parameters, and positional plus named/default
  invocation are complete for the admitted callable arities;
- foreign CLR generic-method import, including method-owned parameter bounds,
  overload resolution, invocation/binding, and subsequent callable reflection;
- value/inline classes;
- reflection-dependent inline operations beyond the completed reified
  type/class/array/enum/`typeOf` closure;
- suspend inline functions until coroutine state machines are supported;
- coroutine state machines and `Task`/`ValueTask` exports;
- concurrency, volatility, synchronization, and atomics;
- `lateinit`;
- collection/stdlib families outside admitted Common dependency closures; and
- broad Gradle/KMP distribution integration beyond the current target model.

An adjacent feature must not assume a parked representation. In particular, value classes
constrain generic interfaces; coroutines constrain callables and cancellation; annotation
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
2. How do mature targets represent or layer it?
3. What exact CLR constraint requires target-specific treatment?
4. Which layer owns the logical fact, physical representation, and validation?
5. Does the change affect public Kotlin ABI, compiler ABI, C# export, runtime, or tooling only?
6. How do stale producers, consumers, runtime/stdlib pairs, schemas, and target profiles fail?
7. Can C# call, implement, reflect, or pass the value without redefining Kotlin semantics?
8. Does unsupported or malformed input fail at a useful location without shrinking an artifact?
9. Which semantic, layout, separate-module, foreign-language, and hostile tests prove the claim?
10. Which ADR owns the lasting decision?
