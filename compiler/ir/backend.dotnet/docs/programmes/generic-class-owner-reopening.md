# Programme: true CLR-generic Kotlin class owners where semantically sound

- Status: **Reopened for architecture design — current erased implementation remains binding**
- Current authority: [`../decisions/generic-class-erased-identity.md`](../decisions/generic-class-erased-identity.md)
- Candidate model: [`../decisions/draft-adr-reified-generic-class-owner.md`](../decisions/draft-adr-reified-generic-class-owner.md)
- Carrier and member admission matrix:
  [`generic-class-owner-carrier-matrix.md`](generic-class-owner-carrier-matrix.md)
- Atomic timing, migration, and rollback plan:
  [`generic-class-owner-migration-plan.md`](generic-class-owner-migration-plan.md)
- Direct C# construction, inheritance, override, and export boundary:
  [`generic-class-owner-csharp-surface.md`](generic-class-owner-csharp-surface.md)
- Historical implementation audit: [`../archive/generic-owner-history-audit-2026-08-12.md`](../archive/generic-owner-history-audit-2026-08-12.md)
- Related export boundary: [`../decisions/draft-adr-explicit-csharp-export-surface.md`](../decisions/draft-adr-explicit-csharp-export-surface.md)

## Objective

The intended final target uses a true CLR-generic owner for every Kotlin-owned
generic class whose complete Kotlin contract can be represented truthfully.
This is an interop and target-quality goal, not merely a local performance
optimization. C# should see and use native `C<T>` identity where Kotlin casts,
projections, mutation, inheritance, reflection, and separate compilation can
all remain correct.

The candidate is not the removed design in which typed
dispatch was normal and an erased canonical route was an exceptional fallback.
It would require both paths to be complete:

```text
Kotlin class Box<T>
    physical owner and authoritative state: Box<T>
    complete Kotlin semantic capability:   erased Box view
```

Exact, unprojected operations could use `Box<string>` or `Box<int>`. Stars,
projections, variance, widened operations, and declaration-erased runtime
classification would use the complete erased capability ABI. The same object
would implement both views and retain one authoritative state.

For one Kotlin declaration, admission and migration are atomic: it must never
mean an erased owner in one use/module and a CLR-generic owner in another.
Unsupported declaration shapes remain on the explicit erased mapping until a
complete representation is designed; they never receive a partly truthful
`C<T>` surface.

## Why the question became credible

The current accepted ABI deliberately follows mature-target delayed failure:

```kotlin
val original = Box("text")

@Suppress("UNCHECKED_CAST")
val wrong = original as Any as Box<Int>

wrong.value = 7
original.value // failure when the value is consumed as String
```

Kotlin diagnoses the generic-argument portion of this cast as unchecked. The
language permits a platform to reject a physically incompatible construction
earlier. If Kotlin/.NET instead throws during `as Box<Int>`, a physical
`Box<string>` never has to accept an `Int` through that invalid view. Typed
single-state storage therefore becomes plausible; the former two-store or
deoptimization contradiction no longer decides the entire architecture.

On this target the physical throwable would normally be the original
`System.InvalidCastException`, classified as Kotlin `ClassCastException` by the
accepted exception model. Do not wrap or translate it merely to mimic a JVM
stack trace.

## What early failure may cover

Only the physically incompatible part of a cast that Kotlin already cannot
fully check is a candidate for earlier failure. For example, a value whose
physical owner is `Box<string>` may fail an unchecked request for `Box<int>` at
that cast.

The eventual design must decide and test at least:

- throwing `as`, safe `as?`, and suppressed/unsuppressed unchecked diagnostics;
- concrete reference, value, nullable-value, and user-defined struct arguments;
- open method type parameters and nested generic constructions;
- null receivers and nullable cast targets; and
- the exact physical exception and logical Kotlin classification.

Parameterized `as?` does not inherit the throwing cast's platform freedom:
generic arguments are not checked with respect to subtyping. It uses the
logical open-owner/capability classifier, returns the same semantic object when
that classifier matches, and otherwise returns `null`; it never leaks
`InvalidCastException` or claims an exact constructed carrier merely because
the CLR can test one.

