# Big-arity callables use one arity-classified `FunctionN` capability

- Status: Accepted (pre-ABI)
- Library ABI version: 28
- Runtime surface level: 28
- Scope: ordinary and reflective function types from execution arity 23,
  invocation, type tests/casts, user implementations, interface inheritance,
  separate compilation, reflective defaults, and suspend execution shape
- Does not enable: delegates, typed C# export, suspend reflective `callBy`,
  member enumeration, or a second callable identity

## Authority and target constraint

Common fixes `BuiltInFunctionArity.BIG_ARITY` at 23. JVM publishes distinct
physical `Function0` through `Function22` interfaces, then uses one vararg
`FunctionN` interface with an arity property. Its late lowering collects the
arguments into an object array, adds an erased bridge to implementations, and
rejects a wrong array length before typed invocation.

JS, Wasm, and Native do not impose a competing physical interface family.
They preserve the logical Kotlin function type through their own runtime
models. The reusable contract is therefore Common's boundary and Kotlin's
logical arity, not a JVM class name or a CLR delegate shape.

Publishing another fixed CLR interface for every arity would be unbounded and
would diverge from Common/JVM without a CLR requirement. Using only the CLR
interface identity would also collapse `Function23` and `Function24` type
tests incorrectly. The CLR therefore requires an explicit runtime arity fact,
but no different Kotlin semantics.

## Decision

`Kotlin.Runtime.dll` owns one public, non-generic physical capability:

```text
Kotlin.FunctionN : Kotlin.Function
    object Invoke(object[] args)
    int Arity { get; }
```

Logical `Function23`, `Function24`, later `FunctionN` classifiers, and their
full parameter/result types remain in IR and KLIB. They all map to this one
physical capability. A callable object remains one object: the lowering adds
the capability, an arity getter, and one bridge to its existing typed `invoke`
body. No wrapper, clone, or arity-specific runtime class is introduced.

A logical invocation evaluates the receiver and each argument exactly once in
Kotlin order, stores the arguments in one `Array<Any?>`, and invokes the
physical capability. The generated bridge checks the array length, casts or
unboxes each element through ordinary target representation lowering, invokes
the typed body, and boxes the result where required. A wrong length throws
Kotlin's classified `IllegalArgumentException` before indexing the array.

`KFunction23+` retains the orthogonal non-generic `KFunction` reflection view
on that same object. Positional `call` passes its already collected array
directly to `FunctionN`; it does not unpack through the fixed-arity runtime
switch. Correct casts preserve reference identity.

## Type tests and casts

Physical `isinst Kotlin.FunctionN` is necessary but insufficient because all
big arities share it. Runtime helpers therefore require both the interface and
the exact `Arity` value:

- `is FunctionN<...>` returns true only for the requested logical arity;
- `as?` returns the same object or null;
- `as` returns the same object or throws the target's classified
  `ClassCastException` carrier.

The arity check is not generic-argument reification. Parameter and result type
arguments remain erased at runtime exactly as Kotlin function types require.

## Interfaces and separate compilation

A Kotlin-owned interface that extends `Function23+` physically inherits the
abstract `Kotlin.FunctionN` capability. It receives no default bridge body:
the `net48` floor cannot rely on CLR default interface implementations. Each
concrete implementor, including one compiled in a later consumer DLL, receives
its own array-to-typed bridge and arity getter. Abstract intermediate classes
may leave the capability abstract.

One object cannot truthfully implement two different big execution arities
because `Arity` has one value. Such a shape is rejected rather than assigning
one physical object contradictory runtime identities.

## Suspend functions

Common continuation lowering appends one execution parameter. Consequently a
logical `SuspendFunction21` still uses fixed physical `Function22`, while a
logical `SuspendFunction22` crosses to physical execution arity 23 and uses
`FunctionN`. The big-arity lowering consumes that continuation-shaped call;
it does not introduce `Task`, `ValueTask`, a coroutine-specific callable
interface, or a second state machine.

## Reflective defaults

The runtime omission representation is now `IntArray`, with one 32-bit word
per group of exposed callable positions. A late compiler pass translates those
bits to every mask word selected by the shared Common default-argument
lowering. Receivers still consume no mask positions, supplied null remains
distinct from omission, and Runtime does not own producer mask layout.

Changing the protected reflective-default capability from one `Int` to this
multiword representation and adding `Kotlin.FunctionN` advance both library
ABI and runtime surface to 28. Old producers/runtimes are rejected rather than
failing later with a missing or mismatched MethodDef.

## Interop boundary

The erased runtime interface is a truthful low-level CLR surface, but it is not
an idiomatic typed C# API. Future explicit exports may project supported
callables as delegates or typed facades. Disabling such an export must not
change Kotlin invocation, casts, reflection, object identity, or DLL contracts.

## Rejected alternatives

### Continue fixed interfaces beyond 22

Rejected. It creates an unbounded runtime family and departs from the Common
boundary without a CLR semantic necessity.

### Use one physical interface without checking arity

Rejected. `value is Function24<...>` would become true for a `Function23`
object, changing accepted Kotlin runtime type behavior.

### Put the bridge body on an intermediate interface

Rejected. It makes ordinary Kotlin callable inheritance depend on modern CLR
default-interface support and fails the supported Framework profile.

### Represent suspend callables as `Task<T>`

Rejected. C# async return types do not replace Kotlin's continuation/sentinel
ABI and are owned by a future explicit export adapter.

## Invariants

1. Common/KLIB owns logical callable arity and signature.
2. Fixed runtime interfaces stop at execution arity 22; execution arity 23+
   uses exactly one `Kotlin.FunctionN` capability.
3. Big-arity widening, invocation, tests, and successful casts preserve the
   original object identity.
4. Every runtime test/cast checks arity as well as physical interface identity.
5. Concrete implementations validate argument count before typed dispatch.
6. Intermediate interfaces remain bodyless on every target framework.
7. Suspend execution arity includes the appended continuation and otherwise
   uses the same callable model.
8. Reflective omission masks scale by 32-bit words and remain a translation to
   Common-owned default dispatch.
9. Typed C# projection remains an optional export layer.

## Freeze conditions

Before ABI freeze, retain adversarial evidence for direct classes and lambdas,
callable references, cross-DLL producer/consumer use, transitive interfaces,
correct and wrong arity tests/casts, primitive boxing boundaries, at least two
reflective mask words, a real suspend/resume call crossing physical arity 23,
both FIR parsers, every CLR profile, stale ABI rejection, emitted physical
surface, and the full target aggregate.
