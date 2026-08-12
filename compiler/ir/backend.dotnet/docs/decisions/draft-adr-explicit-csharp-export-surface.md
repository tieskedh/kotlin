# Draft ADR: Explicit C# export surface

- Status: **Accepted architectural direction; concrete surface and DSL remain draft**
- Dates: 2026-07-15 through 2026-08-12
- Scope: explicit function/property aliases, delegate adaptation, nullability,
  defaults, naming, and collision policy

## Context

Canonical Kotlin CLR signatures optimize Kotlin identity and cross-module
correctness. They are not automatically an idiomatic C# API: function types
use erased Kotlin callables, properties may need typed projection, Kotlin
defaults are callee-executed, and logical nullability lives authoritatively in
KLIB.

Mature targets preserve the Kotlin declaration and add an explicit host-facing
shape. Kotlin/.NET likewise needs a deliberate facade rather than automatically
exporting every public declaration or teaching canonical ABI to mimic C#.

That rule applies to Kotlin-owned declarations whose identity or contract does
not already match .NET. It does not require a facade for a compatible platform
`actual`: imported CLR types and methods should retain their native identity,
and an exact expected class/interface should be able to actualize directly to
that foreign declaration. Export is the additive answer to a real ownership,
shape, or semantic mismatch, not a mandatory wrapper around all interop.

The current CLI selectors are provisional control-plane machinery used to
evaluate representation before a source annotation or typed Gradle DSL is
chosen. Their text is not public Kotlin metadata or stable ABI.

## Decision

### Architectural direction

Kotlin/.NET follows the same dependency direction as Kotlin/Native export:

```text
Kotlin semantics and runtime ABI
        -> explicit foreign-language export
        -> host-native API only where the representation is exact
```

The desired C# shape never drives Kotlin runtime identity in the reverse
direction. Kotlin-owned generic classes and interfaces therefore retain their
semantically erased canonical implementation ABI. A future typed
class/interface export is a separately generated facade, interface, adapter,
or export-created CLR subtype contract and may not alter Kotlin casts,
reflection, virtual dispatch, authoritative state, or cross-module ABI.
Unsupported generic, inheritance, mutation, variance, or identity shapes fail
closed. This direction is accepted; the concrete generic export forms and
source-selection mechanism remain on hold.

### One C# authoring rule

The supported user-facing contract is:

> Kotlin classes remain Kotlin classes. C# consumes only explicitly exported,
> safe .NET APIs.

C# authors need distinguish only native CLR types, explicitly exported .NET
APIs, and Kotlin implementation types. Native CLR generics retain their normal
CLR behavior. An exported API behaves as ordinary typed .NET code within its
declared supported contract, including IntelliSense, nullability, constraints,
and compile-time diagnostics. A Kotlin-owned generic implementation type does
not automatically become a CLR generic class and its low-level physical shape
is not a supported typed C# API.

Public C# documentation uses *Kotlin implementation type*, *exported .NET
API*, *adapter* or *facade* where one exists, and *native CLR type*. Terms such
as canonical ABI, erased classifier, and split-interface model belong to
compiler documentation, not the authoring model. Unsupported shapes fail
closed instead of appearing as partly typed exports, and one public type name
must not sometimes denote an erased implementation and sometimes a CLR generic
export.

An adapter may have its own CLR identity but neither replaces the underlying
Kotlin object nor owns its authoritative state. Reference identity between an
adapter and its implementation object is not implied. An export must state any
identity guarantee explicitly rather than appearing transparently identical
to the implementation type.

A future generic-class export may select one of four explicit categories:

- a same-object CLR subtype for instances constructed through the export;
- an adapter for arbitrary existing Kotlin instances;
- a read-only facade where mutation or identity is not promised; or
- unsupported when no truthful CLR contract exists.

CLR declaration-site variance is an export capability, not a reason to reopen
the canonical Kotlin owner. Kotlin `out` and `in` parameters may be projected
as CLR covariant and contravariant parameters only on exported generic
interfaces and delegates, because those are the only variant generic owners
permitted by ECMA-335. Admission checks the complete inherited member surface,
substituted bounds, properties, nested callable positions, and unsafe-variance
uses before publishing the variant TypeDef. A shape whose use is not valid for
the requested CLR variance is invariant or unsupported; the exporter never
weakens a member signature to preserve the annotation.

Generic CLR classes remain invariant. A Kotlin class with producer and/or
consumer capabilities may instead export an invariant typed class facade plus
separate covariant read and contravariant write interfaces. Kotlin use-site
projections and stars do not acquire a fictitious C# wildcard spelling: each
receives a truthful capability interface, an explicitly reduced safe surface,
an adapter, or an unsupported diagnostic. Variance conversions are promised
only where CLR reference conversions exist; value-type arguments do not gain
reference-type variance through boxing.

This differs intentionally from treating bridge direction as declaration
variance. Swift export's covariant and contravariant bridge positions decide
how values cross the foreign boundary, while its current general generic
parameters erase to upper bounds. The .NET exporter may use the CLR's stronger
native interface/delegate metadata where exact, but retains the same one-way
dependency from Kotlin semantics to a fail-closed host surface.

