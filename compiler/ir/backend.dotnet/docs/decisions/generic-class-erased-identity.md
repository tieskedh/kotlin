# ADR: semantic erasure for Kotlin-owned generic classes

- Status: **Accepted — pre-ABI**
- Date: 2026-08-04
- Amended: 2026-08-05 to distinguish canonical erasure from private
  implementation reification; 2026-08-07 to park the materially different
  true-CLR-generic-owner/early-cast alternative without authorizing it;
  2026-08-13 to bind that alternative to the operation-specific platform-
  freedom rule and preserve parameterized safe-cast semantics
- Scope: Kotlin-owned ordinary generic classes, including their storage,
  member ABI, inheritance, casts, runtime identity, separate compilation, and
  default CLR surface

## Decision

A Kotlin-owned ordinary generic class has one authoritative semantic runtime
classifier, one canonical erased Kotlin runtime/virtual ABI, and one
authoritative mutable state. Its canonical CLR owner is non-generic:

```text
Kotlin:  class Box<T>(var value: T)
CLR:     class Box { object value; object get_value(); void set_value(object); }
```

KLIB remains authoritative for `T`, its bounds and variance, every use-site
argument or projection, and nullability. The versioned physical binding stores
the one non-generic canonical owner; it does not store a typed sibling,
canonical interface, class-member bridge family, or open-generic classifier
token as an alternative Kotlin identity.

This rule applies only to Kotlin-owned classes. Imported CLR generics remain
native reified CLR types. A future typed C# surface is a separate explicit,
fail-closed export product and must not redefine Kotlin runtime identity.
Internal specialization is permitted only when it is removable and invisible
behind this complete erased ABI.

Erasure is therefore not merely the default public ABI. It is the
authoritative semantic model: generic arguments never become Kotlin runtime
identity, casts remain declaration-erased, and every cross-module Kotlin path
has a complete erased route. It is not a permanent prohibition on physically
reified private implementation. No optimization, annotation, compiler switch,
manifest, or export may add a second observable Kotlin implementation whose
CLR construction determines the classifier, class-dependent dispatch, or a
competing authoritative state.

CLR generics remain first-class capabilities where they do not redefine that
identity. This includes imported CLR types, method-owned generic parameters,
truthfully exact constructed interface capabilities governed by the separate
interface ABI, explicit .NET export artifacts, and private implementation
specialization. Class-owned parameters remain erased in public, protected, and
canonical cross-module member positions. The current baseline also stores
their authoritative values through `object`, an erased upper bound, or an
accepted erased Kotlin carrier.

That baseline private layout is not frozen. A later optimization may use
generic methods, private `TypedStorage<T>` cells, scalar replacement, exact
constructors, or another CLR-generic helper when a separate ADR proves all of
the following:

- the complete erased execution route remains available;
- the object retains one identity and one authoritative state;
- unchecked views retain delayed-use behavior, including incompatible erased
  writes where Kotlin permits them;
- virtual dispatch, reflection, and separate compilation remain unchanged;
- disabling the optimization changes no supported DLL signature or observable
  Kotlin behavior; and
- measurements justify the additional compiler, metadata, JIT/AOT, and
  maintenance cost.

For a mutable escaped object, a fixed typed field alone cannot satisfy those
rules. A future typed normal state would need a correct transition to erased
storage, or an equivalent one-state strategy, when an incompatible erased
write occurs. Identity, visibility between threads, atomicity, and transition
ordering belong to that future decision. Two concurrently authoritative
stores remain forbidden.

An internal optimization qualifies as an optimization only when disabling it
does not change public or protected ABI, supported Kotlin/.NET reflection,
runtime casts, object identity, virtual dispatch, or cross-module observable
semantics. Private IL, compiler-generated helpers, internal metadata, and
physical layout may differ. If disabling the mechanism changes one of the
supported observations, the mechanism is a new ABI and requires a separate
architecture decision.

