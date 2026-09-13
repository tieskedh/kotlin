# Interface selection contract review

Reviewed base: `f5020d829b215be7476a8e1fd66e07675c15e68a`.
Scope: observable selection semantics before further compiler integration.
The [candidate ADR](../decisions/draft-adr-selected-interface-view-transport.md)
owns the resulting contract matrix and the still-unaccepted interop proposals.
This is not a new storage ABI or a completed generic-owner migration.

## Authoritative findings

### Kotlin consistency is a material distinction

The [type-system specification](https://kotlinlang.org/spec/type-system.html#parameterized-classifier-types)
requires a consistent supertype graph. The current shared
[FIR helper](../../../../fir/checkers/src/org/jetbrains/kotlin/fir/analysis/checkers/FirInconsistentTypeParameterHelpers.kt)
collects substituted arguments for each parameter and reports distinct values.
[FirSupertypesChecker](../../../../fir/checkers/src/org/jetbrains/kotlin/fir/analysis/checkers/declaration/FirSupertypesChecker.kt)
calls it independently of the target backend. The existing
[GenericArgumentConsistency.kt](../../../../testData/diagnostics/tests/GenericArgumentConsistency.kt)
corpus includes covariant, contravariant, invariant and deeper cases.

Consequently, the direct Kotlin equivalent of an object implementing both
`Producer<Int>` and `Producer<Any>` is not a well-formed Kotlin class. A C# object
with those constructions is legal CLR evidence, but cannot on its own establish
what ordinary Kotlin covariance must do with conflicting implementations.
This finding does not authorize excluding every foreign class/library, nor
conflating physical compiler bridges with inconsistent logical supertypes.

### Assignment, casts and identity do not establish historical selection

The [assignment rules](https://kotlinlang.org/spec/statements.html#assignments)
describe storing the right-hand value or invoking the declared setter. The
[cast rules](https://kotlinlang.org/spec/expressions.html#cast-expressions)
describe checking a requested type, including bounded RTTI and unchecked
generic cases. [Reference equality](https://kotlinlang.org/spec/expressions.html#reference-equality-expressions)
checks runtime identity, not which interface implementation a future call uses.
The [RTTI section](https://kotlinlang.org/spec/runtime-type-information.html#runtime-type-information)
leaves platform representation distinctions to the platform; it does not
define a hidden historical-view component for every Kotlin reference.

The inspected online specification identifies itself as `1.9-rfc+0.1`.
These published rules were cross-checked with current repository code and the
executable baseline below, not treated as a specification of the new .NET ABI.
BK-1 remains the repository's explicitly accepted, separately scoped deviation;
it does not resolve dispatch ambiguity among multiple successful candidates.

Mature-target inspection supplies limited supporting precedent:

- [JVM ExpressionCodegen](../../../backend.jvm/codegen/src/org/jetbrains/kotlin/backend/jvm/codegen/ExpressionCodegen.kt)
  treats implicit casts as the argument and lowers explicit checks from their
  target type and reification contract.
- [JS TypeOperatorLowering](../../../backend.js/src/org/jetbrains/kotlin/ir/backend/js/lower/TypeOperatorLowering.kt)
  constructs target-directed object/interface/parameter checks.
- [WasmTypeOperatorLowering](../../../backend.wasm/src/org/jetbrains/kotlin/backend/wasm/lower/WasmTypeOperatorLowering.kt)
  uses target checks, physical narrowing and erased upper-bound evidence.
- [Native TypeOperatorLowering](../../../../../kotlin-native/backend.native/compiler/ir/backend.native/src/org/jetbrains/kotlin/backend/konan/lower/TypeOperatorLowering.kt)
  erases type parameters for the effective check and returns the same checked
  argument on safe-cast success.

None of those inspected paths defines the CLR dual-construction interop case.
Only JVM execution, not execution of the other three targets, was performed.

### Native CLR conversion is not historical dispatch preservation

[C# interface mapping](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/language-specification/interfaces#1965-interface-mapping)
allows distinct explicit implementations; its
[variance rule](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/language-specification/interfaces#19233-variance-conversion)
describes actual reference/identity conversions. The existing CLR fixture was
rerun unchanged on both profiles. Its dual reference receiver invokes the
object implementation after conversion from `ISource<string>` to the actual
target `ISource<object>`. Its raw-object roundtrip preserves the receiver but
cannot recover both distinct source selections. Boxing the pair changes the
object identity instead.

The accepted native-importer contract and Kotlin-owned semantic variance are
separate. The former's rejection of value/open CLR variance is not permission
to reject ordinary Kotlin-owned `Producer<Int> -> Producer<Any>`.

## New executable Common-source baseline

Run [verify-kotlin-selection-contract.ps1](../../tools/verify-kotlin-selection-contract.ps1)
with a new output directory. It uses the existing local Kotlin distribution;
it neither builds a new compiler nor changes target sources. It first compiles
the [library](../../tools/fixtures/selection-contract/Library.kt), then the
separate [consumer](../../tools/fixtures/selection-contract/Consumer.kt).

The positive executable checks the same receiver and implementation through:

- an exact alias and Kotlin value-argument covariance;
- ordinary generic forwarding;
- `Box<T>` and `ObjectBox<T>` with private `Any?` storage and `as T` recovery;
- mutable replacement in both containers, including an Int-to-String producer
  construction change behind the same `Producer<Any>` view;
- generic `T -> Any? -> T` forwarding and observable generic erasure to `Any?`;
- nullable/non-null `Any` paths, star checks, concrete and safe compatible casts;
- failed classifier checks and null receivers; and
- a non-null `Producer<Nothing?>` whose result is null.

The separately compiled
[negative](../../tools/fixtures/selection-contract/Inconsistent.kt) fails with
`INCONSISTENT_TYPE_PARAMETER_VALUES`, including the covariant Int/Any pair;
no rejected jar is emitted. This checks the specific source boundary rather
than treating an arbitrary compile failure as success.

Evidence: `D:\CodexTemp\kotlin-selection-contract-20260913-complete`.
Both positive compilations use warnings as errors. Compiler version is
`2.5.255-SNAPSHOT`, JRE `21.0.9+10-LTS`; `verification.json` records the actual
compiler/stdlib hashes, frozen sources, separate library and consumer hashes,
assertions and exact rejection. The distribution was not rebuilt from HEAD,
so this is recorded-version JVM precedent, not a fresh full target gate.
The runner directly invokes the JVM compiler entry: the batch wrapper's
semicolon-classpath reparsing was unsuitable for this two-jar consumer.

Fresh unchanged CLR evidence:
`D:\CodexTemp\selected-interface-contract-clr-20260913`.
All four existing assertion groups passed against net48 reference assemblies
on installed serviced CLR4 (`4.8.09221`, release `533509`) and against net10
references on runtime `10.0.9`. This is not an original unserviced Framework
4.8 runtime, a Kotlin/.NET selection implementation, or a performance gate.

## Recommendation and exact remaining decision

Do not promote the pair to general compiler storage. Coherent Kotlin values
require preserved logical dispatch, not necessarily a historical runtime
selector; native CLR views already have target-directed physical contracts.
Investigate whether those authorities suffice before extending the value model.

The unresolved case is a foreign implementation of a **Kotlin-owned** family
with conflicting constructions. A target-directed widened view and a
history-preserving widened view can invoke different methods on the same
receiver. Choosing between them requires an explicit interop decision. The
review recommends investigating target-directed behavior first, but neither
changes that behavior nor introduces a new foreign-route restriction.

If historical selection must survive arbitrary generic `T` flows, the ordinary
`ObjectBox<T>` and generic erasure examples show why the scope extends beyond
one pair field. If it must also survive unchanged raw-object APIs, the existing
information-loss counterexample remains decisive. Conversely, removing that
history requirement does not make an `ISource<int>` receiver fit a physical
`ISource<object>` field; nested generic-state composition is still unclosed.

Current compiler admission, Runtime/Stdlib, physical schemas, production-erased
behavior and both parked constructor experiments are unchanged. No census or
full compiler aggregate was rerun; the prior full checkpoint remains inherited.
