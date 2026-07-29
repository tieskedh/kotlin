# Kotlin/.NET backend way forward

> **Baseline:** branch `dotnet`, rebased on `origin/master` at `6fb64e0c0`, upstream impact
> re-audited on 2026-07-28
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
in schema 4; consumers now declare friend DLL paths whose embedded metadata supplies the same
logical library; dependency loading verifies producer authority
for the actual unsigned output assembly before FIR grants internal visibility. Authorized and
unauthorized separate-module execution paths are pinned. Ordinary `internal` remains CLR
assembly-internal, while `@PublishedApi internal` types, functions, accessors, and constant fields
are public marked compiler ABI. The source-session wiring also now registers the common FIR enum-
entry service required to resolve declaration annotations on the distinct DotNet platform. The
built-in Gradle target now closes the remaining P0-C association work: ordinary `associateWith`
wiring supplies the producer's exact self-describing DLL to the consumer as both dependency and
friend input,
authorizes the consumer's compilation-owned CLR identity in the producer, inherits the
producer's declared dependencies, and infers producer-before-consumer task ordering. A real
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
framework attribute. The verifier now also reads raw ManifestResource rows: a portable public
resource must remain present, public, and embedded, while the C# authoring manifest retains its
schema, assembly identity, logical-identity scheme, and portable logical declarations across
profile-specific physical slot records. A copied platform PE with one rewritten logical
declaration and a recomputed envelope digest is rejected as a negative oracle. Assemblies which
declare `InternalsVisibleTo` now contribute a second, non-duplicated surface containing internal
types and their non-private members plus friend-dependent members of exposed types. Both
executable variants must retain that portable surface, and a narrowed modern fixture is rejected.
Private declarations remain implementation details. Ordinary custom attributes are compared as
decoded semantic multisets, including multiplicity; semantically equivalent raw encodings are
deliberately not a cross-profile ABI. Exact bytes remain bounded to compiler protocols which
explicitly document that representation.
Manifest-addressable MethodImpl obligations are now compared semantically rather than by raw row:
the verifier resolves complete producer-recorded locators, keys each public/protected concrete
interface-map obligation by Kotlin logical identity, physical view, and constructed CLR signature,
and accepts an explicit mapping, natural implementation, selected DIM, or recorded promotion.
Non-public implementation maps are validated within each variant without making their generated
type names ABI. A generic default fixture proves portable class forwarders equivalent to a modern
typed DIM plus erased interface adapter for ordinary, method-generic, and mutable-property
members. It also caught and corrected modern DIM accessors which lost CLR `specialname`; the
portable and modern Property rows now retain the same accessor shape. A corrupted same-length slot
name proves that a name-only locator is rejected. Non-manifest public/compiler-ABI and authorized
friend obligations now form a second physical floor keyed by the real implementing CLR type and
complete constructed interface signature. The effective target is excluded, preserving the
portable-forwarder-to-modern-DIM transition without synthetic tooling identities. A
metadata-public generic fixture remains absent from the C# authoring manifest but participates in
this audit; a reassembled modern PE missing its canonical `.override` is rejected. The C# source-authoring
slice now independently pins its exact `InternalsVisibleTo` blob, friend TypeDef visibility, and
promotion MethodImpl signatures through a metadata-only reader. It also pins the public
compiler-ABI marker and `EditorBrowsable(Never)` blobs while proving ordinary internal source API
is unmarked.
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
`__KotlinDefaultImpls` or `$default` names and do not infer producer
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
the remaining foreign-implementor and clash matrix, the Roslyn generator/analyzer, and the raw
metadata-table audit; those keep P0-D open. The accepted C# source-authoring ADR now selects a
partial-type Roslyn generator/analyzer rather than a generated base class or universal CLR
mechanism. Its schema-7 DLL manifest is implemented: it records the canonical
`PublicIdSignatureComputer(DotNetIrMangler)` identity scheme and rejects parallel runtime/tooling
declaration keys. It also records profile, canonical, declared, and exact owners where those split
views exist, member and property groupings, strongly typed authoring views, MethodDef locators,
helper/DIM obligations, and logical contributor mappings for derived
intersection slots without parsing the private Kotlin metadata resource. A DLL-only
integration test reads the actual assembly metadata, generates a partial C# implementation for a
property, generic method, exact-only unsafe input, and default, then executes Kotlin verification
for `net48`, `netstandard2.0`, and `net10.0`. Ordinary non-generic interfaces use one canonical
owner and canonical member locators rather than artificial split or erased views. The same fixture
maps PascalCase C# properties and methods to Kotlin physical names and covers ordinary inheritance,
mutable properties, helpers, DIMs, and portable-parent promotion. The carrier is now the embedded
public managed resource `Kotlin.CSharpImplementationManifest`: both selected ILAsm implementations
were proven to copy a same-directory resource source into the PE, and the compiler stages that
source only for assembly. A versioned, length-bounded, SHA-256-checked envelope protects the
unchanged record payload. Production Roslyn tooling reads the referenced PE directly without
loading target code, while the DLL-only matrix proves that no sibling KLIB or resource sidecar is
needed. The pre-publication `AssemblyMetadataAttribute` chunks were removed rather than retained
as a compatibility surface. The first foreign barrier slice is now replayable: ordinary Framework
and modern C#
classes consume one portable Kotlin library,
implement the canonical plus exact views on one object, preserve Collection's wrong-shape false
barrier, and retain ordinary CLR cast failure for a user `@UnsafeVariance` member. The same lane
now covers a source-named typed property, a generic method, an exact-only input, and explicit
canonical property/method implementations through both typed and widened Kotlin views. Publication
also now rejects and diagnoses declared-view accessor collisions, exact-view accessor collisions,
erased callable overload collisions, and user TypeDefs occupying generated exact-view identities
without producing a partial pair. A producer-derived reserved-name lookalike is also proven safe:
typed/class dispatch reaches the source member while canonical dispatch remains bound to its
private explicit `MethodImpl`. The wider inherited-slot and real same-owner collision matrix
remains open. A separate nullable-reference overload fixture closes the return-only item:
Kotlin-distinct `String`/`String?` parameters both map to CLR `string`, while `T` and `Any?` leave
different physical results, so publication rejects the pair atomically. Same-named overloads from
two parent interfaces are now also rejected when their distinct Kotlin callable types both erase
to CLR `Function1` and no Kotlin-selected intersection slot covers them. The same gate now follows
consumer-owned intermediate interfaces into a separately compiled producer and refuses the
consumer DLL, closing the direct/transitive cross-module direction.
A valid consumer-substitution direction is now distinct from that rejection. A generic Kotlin
child inherits `select(T)` and `select(String)` without a producer-side intersection; when a C#
partial closes `T = String`, the production generator binds one `Select(string)` source body to
both retained parent contracts. DLL-only Kotlin execution through both parent views succeeds on
all four profile lanes, including a modern child over a portable parent. The closed child call
remains Kotlin-frontend ambiguous, so this coalesces only the implementation obligation and does
not merge logical declaration identity.
The first real same-owner generated-TypeDef case is also closed. A source nested
`__KotlinDefaultImpls` inside a default-bearing interface has exactly the owner/name/arity of the
compiler compatibility helper. Publication rejects that producer atomically on all three target
profiles, including `net10.0` where the helper remains compiler ABI, and emits neither artifact.
This is distinct from a reserved-looking member on another physical owner, which remains legal.
Generated masked-dispatcher method identities are now closed as a separate family. Source
backtick functions colliding with an ordinary member `$default`, a file-facade `$default`, or a
data-class `copy$default` all fail publication on every profile. The compiler retains neither an
arbitrary winner nor a partial library, matching JVM's `CONFLICTING_JVM_DECLARATIONS` precedent
while preserving Kotlin's callee-owned default evaluation.
A separate 65-parameter portable producer now crosses both common fixed-mask boundaries. Kotlin
and C# consumers on net48 and net10 verify declared/exact variance metadata, implementation of the
complete exact capability, same-object widening, high-index canonical fallback, and wrong-shape
cast failure. This is evidence for the existing positional representation, not a new ABI rule.
The fixture also completes the one-through-four parameter matrix with a mixed
`in`/`out`/invariant/`out` interface spanning reference, primitive, nullable, exact-only, and open
generic positions. Its nullable result is concretely `Int?`, pinning `Nullable<int>` on the exact
capability and boxed canonical fallback after widening to `Any?`. Both consumers preserve identity.
A raw CLR provider additionally implements only a portable Kotlin interface's canonical identity
and producer-recorded erased slot. The separately compiled portable reader executes it without any
declared/exact capability on both application profiles, pinning capability-absent fallback.

