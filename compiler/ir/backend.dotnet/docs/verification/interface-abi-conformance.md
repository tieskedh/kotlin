# Interface ABI conformance verification

- Status: **Required pre-publication verification contract**
- Scope: cross-profile semantic interface mappings in assembled Kotlin/.NET
  libraries

This document defines how interface ABI is verified. The generic-interface and
profile-aware-default ADRs own the representations being checked.

## Semantic invariant

Mature Kotlin backends start from Kotlin's declaration/override graph and bind
the selected implementation to target dispatch. They do not use generated
bridge names as logical identity.

For every cross-module Kotlin type concrete in the portable variant, every
inherited interface obligation has exactly one effective non-abstract
implementation in each executable-profile variant. Ordinary calls retain
virtual dispatch. The physical target MethodDef is not itself the invariant.

The ABI floor covers:

- public/protected CLR-consumable concrete types; and
- internal concrete types and internal/private-protected members whose
  availability depends on declared `InternalsVisibleTo` friendship.

Private implementation types remain outside the cross-profile floor.

## CLR-specific reason for semantic comparison

CLR can satisfy one interface slot through a natural class method, explicit
class MethodImpl, helper-backed forwarder, selected default-interface method,
or profile-specific representation bridge.

Portable profiles may use abstract slots plus class forwarders while
`net10.0` selects a DIM. Raw MethodImpl-row equality would reject that accepted
transition; target-method names would freeze lowering detail.

Verification therefore compares effective slot satisfaction rather than raw
row identity.

## Authority layers

1. KLIB logical identities and physical declaration records own Kotlin
   cross-module linking.
2. The C# implementation manifest owns only the supported foreign
   source-authoring contract.
3. The assembled-PE conformance verifier covers the remaining physical CLR
   slot floor.

No runtime-, generator-, or verifier-specific parallel Kotlin identity is
introduced.

## Physical semantic key

For each assembled concrete CLR type, resolve its complete constructed
interfaces and effective interface maps. A portable obligation key consists
of:

- the real implementing CLR type; and
- the complete constructed interface method signature, including owner,
  generic arity, result, parameters, constraints, and accessor role.

The effective target method is deliberately excluded. Any concrete legal
implementation mechanism may satisfy the key. Malformed or unresolved maps
fail during PE load/map resolution rather than passing from matching IL text.

Manifest-addressable obligations keep their logical Kotlin identity and CLR
locator. Non-manifest obligations use the already published physical signature
only for compatibility comparison; it does not replace KLIB identity.

## Profile comparison

Each executable-profile platform surface retains every portable obligation.
It may add profile-owned DIMs, mappings, or interfaces. Upward compatibility
does not require portable/Framework variants to contain modern additions.

Compare separately:

- public/protected mappings;
- friend-dependent mappings;
- C# manifest obligations; and
- non-manifest compiler/Kotlin obligations.

This separation prevents the authoring manifest from becoming a second
general compiler ABI index.

## Required adversarial evidence

Conformance tests must demonstrate:

- helper-backed portable forwarders and modern DIMs satisfying one semantic
  obligation;
- natural and explicit class implementations;
- generic constructed interfaces, properties/accessors, and MethodImpl
  adapters;
- metadata-public compiler ABI intentionally absent from the C# authoring
  manifest;
- public and authorized-friend concrete mappings;
- rejected removed, narrowed, abstract, malformed, or unresolved mappings;
- profile additions that do not weaken the portable floor; and
- real CLR interface maps rather than IL substrings or generated-name matches.

Private MethodImpl rows, target method names, metadata tokens, row order, and
profile-owned modern additions are not frozen by this verifier.

## Evolution

The current reflection/loader oracle is a correct independent verification
mechanism. A future structured CIL/direct-PE writer should share an in-process
metadata validator with emission while retaining runtime loading as an
external conformance oracle.

## CLR references

- [ECMA-335 DIM augmentations](https://github.com/dotnet/runtime/blob/main/docs/design/specs/Ecma-335-Augments.md)
- [C# DIMs](https://github.com/dotnet/csharplang/blob/main/proposals/csharp-8.0/default-interface-methods.md)
