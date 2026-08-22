# Reified generic-interface exact-input family record

Date: 2026-08-22

## Question

How can a separate Kotlin/.NET consumer distinguish the exact-input sibling
required by a broad-input covariant interface from either the natural
covariant interface or its non-generic semantic capability?

Reconstructing that third TypeDef from a generated name would make the new
representation non-atomic and would let producer and consumer disagree about
the physical family. Treating the exact sibling as optional would be worse: a
published broad member could then name a physical route which does not exist.

## ABI 45 record

The published generic-interface family now has an optional, producer-owned
`exactOwnerPath`. Two new structural member roles require that path:

- `BROAD_FIXED_BARRIER_INPUT` for a direct candidate with an authoritative
  incompatible-result barrier; and
- `BROAD_NESTED_SEMANTIC_INPUT` for a nested candidate whose semantic body must
  receive the original broad object.

The roles describe Kotlin semantics and physical routing. They do not contain
`Collection`, `Set`, package, or declaration-name checks.

The family constructor and codec fail closed in both directions:

- either broad role without an exact owner is invalid;
- an exact owner without a broad role is invalid;
- the exact owner cannot alias the natural or semantic owner; and
- its metadata arity must equal the logical generic-interface arity.

The exact path is encoded in the same atomic family record as the natural and
semantic paths. ABI and Runtime surface 45 make old readers reject the changed
payload before they can misparse it. Round-trip coverage includes a two-
parameter owner to prove that the rule is not accidentally fixed to arity one,
plus missing, unsolicited, and arity-mismatched exact-owner negatives.

## Verification

The focused physical-owner round-trip test is green, including missing,
unsolicited, aliased, and arity-mismatched exact-owner negatives. The complete
`dotNetTest` aggregate then exits zero. Direct XML audit reports 190 suites and
2,288 tests with zero failures, errors, or skips. The 187 FIR suites/2,155
tests and two integration suites/127 tests are freshly written by that run;
the unchanged six-test `dotnet.ir` root remains up-to-date from the preceding
green checkpoint.

## Boundary and next gate

This checkpoint records the required third physical identity; it does not emit
that TypeDef or admit a broad-input Kotlin owner. Existing published families
continue to encode no exact owner, and production remains erased. The next
feature must materialize the invariant exact interface, keep illegal input
members off the natural covariant TypeDef, publish exact and semantic slots,
and prove Kotlin/C# implementation and dispatch across separate compilation on
Framework 4.8 and .NET 10.
