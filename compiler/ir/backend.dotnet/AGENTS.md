# Kotlin/.NET target bootstrap contract

This file is the self-contained contract an agent must read before changing
Kotlin/.NET code. It contains the rules needed to start safely; feature
rationale and representation detail belong in the linked ADRs.

The target is a pre-ABI Kotlin-to-CIL prototype. Objective CLR loading lives in
`compiler/frontend.common.dotnet`, FIR policy is composed through the .NET
frontend/CLI modules, IR lowering and CIL production live in
`compiler/ir/backend.dotnet`, and target vocabulary/configuration live above
those compiler layers.

Read:

- [`STATUS.md`](STATUS.md) for the current head, last full gate, active work,
  blockers, and next bounded tasks;
- [`docs/README.md`](docs/README.md) for the decision/programme/archive index;
  and
- the owning ADR before changing a representation, public physical surface,
  metadata contract, or artifact boundary.

## Authority and decision order

Implementation authority descends in this order:

1. accepted Kotlin language and Common stdlib semantics;
2. repository-wide compiler contracts and generated-source ownership;
3. accepted Kotlin/.NET ADRs;
4. current status, the way forward, and active programme gates;
5. verified historical review evidence.

Common Kotlin declarations and the stdlib generators are authoritative for
Kotlin behavior. A .NET source file supplies narrow `actual` declarations and
irreducible host operations; it does not fork a Common algorithm merely
because a BCL equivalent exists.

Kotlin metadata is authoritative for the logical Kotlin declaration. CLR
metadata is authoritative for the physical CLR declaration. For a
Kotlin-produced DLL, retain the complete KLIB contract and derive a truthful
CLR/Roslyn view in addition to it. For a foreign DLL, exact CLR metadata and
standard attributes are evidence from which FIR may derive a Kotlin view.
Never infer authoritative KLIB identity or Kotlin-only split-interface/C#
implementation-manifest contracts from CLR annotations.

This document records the target authors' working position. Do not describe a
choice as a Kotlin core-team decision unless the repository contains such a
decision.

## Module and dependency map

| Owner | Responsibility |
| --- | --- |
| `:core:language.targets.dotnet` | One logical .NET platform and the target-framework vocabulary |
| `:compiler:config.dotnet` | Generated primitive compiler keys and target/product policy |
| `:compiler:frontend.common.dotnet` | Objective PE/ECMA-335 facts, resolution, and physical evidence |
| FIR-owned .NET code | Kotlin types, symbols, contracts, enhancement, and diagnostics |
| `:compiler:cli:cli-base` | Neutral `.NET` content-root carrier |
| `:compiler:cli:cli-dotnet` | Pipeline sequencing and application of configuration |
| `:compiler:ir:backend.dotnet` | IR context, lowerings, intrinsics, CIL mapping/emission, and backend products |
| Kotlin library/ABI infrastructure | Embedded KLIB and physical ABI models/codecs |
| Gradle/packaging layers | Variant configuration, installation, dependency copying, and layouts |
| Roslyn authoring project | C#-facing source generation/analyzers over explicit interop contracts |

Enforce these directions:

- language-target code imports no compiler, FIR, IR, backend, CLI, Gradle, or
  Roslyn layer;
- `config.dotnet` imports no CLR loader, FIR, IR, backend, or CLI layer;
- `frontend.common.dotnet` imports neither compiler configuration nor FIR, IR,
  backend, CLI, Gradle, or Roslyn code;
- objective CLR parsing never creates Kotlin types, contracts, symbols, or
  diagnostics;
- FIR and backend may consume a neutral retained-declaration carrier, but
  neither may own a carrier required by the other;
- CLI orchestrates owners and does not become the owner of metadata, ABI, or
  codegen models;
- backend code must not be imported merely to obtain a target enum, content
  root, physical CLR model, or frontend validator; and
- `.NET` roots are never represented as `JvmClasspathRoot`.

Package placement mirrors the mature target that owns the same concern unless
the CLR creates a concrete different boundary. Do not introduce generic
domain/application/infrastructure packages or new Gradle modules solely to
shorten files or constructors.

See
[`docs/programmes/compiler-architecture.md`](docs/programmes/compiler-architecture.md).

## Target and product model

Kotlin/.NET has one unversioned logical Kotlin platform, `DotNet`. The target
framework is an independent configuration axis represented by
`org.jetbrains.kotlin.config.DotNetTarget`:

