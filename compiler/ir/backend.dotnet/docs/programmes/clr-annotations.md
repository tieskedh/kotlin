# CLR annotation interoperability programme

- Status: **Active — exact Common-contract export, foreign flow-contract
  import, Kotlin valued-annotation production, declaration/type-use
  discovery implemented; wider member surfaces and foreign grammar remain
  open**
- Owner: .NET importer and foreign FIR integration
- Governing decision:
  [`../decisions/draft-adr-clr-importer-boundary.md`](../decisions/draft-adr-clr-importer-boundary.md)
- Class-discovery decision:
  [`../decisions/annotation-discovery.md`](../decisions/annotation-discovery.md)

## Purpose

Make standard CLR metadata useful from Kotlin without treating annotations as a replacement for
Kotlin identity or Common semantics.

For foreign DLLs, an exact standard attribute is authored binary evidence and may enhance the
Kotlin-facing type, contract, call, or diagnostic view. For Kotlin-produced DLLs, KLIB remains the
semantic authority and standard attributes are derived C#/.NET projections.

This programme owns ordering, open mappings, and completion gates. The importer ADR owns the
durable authority, decoding, fallback, and projection rules. Tests and Git own implementation
history.

## Mature-target position

The closest precedent is Kotlin/JVM foreign signature enhancement:

- foreign metadata and recognized annotations enhance platform declarations;
- Kotlin declarations still require Kotlin metadata;
- target-specific annotations are adopted only when they have stable, exact semantics; and
- language-facing export controls are public API decisions, not incidental backend switches.

Native, JS, and Wasm follow the same authority split: platform metadata supplies an interoperable
view, while KLIB retains the complete Kotlin declaration.

## Carrier rule

Use the most native exact carrier for each fact:

1. a CLR table, signature, flag, modifier, or MethodImpl when the CLI expresses it directly;
2. an established CLR/Roslyn attribute when it is the shared .NET language contract;
3. a small Kotlin-specific CLR attribute only for declaration-local tooling facts with no standard
   carrier; and
4. embedded KLIB for exact Kotlin identity and the unrepresentable remainder.

The goal is useful, truth-preserving interoperation, not a maximal number of attributes. Every CLR
projection emitted from Kotlin must be derived from one Kotlin authority and checked for drift.

## Current closed mappings

The following mappings are admitted by the importer ADR. “Closed” means their identity, target,
constructor, payload, multiplicity, Common representation, and physical binding are specified;
it does not declare the complete foreign importer finished.

| CLR evidence | Kotlin-facing result |
| --- | --- |
| Roslyn nullable attributes and context | non-null, nullable, or flexible foreign type qualifiers |
| parameter `NotNull` | positive normal-return Common effect |
| parameter `NotNullWhen` | Boolean-result-conditioned positive Common effect |
| return `NotNullIfNotNull` | parameter-conditioned non-null result effect |
| parameter `DoesNotReturnIf` | opposite-Boolean normal-return implication |
| method `DoesNotReturn` | logical `Nothing` view with original physical signature |
| return `NotNull` / `MaybeNull` | enhanced call-result type |
| by-value `DisallowNull` / `AllowNull` | enhanced call-input type |
| exact `ObsoleteAttribute` | Common deprecation on admitted type/member/property/accessor targets |
| ordinary `SZARRAY` over admitted signed primitives, `string`, or `object` | JVM-shaped flexible `Array<E>` foreign view with exact CLR-vector binding |
| exact final `ParamArrayAttribute` on `string[]`/`object[]` | Common `vararg` call view with raw vector binding |
| Kotlin implementation of an admitted CLR interface | direct implementation of the retained foreign TypeDef/MethodDef slots |
| exact Kotlin Common contract export on `net10.0` | additive standard CodeAnalysis attribute on the explicit CLR export |

Closed interface properties are formed only from coherent Property and MethodSemantics metadata.
Split read/write null-state evidence is deliberately not flattened into the property's one Common
type.

## Attribute adoption matrix

| Area | State | Next requirement |
| --- | --- | --- |
| Declaration nullability | Closed in the admitted grammar | Broaden with each new physical type grammar |
| Positive parameter/result contracts | Exact import/export subset closed | Extend only where Common contract algebra is exact |
| Conditional weakening | Deferred | Model caller-state invalidation for `MaybeNullWhen` and `ref`/`out` |
| Member null-state | Retained only | Preserve Kotlin smart-cast stability; no importer-only exception |
| Properties/indexers | Non-indexed interface properties admitted | Define read/write views and indexer collisions |
| Ordinary vectors | Admitted across methods and non-indexed properties | Add element grammars only with exact physical binding |
| Parameter arrays | Reference vectors admitted | Add primitive call and implementation bridges; resolve parameter collections separately |
| Optional/default arguments | Flags and constants retained | Define calls without inventing Kotlin defaults |
| Extension methods | Raw attributes retained | Prove receiver, owner, accessibility, overload, and collisions |
| Deprecation | Exact admitted targets closed | Extend alongside each new declaration family |
| Required/init/read-only/ref-like | Physical evidence partly available | Specify profile usability and overrides |
| Tooling markers | Retained selectively | Never infer a Kotlin role or hide ABI from a marker alone |
| Kotlin annotation declarations and values | Implemented | Preserve Common/KLIB authority and one runtime class |
| Kotlin-to-CLR annotation projection | Exact fixed-argument subset implemented | Extend only with exact physical carriers and parent mappings |
| Class annotation discovery | Implemented | Reconstruct Kotlin applications from KLIB-derived factories; use CLR reflection only for unmarked foreign assemblies |
| Callable/property-reference annotation discovery | Implemented | Use exact reference targets; keep property and accessor ownership distinct |
| Callable-parameter discovery | Implemented | Reuse the callable signature graph and exact declaration/Param-row owners |
| Member enumeration | Parked | Select declaration ownership, overrides, and lookup identity before exposing a surface |
| Declaration-owned type-use discovery | Implemented | Preserve exact KLIB/IR node ownership; do not flatten CLR rows or populate `typeOf` |
| Kotlin-to-.NET export controls | Undecided public API | Make one language-facing proposal |

