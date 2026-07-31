# Kotlin/.NET development status

Read [`AGENTS.md`](AGENTS.md) before changing Kotlin/.NET code. It is the
self-contained bootstrap contract; this file owns only current branch,
verification, and work state.

## Current branch

- Branch: `dotnet`
- Upstream base: `origin/master` at `733a49b39`
- Last semantic feature: `bbf8ee876` (`[DotNet] Add Common indexed optional
  collection access`)
- Maturity: high-quality pre-ABI prototype; no third-party binary compatibility
  is promised

## Current green gate

The last semantic head passed:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest --rerun -q --no-daemon
```

The fresh JUnit audit covered 16 XML files and 906 tests:

- 818 FIR, IL-text, and box tests
- 21 generated CLI tests
- 67 library-integration tests
- zero failures, errors, or skips

The indexed optional collection slice additionally passed focused execution in
all four PSI/LightTree and Framework/CoreCLR box lanes, plus direct, installed,
separate-consumer, and portable stdlib product checks on every compatible
runtime profile.

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

No implementation slice is half-landed. The authoritative Common
`Iterable.elementAtOrNull` and `List.getOrNull` bodies are now part of every
.NET stdlib product. The shared `RangeContainsLowering` removes their
`0..<size` membership check before .NET for-loop lowering, matching the mature
targets without copying the algorithm. The next work is selection of one
bounded semantic slice through the compare, document, implement, and
adversarially test workflow in `AGENTS.md`.

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
- Gate A and ABI-freeze work remain open; current prototype identities may be
  corrected rather than compatibility-shimmed.

## Next bounded work

1. Select the next exact non-inline Common collections family only after
   proving its complete generator/source dependency closure.
2. Create the shared retained-declaration carrier seam, then split FIR policy
   from backend binding without moving physical CLR loading into either.
3. Continue CLR annotation/import interoperability only through exact standard
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
