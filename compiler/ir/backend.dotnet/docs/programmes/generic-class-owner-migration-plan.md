# Generic class owner migration and rollback plan

- Status: **Draft programme artifact — no production migration authorized**
- Date: 2026-08-12
- Programme:
  [`generic-class-owner-reopening.md`](generic-class-owner-reopening.md)
- Carrier/admission design:
  [`generic-class-owner-carrier-matrix.md`](generic-class-owner-carrier-matrix.md)
- Candidate ADR:
  [`../decisions/draft-adr-reified-generic-class-owner.md`](../decisions/draft-adr-reified-generic-class-owner.md)

This plan answers when and how the target may move from the accepted erased
generic-class owner to the candidate real CLR-generic owner without cycling
through incompatible intermediate ABIs.

## Timing decision

The work has two deliberately different times:

1. **Now, while pre-ABI:** design and hostile-test the hardest representation,
   runtime construction, dispatch family, cast policy, binding schema,
   reflection normalization, and rollback boundary. Keep all typed-owner code
   experimental and outside normal artifact emission.
2. **After ordinary product breadth and real applications:** decide and execute
   one production cutover using evidence from inheritance, reflection,
   exceptions, concurrency/memory semantics, separate assemblies, C# use,
   JIT, ReadyToRun, NativeAOT, trimming, size, startup, and throughput.

Postponing all design would let new compiler/runtime surfaces accidentally
depend on the erased physical ABI. Migrating production now would choose a
permanent construction and dispatch cost before the backend can measure real
applications. The split keeps architectural direction early and ABI
publication late.

The first implementation follows that split. A normal backend lowering now
builds fail-closed architecture plans for local Kotlin generic classes, but
the plan vocabulary has no admitted result and physical emission ignores it.
The subsequent erased-owner lowering verifies complete planning coverage and
then keeps every class erased. This seam lets the semantic corpus continuously
exercise metadata-fixed, broad-body, state, and output analysis without
creating a mixed ABI or an easy-owner pilot.

The seam now goes one step further without crossing the boundary: it creates
detached real-IR typed/semantic/dispatcher members and returns immutable
test snapshots containing state, signature, default, direct-super, logical
binding, and complete local producer-graph evidence. The graph is built once
per module over functions, constructors, all function-access edges, and field/
anonymous initializers, then projected per owner. Tests assert the hostile
snapshots, and a test-owned physicalizer consumes their state/member roles to
generate a temporary generic producer plus a separately compiled C# subclass/
consumer. The emitter cannot see the detached members, and neither snapshots
nor prototype metadata are serialized. Promoting these shapes into
`dotnet.ir` or normal TypeDefs remains part of the one atomic production
cutover, not an incremental vocabulary leak.

The temporary producer now derives exact constructor, member-role, and lowered
default-helper signatures from those compiler snapshots. Its bounded carrier
grammar fails closed for unsupported types/shapes; it never substitutes an
unproven `object`. The ordinary compiler MethodDef base name receives uniform
role suffixes, and transitive state-access evidence selects read/write routes.
No hostile source member name selects a carrier, role signature, MethodDef
identity, or recorded state path. Names remain test-scenario/body labels.
Rewriting every diagnostic producer source label in-memory must produce an
equal artifact. Captured inner parameters with a pre-normalization slot-domain
mismatch remain without exact proof. Any generated/user MethodDef identity
collision rejects the artifact before it can become cross-assembly binding
evidence.
If later external binding changes a consumer's role/domain family, its stale
local exact signatures are discarded in favor of the decoded producer record.

Local detached generic subclass families are now linked role-by-role. An
inherited semantic hook remains a separate derived obligation, while the
private dispatcher is never virtualized as an override. Cross-assembly
consumers retain the overridden producer logical key. The architecture
artifact, now at schema 7, binds that key to the exact producer-selected
typed/semantic MethodDef owner/name, dispatch, slot-domain vector, and neutral
structural signature after complete producer-fingerprint/schema validation.
Stale, truncated, wrong-producer, duplicate, incomplete, and
missing-member records fail before any binding is returned; final capability
dispatchers never become override targets. Each dispatcher separately records
the exact non-generic capability MethodDef with the same signature. Recursive
type expressions retain owner/method parameters, named instances, and SZ arrays;
direct-super targets and the static masked-default helper also carry complete
signatures. Version 4 also binds the exact target profile, open-TypeDef
classification, statically exact constructor MethodDefs/visibility/constructed
owners and `this`/`base` edges, plus each selected state field and its exact
typed/semantic read/write conversions. Runtime-selected and semantic-fallback
construction remain unadmitted. Version 5 also binds each producer open
implementation TypeDef to its existing KLIB classifier key, retains logical
type arguments only in KLIB, hides capability TypeDefs from classifier/member
reflection, and collapses every physical member family to one logical callable
with an exact invocation entry. Exact classifier lookup and ancestry-based
instance classification remain distinct. The artifact is test-owned and is
not serialized into today's DLL/KLIB. A bounded Kotlin-produced subclass
physicalizer now consumes the complete record without switching the emitter.
It accepts only a caller-selected current-compilation TypeDef path, uses the
compiler-recorded delegated constructor for the exact immediate base, requires
an exact admitted child/base constructor signature match plus positional
identity argument forwarding, and copies every
typed/semantic override identity and direct-`super` target from the producer.
Fake overrides join their real declaring KLIB roots; legal final children are
sealed and final producer slots reject the physicalization. The resulting
record drives the hostile C# oracle only.

