# Retained native interface operation-target model

## Scope and provenance

This bounded model feature starts from `695061e0a83db31aa8731e2a8476553297107f1c`.
It follows the
[operation-directed research](target-directed-interface-dispatch-research-2026-09-13.md),
without applying either parked constructor experiment. The durable rule belongs
to the [physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md#7-late-operation-routing).

The retained-foreign route query gains one optional operation-target proof goal.
Its only consumers remain backend model tests: no lowering, emitter, importer,
Runtime or Stdlib path consumes either new form. No compiler runtime check or
pair representation is emitted. Production ABI 71, artifact schema 22 and
Runtime surface 62 are unchanged.

## What changed

The default query still selects the current lineage, direct construction or
unique recorded construction. Native operations can now request a different
target interface through two deliberately distinct proof modes:

| Request | Required evidence |
| --- | --- |
| `ReferenceConversion` | Authenticate the current source; prove identity or a retained CLR reference-variance conversion from it. |
| `CheckedMembership` | Independently prove the requested target from existing physical facts, including valid native variance. |

The second mode does **not** mean that an arbitrary runtime cast succeeded. An
unknown object supplies no proof. Nor may the first mode use an unrelated
implemented sibling to repair an invalid source conversion. For a receiver
currently selected as `Source<int>` but also implementing `Source<object>`,
the reference conversion is unavailable while independently proved membership
in the object construction succeeds.

The result selects the retained **interface MethodDef** with its target owner
arguments. It never resolves an implementation body or selects a construction
by trying arguments. Common validation still authenticates the receiver,
MethodSpec, constraints, parameter vector and physical result layout. Receiver
carrier, guarantees, lineage and storage remain unchanged.

`ReferenceConversion` intentionally remains bounded to a selected/direct/unique
source of the retained family. It is not a complete class-to-interface
assignment algorithm. A class with several relevant native constructions can
nevertheless prove `CheckedMembership` in a supported target. That target need
not have its own literal interface row; the CLR owns dispatch among valid
native variance sources.

## Added hostile coverage

Six tests extend the existing retained-foreign authority suite:

- Explicit string-to-object target and exact checked target preserve the
  original source fact, default route, MethodDef and MethodSpec; the physical
  result follows the requested MethodDef construction.
- Value-type source plus known object sibling distinguishes conversion from
  membership. Identity conversion of the value construction still succeeds.
- Two retained reference constructions, `Source<string>` and
  `Source<string[]>`, support `Source<object>` without an exact row. Both
  interface-edge orders produce the same target MethodDef, not a model-chosen
  body. These cases and the preceding conversion cases cover both profiles.
- Unknown and null-only provenance, wrong family/arity and detached MethodDef
  metadata cannot gain authority from a target request.
- Wrong ordinary arguments and missing MethodSpec arguments retain the common
  rejection path. The existing broader constraint corpus is unchanged.
- A successful operation on `Source<int>` returns a direct `int32` suitable for
  an exact integer slot; it does not make the receiver fit a fixed
  `Source<object>` slot. The rejection uses the declaration index's real CLR
  variance proof, not a blanket test-only ban on reference covariance.

These are symbolic compiler-model tests, not Kotlin/C# integration or new CLR
execution. The prior standalone dual-interface, no-exact-row and foreign
reimplementation probes remain separate mechanism evidence. No throughput,
allocation, AOT or target-wide progress is claimed here.

## Verification

The unfiltered backend task completed successfully on 2026-09-13:

```text
.\gradlew.bat :compiler:backend.dotnet:test -q
```

The fresh JUnit XML contains **24 suites / 420 tests**, with zero failures,
errors or skips. The retained-foreign suite contains 99 tests, including all
six new cases. Source SHA-256 values matched before and after the successful
gate:

```text
DotNetRetainedForeignGenericOwnerPhysicalOperationRoute.kt
D670E8C98DDF2F9AC085849208F6FB5C4EAD217BE1B1087DCB78608DC2FF43AB

DotNetRetainedForeignGenericOwnerPhysicalAuthorityTest.kt
DE474C054191DA328815050CF81242ED5AC120679DA31D92D5E982E36BED85FD
```

This uses the bootstrap contract's pure-model focused lane; all backend tests
were run rather than a filtered subset. Repository call-site review confirms
that the query still has model-test consumers only. No production-selected IR,
mapping, signature, body, artifact, schema or shared test harness changed, so
this delta inherits full checkpoint `3724aeab9cf38649229b842c4b9d6382862da9cd`
without claiming another complete target gate or erased inverse.

The other three XML roots were inspected, not rerun: physical CLI retains
1 suite / 6 passing tests, integration retains 2 / 129, and FIR retains the
one failing focused `testGenericOwnerClosedConstructorInput` invocation from
the parked constructor experiment. Its `StoredInput.read` ambiguity is already
recorded in the [storage archive](selected-interface-view-storage-2026-09-12.md);
that source remains stashed, not promoted. The on-disk FIR subset is not fresh
evidence about this model and cannot be used as a full-suite result. The next
full gate must explicitly rerun the real unfiltered FIR Test task.

Local documentation links and `git diff --check` were also validated. The
earlier CLR/JVM mechanism probes were unchanged and were not rerun for this
model-only delta.

## Remaining boundary

The next compiler integration must carry authenticated logical operation intent
without inferring it from a desired physical construction. This native query
does not settle coherent Kotlin semantic-family dispatch or conflicting foreign
implementations of Kotlin-owned interfaces. It also does not solve natural
generic storage, existing foreign `Box<Source<object>>` allocations or open
generic factories. Those contracts must compose independently before broad
integration or another stdlib census advance.