| Target | Applications | Libraries | May consume |
| --- | --- | --- | --- |
| `net48` | yes | yes | exact `net48` and `netstandard2.0` |
| `netstandard2.0` | no | yes | exact `netstandard2.0` |
| `net10.0` | yes | yes | exact `net10.0` and `netstandard2.0` |

`net48` and `net10.0` never consume one another. Product kind, future runtime
identifier, packaging, and target framework remain orthogonal. Do not put
product/layout/textual-CIL facts on `DotNetTarget`.

`-Xdotnet-target={net48|netstandard2.0|net10.0}` defaults to `net48`.
Configuration rejects invalid values and Standard executables before FIR or
codegen. Profile selection precedes lowerings and controls legal core-library
references, target metadata, runtime/stdlib variants, assembly writing, and
dependency compatibility. Kotlin semantics remain invariant even when
profile-specific CIL differs.

`net10.0` executable requests produce a DLL plus runtime configuration for
`dotnet exec`; do not invent a self-hosting modern ILAsm executable.
`netstandard2.0` produces only portable library DLLs. Framework and modern
ILAsm are target/profile writers and compatibility oracles, not interchangeable
requirements for every profile-specific feature.

Compiler arguments, Gradle compiler options, platform identity, target
attributes, and target/compilation ownership are generated or target-owned as
recorded in the integration ADRs indexed by
[`docs/README.md`](docs/README.md). Do not restore handwritten generated
argument/option models.

## Pre-ABI and publication policy

Nothing has shipped and no public Kotlin/.NET ABI 1 exists. Until an explicit
freeze is recorded:

- correct prototype binaries, names, metadata, runtime types, and layouts
  instead of preserving mistakes;
- move every producer, consumer, runtime, and tool together;
- bump and validate the relevant versioned schema when a physical contract
  changes;
- reject stale artifacts explicitly; and
- do not add compatibility shims for unpublished identities.

Developer-mode declaration eviction may help incomplete frontend work reach a
diagnostic fixpoint. A library or stdlib publication with any eviction is an
error; no successful artifact may silently omit declarations or their
dependents. The endpoint is a located frontend diagnostic.

Every Kotlin library is one self-describing CLR DLL containing its private
KLIB payload and physical binding data. Do not emit, install, or publish a
standalone or sibling Kotlin/.NET KLIB. See
[`docs/decisions/adr-self-describing-dotnet-library-dll.md`](docs/decisions/adr-self-describing-dotnet-library-dll.md).

## Metadata and CLR interoperability

The objective loader reports selected assembly identity, metadata tables,
signatures, custom attributes, and validated physical relationships without
guessing producer language. FIR owns Kotlin-facing enhancement and stability
rules; backend binding consumes retained identities after frontend decisions.
See
[`docs/decisions/draft-adr-clr-importer-boundary.md`](docs/decisions/draft-adr-clr-importer-boundary.md).

Standard CLR/Roslyn attributes are the shared foreign-language vocabulary only
where their exact target, payload, and semantics are verified. Unknown,
malformed, inapplicable, or state-weakening evidence contributes no Kotlin
effect. In particular, Roslyn member-state attributes do not override Kotlin's
stricter smart-cast rule for mutable properties.

When CLR metadata can express a truthful view of Kotlin semantics, emit both
the KLIB contract and the standard CLR view. Nullable attributes help C# and
foreign import; they do not replace Kotlin nullability. CodeAnalysis contract
attributes can reconstruct only the exact effects they express; Kotlin-only
contracts and declaration identity remain in KLIB.

C# authoring and export surfaces are explicit opt-in interop products.
Properties, defaults, callable adapters, collisions, nullability, and
implementation manifests are not inferred from ordinary Kotlin source names.
See
[`docs/decisions/adr-csharp-interface-source-authoring.md`](docs/decisions/adr-csharp-interface-source-authoring.md).

Friend visibility combines Kotlin module authorization with exact CLR
`InternalsVisibleTo` identity. Metadata-public compiler ABI is not ordinary
source `public`. See
[`docs/decisions/adr-friend-assemblies-and-compiler-abi.md`](docs/decisions/adr-friend-assemblies-and-compiler-abi.md).

## Runtime and stdlib ownership

Physical product ownership is:

- `Kotlin.Runtime.dll`: compiler/runtime identities and services required by
  generated code;
