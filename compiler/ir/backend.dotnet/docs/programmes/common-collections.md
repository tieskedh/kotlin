# Common collections programme

- Status: **Paused — admitted production frontier ends at the Map min/max
  adapters; the next generic-owner-dependent closure awaits the owner go/no-go**
- Current production interface authority:
  [`../decisions/generic-interface-erased-identity.md`](../decisions/generic-interface-erased-identity.md)
- Generic-owner rehearsal:
  [`../decisions/draft-adr-reified-generic-interface-owner.md`](../decisions/draft-adr-reified-generic-interface-owner.md)
- Runtime/Stdlib ownership:
  [`../decisions/runtime-and-stdlib-ownership.md`](../decisions/runtime-and-stdlib-ownership.md)

This programme owns selection of Common/generated collection source and its
dependency closures. It does not own the generic-owner representation or an
idiomatic BCL export surface.

## Direction

Kotlin Common declarations, generators, and algorithms are authoritative. The
.NET target supplies only narrow actuals and irreducible CLR operations. A
feature enters as one complete classifier/dependency family; it is not
approximated by a handwritten target method merely to advance a count.

The desired product is ordinary Kotlin collection behavior compiled through
shared source, with truthful CLR carriers underneath. Kotlin collection
identity is not replaced by `IEnumerable<T>`, `ICollection<T>`, LINQ, or another
BCL identity. Such views are separate interop/export decisions.

## Current admitted frontier

The production source set includes the currently selected Common/generated
foundations for:

- the built-in read-only and mutable collection interfaces and their ordinary
  abstract/concrete Kotlin-owned implementations;
- iterator/list-iterator behavior, factories, transformations, filtering,
  aggregation, equality, hashing, rendering, and array conversion required by
  those implementations;
- ranges/progressions and specialized signed primitive-array wrappers used by
  the selected algorithms;
- Kotlin-owned `Sequence` and `Grouping` foundations;
- stable list/object-array/signed-array sorting and selected comparison
  families;
- eager Iterable windowing and Iterable/Sequence consumer families;
- the admitted equality/distinct and natural/selector/comparator min/max
  families; and
- the selected CharSequence and Map min/max adapters.

The exact generated/source inventory is executable compiler input, not a list
maintained in this Markdown file. Completed tranche evidence is indexed under
[`../archive/README.md`](../archive/README.md).

## Generic-owner pause

Do not select another erased-owner stdlib leaf merely to bypass the current
architecture work. The source-built Runtime/Stdlib rehearsal has exposed the
physical-authority and carrier-provenance questions now owned by the generic-
owner draft. The next collection closure is recomputed only after that
architecture checkpoint chooses GO, CONSTRAIN, or NO-GO and records which
generic interface/class families are truthful.

The pause does not make the current erased owner permanent. It is the binding
production baseline until an atomic cutover. Conversely, test-only natural,
exact, or semantic interface families do not authorize production collection
migration one interface at a time.

After the checkpoint:

1. recompute the next complete Common/generated dependency closure from source;
2. classify every generic owner under the selected production representation;
3. add any missing shared prerequisite before the leaf that consumes it;
4. produce Runtime/Stdlib, installed, portable, and separate-consumer products;
5. run the semantic/metadata/C# matrix on Framework 4.8 and .NET 10; and
6. update this frontier without appending a tranche diary.

Optional BCL adapters and C# conveniences are not “the next collection leaf.”
They may be developed independently under an interop/export ADR after the
Kotlin identity and mutation semantics are fixed.

## Selection rules

- Select declarations from Common/generator ownership and dependency closure,
  never by convenient filename or public-name matching in the backend.
- Preserve Common exception type/message, evaluation order, mutation,
  aliasing, subview, iterator, equality, hash, and rendering behavior.
- Keep collection special bridges in the shared special-bridge policy. Do not
  turn them into collection-name branches in CLR representation.
- Do not infer `IEnumerable<T>` or BCL collection identity from a Kotlin
  collection classifier.
- Do not let value-type CLR variance reject legal Kotlin widened views.
- Do not fabricate `Collection<object>`, `List<object>`, or another constructed
  view when only an exact/capability view exists.
- Keep one receiver identity and one authoritative state across natural,
  exact, projected, star, and semantic operations.
- Source-built and packaged fallback products must use the same canonical
  source manifest and produce identical logical/physical output for a fixed
  compiler and profile.
- Publication fails on a missing, evicted, ambiguous, or unbound declaration;
  it never silently emits a smaller Stdlib.

## Explicitly separate work

The following require their own substrate or decision and are not implied by
the admitted frontier:

- Random/entropy-backed operations and shuffle;
- unsigned value classes, arrays, ranges, and algorithms;
- unselected binary-search and specialized sorting families;
- reified/reflection-dependent operations outside the admitted inline graph;
- broad BCL collection/enumeration adapters;
- concurrency-aware collection implementations; and
- generic-owner shapes outside the accepted/rehearsed grammar.

## Completion gate

Each admitted family must prove, where applicable:

- empty, singleton, multiple, nullable, primitive, reference, widened, star,
  and projected behavior;
- hostile user Iterator/Collection/List/Map implementations and exact dispatch
  counts;
- exception, evaluation, equality, hash, rendering, mutation, aliasing, and
  backed-view semantics;
- exact array/vector identity and rejection of invalid value-vector covariance;
- direct, fallback, installed, portable, and separate Kotlin consumers;
- ordinary C# calls/implementations for the selected public physical surface;
- deterministic physical facades with no duplicate bodies or unbindable KLIB;
  and
- both FIR parsers and every compatible Framework 4.8/.NET 10 profile pairing.

The programme is complete when the normal selected Common/generated collection
product compiles without a bootstrap allowlist or target algorithm forks, and
optional BCL adapters remain a distinct surface.
