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

Common `Comparable<T>` uses the profile-selected CLR `System.IComparable` and
`System.IComparable<T>` views on one object, but Kotlin interface calls retain
ordinal String and Kotlin floating ordering through the runtime semantic
boundary. Do not replace that boundary with host `CompareTo` wholesale or
publish an enum-only comparison substitute. See
[`docs/decisions/comparable-clr-views.md`](docs/decisions/comparable-clr-views.md).

Kotlin-owned enums are reference classes. Their one erased `Kotlin.Enum` base
is physically owned by `Kotlin.Runtime`; concrete enum classes and Common
`EnumEntries` behavior remain in their declaring Stdlib/user assembly. The
physical, member-free `Kotlin.Enums.EnumEntries` capability is owned by Runtime
so Runtime enums can expose `entries` without a Runtime-to-Stdlib dependency;
the Common generic declaration, `EnumEntriesList`, factories, and algorithms
remain Stdlib-owned, and Stdlib must not emit a duplicate interface TypeDef.
Runtime must never reference Stdlib to obtain the base, and no CLR value enum or
Runtime-only token may become a second Kotlin enum identity. The stdlib
expect/actual Enum sources are resolution-only for CIL emission. See
[`docs/decisions/ordinary-enum-reference-classes.md`](docs/decisions/ordinary-enum-reference-classes.md).
Enum comparison accepts different private entry-body subclasses of the same
enum declaration, but a broad CLR/unchecked call with an entry of another enum
must fail at that use with the classified CLR `InvalidCastException`; ordinal
alone is never a cross-enum ordering relation.

`KCallable` visibility and modality are declaration-owned Kotlin facts. Derive
them from FIR/IR/KLIB or the foreign CLR importer, never from the selected CLR
MethodDef, bridge, or emitted accessibility. The physical `KVisibility` is an
ordinary Kotlin reference enum owned by Runtime because Runtime callable bases
return it directly. See
[`docs/decisions/callable-visibility-and-modality.md`](docs/decisions/callable-visibility-and-modality.md).

Kotlin metadata is authoritative for the logical Kotlin declaration. CLR
metadata is authoritative for the physical CLR declaration. For a
Kotlin-produced DLL, retain the complete KLIB contract and derive a truthful
CLR/Roslyn view in addition to it. For a foreign DLL, exact CLR metadata and
standard attributes are evidence from which FIR may derive a Kotlin view.
Never infer authoritative KLIB identity or Kotlin-only physical-ABI/C#
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

The emitter's module-wide declaration/function indexes are shared fixpoint
state. A class may layer a bounded local physical-signature view over those
indexes, but must not clone a complete module index per class or per fixpoint
round. Preserve the existing scope rule: a nested class starts from the shared
module view, not from its enclosing class's temporary member overlay.

The live module declaration set is authoritative during emission. Resolution
fallbacks may reconstruct external or resolution-only Stdlib type information,
but must never make a local declaration that failed or left the codegen gate
emittable again. Every requested fixpoint round must make monotonic progress;
fail fast on a no-progress round instead of retrying. Stable classifier facts
may be cached once per lowered compilation by `IrClass` identity, following
Wasm's module-metadata lifetime. Do not cache mapped CLR types, mapper-view
choices, target-profile facts, assembly-reference effects, or live
emitability, and never retain IR in a static/compiler-wide cache.

An external Kotlin-library binding index may cache its final kind-prefixed
public ABI key by `IrDeclaration` identity, including a missing key, for that
one `DotNetExternalDeclarations` lifetime. This mirrors the shared KLIB
declaration table without making the rendered CLR-binding key authoritative
over KLIB. Keep the declaration kind in the cache entry and reject a request
for the same declaration under a different kind. Do not share this cache
blindly across lowerings or emission: a query can still receive local mutable
IR, and JVM's bridge-signature cache documents the same stale-signature risk
when override/type shape changes.

