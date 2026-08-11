# ADR: Common delegated-property semantics over ordinary CLR state

- Status: **Accepted pre-ABI for member, local, and top-level properties**
- Scope: Kotlin-owned delegated `val`/`var` declarations, `provideDelegate`,
  generated accessor calls, initialization order, property-reference tokens,
  file-facade storage, and separate Kotlin libraries
- Does not enable: `KProperty.getDelegate`, delegated declarations that the
  Kotlin frontend rejects, a CLR annotation-based delegate protocol, or the
  Common property-reference cache optimization

## Decision

Kotlin/.NET keeps the repository's frontend and Common IR as the semantic
owner of delegated properties. Operator resolution and expansion produce the
authoritative delegate initializer, optional `provideDelegate` call, and
`getValue`/`setValue` accessor bodies. The .NET backend emits that IR through
the ordinary field, property-accessor, callable-reference, and initialization
pipelines; it does not recognize or reconstruct a second CLR delegation
protocol.

Each declaration has one authoritative delegate carrier:

- a member property uses one private instance field;
- a top-level property uses one private static field on its file facade; and
- a local property uses one local or, after closure conversion, the ordinary
  shared-variable carrier.

For top-level properties, the existing static-initializer lowering moves the
delegate field initializer into the facade `.cctor` in source declaration
order. `provideDelegate`, when selected by the frontend, therefore executes
exactly once as part of declaration initialization. Reads and writes call the
generated accessors and never bypass the delegate through a second value
field.

## Cross-target architecture

All mature targets preserve the frontend-selected operator calls and logical
property declaration. Their physical field and property-reference caching
choices differ:

- JVM combines its property-reference/delegation lowerings with class or file
  static initialization and target-specific singleton/constant optimizations;
- JS runs the shared local-delegated-property lowering after callable/property
  reference lowering;
- Wasm runs Common `DelegatedPropertyOptimizationLowering`, property-reference
  lowering, and `LocalDelegatedPropertiesLowering`; and
- Native likewise uses the Common property-reference optimization before its
  local-declaration pipeline.

.NET follows the shared semantic boundary and its existing emitter
architecture. File facades are physical products created at emission time,
not `IrClass` declarations created before lowering as on JVM. Consequently
top-level delegate state remains property-owned IR until the established
`.cctor` lowering assigns it to the facade. This is a physical-owner
difference, not a semantic deviation.

## Property-reference identity

The exact `KProperty` token passed to `provideDelegate`, `getValue`, and
`setValue` is the one produced by the existing callable/property-reference
pipeline. Its name, declaration flags, annotations, receiver shape, equality,
and separate-library identity remain KLIB/IR-authoritative. The delegate may
retain or compare that object; the emitter must not substitute a CLR
`PropertyInfo`, field token, string-only descriptor, or newly inferred
metadata object.

The current implementation may construct an unbound property-reference object
on more than one access. Common's optional optimization can cache such a token
in a synthetic static field, but observable equality and declaration identity
must already be correct without it. Object-reference sameness of repeated
operator arguments is not made a Kotlin contract by enabling delegation.

## Static initialization and failure

A top-level delegate expression and `provideDelegate` execute at their source
declaration position relative to surrounding top-level property initializers.
An accessor invoked during a later initializer observes the already assigned
delegate. An accessor invoked recursively before assignment follows the same
partially initialized state as the other targets; the backend may not reorder
the delegate merely to avoid that state.

The facade's established static-initialization failure mechanism owns thrown
initializer and `provideDelegate` exceptions. On the first active use an
original Kotlin `Error` is rethrown by identity, while a non-`Error` is the
exact cause of Kotlin `ExceptionInInitializerError`; later uses throw
`NoClassDefFoundError` for the logical file. These are the accepted JVM-shaped
static-initialization rules, not delegate-specific translation. By contrast,
exceptions thrown by `getValue` or `setValue` after successful initialization
propagate by identity. Delegated properties do not get a private failure cache
or wrapper.

## CLR and C# view

The delegate field is compiler-owned private state. A non-extension top-level
or member property exposes the same ordinary CLR property surface as a
non-delegated Kotlin property, and C# calls its accessors. An extension
property remains static accessor methods because CLR properties have no
extension-receiver slot. The delegate protocol itself is not projected as a
C# attribute, event, dynamic binder, or public field.

Standard CLR metadata may describe exact nullability or other foreign-language
facts on the public accessor surface. It cannot replace the KLIB property
identity, the selected Kotlin operator overloads, or initialization semantics.

## Separate compilation

The producer KLIB retains the delegated declaration and logical signatures.
The producer DLL owns the private delegate field, accessor bodies, property
metadata, and static initialization. A Kotlin or C# consumer binds only to the
ordinary producer accessors; it never reconstructs `provideDelegate`, guesses
the delegate-field spelling, or reruns initialization.

Inline accessors or inline delegate expressions continue through the selected
shared inline/KLIB pipeline. This feature adds no delegated-property manifest
codec and no cross-assembly private-field contract.

## Deliberately parked work

### Common property-reference cache optimization

`DelegatedPropertyOptimizationLowering` creates private static raw `IrField`
declarations at file level for lazily required unbound property-reference
objects. The current .NET facade pipeline owns top-level state through
`IrProperty.backingField`; blindly enabling the Common pass would create state
without a truthful facade field/initializer owner.

The optimization stays off until raw compiler-generated file fields are
supported as a general facade capability, including deterministic ownership,
declaration-order initialization, failure behavior, separate compilation, and
IL coverage. Enabling or disabling that optimization must not change public
DLL signatures, property semantics, runtime casts, reflection results, or
cross-module behavior.

### `KProperty.getDelegate`

Reflective delegate discovery remains a separate semantic closure. Publishing
`null`, rediscovering a private CLR field, or exposing the implementation
carrier would be false. It requires JVM comparison, accessibility behavior,
bound-receiver handling, producer-owned executable access, and exact exception
semantics before the existing reflection interface can be widened.

### Interface-owned state

An interface cannot own an instance delegate field. Ordinary implementations
may implement an interface property with their own delegated member, which is
already covered. Any future fieldless delegated interface shape must first be
shown in authoritative post-frontend IR and must compose with the accepted
profile-aware interface-default ABI; this tranche does not invent state on an
interface.

## Alternatives rejected

### A CLR-specific delegation attribute

Rejected. An attribute cannot execute the selected Kotlin operator overloads,
retain evaluation order, or carry the exact property-reference object. It
would also make foreign metadata a competing authority for Kotlin semantics.

### Store the computed property value beside the delegate

Rejected. The delegate owns value semantics. A second field would break
custom getters/setters, mutation, exception timing, and arbitrary delegate
state.

### Re-run `provideDelegate` from each accessor

Rejected. `provideDelegate` is declaration initialization, not access. Repeated
execution changes side effects, returned delegate identity, and failure
timing.

### Enable the Common cache pass before facade raw fields exist

Rejected. A performance lowering may not create ownerless static state or a
second initialization path. Correct uncached semantics are the prerequisite.

## Completion evidence

The selected gate covers both FIR parsers and both runtime profiles, including:

- top-level delegated `val` and `var` reads/writes;
- exact property name and receiver passed to the operators;
- one-time `provideDelegate` and source-order initialization;
- access from another top-level initializer;
- member-extension and local delegates through their already established
  paths;
- private static facade delegate storage plus ordinary public accessors;
- separate producer/consumer binding without private-field knowledge; and
- initializer/operator failure distinctions and mutation without a
  delegate-specific wrapper or exception translation.
