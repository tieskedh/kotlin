# Draft ADR: one reified CLR owner with semantic capability views

- Status: **Draft — architecture spike only; production emission remains erased**
- Date: 2026-08-12
- Current authority:
  [`generic-class-erased-identity.md`](generic-class-erased-identity.md)
- Cross-cutting semantic boundary:
  [`kotlin-semantic-authority-and-platform-freedom.md`](kotlin-semantic-authority-and-platform-freedom.md)
- Programme:
  [`../programmes/generic-class-owner-reopening.md`](../programmes/generic-class-owner-reopening.md)
- Design matrix:
  [`../programmes/generic-class-owner-carrier-matrix.md`](../programmes/generic-class-owner-carrier-matrix.md)
- Migration plan:
  [`../programmes/generic-class-owner-migration-plan.md`](../programmes/generic-class-owner-migration-plan.md)
- Direct C# surface:
  [`../programmes/generic-class-owner-csharp-surface.md`](../programmes/generic-class-owner-csharp-surface.md)

## Intended outcome

Kotlin-owned generic classes should use a true CLR-generic implementation
owner wherever the complete Kotlin contract can be preserved. The target is
not merely fewer boxes: Kotlin should construct, inherit, override, and call
native `C<T>` shapes, and C# should see that natural owner without an adapter
when the logical declaration/use is representable.

The production change is not authorized by this draft. The current erased
owner remains the correctness oracle until the hostile model, physical
bindings, reflection, and atomic migration all pass.

## Hardest-model-first candidate

For one logical declaration `open class C<T>` the candidate has:

1. one CLR implementation TypeDef `C<T>`;
2. one authoritative set of fields and object state on that instance;
3. one non-generic compiler-ABI semantic capability implemented by every
   construction of `C<T>`; and
4. KLIB as the authority for logical arguments, variance, projections,
   nullability, and bounds.

The semantic capability is an interface view of the same object, not a second
implementation owner, wrapper, proxy, copied store, or alternate Kotlin
classifier. Constructed `C<string>`, `C<int>`, and fallback constructions all
normalize to the one Kotlin declaration classifier. The open CLR TypeDef and
capability identities are recorded in the physical binding; consumers never
reconstruct them from a name.

The first spike must use an open mutable invariant owner and compose:

- reference, value, nullable-value, open-nullable, and arbitrary CLR-struct
  substitutions;
- star, output, and input projections;
- candidate-accepting widened calls such as Common `contains`/`containsAll`;
- exact and owner-relative generic interfaces and methods;
- multi-level Kotlin and C# inheritance, overrides, `super`, and default
  dispatch;
- checked, safe, star, projected, and unchecked casts;
- arrays and nested generic constructions;
- metadata-fixed open-nullable base edges such as `D<T> : C<T?>` for both
  value and reference substitutions;
- a general user `@UnsafeVariance` body whose widened incompatible candidate
  must execute Kotlin logic rather than a collection-specific fixed default;
- class/callable reflection normalization; and
- portable producer/consumer assemblies on both target profiles.

Final/read-only/reference-only owners are reductions of that model, not an
earlier production ABI.

## Repairing the removed typed-primary model

The removed model used the same broad shape—`C<T>` plus a non-generic
capability—but made the typed member authoritative. Its erased bridge first
narrowed every owner-dependent argument to physical `T` and then forwarded to
the typed body. That is unsound for ordinary legal Kotlin calls:

```kotlin
val ints: Collection<Int> = ...
val widened: Collection<Any?> = ints
widened.contains("not an Int") // must return false
```

Forwarding `object` into an `AbstractCollection<int>.contains(int)` slot throws
before Common's algorithm can inspect and reject the candidate. This is not an
unchecked-cast corner; it is the normal covariant Collection contract.

The new candidate assigns authority by member-slot domain instead of choosing
one bridge direction for an entire owner:

- a strict input that is legal only as `T` may use a natural typed virtual and
  narrow at its real typed-use barrier;
- a strict output may use a typed virtual and box/reference-convert only for
  the semantic capability;
