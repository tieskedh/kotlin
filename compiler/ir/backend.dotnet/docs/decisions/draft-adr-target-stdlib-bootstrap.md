# Draft ADR: Bootstrap Kotlin/.NET target stdlib assembly

- Status: **Draft candidate; first implementation slice is present in the prototype**
- Date: 2026-07-17
- Scope: physical ownership and bootstrap production of Kotlin standard-library implementations

> **Artifact amendment (2026-07-27):**
> [Self-describing DLL as the Kotlin/.NET library artifact](adr-self-describing-dotnet-library-dll.md)
> supersedes this draft's two-file publication endpoint. The migration is complete: the DLL embeds
> the authoritative KLIB payload, and no standalone Kotlin/.NET KLIB is produced, installed,
> resolved, or accepted.
>
> **Source-ownership amendment (2026-07-28):**
> the canonical bootstrap implementation now consists of ordinary .NET Kotlin files under
> `libraries/stdlib/dotnet/src` plus the explicitly selected authoritative Common files described
> below. Repository profile-product tasks pass that complete classified source set to the
> compiler. The backend JAR packages a byte-identical read-only copy only as a temporary
> no-installed-stdlib bootstrap fallback; it is not a second implementation.

This is a repository-local decision record for the experimental .NET backend. The `dotnet` branch
is a proof of concept; this document does not claim a public Kotlin or Kotlin/.NET commitment.

## Context

`Kotlin.Runtime.dll` already owns the current language/runtime ABI candidates such as erased
callable identities and the canonical/typed collection-interface views, Kotlin-owned exception
identities, and compiler support helpers. It also contained a handwritten IL `ArrayIterator` only
because the backend had no target stdlib assembly
and no general compiler-generated Iterator bridges.

Those bridges now exist. Keeping an ordinary collection implementation in the runtime would blur
the boundary between compiler/runtime identity and library policy, while emitting it into every
program would duplicate its type identity. A CLR assembly by itself is not a Kotlin compile-time
library: the frontend needs Kotlin declaration metadata in addition to the physical methods.

## Decision

The prototype introduces a second reserved platform assembly with this current candidate identity:

```text
Kotlin.Stdlib, Version=1.0.0.0, Culture=neutral, PublicKeyToken=null
```

All generated pieces use version `1.0.0.0` and no public-key token consistently so profile pairing,
embedded-metadata binding, and tests are deterministic. This is a pre-publication build/test identity, not a
published ABI major or a promise to preserve either the version or unsigned status. Before Gate B,
the project must decide the first public runtime and stdlib assembly names, strong-name policy,
AssemblyVersion compatibility policy, and package-version relationship as one publication design.
After publication, changing an assembly name, version binding policy, or strong-name key is an
explicit CLR ABI transition. `Kotlin.Stdlib.dll` references `Kotlin.Runtime.dll` and is produced as
`net48`, `netstandard2.0`, or `net10.0`. The variants share Kotlin declarations and logical
identities but may use profile-specific physical implementations. A consumer selects an
exact-profile pair first and may use the portable `netstandard2.0` pair from either executable
profile; see `draft-adr-dotnet-library-target-profile.md`.

Ownership is split as follows:

- `Kotlin.Runtime.dll` owns Kotlin callable identities, canonical and typed collection-interface
  views, exceptions, and narrowly shared compiler/runtime services;
- `Kotlin.Stdlib.dll` owns ordinary Kotlin library implementations; and
- the user assembly owns only user declarations and calls into those platform assemblies.

The first stdlib implementations are generic Kotlin `ArrayIterator<T>` and `ArrayIterable<T>`,
the common-shaped `EmptyIterator`/`EmptyList` singleton pair, `emptyList<T>()`, the top-level
`Iterable<T>`/`List<T>` `first()` and `last()` operations, and the Kotlin 2.5
`kotlin.internal.throwNoWhenBranchMatchedException(subject)` compiler helper. The classes,
objects, and helpers are compiled through the ordinary pipeline; collection implementations
receive the same compiler-generated canonical and typed MethodImpl bridges as user
implementations. Explicit array `iterator()`
calls a generic compiler-facing factory in `Kotlin.Stdlib`; the
handwritten `System.Array` iterator is removed from `Kotlin.Runtime`. Array `asIterable()`
calls the corresponding generic factory, whose private view stores the original vector and creates
a fresh private `ArrayIterator<T>` per request through ordinary Kotlin code. Direct array `for` loops remain
allocation-free indexed loops. User calls to `first()` and `last()` target the current
compiler/stdlib facade `[Kotlin.Stdlib]Kotlin.Collections.CollectionsKt`; their implementations use guarded typed
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

