# Generic-owner sealed construction closure — 2026-08-17

## Scope

This checkpoint closes the CLR construction rule for a Kotlin sealed generic
owner before the complete record-driven OctoTree candidate is built. It is an
architecture and executable-product proof only. Production generic owners,
emission, DLL/KLIB metadata, Runtime, Common behavior, and the public C# surface
remain on the accepted erased representation.

## Decision

Kotlin `sealed` is a closed logical subclass policy, not the CLI `sealed`
TypeDef flag. A sealed base with known subclasses cannot itself be CLI-sealed.
The selected CLR representation is therefore:

- an abstract, non-CLI-sealed base TypeDef;
- constructor visibility `FamilyAndAssembly` (C# `private protected`); and
- ordinary final/sealed TypeDefs for final known children.

`FamilyAndAssembly` is deliberately stricter than `Assembly`. It permits a
derived TypeDef in the producer assembly to invoke the base constructor, but
permits neither an unrelated producer type nor a derived type in a consumer
assembly. That is the closest CLI visibility to Kotlin's protected sealed-base
construction rule. Kotlin's additional same-package rule remains compiler/KLIB
authority; the CLR has no package visibility.

Schema 12 makes this fail closed. Every physical family recorded with Kotlin
`SEALED` dispatch must contain constructors, and every constructor must use
`FAMILY_AND_ASSEMBLY`. A protected (`Family`) constructor is rejected because
it would allow an external C# subclass; `FamilyOrAssembly` is rejected for the
same reason.

## Record-driven executable proof

The unchanged separate OctoTree producer supplies the four-owner schema-12
artifact. The test physicalizer canonical-encodes and decodes that artifact,
then consumes only its selected physical identities, signatures, visibility,
base edge, state carrier, and positional initializer to produce a bounded C#
product:

- `OctoTreeNode<T>` is abstract and has one `private protected` constructor;
- `OctoTreeLeaf<T>` is sealed and derives from the recorded `Node<T>`;
- `Leaf.value` is a private true CLR generic-parameter field, not `object`;
- `Leaf(T value)` initializes that exact field from the recorded parameter; and
- the known Leaf can invoke the Node constructor in the producer assembly.

A separately compiled positive C# executable constructs `Leaf<int>(42)`. It
uses reflection to prove that the open field type is the owner GenericParam,
the closed field type is `int`, its value is 42, the Node TypeDef is abstract
and not CLI-sealed, and its only constructor is `FamilyAndAssembly`.

A separate negative C# library attempts to derive `IllegalOctoTreeNode<T>` and
invoke the same base constructor. Roslyn must reject it with an access/no-
accessible-constructor diagnostic. The positive reflection proof prevents a
missing constructor or malformed producer from satisfying that negative test.

Both producer and consumers compile against the real .NET Framework 4.8
reference assemblies and independently against the .NET 10 reference pack.
The positive products execute on Framework CLR 4 and CoreCLR respectively.

## Evidence

- backend and FIR test-fixture Kotlin compilation: green;
- focused hostile plus OctoTree PSI/LightTree × Framework 4.8/.NET 10 ×
  same/separate-compilation matrix: four XML suites, 16 tests, zero failures,
  errors, or skips;
- whitespace/error audit: green;
- strict aggregate: green in 1,890.8 seconds;
- direct aggregate audit: 190 XML suites, 2,238 tests, zero failures, errors,
  or skips.

## Remaining boundary

This proves the most restrictive inheritance/construction edge and one real
typed child state path. It is not yet the complete OctoTree candidate product.
The next gate must physicalize the remaining Tree/Branch owners, member-role
families, private semantic root paths, `Node<T>[8]` state, actual call routing,
and a direct C# consumer/subclass surface from the same decoded artifact. Only
the later representative-product and deployment evidence can authorize the
single production owner cutover.