An existing erased implementation object cannot acquire a CLR generic subtype
retroactively. A same-object export-created instance must still execute the
complete erased Kotlin ABI, retain one authoritative state, and satisfy the
generic-class ADR's delayed-use semantics. Typed private storage or
deoptimization is not implied by selecting that export category. Kotlin type
tests and supported reflection normalize its export TypeDef to the original
Kotlin declaration; a shape for which that is not truthful fails closed.

Export admission is declaration-family complete. A generic-input rule applies
to functions, constructors, properties, getters, and setters before any one
surface is published, and compares resolved logical Kotlin bounds rather than
raw parameter spellings. It may not accept a property getter while silently
dropping an incompatible public setter, or validate named functions while
forgetting constructors and properties. This is a fail-closed export check,
not a reason to remove generics from the Kotlin declaration.

Admission is also dependency-graph complete. Each selected module has an
explicit full, transitive, or excluded export role. Excluding a dependency does
not erase its use from inheritance, bounds, parameters, results, or nested type
arguments. A referenced excluded declaration receives only a truthful host
stub when that preserves the complete type relation; otherwise the dependent
export is rejected. It is never replaced with `object`, `dynamic`, a bottom
type, or an unrelated wrapper merely to keep generation running.

Generated marker, protocol, facade, and adapter identities derive from the
complete logical Kotlin identity, including package and owner. Simple names
are presentation only and may not select or bind a cross-module export.

### Export is explicit and additive

Before generating an export, admission checks whether the declaration is an
exact foreign actual/import binding. If so, the native CLR surface is already
the host API and another facade is neither needed nor permitted merely for
uniformity. Direct binding still requires the complete contract to agree:
members and names, nullability, variance, constraints, SAM construction,
casts, reflection, implementation by open Kotlin generic owners, and separate
compilation. A partially compatible identity fails closed and may then request
an explicit supported adapter/export.

One export request selects exactly one supported public declaration and an
explicit CLR name. The original Kotlin declaration, metadata, physical method,
backing state, and callable identity remain unchanged. The compiler adds
wrappers to the declaration's existing static facade.

Selection uses logical Kotlin identity, not CLR tokens or declaration order.
An overloaded function requires its expanded Kotlin parameter signature; the
return type is absent because Kotlin cannot overload by return alone.
Typealiases match by expanded type. Missing or ambiguous selectors are errors.

The current function/property CLI options have reached their maximum scope.
Do not extend their textual grammar to members, receivers, annotations,
constraints, or another declaration language. A declaration-bound annotation
or dedicated DSL must eventually replace them.

### Function exports use typed CLR signatures

A function facade keeps ordinary mapped parameter/result types and projects
fixed-arity Kotlin function positions as `System.Func` or `System.Action`.
Generic/suspend functions, non-fixed callable markers, and unsupported arities
are rejected rather than exposed with erased `FunctionN`.

Kotlin-to-CLR projection binds a typed delegate directly to an exact execution
capability when available and otherwise uses a closed thunk around erased
invocation. Unit becomes `Action`. The delegate is the permitted foreign
projection object; Kotlin subtype conversions still use the original callable.

Repeated projection of one callable to the same closed delegate shape must
produce CLR-equal delegates so callback removal works without a cache. A
different closed shape has no equality promise.

CLR-to-Kotlin adaptation creates a private runtime wrapper that stores the
original delegate and implements erased Kotlin callable identity plus any
truthful optional execution capability. Projecting that adapter back to the
same delegate shape returns the stored original delegate. Foreign adaptation
may allocate and is outside Kotlin upcast `===` guarantees.

Exceptions pass through unchanged; this facade adds no catch or translation.
Host exception protocols or throwing conventions, when selected, are bridge
projections over that same throwable object. They do not alter the Runtime
classifier, Kotlin ancestry, suppressed state, or stacktrace contract.

Any reverse bridge used for C# implementation or cross-language inheritance
selects its slot by the producer-recorded Kotlin declaration plus the complete
physical CLR signature. Name-only slot matching is invalid for overloads,
generic substitutions, receivers, and generated facade members.

### Property exports are real static CLR properties

An exported top-level property adds static wrapper accessors and a CLR
`.property` row on its existing facade. A `val` exposes only a getter. A `var`
exposes a setter only when the Kotlin setter is public; a private setter
therefore projects read-only.

Ordinary property types retain their mapped CLR shape. Function-valued
properties use the same delegate projection/adaptation as function parameters
and results. Property export introduces no callable representation.

Extension properties, const fields, non-public accessors, members, delegated
or otherwise unsupported properties remain outside this draft. Their CLR
shapes need separate source-bound design, not more selector grammar.

### Nullable metadata is an additional CLR view

The facade emits exact standard Roslyn nullable attributes from source IR on
every CLR metadata owner describing the contract: methods, parameters,
returns, property rows, and property accessors as applicable.

Flags follow the standard recursive/preorder encoding and skip value types.
Value-only exports do not synthesize or reserve nullable attributes.
Context-attribute compression is optional future encoding work; KLIB remains
the logical authority.