The post-rebase collision continuation now treats a Kotlin override intersection as producer-
recorded physical ABI rather than relying on ambiguous CLR inheritance. Schema 14 records one
source-named declared or exact slot and its complete contributing logical-member group. Local and
separately compiled implementations map that slot and both parent families to one Kotlin body.
The runtime/C# matrix covers ordinary parameters, generic methods, constraints, read-only and
mutable properties, exact-only inputs, parent-parameter permutations, indirect branches,
covariant refinement, and direct owner-relative `<R : T>` parameters/results. Owner-relative
constraints remain logical in KLIB and erased on executable split-interface views; a concrete
physically constrained implementation is invoked at substituted `T` through object casts, while
an already-erased default forwarder retains the actual `R` required by Kotlin variance widening.
Nested uses such as `Box<R>` fail publication. Candidate discovery precedes shape filtering, so an
unsupported real intersection cannot silently publish an ambiguous C# surface.

The signature-changing continuation now admits nonidentical covariant parent returns when Kotlin
selects one resolved return and a contributor has that exact result. Its existing typed bridge
receives the derived `MethodImpl`; wider parent bridges continue adapting the same source body.
Kotlin and C# producer/consumer execution preserve one refined object through all three views on
both runtime profiles without changing schema 14.

Selected portable generic defaults are also complete under the existing profile-aware promotion
ABI. Two incomparable net10 interfaces may each promote the same helper-owned portable default;
their derived generic diamond emits a new canonical/declared/exact resolver bundle targeting that
same original helper identity. Kotlin and direct C# execution cover portable root, both branches,
derived, exact, method-generic, and widened views without a class forwarder or body copy. FIR
requires an explicit override for unrelated default declarations, so that source shape follows the
ordinary declared-body path rather than requiring another implicit intersection representation.

Split mutable-property intersections now reuse the surface already selected for ordinary variant
properties. The declared-variance view emits only the accessor legal under its CLR variance; the
invariant exact view repeats that safe accessor and emits the complete property. For the common
`out T` shape this is a getter-only declared property plus a read/write exact property. Schema 14
records the declared getter, exact getter, and exact setter independently, while KLIB retains their
one logical property association. Kotlin and C# producer/consumer execution on both profiles proves
that all adapters reach the same accessor bodies.

The remaining P0-D implementation order is:

1. Keep nested/general owner-relative constraint adapters deferred until a sound reified-carrier
   conversion exists; whole-declaration rejection is correct in the meantime.
