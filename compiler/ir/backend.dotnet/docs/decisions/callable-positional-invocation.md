# Positional callable invocation

- Status: Accepted (pre-ABI)
- Scope: `KCallable.call` on supported direct function, constructor, property,
  local delegated-property, and admitted foreign CLR references
- Depends on: [`draft-adr-callable-and-reference-abi.md`](draft-adr-callable-and-reference-abi.md)
  and [`callable-parameters.md`](callable-parameters.md)
- Does not enable: `callBy`, callable lookup, member enumeration, accessor
  objects, suspend callable invocation, or arities above the admitted
  `Function0` through `Function3` closure

## Cross-target contract

Common exposes only `KCallable.name`. JVM is the mature-target authority for
the deliberate platform extension `call(vararg args: Any?): R`: arguments are
positional in `parameters` order, every exposed position is required, a vararg
parameter consumes one array argument, and a wrong count is rejected before
the target runs. Native's smaller reflection surface confirms that this is a
platform capability rather than a Common requirement; it does not justify a
different meaning once the capability is published.

JVM performs the call through its already resolved callable rather than doing
a new name lookup for each invocation. Kotlin/.NET follows that dependency
direction. KLIB/importer IR selects the declaration and constructs the one
callable object; CLR execution is only the physical final step.

## Decision

### Reuse the existing erased execution capability

Every supported direct function or constructor reference already implements
one non-generic `FunctionN.Invoke` slot. Every property wrapper already
implements the corresponding `FunctionN` getter view. `KCallable.call` is one
runtime adapter from an `object[]` and the known exposed arity to that existing
slot:

```text
KCallable.call(args)
        |
        +-- validate args.size against the callable's exposed arity
        |
        `-- FunctionN.Invoke(args[0], ...)
```

The adapter performs no `System.Reflection`, metadata-token lookup, member-name
search, overload resolution, or signature reconstruction. The generated
erased `Invoke` bridge remains responsible for argument casts/unboxing, virtual
dispatch, result boxing, and Unit materialization. A target exception therefore
leaves the target directly and preserves object identity; there is no
reflection invocation wrapper.

Runtime surface level 22 adds the erased physical
`Kotlin.KCallable.Call(object[]) -> object` slot and the internal shared
arity adapter. `KCallable<R>` remains logically generic in source and KLIB but
physically non-generic, like the rest of the callable reflection view.

### Count, defaults, and varargs

Wrong arity throws the mapped Kotlin `IllegalArgumentException` carrier with
the JVM message:

```text
Callable expects <expected> arguments, but <actual> were provided.
```

The check happens before indexing or invoking the target. `call` never applies
default values: optional parameters remain required positions. A vararg
parameter is also one required position and receives its already constructed
array. Default masks and omitted vararg construction belong to `callBy`.

### Bound receivers and properties

The arity stored on a direct reference is the arity after bound receiver
positions have been removed. It is the same number exposed by `parameters` and
implemented by the object's `FunctionN` view. An unbound dispatch or extension
receiver is an ordinary leading argument; a bound receiver is absent.

Calling a property invokes its getter, matching JVM. Mutable property `call`
also means getter; mutation stays on `set` and a future setter callable object.
Local delegated-property tokens retain their established unsupported getter
behavior, so a correctly shaped `call()` reaches that same failure rather than
inventing an executable declaration.

### Foreign CLR references

An admitted foreign CLR callable reference already contains a generated
Kotlin `FunctionN` adapter selected from retained importer identity. `call`
uses that adapter and does not reopen the assembly graph. Consequently foreign
virtual dispatch, ParamArray-as-one-array, nullability enhancement, and
exception behavior remain properties of the normal importer/call path.

The public CLR view is intentionally simple: C# can invoke
`KCallable.Call(object[])`. It is an erased reflective operation, not a typed
export surface; idiomatic typed C# APIs remain the responsibility of explicit
.NET export.

## Design attack

### Use `MethodInfo.Invoke`

Rejected. It would make derived CLR metadata a second declaration authority,
wrap target exceptions, reopen visibility and overload questions, and diverge
between Kotlin-owned, imported, and generated property callables.

### Generate one new call body in every reference class

Rejected for this fixed-arity closure. The one runtime adapter can call the
already generated per-reference `Invoke` bridge and therefore does not need to
duplicate casts, boxing, receiver binding, or target logic. Per-reference
generation becomes justified only if a future callable shape cannot be
expressed through the canonical execution capability.

### Treat optional or vararg positions as omittable

Rejected. That is `callBy` semantics and requires stable `KParameter` identity,
default-mask construction, explicit-null versus absent distinction, and empty
vararg synthesis. Mixing it into positional `call` would disagree with JVM and
make argument count dependent on declaration flags.

### Catch and normalize argument type failures

Rejected. The existing erased bridge already performs the exact physical
cast/unbox required by the selected Kotlin declaration. Catching its exception
would risk also catching a same-shaped exception thrown by the target body and
would destroy target exception identity. Wrong count is the only failure owned
by the positional adapter.

## Invariants

1. Callable selection is complete before runtime positional invocation starts.
2. `call` and ordinary `FunctionN.invoke` execute the same target path.
3. Wrong count is rejected before target evaluation with the JVM message.
4. Defaults are not applied and a vararg array occupies one argument position.
5. Bound receivers are absent; unbound receivers retain parameter order.
6. Property `call` is getter invocation; local tokens remain unsupported.
7. Target exceptions propagate unchanged, without reflection wrappers.
8. Logical result/parameter types remain in KLIB; the CLR slot is erased.
9. Foreign callable invocation consumes retained importer binding and performs
   no runtime rediscovery.
10. `callBy` must build on parameter identity and default-call semantics rather
    than changing this positional contract.

## Verification

The gate covers arities zero through three; functions, constructors,
properties, inner constructors, bound and unbound member/extension receivers;
virtual dispatch; generic, primitive, nullable, Unit, Nothing/throwing, default,
and vararg shapes; exact wrong-count text; wrong argument types; unchanged
target exception identity; local delegated-property failure; separate KLIB
production/consumption; imported CLR methods, properties, and ParamArray; the
physical C# `Call(object[])` view; both FIR parsers; both CLR profiles; emitted
IL; runtime-surface version skew; and the full audited aggregate.