A separate consumer-side construction record now corrects the earlier
constructor-owned mode model while keeping runtime roots outside producer
schema 7. The final compilation supplies a finite set of concrete runtime
types; the decoded
producer supplies the open owner, semantic capability, and strict public
one-`!T` constructor. The current fallback proof requires an unconstrained
owner parameter. Listed value/reference roots select statically visible exact
`C<P(T?)>` constructions, including idempotent already-nullable values; invalid
or nested nullable roots reject the plan. One mandatory `C<object>` route
handles every unlisted type through the same
capability/state. The plan cannot express `MakeGenericType`, duplicate/open
roots, a missing fallback, or recovery of an exact carrier from fallback.
Both CLRs execute the hostile table. NativeAOT managed analysis is clean with
IL3050/IL2026 as errors, but the missing platform linker still blocks native
execution; complete-toolchain link/run and product measurements remain the
next migration proof.

The same exact record-driven finite factory is now exported as a closed,
fingerprinted net10 measurement bundle. Its JIT, ReadyToRun, and full-trimming
runs share one versioned workload, verify exact/fallback state and hostile
dispatch, require a cross-mode checksum, and record startup, workload time,
allocation, peak working set, publication time, and footprint. NativeAOT is a
separate opt-in fail-hard mode and remains unproven on this machine. This
removes the need for a second handwritten AOT/performance model, but condition
9 still requires successful NativeAOT execution and representative real
applications. See
[`../archive/generic-owner-measurement-corpus-2026-08-13.md`](../archive/generic-owner-measurement-corpus-2026-08-13.md).

Schema 6 also records each producer GenericParam's ordered index, CLR special
constraints, and structural type constraints. The current child physicalizer
admits only compiler-derived constraints in its exact supported grammar and
requires equality with the producer row. Matching arity never substitutes for
constraint compatibility. A TypeDef constraint cannot reference a method
parameter, and producer constraints cannot reference consumer-compilation
types; unsupported Kotlin bounds retain a fail-closed proof obligation.

Schema 7 adds a complete classification catalog for every logically bindable
generic owner in the producer snapshot. Each entry records its logical owner,
arity, disposition, and sorted constructor/member binding keys independently
of whether a physical family exists. Every published family must match its
catalog entry exactly. The metadata-fixed `HostileNullableDerived<T>` entry is
therefore present with
`BLOCKED_METADATA_FIXED_CONDITIONAL_SUPERTYPE` while no CLR-generic family is
published. Consumer resolution distinguishes that recorded absence from an
unknown member or malformed producer. This is still test-owned architecture
evidence; production DLL/KLIB classification remains part of the atomic
cutover.

This proof kind intentionally admits only the hostile public/open/non-inner
child with one direct base and one constructor. Additional interfaces, fields,
initializers, nested types, states, non-fake members, secondary constructors, or
constructor effects fail. Inherited fake overrides and default helpers remain
inherited without new MethodDefs.

Broad input positions are semantic override-family authority. They reach a
fixed point across local roots and are inherited from a decoded external
producer record; an apparently strict overriding declaration cannot narrow the
family. Strict and declaration-independent positions remain local physical
shape after substitution.

The local graph does not equate “no semantic-reachable writer” with typed
value provenance. It now traces write values from typed/semantic callable
boundaries through call arguments, local definitions/assignments, returns, and
casts. A logical cast to `T` preserves its input provenance; it cannot upgrade
an object-domain value. Every producer must be physically typed to select typed
storage, while any semantic producer selects object state and an unsupported or
source-free path remains unresolved. This fail-closed result is part of the
architecture phase, not a reason to emit an easy typed owner.

## Rules during ordinary feature development

Until cutover:

- the accepted erased owner remains the only production owner and correctness
  oracle;
- new language/stdlib features may depend on logical KLIB generic semantics
  and the recorded erased binding, but must not expose a new public assumption
  that an ordinary Kotlin generic class can never become `C<T>`;
- no feature receives a private per-class public owner exception;
- no export facade is described as the future internal Kotlin owner;
- hostile generic-owner tests remain green as the semantic corpus grows;
- every new broad candidate, override, reflection, array, value-class,
  coroutine, or concurrency shape is added to the migration inventory; and
- removable local specialization may collect evidence only when disabling it
  preserves behavior and all DLL signatures.

## Entry conditions for the production decision

The cutover is not considered until all of these are true:

1. The hardest open mutable invariant Kotlin/C# matrix passes with one owner
   and one state on same and separate assemblies.