2. Finish producer-declared nested foreign member signatures, incompatible substituted inherited
   overload families, and the remaining same-owner generated-member clash matrix. The generated
   helper-TypeDef and member/facade/data-class `$default` cases are now pinned across every
   profile; method/accessor/constructor and singleton-field collisions already have emitter gates,
   but remaining compiler-generated member-name families still need adversarial publication
   coverage. The valid closed inherited family
   where `select(T)` and `select(String)` converge at `T = String` is now production-pinned: one
   C# body serves both distinct parent contracts without an invented manifest intersection.
   Consumer-owned base-list substitution
   is now recursively validated: nested named constructions, nullable value types, type
   parameters, and CLR SZARRAY vectors are preserved structurally, while nested `dynamic`,
   pointers/function pointers, unresolved/unbound types, and rectangular/non-vector arrays fail
   with `KDNCS004`. A production `List<Nullable<int>[]>` implementation executes through the
   canonical erased adapter without losing list or vector identity. The DLL manifest now covers inherited
   mutable-property obligations and friend-accessible internal interfaces. The first production
   `netstandard2.0` Roslyn analyzer/generator slice is now implemented: the real canonical or
   declared C# base list is the only opt-in, its bounded reader consumes the authoritative DLL
   manifest, and diagnostics cover missing `partial`, unavailable friendship, explicit ABI-member
   conflicts, unsupported substitutions, malformed manifests, and schema/tool mismatch. It emits
   each diagnostic once: the analyzer owns semantically valid types, while the generator owns
   diagnostics suppressed by a blocking C# error in that same type and skips emission. The
   generator emits the additional partial declaration and is exercised against DLL-only
   references. Nested
   reference-class and record-class implementors reconstruct a partial containing-type chain;
   non-partial containers receive `KDNCS011`, while file-local types remain explicitly
   unsupported. Value-type implementors are an accepted deferral behind `KDNCS010`, not a
   reference-class generator backlog item: portable helper boxing and modern DIM/constrained
   dispatch need one Kotlin contract for identity, copies, mutation, and default dispatch first.
   No implicit wrapper is permitted, and the source-authoring ADR records the cross-profile exit
   matrix. Nested friend interfaces also execute through production
   generation: structured owner paths use CLR `+` lookup, and accessibility walks the complete
   public/internal chain with producer friendship. A nested covariant generic friend additionally
   executes its canonical and typed view adapters from one C# property body. Ordinary
   non-generic method/property adapters now execute public and authorized-internal Kotlin
   verification on every profile, including portable helper forwarding and modern/promotion DIM
   suppression. A derived Kotlin reabstraction is also production-pinned: it defeats either
   inherited default representation, maps every inherited/redeclared slot to one C# body, and
   reports `KDNCS008` when that body is absent. Covariant generic reabstraction likewise authors
   the declared typed member and routes canonical and inherited views to it without selecting the
   older helper or DIM. Schema 7 adds the missing logical override graph: a Kotlin-resolved
   competing-default child redirects all portable parent slots to its selected helper, while
   modern lanes inherit the selected DIM. All child/left/right runtime views agree; unrelated C#
   roots without a Kotlin resolver require a C# body rather than an inferred preference. Consumed
   edges are checked against CLR ancestry and resolved signature compatibility; a tampered edge
   fails once with `KDNCS006`. Covariant generic conflict coverage now proves that child, both
   typed parents, and a widened parent all reach the selected child helper/DIM without an
   exact-result erased cast or rejected-parent helper.
   MethodDef locators are matched against complete open return and parameter signatures before
   generic-owner substitution; same-named overloads bind independently and stale parameter or
   result signatures fail closed. Standard CLR core-facade forwarding is normalized for
   `System.*` signatures, including nullable overloads, without weakening external user-assembly
   identity. Split generic views and generic methods now also execute through
   production code:
   the declared base-list view gains the exact constructed interface, while canonical adapters
   alone perform erased casts/boxing and generic constraints remain CLR-authoritative.
   Owner-relative guidance stays diagnostic-only. Manifest-recorded special barriers are now
   production-generated before erased casts, with all `false`, `null`, `-1`, and argument
   fallbacks executed while policy-free unsafe members retain cast failure. Production
   intersections now consume the logical contributor mapping that CLR metadata lacks: parent
   canonical and derived typed slots converge on one method/property body, and split mutable
   accessors are grouped by their CLR Property row. Method, mutable, and owner-relative generic
   intersections execute through the full derived/parent view matrix. Multi-parent generic
   diamonds continue to compose from parent manifest contracts plus the CLR graph.
   Representable method constraints are read from CLR GenericParam metadata and now drive the
   no-KLIB C# `where` clauses. Schema 7 supplies normalized positional analyzer guidance for
   owner-relative constraints erased from illegal variant positions; tooling must explain them,
   not reconstruct CLR signatures.
   Special barriers are now explicit schema policy selected from shared Kotlin declaration
   identities: the manifest records checked argument count and fallback, while ordinary unsafe
   members retain cast failure. `Kotlin.Runtime.dll` now owns built-in-derived contracts for the
   five collection execution interfaces, and C# implementations execute `contains`/`indexOf`
   through exact and widened Kotlin views with the recorded barriers. Cross-profile portable-helper
   promotion needs no manifest record: the parent locators plus the child DLL's
   concrete `MethodImpl` bundle are sufficient and are now metadata-only execution tested. The
   reader matches complete return and parameter signatures and rejects a doctored return, rather
   than treating owner/name/arity/count as a declaration identity. Do not
   add a blanket ban for source names that merely resemble canonical names; physical-owner
   separation and explicit `MethodImpl` already preserve their semantics. Do not make a generated
   base class the only path because C# has single class inheritance. An executed multi-root
   implementor now retains its unrelated C# base state while generated adapters satisfy ordinary
   and generic Kotlin contracts. The authoring-specific raw
   MethodImpl signature, `InternalsVisibleTo` blob, and friend TypeDef rows are now audited. The
   structured portable-superset verifier also closes the managed-resource row, C# manifest
   logical-contract comparison, manifest-addressable semantic MethodImpl obligations, and
   friend-dependent internal surface without requiring byte-identical profile payloads. It now
   also closes non-manifest public/compiler-ABI and authorized friend slot satisfaction through
   effective CLR interface maps, with a hostile reassembled PE rather than an IL substring
   oracle. General attribute-blob equality is intentionally not required:
   ordinary attributes retain decoded identity, arguments, and multiplicity, while only
   explicitly documented compiler protocols freeze raw bytes.

