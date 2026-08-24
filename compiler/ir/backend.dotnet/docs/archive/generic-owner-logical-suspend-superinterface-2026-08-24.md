# Generic-owner logical suspend super-interface

Date: 2026-08-24

## Context

After the local/external function-fact boundary was closed, the source-built
Stdlib rehearsal reached CIL shape validation. Its first repeated rejection was
a synthesized local generic-owner semantic interface extending logical
`SuspendFunction1`. The diagnostic described that declaration as a missing
external or module-local interface.

That description was misleading. `SuspendFunctionN` is a Kotlin logical
builtin, not a separately emitted or producer-recorded CLR TypeDef. The .NET
type mapper already represents it with the continuation-shaped Runtime
`FunctionN+1` carrier. Class validation and final interface rendering already
honored that rule, while the separate interface validator duplicated the
runtime-interface admissibility test and omitted suspend callables.

## Decision

Class and interface shape validation now share one predicate for a logical
interface whose physical carrier is supplied by Kotlin.Runtime. It accepts the
existing Runtime interface mappings plus logical `SuspendFunctionN` and
`KSuspendFunctionN`; final type mapping remains authoritative for the concrete
physical carrier.

Using that predicate also removes the class validator's duplicated fixed
Function, KFunction, KProperty, exact-callable, and typed-arguments checks.
Those classifiers were already covered by `hasBuiltInInterfaceMapping`. The
change does not add a SuspendFunction TypeDef, treat it as producer metadata,
or change its established FunctionN+1 representation.

## Executable proof

`suspendFunInterfaces.kt` now includes an explicit generic
`SuspendIdentity<T> : suspend (T) -> T`. Under the generic-owner rehearsal its
synthesized non-generic semantic capability directly carries the logical
`SuspendFunction1` edge. Without the shared predicate the interface is skipped
with the same module-local-superinterface diagnostic as the Stdlib product and
the test cannot emit its entry point. With the predicate, the capability and
class emit and the continuation-shaped call returns the same `String` value.

PSI and LightTree execute the proof on Framework 4.8 and .NET 10 in all four
rehearsal lanes. The same four production-erased inverse lanes remain green.
The final full aggregate exits zero. Direct XML audit covers 191 suites and
2,342 tests with no failures, errors, or skips: 187 FIR suites/2,207 tests,
two integration suites/127 tests, and the two-test backend resolver suite are
fresh; the unchanged six-test `dotnet.ir` root is up-to-date.

The source-built Stdlib rehearsal no longer reports any of the former
`IlambdaKotlinSemantic... extends SuspendFunction1` failures and reaches the
next independent emitter group.

## Result and next boundary

Synthesized generic-owner capabilities now compose with logical suspend
callables through the same Runtime-carrier rule as ordinary classes and final
rendering. The next repeated source-product blocker is a missing covariant-
return MethodImpl after generic-owner physical rewriting: unrelated
`HashMapEntrySet.getEntry`, callable `InvokeExact`, and iterator-producing
lambda overrides all retain a narrower typed result than their inherited
object slot. Recompute or materialize that bridge generally after the relevant
representation lowering; do not add collection, callable, lambda, or member-
name exceptions.