## What must never be rejected

Early incompatible-cast failure does not relax ordinary Kotlin semantics. A
candidate route must keep all of these valid without wrapping or copying:

- `is Box<*>`, `as Box<*>`, and safe star casts;
- declaration-site covariance and contravariance;
- use-site `out`, `in`, and star projections;
- widened receivers and arguments produced by normal Kotlin subtyping;
- identity, mutation, virtual dispatch, inheritance, `super`, nested/inner
  classes, defaults, and separate compilation on every successful path;
- nullable and bounded generic forms; and
- candidate-accepting operations such as `contains` and `containsAll` that must
  return `false`, rather than throw, for an incompatible candidate admitted by
  the widened Common signature.

The `containsAll` family proves that the erased Kotlin operation path is a
normal correctness path. A typed member may optimize a compatible candidate,
but its erased bridge must test compatibility and preserve Common behavior; it
may not narrow `object` to `int` before deciding that a `String` is absent.

## Hardest-model-first rule

Do not begin with final, immutable, reference-only, or otherwise convenient
owners and then change representation as harder Kotlin features arrive. The
first architecture spike uses one deliberately hostile open mutable invariant
owner and composes value, reference, nullable-value, and user-struct
substitutions; star, `out`, and `in` views; candidate-accepting erased methods;
generic interfaces; multi-level Kotlin and C# inheritance/overrides; throwing
and safe casts; reflection normalization; arrays and nested constructions; and
separate producer/consumer assemblies.

That spike must produce the general one-owner/one-state/capability model.
Simpler declaration families may later be admitted as reductions of that same
model, but they must not select a different canonical representation. No
production CLR-generic owner lands before the hostile matrix works and the
single physical ABI cutover is specified.

Run that hostile architecture spike now, while the target is pre-ABI and the
stdlib/runtime have not accumulated more erased-owner assumptions. Keep its
typed implementation experimental and non-production. Perform the actual
owner cutover later, after the ordinary language surface, concurrency/memory
semantics, and representative real applications can measure interop, boxing,
JIT/AOT, code size, reflection, and maintenance behavior. This separates early
architectural discovery from premature ABI publication.

The first executable step respects that boundary. A production-inert lowering
now creates a fail-closed architecture plan for each local Kotlin generic
class, including member authority, explicit nullable metadata-fixed supertype
edges, direct semantic state writes, and open owner-dependent outputs. Every
outcome is either a blocker or an unfinished proof obligation; no outcome can
select reified emission. The existing lowering then verifies planning coverage
and still puts every Kotlin generic class on the erased ABI. The hostile oracle
runs this analysis on both parsers, both CLR profiles, and across a producer/
consumer boundary.

That plan now constructs detached compiler IR for the typed entry, semantic
hook, and capability dispatcher roles, plus immutable snapshots of state,
explicit typed/erased domains, defaults, direct `super` calls, and logical
producer keys. The hostile test fixture asserts those snapshots in every lane;
a test-owned CLR physicalizer asserts the corresponding GenericParam,
InterfaceImpl/MethodImpl, field, virtual-slot, and override metadata. The
members are never inserted into the class IR and the emitter never consumes
them, so this is a bounded architecture prototype rather than the cutover.

The next physical-family ABI step is now declaration-name independent. Fixture
names still label test scenarios and bodies. The compiler snapshot contains
the ordinary compiler MethodDef base name and exact role signatures derived
from lowered IR. Its fail-closed grammar admits only
the currently proven built-ins, owner/method parameters, and arrays; semantic
owner-dependent arrays use `System.Array`, while unknown classifiers,
star/unsupported projection shapes, nullable value carriers, `Unit` parameters,
and unexpected default helper shapes produce no exact proof. Constructors preserve independent Int
parameters. Static default helpers are described from the actual lowered
dispatcher and mask parameters rather than a hand-built hostile signature.
Recorded transitive state reads/writes select the paired access families. An
exact MethodDef identity may belong to only one logical member; a collision
with a user declaration therefore fails the whole artifact rather than relying
on declaration order. An in-memory rewrite of every diagnostic producer source
label must therefore leave the complete family artifact equal. External
role/domain merging invalidates the consumer's older local signatures so
decoded producer MethodDefs remain the only physical authority.