There is no remaining implicit-intersection representation or production-adapter migration on the
critical path. The next backend implementation item is the foreign boundary and structured
metadata audit.
Nested/general owner-relative carriers may remain rejected until their conversion can be proved
rather than guessed.

P0-C Gradle friend association is now implemented through the built-in experimental Kotlin/.NET
target and compilation model. Common metadata and Gradle module metadata recognize one distinct
Kotlin/.NET platform identity. The typed target-framework attribute models exact-profile
selection plus the two legal `netstandard2.0` fallback edges. The generated Gradle
compiler-options surface remains common Kotlin options plus `moduleName`; operational inputs and
raw export encodings remain deliberately excluded. The target-specific compilation associator
derives producer `InternalsVisibleTo`, the consumer's exact bound friend DLL, dependency
inheritance, and task ordering from the ordinary `associateWith` relationship. Model tests cover
all three profiles, and the real-compiler integration lane compiles a test module that calls an
ordinary internal declaration from its associated main module.

The target's API and runtime variants now publish the profile-attributed self-describing DLL as
their only artifact. The DLL is a declared task output, retaining normal Gradle producer ordering
and configuration-cache behavior. The association lane and a separate producer/consumer project
lane both compile from the producer DLL while excluding its task, proving that Gradle dependency
and friend wiring rely on the declared self-describing artifact.

The compiler now retains that DLL identity through frontend loading as well. The target-owned
ECMA-335 reader locates and authenticates the private resource; a shared packed-metadata KLIB
loader validates canonical entries and bounded expansion and creates the ordinary
`KotlinLibrary`/`KlibMetadataComponent` view in memory. `KotlinLibrary.path`, dependency module
data, friend paths, and diagnostics therefore name the DLL directly, with no temporary KLIB or
container-specific metadata model in dependency loading. Common unit coverage pins archive
validation and component behavior; DLL integration coverage pins malformed-resource diagnostics
and real cross-module use.

Installed Kotlin/.NET platform libraries now follow the distribution lifecycle used by the
mature targets instead of treating runtime support as an application build product. One explicit
producer invocation emits a profile-bound `Kotlin.Runtime.dll`/`Kotlin.Stdlib.dll` pair for each
of `net48`, `netstandard2.0`, and `net10.0`; installation keeps each pair in one profile
directory. The stdlib authenticates itself through its embedded KLIB. The runtime has no Kotlin
declaration KLIB, so the compiler binds its physical Assembly row to the target profile in the
already-required public C# authoring manifest, without loading target code. Exact profiles may
select themselves or the portable pair according to the established compatibility graph. A
partial or profile-mixed pair is diagnosed, and executable packaging copies both selected DLLs
byte-for-byte. Regenerating a runtime remains only as bootstrap compatibility for a manually
supplied standalone stdlib DLL and is not the supported installed path.

The integration coverage now also proves ordinary class-override precedence and explicit
reabstraction across the portable-producer/net10-promotion boundary. Local runtime coverage covers
property accessor bodies, helper-owned default-argument decoding followed by virtual override
dispatch, nested interfaces, and calls through both the original and reabstracted interface views.
Separate-module runtime coverage now also promotes property getter/setter bodies and invokes
producer-recorded masked dispatchers for omitted and named arguments. The dispatcher calls the
interface slot virtually, so consumer overrides win. An abstract interface method with default
parameters proves the dispatcher record is independent of DIM/default-body metadata; the test
asserts both physical declaration shapes before executing on CoreCLR.
Compiler-owned `__KotlinDefaultImpls` classes and methods are excluded from the logical declaration-key
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

Schema 15 records one exact physical `<EnsureInitialized>` entry per logical classifier
initialization event and the exact singleton-field owner/name for every Kotlin object. Its
`.cctor` calls producer-recorded superclass and selected interface entries before executing local
state, while generic construction routes every closed construction through one non-generic
holder. Existing companion state uses `<CompanionStatics>`; an interface or generic classifier
which needs only an event uses the distinct `<StaticInitialization>` holder. Local runtime
coverage pins source ordering, once-only state, private inheritance, abstract-only interface
independence, generic-global identity, and failed initialization. Mixed companion-block/
companion-object initializers share one source-ordered stream, and generic-owner companions place
their singleton on the same non-generic holder. A separately compiled `netstandard2.0` producer is
consumed by both runtime profiles through recorded physical metadata. Protected members which
would acquire holder-relative CLR `family` access remain rejected pending a deliberate
bridge/export design.

