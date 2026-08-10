# Foreign CLR generic methods retain one exact method-generic identity

- Status: **Accepted (pre-ABI)**
- Scope: method-owned CLR generic parameters on admitted foreign interfaces

## Context

A foreign CLR method such as:

```csharp
T Identity<T>(T value);
U Upcast<T, U>(T value) where T : U;
```

has two relevant views. Kotlin needs ordinary declaration-owned type parameters,
bounds, overload resolution, callable references, and reflection. The CLR slot is
nevertheless a generic MethodDef whose signature uses `!!n`, whose constraints are
GenericParam/GenericParamConstraint rows, and whose calls are MethodSpec
instantiations. Neither view may be reconstructed from the other after import.

This decision extends only the existing fail-closed foreign-interface importer.
It does not make Kotlin-owned generic classes into CLR generic owners and does not
define a general mapping for every CLR generic constraint or constructed type.

## Mature-target precedent

Kotlin/JVM imports Java method type parameters into the semantic frontend model
before parameter and return types are built. Java signatures and annotations
enhance that Kotlin view, while the class-file member remains the backend linkage
authority. Callable reflection subsequently consumes the same semantic type-
parameter graph; it does not privately decode a second generic signature.

JS, Wasm, and Native likewise keep foreign declaration import ahead of backend
emission and retain host identity separately from the Kotlin-facing declaration.
Their reusable rule is the direction of dependency: foreign metadata produces one
semantic declaration and one retained physical binding. A backend convenience does
not create an alternative source-level generic model.

The CLR-specific difference is useful rather than exceptional here. CLR methods
natively support value- and reference-type MethodSpec arguments, so an exact
foreign method generic remains a real CLR method generic instead of being erased or
wrapped.

## Decision

### One semantic declaration

The FIR importer creates every method type parameter before it constructs the
method's bounds, return type, and value parameters. All occurrences refer to those
same symbols. FIR2IR, callable-reference lowering, `KCallable.typeParameters`,
`KType`, bounds, and invocation therefore consume one declaration graph.

Valid distinct metadata names are retained. Invalid or duplicate metadata names
receive stable positional names without changing their physical indices.

### One physical method binding

The selected assembly, declaring TypeDef, and exact MethodDef remain attached to
the imported IR declaration. Backend binding checks that semantic and physical
method arity agree, maps `!!n` and `!!n[]` directly, and emits an ordinary MethodSpec
for each call. It does not erase the method, synthesize a wrapper, rediscover it by
name, or derive its slot from the enhanced Kotlin type.

A Kotlin implementation of an admitted foreign interface fills that same physical
slot. FIR2IR may restore a frontend-approved override edge only after checking the
complete rigid parameter shape against the retained MethodDef. Covariant-return
lowering similarly suppresses a bridge only when parameter and return carriers,
including method-parameter identity, equal that MethodDef.

### Closed admitted grammar

The first accepted slice admits a method only when all of the following are true:

- its owner is already an unambiguous, complete, public, top-level, non-generic
  abstract interface in the selected assembly graph;
- method GenericParam rows are resolved, invariant, correctly numbered, and have
  no `class`, `struct`, `new()`, or by-ref-like special constraint;
- each use is a supported primitive/reference leaf, a method-owned `!!n`, or an
  SZARRAY over one of those leaves;
- `params` is present only on the final supported reference/generic vector;
- every explicit bound is another method parameter, one exact nominal
  non-generic interface, or a non-null constructed interface inside the admitted
  structural grammar; each constructed target's substituted nominal constraints
  must be proved from the declaration-qualified method/owner context; and
- nullable evidence does not explicitly mark a method-generic leaf nullable.

An unconstrained parameter receives Kotlin's ordinary nullable `Any?` default
bound. An oblivious CLR use remains a platform/flexible use. A nominal constraint
is exposed as a non-null Kotlin bound. A nullable Kotlin nominal bound would also
admit `Nullable<S>` for a value type `S` implementing that interface, while the CLR
constraint admits `S` but rejects `Nullable<S>`. Explicit nullable constraint
evidence is therefore rejected rather than silently weakened.

