# Draft ADR: Erased Kotlin callable ABI on CLR

- Status: **Draft candidate; implemented in the prototype for evaluation**
- Date: 2026-07-15
- Scope: Kotlin-to-Kotlin callable storage and invocation across CLR assembly boundaries

This is a repository-local decision record for the experimental .NET backend. The entire `dotnet`
branch is a proof of concept; this document keeps that POC internally coherent while evidence is
collected. It does not claim acceptance by the Kotlin project and is not a public KEEP.

## Context

Kotlin function types are logically generic and variant: parameters are contravariant and the
result is covariant. Those rules apply when a type argument is a primitive, a nullable primitive,
or an open type parameter as well as when it is a reference type. A valid conversion such as
`() -> Int` to `() -> Any` or `(Any) -> String` to `(Int) -> Any` must keep the same callable
object so that Kotlin reference identity (`===`) remains true.

The CLR does not provide that model directly. Generic interface and delegate variance is supported
only for reference-type arguments. `System.Func` and `System.Action` also divide value-returning
and void-returning callables into different type families. Adapters can bridge those gaps, but an
adapter is another object and therefore changes observable identity. Making reference-only cases
cheap while wrapping value-type cases would also give one Kotlin rule two physical ABIs.

The callable representation would become a public cross-assembly contract if this draft were
promoted. Once emitted in field, parameter, return, or interface signatures it cannot be replaced
without an ABI break. A candidate canonical form therefore has to preserve all Kotlin subtype
conversions before optimizing common CLR shapes.

## Decision drivers

The canonical ABI must:

1. represent every legal Kotlin function-type variance conversion, including value types and open
   type parameters;
2. preserve callable reference identity across those conversions without allocation;
3. have one stable physical shape across compiler versions and module boundaries;
4. keep Kotlin-owned semantics independent from delegate-specific CLR rules;
5. run on both .NET Framework 4.8 and modern CoreCLR; and
6. admit typed execution paths and .NET-facing adapters without changing the
   canonical Kotlin representation.

## Considered alternatives

### `System.Func` and `System.Action` as the permanent ABI

This is attractive for C# interop and typed calls, but it cannot be canonical. CLR delegate
variance excludes value-type substitutions, `Action` gives Unit-returning callables a different
physical family, and bridging the unsupported cases requires identity-breaking wrapper objects.
It also makes a CLR library type, rather than Kotlin semantics, own the cross-module contract.

### Generic `Kotlin.FunctionN<in ..., out R>` interfaces

Kotlin-owned generic interfaces avoid dependence on `Func`/`Action`, but CLR variance still applies
only when every changed argument is reference-shaped. Primitive and open-generic conversions would
need wrappers or additional representations, so this option does not satisfy the identity and
single-ABI drivers.

### A hybrid canonical representation

A hybrid could use typed delegates or generic interfaces when the CLR admits the conversion and an
erased form otherwise. It may improve selected call sites, but it makes representation depend on
the current instantiation and requires conversion rules at storage, return, and module boundaries.
Some conversions would allocate and lose `===`. The optimization is therefore unsuitable as the
semantic ABI, although separate typed views remain possible and must be validated before
promotion.

### Non-generic, Kotlin-owned interfaces with erased invocation

This is the selected candidate canonical representation. Erasing the physical signature by arity
makes all logical function types of one arity share the same CLR identity. Kotlin's logical type
arguments stay compiler and metadata information rather than CLR generic arguments.

## Candidate decision

The POC currently evaluates these candidate public types in `Kotlin.Runtime`:

```text
Kotlin.Function
Kotlin.Function0 : Kotlin.Function { object Invoke() }
Kotlin.Function1 : Kotlin.Function { object Invoke(object) }
Kotlin.Function2 : Kotlin.Function { object Invoke(object, object) }
```

Direct function references additionally expose this orthogonal reflection view:

```text
Kotlin.KCallable { string name }
Kotlin.KFunction : Kotlin.KCallable, Kotlin.Function
```

`KFunction` deliberately has no `Invoke` member and no arity. A generated direct-reference object
implements `KFunction` and exactly one erased `FunctionN` on the same object. Source
`KFunction0`/`KFunction1`/`KFunction2` signatures map to the non-generic reflection view, while
invocation and widening to a function type use a checked interface view change to the object's
existing `FunctionN` implementation. That operation creates no adapter and preserves reference
identity. Lambdas and adapted references without a KFunction source type remain FunctionN-only.
The reflection view is therefore a capability of the canonical callable object, not another
execution identity or fallback ABI.

This identity is an invariant for subsequent callable work in this POC:

- erased `Kotlin.Function0`/`Function1`/`Function2` remains the only Kotlin callable identity ABI;
- captured values, mutable-capture cells, and bound-reference receivers are fields of generated
  callable classes and do not introduce another callable ABI shape; and
