# Generic-owner split-nullable multiple direct returns — 2026-09-02

This archive records the first bounded retention of one exact natural
`SplitNullable` local across multiple mutually exclusive terminal return sites
in the generic-owner rehearsal. It changes no physical-library ABI, artifact
schema, Runtime/Stdlib declaration, production representation, or Kotlin
semantics.

## Boundary

Earlier pair placement required exactly one read of the immutable logical
`T?` local. The physical payload and null flag are already stable private CLR
locals, so that restriction was stronger than required when every possible use
immediately returns the same pair from the same physical MethodDef.

The admitted use grammar is now exactly:

```text
readCount > 0
readCount == directFunctionReturnCount
directOtherReturnCount == 0
protectedRegionReturnCount == 0
```

Every read must be the bare local value of an `IrReturn` targeting the same
enclosing physical MethodDef, and every such return must be outside
`try`/`catch`/`finally`. Although there may be several static sites, at most one
executes in an invocation because each site terminates its path. This is not
authority for sequential or general repeated consumption.

The rule changes only the use side of an already admitted placement token. The
initializer must still be one final BOUND exact-natural operation with an
identical enclosing split-result layout and every operation-specific receiver,
argument, MethodSpec, and payload proof required by its existing grammar.

Zero reads, a call, copy, cast, comparison, null test, capture, statement-position
read, return to another target, protected return, or nested expression such as
`return if (...) value else value` receives no pair-retention authority. Mixed
direct/non-direct and sequential consumers remain excluded. Control-flow joins,
fields, and split state remain outside this slice.

## Final-emission authority

Placement binding records an exhaustive use summary before granting the pair
token. A later lowering could otherwise add or reshape a use after that proof.
Immediately before declaring the verifier-visible payload and Boolean locals,
the emitter therefore recomputes the same summary from live IR and requires the
same positive-N rule. A mismatch fails closed; it cannot retain stale authority
or silently invent a materialized nullable value.

At each admitted return the emitter copies the private Boolean local into the
enclosing final `out bool`, loads the payload local, and returns. It never passes
the enclosing flag to the nested call and introduces no boxing, `Nullable`
materializer, proxy, wrapper, field, or shadow state.

## Executable evidence

`genericOwnerInlineWidenedTemporary.kt` gives the enclosing split-result method
one declaration-independent Boolean selector. It initializes the retained pair
once from the parameterless exact natural `read(out bool)` operation, then has
two bare direct returns of the same immutable logical local. Both selector paths
execute for non-null and null `Int`, `String`, and `Int?` substitutions.

Candidate CIL has one natural split-producing call, distinct payload and flag
locals, one selector branch, and two identical terminal flag-copy/payload-return
sequences. It contains no boxing, cast, semantic-capability crossing, logical
nullable materialization, or duplicate producer call. Existing strict-input and
MethodSpec fixtures continue to cover their operation-specific validation; this
slice generalizes their shared use predicate rather than adding another callable
role or placement form.

The physical-value model admits positive `(1, 1)` and `(2, 2)` read/direct-return
counts and rejects zero reads, missing direct returns, other-target returns, and
protected returns. Existing materializing and protected-region fixture controls
remain ordinary non-pair paths.

## Verification

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin :compiler:fir:fir2ir:compileTestKotlin --no-configuration-cache -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest" --no-configuration-cache -q
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
```

The physical-value model reported 91 tests with zero failures, errors, or
skips. Candidate and fresh production-erased inverse each passed four generated
test methods across FIR PSI, FIR LightTree, .NET 10, and Framework 4.8. The
inverse retained no candidate generic owner, operation/placement record, route
class, pair flag, or other rehearsal identity.

## Next boundary

Prove split-pair control-flow initializer joins next. General multi-input and
remaining parameter-entry forms follow. Non-return consumers, captures, fields,
and arbitrary MethodSpec carriers require independent policies; none follows
from multiple terminal returns.
