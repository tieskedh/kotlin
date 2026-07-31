# Kotlin/.NET development status

Read [`AGENTS.md`](AGENTS.md) before changing Kotlin/.NET code. It is the
self-contained bootstrap contract; this file owns only current branch,
verification, and work state.

## Current branch

- Branch: `dotnet`
- Upstream base: `origin/master` at `733a49b39`
- Last completed feature: shared retained foreign-CLR carrier and FIR-owned importer boundary
- Maturity: high-quality pre-ABI prototype; no third-party binary compatibility
  is promised

## Current green gate

The last semantic head passed:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest --rerun -q --no-daemon
```

The JUnit audit covered 16 fresh XML files and 925 tests:

- 834 FIR, IL-text, and box tests
- 21 generated CLI tests
- 70 library-integration tests
- zero failures, errors, or skips

The architecture slice additionally passed focused compilation and dependency
analysis for both new modules. Its adversarial carrier test proves exact
assembly, TypeDef, MethodDef, Property, getter, and setter identity and rejects
wrong owners plus copied physical rows.

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
- `:compiler:ir:backend.dotnet` owns IR lowering, CIL mapping/emission, and
  backend product construction.
- `cli-base` owns the .NET content-root carrier; .NET compilation no longer
  represents CLR roots as JVM classpath roots.
- Common and generated stdlib sources remain semantically authoritative.
  .NET supplies narrow actuals and irreducible CLR operations.
- Kotlin-produced libraries are self-describing DLLs. KLIB remains the exact
  Kotlin declaration contract; CLR metadata and standard attributes provide
  the truthful physical and foreign-language view.

## Active state

No implementation slice is half-landed. The foreign CLR FIR provider and its
Kotlin nullability/contract projection now live in `fir-dotnet`; CLI only
supplies the selected assemblies. FIR attaches a V1 carrier containing direct
references to the selected assembly and objective metadata rows. Backend
matches that version exhaustively and binds those exact references without a
second classpath or display-name lookup. The admitted declaration grammar,
Kotlin semantics, and physical ABI are unchanged.

## Open architectural blockers

- Exact Common `AbstractCollection`/`AbstractList` production needs generic
  inline support, `CharSequence`/`Appendable`/`StringBuilder`, and typed
  collection-to-array support; do not fork their algorithms into .NET.
- KLIB-in-DLL and physical ABI codecs still need a neutral serialization owner
  before frontend, tooling, or packaging gains another consumer.
- Broad CLR property/member-state enhancement, `ref`/`out`, events, and
  collection-shaped params each require separate Kotlin-stability decisions.
- Foreign C# `Nullable<T>` signatures are nominal generic instantiations and
  remain outside the closed primitive importer until constructed-type identity
  is retained from the selected assembly graph through backend binding.
- Gate A and ABI-freeze work remain open; current prototype identities may be
  corrected rather than compatibility-shimmed.

## Next bounded work

1. Continue CLR annotation/import interoperability only through exact standard
   metadata mappings that preserve Kotlin smart-cast and mutability rules.
2. Extract a neutral KLIB-in-DLL and physical ABI serialization owner before a
   second frontend, tooling, or packaging consumer appears.
3. Select another exact non-inline Common collection family only after its full
   type/helper closure and cross-profile product behavior are documented.

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