A separately compiled consumer uses one self-describing artifact:

```text
Kotlin.Stdlib.dll -> CLR implementation plus private Kotlin.Metadata KLIB payload
```

The embedded metadata KLIB uses the existing metadata-library reader. Its manifest binds the complete
candidate identity (`dotnet_assembly_name=Kotlin.Stdlib`, version `1.0.0.0`, neutral culture,
and null public-key token), `dotnet_assembly_file=Kotlin.Stdlib.dll`, and
`dotnet_library_tfm=netstandard2.0`. This binding is what turns that one metadata dependency into a physical CLR
reference. The compiler validates those properties against the containing PE Assembly row and
copies the DLL beside an executable consumer. A standalone KLIB with the Kotlin/.NET ABI marker is
rejected. The metadata encoding is currently the common KLIB encoding because there is no durable
.NET KLIB platform kind yet; the custom library-TFM binding records the portable CLR API contract
without claiming that .NET Standard is an executable runtime. The carrier records
`dotnet_implementation_binding=self` and contains no recursive DLL hash.

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

The first source-ownership slice made that endpoint possible without pretending the broad corpus
already compiles. Its target implementation is stored as ordinary `.kt` files under
`libraries/stdlib/dotnet/src`. The explicit repository producer compiles those files directly.
`DotNetStdlibSource` is only a resource catalog: the backend build packages the same files and
loads them when a compiler has neither an installed platform pair nor explicit stdlib-product
sources. Tests require every packaged fallback file to be text-identical to its repository source.

The first Common/actual continuation compiles the exact shared
`libraries/stdlib/src/kotlin/internal/Annotations.kt` and
`throwNoWhenBranchMatchedException.kt` files as Common inputs. The .NET helper body is now an
ordinary `actual`, so its visibility, annotations, signature, and availability contract come from
Common and the target owns only the platform body. The former .NET copy of
`UsedFromCompilerGeneratedCode` is deleted. The entire Common annotation file remains
resolution-only physically: its internal metadata is serialized into the stdlib KLIB, but none of
its annotation classes or version-kind enum enters `Kotlin.Stdlib.dll`, facade-name reservation,
or the physical declaration index.

This source partition requires two FIR source sessions. The .NET caller therefore uses the shared
legacy-MPP session construction rather than metadata's single-session mode, and enables
`MultiPlatformProjects` only when Common sources or the temporary bootstrap product are present.
Like JS/Wasm/Native, it converts and actualises FIR before using `Fir2KlibMetadataSerializer`;
serializing the original expect and actual files before actualisation would create a false
platform KLIB. The CLR requires no semantic deviation here. The only target-specific mechanism
remains the three physical profile products and their temporary packaged-source fallback.

The next source partition compiles the full Common `ExceptionsH.kt` and the shared non-JVM
exception actual classes. `DotNetExceptions.kt` supplies only the remaining .NET actual
identities and Throwable operations. Its public operations are ordinary `Kotlin.Stdlib` methods;
private external helpers bind through the intrinsic registry to the identity-associated
`Kotlin.Runtime` service. Exact exception classes remain runtime-owned physical identities and
are excluded from duplicate stdlib codegen. The former target hierarchy mirror and temporary
non-actual `NoWhenBranchMatchedException` declaration are deleted. Like mature stdlib builds, the
compiler-owned fallback and explicit product mute the expect/actual-class Beta warning; this is
not applied to unrelated user MPP sources.

The I/O continuation adds the exact Common `ioH.kt`. `DotNetStdlibIo.kt` is the narrow actual
surface: it keeps the non-JVM inert `Serializable` marker, the JVM-style primitive `println`
overloads already supported by the target, and ordinary `readln`/`readlnOrNull` bodies on the
stable `Kotlin.Io.ConsoleKt` facade. Only the target-private `dotNetReadLine` operation is
intrinsic to `System.Console.ReadLine`; the Common EOF branch and
`ReadAfterEOFException("EOF has already been reached")` stay Kotlin.Stdlib code. The internal
exception is physically non-public; like other Kotlin `internal` declarations, its binding stays
in the private KLIB physical index for authorized friend compilation. Output remains call-site
intrinsic because it must apply Kotlin value rendering before `Console.Write(string)` or
`WriteLine(string)`. Mapping to CLR object/numeric overloads is rejected because those carry CLR
null, Boolean, culture, and floating-point rendering semantics. Mapping `Serializable` to a
foreign CLR serialization protocol is likewise rejected because Common promises no such protocol.

