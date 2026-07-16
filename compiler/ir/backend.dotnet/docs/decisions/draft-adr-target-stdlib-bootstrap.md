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

The first stdlib implementation is a generic Kotlin `ArrayIterator<T>`. It is compiled through the
ordinary class pipeline and receives the same compiler-generated erased Iterator MethodImpl
bridges as a user implementation. Explicit array `iterator()` constructs the appropriate closed
generic class from `Kotlin.Stdlib`; the handwritten `System.Array` iterator is removed from
`Kotlin.Runtime`. Direct array `for` loops remain allocation-free indexed loops.

The implementation class is private to Kotlin source resolution, but its CLR metadata and
constructor are public because generated user assemblies construct it across the assembly
boundary. Its physical name is therefore a compiler/stdlib ABI detail, not a Kotlin source API.

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
stdlib-owned classes   user declarations
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
- the metadata-public implementation name is a compiler/stdlib contract; and
- source-level cross-module Kotlin compilation remains unproven.

## Validation

The focused IL-text pin verifies that array iterator construction references
`Kotlin.Stdlib, Version=1.0.0.0`. The CoreCLR box pin assembles and loads the runtime, stdlib, and
program together. The box harness also checks the retained stdlib IL for its assembly version,
generic `Kotlin.Collections.ArrayIterator<T>` class, and compiler-generated Iterator bridges.

## Deferred work

This draft does not decide standalone stdlib build tooling, Kotlin metadata format/import,
package-version distribution, signing for a future ABI major, CLR/BCL collection adapters,
primitive-specialized iterators, or the broader collection API. Those features must preserve the
runtime/stdlib ownership split rather than moving ordinary implementations back into the runtime.
