# ADR: Companion static placement and initialization

- Status: **Accepted**
- Date: 2026-07-21
- Scope: Kotlin companion blocks and companion extensions on `net48`, `netstandard2.0`, and
  `net10.0`

This is a repository-local pre-ABI decision for the experimental .NET backend. No Kotlin/.NET
binary has shipped, so the implementation must replace conflicting prototype shapes instead of
preserving them with compatibility aliases.

## Context

The upstream frontend represents a companion-block callable as a member of its semantic class
with no dispatch receiver. A companion extension remains a top-level declaration, records its
target class in metadata, and has no physical receiver in IR. KLIBs additionally record whether
the new companion initialization semantics apply.

These are Kotlin semantic distinctions, not suggestions for CLR placement. In particular:

- companion-block state belongs to the classifier's Kotlin initialization graph;
- a companion extension does not initialize the classifier it extends;
- a generic CLR type has separate static state for every closed construction;
- a CLR interface is not a portable static-storage owner across the required profiles; and
- the CLR does not initialize base classes or selected superinterfaces merely because a derived
  static member is called.

Putting every declaration directly on its IR parent would therefore duplicate generic state and
would delegate Kotlin initialization order to CLR behavior which does not provide it.

## Decision

### 1. Preserve upstream logical metadata

The .NET producer uses the shared KLIB header and manifest helpers. It records metadata flags,
customized language features, and `new_companion_initialization` exactly like other KLIB targets.
The physical declaration index supplements this logical metadata; it does not invent a second
receiver convention or companion identity.

Stale prototype metadata is rejected when the physical holder or initialization records are
introduced. There is no dual-read or old-receiver compatibility path.

### 2. Companion extensions use a file/module static owner

A companion extension function or accessor is emitted as a receiver-free static method on its
normal file facade. Its target classifier is retained only in Kotlin metadata and logical
identity. Calling it must not trigger initialization of that classifier.

A companion-extension property exposes its accessor methods to CLR metadata but does not emit a
CLR `.property` row. Without a receiver parameter such a row would falsely present unrelated
file-facade state as an ordinary property of the facade. A deliberate C# export may provide a
separate idiomatic facade.

### 3. Non-generic class companion blocks use real class statics

Receiver-free companion-block functions and accessors on a non-generic class are CLR static
methods on that class. Backing fields are static and their initializers are moved into a real
`.cctor`. Static accessors bind a static CLR property row where the property shape is otherwise
representable.

This direct placement is valid only when the class is the final physical initialization owner.
It is not generalized to generic classes or interfaces.

### 4. Generic classes and interfaces use non-generic holders

Companion-block declarations whose semantic owner is a generic class or any interface are moved
by target lowering to a deterministic non-generic static holder. The holder is compiler-owned
physical ABI and is not a second Kotlin declaration. Calls, callable references, default
dispatchers, fields, and property accessors all bind to that one physical owner.

Method type parameters remain method parameters. Type parameters of a generic semantic owner are
not available to companion-block declarations under Kotlin rules and must not leak into the
holder ABI.

Private/protected access made illegal by relocation is repaired with synthetic access bridges.
Ordinary `internal` remains CLR assembly-internal; a bridge is public only when cross-assembly
compiler machinery requires it, and is then marked and hidden as compiler ABI.

### 5. One initialization entry point represents the Kotlin graph

Each classifier participating in companion initialization has one stable compiler-owned
`EnsureCompanionInitialized` entry point associated with its physical owner. Invoking the entry
point triggers that owner's `.cctor` exactly once under CLR type-initialization synchronization.
The `.cctor` explicitly ensures Kotlin-required parents and selected superinterfaces before
executing the classifier's own companion initializers in source order.

The entry point is assembly-internal for wholly local graphs. When a separately compiled child
must invoke it, the producer records a metadata-public, marked compiler-ABI entry point in the
physical declaration index. Consumers never derive its owner or name.

Construction, companion-block calls, companion-block property access, and companion-object
access trigger the same semantic owner. Companion-extension calls do not.

CLR `TypeInitializationException` wrapping and failure caching are accepted physical runtime
consequences, but the backend must test and document their interaction with Kotlin exception
classification before the ABI freezes.

### 6. Initialization order is explicit

The lowering computes the Kotlin initialization graph from logical inheritance and override
selection. It must not rely on CLR base-type initialization or DIM ambiguity behavior. A valid
Kotlin hierarchy observes:

1. required superclass companion initialization;
2. selected superinterface initialization in Kotlin-defined order; and
3. the classifier's own companion-block and companion-object initializers in program order.

Cross-module graph edges are resolved exclusively through producer metadata. Missing or
incompatible initialization records are link errors, not fallback name guesses.

### 7. Unsupported shapes fail before emission

Until holder and graph lowering cover a shape, generic owners, interface owners, inherited
initialization obligations, and mixed companion-block/companion-object initialization are
rejected. They must not be emitted with per-construction state, skipped silently, or accepted
because one runtime test happens to pass.

## Consequences

Kotlin semantics and KLIB metadata remain uniform across targets, while the CLR receives natural
static methods and fields where its model is sound. C# sees strongly typed class statics for
ordinary non-generic companion blocks, but companion extensions and compiler holders remain
separate from deliberate export design.

The costs are a compiler-owned holder/init ABI, physical-name records in KLIB, explicit access
bridges, and additional `.cctor` calls. These costs are preferable to generic static duplication
or an initialization ABI that cannot be repaired after publication.

## Required evidence

- PSI and LightTree semantic boxes on `net10.0`;
- IL goldens for static fields, accessors, `.cctor`, holders, and absence of extension property
  rows;
- `net48` and `netstandard2.0` assembly tests for every portable shape;
- cross-module companion-extension and initialization-graph execution;
- generic owner and interface holder tests;
- source-order, inheritance, selected-interface, failure, and re-entrancy tests; and
- C# compilation/reflection tests which distinguish ordinary static surface, compiler ABI, and
  deliberate exports.
