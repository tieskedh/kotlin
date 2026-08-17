# Generic-owner one-state memory-model closure (2026-08-17)

## Scope and authority

This checkpoint closes the bounded concurrency/memory-model migration
condition for the hardest CLR-generic owner candidate. Kotlin semantics remain
authoritative. The change records how a future physical owner can preserve one
state graph when an owner-dependent field has `kotlin.concurrent.Volatile`;
it does not emit a production Kotlin-owned `C<T>`, change DLL/KLIB ABI, or
claim the parked public volatility, synchronization, and atomic APIs.

The CLR cannot use an arbitrary unconstrained generic parameter as the
reference-safe volatile carrier required by the current .NET backend access
model. Erasing the complete owner would forfeit safe typed state unnecessarily.
The planner therefore makes the decision independently for each field:

- ordinary `stored: T` retains `TYPED_STORAGE_PRODUCER_GRAPH_PROVEN`, plain
  memory semantics, physical `!T`, and identity initialization/read/write;
- volatile `published: T` records `VOLATILE_OBJECT_STORAGE_REQUIRED`, volatile
  memory semantics, and one physical `object` field;
- its constructor input and typed writes widen or box before that field, while
  typed reads check/cast or unbox after reading it; and
- if a field already requires semantic object state through widened producers,
  that semantic reason remains authoritative even when the field is volatile.

There is no typed cache, object fallback copy, wrapper identity, or second
publication field. Typed entries and the non-generic semantic capability
observe the same physical state. Compatibility is checked before capability
mutation, so an incompatible write leaves the previous published value intact.

## Schema 14

Physical-family schema 14 adds two facts which a consumer may not infer:

1. every selected state records `PLAIN` or `VOLATILE` memory semantics; and
2. every constructor-state initializer records its conversion, including
   `INPUT_TO_STATE_BOX_OR_REFERENCE_WIDEN` for object-backed generic state.

Validation rejects volatile arbitrary-`!T` state, a plain record for a volatile
object requirement, an init-only volatile field, an identity initializer for
object-backed generic state, a widening initializer for true-`T` state, and
incomplete typed/semantic access conversions. Codec round trips retain both
new facts and skewed records fail before physical binding.

## Executable evidence

`HostileTypedStore<T>` now has both the plain typed field and the volatile
published field. The production-inert C# physicalizer consumes only the
decoded family record. Its typed publication/observation methods and
non-generic capability methods use `System.Threading.Volatile.Write/Read` on
the same `object` field. A separately compiled consumer proves by reflection
that the owner has one `T` field and one `object` field, checks its interface
map, and runs 512 two-thread typed/capability handoffs. Snapshot and paired
products also prove that incompatible capability input fails before mutation.

The candidate and production-erased paired applications run a smaller untimed
handoff proof before their measurement entry. This keeps the concurrency
assertion out of the workload timing and allocation totals while ensuring every
deployed product exercises it. The compiler-derived hostile route census is
now 42 producer-owned sites: 24 producer-erased, 13 exact typed-entry, four
semantic-capability, and one missing-capability site, with nine unrelated gaps.
A final audit found that the trace verifier's requirement counts had been
updated without its old total/index vector. The verifier now rejects an
internally inconsistent corpus definition before generation. A fresh four-lane
instrumented run confirms 42 producer plus nine unrelated events; route and
count SHA-256 values are respectively
`28ed1af998284d4ba5c26760346dd169f1e38b7aae2f6d94f254b4c4d2bb8e71`
and `70d57c34f12c1ecebe4803f4e7885c0f6be3cd5ff483ad6c233e73346508b7b4`.

The focused PSI/LightTree x Framework 4.8/.NET 10 matrix covers eight tests
with zero failures, errors, or skips. The closed corpus manifests are:

- net10: `ebf81a1c283c75eca8f02d7187ca11be508382e3717b982a97b2bbb8c97ecd9a`;
- net48: `c6297589b7940cf828ba17fd4e762b80921e49d34d9946d840ed60bcd62f19f3`;
- physical artifact: `f4440e253d8f476342afc15da79ec07683c647812e42786769cb56fbbdf72ad0`.

A correctness-only five-lane deployment run used one iteration/run rather than
a performance sample. Framework 4.8, .NET 10 JIT, ReadyToRun, full trimming,
and real NativeAOT all produced checksum `16564`. Its result SHA-256 is
`729e5f73de24f4159f6d9a3c4606275eed3cd5fcc9929b9a139806f7bd000be8`.
No timing or allocation conclusion is drawn from that deliberately tiny run.

The final strict aggregate exited successfully. Direct JUnit audit covers one
`dotnet.ir` XML file with six tests, 187 FIR XML files with 2,107 tests, and
two integration XML files with 125 tests: 190 XML files and 2,238 tests total,
with zero failures, errors, or skips.

## Remaining boundary

This result proves that a future owner migration need not erase every field
merely because one sibling requires volatile publication. It does not prove
all synchronization primitives, atomics, lock protocols, runtime-selected
construction, or production artifact consumption. Production Kotlin-owned
generic classes remain erased until the hostile programme selects one atomic
ABI cutover and the draft owner ADR is replaced.
