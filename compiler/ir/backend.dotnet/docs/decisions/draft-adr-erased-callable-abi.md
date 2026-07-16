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

Generated function-reference objects follow Native's structural identity model. Every rich
reference with a real reflection target—including a reference used internally as a property
getter or setter—extends metadata-public `Kotlin.Runtime.Internal.FunctionReferenceBase`.
Lambdas continue to extend `System.Object` directly. The base is public in CLR metadata only so
generated subclasses in other assemblies can extend it. Its constructor and bound-value hook are
protected CLR `family` members, so cross-assembly subclasses can use the implementation contract
without publishing those members as general host-callable APIs. It is a runtime implementation
class, not a callable interface, storage type, execution capability, or Kotlin source API.

The base compares the target's stable serialized Kotlin signature, callable arity, adaptation
flags, bound-value count, and each bound value using Kotlin structural equality. When the current
IR has no serialized signature, it uses a deterministic file-local identity containing the logical
file name, declaration offsets, and full Kotlin mangle. Thus two reference expressions for the same
declaration compare equal even though they are distinct generated classes and instances, overloads
and differently adapted references remain distinct, and equal references have equal hashes. Bound
receivers participate by value. Rendering follows Native: `function <name>`, or `constructor` for a
constructor reference. This policy does not change the erased `FunctionN` identity ABI: ordinary
function-type conversions still preserve the same object, while equality between separately created
references is an `Any` semantic supplied by the implementation base. User function implementations
and lambdas remain ordinary identity-equality objects unless their own class overrides `Any`
members. Fun-interface constructor references remain outside this implemented slice because the
backend deliberately rejects fun interfaces until it has a SAM-conversion model; their mature
identity rule must be decided and pinned with that feature.

The candidate is only the Kotlin identity ABI and universal invocation fallback. It does not claim
that erased invocation or untyped CLR signatures are sufficient final execution and export
surfaces.

The POC now evaluates optional execution layers without changing that identity. Every
compiler-generated, non-Unit callable object implements one exact metadata-public interface in
the reserved compiler/runtime namespace:

```text
Kotlin.Runtime.Internal.ExactFunction0<out R> { R InvokeExact() }
Kotlin.Runtime.Internal.ExactFunction1<in P0, out R> { R InvokeExact(P0) }
Kotlin.Runtime.Internal.ExactFunction2<in P0, in P1, out R> { R InvokeExact(P0, P1) }
```

These interfaces are not Kotlin source declarations or a storage ABI. They are public in CLR
metadata only because generated modules must implement and call them across the runtime assembly
boundary. The generated object's original typed body becomes `InvokeExact`; its erased `Invoke`
bridge calls that body and retains the universal `Kotlin.FunctionN` contract.

When the logical parameter and result types at a call site are known, codegen can probe the
corresponding closed `ExactFunctionN` interface and call `InvokeExact` if present. When codegen can
trace a bounded immutable local initializer chain to a more specific fixed FunctionN/KFunctionN
view, it may recover that view's logical signature. It can then probe the corresponding exact
interface after the call-site shape and apply only legal argument/result widenings. For
example, `(Int) -> Int` stored as `(Int) -> Any` can call `ExactFunction1<Int, Int>` with an
unboxed argument and box only the result. CLR-compatible reference variance needs no second probe
because the call-site-shaped interface already succeeds. Statement-position non-Unit invocation
uses the same guarded path and then discards the result.

This recovery is not part of the `ExactFunctionN` contract. No consumer may assume that widening
causes an object to expose the widened closed exact interface, or that codegen will recover an
original interface after arbitrary storage or control flow.

Cross-module measurement justified one additional, narrower execution capability for the CLR
value-type variance hole:

```text
Kotlin.Runtime.Internal.TypedArgumentsFunction1<in P0> { object InvokeTyped(P0) }
Kotlin.Runtime.Internal.TypedArgumentsFunction2<in P0, in P1> { object InvokeTyped(P0, P1) }
```

A generated non-Unit Function1/2 object with at least one concrete primitive or
nullable-primitive parameter implements this optional interface in addition to FunctionN and
ExactFunctionN. `InvokeTyped` calls the same `InvokeExact` body with unboxed arguments and erases
only the result. Func1/2 adapters at the explicit CLR boundary expose the same capability; Action
adapters do not.

Codegen probes this view only when the call site's logical result is `Any`/`Any?` (`object`) and at
least one argument is primitive-shaped. It evaluates the receiver and arguments once, probes the
call-site-shaped TypedArgumentsFunctionN first, then the existing exact shape(s), then erased
Invoke. This is deliberately not a general provenance system or a promise that every widening can
find a partially typed interface. Exact primitive-result calls retain their ExactFunctionN-first
path. Parameter, field, return, and mutable-local boundaries can now take the partial path when the
runtime object proves it, but they retain the erased fallback when it does not.