## Ordered work

### 1. Broaden the foreign declaration grammar without partial classifiers

Extend one complete declaration family at a time. Each addition must implement both FIR semantics
and exact backend binding before successful calls are admitted.

Candidate bounded continuations are:

1. additional ordinary class/interface member shapes already expressible in Common;
2. generics whose physical classification/assignability is already proven;
3. declaration families needed by common collection and stdlib consumers; and
4. exact Obsolete/nullability projection for those newly admitted targets.

The first ordinary `SZARRAY` slice is closed across parameter, return, and non-indexed property
positions. Like JVM reference-array import, it exposes a flexible invariant-to-out `Array<E>`
view. Unlike JVM primitive-array import, a CLR primitive vector remains `Array<E>` because this
target's Kotlin primitive arrays are nominal runtime wrappers. This is an exact platform
constraint, not a relaxation of Common array semantics.

The implementation direction stays symmetric for the admitted grammar: Kotlin classes may
implement these exact native CLR interfaces, and foreign callers dispatch through the original
MethodDefs. This is deliberately not routed through the Kotlin-owned erased-interface ABI or its
C# authoring manifest.

A classifier with one unsupported public obligation remains withheld. Classpath order never chooses
between duplicate logical classifier identities.

### 2. Design two-state property and by-reference boundaries

`AllowNull`, `DisallowNull`, `MaybeNull`, `NotNull`, and conditional variants can describe distinct
input/output or read/write states. A single Kotlin property or parameter type cannot represent all
of them truthfully.

Before importing those shapes, document:

- the Common-visible declaration type;
- call-input and call-result state;
- assignment and post-call invalidation;
- override/substitution behavior;
- callable-reference behavior; and
- exact physical byref/accessor binding.

Do not generalize the existing by-value parameter enhancer to properties or `ref`/`out` as a
shortcut.

### 3. Keep member contracts behind Common stability

`MemberNotNull` and `MemberNotNullWhen` name receiver fields or properties that Roslyn considers
non-null after a call. Kotlin additionally requires the later expression to be stable.

Therefore:

- retain and resolve the CLR evidence;
- never rewrite declaration-wide nullability from it;
- never model member names as value-parameter contract references;
- never bypass `SmartcastStability`; and
- consume the fact only if a future Common-capable representation reaches the same result as
  Kotlin-owned code.

This may make Kotlin stricter than C#. That is a deliberate Common semantic boundary, not a reason
to discard the attribute.

### 4. Specify foreign call conveniences independently

Extension methods, optional arguments, indexers, parameter collections, events, and required/init
members are separate mappings. They may share metadata plumbing but do not share one semantic
shortcut.

In particular:

- a CLR Constant may support a foreign optional call without becoming a Kotlin default body;
- `ExtensionAttribute` is insufficient without a proven receiver and owner contract;
- `DefaultMemberAttribute` does not replace the Property row;
- `ParamCollectionAttribute` is not an array marker; and
- compiler-generated markers do not prove bridge, dispatcher, or logical declaration identity.

### 5. Add truthful Kotlin-to-.NET projections

When a Kotlin fact has an exact standard representation, emit that representation from the
authoritative Kotlin declaration and validate it independently. Nullable metadata, the exact
first CodeAnalysis contract subset, target-framework facts, and friend access now do this;
deprecation export and broader declaration families remain candidates.

The Common-contract export keeps two paths explicit. Compiler-consumed Kotlin
effects and calls-in-place information remain in KLIB even when no CLR carrier exists. An exact
Roslyn attribute may additionally project the subset it can express, and the importer may decode
that same standard attribute as foreign evidence under Common stability rules. Sharing the
attribute codec does not make it the Kotlin contract authority and does not justify serializing the
same representable effect twice inside Kotlin-owned metadata.

The versioned neutral carrier stores only the five admitted effect kinds and ordinary Kotlin value-
parameter indices. FIR2IR produces it from resolved contracts; only the explicit .NET export
consumer renders it. `net10.0` uses the verified `System.Runtime` TypeDefs. `net48` and
`netstandard2.0` omit the projection because their selected reference contracts do not contain
those standard types; the compiler never synthesizes look-alikes.