Shared compiler performance reporting must identify this target as
`PlatformType.DotNet`; do not label it JVM or Common to reuse an existing
bucket. Keep top-level measurements sequential like the other compiler
pipelines: in-memory library metadata/IR production is `IrSerialization`, .NET
IR lowerings are `IrLowering`, and CIL emission plus assembly is `Backend`.
The self-describing KLIB resource depends on the emitter's completed physical
declaration index, so measure its packing as a dynamic `Backend` subphase. Do
not span `KlibWriting` across lowering/backend work or otherwise nest
top-level `PerformanceManager` phases; the shared manager is intentionally not
a nested timer.

An external nested CLR type is represented by a simple nested class name plus
its `DotNetIlClassInfo.enclosingClass`. Do not put `/` inside one
`ilClassName`: `[A]'Outer/Nested'` is a flat TypeRef, while
`[A]'Outer'/'Nested'` is the real ECMA-335 nested reference.

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

The Common contracts DSL and compiler effect model remain authoritative for
Kotlin data-flow and calls-in-place semantics. A CLR/Roslyn attribute is an
additional exact projection or foreign-input fact, not a second Kotlin
contract store. Import an attribute effect only where its target and effect
algebra match Kotlin and still apply Kotlin stability rules; retain effects
that CLR cannot express in KLIB alone. Do not infer Kotlin contracts merely
because a C# analysis attribute is suggestive.

The closed first export projection contains only `NotNull`, `NotNullWhen`,
`NotNullIfNotNull`, `DoesNotReturnIf`, and `DoesNotReturn`. FIR2IR derives its
versioned neutral carrier; only explicit exports consume it. Emit the standard
attribute only when the selected profile physically supplies the exact type
(`net10.0` does; `net48` and `netstandard2.0` do not), and omit any fact whose
named/defaulted parameter is absent from a generated overload. Do not widen
this set without updating the Common-contracts ADR and proving implication
direction, multiplicity, target, profile identity, Roslyn behavior, and KLIB
independence after attribute stripping.
See the
[Common-contracts ADR](docs/decisions/common-contracts-product.md).

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

A self-describing DLL's packed KLIB retains the DLL itself as
`KotlinLibrary.path` and its resolved physical path as
`KotlinLibrary.canonicalPath`. Never extract the resource to manufacture a
second filesystem identity or treat an embedded ZIP path as the library's
canonical path.

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
- A retained method constraint `C : R` widens with `box C; unbox.any R`,
  including transitive relative bounds. `box C` alone is not an `R` value when
  `R` has a value-type substitution. Preserve this rule through direct,
  separate-library, and erased foreign-callable fallback paths.
- A nullable relative method bound `X : Y?` remains exact in KLIB but
  contributes no CLR `X : Y` constraint. That spelling would reject legal
  value substitutions such as `X = Int?`, `Y = Int`; `object` is not a CLR
  constraint. Preserve both generic tokens and reconstruct the logical bound
  in `KType`.
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
  signature, or generalize this rule to input projections, open `Array<T?>`,
  or other Kotlin generic classes. A bounded `Array<out E>` separately uses
  the same `System.Array` carrier but recovers its stronger KLIB-declared read
  type at each use; it never permits writes. See
  [the star-projected-array ADR](docs/decisions/star-projected-arrays.md) and
  [the bounded-output projection ADR](docs/decisions/bounded-output-projected-arrays.md).
- A Kotlin reference `vararg E` has source type `Array<out E>` but physical
  declaration type `E[]`. Map that exact vector from the vararg marker in both
  pre- and post-lowering signatures, so producer CIL, embedded physical binding,
  and separate consumer calls agree. Do not generalize this exception to an
  ordinary bounded-output array parameter; that remains `System.Array`.
