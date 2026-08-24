# Generic-owner external function-fact authority

Date: 2026-08-24

## Context

After private semantic-result routing let the generic-owner rehearsal compile
past its ten missing Stdlib routes, final routing asked
`DotNetExternalDeclarations` for a producer-recorded result carrier of a local
generated property accessor. That accessor had already been rewritten and no
longer had a public-signature-safe type graph. Trying to derive an external ABI
key therefore entered the Kotlin mangler and failed on an out-of-scope
`Comparator<T>` parameter.

A first diagnostic guard on result-carrier lookup exposed the same operation in
the input-entry lookup immediately afterwards. The bug was therefore not one
Comparator-bearing accessor or one kind of carrier. The external resolver was
missing an authority boundary for its complete generic-owner function-fact
surface.

## Decision

Producer-recorded generic-owner member families, result carriers, and input
entries apply only to metadata-deserialized external declarations. A function
whose parent chain reaches a local `IrFile` belongs to the current compilation
and returns no external fact before any ABI key or public signature is derived.

The rule is centralized in one resolver helper and used by every generic-owner
function-fact query. It does not catch mangler exceptions, identify Comparator
or an accessor by name, or infer producer facts for a local override. When such
an override requires producer authority, its caller must resolve the external
overridden declaration and query that declaration explicitly.

## Executable proof

`DotNetExternalDeclarationsTest` constructs a public local function whose
return type deliberately references a type parameter owned by an unrelated
class. Public ABI mangling of that invalid post-lowering-like graph throws the
same missing-container exception as the source Stdlib product. The test asks
the external resolver for its member family, result carrier, natural-result
fact, and input entry; all four return absent without entering the mangler.

The existing private-result hostile proof remains green in all four rehearsal
lanes and all four production-erased inverse lanes across PSI, LightTree,
Framework 4.8, and .NET 10. The final full aggregate exits zero. Direct XML
audit covers 191 suites and 2,342 tests with no failures, errors, or skips:
187 FIR suites/2,207 tests, two integration suites/127 tests, and the expanded
two-test backend resolver suite are fresh; the unchanged six-test `dotnet.ir`
root is up-to-date.

The actual source-built Stdlib rehearsal no longer throws in either carrier
lookup. It reaches CIL materialization and emits the next independent census of
unsupported representation edges. This product is intentionally not claimed
green yet; its progress is evidence that the authority failure, rather than a
later emitter limitation, was closed.

## Result and next boundary

Local representation planning and producer-recorded external ABI now have an
explicit fail-closed boundary. The first repeated emitter blocker is a local
synthesized semantic interface whose direct super-interface is the
producer-recorded external `SuspendFunction1`; current emission admits only
module-local super-interfaces. The next slice must establish the general rule
for a synthesized local capability inheriting an external interface, without a
SuspendFunction, lambda, package, or stdlib exception.
