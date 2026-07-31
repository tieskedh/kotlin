# Kotlin/.NET development status

Read [`AGENTS.md`](AGENTS.md) before changing Kotlin/.NET code. It is the
self-contained bootstrap contract; this file owns only current branch,
verification, and work state.

## Current branch

- Branch: `dotnet`
- Upstream base: `origin/master` at `733a49b39`
- Last completed feature: ordinary foreign CLR vectors with symmetric Kotlin implementation
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
- `:compiler:fir:fir2ir:dotnet-backend` owns the narrow target-specific IR
  overridability rule for retained flexible CLR array declarations.
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

No implementation slice is half-landed. The closed foreign interface grammar
now admits ordinary one-dimensional zero-based vectors over the supported
signed primitive, `string`, and `object` elements in parameters, returns, and
non-indexed properties. Kotlin sees `Array<E>` with JVM-shaped foreign
flexibility while physical binding remains the exact CLR `E[]`; Kotlin
primitive-array wrappers are not conflated with native vectors. Kotlin classes
can implement the same retained foreign TypeDef/MethodDef slots, and C# reverse
dispatch is verified on every supported runtime profile. Primitive
`ParamArray`, unsigned vectors, rectangular arrays, and unsupported element
grammars remain withheld atomically.

## Open architectural blockers

- Exact Common `AbstractCollection`/`AbstractList` production needs generic
  inline support, `CharSequence`/`Appendable`/`StringBuilder`, and typed
  collection-to-array support; do not fork their algorithms into .NET.
- Inline support needs component-complete embedded KLIB loading, target IR
  serialization, the shared pre-serialization/inlining phases, and Common's
  `SharedVariableBox`; the current metadata-only embedded loader deliberately
  discards serialized IR.
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

1. Complete the embedded KLIB component seam and add target IR serialization
   without moving logical identity out of KLIB.
2. Integrate the shared two-stage IR inliner and Common shared-variable box,
   preserving supported `-Xklib-ir-inliner` modes rather than implementing an
   intra-module-only shortcut.
3. Admit the first non-reified Common inline collection helpers only with
   same-module, separate-DLL, non-local-return, capture, compiler-ABI, and
   cross-profile adversarial evidence.

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