This decision follows the mature target product model:

- Common/JVM/JS/Wasm generated stdlib source is materialized under the relevant library source
  tree and compiled as a library product;
- Native likewise compiles generated runtime/stdlib Kotlin source from its product source tree;
- no mature target treats a compiler string literal as the permanent source of an ordinary
  Kotlin library declaration.

The CLR-specific constraint is not source ownership but product shape: the same logical source set
must produce profile-selected `net48`, `netstandard2.0`, and `net10.0` DLL pairs, and a standalone
bootstrap compiler still needs a cycle-breaking fallback before a platform pair is installed.
Packaging the canonical source files as compiler resources satisfies that temporary bootstrap
constraint without creating a second source namespace. It does not permit profile-dependent
Kotlin declarations or bodies; profile-dependent CLR representation remains a backend-lowering
decision. This preserves Kotlin Common semantics and respects each profile's CLR capabilities.

Classification: ordinary source-product ownership is **Correct direction**. Retaining the
packaged-source fallback is a **Correct temporary implementation, but not a final design**. The
core-team endpoint is generated Common sources plus narrow .NET actuals compiled by the ordinary
library product, followed by removal of same-run fallback production once every compiler
distribution and test bootstrap supplies a complete platform pair.

## Major-target production precedent

JS and Wasm select either KLIB serialization or executable linking in `WebCliPipeline`; an
ordinary executable build does not also republish its libraries. Native routes
`CompilerOutputKind.LIBRARY` through `NativeKlibCliPipeline`, separate from its executable driver.
JVM does emit Kotlin metadata during ordinary code generation, but that metadata is embedded in
the same class-file product as the executable declarations. It does not rebuild `kotlin-stdlib`
while compiling a user program.

The resolver observes one DLL containing Kotlin metadata. The producer remains an explicit
library product, following the JS/Wasm and Native lifecycle. Emitting or refreshing that library
artifact as a side effect of every executable compilation is rejected.

## Bootstrap production model

The POC adds an explicit compiler product route:

```text
-Xdotnet-produce-stdlib -Xdotnet-target={net48|netstandard2.0|net10.0} -d <directory>
```

This provisional build control owns the `Kotlin.Stdlib` module name and cannot be combined with
`-no-stdlib` or CLR export selectors. It accepts either no source arguments, selecting the packaged
bootstrap fallback, or exactly the complete product-owned source set. An incomplete, duplicate, or
unrelated source set is rejected. The repository Gradle producer always supplies the ordinary
sources explicitly. This is not a source annotation or a proposed end-user stdlib API. From one
resolved frontend session it serializes the resolved stdlib declarations, lowers their executable
implementations for the selected profile, packs the metadata as a private managed resource, and
assembles the corresponding DLL:

```text
ordinary product source or packaged fallback
            |
      frontend resolution
       /              \
Kotlin metadata       FIR -> IR -> stdlib-owned IL
       \              /
        Kotlin.Stdlib.dll[Kotlin.Metadata]
```

The resource and implementation originate from the same serialization result and physical
declaration index. The packed KLIB is assembled into the DLL as a private managed resource.

### Reproducibility boundary

For a fixed compiler and source corpus, repeated producer runs must emit an
identical packed KLIB and identical textual IL. KLIB archive order and timestamps are normalized by
the shared Kotlin archive writer, and metadata fragments are ordered by package and source name.
Focused pins extract and compare the embedded packed KLIB, textual IL, and DLL byte for byte
across repeated builds of each profile. Different profiles are not expected to be byte-identical. Both assembler paths use
`/det`, which gives identical IL a deterministic PE module identity.
The self-bound KLIB inside the DLL has no recursive self-hash; containment and physical
Assembly-row validation bind it to the implementation. Executable/test assembly
remains outside this publication reproducibility contract. The direct PE writer described in
`draft-adr-il-assembly-pipeline.md` must eventually own deterministic PE construction rather than
depending on the external assembler flag.

