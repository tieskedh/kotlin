# Exact and nested foreign input contract proof

This test-only proof starts from `f23e3d20cf`. It changes no compiler
implementation, admission rule, physical mapping, Runtime/Stdlib, or schema.
Current integration belongs in [`../../STATUS.md`](../../STATUS.md); physical
authority and non-derivable foreign routes remain governed by the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md)
and [natural-interface ADR](../decisions/draft-adr-reified-generic-interface-owner.md).

## Executable distinction

The custom producer declares `Producer<out T>` and
`Lookup<K, out V>.find(K): V?`. Its Kotlin operations have different physical
contracts:

| Kotlin operation | Actual CLR receiver construction | Natural input/result |
| --- | --- | --- |
| Exact `Lookup<Int, Int>` input | `Lookup<int,int>` | `int -> int + out bool` |
| Nested `Lookup<Producer<Any?>, Int>` input | `Lookup<object,int>` | `object -> int + out bool` |

The nested operation accepts an identity-preserving Kotlin variance view of a
`Producer<Int>`. Its semantic input carrier does not erase the independent Int
result. Kotlin's own implementation executes the operation, and an ordinary
separately compiled C# implementation of the actual `Lookup<object,int>`
contract receives the same producer object. No hidden compiler ABI is required
from C#.

A separate exact C# implementation uses `Lookup<int,int>` and a natural
`int find(int, out bool)` MethodDef. Kotlin exact calls test both a present
result and a nondefault payload ignored by a true null flag. CLR InterfaceMap
inspection requires the real Int input, Int result, and trailing out Boolean.

The initial hostile C# caller instead supplied
`Lookup<Producer<object>,int>` to the nested Kotlin API. C# rejected that call
with CS1503: it is not the published `Lookup<object,int>` construction. The
final fixture keeps this compiler-negative check and a reflection assertion
on the actual Kotlin parameter. It does not reinterpret either construction
to manufacture assignability. This is evidence about the current outward
contract, not a universal answer for incoming foreign metadata.

## Unclosed incoming direction

Two separate incoming probes compiled a C# DLL against the Kotlin producer:
a static factory, then an ordinary `IFactory` interface, returning
`Lookup<Producer<object>,int>` and `Producer<object>`. The static-factory
attempt failed in FIR on `ForeignOwnerInput`; the interface attempt failed in
FIR on `lookup`. Neither reached the input conversion being investigated.

The complete incoming reference/member graph therefore remains unproved.
These failures do not authorize checked narrowing of every logical `K`, nor
prove that all native nested constructions can be used as the same Kotlin
physical view. Broader foreign input admission must first close that evidence
gap or state its actual unsupported boundary. No input conversion is added by
this proof, and `Map`/generic-class owner admission does not advance.

The full incoming experiment, including its fixture-only environment hook,
is preserved in owned stash `1c47ca6f76025c46d446f84aa6c1d674ccb44113`, based
on `f23e3d20cf`. That red experiment is not part of the promoted test. It must
not be blindly reapplied onto later test-harness changes.

## Verification

Evidence is under `D:\CodexTemp\foreign-owner-input-20260911`:

- `nested-construction-csharp-rejection.xml`: initial wrong-construction call;
- `nested-canonical-input-green.xml`: matching construction and Kotlin box;
- `incoming-static-factory-unadmitted.xml` and
  `incoming-interface-member-unadmitted.xml`: the two earlier importer gates;
- `candidate-12.zip`: final candidate matrix, green at 17:52:59 local time;
- `inverse-12.zip`: same production inverse, green at 17:55:45.

Both matrices contain four suites and 12 tests, with zero failures, errors,
or skips. SHA-256 checks confirmed that both changed test sources were
unchanged across the gates. The focused matrix uses both parsers and executable
profiles with:

```text
:compiler:fir:fir2ir:dotNetTest --rerun
--tests '*testGenericOwnerForeignOwnerInput'
--tests '*testGenericOwnerForeignSplitResult'
--tests '*testGenericOwnerFixedCarrierMultiInput'
```

Commands use `--max-workers=1 --no-configuration-cache -q`; only the candidate
adds `"-Pkotlin.dotnet.genericOwnerRehearsal=true"`. The scoped generator
registered the new fixture in all four runners. The inverse checks absence of
rehearsal epoch records and generic TypeDefs in the new family.

The backend's 402 tests are inherited from `f23e3d20cf`; the unchanged full
production checkpoint is `17676a90e1` (2,863 tests). This fixture-only proof
does not claim a new backend run, full aggregate, or production ABI checkpoint.