- `Kotlin.Stdlib.dll`: ordinary Kotlin library declarations and algorithms;
  and
- the user assembly: declarations and initialization owned by that
  compilation.

Do not place an ordinary stdlib algorithm in the emitter or runtime. Do not
copy it into every consumer. Common/generated source is compiled once into
the profile-selected stdlib; target-private external helpers cover only
irreducible CLR operations.

The direct repository stdlib product and temporary packaged-source fallback
must use the same canonical source set and produce the same logical KLIB/IL
content. The fallback is a bootstrap cycle breaker, not a second
implementation or final distribution design. Installed selection treats each
profile's runtime/stdlib as one pair and copies their bytes unchanged beside
applications.

Collections follow exact Common generator/source dependency closures.
Kotlin collection identity is not replaced by BCL collection identity;
explicit BCL adapters are a separate interop programme. Common I/O owns EOF
and rendering semantics; `.NET` supplies only narrow actuals and the
`Console.ReadLine` host operation.

See
[runtime/stdlib ownership ADR](docs/decisions/runtime-and-stdlib-ownership.md)
and
[`docs/programmes/common-collections.md`](docs/programmes/common-collections.md).

## Nullability model

Nullability uses a hybrid physical representation:

- a nullable non-erased CLR value position uses `System.Nullable<T>`;
- a nullable reference position uses the same CLR reference type plus truthful
  nullable metadata where representable;
- an erased `Any?`/object boundary represents both a boxed value and `null` as
  CLR object/null, not as a surviving `Nullable<T>` box;
- generic `T?` representation follows the type parameter's constraints and
  exact use-site requirements; and
- Kotlin-produced declarations retain their logical nullability in KLIB even
  when Roslyn-compatible nullable attributes are also emitted.

Do not globally map `T?`, `Any?`, or a nullable primitive to one CLR shape.
Boxing, unboxing, equality, type tests, arrays, generic constraints, returns,
and imported CLR enhancement must preserve the boundary at which nullability
is observed.

See the
[generic nullability ADR](docs/decisions/adr-hybrid-generic-nullability-and-covariant-returns.md).

## Core representation boundaries

- `System.Object` is the physical foundation for Kotlin `Any`; Kotlin-facing
  `equals`, `hashCode`, and `toString` semantics remain explicit compiler or
  runtime behavior. See
  [the `Any` foundation ADR](docs/decisions/system-object-any.md).
- An explicit generic upper bound `T : Any` remains authoritative in KLIB but
  contributes no CLR `class`, `valuetype`, or `System.Object` constraint: the
  Kotlin bound admits both non-null references and value types, which CLR
  runtime constraint flags cannot express as one parameter. Preserve the real
  generic token and every other supported bound. Roslyn `notnull` metadata is
  an additive future warning view, not Kotlin authority. See
  [the non-null generic-bound ADR](docs/decisions/non-null-generic-upper-bound.md).
- `CharSequence` uses a classified `System.Object` carrier because sealed
  `System.String` cannot implement a Kotlin-owned interface. Strings retain
  identity; Kotlin implementations occupy the runtime capability interface;
  calls, casts, and type tests share one classifier. Do not wrap strings,
  constrain generic parameters to the marker, or admit arbitrary objects. See
  [the `CharSequence` carrier ADR](docs/decisions/char-sequence-carrier.md).
- `Appendable` and `StringBuilder` are Kotlin-owned `Kotlin.Stdlib` identities.
  The builder may use `System.Text.StringBuilder` only as private storage;
  never expose that storage, map the Kotlin class directly to it, or add the
  BCL builder to the `CharSequence` classifier. See
  [the builder ADR](docs/decisions/appendable-string-builder.md).
- All eight signed Common primitive arrays use Kotlin-owned wrapper identity
  around exact CLR vector storage (`Boolean`, `SByte`, `Int16`, `Int32`,
  `Int64`, `Single`, `Double`, and `Char`). Do not expose raw CLR vectors as
  Kotlin array identity, collapse specialized arrays with `Array<T>`, or infer
  unsigned-array support from this completed family. See
  [the primitive-array ADR](docs/decisions/primitive-arrays.md).
