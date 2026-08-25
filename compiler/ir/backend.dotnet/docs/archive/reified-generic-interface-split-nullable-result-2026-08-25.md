# Reified generic-interface split-nullable result (2026-08-25)

## Scope

This checkpoint starts from `8dd5800d19` and closes the first physical
typed-payload convention for a logical open-nullable interface result. It is
active only in the test-owned generic-owner rehearsal; production remains
atomically erased.

The admitted family is structural and name-independent:

```kotlin
interface Source<out T> {
    fun read(flag: Boolean): T?
}
```

The owner remains a natural covariant CLR `Source<T>`. Its natural member is
physically:

```text
!T read(bool flag, [out] bool& isNull)
```

Kotlin IR, KLIB, override selection, and reflection retain the logical `T?`.
The existing non-generic declaration-semantic capability retains its
`object read(bool)` slot for Kotlin views which the CLR cannot name.

## Selection and separate compilation

Admission publishes `SPLIT_NULLABLE_PRODUCER` in generic-interface ABI 59.
The role requires a public abstract direct `T?` result, no property/default/
suspend/method-generic form, and only declaration-independent regular inputs.
No interface, member, package, Runtime, Stdlib, or Map name participates.

The producer-derived payload is the owner parameter before the outer nullable
marker is applied. A separate consumer follows the published member family
instead of recomputing the convention. Local implementations substitute the
payload through their actual super construction. Consequently:

- `Source<Int>` returns `int32 + bool&`;
- `Source<String>` returns `string + bool&`; and
- `Source<Int?>` returns `Nullable<int32> + bool&`, not `int32 + bool&`.

Covariant-return planning compares these physical payloads and treats the
presence of the final flag as part of the calling convention. A downstream
bridge cannot silently pair a split slot with an ordinary one.

## Typed happy path and semantic boundary

An exact receiver which names the natural `Source<X>` construction calls the
typed MethodDef directly. Code generation allocates one Boolean local, passes
its address, and reconstructs the logical nullable result from the payload and
flag. Value payloads remain typed across the call. A null branch produces the
logical nullable default rather than accidentally boxing a zero payload.

Stars, projections, open or value-type-widened constructions retain the
semantic capability route. This is operation-local: it does not change the
owner, implementation fields, other members, or object identity. A natural-
only foreign object reached through that route uses the bounded reflection
fallback. The resolver accepts the extra physical parameter only when metadata
proves a final `[out] bool&`, invokes with a correspondingly extended argument
array, and joins to CLR null only when the populated flag is true. Ambiguous or
missing methods still fail closed.

## Kotlin and C# implementations

Kotlin return emission handles concrete nullable values without boxing,
reference null checks directly, and keeps an open `!T` payload typed while
using one boxed null probe solely to set the flag. There is no shadow state or
wrapper.

Ordinary sealed non-partial C# implements only the natural interface and an
ordinary method such as:

```csharp
public int read(bool missing, out bool isNull)
```

It needs no generator and never names the semantic capability. Exact and
widened Kotlin calls execute on that same C# object. The public C#
implementation manifest advances to schema 9 and records the physical
`bool&`; Roslyn accepts only an actual `out bool` match. The optional
partial-class generator forwards the natural slot with `out`, and its hidden
semantic bridge calls that same typed member before interpreting the flag.
Arbitrary by-reference methods remain unsupported.

## Deliberate exclusions

This checkpoint does not change open-nullable fields, inputs, class-owner
state, nested carriers, or the production-erased ABI. It does not migrate
`Map.get`: that member composes an owner-dependent key input and fixed barrier,
while this first admitted family deliberately permits only declaration-
independent regular inputs. That composition requires a later general proof.

Trimming and NativeAOT remain pre-freeze gates for the complete generic-owner
switch. The portable Framework 4.8 representation is already the floor; .NET
10 receives no alternate ABI.

## Verification

The focused separate-compilation test covers:

- Kotlin `Int`, `String`, and already-nullable `Int?` implementations;
- exact, widened, hit, missing, and stored-null results;
- producer/member binding across three Kotlin modules;
- reflected generic return plus `[out] bool&` metadata;
- ordinary non-partial C# value/reference implementations;
- exact and widened Kotlin calls to those foreign objects; and
- a generated partial C# implementation whose semantic bridge invokes the
  natural split method.

Both FIR parsers execute the proof on Framework 4.8 and .NET 10. The Roslyn
authoring project builds with zero warnings. The final target aggregate and
exact XML inventory are recorded in `STATUS.md`.
