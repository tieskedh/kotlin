# Kotlin/.NET development status

Read [`AGENTS.md`](AGENTS.md) before changing Kotlin/.NET code. It is the
self-contained bootstrap contract; this file owns only current branch,
verification, and work state.

## Current branch

- Branch: `dotnet`
- Upstream base: `origin/master` at `733a49b39`
- Last completed feature: selected-dependency-graph ordinary inline closure
- Maturity: high-quality pre-ABI prototype; no third-party binary compatibility
  is promised

## Current green gate

The last semantic head passed:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest --rerun -q --no-daemon
```

The JUnit audit covered 16 fresh XML files and 939 tests:

- 840 FIR, IL-text, and box tests
- 21 generated CLI tests
- 78 library-integration tests
- zero failures, errors, or skips

Focused evidence additionally covers component-complete packed-KLIB loading,
same- and cross-library inlining from prepared and main IR, all three KLIB
inliner modes, mutable capture and non-local control flow, compiler ABI and
friend access, stdlib-free diagnostics, reproducible direct/fallback stdlib
IR, explicit reified/suspend rejection, and every target/runtime profile. The
selected-graph closure additionally proves that an inline body from library A
binds exact public declarations and a nested inline body from explicitly
selected library B without a general linker; surviving B calls use B's exact
physical assembly while fully inlined A disappears as a runtime dependency.
Both prepared-IR and main-IR consumers reject an omitted B with named unbound
signatures before target lowering and leave no artifact. The
collection product now also proves empty Collection fast paths, exact
short-circuit and traversal counts, nullable/widened predicates, reverse List
search, inlined separate consumers, and direct CIL execution of all six
physical fallback methods on Framework CLR and CoreCLR. The classified
`CharSequence` carrier additionally proves unchanged `System.String` and
custom-implementation identity, shared operation/cast/type-test
classification, erased physical CLR bounds with authoritative KLIB bounds,
portable Kotlin-library consumption on both runtimes, and handwritten C#
implementation through the runtime manifest. The collection-to-array closure
additionally proves exact Common iteration, erased and typed results, nullable
and value elements, undersized allocation, oversized and empty destination
identity, non-Java tail preservation, covariant runtime vector identity,
negative-size failure, and hostile inaccurate-size behavior on Framework CLR
and CoreCLR.

## Current architecture

- `:core:language.targets.dotnet` owns the logical .NET platform and the
  `net48`, `netstandard2.0`, and `net10.0` target vocabulary.
- `:compiler:config.dotnet` owns generated compiler keys and target-policy
  validation without depending on FIR, IR, backend, or CLI code.
- `:compiler:frontend.common.dotnet` owns objective PE/ECMA-335 facts and
  physical CLR validation; FIR owns Kotlin interpretation.
- `:compiler:dotnet.imports` owns the versioned, self-validating in-process
  carrier for one already-selected foreign CLR declaration.
- `:compiler:fir:fir-dotnet` owns foreign Kotlin projection and lazy FIR symbol
  construction without depending on backend or CLI implementation packages.
- `:compiler:fir:fir2ir:dotnet-backend` owns the narrow target-specific IR
  overridability rule for retained flexible CLR array declarations.
- `:compiler:ir:backend.dotnet` owns IR lowering, CIL mapping/emission, and
  backend product construction.
- `:compiler:ir:serialization.dotnet` owns .NET KLIB IR serialization and the
  logical IR mangler shared with backend identity mapping.
- `cli-base` owns the .NET content-root carrier; .NET compilation no longer
  represents CLR roots as JVM classpath roots.
- Common and generated stdlib sources remain semantically authoritative.
  .NET supplies narrow actuals and irreducible CLR operations.
- Kotlin-produced libraries are self-describing DLLs. KLIB remains the exact
  Kotlin declaration contract; CLR metadata and standard attributes provide
  the truthful physical and foreign-language view.

## Active state

No implementation slice is half-landed. Ordinary non-reified inline bodies now
bind exact signatures throughout the complete frontend-selected dependency
graph. Resolution remains non-linking, and an incomplete graph fails at the
post-inline/pre-target-lowering boundary instead of crashing an arbitrary
lowering. The non-reified Common collection-to-array closure uses the exact
shared loops. Its narrow CLR actual reproduces a supplied vector's runtime
element type, retains sufficiently large destination identity without JVM's
Java-specific tail terminator, and keeps public reified `toTypedArray` outside
the admitted surface. The backend's
explicit erased-object cast to an open type parameter uses `unbox.any`; safe
generic casts remain unsupported. Builder storage is selected as a Kotlin-owned
wrapper over private BCL storage, but implementation is parked: the exact Common
source closure reaches the public contract DSL, including annotation classes
and `InvocationKind`, whose general representations are not yet selected. The
classified `CharSequence` carrier, Common collection predicates, and ordinary
inline-function boundary remain intact; reified and suspend inline are still
explicit errors.

The reified audit is complete. Shared IR substitution is ready, but public
reified support stays parked because Kotlin-owned generic-class type tests and
casts require an erased runtime identity over physically closed CLR carriers.
Physically exact non-generic reference casts are now complete for Kotlin
classes/interfaces, imported CLR interfaces, strings, `Any`, primitive-array
wrappers, and exact CLR vectors without admitting closed generic instances.
Boxed-scalar casts are now complete for all eight selected Common primitives:
exact boxed identity, nullable unboxing for checked nullable casts, and
`isinst` plus nullable unboxing for safe casts, with no numeric-conversion or
value-class widening.

## Open architectural blockers

- Exact Common `AbstractCollection`/`AbstractList` production still needs the
  remaining `Appendable`/`StringBuilder` closure; do not fork its algorithms
  into .NET. Modern enums, the public contract DSL, builders, Common abstract
  collection bases, and `EnumEntries` form one atomic source bootstrap cluster;
  do not break it with target substitutes or one-enum exceptions.
- KLIB-in-DLL and physical ABI codecs still need neutral serialization owners
  as those additional compiler/tooling consumers appear.
- Broad CLR property/member-state enhancement, `ref`/`out`, events, and
  collection-shaped params each require separate Kotlin-stability decisions.
- Foreign C# `Nullable<T>` signatures are nominal generic instantiations and
  remain outside the closed primitive importer until constructed-type identity
  is retained from the selected assembly graph through backend binding.
- Gate A and ABI-freeze work remain open; current prototype identities may be
  corrected rather than compatibility-shimmed.

## Next bounded work

1. Continue the reversible reified prerequisites by adversarially closing the
   concrete type-test/array-intrinsic matrix; do not admit Kotlin-owned
   generic-class casts before their erased runtime view is selected.
2. Audit and select the atomic enum/annotation/contracts/builder/abstract-
   collections/`EnumEntries` bootstrap cluster; do not create builder-only or
   one-enum stubs.
3. Actualize the selected complete Common `Appendable`/`StringBuilder` and
   generated `joinTo`/`joinToString` closure once that foundation exists.
4. Compile the exact Common `AbstractCollection`/`AbstractList` sources once
   the remaining builder closure is complete.

## Navigation

- Current sequencing and release gates:
  [`docs/programmes/way-forward.md`](docs/programmes/way-forward.md)
- Documentation and evidence index:
  [`docs/README.md`](docs/README.md)
- Collections programme:
  [`docs/programmes/common-collections.md`](docs/programmes/common-collections.md)
- Architecture ownership audit:
  [`docs/programmes/compiler-architecture.md`](docs/programmes/compiler-architecture.md)
- Durable representation decisions: [`docs/decisions`](docs/decisions)

Update this file when branch state, the latest full gate, active work, blockers,
or the next bounded items change. Put rationale in ADRs, future ordering in the
way forward, chronological history in Git, and executable evidence in tests.