The long-term implementation objective is to maximize truthful and measured
CLR reification, especially where it removes value-type boxing, while keeping
semantic erasure authoritative. “Maximize” does not make specialization an end
in itself: an unmeasured mechanism whose complexity exceeds its benefit should
not land.

This is the Kotlin/.NET target authors' pre-ABI decision. It follows the
architecture of mature Kotlin targets, but it is not a Kotlin core-team
decision, a public KEEP, or an official target commitment.

## Why

Kotlin runtime identity is declaration-erased on the mature targets:

- JVM uses one raw class for `C<A>`, `C<B>`, and `C<*>`;
- JS uses one constructor/class identity;
- Native erases type parameters before checked and safe runtime casts; and
- Wasm maps runtime tests and casts to an erased upper-bound class.

CLR constructed generic classes instead have distinct invariant runtime
identities. `Box<string>` and `Box<int32>` cannot both be the physical identity
of Kotlin `Box<*>`. Retaining `C<T>` therefore required a second erased
canonical interface, duplicate member families, `MethodImpl` bridges, an
ancestry classifier, additional ABI records, and two dispatch paths.

That hybrid design also failed ordinary Common behavior. A widened
`AbstractCollection<Int>.containsAll(Collection<Any?>)` may legally inspect a
`String` candidate and return `false`; forwarding its erased signature into an
`AbstractCollection<int>` body narrowed the candidate too early and threw.
Unchecked mutation exposed the deeper conflict: a physical `Box<string>` field
cannot accept an `Int` through an unchecked `Box<Int>` view without either
rejecting the write early or duplicating storage.

The target has no hard requirement that every arbitrary Kotlin-created object
also expose natural same-object CLR `C<T>` identity. Without that requirement,
the permanent dual ABI and storage analysis are not justified.

This rejects a second public class ABI, not private optimization. A
non-escaping allocation may eventually disappear through scalar replacement;
an immutable producer may admit typed private storage; and a mutable object
may conceptually begin in typed storage and transition to erased storage on an
incompatible erased write. Each is compatible with the decision only when it
preserves the semantic contract below and earns its complexity with evidence.

## Semantic contract

For a Kotlin-owned `Box<T>`:

- `Box<String>`, `Box<Int>`, and `Box<*>` have one runtime classifier;
- `is Box<*>`, checked casts, and safe casts test only that classifier;
- projections and declaration-site variance change legal source operations,
  not physical identity;
- casts preserve the original object, mutation, synchronization, and virtual
  dispatch;
- a subclass satisfies erased tests of every Kotlin-owned generic base in its
  inheritance chain; and
- generic results are narrowed or unboxed only at their logical use site.

Kotlin/.NET deliberately selects classifier-only behavior for unchecked casts:

```kotlin
val original = Box("text")
@Suppress("UNCHECKED_CAST")
val wrong = original as Any as Box<Int>

wrong.value = 7        // mutates the same erased object
val star: Box<*> = original
check(star.value == 7)
original.value         // fails when the result is consumed as String
```

The Kotlin language permits a platform to fail a not-fully-checkable cast
earlier. This target nevertheless chooses the familiar erased, delayed-use
behavior because the CLR does not force an earlier failure and mature Kotlin
targets establish that user expectation.

That choice and the planned replacement are constrained by the accepted
[semantic-authority and platform-freedom ADR](kotlin-semantic-authority-and-platform-freedom.md).
Only the implementation-defined failure point of a parameterized throwing
`as` may move earlier. Parameterized `as?` continues to ignore generic
arguments for subtyping and therefore uses the logical classifier rather than
an exact constructed-owner predicate.

The Common `containsAll` case is stronger: it is ordinary source-legal
behavior, not an unchecked-cast preference. The target must execute the Common
element-wise algorithm without narrowing a complete nested carrier such as
`Collection<object>` to `Collection<int>`.

## Physical mapping