- optional exact-shape members or foreign delegate projections are execution or export
  layers only. They cannot replace the erased interface identity or participate in ordinary Kotlin
  function-type conversions.

In particular, a bound reference must not be represented by a specialized delegate-like wrapper.
Its generated callable object may store a receiver and may expose an exact-shape entry point,
but its Kotlin-facing identity remains the erased `Kotlin.FunctionN` interface and its erased
`Invoke` remains the universal fallback.

The first implementation supports arities zero through two; later arities must follow the same
shape. A Kotlin function type maps to `Kotlin.FunctionN` solely by arity, while its common
`kotlin.Function<R>` view maps to the non-invokable `Kotlin.Function` marker. Projections such as
`Function<*>` do not change that physical marker. A variance conversion is an instruction-free
reference copy and never creates an adapter.

Generated callable classes implement one erased `Invoke` bridge:

- reference arguments are cast on entry;
- primitive, nullable-primitive, and open-type arguments use `unbox.any` on entry;
- value results are boxed on exit; and
- a Kotlin Unit result executes the ordinary void body and returns `Kotlin.Unit.INSTANCE` from the
  erased bridge.

The prototype may cache non-capturing callable objects as singletons. That is an implementation
policy, not part of this ABI candidate. Regardless of allocation strategy, the same physical
instance is observed before and after every ordinary Kotlin function-type subtype conversion.

Logical parameter and result types remain in IR today and must be written to Kotlin metadata before
the backend supports Kotlin cross-module consumption. The canonical `Kotlin.FunctionN` interface
does not encode those types, and CLR reflection is not sufficient to reconstruct the Kotlin
function type even if future optimization members are themselves visible. The implemented
KFunction reflection contract currently exposes only the stable common `name` property; complete
signature/owner/parameter metadata is a separate contract.

The candidate is only the Kotlin identity ABI and universal invocation fallback. It does not claim
that erased invocation or untyped CLR signatures are sufficient final execution and export
surfaces.

The POC now evaluates a first exact-shape execution layer without changing that identity. A
compiler-generated, non-Unit callable object also implements one metadata-public interface in the
reserved compiler/runtime namespace:

```text
Kotlin.Runtime.Internal.ExactFunction0<out R> { R InvokeExact() }
Kotlin.Runtime.Internal.ExactFunction1<in P0, out R> { R InvokeExact(P0) }
Kotlin.Runtime.Internal.ExactFunction2<in P0, in P1, out R> { R InvokeExact(P0, P1) }
```

These interfaces are not Kotlin source declarations or a storage ABI. They are public in CLR
metadata only because generated modules must implement and call them across the runtime assembly
boundary. The generated object's original typed body becomes `InvokeExact`; its erased `Invoke`
bridge calls that body and retains the universal `Kotlin.FunctionN` contract.

When the logical parameter and result types at a call site are known, codegen evaluates the
receiver and arguments once, probes the corresponding closed `ExactFunctionN` interface, and
calls `InvokeExact` if present. Otherwise it calls erased `Invoke` and performs the established
boxing, casting, and unboxing. This makes older modules and explicit user-written function
implementations valid fallback providers. CLR reference-type variance can make a compatible exact
view succeed; CLR value-type variance cannot, so those widened calls safely use the erased path.
Unit callables deliberately remain erased on Kotlin-side execution because CLR `void` cannot be a
generic result argument. The later export boundary may project that object to Action, but the
foreign delegate does not justify a second exact capability on the canonical callable object.

This follows the JVM pattern of a typed implementation member plus an erased bridge. The shared
optional interface is CLR-specific: it lets a call through the non-generic identity discover a
typed cross-assembly member without knowing the generated implementation class.

## Required layers before promotion

An upstream-quality design needs three distinct layers:

1. **Canonical Kotlin identity ABI.** Non-generic `Kotlin.FunctionN` is the stable storage identity,
   and erased `Invoke` is the universal fallback.
2. **Exact-shape execution ABI.** The implemented candidate uses the optional internal
   `ExactFunctionN` interfaces described above. A typed invocation is admissible only when the
   compiler knows the logical call shape and the runtime object proves that it provides the
   matching closed capability. Otherwise it uses erased `Invoke`. The optimization does not
   change callable identity or add required members to the candidate `Kotlin.FunctionN`
   interfaces. Unit, higher arities, benchmark-guided call-site selection, and any future direct
   generated-class call remain deliberate follow-ups rather than alternate identities.
3. **CLR export ABI.** Ordinary public APIs intended for C# and other CLR languages need typed
   projections, such as generated `Func`/`Action` adapters or typed facade members. Those
   projections do not participate in Kotlin subtype conversion and may allocate wrappers.