The accepted `adr-kotlin-static-initialization-failures.md` closes the Kotlin-owned failure item.
Compiler-generated class and file `.cctor` methods catch the original `System.Exception`, publish
private failure state, and complete so CLR `TypeInitializationException` never becomes Kotlin
behavior. The logical barrier atomically rethrows the first Kotlin `Error` object, otherwise
constructs `ExceptionInInitializerError(cause)`, and constructs `NoClassDefFoundError` on later
use. Constructors, Kotlin static functions/accessors, top-level functions/accessors, singleton
loads, generated static machinery, dependency edges, and cross-module singleton reads re-enter
the barrier. Foreign CLR initializers remain untouched. Runtime surface level 8 and schema 15
reject the incompatible unpublished predecessor.

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
remain, but the Kotlin-owned physical bridge direction is implemented. ABI schema 13 retains each
covariant MethodImpl as a structured `R` entry (introduced in schema 12), so a downstream class
inherits an external interface-owned bridge without rediscovering it or emitting a duplicate class adapter. The
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
- Kotlin-owned initializer failures: original `Error` identity, first non-`Error`
  `ExceptionInInitializerError`, later `NoClassDefFoundError`, inherited poisoning, top-level
  files, generic closed constructions, and cross-module access. CLR
  `TypeInitializationException` must not be the final Kotlin observation.

Benchmarks may guide optional capabilities only when their source, environment, and result parser
are committed. Prose reports of a local probe do not establish ABI.

**Implementation status (2026-07-22):** every accepted IL-text module is now assembled as the
appropriate executable or library in both FIR parser pipelines with each available Framework and
modern ILAsm. Strict toolchain runs require both; other hosts keep text comparison while running
whichever assembler is present. The modern pass consumes the unchanged net48 IL as a compatibility
oracle and is not net10 profile evidence. This closes assemble-all accepted goldens and
dual-assembler source acceptance. It does not close modern net10 verification, runtime execution,
or the entire retained same-/cross-assembler runtime-pairing matrix. A committed net48 integration
test now writes the same compiler-produced application, stdlib, and runtime IL with both assemblers
and executes all eight artifact-writer combinations on Framework CLR 4 and CoreCLR, for 16
executions total. This closes the retained net48 writer-substitution matrix. Net10 deliberately has
no cross-writer matrix: a committed boundary test proves that Framework ILAsm rejects a real DIM
body while the profile-selected modern writer executes the same program on CoreCLR. Treating the
old assembler as a net10 requirement would erase the accepted profile capability distinction.
Failed assembler attempts remove partial PE/runtimeconfig output. The retained pairing evidence is
therefore closed by a complete net48 matrix and an explicit net10 capability boundary.

The current supported Kotlin box corpus now has symmetric execution lanes: all 117 cases run for
`net48` and `net10.0` under both FIR parsers on real Framework CLR 4 and CoreCLR hosts. This closes
the full-profile semantic-lane item for the currently accepted feature set; expanding the common
semantic corpus remains continuous target work rather than a reason to weaken the gate.

**Implementation status (2026-07-28):** Kotlin-owned initializer-failure coverage now executes
companions, inherited class events, a generic derived class across distinct closed
constructions, ordinary objects, exact `Error` identity, and file-facade failures on both runtime
profiles and both FIR parsers. A portable producer is consumed separately from `net48` and
`net10.0`; the test asserts producer-recorded barriers and their ordering before external
singleton-field reads. The remaining C# raw-singleton-field bypass belongs to the deliberate
export-facade work before ABI stability and is not a Kotlin semantic gap.

The full library-integration class now also pins a portable generic interface default-argument
dispatcher consumed on both runtime profiles. Consumer synthesis retains the recorded instance
receiver and declaring-interface type context, while static companion/top-level dispatchers retain
no false owner context. This closes the previously crashing cross-module generic dispatcher path.

The backend now owns one strict replay entry point,
`:compiler:backend.dotnet:dotNetTest`, rather than allowing the FIR matrix to stand in for the
whole target. It combines 780 FIR/IL/semantic tests, 21 generated CLI tests, and 49
library-integration tests; the current audited result is 850/0/0/0 across 16 JUnit XML suites with
required-toolchain enforcement. The integration child task uses the private short name `dn`
because its name is embedded in temporary paths passed to CLR4 and Framework ILAsm. This closes
the test-entry-point omission, not the remaining evidence items above.

Private generated-field collisions now follow the mature JVM rule rather than rejecting valid
Kotlin. A late class lowering reserves public/protected CLR and compiler-ABI field names, then
suffixes later private storage through the existing `IrField` symbols. The rule also removes
type-distinguished duplicate private names which CLR metadata permits but C# cannot naturally
author. IL and executable semantic coverage pin both an object property named `INSTANCE` and an
inner property named ``this$0`` while independently exercising the singleton and outer-receiver
fields. The emitter retains its physical field-identity gate for unrenamable exposed collisions.

The raw foreign-exception lane now validates catch/return and catch/rethrow from C# through Kotlin
on both runtime profiles. The same CLR object retains its exact type, message, `InnerException`,
`Data`, and an observable CLR stack trace; Kotlin source `throw e` exposes the new Kotlin throw site
without wrapping the object. Nullable import/export remains open, but raw foreign-exception state
and identity are now replayable P0-F evidence.

A separately compiled `netstandard2.0` library now also performs the catches: net48 and net10
applications supply an exact subclass declared in the consumer, a mapped runtime fault, a broad
Exception, and an Error, and the portable library selects the same Kotlin categories on both
runtimes. This closes the portable-library/application-supplied direction of the exception matrix.

