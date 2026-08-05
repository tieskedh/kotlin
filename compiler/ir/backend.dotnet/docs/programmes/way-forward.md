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
- [static-initialization failures](../decisions/adr-kotlin-static-initialization-failures.md); and
- [CIL/PE production direction](../decisions/cil-and-pe-production.md).

New features build on these decisions or amend them explicitly. They do not restate a competing
local version.

## Current execution order

The current bounded work order is intentional. It may interleave small slices, but a later item
must not pull an earlier responsibility back into the backend or publish a shape whose prerequisites
are still undecided.

### 1. Preserve the completed selected-graph ordinary inline foundation

[`inline-functions.md`](inline-functions.md) records the completed component-aware embedded KLIB
loading, target IR serialization, shared first-/second-stage inliner phases, Common shared-variable
ABI, and all existing KLIB inliner modes. New work must keep its separate-DLL, friend/compiler-ABI,
main/prepared IR, and cross-profile matrix green.

Ordinary non-reified inline is available for exact Common-source adoption. Reified and suspend
inline functions remain separate programmes and must continue to fail clearly.

The selected-graph breadth is now pinned explicitly: a body from library A binds declarations in an
explicitly selected library B through the existing non-linking deserializer and frontend-owned IR
symbol finder. Preserve that boundary without introducing a general IR linker or transitive
dependency discovery.

The reified programme has completed its independently truthful non-generic reference,
boxed-scalar, ordinary type-test, signed primitive-array, nullable-primitive generic-array,
classified `Array<*>`, and declaration-erased generic-class prerequisites. The concrete
post-substitution array-operation audit is complete as well: array construction reuses those
ordinary carriers and needs no reified-only representation. Do not flip either inliner capability
gate or mistake that allocation readiness for the whole language feature. Kotlin `KClass` and
class literals now form a completed nominal floor over classified CLR evidence. `KType`, the
reified enum helpers, the final substituted type-operator matrix, and
the physical reified throwing-stub contract remain separate boundaries.

### 2. Expand Common collections by exact dependency closure

Use [`common-collections.md`](common-collections.md). Its builder and Common abstract-base
foundation now composes with the selected erased generic-class ABI without a target-authored
algorithm or collection-specific bridge.

The collection work provides ordinary user value and the foundation now consumed by enums, while exercising
generic interfaces, arrays, separate products, and profile-compatible stdlib publication.

The erased physical generic-class route is selected in
[`../decisions/generic-class-erased-identity.md`](../decisions/generic-class-erased-identity.md).
The disproven primary-typed/exceptional-canonical model has been replaced by one non-generic
Kotlin runtime owner and one erased virtual hierarchy. Explicit typed C# export remains a later
fail-closed product. Hybrid class capabilities, wrappers as identity, receiver-only provenance,
guard hoisting, visibility-dependent ABI, and AOT-specific specialization remain excluded.

The source-level builder/contracts bootstrap cycle is complete. Common `Appendable`, the complete
`StringBuilder` file including both `buildString` declarations, generated
`joinTo`/`joinToString`, the Common contracts DSL/effects, and Common abstract collection bases
ship in one self-describing product. `Standard.kt` is exact through `takeUnless`; only its final
`repeat` declaration remains projected out until the real `Int.until`/range/progression closure
lands. No admitted body is a target stub or rewritten algorithm.

Modern enums plus the non-reified `EnumEntries` core and the ordinary `InvocationKind` enum are now
complete as coherent language/product phases. They use
Kotlin-owned reference classes, the general Comparable mapping, producer-recorded entry-field
binding, and the existing static-initialization machinery; they are not CLR value-type enums. The
contracts/`Standard.kt`/`buildString` closure is complete under the precise `repeat` exclusion
above. General reified enum functions remain behind the reified gate throughout.

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
Compiler-consumed effects belong to KLIB/Common semantics. The next annotation/export slice may
project only the exact CodeAnalysis subset through a neutral FIR-to-export carrier; it must not
rediscover contracts from lowered IR or make CLR attributes authoritative. The importer may
continue accepting those standard attributes as foreign evidence under Kotlin stability rules.

### 3. Retain and enforce the completed declaration architecture seam

Use [`compiler-architecture.md`](compiler-architecture.md). The versioned neutral carrier is now
shared by the foreign FIR provider and backend binding, and Kotlin-facing provider policy lives in
the FIR-owned .NET module while objective CLR loading stays below FIR and IR binding stays in the
backend. Preserve and validate that dependency direction as the importer grows.

Further extraction still requires concrete independent consumers. It is not a request to split
large classes or create layers for their own sake.

### 4. Broaden foreign CLR interoperability only through exact mappings

Use [`clr-annotations.md`](clr-annotations.md) and the
[importer ADR](../decisions/draft-adr-clr-importer-boundary.md). Admit complete declaration families
and standard CLR attributes only when Kotlin type, contract, stability, call, and backend-binding
semantics are all specified.

Do not flatten property/ref/out state, bypass Common smart-cast stability, or infer a declaration
role from an attribute name.

### 5. Close the remaining draft ABI decisions before wider breadth

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

The current verified count and command belong only in [`../../STATUS.md`](../../STATUS.md).

## Explicitly parked feature families

Parking means “fail clearly and do not constrain a future ABI,” not “approximate now.”

- valued annotation constructors and arguments, wider use-site targets, and runtime annotation
  reflection; parameterless markers, retention, and their exact CLR-parent projection are selected;
- `KType`, `typeOf`, and member/annotation reflection; the nominal `KClass`/class-literal floor is
  complete;
- value/inline classes;
- reified functions, including `enumValues`, `enumValueOf`, and `enumEntries`, plus `typeOf` and
  reflection-dependent inline substitution;
- suspend inline functions until coroutine state machines are supported;
- coroutine state machines and `Task`/`ValueTask` exports;
- concurrency, volatility, synchronization, and atomics;
- `lateinit`;
- collection/stdlib families outside admitted Common dependency closures; and
- broad Gradle/KMP distribution integration beyond the current target model.

An adjacent feature must not assume a parked representation. In particular, value classes
constrain generic interfaces; coroutines constrain callables and cancellation; valued annotation
arguments constrain reflection and custom-attribute emission; enums consume collection identity.

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
