# Draft ADR: Bootstrap Kotlin/.NET target stdlib assembly

- Status: **Draft candidate; first implementation slice is present in the prototype**
- Date: 2026-07-16
- Scope: physical ownership and bootstrap production of Kotlin standard-library implementations

This is a repository-local decision record for the experimental .NET backend. The `dotnet` branch
is a proof of concept; this document does not claim a public Kotlin or Kotlin/.NET commitment.

## Context

`Kotlin.Runtime.dll` already owns stable language/runtime ABI types such as erased callable and
collection interfaces, Kotlin-owned exception identities, and compiler support helpers. It also
contained a handwritten IL `ArrayIterator` only because the backend had no target stdlib assembly
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
and uses the .NET Framework 4.8 API floor so the same logical IL can be assembled for either
supported runtime target.

Ownership is split as follows:

- `Kotlin.Runtime.dll` owns Kotlin callable/collection identities, exceptions, and narrowly shared
  compiler/runtime services;
- `Kotlin.Stdlib.dll` owns ordinary Kotlin library implementations; and
- the user assembly owns only user declarations and calls into those platform assemblies.

The first stdlib implementations are generic Kotlin `ArrayIterator<T>` and `ArrayIterable<T>`
classes plus the top-level `Iterable<T>.first()` and `last()` operations. The classes are compiled
through the ordinary class pipeline and receive the same compiler-generated erased
Iterator/Iterable MethodImpl bridges as user implementations. Explicit array `iterator()`
constructs the appropriate closed iterator class from `Kotlin.Stdlib`; the
handwritten `System.Array` iterator is removed from `Kotlin.Runtime`. Array `asIterable()`
constructs the corresponding closed view, which stores the original vector and creates a fresh
`ArrayIterator<T>` per request through ordinary Kotlin code. Direct array `for` loops remain
allocation-free indexed loops. User calls to `first()` and `last()` target the stable physical
facade `[Kotlin.Stdlib]Kotlin.Collections.CollectionsKt`; their implementations use the universal
erased Iterable/Iterator contract and therefore work equally for stdlib and user-defined
producers.

The implementation classes are private to Kotlin source resolution, but their CLR metadata and
constructors are public because generated user assemblies construct them across the assembly
boundary. Their physical names and the callable facade name are therefore compiler/stdlib ABI
details, not additional Kotlin source APIs.

A separately compiled consumer uses a paired artifact:

```text
Kotlin.Stdlib.klib  -> Kotlin declaration/type metadata used by FIR
Kotlin.Stdlib.dll   -> CLR implementations referenced by emitted IL
```

The metadata KLIB uses the existing metadata-library reader. Its manifest binds the complete
unsigned ABI-1 identity (`dotnet_assembly_name=Kotlin.Stdlib`, version `1.0.0.0`, neutral culture,
and null public-key token), `dotnet_assembly_file=Kotlin.Stdlib.dll`, and the requested
`dotnet_target`. This binding is what turns that one metadata dependency into a physical CLR
reference; arbitrary KLIBs remain compile-time-only. The compiler requires the named sibling DLL
and copies it beside an executable consumer. The metadata encoding is currently the common KLIB
encoding because there is no durable .NET KLIB platform kind yet; the custom target binding
prevents that implementation detail from claiming cross-target executability.

## Relationship to the stdlib generator

The mature stdlib generator currently emits Common, JVM, JS, WASM, and Native sources; it has no
.NET target. `Iterable<T>.first()` and `last()` are owned by the common `Elements.f_first` and
`Elements.f_last` templates, not by platform-specific implementations. The bootstrap source
carries the universal parts of those common bodies. It omits the templates' `List` fast paths
because this target has no coherent List ABI yet; the omission changes performance only, not
Iterable semantics or exception behavior.

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
-Xdotnet-produce-stdlib -d <directory> [-Xdotnet-target=netframework|net]
```

This provisional build control accepts no user source files, owns the `Kotlin.Stdlib` module name,
and cannot be combined with `-no-stdlib` or CLR export selectors. It is not a source annotation or
a proposed end-user stdlib API. From one resolved frontend session it serializes all compiler-owned
bootstrap declarations, lowers their executable implementations, assembles the target-specific
DLL, and publishes the packed metadata KLIB only after the DLL succeeds:

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

For a fixed compiler, source corpus, and requested target, repeated producer runs must emit an
identical packed KLIB and identical textual IL. KLIB archive order and timestamps are normalized by
the shared Kotlin archive writer, and metadata fragments are ordered by package and source name.
Focused CoreCLR and Framework pins compare both compiler-owned files byte for byte.

The current external ILAsm implementations give identical IL a fresh PE module identity, so DLL
byte identity is not a truthful POC gate. The durable requirement at this stage is the fixed
assembly identity recorded in the KLIB plus successful separate consumption of the assembled DLL.
The direct PE writer described in `draft-adr-il-assembly-pipeline.md` must eventually make the PE
itself deterministic.

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

- the stdlib is redundantly rebuilt beside each executable for now;
- the emitter temporarily recognizes injected stdlib ownership;
- the explicit producer flag is POC build control rather than a final distribution interface;
- the metadata-public implementation and facade names are compiler/stdlib contracts;
- the current physical-member mapping covers only compiler-owned stdlib shapes; and
- the standalone producer is still limited to the compiler-owned bootstrap stdlib.

## Validation

The focused IL-text pin verifies that array iterator construction references
`Kotlin.Stdlib, Version=1.0.0.0`. The CoreCLR box pin assembles and loads the runtime, stdlib, and
program together. The box harness also checks the retained stdlib IL for its assembly version,
generic `Kotlin.Collections.ArrayIterator<T>`/`ArrayIterable<T>` classes, their ordinary internal
composition, compiler-generated Iterator/Iterable bridges, and the generic
`Kotlin.Collections.CollectionsKt.first<T>` and `last<T>` methods. The Iterable IL pin verifies that
open generic user wrappers call both methods across the stdlib assembly boundary; the box pin
covers primitive, widened, reference, nullable, empty, stdlib-produced, and user-produced
Iterables. `last()` additionally exercises a mutable generic local, a loop, and repeated erased
Iterator calls inside the stdlib assembly. A focused CLI integration pin compiles a consumer with
`-no-stdlib`, resolves `first()` from a metadata KLIB, and verifies the generic external DLL call
without a generated consumer-side CollectionsKt. A manual Framework executable exercised the same
path against a real generated DLL and user-defined Iterable. Two focused integration pins run the
explicit producer for CoreCLR and Framework, check each packed KLIB manifest and real target DLL,
then consume both `first()` and `last()` from each exact produced pair in a separate compilation.
Each target producer is also run twice; the packed KLIB and compiler-owned IL are byte-identical.

## Deferred work

This draft does not decide the final .NET KLIB platform marker, general Kotlin/.NET library
production beyond the compiler-owned stdlib, package-version distribution,
signing for a future ABI major, CLR/BCL collection adapters, primitive-specialized iterators, or the
broader collection API. Those features must preserve the runtime/stdlib ownership split rather
than moving ordinary implementations back into the runtime.
