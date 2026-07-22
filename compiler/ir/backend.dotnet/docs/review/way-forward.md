# Kotlin/.NET backend way forward

> **Baseline:** branch `dotnet`, rebased on `origin/master` at `0349ed5cd`, reviewed 2026-07-21
>
> **Status:** living pre-ABI execution plan
>
> **Normative boundary:** this document orders work and sets gates; ADRs own representation and ABI
> decisions.

## 1. Core-team position

The backend continues. Its erased Kotlin identity model, CLR-native object model, `System.Object`
foundation, concrete nullable-primitive representation, and runtime/stdlib ownership split justify
further investment.

It does not publish a stable or third-party experimental ABI yet. Work now enters a
foundation-correction phase. Feature breadth is secondary until successful publication is atomic,
producer and consumer identities agree, CLR visibility matches Kotlin visibility, and public
runtime/name evolution is versioned.

Three target profiles are required products, not probes or fallback modes:

| Profile | Product | Purpose |
| --- | --- | --- |
| `.NET Framework 4.8` (`net48`) | applications and libraries | The established Framework ecosystem |
| `.NET Standard 2.0` (`netstandard2.0`) | libraries only | One portable library asset consumable by both supported runtimes |
| `.NET 10` (`net10.0`) | applications and libraries | The modern LTS runtime and modern CLR capabilities |

`.NET Standard` is not an executable runtime. The compiler selects a profile before target
lowerings and emits profile-specific code, metadata references, runtime/stdlib assets, and
packaging. Shared Kotlin semantics remain invariant; physical implementation may differ where the
CLR capability sets differ. Modern features do not get simulated into the Framework ABI merely to
make the IL identical, and Framework limitations do not suppress a sound `net10.0` design.

The `netstandard2.0` surface is the deliberate intersection for a library intended to run on both
runtimes. A library that needs a profile-specific API targets `net48` or `net10.0` explicitly.
Target-specific source/API availability must be represented by target configuration and
multiplatform source sets, never by changing the meaning of the same common Kotlin declaration.
Each application deploys one profile-selected Kotlin runtime/stdlib pair; the `net48` and
`net10.0` platform variants must be binary supersets of the portable `netstandard2.0` platform
surface so shared libraries do not require a second Kotlin runtime identity.

References:

- [Microsoft .NET support policy](https://dotnet.microsoft.com/en-us/platform/support/policy)
- [Microsoft .NET Framework support policy](https://dotnet.microsoft.com/en-us/platform/support/policy/dotnet-framework)

## 2. Reconciliation decisions

The following dispositions resolve disagreements between the supplied reviews.

### 2.1 Declaration eviction

The fixpoint mechanism may remain as an explicit developer aid while the frontend is incomplete.
It is forbidden as a successful library or stdlib publication policy. Any eviction in those modes
is an error now; the endpoint is a located FIR diagnostic and no backend eviction.

### 2.2 Producer/consumer logical keys

This is a confirmed P0 correctness defect. `DotNetVarargLowering` mutates parameter types before
the producer computes an `IdSignature`, while a metadata consumer sees the pre-lowering shape.
Logical keys must be computed immediately after FIR-to-IR and retained by symbol. Codegen may only
attach physical identities to those precomputed keys.

### 2.3 Visibility

The earlier visibility finding is now **verified**, not `CARRYOVER-REVERIFY`. Ordinary source-
private top-level classes and functions are emitted public in committed IL goldens; internal
members also take the public default. This is distinct from deliberately metadata-public compiler
ABI.

Required mapping:

- Kotlin `public` -> CLR `public`;
- Kotlin `protected` -> a deliberately audited CLR family form;
- Kotlin member `private` -> CLR `private`;
- Kotlin top-level `private` -> non-public type/member;
- Kotlin `internal` -> CLR `assembly`, with a separate friend-module design;
- compiler-required cross-assembly surface -> public only when required, marked with a Kotlin ABI
  attribute and hidden from ordinary completion where practical.

Synthetic accessors are preferred to widening source declarations. Sealed-constructor access and
foreign subclassing are part of this work package.

### 2.4 Arrays

The `Array<Int>`/`IntArray` collision is confirmed and the open `Array<T>` representation is
physically `T[]`. A generic producer can therefore instantiate it as CLR `int[]`.

The selected model preserves the Kotlin distinction rather than banning the substitution:

- `Array<T>` keeps the natural CLR `T[]` representation, including `Array<Int>` becoming `int[]`;
- `IntArray` is a Kotlin-owned wrapper with its own CLR runtime type, reference identity, and
  Kotlin metadata identity; the same rule applies to every specialized primitive-array type;
- the wrapper owns or aliases the primitive CLR vector used as storage, but the vector is never
  the canonical Kotlin value;
- ordinary Kotlin ABI exposes the wrapper, not `int[]`;
- deliberate C# export facades may project it as `int[]`, and the exported wrapper may offer
  standard CLR `op_Implicit` conversions. Those conversions are interop adapters and do not alter
  Kotlin type identity.

The array ADR now pins live aliasing, nullability, weakly interned repeated conversion/round-trip
identity, bounds/store behavior, generic substitution, and cross-module signatures. The implemented
foundation includes executing `Array<T>` with `T = Int`, nominal wrapper overload/identity tests,
and netstandard2.0 producers consumed from Kotlin and C# on net48 and net10. Full reflection and
debugger presentation remain later work; they must expose the wrapper as the Kotlin identity.

### 2.5 Exceptions

The earlier prescription of an entirely owned hierarchy plus comprehensive translation is not
accepted: foreign frames make comprehensive translation impossible. The current collapse is also
not accepted as final Kotlin semantics.

The selected model keeps every catchable foreign exception as the original CLR object.
`System.Exception` is the universal physical throwable root. Kotlin's logical
`Throwable`/`Exception`/`RuntimeException`/`Error` relationships are restored by compiler-generated
CLR catch filters calling one versioned, side-effect-free runtime classification predicate.

Kotlin-owned exact CLR types remain appropriate where exact physical identity is part of the
contract. Kotlin metadata preserves logical signature types where the physical signature uses the
universal root. Neither mechanism wraps or replaces a foreign exception. Catch variables,
signatures, type tests, rethrow, user exceptions, cancellation, critical faults, and C# admission
must use the same classifier contract. `CancellationException`, a raw C# `System.Exception`,
Kotlin `Error`, a foreign-frame fault, and repeated rethrow of the same object are mandatory
forcing cases.

### 2.6 Ordinary CLR surface versus C# exports

Kotlin-to-Kotlin ABI and deliberate C# exports remain separate surfaces. `Func`/`Action`, nullable
attributes, property conveniences, and default overloads belong to exports. Ordinary Kotlin ABI
that must be public for linking is marked as compiler ABI rather than presented as idiomatic C#.
C# conventions do not redefine Kotlin identity, variance, equality, or exceptions.

### 2.7 Pre-publication break policy

No Kotlin/.NET binary, metadata, runtime, or generated-name compatibility has been promised.
Until an explicit freeze decision is recorded, architectural corrections may replace all current
prototype representations. Make such breaks atomically across compiler, runtime, stdlib, metadata,
tests, and packaging; bump the prototype schema and reject stale artifacts. Do not add migration
adapters, dual-read logic, or compatibility aliases for binaries that were never shipped.

This freedom does not permit semantic drift between profiles. It removes compatibility with old
prototype artifacts, not Kotlin language compatibility or the obligation to keep
`net48`/`netstandard2.0`/`net10.0` coherent.

## 3. P0 work packages

The order is intentional. A later package must not start if an earlier package can invalidate its
public shape.

### P0-A — Make the build truthful

1. Replace both integration-test `?: return` paths with explicit assumptions and add a strict
   toolchain-required lane.
2. Reject every library/stdlib emission containing an evicted declaration.
3. Assert parity between Kotlin metadata-visible declarations and the physical declaration index.
4. Check conflicting fall-through versus branch stack depth at every emitted label.
5. Replace assembly-reference discovery by substring search over rendered IL with structured
   reference collection.
6. Unify `allowKotlinPackage` on one predicate used by both FIR analysis and CLI validation.

**Exit:** no successful test or published pair can claim work that did not execute or declarations
that do not exist.

**Implemented 2026-07-18:** strict toolchain/SAC execution mode, fatal declaration eviction for
published products, pre-lowering metadata-linkage coverage, label stack-join checks, structured
assembly-reference collection, and one `allowKotlinPackage` predicate shared by CLI and FIR.

### P0-B — Fix logical and physical identity

1. Precompute public logical ABI keys before lowerings.
2. Add a separately compiled vararg producer/consumer regression.
3. Define a .NET-owned physical-name grammar and version, or explicitly pin the Kotlin signature
   version used as its input.
4. Add digest input/output goldens and explicit stale-schema/profile-mismatch rejection fixtures.
5. Add a monotonic runtime-surface level to KLIB and runtime metadata; reject an insufficient
   runtime before assembly execution.
6. Bind every KLIB to its actual DLL by a content hash or stable PE identity recorded after
   assembly production.

**Exit:** recompilation phase ordering cannot change logical linkage, and incompatible artifacts
fail with a compiler diagnostic rather than load/JIT failure. Pre-freeze compiler generations are
not required to interoperate.

**Implemented 2026-07-18:** source declaration keys are retained across lowering and consumed by
the physical index; a separately compiled vararg producer/consumer pins the phase boundary; ABI
schema 4 records its logical-identity scheme, physical-name grammar, runtime-surface level, exact
profile, and deterministic `/det`-assembled DLL SHA-256; stale schema, profile, runtime surface,
DLL mismatches, and missing friend-identity fields are rejected during dependency loading. Schema
4 adds producer-authorized CLR friend identities. Digest input/output fixtures pin the version-1
helper-name hash.

### P0-C — Restore the Kotlin visibility contract

Implement the mapping in section 2.3, including top-level facades, accessors, fields, nested types,
companions, sealed constructors, synthetic access, `internal`, and friend modules. Add metadata
inspection and C# negative-access tests.

**Exit:** C# and reflection cannot access a source-private declaration; Kotlin cross-module and
friend access still works; deliberately public compiler ABI is mechanically identifiable.

**In progress 2026-07-18:** ordinary top-level/type/member/const/object visibility now maps to CLR
public, assembly/family, or non-public metadata instead of defaulting to public. Protected
constructors of sealed classes use CLR `famandassem`, preserving same-module derived construction
without permitting a foreign C# assembly to extend the hierarchy. Framework C# compilation pins
both the legal public/protected surface and negative private/internal/sealed access. Public
compiler-only methods and runtime helper types carry
`Kotlin.Runtime.Internal.KotlinCompilerAbiAttribute` plus `EditorBrowsable(Never)`; adding that
marker advanced the prototype runtime-surface level to 2. Source-private companion declarations
now stay CLR-private; a post-object-lowering phase based on the common KLIB accessor generator
redirects only illegal enclosing-to-nested accesses through assembly-scoped synthetic bridges.
Constructor bridges use a distinct null-only `SyntheticConstructorMarker`, advancing the runtime
surface floor to 3 without preserving the unshipped level-2 layout. Framework reflection now executes against the
assembled metadata and pins type, field, method, sealed-constructor, compiler-bridge, and runtime
marker attributes. That test exposed and fixed an invalid `[mscorlib]EditorBrowsableAttribute`
MemberRef: mscorlib-profile modules now use an explicit `[System]` reference only when needed,
while portable libraries retain `[netstandard]`. The selected friend model is now implemented at
the compiler boundary: producers emit structured `InternalsVisibleTo` identities and persist them
in schema 4; consumers declare friend KLIB paths; dependency loading verifies producer authority
for the actual unsigned output assembly before FIR grants internal visibility. Authorized and
unauthorized separate-module execution paths are pinned. Ordinary `internal` remains CLR
assembly-internal, while `@PublishedApi internal` types, functions, accessors, and constant fields
are public marked compiler ABI. The source-session wiring also now registers the common FIR enum-
entry service required to resolve declaration annotations on the distinct DotNet platform. The
remaining P0-C integration work is a structured Gradle association that wires both sides. A real
Framework Roslyn compiler/execution lane proves that an authorized C# assembly receives ordinary
CLR friend access; the existing negative C# lane proves that an untrusted assembly does not.
Signed output consumption is deferred but its full-key identity and long custom-attribute
encoding are represented now. A public sealed interface has no
CLR enforcement primitive and therefore still admits foreign CLR implementors; that limitation
must remain explicit in exhaustiveness/interoperability tests rather than being hidden by flags.

### P0-D — Establish the platform profiles and interface premises

1. Implement an explicit `net48`/`netstandard2.0`/`net10.0` profile axis independently of
   executable versus library product kind.
2. Implement the accepted profile-aware default-interface strategy: helper-owned bodies and class
   forwarders for `net48`/`netstandard2.0`, DIM bodies plus the compatible exact-call helper for
   `net10.0`, and explicit bridges wherever Kotlin resolution or a physical interface view requires
   them. Continue the broader variant-interface work for `IEnumerable<T>`/BCL views, foreign
   implementors, barrier behavior, and clash policy.
3. Require both executable-profile platform variants to satisfy the complete portable platform
   ABI; select exactly one runtime/stdlib pair for an application.
4. Do not generalize the current default-argument helper into a permanent DefaultImpls body model
   merely because the helper already exists.

**Exit:** every interface member and superedge has a deliberate placement rule for all three
profiles, and metadata prevents linking a physical asset under the wrong profile.

**Implementation status (2026-07-20):** item 1 and the dependency-selection portion of item 3 are
implemented. The CLI accepts only the three explicit profiles, selects the profile before backend
lowerings, emits profile-specific core/target metadata and runtime/stdlib assets, rejects Standard
executables, and enforces the compatibility matrix before FIR. Repository stdlib production and
installation own all three variants, and a portable pair is executed from both runtime profiles.
The physical declaration-index portion of the binary-superset audit is now mechanical:
`net48` and `net10.0` may add logical entries but must retain every `netstandard2.0` logical key with
the same assembly-independent CLR owner/member binding, identity scheme, physical-name grammar,
and at least the portable runtime-surface floor. Generated variants and negative missing/changed
fixtures execute this comparator. A raw CLR metadata-surface superset audit remains part of the
future structured metadata work; do not replace it with substring comparison over IL. A first
independent PE audit is now mechanical: isolated CoreCLR load contexts compare externally
consumable public/protected types, hierarchy edges, generic constraints, methods, fields,
properties, and events for both runtime/stdlib pairs, while allowing portable abstract slots to
become modern DIMs. It also retains normalized portable custom-attribute identities and payloads
on assemblies and exposed declarations, excluding the deliberately profile-specific target-
framework attribute. Raw attribute-blob encoding, MethodImpl rows, resources, and friend-only
internal surface remain open for the structured metadata model.
The accepted `adr-profile-aware-interface-default-implementations.md` fixes item 2's body
placement, and its non-generic foundation is now implemented. Portable profiles emit abstract
interface slots, helper-owned bodies, and hidden explicit class forwarders. `net10.0` emits real
DIM bodies plus the same exact-call helper; a private final interface MethodImpl bridge maps an
inherited physical slot when CLR metadata requires it, while the public Kotlin DIM remains
overridable. ABI schema 8 records each default's body placement and exact helper identity, the
independent physical owner/method of the masked default-argument dispatcher, derived-interface
DIM promotions as structured `P` records, inherited final generic-interface view adapters as
structured `B` records, and hidden class MethodImpl forwarders as structured `W` records.
Consumers do not derive
`<DefaultImpls>` or `$default` names and do not infer producer
lowering from a target profile. Later lowering and whole-class shape validation traverse `W`
records through arbitrary base-class depth and suppress a class forwarder only when the selected
DIM is physically effective. If an inherited portable class MethodImpl would mask a more-specific
selected DIM under CLR class precedence, the derived class emits one resolver bridge
to the selected helper. Selection otherwise uses the most-specific provider set: zero DIM providers
requires promotion or forwarding, one suppresses redundant emission even through another derived
interface, and multiple incomparable providers require a resolving DIM or class forwarder instead
of exposing CLR ambiguity. A three-assembly integration test covers a `netstandard2.0` producer, a
net10 promotion library, a downstream net10 consumer, indirect single-provider inheritance,
competing promotions resolved in a class and in a derived-interface diamond, the required
unpromoted class forwarder, omitted redundant promoted forwarders, portable-output execution on
CoreCLR, and an indirect portable base-class forwarder combined with a more-specific net10 default.
The last case executes through both interface views and requires exactly one resolver bridge.
A second masking case proves the same result when the hidden helper-backed MethodImpl was emitted
by a net10 intermediate assembly rather than by the original portable producer.
A separately compiled subclass case additionally proves that the same selected helper-backed
MethodImpl is inherited without redundant redeclaration.
Qualified interface-super coverage executes a portable helper call, a net10 consumer of a portable
helper, and a net10 DIM-to-base-DIM exact call. Direct IL assertions pin the latter to a plain
nonvirtual `call` inside the helper and reject `callvirt`, while runtime checks prove ordinary
dispatch still selects the derived DIM.
Common Kotlin rejects omitted arguments in a super call with
`SUPER_CALL_WITH_DEFAULT_PARAMETERS`; qualified-super tests therefore supply every argument, and
the .NET ABI does not add an exact masked dispatcher for malformed IR.
The target now has a dedicated FIR expression-checker set, registered by the metadata session
factory for `DotNetPlatform`; a PSI/LightTree integration regression proves the standard diagnostic
before FIR2IR without pretending that the target is JVM or Native.
Generic interface and generic-method defaults now implement the accepted coordinated split-view ABI.
Every declaration has one canonical semantic body and one stable helper identity. Portable helpers
own the body; `net10.0` exposes one strongly typed DIM, with the exact invariant view as the normal
typed C# surface. Erased and declared-variance views are final MethodImpl adapters that dispatch
virtually to that DIM and box, cast, or widen only for their own ABI. Helpers select the body
nonvirtually; no promotion, view adapter, class bridge, or class forwarder copies it. A concrete
closed interface override owns one complete canonical/declared/exact adapter bundle, and schema-8
`B` records let separately compiled implementors inherit it without duplicate methods. Local boxes
and a four-assembly netstandard2.0/net10.0 integration test execute typed, exact, widened, promoted,
overridden, and helper-backed paths and assert that the Kotlin body occurs once. Runtime/external
generic capability fallbacks bind recorded physical method names rather than inventing Kotlin hash
names. The `net10.0` profile temporarily retains `mscorlib` MemberRefs for the current common
surface; that does not freeze identical Framework/modern IL. The remaining generic/BCL work is
the broader foreign-implementor and clash matrix, generated implementor tooling, and the remaining
raw metadata-table audit; those keep P0-D open. The first foreign barrier slice is now
replayable: ordinary Framework and modern C# classes consume one portable Kotlin library,
implement the canonical plus exact views on one object, preserve Collection's wrong-shape false
barrier, and retain ordinary CLR cast failure for a user `@UnsafeVariance` member. Publication
also now rejects and diagnoses declared-view accessor collisions, exact-view accessor collisions,
and user TypeDefs occupying generated exact-view identities without producing a partial pair.
The wider overload, inherited-slot, and reserved-member collision matrix remains open.

The integration coverage now also proves ordinary class-override precedence and explicit
reabstraction across the portable-producer/net10-promotion boundary. Local runtime coverage covers
property accessor bodies, helper-owned default-argument decoding followed by virtual override
dispatch, nested interfaces, and calls through both the original and reabstracted interface views.
Separate-module runtime coverage now also promotes property getter/setter bodies and invokes
producer-recorded masked dispatchers for omitted and named arguments. The dispatcher calls the
interface slot virtually, so consumer overrides win. An abstract interface method with default
parameters proves the dispatcher record is independent of DIM/default-body metadata; the test
asserts both physical declaration shapes before executing on CoreCLR.
Compiler-owned `<DefaultImpls>` classes and methods are excluded from the logical declaration-key
set: their physical identities are reachable only through records attached to real KLIB members.
The same separate-module test asserts that no synthetic helper name leaks into the logical index
while the marked public compiler ABI remains callable.
The obsolete whole-interface rejection golden has been replaced by an exact portable helper/slot/
forwarder golden that passes both PSI and LightTree. The developer toolchain now provisions a pinned
10.0.100 SDK and discovers its Roslyn compiler plus net10 reference pack independently of
production IL assembly. C# source tests implement both an invariant generic Kotlin interface and
a covariant interface's distinct invariant exact-operation view without declaring methods. They
inherit and execute typed net10 DIMs with `int` results and no erased cast. The same sources fail
with `CS0535` against both portable representations, using modern Roslyn for `netstandard2.0` and
Framework csc for `net48`.

The accepted `adr-companion-static-placement-and-initialization.md` now owns companion placement
and initialization. The implementation preserves upstream KLIB feature flags, emits non-generic
class companion members as true CLR statics with `.cctor`-owned state, and keeps receiver-free
companion extensions on their file facade without a false CLR property row. A separate-module
`netstandard2.0` producer/`net10.0` consumer executes that receiver-free extension ABI. Generic-
class and interface companion-block members move before default/callable lowering to one marked,
non-generic nested `<CompanionStatics>` holder; a split generic interface owns that holder only on
its canonical erased TypeDef. Runtime and IL tests cover constants, computed properties, field-
backed state, method generics, callable references, private enclosing access, and masked defaults.
The physical KLIB record is authoritative for cross-module holder calls and for their static
default dispatchers, and producer variants assemble on all three profiles.

Schema 10 additionally records one exact physical `<EnsureCompanionInitialized>` entry per logical
classifier initialization event and the exact singleton-field owner/name for every Kotlin object.
Its `.cctor` calls producer-recorded superclass and selected
interface entries before executing local state, while generic construction routes every closed
construction through the same non-generic holder. Local runtime coverage pins source ordering,
once-only state, private inheritance, abstract-only interface independence, and generic-global
identity. Mixed companion-block/companion-object initializers now share one source-ordered stream,
and generic-owner companions place their singleton on the same non-generic holder. A separately
compiled `netstandard2.0` producer/`net10.0` consumer executes the graph, an ordinary object, and a
generic companion through recorded physical metadata. Protected members which would acquire
holder-relative CLR `family` access remain rejected pending a deliberate bridge/export design.

The accepted `adr-hybrid-generic-nullability-and-covariant-returns.md` fixes the remaining
nullability representation. ABI schema 11 replaced the unshipped schema-10 signature model:
open `T?` uses one declaration-stable boxed-or-null `object` carrier, while concrete nullable
primitives retain `System.Nullable<T>` and non-null `T` remains reified. Boundary code boxes on
entry and uses `castclass`/`unbox.any` on recovery; `!!` performs the Kotlin null check before
recovering an open `T`. Local execution and netstandard2.0 producers consumed on both runtime
profiles cover primitive/reference values, null, fields, locals, equality, interface views,
forwarding, and recovery. Closure capture remains part of the pre-stable evidence once general
closure construction is available. Covariant-return generation now emits one exact virtual method
plus private final `MethodImpl` adapters uniformly on every profile. Same-module boxes cover class,
property, interface, inherited-interface, abstract, generic-method, nullable-value, erased-
reference, and multilevel dispatch. A separate `netstandard2.0` producer is consumed and executed
by both runtime profiles; Framework and modern C# consumers verify the precise public surface and
private bridge visibility. Import/export projections and pre-stable compatibility-version tests
remain, but the Kotlin-owned physical bridge direction is implemented. ABI schema 12 records each
covariant MethodImpl as a structured `R` entry, so a downstream class inherits an external
interface-owned bridge without rediscovering it or emitting a duplicate class adapter. The
open-nullable check now also precedes the legacy non-null string-bound shortcut: method-level
`T : String` keeps `T` as `string`, but maps `T?` to `object` and casts back only after Kotlin's
`!!` null check. Portable-library consumers execute that ABI on both runtime profiles, and modern
C# consumes the object boundary directly.

### P0-E — Decide the unresolved semantic representations

Specify and implement, in dependency order:

1. the selected classified-CLR exception model;
2. generic-nullability and covariant-return bridge ADR;
3. the selected Kotlin-owned primitive-array wrapper model;
4. callable-reference identity, BigArity, and suspend-marker ADR amendment.

Each decision requires Kotlin semantic boxes, generated-layout assertions, separate-module tests,
and a foreign CLR consumer/provider where the boundary is observable.

**Exit:** `Map`, user exceptions, higher arities, suspend functions, and broader array APIs can be
implemented without revising an already published identity.

### P0-F — Convert evidence into invariants

Commit replayable tests for:

- full supported Kotlin semantic lanes on `net48` and `net10.0`;
- `netstandard2.0` production plus consumption from both runtime profiles;
- Roslyn compilation and execution, including foreign implementors;
- IL verification and assemble-all accepted goldens;
- same- and cross-assembler pairs where retained as an oracle;
- same-generation runtime, stdlib, producer, and consumer profile combinations plus stale-schema
  rejection;
- nullable import/export, reflection visibility, and raw foreign exceptions.

Benchmarks may guide optional capabilities only when their source, environment, and result parser
are committed. Prose reports of a local probe do not establish ABI.

**Implementation status (2026-07-22):** every accepted IL-text module is now assembled as the
appropriate executable or library in both FIR parser pipelines with each available Framework and
modern ILAsm. Strict toolchain runs require both; other hosts keep text comparison while running
whichever assembler is present. The modern pass consumes the unchanged net48 IL as a compatibility
oracle and is not net10 profile evidence. This closes assemble-all accepted goldens and
dual-assembler source acceptance. It does not close modern net10 verification, runtime execution,
or the entire retained same-/cross-assembler runtime-pairing matrix. A committed net48 integration
test now writes the same compiler-produced application and stdlib IL with both assemblers and
executes all four application/stdlib writer pairings on Framework CLR 4 and CoreCLR. The remaining
matrix is runtime-assembly writer substitution and net10-specific pairing evidence.

The current supported Kotlin box corpus now has symmetric execution lanes: all 116 cases run for
`net48` and `net10.0` under both FIR parsers on real Framework CLR 4 and CoreCLR hosts. This closes
the full-profile semantic-lane item for the currently accepted feature set; expanding the common
semantic corpus remains continuous target work rather than a reason to weaken the gate.

The full library-integration class now also pins a portable generic interface default-argument
dispatcher consumed on both runtime profiles. Consumer synthesis retains the recorded instance
receiver and declaring-interface type context, while static companion/top-level dispatchers retain
no false owner context. This closes the previously crashing cross-module generic dispatcher path.

The backend now owns one strict replay entry point,
`:compiler:backend.dotnet:dotNetTest`, rather than allowing the FIR matrix to stand in for the
whole target. It combines 780 FIR/IL/semantic tests, 21 generated CLI tests, and 37
library-integration tests; the current audited result is 838/0/0/0 across 16 JUnit XML suites with
required-toolchain enforcement. The integration child task uses the private short name `dn`
because its name is embedded in temporary paths passed to CLR4 and Framework ILAsm. This closes
the test-entry-point omission, not the remaining evidence items above.

## 4. P1 consolidation

After P0:

- introduce `FirDotNetSessionFactory`, target checkers, actualisation, and a real .NET KLIB kind;
- prototype the CLR importer before freezing the final export/import surface;
- record permanent, transitional, and removable runtime-IL strata and migrate Kotlin-authorable
  runtime/stdlib code out of compiler string literals;
- introduce a structured compiler-owned CIL/metadata model with typed stack/CFG validation;
- define file-facade/clash naming and a source-level rename escape;
- add export null guards or explicitly record a different boundary contract;
- restructure whole-file IL goldens toward declaration-level ABI assertions plus semantic boxes;
- add standard `MODULE:` coverage and a target-owned test module;
- decide packaging, signing, user-library versioning, and whether metadata remains a sibling KLIB
  or becomes a DLL component.

## 5. Explicitly parked work

These may remain unimplemented during foundation correction, but must fail loudly:

- enums and annotation classes/custom attributes;
- value/inline classes;
- `KClass`, class literals, `typeOf`, and broad reflection;
- inline/reified and cross-module inlining;
- coroutine state machines and `Task`/`ValueTask` exports;
- concurrency, volatility, synchronization, and atomics;
- `CharSequence`, `StringBuilder`, and `lateinit`;
- broad stdlib and Gradle/KMP product integration.

Parking a feature does not permit an adjacent ABI to assume its future representation. In
particular, value classes constrain generic-interface placement, and coroutines constrain callable
arity, markers, and exception/cancellation design.

## 6. Release gates

### Gate A — viable internal experimental backend

- P0-A, P0-B, and P0-C complete;
- all three profiles and their interface premises represented explicitly;
- mandatory `net48` and `net10.0` execution lanes plus `netstandard2.0` cross-runtime consumption;
- no document describes current prototype identities as stable ABI 1.

### Gate B — third-party experimental binaries

- all P0 work packages complete;
- KLIB/DLL binding and version-skew diagnostics active;
- CLR importer prototype and committed C# provider/consumer tests;
- exception, generic-nullability, array, and callable freeze decisions accepted;
- public Kotlin ABI, compiler ABI, and C# export surfaces mechanically distinguishable;
- distribution-owned runtime and stdlib artifacts replace per-program bootstrap production.

### Gate C — official experimental target discussion

- dedicated .NET frontend session, KLIB platform identity, Gradle/KMP target, and publication model;
- supported runtime/tier matrix required in CI;
- shared Kotlin semantic corpus and multi-module compatibility coverage at mature-target scale;
- structured diagnostics and structured CIL/metadata validation;
- no unresolved P0 decision and an explicit schedule or exclusion for every parked language area.

## 7. Change-review checklist

Every proposed change answers:

1. Which Kotlin semantic invariant does it preserve?
2. What CLR constraint requires target-specific treatment?
3. Which layer owns it: common compiler, backend, runtime, stdlib, importer, or exporter?
4. Does it change logical identity, physical name, public metadata, runtime surface, or required
   capability?
5. Before freeze, how are stale schemas and wrong-profile artifacts rejected? After freeze, what
   happens for old/new compiler, producer, consumer, runtime, and stdlib combinations?
6. Can C# call, implement, reflect, or pass a foreign value through it without redefining Kotlin
   semantics?
7. Does unsupported input fail at a source location, or can it shrink an artifact?
8. Which committed semantic, layout, foreign-language, and skew tests prove the claim?
9. Is a temporary mechanism externally bindable, and what removes it?
10. Which ADR owns the decision?
