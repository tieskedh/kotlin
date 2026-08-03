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
- [`common-io-source-partition.md`](common-io-source-partition.md) preserves a
  completed programme whose durable rules now live in the runtime/stdlib ADR.
- [`superseded-hybrid-exception-identity.md`](superseded-hybrid-exception-identity.md)
  preserves the exception design replaced by the classified-carrier model.

Line references inside a snapshot resolve against the commit named by that
snapshot, not necessarily against the current tree. Do not rewrite snapshots
to make them look current. If later evidence changes a conclusion, record the
new evidence in an active programme or ADR and keep the old snapshot intact.
