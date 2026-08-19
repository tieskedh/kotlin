# Reified generic interface foreign classifier (2026-08-19)

## Result

The test-only generic-owner rehearsal now preserves Kotlin's declaration-
erased runtime classifier semantics for the admitted natural covariant
producer family, including ordinary precompiled CLR implementations which do
not implement the compiler semantic capability.

For `Producer<out T>` the physical test is:

```text
object
  -> implements ProducerSemantic
     or
  -> runtime type implements any Producer<X>
```

It is not `isinst Producer<string>` or `isinst Producer<object>`. Kotlin
`is Producer<*>`, `!is Producer<*>`, nullable classifier tests, and
parameterized `as? Producer<String>` therefore ignore the constructed generic
argument. The runtime type's interface vector is captured once in the existing
weak producer-dispatch state and shared by classifier and member resolution.

A successful safe cast returns the original object reference on the broad
`object` carrier. It does not choose a construction, implement a hidden
interface, or allocate a wrapper. FIR smart-cast receiver emission preserves
that carrier until the already selected capability-or-natural member
dispatcher; a later lowering cannot reconstruct the capability before the
foreign fallback runs.

Classifier admission and member dispatch intentionally differ:

```text
zero Producer<X> constructions
  -> classifier false / safe cast null

one construction
  -> classifier true / safe cast same object
  -> broad member dispatch selects that construction

multiple constructions without a capability
  -> classifier true / safe cast same object
  -> broad member dispatch rejects ambiguity deterministically
```

Consequently an ordinary `Producer<int>` passes
`as? Producer<String>`. Calling `produce()` still selects its unique natural
member and boxes the `int`; consuming that result as `String` throws
`InvalidCastException` at the typed use. This follows Kotlin's `as?` rule
without pretending that the object physically implements `Producer<string>`.

## Evidence

The separate producer/implementation/consumer rehearsal compiles a
non-partial C# DLL without the Kotlin authoring generator. It proves:

- positive, negative, negated, and nullable classifier checks;
- smart-cast `produce()` on an ordinary `Producer<int>`;
- same-object parameterized safe casts;
- successful `String` use from a natural `Producer<string>`;
- delayed `InvalidCastException` from a natural `Producer<int>`;
- classifier admission but member-call rejection for a type implementing both
  `Producer<int>` and `Producer<string>`; and
- unchanged exact, star-field, exception, and C# interop behavior.

The focused matrix executes the same proof through FIR PSI and LightTree on
.NET 10 and Framework 4.8: four tests, zero failures, errors, or skips.

The required final target aggregate covers 190 XML suites and 2,287 tests with
zero failures, errors, or skips. FIR wrote 187 suites/2,155 tests freshly and
integration wrote two suites/126 tests freshly; the independent six-test
`dotnet.ir` root remained up-to-date from its prior green checkpoint.

## Remaining boundary

This proof is limited to local classifier-derived use of the already admitted
single-parameter, no-input covariant producer family. A classifier-derived
exact-looking view returned from, stored through, or passed across a separately
compiled callable boundary may require an `object` physical carrier recorded
in producer ABI; that complete data-flow and publication rule remains open.
Throwing parameterized `as`, foreign input/mixed/member families, properties,
defaults, generic methods, trimming, ReadyToRun, and NativeAOT remain separate
gates. Production generic interfaces remain erased until the atomic owner
rehearsal and inverse rollback close.
