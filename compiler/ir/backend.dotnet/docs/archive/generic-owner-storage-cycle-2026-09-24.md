# Generic storage cycles across Kotlin and C# assemblies

## Scope

Reviewed base: `68ea19b82574396716aae856f212ee5e976f9819`. Its compiler is
unchanged from `c4dcde568b63389fa1dd54700f5dfcf723c5ac41`; the intervening
commits record evidence and the early
[storage-cycle decision gate](../programmes/generic-owner-storage-cycle.md).
This is an experimental test/observer delta, not a compiler fix, new ABI,
accepted interop restriction or production cutover.

The producer library defines `Producer<out T>`, mutable `Box<T>` with one
private field and ordinary read/write methods, `make<T>`, and `makeNested<T>`.
Another Kotlin library calls those factories and performs widening/replacement.
Ordinary C# then reads the same box, including boxes initially allocated by C#.
The declarations are compiled once per run, not rebuilt for each substitution.

The observer calls actual public MethodDefs. Production-erased writers have
published mangled names; the observer obtains their spelling from PE metadata.
It calls neither hidden candidate entries nor reflective dispatch. Reflection
checks actual types, fields, interfaces and signatures. No authoring generator,
analyzer, wrapper, pair representation or shadow state completes a cycle.

## First boundary: the open nested writer

The initial fixture also contains this separately compiled function:

```kotlin
fun <T> writeNested(box: Box<Producer<T>>, value: Producer<T>) {
    box.write(value)
}
```

On LightTree/.NET 10 the erased fixture and both C# observers pass. The
candidate producer library assembles, but the writer library is rejected:

```text
call to 'write' through object is not an instantiation of declaring class Box
```

Both the source writer and its generated input entry hit that rejection. No
C# execution is claimed for this candidate run. This is an implementation
composition gap, not proof that a broad box cannot be written on the CLR.

The producer PE, ABI records and CIL agree on this already emitted strategy:

```text
Box<T>: one private !T field; ordinary !T read/write

natural makeNested<T>(Producer<T>) -> object
input entry makeNested<T>(object) -> object
    both allocate a fresh Box<object>, without narrowing the input
```

This is one fixed open MethodDef and a real broad allocation, not a view cast
from another box construction. It does not promise a natural
`Box<Producer<T>>` result to C#. Its consumer still has to compose with that
broader contract.

## Isolated execution observations

To expose the other routes without fixing the compiler, the second experiment
removes only the open nested writer. Its `makeNested<String>` observer becomes
an explicitly factory-only read; it is not reported as a later-write proof.
The other factory/write cycles remain unchanged. The original writer is
retained as an additional reproducible negative experiment.

All four isolated parser/runtime lanes produce the same observations:

| Observation | Erased | Candidate |
| --- | --- | --- |
| Scalar Int and String factories, later writes, C# reads | Pass, object fields | Pass, actual int/string fields |
| Exact String producer and CLR-compatible reference covariance controls | Pass | Pass, natural nested fields/results |
| `make<Producer<Any>>` and `makeNested<Any>`, Int producer then String replacement | Pass | Pass through actual `Box<object>` and object-valued C# reads |
| C#-allocated broad box, later Kotlin Int-producer write | Pass | Pass through actual `Box<object>` |
| `makeNested<String>` factory-only observation | Pass | Pass through actual `Box<object>`; no generic-writer claim |
| `makeNested<String>` with bottom producer, object-valued observation | Pass | Pass, original receiver/exception and one body invocation |
| Closed String box factory accepting bottom producer | Pass | Invalid cast during allocation, body count zero |
| Kotlin-allocated narrow String box, later bottom write | Pass | Invalid cast during write, body count zero |
| C#-allocated narrow String box, later bottom write | Pass | Invalid cast during write, body count zero |
| C#-allocated `Box<Producer<object>>`, Kotlin Int-producer write, natural read | Pass on erased physical surface | C# compilation rejects the writer argument |

