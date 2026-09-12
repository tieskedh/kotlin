# Common input policy in class-slot bridges

This production repair starts from `5ff1de08d0`. Current integration belongs in
[`../../STATUS.md`](../../STATUS.md); the durable rule belongs in the
[covariant-return ADR](../decisions/adr-hybrid-generic-nullability-and-covariant-returns.md).

## Reproduction and repair

The erased inverse of a candidate foreign-input feature exposed an older
production defect. A specialized `get(Int): Int?` override reached through an
erased generic base received a string candidate. The interface bridge could
accept that object, but the downstream class-slot bridge cast it to Int before
Common's NULL-default check. The result was InvalidCastException instead of
null. The candidate feature was parked intact in stash
`a5a66cf47bf4279d9b5b92524fb4c9922863f77e`; it is not part of this repair.

A standalone lib/middle/main fixture reproduces the identical failure on the
unchanged base compiler. The fix makes DotNetCovariantReturnBridgeLowering
consume the existing Common SpecialBridgeMethods policy before casting into
the selected forwarding body. That source owns checked argument count and the
wrong-input result. No declaration/member-name table was added to this pass,
and no Common source or emitted signature was changed.

The guard uses the selected body's parameter domain. If operation routing
selected a broad semantic endpoint, a more restrictive natural wrapper must
not regain authority through this check. Ordinary methods lacking a Common
policy retain their original casts and failure behavior. The bridge continues
calling the selected body virtually and does not clone that body or state.

JVM BridgeLowering and JS BridgesConstruction already compose Common input
checks with bridge delegation; Wasm inherits the JS construction. Native's
TypeSafeBarrierDescription provides the corresponding checked bridge behavior.
The existing .NET interface bridge uses this Common source as well. The missing
composition was specifically the later class-slot adapter, not a different
language rule or a new representation.

## Evidence and scope

The three-assembly Kotlin fixture covers null and incompatible value/reference
inputs, typed matches/misses, nullable keys, deeper virtual overrides, and
Common NULL, FALSE and MINUS_ONE outcomes. Counters prove invalid inputs never
enter the specialized body while valid inputs still dispatch to the leaf.
A separate production fixture distinguishes the nominal key Id(1) from its
underlying boxed Int.
A strict non-Common method still fails on an invalid unchecked use and does not
acquire a fabricated default return.

Physical MethodDef signatures and MethodImpl ownership remain unchanged; only
the generated adapter body gains the authorized pre-cast guard. The separate
Kotlin producers are assembled and executed on Framework 4.8 and .NET 10. The
focused candidate matrix also exercises existing physical covariant slots,
closed semantic inputs and ordinary C# split-result override chains.

The nominal-key class bridge is still unavailable in the candidate epoch. With
the new guard removed, the existing forwarding call already offers a nominal
Id parameter to an underlying Int target and is rejected before emission. With
the guard, the earlier instance check exposes the same mistaken input-carrier
assumption as a redundant boxing call. This is a separate pre-existing
nominal-parameter adaptation gap, not permission to flatten Id to Int or a
reason to remove the production value-class regression. The candidate matrix
does not claim this unadmitted shape; its failed diagnostic is preserved.

This changes production-selected CIL, so the prior full gate cannot be inherited.
The fresh full aggregate passes 2,914 tests in 213 suites: backend 409/23,
physical CLI model 6/1, FIR2IR 2,371/187 and CLI/library integration 128/2.
Direct JUnit XML audit finds zero failures, errors or skips. Both new fixtures
execute under PSI and LightTree on Framework 4.8 and .NET 10.

The actual unfiltered FIR2IR dotNetTest task was explicitly rerun before the
backend.dotnet:dotNetTest aggregate, with `--max-workers=1
--no-configuration-cache -q` and no rehearsal property. The full run took about
48 minutes. The unchanged physical CLI model remained up to date; its complete
six-test XML was audited too. ABI 71, artifact schema 22 and surface 62 are
unchanged. This full checkpoint supersedes `cafb56a4e8`.

The candidate matrix passes 16 tests in four suites, zero failures/errors/skips.
It runs CovariantBridgeSpecialInput, CovariantReturnPhysicalSlots,
GenericOwnerClosedSemanticInputBridge and GenericOwnerForeignSplitResult in
each PSI/LightTree box class on both runtimes, with exact generated method
filters and `-Pkotlin.dotnet.genericOwnerRehearsal=true`. The three semantic
file SHA-256 hashes match across final candidate and full production gates.

Evidence directory: `D:\CodexTemp\covariant-input-barrier-20260912`.
`baseline.xml` retains the reproduction without the fix; `focused-green.xml`
retains the initial passing expanded Kotlin fixture. The original feature's
candidate and failed inverse are in `D:\CodexTemp\owner-admission-20260912`.
`nominal-production-green.xml` and `nominal-without-guard.xml` retain the nominal
investigation. `candidate-16.zip` and `full-2914.zip` contain final XML;
`semantic-hashes.json`, `verification.json` and `audit.ps1` record the exact
checkpoint, counts and audit. All temporary guard-removal diagnostics were
restored before the final frozen gates.

After this repair is green and pushed, restore the parked feature by its exact
stash ID, resolve only overlapping documentation, and reverify its candidate
and production inverse. Do not remove its failed-input assertions or claim its
earlier candidate-only evidence completes that feature.