- a widened, nested, or `@UnsafeVariance` candidate must reach a semantic body
  as an object and return/throw exactly as Common specifies, unless Kotlin's
  shared special-bridge contract supplies a type-safe incompatible result;
- a broad candidate uses separate typed virtual, semantic hook, and capability
  dispatcher roles where a semantic body is required, while a shared
  type-safe-barrier member may return its specified `false`/`null`/`-1`/second
  argument result directly; compatible calls still observe a C# typed
  override and incompatible candidates are never narrowed first; and
- all roles form one coherent override family for Kotlin, C#, `super`,
  defaults, and separate compilation.

The current erased implementation is therefore useful infrastructure: its
body behavior is the oracle and prospective semantic path. Reification adds a
typed owner and optimized entries around it; it must not replace the proven
semantic body with a narrowing bridge.

For a general semantic-body family, the producer direction is therefore the
inverse of the removed design. The Kotlin source body is lowered over semantic
carriers into the semantic virtual hook. A Kotlin typed virtual converts its
typed arguments into those carriers and invokes that hook; it does not own a
second implementation. The capability dispatcher invokes the typed virtual
for compatible candidates so ordinary C# overrides remain observable, and
invokes the semantic hook directly for incompatible candidates. A semantic
`super` call targets the recorded base semantic hook non-virtually. This body
transformation, including defaults and nested carriers, must be compiler-
produced and validated before admission.

The same analysis applies to state, not only methods. Kotlin can use
`@UnsafeVariance` to write an incompatible value through a widened covariant
owner and observe the failure only when a later exact consumer requires a
physical conversion. Merely invoking an erased getter and discarding its
result performs no cast. A physical `!T`
field in `C<int>` cannot reproduce that sequence because the CLR rejects the
write immediately. Any field reachable from such a semantic mutation path
therefore needs one semantic/object carrier with typed accessors around the
same slot, or the declaration is unadmitted. Reified TypeDef identity does not
authorize typed storage that changes Kotlin failure timing.

Object-carried state is necessary but may still be insufficient for an open
typed C# override. After an incompatible semantic write, a widened Kotlin read
must return the stored object while an exact `Read(): T` may fail converting
it. One CLR typed override cannot implement both entries automatically. The
prototype must either prove a coherent typed/semantic output override family
which C# authors can implement explicitly, seal/narrow only a surface that is
truthful, or leave the declaration unadmitted. It must not silently let a C#
typed override affect exact calls but disappear from Kotlin widened calls.

## Cast and mutation policy

Kotlin diagnoses the generic-argument part of `value as C<X>` as unchecked.
The CLR target may reject a physically incompatible constructed owner at that
cast instead of admitting a view that later writes an incompatible value. The
candidate uses that permission:

- `C<string> as C<int>` may throw `InvalidCastException`, logically classified
  as Kotlin `ClassCastException`, at the throwing cast;
- `C<string> as? C<int>` checks the logical classifier rather than generic
  argument subtyping and returns the same semantic object; a classifier
  mismatch returns null;
- `is C<*>`, `as C<*>`, star/projection conversions, and ordinary widened
  calls test the open declaration/capability and preserve the same object;
- every source-legal mutation through an exact or input-projected view updates
  the one typed field/store; and
- no two-store synchronization or deoptimization is introduced merely to
  preserve the current baseline's deliberately later failure for an invalid
  unchecked construction.

Early failure never permits a valid star, projection, variance, widened
candidate, or separate-module call to fail. The hostile matrix, rather than a
microbenchmark, decides that boundary.

This asymmetry is deliberate. The Kotlin cast-expression rules make the
failure point of a parameterized throwing `as` implementation-defined, while
generic arguments of a parameterized `as?` are not checked with respect to
subtyping. See the accepted
[semantic-authority and platform-freedom ADR](kotlin-semantic-authority-and-platform-freedom.md).

## Physical carrier rule

An exact construction which has a truthful CLR type uses it directly:

- `C<String>` becomes `C<string>`;
- `C<Int>` becomes `C<int>`;
- closed `C<Int?>` may become `C<Nullable<int>>`; and
- a method-owned exact `C<T>` may use `C<!!T>`.

Stars, projections, and a construction which has no uniform truthful CLR
spelling use the same object's semantic capability. This is a carrier choice,
not another Kotlin identity.

