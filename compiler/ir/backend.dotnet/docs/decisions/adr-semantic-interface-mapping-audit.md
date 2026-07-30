# ADR: Semantic audit of CLR interface mappings

- Status: **Accepted for pre-publication development**
- Date: 2026-07-27
- Scope: cross-profile ABI verification for Kotlin-owned interface implementations in assembled
  `net48`, `netstandard2.0`, and `net10.0` libraries

This is a repository-local decision for the experimental Kotlin/.NET backend. It is not a public
Kotlin commitment or a KEEP.

## 1. Precedent in the other Kotlin targets

The mature Kotlin backends start with Kotlin's declaration and override graph and then bind that
graph to the target dispatch mechanism:

- common KLIB serialization computes public declaration identities with
  `PublicIdSignatureComputer` and a target mangler
  (`compiler/ir/serialization.common/.../DeclarationTable.kt`);
- JVM bridge lowering follows Kotlin override edges and emits a JVM bridge only when the selected
  implementation and an overridden JVM descriptor differ
  (`compiler/ir/backend.jvm/lower/.../BridgeLowering.kt`). Interface lowering separately chooses
  interface bodies or `DefaultImpls` according to the selected JVM-default mode;
- JS bridge construction walks `overriddenSymbols`, groups distinct physical signatures, and
  delegates each required bridge to the one selected implementation
  (`compiler/ir/backend.js/.../BridgesConstruction.kt`);
- Wasm uses the same override-derived bridge construction and places the selected implementation
  in its interface dispatch table by physical Wasm signature
  (`compiler/ir/backend.wasm/.../DeclarationGenerator.kt`); and
- Native derives bridge directions, vtable entries, and interface-table entries from
  `OverriddenFunctionInfo`
  (`kotlin-native/backend.native/.../ClassLayoutBuilder.kt`).

These targets do not compare generated bridge names as declaration identity and do not create a
tooling-only identity namespace for dispatch records. Their common rule is:

> Kotlin chooses the semantic implementation; the backend records enough target ABI information
> to make that choice dispatchable and linkable.

**Classification:** using `PublicIdSignatureComputer(DotNetIrMangler)` for Kotlin declarations and
the existing `DotNetPhysicalDeclaration` records for CLR bindings is **Correct direction**.

## 2. CLR differences which justify a target-specific audit

The CLR has two distinct mechanisms which can satisfy the same interface slot:

- a class MethodDef, selected naturally or through a `MethodImpl` row; and
- on runtimes with default interface methods, a selected interface MethodDef, possibly mapped
  through an interface-owned `MethodImpl`.

Class implementations take precedence over default-interface fallback. Default-interface
selection then uses the runtime's most-specific-interface rules. An interface-owned `MethodImpl`
body has additional CLR legality requirements, including finality. These are CLR metadata and
dispatch rules, not differences in Kotlin compiler architecture.

Consequently, one portable Kotlin obligation can have deliberately different physical
implementations:

- `net48` and `netstandard2.0`: abstract interface slot plus helper-backed class forwarder;
- `net10.0`: selected DIM, with a class bridge only when a representation adapter or explicit
  Kotlin override decision requires one.

Raw MethodImpl-row equality would incorrectly reject this accepted profile transition. Generated
target-method names would also freeze an implementation detail which Kotlin callers cannot
observe.

**Classification:** auditing effective CLR interface maps instead of raw MethodImpl-row equality
is a **Reasonable platform-specific divergence**.

## 3. Kotlin Common invariant

For every cross-module Kotlin type which is concrete in the portable variant, every inherited
Kotlin interface obligation must still have exactly one effective non-abstract implementation in
the executable-profile variant. Ordinary calls must continue to use virtual dispatch; the audit
must not prefer a helper, bridge, class method, or DIM merely because one representation is easier
to compare.

The identity of the effective target MethodDef is not the invariant. Requiring it would make the
portable helper representation observable and would prevent the accepted `net10.0` DIM
representation. Exact qualified-super selection remains governed by the separate stable helper
binding and interface-default ADR.

The ABI floor covers:

