# Draft ADR: Bootstrap Kotlin/.NET target stdlib assembly

- Status: **Draft candidate; first implementation slice is present in the prototype**
- Date: 2026-07-17
- Scope: physical ownership and bootstrap production of Kotlin standard-library implementations

This is a repository-local decision record for the experimental .NET backend. The `dotnet` branch
is a proof of concept; this document does not claim a public Kotlin or Kotlin/.NET commitment.

## Context

`Kotlin.Runtime.dll` already owns stable language/runtime ABI types such as erased callable
identities and the canonical/typed collection-interface views, Kotlin-owned exception identities,
and compiler support helpers. It also contained a handwritten IL `ArrayIterator` only because the
backend had no target stdlib assembly
and no general compiler-generated Iterator bridges.

Those bridges now exist. Keeping an ordinary collection implementation in the runtime would blur
the boundary between stable execution identity and library policy, while emitting it into every
program would duplicate its type identity. A CLR assembly by itself is not a Kotlin compile-time
library: the frontend needs Kotlin declaration metadata in addition to the physical methods.

## Decision

The prototype introduces a second reserved platform assembly:

```text
Kotlin.Stdlib, Version=1.0.0.0, Culture=neutral, PublicKeyToken=null
```

ABI major 1 is unsigned. AssemblyVersion stays fixed for compatible ABI-1 builds; product/package
versions belong outside CLR AssemblyVersion. `Kotlin.Stdlib.dll` references `Kotlin.Runtime.dll`
and is produced as `net48`, `netstandard2.0`, or `net10.0`. The variants share Kotlin declarations
and logical identities but may use profile-specific physical implementations. A consumer selects
an exact-profile pair first and may use the portable `netstandard2.0` pair from either executable
profile; see `draft-adr-dotnet-library-target-profile.md`.

Ownership is split as follows:

- `Kotlin.Runtime.dll` owns Kotlin callable identities, canonical and typed collection-interface
  views, exceptions, and narrowly shared compiler/runtime services;
- `Kotlin.Stdlib.dll` owns ordinary Kotlin library implementations; and
- the user assembly owns only user declarations and calls into those platform assemblies.

The first stdlib implementations are generic Kotlin `ArrayIterator<T>` and `ArrayIterable<T>`,
the common-shaped `EmptyIterator`/`EmptyList` singleton pair, `emptyList<T>()`, and the top-level
`Iterable<T>`/`List<T>` `first()` and `last()` operations. The classes and objects are compiled
through the ordinary pipeline and receive the same compiler-generated canonical and typed
MethodImpl bridges as user implementations. Explicit array `iterator()`
calls a generic compiler-facing factory in `Kotlin.Stdlib`; the
handwritten `System.Array` iterator is removed from `Kotlin.Runtime`. Array `asIterable()`
calls the corresponding generic factory, whose private view stores the original vector and creates
a fresh private `ArrayIterator<T>` per request through ordinary Kotlin code. Direct array `for` loops remain
allocation-free indexed loops. User calls to `first()` and `last()` target the stable physical
facade `[Kotlin.Stdlib]Kotlin.Collections.CollectionsKt`; their implementations use guarded typed
capabilities with universal canonical fallbacks and therefore work equally for stdlib and
user-defined producers. The Iterable overloads retain the common runtime List dispatch, so a List
uses indexed access and its List-specific empty exception path.

`EmptyIterator : ListIterator<Nothing>` and `EmptyList : List<Nothing>` remain non-generic
singletons, preserving one reference across every logical element view. Their typed CLR
capabilities close over `[Kotlin.Runtime]Kotlin.Nothing`; canonical bridges remain the universal
fallback for `List<Int>`, `List<String>`, and other views. Empty arrays return this singleton via
the common `asIterable()` fast path. `RandomAccess` is a public inert target-stdlib marker.
The internal `kotlin.io.Serializable` actual is also inert and intentionally has no relationship
to `System.Runtime.Serialization.ISerializable` or a promised .NET serialization format.

The implementation classes, objects, and constructors are private to the stdlib assembly;
`RandomAccess` is the public marker exception. Generated user assemblies instead call
Kotlin-internal, metadata-public generic factory methods and public stdlib functions on
`Kotlin.Collections.CollectionsKt`. The factory names and signatures are compiler/stdlib ABI;
the implementation class names are not. This follows JVM's public-metadata iterator helper around
a private `ArrayIterator` and JS's compiler-used internal array-iterator helpers.