State selection now follows one shared producer graph covering functions,
constructors, all function-access edges, field initializers, and anonymous
initializers. The graph is built once per module and projected per owner.
Private helpers do not become widened entry points merely because their
signature uses an owner parameter; semantic reachability propagates to them
from an exposed broad body. The owner projection now traces each field-write
value through callable boundaries, call arguments, local definitions and
assignments, returns, and casts. Casts preserve the input domain rather than
upgrading a logical `T` result: an exact value boxed to `Any?` and cast back
remains typed, while the same shape reached from a widened input remains
semantic. Unsupported or source-free paths retain an explicit unresolved
provenance obligation; a semantic producer selects the one object state, and
a non-private field retains a cross-assembly obligation.
Every result is still production-inert.

For the hostile store, the test facade consumes the immutable compiler
snapshot to generate a temporary CLR-generic producer and a separately
compiled C# subclass/consumer. Both CLR profiles execute compatible typed
override dispatch, incompatible semantic mutation, delayed typed-read failure,
paired semantic output overrides, one object field, and explicit interface
dispatch. The next link is compiler-produced Kotlin subclass override families
and producer/consumer binding records—not the base owner shape. Local
generic subclasses now have detached typed-to-typed and semantic-to-semantic
links; inherited semantic hooks are propagated as obligations and private
dispatchers remain final selectors. A generic consumer subclass of an external
producer records the overridden logical key. A production-inert family
  artifact, now at schema 12, proves the cross-assembly link: it is fingerprinted
to the exact temporary producer, wholly decoded before use, and supplies the
producer-selected typed and semantic MethodDef owners, names, dispatch,
slot-domain vectors, and structural signatures for that key. A dispatcher also
records its exact non-generic capability MethodDef. Hostile
tests reject stale, truncated, wrong-producer, duplicate, incomplete, and
missing-member artifacts, then compile and run the resolved C# subclass on both
runtimes. The normal compiler still emits/consumes only erased artifacts. The
neutral type vocabulary recursively retains `!T`, `!!T`, named instances, and
SZ arrays; the hostile nested-array family proves producer and consumer agree
on `!T[]` versus `System.Array` without substitution inference. Direct-super
targets and the static masked-default helper carry complete signatures too.
Broad candidate inputs propagate to a fixed point across local override roots
and are inherited from the external producer record, so a derived declaration
that looks strict in isolation cannot silently narrow the Kotlin family.
Version 4 also records the exact target profile, open-TypeDef classification,
statically exact constructor MethodDefs/visibility/constructed owners and
`this`/`base` edges, and the one state field's paired typed/semantic read/write
MethodDefs and conversions. The decoded record now drives the separate C#
consumer's immediate generic base construction, constructor input, and state
operations without reconstruction. Version 5 records exact open-TypeDef
classifier normalization, KLIB-only logical type-argument authority, hidden
capability exposure, and one logical callable for each complete physical
typed/semantic/capability/default-helper family. Closed constructions share
the producer's logical classifier; exact classifier lookup rejects capability
and foreign subclass TypeDefs, while logical instance checks use objective
open-TypeDef ancestry. The bounded cross-assembly subclass link is now complete
in the architecture channel. A pure compiler physicalizer accepts only the
unresolved external-subclass snapshot plus a fully decoded producer artifact;
the caller supplies only a distinct current-compilation TypeDef path. The
compiler supplies child visibility, modality, exact admitted constructor
signature, fake-override declaration roots, and source `super` edges. The
version-6 producer record supplies ordered GenericParam constraints, the exact
delegated base/constructor, and every typed/semantic MethodDef identity. The
immediate constructed base is selected
from constructor delegation even when an inherited fake override's MethodDef is
declared on an earlier ancestor. Matching domains never replace exact signature
equality, and every base argument must be the corresponding child parameter;
producer and child constraint rows must also be identical in the current
bounded grammar. The accepted child is public, open, non-inner, has one direct
base and one constructor, and adds no interface, field, initializer, nested
type, state, or non-fake member; inherited fake overrides remain inherited.
Final child member overrides remain sealed, final producer slots fail, and
semantic hooks remain protected. The record-generated C# consumer materializes
that Kotlin-like open generic subclass and a further C# generic grandchild on
both runtimes. Production emission remains erased.