The main observer has fourteen groups: one signature group and thirteen
behavioral groups. The isolated candidate has eleven passes and three runtime
failures. A fifteenth group is compiled separately so its C# source rejection
cannot prevent the first executable from running. These groups are not fifteen
JUnit tests. Erased scenario names describe the logical comparison; its actual
CLR types are non-generic `Box` and `Producer`, with object-return readers.

| Parser/runtime | Erased JUnit | Candidate JUnit | Erased observer groups | Candidate observer groups |
| --- | --- | --- | --- | --- |
| LightTree / .NET 10 | 1 pass | 1 failure | 14 + 1 pass | 11 pass, 3 runtime failures, 1 C# rejection |
| LightTree / Framework 4.8 | 1 pass | 1 failure | 14 + 1 pass | 11 pass, 3 runtime failures, 1 C# rejection |
| PSI / .NET 10 | 1 pass | 1 failure | 14 + 1 pass | 11 pass, 3 runtime failures, 1 C# rejection |
| PSI / Framework 4.8 | 1 pass | 1 failure | 14 + 1 pass | 11 pass, 3 runtime failures, 1 C# rejection |

There are no JUnit errors or skips. Candidate failures are the intended
negative research evidence, not a green candidate gate. The original open
writer experiment is additional LightTree/.NET 10 evidence, not part of these
eight isolated JUnit cases.

Each runtime scenario catches and records its own failure, then contributes to
a failing process/test exit. Positive cases verify aliases, stored-receiver
identity, actual field/reader types and producer call counts. Bottom cases
require zero calls before dispatch and the original exception after exactly
one body call. Premature storage failures are never accepted as expected bottom
behavior. The three candidate cast failures occur before any producer call.

Candidate bottom objects actually implement `Producer<Kotlin.Nothing>`.
The observer verifies that CLR variance supports their `Producer<object>` view
before using that view for the broad-result control. It does not claim that
`Producer<string>` exists on those receivers.

There are distinct public-surface costs. The open factory returns `object`;
the closed Kotlin forwarding functions `makeNestedBroad` and `makeNestedBottom`
return the generated box semantic interface. The C# observer converts those
results to object and verifies the actual `Box<object>` construction before
using its ordinary reader. This proves explicit broad consumption, not an
idiomatic typed `Box<Producer<...>>` public result or hidden-ABI-free signatures.
After object-valued reads the driver dispatches through the actual known
interface of that test value. It does not prove that an arbitrary element can
be consumed as `Producer<object>`. No semantic-interface method is called by
the C# source.

The separately compiled narrow C# case fails with:

```text
cannot convert from Box<Producer<object>> to Box<object>
```

Those are invariant sibling constructions. The failure is a public entry
boundary, not a failed Kotlin body execution and not evidence that native CLR
generic imports should be reinterpreted.

## General representability rule exposed by the cycle

An exact argument is valid for storage only if its physical carrier can hold
the complete admitted logical value domain, including future writes. An exact
initializer or a successful CLR-reference covariance example is insufficient.

The rule must also compose with already frozen producer contracts:

1. Bind producer-recorded physical parameter/result expressions; do not remap
   a generic MethodDef after seeing a later logical substitution.
2. A fresh construction may choose a broad argument only with a truthful
   outward signature and complete writer/reader boundaries.
3. A logical view does not change the actual construction or enlarge an
   already allocated field. One field and one receiver remain authoritative.
4. Broad semantic compatibility does not prove a narrower CLR construction.
5. Declaration admission covers all supported substitutions and callers,
   including ordinary C# allocations; construction-local precision is not
   admission based on observed favorable uses.

The open writer's receiver routing could be repaired without solving the
independent narrow-box problem. Conversely, the successful broad cycles show
that typed outer owners do not intrinsically require every field to become
object. They do not establish complete admission of this open `Box<T>`.

An existing `Box<Producer<string>>` with a `!T` field cannot both store the
incompatible bottom receiver and return that same receiver as
`Producer<string>`. A semantic writer still converts to the actual field's
`!T`; removing a cast does not make the store valid. A broader field alone
does not make an unconditional narrow getter honest either.

