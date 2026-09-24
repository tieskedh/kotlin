# Native interface calls and source-proved safe upcasts

## Scope

This compiler-path feature starts from
`293e85858dd9a2a06e3dfff4648f5685b5a0301c`. Neither parked constructor experiment
is applied. The [preceding operation-target query](native-interface-operation-target-2026-09-13.md)
remains a production-inert model with no emitter consumer. Inspection found that
the existing retained-native emitter already issues calls against the operation's
target interface. Replacing that path merely to consume the new query would not
fix an observed defect.

This checkpoint instead verifies those real calls through hostile separately
compiled C# implementations and fixes a reproduced rejection of source-proved
native safe upcasts. The durable scope is in the
[retained native identity decision](../decisions/foreign-clr-generic-type-identities.md#native-operations-select-a-contract-not-historical-dispatch).
No Common code, Kotlin-owned representation, model routing, Runtime/Stdlib,
artifact schema, ABI or runtime surface changes. Production remains erased for
Kotlin-owned generic owners. Native imported generics keep their existing CLR
identity in both production and rehearsal.

## Reproduced failure and repair

Before the fix, a LightTree/.NET 10 candidate invocation of
`testForeignKotlinInterfaceResult` failed. The actual compiler diagnostics reject
`nativeSafeWidened` and `nativeSafeExactInt` with
`type operator SAFE_CAST is not supported`. The later C# compiler consequently
cannot find those emitted methods. These inputs already prove either an ordinary
native reference-covariance conversion or an identical value-argument interface:

```kotlin
fun widen(source: NativeProducer<String>?): NativeProducer<Any>? =
    source as? NativeProducer<Any>

fun exact(source: NativeProducer<Int>?): NativeProducer<Int>? =
    source as? NativeProducer<Int>
```

The old emitter accepted constructed native throwing casts but rejected every
constructed native safe cast at this branch. The repair admits only a retained
native generic target whose source reference carrier is physically assignable
to it, after retaining the target-to-destination check. It emits the source once
through the normal source-recovery/reference-conversion path. It does not
relabel an object-domain load, assert non-null, add a membership test, or mint
new provenance. Existing source recovery can still fail where required.

An unrelated native construction, an unknown `Any?`, or native value-type
variance is not admitted. In particular, a compiler proof of incompatibility is
not permission to turn an unchecked safe cast into `null`. BK-1's Kotlin-owned
scope is unchanged. The earlier repro also used a public `Throwable` argument
whose existing physical name mangling made the C# call incorrect; the test now
uses `Any`, since it only compares exception identity. No naming fix was made.

The original failure XML is retained locally at
`D:\CodexTemp\native-operation-calls-20260913\before-fix.xml`. Its test-data and
harness SHA-256 values were respectively:

```text
7344048FA9BC98D60EC2CA888B92753181AC2683E13089B48845F4BCE0B2C782
71B422D588D1F8820FAD96A62B5C62DCA90779C2B8F1B693006B16F4D6E23F7A
```

## Executable coverage

The existing `foreignKotlinInterfaceResult` fixture imports honest native
interfaces from a separate C# contract DLL. Kotlin then produces its assembly;
only afterwards does a separate C# consumer define the hostile implementations.
Thus the test does not rely on importing an inconsistent Kotlin supertype graph.
Its existing Kotlin-owned result/state checks remain in place.

The new assertions cover:

- Different explicit string and object bodies on one native receiver: exact,
  widened and checked-after-`Any` Kotlin operations must reach their respective
  target contracts, not a historically selected implementation.
- Two reference constructions, string and string-array, with no exact object
  interface row and both InterfaceImpl orders. Kotlin's target operation is
  compared with that same operation in C# on each runtime. The test deliberately
  does not prescribe which variant source wins.
- Exact integer results without boxing; failed throwing conversion of an
  integer-only producer to the native object construction before any read.
- Nullable source-proved reference covariance and identical integer
  constructions, receiver identity, and a side-effecting foreign provider
  evaluated once, including its null result.
- C# interface reimplementation below a virtual base member, original call
  counters, exception identity and Kotlin catch behavior.
- Retained generic MethodDef and Kotlin entry signatures in loaded CLR
  metadata; emitted typed `callvirt` owner constructions and `!0` slots; no
  reflection helper or receiver allocation; no additional cast or membership
  check for the source-proved fixture cases.

The existing native-interface CLI integration test additionally rejects three
library-mode casts for each parser/profile: `Any? as? Producer<String>`,
`Producer<String>? as? Producer<Int>` and `Producer<Int>? as? Producer<Any>`.
It requires the intended unsupported-safe-cast diagnostic and no published DLL
or admitted rejected method. These are current admission limits, not a claim
that every unimplemented native cast is intrinsically invalid Kotlin.

## Verification

The focused candidate invocation passed on 2026-09-13:

```text
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --tests "*testForeignKotlinInterfaceResult" -q
```

Direct XML audit found four suites / four tests, covering PSI and LightTree on
Framework 4.8 and .NET 10, with zero failures, errors or skips. Each fixture
assembled and ran its separately compiled Kotlin/C# graph and physical checks.
The copied XML lives in the local evidence directory's `candidate` subdirectory.

Two test-authoring mistakes were corrected before that green run: new
destructuring uses the repository's positional `[]` syntax, and the no-receiver-
allocation assertion permits the existing null-result failure exception.
Neither correction weakens dispatch expectations or removes Kotlin null checks.
Independent read-only reviews checked native MethodDef resolution, source
recovery, physical assignability and the negative admission boundary; they ran
no competing toolchain tasks.

The production-selected emitter change required a new full target gate, not
inheritance of `3724aeab9cf38649229b842c4b9d6382862da9cd`. The user requested an
OS-switch pause before that gate finished. Its explicitly rerun unfiltered
FIR2IR task passed on 2026-09-13 with 187 suites / 2,383 tests and zero failures,
errors or skips. All four fixture inverses were individually found in that
fresh full XML. CLI/library integration was cancelled during execution at the
pause; that cancellation was not a test failure or a successful full gate.

```text
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun :compiler:tests-integration:dn --rerun :compiler:backend.dotnet:dotNetTest -q
```

The task-local reruns invalidate filtered Test-task results without forcing an
unrelated dependency-wide rebuild. The full invocation has no rehearsal
property. No other Gradle or Framework toolchain lane runs concurrently.

On 2026-09-24, resumption independently verified all four pinned semantic
source hashes and byte-for-byte equality of all 187 full FIR XML files with
the saved evidence. No semantic source had changed and no intervening filtered
FIR invocation had replaced the full output. The actual unfiltered CLI task and
aggregate then completed successfully:

```text
.\gradlew.bat :compiler:tests-integration:dn --rerun :compiler:backend.dotnet:dotNetTest -q
```

Direct audit of all four XML roots found:

| Lane | Suites | Tests | Execution evidence |
| --- | ---: | ---: | --- |
| Backend | 24 | 420 | Fresh 2026-09-24 run |
| Physical CLI model | 1 | 6 | Unchanged up-to-date dependency, XML audited |
| Full FIR2IR | 187 | 2,383 | Unchanged full 2026-09-13 run, reverified |
| CLI/library integration | 2 | 129 | Fresh unfiltered 2026-09-24 run |
| **Total** | **214** | **2,938** | **Zero failures, errors or skips** |

Declared test totals equal the actual testcase-element counts. The four FIR
fixture inverses and `testForeignClrInterfaceCallsRetainPhysicalBindingAcrossRuntimeProfiles`
were individually found without failure/error/skip children. The latter passed
with the new negative assertions for both parsers and profiles. All four source
hashes still matched after completion. This is one completed full gate across
the explicit pause/resume boundary, not a claim that every dependency executed
afresh on 2026-09-24. The completed XML snapshot is retained locally under
`D:\CodexTemp\native-operation-calls-20260913\completed-20260924`.

Semantic source SHA-256 values pinned for final verification:

```text
DotNetIlExpressionCodegen.kt
8EB7F2FC5EC22CF6D99A61DE3512A2511409917A1B891DF387A8FCAD78545ACF

foreignKotlinInterfaceResult.kt
9F72EABF5616F5266A822F6B6C897A86DB6CFC1BDE8A86FFF8EF79A6C6B8A168

AbstractDotNetIlTextTest.kt
DC7C7A42B3B1EEE248B9FF8C38728936A35FB986B4EB0860EF6810B0D0F781B7

DotNetLibraryIntegrationTest.kt
0A6742634285C3088DF49CE2CD7EC5BD12F531A6F318CA7D3D3A4B6475BBC0D7
```

The candidate's emitter hash was
`65F5B31093ABC961D75CEEF49FA0E823A3FBA0EC0F499ADC4487B1CD78D40A0F`;
only its KDoc wording changed afterwards, from generic interface to native
generic reference carrier, to describe the structural predicate accurately.
Executable code and the candidate fixture/harness are unchanged. The CLI
negative additions are exercised by the full integration lane, not by that
focused FIR invocation. Reconstructing that one previous KDoc line in memory
on resumption reproduced the candidate's exact hash; the executable-byte
equivalence was not inferred merely from the description of the edit.

## Remaining boundary

This is real Kotlin/.NET emission and foreign execution coverage, unlike the
earlier model-only query and standalone mechanism probes. It does not establish
general cast admission, coherent Kotlin semantic-family routing, or a policy
for conflicting foreign implementations of Kotlin-owned interfaces. It does
not solve fixed nested generic storage, broad writers, open factories or the
parked constructor ambiguity. No general pair representation, historical
selector, adapter, wrapper or shadow state is introduced. There is no new
performance, trimming or NativeAOT claim, and the stdlib census remains paused.