- Every Kotlin-owned ordinary generic class has one canonical non-generic CLR
  owner, one authoritative declaration-erased runtime classifier/virtual ABI,
  and one authoritative mutable state. Erasure is authoritative semantics and
  canonical Kotlin ABI, not a permanent prohibition on private physical
  reification. KLIB remains authoritative for logical parameters, arguments,
  variance, projections, nullability, and bounds. Public, protected, and
  cross-module owner-dependent positions use `object`, an erased upper bound,
  or an accepted erased Kotlin carrier; generic results narrow only at their
  logical use site. The current baseline uses the same erased forms in private
  storage, but that private layout is not frozen. A closed CLR `C<T>` is neither
  Kotlin runtime identity nor the canonical class ABI. Imported CLR generics
  remain reified and explicit typed C# export is a separate fail-closed
  product. CLR generics may describe method parameters, imported types,
  truthful interface capabilities, export artifacts, scalar replacement, or
  removable private helpers/storage; they must never redefine runtime identity,
  authoritative state, delayed-use behavior, or class-dependent dispatch.
  Do not emit the prototype's canonical interface, typed class, capability
  probes, ancestry classifier, class bridge records, wrappers, copies, or
  duplicate authoritative storage. Do not add a `BoxImpl<T>`-style alternative
  implementation of ordinary Kotlin objects or make an annotation/compiler
  switch alternate one declaration between erased and CLR-generic meanings. A
  future typed-storage/deoptimization scheme requires a separate ADR covering
  concurrency and must remain removable without changing public/protected ABI,
  supported reflection, casts, identity, virtual dispatch, cross-module
  semantics, or DLL signatures. Optimize only after measurement justifies the
  compiler, metadata, JIT/AOT, and maintenance cost. Never narrow a nested
  carrier such as `Collection<object>` to `Collection<int>` or special-case one
  Common method.
  Kotlin/.NET deliberately selects classifier-only unchecked casts and delayed
  typed failure for Kotlin-owned classes as a cross-target compatibility
  contract, although the language specification would permit earlier platform
  failure. Preserve same-object identity, mutation, virtual dispatch, and
  separate compilation. An export-created same-object CLR subtype or an export
  adapter requires a separate explicit export contract; neither changes the
  underlying Kotlin classifier or creates competing state. An arbitrary
  existing instance needs an adapter rather than a retroactive CLR generic
  identity. The public rule is: Kotlin classes remain Kotlin classes; C#
  consumes only explicitly exported, safe .NET APIs. See
  [the generic-class ADR](docs/decisions/generic-class-erased-identity.md).
  A distinct true CLR-generic owner plus complete erased Kotlin capability and
  early failure of physically incompatible unchecked casts is explicitly on
  hold, not silently rejected or authorized. Until the generic-class ADR is
  reopened, do not emit Kotlin-owned `C<T>` owners, change cast timing, weaken
  delayed-use tests, or build ABI on that alternative. The parked question
  locks only that owner/ABI choice; continue Common stdlib, CLI IR,
  callable/reflection, imported CLR generics, generic methods, explicit export,
  and removable private optimization work. See
  [the reopening programme](docs/programmes/generic-class-owner-reopening.md).
- `KClass` is a nominal Kotlin runtime value over exact or classified CLR type
  evidence. KLIB owns logical `KClass<T>` and declaration identity;
  `System.Type` is a retained physical bridge and never becomes `KClass` or
  `KType` authority. Static and dynamic literals share the ordinary runtime
  classifiers, generic arguments do not participate in class identity, and
  equality/hash never use names. Local/anonymous naming attributes carry only
  nullable source names, while exact Kotlin exception-constructor ids reuse
  weak identity-associated throwable state. Do not infer `KType`, callable or
  property reflection, or reified support from this floor. Class-level runtime
  annotation discovery is a separately admitted JVM-shaped platform extension.
  See [the KClass decision](docs/decisions/kclass-and-class-literals.md).
- `KType` is the Common logical type graph, not `System.Type` plus flags. The
  backend materializes it after shared reified substitution, preserving
  classifiers, arguments, projections, nullability, declaration parameters,
  recursive bounds, and stable container identity. Allocate all reachable
  parameter identities before initializing any bound. A classifier with no
  physical CLR Type uses a separate KLIB-mangled logical key; never use its
  display name as identity. The minimal physical `KType` interface lives in
  `Kotlin.Runtime.dll`, beside `KClass` and runtime-owned `KCallable`; Common
  behavior and `KTypeImpl` remain in `Kotlin.Stdlib.dll`. Do not create a
  Runtime-to-Stdlib assembly dependency, weaken the callable slot to `object`,
  or emit a second Stdlib `KType` identity. Compiler helpers are runtime/stdlib ABI, and a
  separate-module test must use the CLI's metadata-serialization and
  finalization phases rather than a same-invocation source dependency. See
  [the KType decision](docs/decisions/ktype-and-typeof.md).