The explicit ordinary-source route and packaged-fallback route must also produce identical packed
KLIB entries, IL, and DLL bytes for the same profile. The physical declaration index is a
cross-module contract and therefore contains only declarations that can participate in
cross-module linkage. Private, private-to-this, and local declarations remain present in the CLR
implementation where needed, but their file-local `IdSignature`s are not exported into that
index. Such signatures may contain source-location identity and would otherwise make checkout
paths observable in an alleged ABI and make private implementation details look bindable.

### DLL-first installed discovery

Following JVM/JS `KotlinPaths` and Native distribution ownership, the current resolver prefers the
exact-profile DLL at `lib/dotnet/<selected-profile>`, then the portable DLL
at:

```text
<kotlin-home>/lib/dotnet/netstandard2.0/Kotlin.Stdlib.dll
<kotlin-home>/lib/dotnet/netstandard2.0/Kotlin.Runtime.dll
```

The embedded manifest binds each DLL to its declared TFM. The loader accepts only the explicit
compatibility matrix: an executable profile accepts itself or `netstandard2.0`; the portable
profile accepts only itself. A standalone or legacy Kotlin/.NET KLIB is not a candidate. Focused
tests install only the two DLLs, with no metadata or resource sidecar. `-no-stdlib`
remains the opt-out and, together with an explicit classpath, the bootstrap override.

The CLI classpath, friend, installed-stdlib, and Gradle dependency resolvers now read
`Kotlin.Metadata` from the selected DLL. Producer, installation, and Gradle publication flows use
only profile-selected DLL assets. Absence of the DLL still selects the injected-source
compatibility path. Installed-DLL use no
longer enables `kotlin.*` packages in user sources; that temporary permission is limited to
compiler-owned injected sources.

### Profile-paired runtime distribution

The mature targets consume distribution-owned platform libraries while compiling user code.
JVM does not regenerate `kotlin-stdlib` or its runtime support beside every application; JS/Wasm
and Native likewise build their platform libraries as separate products and resolve those products
through the target distribution. The logical Kotlin declarations remain compiler/metadata-owned,
while deployment copies the target's already built physical artifacts.

The CLR-specific difference is that the present platform boundary is a pair of assemblies.
`Kotlin.Stdlib.dll` contains ordinary Kotlin implementations and has an AssemblyRef to the
compiler/runtime identities in `Kotlin.Runtime.dll`. A selected installed stdlib is therefore not
a complete product unless the same profile directory also contains its runtime. The pair may have
profile-specific metadata and implementation:

- `net48` consumes the `net48` pair or the portable `netstandard2.0` pair;
- `net10.0` consumes the `net10.0` pair or the portable `netstandard2.0` pair; and
- `netstandard2.0` consumes only the `netstandard2.0` pair.

This does not alter Kotlin Common semantics. Every pair exposes the same supported Kotlin logical
runtime and stdlib identities. Profile selection changes only the legal CLR API references,
default-interface representation, and other physical implementation capabilities. In particular,
an exact-profile stdlib must not be combined with another profile's runtime merely because their
current unsigned AssemblyVersion happens to match.

The selected bootstrap migration is:

1. the explicit stdlib producer emits `Kotlin.Runtime.dll` and the self-describing
   `Kotlin.Stdlib.dll` together from one target/profile compiler invocation;
2. installation copies both DLLs into the same `lib/dotnet/<profile>` directory;
3. installed discovery treats a stdlib without its sibling runtime as an incomplete distribution
   and diagnoses it instead of silently rebuilding another runtime;
4. the compiler validates the runtime's physical Assembly row and public
   `Kotlin.CSharpImplementationManifest` profile without loading target code; and
5. executable packaging copies the selected runtime and stdlib bytes unchanged.

The existing same-run runtime builder remains a temporary fallback only when a self-describing
stdlib is supplied manually without an installed platform pair. This keeps bootstrap and focused
test inputs usable while making the normal installed path distribution-owned. Removing that
fallback is a later mechanical step once every test and distribution flow supplies a complete
pair.

This follows the other targets' product lifecycle and is a **Correct direction**. Using a public
managed resource to authenticate the runtime profile is a **Reasonable platform-specific
divergence**: unlike the stdlib, the runtime has no Kotlin declaration KLIB, but Roslyn tooling and
the compiler already require its C# implementation contract. The resource remains an exported
view of Kotlin logical identities, not a second declaration namespace.

