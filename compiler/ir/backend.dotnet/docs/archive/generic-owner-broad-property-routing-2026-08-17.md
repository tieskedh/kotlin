# Generic-owner broad property routing (2026-08-17)

## Decision

Physical-family schema 17 records how every direct CLR property accessor
participates in Kotlin's semantic capability.

A natural C# property may remain typed even when Kotlin permits a wider access
domain. That does not authorize narrowing every capability call to the C# type.
The producer must record and implement a complete route for compatible and
incompatible values, must retain one authoritative state object, and must keep
the paired raw read where a typed accessor can later fail.

This is architecture and separate-compilation evidence. Production
Kotlin-owned generic owners remain erased.

## Hostile source shape

The hostile owner now contains:

```kotlin
open class HostileUnsafeStore<out T>(initial: T) {
    private var stored: T = initial

    open var exposed: @UnsafeVariance T
        get() = stored
        set(value) {
            installUnchecked(value)
        }
}
```

The `@UnsafeVariance` setter is deliberately not a strict `T`-only Kotlin
boundary. This is legal:

```kotlin
val exact = HostileUnsafeStore(11)
val widened: HostileUnsafeStore<Any?> = exact
widened.exposed = "property-widened"
```

The widened read must return the stored string. A subsequent read through the
exact `HostileUnsafeStore<Int>` view fails when that physical use checks or
unboxes the value. Rejecting the string at the setter, storing a typed shadow
copy, or inventing a fallback Int would each change Kotlin behavior.

Both the ordinary single-module oracle and the producer/consumer source oracle
execute this widened-write, raw-read, delayed-typed-failure, and recovery
sequence on the current erased backend.

## Recorded routes

The ordinary PropertyDef still binds only the visible typed getter and setter
MethodDefs. Schema 17 adds explicit routes beside those identities:

```text
getter:
  TYPED_ENTRY
  SEMANTIC_HOOK

setter:
  ABSENT
  TYPED_ENTRY
  COMPATIBLE_TYPED_ELSE_SEMANTIC_HOOK
```

`TYPED_ENTRY` getter routing is valid when the typed accessor is also a
complete source for the capability result. For example, a typed `Node<T>[]`
getter can be widened by its capability dispatcher to `System.Array` without a
second semantic body.

`SEMANTIC_HOOK` getter routing is required for the hostile property because
the `object` field may hold a value which the typed `T` getter cannot return.
The semantic hook returns that same raw state.

`COMPATIBLE_TYPED_ELSE_SEMANTIC_HOOK` setter routing performs the only valid
split for a general broad body:

```text
capability object input
        |
        +-- compatible with closed T --> virtual typed property setter
        |
        `-- incompatible -------------> protected semantic setter hook
```

The compatibility test precedes mutation. The typed route observes ordinary
C# virtual override dispatch. The incompatible route never casts before the
authoritative Kotlin semantic body.

## Fail-closed family joins

A property route is not accepted merely because a similarly named method
exists.

For a semantic getter route, the logical getter family must contain the typed
entry, semantic hook, and capability dispatcher, and its compiler reasons must
contain paired open-output state or inherited semantic override authority.

For a compatible/semantic setter route, the logical setter family must contain
the same three roles and must retain a general widened body or inherited
semantic override authority.

The existing property validation still requires that the PropertyDef getter
and setter are the exact visible typed-entry MethodDefs with one physical type
and nullable transform. The hostile state record selects those property
families as its typed and semantic read/write access paths, so all four paths
join the same private `object` field.

Schema 17 serializes route enum names. Decoding rejects stale field counts and
unknown route names. Construction tests additionally reject:

- typed-only routing for the hostile raw getter;
- unconditional typed routing for the hostile broad setter;
- a semantic route without its complete member roles/reasons;
- a partial or mismatched PropertyDef accessor;
- a state access path bound to another logical family; and
- any second/copy state implied by the product shape.

## Separate C# product

The record-driven producer exposes one ordinary property:

```csharp
public virtual T exposed
{
    get { return (T)stored; }
    set { stored = value; }
}
```

The semantic getter/setter remain protected virtual methods. The non-generic
capability is implemented explicitly, so none of its methods appears as a
duplicate ordinary C# member.

A separately compiled C# subclass overrides the typed property and the
protected semantic setter. The executable proves:

- a compatible capability write invokes the typed property override;
- direct property read invokes the typed getter override;
- semantic read observes the underlying stored value rather than the typed
  getter's transformed result;
- an incompatible write invokes the semantic setter override;
- semantic read returns the exact incompatible stored object;
- typed property read throws `InvalidCastException` at the read boundary;
- a later compatible capability write restores typed state through the same
  virtual property override; and
- the open TypeDef has exactly one private `object` field.

Reflection also pins the property type to the CLR owner GenericParam, the
recorded getter/setter MethodDef names, virtual dispatch, and the five-entry
explicit capability interface map. Producer C# compilation treats warnings as
errors.

The product runs separately on Framework 4.8 and .NET 10. No runtime-specific
boxing or object optimization is relied on for correctness.

## Call-route census

Adding the property sequence changes the closed external hostile census by
exactly four producer-owned calls:

```text
EXACT_TYPED_ENTRY   +1
SEMANTIC_CAPABILITY +3
MISSING_CAPABILITY   0
```

The exact call is the typed failing read. The semantic calls are widened set,
raw read, and recovery set. Their callee keys are the producer property
accessors; no name reconstruction is used.

## Verification

The focused matrix covers:

- the ordinary Kotlin hostile oracle;
- the separate Kotlin producer/consumer and external C# subclass product; and
- the recursive OctoTree property product as a strict-property regression.

FIR PSI and LightTree run each product on .NET 10 and Framework 4.8: 12 tests,
zero failures, errors, or skips.

The final `:compiler:backend.dotnet:dotNetTest` aggregate exited successfully.
Its direct XML audit covers 187 suites and 2,107 tests with zero failures,
errors, or skips.

## Remaining work

This condition closes broad property semantic routing. It does not select the
public generic-owner ABI and does not close:

- overload and generated-name collision allocation;
- nullable transforms on complete base/interface graphs;
- every abstract broad property obligation for foreign subclasses;
- arbitrary multiple public property aliases over one state proof;
- the remaining cast/reflection/dispatch application matrix; or
- the atomic production migration to canonical CLR `C<T>`.

The next change must again close one complete hostile condition. It must not
turn a broad Kotlin property into a strict setter for performance or C#
surface simplicity.
