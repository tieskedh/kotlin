# ADR: erased identity and typed capability for generic classes

- Status: **Accepted — pre-ABI**
- Date: 2026-08-01
- Scope: Kotlin-owned ordinary generic classes, projections, casts, runtime
  identity, member dispatch, inheritance, and CLR consumption

This is the selected direction for the experimental target. It is not a
public KEEP or an official Kotlin target commitment. Generic interfaces keep
their separate split-interface decision; arrays keep their structural vector
decisions.

KLIB metadata remains authoritative for the Kotlin declaration, arguments,
variance, projections, nullability, and bounds. The physical binding records
every CLR view explicitly.

## Common contract

Kotlin runtime class identity erases generic arguments. For one Kotlin class
declaration `Box<T>`:

- `Box<String>`, `Box<Int>`, and `Box<*>` share one runtime classifier;
- `value is Box<*>` tests the declaration and not a constructed type;
- an unchecked `value as Box<Int>` accepts a `Box<String>` object, while a
  later read or call may fail when the erased result or argument is narrowed;
- declaration- and use-site variance change the legal operations, not object
  identity;
- casts and projected views preserve `===`, mutation, virtual dispatch, and
  the original object; and
- subclasses satisfy erased tests of every generic base declaration in their
  Kotlin inheritance chain.

The representation must therefore support typed use after a successful
erased cast. A predicate which reports success but leaves a physically
incompatible `C<U>` receiver is not an implementation of the Common contract.

## Mature-target evidence

- JVM emits one raw class identity. Generic signatures describe source-facing
  constructions, while `instanceof` and `checkcast` use the erased class and
  bridge/member boundaries cast erased values as required.
- JS tests the one generated Kotlin constructor/class identity. Type
  arguments do not produce JavaScript constructors or participate in
  `instanceof`; member bodies operate on the same object.
- Native's type-operator lowering explicitly erases type parameters before
  checked and safe casts. Runtime subtype tests use declaration type
  information, while generic call results are narrowed at their use boundary.
- Wasm maps a Kotlin type to its `erasedUpperBound` runtime class. Reference
  storage and `ref.test`/`ref.cast` therefore name the declaration class, not
  a constructed generic runtime type.

All four models separate logical generic construction from runtime class
identity. None makes a closed target-generic instantiation Kotlin identity.

## CLR constraint

CLR constructed classes are reified and invariant. `Box<String>` and
`Box<Int32>` are distinct runtime types, and neither is assignable to
`Box<Object>`. A CLR check against any one construction is consequently too
narrow for Kotlin.

The CLR also provides valuable facilities which Kotlin need not discard:
typed generic classes, unboxed value arguments, typed fields and methods,
native generic inheritance, and natural C# construction/subclassing. Those
facilities are truthful capabilities for an object created as `Box<T>`; they
are not a truthful universal Kotlin cast identity.

CLR has one base-class slot, so a non-generic canonical base cannot be added
to every generic class without breaking Kotlin inheritance. A non-generic
interface can be implemented alongside the real base class.

## Decision

### Canonical Kotlin view