Schema 7 also records a producer-complete candidate classification catalog
outside the optional physical families. Logical owner, arity, disposition, and
constructor/member keys survive serialization even when the declaration is
kept erased. `HostileNullableDerived<T>` is therefore present as a
metadata-fixed exclusion with no physical family. Catalog/family mismatch,
unknown declarations, and classified absence are distinct fail-closed cases;
none authorizes consumer-side family reconstruction.

Schema 8 adds a counted physical state-initializer block. A fixed zeroed
owner-dependent SZ-array records its exact element count and every logical
base-delegating constructor root which executes it. Initializer writes compose
with typed identity member access to satisfy complete state READ/WRITE; no
setter MethodDef is fabricated. Semantic-object state, non-vector storage,
missing or `this`-delegating roots, and incomplete operation coverage reject
the family.

Schema 9 separates logical member-family state paths from exact producer-
private MethodDefs. A producer-private path has no KLIB callable key, member
role, or reflection entry; it is a private typed identity method on the same
physical TypeDef and cannot collide with a logical member MethodDef. Purely
private typed identity access can cover object storage with one READ and one
WRITE, but any semantic path or conversion restores the complete paired
typed/semantic READ/WRITE requirement.

Schema 10 adds the exact physical visibility and dispatch of every owner
TypeDef plus the visibility of every member MethodDef slot. Typed entries
retain source visibility, semantic hooks are protected, and explicit
capability dispatchers are private/final. A decoded record can therefore
distinguish public/internal and final/open/abstract/sealed declarations without
consulting compiler-local prototypes.

Schema 11 adds exact positional constructor-to-state initialization and closes
the recursive OctoTree family atomically. All four owner TypeDefs, every
MethodDef owner, recursive `Node<T>[]` carrier, constructor edge, state path,
and reflection family must resolve inside one logical-keyed graph. The
`Leaf.value: !T` field records the exact logical constructor and parameter
index which initialize it; `root = null` is accepted only as a proven CLR
object-field default. Unsupported initialization and phantom physical owners
fail closed. See
[`../archive/generic-owner-complete-octo-tree-family-2026-08-17.md`](../archive/generic-owner-complete-octo-tree-family-2026-08-17.md).

Open-nullable construction now has a bounded consumer-side record rather than
new producer-schema claims. It accepts only finite concrete final-compilation
runtime roots, derives every exact owner/constructor from the decoded producer,
and returns the semantic capability. Exact value roots use
`C<Nullable<V>>`, exact references use `C<R>`, and already-nullable values do
not become nested nullable types. One mandatory `C<object>` fallback handles
unlisted value/reference roots honestly. The record has no unbounded reflection
mode. Both CLRs execute exact and fallback paths with one state and classifier;
a NativeAOT control passes managed analysis. A later explicit signed-MSVC run
completes native link/execution; representative measurements remain the
construction gate.

That exact compiler-record-driven factory now also owns the reproducible
measurement corpus. A pinned and fingerprinted net10 bundle—not a second
handwritten generic model—runs the same exact/fallback, state, array, and
multi-level hostile dispatch workload under JIT, ReadyToRun, full trimming,
and NativeAOT. All four modes agree on one checksum and record
startup, throughput, allocation, peak working set, publish cost, and
footprint. NativeAOT remains fail-hard; workload version 2 now records a
successful native executable plus exact signed-linker provenance. See
[`../archive/generic-owner-native-aot-measurement-2026-08-13.md`](../archive/generic-owner-native-aot-measurement-2026-08-13.md).

