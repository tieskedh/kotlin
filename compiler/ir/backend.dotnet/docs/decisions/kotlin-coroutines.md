# Kotlin coroutines use the Common continuation ABI and an explicit CIL state machine

- Status: accepted for implementation; no public ABI freeze is implied
- Scope: Kotlin-owned suspend declarations and calls, continuation passing,
  suspension/resumption, exception delivery, coroutine context and intrinsics,
  suspend lambdas, separate compilation, and their interaction with value classes
- Does not enable: `Task`/`ValueTask` export, `async`/`await` source projection,
  coroutine scheduling, `kotlinx.coroutines`, sequence builders, or debugger metadata

## Common contract

Kotlin suspend functions are continuation-based computations. Their logical
declarations, suspend flag, result type, generic contract, and callable identity
remain in FIR/IR/KLIB. At a suspension boundary the computation either returns
its value immediately or returns the unique `COROUTINE_SUSPENDED` sentinel and
later resumes the supplied `Continuation<T>` with a Kotlin `Result<T>`.

`Continuation`, `CoroutineContext`, the coroutine intrinsics, `Result`, and the
Common inline helpers are authoritative Kotlin declarations. The target may
supply only their `actual` runtime mechanism and physical code-generation ABI.
An ordinary CLR async return type cannot replace this contract.

## Mature-target direction

All mature targets retain the logical continuation/sentinel protocol:

- JVM physically appends a continuation parameter, widens the immediate return
  to `Object`, and builds a bytecode-oriented state machine.
- JS constructs an explicit ordinary-IR state machine and then applies the
  Common continuation declaration/call transformations.
- Wasm reuses that explicit state-machine algorithm when stack switching is
  disabled; its optional stack-switching mode is a Wasm capability, not a new
  Kotlin coroutine meaning.
- Native uses the same continuation ABI but represents suspension points in
  Native-specific IR for later LLVM-oriented liveness and spilling.

The reusable architecture is the Common continuation transformation plus one
target-owned machine representation. JVM bytecode frames, Wasm stack switching,
Native suspendable pseudo-IR, and JS generators are host mechanisms rather than
cross-target contracts.

## CLR constraints and selected representation

ECMA-335 has ordinary objects, virtual methods, exception regions, and branches,
but no Kotlin suspension primitive. The .NET backend therefore lowers a
suspending body to an explicit reference-class state machine before CIL
emission. The CIL emitter continues to consume target-ready ordinary IR; it does
not gain hidden suspend-function semantics or coroutine-only evaluation-stack
state.

Every lowered Kotlin suspend function has one physical Kotlin ABI entry point:

1. its ordinary parameters retain their established Kotlin/.NET mapping;
2. a final `Continuation<R>` parameter is appended by the Common lowering;
3. the immediate return is erased to `Any`/`Any?`, so it can carry either the
   result or `COROUTINE_SUSPENDED`;
4. a non-tail body that can suspend allocates a generated state-machine class;
5. values live across suspension are fields of that object, and state dispatch
   occurs through ordinary branches, loops, and exception regions.

Library ABI version 27 owns that continuation-shaped physical MethodDef contract.
That coroutine tranche left runtime surface level 26 unchanged because it did
not alter `Kotlin.Runtime.dll`; the later fixed-callable closure advances the
current runtime surface to 27 for `Function4` through `Function22`.

`Continuation<R>` is a Kotlin-owned generic interface. Its canonical erased
interface remains the authoritative Kotlin dispatch identity under the accepted
split-interface decision. A typed CLR interface capability may be emitted by
that existing model, but it does not change the suspend entry-point ABI or make
the continuation result a CLR-generic runtime identity.

`Result<T>` uses the accepted value-class representation: exact Kotlin
calculation may use its `Any?` carrier, while nominal/erased/interface boundaries
use its one box owner. The continuation protocol observes the authoritative
Common `Result.Failure` representation and preserves the original throwable
object. It does not translate a failure to `AggregateException`, clone an
exception, or replace the target's classified-carrier exception model.

## Lowering ownership and order

The .NET pipeline reuses the Common suspend-kind analysis, generated coroutine
class construction, continuation parameter transformation, call rewriting,
tail-call classification, finally normalization, returnable-block conversion,
and liveness concepts where their contracts apply.

The explicit state-machine algorithm follows the proven JS/Wasm shape, but its
.NET integration lives in `backend.dotnet`. `backend.dotnet` must not depend on
`backend.js`, implement `JsCommonBackendContext`, or import Web origins merely
to reuse code. JS and Wasm share a Web compiler layer; the CLR is not part of
that platform family. A later repository-wide extraction to `backend.common`
is permitted only if it removes target assumptions for the existing consumers
as well, rather than moving JS vocabulary into a nominally Common package.