Cross-module Kotlin ABI cannot assume that a value with a closed logical type
was born from a closed construction. A generic producer can return a value
whose physical construction was selected under an open type expression.
Consequently, public/protected fields, parameters, and returns remain on the
semantic carrier until a declaration-stable proof shows that every legal
producer uses the same exact `C<X>`. Local SSA values may retain an exact
construction only while joins, stores, calls, casts, and escapes preserve that
proof. The physical binding, never the consumer's substituted static type,
describes the carrier at a call boundary.

This conservative Kotlin ABI does not hide the CLR class. C# can construct and
subclass `C<T>` directly, and explicit export can publish exact typed
parameters/returns where the complete call boundary is truthful. An export may
not cast a semantic fallback to an incompatible construction or silently copy
it.

## Open nullable owner arguments are a primary gate

An unconstrained logical `T?` has no single CLR generic argument:

- reference `T` uses the same reference token plus nullable metadata;
- value `T` requires `Nullable<T>`; and
- ECMA-335 cannot instantiate `Nullable<T>` for an unconstrained token that
  may be a reference type.

Therefore `fun <T> make(value: T?): C<T?>` cannot be implemented by pretending
that either `C<T>`, `C<Nullable<T>>`, or `C<object>` is the exact static
construction for every substitution.

A direct CLR probe now establishes that guarding a statically emitted
`Nullable<!!T>` construction with `typeof(T).IsValueType` still fails at
execution on CLR 4 and CoreCLR. The runtimes validate the invalid constructed
token independently of the source-level branch. The same probe establishes
that runtime type construction can create the truthful closed owner—
`C<Nullable<int>>` or `C<string>`—and preserve null and mutation through one
semantic capability on both JIT runtimes.

That is feasibility evidence, not a selected product mechanism. The spike
must compare dynamic exact construction with an honest semantic fallback
construction of the same open `C<>` TypeDef, including reference/value/null
state, identity, mutation, casts, arrays, joins, trimming, NativeAOT, and
separate compilation. If neither composes without rejecting a source-legal
operation or creating an unacceptable permanent runtime dependency, the
admission model must be revised before any production owner lands. Easy
`C<Int>` success does not defer this gate.

Runtime exact closure does not solve metadata-fixed inheritance. In particular,
one CLR `D<T>` TypeDef for the source shape `D<T> : C<T?>` cannot select
`C<Nullable<T>>` for value substitutions and `C<T>` for reference
substitutions: its base TypeSpec is fixed before either closed construction
exists. The hostile spike must therefore test a deliberately fixed fallback
against Kotlin override, `super`, shared state, casts, reflection, and direct
C# ancestry. If that fallback is not semantically complete and honest to C#,
the whole `D<T>` declaration shape remains erased or unadmitted; successful
runtime construction of standalone `C<>` objects is not evidence otherwise.

A direct CLR 4/CoreCLR fallback probe now pins the trade-off. One fixed
`C<object>` base can retain one inherited state, virtual dispatch, direct
`super`, and null/value/reference mutation. CLR reflection and C# also
correctly report exactly that base: `D<int>` is not assignable to
`C<Nullable<int>>`, and `D<string>` is not assignable to `C<string>`. This is
an operational semantic fallback, not a truthfully reified `D<T>` surface.
Under the intended interop criterion the declaration must therefore remain in
the erased/fallback admission class unless a different one-owner
representation proves the exact ancestry; an export must not conceal it.

A bounded .NET 10 application probe also passed ReadyToRun and full-trimming
execution for the exercised closed value/reference cases. NativeAOT analysis
flagged the runtime `MakeGenericType` route as requiring dynamic code, and the
available machine lacked the native platform linker, so no NativeAOT execution
claim is made. Arbitrary structs, external assemblies, finite rooting, and a
complete native toolchain remain acceptance gates.