- A Kotlin annotation is one concrete sealed CLR `System.Attribute` subtype
  with Common-generated value semantics. KLIB owns its logical declaration,
  values, defaults, targets, retention, and applications. Only a complete
  runtime-retained application whose parent, constructor, and every fixed
  value have an exact ECMA-335 representation receives an additional CLR
  custom-attribute row; otherwise omit the whole derived row and retain the
  KLIB application. Never infer `KClass` as `System.Type`, Kotlin reference
  enums as CLR enums, primitive-array wrappers as raw vectors, or nested
  annotations as CLR constants. `KClass.annotations` reconstructs
  Kotlin-produced runtime applications through a private factory derived after
  KLIB serialization, never by decoding those narrower CLR rows. Every
  Kotlin-produced assembly carries a private marker; a missing factory in such
  an assembly means an empty list. Only an unmarked foreign assembly uses
  inherited CLR custom-attribute discovery, and mapped BCL-backed Kotlin
  classifiers expose no host implementation attributes. `KCallable` is the
  JVM-shaped platform `KAnnotatedElement` view on the same function/property
  reference object. Kotlin callable applications come from the exact
  KLIB-derived reflection target; imported CLR methods/properties use their
  retained declaring type and exact metadata token. Never merge a property's
  annotations with getter/setter annotations or match foreign members by name.
  `KCallable.returnType` uses the exact reflection target and the shared
  logical `KType` graph: Kotlin declarations are KLIB-derived, imported CLR
  declarations are importer-enhanced IR, and generated `invoke` adapters or
  runtime CLR reflection are never signature authority. `KCallable.typeParameters`
  is the JVM-shaped declaration-owned extension above Native's smaller floor.
  Return types, every callable-owned parameter, their recursive bounds, and
  reachable enclosing parameters must be allocated in one graph. The exposed
  list follows JVM ownership: functions and generic extension properties expose
  their own parameters, while constructors expose their constructed class's
  own parameters, always in declaration order. An exposed parameter must be
  the exact classifier object reused by return and bound types. Bound and
  unbound references retain the unbound declaration owner. Never build
  independent per-property graphs, enumerate physical CLR generic parameters,
  or leak enclosing parameters into the own list. `KCallable.parameters` and
  `KParameter` extend that same graph. Follow JVM ordering (instance, context,
  extension, values), omit captured receiver prefixes and reindex bound
  references, retain inherited Kotlin defaults, and keep vararg types as their
  array type. Parameter annotations belong to the exact Kotlin parameter;
  imported CLR parameters instead use their retained Param row for names,
  `ParamArray`, and honest custom attributes. CLR optional metadata does not
  imply Kotlin `isOptional`. Runtime owns the erased callable slot but never
  names Stdlib's `KParameter` implementation: compiler-produced references pass
  the Stdlib factory once and Runtime caches the resulting read-only list.
  Direct member-extension references are prohibited by the Common frontend;
  their two-receiver view waits for member enumeration rather than a target
  source exception. Backend symbol wiring must feature-detect every platform
  extension: `-no-stdlib`, malformed-library, and older-surface diagnostic
  paths may initialize the backend without the property and must not crash
  before their intended diagnostic.
  `KCallable.call` is positional JVM-shaped invocation through the callable's
  already selected erased `FunctionN` execution capability. Runtime may check
  the exposed arity and dispatch to `Invoke`, but it must never rediscover a
  member through `System.Reflection`, name, token, or CLR signature. Defaults
  remain required positions, a vararg consumes one array argument, properties
  call their getter, and target exceptions propagate unchanged. The logical
  `R` stays KLIB-authoritative while the physical `Call(object[])` result is
  object-shaped. Only true reflective reference classes implement the slot;
  internal getter/adaptation callable helpers that share
  `FunctionReferenceBase` must not become KCallable accidentally.
  `KCallable.callBy` extends the same parameter-identity graph for the admitted
  `KFunction0` through `KFunction3` closure. Map presence distinguishes an
  explicit null from omission; absent optional values reuse the shared
  default-argument lowering; absent varargs receive a fresh exact array; and
  missing required parameters follow the JVM failure contract. Ordinary class
  default dispatchers use one JVM-shaped static compiler ABI with an explicit
  receiver so source and reflective calls share virtual, separate-library
  behavior. Runtime must not rediscover CLR members or own Kotlin default-mask
  layout. The JVM-shaped `KFunction` declaration flags (`isInline`,
  `isExternal`, `isOperator`, `isInfix`, and `isSuspend`) are one shared
  property capability inherited by every admitted `KFunction0` through
  `KFunction3`. Read them only from the exact KLIB/importer-IR reflection
  target; generated invoke adapters and runtime CLR reflection are never flag
  authority. Store the facts in the existing private reference-flag carrier
  and inherit its virtual-final getters; do not emit five getter/property pairs
  per reference or make the base itself implement `KFunction`. Constructors
  report false, and publishing the suspend/external facts does not admit those
  execution features. Typed foreign attribute
  import, foreign CLR generic methods, accessor objects, `KCallable`
  visibility/modality, and broader member reflection remain separate. A
  foreign generic method must continue to fail the current interface importer
  closed until its
  own complete FIR/import/binding feature lands; never decode it privately in
  callable reflection. See
  [the annotation-value decision](docs/decisions/valued-annotation-classes.md),
  [the class-discovery decision](docs/decisions/annotation-discovery.md), and
  [the callable-discovery decision](docs/decisions/callable-annotation-discovery.md), and
  [the callable-return decision](docs/decisions/callable-return-types.md), and
  [the callable-type-parameter decision](docs/decisions/callable-type-parameters.md), and
  [the callable-parameter decision](docs/decisions/callable-parameters.md), and
  [the positional-call decision](docs/decisions/callable-positional-invocation.md), and
  [the named-call decision](docs/decisions/callable-named-invocation.md), and
  [the function-flag decision](docs/decisions/function-declaration-flags.md).
