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

- checked `as`, safe `as?`, and suppressed/unsuppressed unchecked diagnostics;
- concrete reference, value, nullable-value, and user-defined struct arguments;
- open method type parameters and nested generic constructions;
- null receivers and nullable cast targets; and
- the exact physical exception and logical Kotlin classification.

`as?` must still return `null` rather than leak `InvalidCastException` when the
ordinary safe-cast contract applies.

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
generic interfaces; multi-level Kotlin and C# inheritance/overrides; checked
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
producer records the overridden logical key. A production-inert version-6
family artifact now proves the first cross-assembly link: it is fingerprinted
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

Open-nullable construction now has a bounded consumer-side record rather than
new producer-schema claims. It accepts only finite concrete final-compilation
runtime roots, derives every exact owner/constructor from the decoded producer,
and returns the semantic capability. Exact value roots use
`C<Nullable<V>>`, exact references use `C<R>`, and already-nullable values do
not become nested nullable types. One mandatory `C<object>` fallback handles
unlisted value/reference roots honestly. The record has no unbounded reflection
mode. Both CLRs execute exact and fallback paths with one state and classifier;
a NativeAOT control passes managed analysis and reaches the absent Windows
platform linker. Full native link/run and representative measurements remain
the construction gate.

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
2. invalid checked and safe casts, including exception timing and identity;
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
