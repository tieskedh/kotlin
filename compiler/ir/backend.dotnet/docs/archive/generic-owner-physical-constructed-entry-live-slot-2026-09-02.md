# Generic-owner constructed-entry live-slot validation — 2026-09-02

This archive records the late-authority repair for a direct constructed-generic
parameter/local entry. It changes neither Kotlin semantics nor the candidate or
production ABI.

## Boundary

Final value flow could already prove that an immutable alias both receives and
stores an exact local construction such as `InlineLookup<!T,!T>`. For every
non-control-flow constructed initializer, however, the late emitter check used
the whole expression's mapped carrier. For a direct `IrGetValue`, that mapping
can be reconstructed from the logical Kotlin type rather than read from the
verifier-visible source slot. A stale early prototype could therefore agree
with its own reconstruction without agreeing with the final `ldarg`/`ldloc`
carrier.

The retained-carrier token now records `DIRECT_STORAGE_READ_CARRIER` when the
constructed initializer is a direct storage read, allowing only the existing
identity-preserving implicit-cast/not-null wrappers. The emitter reconstructs
the expected local TypeDef plus current physical-owner parameters, reads the
actual source slot from the final method context, and requires exact equality.
A logical source type and the older whole-expression mapper cannot repair or
hide a mismatch.

The shared direct-value shape classifier now unwraps any nesting of exactly
those two compiler identity operators. This also aligns the existing bare
owner-parameter entry with its already-recursive live emitter query; every
level still ends in the same required direct storage read and exact slot check.

This is a validation rule, not a source of provenance. The earlier final-value
proof must still independently establish the exact constructed carrier and the
same storage carrier. Stars, projections, mutable flow, broad/object values,
foreign constructions, method binders, representation-changing casts, and
unsupported joins remain unable to mint the token.

## Executable proof

The physical-value model wraps one direct read in both admitted identity
operators, then reverses the two late observations around that constructed
alias:

```text
whole expression = object, live slot = I<!T>  -> retain I<!T>
whole expression = I<!T>, live slot = object  -> fail closed
```

The existing `genericOwnerInlineWidenedTemporary.kt` fixture supplies the real
positive path for both value- and reference-type substitutions. Its placement
comparison requires `PHYSICAL_VALUE_RETAINED_PRODUCER`; emitted IL requires an
`InlineLookup<!0,!0>` parameter and local connected by a direct
`ldarg.1`/`stloc` pair without an intervening adaptation. The exact natural
lookup call remains separate from the function's ordinary logical `T?` return
materialization. The identical fixture remains erased when rehearsal is
disabled.

Verification:

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin :compiler:fir:fir2ir:compileTestKotlin --no-configuration-cache -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest" --no-configuration-cache -q
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
```

The model has 93 green tests. The focused candidate and fresh production-erased
inverse each execute four tests: PSI and LightTree on .NET 10 and Framework 4.8.
All have zero failures, errors, and skips.

## Remaining boundary

This repair deliberately does not claim that every whole-expression carrier is
an independent final-emission observation. Direct constructed call results,
constructor allocations, and transparent block/composite containers require
their own enumerated live validation before their current whole-expression
fallback can be retired. The next constructed-entry slice starts with the
actual MethodDef result of a direct call; caller-MethodDef `!!R`, captures,
properties, state, and new Runtime/Stdlib declarations remain separate later
proofs.