A follow-up removes that unbounded reflection mechanism from the candidate
vocabulary. A consumer-side plan accepts only finite concrete runtime roots,
derives statically visible exact constructions from the decoded producer, and
adds one mandatory `C<object>` semantic fallback for unlisted types. Exact
value, already-nullable value, reference, and consumer-struct roots plus
unlisted struct/reference fallbacks execute on both CLRs with one state and
capability. Under IL3050/IL2026-as-error NativeAOT analysis, the old control
fails at `MakeGenericType`; the finite table is warning-clean. A later explicit
signed-MSVC run completes its native link and execution with the same checksum
as JIT, ReadyToRun, and full trimming. Representative product measurements
remain open; bounded NativeAOT success does not authorize production emission.

A second direct CLR probe validates the proposed strict-versus-candidate
dispatch split on CLR 4 and CoreCLR. A single generic owner/state can expose
typed virtual read/write/candidate members and an explicit non-generic
capability; compatible capability candidates observe multi-level C# typed
overrides, while incompatible candidates reach an object-domain semantic hook
without narrowing. Nullable values, references, and a user struct retain the
same state. This proves the runtime shape is possible, but does not yet prove
compiler-generated Kotlin override, `super`, default, binding, reflection, or
separate-assembly behavior.

A follow-up producer/consumer probe validates the same dispatch families
across separate C# assemblies on both runtimes. Compatible candidates observe
consumer-owned typed overrides; shared fixed-result barriers reject
incompatible candidates without entering an arbitrary body; general widened
operations enter the protected semantic hook; and multi-level overrides keep
one inherited state. A deliberately incomplete subclass of an abstract broad
operation fails C# compilation because implementing only the typed member does
not satisfy the wider semantic obligation. This remains physical feasibility
evidence: the producer is not yet emitted from Kotlin IR.

The backend now also contains a production-inert, fail-closed architecture
planner immediately before the existing erased generic-owner/interface work.
For each local Kotlin-owned generic class it records member authority, explicit
nullable-owner metadata-fixed supertypes, open owner-dependent outputs, and a
projection of one module-wide producer field/call graph. That graph covers
functions, constructors, general function-access edges, field initializers,
and anonymous initializers. Private helpers are strict graph nodes, not broad
entries; an exposed semantic body propagates reachability through them. A
private field additionally records the provenance of every actual write.
Typed and semantic callable-boundary seeds flow through call arguments, local
definitions/assignments, returns, and casts; a cast to `T` preserves rather
than upgrades its input domain. All-typed producers select typed state, any
object-domain producer selects semantic state, and unsupported/source-free
paths retain a typed-value-provenance obligation. A non-private field remains
cross-assembly incomplete. Its dispositions are limited to established blockers and
further proof obligations. There is intentionally no `ADMITTED` result, no compiler
switch, and no emitter consumer; the next lowering asserts that every Kotlin
generic class was planned and then retains it in the erased owner set.

That seam now constructs a detached real-IR member family for every planned
source member. The role set contains the natural typed entry, an object-domain
semantic hook where broad behavior or paired open output requires it, and a
private capability dispatcher where the non-generic capability needs a slot.
The generic owner remains the receiver; only explicit semantic parameters and
results erase. The family also observes the already-selected masked default
dispatcher, real `superQualifierSymbol` calls, and any pre-lowering logical
owner/member key. A hard invariant rejects a prototype member in the owner's
declaration list. The backend exposes an immutable IR-free snapshot to the
test pipeline, but neither the emitter nor DLL/KLIB serialization consumes it.

The hostile snapshot is asserted in all eight Kotlin lanes. Its
`HostileNullableDerived<T>` records one conditional `T?` base edge and its
direct base read; its open covariant unsafe store records an object-required
field reached through `writeUnsafe -> installUnchecked -> <set-stored> ->
stored`, its declaration initializer, a general semantic write
hook/dispatcher, a paired semantic read hook/dispatcher, and typed explicit
inputs/outputs. Its read-only unsafe producer separately records typed private
state after the closed producer graph. A second invariant hostile store routes
exact `T` through a private `Any?` parameter and `as T`; its initializer and
lowered setter both remain physically typed, proving that the cast shape is
classified by producer domain rather than syntax. The test-owned CLR physicalizer consumes
those exact state/member snapshots to generate a temporary generic producer
and separately compiled C# subclass/consumer. It validates GenericParam, exact
non-generic InterfaceImpl, private-final-virtual explicit MethodImpl targets,
typed `!T` virtuals, protected object hooks, single object field, and paired
override behavior on both runtimes. This closes the base snapshot-to-CLR seam,
not Kotlin-produced subclass families or the production TypeDef/emitter/
binding-schema gate.

