# Ordinary inline functions programme

- Status: **Completed compiler foundation — selected Common collection adoption may begin**
- Scope: ordinary Kotlin `inline`, inline lambda control flow, and same-/cross-module compiler ABI
- Explicitly excluded: reified type parameters, `typeOf`, broad reflection, suspend inline functions,
  and target-specific source substitutes

## Kotlin authority

Kotlin `inline` is a language and compiler contract. The source declaration, visibility,
nullability, contracts, lambda modifiers, non-local control flow, evaluation order, and logical
identity remain the Common/FIR/KLIB declarations. A CLR method body or JIT inlining decision is not
Kotlin inlining: it cannot substitute lambda bodies, implement non-local returns, expose
`@PublishedApi` compiler ABI, or make an inline body available to a separately compiled consumer.

Common stdlib sources remain authoritative. The first collection beneficiaries are the generated
non-reified `any(predicate)`, `all`, `indexOfFirst`, and `indexOfLast` families required by
`AbstractCollection` and `AbstractList`. They land only after the compiler slice below is complete;
they are not copied into .NET source and are not published as non-inline substitutes.

## Mature-target precedent

JS, Wasm, and Native are the relevant KLIB targets. Their first compilation stage uses the shared
`loweringsOfTheFirstPhase` sequence:

1. avoid local fake overrides in inline bodies, materialize version overloads, and reject inline
   call cycles;
2. when intra-module inlining is enabled, lower `lateinit`, shared variables, local classes in
   inline lambdas, and array constructors;
3. inline private functions, validate inline declarations, create required outer-this and
   synthetic accessors, and validate the private-inline result;
4. inline the selected intra-/cross-module set, prepare the remaining public inline bodies for
   KLIB serialization, remove redundant casts, and validate the result.

Their binary stage begins with the corresponding common lowering/inliner prefix so bodies that
remain for the selected KLIB inliner mode receive the same semantics before target lowerings.
KLIB stores both normal serialized IR and, when produced by the first stage, the prepared
inlinable-functions component. The logical declaration in metadata remains authoritative.

JVM uses its class-file inline-body representation and JVM backend sequence rather than KLIB IR,
but establishes the same invariant: a consumer receives compiler-readable body information and
Kotlin compiler ABI is distinct from ordinary source-public API.

## Corrected baseline

The completed slice corrects the former false partial support rather than teaching the CLR JIT a
target substitute:

- the shared packed-KLIB adapter retains metadata, main IR, and prepared-inlinable IR while its
  metadata-only entry point deliberately keeps the old narrow contract;
- `compiler:ir:serialization.dotnet` owns the .NET mangler and target IR serializer;
- every self-describing Kotlin library DLL embeds main IR and embeds prepared inline copies when
  the selected Common first stage produces them;
- stdlib IR paths use Common KLIB relative-path serialization against the authoritative stdlib
  source layout, keeping repository-source and packaged-fallback DLLs byte-for-byte reproducible;
- the CLI runs the shared pre-serialization phases before serialization and CIL production;
- the binary stage runs the mature KLIB inline prefix when the first stage did not already mutate
  that same IR tree;
- cross-library body resolution treats prepared IR as authoritative, uses main IR as the legacy
  fallback, and binds referenced producer declarations and built-ins without starting a general
  IR linker;
- captured mutable state uses Common's `kotlin.internal.SharedVariableBox<T>` compiler ABI; and
- ordinary non-reified generic inline methods are admitted while reified and suspend functions
  still fail explicitly.

An ordinary physical CLR method remains for each supported inline declaration. It is a callable
fallback and useful C# shape, but the tests require Kotlin call sites to disappear after IR
inlining rather than relying on that method or on JIT heuristics.

## CLR constraints and non-constraints

The CLR supplies ordinary generic methods, closures, arrays, exceptions, and public metadata
members. None requires a different logical inline model. CLR generic reification is useful for the
physical ordinary method that remains callable, but does not implement Kotlin reified-inline
substitution and is not a reason to couple the two features.

