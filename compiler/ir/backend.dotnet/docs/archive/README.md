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

Line references inside a snapshot resolve against the commit named by that
snapshot, not necessarily against the current tree. Do not rewrite snapshots
to make them look current. If later evidence changes a conclusion, record the
new evidence in an active programme or ADR and keep the old snapshot intact.
