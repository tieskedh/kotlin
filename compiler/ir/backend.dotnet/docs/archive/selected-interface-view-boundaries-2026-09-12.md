# Selected interface-view transport: storage and object boundaries

Reviewed base: `aff3ae7e2f6eb8dad53560cc27ae38104f526970`.
This extends the [first CLR mechanism probe](selected-interface-view-storage-2026-09-12.md).
The [candidate ADR](../decisions/draft-adr-selected-interface-view-transport.md)
owns the investigation contract. Nothing here admits a compiler storage ABI.

## Executable matrix

The [runner](../../tools/verify-selected-interface-view-storage.ps1) first
compiles an [ordinary generic library](../../tools/fixtures/SelectedViewStorageLibrary.cs)
without any dependency on the selected-view representation. Only then does it
compile the [consumer](../../tools/fixtures/SelectedViewStorageProbe.cs). This
is repeated separately against real net48 and net10 reference assemblies.
Source and library hashes, compile logs, assertion output and actual runtime
versions are retained in `verification.json` and the adjacent logs.

Both profiles passed:

| Case | Observed result / limit |
| --- | --- |
| Original direct, stored and alternating selected views | Two selections of the same dual-interface receiver still dispatch differently. |
| Separate `Box<T>` and generic `Forward<T>` | A physical `Box<SelectedView>` preserves the pair through calls, property replacement and nested containers. The library has no consumer reference. |
| Actual fields | Generic `Box<T>.value` remains `!T`; int, string and selected-pair constructions close to their actual respective field types. |
| Null | The zero-initialized probe pair has no receiver/witness, survives generic storage, and rejects a read with a null receiver. |
| Natural nested slot negative | An int-only source cannot be written as `ISource<object>` into a natural `Box<ISource<object>>`. Successful pair storage grants no such conversion. |
| Unchanged separate `object Echo(object)` | Exporting the two selected values' original receivers returns the same reference. Establishing the same new target view afterwards gives the same result, not both original selections. |
| Boxing negative | Exporting the struct itself preserves its selection but produces an object distinct from the original receiver. It is not a valid reference-to-`Any` implementation. |
| Native reference variance | A receiver with distinct `ISource<string>` and `ISource<object>` implementations dispatches the latter after a native CLR conversion to that target. Retaining its original string selection would change the C# operation. |
| Coherent replacement | Two writers complete 40,000 whole-pair replacements; two readers complete 40,000 local snapshot reads. Each snapshot has a matching receiver, selected view and result. |

The concurrency case uses two different receivers with incompatible interface
constructions, so mixing their components is invalid. All accesses to the one
pair field take the same private lock; dispatch occurs after copying the
snapshot and releasing the lock. The source-level protocol is the coherence
argument. Stress execution checks that implementation, not the atomicity of an
unsynchronized struct, a pair of volatile fields or Kotlin atomics. The lock
itself is real synchronization state and its cost has not been measured.

Evidence: `D:\CodexTemp\selected-interface-view-boundaries-20260912-final`.
All four assertion groups ran on each profile. Compilation used warnings as
errors. The net48 target ran on installed serviced CLR4, registry version
`4.8.09221`, release `533509`; it is not an original unserviced Framework 4.8
runtime. Modern SDK `10.0.100` / net10 reference pack `10.0.0` executed on
runtime `10.0.9`.

## What this establishes

The proposal can preserve runtime selection inside an explicitly selected
compound CLR value across a separately compiled ordinary generic library. It
does not globally erase that library's `T` field. One explicit synchronization
protocol can keep its two components correlated.

The object-boundary negative is equally important: same receiver identity and
preserved dispatch selection are independent requirements. Neither extraction
of the receiver nor boxing of the pair satisfies both for an unchanged raw
`object` transport. A new explicit checked target selects a new view, rather
than recovering a lost selection. No global per-object selection table is used.

## What remains unproved

- Kotlin-generated `===`, universal operations, casts, tests and `Any` flows;
- whether each actual Kotlin/interop operation must preserve or may establish
  selection, including stars and generic forwarding through an object ABI;
- Kotlin `Box<Source<Any?>>` construction/state and its natural C# contract;
- full constructor/helper/result transport and separate Kotlin physical seals;
- nullable result, nullable value and Kotlin value-class payload composition;
- arbitrary mutable/volatile/atomic storage, safe publication and `this` escape;
- deeper Kotlin/C# virtual dispatch and hostile broad inputs;
- deployment, trimming/NativeAOT and real-route performance/allocation costs.

The probe still dispatches through reflection. Its elapsed test time is not a
benchmark, and this is not the current compiler-generated semantic helper.
The ADR requires comparable natural/erased/current-semantic/candidate workloads;
an existing route which rejects a dual view is not a timing baseline.

Only standalone tooling and documentation changed. Compiler, Runtime, Stdlib,
schemas and admission guards are unchanged; the earlier full compiler gate is
inherited, not rerun or enlarged by these assertions. Both recorded compiler
experiments remain parked in their original stashes.
