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

### 1. Expand Common collections by exact dependency closure

Use [`common-collections.md`](common-collections.md). Select the next non-inline Common/generated
family only after its complete source, expect/actual, backend, and runtime dependency closure is
known. Do not fork Common algorithms to make a slice look smaller.

The collection work provides ordinary user value and foundations for enums, while exercising
generic interfaces, arrays, separate products, and profile-compatible stdlib publication.

### 2. Retain and enforce the completed declaration architecture seam

Use [`compiler-architecture.md`](compiler-architecture.md). The versioned neutral carrier is now
shared by the foreign FIR provider and backend binding, and Kotlin-facing provider policy lives in
the FIR-owned .NET module while objective CLR loading stays below FIR and IR binding stays in the
backend. Preserve and validate that dependency direction as the importer grows.

Further extraction still requires concrete independent consumers. It is not a request to split
large classes or create layers for their own sake.

### 3. Broaden foreign CLR interoperability only through exact mappings

Use [`clr-annotations.md`](clr-annotations.md) and the
[importer ADR](../decisions/draft-adr-clr-importer-boundary.md). Admit complete declaration families
and standard CLR attributes only when Kotlin type, contract, stability, call, and backend-binding
semantics are all specified.

Do not flatten property/ref/out state, bypass Common smart-cast stability, or infer a declaration
role from an attribute name.

### 4. Close the remaining draft ABI decisions before wider breadth

The following drafts must be accepted, revised, or explicitly excluded before third-party binary
publication:

- [generic and variant interface ABI](../decisions/draft-adr-generic-interface-abi.md);
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

- enums, pending their collection and static-initialization prerequisites;
- general annotation classes, use-site targets, retention, and runtime reflection;
- `KClass`, class literals, `typeOf`, and broad reflection;
- value/inline classes;
- inline/reified functions and cross-module inlining;
- coroutine state machines and `Task`/`ValueTask` exports;
- concurrency, volatility, synchronization, and atomics;
- `CharSequence`, `Appendable`, `StringBuilder`, and `lateinit`;
- collection/stdlib families outside admitted Common dependency closures; and
- broad Gradle/KMP distribution integration beyond the current target model.

An adjacent feature must not assume a parked representation. In particular, value classes
constrain generic interfaces; coroutines constrain callables and cancellation; annotation classes
constrain reflection and custom-attribute emission; enums consume collection identity.

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