Every Kotlin-owned ordinary generic class declaration gets one non-generic
canonical CLR interface. It uses the declaration's unsuffixed physical base
name; the typed class retains the normal arity-suffixed name. Thus logical
`sample.Box<T>` is recorded as both `sample.Box` and `sample.Box`1`, without
reconstructing either name from the other.

Every Kotlin ABI value position for that declaration uses the canonical
interface, regardless of its logical arguments or projection. This includes
fields, parameters, returns, locals, ordinary upcasts, checked/safe casts, and
star projections. KLIB retains the exact logical type.

The canonical interface owns one deterministic erased slot for every
non-private Kotlin member callable through such a value. Private members stay
only on the typed implementation: no legal external Kotlin call needs them,
and publishing an erased slot would widen the metadata surface. Arguments are
boxed/widened at the call boundary; results are narrowed or unboxed at the
logical use site.
Projected-out members remain unavailable because FIR/Common, not the CLR
interface, decides which source call is legal.

The direct carrier of an owner type parameter is `object`, matching the
existing split-interface barrier and admitting every truthful CLR
construction. Nested split Kotlin classifiers retain their own canonical
identity; a nested carrier without such an identity erases as a whole rather
than selecting one closed CLR construction.

The canonical class interface inherits only direct interface views that map
to a truthful class-like canonical carrier without inventing an
owner-dependent construction. An interface capability whose physical shape
still mentions the class's `T` remains on typed `C<T>` only. The typed class
retains its complete exact interface set; the canonical sibling must not turn
an exact `I<T>` capability into a fictitious `I<object>` edge. Common/FIR
still determines which interface operation is legal through the logical
receiver.

Canonical member names derive from complete logical identities and are
recorded in the physical ABI. They do not depend on declaration order or the
current overload set. Erasure collisions must receive deterministic physical
names or reject the whole declaration; CLR construction types must never be
substituted merely to keep overloads distinct.

Cross-module declarations derive that identity from the authoritative public
KLIB `IdSignature`. A member below a private or local owner must not reuse its
file-local IR symbol signature: lowered type-parameter identities in that
signature are process-local. Such owner-scoped slots use an explicit
structural codec of stable classifier names, projections, nullability,
parameter kinds, and owner/method type-parameter indices. Type rendering,
object hashes, declaration order, and source offsets are forbidden inputs.

This also applies outside the class itself. If an ordinary Kotlin callable has
a parameter containing a split generic class, its canonical CLR parameter no
longer contains that class's arguments. The callable therefore receives a
stable `__KotlinErased__<digest>` physical suffix derived from its complete
Kotlin signature, even before a colliding overload exists. Existing classified
exception carriers retain their older `__KotlinException__<digest>` spelling.
KLIB and the physical function record restore the source name; an explicit C#
export product may later provide source-named typed overload adapters.

### Typed CLR implementation and capability

The existing invariant CLR generic class remains the physical implementation.
It owns constructors, backing fields, typed source members, class generic
parameters and constraints, the real base-class edge, and the natural C#
subclassing surface. CLR classes remain invariant even when the Kotlin
declaration parameter is `in` or `out`; Kotlin variance lives in KLIB and
canonical conversions.

The typed class implements its canonical interface on the same object.
Generated forwarding members and explicit `MethodImpl` rows adapt canonical
erased slots to typed virtual/source members. They cast or unbox an erased
input before dispatch and box or widen a typed result afterward. A cast never
allocates an adapter, wrapper, proxy, or copy.

Canonical dispatch remains the stable semantic fallback for Kotlin calls. A
call may use the typed `C<T>` member directly when the receiver's physical
capability is proven, or probe that capability once with `isinst C<T>` and use
canonical dispatch when the probe misses. The receiver and arguments are
evaluated once in Kotlin order, typed virtual calls retain CLR override
dispatch, and both paths return the same logical Kotlin value. The probe never
turns `C<T>` into Kotlin runtime identity: it selects an optional execution
capability on the same object.

The first provenance boundary is deliberately local and conservative. A
fresh `C<T>` construction and immutable local aliases which lead directly to
that construction have a guaranteed capability. A static Kotlin `C<T>` type,
parameter, field, mutable local, control-flow join, or cast does not: an
unchecked Kotlin cast can produce that logical view while the object remains a
different CLR construction. Such receivers take the guarded path. When a
guard misses, the canonical call preserves Kotlin's required delayed failure
at the later argument/result use barrier. That exceptional path is a
correctness path, not an optimization target.

C# may construct, call, and derive from the typed `C<T>` class directly.
Kotlin functions expose their canonical Kotlin ABI unless an explicit C#
export product later emits a typed adapter.

Compiler-generated default-argument dispatchers are not source members and
do not acquire canonical member slots of their own. Their exact logical
receiver type in IR is the authority for recovering the typed owner needed by
the helper body; its arguments and result then cross the same ordinary
canonical boxing/narrowing barriers as every other call. This preserves Common
default-expression semantics without publishing a second erased class
contract or deriving a closed owner from the canonical CLR interface.

### Runtime classification and casts

The canonical interface is necessary for storage and dispatch but is not by
itself Kotlin class identity: public compiler ABI can be named and implemented
by foreign CLR code.

One runtime classifier therefore receives the original object and the exact
producer-recorded open generic class TypeDef. It walks the object's CLR base
class chain and succeeds when a constructed base has that exact generic type
definition. This admits Kotlin classes and ordinary C# subclasses of the
typed class, rejects an unrelated implementation of the canonical interface,
and ignores constructed arguments.

`is`, `!is`, `as`, and `as?` share that classifier. Successful casts return
the original reference through the canonical interface. Non-null checked
casts apply the existing Kotlin null barrier; safe casts return null on a
wrong declaration. The operand is evaluated once.

The open TypeDef identity comes from the producer's physical binding. A
consumer never searches assemblies or reconstructs a name. Nested generic
classes record both complete owner paths, and the runtime token names the
typed definition at that path.

### Inheritance and dispatch

A typed generic class keeps its exact CLR base instantiation and implements
its own canonical interface. Its base class supplies the base declaration's
canonical interface and bridge set. A non-generic subclass of `Base<String>`
therefore satisfies `Base<*>` without adding an adapter.

A canonical interface cannot inherit a non-generic CLR base class. When Kotlin
upcasts a canonical generic-class value to such a base, code generation first
proves that the producer-recorded typed capability is assignable to the base
and then emits a same-object checked reference cast. This is a representation
bridge, not a second Kotlin runtime classifier; a hostile value which violates
the compiler-owned canonical contract fails rather than acquiring false base
identity.

Canonical bridges dispatch virtually to the typed source slot. A C# subclass
override of an open Kotlin typed member remains observable through Kotlin
canonical calls. Abstract obligations remain abstract until a concrete class
supplies a body; inherited bridge reuse must not duplicate state or bypass an
override.

`super` calls, constructor delegation, backing-field access, and private or
protected implementation code continue to use the exact typed owner inside
the implementation. They are not rewritten into public canonical calls.

### Physical ABI and foreign boundaries

The class binding records:

- the canonical interface owner path;
- the typed class owner path and invariant generic arity;
- canonical member slots and typed implementation members;
- every forwarding method and `MethodImpl` relationship; and
- the open typed TypeDef used by the runtime classifier.

This expands the meaning of the versioned class-view schema and therefore
requires an ABI-version bump. A stale producer is rejected; no compatibility
shim infers views from arity or spelling.

The canonical interface and its slots are marked as compiler ABI and hidden
from normal completion. They are not permission for C# to author a Kotlin
class. Direct foreign calls can physically pass an implementation of
that interface, just as a `System.Array` signature is broader than KLIB's
`Array<*>` contract. Kotlin runtime tests/casts still reject it. Explicit C#
authoring of a Kotlin class means subclassing the typed CLR class where Kotlin
modality permits it.

## Scope boundary

This decision covers ordinary Kotlin-owned generic classes already admitted
by the class model and establishes the representation needed to admit their
stars, projections, and erased casts. It does not by itself enable:

- value/inline classes;
- annotation or enum classes;
- reflection, `KClass`, `KType`, or `typeOf`;
- generic interfaces beyond their existing split-interface ABI;
- foreign CLR generic classes as Kotlin-owned declarations;
- currently rejected nested open-nullable carriers whose typed construction
  needs an additional erased implementation choice; or
- either public reified-inline support gate.

Reified substitution may use this classifier only after the remaining
operation closure is complete.

## Decision evidence

A representative .NET 10 microbenchmark over two million `Box<Int>` operations
measured the permanent cost which motivated this bounded path. Canonical reads
allocated about 48 MB and took 11.77 ms; a capability probe at every read
allocated 64 bytes and took 1.75 ms, while an explicitly hoisted probe and a
direct typed call both took about 0.97 ms. Canonical read/write allocated about
96 MB and took 19.05 ms; a probe at every update allocated 64 bytes and took
3.49 ms, compared with 3.63 ms for the explicitly hoisted form and 3.10 ms for
the direct typed form.

These are microbenchmark observations rather than language semantics, but they
show that one ordinary per-call probe already removes the boxing and allocation
problem and recovers most of the typed route's throughput. Committed IL tests
therefore pin allocation-free value-type fast branches and canonical fallback
branches, while runtime and separate-library tests pin behavior. The residual
gap does not justify compiler-managed loop versioning or global provenance
analysis without further measurements from actual target programs.

## Design attack

- **Keep only closed CLR `C<T>` types.** Rejected. Runtime checks become too
  strict and unchecked Kotlin casts fail before the first typed use.
- **Map `C<*>` to `C<object>`.** Rejected. CLR classes are invariant, and
  value/reference constructions are unrelated to that type.
- **Add only a reflection predicate.** Rejected. A successful cast still has
  no physical receiver on which projected or mismatched typed members can be
  called.
- **Reinterpret one constructed reference as another.** Rejected. Unverifiable
  type confusion can dispatch against the wrong field/method instantiation
  and is not Kotlin erasure.
- **Wrap or copy after a cast.** Rejected. It changes identity, state,
  synchronization, virtual dispatch, and foreign subclass behavior.
- **Erase the CLR class completely.** Semantically valid but rejected for the
  selected .NET target. It discards truthful CLR generic storage, value
  specialization, inheritance, and C# usability even though the split view
  can preserve Kotlin semantics.
- **Use a canonical marker without erased members.** Rejected. Smart-cast and
  post-cast typed use would require reflection or an incompatible closed
  receiver.
- **Trust the canonical interface as class identity.** Rejected. Foreign code
  could implement it without inheriting the Kotlin class. Runtime operations
  must verify the typed class ancestry.
- **Choose canonical versus typed storage by local provenance.** Rejected.
  Values cross fields, joins, libraries, unchecked casts, and foreign calls;
  one logical Kotlin ABI type cannot have a flow-dependent physical contract.
- **Treat a static `C<T>` receiver type as exact physical provenance.**
  Rejected. Unchecked casts deliberately allow a mismatched construction to
  retain the declaration-erased Kotlin view until a typed member use.
- **Optimize a failed typed-capability probe.** Rejected. The miss must execute
  canonical semantics and normally reaches an exceptional typed-use barrier;
  making that already exceptional path faster does not justify more state or
  control-flow complexity.

## On hold

The bounded dispatch optimization does not select any of these larger
programmes:

- global SSA/CFG provenance or interprocedural exactness analysis;
- cached probes, explicit loop versioning, or compiler-managed guard hoisting
  beyond ordinary CLR JIT/AOT optimization;
- a distinct fully erased physical representation for private or local generic
  classes based on visibility; or
- profile-specific specialization policy for ReadyToRun or NativeAOT.

They require actual generated-code measurements which remain material after
the direct/guarded typed path. None may change canonical storage, runtime
classification, casts, identity, or the published ABI.

## Completion gate

The first complete implementation must cover final, open, abstract, and
sealed generic classes already admitted by the target; reference, value,
nullable-value, and multiple type arguments; generic inheritance and a
non-generic derived class; fields, properties, ordinary/generic methods,
virtual overrides, constructor delegation, and projected typed use.

Executable tests must prove star tests/smart casts, checked and safe casts,
argument-erased unchecked casts whose failure occurs inside the later member
barrier, nullable behavior, single evaluation, unchanged identity and mutable
state, nested and inner open-TypeDef identity, default-argument dispatch,
parameter overloads which differ only in erased class arguments, and rejection
of unrelated canonical-interface implementors. Separate
netstandard2.0 producers must execute from Kotlin consumers on Framework CLR
and CoreCLR. Roslyn must construct and subclass the typed class, observe
canonical Kotlin function signatures, and preserve virtual dispatch through
the canonical bridge. Metadata tests must pin both TypeDefs, invariant typed
generic parameters, InterfaceImpl/MethodImpl rows, and the versioned physical
bindings. Compiling an identical producer twice must produce identical CIL,
including private/local canonical slot names.

Existing generic classes, generic inheritance, nullable/scalar members,
initialization, friends/compiler ABI, and split generic-interface suites must
remain green. Unsupported open-nullable constructions and parked language
families must continue to fail explicitly rather than losing declarations.