The test-owned producer ABI is no longer reconstructed by switching on hostile
member names. Those names remain fixture-scenario/body labels only. Lowered IR
supplies exact constructor and typed/semantic/
capability signatures through a deliberately bounded structural type grammar;
the actual lowered default helper supplies its static tail and mask slots, and
the producer access graph supplies state read/write families. Uniform role
suffixes extend the compiler-selected ordinary MethodDef name. Unsupported
types and helper shapes leave the whole exact proof unavailable rather than
falling back to `object`. Pre-normalization inner metadata whose captured
parameter disagrees with its recorded slot domain is likewise unavailable. A
regression copy rewrites only diagnostic source labels and must yield an equal
artifact. A generated role identity which collides with another logical member
rejects the artifact atomically. Local exact signatures are discarded after
an external producer merge changes a consumer role or domain, preserving the
decoded producer record as the sole cross-assembly physical authority. This
generalizes the architecture proof; it does not change production emission.

The detached graph now covers local Kotlin-produced generic subclasses as
well. Typed entries point to ancestor typed prototypes, inherited semantic
hooks are propagated and point to ancestor semantic prototypes, and private
capability dispatchers never form override chains. When the base generic owner
is external, the consumer records the overridden logical member key and the
candidate remains `REQUIRES_EXTERNAL_OVERRIDE_BINDING_SCHEMA`; no physical
slot is inferred from the current erased artifact. A production-inert family
artifact, now at schema 7, makes the first external link objective: it
fingerprints the exact temporary producer and records logical joins,
owner/capability paths, arity, disposition, state requirements, complete
roles/reasons, selected
MethodDef owners/names, slot dispatch, complete slot-domain vectors, and neutral
structural signatures. A capability dispatcher names the exact interface
MethodDef it implements; nested owner/method parameters, named generic
instances, and SZ arrays remain structural records rather than IL strings.
Only a fully decoded artifact may resolve the typed and semantic obligations;
stale, truncated, wrong-producer, duplicate,
incomplete, and missing-member artifacts fail. The resolved snapshot advances
only to member physicalization proof, never admission. Production DLL/KLIB and
the erased emitter do not consume this artifact. Version 3 gives separate
typed/semantic direct-super targets and the static masked-default helper exact
signatures as well; the helper remains outside override roles and preserves
derived typed dispatch. Version 4 records the exact target profile,
open-TypeDef classification mode, statically exact constructor MethodDefs and
visibility, constructed owner, exact `this`/`base` edges, and the selected
field carrier's paired typed/semantic read/write MethodDefs and boundary
conversions. It admits no runtime-selected or semantic-fallback construction.
Version 5 adds an exact producer-open-TypeDef-to-KLIB-classifier join and one
logical callable record for each complete physical MethodDef family. Exact
classifier lookup never accepts the semantic capability or a foreign subclass;
logical instance classification separately follows recorded open-TypeDef
ancestry. Multiple closed constructions normalize to the same classifier,
while names, logical type arguments, variance, projections, nullability, and
bounds remain exclusively in the KLIB graph. Capability TypeDefs and physical
hooks/dispatchers/default helpers remain hidden from Kotlin member identity;
semantic callables select the capability dispatcher and strict callables select
the typed entry. Constructors continue to use the version-4 construction
records rather than becoming ordinary members. Schema 6 adds ordered physical
GenericParam constraints. Schema 7 separately catalogs every logically
bindable producer candidate by owner key, arity, disposition, and complete
constructor/member key sets. Physical families form an exact checked subset of
that catalog. A known candidate without a family therefore reports its producer
disposition; it can no longer be confused with an omitted, stale, or foreign
declaration. In particular, metadata-fixed `D<T> : C<T?>` is recorded as
`BLOCKED_METADATA_FIXED_CONDITIONAL_SUPERTYPE` without a dishonest CLR family.
The bounded external Kotlin-subclass physicalizer now joins compiler-derived
child visibility, modality, exact admitted constructor
signature, fake-override roots, and direct `super`
edges to the decoded producer record. Only the child TypeDef path is caller-
selected. Constructor delegation determines the immediate base independently
of a MethodDef's declaring ancestor; exact signature equality and positional
identity forwarding of every child parameter are required, and producer slot
identities are copied without name inference. Producer and child GenericParam
constraint rows must be exactly equal in the currently supported grammar;
matching arity is not proof. The current proof further requires a public open
non-inner child with one direct base/constructor and no added interface, field,
initializer, nested type, state, or non-fake member. Inherited fake overrides
remain inherited. The result drives only the hostile temporary C# oracle and
does not change production emission.

