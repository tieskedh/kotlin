# Kotlin/.NET documentation archive

This directory contains immutable review, rebase, probe, or superseded-design
snapshots. They preserve provenance for decisions and implementation history,
but they do not define current branch state or normative architecture.

Use:

- [`../../STATUS.md`](../../STATUS.md) for current branch, verification, and
  work state;
- [`../programmes/way-forward.md`](../programmes/way-forward.md) for future gates and
  ordering; and
- [`../decisions`](../decisions) for active draft and accepted decisions.

Archived snapshots:

- [`reified-generic-interface-foreign-classifier-2026-08-19.md`](reified-generic-interface-foreign-classifier-2026-08-19.md)
  records declaration-erased `is`/`as?` over ordinary foreign CLR producers,
  same-object safe casts, delayed typed-result failure, classifier-admitted
  multi-construction objects, and Framework 4.8/.NET 10 execution.
- [`reified-generic-interface-ordinary-foreign-producer-2026-08-19.md`](reified-generic-interface-ordinary-foreign-producer-2026-08-19.md)
  records the sibling natural/semantic interface correction, cached unique-
  construction fallback for ordinary precompiled CLR producers, real star
  storage and identity, exception transparency, and ambiguity rejection on
  Framework 4.8 and .NET 10.
- [`generic-owner-foreign-override-deployment-2026-08-19.md`](generic-owner-foreign-override-deployment-2026-08-19.md)
  records the closed self-producing Kotlin base/override plus ordinary C#
  subclass deployment oracle, allocation-free probe audit, and successful JIT,
  ReadyToRun, full-trimming, and real NativeAOT execution.
- [`common-selector-min-max-family-2026-08-18.md`](common-selector-min-max-family-2026-08-18.md)
  records the complete 40-declaration selector min/max supported-classifier
  release, inline loop-entry stack-baseline correction, exact selector and
  floating semantics, direct Kotlin/C# evidence, and the final 2,262-test
  proof.
- [`common-natural-min-max-family-2026-08-18.md`](common-natural-min-max-family-2026-08-18.md)
  records the complete 52-declaration natural min/max supported-classifier
  release, bounded logical-element-derived physical naming, exact floating and
  empty/tie semantics, direct Kotlin/C# evidence, and the final 2,258-test
  proof.
- [`common-all-distinct-family-2026-08-18.md`](common-all-distinct-family-2026-08-18.md)
  records the complete 20-declaration supported-classifier distinct aggregate
  release, carrier-neutral byte-domain helper, absence of public unsigned .NET
  surface, direct Kotlin/C# evidence, and the final 2,254-test proof.
- [`common-all-equal-family-2026-08-18.md`](common-all-equal-family-2026-08-18.md)
  records the complete 20-declaration supported-classifier equality aggregate
  release, exact selector/floating semantics, the `allDistinct` unsigned-helper
  blocker, direct Kotlin/C# evidence, and the final 2,250-test proof.
- [`common-eager-iterable-sequence-consumers-2026-08-18.md`](common-eager-iterable-sequence-consumers-2026-08-18.md)
  records the exact seven-declaration eager Sequence-consumer release, stable
  logical-selector physical names, direct Kotlin/C# evidence, the physical
  Sequence-owner boundary, and the final 2,246-test proof.
- [`common-eager-iterable-windowing-2026-08-18.md`](common-eager-iterable-windowing-2026-08-18.md)
  records the exact four-member eager Iterable window/chunk family, its Common
  sized-list factory prerequisite, both execution routes, and the final
  2,242-test Framework/CoreCLR proof.
- [`generic-owner-octo-tree-ordinary-body-closure-2026-08-17.md`](generic-owner-octo-tree-ordinary-body-closure-2026-08-17.md)
  records schema-13 declaration-independent fields, private non-KLIB
  implementation MethodDefs, the complete OctoTree algorithms, and executable
  state/body evidence on CLR 4 and CoreCLR.