Otherwise codegen calls erased `Invoke` and performs the established boxing, casting, and
unboxing. Parameters, fields, return values, and mutable locals do not retain trustworthy local
initializer provenance, while older modules and explicit user-written function implementations
may expose no exact capability at all. Every optional path is therefore guarded by `isinst`, and
those cases remain valid fallback providers. A failed fully call-site-shaped exact probe is proof
only that that interface is absent; it is not treated as proof that no typed invocation is
possible when the partial capability or safe local provenance supplies another shape.

Unit callables deliberately remain erased on Kotlin-side execution because CLR `void` cannot be a
generic result argument. The later export boundary may project that object to Action, but the
foreign delegate does not justify a second exact capability on the canonical callable object.

This follows the JVM's semantic pattern of a typed implementation member plus an erased bridge.
The shared optional interface is CLR-specific: it lets a call through the non-generic identity
discover a typed cross-assembly member without knowing the generated implementation class.

## Required layers before promotion

An upstream-quality design needs three distinct layers:

1. **Canonical Kotlin identity ABI.** Non-generic `Kotlin.FunctionN` is the stable storage identity,
   and erased `Invoke` is the universal fallback.
2. **Optional execution ABI.** The implemented candidate uses the internal `ExactFunctionN` and
   narrowly benchmarked `TypedArgumentsFunction1/2` interfaces described above. An exact invocation
   requires a safe call shape (the call-site shape or immutable local provenance) and a matching
   runtime capability. The partial invocation requires an object-shaped logical result, at least
   one concrete primitive-shaped argument, and a matching runtime capability. Otherwise codegen
   uses erased `Invoke`. Neither optimization changes callable identity or adds required members to
   `Kotlin.FunctionN`. Unit, higher arities, broader partial capabilities, and any future direct
   generated-class call remain deliberate follow-ups rather than alternate identities.
3. **CLR export ABI.** Ordinary public APIs intended for C# and other CLR languages need typed
   projections, such as generated `Func`/`Action` adapters or typed facade members. Those
   projections do not participate in Kotlin subtype conversion and may allocate wrappers.

The non-generic top-level function subset of layer 3 is implemented below, including ordinary
functions, Function0/1/2 adaptation, explicit CLR nullability metadata, and Kotlin-default
overloads, including overload-aware selection. The top-level property subset reuses the same
callable boundary and is specified separately in the
[CLR property-export draft ADR](draft-adr-clr-property-exports.md). Other non-function declarations
remain open. Performance validation of layer 2 and representative evidence for the remaining
layer-3 directions are requirements for promoting this draft rather than optional post-promotion
work.

## Identity boundary

Identity preservation applies to ordinary Kotlin subtype conversions, for example assigning a
`() -> Int` value to `() -> Any`. That operation is a reference upcast and must not allocate.

The requirement does not cover semantic callable adaptations such as SAM conversion or adapted
callable references, nor foreign-language export projections. Those operations may create another
object because they are not Kotlin function-type upcasts. A projected delegate is not required to
be reference-identical to the canonical Kotlin callable it wraps.

## Explicit CLR function export boundary

The POC now has a bounded owner for Kotlin-to-CLR projection. Repeatable compiler configuration
uses this spelling:

```text
-Xdotnet-export=<kotlin-selector>=<clr-method-name>
```

It deliberately is not a Kotlin source annotation and does not export every public declaration.
One mapping must select exactly one public, non-generic top-level function. The original Kotlin
method remains unchanged. The compiler adds a user-named static method to that file's existing
facade, retaining ordinary mapped parameter/return shapes and replacing only Function0/1/2
positions with typed Func/Action shapes. An ordinary function with no callable position is still a
valid explicit export; the alias supplies deliberate CLR naming, nullability metadata, and
Kotlin-default overloads. Requiring the CLR name in configuration makes naming an explicit owner
decision; occupied exported signatures are errors rather than backend guesses.

A unique declaration keeps the short selector `pkg.name`. An overloaded group requires a
fully qualified, whitespace-free expanded Kotlin parameter signature, for example
`pkg.name(kotlin.Int,kotlin.Function1<kotlin.Int,kotlin.Int>)`. The extension receiver is the first
parameter. The return type is intentionally absent because Kotlin cannot overload solely by
return type. This spelling identifies the source-level declaration: it does not expose CLR/IL
type tokens, and it does not use a declaration-order index that could change after unrelated
source edits. Multiple overloads of one Kotlin name can therefore be exported independently. A
legacy bare selector for an overloaded group remains an error, as does a signature that is absent
or still ambiguous. Typealiases are matched by their expanded logical type; no promise is made to
distinguish aliases whose expanded declarations are the same.

