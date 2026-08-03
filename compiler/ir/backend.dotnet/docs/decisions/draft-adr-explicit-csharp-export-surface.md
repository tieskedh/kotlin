# Draft ADR: Explicit C# export surface

- Status: **Draft — bounded top-level POC surface**
- Dates: 2026-07-15 through 2026-07-16
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

The current CLI selectors are provisional control-plane machinery used to
evaluate representation before a source annotation or typed Gradle DSL is
chosen. Their text is not public Kotlin metadata or stable ABI.

## Decision

### Export is explicit and additive

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
- Backend facade builder: collision validation and wrapper/property emission.
- Runtime interop helpers: delegate projection/adaptation and round trips.
- Standard CLR attributes: truthful foreign-language nullability view.
- Shared physical ABI/placement model: the single answer for compiler, export,
  and future Analysis API queries about CLR owner, name, property/event shape,
  or intentional absence.
- C# presentation/tooling: KDoc projection and C# keyword escaping without
  changing Kotlin identity or the canonical implementation ABI.

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
- delegate reuse, callback registration/removal, and both projection
  round-trips across assemblies;
- nullability on nested generics, arrays, delegates, properties, and defaults;
- caller-embedded constant policy, if any, with binary-versioning rules;
- non-trailing named omission policy; and
- atomic cross-kind collision diagnostics before backend emission.

None may make the C# facade authoritative for Kotlin declarations or replace
the canonical callable/reference ABI.