- [`generic-owner-octo-tree-metadata-reflection-product-2026-08-17.md`](generic-owner-octo-tree-metadata-reflection-product-2026-08-17.md)
  records classifier-contextual physical MethodDef normalization, hidden
  capability/private methods, exhaustive raw ECMA-335 table inspection, and
  the missing Leaf/Branch rendering MethodDef repair.
- [`generic-owner-octo-tree-root-product-2026-08-17.md`](generic-owner-octo-tree-root-product-2026-08-17.md)
  records the outer open Tree, semantic object root, compiler-census capability
  calls, typed/non-generic C# surface, inherited base dispatchers, and explicit
  declaration-independent scenario-body boundary.
- [`generic-owner-octo-tree-state-capabilities-2026-08-17.md`](generic-owner-octo-tree-state-capabilities-2026-08-17.md)
  records the Leaf object-boundary read/write and Branch `System.Array` read
  capabilities over the same true generic state, incompatible pre-mutation
  failure, array identity, and private/final interface maps.
- [`generic-owner-octo-tree-strict-capability-2026-08-17.md`](generic-owner-octo-tree-strict-capability-2026-08-17.md)
  records the strict set non-generic interface slots, object-to-`!T` explicit
  dispatchers, most-derived C# override routing, incompatible pre-mutation
  failure, and private/final interface maps.
- [`generic-owner-octo-tree-typed-callables-2026-08-17.md`](generic-owner-octo-tree-typed-callables-2026-08-17.md)
  records the decoded abstract/override Node.set typed family, exact Leaf/
  Branch identity accessors, direct base-reference C# dispatch, and retained
  non-final virtual slots on final TypeDefs.
- [`generic-owner-octo-tree-branch-product-2026-08-17.md`](generic-owner-octo-tree-branch-product-2026-08-17.md)
  records the decoded Branch `Node<T>[]` field, fixed eight-element base-root
  initializer, exact base/this constructors, and direct Framework/.NET C#
  open/closed carrier and execution evidence.
- [`generic-owner-sealed-construction-closure-2026-08-17.md`](generic-owner-sealed-construction-closure-2026-08-17.md)
  records schema 12's abstract Kotlin-sealed base plus
  `FamilyAndAssembly` constructor closure, record-driven true-`T` Leaf product,
  positive C# construction/reflection, and rejected external C# subclass.
- [`generic-owner-complete-octo-tree-family-2026-08-17.md`](generic-owner-complete-octo-tree-family-2026-08-17.md)
  records schema 11's complete logical-keyed recursive OctoTree TypeDef,
  MethodDef, constructor, state/initializer, reflection, and atomic-closure
  graph plus the remaining record-driven candidate/C# product gate.
- [`generic-owner-physical-visibility-dispatch-2026-08-16.md`](generic-owner-physical-visibility-dispatch-2026-08-16.md)
  records schema 10's exact owner TypeDef and member MethodDef visibility/
  dispatch envelope, semantic/capability visibility invariants, and the
  remaining complete OctoTree family gate.
- [`generic-owner-producer-private-state-access-2026-08-16.md`](generic-owner-producer-private-state-access-2026-08-16.md)
  records schema 9's strict separation between logical member bindings and
  exact producer-private state MethodDefs, the semantic-object access
  invariant, canonical codec evidence, and the remaining complete OctoTree
  product gate.
- [`generic-owner-physical-state-initializers-2026-08-16.md`](generic-owner-physical-state-initializers-2026-08-16.md)
  records schema 8's fixed zeroed SZ-array initializer, exact constructor
  roots, typed state access/init composition, canonical codec evidence, and
  the remaining complete OctoTree product gate.
- [`generic-owner-state-initializer-recipes-2026-08-16.md`](generic-owner-state-initializer-recipes-2026-08-16.md)
  records compiler-derived fixed-vector recipes, separate-producer carrier
  binding, and the opposing unchecked ArrayCopy fail-closed oracle.
- [`generic-owner-path-unbound-member-signatures-2026-08-16.md`](generic-owner-path-unbound-member-signatures-2026-08-16.md)
  records the shared state/callable prototype type tree, atomic logical-key to
  TypeDef binding, recursive OctoTree getter evidence, and the remaining full
  physical-family/C# product gate.
