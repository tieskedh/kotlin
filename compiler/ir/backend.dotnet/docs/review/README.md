# Kotlin/.NET architectural review index

This directory separates review evidence from normative design decisions and from the live work
queue. It exists so a human or AI can find the provenance of the current priorities without
copying a snapshot review into binding design law.

## Sources and authority

1. [`../review-2026-07-17.md`](../review-2026-07-17.md) is the full consolidated architectural
   review of branch commit `8dd89907d`. It includes the dimension reviews, red-team analyses,
   verification corrections, evidence, and file/line anchors. Preserve it as snapshot evidence.
2. An earlier classified review was supplied in the review conversation. It is supplemental. Its
   claims are actionable only after repository re-verification.
3. A later pasted "Canonical AI and Human Context" composes those two reviews. It is a useful
   digest, but it is not a third independent review and is not the repository source of truth. The
   supplied text contains the digest twice and omits a durable repository link to source 1, so it
   should not be checked in verbatim.
4. [`way-forward.md`](way-forward.md) is the living execution order and release-gate document. It
   records re-verification performed after the two reviews and may promote, downgrade, or split a
   review item. Every normative representation or ABI decision still belongs in an ADR under
   [`../decisions`](../decisions).

Authority order for implementation work:

1. accepted Kotlin language semantics and repository-wide compiler contracts;
2. accepted target ADRs;
3. the current way forward and its release gates;
4. verified findings in the consolidated review;
5. supplemental or unverified review claims.

If two sources disagree, do not average them. Reproduce or statically prove the behavior, record
the result in `way-forward.md`, and amend the relevant ADR before implementing an ABI-bearing
choice.

## Current status

- Snapshot and current HEAD: `8dd89907d` at the time this index was created.
- Maturity: high-quality prototype; credible trajectory; not open for third-party binary ABI.
- Immediate objective: Gate A in [`way-forward.md`](way-forward.md).
- Required profiles: `net48` applications/libraries, `netstandard2.0` portable libraries, and
  `net10.0` applications/libraries. Profile-specific code generation is intentional.
- Selected semantic direction: Kotlin-owned primitive-array wrappers and classified, identity-
  preserving CLR exceptions. The accepted pre-ABI ADRs own the details.
- Nothing has shipped. Existing prototype artifacts have no compatibility standing until an
  explicit freeze is recorded.
- No new public runtime capability, generic-interface surface, exception mapping, array-bearing
  ABI, or interface body should be added while its P0 dependency remains open.

## Maintenance rule

Keep this index short. Update issue status and sequencing in `way-forward.md`; put rationale and
alternatives in ADRs; put executable evidence in tests. Do not grow another all-in-one review.
