# Generic array-fill specialization — 2026-08-15

This immutable checkpoint records the correctness and performance audit which
replaced the universal erased implementation of Common
`Array<T>.fill`. Current normative rules remain in `AGENTS.md` and the
generic-array-fill ADR.

## Cost found

The admitted Common actual already received an exact CLR vector for ordinary
closed and method-generic arrays, but its intrinsic discarded that evidence:

```text
E[] + E
  -> System.Array + object
  -> Runtime ArrayFill
  -> virtual System.Array.SetValue(object, index) for every element
```

`Array<Int>.fill` therefore boxed the element once per invocation and crossed
reflective array dispatch for every slot. Reference elements avoided boxing
but still paid the virtual operation. This was not required by Kotlin
semantics or by the exact CLR array representation.

An ordinary generic-class owner remains different. Its owner-dependent
`Array<T>` field is canonical `System.Array` state, so a dedicated
`GenericArrayFiller<T>` sentinel still emits the Runtime call. The correction
does not reify that owner, duplicate state, or create a typed cache.

## Selected routes

Every route evaluates receiver, element, `fromIndex`, and `toIndex` once in
source order, then performs Common's out-of-range check before its reversed-
range check. Only the storage operation differs:

- Framework 4.8 and netstandard 2.0 exact vectors use a direct typed loop;
- statically known reference vectors use that loop on every profile;
- .NET 10 value, nullable-value, and open `T` vectors use
  `System.Array.Fill<E>(E[], E, start, count)`; and
- erased `System.Array` capabilities retain Runtime surface 37 and `SetValue`.

Framework IL goldens contain `stelem int32`,
`stelem Nullable<int32>`, and `stelem !!0`, without element boxing, for the
three exact sentinels. The erased-owner method contains the unchanged
`ArrayFill(System.Array, object, ...)` call. CoreCLR execution covers all eight
scalar MemberRef arguments plus open `T = Int`, `String`, and `Int?`.

No public signature, Runtime helper, surface level, KLIB field, metadata
schema, or generic-owner representation changed.

## Paired physical-route measurement

`tools/measure-generic-array-fill.ps1` builds one checksum-identical C#
workload against the installed Framework compiler/runtime and pinned .NET 10
SDK. The erased function duplicates the previous Runtime loop; typed closed/
open loops duplicate portable emitted IL; the CoreCLR-only route calls the
same generic BCL method selected by codegen. It measures a hot length-256 array
for 20,000 fills and reports the median of nine runs. Allocations and startup
are outside the timed region. This is physical-route evidence, not a
representative Kotlin-application benchmark.

Environment:

- .NET Framework release `533509`, CLR `4.0.30319.42000`;
- .NET SDK `10.0.100`, runtime `10.0.9`;
- 10,000,000 stopwatch ticks per second; and
- .NET 10 tiered compilation disabled to measure stable optimized code rather
  than route-order-dependent tier promotion.

Every paired route produced these same checksums:

```text
int       -8808262155695968255
nullable  -4875318336683919405
reference -4601590391375061897
```

Median ticks and speedup over erased:

| Runtime/shape | Erased | Typed closed | Typed open | BCL generic |
| --- | ---: | ---: | ---: | ---: |
| Framework `int` | 823,914 | 20,586 / 40.02× | 24,186 / 34.07× | unavailable |
| Framework `int?` | 1,040,042 | 22,203 / 46.84× | 24,749 / 42.02× | unavailable |
| Framework reference | 663,575 | 91,752 / 7.23× | 92,302 / 7.19× | unavailable |
| .NET 10 `int` | 464,937 | 13,270 / 35.04× | 13,323 / 34.90× | 2,225 / 208.96× |
| .NET 10 `int?` | 1,261,946 | 13,450 / 93.82× | 13,504 / 93.45× | 4,200 / 300.46× |
| .NET 10 reference | 351,917 | 68,589 / 5.13× | 126,108 / 2.79× | 95,543 / 3.68× |

The modern `object`/`SetValue` path is far better than Framework's, but is
still not the best value route. Known references favor the closed typed loop;
the same ordering held at length 8 (4.45× typed versus 2.36× BCL) and length
1,024 (5.17× versus 3.81×). An open generic declaration cannot know whether
`T` is a reference or value; its BCL route beats the open reference loop while
retaining the much larger value gains. Those observations determine the
bounded profile matrix rather than an assumption that one route wins
universally.

## Verification

Focused verification covered:

- all eight closed scalar generic arrays;
- closed/open value, nullable-value, null, and reference fills;
- empty, partial, and full ranges;
- receiver/element/bound evaluation order and both failure categories;
- the retained canonical erased-owner fallback;
- PSI and LightTree on Framework 4.8 and .NET 10;
- deterministic Framework IL; and
- closed and open generic .NET 10 BCL MemberRef assembly/execution.

The strict aggregate command exited with code 0:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

Direct XML audit of its three result roots:

```text
dotnet/dotnet.ir                         1 XML       6 tests
compiler/fir/fir2ir dotNetTest        187 XML   2,085 tests
compiler/tests-integration dn           2 XML     125 tests
total                                  190 XML   2,216 tests
failures=0 errors=0 skipped=0
```
