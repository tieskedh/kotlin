# Kotlin/.NET backend codegen conventions

This document owns private implementation conventions that are important for
correct codegen but are not public representation/ABI decisions. Exact private
names and layouts are pinned by tests and may change without becoming Kotlin
source or binary API.

## Private CLR field disambiguation

### Invariant

A legal Kotlin property and compiler-required state—outer receiver, singleton
cache, capture, delegate storage, or a future synthesized field—must coexist.
Private physical spelling is not Kotlin identity; symbol-bound reads/writes,
property accessors, metadata, visibility, and initialization remain unchanged.

Public, protected, const, and explicitly metadata-public compiler-ABI fields
cannot be renamed to repair a collision. Such collisions fail atomically.

### Mature-target precedent and CLR constraint

JVM's late field-renaming pass gives exposed fields first claim, gives stable
compiler fields the next priority, and suffixes later private storage. JS,
Wasm, and Native likewise preserve logical properties while target layout
disambiguates synthesized storage.

CLR field identity includes owner, name, and signature and can technically
retain same-named fields of different types. C# and ordinary reflection are
name-oriented, however; exploiting that metadata curiosity would make a less
usable CLR surface without preserving extra Kotlin semantics.

### Convention

Run one late class lowering after every field-producing lowering:

1. reserve every exposed/stable field name;
2. reserve all original source spellings, including suffix-like names;
3. process fields in deterministic JVM-shaped priority order;
4. retain the first legal spelling; and
5. give later private fields the smallest free deterministic `$n` suffix.

The pass visits both loose fields and property backing fields and mutates only
physical `IrField.name`. It is profile-independent. The final emitter collision
gate remains a safety check for identities that cannot be renamed.

Do not add special cases for `INSTANCE`, outer receivers, captures, or current
generated field families. A new private producer participates in the same
ordering.

### Consequences

- Source names resembling generated names remain legal.
- Pre-existing suffixed source names cannot be shadowed by a generated suffix.
- Same-named private fields are disambiguated even when raw CLR signatures
  differ, improving C#/reflection usability.
- Private physical names are never persisted as logical declaration identity.

## Generic data-class equality view

### Kotlin invariant and CLR mismatch

Kotlin data-class equality checks declaration identity and primary-constructor
properties. Generic arguments do not participate in runtime class identity;
`Box<String>` and `Box<Any>` may compare equal when their properties do.

The backend otherwise uses real CLR generics. A literal `isinst C<T>` in the
Common generated equality body would make equality depend on reified CLR type
arguments and can break symmetry. Erasing the whole class would discard useful
generic storage and signatures.

### Convention

For a generic data class with compiler-generated equality, add one private
non-generic nested interface owned by that declaration. It has one
object-returning slot per source primary-constructor property. The generic
class implements those slots with private methods and explicit `MethodImpl`
rows.

CLR nested types do not implicitly capture their enclosing generic arguments,
so every construction of `C<T>` implements the same nested identity. A
different data-class declaration owns a different nested interface.

Generated equality:

1. accepts reference identity immediately;
2. rejects an object lacking this declaration's private view;
3. casts once to the view;
4. obtains each source data property from both objects through the ordinary
   value and erased-view paths; and
5. compares pairs through Kotlin `Any?` equality.

Arrays retain the mature data-class rule: equality observes array identity,
while generated hash/string may use content operations. Primitive, nullable,
reference, constrained, and open values cross the same object equality
boundary.

Everything else remains ordinary reified `C<T>`: fields, accessors,
constructors, components, copy methods, defaults, returns, and call tokens.
Values box only while crossing the private equality view.

Closure-converted local data classes exclude synthetic bound receiver/value
capture parameters from the view. Captures remain stored/copied implementation
state; only source primary-constructor properties define data identity.

### Boundary

The nested view and component bridges are private class layout, not Kotlin
callable identity, runtime ABI, C# export, or a general structural protocol.
Privileged reflection may observe them; public reflection and consumers may
not depend on their names or exact layout.

User-defined equality receives no view. Data-object equality, general generic
type tests, reflection, export, and unsupported property families remain
separate concerns. Any optimized typed fast path must still fall back to the
declaration-local erased view for cross-instantiation comparisons.

### Required checks

Exact/semantic tests pin reified public class signatures, private view
visibility, explicit MethodImpl dispatch, cross-instantiation symmetry,
declaration separation, Kotlin equality normalization, array behavior,
defaults/copy, multiple type parameters, and closure-converted locals on both
runtime profiles.