The same cross-language lane invokes the runtime classifier directly for representative null,
foreign, mapped, exact Kotlin, and fatal objects, covering every assigned id and hostile negative/
overflow ids on both runtime variants. Every invocation returns `bool` without throwing. This
closes the exception-filter totality requirement and pins unknown ids to a safe false result.

The portable exception ABI lane now publishes direct and nested-generic overloads whose logical
`Throwable`, `Exception`, `RuntimeException`, and `Error` parameters all share the physical
`System.Exception` carrier. Physical-name grammar version 2 derives a stable suffix from the
owner-independent Kotlin signature before a collision is observed. Separate net48 and net10
consumers execute top-level calls, class overrides, ordinary interface dispatch, and split generic-
interface dispatch against the recorded producer names; a substituted generic-base override also
retains its original slot, and an explicit `MethodImpl` maps that body to a differently named
non-generic interface slot. This closes method-overload
disambiguation; exception returns/properties, constructor collisions, narrow export admission, and
remaining generic boundary shapes stay open.

Physical-name grammar version 3 retains that exception-signature rule and additionally gives
interface-default property helpers a C#-expressible
`get_/set_...__KotlinDefault__<logical-identity-digest>` name. The public Property row and ordinary
CLR accessors do not change. The producer records the helper locator; neither the compiler
consumer nor Roslyn tooling derives it. A four-lane DLL-only conflict test selects one Kotlin
default getter. Portable parent adapters call only the selected child helper; modern parent
Property adapters dispatch virtually through the selected child DIM because Roslyn's C# base-list
validation does not treat the interface-owned getter MethodImpl as satisfying those source-level
parent Property obligations.

A mutable conflict lane additionally selects the left qualified-super getter and the right
qualified-super setter. The manifest and generator preserve them as independent Kotlin
declarations even though both physical accessors belong to one CLR Property row. Reads and writes
through child and parent views execute those distinct selections across all profile combinations;
portable C# uses only the matching child helpers and modern C# dispatches through the child DIMs.

A covariant generic property conflict now covers the split physical views too. The declared typed
interface owns the DIM; its erased canonical slot is abstract and mapped by an interface-owned
MethodImpl. CLR dispatch accepts that representation, while Roslyn still requires an explicit
canonical Property on the C# class. The generator emits that physical adapter and forwards
virtually through the typed DIM. Portable adapters use the selected generic child helper, and
declared/exact results never detour through an erased cast.

A covariant mutable default now pins the complementary setter path. The declared getter and exact
setter own their typed DIM bodies; secondary typed DIM adapters are inherited rather than
re-emitted on the class. Only the abstract erased Property and selected inherited slots receive
class adapters. Portable erased setters convert `object` to the selected helper's constructed
parameter, while modern erased setters dispatch to the exact DIM after the same boundary cast.
Wrong-shaped `@UnsafeVariance` writes retain ordinary cast failure and do not reach either parent
body.

A second portable exception-signature lane now returns each broad logical category, stores and
mutates all four through public properties, and returns a nested generic runtime-exception value.
Separate net48 and net10 consumers preserve classifier results and exact object identity across the
producer DLL boundary; the IL assertion also pins four `System.Exception` property carriers
and four grammar-v2 setter names. This closes Kotlin-producer return/property coverage without
claiming the still-undecided narrow or foreign C# admission policy.

The portable signature matrix also overloads on `Array<RuntimeException>` versus `Array<Error>`
and on function types accepting those categories. Grammar-v2 names remain distinct after nested
carrier mapping, while separate net48 and net10 consumers select the correct overload and return
the exact callback argument object. This closes Kotlin-owned array/function-type placement; it
does not decide foreign callback admission or array projection guards.

Cancellation now uses the forcing-case design implied by the selected classifier model.
`CancellationException` is the exact CLR `OperationCanceledException` identity, including foreign
`TaskCanceledException` children. Its Kotlin `IllegalStateException` parent uses the shared
`System.Exception` carrier while ordinary construction remains `InvalidOperationException`, so
both sibling CLR roots classify under one truthful Kotlin edge without wrapping. A portable
producer plus Kotlin and C# consumers execute owned/foreign construction, exact catches, parent
returns, classification, message/cause identity, and Kotlin subclass ancestry on net48 and net10.
Classifier id 14, runtime surface level 7, and physical ABI schema 13 reject the incompatible
unpublished predecessor. The common cause-only factory remains in the mapped-constructor/factory
ABI work before Gate B.

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
- decide signing and user-library/assembly versioning before publication.

**Source-product progress (2026-07-28):** the first P1 migration slice moves the complete current
Kotlin/.NET bootstrap stdlib implementation from compiler string literals to ordinary
`libraries/stdlib/dotnet/src` Kotlin files. This follows Common/JVM/JS/Wasm and Native in making
library sources the product input. The actual CLR difference is the need to build three physical
profile variants and to break the compiler/stdlib installation cycle; therefore the backend JAR
temporarily packages the same files as a read-only fallback. The logical declarations and bodies
remain identical across profiles, so Kotlin Common semantics do not change; only later target
lowerings may select profile-legal CLR representations. Repository Gradle tasks compile the
ordinary files directly and reject incomplete or unrelated source sets. Direct-source and fallback
products are byte-identical in packed metadata, IL, and DLL output. Private/file-local
`IdSignature`s are now excluded from the cross-module physical declaration index, preventing
checkout paths and non-bindable implementation details from becoming ABI. The fallback remains
transitional; generated Common sources plus narrow .NET actuals and a fully installed platform
pair remain the core-team endpoint.

