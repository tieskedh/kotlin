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
- Current checkpoint: physical library ABI 66, generic-owner artifact schema
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
recursive inherited-interface, binder-forwarding, constrained-edge, and lazy
TypeDef transport authority, plus retained interface/delegate reference
variance with conversion-scoped constraint validation. The current focused
evidence compiles the importer and test fixture and passes 172 model tests: 87
shared physical-value tests and 85 retained-metadata tests. Six unconstrained
memberless external-DLL pipelines remain green with both FIR parsers. Git owns
the intermediate chronology.

Local natural-interface admission now records one explicit producer-selected
physical variance vector for every admitted TypeDef. This repairs the declaration-
authority gap in which an older bounded `I<out T>` family was emitted correctly
but omitted from BOUND solely because it had no newer complete-surface rewrite
plan. The existing value/operation fixture passes in candidate and erased modes
across PSI, LightTree, Framework 4.8, and .NET 10. Exact evidence is in the
[dated archive](docs/archive/generic-owner-local-natural-variance-authority-2026-09-01.md).

Final physical-value facts now authorize three bounded local-placement forms. A
direct immutable alias may retain an equal owner-bound `C<!n>` producer/storage
carrier. An exhaustive control-flow initializer may retain an identical carrier
or the unique construction of one physically recorded common interface family
across all reaching arms. A regular parameter whose role-specific physical
entry prototype is exactly the current owner parameter may enter and remain in
local storage as the same `!n`; an object-domain semantic-hook prototype remains
object-domain. Selected lineage can disambiguate only a view already present in
every recorded closure; missing or ambiguous views remain unavailable and never
fabricate `I<object>`. The emitter reconstructs each carrier from the physical
MethodDef owner and independently validates the whole initializer, every branch
at its fixed storage boundary, or the direct live parameter read. Source and
compiler-owned aliases share the rule; star/projected and mutable/multiple-write
controls receive no token. Exact evidence is in the
[direct-placement](docs/archive/generic-owner-physical-local-storage-consumption-2026-09-01.md),
[control-flow](docs/archive/generic-owner-physical-control-flow-join-placement-2026-09-01.md),
and [typed-entry](docs/archive/generic-owner-physical-typed-parameter-entry-2026-09-01.md)
archives.

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

The local placement consumer now covers direct equal-carrier aliases, one
exhaustive reference-shaped control-flow join, and one exact owner-parameter
entry. The join uses a logical interface only to select a family; physical
construction authority comes exclusively from the intersection of recorded
interface closures. The parameter entry comes only from the role-specific
physical prototype and is checked against the live MethodDef slot; a Kotlin
source type cannot manufacture `!T`, and a semantic prototype does not inherit
the typed entry's fact. None of these permissions authorizes conversion,
adaptation, boxing, state, or ABI changes. The older compiler-origin recognizer
remains only as migration fallback for transfer shapes the shared model has not
yet derived.

The next consolidation boundary is exact typed result entry/production and the
remaining parameter-entry compositions, followed by null/bottom/unknown
control-flow arms and explicit representation-changing conversions. Fields,
captures, foreign/fixed/nested carriers, stars/projections, and split-nullable
layouts stay outside these local consumers rather than acquiring local
exceptions.

