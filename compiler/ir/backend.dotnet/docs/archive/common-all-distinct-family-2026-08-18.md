# Common allDistinct aggregate family — 2026-08-18

This immutable checkpoint records the implementation and verification evidence
for the complete generated distinct aggregate family over every currently
supported classifier. Current rules remain in `AGENTS.md`, `STATUS.md`, and the
Common collections programme.

## Exact source closure

The owning stdlib generator now selects exactly 20 additional Common
declarations:

- `allDistinct` and `allDistinctBy` over Iterable;
- both functions over generic object arrays; and
- both functions over each of ByteArray, ShortArray, IntArray, LongArray,
  FloatArray, DoubleArray, CharArray, and BooleanArray.

The same Sequence pair was already published by the complete Sequence
foundation. Unsigned array receivers remain outside the .NET supported-
classifier set. No target collection algorithm, Runtime surface, or bridge
protocol was added.

The generated .NET collections output was byte-stable across the final owning-
generator rerun:

```text
056A97F7983843CD1C330A1A5DCF0E602612D3349010C6A04AFFE2893A995517 Collections
```

## Carrier-neutral byte-domain helper

The first exact compile confirmed that upstream's signed ByteArray template
had an accidental source dependency on public unsigned scalars: the internal
`UByteValueSet` accepted `UByte`, and the caller converted through
`Byte.toUByte()`. Neither declaration existed in the .NET stdlib.

The shared Common helper was therefore made carrier-neutral rather than copied
or replaced for .NET. `ByteDomainValueSet` accepts an already normalized Int
index in `0..255`. The signed template passes `element.toInt() and 0xFF`; the
existing upstream UByte template passes `element.toInt()`. Both retain the
same four-Long, 256-bit allocation-free algorithm. The ordinary generated
signed and unsigned array products were regenerated from that one template.
Their final hashes were:

```text
E92E6A58F0C433FBBA40C9828E69336EED59BC1F27C78752541C37F4F61D8BC0 Arrays
1B6FBC81719659A816B6916CA4AABC36CF5E6D13068D0982EE3A69B8CB3A128F UArrays
```

The .NET source inventory compiles that exact Common helper. Raw product
metadata and IL prove one non-public `kotlin.collections.ByteDomainValueSet`
TypeDef and no `Kotlin.UByte` TypeDef. This internal dependency does not select
public unsigned scalars, arrays, or ranges.

## Semantic and product evidence

The hostile box test proves empty/singleton behavior, zero singleton selector
calls, first-duplicate short circuit, callback exception identity and stopping,
nullable selector keys, every one of the 256 signed Byte bit patterns, the
257-element pigeonhole boundary, and every signed primitive classifier.
Primitive and boxed Float/Double cases pin Kotlin equality: repeated NaN is a
duplicate, while negative and positive zero are distinct.

Raw stdlib metadata contains exactly ten `allDistinct` and ten
`allDistinctBy` MethodDefs on `Kotlin.Collections.CollectionsKt`. Installed
Kotlin code calls all ten ordinary fallbacks and inlines all ten selector
bodies. Roslyn directly calls the IntArray ordinary overload with distinct and
duplicate data. The portable netstandard product executes on Framework CLR
4.8 and .NET 10.

Focused evidence passed before the aggregate:

```text
:kotlin-stdlib:jvmTest
  test.collections.ByteDomainValueSetTest
  test.generated.alldistinct.AllDistinctByteArrayTest
  test.generated.alldistinct.AllDistinctUByteArrayTest

:compiler:fir:fir2ir:dotNetTest
  FirPsiDotNetBoxTestGenerated.Box.testAllDistinctAggregates
  FirPsiDotNetFrameworkBoxTestGenerated.Box.testAllDistinctAggregates

:compiler:tests-integration:test
  DotNetLibraryIntegrationTest.testPortableStdlibDllExecutesOnBothRuntimeProfiles
```

The final full target aggregate completed with exit code 0:

```text
./gradlew :compiler:backend.dotnet:dotNetTest -q

dotnet/dotnet.ir                         1 XML       6 tests
compiler/fir/fir2ir dotNetTest        187 XML   2,123 tests
compiler/tests-integration dn           2 XML     125 tests
total                                  190 XML   2,254 tests
failures=0 errors=0 skipped=0
```

All three result roots were freshly written by the final candidate. The four-
test increase is exactly the new box under PSI and LightTree on Framework CLR
and CoreCLR.
