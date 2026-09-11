# Incoming CLR references to Kotlin-owned interfaces

This diagnostic investigation starts from `9beb1c9217`. It changes no
compiler implementation or representation. It isolates the incoming gap
left by the [foreign input proof](generic-owner-foreign-input-contracts-2026-09-11.md).
Current integration and verification remain in [`../../STATUS.md`](../../STATUS.md).

## Reproducer and result

A Kotlin library declares `Source<out T>.value(): T`. A separate C# library
declares three independent factory interfaces whose `read` methods return:

- a primitive `int`;
- a C#-owned, non-generic `NativeSource` interface; or
- the Kotlin-produced `Source<int>` in the rehearsal, or physical erased
  `Source` in production.

A separately compiled Kotlin consumer calls all three and includes a direct
KLIB `Source<Int>` control. Both the LightTree/.NET 10 candidate and the
production-erased run stop in FIR at the third factory's `read` call with
`UNRESOLVED_REFERENCE`. The controls produce no FIR diagnostics, but no
consumer execution is claimed: the failing module never reaches emission.

The same fixture first checks the physical MethodDefs with the existing
selected-assembly binder and signature resolver:

1. Primitive and native-interface results resolve with only the C# assembly.
2. The Kotlin-interface result then fails with `UNBOUND_ASSEMBLY_REFERENCE`.
3. Selecting the Kotlin assembly too resolves the exact referenced TypeDef.
   The candidate retains its Int32 generic argument; production retains the
   arity-zero named type, without inventing a logical Int argument.

All resolver assertions pass before the recorded FIR error in both epochs.
This is a mixed declaration-graph limitation, not evidence of faulty
split-nullable emission, input conversion, or a generic-owner regression.

## Source explanation and remaining work

The CLI classifies self-describing Kotlin DLLs as KLIB-backed dependencies.
Only `WithoutCarrier` assemblies enter `DotNetClrFirSymbolProvider`. Its
resolver and complete-contract candidate fixpoint operate on that foreign
graph. `DotNetClrImportedDeclarationGraph` likewise retains only those
assemblies and selected native hierarchies. A reference to a Kotlin-produced
type therefore has no complete path through this importer.

Adding the Kotlin DLL to a resolver alone is insufficient. The candidate
fixpoint, FIR type projection, retained reference graph, and backend binding
also need the connection. Nor may the Kotlin TypeDef be imported as a second
foreign classifier: KLIB is still logical authority and its producer ABI
records still select the physical Kotlin owner. The referencing C# MethodDef
must independently retain its original signature.

A complete implementation must distinguish physical reference dependencies
from declarations eligible for foreign symbol import. It must authenticate
the referenced TypeDef against the selected Kotlin library's logical
classifier and physical binding, reuse that classifier, and retain the actual
foreign signature through FIR2IR and emission. Erased generic references must
not acquire invented arguments. Candidate nested constructions must not gain
Kotlin variance conversions merely because a logical type can be spelled.

That cross-layer linkage is not implemented here. In particular this failure
does not authorize general `object -> !K` casts in foreign override dispatch.
Input forwarding with identical already-selected physical carriers can be
investigated independently: it needs no such narrowing. Different-carrier
inputs and a claim of complete incoming C#/Kotlin interop remain open.

## Preservation and verification scope

The complete test-only probe is retained in owned stash
`bb293b6fd9e2385beb78a25c80c53eece3906305`, based on `9beb1c9217`.
It contains `foreignKotlinInterfaceResult.kt` and its fixture-local harness
hook. Do not blindly reapply the harness patch over later changes.

Evidence under `D:\CodexTemp\foreign-kotlin-result-20260911`:

- `candidate-owned-result-unadmitted.xml`: corrected initial FIR reproducer;
- `candidate-resolver-proof.xml`: candidate with resolver assertions;
- `production-resolver-proof.xml`: production with the same assertions;
- `initial-probe.xml`: an earlier C# fixture-setup error, not importer evidence.

The scoped generator registered all four runners. Only the LightTree modern
runner was executed for this diagnostic investigation, using
`--tests '*FirLightTreeDotNetBoxTestGenerated*testForeignKotlinInterfaceResult'`.
The final two runs each contain one failed test and the expected FIR error.
They are red reproductions, not a feature gate, parser/runtime matrix, or new
target-wide checkpoint. The probe is removed from the active test corpus;
the green checkpoint and its recorded verification remain unchanged.
