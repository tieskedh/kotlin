# Consuming foreign MethodDefs with Kotlin-owned interface results

Base: `2a13f453411adf6942660b8974ca8dfb4b33276b`. This closes the bounded
incoming result-reference gap reproduced in the
[earlier investigation](foreign-kotlin-interface-reference-graph-2026-09-11.md).
The owning rule is section 14 of the
[importer ADR](../decisions/draft-adr-clr-importer-boundary.md).

## Boundary and implementation

The same test-only reproducer fails at `KotlinFactory.read` on the base and
passes after physical-reference eligibility is separated from foreign
classifier eligibility. The CLI joins the selected embedded KLIB, its physical
class record and the actual PE TypeDef. The native resolver can then follow
the foreign MethodDef's reference without creating a second Kotlin classifier.

FIR keeps the KLIB ClassId and logical members. Reified TypeSpecs retain
their real argument vector; an erased TypeDef provides stars, not invented
arguments. The backend binds the original foreign signature and the exact
referenced Kotlin TypeDef. A selected KLIB classifier cannot be reintroduced
by a same-named foreign type, even when its DLL is not an AssemblyRef target.
This includes top-level and nested KLIB typealiases. A separate baseline probe
without alias reservation returned CLI success while skipping a consumer
whose foreign Alias result had been projected as the Kotlin aliased Source.
The corrected importer rejects that classifier before emission.

The bounded grammar covers public top-level non-expect interfaces with default
nullable-Any bounds and no physical parent-interface edges, used in foreign
method results. Foreign inputs, properties, constraints, inherited edges and
Kotlin-only stronger bounds remain outside this proof. Whole unsupported
foreign classifiers remain withheld. No Common compiler code, serialized
library ABI, Runtime/Stdlib surface or production owner epoch changes. The
in-process retained-declaration protocol moves from V3 to V4.

## Executable evidence

`foreignKotlinInterfaceResult.kt` compiles a Kotlin library, a separate C#
factory library, a Kotlin consumer and a C# executable. It covers native and
Kotlin-owned result controls, actual C# and Kotlin implementations of Source,
same-object identity, owner-generic value/reference substitutions, a null
reference payload and nested constructions. Reflection checks the original
foreign MethodDef and containing assembly. Neither C# implementation supplies
hidden compiler ABI. Production checks absence of rehearsal epoch records.
The production inverse implements the actual sole public erased MethodDef,
including its existing mangled name; only the candidate has the natural
`value()` spelling. The probe does not change or beautify the legacy ABI.

The CLI negative test separately proves nullable use-site enhancement,
erased-star projection, no fabricated Int argument, complete withholding of
unsupported edges and bounds, missing assemblies, and foreign lookalikes both
with and without a foreign reference to the Kotlin library. Retained-graph
unit tests reject detached rows, inconsistent arity/resource identities,
duplicate classifier bindings and mixed Kotlin/foreign declaration authority.

Candidate: 4 FIR2IR tests across both parsers and Framework 4.8/.NET 10, plus
the complete 24-suite/414-test backend model corpus, all green. The focused
CLI negative test and all four focused production-erased inverses are green.
The fresh, unfiltered full production aggregate passed on 2026-09-12 with
214 suites / 2,932 tests: backend 24/414, physical CLI model 1/6, FIR2IR
187/2,383 and CLI integration 2/129. All four XML roots contain zero failures,
errors or skips. The actual FIR2IR and CLI integration Test tasks were rerun;
all four fixture inverses were individually verified in the full FIR XML.
Thirteen semantic source hashes match the final candidate and inverse evidence.

Evidence is preserved under `D:\CodexTemp\foreign-kotlin-result-20260912`,
including `candidate-baseline.xml`, `candidate-verified.zip`, `inverse.zip`,
`full.zip`, `cli-negatives.xml`,
`retained-model-93.xml`, `alias-lookalike-red.xml`,
`cli-negatives-with-alias.xml` and frozen semantic source hashes. The old probe stash
is retained unchanged; it must not be reapplied over the promoted fixture.

## Separately exposed override boundary

This candidate-only expansion was also tried:

```kotlin
class KotlinFactory(private val source: Source<Int>) : ForeignReturn.KotlinFactory {
    override fun read(): Source<Int> = source
}
```

The imported foreign slot physically returns `Source<int>`, but the Kotlin
implementation still chooses an object result carrier. The final physical
signature validator refuses the missing adapter. The red executable probe
is retained as `candidate-kotlin-override-red.xml`; no result cast or false
construction was added to make it pass. Consuming a foreign result does not
prove that every logically compatible Kotlin implementation can supply its
physical result contract. That override/state boundary remains separate work.

The post-base source-built stdlib census still contains 215 diagnostic lines,
unchanged from `9ada71a395`. This reference repair is a prerequisite for more
incoming interoperability; it does not by itself close the foreign semantic
input or complete Runtime/Stdlib rehearsal gates.