**CLR-importer progress (2026-07-28):** the first importer slice follows the JVM foreign
classfile/provider split without pretending that CLR metadata is Java metadata. A single bounded,
JVM-hosted PE/ECMA-335 engine now serves both embedded-resource loading and an immutable physical
CLR model. It exposes assembly identity and references, type references and definitions,
TypeDefOrRef base handles, nested ownership, visibility, and raw type flags. The actual CLR
difference is that properties, return types, TypeSpec signatures, declaration-site variance,
constraints, custom modifiers, and nullable-reference attributes live in a richer metadata graph;
those remain physical importer inputs rather than C# naming conventions or backend guesses.
Kotlin-produced DLLs remain KLIB-authoritative, and no CLR row is mapped directly to IR. Common
semantics are unchanged, while each compilation will eventually resolve only the selected
`net48`, `netstandard2.0`, or `net10.0` reference graph. One direct IL fixture assembled by both
Framework and modern ILAsm proves assembly scope, inheritance, nesting, and flags. Lossless
signatures, methods, properties, generic constraints, semantic attributes, import policy, and the
lazy FIR provider remain subsequent slices. See `../decisions/draft-adr-clr-importer-boundary.md`.

**CLR-signature progress (2026-07-28):** the physical model now owns a lossless ECMA-335 signature
algebra instead of reusing the backend's output-oriented IL types or recording C# display strings.
TypeSpec and MethodDef rows retain structural class/value kind, physical handles, generic
positions/instantiations, legal pointer and by-reference forms, SZARRAY versus ranked arrays with
sizes and signed lower bounds, custom modifiers, function-pointer convention, `this` flags,
generic arity, and raw bytes for diagnostics. Method ownership is recovered from the TypeDef
MethodList partition. The decoder applies ECMA-335 plus the official .NET augmentations: it accepts
custom modifiers in the extra CLR-supported positions and retains modifier TypeSpec handles
without resolving them, while restricting named/generic constructors to TypeDef/TypeRef and
requiring the later resolver to detect cycles. It rejects illegal by-ref/typed-ref nesting,
call-site sentinels in MethodDef signatures, non-canonical compressed integers, excessive
nesting/counts, truncation, and trailing bytes. Framework and modern ILAsm independently produce
the tested fixture; a corrupted copy is rejected. The scale lane also walks real Framework
mscorlib and net10 System.Runtime metadata, including a modern modified-TypeSpec root. This
remains profile-neutral physical input and creates no Kotlin type or FIR symbol. Property grouping,
generic constraints, semantic attributes/nullability, and import policy are the next boundary
layers.

**CLR-property-metadata progress (2026-07-28):** the next physical-importer slice decodes
Property, PropertyMap, and MethodSemantics as first-class CLR metadata instead of reconstructing a
property from accessor names. This matches the mature-target separation: the foreign binary model
is preserved first and Kotlin-facing convenience is synthesized later. The actual CLR difference
is that a Property row already groups accessor MethodDefs and carries its own signature, including
indexed and, under the official .NET augmentation, by-reference forms. The physical model
therefore retains the property token, declaring TypeDef, exact metadata name, flags, signature,
raw bytes, and each getter/setter/other association. It enforces CTS ownership and semantics-kind
rules but does not turn optional CLS/C# naming conventions into Kotlin validity rules. A
dual-profile IL fixture deliberately binds unusually named accessors and both indexed instance
and static properties; corrupted signature input fails closed. Real Framework `mscorlib` and
.NET 10 `System.Runtime` rows are also decoded. No Kotlin property or FIR symbol is created yet.
GenericParam/constraints are implemented by the continuation below; semantic
attributes/nullability, MemberRef/Field signatures as needed, and then the
import-policy/provider layers remain next.

**CLR-generic-metadata progress (2026-07-28):** GenericParam and GenericParamConstraint now retain
their physical row identities, owners, zero-based positions, metadata names, raw flags, variance,
special constraints, and TypeDef/TypeRef/TypeSpec constraint handles. This follows JVM's raw
foreign-type-parameter then FIR-enhancement split. The real CLR divergence is that variance and
reference/value/default-constructor constraints are runtime flags, open constraints can be
TypeSpecs, and .NET 10 adds `AllowByRefLike`. The reader validates row and method-arity coherence
without treating those CLR flags as Kotlin nullability or ordinary Kotlin upper bounds. Framework
and modern ILAsm exercise invariant/variant parameters and open constraints; a Roslyn fixture
adds reference, value, constructor, recursive parameter, and `allows ref struct` forms. Real
`mscorlib` and `System.Runtime` metadata and a corrupted generic-arity copy are covered. Constraint
resolution and physical classification are now implemented through the bounded selected-graph
layers described below; semantic nullability and the lazy import policy/provider remain next.

**CLR open-generic-constraint progress (2026-07-29):** constructed generic validation now accepts
an explicit declaration-qualified parameter context. This follows the mature targets' retention of
logical declaration ownership rather than treating a parameter index as a global identity. The CLR
difference is physical: `!n` and `!!n` are separate TypeDef- and MethodDef-owned spaces, and VES
generic-argument validation uses boxed nominal implications plus independent special constraints.
The context resolver validates the selected owner, numbering, arity, and every open reference;
only a complete identity TypeDef view exposes its own `!n` bindings. Nominal bounds are followed
under a cycle guard, while `class`, `struct`, `new()`, and `AllowByRefLike` use deliberately narrow
proof rules. The latter remains a `net10.0`-only permission. No Kotlin bound is inferred and global
signature assignability is unchanged. Real Roslyn metadata, hostile scope/cycle mutations, and
Framework/CoreCLR runtime probes cover the rule. Semantic nullability and the FIR import policy
remain above this physical validity layer.