- public and protected CLR-consumable concrete types; and
- assembly-internal concrete types when the producer grants `InternalsVisibleTo`, because their
  implementation obligations are part of the associated friend ABI.

Private implementation types remain outside the cross-profile ABI floor.

## 4. CLR language and metadata rules

The verifier must resolve each assembled type through the CLR loader and `GetInterfaceMap`.
For each constructed interface MethodDef it records whether the effective target is concrete.
The portable semantic key is the implementing CLR type plus the complete constructed interface
method signature. This is a locator for an actual physical CLR slot, not a replacement logical
identity.

The key deliberately excludes the target MethodDef. Therefore all of the following may satisfy
one portable obligation:

- a natural class implementation;
- an explicit class MethodImpl;
- a portable compiler-generated forwarder;
- a selected `net10.0` DIM; or
- a profile-specific representation bridge.

`net10.0` may add profile-owned DIMs and mappings. Upward compatibility requires retaining the
portable floor; it does not require `net48` or `netstandard2.0` to contain those additions.
Malformed or unresolved interface maps fail while the PE is loaded or mapped.

## 5. Selected architecture

The three existing metadata layers keep separate responsibilities:

1. Kotlin KLIB identity and `DotNetPhysicalDeclaration` remain authoritative for Kotlin
   cross-module linking.
2. `Kotlin.CSharpImplementationManifest` remains the supported C# source-authoring contract. It
   is not expanded with declarations which C# authors cannot or should not implement.
3. The assembled-PE compatibility verifier adds a physical slot-satisfaction floor for mappings
   not represented by that authoring manifest.

Manifest-addressable slots continue to use their existing Kotlin logical identity and complete
CLR locator. Non-manifest slots use their already published CLR owner and complete constructed
method signature only for physical compatibility comparison. No `runtime:*`, generator-specific,
or other parallel Kotlin identity is introduced.

This follows the mature backends: Kotlin metadata owns semantics and logical identity, while the
target's real dispatch signature describes the physical obligation.

## 6. Kotlin-aligned target choice

Given the alternatives, the Kotlin-compatible choice is to compare semantic slot satisfaction:

- **Rejected:** compare raw MethodImpl rows. It confuses one CLR encoding with Kotlin semantics
  and rejects the deliberate portable-to-DIM transition.
- **Rejected:** put every compiler mapping in the C# authoring manifest. It turns a supported
  foreign-source projection into a second general compiler ABI authority.
- **Rejected:** derive logical identities from generated CLR method names. It freezes lowering
  details and duplicates `IdSignature`.
- **Selected:** retain Kotlin logical identities in KLIB and the authoring manifest where
  applicable, then audit all remaining cross-module CLR slots by their real physical signature
  and effective runtime mapping.

This decision is **Correct direction**. The reflection-based verifier is a
**Correct temporary implementation, but not a final design**: a future direct PE writer should
share a structured metadata validator with the emitter, while runtime loading remains valuable as
an independent conformance oracle.

## Consequences and tests

The portable-superset test must prove that:

- manifest and non-manifest slot sets are reported independently;
- a metadata-public compiler-ABI generic interface which is intentionally absent from the C#
  authoring manifest still participates in the physical mapping audit;
- ordinary public and authorized friend concrete mappings retain their portable obligations;
- helper-backed portable forwarders and `net10.0` DIM dispatch satisfy the same portable slot;
  and
- a malformed or unresolved mapping is rejected by the loader/verifier rather than accepted from
  matching IL text.

This ADR does not freeze private MethodImpl rows, target method names, metadata tokens, row order,
or the set of profile-owned `net10.0` additions.

## CLR references

- [ECMA-335 runtime augmentations for default interface methods](https://github.com/dotnet/runtime/blob/main/docs/design/specs/Ecma-335-Augments.md)
- [`MetadataBuilder.AddMethodImplementation`](https://learn.microsoft.com/dotnet/api/system.reflection.metadata.ecma335.metadatabuilder.addmethodimplementation?view=net-10.0)
- [C# default-interface-method proposal](https://github.com/dotnet/csharplang/blob/main/proposals/csharp-8.0/default-interface-methods.md)
