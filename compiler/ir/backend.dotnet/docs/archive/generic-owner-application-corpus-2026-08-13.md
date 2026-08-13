# Generic-owner paired application corpus — 2026-08-13

## Outcome

The generic-owner reopening now has one closed, reproducible application
corpus which places the accepted production-erased owner and the
record-driven candidate `C<T>` model beside the same hostile Kotlin source.
PSI and LightTree produce and execute the corpus on Framework CLR and
CoreCLR. This is the first paired product/correctness baseline; it is not yet
the representative performance evidence required for owner migration.

Each profile bundle contains:

- the exact multi-module Kotlin application source;
- the actual production-erased Kotlin producer and separately compiled Kotlin
  consumer;
- a direct C# application compiled against that erased producer;
- the snapshot-derived candidate producer, its schema-7 physical-family
  record, generated C# source, and separately compiled candidate consumer;
- the exact target Runtime and Stdlib; and
- a closed manifest with a SHA-256 fingerprint for every file.

The CoreCLR form also records the three runtime configurations and pinned
`global.json`. A bundle is exported only through an explicit test property to
a required-empty directory. The verifier rejects a missing, stale, extra, or
unfingerprinted entry.

## What the applications prove

The direct C# erased application uses the real Kotlin-produced `lib` assembly;
there is no adapter, generated facade, or test substitute between C# and the
Kotlin class. It constructs, mutates, subclasses, overrides, and calls the
production owner with:

- `Guid`, `DateTime`, `decimal`, an enum, `ValueTuple<int, string>`, and a
  user-defined struct;
- `null`, strings, and sequentially different value types in one owner;
- reference and nullable-value arrays with identity preservation;
- the genuine method-generic `relay<R>` entry; and
- a two-level C# subclass hierarchy.

Reflection deliberately pins the current C# surface. The Kotlin generic owner
is physically arity zero; its constructor, state, read, and write positions
are `object`, and its broad array position is `System.Array`. The independent
`relay<R>` remains an ordinary CLR method generic. This is callable direct
interop, but it also records exactly why the erased owner is weaker and less
idiomatic for C# than the candidate `C<T>` surface.

The candidate application is generated from the compiler snapshot and
schema-7 family record. It exercises the finite exact/fallback construction
table, typed and semantic dispatch, one shared state, arrays, reflection
normalization, a compiler-derived Kotlin subclass, and a further C# generic
subclass. Both applications remain separate products; the candidate does not
replace or alter production emission.

## Reproducibility findings

The first repeated production audit found a random test temporary directory
inside the embedded KLIB `strings.knt`. The .NET test environment now supplies
the same relative-path-base contract as the other KLIB-producing backends.
Two repeated PSI CoreCLR exports then produced byte-identical complete
bundles.

PSI and LightTree intentionally retain slightly different source-location
encoding in `default/ir/bodies.knb`: the observed hostile producer differs by
one encoded byte and one byte of entry length. The verifier does not disguise
that as raw DLL identity. Instead it requires all of the following to be
exactly equal between frontends:

- complete CLR metadata;
- every executable method body, local signature, stack/init flag, and
  exception region;
- every non-KLIB managed resource;
- every KLIB entry except the parser-owned IR body/source-location stream;
- the physical-family record and generated candidate source; and
- every separately compiled candidate, Kotlin, and C# consumer product.

The raw manifest retains each parser's distinct body hash. The verifier
excludes that complete parser-owned body stream from cross-parser byte
equality; it does not pretend to canonicalize or prove every unused serialized
inline body. Exact executable CLR content, every other KLIB item, the generic-
owner snapshot/family, and the separately compiled downstream product
constrain the fixture strongly, but a future release-level KLIB
reproducibility gate still needs a semantic body canonicalizer. An IL, ABI,
resource, non-body KLIB, binding, or downstream-consumption difference fails
closed today.

The installed Framework `csc.exe` predates Roslyn and cannot produce
deterministic binaries. Framework application products are therefore compiled
by the pinned modern Roslyn driver with explicit CLR 4 `mscorlib`, `System`,
and `System.Core` references, then executed on the real Framework CLR. This
removed per-run MVID/header drift without changing the target runtime.

## Verification

The canonical command is:

```text
pwsh compiler/ir/backend.dotnet/tools/verify-generic-owner-applications.ps1
```

It runs PSI/LightTree × CoreCLR/Framework CLR separate compilation, validates
each manifest and closed file set, performs the frontend-equivalence audit,
then executes the candidate, erased Kotlin, and direct erased C# applications
again from each verified profile bundle. A single profile can be selected
with `-Profiles net10` or `-Profiles net48`; `-ExistingBundle` verifies and
executes a previously recorded bundle.

The focused matrix completed four tests with zero failures, errors, or skips.
Both profile comparisons and all final application executions passed.

## Remaining gate

This corpus closes a correctness and physical-surface prerequisite. It does
not call this hostile fixture a representative real application, does not yet
measure erased versus candidate compile time, startup, throughput, allocation,
working set, boxing, code/metadata size, incremental rebuilds, or bridge
crossings, and does not authorize production `C<T>`. Those measurements are
the next bounded foundation and must use these exact paired products rather
than a new handwritten model.