Stage 7 composes `STRICT_OWNER_INPUT(!K)` with an independently recorded
`SplitNullable(STRICT_OWNER_OUTPUT(!V), out bool)` result. ABI 66 retains the
independent `H` semantic-role/result-layout fields introduced in ABI 65 and
adds producer-recorded sealed-delegate variance authority; local BOUND,
producer-final `N`, direct consumers, semantic capability dispatch, and
ordinary natural-only C# implementations consume the same MethodDef authority.
The admitted grammar is deliberately limited to a single-member root interface
with one invariant input and one distinct covariant nullable output. Existing
result-only split-nullable families remain green and Runtime `Map` retains its
previous contract.

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
one selected abstract MethodDef from exact raw metadata. Authority deliberately
remains per MethodDef: a consumer may bind multiple independently retained
methods from the same owner without forming a name-based declaration family. An
inherited receiver may now be the root of a resource-bounded acyclic graph of
public top-level memberless interfaces. Every visited TypeDef has a complete
ordered vector of up to 1,024 CLR parameters with exact variance, no base
class or MethodImpl, and a complete retained/raw `InterfaceImpl` edge set.
Bounded TypeSpec-backed nominal constraints and exact nominal rows to public
interfaces, ordinary reference classes, sealed CLR delegates, and value types
are retained. Auxiliary generic TypeDefs require a complete supported binder
vector, including exact CLR reference/value/default-constructor and by-ref-like
flags; their recursive constructions are depth- and node-bounded. Reference,
non-nullable-value, and nullable-value carriers use the shared physical
classifier. Variant non-interface binders are accepted only when the shared
delegate classifier proves a sealed TypeDef whose immediate selected base is
the selected `System.MulticastDelegate`. An actual signature must agree with
the selected TypeDef's class/value marker; only a bare
TypeDef/TypeRef constraint row may infer that marker because the row has no
signature-side kind. Non-nullable and `System.Nullable<T>` carriers preserve
`NON_NULL_ONLY` and `INLINE_NULLABLE_VALUE` respectively. A constrained
construction may occur at any depth inside one retained edge only after the
shared CLR nominal and special-constraint validators prove that exact subtree
in the source TypeDef's open binder context. Open binder rows themselves remain
target-independent metadata authority. Construction proofs involving special
constraints or a possibly by-ref-like argument require an explicit target
profile; implicit
by-ref-like eligibility is checked even when the target binder has no other
constraint. Their proof is keyed by source, exact edge root, and constrained
subtree; neither the outer proof nor a caller-authored construction can reuse
it. These TypeDefs and their raw-authenticated identity are authority; an
unretained edge set is not. Every edge is authenticated through its exact
AssemblyRef and retained in the shared physical-view closure; that closure
remains the sole substitution engine. The graph may be deep, branching, and
diamond-shaped, and must reach the selected MethodDef owner by retained
identity. Cycles, violated constraints, and retained/raw disagreement are
conflicts; missing target/core authority, unsupported shapes, and the depth/
node/edge/binder/constraint ceilings fail unavailable. Raw binder and
constraint-row counts are reserved before the shared generic-context resolver
allocates their normalized views; duplicate metadata handles do not reduce the
raw row budget.

An imported operation selects its receiver construction only from existing
value facts and this recorded closure. Selected lineage may choose an already-
guaranteed construction but cannot establish one; otherwise the direct carrier
or a unique closed view must select it. Distinct owner constructions remain
ambiguous without lineage. Only that independent provenance/closure proof can
mint an operation-scoped physical-view authority token. Signature substitution,
argument admission, and result production may reuse a constrained subtree only
through that token; passing a construction itself cannot self-authenticate it.
The shared route then produces the instantiated direct, void, or split-nullable
result fact without a logical-supertype reconstruction, member-name search,
fabricated construction, or `object` fallback.

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
and recursive four-assembly paths through FIR, lazy FIR2IR, and CIL. One
recursive path closes two one-binder intermediates at `int32`; another proves
`PairOuter<int,string> -> PairForwarding<string,int> -> Source<int>`. The latter
retains both ordered binders and their permutation without admitting the false
`Source<string>` view. The fixture also consumes three independently retained
parent MethodDefs. Two have the same name and arity but different `int32` and
string parameter signatures. Both parser pipelines emit the exact original
overload tokens; direct and selected calls likewise target the original parent
MethodDef, with no references to the two intermediate assemblies in emitted
CIL. No registry, fake member, copied MethodDef, name or arity lookup, row-order
selection, cast, wrapper, or fabricated construction is used.

