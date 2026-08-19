# Reified generic-interface classifier input boundary (2026-08-19)

## Question

ABI 39 allowed a classifier-derived `Producer<String>` view to cross a
callable result as CLR `object`. The next exact-looking boundary was an input:

```kotlin
fun same(producer: Producer<String>, expected: Any?): Boolean =
    producer === expected

fun read(producer: Producer<String>): String = producer.produce()
```

Changing these functions' only MethodDefs to accept `object` would make natural
Kotlin and C# APIs pay for an exceptional semantic route. Keeping only
`Producer<string>` would cast a valid classifier-derived view before the Kotlin
body could observe its identity.

## Fail-first evidence

The separate consumer first failed at the call boundary:

```text
argument 1 of 'same' ... is not supported:
'!!' produces object where Producer<string> is expected
```

The producer already emitted the intended alternate MethodDef and its ABI
record. The remaining provenance break was FIR's built-in `CHECK_NOT_NULL`
call. It wrapped the classifier-derived value before an immutable local, and
the generic-owner planner initially treated that compiler call as a new
producer instead of a carrier-neutral null check.

## Closed contract

The natural MethodDef remains authoritative for exact calls:

```text
bool same(Producer<string>, object)
string read(Producer<string>)
```

ABI 40 adds one producer-recorded compiler MethodDef for the classifier route:

```text
bool same__KotlinClassifierInput__<digest>(object, object)
string read__KotlinClassifierInput__<digest>(object)
```

The record binds the logical function, exact CLR owner and method name,
instance/static shape, and selected object parameter indices. A separately
compiled consumer reconstructs that MethodRef and chooses it only when the
argument carries classifier-derived foreign provenance. It never infers the
alternate from an exact-looking logical type.

The alternate owns a compiler IR copy of the source body with only the selected
parameter widened. The original body remains on the natural MethodDef, so
ordinary Kotlin and C# calls do not cross an object wrapper. The current proof
admits only a public final body with no defaults, varargs, callable type
parameters, property role, generic owner, classifier-derived result, or more
than one selected input.

`CHECK_NOT_NULL` now propagates exact/semantic/foreign provenance from its sole
argument. This is representation-neutral: it checks null and cannot change
object identity or a CLR generic construction. The following immutable local
therefore also keeps the producer-recorded object carrier.

## Executable evidence

The strict foreign object implements only natural `Producer<int>`. The oracle
proves:

- reflection still sees the natural `Producer<string>` parameters;
- ordinary C# calls use those natural MethodDefs and return authored values;
- the separate Kotlin consumer passes the foreign object through `as?`, `!!`,
  an immutable local, and the alternate input entry without changing identity;
- `same` succeeds before any constructed-generic cast;
- `read` invokes the foreign producer exactly once and only the later `String`
  use throws `InvalidCastException`; and
- the alternate physical identity round-trips through the library ABI codec.

PSI and LightTree execute the proof on .NET 10 and Framework 4.8: four focused
tests, zero failures, errors, or skips. Runtime surface 38 remains current; the
feature adds compiler ABI only.

The required final target aggregate is green. FIR and integration results were
freshly written on 2026-08-19; the unchanged `dotnet.ir` root retained its
up-to-date checkpoint:

| Root | XML suites | Tests | Failures/errors/skips |
| --- | ---: | ---: | ---: |
| `compiler/fir/fir2ir` | 187 | 2,155 | 0 / 0 / 0 |
| `compiler/tests-integration` | 2 | 126 | 0 / 0 / 0 |
| `dotnet/dotnet.ir` | 1 | 6 | 0 / 0 / 0 |
| **Total** | **190** | **2,287** | **0 / 0 / 0** |

## Boundary

This closes one final, single-input callable shape. It does not prove
open/overridable functions, multiple classifier inputs, mixed control flow,
fields, properties, defaults, method generics, trimming, NativeAOT, or
production cutover. Nested carrier substitution remains the next hard storage
gate; this feature does not authorize global `Box<T>` or `List<T>` erasure.