Runtime roots remain outside producer schema 7. A separate consumer-side
record now derives finite runtime-exact `C<P(T?)>` routes from the producer's
open unconstrained owner, capability, and public strict one-`!T` constructor.
It normalizes an already-nullable value idempotently, returns the semantic
capability, and
contains exactly one default `C<object>` fallback. No unbounded dynamic-code
route is representable; invalid nullable roots and constrained owners reject
the plan. This is executable JIT/analyzer evidence; native
link/run is still required before the construction modes can be admitted.

The hostile source now exports a paired application corpus containing the
actual production-erased Kotlin producer/consumer, a direct C# consumer and
two-level subclass of that erased owner, and the record-driven candidate
producer/consumer. Both CLR profiles and FIR parsers execute framework/user
structs, nullable and mixed state, arrays, method generics, reflection, and
multi-level dispatch. The direct C# oracle records that the production owner
is arity zero with `object`/`System.Array` positions while independent method
generics remain native. Strict closed manifests and frontend-equivalence
checks make this a stable comparison input; no production `C<T>` is emitted
and representative cost review remains open. See
[`../archive/generic-owner-application-corpus-2026-08-13.md`](../archive/generic-owner-application-corpus-2026-08-13.md).

The exact pair now has a bounded Framework CLR 4 plus .NET 10 JIT,
ReadyToRun, trimming, and NativeAOT measurement. The candidate retains the
correct checksum but takes 1.62–2.96× the erased workload time and allocates
6.89–7.52% more in the deliberately semantic-heavy call mix. This does not
reverse the desired direct `C<T>` interop direction and does not establish an
intrinsic CLR-generics cost. It prevents this draft from advancing on typed
identity alone: route-specific bridge cost and representative complete Kotlin
products are separate gates. Full trimming also established a metadata invariant:
a class-owned canonical MethodImpl rebuilt over an external base requires a
direct canonical InterfaceImpl reimplementation. The backend and verifier now
enforce that shape without wrappers or duplicate state. See
[`../archive/generic-owner-paired-application-measurement-2026-08-14.md`](../archive/generic-owner-paired-application-measurement-2026-08-14.md).

The bounded route-specific gate is now closed. The current candidate's
compiler-derived owner state is `SEMANTIC_OBJECT_REQUIRED`: typed value entry
still boxes into object state, compatible capability value entry adds a
runtime check plus a re-box, and allocation-free reference/array capability
routes retain material dispatch cost. Equal-layout fallback structs eliminate
payload-size bias. Owner-independent method generics stay near parity, and
some NativeAOT typed/override routes are competitive, so CLR generics are not
rejected; the present capability/object-state architecture is the cost center.
Framework 4.8 and .NET 10 failure behavior also differ enough to remain
independent evidence lanes. Representative complete Kotlin applications,
typed-storage/deoptimization feasibility, and the atomic migration remain
open. See
[`../archive/generic-owner-route-attribution-2026-08-14.md`](../archive/generic-owner-route-attribution-2026-08-14.md).

The other state decision produced by the same compiler graph is now
physicalized in the bounded hostile family. An invariant mutable owner whose
initializer and every transitive write retain physically typed provenance may
publish `!T` state. Its exact typed read/write entries access that field by
identity and never cross the non-generic capability. The capability remains
mandatory for widened and star operations: strict input dispatch checks and
narrows to `!T`, strict output dispatch widens or boxes from `!T`, and no
incompatible input may mutate typed state. This remains one owner and one field,
not a typed cache beside canonical object state.