An owner type parameter in the canonical constructor or instance-member ABI,
and in the current baseline class storage, maps to:

1. its already accepted erased Kotlin carrier, when one exists;
2. an exactly representable erased upper bound; or
3. `System.Object`.

An array whose element is an erased owner parameter uses the accepted erased
`System.Array` carrier. This does not change the separately accepted exact
array rules for concrete element types.

Private storage fields and compiler-generated helpers are not semantic ABI.
They may later use more exact CLR shapes under the optimization proof in the
decision above. Public/protected signatures, runtime tests, cross-module calls,
and the canonical erased fallback remain stable while those private details
change.

Methods may retain their own CLR method type parameters. Only type parameters
owned by the erased class lose physical CLR generic slots. Imported CLR types
nested in a signature remain reified only where their complete construction is
truthfully representable; an unsupported open carrier fails closed.

Logical overloads which collide after class erasure keep deterministic
physical names derived from the complete Kotlin signature. KLIB and the
physical function record restore their Kotlin names. Name allocation must not
depend on declaration order or the current overload set.

A generic upper bound whose classifier is a Kotlin-owned erased class keeps
that one non-generic owner as a necessarily true CLR constraint, while KLIB
retains the complete arguments and recursive relation. Thus Common's
`E : Enum<E>` is physically `E : Enum`, never `Enum<E>` and never an omitted
class relationship. This is a truthful weakening: it rejects values outside
the erased class hierarchy without pretending CLR can encode Kotlin's exact
self-bound. Imported CLR generic bounds are not erased by this rule; their
native constructed identity must be represented exactly or rejected.

## Inheritance and dispatch

The emitted CLR class retains its ordinary non-generic base-class edge. A
Kotlin subclass of `Base<T>` therefore physically extends the one erased
`Base`, while KLIB retains the substituted logical base type.

CLR method slots include their physical parameter and return types. When a
subclass narrows a logically substituted member, the backend emits an erased
override bridge against the base slot, following the JVM bridge direction:

```text
Base<T>.read(): object
Derived : Base<String>
    read(): string
    synthetic bridge read(): object -> Derived.read(): string
```

The bridge is one adapter inside the single class hierarchy, not a second
class ABI. It preserves most-derived dispatch through base-typed receivers and
is recorded through normal function/override metadata rather than the removed
generic-class bridge schema.

`super` calls, constructor delegation, fields, nested and inner classes, and
default dispatchers all reference the same erased owner. Inner-class lowering
may copy logical outer parameters while normalizing IR, but emitted
Kotlin-owned class TypeDefs do not regain CLR class generic arity.

## CLR and C# boundaries

The erased CLR class is a truthful low-level view: C# can see and, where CLR
visibility/modality permit, subclass its `object`-based members. That is not a
typed Kotlin export contract.

Ordinary Kotlin compilation must not silently publish `Box<T>`. A future typed
C# export must be opt-in and state, per declaration, whether it emits an
adapter, facade, read-only interface, export-created same-object subtype, or
another explicitly separate surface. A same-object export is a new export ABI,
not a different representation of the Kotlin declaration: only objects
constructed through that export may possess its CLR generic identity, while
every Kotlin operation continues through the canonical erased contracts. An
arbitrary existing `Box` cannot be retroactively converted into
`DotNetBox<T>` without an adapter. Every export form must diagnose
unrepresentable construction, identity, mutation, inheritance, override,
projection, collision, and nullability shapes rather than falling back to a
misleading typed ABI.

For Kotlin type tests and supported reflection, an export-created subtype must
normalize to the original Kotlin declaration rather than manufacture a second
Kotlin classifier from its CLR export TypeDef. If that normalization is not
truthful for a proposed shape, same-object export is unsupported for it.

The public C# rule is deliberately simpler than the compiler architecture:

> Kotlin classes remain Kotlin classes. C# consumes only explicitly exported,
> safe .NET APIs.