A separately compiled consumer uses a paired artifact:

```text
Kotlin.Stdlib.klib  -> Kotlin declaration/type metadata used by FIR
Kotlin.Stdlib.dll   -> CLR implementations referenced by emitted IL
```

The metadata KLIB uses the existing metadata-library reader. Its manifest binds the complete
unsigned ABI-1 identity (`dotnet_assembly_name=Kotlin.Stdlib`, version `1.0.0.0`, neutral culture,
and null public-key token), `dotnet_assembly_file=Kotlin.Stdlib.dll`, and
`dotnet_library_tfm=netstandard2.0`. This binding is what turns that one metadata dependency into a physical CLR
reference; arbitrary KLIBs remain compile-time-only. The compiler requires the named sibling DLL
and copies it beside an executable consumer. The metadata encoding is currently the common KLIB
encoding because there is no durable .NET KLIB platform kind yet; the custom library-TFM binding
records the portable CLR API contract without claiming that .NET Standard is an executable runtime.

## Relationship to the stdlib generator

The mature stdlib generator currently emits Common, JVM, JS, WASM, and Native sources; it has no
.NET target. The Iterable/List `first()` and `last()` overloads are owned by the common
`Elements.f_first` and `Elements.f_last` templates, not by platform-specific implementations. The
bootstrap source retains their runtime List dispatch now that the target has a coherent List ABI.
The generated `List.last()` spells its index through the separately generated `lastIndex`
extension property; this narrow extraction expands that property to its exact `size - 1` body
because generic extension properties remain outside the current backend surface.
`EmptyIterator`, `EmptyList`, and `emptyList()` follow the ordinary common
`kotlin.collections.Collections.kt` source shape. The only target decisions are the physical
Nothing carrier plus inert `RandomAccess`/internal Serializable actuals; no singleton intrinsic
or hand-written collection bridge is introduced.

Adding a `KotlinTarget.DotNet` generator entry now would produce a broad corpus that this POC
cannot compile and would falsely suggest a supported target surface. `first()` and `last()` prove
straight-line and looping generic common bodies respectively; adding more piecemeal operations
would not prove standalone dependency consumption. Until standalone stdlib compilation can consume
generated common sources, each bootstrap extraction must identify its generator/template origin
and preserve its semantics. The eventual .NET stdlib build should compile the common generated
corpus plus narrowly generated .NET actuals rather than maintain a permanent handwritten fork.

## Major-target production precedent

JS and Wasm select either KLIB serialization or executable linking in `WebCliPipeline`; an
ordinary executable build does not also republish its libraries. Native routes
`CompilerOutputKind.LIBRARY` through `NativeKlibCliPipeline`, separate from its executable driver.
JVM does emit Kotlin metadata during ordinary code generation, but that metadata is embedded in
the same class-file product as the executable declarations. It does not rebuild `kotlin-stdlib`
while compiling a user program.

The current .NET representation is a split KLIB + DLL product, so the JVM exception does not
apply. The producer must be an explicit library product, following the JS/Wasm and Native
lifecycle. Emitting or refreshing `Kotlin.Stdlib.klib` as a side effect of every executable
compilation is rejected.

## Bootstrap production model

The POC adds an explicit compiler product route:

```text
-Xdotnet-produce-stdlib -Xdotnet-target={net48|netstandard2.0|net10.0} -d <directory>
```

This provisional build control accepts no user source files, owns the `Kotlin.Stdlib` module name,
and cannot be combined with `-no-stdlib` or CLR export selectors. It is not a source annotation or
a proposed end-user stdlib API. From one resolved frontend session it serializes all compiler-owned
bootstrap declarations, lowers their executable implementations for the selected profile,
assembles the corresponding DLL, and publishes the packed metadata KLIB only
after the DLL succeeds:

```text
compiler-owned stdlib source
            |
      frontend resolution
       /              \
Kotlin metadata       FIR -> IR -> stdlib-owned IL
       \              /
        Kotlin.Stdlib.klib + Kotlin.Stdlib.dll
```