**CLR parameter-attachment progress (2026-07-29):** the physical importer now retains optional
Param rows before attempting nullable-reference enhancement. This matches the mature-target rule
that declaration/return/value-parameter attachment is preserved before source-language type
mapping. The actual CLR difference is that MethodDef signatures own the types and count while a
separate Param table optionally carries names, flags, custom attributes, constants, and marshal
metadata; MethodDef.ParamList owns the rows and sequence 0 denotes the return. The reader retains
token, owner, raw flags, sequence, nullable name, multiplicity, and row order without synthesizing
missing rows. It rejects invalid list bounds, reserved flags, and sequences outside the signature,
while retaining ECMA warning-only gaps, duplicate/decreasing sequences, and non-null empty names
for later located importer diagnostics. This changes no Kotlin Common contract and is uniform
across all three profiles. Dual-profile ILAsm, Roslyn semantic shapes, real net10
`System.Runtime`, and hostile byte-level fixtures cover the rule. NullableAttribute context/flag
decoding is now the next semantic layer; Constant and FieldMarshal payload decoding remains an
explicit physical prerequisite when defaults or interop marshaling are imported.

**CLR nullable-metadata progress (2026-07-29):** the importer now decodes Roslyn's three
nullable-reference metadata conventions without projecting a Kotlin type. This follows JVM
foreign-type enhancement by retaining annotation ownership and semantic payload before applying
language policy. The actual CLR difference is that C# nullability is compiler metadata rather
than a runtime type distinction, and Roslyn may privately embed its well-known attribute
definitions in the producing assembly. Recognition therefore requires the exact top-level
`System.Runtime.CompilerServices` name and exact resolved `byte`/`byte[]`, `byte`, or `bool`
constructor shape, but deliberately no fixed assembly identity. Scalar uniform transforms,
preorder sequences, enclosing contexts, and module public-only policy remain distinct evidence.
Duplicate recognized attributes, malformed/null payloads, named arguments, and flags outside
0..2 fail as explicit semantic metadata errors. Real Roslyn generic/array/oblivious/context and
public-only output plus hostile selected-metadata variants cover the decoder. Kotlin Common is
unchanged: effective context lookup, accessibility filtering, physical type-tree application,
generic interaction, diagnostics, and FIR enhancement remain the next importer slice.

**CLR nullable-type-shape progress (2026-07-29):** the decoded evidence can now be aligned with an
exact resolved CLR signature before any Kotlin type is created. This mirrors JVM's indexed foreign
type qualifiers and Roslyn's preorder transform traversal. The CLR-specific rule is structural:
ordinary reference/generic/array/pointer/function-pointer nodes consume before their children;
non-generic value types and `System.Nullable<T>` do not; another generic struct consumes an
oblivious position; `ref` and custom modifiers are transparent; function pointers visit return
then parameters. The selected profile's physical classifier validates nominal class/value shape.
Uniform context evidence repeats across consuming nodes, while an array transform must match the
count exactly. Mismatches and invalid physical types are structured failures rather than partial
enhancement. Real Roslyn generic, array, `ref`, generic-struct, primitive-skip, and nullable-value
shapes plus synthetic function-pointer and hostile inputs cover the rule. Enclosing context,
effective accessibility/`NullablePublicOnly`, diagnostic fallback, generic constraint
interaction, and FIR projection remain separate next layers.

**CLR nullable-declaration-policy progress (2026-07-29):** nullable evidence selection now happens
at the physical declaration boundary before type-tree application or FIR projection. As on JVM,
declaration ownership and inherited defaults are resolved before foreign type enhancement. The
CLR-specific policy decodes Roslyn's module `NullablePublicOnly(bool IncludesInternals)` marker
and computes effective public/internal/private accessibility through containing types. Parameters
and generic parameters follow their method/type owner. CLR Property rows have no accessibility;
matching Roslyn, their nullable-public-only access symbol is the containing type rather than
either accessor. Thus a private property in a public type is included while its private accessor
MethodDefs are suppressed when considered separately. Local Param/Field/Property/GenericParam
evidence wins, followed by the nearest MethodDef and containing-TypeDef context. The result stays
explicitly selected, oblivious, suppressed, or invalid and creates no Kotlin type. Real Roslyn
fixtures cover both public-only modes, friend-assembly internals, accessor asymmetry, and nested
visibility; hostile metadata covers ambiguous Param rows, invalid ownership, cycles, and depth.
It also proves that excluded malformed local evidence stays suppressed. Diagnostic fallback,
generic-constraint interaction, and FIR enhancement remain later layers.

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
- no normative document describes current prototype identities as stable ABI 1; historical review
  snapshots remain evidence of the contradiction that this gate corrected, not ABI declarations.

### Gate B — third-party experimental binaries

- all P0 work packages complete;
- embedded Kotlin metadata/resource-format and version-skew diagnostics active;
- CLR importer prototype and committed C# provider/consumer tests;
- exception, generic-nullability, array, and callable freeze decisions accepted;
- public Kotlin ABI, compiler ABI, and C# export surfaces mechanically distinguishable;
- distribution-owned runtime and stdlib artifacts replace per-program bootstrap production.

### Gate C — official experimental target discussion

- dedicated .NET frontend session and logical Gradle/Common-metadata identity are present;
  embedded KLIB platform marking and profile-aware self-describing-DLL publication remain
  required;
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
