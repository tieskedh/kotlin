# Abstract generic-owner foreign output bridge

This snapshot records the bounded rehearsal feature developed from
`0ebcda9095`. Current state belongs in [`../../STATUS.md`](../../STATUS.md);
the lasting rule belongs in the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md).

## Why this boundary

The post-canonical-state source-built stdlib census still failed with 234
cascading error lines. The first anonymous mutable-map view had a
producer-proven typed capture, not an unresolved capture cycle. Its enclosing
class remained erased because its foreign semantic override contract was
unadmitted, so the construction site had no CLR owner binders to instantiate
the generated generic class. Neither fake `object` arguments nor weakening
capture authority would repair that missing physical construction.

One independent part of the override boundary is an abstract result-producing
member. A natural abstract `Source<T> source()` does not have a Kotlin base
body which must remain authoritative. An ordinary C# override can supply its
result completely; the compiler can expose that same reference to Kotlin's
semantic route. Requiring a second protected abstract C# implementation would
be an unnecessary authoring obligation.

The Common IR declaration remains abstract. JVM bridge lowering also preserves
source abstract obligations while owning physical adapters. Native Swift
export's dispatch lowering separately distinguishes abstract targets from
concrete non-virtual bridge targets. Neither supplies the CLR's paired-slot
implementation; the target-specific requirement here is to connect those slots
without dispatching to an abstract method body or bypassing a later override.

## Implementation and limits

- Keep the natural source MethodDef abstract and typed.
- Emit a concrete virtual semantic forwarder using ordinary IR builders. It
  forwards the existing receiver and fixed arguments and widens the result.
- Give this abstract declaration a concrete virtual probe which selects the
  natural override. There is no abstract body address to compare with `ldftn`.
- Kotlin implementations continue to override both compiler hook and probe;
  ordinary C# implementations override only the source member. Reabstraction
  restores the abstract-declaration rule in the same override family.
- Admission uses the final semantic-result policy, identical fixed-leaf input
  carriers, no method binders, no split-nullable layout, and no abstract
  broad-property obligation. An inherited semantic-routing reason alone does
  not add source behavior to a newly abstract declaration.
- The body-placement vocabulary distinguishes this direction from a concrete
  semantic body with a checked natural wrapper. This is an internal enum,
  not a new serialized member role or ABI version.

There is no Runtime, Stdlib, Common compiler, importer, artifact/schema, state-
grammar, or production-erased mapping change. The existing rehearsal-selected
probe emission consumes an otherwise empty context when rehearsal is disabled.
Physical library ABI 71, artifact schema 22, and runtime surface 62 remain
unchanged.

This is not the full abstract mutable-collection/map contract. Broad inputs,
owner-relative MethodSpecs, split-result forwarding, and abstract broad
properties remain independently unadmitted.

## Executable evidence

`genericOwnerAbstractForeignOutput.kt` has separate producer, intermediate,
and consumer Kotlin assemblies. Before implementation its candidate failed
the PE assertion requiring the natural `Owner<T>` TypeDef.

The fixture checks:

- parameterless methods, a fixed Boolean input, and a read-only property;
- abstract inheritance, a concrete Kotlin implementation, and reabstraction;
- ordinary C# subclasses of each relevant level, including a further C#
  override after a Kotlin/C# chain;
- exact natural MethodDef result types and truthful inherited constructions;
- only the three natural abstract obligations, not hidden compiler members;
- direct execution of the concrete protected forwarders, normal Kotlin
  semantic dispatch, and one receiver/result identity;
- a Kotlin override producing physical `Source<int>` through logical
  `Source<Any?>`, without forcing the result through `Source<object>`;
- nullable and value-class substitutions; and
- negative abstract broad-property, broad-interface-input, and inherited
  split-nullable families, plus the same erased inverse with no rehearsal
  epoch records or generic TypeDefs.

The focused regression set also includes
`genericOwnerForeignOverrideSeparateCompilation.kt` and
`genericOwnerSplitNullableResultSeparateCompilation.kt`.

### A distinct excluded experiment

An initial hostile subclass stored its widened result in a fixed
`Source<Any?>` field. That separately triggered the existing
`BLOCKED_FIXED_SEMANTIC_STATE_CARRIER` gate and then a missing physical-return
adapter on the erased descendant. This remains a real combined
state/inheritance gap in candidate closure, not a supported shape or a green
regression. The committed output test isolates the widened construction by
producing it directly, without changing that state grammar. No whole-family
or production-readiness claim follows from this bounded result. The diagnostic
is retained locally at
`D:\CodexTemp\abstract-foreign-output-20260911\fixed-state-exclusion.xml`.

### Verification commands

The rehearsal-physical lane uses the three fixtures above across PSI/LightTree
and net48/net10, followed by the complete backend model suite and the same
fixture matrix without the rehearsal property. The inherited target-wide base
is `3a2384f636` (2,821 production-erased tests); this feature must not be
presented as a fresh full aggregate.

Final direct JUnit audit on 2026-09-11 found:

| Lane | Suites | Tests | Failures/errors/skips |
| --- | ---: | ---: | ---: |
| Focused candidate, both parsers and runtimes | 4 | 12 | 0 |
| Complete backend suite | 22 | 402 | 0 |
| Same production-erased inverse | 4 | 12 | 0 |

All five changed Kotlin files retained identical SHA-256 hashes throughout the
final candidate/backend/inverse sequence. The four XML roots were audited:
backend 402 and FIR2IR 12 were fresh; `dotnet.ir` 6 and integration 128 were
inherited, not rerun. Their XML also contained no failures, errors, or skips.
The candidate, backend, and inverse XML archives are retained under
`D:\CodexTemp\abstract-foreign-output-20260911\`.

The scoped runner generator was run after adding the fixture, and both parser
runners contained it. The final commands were:

```powershell
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" --max-workers=1 --no-configuration-cache -q :compiler:fir:fir2ir:dotNetTest --rerun --tests '*DotNet*BoxTestGenerated*Box.testGenericOwnerAbstractForeignOutput' --tests '*DotNet*BoxTestGenerated*Box.testGenericOwnerForeignOverrideSeparateCompilation' --tests '*DotNet*BoxTestGenerated*Box.testGenericOwnerSplitNullableResultSeparateCompilation'
.\gradlew.bat --max-workers=1 --no-configuration-cache -q :compiler:backend.dotnet:test --rerun
.\gradlew.bat --max-workers=1 --no-configuration-cache -q :compiler:fir:fir2ir:dotNetTest --rerun --tests '*DotNet*BoxTestGenerated*Box.testGenericOwnerAbstractForeignOutput' --tests '*DotNet*BoxTestGenerated*Box.testGenericOwnerForeignOverrideSeparateCompilation' --tests '*DotNet*BoxTestGenerated*Box.testGenericOwnerSplitNullableResultSeparateCompilation'
```