- Reified functions use shared IR call-site substitution only. A selected KLIB
  body is authoritative; CLR generic dispatch, `System.Type`, and a closed
  Kotlin-owned `C<T>` are never alternate reification mechanisms. Preserve
  bodyless compiler intrinsics during the pre-serialization stage, then run the
  target-stage shared inlining completion before enum and physical-remainder
  lowerings. Array operations reuse the ordinary substituted carrier: never add
  a reified-only token/wrapper, fall back to `object[]`, or change Kotlin-owned
  generic identity. A truthfully representable open CLR signature may receive
  an assembly-visible throwing remainder; an unrepresentable signature is
  omitted. Neither form belongs to the producer physical declaration index or
  explicit C# export. No Kotlin call may reach either remainder. `KType` and
  `typeOf` compose this substitution path through their own completed logical
  graph; suspend inline and future classifier families
  remain separate closures. See [the reified-inline decision](docs/decisions/reified-inline-functions.md)
  and its [array prerequisite](docs/decisions/reified-array-operations.md).
- Ordinary runtime type tests evaluate their operand once at the erased object
  boundary, implement Kotlin nullable-target semantics before the non-null
  check, and then use either an existing Kotlin classifier or one physically
  exact CLR carrier. Exact carriers currently include the eight boxed Common
  scalars, `Any`, `String`, non-generic classes/interfaces, supported
  primitive-array wrappers, and fully known CLR vectors. Never admit a
  `GenericInstance` as Kotlin runtime identity: Kotlin-owned generic classes
  are declaration-erased on this target as well. `Array<*>` is the one
  selected structural erased-array case above; its classified `System.Array`
  path must not leak into ordinary generic-class RTTI.
- Kotlin-owned ordinary generic interfaces have one authoritative non-generic
  CLR TypeDef and one erased virtual slot family. Do not reintroduce
  declaration-variant or invariant-exact sibling TypeDefs, typed-view guards,
  or an implementation-set-dependent typed ABI. Kotlin constructions,
  projections, casts, defaults, and separate consumers all use that one
  physical identity; KLIB retains the logical parameters and override graph.
  Imported CLR generic interfaces remain native. A separately accepted BCL
  mapping such as `Comparable<T>` may expose an additional exact host
  capability only where independently truthful, and future typed C# surfaces
  belong to explicit export. See
  [the erased generic-interface decision](docs/decisions/generic-interface-erased-identity.md).
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

