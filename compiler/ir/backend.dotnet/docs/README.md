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
| [`review/way-forward.md`](review/way-forward.md) | Future gates, ordering, and open work packages |
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

- [`review/way-forward.md`](review/way-forward.md) owns release gates and the
  ordered pre-ABI programme.
- [`review/common-collections-program.md`](review/common-collections-program.md)
  keeps Common and the stdlib generator authoritative while expanding the
  collection product in bounded dependency closures.
- [`review/clr-annotation-interoperability.md`](review/clr-annotation-interoperability.md)
  tracks the still-open standard CLR metadata mappings. Durable importer
  authority rules belong in the importer ADR.
- [`review/architecture-responsibility-audit.md`](review/architecture-responsibility-audit.md)
  tracks the remaining module/package ownership corrections.

## Foundational decisions

- [Self-describing Kotlin/.NET library DLL](decisions/adr-self-describing-dotnet-library-dll.md)
- [CLR importer boundary](decisions/draft-adr-clr-importer-boundary.md)
- [`System.Object` as the physical `Any` foundation](decisions/draft-adr-system-object-any-foundation.md)
- [Classified CLR exception model](decisions/draft-adr-classified-clr-exception-model.md)
- [Kotlin-owned primitive-array wrappers](decisions/draft-adr-kotlin-primitive-array-wrappers.md)
- [Profile-aware interface defaults](decisions/adr-profile-aware-interface-default-implementations.md)
- [Generic and variant interface ABI](decisions/draft-adr-variant-interface-abi.md)
- [Callable ABI](decisions/draft-adr-erased-callable-abi.md)
- [Runtime and stdlib product ownership](decisions/draft-adr-target-stdlib-bootstrap.md)
- [CIL and PE production direction](decisions/draft-adr-il-assembly-pipeline.md)

## Interop, integration, and supporting decisions

- [Generic nullability and covariant returns](decisions/adr-hybrid-generic-nullability-and-covariant-returns.md)
- [C# interface source authoring](decisions/adr-csharp-interface-source-authoring.md)
- [Companion/static placement and initialization](decisions/adr-companion-static-placement-and-initialization.md)
- [Static-initialization failures](decisions/adr-kotlin-static-initialization-failures.md)
- [Friend assemblies and compiler ABI](decisions/adr-friend-assemblies-and-compiler-abi.md)
- [Target library profiles](decisions/draft-adr-dotnet-library-target-profile.md)
- [Gradle target and compilation model](decisions/adr-gradle-dotnet-target-and-compilation-model.md)
- [Gradle platform identity](decisions/adr-gradle-dotnet-platform-identity.md)
- [Gradle target-framework attribute](decisions/adr-gradle-dotnet-target-framework-attribute.md)
- [Generated compiler arguments](decisions/adr-generated-dotnet-compiler-arguments.md)
- [Generated Gradle compiler options](decisions/adr-generated-dotnet-gradle-compiler-options.md)
- [Explicit C# property exports](decisions/draft-adr-clr-property-exports.md)
- [Explicit C# default-argument exports](decisions/draft-adr-clr-default-argument-exports.md)
- [Property-reference ABI](decisions/draft-adr-erased-property-reference-abi.md)
- [Iterator ABI](decisions/draft-adr-erased-iterator-abi.md)

Three current files document implementation or verification mechanisms and
will move out of `decisions` when their active links are migrated:

- [Private CLR field disambiguation](decisions/adr-private-clr-field-disambiguation.md)
- [Generic data-class equality](decisions/draft-adr-generic-data-class-equality.md)
- [Semantic interface-map verification](decisions/adr-semantic-interface-mapping-audit.md)

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