- [`generic-owner-nonexact-call-carriers-2026-08-15.md`](generic-owner-nonexact-call-carriers-2026-08-15.md)
  records truthful object/System.Array MethodDef carriers for open-nullable and
  projected generic positions, retained exact GenericParam cases, hostile C#
  override evidence, and the then-open constructed-member binding gate.
- [`generic-owner-structural-state-carrier-2026-08-15.md`](generic-owner-structural-state-carrier-2026-08-15.md)
  records the path-unbound exact state-carrier grammar, logical producer-key
  binding, open-nullable rejection, separate OctoTree producer/consumer
  evidence, and the still-open serialized candidate/C# product gate.
- [`projected-generic-array-join-fast-path-2026-08-15.md`](projected-generic-array-join-fast-path-2026-08-15.md)
  records the retained projected `System.Array` ABI, compatible `T[]` read
  path, Framework/.NET 10 causal boxing evidence, and strict widened-value
  fallback boundary.
- [`generic-owner-octo-tree-application-census-2026-08-15.md`](generic-owner-octo-tree-application-census-2026-08-15.md)
  records the second exact repository-application census, full array joining,
  declaration-stable erased-owner classifier vectors, mixed exact/capability
  routes, and the still-open production-owner and paired-product gates.
- [`generic-owner-array-copy-application-census-2026-08-15.md`](generic-owner-array-copy-application-census-2026-08-15.md)
  records the first exact repository-application route/state distribution,
  open/erased generic-array copying correction, four-lane equality, and the
  still-open candidate/C# and representative-breadth gates.
- [`generic-array-fill-specialization-2026-08-15.md`](generic-array-fill-specialization-2026-08-15.md)
  records the exact-vector fill correction, cross-profile route measurement,
  retained erased-owner boundary, and final strict gate.
- [`generic-resized-array-copy-2026-08-15.md`](generic-resized-array-copy-2026-08-15.md)
  records the closed nullable-vector correction, bounded open projected copy
  carrier, FIR bottom-capture read boundary, and final strict gate.
- [`review-2026-07-17.md`](review-2026-07-17.md) is the consolidated review of
  branch commit `8dd89907d`.
- [`upstream-sync-2026-07-28.md`](upstream-sync-2026-07-28.md) and
  [`upstream-sync-2026-07-30.md`](upstream-sync-2026-07-30.md) preserve dated
  rebase-impact evidence.
- [`upstream-impact-2026-08-03.md`](upstream-impact-2026-08-03.md) records the
  exact 179-commit pending range, virtual-merge evidence, screened directions,
  and durable Kotlin/.NET consequences before a later rebase. Git and the exact
  range own its reproducible per-commit ledger.
- [`upstream-impact-2026-08-07.md`](upstream-impact-2026-08-07.md) records the
  exact 195-commit reviewed and integrated range, conflict-free three-path
  virtual merge, contract-level reverse-dependency and architecture audit,
  normalized compiler/export/test directions, strict post-rebase gate, and
  pure-rebase evidence. Git and the exact range own its exhaustive per-commit
  ledger.
- [`upstream-impact-2026-08-11.md`](upstream-impact-2026-08-11.md) records the
  exact 170-commit range through `d78e4a4c14`, the one non-linking-deserializer
  conflict, contract-level reverse-dependency audit, and normalized inline,
  export, IDE/KLIB, BTA, Gradle, and test implications. Git and the exact range
  own its exhaustive per-commit ledger.
- [`common-io-source-partition.md`](common-io-source-partition.md) preserves a
  completed programme whose durable rules now live in the runtime/stdlib ADR.
- [`superseded-hybrid-exception-identity.md`](superseded-hybrid-exception-identity.md)
  preserves the exception design replaced by the classified-carrier model.
- [`generic-owner-history-audit-2026-08-12.md`](generic-owner-history-audit-2026-08-12.md)
  audits the removed typed-owner/canonical-capability implementation, its exact
  widened-candidate bridge failure, reusable infrastructure, and the new
  hardest-model-first constraints.