The factory-return subset of layer 3 is implemented below; reverse adapters, nullability metadata,
and broader export selection remain open. Performance validation of layer 2 and representative
evidence for the remaining layer-3 directions are requirements for promoting this draft rather
than optional post-promotion work.

## Identity boundary

Identity preservation applies to ordinary Kotlin subtype conversions, for example assigning a
`() -> Int` value to `() -> Any`. That operation is a reference upcast and must not allocate.

The requirement does not cover semantic callable adaptations such as SAM conversion or adapted
callable references, nor foreign-language export projections. Those operations may create another
object because they are not Kotlin function-type upcasts. A projected delegate is not required to
be reference-identical to the canonical Kotlin callable it wraps.

## First explicit CLR delegate export boundary

The POC now has a bounded owner for Kotlin-to-CLR projection. Repeatable compiler configuration
uses this spelling:

```text
-Xdotnet-export=<kotlin-fq-name>=<clr-method-name>
```

It deliberately is not a Kotlin source annotation and does not synthesize overloads for every
public declaration. One mapping must select exactly one public, non-generic top-level factory that
returns a non-null Function0/1/2. The original Kotlin factory and its FunctionN return signature
remain unchanged. The compiler adds one user-named static method to that file's existing facade,
with the factory's ordinary parameters and a typed Func/Action return. Requiring the CLR name in
configuration makes naming an explicit owner decision; overloaded Kotlin names and an occupied
facade method name/signature are errors rather than backend guesses.

This first slice is intentionally one-way. Callable parameters require delegate-to-Kotlin
adaptation and are rejected. Nullable callable returns require CLR nullability metadata and are
rejected. Generic factories, KFunction/suspend returns, and arities above two are likewise outside
the slice. These gates keep an incomplete facade from exposing erased FunctionN in a position that
claims to be CLR-friendly. They also mean no adapter round trip exists yet; the reverse direction
must be added explicitly at this same boundary and must restore an original delegate when it sees
its own adapter.

The implementation does not require another Kotlin callable representation. The generated facade
passes the canonical FunctionN object to metadata-public
`Kotlin.Runtime.Internal.DelegateProjection`. For a matching non-Unit `ExactFunctionN` view, the
helper binds a `Func` delegate directly to `InvokeExact`. For an erased-only object, a closed static
generic thunk binds the canonical FunctionN object as its first argument and performs the existing
box/unbox fallback. A Unit projection uses the same closed-thunk shape, discards the erased
`Kotlin.Unit` result, and exposes `Action`. The delegate itself is the allowed foreign projection
object; no Kotlin-side wrapper is introduced by Kotlin-to-CLR conversion.

Repeated projection of the same callable to the same closed delegate type produces delegates with
the same target and method, so CLR delegate equality succeeds and a separately projected value can
remove an earlier event registration. This follows the CLR equality contract rather than requiring
a cache. A different closed delegate shape is a different projection and has no equality promise.
Future delegate-to-Kotlin conversion may allocate a generated adapter that stores the delegate,
implements the erased `FunctionN` identity, and exposes the optional exact capability for `Func`;
that is a semantic foreign adaptation, so ordinary Kotlin `===` preservation does not apply. A
later implementation must return the original delegate when such an adapter is projected back. It
must also specify whether Kotlin-to-CLR-to-Kotlin returns the original callable or an equivalent
adapter; the canonical ABI does not require that round trip to preserve identity.

Nullability must come from export metadata rather than from `FunctionN`; until that layer exists,
nullable callable factory returns are rejected. User exceptions pass through factory and delegate
invocation unchanged because the facade and thunks add no catch/translation. Any future explicit
exception translation belongs to the interop boundary, not the canonical callable or projection
thunk.

Probe series `delegateprojection_s1` first validated direct-exact, erased-thunk, generic-thunk,
Func, Action, repeated-equality, and callback-removal shapes. `delegateexport_s1` then executed all
exact/erased Func and Unit Action arities under modern 10.0.9 and .NET Framework 4.8 ILAsm. Finally,
compiler-produced `delegateexport_compiler_s1` facades plus the landed Kotlin.Runtime helper ran
exact, erased fallback, repeated exact-delegate equality, and Action target/invocation checks on
both runtimes. Repository IL pins cover all Func/Action arities; the CLI test pins the explicit
configuration path.

## Consequences

Benefits:

- all Kotlin variance conversions, including primitive and open-generic cases, preserve `===`;
- the candidate public ABI has one predictable shape for fields, parameters, returns, and module
  boundaries;
- callable semantics stay under the Kotlin runtime namespace; and
- typed CLR adapters can evolve separately from the Kotlin-to-Kotlin ABI.

Costs:

