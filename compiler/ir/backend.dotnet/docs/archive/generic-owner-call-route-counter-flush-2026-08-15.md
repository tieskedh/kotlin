# Generic-owner call-route counter/final-flush transport — 2026-08-15

## Outcome

The compiler-indexed route tracer can now collect representative dynamic call
counts without performing console I/O for every execution. The semantic
insertion point and same-compilation call-site join are unchanged. Only the
explicit architecture-test executable receives one private physical counter
helper; each event is a CLR atomic increment and one final snapshot writes at
most one line per visited compiler site.

The transport therefore changes output complexity from O(executed calls) to
O(visited sites). It does not make an instrumented run performance evidence:
the atomic increment is still collection overhead, and timing/allocation/
startup/scheduling measurements must use separate clean products.

## Why the helper is physical

The Kotlin-source test universe does not expose arbitrary `System.Threading`
names, so a generated Kotlin call to `System.Threading.Monitor` correctly
failed frontend resolution. A racy Kotlin `LongArray` increment was rejected.
A Kotlin generic map was also rejected because calls inside that recorder
would themselves enter the generic-owner census and could recursively
instrument the instrumentation.

The selected design keeps two private top-level Kotlin declarations only as
exact IR hooks:

- `(Int) -> Unit` records one compiler site; and
- `() -> Unit` flushes the final snapshot.

When and only when the test fixture supplies both exact declarations, USER
executable emission replaces their bodies with calls to a private
`Kotlin.DotNet.Testing.GenericOwnerCallRouteCounters` TypeDef in that same
instrumented assembly. Ordinary CLI configuration cannot select it. Runtime,
Stdlib, KLIB, published library metadata, and normal executables gain no type,
method, field, or option.

## Counter protocol

The complete compiler census owns dense indices before producer filtering, so
the helper allocates one `Int64[]` of exactly that complete site count. A hard
1,048,576-site bound fails before emission rather than growing an unbounded
table.

`Record(index)`:

1. rejects an index outside the compiler-owned table;
2. obtains the exact `Int64&` array element; and
3. invokes `System.Threading.Interlocked.Increment(Int64&)`.

`Flush()` walks the fixed table, obtains each value through
`Interlocked.Read(Int64&)`, and emits only positive counters in the canonical
form:

```text
KOTLIN_DOTNET_GENERIC_OWNER_CALL_ROUTE|site=<index>|count=<count>
```

The generated main calls `box()`, then flushes, then prints the unchanged box
result. A representative application must join every worker that can execute
instrumented code before returning from `box()`. Atomic reads are well-defined
if a worker is still active, but a concurrent post-read increment cannot belong
to a complete final snapshot; the harness deliberately does not pretend to be
a stop-the-world protocol.

## Closed evidence and fail-closed parsing

The closed bundle still contains exactly the route manifest, producer count
file, and trace properties. The count-file grammar remains schema 1 because
its semantic data is unchanged. Trace properties advance to schema 2 and add:

```text
counterProtocol=FINAL_FLUSH
```

The runner accepts exactly one final line per emitted site, requires a
non-negative index and positive `Int64` count, rejects duplicates, and uses
checked addition for totals. Producer sites absent from the final output are
written explicitly as zero in the joined sparse count file. The independent
PowerShell verifier requires the exact schema-2 property order and protocol,
the exact closed file set, all fingerprints, the complete hostile per-site
vector, and frontend/profile equality.

## Four-lane result

PSI and LightTree on .NET 10 and .NET Framework 4.8 produced identical route
and count bytes:

- route SHA-256:
  `ce5c2d57d8b314eabdc9e44447758c6ba682947e2785e7090e550e39bc884ce2`;
- count SHA-256:
  `22069e5f1a5bdd08b1e9bb54240254a95aff216b85002f9fabef11c2ea421398`;
- net10 instrumented assembly SHA-256 (both frontends):
  `f86e5574c0955bb397025cbf9f91baa4713d7b44243c8b8a9386f3049c55c0a4`;
  and
- net48 instrumented assembly SHA-256 (both frontends):
  `6dfa837a0536f9df79db8c50152e06c00ffb8c5247ecf3f468b773ee6c28deae`.

The exact dynamic result remains 49 complete-census events: 40 producer and
nine unrelated. Producer routes remain 24 producer-erased, 11 exact typed,
four semantic-capability, and one missing-capability event. Producer site 2 is
zero, site 3 is two, and every other producer site is one. Batching therefore
did not turn the oracle into aggregate-only evidence.

The Framework lane independently proves the mscorlib implementation. During
development, ILAsm accepted a nonexistent four-`object` `String.Concat`
reference that failed only on .NET 10 at execution. The final helper uses the
already established cross-profile `Int32/Int64.ToString()` and binary
`String.Concat(string, string)` surface; both Framework 4.8 lanes execute it.
This is also why CoreCLR success was not treated as Framework proof.

## Production-inert proof

A fresh normal net10 paired application corpus was built with no trace
property. Each PSI and LightTree lane contained the same closed 17-file set as
the pre-feature baseline. Every corresponding SHA-256 was identical: 34 file
comparisons, zero name differences, and zero content differences. The private
helper and custom hook bodies therefore exist only in the separately
fingerprinted instrumented assemblies.

## Boundary and next gate

This closes scalable route-frequency collection, not representative evidence
itself and not production `C<T>` admission. The next gate applies this exact
collector to complete erased/candidate Kotlin applications and direct C#
consumers/subclasses, records actual route and state distributions on both CLR
families, and then measures clean matching products independently under
Framework 4.8, .NET 10 JIT, ReadyToRun, trimming, and NativeAOT.

## Verification

`verify-generic-owner-call-route-traces.ps1` generated and independently
verified all four schema-2 lanes. `verify-generic-owner-applications.ps1`
regenerated the two uninstrumented net10 frontend products and executed the
candidate, erased Kotlin, and direct erased C# products before the explicit
34-file baseline comparison.

The final strict aggregate completed in 2,465.4 seconds. Direct JUnit audit
covers one `dotnet.ir` XML file with six tests, 187 FIR XML files with 2,085
tests, and two integration XML files with 125 tests: 190 files and 2,216 tests
in total, with zero failures, errors, or skips.
