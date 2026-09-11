# Exact native-array constructor and state authority

This bounded rehearsal starts from `cafb56a4e8`. Current integration belongs in
[`../../STATUS.md`](../../STATUS.md); the durable rules belong in the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md).

## Reproduced cause

The fresh source-built candidate census after the array-factory correction
contains 228 error lines, six fewer than its predecessor and no new lines.
One remaining independent failure passes a proven `!T[]` field/getter result
to an iterator constructor whose owner was rewritten to use `object`.

The separate `lib`/`main` fixture `genericOwnerArrayConstructor.kt` reproduces
that failure without Runtime, collection, or declaration-name recognition:

```kotlin
interface Read<out T> { fun read(): T }
interface Build<out T> { fun make(): Read<T> }
class Cursor<T>(private val values: Array<T>) : Read<T> {
    override fun read(): T = values[0]
}
class Source<T>(private val values: Array<T>) : Build<T> {
    override fun make(): Read<T> = Cursor(values)
}
```

`Source.make`'s semantic body incorrectly requested `Cursor<object>` while its
argument remained `!T[]`. A native vector has an element carrier; it does not
need a Kotlin `Array` TypeDef. The early exact-input proof and recursive BOUND
carrier binder both lacked that representation form.

The strengthened validator then exposed two additional consequences of the
same omission. The BOUND state selector excluded symbolic SZ arrays, despite
the existing final-state model supporting them. Also, the route analyzer
marked exact array parameters as capabilities because it looked for a nominal
generic owner. Actual field/constructor metadata was already typed, but no
complete BOUND-to-final state seal could be issued.

## Correction and limits

- Rehearsal exact-vector boundaries retain their existing carrier under exact
  type equality. This neither introduces array covariance nor exactifies a
  projected source value.
- Early constructor proof recursively checks the admitted invariant element
  grammar. The shared BOUND binder independently constructs a symbolic SZ
  array from its authoritative element; it creates no nominal array view.
- The state selector accepts that symbolic carrier only with the unchanged
  private-field, initializer, complete live-writer, and plain-memory checks.
  Final emission must match both the FieldDef and every selected writer's
  actual MethodDef parameter.
- Constructor allocation still requires a selected owner/constructor contract
  and independently compatible determining inputs. A desired result type is
  never sufficient authority.

Open-nullable owner elements, stars/projections, foreign/unbound element
constructions, and independently unproven value-class representations remain
unavailable to the exact binder. This differs from substituting an already
selected owner parameter with a concrete nullable value or value-class wrapper:
the latter preserves the original physical generic argument and `!T[]` field.

There is one original array and one authoritative field per owner. The test
does allocate the source-requested `Cursor`; no representation-repair wrapper,
proxy, duplicate array, or shadow field is introduced. The natural `make`
entry still forwards through its semantic body, so this is not a claim that
every natural method body is route-free or a measured performance improvement.

Common's invariant `Array<T>` is the logical contract. JVM's array iterator
also stores the supplied vector and accesses its elements, and JS preserves
the same array contract. Native's generic-call return erasure illustrates why
callee physical authority, not later logical substitution, controls a call.
No Common, Runtime, Stdlib, production mapper, or serialized schema changes.

## Verification

The final candidate/backend and production-inverse gates passed on 2026-09-11.
Direct XML audit found zero failures, errors, or skips:

| Lane | Suites | Tests |
| --- | ---: | ---: |
| Candidate, PSI/LightTree and Framework 4.8/.NET 10 | 4 | 32 |
| Backend model | 22 | 404 |
| Same focused production-erased inverse | 4 | 32 |

The selected eight fixtures cover the new array case, closed constructor
inputs, canonical reference state, separately compiled state authority,
hostile typed/semantic state, exact current-receiver capture, exact result
chains, and inline widened temporaries. Both parser families and Framework 4.8
and .NET 10 are required. The new fixture verifies actual PE field/constructor
signatures and state seals, then compiles and executes an ordinary C# consumer.

