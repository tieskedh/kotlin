# Kotlin/.NET development status

Read [`AGENTS.md`](AGENTS.md) before changing Kotlin/.NET code. It is the
self-contained bootstrap contract; this file owns only current branch,
verification, and work state.

## Current branch

- Branch: `dotnet`
- Upstream base: `origin/master` at `733a49b39`
- Last completed feature: complete generated Common signed `Iterable.sum()` family
- Maturity: high-quality pre-ABI prototype; no third-party binary compatibility
  is promised

## Current green gate

The last semantic head passed:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest --rerun -q --no-daemon
```

The JUnit audit covered 16 XML files and 924 tests:

- 834 FIR, IL-text, and box tests
- 21 generated CLI tests
- 69 library-integration tests
- zero failures, errors, or skips

The numeric-sum slice additionally passed focused `net10.0`, `net48`, and
portable `netstandard2.0` product tests. They proved direct, packaged-fallback,
installed, and separate-consumer binding for all six logical overloads; exact
`sumOfByte` through `sumOfDouble` physical names; integer wraparound; Float
encounter-order rounding, NaN, and empty positive zero; and one-iterator
traversal on both supported runtimes.

## Current architecture

- `:core:language.targets.dotnet` owns the logical .NET platform and the
  `net48`, `netstandard2.0`, and `net10.0` target vocabulary.
- `:compiler:config.dotnet` owns generated compiler keys and target-policy
  validation without depending on FIR, IR, backend, or CLI code.
- `:compiler:frontend.common.dotnet` owns objective PE/ECMA-335 facts and
  physical CLR validation; FIR owns Kotlin interpretation.
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

No implementation slice is half-landed. The complete signed generated Common
`Iterable.sum()` family now lives in `Kotlin.Stdlib`: Common owns the six source
bodies and logical `sum` declarations, while the self-describing physical
binding records the exact erased CLR names `sumOfByte` through `sumOfDouble`.
The canonical Kotlin collection carrier remains unchanged; no LINQ/BCL
algorithm or target-authored body was introduced. Kotlin `Byte`, `Short`, and
`Float` retain their exact CLR scalar carriers across this new library boundary.

## Open architectural blockers

- Exact Common `AbstractCollection`/`AbstractList` production needs generic
  inline support, `CharSequence`/`Appendable`/`StringBuilder`, and typed
  collection-to-array support; do not fork their algorithms into .NET.
- The foreign CLR provider still needs a shared retained-declaration carrier
  seam before it can move into a FIR-owned .NET module.
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

1. Create the shared retained-declaration carrier seam, then split FIR policy
   from backend binding without moving physical CLR loading into either.
2. Continue CLR annotation/import interoperability only through exact standard
   metadata mappings that preserve Kotlin smart-cast and mutability rules.
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