Native CLR generics such as `List<int>` and `Task<T>` remain ordinary .NET
types. A Kotlin implementation type such as `Box<T>` does not itself become a
CLR `Box<T>`; an explicit export may instead publish a supported surface such
as `IReadOnlyBox<T>` or `DotNetBox<T>`. C# documentation and IntelliSense must
describe the exported .NET API rather than expose canonical-dispatch,
classifier, or split-interface vocabulary. Unsupported declaration shapes
fail closed instead of appearing as partially typed APIs.

An export adapter may have its own CLR identity, but it is never an alternative
physical implementation of the Kotlin declaration and never owns the
authoritative Kotlin state. It does not affect Kotlin `is`, `as`, reflection,
generic semantics, or dispatch. It must not imply same-object reference
identity with the underlying Kotlin object unless that property has been
explicitly proven and specified. The implementation TypeDef may remain
technically visible to raw CLR tooling, but it is not a supported typed C#
contract.

The export programme may therefore distinguish four honest categories:

- same-object export for instances created through that export contract;
- adapter export for arbitrary existing Kotlin instances;
- read-only facade where mutation or identity is not promised; and
- unsupported when no exact host contract exists.

The same rule applies when the class implements a Kotlin-owned generic
interface. `class C<T> : I<T>` physically implements the one erased `I`,
because neither declaration has a CLR owner parameter. Mapping it to
`I<object>` would add a different foreign capability, not preserve the logical
edge. A closed edge such as `C<T> : I<String>` still implements that same
erased Kotlin-owned `I`; a truthful typed capability exists only through an
explicit built-in mapping or export governed by the generic-interface ADR.
Method bounds such as `<R : T>` remain authoritative in KLIB and omit the
unrepresentable CLR relational constraint on the erased owner.

On a profile with default interface methods, the default body is hosted by the
one erased Kotlin-owned `I`. Portable profiles retain their accepted helper
and class-forwarder policy; no typed-sibling correction is required.

Imported CLR `Foreign<T>` declarations are unaffected. Their constructed CLR
identity, constraints, typed fields, inheritance, and C# subclassing remain
native platform facts.

## Metadata and migration

The pre-ABI migration:

- bumps the physical ABI schema;
- records one owner path for a Kotlin-owned generic class;
- removes class-only declared/exact owner paths;
- removes generic-class member-bridge records;
- removes canonical class interfaces and their compiler-only members;
- removes typed-class capability probes and open-ancestry classifiers; and
- rejects artifacts written with the superseded schema.

No compatibility shim infers the old dual view from arity or names. Nothing
has shipped, so every producer and consumer moves together.

## Rejected alternatives

- **Closed CLR `C<T>` only.** Runtime identity and unchecked casts become too
  strict.
- **Treat `C<object>` as `C<*>`.** CLR classes are invariant; value and
  reference constructions are unrelated to that type.
- **Primary typed class plus exceptional canonical fallback.** Rejected because
  erased/projected dispatch is an ordinary correctness path, not an uncommon
  recovery route. The distinct design with a complete erased capability ABI
  and deliberately early failure of physically incompatible unchecked casts is
  the planned reopening below rather than rejected by this argument.
- **Wrap, copy, or proxy on casts.** Breaks identity, mutation, dispatch, and
  synchronization.
- **Maintain two concurrently authoritative typed and erased stores.** Creates
  an incoherent aliasing, visibility, and synchronization model. A one-state
  transition from typed to erased storage is not rejected here; it requires a
  separate proof.
- **Choose canonical ABI representation from local provenance or visibility.**
  A single logical ABI cannot vary across fields, joins, casts, libraries, and
  foreign calls. Removable scalar replacement and private helpers do not
  change that ABI and remain possible.
- **Special-case `containsAll`.** Other nested carriers, overrides, and
  separate compilation require the general erased rule.

## Planned replacement not authorized by this baseline

