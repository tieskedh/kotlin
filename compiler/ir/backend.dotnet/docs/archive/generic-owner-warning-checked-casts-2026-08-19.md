# Warning-checked generic-owner cast closure (2026-08-19)

## Result

The generic-owner rehearsal now gives warning-bearing parameterized `as` and
`as?` operations one shared Kotlin-aware runtime-subtyping predicate. This is
the deliberate pre-ABI incompatibility recorded as BK-1; it supersedes the
older classifier-only parameterized-safe-cast conclusion without rewriting
the historical snapshots which recorded that conclusion.

For the admitted `Producer<out T>` family:

```text
Producer<Int> -> Producer<Any>       succeeds for as and as?
Producer<Int> -> Producer<String>    throws for as, returns null for as?
Producer<Producer<Int>>
  -> Producer<Producer<Any>>         succeeds recursively
```

Every success returns the original object. In particular, a physical
`Producer<int>` is not converted, wrapped, or relabelled as the CLR-unnameable
`Producer<object>` construction. Its successful Kotlin view remains on the
object/semantic carrier.

Star and classifier operations remain separate. `is Producer<*>`, throwing
and safe star casts, normal declaration-site variance, projections, widened
calls, identity, dispatch, and the single authoritative state do not acquire
the stricter parameterized predicate.

## Runtime and compiler shape

Runtime surface 39 adds one compiler-ABI compatibility operation. It reuses
the cached interface vector and recursively compares generic arguments using
the declaration's CLR variance flags:

- invariant parameters require equal arguments;
- covariant parameters compare source to target; and
- contravariant parameters compare target to source.

The ordinary CLR assignability relation is accepted first, so reference
inheritance remains direct. The recursive fallback is what preserves Kotlin
value-type covariance such as `Int -> Any`. The helper inspects the runtime
class, its base chain, and its implemented interfaces; this avoids baking an
interface-only defect into the later generic-class reopening even though the
current hostile admission proof is the producer interface.

Code generation evaluates the cast operand exactly once, checks the requested
construction, and reloads the same object on success. Throwing mismatch creates
the CLR exception classified as Kotlin `ClassCastException`; safe mismatch
produces null. FIR-generated implicit casts keep the proven object carrier and
must not reconstruct a fictitious constructed generic type after a successful
CLR-unnameable Kotlin view.

## Evidence

The separate-compilation hostile product covers:

- Kotlin-owned and ordinary non-partial C# `Producer<int>` and
  `Producer<string>` implementations;
- throwing and safe mismatch at the cast boundary;
- throwing and safe `Int -> Any` covariance with reference identity;
- recursive nested producer covariance and mismatch;
- star `is`, throwing star cast, and safe star cast behavior;
- an object implementing multiple natural constructions;
- exact and semantic member dispatch; and
- classifier-derived callable result/input carriers across separate Kotlin
  compilation.

The focused product executes through PSI and LightTree on both .NET Framework
4.8 and .NET 10. The Runtime-surface rejection and Kotlin-classifier physical-
owner integration tests also pass with surface 39. The production epoch-off
fixture passes the same four focused lanes, and the complete target aggregate
covers 190 XML suites and 2,287 tests with zero failures, errors, or skips.

## Remaining boundary

The proof does not claim nullable-value argument subtyping, arbitrary open
arguments, mixed multi-parameter owners, imported generic owners, or the full
generic-class family. Those shapes are not promoted as proven behavior and
remain future hostile admission gates. BK-1 is not a general warning waiver,
and no other Kotlin operation may copy this behavior without extending the
breaking-change ledger and its hostile evidence.

The next storage gate remains `Box<T>(var value: T)` with a legal semantic
`Producer<Int> -> Producer<Any?>` transition materialized inside
`Box<Producer<Any?>>`. The unstable transition must not globally erase
`Box<T>`, `List<T>`, or their fields.