These are ordinary Kotlin covariance/write operations, not unchecked casts.
BK-1 does not license their early failure. No historical interface selector
would enlarge the field or repair the natural return type.

## Cost evidence and limits

The original producer sources are identical across the two compiled epochs.
Their untrimmed LightTree/.NET 10 products provide this diagnostic inventory:

| Producer library metric | Erased | Candidate |
| --- | ---: | ---: |
| DLL bytes | 14,848 | 17,408 |
| TypeDefs, including module/compiler marker | 11 | 13 |
| MethodDefs | 37 | 43 |
| FieldDefs | 8 | 8 |

That is 2,560 more DLL bytes (17.24%), two semantic-interface TypeDefs and six
MethodDefs, with no extra fields. DLL size includes metadata and embedded
Kotlin data; it is not native-code size or a whole-application percentage.
The reused test-platform dependencies are separate from this user library:
Runtime is 91,648 bytes and Stdlib 2,939,392 bytes in the audited modern export.
This fixture is not a migrated Runtime/Stdlib deployment comparison.

Candidate CIL confirms direct `!T` construction/read/write and `make<T>`
constructing `Box<!!T>` without explicit boxing. Capability entries still use
`box !T`/`unbox.any !T`. This is static evidence of a removed boxing boundary
on natural scalar paths. The following measurements separately test the
allocation and execution consequences on supported workloads.

There is no successful full-cycle runtime/allocation ratio: the requested
candidate contract has unavailable routes. No number from the old test-owned
application candidate is substituted for that missing measurement.

### Actual-product overlap measurements

The archived [driver](generic-owner-storage-cycle-2026-09-24/Measure-StorageCycle.ps1)
compiles an ordinary C# observer against the actual isolated LightTree products,
not hand-written candidate classes. Three supported workloads are measured:

- Scalar: allocate through `makeInt`, read, separately compiled Kotlin write,
  read again; checksum 46 per cycle.
- Broad: allocate through `makeBroad`, read the actual Int producer, replace
  with the String producer and read it, replace with Int and read again;
  checksum 151, with two Int and one String producer call per cycle.
- Nested: the same broad workload through `makeNestedBroad`.

Each cycle allocates one box. Producers are allocated before timing. Both
broad workloads retain object fields and object-return box readers; their
natural producer calls must not be mistaken for universally typed nested
storage. The C# source calls the known actual interface of each element, not
an unavailable widened `Producer<object>` view.

Seven alternating-order paired samples per workload/profile use separate fresh
CLR processes, one million warmup cycles and ten million measured cycles.
Candidate/erased assemblies with identical names never share an AppDomain.
Identity, actual construction/field shape, checksums and effect counts are
verified outside timing. Per-thread allocated bytes exclude observer setup,
warmup, producer creation, host startup and result formatting. Both runtimes
support the allocation API. All 84 samples pass their correctness checks.

| Runtime | Workload | Erased median ns/cycle | Candidate median ns/cycle | Candidate / erased |
| --- | --- | ---: | ---: | ---: |
| .NET 10.0.9 | Scalar | 12.56 | 6.13 | 0.49 |
| .NET 10.0.9 | Broad | 16.51 | 10.76 | 0.65 |
| .NET 10.0.9 | Nested | 16.44 | 10.80 | 0.66 |
| Framework target / CLR 4.0.30319.42000 | Scalar | 10.98 | 3.65 | 0.33 |
| Framework target / CLR 4.0.30319.42000 | Broad | 25.88 | 130.77 | 5.05 |
| Framework target / CLR 4.0.30319.42000 | Nested | 27.26 | 134.56 | 4.94 |

Every measured candidate sample allocates **24 bytes/cycle**, versus **72**
for erased. These are allocated bytes, not retained heap size. The framework
broad-path slowdown therefore cannot be explained by extra allocated bytes
in this workload. No JIT/dispatch cost attribution is established here.

