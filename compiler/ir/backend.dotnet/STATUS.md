# Kotlin/.NET development status

Read [`AGENTS.md`](AGENTS.md) before changing Kotlin/.NET code. It is the
self-contained bootstrap contract; this file owns only current branch,
verification, and work state.

## Current branch

- Branch: `dotnet`
- Upstream base: `origin/master` at `733a49b39`
- Last completed feature: classified Common `CharSequence` carrier
- Maturity: high-quality pre-ABI prototype; no third-party binary compatibility
  is promised

## Current green gate

The last semantic head passed:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest --rerun -q --no-daemon
```

The JUnit audit covered 16 fresh XML files and 938 tests:

- 840 FIR, IL-text, and box tests
- 21 generated CLI tests
- 77 library-integration tests
- zero failures, errors, or skips

Focused evidence additionally covers component-complete packed-KLIB loading,
same- and cross-library inlining from prepared and main IR, all three KLIB
inliner modes, mutable capture and non-local control flow, compiler ABI and
friend access, stdlib-free diagnostics, reproducible direct/fallback stdlib
IR, explicit reified/suspend rejection, and every target/runtime profile. The
collection product now also proves empty Collection fast paths, exact
short-circuit and traversal counts, nullable/widened predicates, reverse List
search, inlined separate consumers, and direct CIL execution of all six
physical fallback methods on Framework CLR and CoreCLR. The classified
`CharSequence` carrier additionally proves unchanged `System.String` and
custom-implementation identity, shared operation/cast/type-test
classification, erased physical CLR bounds with authoritative KLIB bounds,
portable Kotlin-library consumption on both runtimes, and handwritten C#
implementation through the runtime manifest.

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

No implementation slice is half-landed. Logical `CharSequence` values now use
the accepted classified object carrier: raw strings keep `System.String`
identity, Kotlin and explicit C# implementations occupy the runtime capability
interface, and every polymorphic operation, cast, and type test uses the same
two-arm classifier. KLIB retains the generic bound while the incompatible CLR
marker constraint is omitted. Runtime surface level 10 records this ABI.
Builder storage is now selected as a Kotlin-owned wrapper over private BCL
storage, but implementation is parked: the exact Common source closure reaches
the public contract DSL, including annotation classes and `InvocationKind`,
whose general representations are not yet selected. The previously completed
Common collection predicates and ordinary inline-function boundary remain
intact; reified and suspend inline are still explicit errors.

## Open architectural blockers

- Exact Common `AbstractCollection`/`AbstractList` production still needs the
  remaining `Appendable`/`StringBuilder` and typed collection-to-array
  closures; do not fork their algorithms into .NET. The builder closure
  transitively requires the complete public contract DSL and therefore the
  parked enum and annotation-class representation programmes.
- An inline body in library A can currently bind built-ins and A-owned
  declarations. Arbitrary calls from that body into a distinct Kotlin library
  B need the selected .NET assembly graph as an explicit non-linking resolver
  input before that breadth is claimed.
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

1. Design and implement typed collection-to-array actuals that preserve the
   requested CLR vector element type.
2. Audit and select the enum and annotation-class representations required by
   the exact Common contract DSL; do not create builder-only stubs.
3. Actualize the selected complete Common `Appendable`/`StringBuilder` and
   generated `joinTo`/`joinToString` closure once that foundation exists.
4. Compile the exact Common `AbstractCollection`/`AbstractList` sources once
   both remaining closures are complete.

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