`DotNetKotlinExceptionTypeId` is the single authority for the classifier's
stable integer ABI. Runtime switch IL must interpolate those assigned values;
never duplicate a bare number or substitute an unrelated monotone counter such
as the runtime-surface level. Advancing one version must not renumber exception
identity.

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

Migrated physical ECMA-335 forms live in `:dotnet:dotnet.ir`, which must not
depend on Kotlin IR, FIR, KLIB, target profiles, export policy, or backend
implementation types. `backend.dotnet` owns Kotlin representation choices,
profile legalization, instruction selection, and lowering into concrete CLI
forms; `dotnet.ir` owns their structural validation and deterministic text and
future JVM-hosted PE serialization. Add no speculative CLI nodes. Migrate one
closed production form at a time and remove its old string builder in the same
slice. CLI generic support is a physical capability only and never reopens the
accepted declaration-erased runtime owner of a Kotlin-owned generic class.
See [the structured CLI programme](docs/programmes/structured-cli-ir.md).

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

## Upstream synchronization

Before rebasing, fetch and name the exact upstream head, account for every
commit in the pending range by subject and changed paths, inspect the patches
for every shared owner that can affect .NET, and compute both the path overlap
and a virtual merge. A thematic review may omit irrelevant commits from its
prose only after that complete accounting exists.

For every upstream change to an interface, abstract base, sealed hierarchy,
constructor contract, or factory contract, also perform a repository-wide
reverse-dependency audit of target-owned implementations, subclasses,
exhaustive branches, and call sites. Changed-path overlap and a clean virtual
merge are insufficient evidence because a target-owned implementation may not
have been touched upstream.

Route lasting conclusions to their existing owners: ADRs for decisions,
programmes for ordering and open work, `STATUS.md` for current state, and this
file only for repeatable workflow. An archive snapshot retains the exact range,
rebase facts, screened directions, and evidence needed later; Git owns the
exhaustive commit ledger. Do not make a second changelog in Markdown.

During the rebase, resolve shared generated artifacts through their owning
generators and preserve target-owned fixtures or module registrations beside
upstream removals. Keep mechanical integration separate from semantic cleanup.
Afterward, inspect generated churn, run focused checks for affected boundaries,
then run and audit the strict aggregate gate.

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

Ordinary and reified inline support is selected by
`docs/programmes/inline-functions.md`. Non-linking inline deserialization may
bind an exact public signature through `IrBuiltIns.symbolFinder`: that finder
is the logical dependency graph already selected by the frontend, including
library B for a body owned by library A. Do not replace it with DLL discovery,
physical-name lookup, classpath arbitration, or a general IR linker. Surviving
external calls still bind only through the producer-recorded physical ABI.
After the shared inline prefix, traverse the actual IR graph and reject every
remaining unbound symbol before target lowerings; do not make an arbitrary
lowering or the CIL emitter the missing-dependency detector.
Cross-module IR inlining is the upstream production default for stdlib and
`kotlin-test`. Exercise that default in ordinary Common semantic coverage;
retain target-owned mode variants only where they prove the supported
disabled/intra-module/full compiler ABI, embedded-KLIB linkage, or physical
fallback. Do not create a duplicate runner merely to force the default mode.
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
Reified call-site substitution must leave no Kotlin call to a physical
remainder. Never compile a Kotlin-owned generic-class type test/cast as closed
CLR `C<T>` identity: Kotlin generic arguments do not participate in runtime
class identity, and the private data-class equality view is not a general
carrier. A later-admitted classifier extends the ordinary and reified
type-operation matrix together before publication. The completed
`KType`/`typeOf` foundation is a separate logical reflection graph and must
not be approximated by `System.Type` or the nominal `KClass` floor. Valued
annotation construction and class/callable-reference runtime discovery are
complete through Common/KLIB authority, fail-closed CLR projection, and
disjoint exact foreign CLR paths. Callable parameters and their declaration
annotations extend the same signature graph; do not infer member enumeration,
reflective call, accessor objects, fields, or type-use reflection from those
surfaces. Suspend inline functions, value
classes, member reflection, coroutines, concurrency primitives, and broad
KMP/Gradle product integration remain separate programmes until `STATUS.md` or
the way forward selects one.

