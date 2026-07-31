# Kotlin/.NET development status

Read [`AGENTS.md`](AGENTS.md) before changing Kotlin/.NET code. It is the
self-contained bootstrap contract; this file owns only current branch,
verification, and work state.

## Current branch

- Branch: `dotnet`
- Upstream base: `origin/master` at `733a49b39`
- Last semantic feature: `734fcf98a` (`[DotNet] Add signed narrow scalars`)
- Maturity: high-quality pre-ABI prototype; no third-party binary compatibility
  is promised

## Current green gate

The last semantic head passed:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest --console=plain
```

The JUnit audit covered 16 XML files and 917 tests:

- 828 FIR, IL-text, and box tests
- 21 generated CLI tests
- 68 library-integration tests
- zero failures, errors, or skips

The signed-narrow slice additionally passed focused execution in all four
PSI/LightTree and Framework/CoreCLR box lanes. Its portable product gate proved
exact `int8`/`int16` overloads, nullable and generic carriers, Kotlin library
round trips, direct C# consumption, and foreign CLR `sbyte`/`short` calls on
both supported runtime profiles.

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

No implementation slice is half-landed. Kotlin `Byte` and `Short` now use exact
CLR `int8`/`System.SByte` and `int16`/`System.Int16` carriers across operations,
nullability, generics, boxing, libraries, and the closed foreign primitive
import slice. Common arithmetic, overflow, equality, hash, and rendering remain
authoritative. `Float` is the remaining scalar prerequisite before the complete
Common `Iterable.sum()` overload family can land without a target-owned fork.

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

1. Close Kotlin `Float` as exact CLR `float32`/`System.Single`, including Common
   equality, hash, conversion, and shortest-roundtrip rendering semantics.
2. Once all six scalar prerequisites are green, adopt the complete generated
   Common `Iterable.sum()` overload family without copying its algorithms.
3. Create the shared retained-declaration carrier seam, then split FIR policy
   from backend binding without moving physical CLR loading into either.
4. Continue CLR annotation/import interoperability only through exact standard
   metadata mappings that preserve Kotlin smart-cast and mutability rules.

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
