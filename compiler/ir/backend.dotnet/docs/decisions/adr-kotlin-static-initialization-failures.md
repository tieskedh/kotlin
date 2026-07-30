# ADR: Kotlin-owned static-initialization failures on CLR

- Status: **Accepted**
- Date: 2026-07-28
- Scope: Kotlin-generated class, companion, object, and file static initializers on `net48`,
  `netstandard2.0`, and `net10.0`

This is a repository-local pre-ABI decision for the experimental .NET backend. No Kotlin/.NET
binary has shipped. The compiler, runtime, physical declaration schema, and generated member names
therefore move together without a compatibility path for older prototype artifacts.

## Context

Kotlin defines observable behavior when initialization fails. That behavior cannot be delegated
unchanged to a CLR type initializer:

- the first failed active use rethrows the original Kotlin `Error` object;
- another first failure throws `ExceptionInInitializerError` with the original failure as cause;
- every later active use throws `NoClassDefFoundError`; and
- the logical state follows Kotlin initialization dependencies, including inherited and
  cross-module edges.

An exception escaping a CLR `.cctor` is instead observed through `TypeInitializationException`,
and the CLR permanently marks the physical type as failed. That loses the required first/later
distinction and replaces the original exception identity. A generic CLR type also has a distinct
type-initialization event per closed construction, while Kotlin companion state is not
per-construction.

## Decision process

### 1. Other Kotlin targets

The JVM obtains the first-failure and later-use distinction from JVM class initialization:
non-`Error` failures become `ExceptionInInitializerError`, an `Error` survives, and later active
use fails with `NoClassDefFoundError`.

JS and Wasm use their static-initializer declaration and usage lowerings together. The declaration
side records a failure through the common `staticInitializationFailure` contract; the usage side
ensures that each active use re-enters the logical state machine. Native likewise owns
target-specific initialization machinery rather than treating a foreign runtime wrapper as the
Kotlin exception model.

The uniform compiler pattern is therefore a logical Kotlin initialization event plus target-owned
declaration and active-use handling. The target runtime mechanism may supply synchronization, but
does not redefine the Kotlin failure contract.

### 2. CLR-specific difference

The CLR already provides correct once-only execution, synchronization, re-entrancy, and publication
for `.cctor`. Replacing it with a process-wide dictionary or a compiler-generated lock would
duplicate difficult runtime behavior and would be less sound.

The CLR's escaping-failure behavior is nevertheless incompatible with Kotlin. The .NET target
therefore catches every Kotlin throwable inside the compiler-generated `.cctor`, stores the
original `System.Exception` in private logical state, and lets the `.cctor` complete normally.
Only the compiler-owned logical barrier classifies and throws the observable Kotlin exception.

Foreign CLR type initializers are not rewritten. Their runtime behavior remains a foreign
exception boundary and the existing exception-classification design preserves the original CLR
exception object.

### 3. Kotlin Common invariant

One physical initialization owner has one failure-state object. The state retains:

- the exact original physical exception object; and
- an atomically updated first-observer flag.

The first observer receives the original object when the Kotlin exception classifier says it is
an `Error`. Otherwise it receives a new exact Kotlin `ExceptionInInitializerError` whose cause is
the original object and whose message follows the Kotlin constructor contract. A later observer
receives a new exact Kotlin `NoClassDefFoundError` with the logical class or file message.

Dependencies call the producer-recorded logical barrier. A failure from a parent is consequently
an `Error` at the dependent boundary and is propagated by identity on that dependent's first
failed use. This matches JVM-style initialization propagation while giving each failed logical
owner its own later-use state.

### 4. .NET profile rules

All three required profiles provide the needed CLR primitives:

- `.cctor` once-only execution and publication;
- `System.Exception` as the Kotlin physical throwable root;
- static fields and exception regions; and
- `System.Threading.Interlocked.Exchange` for first-observer selection.

The semantic implementation is therefore intentionally the same on `net48`, `netstandard2.0`,
and `net10.0`. Profile-specific emission is allowed when CLR capabilities differ, but no such
difference justifies different Kotlin failure identities here. Portable output remains executable
with the matching modern runtime pair.

### 5. Alignment with compiler architecture

The backend separates the work at the same boundaries used by mature targets:

- the static-initialization graph lowering chooses each logical event, its dependencies, and its
  non-generic physical owner;
- the failure lowering instruments compiler-generated class and file initializers and inserts
  active-use barriers;