- calls whose object does not provide the matching exact capability still box value arguments and
  results and cast or unbox at the erased boundary;
- exact-capable calls add a guarded interface probe until profiling justifies a narrower direct
  path;
- the raw POC interface is untyped to C# and is not proposed as the final CLR export surface;
- overloads that differ only in logical function type arguments have the same CLR signature and
  must be rejected as platform clashes; and
- logical callable types cannot be reconstructed from CLR signatures alone, so Kotlin metadata is
  mandatory for future cross-module Kotlin compilation.

## Validation

Probe series `callableabi_s2` assembled an erased runtime/consumer pair with modern 10.0.9 and
.NET Framework 4.8 ILAsm. All four same-assembler and cross-assembler runtime pairings preserved
object identity and executed a boxed `Int` result.

Repository pins cover both FIR parsers and real CoreCLR execution:

- `compiler/testData/codegen/dotnet/ilText/callableObjects.kt`;
- `compiler/testData/codegen/dotnet/ilText/callableCaptures.kt`;
- `compiler/testData/codegen/dotnet/ilText/callableObjectsRejected.kt`; and
- `compiler/testData/codegen/dotnet/box/callableObjects.kt`.

Probe series `captureabi_s3` then compiled the capturing implementation itself with both ILAsm
versions. All four same-target and cross-runtime pairings executed immutable and mutable captures,
a Unit-mutating closure, an open-generic cell, and primitive and reference bound receivers. The
generated classes acquired no capture-specific callable interface: at that stage lambdas
implemented only the erased `Kotlin.FunctionN`, while direct references could additionally carry
the fixed KFunction reflection view described above. The later exact-capability slice adds the
same optional execution interface to eligible capturing and non-capturing objects; it is unrelated
to capture layout. Captures and bound receivers appeared only as fields, mutable state used one invariant
`Kotlin.Runtime.Internal.MutableRef<T>` cell, and no stateful callable had a singleton cache.

Probe series `kfunction_s1` assembled a runtime and a direct reference implementing both
`Kotlin.KFunction` and `Kotlin.Function1` with modern 10.0.9 and .NET Framework 4.8 ILAsm. All four
same-target and cross-runtime pairings read `KCallable.name`, observed one object through the
KFunction and Function1 views, and invoked the erased Function1 slot. Repository IL and CoreCLR
pins cover non-capturing, bound, and local references, name metadata, erased invocation,
KFunction-to-Function identity, KFunction variance identity, singleton reuse, and stateful
freshness in `ilText/callableReferences.kt`, `box/callableReferences.kt`, and the local-function
fixtures.

Probe series `callableexact_s1` assembled the optional interfaces, exact-capable and erased-only
implementations, and one guarded consumer with modern 10.0.9 and .NET Framework 4.8 ILAsm. All
four same-target and cross-runtime pairings took the exact path for an identical primitive shape,
fell back for an erased-only implementation, used CLR variance for a compatible reference shape,
and rejected value-type variance so the erased fallback remained available. Repository goldens
pin generated exact members and guarded calls in ordinary, capturing, reference, local-function,
and array-initializer contexts. CoreCLR pins additionally cover parameters, open generics,
nullable primitives, reference and primitive variance, captures, bound references, evaluation
order, Unit erasure, and explicit user implementations.

## Deferred decisions

The POC currently uses generated fields for captures and bound receivers and one invariant generic
mutable-reference cell. Those concrete layouts are implementation evidence, not additional
callable identities and not standardized by this ADR. This ADR does not decide KCallable metadata
beyond `name`, property references, reflective lookup/call APIs, suspend callables, reverse
delegate adapters and their round trips, nullable CLR export metadata, broader export selection,
Unit exact execution, or the exact Kotlin metadata encoding. Those features
must preserve the canonical ABI invariants above or explicitly revise this draft before they land.
The POC's .NET Framework 4.8 compatibility is probe evidence for the candidate representation, not
a decision about the eventual product support baseline.

## Promotion or revision

Promote this draft only after all of the following validate the boundary:

- cross-module Kotlin metadata preserves the logical callable types;
- adapted references, suspend callables, property references, and fuller KCallable metadata
  coexist with the candidate identity (capturing/bound function references and the minimal
  KFunction name view are already validated);
- the implemented exact-shape strategy is benchmarked and either retained or revised while its
  erased fallback and single-object identity remain intact; and
- representative typed CLR exports give host-language consumers a normal typed surface and define
  adapter reuse/delegate equality, callback registration and removal, repeated Kotlin-to-CLR and
  CLR-to-Kotlin projection, nullability translation, and exception translation.

Revise the draft if a concrete implementation, benchmark, interop experiment, or runtime probe
shows that those requirements cannot be met. Mere convenience for a reference-only CLR fast path
is not enough to split the canonical representation.
