# Generic-owner call-route manifest — 2026-08-14

## Outcome

The closed paired generic-owner application bundle now carries the compiler's
static producer-owned call-route census as a versioned, deterministic artifact.
This removes the gap between the Kotlin IR analysis and future execution
weighting: a measurement instrumenter can join counters to the exact compiler
call-site index instead of selecting routes again by source name or by a
handwritten workload model.

Production emission remains unchanged. The route artifact is generated only by
the architecture/test pipeline, is not embedded in a DLL or KLIB, and cannot
select a typed or capability call.

## Artifact identity

`generic-owner-call-routes.tsv` schema 1 contains, for every route classified by
the selected producer artifact:

- the original index in the complete compilation call-route census;
- the optional caller KLIB logical binding key;
- the required producer-member KLIB logical binding key;
- receiver provenance; and
- the resolved route requirement.

The manifest deliberately contains no caller source name, callee source name,
CLR owner name, MethodDef name, target profile, or temporary path. Diagnostic
names can all be changed without changing its bytes. An unresolved external
route cannot be serialized, and the decoder rejects stale schema, truncation,
duplicate or unordered indices, non-canonical text, invalid provenance/route
pairs, and missing logical member identity.

Indices remain those of the complete compilation census rather than being
renumbered after producer filtering. The hostile producer therefore contributes
40 records across indices 0 through 48, with nine gaps occupied by unrelated
external generic owners. This makes an index usable as the exact join to
instrumentation produced from the same compilation. It is intentionally not a
source-stable identifier across changed programs or compiler revisions.

All 40 current caller logical bindings are absent. Those call scopes have no
published KLIB declaration binding, and the compiler does not manufacture one
from a source label. The call-site index remains the per-compilation identity;
the callee logical key remains the semantic aggregation identity.

## Closed bundle contract

The paired application manifest advances atomically from schema 1 to schema 2
and fingerprints the route artifact. The PowerShell verifier requires the new
file in the exact closed file set, checks its hash and canonical grammar, and
pins the compiler-derived hostile distribution:

| Resolved requirement | Static sites |
|---|---:|
| producer-erased owner | 24 |
| exact typed entry candidate | 11 |
| semantic capability | 4 |
| missing capability | 1 |

PSI and LightTree must produce byte-identical route artifacts. Framework 4.8
and net10 bundles must also agree on the same hash: the Kotlin route graph is
profile-neutral even though their physical assemblies and outer bundle
manifests differ.

## Boundary of the result

This checkpoint makes compiler-derived route evidence portable and
reproducible; it does not supply dynamic execution counts. The hostile corpus
is still deliberately adversarial and is not representative application
evidence. The next gate is compiler-indexed instrumentation over complete
applications, followed by independent Framework 4.8 and .NET 10 deployment-
lane measurements. A static site count must never be treated as an execution
frequency.

## Verification

The exact separate-compilation application lanes pass through PSI and
LightTree on .NET 10 and Framework CLR 4. Each closed bundle is decoded,
fingerprinted, compared across frontends, and executed as the candidate,
production-erased Kotlin product, and direct C# product. A combined existing-
corpus audit additionally executes the cross-profile route-hash guard.

The final strict aggregate completed in 2,819.9 seconds. Direct JUnit audit
covers one `dotnet.ir` XML file with six tests, 187 FIR XML files with 2,085
tests, and two integration XML files with 125 tests: 190 files and 2,216 tests
in total, with zero failures, errors, or skips.