Repository production and installation are explicit opt-in tasks:

```text
./gradlew :kotlin-compiler:produceDotNetStdlib
./gradlew :kotlin-compiler:installDotNetStdlib
```

The aggregate producer depends on the assembled compiler distribution and runs that distribution's
complete compiler classpath. Its three constituent tasks are `produceDotNetStdlibNet48`,
`produceDotNetStdlibNetStandard20`, and `produceDotNetStdlibNet100`. Framework ILAsm writes
`net48`; modern ILAsm writes `netstandard2.0` and `net10.0`. The producer writes
`Kotlin.Runtime.dll`, `Kotlin.Stdlib.dll`, and diagnostic `Kotlin.Stdlib.il` under each
`prepare/compiler/build/dotnet-stdlib/<profile>` directory. The install task copies both DLLs into
their corresponding Kotlin-home profile directory; IL remains a build diagnostic. Neither aggregate task
participates in ordinary `dist` or `distKotlinc`, so ordinary builds do not acquire those host-tool
requirements. `distKotlinc` is a whole-home `Sync` and therefore removes an earlier optional
installation; invoking `installDotNetStdlib` afterward restores all three variants. Each profile
producer declares `libraries/stdlib/dotnet/src/**/*.kt` as task input and passes those source files
to the compiler, rather than silently selecting the fallback resource.

The default bootstrap fallback still injects the packaged copy of the canonical stdlib source into
the same frontend/IR run as the program, lowers the combined IR once, and emits it through two
declaration-ownership scopes:

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

This split emitter is temporary bootstrap compatibility, not the library build. The normal
consumer path no longer depends on it: ordinary compilation discovers the installed
profile-compatible runtime/stdlib pair and calls the prebuilt stdlib without injected
implementations. Explicit `-no-stdlib` plus a self-describing DLL remains available for bootstrap
tests. Same-run production now survives only as a temporary fallback for a manually supplied
stdlib with no installed runtime sibling.

## Rejected alternatives

### Keep handwritten collection implementations in `Kotlin.Runtime`

This would work mechanically but turn bootstrap convenience into permanent architecture. It also
prevents the implementation from exercising the same source and bridge pipeline as user code.

### Emit `ArrayIterator` into every user module

This avoids an extra assembly but duplicates physical type identity and makes library behavior a
per-program compiler artifact.

### Treat a bare CLR DLL as a Kotlin library

Producing a PE is not enough: CLR metadata does not describe Kotlin extension receivers, source
visibility, nullability, expect/actual relationships, or other Kotlin declaration semantics. The
accepted input is a self-describing DLL with authoritative embedded Kotlin metadata, not assembly
reflection or a bare foreign DLL.

## Consequences

Benefits:

- collection implementation policy leaves the runtime ABI assembly;
- built-in and user Iterator implementations use one general bridge path;
- compiled programs reference one Kotlin-owned stdlib identity; and
- future stdlib classes have a clear physical home.

Costs and limits:

- the compatibility path still rebuilds the stdlib beside an executable when no installed DLL is
  available;
- the emitter temporarily recognizes injected stdlib ownership;
- the explicit producer flag is POC build control rather than a final distribution interface;
- installing the discoverable target assembly is opt-in rather than part of the normal distribution;
- the metadata-public implementation and facade names are compiler/stdlib contracts;
- the current physical-member mapping and manual source-ownership catalog cover only
  compiler-owned stdlib shapes; every new executable source/helper must be added to both or it can
  resolve from embedded metadata while disappearing from the physical DLL;
- resolution-only bootstrap sources need an explicit catalog entry so their declarations remain
  available to frontend/KLIB serialization without entering user IL, facade-name reservation, or
  the physical declaration index; and