The genuine CLR constraint is visibility: code copied into a consumer assembly can call only
physically linkable producer declarations. `@PublishedApi internal` and any synthetic accessor
that Common makes public must therefore be CLR-public compiler ABI, retain Kotlin-internal
metadata, and carry `KotlinCompilerAbiAttribute` plus `EditorBrowsable(Never)`. An accessor for an
`internal inline` friend API remains internal and links through the producer's exact friend
authorization instead of being widened. Compiler-only surfaces remain absent from ordinary C#
exports and the C# implementation manifest.

## Kotlin-aligned target choice

### 1. Complete the embedded KLIB component adapter

Add a component-complete packed-KLIB loader in shared `compiler:util-klib` infrastructure. It
reuses the existing canonical ZIP, central-directory, duplicate-entry, CRC, and expansion-budget
validation, retains metadata, main IR, and prepared-inlinable IR components, and keeps the
containing DLL as `KotlinLibrary.path`.

Keep `loadPackedMetadataKlib` as the explicit metadata-only compatibility entry point and its test
that ignores unknown components. The .NET loader switches to the component-complete entry point.
Missing, partial, duplicated, malformed, or unexpectedly shaped IR components fail as KLIB
format errors; they do not silently downgrade a Kotlin library to metadata-only inline behavior.

### 2. Give IR serialization a target serialization owner

Create `compiler:ir:serialization.dotnet`, parallel to `serialization.js` and
`serialization.native`. It owns `DotNetIrMangler`, the global declaration table, file serializer,
and module serializer. Backend CIL code continues to consume the mangler for the same logical
identities, but CLI/FIR2IR no longer imports that identity implementation from `backend.dotnet`.

One validated source-product selection owns both metadata and IR serialization. A stdlib product
serializes exactly `DOTNET_STDLIB_SOURCES`; a user library excludes injected bootstrap stdlib
files. Prepared inline-function copies are filtered by the same file selection. Injected
resolution sources must never leak into a user KLIB merely because IR serialization was added.
HMPP Common FIR files are not assumed to have a one-to-one post-actualization IR file. Selection
therefore excludes the known injected source paths from the actualized main IR, preserving
plugin-generated files, rather than consulting a platform-only FIR-to-IR file cache.

The direct and packaged-fallback stdlib source products have different physical roots but the
same `DOTNET_STDLIB_SOURCE_PATHS` layout. The stdlib serializer derives that product root and feeds
it to Common `IrSerializationSettings.sourceBaseDirs`; no .NET-specific IR path encoding is
introduced.

The library packager includes `SerializedIrModule` next to metadata in the existing embedded KLIB.
Applications run the first-stage lowering sequence but do not manufacture a library IR resource.
Libraries serialize after that sequence, then produce their CLR DLL from the same transformed IR.
When intra-module first-stage inlining is enabled, the binary stage does not rerun that prefix on
the same declarations: doing so would attempt to freeze the generated outer-this/accessor shape
twice. With the first-stage inliner disabled, the binary prefix owns those transformations.

### 3. Run the shared first and second stages

Add a .NET `PreSerializationLoweringContext` using `PreSerializationKlibSymbols`,
`KlibSharedVariablesManager`, and `DotNetIrMangler`. Compile the authoritative Common
`SharedVariableBox.kt` and `SyntheticConstructorMarker.kt` into the stdlib source product. The
generic box is the first-stage ABI; primitive box specialization is an optimization, not a
prerequisite for correctness.

Run the shared first-stage phases before target lowering/serialization. Start the binary-stage
pipeline with the mature-target common prefix before callable-reference, returnable-block, local
declaration, and other .NET-specific transformations when the first stage did not already run that
prefix on the same IR. The inliner may introduce returnable blocks, captures, array constructors,
and accessors, so target lowerings must consume its output rather than precede it. Both stages
deliberately omit `LateinitLowering`: `lateinit` and its required target throw-helper contract
remain a separate parked feature. The shared first-stage builder keeps that lowering enabled by
default for every existing mature target and exposes only this narrow target-capability switch.