The same hostile Kotlin source now also owns a paired application corpus. Each
profile bundle carries the real production-erased Kotlin producer/consumer, a
direct C# erased consumer and two-level subclass, and the record-driven
candidate producer/consumer. Framework and user structs, nullable and mixed
state, array identity, method generics, reflection, and multi-level dispatch
execute under both FIR parsers and both CLR profiles. The erased C# reflection
oracle pins arity-zero owners with `object`/`System.Array` positions while
retaining real method generics, so the present interop cost is measured rather
than hidden. Closed manifests and a strict frontend-equivalence audit make the
pair suitable as the next measurement input. This closes paired correctness
preparation, not the representative performance gate. See
[`../archive/generic-owner-application-corpus-2026-08-13.md`](../archive/generic-owner-application-corpus-2026-08-13.md).

That exact pair now also drives a five-mode bounded comparison. Framework CLR
4 and .NET 10 JIT, ReadyToRun, full trimming, and NativeAOT agree on one
checksum. In the hostile call mix the candidate takes 1.62–2.96 times the
erased workload time and allocates 6.89–7.52% more. This is a measurement of
the current test-owned typed/semantic/capability architecture, not an inherent
CLR-generics verdict: 24 regular routes per iteration deliberately use the
semantic capability and only three use a typed entry. The first trimmed run
also exposed an invalid-for-ILLink inherited canonical MethodImpl map. The
backend now records producer-visible class-owned bridge families, excludes
lowering-created synthetic owners from that index, and directly reimplements a
canonical interface when rebuilding its bridge on a descendant of an old or
bootstrap external class. Both CLR loaders and ILLink accept the resulting one-
object/one-state product. Published sizes remain non-comparable because the
candidate is not a complete Kotlin product. Production emission stays erased;
the route attribution recorded below and representative real applications are
separate gates.
See
[`../archive/generic-owner-paired-application-measurement-2026-08-14.md`](../archive/generic-owner-paired-application-measurement-2026-08-14.md).

The route-specific gate is now closed for this bounded hostile model. The
candidate reports a compiler-required semantic object state even though its
owner and entries are CLR-generic. Direct typed value entry retains the same
boxing as erased. Compatible value capability entry adds one re-box per
iteration, while allocation-free reference and semantic-array capability
routes isolate substantial dispatch/compatibility-check cost. Equal-layout
fallback structs prevent payload size from becoming a false allocation win.
Owner-independent method generics remain near parity, and NativeAOT can make
typed arrays and compatible overrides competitive, so this is evidence
against typed identity over the current object-state/capability mix—not
against CLR generics. Framework 4.8 and .NET 10 have materially different
hostile-failure allocation and remain independent gates. Production emission
stays erased; representative complete applications are now the next cost gate.
See
[`../archive/generic-owner-route-attribution-2026-08-14.md`](../archive/generic-owner-route-attribution-2026-08-14.md).

The compiler's opposite state decision is now physicalized in the same bounded
family. `HostileTypedStore<T>` has one actual `!T` field because its initializer
and every transitive write are already proven physically typed by the complete
producer graph. Exact calls use identity field access and do not cross the
capability. The strict widened/star capability checks before write, boxes or
widens after read, and an incompatible input cannot mutate state. Metadata and
direct C# reflection pin that one-owner/one-field shape on both CLR families.

Paired `Int32`, `Int32 + Guid`, and `Nullable<Int32>` routes eliminate all
per-iteration allocation on exact access. Capability access retains two value-
domain conversions and one check, allocating twice the erased value-route
baseline. Exact scalar and nullable timing improves in all modes; large-struct
timing remains deployment-sensitive despite the allocation win. This closes
typed-storage causal feasibility, not owner admission. The semantically hostile
owner remains object-backed, production remains erased, and representative
complete applications remain the next gate. See
[`../archive/generic-owner-typed-storage-attribution-2026-08-14.md`](../archive/generic-owner-typed-storage-attribution-2026-08-14.md).

The static census now has a compiler-indexed execution join. An explicit
architecture-test product retains each analyzed call, evaluates its receiver
and arguments once in original order, records the original index immediately
before invocation, and then performs the unchanged dispatch. The exact hostile
vector includes one zero-hit and one two-hit site, so exact site identity—not
aggregate coincidence—is tested. See
[`../archive/generic-owner-call-route-trace-2026-08-14.md`](../archive/generic-owner-call-route-trace-2026-08-14.md).