2. Open-nullable construction has a selected JIT and AOT/trimming strategy;
   no invalid static `Nullable<T>` token or unbounded dynamic-code assumption
   remains.
3. Metadata-fixed shapes—especially `D<T> : C<T?>`—have a selected, tested,
   and C#-honest fixed representation or are deterministically excluded from
   reified admission; runtime type construction is not used to hand-wave a
   TypeDef base/interface/signature edge.
4. Strict, broad-candidate, nested, default, `super`, and multi-level override
   families are compiler-produced and physically validated.
5. Field carriers are selected from complete semantic mutation reachability;
   widened incompatible writes retain Kotlin failure timing with one state,
   and paired typed/semantic output overrides remain coherent or cause
   deterministic exclusion.
6. KClass, KType, callable reflection, casts, arrays, nested owners, and
   value-class carriers normalize to one logical declaration.
7. The producer binding schema is neutral, versioned, self-validating, and
   consumed without name inference.
8. Representative real Kotlin applications and C# consumers/subclasses run
   on both profiles and include arbitrary structs plus nullable values.
9. JIT, ReadyToRun, NativeAOT, trimming, metadata/code size, startup,
   allocations, boxing, compile time, and memory measurements are reviewed.
10. Concurrency and memory-model primitives do not require a second state or
   representation-specific locking protocol.
11. The exact C# public/protected surface is judged understandable, including
   any documented semantic candidate hook.
12. The complete migration diff and exact revert are rehearsed on a temporary
    branch with the strict aggregate and artifact-skew rejection.

Passing these conditions permits a decision; it does not predetermine that
dynamic exact construction, semantic fallback, or every declaration family
will be admitted.

## Atomic cutover set

If accepted, one coherent migration changes all owners of the physical model
together:

1. the owner-admission classifier and type/carrier mapper;
2. generic TypeDef, GenericParam, constraint, base, interface, field,
   constructor, and member emission;
3. semantic capability and typed/hook/dispatcher lowering;
4. call, field, constructor, cast, type-test, array, default, and `super`
   selection;
5. override resolution, `MethodImpl`, C# subclass/implementation contracts,
   and profile default-method behavior;
6. KLIB companion physical bindings and their schema/version rejection;
7. runtime classification plus KClass/KType/callable reflection
   normalization;
8. Runtime and Stdlib generic owners and every bootstrap assumption;
9. importer/exporter/Roslyn-facing mappings and diagnostics;
10. same-module, separate-module, portable, installed-product, malformed,
    stale-schema, IL/metadata, and C# test products; and
11. accepted ADRs, bootstrap rules, status, and distribution documentation.

For a logical declaration, erased and reified production owners never coexist.
The admission algorithm may deliberately keep an unsupported declaration
shape erased, but that classification is deterministic from the declaration
contract and is recorded by its producer. It is not chosen per call site,
source visibility, module, profile, or optimization level.

There is no easy final/read-only pilot ABI. The first cutover candidate must
already satisfy the hostile open mutable model, even if some rarer declaration
shapes remain explicitly unadmitted.

## Schema and artifact boundary

The cutover bumps every physical contract whose old consumer could otherwise
misread a `C<T>` owner as the non-generic erased owner. At minimum this includes
the self-describing physical declaration codec and any runtime/compiler surface
used by capability dispatch or reflection normalization. Old/new producer,
consumer, Runtime, Stdlib, reflection, and C# implementation artifacts must
fail with an explicit version error rather than bind partially.

Because the target is pre-ABI, no compatibility shim or dual decoder is
required merely to preserve unpublished artifacts. A migration tool is useful
only if real development artifacts justify it; it must not make mixed owner
identity executable.

## Rollback boundary

Rollback is the exact inverse of the one cutover tranche:

- revert owner admission, lowerings, mapping/emission, binding schema,
  runtime/stdlib/reflection support, tooling, tests, and decisions together;
- restore the previous erased schema/version and rebuild every target product;
- reject, rather than consume, any reified-owner artifact left from the
  reverted epoch; and
- rerun the strict aggregate plus the representative real-app corpus on the
  erased semantic oracle.

Do not roll back by adding wrappers, copying state into erased twins, teaching
old consumers both identities, or retaining typed helper TypeDefs in public
artifacts. Those repairs would create the mixed period this programme exists
to avoid.

The architecture probes and hostile tests survive a rollback as evidence and
future regression oracles. Experimental code that cannot be completely
disabled without changing artifacts is already production migration code and
must not land during the current design phase.

## Go/no-go outcomes

The production checkpoint records one of three outcomes:

- **Go:** accept the draft ADR, execute the atomic cutover, and freeze its
  schema only after the complete gates pass.
- **Constrain:** admit only declaration shapes selected by the deterministic
  model and keep every other whole declaration on the erased owner; publish no
  partly truthful `C<T>` surface.
- **No-go for now:** retain erased production owners, keep explicit C# export
  and local specialization independent, and preserve the hostile evidence for
  a future runtime/toolchain change.

None of these outcomes requires repeated switching. The current phase learns;
the later checkpoint chooses once from complete evidence.