- [`generic-owner-runtime-compilation-probe-2026-08-12.md`](generic-owner-runtime-compilation-probe-2026-08-12.md)
  records the bounded JIT/ReadyToRun/trimming success and the explicitly
  incomplete NativeAOT open-nullable construction probe.
- [`generic-owner-measurement-corpus-2026-08-13.md`](generic-owner-measurement-corpus-2026-08-13.md)
  records the fingerprinted record-driven hostile corpus, its reproducible
  JIT/ReadyToRun/full-trimming baseline, and the still-open NativeAOT link/run.
- [`generic-owner-producer-classification-catalog-2026-08-13.md`](generic-owner-producer-classification-catalog-2026-08-13.md)
  records schema 7's complete producer candidate catalog, explicit
  metadata-fixed erased-only classification, and fail-closed family join.
- [`generic-owner-native-aot-measurement-2026-08-13.md`](generic-owner-native-aot-measurement-2026-08-13.md)
  records workload version 2's race-free working-set handshake, explicit
  signed MSVC provenance, and the successful four-mode NativeAOT link/run.
- [`generic-owner-application-corpus-2026-08-13.md`](generic-owner-application-corpus-2026-08-13.md)
  records the closed paired production-erased/candidate application products,
  direct C# erased-owner surface, two-profile execution, and strict
  cross-frontend reproducibility boundary.
- [`generic-owner-paired-application-measurement-2026-08-14.md`](generic-owner-paired-application-measurement-2026-08-14.md)
  records the independent Framework CLR 4 and .NET 10 erased-versus-candidate
  measurements, the current semantic-routing cost, and the direct-interface
  reimplementation repair required by full trimming.
- [`generic-owner-route-attribution-2026-08-14.md`](generic-owner-route-attribution-2026-08-14.md)
  isolates typed entry, semantic capability, object-state, construction,
  array, method-generic, compatible override, and hostile failure costs across
  Framework 4.8 and all four .NET 10 deployment modes.
- [`generic-owner-typed-storage-attribution-2026-08-14.md`](generic-owner-typed-storage-attribution-2026-08-14.md)
  proves one compiler-derived `!T` field and exact identity access, retains the
  strict one-state capability boundary, and attributes scalar, struct, and
  nullable costs across Framework 4.8 and all four .NET 10 deployment modes.
- [`generic-owner-call-route-census-2026-08-14.md`](generic-owner-call-route-census-2026-08-14.md)
  records the production-inert fixed-point receiver provenance analysis and
  its 40-site hostile producer census.
- [`generic-owner-call-route-manifest-2026-08-14.md`](generic-owner-call-route-manifest-2026-08-14.md)
  records the versioned diagnostic-name-free application route artifact,
  closed bundle schema 2, and cross-frontend/profile reproducibility proof.
- [`generic-owner-call-route-trace-2026-08-14.md`](generic-owner-call-route-trace-2026-08-14.md)
  records exact-IR-call instrumentation, the closed compiler-indexed dynamic
  route profile across Framework CLR 4/net10 and both frontends, and unchanged
  normal-product byte evidence.
- [`generic-owner-call-route-counter-flush-2026-08-15.md`](generic-owner-call-route-counter-flush-2026-08-15.md)
  replaces per-call console transport with exact-sized thread-safe primitive
  counters and one final flush, retaining the exact four-lane route oracle and
  unchanged normal-product bytes.
- [`common-sequence-builder-closure-2026-08-15.md`](common-sequence-builder-closure-2026-08-15.md)
  records the exact Common builder/window closure, its bounded local-array and
  runtime-fill dependencies, two strict-gate regressions found and repaired,
  and the final 2,216-test Framework/CoreCLR proof.

Line references inside a snapshot resolve against the commit named by that
snapshot, not necessarily against the current tree. Do not rewrite snapshots
to make them look current. If later evidence changes a conclusion, record the
new evidence in an active programme or ADR and keep the old snapshot intact.