Support the CLI's existing `-Xklib-ir-inliner=disabled`, `intra-module`, and `full` modes. Prepared
inlinable IR serves the modern intra/full paths. The disabled/legacy path requires the binary-stage
resolver to read inline bodies from main IR; silently forcing `full`, ignoring the flag, or leaving
an external inline call as an ordinary call is not acceptable.

`-no-stdlib` remains a valid diagnostic and foreign-CLR compilation mode. When the Common
`SharedVariableBox` and `SyntheticConstructorMarker` compiler ABI classes are absent, the CLI does
not construct a pre-serialization KLIB lowering context. This does not define a reduced inline
semantics: ordinary inline support is available in a complete Kotlin stdlib environment, while a
stdlib-free diagnostic compilation retains its pre-existing behavior instead of crashing during
eager symbol lookup.

### 4. Separate ordinary generic inline from reified inline

Remove the blanket rejection only for non-reified generic inline functions once both stages are
active. They retain an ordinary CLR generic method as their non-inlined physical fallback. Keep
reified type parameters rejected until type tests/casts, class literals, `KClass`, `typeOf`, array
construction, and reflection-dependent substitutions form one complete feature.

## Design attack and rejected shortcuts

- **Rely on CLR/JIT inlining.** This preserves neither Kotlin control flow nor cross-module body
  availability.
- **Inline only within the current source module.** Common public inline stdlib functions would
  still be unusable from a separate DLL and compiler ABI visibility would remain untested.
- **Store only annotations or source text.** Neither is the mature-target executable inline-body
  contract; KLIB IR already owns it.
- **Force the current default inliner mode.** The compiler already exposes three modes and KLIB
  consumers must interpret each honestly.
- **Reuse `MutableRef<T>` in the first stage.** That would serialize a target-private capture ABI
  where mature KLIB targets serialize the Common box and would make inline bodies target-pipeline
  dependent.
- **Treat CLR reified generics as Kotlin `reified`.** CLR type availability does not perform
  Kotlin call-site substitution or supply the missing reflection/type-token operations.
- **Hide compiler ABI behind ordinary `internal`.** A separately compiled non-friend consumer
  cannot legally link the copied body.

## Required adversarial evidence

The completed slice proves:

- same-module private and public inline calls and a separate self-describing producer DLL;
- top-level and member inline declarations, including a member body that reads producer state;
- `disabled`, `intra-module`, and `full` KLIB inliner modes;
- non-local return, `crossinline`, `noinline`, exactly-once argument evaluation, and side-effect
  order;
- mutable captured locals using the Common shared-variable box;
- private access through a Common-generated friend synthetic accessor and explicit
  `@PublishedApi` access;
- exact Kotlin metadata/IR identity and CLR compiler-ABI markers without accidental C# export;
- `net48` and `net10.0` execution plus `netstandard2.0` production and consumption;
- complete main/prepared component loading, partial-component rejection, and the existing packed
  archive's duplicate, non-canonical, truncated, CRC, and expansion-budget rejection;
- no injected bootstrap stdlib IR in a user-produced library; and
- byte-identical embedded stdlib KLIBs for repository sources and packaged fallback sources, plus
  successful stdlib-free foreign/diagnostic compilation; and
- continued explicit rejection of reified and suspend-inline declarations outside this slice.

This matrix is green. Reified substitution, coroutine lowering, `lateinit`, reflection, and
adjacent parked features did not enter the slice. The selected Common collection inline families
may now enter the stdlib product through their exact dependency closures.

This compiler foundation does not yet claim arbitrary dependency chains inside a user library's
inline body. A body serialized by library A may currently bind built-ins and declarations owned by
A; binding a call owned by a distinct Kotlin library B requires the selected .NET assembly graph
to become an explicit input to non-linking IR resolution. That is a later ordinary-inline breadth
slice, not a reason to introduce a general backend linker implicitly. The selected first Common
collection families depend only on built-ins and declarations in the same stdlib product, so their
adoption does not freeze or bypass that future graph boundary.
