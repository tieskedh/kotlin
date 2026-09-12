# Fixed interface results through local calls

Base: `eadcd2d41bb6ba71bcf44d8f5147fb07158c646f`. Its preceding fresh-result
proof inherited the full production checkpoint `3724aeab9c` (2,932 tests).

## Reproduced boundary

A non-generic Kotlin implementation of a foreign interface initialized a
private `Source<Int>` field with an `IntSource`. The candidate already proved
both the field and its private getter exact. Its public `read()` nevertheless
became `object`: the call-result query recognized external natural MethodDefs
but ignored the local getter's already-proven result. The retained foreign
`Source<int>` slot correctly rejected that mismatch.

The initial red execution and temporary diagnostic are retained separately.
The diagnostic established the field/getter facts before the fix; its
declaration-name filter was removed, not turned into an admission rule.

## Transfer and limits

An ordinary call can now transfer its selected local declaration's already-
proven closed invariant interface result. The recorded result must agree with
both the declaration and call, and must actually provide the expected physical
view. The existing closed-type predicate is reused. Open owner/MethodDef
substitutions require independent binder-aware authority. Final semantic-route
revocation remains stronger than a provisional exact fact.

This adds neither a getter recognizer nor a field-state rule. The regression
also forwards through an effectful helper with an unrelated broad input and
checks ordinary C# overrides whose result has no Kotlin capability. Kotlin
bottom views and successive mutable Int/String constructions remain broad;
their source return types cannot manufacture a physical construction.

Reflection checks the private field carriers, declared public result surfaces,
and unchanged foreign InterfaceMap signatures. Same-object checks reject
wrappers or copied state. Ordinary private backing fields still omit CLR
`initonly`, as the existing emitter and authority ADR specify; an initial test
assertion incorrectly expected that flag and was corrected without changing
compiler field emission.

The mature-target precedent is declaration-owned calls: JVM callables use
mapped callee signatures, Native calls use `LlvmCallable`, and Wasm compares
the caller and selected callee result layouts. JS has no CLR-style constructed
interface verifier constraint. No shared compiler or Common source is changed.

## Verification

The candidate and production-erased inverse each passed four suites / 32
tests across PSI and LightTree on Framework 4.8 and .NET 10. All 24 backend
model suites / 414 tests also passed. Direct XML audit found no failures,
errors or skips, and the three frozen semantic-file hashes match both lanes.
The existing mixed-reference validator additionally rejects rehearsal records
in the production-erased artifact.

Evidence is under `D:\CodexTemp\foreign-kotlin-state-result-20260912`, including
the baseline and diagnostic XML, initial fixed run, expanded-test assertion
failure, frozen patch and source hashes, `candidate.zip`, `model.zip`,
`inverse.zip`, and the gate audit script. The focused matrix includes inline
widening, private semantic results, closed semantic inputs, exact receiver
helpers/result chains/captures, and separate foreign overrides.

All changed lowering code is dominated by the rehearsal flag's early return.
The validator is fixture-local. No production-selected mapping, Runtime,
Stdlib, importer, serialized ABI or shared test infrastructure is changed.
The full production checkpoint remains inherited, not rerun. The feature does
not claim arbitrary broad state, open result substitution, complete source-built
stdlib closure, or a production generic-owner cutover.