- An invariant generic array whose element is one concrete nullable signed
  Common primitive uses the exact closed `System.Nullable<V>[]` carrier for
  all eight families. Preserve its ordinary `Array<E>` identity and its
  natural C# `V?[]` view. Do not replace it with `object[]`, collapse it into a
  specialized primitive-array wrapper, infer support for open `Array<T?>` or
  input projections, or manufacture value-vector covariance by copying.
  `Array<*>` follows its separate classified erased-view decision below.
  See [the primitive-array ADR](docs/decisions/primitive-arrays.md).
- Collection-to-array uses the exact Common loops. A replacement generic
  vector preserves the supplied array's runtime element type; do not erase it
  to `object[]`, substitute a target loop, or import JVM's Java-specific null
  terminator. See
  [the collection-to-array ADR](docs/decisions/collection-to-array.md).
- A non-reified explicit cast from an erased object to an open type parameter
  uses CLR `unbox.any !n`/`!!n`, which handles value and reference
  instantiations. Do not use that throwing operation to implement `as? T`.
- Explicit casts to the eight selected Common primitive scalars preserve exact
  boxed identity and never perform numeric conversion. Checked non-null casts
  unbox the exact `System.<T>` box; checked nullable casts unbox as
  `Nullable<T>`; safe casts test the exact underlying box and materialize the
  existing `Nullable<T>` result. Do not infer value-class identity from the
  same storage shape.
- `Array<*>` uses `System.Array` only as its physical erased storage view. All
  exact CLR SZ vectors widen to it without copying; `size`, reads, and erased
  iteration operate on the original array, and writes remain projected out.
  Runtime tests/casts must use the one runtime SZ-array classifier so
  rectangular and non-zero-based arrays are not silently admitted. Do not use
  `object[]`, wrap or copy value vectors, infer star identity from a bare CLR
  signature, or generalize this rule to input/out projections, open
  `Array<T?>`, or other Kotlin generic classes. See
  [the star-projected-array ADR](docs/decisions/star-projected-arrays.md).
- Every Kotlin-owned ordinary generic class uses a non-generic canonical CLR
  interface for Kotlin storage, dispatch, projections, and erased casts while
  retaining its invariant arity-suffixed CLR class as the typed implementation
  and C# capability on the same object. Canonical bridges own erased member
  barriers; runtime tests/casts must also verify ancestry from the exact
  producer-recorded open generic class definition, never the interface alone.
  Do not use a closed `C<T>` as Kotlin identity, wrap or reinterpret a cast,
  erase away the typed CLR class, or choose carriers from local provenance.
  Any ordinary callable parameter containing this erased view receives a
  stable whole-Kotlin-signature physical name before an overload collision
  exists; never derive ABI naming from the current overload set. A canonical
  slot of a cross-module declaration uses its public KLIB identity; a slot
  below a private/local owner uses only the explicit stable structural codec.
  Never hash file-local IR signatures, rendered types, object identity,
  declaration order, or source offsets into a physical name. A canonical
  value upcast to a non-generic CLR base uses only a proven same-object checked
  cast from its typed ancestry; the canonical interface cannot inherit a CLR
  class. Keep owner-relative exact interface capabilities on typed `C<T>`;
  never fabricate an `I<object>` canonical edge. Compiler-generated default-
  argument dispatchers are implementation helpers, not canonical source-member
  slots, and recover any exact typed owner only from authoritative IR.
  See [the generic-class ADR](docs/decisions/generic-class-erased-identity.md).
- Reified array construction, once enabled, must reuse the ordinary carrier
  selected after shared IR substitution. Do not add a reified-only array
  token/wrapper, fall back to `object[]` for an unsupported element, or use a
  closed typed CLR generic class as Kotlin element identity. Array-allocation
  readiness does not enable either public reified gate: `KClass`, `KType`,
  enums, annotations, remaining classifier families, the final substituted
  type-operator matrix, and the physical throwing-stub contract stay one
  complete feature boundary. See
  [the reified-array decision](docs/decisions/reified-array-operations.md).
- Ordinary runtime type tests evaluate their operand once at the erased object
  boundary, implement Kotlin nullable-target semantics before the non-null
  check, and then use either an existing Kotlin classifier or one physically
  exact CLR carrier. Exact carriers currently include the eight boxed Common
  scalars, `Any`, `String`, non-generic classes/interfaces, supported
  primitive-array wrappers, and fully known CLR vectors. Never admit a
  `GenericInstance` as Kotlin runtime identity: Kotlin-owned generic classes
  are declaration-erased on mature targets even though their current CLR
  storage is closed. `Array<*>` is the one selected structural erased-array
  case above; its classified `System.Array` path must not leak into ordinary
  generic-class RTTI.
