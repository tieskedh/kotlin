# Generic-owner physical-value shadow first slice

Date: 2026-08-26

## Context

The symbolic provenance model had no integrated lowering consumer. Existing
semantic-body recognizers could preserve exact CLR constructions in selected
cases, but the shared model had not yet demonstrated the essential asymmetry
between an exact current receiver and a genuinely broad semantic input.

## Decision

A rehearsal-only shadow analysis now runs once after the generic-owner final-
routing fixpoint. For each admitted local generic class it seeds:

- the physical current receiver as exact `C<!T>` with
  `CURRENT_PHYSICAL_RECEIVER` evidence; and
- regular `Any`/`Any?` parameters as object-carrier values with unknown
  physical views.

The first transfer grammar covers sequential immutable `Any`/`Any?` locals and
identity-preserving value reads, implicit reference casts, and implicit not-
null flow. Produced and storage carriers remain independent. Thus `this` is
produced as `C<!T>`, placed in `object`, and subsequently read as `object` while
retaining the independently guaranteed `C<!T>` view. An object-domain
candidate never acquires that view from its destination or logical Kotlin
type.

Mutable locals, warning-bearing casts, unsupported initializers, and other
unmodelled flow fail closed. Selected lineage remains empty: the exact receiver
guarantee does not by itself select a logical view. Snapshot projection is
complete or `UNSUPPORTED`; an unrenderable carrier, guaranteed view, or lineage
entry cannot be silently dropped from an otherwise analyzed result.

## Production-inert boundary

The shadow does not mutate IR and is not consulted by architecture planning,
call routing, state selection, fixpoint sizes, MethodImpl materialization,
emission, or ABI serialization. Its immutable, IR-free snapshots are copied
only into the in-memory backend/CLI result used by architecture tests.

With generic-owner rehearsal disabled, no snapshot is published. Production
therefore remains on the accepted erased epoch, and no existing recognizer is
removed by this slice.

## Executable proof

The hostile probe places an exact receiver and a genuinely broad candidate
through the same semantic-hook body. It verifies:

- exact `C<!T>` production followed by object storage;
- an object-storage reread without fabricated exact carrier recovery;
- retention of independently guaranteed receiver provenance;
- empty selected lineage;
- a broad object input remaining unknown; and
- fail-closed mutable and unchecked-cast locals.

The focused matrix contains eight passing lanes: rehearsal enabled and
explicitly disabled, each through PSI and LightTree on Framework 4.8 and .NET
10. The disabled lanes additionally prove that the production-erased backend
result contains no physical-value shadow snapshots.

The final normal target aggregate exits zero. Direct XML audit covers 194
suites and 2,431 tests with zero failures, errors, or skips. Its changed FIR
root freshly rewrites 187 suites and 2,239 tests. The up-to-date `dotnet.ir`,
integration, and backend-unit roots retain respectively one suite/six tests,
two suites/127 tests, and four suites/59 tests, all green.

## Diagnostic mixed-epoch aggregate

A diagnostic aggregate with
`-Pkotlin.dotnet.genericOwnerRehearsal=true` was also observed, but it is not a
supported target gate. Gradle forwards that property only to the FIR-to-IR test
task. The reusable net48 and net10 platform producers still invoke the ordinary
`-Xdotnet-produce-stdlib` path, do not receive or key their outputs by the
rehearsal switch, and publish the erased Runtime/Stdlib epoch. Ordinary tests
therefore emitted candidate generic-owner references while compiling and
executing against erased platform assemblies; IL-text tests additionally
compared candidate output with production-erased goldens.

That mixed run contained 187 FIR suites and 2,239 tests with 714 failures and
no errors or skips. Representative failures requested `List<T>` and
`Iterator<T>` members absent from the erased platform, differed from erased IL
goldens, or reached families deliberately outside the bounded rehearsal. All
four shadow-probe cases passed, and no failure referenced the physical-value
shadow. The result is useful negative evidence that the candidate epoch cannot
be enabled non-atomically; it is neither a shadow regression nor a replacement
for focused rehearsal-on, focused production-off, and the normal aggregate.

## Boundary and next step

This slice does not yet model generic local storage, pre-semantic-remap values,
calls, fields, constructors, captures, control-flow joins, stars, projections,
foreign constructions, or separate-assembly flow. Observing only the final
body also has survivor bias because semantic remapping may already have
discarded earlier exact-looking information.

The next bounded step runs the same per-function analysis immediately after an
authoritative body moves to its semantic hook and before semantic remapping. It
must distinguish produced from deferred destination storage, preserve only
independently proven direct reference carriers, inspect real definitions, and
keep unsupported flow fail closed. No current recognizer is removed until both
epochs explain the positive and hostile negative cases.