The two files therefore cannot silently describe different source compilations. A stale KLIB and
DLL are removed before a replacement build; metadata is written through a temporary packed KLIB
after the implementation assembly exists.

### Reproducibility boundary

For a fixed compiler and source corpus, repeated producer runs must emit an
identical packed KLIB and identical textual IL. KLIB archive order and timestamps are normalized by
the shared Kotlin archive writer, and metadata fragments are ordered by package and source name.
Focused pins compare the KLIB, textual IL, and DLL byte for byte across repeated builds of each
profile. Different profiles are not expected to be byte-identical. Both assembler paths use
`/det`, which gives identical IL a deterministic PE module identity.
The packed KLIB records the resulting DLL's SHA-256, and consumers reject a sibling whose bytes do
not match, so the two-file product cannot be recombined accidentally. Executable/test assembly
remains outside this publication reproducibility contract. The direct PE writer described in
`draft-adr-il-assembly-pipeline.md` must eventually own deterministic PE construction rather than
depending on the external assembler flag.

### Installed-pair discovery

Following JVM/JS `KotlinPaths` and Native distribution ownership, an ordinary .NET compilation
prefers the complete exact-profile pair at `lib/dotnet/<selected-profile>`, then the portable pair
at:

```text
<kotlin-home>/lib/dotnet/netstandard2.0/Kotlin.Stdlib.klib
<kotlin-home>/lib/dotnet/netstandard2.0/Kotlin.Stdlib.dll
```

The manifest binds each companion to its declared TFM. The loader accepts only the explicit
compatibility matrix: an executable profile accepts itself or `netstandard2.0`; the portable
profile accepts only itself. A half-installed candidate is an error rather than permission to
silently rebuild a different implementation. `-no-stdlib` remains the opt-out and, together with
an explicit classpath, the bootstrap override.

Absence of both files still selects the injected-source compatibility path. Installed-pair use no
longer enables `kotlin.*` packages in user sources; that temporary permission is limited to
compiler-owned injected sources.

Repository production and installation are explicit opt-in tasks:

```text
./gradlew :kotlin-compiler:produceDotNetStdlib
./gradlew :kotlin-compiler:installDotNetStdlib
```

The aggregate producer depends on the assembled compiler distribution, runs that distribution's
complete compiler classpath, and writes `Kotlin.Stdlib.{klib,dll,il}` under one
`prepare/compiler/build/dotnet-stdlib/<profile>` directory per profile. Its three constituent
tasks are `produceDotNetStdlibNet48`, `produceDotNetStdlibNetStandard20`, and
`produceDotNetStdlibNet100`. Framework ILAsm writes `net48`; modern ILAsm writes
`netstandard2.0` and `net10.0`. The install task copies only each bound KLIB/DLL pair into its
corresponding Kotlin-home profile directory; IL remains a build diagnostic. Neither aggregate task
participates in ordinary `dist` or `distKotlinc`, so ordinary builds do not acquire those host-tool
requirements. `distKotlinc` is a whole-home `Sync` and therefore removes an earlier optional
installation; invoking `installDotNetStdlib` afterward restores all three variants.

The default bootstrap producer still injects the stdlib source into the same frontend/IR run as the
program, lowers the combined IR once, and emits it through two declaration-ownership scopes:

```text
injected stdlib source + user source
                 |
          frontend + lowerings
                 |
       +---------+----------+
       |                    |
stdlib-owned declarations   user declarations
       |                    |
Kotlin.Stdlib.dll      program assembly
```

Every assembled executable is supplied `Kotlin.Runtime.dll` and `Kotlin.Stdlib.dll`. The stdlib IL
is retained beside the executable for deterministic inspection, like the program IL. Raw IL-only
compilation does not yet constitute a distributable multi-assembly library build.

This split emitter is temporary bootstrap compatibility, not the library build. The consumer half
no longer depends on it: with `-no-stdlib` and the produced bound KLIB on its classpath, a user
module resolves and calls the prebuilt DLL without injected implementations. Same-run production
remains only until ordinary compilation can discover a distribution-owned target pair by default.

## Rejected alternatives

### Keep handwritten collection implementations in `Kotlin.Runtime`

This would work mechanically but turn bootstrap convenience into permanent architecture. It also
prevents the implementation from exercising the same source and bridge pipeline as user code.