The scalable collection transport is now closed. The explicitly instrumented
executable alone receives one private exact-sized primitive counter table and
private physical recorder/flusher bodies. `Interlocked.Increment` records each
attempt; an atomic final snapshot prints one line per visited site after
`box()` returns. Both frontends and both CLR profiles retain the same 49 total,
40 producer, and nine unrelated hostile events; normal products remain byte-
identical in all 34 control comparisons. There is no CLI/Runtime/KLIB/
published ABI. Representative applications must join workers before returning
and use this only to collect route/state distributions; throughput, allocation,
startup, and scheduling must be measured in separate uninstrumented products.
See
[`../archive/generic-owner-call-route-counter-flush-2026-08-15.md`](../archive/generic-owner-call-route-counter-flush-2026-08-15.md).

That collection product now has its first repository-owned application input.
The exact Kotlin/Native ArrayCopy source yields 16 local static sites and
5,664 local dynamic events, all exact typed-entry candidates, with identical
route/count bytes across both frontends and both CLRs. Its state result is the
important counterexample to naive field reification: unchecked construction
from `Array<Any?>` makes `values: Array<T?>` semantic array state even when the
closed owner is `CustomArray<Int>`. The member family can still expose a
strict typed `add` entry and a capability dispatcher. This is one bounded real
distribution only; it does not close representative breadth, C# products, or
clean erased/candidate measurement. See
[`../archive/generic-owner-array-copy-application-census-2026-08-15.md`](../archive/generic-owner-array-copy-application-census-2026-08-15.md).

The second exact input, recursive OctoTree, exercises a mixed distribution:
5,941 exact and 3,096 semantic-capability producer events over 25 and nine
local static sites respectively. It also proves a useful boundary below owner
reification. `Array<Node<T>?>` can be the truthful `Node[]` of the current
erased Kotlin classifier, while direct `Array<T>` remains `System.Array` and
`Leaf.value` remains physically `object`. The initializer supplies typed
candidate provenance, but `root` retains semantic state. All route/count bytes
agree across PSI/LightTree and Framework 4.8/net10. This is broader correctness
evidence, not representative completion or performance evidence. See
[`../archive/generic-owner-octo-tree-application-census-2026-08-15.md`](../archive/generic-owner-octo-tree-application-census-2026-08-15.md).

The state snapshot now closes the field-type ambiguity exposed by that input.
`TYPED_STORAGE_PRODUCER_GRAPH_PROVEN` alone did not identify whether a future
field was `T`, `Node<T>[]`, or another constructed carrier, so a physicalizer
could only guess. The compiler now retains a bounded path-unbound type tree.
Nested Kotlin generic classifiers are identified by pre-lowering logical
producer key and bind only after a complete artifact selects TypeDef paths;
missing paths, projections, unsupported classifiers, and open nullable `T?`
remain unavailable. A separate OctoTree library proves structural `Node<T>[]`
and `T` state, while the semantic `root` requirement remains unchanged. The
hostile candidate fixture consumes the same record instead of hardcoding `T`.
Schema 12 now serializes and binds the whole recursive owner family while
remaining production-inert. It also fixes Kotlin sealed construction as an
abstract, non-CLI-sealed base whose constructors are `FamilyAndAssembly`.
The decoded record drives a bounded real `Node<T>`/`Leaf<T>` product with true
`T` state: external C# constructs and reflects `Leaf<int>`, while an external
subclass fails compilation on Framework 4.8 and .NET 10. The next product
extends this same candidate over Tree/Branch, semantic/member routing, and the
complete direct C# surface. See
[`../archive/generic-owner-structural-state-carrier-2026-08-15.md`](../archive/generic-owner-structural-state-carrier-2026-08-15.md).
The sealed-construction checkpoint is recorded in
[`../archive/generic-owner-sealed-construction-closure-2026-08-17.md`](../archive/generic-owner-sealed-construction-closure-2026-08-17.md).

