# Kotlin/.NET architectural review index

This directory separates review evidence from normative design decisions and from the live work
queue. It exists so a human or AI can find the provenance of the current priorities without
copying a snapshot review into binding design law.

## Sources and authority

1. [`../archive/review-2026-07-17.md`](../archive/review-2026-07-17.md) is the full consolidated architectural
   review of branch commit `8dd89907d`. It includes the dimension reviews, red-team analyses,
   verification corrections, evidence, and file/line anchors. Preserve it as snapshot evidence.
2. An earlier classified review was supplied in the review conversation. It is supplemental. Its
   claims are actionable only after repository re-verification.
3. A later pasted "Canonical AI and Human Context" composes those two reviews. It is a useful
   digest, but it is not a third independent review and is not the repository source of truth. The
   supplied text contains the digest twice and omits a durable repository link to source 1, so it
   should not be checked in verbatim.
4. [`../../STATUS.md`](../../STATUS.md) is the canonical owner of current
   branch, verification, active-work, blocker, and next-task state.
5. [`way-forward.md`](way-forward.md) is the living future execution order and
   release-gate document. It records re-verification performed after the two
   reviews and may promote, downgrade, or split a review item. Every normative
   representation or ABI decision still belongs in an ADR under
   [`../decisions`](../decisions).
6. [`../archive/upstream-sync-2026-07-28.md`](../archive/upstream-sync-2026-07-28.md) records the six-step impact review
   and adaptations for the rebase from `0349ed5cd` to `6fb64e0c0`.
7. [`../archive/upstream-sync-2026-07-30.md`](../archive/upstream-sync-2026-07-30.md) records the 161-commit impact
   review, exact rebase verification, immediate Common/IR adaptations, and longer-term KLIB,
   Analysis API, and BTA direction for the rebase to `733a49b39`.
8. [`../archive/common-io-source-partition.md`](../archive/common-io-source-partition.md) preserves
   the completed Common I/O source-partition review and implementation evidence.
9. [`common-collections-program.md`](common-collections-program.md) selects collections as a
   first-class feature programme, keeps Common and the stdlib generator authoritative, and
   separates Kotlin identity from later explicit BCL adapters.
10. [`architecture-responsibility-audit.md`](architecture-responsibility-audit.md) classifies every
   backend production file, compares mature-target module and package ownership, and selects a
   bounded CLR load-layer correction with enforced dependency direction.

Authority order for implementation work:

1. accepted Kotlin language semantics and repository-wide compiler contracts;
2. accepted target ADRs;
3. current status, then the way forward and its release gates;
4. verified findings in the consolidated review;
5. supplemental or unverified review claims.

If two sources disagree, do not average them. Reproduce or statically prove the behavior, record
the result in `way-forward.md`, and amend the relevant ADR before implementing an ABI-bearing
choice.

## Current status

Read [`../../STATUS.md`](../../STATUS.md). This index deliberately does not
duplicate changing branch, test, blocker, or task state.

## Maintenance rule

Keep this index short. Update current state in `STATUS.md`, future issue
sequencing in `way-forward.md`, rationale and alternatives in ADRs, and
executable evidence in tests. Do not grow another all-in-one review.