- Variant/generic interfaces use the versioned split-interface/bridge model
  where one CLR interface cannot truthfully carry all Kotlin views. MethodImpl
  and effective interface maps are semantic ABI, not IL spelling trivia. See
  [the generic-interface draft](docs/decisions/draft-adr-generic-interface-abi.md).
- Interface default bodies are profile-aware. Do not simulate modern DIM into
  the Framework ABI or reject a Kotlin body without applying the accepted
  fallback policy. See
  [the interface-default ADR](docs/decisions/adr-profile-aware-interface-default-implementations.md).
- Function values use the selected erased `FunctionN` identity plus exact
  execution capabilities; callable and property-reference identity is a
  separate physical contract. See
  [the callable/reference draft](docs/decisions/draft-adr-callable-and-reference-abi.md).

Exact private lowering machinery is not automatically public ABI. Tests own
private field disambiguation, nested equality views, and conformance
mechanics; the documentation index identifies the implementation/verification
files awaiting relocation.

## Exception model

All throwable values remain the original `System.Exception` objects in one
physical CLR universe. Exact Kotlin or CLR identities are physical only when
truthful. Broad or non-physical Kotlin subtype relationships are enforced by
one versioned classifier used consistently by type tests, casts, catches, and
metadata consumers.

Kotlin `Throwable` state missing from `System.Exception`, especially
suppressed exceptions, is identity-associated runtime state on the original
object. Do not wrap, clone, translate, or mutate foreign extensibility
surfaces such as `Exception.Data`. Stacktrace operations preserve CLR
diagnostic facts and compose Kotlin suppressed-state semantics with
reference-identity cycle handling.

See the
[classified-exception ADR](docs/decisions/classified-clr-exceptions.md).

## Static placement and initialization

Objects and companions follow Kotlin/JVM first-active-use semantics on CLR
`.cctor` ownership. A plain object owns `INSTANCE`. A companion singleton
field lives on the selected enclosing static owner, using a non-generic holder
when a generic owner would otherwise create one singleton per construction.

Companion backing state and init blocks remain on the companion instance; the
selected owner's `.cctor` constructs it. This is the accepted CLR nested-type
delta from JVM field hoisting and is observable only under initialization
re-entrancy.

The **beforefieldinit decision** is to omit `beforefieldinit` for
Kotlin-initialized types so the CLR cannot run their initializer earlier than
Kotlin permits. Failed initialization preserves one Kotlin-visible failure
identity/state above CLR `.cctor` caching rather than exposing unstable
wrapper chains.

See the
[static-placement ADR](docs/decisions/adr-companion-static-placement-and-initialization.md)
and the
[initialization-failure ADR](docs/decisions/adr-kotlin-static-initialization-failures.md).

## Diagnostics and CIL production

Unsupported IR fails through a specific `dotNetUnsupported()` diagnostic
path. Never emit plausible fallback IL such as empty strings or zero values.
Do not let a known-invalid construction reach ILAsm or become JIT-poisoned;
interface/generic mapping mistakes can assemble successfully and fail only at
type load or dispatch.

Textual IL plus ILAsm is the accepted prototype production path. The endpoint
is a structured compiler-owned CIL/metadata model with deterministic text and
direct-PE sinks; do not add a sidecar merely to exchange one external process
for another. See
[CIL/PE production ADR](docs/decisions/cil-and-pe-production.md).

Any IL spelling not already golden-pinned must first be assembled and executed
in a temporary probe outside the repository. Verify physical metadata through
tables, reflection/interface maps, and real dispatch as appropriate; substring
checks alone are insufficient for semantic ABI.

## Contribution workflow

For every bounded semantic feature:

1. Start from authoritative Common Kotlin/source-generator behavior.
2. Document how JVM, JS, Wasm, and Native handle the concern.
3. Deviate only for a concrete CLR constraint, not a limitation of the current
   target implementation.
4. Attack the preferred design and state what a Kotlin-aligned target team
   would likely reject; do not manufacture core-team endorsement.
5. Amend the owning ADR/programme before implementing the representation or
   semantic choice.
6. Implement the complete bounded feature across every producer and consumer.
7. Test adversarial source, metadata, dispatch, profile, artifact, and runtime
   boundaries.
