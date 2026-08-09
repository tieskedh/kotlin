# Named callable invocation

Library ABI version: 22. Runtime surface level: 23.
The current fixed-arity closure does not advance library ABI 27 and requires
runtime surface level 27 for `Function4` through `Function22`.

- Status: Accepted (pre-ABI)
- Scope: `KCallable.callBy` on the callable-reference surface already admitted
  by Kotlin/.NET
- Depends on: [`callable-positional-invocation.md`](callable-positional-invocation.md)
  and [`callable-parameters.md`](callable-parameters.md)
- Does not enable: runtime member lookup, suspend invocation, arbitrary
  `KCallable` implementations, annotation-constructor callable references, or
  the vararg big-arity representation for arity 23 and above

## Cross-target contract

Common does not require executable reflection. Once Kotlin/.NET deliberately
publishes `callBy`, JVM is the mature-target authority for its meaning:

1. a present map key supplies its value, including an explicit `null`;
2. an absent optional value parameter selects its Kotlin default;
3. an absent vararg receives a fresh empty array of the declaration's exact
   array type;
4. every other absent parameter is rejected with
   `No argument provided for a required parameter: <parameter>`;
5. receiver parameters never participate in a default mask;
6. unknown map entries are not a separate validation error;
7. when no optional value is absent, invocation uses the ordinary positional
   path; and
8. target exceptions leave the selected declaration unchanged.

Native, JS, and Wasm do not supply a competing executable-reflection contract.
Their smaller surfaces therefore justify keeping this capability bounded, not
giving the same API another meaning.

## Decision

### Keep argument interpretation in Runtime

The runtime callable base already owns the exact parameter objects created for
one reference. It retains the private erased parameter descriptors used to
construct those objects and can therefore distinguish present, optional, and
vararg positions without depending on stdlib's physical `KParameter` class.
It consumes only the stable erased `Kotlin.Collections.Map` and `List` slots.

`callBy` first builds the same `object[]` consumed by `call`. A present key is
read exactly once. A missing required parameter fails before target execution.
When no optional value is missing, the existing `FunctionN.Invoke` path is
used unchanged.

### Reuse ordinary default-call lowering

A generated direct reference supplies one protected implementation capability
containing an ordinary IR call with every optional argument absent. The later
shared default-argument and class/interface-default lowerings rewrite that
template to the same masked dispatcher used by source calls. A late .NET pass
then replaces only the dispatcher placeholders with selections from the
runtime argument array and translates the exposed-position omission mask to
the Common receiver-free physical mask layout.

This produces one body plus work linear in the number of optional positions
for the admitted `KFunction0` through `KFunction22` closure. It deliberately
does not generate one branch per omission combination: 22 optional parameters
would otherwise require more than four million helpers. Constructors,
inherited defaults, interface defaults, separate libraries, placeholder
values, and virtual dispatch still reuse the ordinary compiler path. No
runtime reflection and no reflection-private constructor/default ABI are
introduced.

That separate-compilation proof exposed a more general class-default ABI
defect: the Common factory's instance-shaped class dispatcher could execute
inside one compilation but did not match the target's external-dispatcher
record. Kotlin/.NET therefore normalizes ordinary Kotlin class dispatchers to
the JVM-shaped static compiler ABI, with the receiver as an explicit first
parameter. Kotlin-owned class parameters remain physically erased; genuine
method-owned parameters retain their CLR generic slots, while KLIB retains the
logical owner construction. The helper still invokes the original member
virtually. Source calls and `callBy` now share this one cross-module
default-dispatch route; the correction is not reflection policy. Library ABI
version 22 rejects the former instance-shaped class-dispatch record instead of
allowing a consumer to fail with `MissingMethodException` at execution.

The runtime mask describes exposed callable positions only. It is private to
the generated reference class; the shared lowering remains the sole owner of
the producer's actual default-mask layout.

### Create omitted varargs in the generated reference

An absent vararg is not a default argument. The generated reference creates a
fresh empty array using the compiler-known substituted element and array type,
then returns it to the runtime argument collector. This avoids reconstructing
`KType`, inspecting CLR metadata, or guessing a vector type from a boxed value.
For an imported C# `params T[]`, importer IR may expose Kotlin's projected
`Array<out T>` view; construction deliberately uses the invariant physical
`Array<T>` carrier while parameter reflection preserves that logical
projection.

### Properties

Property parameters are required receivers and cannot be optional or vararg.
Their runtime bases therefore use the same map-presence and error rule, then
invoke the established getter `FunctionN` view. A local delegated-property
token still reaches its existing unsupported getter failure after an empty map
has been accepted.

## Design attack

### Interpret `KParameter` through CLR reflection

Rejected. Logical parameter kind, optionality, and vararg identity are
KLIB/importer facts. Rediscovering them from a `MethodInfo` would create a
second authority and would not describe Kotlin default dispatch reliably.

### Add a new generic default-dispatch ABI

Rejected. The shared Kotlin default-argument lowering already owns functions,
constructors, inherited defaults, masks, marker parameters, and library calls.
The one all-omitted template lets it select the dispatcher before the target
patches runtime values into that selected call; Runtime and the CIL emitter do
not reproduce default-dispatch policy.

### Put `callBy` policy in target-framework profiles

Rejected. `net48`, `netstandard2.0`, and `net10.0` do not differ in Kotlin's
named-call semantics. Framework profiles describe physical CLR capabilities;
they are not owners of reflection semantics or compiler lowerings.

### Validate every map key

Rejected. JVM does not make extra keys a separate precondition. Iterating the
callable's own parameter list also preserves explicit-null versus absence and
avoids requiring a second map traversal.

## Invariants

1. KLIB/importer IR remains authoritative for parameter identity and flags;
   map lookup uses the admitted JVM-shaped callable-plus-index equality.
2. Map presence, not a nullable value, determines whether an argument exists.
3. Receivers never consume default-mask bits.
4. The shared default-argument lowering remains the only producer-mask owner.
5. Omitted varargs are fresh arrays of the exact substituted array type.
6. `callBy` without omitted optionals executes the same path as `call`.
7. Constructors and separate libraries receive no weaker semantics.
8. No `System.Reflection`, name lookup, or exception wrapping is introduced.
9. Target profiles do not own target-independent Kotlin semantics.
10. Ordinary class defaults have one static cross-module compiler ABI shared
    by source calls and reflective calls; its explicit receiver preserves
    virtual dispatch.
11. Generated reflective-default code grows linearly, not combinatorially, in
    the number of optional parameters.

## Verification

The gate must cover explicit null versus absence; missing required receivers
and values with the JVM message; ignored foreign keys; dependent and
side-effecting defaults; primitive placeholders; mixed optional/vararg calls;
fresh primitive, reference, and generic vararg arrays; bound and unbound
receivers; inherited defaults and virtual dispatch; ordinary and inner
constructors; properties and local delegated properties; exception identity;
separate portable KLIB consumption; imported CLR callables without invented
optional semantics; erased generic class owners beside genuine generic methods;
both FIR parsers; both CLR profiles; emitted IL; runtime surface skew; and the
full audited aggregate. Fixed-arity scale is pinned by a 22-parameter function
whose dependent defaults can all be omitted or selectively supplied, plus
producer- and consumer-created references across separate DLLs.
