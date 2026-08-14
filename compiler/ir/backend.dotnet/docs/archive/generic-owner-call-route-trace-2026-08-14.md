# Generic-owner compiler-indexed call-route trace — 2026-08-14

## Outcome

The versioned static generic-owner route artifact now has an executable
same-compilation join. An explicit architecture-test product records the
compiler's original call-site index immediately before every analyzed call.
The resulting profile is derived from Kotlin IR and actual application
execution; no C# workload author assigns route weights.

Production compilation and published artifacts remain unchanged. The hook has
no CLI option, Runtime helper, KLIB record, emitter policy, or physical-name
lookup. It is enabled only when the test harness passes the exact private
module-local recorder IR declaration to the backend.

## Semantic insertion point

The planner retains the exact `IrCall` associated with each census entry. In
the explicit trace product only, the lowering replaces that call with this
sequence:

1. evaluate every present receiver and argument once into a temporary, in the
   original unified IR argument order;
2. invoke the private `(Int) -> Unit` recorder with the compilation index; and
3. execute the original call using the temporaries.

Nested calls are transformed before their parent and retain their independent
indices. Identity maps and an identity-backed completion set require every
analyzed call to be instrumented exactly once. The recorder must be a private
top-level function in the same module, have one regular `Int` parameter, return
`Unit`, have no type parameters, not be suspend, and have a body.

This placement preserves receiver/argument evaluation count and order. A
failure while evaluating an argument produces no event because invocation was
never attempted; a failure thrown by the callee is recorded as an attempted
call. The original dispatch expression is not reconstructed.

## Closed trace bundle

Each lane owns exactly three files:

- `generic-owner-call-routes.tsv`, copied byte-for-byte from the compiler-
  derived producer route artifact;
- `generic-owner-call-route-counts.tsv` schema 1, which joins the route-file
  SHA-256 and one non-negative count to every sparse producer site index; and
- `generic-owner-call-route-trace.properties` schema 1, which fingerprints the
  route file, count file, and instrumented assembly and records target and
  producer/unrelated/all event totals.

The runner derives hashes from the exact canonical UTF-8 strings it writes,
without reading its own outputs back as undeclared Gradle inputs. A process-
local concurrent registry carries only the canonical route text between the
producer handler and runner and consumes it once. The external PowerShell tool
then independently reads every file, checks the closed file set, codecs,
hashes, joins, ordering, logical-key Base64, target profile, exact site vector,
semantic aggregates, frontend equality, and cross-profile equality.

## Observed hostile profile

All four PSI/LightTree × Framework 4.8/net10 lanes produced byte-identical
route and count artifacts:

| Route requirement | Static sites | Dynamic producer events |
|---|---:|---:|
| producer-erased owner | 24 | 24 |
| exact typed entry candidate | 11 | 11 |
| semantic capability | 4 | 4 |
| missing capability | 1 | 1 |
| **total producer** | **40** | **40** |

The equality of those aggregate columns is not assumed as sufficient proof.
The exact vector has call-site 2 at zero events, call-site 3 at two events, and
every other producer site at one. The two exact-route differences cancel only
in aggregate. Nine additional complete-census events belong to unrelated
generic owners, yielding 49 total events; they remain auditable rather than
being silently discarded by producer filtering.

PSI and LightTree also produce the same instrumented assembly hash within each
target profile. Framework execution uses the registered CLR 4.8 host; net10
uses CoreCLR. The profiles are independent runtime lanes, not two reference
sets executed by one host.

## Production-inert proof

After the trace lanes, a fresh normal net10 paired application corpus was
generated with the property absent and compared against the bundle archived by
the immediately preceding feature. All 17 files in each frontend lane were
SHA-256-identical, including the production producer/consumer DLLs, Runtime and
Stdlib DLLs, generated candidate/C# products, family and route artifacts, and
the closed application manifest. The recorder and transformation therefore do
not leak into ordinary compilation or shared configuration.

## Boundary and next gate

The recorder prints one line per event. That makes the profile an exact
correctness and frequency oracle for this bounded corpus, but intentionally
invalidates any timing, allocation, startup, or scheduling measurement taken
from the instrumented product. The hostile corpus is still adversarial rather
than representative.

The next execution-weight foundation is a bounded counter table with one final
flush outside the measured workload. It must retain the same compiler-index
join and semantic insertion point, handle complete representative application
compilations, and keep collection runs separate from Framework 4.8 and .NET 10
JIT/ReadyToRun/trimming/NativeAOT performance runs. Neither this trace nor a
later weighted distribution authorizes production `C<T>` admission by itself.

## Verification

`verify-generic-owner-call-route-traces.ps1` generated and independently
verified all four trace lanes. A combined-corpus pass proved cross-profile
route/count equality. The ordinary application verifier regenerated both
net10 frontends and the explicit 34-file pre/post hash comparison found no
difference.

The final strict aggregate completed in 3,063.2 seconds. Direct JUnit audit
covers one `dotnet.ir` XML file with six tests, 187 FIR XML files with 2,085
tests, and two integration XML files with 125 tests: 190 files and 2,216 tests
in total, with zero failures, errors, or skips.
