# Invariant recursive-owner state coordinates

Base: `f132c354f52300c407be5a793d60c878a942060c`, with candidate/inverse
32-test matrices and 414 backend tests. The inherited full production
checkpoint is `3724aeab9c` (2,932 tests).

## Reproduced admission cycle

The source-built census on the base remains exactly 215 diagnostic lines,
unchanged from `3724aeab9c`. Tracing nominal state requirements suggested a
cycle between identifying a generic owner and proving its own fields. The
independent library reproducer was:

```kotlin
class RecursiveState<T>(
    private val value: T,
    private val previous: RecursiveState<T>?
)
```

The baseline emitted only `RecursiveState`, while unrelated admitted owners in
the same DLL remained generic. Typed-write analysis required completed owner
admission to form the `RecursiveState<!0>` coordinate, but admission itself
required that field's typed-write proof.

## Bounded correction

The early analysis can form a symbolic construction of its invariant current
owner, just as it already forms bare owner-parameter coordinates. This is
conditional on complete owner admission, not a claim that a TypeDef has been
emitted. Other classifiers still require their independent authority. Argument
coordinates, projections, nullability limits, every writer, intrinsic owner
exclusions and BOUND/final field seals remain checked.

This does not admit mutually dependent owners by assuming each other, nor does
it admit a covariant self construction. The latter can legally receive a
Kotlin view of another physical construction. No state is erased or duplicated
to repair the recursion, and no field is specialized from observed callers.

## Executable coverage

The expanded fixture verifies:

- separate Kotlin producer/consumer modules and ordinary C# construction;
- one private `!T` value field and one private `RecursiveState<!T>` reference,
  both with actual BOUND-to-final field seals;
- Int, String, nullable Int and value-class substitutions;
- null termination, stored values and same-object identity;
- reordered `ReorderedRecursive<V,K>` field coordinates; and
- a broad writer retaining an object field without erasing its independent
  `!T` field, plus a covariant recursive owner remaining unadmitted.

The initial fixed and expanded one-lane candidate probes pass. The frozen
final gate has 48 candidate executions, 414 backend tests and the same 48
production-erased inverse executions, all with zero failures, errors or skips.
The twelve fixtures cover recursive/canonical state, state-carrier and separate
state authority, the hardest-model oracle and representative OctoTree (both
local and separate), array/projected-array construction, inline temporaries,
exact current-receiver captures and closed constructor inputs. Both PSI and
LightTree execute on Framework 4.8 and .NET 10.

All three semantic source hashes match after each gate. Evidence is in
`D:\CodexTemp\recursive-owner-state-20260912`, including baseline/fixed XML,
the test patch, frozen source hashes, audit script and `candidate.zip`,
`model.zip`, and `inverse.zip`. The inverse validator requires erased owners
and absence of rehearsal epoch records and physical-state emissions.

## Scope

The new positive coordinate is explicitly rehearsal-only. With the flag false,
the earlier predicate is unchanged. The fixture-local validator adds no shared
test protocol. There is no Runtime/Stdlib, importer, serialized ABI, or Common
compiler change. The audited focused erased inverse permits inheriting the
unchanged full production checkpoint, not claiming a new target-wide gate.

This proof does not establish source-built collection closure. In particular,
foreign semantic inputs, broad constructors, other-owner cycles and covariant
state still require their own complete contracts. The census must be rerun
after promotion before claiming an advance.