### Emit `ArrayIterator` into every user module

This avoids an extra assembly but duplicates physical type identity and makes library behavior a
per-program compiler artifact.

### Treat the CLR DLL alone as a Kotlin library

Producing a PE is not enough: CLR metadata does not describe Kotlin extension receivers, source
visibility, nullability, expect/actual relationships, or other Kotlin declaration semantics. The
accepted input is an explicitly bound Kotlin metadata KLIB plus DLL, not assembly reflection.

## Consequences

Benefits:

- collection implementation policy leaves the runtime ABI assembly;
- built-in and user Iterator implementations use one general bridge path;
- compiled programs reference one Kotlin-owned stdlib identity; and
- future stdlib classes have a clear physical home.

Costs and limits:

- the compatibility path still rebuilds the stdlib beside an executable when no installed pair is
  available;
- the emitter temporarily recognizes injected stdlib ownership;
- the explicit producer flag is POC build control rather than a final distribution interface;
- installing the discoverable target pair is opt-in rather than part of the normal distribution;
- the metadata-public implementation and facade names are compiler/stdlib contracts;
- the current physical-member mapping covers only compiler-owned stdlib shapes; and
- the standalone producer is still limited to the compiler-owned bootstrap stdlib.

## Validation

The focused IL-text pin verifies that array iterator construction references
`Kotlin.Stdlib, Version=1.0.0.0`. The CoreCLR box pin assembles and loads the runtime, stdlib, and
program together. The box harness keeps a small product smoke check for the stdlib assembly,
profile, facade, implementation classes, Empty objects, RandomAccess, and `emptyList()`. The
focused standalone-product integration pin owns the detailed physical assertions for private
`Kotlin.Collections.ArrayIterator<T>`/`ArrayIterable<T>` classes, private EmptyIterator/EmptyList
objects, the public RandomAccess and private inert Serializable markers, their ordinary internal
composition, compiler-generated collection bridges, and the generic
`Kotlin.Collections.CollectionsKt.first<T>` and `last<T>` methods for both canonical Iterable and
canonical List receivers. The IL pins verify that open generic user wrappers select the correct
overload across the stdlib assembly boundary; the box pins cover primitive, widened, reference,
nullable, empty, stdlib-produced, and user-produced Iterables and Lists. The List-as-Iterable pin
also proves indexed dispatch without calling `iterator()` and the `List is empty.` path. `last()`
additionally exercises a mutable generic local, a loop, and repeated erased
Iterator calls inside the stdlib assembly. A focused CLI integration pin compiles a consumer with
`-no-stdlib`, resolves both receiver overloads from a metadata KLIB, and verifies the generic external DLL call
without a generated consumer-side CollectionsKt. A manual Framework executable exercised the same
path against a real generated DLL and user-defined Iterable. Focused integration pins produce the
`net48`, `netstandard2.0`, and `net10.0` variants, check each packed KLIB manifest and DLL, then
consume `first()`, `last()`, `emptyList()`, and RandomAccess from each compatible profile in a
separate compilation. The portable pin also installs the pair only under `netstandard2.0`, proves
fallback discovery, assembles applications, and executes the same stdlib implementation on both
Framework 4.8 and .NET 10. The stdlib KLIB participates in the same physical-declaration binder as any other
Kotlin/.NET library; the separate stdlib record controls installation and packaging only. Each
producer is also run twice; the packed KLIB and compiler-owned IL are
byte-identical. Finally, each produced pair is installed into a temporary portable-profile Kotlin
home and consumed by an ordinary compilation with neither `-no-stdlib` nor a manual metadata
classpath; the consumer does not regenerate the stdlib facade. The repository producer and install
tasks are also exercised as products: the producer emits the expected KLIB/DLL/IL set, while
installation copies exactly the byte-matching KLIB/DLL pair into the distribution and does not
install the diagnostic IL.

## Deferred work

This draft does not decide the final .NET KLIB platform marker, general Kotlin/.NET library
production beyond the compiler-owned stdlib, package-version distribution,
signing for a future ABI major, CLR/BCL collection adapters, primitive-specialized iterators, or the
broader collection API. Those features must preserve the runtime/stdlib ownership split rather
than moving ordinary implementations back into the runtime.
