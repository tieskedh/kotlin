# ADR: erased ABI for Kotlin-owned generic classes

- Status: **Accepted — pre-ABI**
- Date: 2026-08-04
- Scope: Kotlin-owned ordinary generic classes, including their storage,
  member ABI, inheritance, casts, runtime identity, separate compilation, and
  default CLR surface

## Decision

A Kotlin-owned ordinary generic class has one authoritative physical CLR type
identity, object representation, storage model, and runtime/virtual ABI. The
CLR class is non-generic:

```text
Kotlin:  class Box<T>(var value: T)
CLR:     class Box { object value; object get_value(); void set_value(object); }
```

KLIB remains authoritative for `T`, its bounds and variance, every use-site
argument or projection, and nullability. The versioned physical binding stores
the one non-generic CLR owner; it does not store a typed sibling, canonical
interface, class-member bridge family, or open-generic classifier token.

This rule applies only to Kotlin-owned classes. Imported CLR generics remain
native reified CLR types. A future typed C# surface is a separate explicit,
fail-closed export product and must not redefine Kotlin runtime identity.
Internal specialization is permitted only when it is invisible behind this
one ABI.

Erasure is therefore not merely the default public ABI. It is the single
semantic runtime representation. No optimization, annotation, compiler
switch, manifest, or export feature may add a second physical implementation
whose CLR construction determines a Kotlin-owned object's classifier,
authoritative generic-dependent state, or class-dependent virtual dispatch.
In particular, the target must not restore `BoxImpl<T>`-style owners, typed
authoritative storage for escaped objects, or a declaration whose runtime
meaning alternates between erased and CLR-generic.

CLR generics remain first-class capabilities where they do not redefine that
identity. This includes imported CLR types, method-owned generic parameters,
truthfully exact constructed interface capabilities governed by the separate
interface ABI, explicit .NET export artifacts, and private implementation
specialization. A class-owned parameter remains erased in storage and member
positions even when a method on that class has its own reified CLR method
parameters.

An internal optimization qualifies as an optimization only when disabling it
does not change public or protected ABI, supported Kotlin/.NET reflection,
runtime casts, object identity, virtual dispatch, or cross-module observable
semantics. Private IL, compiler-generated helpers, internal metadata, and
physical layout may differ. If disabling the mechanism changes one of the
supported observations, the mechanism is a new ABI and requires a separate
architecture decision.

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

The Common `containsAll` case is stronger: it is ordinary source-legal
behavior, not an unchecked-cast preference. The target must execute the Common
element-wise algorithm without narrowing a complete nested carrier such as
`Collection<object>` to `Collection<int>`.

## Physical mapping

An owner type parameter in class storage, a constructor, or an instance member
maps to:

1. its already accepted erased Kotlin carrier, when one exists;
2. an exactly representable erased upper bound; or
3. `System.Object`.

An array whose element is an erased owner parameter uses the accepted erased
`System.Array` carrier. This does not change the separately accepted exact
array rules for concrete element types.

Methods may retain their own CLR method type parameters. Only type parameters
owned by the erased class lose physical CLR generic slots. Imported CLR types
nested in a signature remain reified only where their complete construction is
truthfully representable; an unsupported open carrier fails closed.

Logical overloads which collide after class erasure keep deterministic
physical names derived from the complete Kotlin signature. KLIB and the
physical function record restore their Kotlin names. Name allocation must not
depend on declaration order or the current overload set.

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
adapter, facade, wrapper, or another explicitly separate surface. It must not
derive a typed implementation from the erased Kotlin class and thereby make
only some instances physically typed. It must diagnose unrepresentable
construction, identity, mutation, inheritance, override, projection,
collision, and nullability shapes rather than falling back to a misleading
typed ABI.

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
- **Hybrid typed class plus canonical interface.** Rejected because canonical
  dispatch is an ordinary correctness path, mutable state still needs erasure,
  and the second TypeDef/member/metadata/runtime system has no hard product
  requirement.
- **Wrap, copy, or proxy on casts.** Breaks identity, mutation, dispatch, and
  synchronization.
- **Maintain typed and erased storage.** Creates an incoherent aliasing and
  synchronization model.
- **Choose physical representation from local provenance or visibility.** A
  single logical ABI cannot vary across fields, joins, casts, libraries, and
  foreign calls.
- **Special-case `containsAll`.** Other nested carriers, overrides, and
  separate compilation require the general erased rule.

## Explicitly on hold

This decision does not authorize:

- typed C# generic-class export;
- SSA/CFG provenance analysis, capability guards, or loop versioning;
- profile-specific ReadyToRun or NativeAOT specialization;
- a visibility-dependent mix of erased and reified Kotlin-owned class ABIs;
- value/inline classes, enum classes, valued annotations, or reflection;
- changes to the separately selected generic-interface or array ABIs; or
- public reified-inline support.

These may be considered after the semantic ABI and representative target
programs provide measurements. An optimization may not change the supported
public or cross-module observations listed in the decision above.

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

The implementation is complete only when the old class-only runtime helpers,
bridges, metadata records, TypeDefs, and tests no longer survive as active
behavior or documentation.