The physical callable grammar now observes the same no-guessing rule. Open
nullable `T?` cannot be a fixed CLR `!T`: value substitutions need a nullable
value while reference substitutions do not. Its universal call carrier is
therefore `object`. Likewise, `Array<T?>` and projected `Array<out/in E>` use
`System.Array`; an open `!T[]` or `object[]` would reject legal vectors. Exact
non-null/invariant and independent method-generic arrays remain typed. The
hostile projected `echo` typed and semantic MethodDefs now both record
`System.Array`, but the capability dispatcher retains its compatibility probe
and hence the correct override route. OctoTree `get(): T?` and an invariant
`Array<T?>` constructor/result independently pin the nullable cases. See
[`../archive/generic-owner-nonexact-call-carriers-2026-08-15.md`](../archive/generic-owner-nonexact-call-carriers-2026-08-15.md).

Constructed callable carriers now use the same path-unbound type tree as state.
Constructor, member, and masked-default snapshots retain logical classifier
keys and bind atomically only after the complete producer TypeDef map exists.
The separate OctoTree `nodes` getter proves typed `Node<T>[]`, capability
`System.Array`, missing-path rejection, and exact equality with its bound field
carrier. Same-compilation snapshots without a stable library key remain
unavailable. Schema 11 serializes only fully bound, atomically closed physical
records and now includes the complete recursive family. The next product must
build its paired candidate/C# consumers from that decoded record. See
[`../archive/generic-owner-path-unbound-member-signatures-2026-08-16.md`](../archive/generic-owner-path-unbound-member-signatures-2026-08-16.md).
The physical initializer record is described in
[`../archive/generic-owner-physical-state-initializers-2026-08-16.md`](../archive/generic-owner-physical-state-initializers-2026-08-16.md).
Producer-private state binding is recorded in
[`../archive/generic-owner-producer-private-state-access-2026-08-16.md`](../archive/generic-owner-producer-private-state-access-2026-08-16.md).
The physical declaration envelope is recorded in
[`../archive/generic-owner-physical-visibility-dispatch-2026-08-16.md`](../archive/generic-owner-physical-visibility-dispatch-2026-08-16.md).
The complete recursive family is recorded in
[`../archive/generic-owner-complete-octo-tree-family-2026-08-17.md`](../archive/generic-owner-complete-octo-tree-family-2026-08-17.md).

External-family binding consequently keeps logical and physical authority
separate. It merges inherited producer slot domains, then compares exact
compiler-derived physical signatures. It does not infer owner dependence from
whether a carrier happens to contain `!T`. When the producer introduces a
semantic hook that was unavailable during consumer planning, the consumer's
already-required capability signature supplies the exact local owner-erased
shape. This preserves fail-closed separate compilation without requiring the
consumer to reconstruct a producer role by name.

## Engineering gates

### 1. Does the complete semantic matrix work with one object and one state?

If no for a declaration shape, keep that shape on the accepted erased owner
until the missing semantic mechanism exists. Wrappers, copying, two
authoritative stores, or visibility-dependent runtime identity are not
acceptable repairs.

If yes, continue to the ABI and product questions; semantic possibility alone
does not justify the route.

### 2. Can inheritance and dispatch remain complete?

The spike must cover open classes, typed overrides, erased capability slots,
`MethodImpl`, C# subclasses, Kotlin subclasses of C# types, projected calls,
and multi-level separate compilation. An erased bridge that bypasses the most
derived override or narrows a candidate too soon rejects the route.

### 3. Can reflection expose one Kotlin declaration identity?

`KClass`, `KType`, class literals, `is`, callable owners, and future member
enumeration must normalize every constructed `Box<T>` to one logical Kotlin
classifier while retaining logical arguments only where Kotlin APIs expose
them. Raw `System.Type` constructions may be useful CLR evidence but cannot
become Kotlin declaration identity.

### 4. Is the C# surface honest and understandable?

Measure whether a true `Box<T>` owner actually removes enough adapters and
provides usable C# construction, inheritance, nullability, constraints, and
IntelliSense. Half-typed surfaces, surprising erased members, or CLR casts that
look stronger than the supported contract count against the design. Explicit
export remains an independent alternative.