This is exploratory evidence, not a whole-application performance claim.
Unrelated user applications remained active; tiered compilation was not forced
to a final tier. The modern run uses dotnet directly; the Framework assembly
runs through a fresh Windows PowerShell CLR host, as in existing measurements.
Compare epochs within a profile, not modern and Framework absolute times.
An earlier one-million-cycle pilot with 200,000 warmup cycles found the same
direction, including the Framework broad slowdown. Its raw files are retained;
the table uses the longer confirmation, not selected favorable samples.

The [samples](generic-owner-storage-cycle-2026-09-24/measurement-samples.csv),
[min/median/max summary](generic-owner-storage-cycle-2026-09-24/measurement-summary.csv)
and [input/tool manifest](generic-owner-storage-cycle-2026-09-24/measurement-manifest.json)
retain order, hashes, runtime versions and limits. CSV numbers use the original
machine's comma decimal separator inside quoted fields. The results demonstrate
useful scalar savings and a substantial profile-dependent broad-path cost;
neither result establishes admission of the failed contracts.

### Unmeasured dimensions

Kotlin compiler time/memory has not been measured. Gradle/test duration also
includes generator, harness, discovery and C# work. The normal CLI does not
expose the test-only rehearsal selector. A paired compiler measurement needs a
bounded standalone test entry using the same compiler phases and configuration,
with frontend/backend/ILAsm costs separated from setup. Shared performance
reporting can help, but current backend time includes ILAsm and does not isolate
child-process memory. Concurrent unrelated applications must be accounted for.

Compiler complexity is likewise not reduced to LOC: this producer needs the
physical S/Q contracts, semantic capabilities, broad-construction mapping and
operation routing, while an open nested writer still fails to compose. The
generated semantic type escaping into a public result is an interop cost.
There is not yet a proved replacement grammar whose implementation cost can be
fairly compared with the existing erased model.

## Strategic assessment, not an accepted replacement

**Do not resume broad census expansion under the unrestricted natural-surface
promise. Investigate CONSTRAIN/hybrid boundaries before a broader GO.**
The complete strategic gate remains open: neither a sound complete subset nor
its complete-contract cost comparison has been established.

The fixed-field contradiction can reject that combination of promises without
a timing benchmark. It does not reject native CLR generics, typed scalar state,
or every possible Kotlin-owned generic declaration.

Honest alternatives require an explicit outward-contract decision:

- Broader arguments/results for affected constructions, together with a
  clearly specified treatment of existing narrow C# instances. Restricting
  those instances is a new interop contract, not a routing optimization.
- Erase the outer declaration where full generic admission cannot be proved.
- Erase an affected inner interface declaration while preserving typed outer
  owners. For this family, one nominal `Producer` carrier permits
  `Box<Producer>` and a uniform nested factory, but gives up `Producer<T>` as
  the C# contract. It does not solve projected nested classes such as
  `Box<Box<out Any>>` or establish all other substitutions.

Preserve Common semantics and current working proofs while comparing such
boundaries. Do not silently reinterpret a C# allocation of a Kotlin-owned box
as an unrelated native container, infer logical arguments from current mutable
contents, or assume that broader mappings automatically require runtime tags.
No alternative is approved or integrated by this archive.

Unchanged native foreign boxes, projected nested classes, Any/star paths,
nullable/value-class substitutions, wider inheritance and deployment remain
outside this fixture. The existing full production gate remains inherited;
these negative research tests do not replace it.

## Reproduction and evidence hygiene

The isolated experiment is preserved as
[cycle.patch](generic-owner-storage-cycle-2026-09-24/cycle.patch). Apply it to
the reviewed base using `git apply --check --unidiff-zero` followed by
`git apply --unidiff-zero`, then run the scoped FIR2IR test generator.
To restore the initial open-writer experiment, additionally apply
[open-writer.patch](generic-owner-storage-cycle-2026-09-24/open-writer.patch)
with `git apply --check --unidiff-zero` and `git apply --unidiff-zero`.