Until the hardest-model-first programme supplies and accepts the complete
replacement, this current implementation decision does not authorize:

- a true CLR-generic `C<T>` owner plus a complete erased Kotlin capability ABI
  whose physically incompatible unchecked casts fail at the cast boundary;
- typed C# generic-class export;
- typed private storage with deoptimization for escaped mutable objects;
- SSA/CFG escape/provenance analysis, capability guards, or loop versioning;
- profile-specific ReadyToRun or NativeAOT specialization policy;
- a visibility-dependent mix of erased and reified Kotlin-owned class ABIs;
- value/inline classes, enum classes, valued annotations, or reflection;
- changes to the separately selected generic-interface or array ABIs; or
- public reified-inline support.

The public-owner replacement is now an active architecture programme, starting
with the complete hostile semantic model rather than an easy production
subset. The separate private-optimization items still require representative
target measurements and the relevant concurrency/memory model. Prefer scalar
replacement and immutable/private shapes before a mutable escaped-object
deoptimization system. An optimization may not change the supported public or
cross-module observations listed in the decision above.

Swift-style devirtualization and Kotlin/Native's Variable Type Analysis are
admissible future proof engines for that private optimization work. A finite
receiver set may justify a direct call, a private specialized helper, or an
exact BCL operation only while the canonical erased call remains the semantic
fallback at every open module/assembly boundary. Such analysis consumes type
and reachability facts; it does not recreate a discarded CLR owner generic
parameter and therefore cannot make the exported non-generic owner equivalent
to `C<T>`. Using devirtualization to change public TypeDefs, casts, reflection,
inheritance, or C# signatures would be the planned ABI reopening below, not an
optimization.

The first planned item is not an optimization. It would change TypeDefs, DLL
signatures, runtime identities, cast timing, reflection normalization, and
cross-module ABI, so it can land only by replacing this ADR and the current
baseline atomically after the hostile semantic model succeeds. Kotlin already
diagnoses the uncheckable generic
argument portion of casts such as `value as Box<Int>`; the language permits a
platform to fail that cast earlier when the physical runtime can inspect it.
That observation removes the former requirement that an incompatible value be
writable through the same object after such a cast, and therefore makes typed
single-state storage technically plausible. It does not permit any ordinary
type-correct star, projection, variance, widened-call, or collection candidate
to fail early.

The exact question, admissible failures, forbidden rejections, evidence matrix,
and locked scope are recorded in
[`../programmes/generic-class-owner-reopening.md`](../programmes/generic-class-owner-reopening.md).
Until that programme is explicitly activated and this ADR is revised, the
non-generic owner and delayed-use behavior above remain authoritative.

## Verification gate

The accepted ABI must cover:

- final, open, abstract, and sealed generic classes;
- reference, value, nullable-value, bounded, and multiple owner parameters;
- fields, properties, constructors, ordinary and method-generic members;
- one- and multi-level inheritance, overrides, `super`, nested and inner
  classes, and default arguments;
- stars, projections, `is`, checked/safe/unchecked casts, same-object mutation,
  and delayed incompatible reads;
- widened direct and nested generic-bearing inputs, including Common
  `containsAll` true/false/null/empty cases;
- deterministic erased overload names and physical IL shape;
- a netstandard2.0 producer consumed by Kotlin on Framework CLR and CoreCLR;
- absence of an implicit CLR `C<T>` surface and continued reification of
  imported CLR generics; and
- unchanged generic-interface, array, nullability, friend/compiler-ABI, and
  stdlib behavior.

The semantic assertions above are permanent. Tests that require one canonical
public/protected erased signature are ABI tests. Tests that literally require
one private `.field object` or forbid every private generic helper describe the
current baseline layout and must be labelled as such; they may change after a
specialization ADR without weakening any semantic assertion.

The implementation is complete only when the old class-only runtime helpers,
bridges, metadata records, TypeDefs, and tests no longer survive as active
behavior or documentation.
