# Private overload identity across semantic interface carriers

This snapshot records the bounded rehearsal developed from `e3c4e79a88`
and verified after the independent result-provenance repair `2d1c898371`.
Current integration belongs in [`../../STATUS.md`](../../STATUS.md); the
durable declaration-authority rule belongs in the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md).

## Finding and repair

The source-built census exposed a private Common helper overload whose logical
generic-interface parameter becomes `object`. A custom `Renderer<T>` reproduced
the failure without a collection dependency: `render(Source<T>)` and
`render(Any?)` both reserved `render(object)` on its canonical owner.

The accepted erased-interface/class naming rule already requires an
overload-order-independent discriminator derived from the full Kotlin
signature when physical mapping loses logical overload identity. Existing
exception, erased-class, and value-class naming covered other non-injective
boundaries, but not this rehearsal interface-to-object mapping.

The repair selects that discriminator only for private final class methods
with no override, BOUND MethodDef, emission binding, or MethodImpl reservation.
It runs before local identity reservation and never waits for an actual
collision. Existing ABI names remain authoritative. Exact natural parameters,
public/virtual entries, retained foreign declarations, producer endpoints,
constructors, and bound compiler methods are untouched. Logical IR names and
KLIB are not renamed; the registered physical MethodDef supplies call targets.
The change is dominated by the rehearsal property and changes neither
production mapping nor Runtime/Stdlib or artifact schemas.

The mature targets separate logical signatures from their physical symbols:
JS's `NameTables` hashes a logical member signature, Native's
`KonanBinaryInterface` uses the IR mangler for function symbols, and Wasm keeps
`IdSignature`-based function/linker identity independent of display names.
JVM's signature mapper preserves explicit platform names and selected slot
rules. None is a reason to rename an already issued CLR slot; the existing
target naming policy supplies the appropriate local decision here.

## Executable scope

`genericOwnerSemanticOverloads.kt` exercises separate Kotlin producer/consumer
assemblies, open and star interface parameters, `Any?` overloads, default
arguments, and exact reference constructions. Three owners declare the same
logical overload with a different order, an additional overload, or no
competing overload. PE inspection requires the same private physical name in
all three, a distinct star-signature name, unchanged public names, and the
ordinary overloaded name for the exact `Source<string>` parameter.

An independently compiled ordinary C# implementation calls the public Kotlin
methods, exercises those private bodies/defaults, and checks that the stored
source is the same object. It implements no hidden semantic interface.

The production inverse checks absence of both rehearsal epoch records and the
new naming family. It retains the erased interface/owner metadata.

## Explicitly unclosed probes

An attempted `(Source<T>) -> String` bound reference failed because its
generated class lacked the required exact `ExactFunction1` view. A default-
argument `() -> String` reference reached a separate Runtime epoch boundary:
the reused platform returned erased `List` annotations where the candidate
constructor expected `List<Attribute>`. Those probes did not establish complete
candidate callable-reference support and are not counted as green coverage.
The final fixture exercises the same default argument by an ordinary call.
No logical callable name or reference lowering was changed to bypass either
failure.

These diagnostics and the default-reference probe source are retained under
`D:\CodexTemp\semantic-overloads-20260911`. The original overload rejection is
`baseline.xml`; the two reference failures are
`open-interface-reference-gap.xml` and
`default-reference-runtime-epoch-gap.xml`.

Public semantic overloads, nested/capability-carrier collisions, callable-
reference closure, and the remaining inheritance census are not admitted by
this private-method repair. In particular, this is not a claim that the
`SequenceScope.yieldAll` family or the complete source-built Stdlib is closed.

## Verification

The initial expanded matrix exposed the existing semantic owner-parameter
result regression on the base, independently reproduced with the overload
name selection disabled. The overload change was preserved in a named stash
while that correctness repair was committed separately. Its evidence belongs
to the [semantic result archive](generic-owner-semantic-owner-result-2026-09-11.md),
not to this naming decision.

The final candidate is green: four suites, 40 tests, no failures/errors/skips.
The complete backend suite is green: 22 suites, 402 tests, no failures/errors/
skips. The identical production inverse is green: four suites, 40 tests, no
failures/errors/skips. The final compiled source hashes match those recorded
before the candidate and inverse; only documentation changed during the gates.
The inherited full production-erased checkpoint is `3a2384f636` (2,821 tests),
not a new full aggregate. Evidence is archived as `backend-402.zip`,
`candidate-40.zip`, and `inverse-40.zip` under
`D:\CodexTemp\semantic-overloads-20260911`.

The generator was `:compiler:fir:fir2ir:generateTests`; all four parser/profile
runners contain the fixture. The backend command uses
`:compiler:backend.dotnet:test --rerun`. Both matrix commands use
`:compiler:fir:fir2ir:dotNetTest --rerun` with these filters:

```text
--tests '*testGenericOwnerSemanticOverloads'
--tests '*testGenericOwnerSemanticBodyExact*'
--tests '*testGenericOwnerSemanticIteratorResult'
--tests '*testGenericOwnerClosedConstructorInput'
--tests '*testGenericOwnerCompleteNaturalInterfaceSeparateCompilation'
--tests '*testGenericOwnerForeignScalarResult'
--tests '*testGenericOwnerCallableCompositionSeparateCompilation'
--tests '*testValueClassSeparateCompilation'
```

All commands use `--max-workers=1 --no-configuration-cache -q`; only the
candidate supplies `"-Pkotlin.dotnet.genericOwnerRehearsal=true"`. The exact-body
wildcard selects three fixtures, giving ten fixtures per parser/profile.
