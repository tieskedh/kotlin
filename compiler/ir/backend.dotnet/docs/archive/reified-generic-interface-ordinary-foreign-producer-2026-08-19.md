# Reified generic interface ordinary foreign producer (2026-08-19)

## Result

The test-only generic-owner rehearsal no longer makes a compiler semantic
capability a base interface of the natural covariant `Producer<T>`. The two
physical interfaces are siblings:

```text
natural CLR contract:        Producer<out T>
optional Kotlin fast path:   ProducerSemantic
```

Kotlin-emitted implementations and generated partial C# implementations name
both siblings on the same object. An ordinary precompiled/non-partial CLR type
may implement only `Producer<T>`. No wrapper, proxy, duplicate state, or third
public canonical identity is introduced.

Production remains on the accepted erased generic-interface ABI.

## Broad carrier and dispatch

A star, projection, open argument, or widened value-type producer has no one
truthful constructed CLR interface. Its public physical carrier is therefore
`object`, including across fields, constructor parameters, methods, and
separate compilation. The logical KLIB type remains authoritative.

Calls use two levels:

1. `isinst ProducerSemantic` and direct virtual capability dispatch for
   Kotlin/generated implementations;
2. for an ordinary foreign object, select the sole closed interface whose
   generic definition is `Producer<>`, then invoke its declared no-input
   member.

Successful resolution is cached per runtime type, open interface, and member
in a weak runtime-type table. Invocation occurs after releasing the cache
lock. A missing construction throws `InvalidCastException`; two distinct
constructions throw `InvalidOperationException`. Interface enumeration order
never selects semantic behavior.

`MethodInfo.Invoke` remains an implementation detail. If the authored member
throws, the runtime helper unwraps `TargetInvocationException` and rethrows the
original exception with `ExceptionDispatchInfo`.

## Fail-first corrections

The first raw C# compilation failed because `Producer<T>` inherited the hidden
semantic interface. Removing that edge exposed three independent composition
bugs which the proof corrected:

- a member-free Kotlin intersection implementation obtained semantic identity
  only accidentally through its natural interface, so implementations now add
  every direct admitted semantic owner explicitly;
- a separately compiled Kotlin caller did not reproduce the producer's broad
  `object` method/constructor ABI, so external call stubs derive the same
  declaration rule; and
- field/property codegen reconstructed the logical capability before the
  two-level dispatcher, so value, field, and accessor provenance now retain
  the actual physical carrier.

These are general owner/carrier composition rules, not declaration-name or
stdlib exceptions.

## Executable boundary

The separate C# DLL is compiled without the Kotlin analyzer or source
generator. Its ordinary non-partial types include:

- one `Producer<int>` returning `83`;
- one `Producer<int>` using an explicit CLR MethodImpl and returning `87`;
- one `Producer<string>` throwing an authored exception; and
- one object explicitly implementing both `Producer<int>` and
  `Producer<string>`.

The C# consumer and Kotlin product prove direct exact calls, broad reader
calls, repeated cache-hit calls, a real Kotlin `Producer<*>` field, `===`/
`ReferenceEquals`, original exception propagation, and deterministic rejection
of the ambiguous object. The same product executes on .NET 10 and Framework
4.8.

The final production-inverse target aggregate covers 190 XML suites and 2,287
tests with zero failures, errors, or skips. FIR wrote 187 suites/2,155 tests
freshly, integration wrote two suites/126 tests freshly, and the independent
six-test `dotnet.ir` root remained up-to-date from its prior green checkpoint.

## Remaining boundary

The foreign fallback is admitted only for the structurally derivable
no-input covariant producer family. Input-bearing, invariant, mixed,
multi-parameter, overloaded, defaulted, property, and generic-method families
remain separate gates. Runtime `is`/`as?` classifier behavior for ordinary
foreign objects and ReadyToRun, trimming, and NativeAOT retention also remain
unproven. Generated semantic siblings remain an optional fast path, and remain
required for families such as the current contravariant consumer whose adapter
has not yet been derived language-neutrally.