This follows the mature targets' semantic boundary: JVM naming/default annotations and Wasm/JS
exports preserve the Kotlin declaration and add or expose an explicit host-facing shape. Their
annotations are already bound to one declaration, however, so the textual overload selector has
no target precedent and is not a CLR requirement. It is provisional POC control-plane machinery
needed only because this branch must evaluate the CLR boundary without adding a public Kotlin
annotation prematurely. A future declaration-bound export model should remove the textual
disambiguator rather than standardize this spelling as public ABI.

Generic or suspend functions, KFunction/suspend callable positions, callable markers without a
fixed arity, and arities above two remain outside the slice. These gates keep an incomplete facade
from exposing erased FunctionN in a position that claims to be CLR-friendly. Member functions,
member properties, constructors, classes, and automatic whole-module export also remain out of
scope.

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

The reverse direction constructs a private runtime-owned adapter that stores the original
delegate and implements the erased `FunctionN` identity. A Func adapter also implements the
matching optional `ExactFunctionN` capability; Func1/2 adapters expose TypedArgumentsFunctionN as
well. Exact calls avoid all boxing, while eligible widened-result calls retain typed arguments.
An Action adapter uses the universal erased path and returns `Kotlin.Unit.INSTANCE` after the void
delegate completes. This is a semantic foreign adaptation, so ordinary Kotlin `===`
preservation does not apply to the newly allocated adapter. When that adapter is projected back to
the same closed delegate shape, the helper recognizes its own invariant adapter class and returns
the stored original delegate object. A different closed shape may produce a new delegate.
Kotlin-to-CLR-to-Kotlin is not required to return the original Kotlin callable in this slice.

