# Common CharSequence min/max aggregate family — 2026-08-18

This immutable checkpoint records the implementation and verification evidence
for the complete generated CharSequence min/max aggregate family. Current
rules remain in `AGENTS.md`, `STATUS.md`, and the Common collections programme.

## Exact source closure

The owning stdlib generator now projects exactly 28 Common declarations into
the source-aligned `_DotNetBootstrapStrings.kt`/`Kotlin.Text.StringsKt`
product:

- natural `min`, `max`, `minOrNull`, and `maxOrNull`;
- `minBy`, `maxBy`, and their nullable forms;
- generic Comparable, Float, and Double `minOf`/`maxOf` throwing and nullable
  forms;
- `minWith`, `maxWith`, and their nullable forms; and
- comparator-result `minOfWith`/`maxOfWith` throwing and nullable forms.

The first compile found one exact missing Common dependency:
`CharSequence.lastIndex`. That upstream declaration is now projected into the
same StringsKt product. Its extension receiver is physically one static getter
MethodDef; logical property identity remains in KLIB, so no CLR PropertyDef is
invented. Map adapters remain a separate closure because their source façade,
delegating bodies, and fallback visibility differ.

The generated .NET Strings output was byte-stable across the final owning-
generator rerun:

```text
159B686D6BB9654A323C6FC200A6C26AB9A62DB7111B3756E51B1BE36F0706A0 Strings
```

## Classified-carrier semantics

CLR has no common native CharSequence interface implemented by
`System.String`. Kotlin therefore retains its classified carrier: raw strings
remain unchanged references, while Kotlin and explicitly authored foreign
implementations expose the erased `Kotlin.CharSequence` capability. Aggregate
calls operate on both arms without wrappers or copies.

The hostile box test proves natural/selector/comparator results, empty
throwing versus nullable behavior, selector elision for empty/singleton element
selection, one selector call for singleton result selection, first identity
for comparison-equal result objects, nullable comparator results, and exact
comparator/selector failure stopping. Float/Double NaN and signed-zero behavior
and exact indexed dispatch on a custom implementation execute under both
Framework CLR 4 and .NET 10.

## Physical naming and interop

Generic, Float, and Double selector-result overloads differ only by return
type after their Function1 parameter is erased. The bounded stdlib router now
keys each admission by exact physical façade, Kotlin package prefix, and
receiver classifier. It admits CollectionsKt for the existing collection
receivers and StringsKt for CharSequence; unrelated declarations fail closed.
This does not define a public `DotNetName` annotation.

Raw StringsKt metadata contains all 28 aggregate MethodDefs plus the one
`get_lastIndex` prerequisite. Twelve aggregate fallbacks are public and sixteen
`@InlineOnly` methods are assembly-visible. Installed Kotlin calls the eight
ordinary natural/comparator-element fallbacks and inlines the other twenty
bodies. Roslyn directly calls natural, selector, and comparator fallbacks over
both a `System.String` and an explicitly implemented foreign CharSequence. A
negative consumer proves that selector-result inline-only methods remain
inaccessible.

## Final verification

The final full target aggregate and explicit model-suite freshness rerun both
completed with exit code 0:

```text
./gradlew :compiler:backend.dotnet:dotNetTest -q
./gradlew :dotnet:dotnet.ir:test --rerun -q

dotnet/dotnet.ir                         1 XML       6 tests
compiler/fir/fir2ir dotNetTest        187 XML   2,143 tests
compiler/tests-integration dn           2 XML     125 tests
total                                  190 XML   2,274 tests
failures=0 errors=0 skipped=0
```

All three result roots were freshly written by the final candidate. The four-
test increase is exactly the new box under PSI and LightTree on Framework CLR
and CoreCLR.
