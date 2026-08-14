# Generic-owner call-route census — 2026-08-14

## Outcome

The production-inert generic-owner planner now derives a static receiver-route
census from Kotlin IR. It records evidence only: production generic owners
remain erased, the emitter does not consume the result, and no DLL or KLIB ABI
is changed.

Every call whose receiver is a Kotlin-owned generic class receives one of three
provenances:

- `EXACT_CONSTRUCTION` when every traced producer retains the same physical
  construction required at the call;
- `SEMANTIC_VIEW` when the producer is known but the call uses a star,
  projected, widened, or otherwise incompatible view; or
- `UNRESOLVED` when an open or unsupported producer prevents proof.

Local calls then require an exact typed entry, a semantic capability, or a
recorded missing capability. External calls remain
`EXTERNAL_FAMILY_RECORD_REQUIRED` until a decoded producer catalog claims the
exact logical member key. That producer-scoped resolver yields an exact typed
entry, semantic capability, missing capability, or the producer's deliberate
erased-owner classification. An unrelated producer artifact leaves the route
unresolved.

## Provenance graph

The fixed-point analysis follows receiver values through definitions,
assignments, branches, function returns, private fields, constructor/property
state, and arguments to closed local/private call boundaries. Constructor
expressions introduce exact provenance. Casts preserve their input provenance
and never manufacture exactness.

An open signature is not automatically semantic. A closed invariant public
signature such as `HostileTypedStore<String>` uniquely fixes the physical
construction and remains exact. A star projection or a declaration-variant
owner does not. This preserves truthful C# `C<string>` signatures where they
are objectively sufficient without treating Kotlin covariance as CLR
invariance.

External IR declaration stubs are excluded from the current-compilation graph.
The initial implementation incorrectly visited an external `Function2`
constructor and attempted to create a local caller identity for it; the final
boundary permits external declarations only as call targets. Kotlin stdlib
owners such as `Array` likewise remain unresolved when presented with an
unrelated user-library family artifact.

## Default arguments

Class default-argument lowering replaces a member call with a static helper and
moves the dispatch receiver into an ordinary parameter. A receiver-only scan
would silently omit those calls. The census uses the backend's existing
source-to-dispatcher map and moved-receiver marker to join the helper back to
the original logical member. External default helpers are joined through their
already bound producer declaration record. No `$default` name inference is
used.

The hostile corpus includes a star-projected `label()` default call whose
member family has no capability dispatcher. It is therefore retained as a
real `MISSING_CAPABILITY` obligation rather than disappearing from the census
or being guessed as an exact call.

## Separate-compilation census

The separate hostile application contains 40 static call sites owned by its
library producer catalog:

| Resolved requirement | Static sites |
|---|---:|
| producer-erased owner | 24 |
| exact typed entry candidate | 11 |
| semantic capability | 4 |
| missing capability | 1 |

These figures are structural call-site counts, not execution frequencies and
not benchmark weights. The earlier handwritten hostile benchmark deliberately
used three typed versus 24 semantic regular calls per iteration; this census
does not validate that dynamic ratio. Dynamic weights still require execution
of complete applications.

The fixture additionally pins exact invariant parameters, star parameters,
exact and star fields, exact returns, exact-plus-star branch merges, local and
public boundaries, direct and separate compilation, and the external
default-helper path. Renaming caller, owner, and member diagnostic labels does
not change external resolution. Replacing a logical member key with an unknown
key leaves it unresolved and cannot be recovered from those labels.

## Architectural consequence

The result supports a gradual internal optimization without a gradual public
ABI switch. Exact routes can eventually stay on `C<T>` typed entries; only the
semantic/unresolved slice needs the non-generic capability. Missing
capabilities are explicit design work, and producer-erased owners remain
objective exclusions rather than being forced into a partial family.

The census is not migration authority. It neither proves representative
dynamic frequency nor selects which owners become CLR generic. The next gate
is to collect the same compiler-derived routes from complete representative
applications, execute those applications on Framework 4.8 and every .NET 10
deployment lane, and compare route frequency, state representation, size, and
time without handwritten weights.

## Verification

Focused PSI and LightTree direct/separate hostile oracles execute on Framework
CLR 4 and .NET 10: eight tests and zero failures, errors, or skips. The final
strict aggregate completed in 2,935.6 seconds. Direct audit covers 190 JUnit
XML files and 2,216 tests with zero failures, errors, or skips.