Nullability comes from source IR at the explicit export boundary, never from erased `FunctionN`.
When at least one exported return or parameter has a non-empty nullable-reference shape, the
compiler synthesizes its reserved `System.Runtime.CompilerServices.NullableAttribute` and emits an
explicit attribute on that shape. A primitive-only export neither emits nor reserves the
attribute. Flags follow
[Roslyn's nullable metadata contract](https://github.com/dotnet/roslyn/blob/main/docs/features/nullable-metadata.md)
in preorder (`0` oblivious, `1` non-null, `2` nullable), including nested generic arguments and
array elements while skipping value types.
This slice deliberately emits deterministic attributes rather than `NullableContextAttribute`
compression or inference from surrounding Kotlin declarations. A nullable callable position maps
null in both directions; a null delegate supplied to a non-null exported parameter still throws
`ArgumentNullException("callable")`. User exceptions pass through the Kotlin method, adapter, and
delegate invocation unchanged because the boundary adds no catch/translation. Any future explicit
exception translation belongs to the interop boundary, not the canonical callable or projection
thunk.

Trailing source defaults add shorter overloads to this explicit surface without changing the
Kotlin declaration or callable identity. They call the existing masked `$default` dispatcher; the separate
[CLR default-export draft ADR](draft-adr-clr-default-argument-exports.md) owns that decision and its
deliberate rejection of implicit CLR optional constants.

Probe series `delegateprojection_s1` first validated direct-exact, erased-thunk, generic-thunk,
Func, Action, repeated-equality, and callback-removal shapes. `delegateexport_s1` then executed all
exact/erased Func and Unit Action arities under modern 10.0.9 and .NET Framework 4.8 ILAsm. Finally,
compiler-produced `delegateexport_compiler_s1` facades plus the landed Kotlin.Runtime helper ran
exact, erased fallback, repeated exact-delegate equality, and Action target/invocation checks on
both runtimes. Repository IL pins cover all Func/Action arities; the CLI test pins the explicit
configuration path. Probe `delegateadapter_s1` then executed Func and Action adapters for arities
zero through two, erased invocation, and original-delegate round trips under both ILAsm/runtime
flavors. Compiler-produced `delegateadapter_compiler_s1` facades and the landed runtime executed
Func/Action parameters and same-object echo round trips on modern CoreCLR and .NET Framework.
Probe `nullableexport_s1` assembled scalar and vector nullable-attribute blobs and exercised
nullable Func/Action adapter round trips under both ILAsm/runtime flavors. CoreCLR's
`NullabilityInfoContext` reconstructed the intended nested nullable states from compiler-produced
facades. `plainfunctionexport_s1` then assembled compiler-produced ordinary aliases with modern
10.0.9 and .NET Framework 4.8 ILAsm; Roslyn 5.6.0 consumers executed primitive, nullable-reference,
default-overload, and extension-receiver calls on both runtimes. Repository pins also prove that a
primitive-only export does not reserve or synthesize `NullableAttribute`.
`overloadedexport_s1` assembled signature-selected aliases with both ILAsm versions; Roslyn 5.6.0
consumers executed primitive/reference overloads, typed callable adaptation, a defaulted
extension, and a nested nullable generic argument on CoreCLR and .NET Framework.

## Consequences

Benefits:

- all Kotlin variance conversions, including primitive and open-generic cases, preserve `===`;
- the candidate public ABI has one predictable shape for fields, parameters, returns, and module
  boundaries;
- callable semantics stay under the Kotlin runtime namespace; and
- typed CLR adapters can evolve separately from the Kotlin-to-Kotlin ABI.

Costs:

- calls whose object does not provide a matching optional capability still box value arguments and
  results and cast or unbox at the erased boundary;
- the exact path adds one guarded interface probe, or two for provenance-recovered widening; an
  eligible object-result/value-argument path adds the partial probe first and may therefore make
  up to three guarded probes before erased fallback;
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
freshness and structural equality/hash/rendering in `ilText/callableReferences.kt`,
`box/callableReferences.kt`, and the local-function fixtures.

Probe series `callableexact_s1` assembled the optional interfaces, exact-capable and erased-only
implementations, and one guarded consumer with modern 10.0.9 and .NET Framework 4.8 ILAsm. All
four same-target and cross-runtime pairings took the exact path for an identical primitive shape,
fell back for an erased-only implementation, used CLR variance for a compatible reference shape,
and rejected value-type variance so the erased fallback remained available. Repository goldens
pin generated exact members and guarded calls in ordinary, capturing, reference, local-function,
and array-initializer contexts. CoreCLR pins additionally cover parameters, open generics,
nullable primitives, reference and primitive variance, captures, bound references, evaluation
order, Unit erasure, and explicit user implementations.

`compiler/testData/codegen/dotnet/ilText/callableInvocationProvenance.kt` separates exact primitive
calls, primitive-result widening, parameter widening, immutable alias chains, function references,
mutable and parameter-boundary capability dispatch, and explicit user fallback. Its widened
`(Int) -> Any` paths now probe TypedArgumentsFunction1 first, then the call-site exact shape and
the locally recoverable original `ExactFunction1<Int, Int>` shape. Every hit avoids argument
boxing while retaining an object result. The exact golden assembles and executes under both
ILAsm/runtime flavors.

Raw probe series `callable_capability_s1` also implemented a partial
`TypedArgumentsFunction1<Int>` interface with `object InvokeTyped(int)`. The fully shaped
`ExactFunction1<Int, object>` probe missed as expected, while the partial probe succeeded and
avoided argument boxing on both modern CoreCLR and .NET Framework. The follow-up
`typed_arguments_crossmodule_s1` compiled the runtime contract, producer, and consumer as separate
Roslyn 5.6 assemblies. Over 2,000,000 widened `(Int) -> Any` calls on CoreCLR, erased invocation
allocated 96,000,040 bytes and partial invocation 48,000,040 bytes: exactly one 24-byte argument
box was removed per call. Stable partial-first timings improved from 40–43 ms to 30–31 ms on
CoreCLR and from 200–213 ms to 141–142 ms on Framework. Each producer gained one InterfaceImpl row
and a 13-byte Function1 or 17-byte Function2 bridge; both small producer PEs remained at the same
4096-byte aligned size. A Unit variant removed its argument allocation but regressed Framework
timing, so the implemented capability excludes Unit rather than generalizing the technically
possible shape. These measurements cross the evidential threshold for the narrow Function1/2
capability above, not for broader partial interfaces.

## Deferred decisions

The POC currently uses generated fields for captures and bound receivers and one invariant generic
mutable-reference cell. Those concrete layouts are implementation evidence, not additional
  callable identities and not standardized by this ADR. This ADR does not decide KCallable metadata
  beyond `name`, property references, reflective lookup/call APIs, suspend callables, member
  export, Unit exact/typed-argument execution, broader or higher-arity typed-argument
  capabilities, or the exact Kotlin
  metadata encoding. Those features must
  preserve the canonical ABI invariants above or explicitly revise this draft before they land.
The POC's .NET Framework 4.8 compatibility is probe evidence for the candidate representation, not
a decision about the eventual product support baseline.

## Promotion or revision

Promote this draft only after all of the following validate the boundary:

- cross-module Kotlin metadata preserves the logical callable types;
- adapted references, suspend callables, property references, and fuller KCallable metadata
  coexist with the candidate identity (capturing/bound function references and the minimal
  KFunction name view are already validated);
- the implemented exact and partial execution strategies receive representative application-level
  benchmarks and are either retained or revised while their erased fallback and single-object
  identity remain intact; and
- representative typed CLR exports give host-language consumers a normal typed surface and define
  adapter reuse/delegate equality, callback registration and removal, repeated Kotlin-to-CLR and
  CLR-to-Kotlin projection, nullability translation, and exception translation.

Revise the draft if a concrete implementation, benchmark, interop experiment, or runtime probe
shows that those requirements cannot be met. Mere convenience for a reference-only CLR fast path
is not enough to split the canonical representation.
