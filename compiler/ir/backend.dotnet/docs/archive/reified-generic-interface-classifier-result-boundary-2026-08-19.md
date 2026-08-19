# Reified generic-interface classifier result boundary (2026-08-19)

## Question

The admitted covariant producer permits a parameterized safe cast to retain a
plain foreign `Producer<int>` as the logical Kotlin view
`Producer<String>`. The classifier proof kept that object inside one compiled
function, but did not establish what happens when the view is returned from a
library:

```kotlin
@Suppress("UNCHECKED_CAST")
fun safeView(value: Any?): Producer<String>? =
    value as? Producer<String>
```

Publishing the logical result as CLR `Producer<string>` would make the return
boundary perform a constructed-generic cast which Kotlin's `as?` operation did
not authorize. Publishing every exact-looking `Producer<String>` result as
`object` would instead erase ordinary natural C# APIs without evidence.

## Fail-first evidence

The first separate-compilation probe failed while emitting the producer:

```text
safe generic-owner cast produces object where
RehearsalSeparateProducer<string> is expected
```

After the producer result was made object-shaped, C# reflection saw the right
signature and a direct identity check passed. The separately compiled Kotlin
consumer still failed before invoking `produce()`. Exported `middle.il` showed
the cause:

```text
call object safeView(object)
castclass Producer<string>
stloc tmp0_safe_receiver
...
callvirt Producer<string>::produce()
```

FIR had already desugared the safe call into a synthetic local. The generic-
interface planner classified that local only from its exact-looking logical
type and lost the producer-recorded object provenance. This was also dependent
on visitor order: the outer member call was inspected before its external
receiver call had been visited.

## Closed contract

Logical KLIB types remain authoritative. The producer proves the physical
carrier from the returned value and records that decision; a consumer never
re-derives it from `Producer<String>` alone.

Library ABI 39 generalizes the existing generic-owner function-carrier record.
Every selected return or parameter slot now identifies one of two physical
kinds:

```text
SEMANTIC_CAPABILITY
OBJECT
```

The producer currently selects `OBJECT` for a function with one authoritative
classifier-derived safe-cast return. Mixed or otherwise unproven return control
flow remains fail-closed. An ordinary sibling
`exactView(Producer<String>): Producer<String>` receives no carrier record and
retains natural CLR `Producer<string>` input and result signatures. Runtime
surface 38 remains current because this feature adds no runtime entry point.

The consumer reads the producer record before physical signature mapping and
propagates foreign-object provenance through returned calls, aliases, and the
synthetic safe-call local. Semantic member routing therefore receives the
original object, takes the capability fast path when available, or invokes the
unique natural foreign construction. The final concrete result use performs
the `String` check. No constructed-generic cast, wrapper, proxy, copied state,
or third interface identity is introduced.

## Executable evidence

The strict precompiled C# producer implements only natural
`Producer<int>`. The closed oracle proves:

- `safeView` is physically `object safeView(object)`;
- `exactView` has natural `Producer<string>` parameter and result types;
- both functions return the original object;
- a separately compiled Kotlin safe-call consumer invokes `produce()` exactly
  once before the incompatible `String` result fails; and
- the matching `Producer<string>` path still returns its authored value.

The producer-owned carrier codec round-trips a semantic result together with
semantic and object parameter kinds. PSI and LightTree execute the separate
Kotlin/C# proof on .NET 10 and Framework 4.8: four focused tests, zero
failures, errors, or skips.

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

This closes one classifier-derived result boundary for the admitted no-input
covariant producer. It does not yet prove mixed return control flow, stored
classifier-derived fields, object-carrier parameters, broad input mutation,
other interface families, trimming, NativeAOT, or production cutover. Those
remain complete-family gates; production generic interfaces remain on the
accepted erased ABI.
