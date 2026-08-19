# Reified contravariant generic-interface consumer (2026-08-19)

## Result

The test-only generic-owner rehearsal now admits its first independent
input-bearing Kotlin interface:

```kotlin
interface Consumer<in T> {
    fun consume(value: T)
}
```

The natural CLR interface remains `Consumer<in T>` and owns the ordinary
`void consume(!T)` MethodDef. One non-generic declaration-semantic capability
owns `void consume(object)`. Every Kotlin or generated partial C# implementation
occupies both views on the same object; there is no wrapper or duplicate state.
Production remains on the accepted erased generic-interface ABI.

## Route rule

CLR contravariance already represents reference-only conversions such as
`Consumer<object> -> Consumer<string>`, so those declarations and calls remain
on the natural generic interface. CLR variance does not apply when a constructed
argument is a value type. Kotlin nevertheless permits:

```kotlin
val any: Consumer<Any?> = ...
val ints: Consumer<Int> = any
```

That second view is therefore stored as the non-generic capability. Calling it
boxes the `Int` once for `consume(object)`. The implementation bridge casts or
unboxes to its own natural `T` before invoking the authored member.

The lowering does not classify every `Consumer<Int>` as semantic. A final local
whose initializer has an invariant physical `Consumer<int>` supertype retains
the natural carrier and direct typed call. A semantic source declaration or a
source without the requested invariant physical view selects the capability.
This enforces `PROVEN_TYPED > semantic fallback` for contravariant inputs.

## Separate compilation and C# authoring

Assembly A declares the interface, its capability, a Kotlin implementation, and
a public reader whose `Consumer<Int>` boundary can accept every legal Kotlin
construction. Assembly B independently implements A's interface with one
generic Kotlin class. Assembly C executes exact, reference-contravariant, and
value-semantic routes through both implementations while preserving identity.

The producer's versioned member-family record already carries an arbitrary
parameter vector, so no ABI schema change was required. A downstream Kotlin
bridge binds the recorded capability MethodDef and materializes the logical
object-domain input needed for IR adaptation.

The public C# implementation manifest records declaration-site `in` variance,
natural `void consume(!0)`, and semantic `void consume(object)`. The supported
Roslyn generator lets partial C# classes implement either `Consumer<object>` or
`Consumer<int>` by authoring only their natural `consume` member. Kotlin
semantic calls reach both bodies, while an ordinary C#
`Consumer<object> -> Consumer<string>` conversion remains native CLR variance.
Precompiled, non-partial, and other-language implementors remain outside this
source-authoring proof.

## Evidence

The fail-first producer manifest contained no `Consumer<T>` contract because
the interface was outside the producer-only admission grammar. After the
generalization, PSI and LightTree execute both the rehearsal and production
inverse on .NET 10 and Framework 4.8: eight focused tests with zero failures,
errors, or skips, including the warnings-as-errors C# product.

The exported IL proves:

- `Consumer<T>` is a genuine contravariant CLR interface;
- its natural member accepts `!T` and its capability accepts `object`;
- a Kotlin implementation stores exactly one `!T` field;
- an exact `Consumer<int>` local calls the natural MethodDef;
- `Consumer<object> -> Consumer<string>` stays on the natural CLR interface;
- `Consumer<object> -> Consumer<int>` is carried by the capability and boxes
  the input; and
- the semantic implementation bridge uses `unbox.any !T` before calling the
  natural authored member.

The final production-inverse target aggregate covers 190 XML suites and 2,287
tests with zero failures, errors, or skips. FIR wrote 187 suites/2,155 tests
freshly, integration wrote two suites/126 tests freshly, and the independent
six-test `dotnet.ir` root remained up-to-date from its prior green checkpoint.

## Remaining boundary

Multiple and overloaded members, consumer children and intersections, mixed or
invariant parameters, properties, defaults, generic methods, nullable/open/
bounded/value-class substitutions, Runtime/Stdlib closure, arbitrary foreign
implementors, deployment modes, and the atomic production cutover remain
separate gates.