8. Commit and push the completed feature with its ADR and status update.

## Decision escalation and locks

Exhaust authoritative Common source, shared compiler machinery, mature-target
precedent, CLR facts, and accepted Kotlin/.NET decisions before escalating a
question. If those sources still do not determine a material choice and the
alternatives would be difficult to change after more stdlib or ABI work lands,
stop at that boundary and ask the user. Continue reversible work and features
outside the affected area; an unanswered question does not stop the whole
target.

If the user has not answered within one hour, add a focused
`docs/decisions/open-question-<topic>.md`. It must contain the exact root
question, verified facts, every viable answer, the target authors' best answer,
the consequences of each answer, and the complete follow-on question tree.
Answer each follow-on question as far as the evidence permits and recurse when
an answer creates another choice. Do not implement the hard-to-reverse portion
while its question remains open.

Every active open-question document must also have a row in the table below.
The row identifies exactly which stdlib features, representation, metadata,
or ABI work is locked and what work remains safe. Remove the row only when the
answer has been incorporated into the owning ADR and implementation plan.

| Open question | Hard-to-reverse work locked | Work that may continue |
| --- | --- | --- |
| None | None | Every currently selected bounded feature |

Also:

- preserve unrelated worktree changes and do not modify another branch;
- work directly on `dotnet`; do not create worktrees;
- never edit `*Generated.java`, generated configuration keys, or API baselines
  by hand—run the owning scoped generator and critically review its output;
- the Kotlin 2.5 bootstrap uses name-based destructuring `[a, b]` for
  data-like carriers; do not introduce positional `(a, b)` destructuring that
  the bootstrap cannot compile;
- do not pin a frontend-rejected source shape in an IL-text test; use the
  frontend diagnostic suite;
- do not broaden a feature to an adjacent parked programme merely because it
  becomes visible during implementation; and
- keep temporary probes, playgrounds, and IDE projects outside the repository.

Ordinary non-reified inline support is selected by
`docs/programmes/inline-functions.md`. Non-linking inline deserialization may
bind an exact public signature through `IrBuiltIns.symbolFinder`: that finder
is the logical dependency graph already selected by the frontend, including
library B for a body owned by library A. Do not replace it with DLL discovery,
physical-name lookup, classpath arbitration, or a general IR linker. Surviving
external calls still bind only through the producer-recorded physical ABI.
After the shared inline prefix, traverse the actual IR graph and reject every
remaining unbound symbol before target lowerings; do not make an arbitrary
lowering or the CIL emitter the missing-dependency detector.
An inlined caller-targeted return may occur with older expression operands on
the CIL evaluation stack. Preserve Kotlin evaluation order: spill the return
value, drain only those older operands, reload the result, and then `ret`, or
drain before the existing protected-region `leave`. Never repair this by
rewriting a Common body or evaluating the returning operand early.
An `@InlineOnly` function remains logically public with its body authoritative
in KLIB, but its CLR MethodDef is `assembly`-visible. Apply the same rule to an
accessor whose property carries the annotation. A separate Kotlin consumer
must inline it; never widen it into C# API, mark it as public compiler ABI, omit
the physical body, or allow an external fallback call. See
[the inline-only physical ABI ADR](docs/decisions/inline-only-physical-abi.md).
Do not enable either reified-inline support gate until the complete operation
closure in `docs/programmes/inline-functions.md` is truthful. In particular,
never compile a Kotlin-owned generic-class type test/cast as closed CLR
`C<T>` identity: Kotlin generic arguments do not participate in runtime class
identity, and the private data-class equality view is not a general carrier.
Physically exact non-generic casts and scalar/array prerequisites may land
independently, but must reject rather than generalize to `GenericInstance`.
Reified Common stdlib declarations remain outside the product meanwhile.
Suspend inline functions, enums, annotation classes, value classes, reflection,
coroutines, concurrency primitives, and broad KMP/Gradle product integration
remain separate programmes until `STATUS.md` or the way forward selects one.

## Verification contract

The strict semantic commit gate is:

```text
./gradlew :compiler:backend.dotnet:dotNetTest --rerun -q --no-daemon
```

Do not trust quiet Gradle success alone. Audit every JUnit XML file under:

```text
compiler/fir/fir2ir/build/test-results/dotNetTest/
compiler/tests-integration/build/test-results/dn/
```

