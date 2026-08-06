# Kotlin/.NET documentation

This index separates bootstrap rules, current state, future programmes,
normative decisions, and historical evidence. It must remain navigational;
do not turn it into another architecture summary.

## Authority and ownership

| Owner | Responsibility |
| --- | --- |
| [`../AGENTS.md`](../AGENTS.md) | Self-contained bootstrap contract and non-negotiable contribution rules |
| [`../STATUS.md`](../STATUS.md) | Current branch, latest full verification, active work, blockers, and next bounded items |
| [`decisions`](decisions) | Durable representation and ABI decisions, invariants, consequences, and rejected alternatives |
| [`programmes/way-forward.md`](programmes/way-forward.md) | Future gates, ordering, and open work packages |
| Active programme documents | Current scope, prerequisites, and exit conditions for one workstream |
| Git | Chronological implementation history |
| Tests and CI | Executable evidence |
| [`archive`](archive) | Immutable snapshots and superseded design history |

Implementation authority descends in this order:

1. accepted Kotlin language semantics and repository-wide compiler contracts;
2. accepted target ADRs;
3. current status, then the way forward and active programme gates;
4. verified findings in historical reviews;
5. supplemental or unverified review claims.

If sources disagree, do not average them. Reproduce or statically prove the
behavior, amend the owning ADR, and only then implement an ABI-bearing choice.

## Active programmes

- [`programmes/way-forward.md`](programmes/way-forward.md) owns release gates and the
  ordered pre-ABI programme.
- [`programmes/common-collections.md`](programmes/common-collections.md)
  keeps Common and the stdlib generator authoritative while expanding the
  collection product in bounded dependency closures.
- [`programmes/clr-annotations.md`](programmes/clr-annotations.md)
  tracks the still-open standard CLR metadata mappings. Durable importer
  authority rules belong in the importer ADR.
- [`programmes/compiler-architecture.md`](programmes/compiler-architecture.md)
  tracks the remaining module/package ownership corrections.
- [`programmes/inline-functions.md`](programmes/inline-functions.md)
  owns the selected ordinary inline/KLIB IR infrastructure programme while
  keeping reified and suspend inline support separate.

## Foundational decisions

- [Self-describing Kotlin/.NET library DLL](decisions/adr-self-describing-dotnet-library-dll.md)
- [CLR importer boundary](decisions/draft-adr-clr-importer-boundary.md)
- [`System.Object` as the physical `Any` foundation](decisions/system-object-any.md)
- [Logical `T : Any` with an unconstrained CLR parameter](decisions/non-null-generic-upper-bound.md)
- [`@InlineOnly` physical CLR ABI](decisions/inline-only-physical-abi.md)
- [Classified `CharSequence` carrier](decisions/char-sequence-carrier.md)
- [Common `Comparable` over CLR interface views](decisions/comparable-clr-views.md)
- [Kotlin-owned `Appendable` and `StringBuilder`](decisions/appendable-string-builder.md)
- [Classified CLR exception model](decisions/classified-clr-exceptions.md)
- [Primitive scalar carriers](decisions/primitive-scalars.md)
- [Kotlin-owned primitive-array wrappers](decisions/primitive-arrays.md)
- [Classified `Array<*>` erased view](decisions/star-projected-arrays.md)
- [Bounded `Array<out E>` read-only CLR view](decisions/bounded-output-projected-arrays.md)
- [Reified array operations reuse ordinary substituted carriers](decisions/reified-array-operations.md)
- [Nominal `KClass` and class literals over classified CLR evidence](decisions/kclass-and-class-literals.md)
- [Parameterless Kotlin marker annotation classes](decisions/marker-annotation-classes.md)
- [Semantic erasure and canonical ABI for Kotlin-owned generic classes](decisions/generic-class-erased-identity.md)
- [Runtime-typed collection-to-array allocation](decisions/collection-to-array.md)
- [Profile-aware interface defaults](decisions/adr-profile-aware-interface-default-implementations.md)
- [Erased ABI for Kotlin-owned generic interfaces](decisions/generic-interface-erased-identity.md)
- [Ordinary Kotlin enums as reference classes](decisions/ordinary-enum-reference-classes.md)
- [Ordinary ranges, progressions, and primitive iterators](decisions/ordinary-ranges-and-progressions.md)
- [Common contracts with additive CLR projections](decisions/common-contracts-product.md)
- [Callable and callable-reference ABI](decisions/draft-adr-callable-and-reference-abi.md)
- [Runtime and stdlib product ownership](decisions/runtime-and-stdlib-ownership.md)
- [CIL and PE production direction](decisions/cil-and-pe-production.md)

## Interop, integration, and supporting decisions

- [Generic nullability and covariant returns](decisions/adr-hybrid-generic-nullability-and-covariant-returns.md)
- [C# interface source authoring](decisions/adr-csharp-interface-source-authoring.md)
- [Companion/static placement and initialization](decisions/adr-companion-static-placement-and-initialization.md)
- [Static-initialization failures](decisions/adr-kotlin-static-initialization-failures.md)
- [Friend assemblies and compiler ABI](decisions/adr-friend-assemblies-and-compiler-abi.md)
- [.NET platform and target frameworks](decisions/dotnet-platform-and-target-frameworks.md)
- [Compiler and Gradle integration](decisions/compiler-and-gradle-integration.md)
- [Explicit C# export surface](decisions/draft-adr-explicit-csharp-export-surface.md)

## Implementation and verification

- [Backend codegen conventions](implementation/backend-codegen-conventions.md)
- [Interface ABI conformance](verification/interface-abi-conformance.md)

## Historical evidence

[`archive/README.md`](archive/README.md) defines the archive contract and
indexes immutable review, upstream-sync, completed-programme, and superseded
design snapshots. Snapshot claims are actionable only after re-verification
against the current tree.

## Maintenance rules

1. An ADR contains no current test score or commit log.
2. A `draft-` filename must agree with `Status: Draft`; accepted pre-ABI
   decisions use an accepted filename and explicit pre-ABI status.
3. Accepted ADRs contain decisions, invariants, consequences, and freeze
   conditions, not a growing implementation diary.
4. Programme documents contain only current scope, gates, and next steps.
5. Archived reviews and probes are immutable snapshots.
6. `AGENTS.md` links to feature decisions instead of copying their full
   implementation and probe history.
7. A semantic or ABI change updates its owning ADR in the same feature commit.
