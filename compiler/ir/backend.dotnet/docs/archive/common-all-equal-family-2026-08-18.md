# Common allEqual aggregate family — 2026-08-18

This immutable checkpoint records the implementation and verification evidence
for the complete generated equality aggregate family over every currently
supported classifier. Current rules remain in `AGENTS.md`, `STATUS.md`, and the
Common collections programme.

## Exact source closure

The owning stdlib generator now selects exactly 20 additional Common
declarations:

- `allEqual` and `allEqualBy` over Iterable;
- both functions over generic object arrays; and
- both functions over each of ByteArray, ShortArray, IntArray, LongArray,
  FloatArray, DoubleArray, CharArray, and BooleanArray.

The same Sequence pair was already published by the complete Sequence
foundation. Unsigned array receivers remain outside the supported classifier
set. No target algorithm, collection identity, Runtime surface, or bridge
protocol was added.

The generated collections output was byte-stable across the final owning-
generator rerun:

```text
15833B871F53AA79CEB46EDB17618A4396E47FFF84565181C6AA2E845BB0803D Collections
```

## Semantic boundaries

The dedicated source test proves that the Common implementations call no
selector for empty or singleton inputs, stop at the first mismatch, propagate
the exact callback exception without further traversal, and preserve nullable
selector keys. Iterable, object-array, and every signed primitive-array
classifier execute. Nullable and widened numeric values cross the existing
erased physical boundary without changing logical equality.

Primitive and boxed Float/Double inputs pin Kotlin equality: NaN compares
equal to NaN in this generic equality algorithm, while negative zero remains
different from positive zero. These cases prevent replacing the Common body
with a CLR collection/comparer shortcut.

`allDistinct` is deliberately not part of this closure. Its signed ByteArray
template reaches Common's internal `UByteValueSet`, whose storage uses a
four-word LongArray bit set, and converts each element through `Byte.toUByte()`.
The target does not yet own that exact bounded unsigned-helper dependency.
Dropping only ByteArray would split the classifier family; substituting a
target HashSet loop would fork the algorithm; admitting the internal helper
would not by itself authorize public unsigned arrays or ranges. The whole
`allDistinct` family therefore remains excluded pending a separate dependency
audit.

## Physical and product evidence

Raw stdlib metadata contains exactly ten `allEqual` and ten `allEqualBy`
MethodDefs on `Kotlin.Collections.CollectionsKt`: Iterable, generic object
array, and eight signed primitive wrappers for each logical operation.
Installed Kotlin code calls all ten public ordinary fallbacks. The selector
variants retain their Common inline ABI, so their ten installed uses inline
and create no external `allEqualBy` calls. Roslyn directly calls the IntArray
ordinary overload with both equal and mismatching data.

The hostile box test executes through PSI and LightTree on Framework CLR 4.8
and .NET 10. The portable netstandard product compiles the installed Kotlin and
Roslyn consumers once and executes them on both real runtime profiles.

Focused evidence passed before the aggregate:

```text
:compiler:fir:fir2ir:dotNetTest
  FirPsiDotNetBoxTestGenerated.Box.testAllEqualAggregates
  FirPsiDotNetFrameworkBoxTestGenerated.Box.testAllEqualAggregates

:compiler:tests-integration:test
  DotNetLibraryIntegrationTest.testPortableStdlibDllExecutesOnBothRuntimeProfiles
```

The final full target aggregate completed with exit code 0:

```text
./gradlew :compiler:backend.dotnet:dotNetTest -q

dotnet/dotnet.ir                         1 XML       6 tests
compiler/fir/fir2ir dotNetTest        187 XML   2,119 tests
compiler/tests-integration dn           2 XML     125 tests
total                                  190 XML   2,250 tests
failures=0 errors=0 skipped=0
```

The FIR and integration roots were freshly written by the final candidate; the
unchanged `dotnet.ir` unit root remained up-to-date from its preceding green
checkpoint. The four-test increase is exactly the new box under PSI/LightTree
on Framework CLR and CoreCLR.