A null delegate maps both ways where Kotlin permits it. Supplying null to a
non-null callable boundary fails explicitly rather than entering Kotlin under
a false type.

Roslyn nullable metadata is not runtime enforcement. Every non-null reference
or boxed-value position entering through a generated facade receives the same
Kotlin call-boundary validation as the declaration shape it exposes, before
delegate adaptation, unboxing, or target invocation. Disabling or omitting
nullable attributes must not change that execution behavior, and emitting an
attribute may not be used as a reason to omit the check.

### Kotlin defaults become facade overloads

When a function has a contiguous trailing suffix of Kotlin defaults, emit one
ordinary CLR overload for each progressively omitted suffix.

Each shorter overload:

- preserves parameter order and delegate adaptation for supplied arguments;
- supplies physical placeholders for omitted arguments;
- sets the Common/JVM-style masks; and
- invokes the existing callee-owned `$default` dispatcher.

No facade overload evaluates or copies a default expression. Allocation,
calls, dependencies on earlier parameters, and later library changes retain
Kotlin's callee-executed semantics.

The Kotlin `$default` dispatcher is compiler ABI, never an exported method in
its own right and never the carrier of a host-exposure marker. Only the
ordinary facade overloads form the C# default-argument surface.

The draft emits neither CLR optional flags nor constant `.param` values, even
for Kotlin literals. Roslyn embeds such constants in callers and would create
a different versioning/execution model. Caller-embedded constants require a
separate opt-in ABI decision.

Non-trailing omission does not generate exponential overload subsets or
invent names. The full facade method remains available.

### Generated default dispatchers are collision-checked

Source and generated functions are compared after lowering by complete
physical owner/name/signature identity. A backtick-named source function may
occupy the same CLR identity as `$default`; CLR return type, visibility, and
staticness do not disambiguate it.

An actual collision atomically rejects the complete class or every affected
file-facade callable. The compiler never renames or drops a dispatcher or
redirects one Kotlin declaration to another. A future frontend diagnostic
should report both source declarations; atomic rejection is final.

### Facade collisions are cross-kind and atomic

Before output, validate exported methods, property names, accessors, const
fields, existing facade members, and every other requested export together.
An occupied CLR identity rejects the complete requested export, including all
of its generated default overloads. The backend never emits ILAsm-valid but
C#-ambiguous facade metadata.

## Ownership

- Kotlin source/KLIB: original declarations, logical types, nullability,
  defaults, visibility, and identity.
- Export configuration/DSL: explicit selection and CLR naming.
- Export admission/model owner: selector resolution, complete declaration-family
  admission, logical collision planning, and one validated host-facing export
  plan independent of CIL rendering.
- Backend facade builder: consumes that validated plan and owns physical CLR
  signature collision validation, wrapper/property CIL emission, and bridge
  binding.
- Runtime interop helpers: delegate projection/adaptation and round trips.
- Standard CLR attributes: truthful foreign-language nullability view.
- Shared physical ABI/placement model: the single answer for compiler, export,
  and future Analysis API queries about CLR owner, name, property/event shape,
  or intentional absence.
- C# presentation/tooling: KDoc projection and C# keyword escaping without
  changing Kotlin identity or the canonical implementation ABI.

The current POC still performs selector resolution and much of export-model
construction inside `DotNetIlEmitter`. Swift Export's Analysis-API/SIR-provider
versus Native-backend split is the mature-target dependency precedent: move
the reusable admission/model concern to a precisely named export/interop owner
before adding generic, member, or inheritance export. This extraction does not
move physical CIL decisions out of the backend and does not require a generic
architecture layer.

## Rejected alternatives

- automatic whole-module export;
- replacing canonical Kotlin callables with delegates;
- extending provisional selector strings into an annotation language;
- CLR optional constants as the default representation of Kotlin defaults;
- silent collision-based omission or declaration-order naming; and
- inferring Kotlin identity/contracts back from the facade.

## Consequences

- C# receives typed methods, delegates, properties, nullability, and trailing
  overloads without changing Kotlin ABI.
- Projection/adaptation may allocate foreign wrapper objects while ordinary
  Kotlin upcasts remain allocation-free.
- The POC carries temporary CLI controls and wrapper members pending a durable
  declaration-bound export model.
- Unsupported declaration kinds fail explicitly instead of leaking erased or
  misleading CLR shapes.

## Promotion conditions and open decisions

Before promotion, decide and validate:

- source annotation versus typed Gradle/export DSL and stable naming rules;
- member, constructor, class, extension, generic, and suspend exports;
- complete CLR variance admission for exported interfaces/delegates and
  capability-interface projection for invariant generic classes;
- same-object export-created, adapter, read-only, and unsupported class
  categories, including their construction and reference-identity rules;
- delegate reuse, callback registration/removal, and both projection
  round-trips across assemblies;
- nullability on nested generics, arrays, delegates, properties, and defaults;
- caller-embedded constant policy, if any, with binary-versioning rules;
- non-trailing named omission policy; and
- atomic cross-kind collision diagnostics before backend emission.

None may make the C# facade authoritative for Kotlin declarations or replace
the canonical callable/reference ABI.