Coroutine construction must occur while suspend lambdas and captured locals
still have their semantic structure. Continuation declaration and call
rewriting follows state-machine construction, matching JS/Wasm. Local closure
conversion, default-argument materialization, initializer merging, value-class
declaration/usage lowering, loop lowering, and final autoboxing then process the
generated ordinary IR in their dependency-correct order. No coroutine pass may
run after the final representation-transition sweep if it can synthesize a new
value-class consumer.

The initial executable closure must include real suspension and resumption;
supporting only non-suspending or tail-delegating `suspend` declarations is an
intermediate implementation state, not feature completion.

Coroutine phases retain that fixed pipeline position but defer resolving their
stdlib/runtime symbols until a suspend body or continuation-shaped call is
actually present. An ordinary `-no-stdlib` compilation used solely for foreign
CLR metadata must not start requiring `Continuation` because the backend gained
coroutine support.

## Runtime and stdlib ownership

Stdlib selects the authoritative Common coroutine/context/`Result` sources.
Target-specific Kotlin sources own only:

- `SafeContinuation` synchronization/state transitions;
- the state-machine continuation base and interception cache;
- the intrinsic declarations consumed by compiler-generated IR; and
- the small unintercepted create/start adapters required by Common APIs.

Runtime remains independent of Stdlib unless a physical type is required in a
Runtime-owned signature. The coroutine implementation belongs to Stdlib because
it uses Common `Continuation`, `CoroutineContext`, and `Result` behavior. A
compiler intrinsic may have a throwing source body for resolution, but every
reachable occurrence must be removed by lowering before emission.

## Concurrency

Kotlin's low-level continuation API permits resumption from another thread.
`SafeContinuation` must therefore perform its `UNDECIDED`, suspended, resumed,
and completed transitions atomically on every supported CLR profile. A plain
unsynchronized field implementation copied from single-threaded JS is not an
acceptable .NET actual. The portable implementation uses CLR primitives
available to `netstandard2.0`; profile-specific optimization must preserve the
same state machine and failure behavior.

The generated coroutine object's own execution is not made generally
thread-safe. As on other targets, the protocol prevents duplicate completion;
arbitrary concurrent mutation inside user coroutine code is outside this ABI.

## .NET interoperability

Kotlin suspend entry points are compiler ABI, not idiomatic C# async APIs. A
future explicit export product may project a supported suspend declaration to
`Task<T>` or `ValueTask<T>`, with CLR nullability and cancellation metadata where
truthful. Such an adapter terminates or resumes the Kotlin continuation protocol
at the boundary. Disabling it must not change Kotlin DLL signatures, state
machines, object identity, exception identity, reflection, or separate-module
behavior.

Imported CLR `Task<T>` remains an ordinary foreign CLR generic class. Calling
or awaiting it from Kotlin needs separate library/compiler APIs and does not
turn it into the internal representation of every Kotlin suspend function.

## Implementation state

The initial executable foundation is implemented. Stdlib compiles the
authoritative Common `Result`, continuation/context, and coroutine-intrinsic
sources; the target supplies only its state-machine continuation,
`SafeContinuation`, and irreducible compiler/runtime adapters. The lowering
produces ordinary IR before CIL emission and supports real suspension and
resumption rather than only immediate or tail paths.

Current unchanged shared coverage executes immediate/suspended completion,
tail delegation, exception delivery, `try`/`catch`/`finally`, Unit coercion,
non-empty evaluation-stack control flow, direct and local suspend callable
references, continuation interception/release, value-class result carriers,
repeated suspension in `while`, `do while`, and `for` state machines, nullable
reference and mutable-reference spills, null operands across two suspensions,
array-element spills, and `Boolean`, `Char`, `Byte`, `Short`, `Int`, `Long`, and
`Double` locals/results across merges, calls, safe-call receivers, construction,
direct resume, delayed resume, and exceptional resume. `Int`- and `Long`-backed
value-class results and a nullable-`Int` value class through a generic suspend
override cover representation transitions. A target-owned CLR test adds the
otherwise absent `Float` edge, retaining a `float32` field across suspension and
restoring a boxed `Continuation<Float>` result without widening it to
`float64`. Member/extension coverage executes a
local suspend extension, inline and non-inline extensions with a second dispatch
receiver, repeated suspension in an ordinary member, virtual override and
`super` dispatch, a suspend operator, private top-level/member state machines,
and receiver-start dispatch. Context coverage executes the Common
`plus`/replacement/`minusKey`/`fold` behavior, pins the interceptor as the last
element, reads `coroutineContext` through top-level, receiver, and inline forms,
and preserves the exact completion context before and after real suspension.
It also proves that both arities of the `createCoroutineUnintercepted` wrapper
remain in the completion chain and release their cached interceptor instead of
being stranded as launch-only objects. The same matrix covers a suspend-inline
producer/consumer boundary across both FIR parsers and both CLR profiles. A
target-owned integration lane additionally executes
suspend-inline code and races two CLR threads against one `SafeContinuation`,
proving one completion and one classified duplicate-resume failure. That lane
also pins external-inline access to a producer companion without allowing a
same-logical-key local singleton to bind the dependency. The Common helper
closure proves a named singleton inheriting its coroutine base; the shared
generic-object bridge test separately pins the general erased generic
superclass case.