Kotlin and C# checks include value/reference/nullable/value-class substitutions,
nested vectors, projected array elements, an unrelated broad input, immutable
aliases, mutable source values and joins with different constructions, stars,
legal widening, receiver/element/vector identity, and mutation visibility.
Two backend tests cover recursive symbolic binding and hostile exclusions.
The inverse must contain no rehearsal epoch records, state seals, or generic
fixture TypeDefs. The inherited full production checkpoint is `cafb56a4e8`
(2,887 tests); this delta uses the rehearsal-physical lane, not a new full gate.

Both new early recognitions are explicitly rehearsal-gated. BOUND publication
and its selected physical consumers are rehearsal-only; their state is empty
in production. The harness addition is fixture-local. Production-erased
selection and trace classification are unchanged.

Evidence directory: `D:\CodexTemp\array-constructor-carrier-20260911`.
`baseline.xml` records the original incompatible constructor operand;
`prototype-state-candidate.xml` and `parameter-marker-candidate.xml` isolate
the missing seal and incorrect capability marker. The temporary diagnostic
was removed. `bound-state-candidate-green.xml` records the first complete
PE/state/C# execution; `export/lib.il` and `export/lib.dll` retain that producer.
The census is separately preserved in
`D:\CodexTemp\array-iterator-object-20260911\post-cafb56a4e8-census.xml`.

Final candidate XML is preserved in `candidate-32-backend-404.zip`; the inverse
is preserved in `inverse-32.zip`. The gate
uses `--max-workers=1 --no-configuration-cache -q`, the quoted Gradle property
`-Pkotlin.dotnet.genericOwnerRehearsal=true`, the actual FIR2IR Test task with
`--rerun`, and these filters (single-quoted in PowerShell):

```text
*DotNet*BoxTestGenerated$Box.testGenericOwnerArrayConstructor
*DotNet*BoxTestGenerated$Box.testGenericOwnerClosedConstructorInput
*DotNet*BoxTestGenerated$Box.testGenericOwnerCanonicalStateReference
*DotNet*BoxTestGenerated$Box.testGenericOwnerStateAuthoritySeparateCompilation
*DotNet*BoxTestGenerated$Box.testGenericOwnerRehearsalStateCarriers
*DotNet*BoxTestGenerated$Box.testGenericOwnerSemanticBodyExactCurrentReceiverCapture
*DotNet*BoxTestGenerated$Box.testGenericOwnerSemanticBodyExactResultChain
*DotNet*BoxTestGenerated$Box.testGenericOwnerInlineWidenedTemporary
```

Each filter is passed separately with `--tests`. The backend task
`:compiler:backend.dotnet:test --rerun` follows in the same serial invocation.
The inverse selects the same methods through their exact four generated class
names, with no rehearsal property. Exact class filters avoid unnecessary broad
Gradle test discovery; the XML must still contain all four eight-test suites.

Semantic SHA-256 hashes were unchanged across the final gates:

| File | SHA-256 |
| --- | --- |
| `DotNetGenericOwnerExactCarrierBinding.kt` | `2BA188C3396541E0B2F1527504A91F4F1DC762522FC8906BBC0C71D86A748B31` |
| `DotNetGenericOwnerArchitecturePlanningLowering.kt` | `AEF77178608BEE5F975B1E4BE37353EC75960614D9E011AF1AA41BEA566502D7` |
| `DotNetLocalGenericOwnerPhysicalAuthorityLowering.kt` | `64CBD1D19023CC8AD8171B9894D4ACE72CFFC2F9086FF21F58419ABF15FF7954` |
| `DotNetGenericOwnerPhysicalValueModelTest.kt` | `102FC09A2045E35AF5D45B3230D126376AFAB2CCADBE915ECE24E453071DD4E8` |
| `AbstractDotNetIlTextTest.kt` | `CF93A768F2AB3F94D0668096A72BAD302F0E8F30EF281771F0CC4E25B14D9974` |
| `genericOwnerArrayConstructor.kt` | `C2078A635D8F490F0AF5073C3CC58B71DC9D5640F471EF36E5C81BB44A7957A3` |

This bounded proof does not close the complete source-built Stdlib family,
general array state, AOT, or production generic-owner migration.
