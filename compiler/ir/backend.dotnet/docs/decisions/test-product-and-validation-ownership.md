# ADR: .NET test product and validation ownership

- Status: **Accepted — pre-ABI**
- Date: 2026-08-07
- Scope: platform fixtures, source-product tests, CIL validation, and target
  integration prerequisites

## Context

The bootstrap codegen runner historically injected the complete compiler-owned
stdlib source corpus into every test module. The backend then emitted and often
assembled a fresh `Kotlin.Stdlib.dll` and `Kotlin.Runtime.dll` beside each test
program. The IL-text handler also submitted every net48 golden to Framework and
modern ILAsm, while the narrow `.NET` integration task inherited the generic
integration module's compiler-distribution and Wasm prerequisites.

Those choices combined platform production, ordinary consumption, canonical
CIL validation, cross-writer compatibility, and all-target integration setup in
one operation. They were useful during early bootstrap, but the target now
produces and consumes self-describing stdlib DLLs.

Mature targets keep these responsibilities separate:

- JVM codegen tests consume built stdlib/reflection artifacts;
- JS and Wasm test tasks provision their built KLIB products and `kotlin-test`;
- Native resolves platform libraries from a prepared distribution; and
- representation-text tests use their canonical validator while runtime and
  compatibility matrices have separate owners.

The CLR still creates two real differences: Framework and modern profiles have
different canonical tools, and Framework ILAsm/CLR4 cannot safely execute with
unbounded process concurrency.

## Decision

### Ordinary codegen tests consume reusable profile products

The `.NET` FIR2IR test task produces one exact-profile
`Kotlin.Runtime.dll`/`Kotlin.Stdlib.dll` pair for `net48` and one for
`net10.0`. Each pair is a Gradle-owned immutable input to the tests in that
invocation. Ordinary test modules add the self-describing stdlib DLL to their
selected CLR classpath and let the normal library loader validate its embedded
KLIB, physical declaration index, target profile, and sibling runtime.

The fixture producer uses the same compiler and packaged authoritative source
product as standalone fallback compilation. Compiler/source changes invalidate
the producing task through its executable classpath and declared outputs.
An explicit dependency-wide `--rerun-tasks` checkpoint rebuilds each pair once,
not once per test module.

Source production remains an explicit test mode. Only tests whose subject is
bootstrap source ownership, product partitioning, direct/fallback equivalence,
or stdlib publication may request it. That mode retains the Common-source
flags, source roots, complete IL inspection, and same-run product checks.

### Canonical and compatibility CIL validation are distinct

Every accepted net48 IL-text product is still assembled by Framework ILAsm,
the canonical writer for that suite. Modern ILAsm no longer runs for every
golden. A bounded LightTree compatibility class submits representative shapes
to both writers: generic constraints, generic methods, interface defaults,
nested and escaped identifiers, custom attributes, property/callable shapes,
and entry points. The two reusable stdlib fixture builds additionally exercise
large managed-resource products under both profile writers.

Text comparison, canonical acceptance, execution, and cross-writer
compatibility remain separate evidence. Passing one does not substitute for
another.

### Target integration tasks declare only consumed products

The generic integration task retains its compiler distribution and Wasm
runtime inputs. The filtered `.NET` task receives neither unless a `.NET` test
begins to consume one explicitly. It continues to use the in-process .NET
compiler, target toolchains, and target-owned products.

### Semantic coverage is retained

This decision does not remove either FIR parser or either executable CLR
profile. Framework external-tool work remains serialized. Arbitrary JUnit or
Gradle worker parallelism remains forbidden until the Framework lock is proven
cross-process.

Generated `.NET` orchestration may remain temporarily in FIR2IR and integration
modules, but its endpoint is a dedicated target test module when that move
creates an enforceable dependency and test-lifecycle boundary. File movement
alone is not a performance feature.

## Design attack

A shared platform pair could conceal source-product regressions or make tests
pass against a stale library. The design therefore fails closed on a missing
pair, keeps explicit source producers, exercises direct/fallback and installed
products in integration tests, and rebuilds fixtures under the strict
dependency-wide `--rerun-tasks` checkpoint. Ordinary box tests also reject an
incidental per-test `Kotlin.Stdlib.il`, proving that their compiler invocation
consumed rather than published the platform product.

A small cross-writer corpus could miss an ILAsm incompatibility. Canonical
assembly still covers every golden; the compatibility corpus is selected by
physical shape rather than feature popularity and must expand whenever a new
emission family has writer-sensitive syntax or metadata. Repeating the same
modern process for every parser-equivalent golden does not add independent
evidence.

## Rejected alternatives

### Keep source injection because it is stricter

Rejected. It tests the platform producer incidentally and repeatedly while
under-testing the normal self-describing-library consumer path.

### Stop assembling ordinary IL text

Rejected. Text equality does not establish that the canonical writer accepts
the emitted CIL.

### Run both ILAsm implementations for every golden

Rejected. Cross-writer agreement is a compatibility matrix, not the canonical
meaning of every text test.

### Remove parser or runtime lanes to gain speed

Rejected without measured, replacement evidence. Parser symmetry and real
Framework/CoreCLR execution currently prove distinct target contracts.

### Enable unrestricted test-process parallelism

Rejected. The Framework tools and existing temporary-product ownership do not
yet provide a cross-JVM exclusion guarantee.

## Consequences and freeze conditions

- Ordinary feature tests exercise the same compiled-library boundary used by
  separate consumers.
- Platform production cost grows once per profile rather than once per module.
- Cross-assembler process count is bounded by representation families.
- The `.NET` integration graph no longer rebuilds unrelated Wasm or compiler
  distribution products.
- Test task/module ownership can move later without changing semantic coverage
  or the platform artifact contract.

Before ABI freeze, retain source/product equivalence, profile-pair validation,
canonical assembly of every accepted CIL product, representative cross-writer
coverage, both parser/runtime lanes, and strict failure for missing toolchains.