### 5. Is the permanent cost controlled and measured?

Use representative applications, not one microbenchmark. Compare at least:

- boxing, allocations, and object size for Kotlin primitives and arbitrary CLR
  structs such as `Guid`, `DateTime`, `decimal`, enums, tuples, and user types;
- exact typed dispatch and compatible erased dispatch;
- JIT, ReadyToRun, and NativeAOT code size and throughput;
- DLL metadata size, TypeDefs, MethodDefs, MethodImpl rows, and generic
  instantiations;
- compile time, memory, KLIB/physical-binding size, and incremental rebuilds;
  and
- compiler, runtime, reflection, importer/exporter, and maintenance complexity.

Measurements select representation details, specialization policy, and
implementation priorities. They do not reduce native CLR generic identity and
direct interop to a micro-optimization. A shape that cannot yet satisfy the
complete semantic and maintenance cost remains explicitly erased rather than
receiving a misleading partial generic owner.

### 6. Can devirtualization safely accelerate the final model?

Swift-style closed-world devirtualization and Kotlin/Native Variable Type
Analysis may prove exact receivers and substitutions for direct calls, private
generic helpers, and BCL operations while the erased call remains a fallback.
This is useful incremental infrastructure and supplies comparison evidence.
It cannot recreate a discarded public CLR `!T`, change a TypeDef, or prove
open-world C# inheritance. Public owner migration still passes every gate
above and uses the one model established by the hostile spike.

## Shared adversarial comparison matrix

Any reopening must compare the accepted erased owner and the candidate typed
owner against the same sources and assertions:

1. exact reference/value/nullable/struct construction and member access;
2. invalid throwing and safe casts, including exception timing and identity;
3. stars, projections, declaration variance, widened joins, and erased calls;
4. `contains`/`containsAll` false/null/empty/throwing candidates;
5. mutable same-object state on every successful view;
6. inheritance, overrides, abstract members, interfaces, default methods, and
   C# subclassing;
7. nested/inner classes, recursive bounds, generic methods, arrays, and
   nullability;
8. KClass/KType/reflection normalization;
9. self-describing separate libraries and version-skew rejection;
10. both FIR parsers and every compatible target profile; and
11. C# compilation and execution against the supported public surface.

The matrix may be designed and committed during this architecture phase, but
production typed-owner infrastructure must not be implemented merely to make
one side pass. Each explicitly selected implementation spike must remain
bounded and must not publish a third ABI.

## What the design phase locks

Until the hostile model, admission rules, and atomic migration plan are
accepted, do not:

- emit a CLR-generic TypeDef as the implementation owner of an ordinary
  Kotlin-owned generic class;
- change the accepted delayed-use cast behavior or its tests;
- reintroduce canonical class interfaces, ancestry classifiers, generic-class
  bridge manifests, or typed-owner capability probes;
- freeze public ABI or export rules that assume the internal Kotlin class is
  CLR `C<T>`; or
- describe CLI generic capability as authorization for that representation.

This design phase does **not** block:

- Common stdlib and language-feature foundations using the accepted erased
  owner;
- callable invocation, member reflection, annotations, or contracts;
- structured CLI IR and complete support for physical generic metadata;
- imported CLR generic classes and interfaces;
- CLR-generic methods and truthful exact interface capabilities;
- explicit fail-closed .NET export facades/adapters; or
- removable private specialization whose disablement changes no supported ABI
  or behavior.

## Reopened direction and next design artifacts

The programme is explicitly reopened with truthful CLR reification as the
destination wherever the complete Kotlin contract permits it. Before the first
production owner migration, record: the hostile executable matrix,
deterministic declaration admission model, complete erased-capability ABI,
cast policy, reflection normalization, inheritance/override model, physical
binding schema, C# surface, rollback boundary, and measurement corpus. Then
amend the erased-owner ADR and migrate the canonical owner model atomically
across compiler, runtime, stdlib, KLIB/physical metadata, reflection,
export/import tooling, tests, and documentation. There is no easy-owner pilot
ABI and no mixed compatibility period for one logical owner on this pre-ABI
branch.
