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
recursive inherited-interface, binder-forwarding, and lazy TypeDef transport
authority. The current focused evidence compiles the importer and test fixture,
runs the 46-test metadata model gate, and runs six memberless external-DLL
pipelines with both FIR parsers. Git owns the intermediate chronology.

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

One retained-foreign adapter binds an open, parentless CLR generic interface and
one selected abstract MethodDef from exact raw metadata. An inherited receiver
may now be the root of a resource-bounded acyclic graph of public top-level
memberless interfaces. Every visited TypeDef has zero or one unconstrained CLR
parameter with exact variance, no base class or MethodImpl, and a complete
retained/raw `InterfaceImpl` edge set. Every edge is authenticated through its
exact AssemblyRef and retained in the shared physical-view closure; that closure
remains the sole substitution engine. The graph may be deep, branching, and
diamond-shaped, and must reach the selected MethodDef owner by retained identity.
Cycles and retained/raw disagreement are conflicts; missing authority,
unsupported shapes, and the depth/node/edge ceilings fail unavailable.

An imported operation selects its receiver construction only from existing
value facts and this recorded closure. Selected lineage may choose an already-
guaranteed construction but cannot establish one; otherwise the direct carrier
or a unique closed view must select it. Distinct owner constructions remain
ambiguous without lineage. The shared route then admits arguments and produces
the instantiated direct, void, or split-nullable result fact without a logical-
supertype reconstruction, member-name search, fabricated construction, or
`object` fallback.

Lazy external FIR2IR now transports this already-recorded TypeDef carrier
through a narrow target hook and compilation-local class metadata. Common IR
does not interpret or serialize the platform source. Backend class mapping can
therefore recover an exact memberless TypeDef without forcing declarations or
searching for a callable; callable MethodDefs retain separate authority and
must agree with the class carrier by assembly, TypeDef, hierarchy, and graph
identity. Other targets retain the previous null metadata behavior.

The production importer now accepts an inherited interface contract with no
declared public callable. Six resource-free external CLR pipelines prove same-
assembly, cross-assembly, multiple-edge, multiple-owner-view, one-intermediate,
and recursive four-assembly paths through FIR, lazy FIR2IR, and CIL. The
recursive fixture closes two independent binders at `int32`; direct and selected
calls still target the original parent MethodDef, with no references to the two
intermediate assemblies in emitted CIL. No registry, fake member, copied
MethodDef, name lookup, row-order selection, cast, wrapper, or fabricated
construction is used.

The next retained-foreign boundary is complete multi-member declaration and
operation authority: separately authenticate multiple selected MethodDefs on
one root and prove their inherited routes and overload identity without name
matching. Multiple binders, variance conversions, constraints, classes,
MethodImpls, properties, and Runtime/Stdlib application remain later.
The shared model and remaining boundary are owned by the
[physical-authority ADR](docs/decisions/draft-adr-generic-owner-physical-authority.md)
and [way forward](docs/programmes/way-forward.md).

The current focused gate passed the retained-metadata model suite (46 tests)
and all six memberless pipelines under both FIR parsers (12 tests), with zero
failures, errors, or skips.

## Current blockers

- Callable composition remains bounded to one structural root member. Multiple
  members/inputs, properties/defaults, constraints, method generics, deeper
  inheritance, explicit MethodImpls, value-class payloads, and Runtime/Stdlib
  application are not yet closed.
- Retained foreign CLR declaration authority remains bounded to one selected
  MethodDef on an open root interface and a resource-bounded acyclic inherited
  graph. Graph nodes must be public top-level memberless interfaces with zero or
  one unconstrained binder. Multiple selected members and overloads, multiple
  binders, variance conversions, constraints, classes, MethodImpls, properties,
  wider nominal carriers, and broader operation routing remain incomplete.
  Distinct constructions are retained, but selecting one still requires
  independently proven lineage.
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