The Common fixed-callable closure is now complete through physical `Function22`.
That admits logical suspend arity 21 after the appended continuation and is
verified by unchanged Common physical-`Function5` and `Function7` probes plus a
target-owned suspend/resume `Function22` edge. Arity 23 and above remains the
separate Common/JVM-style vararg big-arity `FunctionN` feature; fixed CLR
interfaces must not be extended beyond the Common boundary.

This is an implemented foundation, not a claim that the complete coroutine
programme or every evidence lane below is closed. In particular, big-arity
callables, default/interface-bridge and reflective suspend-member shapes,
stale-ABI behavior, and
exhaustive residual-IR assertions still require explicit evidence before this
ADR's full scope is called complete.

The Common state-machine builder copies source visibility to its generated
constructor. That is harmless for JS/Wasm, but a CLR file facade cannot call a
private constructor declared on a separate generated TypeDef. The .NET
lowering therefore retains a private state-machine type while giving its
constructor the established public-in-private-type compiler-local shape. The
constructor is not serialized as Kotlin ABI and cannot become a C# source
surface while its declaring type remains inaccessible.

The shared root `controlFlow_while1` and `controlFlow_while2` assertions also
exercise repeated suspension, but depend on Common `String.trimIndent`. Keep
them unselected until the authoritative Strings/Indent dependency closure is
admitted; do not copy or simplify those tests for this target.

## Required adversarial evidence

Feature completion requires shared upstream coroutine tests where applicable
and .NET-specific physical assertions for at least:

- immediate return, one suspension, repeated suspension, and tail delegation;
- synchronous and delayed resumption, duplicate-resume rejection, and
  resumption from another CLR thread;
- locals/parameters across suspension, loops, branches, nullable values, and
  nested `try`/`catch`/`finally` including throws during resumption;
- generic, primitive, nullable, reference, and value-class results and spills;
- member, local, extension, suspend-lambda, and callable-reference shapes;
- continuation interception and context composition;
- separate producer/consumer DLLs, stale ABI rejection, and all target profiles;
- original throwable identity and classified Kotlin catch relationships; and
- absence of residual suspend IR or compiler-only intrinsic calls at emission.

## Rejected alternatives

### Use `Task<T>` or `ValueTask<T>` as the Kotlin suspend ABI

Rejected. They encode a host async product, not the complete Kotlin continuation
protocol, and would make C# ergonomics dictate Kotlin runtime representation.

### Teach the CIL emitter to compile residual suspend functions directly

Rejected. It hides semantic lowering in instruction emission, couples liveness
and exception transformation to evaluation-stack bookkeeping, and diverges
from the target-ready IR architecture used by JS/Wasm and Native.

### Depend on `backend.js` and reuse its lowering unchanged

Rejected. The implementation requires Web context, symbols, origins, and
runtime declarations. A module dependency would reverse platform ownership and
make unrelated JS evolution part of the .NET backend ABI.

### Implement only the immediate and tail paths

Rejected as completion. Those paths are useful bootstrap tests but do not prove
suspension, spilling, resumption, or exception-state correctness.

### Make the JS `SafeContinuation` field transitions the .NET implementation

Rejected. JS execution assumptions do not satisfy the Common cross-thread
resume contract on the CLR.

## Invariants

1. KLIB owns the logical suspend declaration; the continuation/sentinel protocol
   owns Kotlin execution; CLR async types do not.
2. No suspend function or coroutine-only pseudo-expression reaches CIL emission.
3. `COROUTINE_SUSPENDED` is one identity-stable object per Stdlib product.
4. Every value live across suspension is stored explicitly and restored with
   the same Kotlin type/representation semantics.
5. Success and failure resume exactly once; failure preserves throwable identity.
6. Separate compilation observes the same appended-continuation ABI as a
   same-module call.
7. A C# async export is an optional adapter and cannot redefine internal Kotlin
   signatures or object identity.
