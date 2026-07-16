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
program would duplicate its type identity. A precompiled stdlib cannot yet replace injected source
for frontend compilation because this POC cannot import Kotlin declarations and metadata from a
separately compiled CLR assembly.

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
classes plus the top-level `Iterable<T>.first()` and `last()` operations. The classes are compiled through the
ordinary class pipeline and receive the same compiler-generated erased Iterator/Iterable
MethodImpl bridges as user implementations. Explicit
array `iterator()` constructs the appropriate closed iterator class from `Kotlin.Stdlib`; the
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
generated common sources, each bootstrap extraction must identify its generator/template origin and preserve its semantics. The eventual .NET stdlib build should
compile the common generated corpus plus narrowly generated .NET actuals rather than maintain a
permanent handwritten fork.

## Bootstrap production model

Until standalone Kotlin library compilation and metadata import exist, the compiler injects the
stdlib source into the same frontend/IR run as the program, lowers the combined IR once, and emits
it through two declaration-ownership scopes:

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

This split emitter is temporary bootstrap control, not a substitute for module metadata. Once the
backend can compile and consume a standalone stdlib, the injected implementation source and
same-run stdlib emission must be removed without changing the runtime/stdlib ownership boundary.

## Rejected alternatives

### Keep handwritten collection implementations in `Kotlin.Runtime`

This would work mechanically but turn bootstrap convenience into permanent architecture. It also
prevents the implementation from exercising the same source and bridge pipeline as user code.

### Emit `ArrayIterator` into every user module

This avoids an extra assembly but duplicates physical type identity and makes library behavior a
per-program compiler artifact.

### Pretend a prebuilt stdlib is already consumable

Producing a PE is not enough. Without Kotlin metadata serialization/import, frontend resolution
cannot compile ordinary user source against the separately built Kotlin declarations. The current
same-run partition states that limitation honestly while establishing the physical boundary.

## Consequences

Benefits:

- collection implementation policy leaves the runtime ABI assembly;
- built-in and user Iterator implementations use one general bridge path;
- compiled programs reference one Kotlin-owned stdlib identity; and
- future stdlib classes have a clear physical home.

Costs and limits:

- the stdlib is redundantly rebuilt beside each executable for now;
- the emitter temporarily recognizes injected stdlib ownership;
- the metadata-public implementation and facade names are compiler/stdlib contracts; and
- source-level cross-module Kotlin compilation remains unproven.

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
Iterator calls inside the stdlib assembly.

## Deferred work

This draft does not decide standalone stdlib build tooling, Kotlin metadata format/import,
package-version distribution, signing for a future ABI major, CLR/BCL collection adapters,
primitive-specialized iterators, or the broader collection API. Those features must preserve the
runtime/stdlib ownership split rather than moving ordinary implementations back into the runtime.
