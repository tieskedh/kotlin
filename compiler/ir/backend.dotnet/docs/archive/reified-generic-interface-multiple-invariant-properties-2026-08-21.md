# Reified multiple invariant properties checkpoint (2026-08-21)

This checkpoint closes the first reified generic-interface root with more than
one direct property family without erasing the owner or its implementation
state.

## Scope

The structural proof is a public top-level invariant interface whose entire
declared surface consists of one or more abstract mutable properties:

```kotlin
interface DualCell<T> {
    var primary: T
    var secondary: T
}
```

Every property must contribute exactly one public abstract getter returning the
non-null owner parameter and one public abstract setter accepting it. The gate
does not admit read-only, nullable, covariant, defaulted, inherited, mixed,
constrained, or incomplete multi-property owners.

## Physical contract

`DualCell<T>` remains one natural invariant CLR generic interface. It exposes
two real Property rows and four accessor MethodDefs whose value carriers are
`!T`. A generic Kotlin implementation stores the two values in two distinct
physical `!T` fields. Exact and open calls retain those types.

Star/output reads and input-projected writes cross the non-generic semantic
boundary only for the selected accessor operation. They preserve the same
receiver and do not replace the interface, either property, either field, or an
unrelated enclosing generic construction with an object-domain representation.

## C# authoring composition

The fail-first compiler omitted the owner from the reified-interface manifest.
After root admission was generalized, the C# analyzer exposed a second real
bug: it required `partial` because its natural-fallback proof assumed that a
complete property contract always contained exactly two members.

The analyzer now partitions the complete invariant manifest by source property
name. Every group must contain exactly one abstract getter and setter with the
proven natural and semantic signatures, and no default, override, intersection,
wrong-shape policy, or extra member may be present. An ordinary non-partial C#
class can consequently implement `DualCell<string>` or `DualCell<object>` with
two normal auto-properties. Method bundles retain their previous narrow limit.

## Evidence

Kotlin exact, open, star, output-projected, input-projected, and broad object
constructions exercise both properties and preserve identity. C# exact and
projected calls do the same through ordinary string/object implementations.
Reflection verifies invariant generic metadata, two independently named CLR
Property rows, four typed accessors, two `!T` Kotlin backing fields, and object
only on the projected operation signatures. The producer manifest contains the
four exact property-member families.

All four PSI/LightTree x Framework 4.8/.NET 10 candidate lanes and all four
erased epoch-off inverse lanes pass. The final normal aggregate audits 190 XML
suites and 2,287 tests with zero failures, errors, or skips.
