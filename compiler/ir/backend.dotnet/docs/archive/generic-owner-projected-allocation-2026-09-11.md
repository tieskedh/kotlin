# Fresh allocations behind projected logical views

This bounded rehearsal starts from `a43d1db5cf`. Current integration belongs in
[`../../STATUS.md`](../../STATUS.md); the durable constructor/result rule is in
the [physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md).

## Reproduced boundary

The source-built stdlib census after the fixed projected-array state feature
has 228 error lines versus 227. The KTypeParameter upperBounds physical-return
failure and its two dependent failures disappeared. ArrayAsList is now generic;
its later static-initialization and result-view failures remain. A newly reached
Sequence.minus(Array<out T>) semantic body fails on a generated lambda star view.
The count is not a count of independent compiler bugs or a progress percentage.

A custom Source<T> returning Iterator<T> reproduces the lambda failure without
Sequence or collection implementation source. Its nested predicate captures the
original Array<out T>. A direct T-result control passes; the constructed result
selects the semantic body and exposes the failure. Temporary instrumentation
shows a fresh allocation whose logical result is Lambda<*> while its independent
constructor class-argument vector is [Any?]. Its selected constructor accepts
System.Array. A separate Single<*> allocation has the same selected [Any?] vector.

The emitter was mapping the logical star result before reaching the constructor.
The failure is not evidence that an existing star value has a natural construction,
nor that a semantic capability must be added to every generated class.

## Rule and scope

The shared constructor query distinguishes a projected logical result from an
invariant construction encoding. For an all-star result naming the constructor's
own classifier, only the complete independent class-argument vector describes
the allocation. Missing vectors, incompatible arity, nullable results, partial
projections and unsupported physical arguments remain unavailable. Existing
values, casts and joins are not allocations. The pre-existing invariant resolver
still rejects disagreement between two invariant encodings.

The live emitter separately requires an already-selected non-erased Kotlin owner,
checks its physical arity, binds the vector, and uses the ordinary constructor
MethodDef, parameter conversions and destination check. Retained foreign CLR
constructors are excluded. A container can expose its final allocation's produced
carrier without removing its prefix effects or selecting a local/field carrier.
The same query supplies pre-dispatch value observation and actual newobj emission.

Here Lambda<object> is the object actually allocated, not an invented view of an
existing Lambda<int>. Its one System.Array field retains the original array.
An unrelated incompatible T[] constructor input would still fail the ordinary
MethodDef check; the new rule is not permission to change constructor vectors
until inputs happen to fit. No declaration-, package-, member-, collection-,
stdlib-, generated-name-, or IR-origin-specific policy was added.

Common constructor lowering retains a constructor symbol and class arguments
independently of the contextual result view. JVM ExpressionCodegen allocates the
constructor owner and returns a MaterialValue with that physical owner plus the
logical expression type. JS constructor translation, Wasm BodyGenerator and
Native ConstructorsLowering likewise select the constructor's declaring class.
CLR reification additionally requires the complete actual TypeSpec vector. No
Common code was changed.

## Executable evidence and honest limits

The separate lib/main fixture covers Int, reference, nullable-value and value-class
elements, array alias mutation, identity under widening, and mutable views which
successively select Int and String producers. A second anonymous implementation
accepts a broad candidate: widening PredicateSource<Int> to PredicateSource<Any?>
must let a string candidate return false, not fail in a narrowing lambda bridge.

An ordinary C# assembly consumes the current PE-published Source contract and its
actual Iterator<object> result. Reflection checks that the predicate is a genuine
constructed generic class with the selected object argument and one System.Array
capture holding the original array. This includes nested array substitutions.
The outer Source<T> remains on its existing canonical interface contract; this
proof does not claim Source<T>/Iterator<T> natural export or unboxed typed results.
The initial C# probe incorrectly assumed that unadmitted surface and source-level
member spelling. The final consumer uses the actual PE-recorded member name.

Three model tests cover ordered multiple arguments, missing/conflicting encodings,
star and partial-projected vectors, open-nullable arguments, container prefixes,
and rejection of existing values, casts, joins, non-final and missing producers.
No wrapper, proxy, shadow field, Runtime/Stdlib change, serialization/schema change,
or performance claim is involved.

## Verification

The final candidate matrix passed 36 tests in four suites, the backend passed
409 tests in 23 suites, and the production inverse passed the same 36 tests in
four suites. Direct JUnit XML audit found zero failures, errors or skips. The
five semantic files retained their frozen SHA-256 hashes throughout both gates.

The final serial candidate command uses `--max-workers=1 --no-configuration-cache
-q`, `-Pkotlin.dotnet.genericOwnerRehearsal=true`, the actual
`:compiler:backend.dotnet:test --rerun` and
`:compiler:fir:fir2ir:dotNetTest --rerun` tasks. It supplies the nine exact test
methods below for each PSI/LightTree box class on Framework 4.8 and .NET 10:

- GenericOwnerProjectedArrayCallable
- GenericOwnerProjectedArrayConstructor
- GenericOwnerArrayConstructor
- GenericOwnerCallableSemanticCapture
- GenericOwnerNullableCallableCapture
- GenericOwnerSemanticBodyExactCurrentReceiverCapture
- GenericOwnerInlineWidenedTemporary
- GenericOwnerGenericSamWrapperSeparateCompilation
- GenericOwnerClosedConstructorInput

The generated method prefix is `test`. Classes are
`org.jetbrains.kotlin.test.runners.codegen.FirLightTreeDotNetBoxTestGenerated$Box`,
`FirPsiDotNetBoxTestGenerated$Box`,
`FirLightTreeDotNetFrameworkBoxTestGenerated$Box`, and
`FirPsiDotNetFrameworkBoxTestGenerated$Box` in the same package. The inverse uses
the same 36 filters without the rehearsal property and checks absence of all
rehearsal records and generic implementation TypeDefs in the new fixture.

The production full checkpoint `cafb56a4e8` (2,887 tests) is inherited, not rerun
or increased by this focused work. The new emitter query returns immediately
when rehearsal is disabled; both existing production mapper calls remain in
their original paths. The query changes no state or public signature, and the
validator is fixture-local. ABI 71, artifact schema 22 and surface 62 remain
unchanged. There is no full candidate stdlib, AOT, or production-cutover claim.

Evidence directory: `D:\CodexTemp\projected-array-callable-20260911`.
`nested-result-baseline.xml`, `constructor-diagnostic.xml`,
`allocation-carrier-probe.xml`, `allocation-container-green.xml`,
`csharp-contract-diagnostic.xml`, `broad-input-probe.xml`, and
`kotlin-csharp-broad-green.xml` preserve the investigation. All temporary
instrumentation was removed. `semantic-hashes.json` freezes the five semantic
files for the final matrices. `candidate-36-backend-409.zip` and `inverse-36.zip`
retain the final XML; `verification.json` records the exact filters and counts.
