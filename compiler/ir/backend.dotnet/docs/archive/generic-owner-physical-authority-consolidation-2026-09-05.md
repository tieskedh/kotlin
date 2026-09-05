# Generic-owner physical-authority consolidation — 2026-09-05

This is immutable checkpoint evidence, not a normative decision or current
status. The active model belongs to the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md),
current work to [`../../STATUS.md`](../../STATUS.md), and future ordering to the
[way forward](../programmes/way-forward.md).

## Checkpoint

- Reviewed upstream base:
  `2868cfb88a7ea111ea6f6bf02f24430dc0e039e5`.
- Physical library ABI 68.
- Generic-owner artifact schema 22.
- Compiler/runtime surface 60.
- Production remained on the erased generic-owner ABI.

The milestone consolidated the bounded generic-owner proofs into three
cooperating models rather than one circular representation oracle:

1. declaration authority advances from representation planning through BOUND
   identity to sealed final MethodDef, MethodImpl, FieldDef, constructor, and
   supertype observations;
2. value provenance records produced carriers and guaranteed physical views,
   while storage placement remains a separate destination decision; and
3. operation routing binds a selected receiver, MethodDef, MethodSpec,
   arguments, and result layout without rediscovering representation.

Producer-wide state remains separate and open-world. It chooses one
authoritative field set and carrier per field before ordinary value provenance
can route reads or writes around that state.

## Disposition of the motivating bounded proofs

| Earlier proof | Consolidated interpretation |
| --- | --- |
| `445266c9` post-representation covariant slots | Existing physical MethodDefs are fundamental authority; specialization uses an adapter/MethodImpl. |
| `ec04adb7` erased bootstrap edges | A TypeDef may mention only binders it physically owns; this metadata rule remains fundamental. |
| `8dd5800d` closed semantic inputs | Parameter domains are independent; a broad input does not erase unrelated exact receiver/state facts. The first final/non-generic grammar remains a temporary restriction. |
| `3581b56d` nullable results | `SplitNullable(payload, out bool)` remains a fundamental result layout; combined member roles are removable. |
| `155e82c9` inline temporaries | A single-definition immutable alias preserves its producer fact through the general value/placement model; IR origin is not evidence. |
| `00dc1de3` output-only helpers | Receiver provenance, parameter polarity/domain, and selected operation authority derive the behavior; the helper recognizer is removable. |
| `03cd3271` exact result chains | A selected non-semantic operation may produce its recorded result. Exact outer construction alone never proves an owner-dependent nested result. |
| `030bb9e1` generated captures | Capture definition, constructor transfer, and field plan derive the result; generated or anonymous status is not evidence. |

Later slices extended the same model to producer-wide FieldDef authority,
current caller-MethodDef entries, exact MethodSpec operations, ordered prefix
placement, complete MethodImpl signatures, and constructor MethodDef seals.

## Review corrections

The final review found two production-isolation defects and one authority-
boundary defect before promotion:

- source-backed bootstrap Runtime/Stdlib declarations were incorrectly treated
  as current-emitter declarations. General external C/F/E/constructor binding
  now uses actual emitter ownership; retained foreign CLR metadata remains
  terminal authority;
- new semantic-input and physical-passthrough bridge behavior is explicitly
  rehearsal-gated, preserving the erased production lowering; and
- a non-current source-backed generic constructor with a semantic carrier now
  requires the producer-recorded `L` MethodDef seal. It cannot silently remap
  its logical KLIB signature when that record is missing.

Direct regression tests cover source-backed external authority and the opposing
current-emitter rejection. The earlier nested-result overclaim and its hostile
same-object correction are preserved in the
[nested-result archive](generic-owner-nested-variant-result-correction-2026-09-03.md).

## Focused rehearsal evidence

The final candidate matrix used these 15 fixtures:

```text
genericOwnerCallableCompositionSeparateCompilation
genericOwnerCompleteNaturalInterfaceSeparateCompilation
genericOwnerExactInterfaceInputsSeparateCompilation
genericOwnerForeignOverrideSeparateCompilation
genericOwnerInlineWidenedTemporary
genericOwnerMethodGenericSealedEmission
genericOwnerPhysicalValueShadow
genericOwnerRehearsalStateCarriers
genericOwnerRepresentativeOctoTree
genericOwnerRuntimeMutableCollectionSeparateCompilation
genericOwnerSemanticBodyExactCurrentReceiverCapture
genericOwnerSemanticBodyExactReceiverHelper
genericOwnerSemanticEquivalenceCertificateSeparateCompilation
genericOwnerSplitNullableResultSeparateCompilation
genericOwnerStateAuthoritySeparateCompilation
```

With `-Pkotlin.dotnet.genericOwnerRehearsal=true`, and again with the property
omitted entirely, the matrix passed through PSI and LightTree on .NET 10 and
Framework 4.8: four suites, 60 tests, and zero failures, errors, or skips per
mode. The backend model contained 106 physical-value tests.

## Full production-erased gate

The final aggregate completed on 2026-09-05. Direct JUnit XML audit reported:

| Root | Suites | Tests | Failures | Errors | Skips |
| --- | ---: | ---: | ---: | ---: | ---: |
| backend | 22 | 398 | 0 | 0 | 0 |
| `dotnet.ir` | 1 | 6 | 0 | 0 | 0 |
| FIR2IR | 187 | 2,279 | 0 | 0 | 0 |
| integration | 2 | 128 | 0 | 0 | 0 |
| **Total** | **212** | **2,811** | **0** | **0** | **0** |

The direct runs used `--rerun`; the final supported aggregate was:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

## Result

This checkpoint is a **GO** for the production-inert authority/provenance model,
not for a generic-owner production cutover. The next step is to resume the
source-built Runtime/Stdlib rehearsal census and let its next real failure select
a general rule. Production migration still requires the complete family,
deployment, tooling, representative-application, measurement, and rollback
gates.