- the standalone producer is still limited to the exact bootstrap stdlib source product.

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
`-no-stdlib`, resolves both receiver overloads from the DLL's embedded metadata, and verifies the generic external DLL call
without a generated consumer-side CollectionsKt. A manual Framework executable exercised the same
path against a real generated DLL and user-defined Iterable. Focused integration pins produce the
`net48`, `netstandard2.0`, and `net10.0` variants, check each embedded packed-KLIB manifest and DLL, then
consume `first()`, `last()`, `emptyList()`, and RandomAccess from each compatible profile in a
separate compilation. The portable pin also installs only the paired DLLs under `netstandard2.0`,
proves fallback discovery, copies both artifacts byte-for-byte into assembled applications, and
executes the same runtime/stdlib implementation on both Framework 4.8 and .NET 10. Missing and
wrong-profile runtime siblings are rejected instead of triggering an implicit rebuild. The
embedded stdlib KLIB participates in the same physical-declaration
binder as any other Kotlin/.NET library; the separate stdlib record controls installation and
packaging only. Each producer is also run twice; the packed KLIB and compiler-owned IL are
byte-identical. The ordinary-source and packaged-fallback producer routes additionally emit
byte-identical packed KLIB entries, textual IL, and DLLs for `net48` and `net10.0`; the manifest
asserts that no file-private identity entered the physical declaration index. An incomplete
ordinary source set is rejected before compilation. Finally, each produced DLL is installed into a temporary portable-profile Kotlin
home and consumed by an ordinary compilation with neither `-no-stdlib` nor a manual metadata
classpath; the consumer does not regenerate the stdlib facade or runtime. The repository producer
and install tasks are also exercised as products: the producer emits the expected DLL pair and
diagnostic IL, while installation copies exactly the byte-matching DLLs into the distribution and
does not install the diagnostic IL or a standalone KLIB.

The fallback source catalog is sorted by relative path and injects files under the same
package-relative temp paths as the ordinary-source producer. This is semantic for reproducibility
even when every file is byte-identical: FIR orders actual source paths, which controls the order
of otherwise independent physical declarations in generated IL.

The Common/actual continuation additionally rejects an explicit complete source set unless the two
authoritative Common files are passed through `-Xcommon-sources`. Direct and packaged-fallback
profile products remain byte-identical in packed KLIB entries, compiler-owned IL, and PE bytes.
The exhaustive-when IL pins also prove that FIR prefers the non-expect actual helper when both
symbols are visible; accepting the legacy parameterless fallback would erase the Kotlin 2.5
message contract. The fresh aggregate validation is 885 tests across 16 XML suites, with zero
failures, errors, or skips.

The exception continuation adds `ExceptionsH.kt` to those authoritative Common inputs and
packages the shared `common-non-jvm/Exceptions.kt` actual bodies unchanged. Separate consumers
bind `stackTraceToString`, `printStackTrace`, `addSuppressed`, and
`suppressedExceptions` through the produced self-describing DLL; the portable pair executes the
same Common API on Framework CLR 4 and CoreCLR 10. Runtime-owned exact classes and weak throwable
state remain outside `Kotlin.Stdlib`, preserving the runtime/stdlib ownership split. The fresh
strict gate is 889/0/0/0 across 16 XML suites.

The I/O continuation adds `ioH.kt` to the authoritative Common inputs. Direct-source and packaged
fallback products remain byte-identical, and misclassifying any Common file rejects the complete
explicit source product. Separate consumers call `Kotlin.Io.ConsoleKt.readln` and
`readlnOrNull`; only the stdlib IL contains `System.Console.ReadLine()`. Redirected CRLF input,
null-at-EOF, the exact Common EOF message and `RuntimeException` ancestry, and Kotlin-shaped
`print(false)`/`print(null)` output execute through one portable stdlib on Framework CLR 4 and
CoreCLR 10. The new public methods use the existing physical Function record, so no physical
schema or runtime-surface revision is required. The fresh strict gate is 889/0/0/0 across 16 XML
suites.

The exhaustive-when matrix additionally verifies that the internal subject-aware helper is emitted
once in `Kotlin.Stdlib`, that a separately compiled application calls that physical facade, and
that bootstrap production packages the same dependency. A raw IL caller supplies noncanonical CLR
`bool` value `2` to force the otherwise unreachable Kotlin fallthrough and observes the exact
runtime-owned exception plus `No branch matched for subject: true` on Framework CLR 4 and CoreCLR
10 across both supported ILAsm implementations.

## Deferred work

This draft does not decide the final .NET KLIB platform marker, general Kotlin/.NET library
production beyond the compiler-owned stdlib, the first published assembly/version/signing policy,
CLR/BCL collection adapters, primitive-specialized iterators, or the broader collection API. The
publication identity decision is required before Gate B; it is not deferred until after an
unsigned ABI major has shipped. Those features must preserve the runtime/stdlib
ownership split rather than moving ordinary implementations back into the runtime.
