# Generic-owner physical local storage consumption — 2026-09-01

This archive records the first authoritative consumer of final physical-value
facts on physical-library ABI 66, generic-owner artifact schema 21, and
compiler/runtime surface 60. It is executable evidence for the physical-
authority ADR, not a new representation category or production cutover.

## Boundary

The physical-value analysis already distinguished the carrier produced by an
initializer from the carrier selected for its destination. Emission still chose
some local carriers through an older recognizer limited to compiler-owned IR
origins. Consequently, an ordinary source immutable alias and an inliner
temporary with the same final physical facts could receive different treatment.
The diagnostic shadow could describe the correct answer but was deliberately
not emission authority.

The first consumer closes only the smallest non-converting placement:

```text
ProducedLayout = Direct(local C<!n>, null-reference)
StorageLayout  = Direct(local C<!n>, null-reference)
all !n bind to the exact physical MethodDef owner
--------------------------------------------------
retain the already-produced carrier in this local
```

This is deliberately not a request to reconstruct `C<T>` from the logical
Kotlin type. The final IR-bound facts mint one identity-keyed permission; the
emitter independently resolves the local TypeDef, reconstructs each owner
parameter against the live physical MethodDef owner, observes the initializer's
verifier-visible carrier, and requires exact equality. A mismatch is an internal
compiler error rather than a silent fallback.

Diagnostic snapshots, declaration names, packages, member names, stdlib
identity, and IR origins do not participate. A selected-view lineage cannot
establish the construction. Unsupported facts mint no permission, while two
final records for one physical local are contradictory authority and fail as a
`Conflict`.

## Consequences

Both a source immutable widened alias and compiler-created inline aliases may
now retain the exact local `InlineProducer<!T>` carrier supplied by their
initializer. Their logical widened or open-nullable Kotlin view remains intact,
so calls which require the semantic route still use it. Local storage precision
does not turn a semantic operation into a natural one.

The permission authorizes no cast, variance conversion, semantic adaptation,
boxing, nullable materialization, field or capture choice, state change,
MethodDef change, or public ABI change. It introduces no wrapper, proxy, shadow
state, or second object identity. Production remains on the erased owner model
when the rehearsal property is absent.

The existing compiler-origin local recognizer remains as a migration fallback
for shapes not yet consumed by the shared model. In particular, the new token is
unavailable for:

- star or projected carriers;
- mutable or multiple-write locals;
- representation-changing conversions and control-flow joins;
- fields, properties, captures, and generated-owner state;
- foreign, fixed, nested, value-shaped, or nullable carriers; and
- split-nullable two-slot layouts.

An attempted broad `IrWhen` join exposed an older conversion/placement gap
before this authority is consulted. That gap is recorded as the next transfer
boundary; it was not hidden by widening this feature.

## Hostile proof

`genericOwnerInlineWidenedTemporary.kt` now executes exact and logically widened
aliases for both `Int` and `String` owners. It proves that:

- source and compiler-owned immutable aliases select the same final-fact rule;
- exact storage still uses the local owner parameter rather than fabricated
  `InlineProducer<object>`;
- logical widened and open-nullable calls remain semantic;
- a star-projected source alias receives no retained-producer token;
- one mutable source local can successively hold two implementation objects and
  receives no retained-producer token; and
- every view preserves the same Kotlin object identity.

The model test separately admits a local owner-bound construction, rejects a
foreign/fixed carrier, and rejects duplicate final facts.

Verification:

```text
.\gradlew.bat :compiler:backend.dotnet:compileTestKotlin :compiler:fir:fir2ir:compileTestKotlin --no-configuration-cache
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest" --no-configuration-cache -q
```

The focused fixture assembled and executed under FIR PSI and LightTree on .NET
10 and Framework 4.8. Direct XML audit reported four candidate suites/four tests
and four production-erased inverse suites/four tests, each with zero failures,
errors, or skips. The inverse used the identical source and filters without the
rehearsal property.

No physical ABI, artifact schema, Runtime/Stdlib surface, or Kotlin semantics
changed.