The isolated source SHA-256 is
`2B91786078F7605B16E8CC56AC47527FACECBDB61827C244DC18A561C81C3A47`;
the fixture harness SHA-256 is
`93B9D14B7C3E16B093637735757EB5E5AE1B6B8EBE5B5C9760874469A80C5B5E`.
These are Windows working-file hashes. The initial open-writer source is
`3B14F8A76E01D3211838201D01BDCA56E69B3DB804134BE0D7C543A27A3D20D8`.

Raw evidence is under `D:\CodexTemp\generic-owner-storage-cycle-20260924`.
Use a fresh export directory and one parser/runtime filter per invocation:

```powershell
$env:KOTLIN_DOTNET_STORAGE_CYCLE_PROBE_DIR = 'D:\CodexTemp\unique-storage-cycle-lane'
.\gradlew.bat --no-parallel --no-configuration-cache "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --rerun --tests "*FirLightTreeDotNetBoxTestGenerated*testGenericOwnerStorageCycle" -q
```

Omit the rehearsal property for erased. Substitute PSI and/or Framework runner
names for the other lanes. Preserve XML before another filtered invocation
overwrites it. The fixture exports DLL/CIL, PE/ABI records, actual C# source,
compilation output and independently reported scenario results in both epochs.
Keep console output bounded: the existing snapshot host waits before draining
stdout; full exceptions and observations belong in the exported result file.

For the passing-overlap measurement, retain the shared frozen fixture as
`isolated-candidate-lighttree-net10/genericOwnerStorageCycle.kt` in the export
root. The driver verifies its pinned hash; the serialized matrix used that
unchanged source, but did not export independent per-lane source copies. Keep
the driver beside its
[C# template](generic-owner-storage-cycle-2026-09-24/StorageCycleMeasurement.cs.in)
and [Framework launcher](generic-owner-storage-cycle-2026-09-24/Invoke-FrameworkStorageMeasurement.ps1).
Invoke it with PowerShell 7, `-ArtifactDirectory` pointing at the export root,
`-Iterations 10000000 -Warmup 1000000 -Samples 7 -IncludeNested`, and a fresh
`-OutputDirectory`. It uses only installed tools and performs no Kotlin build.
The manifest hashes identify the executed Windows source files; Git line-ending
normalization of archived scripts does not rewrite that evidence.

The first measurement preparation required the shared source snapshot instead
of nonexistent per-lane copies. The second exposed a case-insensitive template
check which mistook legitimate `__KotlinErased__` names for unexpanded tokens.
Both were corrected before any sample ran. Raw `measurement-overlap` and
`measurement-overlap-final` contain those incomplete preparations;
`measurement-overlap-complete` is the successful pilot and
`measurement-overlap-long` the longer confirmation. No compiler fix was made.

Initial harness compilation needed `[]` positional syntax under this project's
name-based-destructuring mode; initial erased C# source used unmangled writer
names. Both observer issues
were corrected without changing compiler semantics. They are not target
representation failures. The successful original erased lane is
`erased-lighttree-net10`; the failed open-writer XML/source/harness are in
`candidate-lighttree-net10`.

That original candidate directory's DLL/CIL exports were subsequently
overwritten by the isolated run: Gradle configuration-cache reuse retained its
old environment-based export destination. Do not treat it as a coherent
original artifact set. The isolated artifacts and their own XML were recovered
into `isolated-candidate-lighttree-net10`; its `lib1` source is unchanged.
Subsequent lanes disable configuration caching and use fresh pre-created
destinations. This is an evidence-capture correction, not a compiler result.

The experiments were removed from the active corpus after completing the
matrix. The fixture-local harness was restored byte-for-byte to its baseline
Git blob, and the scoped generator removed the probe's runner entries. Their
archived patches remain recoverable; no red test or partial compiler
implementation is promoted. The production full-gate evidence remains the
unchanged compiler checkpoint `c4dcde568b`; this archive does not claim a new
full aggregate or count the negative probes as passing production tests.