- the intrinsic registry maps CLR state capture/observation to the runtime service and maps the
  existing Common `kotlin.internal.staticInitializationFailure(reason, className)` contract to
  its target implementation;
- `Kotlin.Runtime` owns the private state implementation and the exact Kotlin error classes;
- the physical declaration index records every public or friend-reachable classifier barrier for
  separate compilation; and
- IL emission only renders the lowered fields, methods, exception regions, and calls.

There is no emitter-only semantic state machine, no global lookup keyed by names, and no
consumer-side reconstruction of a producer's physical holder.

### 6. Kotlin-aligned target choice

The selected design keeps CLR `.cctor` synchronization but prevents a Kotlin-owned exception from
escaping it. A private object-typed state field is stored on the physical owner. The public
compiler/runtime ABI consists of:

- `Kotlin.Runtime.Internal.StaticInitialization.Capture(System.Exception): object`;
- `Kotlin.Runtime.Internal.StaticInitialization.Observe(object): System.Exception?`;
- `Kotlin.Runtime.Internal.StaticInitialization.Throw(System.Exception?, string?): void`, the
  target implementation of Common `staticInitializationFailure`; and
- the producer-recorded `<EnsureInitialized>` method for a logical classifier.

The concrete runtime state type is assembly-internal and may evolve without changing producer
metadata. Compiler-ABI types and methods are public only where cross-assembly calls require it and
are marked with `KotlinCompilerAbiAttribute` plus `EditorBrowsable(Never)`.

This is the direction a Kotlin core review should choose: it preserves the Common contract,
retains CLR runtime strengths, and follows the established declaration/usage-lowering split
without imitating JVM bytecode shapes which the CLR cannot support.

## Active-use contract

The compiler inserts `<EnsureInitialized>` before user code can observe a Kotlin-owned failed
event:

- class construction;
- Kotlin static functions and property accessors;
- top-level functions and property accessors on a file with static initialization;
- direct reads of Kotlin singleton fields;
- compiler-generated static adapters and dispatchers; and
- producer-recorded singleton reads and initialization dependencies across DLL boundaries.

A producer method contains its own prologue, so a consumer does not need a separate file-entry ABI
record. A public, protected, `@PublishedApi`, internal-friend, or otherwise cross-module classifier
event does need its exact physical barrier identity in the DLL's embedded KLIB metadata.

Generic classifiers and interfaces which only need an event use a compiler-owned non-generic
`<StaticInitialization>` holder. Existing companion storage continues to use
`<CompanionStatics>`. This prevents closed CLR generic constructions from acquiring independent
Kotlin failure states and avoids pretending that every initialization event is companion state.

## Interoperability boundary

C# construction and calls to Kotlin-emitted methods execute the generated barrier prologue and
observe Kotlin semantics. A direct C# read of a raw public singleton field can bypass that
prologue after the caught `.cctor` has completed. That raw field is compiler representation, not
the final supported C# surface.

Before ABI stability, deliberate C# export facades and generated C# authoring adapters for
singletons must expose a property or method which calls the barrier before returning the field.
The raw-field bypass is classified as a **Deferred problem that must be recorded before the ABI
becomes stable**. It does not justify leaking `TypeInitializationException` into Kotlin or making
C# conventions define Kotlin initialization.

## Consequences

The implementation preserves original `Error` identity, original non-`Error` cause identity,
first/later failure classes, logical dependency poisoning, one state across generic
constructions, and cross-module behavior on Framework CLR 4 and CoreCLR 10.

The costs are one private nullable object field and one barrier call at each relevant active-use
boundary, plus three public-but-marked runtime methods and exact internal Kotlin error types.
Successful initializers retain only a null state and the barrier's fast null check.

The overall classification is **Correct direction**. Treating raw CLR
`TypeInitializationException` as Kotlin behavior would be **Architecturally wrong and should be
changed**.

## Required evidence

- PSI and LightTree boxes on both runtime profiles;
- original Kotlin `Error` object identity;
- first non-`Error` wrapper type, null message, and original cause identity;
- later `NoClassDefFoundError` and logical owner message;
- companions, ordinary objects, inherited events, generic closed constructions, and top-level
  files;
- one portable producer consumed by separate `net48` and `net10.0` modules;
- IL assertions that the `.cctor` catches and records instead of leaking a failure;
- IL assertions that barriers precede singleton reads and user bodies; and
- a pre-ABI C# export test proving the eventual singleton facade cannot bypass the barrier.