## Verification contract

Choose verification from the boundary changed, not from the number of source
files. During local development, the full target aggregate is:

```text
./gradlew :compiler:backend.dotnet:dotNetTest -q
```

Gradle 9's `--rerun` is a selected-task option. On the empty backend
`dotNetTest` lifecycle task it reruns only that aggregate task, not its FIR or
integration dependencies, and therefore adds no fresh evidence. Use the exact
global `--rerun-tasks` option only when a deliberately dependency-wide clean
checkpoint is required; it also rebuilds every transitive producer and is not
the default local feature gate. Never describe `--rerun` on the aggregate as a
fresh full matrix.

Use `--no-daemon` only for CI-equivalent clean-room evidence, after suspected
daemon/toolchain contamination, or when an explicitly selected checkpoint
requires it. The daemon does not weaken test isolation guaranteed by the test
tasks and shared external-tool locks.

Do not trust quiet Gradle success alone. Audit every JUnit XML file under:

```text
dotnet/dotnet.ir/build/test-results/test/
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

When invoking that property from PowerShell, pass it as one quoted native
argument before the task, for example
`./gradlew "-Pkotlin.test.update.test.data=true" :compiler:fir:fir2ir:dotNetTest ...`.
The unquoted spelling can be split so Gradle reports a nonexistent
`.test.update.test.data=true` task instead of updating the golden.
Treat `Task '.test.update.test.data=true' not found` as this recurring
PowerShell invocation error, not as a compiler or test failure: stop, quote the
complete `-P...` argument, place it before the task name, and rerun the same
command.

Focused compilation/tests are the commit gate for a bounded Common-stdlib
source addition that changes no shared compiler representation, lowering,
code generation, runtime surface or ABI. Its matrix must still cover every
affected frontend, profile, runtime, artifact and separate-consumer boundary,
and `STATUS.md` must distinguish that focused evidence from the last full
checkpoint.

Run and audit the full target aggregate after changes to type mapping, IR
lowerings, CIL code generation, runtime helpers or surface levels, arrays or
generic representation, physical ABI, artifacts, target profiles, toolchain
integration or shared test infrastructure. Also run it for every periodic
recorded checkpoint, after upstream integration, and before ABI-readiness
work. When the boundary is unclear, use the full aggregate. Before committing,
verify that status shows only intended files.

Prefer existing Common compiler test data and the shared stdlib test corpus
for Kotlin semantics once the admitted target closure can compile and execute
them. Generated runner methods merely enumerate test-data files; they do not
make target-owned duplicate assertions authoritative or cheaper. Keep .NET-
owned tests for CIL, CLR profiles, physical ABI, self-describing DLLs, foreign
interop, target diagnostics, and other genuinely target-specific boundaries.
Do not remove a duplicate target behavior test until the upstream test itself
executes through the supported .NET product on every applicable profile.

If the aggregate is split into smaller Gradle test tasks, their groups must be
disjoint and exhaustive, their union must remain behind `dotNetTest`, and the
repository test-lifecycle model must see every task. Do not parallelize
separate tasks that can reach Framework ILAsm/CLR4 until their resource lock
works across Gradle worker processes; a JUnit lock inside one worker is not
such proof.

During one coherent feature tranche, use focused tests for each internal slice
and run the selected commit gate once against the final semantic head before
its commit and push. Do not split a coherent feature into microcommits merely
to repeat a gate, and do not batch unrelated semantics merely to amortize test
time.

Ordinary FIR/IL/box tests consume the Gradle-produced exact-profile
`Kotlin.Runtime.dll`/`Kotlin.Stdlib.dll` fixture. Only a test whose subject is
stdlib source production may request `DOTNET_STDLIB_FROM_SOURCE`; do not restore
unconditional source injection. The filtered `dn` integration task must not
inherit compiler-distribution or Wasm products without an actual target test
consumer. See the
[test-product ADR](docs/decisions/test-product-and-validation-ownership.md).

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
net48 golden with its canonical Framework ILAsm. The bounded cross-assembler
class submits representative writer-sensitive shapes to both Framework and
modern ILAsm; extend that corpus when a new physical family needs it instead
of restoring a second writer pass for every golden. Text equality does not
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
