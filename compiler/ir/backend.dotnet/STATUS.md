# Kotlin/.NET development status

This file is the current integration snapshot. Read [`AGENTS.md`](AGENTS.md)
before changing the target. Future ordering belongs in the
[way forward](docs/programmes/way-forward.md), durable representation rules in
ADRs, and dated evidence in [`docs/archive`](docs/archive/README.md).

## Integration state

- Integration branch: `dotnet`. Completed feature checkpoints are promoted to
  local `dotnet` and `fork/dotnet` together.
- Reviewed upstream base:
  `2868cfb88a7ea111ea6f6bf02f24430dc0e039e5`.
- Current checkpoint: physical library ABI 65, generic-owner artifact schema
  21, compiler/runtime surface 60.
- Stage 7 separates semantic member role from physical result layout and proves
  the structural `Lookup<K, out V>.lookup(K): V?` contract as
  `!V lookup(!K, out bool)` without migrating Runtime `Map`. Exact value calls
  remain unboxed; semantic and ordinary C# routes preserve the same object.
- Git owns the exact promoted checkpoint identity.
- Reviewed upstream synchronization:
  [`docs/archive/upstream-sync-2026-08-31.md`](docs/archive/upstream-sync-2026-08-31.md).

Nothing has shipped and no Kotlin/.NET ABI is frozen. Prototype schemas and
physical identities may still be corrected atomically.

## Latest verification

The latest fresh production-erased target gate completed on 2026-08-31 after
all 672 target patches were rehearsed over pinned upstream
`2868cfb88a7ea111ea6f6bf02f24430dc0e039e5`. The pure replay matched the
precomputed virtual-merge tree; one patch was context-adjusted for upstream's
test-runtime rename and no patch was dropped or added. The required target-
owned build-convention adaptation, focused KGP API check, and exact evidence
are owned by the
[2026-08-31 upstream archive](docs/archive/upstream-sync-2026-08-31.md).

The verified commands are:

```text
.\gradlew.bat :compiler:backend.dotnet:compileTestKotlin -q
.\gradlew.bat :kotlin-gradle-plugin:apiCheck -q
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

Direct JUnit XML audit found 205 suites and 2,621 tests, with zero failures,
errors, or skips:

| Root | Suites | Tests |
| --- | ---: | ---: |
| backend | 15 | 228 |
| `dotnet.ir` | 1 | 6 |
| FIR2IR | 187 | 2,259 |
| integration | 2 | 128 |

The aggregate includes Stage 7, the production-inert TypeDef-authority work,
and the retained-foreign adapter. Their focused design evidence remains in the
owning archives and ADR; this current snapshot does not duplicate it.

Since that aggregate, the retained-foreign rehearsal has added exact operation,
inherited-interface, binder-forwarding, and lazy TypeDef transport authority.
The current focused evidence compiles the importer and test fixture, retains
the 40-test metadata model gate, and runs the same-assembly, cross-assembly,
multiple-edge, and multiple-owner-view memberless external-DLL pipelines with
both FIR parsers. The resulting focused totals are recorded below; Git owns the
intermediate chronology.

## Production binding state

- Kotlin Common declarations and Kotlin IR/KLIB remain logical authority.
  Emitted or retained CLR metadata remains physical authority.
- Kotlin-produced libraries remain self-describing DLLs containing their KLIB
  and physical binding records.
- Production Kotlin-owned generic classes and interfaces still use the
  accepted erased ABI. All CLR-generic owner work remains rehearsal-only until
  one complete family can switch atomically with its exact inverse and
  rollback.
- The candidate keeps one receiver identity and one authoritative state.
  Proven natural CLR-generic routes are preferred; semantic capabilities are
  used only for Kotlin views the CLR cannot truthfully name. No wrapper, proxy,
  shadow state, or fabricated construction may repair a representation gap.
- BK-1 remains the only accepted target-specific cast change; its scope is
  owned by the
  [semantic-authority decision](docs/decisions/kotlin-semantic-authority-and-platform-freedom.md)
  and [breaking-change ledger](docs/decisions/breaking-kotlin-changes.md).

## Active work

The source-built Stdlib census remains paused while generic-owner physical
authority and value provenance are consolidated in rehearsal mode.

Stage 7 composes `STRICT_OWNER_INPUT(!K)` with an independently recorded
`SplitNullable(STRICT_OWNER_OUTPUT(!V), out bool)` result. ABI 65 records
semantic role and result layout separately in `H`; local BOUND, producer-final
`N`, direct consumers, semantic capability dispatch, and ordinary natural-only
C# implementations consume the same MethodDef authority. The admitted grammar
is deliberately limited to a single-member root interface with one invariant
input and one distinct covariant nullable output. Existing result-only
split-nullable families remain green and Runtime `Map` retains its previous
contract.

The exact scope, PE/reflection/C# evidence, erased inverse, and discovered
downstream object-remapping repair are owned by the
[Stage 7 archive](docs/archive/generic-owner-callable-contract-composition-2026-08-31.md).
Stage 6 state details remain in its
[archive](docs/archive/generic-owner-producer-wide-state-fielddef-authority-2026-08-29.md),
not in this current snapshot.

Shared TypeDef authority now carries complete ordered physical `GenericParam`
rows rather than arity alone. Local and producer paths preserve only rows they
can prove; detached producer artifacts retain their recorded constraints, while
generic core/assembly references and arbitrary constrained constructions fail
closed until exact metadata or constraint-satisfaction authority is joined.

One bounded retained-foreign adapter now binds an open, parentless CLR generic
interface and one selected abstract MethodDef from exact raw metadata. It
cross-checks the retained signature and complete empty hierarchy, preserves
ordered GenericParam facts, authenticates only the exact selected rows, and
distinguishes unsupported valid shapes from contradictory carriers. Direct
caller descriptions, MethodImpl owners, unsupported carrier leaves,
and arbitrary constrained constructions remain unavailable.

One imported operation now uses that retained MethodDef as its endpoint and
selects a receiver construction only from the existing value fact and recorded
physical edges. Selected lineage wins; otherwise the direct carrier or one
unique recorded physical-view closure must identify the construction. The first
inherited grammar authenticates one child interface with an exact retained
TypeDef carrier and zero or one unconstrained CLR parameter, preserving its
exact variance. The carrier is sealed, validates selected assembly, TypeDef,
hierarchy, and graph identity, and does not depend on a callable declared by
the child. Its complete set contains one or two exact `InterfaceImpl` rows and
at least one construction of the selected MethodDef owner. With one owner edge,
an optional second row may target a separately authenticated non-generic root
interface. Alternatively, both rows may be distinct exact constructions of the
MethodDef owner; duplicate physical edges are a declaration conflict. An owner
edge may close the owner or forward the receiver binder, including through the
admitted SZ-array carrier. Selection is by retained TypeDef identity, never row
order, and every row remains in the physical-view closure. The memberless
hostile child has no marker MethodDef. Recorded substitution maps `Child<int>`
and `Child<string>` to distinct exact parent views; the value-type route remains
`int32`, not `object`. When one receiver implements both `Source<int>` and
`Source<bool>`, an operation without selected lineage is unavailable. Existing
lineage may select either guaranteed view, but cannot manufacture
`Source<object>`. The adapter re-resolves raw metadata in the same assembly
graph, rejects retained disagreement, and never promotes a derived base view
into new provenance. Ambiguous or genuinely broad receivers remain unavailable.
The shared route independently admits arguments and produces the instantiated
direct, void, or split-nullable result fact.

Lazy external FIR2IR now transports this already-recorded TypeDef carrier
through a narrow target hook and compilation-local class metadata. Common IR
does not interpret or serialize the platform source. Backend class mapping can
therefore recover an exact memberless TypeDef without forcing declarations or
searching for a callable; callable MethodDefs retain separate authority and
must agree with the class carrier by assembly, TypeDef, hierarchy, and graph
identity. Other targets retain the previous null metadata behavior.

The production importer now accepts a complete interface contract with no
declared public callable. Resource-free external CLR DLLs prove same-assembly,
cross-assembly, multiple-edge, and multiple-owner-view
FIR-to-lazy-FIR2IR-to-CIL paths. The hostile multiple-edge child records
`Marker` before `Source<int>`; invocation still targets the parent's retained
`Read` MethodDef. A second child implements both `Source<int>` and
`Source<bool>`; explicitly typed Kotlin locals invoke each exact constructed
slot. All exact views return by plain `ldarg.0; ret`. No registry, fake member,
copied MethodDef, name lookup, row-order selection, cast, wrapper, or fabricated
construction is used.

The next retained-foreign boundary is one additional memberless interface hop,
including a separate-assembly chain. Every intermediate TypeDef and
`InterfaceImpl` must be authenticated from retained metadata, physical closure
must derive the final owner construction by substitution, and invocation must
still target the original parent MethodDef. No logical supertype reconstruction
or member-name search may fill a missing intermediate edge. Multiple members or
binders, variance conversions, constraints, classes, MethodImpls, and
Runtime/Stdlib application remain later.
The shared model and remaining boundary are owned by the
[physical-authority ADR](docs/decisions/draft-adr-generic-owner-physical-authority.md)
and [way forward](docs/programmes/way-forward.md).

The current focused gate passed the retained-metadata model suite (40 tests)
and all four memberless pipelines under both FIR parsers (8 tests), with zero
failures, errors, or skips.

## Current blockers

- Callable composition remains bounded to one structural root member. Multiple
  members/inputs, properties/defaults, constraints, method generics, deeper
  inheritance, explicit MethodImpls, value-class payloads, and Runtime/Stdlib
  application are not yet closed.
- Declaration and implementation authority is not yet closed for deeper base
  chains or multiple/distinct constructed views.
- Retained foreign CLR declaration authority remains bounded to an open root
  interface, one selected MethodDef, and either its root receiver or one
  memberless child interface with at most one unconstrained binder and at most
  two exact `InterfaceImpl` rows, in the same selected graph but not necessarily
  the same assembly. A second edge may be a non-generic root interface or a
  distinct construction of the MethodDef owner. More than two rows, a dual-owner
  plus auxiliary combination, generic or deeper auxiliary hierarchies,
  multiple binders or members, variance conversions, constraints, classes,
  MethodImpls, wider nominal carriers, and broader operation routing remain
  incomplete.
- Producer-wide state remains incomplete beyond the bounded direct-owner-
  parameter/plain-field grammar, including nested carriers, multiple owner-
  dependent fields,
  nullable/value-class storage, volatile state, mixed captures, open writer
  graphs, and external state authority. Shared per-value provenance also
  remains incomplete.
- Conversion from a generic child semantic-capability carrier to a differently
  owned base capability remains a separate interface-routing gap; Stage 6 does
  not claim to solve it.
- Remaining retained-foreign projected conversions and SZ-array entry guards
  are not yet proven.
- Complete Runtime/Stdlib coverage, Framework/CoreCLR deployment breadth,
  ReadyToRun, trimming, NativeAOT, tooling, and rollback still block a
  production cutover.
- Wider target and release gaps are listed only in the
  [way forward](docs/programmes/way-forward.md).

## Navigation

- Documentation authority and index: [`docs/README.md`](docs/README.md)
- Ordered work and release gates:
  [`docs/programmes/way-forward.md`](docs/programmes/way-forward.md)
- Physical authority and value provenance:
  [`docs/decisions/draft-adr-generic-owner-physical-authority.md`](docs/decisions/draft-adr-generic-owner-physical-authority.md)
- Generic-interface candidate:
  [`docs/decisions/draft-adr-reified-generic-interface-owner.md`](docs/decisions/draft-adr-reified-generic-interface-owner.md)
- Generic-owner programme:
  [`docs/programmes/generic-class-owner-reopening.md`](docs/programmes/generic-class-owner-reopening.md)
- Atomic migration and rollback:
  [`docs/programmes/generic-class-owner-migration-plan.md`](docs/programmes/generic-class-owner-migration-plan.md)
- Historical evidence: [`docs/archive/README.md`](docs/archive/README.md)

Update this file only when the integration base, latest verified checkpoint,
active work, or current blockers change. Git owns chronology, ADRs own lasting
decisions, programmes own future ordering, and dated archives own detailed
evidence.