As with the existing importer, one unsupported public member rejects the complete
interface. Special constraints, type-owned parameters outside an admitted owner,
constructed bounds outside the closed interface grammar, nullable constraint roots,
general arrays, pointers/byrefs, malformed rows, unresolved or ambiguous nominal
bounds, and unsupported nullable generic leaves remain absent from Kotlin lookup.

An admitted constructed bound remains one exact TypeSpec in CLR metadata and one
structural Kotlin bound in FIR/IR. Nested Roslyn nullability is aligned before the
Kotlin bound is built; open parameter leaves remain declaration-rigid. Kotlin
implementations emit that same GenericParamConstraint. Structural codegen retains
the GenericInstance as an upper-bound capability, so calls through the open
parameter use the selected foreign owner rather than a name-based remapping.

### Why unconstrained C# `T?` is rejected

Roslyn's unconstrained `T?` does not change the physical `!!0` signature. For a
reference instantiation it describes a nullable reference; for `T = int` the CLR
parameter and result are still `int`, not `Nullable<int>`. Kotlin's `T?` has the
latter real nullable meaning for every `T`.

Projecting that use as Kotlin `T?` would therefore admit calls and implementations
that cannot inhabit the retained MethodDef. Projecting it as non-null would lose
the reference contract. Until a reference-only foreign constraint has an exact
Kotlin admission model, the complete declaration fails closed. The same rule
applies to `T?[]` element evidence. Ordinary nullable array references remain
independent and supported where their element mapping is exact.

## Design attack

Erasing foreign method parameters to `object` would discard native CLR value-type
instantiation, constraints, overload identity, and C# implementation compatibility.
It would also diverge from JVM's semantic-import pattern without a CLR necessity.

Treating the MethodDef as backend-only and decoding generic information again for
reflection would create two authorities that can disagree after enhancement or
separate compilation. Admitting `class`, `struct`, or `new()` as approximate Kotlin
bounds would let Kotlin produce MethodSpecs the CLR rejects. Admitting explicit
unconstrained `T?` because reference-only examples happen to work would make the
source type system promise invalid value-type substitutions.

The closed native-generic route is therefore both simpler and more faithful: use
real CLR method generics exactly where Kotlin can describe their complete contract,
and reject the whole declaration everywhere else.

## Consequences

- Kotlin can infer or explicitly supply foreign method arguments, including CLR
  value types already representable by the backend.
- Relative, nominal, and admitted constructed-interface bounds participate in
  Kotlin checking and retain their physical GenericParamConstraint rows for
  implementations and open bound dispatch.
- Generic arrays, `params T[]`, generic/non-generic overloads, callable references,
  reflection, separate consumers, and reverse C# dispatch share one declaration.
- Primitive MethodSpec results may carry a redundant platform not-null assertion;
  code generation treats an identical non-null CLR scalar as an already-proven
  no-op rather than boxing it.
- Broader CLR special constraints and constructed types outside the admitted
  interface grammar require new exact importer slices. They may extend this
  grammar but may not weaken it.

## Verification obligations

Coverage must retain both Framework CLR and current CoreCLR profiles and prove:

- inferred and explicit MethodSpec calls, value/reference arguments, vectors,
  relative, nominal, and constructed-interface bounds, `params` expansion/spread,
  and overload resolution;
- exact IL MethodSpec signatures and same-object nominal results;
- a Kotlin implementation called through the original C# interface;
- declaration-owned callable type parameters, relative-bound identity, nominal
  bound equality, and reflective invocation; and
- complete-interface rejection for special constraints and explicit nullable
  method-generic leaves, including CodeAnalysis and Roslyn nullable evidence;
  plus frontend rejection on a non-null modern slot and diagnostic physical
  eviction on an oblivious slot when a Kotlin override changes `!!n` to open
  nullable `T?`.
