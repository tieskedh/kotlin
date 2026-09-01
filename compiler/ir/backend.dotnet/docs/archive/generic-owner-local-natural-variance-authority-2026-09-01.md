# Generic-owner local natural variance authority — 2026-09-01

This archive records one focused rehearsal repair on physical-library ABI 66,
generic-owner artifact schema 21, and compiler/runtime surface 60. It is evidence
for the current physical-authority ADR, not a separate architecture decision.

## Boundary found

Commit `09618a4e60` correctly stopped treating generic arity as complete TypeDef
authority, but its local declaration adapter admitted a natural interface only
when that interface also had a newer complete-surface variance plan. The emitter
continued to emit already-supported bounded families without such a rewrite plan
using the declaration variance selected by their existing admission grammar.

The result was internally inconsistent: an `InlineProducer<out T>` TypeDef and
its natural `produce(): !T` MethodDef were emitted, while the BOUND declaration
index omitted the natural TypeDef. The existing operation-route shadow therefore
published no route even though final value provenance and executable CIL were
present. The same failure reproduced on the clean pre-repair `dotnet` checkpoint;
it was not introduced by the subsequent local-storage experiment.

## Repair

Every local natural interface now records one complete ordered physical variance
vector at the moment its family is admitted:

- an admitted complete-surface plan supplies its selected vector;
- an older bounded family records the declaration variance which its existing
  grammar already selected for emission; and
- no non-admitted interface receives a record.

The BOUND local declaration index and both emitter scopes consume this same
record. The emitter requires its key set to equal the admitted reified-interface
set and no longer independently falls back to logical IR. The record remains
expected declaration authority: final emission still observes and validates the
actual `GenericParam` rows separately.

This changes no Kotlin semantics, interface admission, candidate CIL, physical
library schema, Runtime/Stdlib surface, object identity, state, or production
ABI. Production remains erased when the rehearsal property is absent.

## Verification

Compilation:

```text
.\gradlew.bat :compiler:backend.dotnet:compileTestKotlin :compiler:fir:fir2ir:compileTestKotlin --no-configuration-cache -q
```

The `genericOwnerInlineWidenedTemporary` fixture assembled and executed in the
candidate epoch under all four parser/profile combinations:

- FIR PSI on .NET 10;
- FIR LightTree on .NET 10;
- FIR PSI on Framework 4.8; and
- FIR LightTree on Framework 4.8.

The same four filters passed without
`-Pkotlin.dotnet.genericOwnerRehearsal=true`, proving the erased inverse and empty
candidate-shadow boundary. Direct JUnit XML audit reported four suites and four
tests with zero failures, errors, or skips for each final four-filter lane.

The restored candidate validator proves the natural MethodDef route, semantic
routes for widened and open-nullable views, exact carrier provenance, assembly,
execution, and the existing placement comparison. No new fixture-specific or
member-name recognizer was added.