The first constrained inherited-binder boundary is now closed for nominal
TypeSpec rows expressible by the bounded carrier grammar, including a forwarded
`TDerived : TBase` implication and a concrete `string : object` close. An exact
direct nominal row may also name a selected, raw-authenticated public
non-generic CLR interface, ordinary reference class, or value type. A TypeSpec
may recursively construct any selected public generic form when its complete
binder vector is supported and unconstrained, including with an exact value-
type argument. The same exact carrier may close or flow through an inherited
edge without fabricating the auxiliary TypeDef's edge closure. Ordinary class
and value carriers are classified through the shared physical type classifier.
Covariant and contravariant delegate binders are retained only for a sealed
TypeDef whose immediate selected base has the exact selected
`System.MulticastDelegate` identity; an `Invoke`-shaped member or name is never
evidence. Ordinary variant classes and non-sealed delegate TypeDefs conflict,
while missing selected core or hierarchy authority remains unavailable.
Exact direct and recursively constructed non-nullable value types retain
`VALUE_TYPE`/`NON_NULL_ONLY` on Framework 4.8 and .NET 10; a validated
`System.Nullable<int>` edge retains `VALUE_TYPE`/`INLINE_NULLABLE_VALUE` without
opening its constrained construction to unrelated callers. Exact delegate
constructions now participate in the same bounded CLR reference-variance proof
as interfaces. The shared frontend/backend planner fixes ordered `in`/`out`
assignment direction, while physical authority proves reference shape and exact
ancestry. Conversion preserves the produced carrier and records only a per-
value view; recorded closure, identity, and state remain unchanged. Nested and
array reference conversions compose, while differing value arguments, unknown
binders, wrong direction, and missing hierarchy fail closed. The full boundary
and hostile matrix are in the
[physical-authority ADR](docs/decisions/draft-adr-generic-owner-physical-authority.md).
Constrained TypeDefs may now occur recursively inside one exact retained edge,
but each constrained subtree receives its own shared-validator proof; an outer
proof alone is insufficient and the general construction helper remains
closed. Exact `class`, `struct`, `new()`, and `allows ref struct` binder forms
compose through the same direct and nested grammar. Target-aware propagation
proves an open by-ref-like-capable binder on .NET 10 and rejects it on Framework
4.8; no target remains unavailable. A newly requested CLR-variance target now
receives a fresh conversion-scoped proof from the same selected metadata and
shared nominal/special validators. The authority covers directly retained
owners and inherited graphs, validates every constrained subtree, and is tied
to the exact target profile and argument vector. It neither reuses an edge proof
nor opens the general construction helper; successful conversion adds only a
per-value view and does not mutate recorded ancestry. Classes as inherited graph
nodes, MethodImpls, properties, and Runtime/Stdlib application remain later.
The fast external-DLL FIR fixture intentionally has no selected physical core
catalog, so this constrained slice is proven in the metadata model with a
complete synthetic selected core; an end-to-end constrained FIR pipeline
remains an explicit later gate rather than using a second local constraint
solver.

Physical-library ABI 66 now records the sealed CLR delegate exception as an
orthogonal class-TypeDef fact. The consumer adapter admits only that decoded
producer record, preserves its complete ordered unconstrained variance vector,
and binds the TypeDef as `CLASS`; a TypeDef name, Kotlin function shape,
`Invoke` member, ordinary variant class record, or caller-authored
`supportsClrDelegateVariance` flag remains insufficient. Covariant,
contravariant, and mixed two-binder constructions use the same reference-only
variance proof as retained delegates. Successful conversion changes only the
per-value view; it does not add an InterfaceImpl edge, rewrite the carrier, or
create state. The producer schema currently records no delegate GenericParam
constraints, delegate members, or operation endpoints, and the Kotlin emitter
does not yet synthesize a sealed delegate TypeDef from an ordinary Kotlin
declaration; those facts remain outside this bounded declaration proof.

The shared model and remaining boundary are owned by the
[physical-authority ADR](docs/decisions/draft-adr-generic-owner-physical-authority.md)
and [way forward](docs/programmes/way-forward.md).

The retained-metadata model gate passes 85 tests, the shared physical-value
model passes 87 tests (172 combined), and the producer-delegate authority gate
passes 8 tests. All six unconstrained
memberless pipelines pass under both FIR parsers (12 tests), with zero failures,
errors, or skips.

## Current blockers

- Callable composition remains bounded to one structural root member. Multiple
  members/inputs, properties/defaults, constraints, method generics, deeper
  inheritance, explicit MethodImpls, value-class payloads, and Runtime/Stdlib
  application are not yet closed.
- Retained foreign CLR declaration authority remains independently bounded per
  selected MethodDef on an open root interface and a resource-bounded acyclic
  inherited graph. Multiple members and same-name/same-arity overloads can
  coexist without becoming a name-based family. Graph nodes must be public
  top-level memberless interfaces with a bounded ordered binder vector. Only
  bounded TypeSpec-backed nominal constraints, exact direct nominal rows, and
  recursive constructions of public generic interfaces, ordinary reference
  classes, sealed CLR delegates, or value types with complete nominal and
  special binder authority are admitted. A constrained construction is admitted
  as an independently validated subtree of one exact retained edge, or solely
  as the target of one conversion-scoped variance proof. A target profile is
  required wherever special-constraint or possible by-ref-like validation
  participates; the result never becomes reusable declaration, edge, or general
  construction authority.
  Variant delegate constructions require exact selected delegate-root identity
  and sealed metadata. Reference-only variance is available for already
  admitted interface and retained delegate constructions, including constraint-
  bearing targets independently proven from selected raw metadata, using
  physical binder rows, shared constraint/reference classification, exact
  ancestry, and per-value provenance. ABI 66 closes unconstrained producer-
  recorded delegate declaration authority, but producer delegate constraints,
  emitted members/operations, and source production remain incomplete. Classes
  as graph nodes, MethodImpls, properties, and broader operation routing also
  remain incomplete. Distinct exact constructions are retained; selection still
  requires independently proven lineage or a verifier-valid variance transfer
  to the requested construction.
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