The paired `Int32`, non-trivial struct, and nullable routes prove the field and
exact signatures use the owner GenericParam on Framework CLR 4 and every .NET
10 deployment lane. Exact access eliminates per-iteration allocation. The
capability still pays two object-domain conversions and one check per iteration
and remains materially slower; large-struct exact timing is deployment-
sensitive even without allocation. This physicalizes the hostile fixture's
existing compiler-proven state case. It does not admit an easy production
owner, weaken the complete hostile semantic owner, or authorize atomic
migration. See
[`../archive/generic-owner-typed-storage-attribution-2026-08-14.md`](../archive/generic-owner-typed-storage-attribution-2026-08-14.md).

A broad candidate input is inherited semantic authority, not a property that
may be narrowed by re-reading only the overriding declaration. Local families
therefore propagate broad positions to a fixed point across their override
roots. A separate consumer merges the producer record's broad positions into
its own snapshot before any physical binding is returned.

## Inheritance, interfaces, and reflection

The generic implementation retains its exact CLR base construction and exact
truthful interface edges. A C# subclass of `C<T>` and a Kotlin subclass must
both remain visible through typed and semantic virtual calls. `MethodImpl`
rows may connect capability slots to semantic entries, but a bridge may not
bypass the most-derived override or narrow a candidate before dispatch.

CLR declaration-site variance may be used only on capability interfaces and
delegates whose complete member surface satisfies ECMA-335 variance. CLR
classes remain invariant. A member excluded from a variant interface remains
available through the non-generic semantic capability; no fictitious
`C<object>` or `I<object>` edge is emitted.

`KClass` normalizes every construction of the recorded open `C<>` TypeDef to
one Kotlin declaration. `KType` continues to obtain logical arguments from
KLIB, not from a guessed runtime construction. Callable reflection exposes
one Kotlin member and dispatches through the same semantic/typed override
family; compiler capability members do not leak into `members`.

## Devirtualization and specialization

Kotlin/Native Variable Type Analysis and Swift SIL devirtualization are useful
proof precedents. A finite receiver/construction set can select a direct typed
entry, clone a semantic body for an exact value type, or invoke an exact BCL
operation. At public/cross-assembly roots the analysis must retain the
semantic virtual fallback.

Devirtualization cannot create a missing public `!T`, prove future C#
subclasses, or turn an unrepresentable open-nullable construction into an
exact CLR type. It is a consumer of this representation model, not its
correctness argument.

## Rejected recurrence of earlier bugs

- Do not make an erased capability bridge cast every argument and forward to
  the typed slot.
- Do not use `C<object>` as star identity or a universal exact construction.
- Do not select canonical ABI from source visibility, a static Kotlin type,
  or one local allocation while ignoring joins and separate producers.
- Do not keep typed and erased authoritative stores.
- Do not wrap/copy after casts or projections.
- Do not let a typed fast path change receiver/argument evaluation count,
  virtual target, exception identity/timing for valid calls, or returned object
  identity.
- Do not publish easy owners before the open mutable hostile model is green.

## Evidence required before acceptance

Acceptance requires:

1. the hostile Kotlin oracle in same and separate compilation;
2. a C# producer/subclass/consumer matrix including arbitrary structs;
3. completion of the current snapshot-driven base owner into compiler-produced
   Kotlin subclass override families with one `C<T>`/state/capability product;
4. exact CIL assertions for TypeDefs, GenericParams, InterfaceImpls,
   MethodImpls, fields, virtual slots, constraints, and physical bindings;
5. class/callable/KType normalization and no capability-member leakage;
6. both CLR profiles, both FIR parsers, and stale-schema rejection;
7. representative JIT, ReadyToRun, and NativeAOT measurements for boxing,
   allocations, throughput, code size, metadata, compile time, and memory; and
8. an atomic migration/rollback plan which removes the old erased owner rather
   than supporting a mixed period for one logical declaration.

Only then may this draft replace the current erased-owner ADR and authorize
production Kotlin-owned `C<T>` TypeDefs.
