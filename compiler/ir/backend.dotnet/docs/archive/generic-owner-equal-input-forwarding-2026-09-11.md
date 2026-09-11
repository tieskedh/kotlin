# Identical input carriers through foreign owner overrides

This investigation starts from `edfa583ba9`. Current integration belongs in
[`../../STATUS.md`](../../STATUS.md); the durable rule belongs in the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md).

## Reproduced cause

The separately compiled `Reader<K, out V>` has an open
`read(K?, Boolean): V`, immutable `anchor: K` and `alternative: V`, and mutable
`value: V` reachable through a widened setter. On the reviewed base its physical
assertion fails: the owner is erased with
`BLOCKED_UNSUPPORTED_FOREIGN_SEMANTIC_OVERRIDE`. The recorded prototypes already
agree on `(object, bool)` for the typed entry, semantic hook, and dispatcher.
The two immutable fields are producer-proven typed; only the mutable field
needs semantic object storage. The failed admission is not a state-proof gap.

The previous fixed-leaf input recognizer excluded a logical owner-dependent
input even when both physical entries already use the same carrier. The new
candidate query consumes the parameter-slot builder used by the existing
member prototypes. It compares the natural and semantic physical vectors,
without creating a second logical-to-physical mapper. Final emitted MethodDef
parameter and generic-constraint equality checks remain authoritative.

## Bounded physical correction

No conversion is added. An already boxed-or-null nullable input passes through
as `object`; a projected array input passes through as `System.Array`. Neither
requires erasing the independent `!V` result or the `!K`/`!V` immutable fields.
The virtual foreign-override probe and result forwarding are unchanged.

The query deliberately cannot bind logical classifier placeholders. Matching
unbound names are not evidence that the selected physical TypeDefs exist.
Primitive-bound shortcuts, different physical carriers, and unclosed broad
candidate policies do not enter this proof. In particular, strict `K` with
natural `!K` and semantic `object` remains excluded; this does not authorize
an `object -> !K` conversion. Existing MethodSpec admission is unchanged.

Input equality is orthogonal to result layout. This slice does not expand the
natural-interface grammar for a nullable owner input combined with a direct
split-nullable result. The custom `NullableLookup<K, out V>` remains an explicit
negative for that separate composition. No collection or member-name rule is
introduced.

New admission is rehearsal-only. Production retains the old fixed-leaf
condition, and extracting the existing parameter-slot construction does not
change its records. Emitter, Common lowerings, Runtime, Stdlib, schemas, and
production physical mapping are unchanged.

## Hostile verification

`genericOwnerForeignNullableInput.kt` contains separate producer, Kotlin
subclass, and consumer modules. Its PE assertions require `Reader` with two
physical generic parameters, public virtual `object, bool -> !V`, and exactly
three fields: `!K`, `!V`, and one authoritative `object` state slot. C# reflection
also verifies the array input is `System.Array` and the closed result is `int`.

Ordinary C# overrides execute through exact, widened, and star Kotlin calls,
including direct, deeper C#, generic Kotlin-middle, and Kotlin-override/C#-leaf
chains. Arguments, nulls, alternate branches, and receiver identity are checked.
A nonvirtual Kotlin `super` call must bypass the C# leaf. Reference, nullable
Int, and nominal value-class results retain their declared carriers and values.
No foreign subclass implements or overrides hidden compiler ABI.

Negative owners cover unequal `!K`/`object` inputs, a broad candidate policy,
and an unbound named input. Their non-generic TypeDefs remain, with no generic
variant admitted. The separate nullable-input/interface-result composition is
also excluded. Kotlin execution retains widened writes and same-object state.
The production inverse checks the absence of rehearsal epoch records and
generic TypeDefs in the fixture namespace.

The final candidate and the same production-erased inverse each passed four
suites and 40 tests; the complete backend passed 22 suites and 402 tests. All
XML audits contain zero failures, errors, or skips. The four compiler/test
source hashes remained identical throughout these final gates.

Evidence is retained in `D:\CodexTemp\foreign-equal-input-20260911`:

- `kotlin-only-baseline.xml` is logical execution, not representation proof;
- `physical-baseline.xml` and `reader-physical-baseline.xml` preserve the red
  physical assertions and the latter's matching prototype vectors;
- `first-candidate-green.xml` and `expanded-csharp-candidate.xml` record the
  initial correction and foreign override coverage;
- `array-and-unbound-candidate.xml` adds the array positive and unbound-name
  negative before the final matrix; and
- `candidate-40.zip`, `backend-402.zip`, and `inverse-40.zip` contain final
  audited XML.

The selected matrix is:

```text
--tests '*testGenericOwnerForeignNullableInput'
--tests '*testGenericOwnerForeignSplitResult'
--tests '*testGenericOwnerForeignOverrideSeparateCompilation'
--tests '*testGenericOwnerSplitNullableResultSeparateCompilation'
--tests '*testGenericOwnerFixedCarrierMultiInput'
--tests '*testGenericOwnerAbstractForeignOutput'
--tests '*testGenericOwnerHardestModelOracle'
--tests '*testGenericOwnerHardestModelOracleSeparateCompilation'
--tests '*testGenericOwnerForeignScalarResult'
--tests '*testGenericOwnerForeignOwnerInput'
```

These use `:compiler:fir:fir2ir:dotNetTest --rerun` with
`--max-workers=1 --no-configuration-cache -q`; only the candidate adds
`"-Pkotlin.dotnet.genericOwnerRehearsal=true"`. The backend command is
`:compiler:backend.dotnet:test --rerun`. The inherited full production
checkpoint is `17676a90e1` (2,863 tests). This bounded delta is not a new full
gate, complete Stdlib closure, incoming foreign/KLIB graph binding, or
production cutover.