Do not omit the same fact from KLIB. A Kotlin-produced DLL remains self-describing if attributes
are stripped by external tooling, and KLIB-only consumers must not need the PE attribute graph to
recover Kotlin semantics.

### 6. Grow annotation discovery from the completed class foundation

Known CLR attributes can be decoded and emitted without broad Kotlin reflection. Kotlin annotation
declaration, construction, Common value members, defaults, KLIB applications, cross-module
identity, and the exact CLR fixed-argument subset are now selected by
[`../decisions/valued-annotation-classes.md`](../decisions/valued-annotation-classes.md).

The first bounded discovery slice is now complete under
[`../decisions/annotation-discovery.md`](../decisions/annotation-discovery.md):

1. post-KLIB factories reconstruct Kotlin-produced class applications with the
   already selected concrete annotation values;
2. the producer marker prevents projected CLR rows and compiler attributes from
   becoming duplicate or invented Kotlin values;
3. class-level runtime retention and declaration order remain authoritative;
4. unmarked foreign CLR types use their native inherited attribute discovery;
5. mapped BCL classifiers do not leak host implementation attributes.

The second bounded slice is complete under
[`../decisions/callable-annotation-discovery.md`](../decisions/callable-annotation-discovery.md).
Function, constructor, and property references retain runtime annotations on
the existing executable object. Kotlin dependencies use their embedded KLIB;
foreign methods and properties use retained declaring types plus exact metadata
tokens. Property applications remain distinct from getter/setter applications.

Callable parameters and their declaration applications are complete under
[`../decisions/callable-parameters.md`](../decisions/callable-parameters.md):
Kotlin applications use their exact IR/KLIB owner, while admitted foreign
parameters use exact Param rows. The remaining chain covers built-in Kotlin
meta-annotation runtime values, typed foreign attribute import, member
enumeration, and field/accessor-object ownership. Each needs an admitted
declaration identity and use-site model before it can extend the reflection
surface. The exact CLR value/parent grammar may grow independently only when
its physical representation is exact.

Declaration-owned type-use discovery is complete under
[`../decisions/type-use-annotation-reflection.md`](../decisions/type-use-annotation-reflection.md).
The .NET `KType` actual follows JVM by adding `KAnnotatedElement`; each
declaration-derived node receives only its own runtime-retained Kotlin
applications from semantic IR/KLIB. Nested arguments, receivers, parameters,
and upper bounds retain separate owners. Binary/source-retained values, CLR
nullable metadata, and nearby custom-attribute rows never become Kotlin
`KType` annotation objects. Annotated `typeOf` remains empty, matching JVM.

This is a bounded annotation-reflection layer, not permission to build broad member enumeration or
invocation first. The foreign decoder and Kotlin producer may share a neutral value algebra, but a
derived custom-attribute row must never become the round-trip store for a Kotlin-produced library.

Shared PSI and decompiler support for rich annotation constants is the canonical future semantic
test reservoir. Enable its unary/binary/parenthesized constants, strings, class literals, arrays,
enum entries, nested annotations, unsigned values, and invalid non-constants as their real target
dependencies land; do not copy those cases into a .NET-only language suite.

The shared KLIB metadata reader can post-process each `KmAnnotation`. A future foreign importer or
interop tool may use that callback to normalize an already decoded, recognized foreign annotation
while constructing a Kotlin view. The callback is not a physical ABI codec, does not erase the
original CLR evidence, and never lets projected custom attributes override a Kotlin-produced
library's authoritative embedded KLIB.

## Public source-annotation decision

Names analogous to `@JvmName`, `@JvmStatic`, `@JsExport`, or `@ObjCName` may eventually be useful
for .NET export and authoring control. Their package, names, targets, retention, interaction with
ordinary Kotlin semantics, and compatibility promises form a public-language decision.

Do not invent `DotNetName`, `DotNetExport`, or similar source annotations inside a foreign-import
slice. Continue adopting established standard CLR attributes where no new Kotlin API is required;
bring the public Kotlin annotation family as a dedicated proposal when concrete export cases and
round-trip behavior are ready.

## Adversarial completion gates

Every mapping must prove:

- exact selected identity, target row, constructor, payload, multiplicity, and profile legality;
- real Framework and modern Roslyn metadata where both can express the shape;
- wrong-identity look-alikes, duplicates, malformed blobs, named payloads, and wrong targets;
- absence and invalid evidence never strengthen nullability or control-flow;
- weakening evidence cannot be ignored in a way that preserves unsafe non-nullness;
- Kotlin-produced DLLs remain on the KLIB path;
- no attribute changes a physical signature or member identity;
- Common contract and smart-cast stability rules remain decisive;
- separate producer/consumer compilation retains the exact assembly/member binding; and
- newly admitted calls execute on every compatible target-profile pairing.

The programme is complete when the supported carrier matrix is explicit, every admitted mapping
has those gates, remaining attributes are intentionally rejected or parked, and the public
source-annotation proposal is either accepted separately or explicitly left outside the target's
initial stable surface.