`STATUS.md` owns the expected current file/test totals. Strict mode turns a
missing required toolchain or Smart App Control refusal into failure. The
short internal `dn` task name preserves Framework CLR/ILAsm path-length
budget; invoke the backend aggregate rather than treating `dn` as public API.

A Markdown-only ownership, history, rename, or index change does not require
the compiler gate. It does require a repository-reference audit, local-link
validation, whitespace/diff review, and confirmation that no semantic file is
staged.

Generate FIR-to-IR runners with:

```text
./gradlew :compiler:fir:fir2ir:generateTests
```

Do not use the repository-wide aggregate merely for this target. Generated
runners live under `build/tests-gen` and are not committed. To update an
IL-text golden, change the `.kt`, run the scoped test with
`-Pkotlin.test.update.test.data=true`, then read and assemble the resulting
`.txt`; generated goldens can faithfully preserve broken IL.

Focused compilation/tests are useful while iterating but never replace the
strict gate for a completed semantic feature. Before committing, verify that
status shows only intended files.

## Box tests

Like mature targets, Kotlin/.NET box tests execute on real runtimes. PSI and
LightTree compile the same target-owned corpus:

- `net10.0` produces a DLL and runs it with the signed `dotnet exec` host;
- `net48` produces an executable assembly loaded and invoked by the signed
  Windows PowerShell CLR 4 host; and
- no lane directly launches a fresh unsigned executable.

The shared `kotlin-dotnet-framework-toolchain` JUnit resource lock serializes
the physical Framework ILAsm/CLR4 lane because those external tools are
nondeterministic under unbounded fan-out. Do not serialize ordinary compiler
or modern-runtime work.

The IL-text suite compares text even without a toolchain and assembles every
supported golden with each available compatible ILAsm. Text equality does not
replace execution: add real runtime coverage for dispatch, exception,
initialization, reflection-map, cross-assembly, or profile behavior.

Adversarial coverage should include nullable/value/reference and widened
forms, hostile user implementations, empty/singleton/multiple boundaries,
separate producers/consumers, direct/fallback/installed products, malformed or
inapplicable foreign metadata, both parsers, and every supported runtime/profile
that can observe the feature.

## Modern .NET toolchain

The durable per-user toolchain lives under:

```text
%LOCALAPPDATA%\kotlinc-dotnet\toolchain\
```

It contains the pinned .NET 10 host/SDK/reference pack and modern CoreCLR
ILAsm. Provision or repair it with
`compiler/ir/backend.dotnet/tools/provision-dotnet-toolchain.ps1`.
Production CIL assembly does not depend on Roslyn.

Modern assembler discovery order is:

1. `KOTLIN_DOTNET_ILASM` for an exact `ilasm.exe`;
2. `KOTLIN_DOTNET_ROOT` containing `ilasm/` and `dotnet/`;
3. the durable per-user location above; and
4. legacy Framework ILAsm only where the selected profile permits it.

Modern C# integration discovers `csc.dll` and the reference pack from the same
toolchain root and invokes Roslyn through the discovered `dotnet` host.

A CoreCLR DLL needs a sibling runtime configuration and runs through
`dotnet.exe exec`. Prefer the signed host over direct execution of unsigned
test binaries.

Smart App Control can refuse to load freshly assembled unsigned content. A
normal optional lane reports that environmental inability as a visible skip;
the strict required-toolchain lane fails. Never perturb hashes, restructure a
program, weaken a test, or otherwise attempt to bypass the classifier. Valid
options are a host without SAC, a trusted signature, or a user-controlled OS
policy change.

## Documentation maintenance

- `AGENTS.md` owns only this bootstrap contract.
- `STATUS.md` owns current state and verification.
- `docs/programmes/way-forward.md` owns future gates and ordering.
- active programme files own one current workstream.
- ADRs own durable decisions, invariants, consequences, and rejected
  alternatives.
- Git owns chronological implementation history.
- tests/CI own executable evidence.
- `docs/archive` owns immutable snapshots and superseded history.

An ADR contains no current test count or commit log. A `draft-` filename must
agree with `Status: Draft`; accepted pre-ABI decisions are renamed and state
their freeze conditions. A semantic or ABI change updates its owning ADR in
the same feature commit.

When finishing a feature, update `STATUS.md` with the semantic head, fresh
gate, remaining blockers, and next bounded work. Keep `HANDOVER.md` as a
compatibility pointer only.
