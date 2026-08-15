# Generic-owner ArrayCopy application census — 2026-08-15

## Scope

This tranche applies the compiler-derived route counter to its first source
outside the hostile architecture oracle. It deliberately does not emit a
production CLR-generic Kotlin owner, build the record-driven candidate, or
claim that one benchmark is representative application breadth.

The selected input is the repository's exact
`kotlin-native/performance/ring/src/commonMain/kotlin/org/jetbrains/ring/ArrayCopyBenchmark.kt`.
The .NET test provider copies the source bytes into the test compilation and
supplies only benchmark annotation/blackhole stubs plus a bounded driver. The
source is a declared Gradle test input, so undeclared access and source drift
invalidate the task. The driver invokes the real `CustomArray<Int>.add`
implementation 512 times at index zero, exercising growth, overlapping
`IntArray.copyInto`, overlapping `Array<T?>.copyInto`, hash storage, generic
element storage, and size mutation.

## Generic-array correction

The real source exposed a backend gap before it could enter the census.
`copyInto` accepted concrete generic vectors but rejected both method-open
`T[]` and the deliberate `System.Array` carrier used by owner-relative
`Array<T?>`. Both carriers can cross the existing runtime helper truthfully:
the helper receives the same array objects, owns Kotlin range validation, and
then delegates to overlap-safe `System.Array.Copy`. The destination object and
its runtime component checks remain unchanged.

The intrinsic now admits `GenericArray` and `ErasedGenericArray` source and
destination carriers. It spills receiver, destination, offsets, and bounds in
Kotlin evaluation order, derives the default end from the already evaluated
source, invokes the unchanged helper, and returns the original destination.
The IL oracle proves a method-owned `Array<T>` stays `!!T[]`; the
representative owner proves its `Array<T?>` state stays `System.Array`.

## State and route result

The compiler snapshot classifies the benchmark deliberately rather than
inferring physical state from a closed call site:

- `values` is initialized as `arrayOfNulls<Any>(capacity) as Array<T?>`;
- the unchecked cast cannot manufacture physically typed provenance;
- the one `values` state is therefore `SEMANTIC_OBJECT_REQUIRED`;
- `add(index: Int, element: T)` retains its strict typed entry and a capability
  dispatcher, without a semantic override hook; and
- every executed application call reaches the exact entry, so the capability
  remains required by the declaration contract but unused by this call mix.

The canonical static manifest contains 16 local records at compiler indices
0–11 and 13–16. Eleven unrelated external sites occupy index 12 and 17–26;
only index 12 executes in the bounded driver. The execution produces:

| Evidence | Count |
|---|---:|
| local static routes | 16 |
| unrelated external static routes | 11 |
| local dynamic exact-entry events | 5,664 |
| local dynamic capability events | 0 |
| unrelated dynamic events | 512 |
| all dynamic events | 6,176 |

One local source site is intentionally unexecuted; the exact per-site vector,
not only these totals, is pinned by the verifier. PSI and LightTree agree
within each profile, and Framework CLR 4 and .NET 10 produce identical route
and count bytes.

This answers two separate questions. A normal call mix can make canonical
capability crossings very limited—in this input they are zero—while the same
owner can still require erased semantic field state. Reifying the owner alone
therefore neither forces nor proves reification of each field.

## Tool correction

The first real logical binding containing Base64URL punctuation exposed a
latent verifier bug. The Kotlin codec has always emitted unpadded Base64URL,
but the PowerShell verifier happened to accept the hostile corpus using the
classic Base64 alphabet. The verifier now accepts only the codec's canonical
`A-Z a-z 0-9 _ -` alphabet, translates solely for platform decoding, and
round-trips back to canonical unpadded Base64URL. Existing hostile artifact
bytes do not change.

The source-controlled command is:

```powershell
& compiler/ir/backend.dotnet/tools/verify-generic-owner-call-route-traces.ps1 `
  -Corpus array-copy
```

It runs PSI/LightTree independently on Framework CLR 4 and .NET 10 and closes
each three-file trace bundle before comparing route and count fingerprints.
Instrumentation is correctness evidence only; timing instrumented products
would measure `Interlocked` counters rather than the application.

## Verification

Focused execution covered the representative app and generic-array copy
oracles on both frontends and both CLR profiles. The final strict
`:compiler:backend.dotnet:dotNetTest` aggregate completed in **1,203.1
seconds**.
Direct XML audit covers **190 files and 2,220 tests**, with zero failures,
errors, or skips.

## Remaining gate

This is the first real source distribution, not conditions 8 and 9 of the
generic-owner migration plan. The next architecture work must broaden the
source set, create complete record-driven candidate and direct C# products,
then measure clean erased/candidate builds for startup, throughput,
allocation, peak memory, compile cost, metadata/managed/native size, and
bridge crossings on Framework 4.8 and every selected .NET 10 deployment lane.
Production generic owners remain erased.
