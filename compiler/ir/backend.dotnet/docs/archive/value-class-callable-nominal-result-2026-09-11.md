# Preserve nominal value-class results through callable bridges

- Date: 2026-09-11
- Reviewed base: `40ef9b75f20a35ba27edece85d3d38b1f6430a68`
- Scope: production-selected value-class result adaptation and physical-call
  carrier observation; no MethodDef signature, owner/state, Runtime, artifact,
  or ABI-schema change.
- Verification: focused regression and full target gate green.

## Reproduced defect

The generated-callable generic-owner rehearsal's erased inverse exposed an
existing production bug. A lambda returned from an erased generic owner had a
correct nominal value-class `InvokeExact` MethodDef but its ordinary erased
`Invoke` adapter unpacked that box and CLR-boxed the underlying `Int32`. A
separate Kotlin consumer consequently failed its legitimate nominal cast.

The first minimal supplier/mapping pair passed: its exact capabilities hid the
broken fallback. Adding a captured `Source<T>` parameter reproduced the error
without the rehearsal property. The baseline IL showed:

```text
callvirt Id InvokeExact(Source)
call int32 Id.unbox(Id)
box System.Int32
ret object
```

This is not a request to make Kotlin value classes CLR structs or change their
generic identity. Both physical slots were already correct; the adapter body
violated the existing nominal-box contract.

## Two composing causes

1. Return adaptation used logical `V` even when the destination MethodDef was
   erased `object`. Calls to a constructed generic slot already returning
   nominal `V` were not consistently recognized as boxed producers.
2. Late natural-carrier observation excluded every member of an erased generic
   class. It therefore remapped logical `V` to its underlying primitive despite
   the concrete nominal MethodDef. A lowering-only correction fixed ordinary
   suppliers but left captured generic-owner adapters broken; that failed
   intermediate matrix and its IR/IL evidence were retained.

The existing generic-boundary classifier now exposes one shared nominal-result
fact consumed by signature mapping, value-use adaptation, and late physical
call observation. Erased callable/reflective/property result destinations use
their nominal object boundary. A nominal generic-boundary call on an erased
owner observes its actual resolved MethodDef instead of reconstructing a
carrier from the logical occurrence. Exact computations retain their underlying
carriers; generic slots retain the nominal value-class owner.

No declaration-name, package, collection, or generated-class-origin rule was
added. Ordinary erased-owner behavior outside this nominal result fact is
unchanged. The owning rule is in the [value-class ADR](../decisions/value-classes.md).

## Evidence and coverage

`valueClassCallableResult.kt` separately compiles its producer and Kotlin
consumer. It covers primitive, reference, nullable-underlying and nullable-outer
value-class results; exact and captured generic-owner lambdas; a hand-written
callable implementation; and a generic captured state/result control.

An ordinary C# consumer explicitly calls canonical `FunctionN.Invoke`, optional
exact and typed-argument capabilities, and verifies nominal results. Reflection
checks the canonical object MethodDef and exact nominal MethodDef. A PE-body
check requires the simple forwarding bridge to be exactly receiver load, direct
virtual call to the nominal MethodDef, and return, without unbox/rebox traffic.
The C# consumer implements no compiler-private interface.

Three unchanged upstream fixtures join the existing lambda value-class corpus:
`boxInt`, `boxString`, and `boxNullableIntNullGeneric2`. The four generated
PSI/LightTree and Framework/.NET runners were regenerated and inspected.

The focused production matrix passed 28 tests in 8 suites, with zero failures,
errors, or skips. Commands use `--max-workers=1 --no-configuration-cache -q`:

```text
:compiler:fir:fir2ir:generateTests :compiler:fir:fir2ir:dotNetTest --rerun
  --tests '*testValueClassCallableResult'
  --tests '*testValueClassSeparateCompilation'
  --tests '*BoxReturnValueInLambda*'
```

The final instruction-level assertion passed all four parser/runtime cases,
and the complete backend suite passed 402 tests in 22 suites. The final full
gate ran from approximately 15:25 to 16:12 local time on unchanged source:

```text
.\gradlew.bat --max-workers=1 --no-configuration-cache -q
  :compiler:fir:fir2ir:dotNetTest --rerun :compiler:backend.dotnet:dotNetTest
```

Direct audit of all four JUnit XML roots found 212 suites and 2,863 tests:
backend 402, physical CLI model 6, full FIR2IR 2,327, and integration 128.
Failures, errors, and skips were all zero. FIR2IR was explicitly rerun without
filters, integration rebuilt, and the backend had just been rerun on this same
final source. The unchanged physical-model task remained up to date. All six
changed compiler/test-source SHA-256 hashes matched the pre-gate checkpoint.

Compact evidence, including `full-2863.zip`, is retained under
`D:\CodexTemp\value-class-callable-result-20260911\`.

## Follow-on boundary

The independent rehearsal-only callable InterfaceImpl change remains parked as
owned stash `9925190c497a151f1cd8cbcec2d1e9e3a5391311`. After promoting this
independently verified production repair, reapply that feature and repeat its
candidate/inverse matrix. Its hostile value-class case must not be removed to
make the inverse green. This repair does not close generated-owner inheritance,
open-nullable captured getters, or the source-built candidate stdlib census.
