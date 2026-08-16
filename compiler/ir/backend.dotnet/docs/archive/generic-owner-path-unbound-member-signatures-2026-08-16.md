# Generic-owner path-unbound member signatures — 2026-08-16

## Problem

Generic-owner state already retained constructed Kotlin-owned types by logical
producer key, but constructor/member/default signatures still contained final
`DotNetGenericOwnerPhysicalTypeExpressionRecord` values. That forced a physical
TypeDef path to exist while the compiler snapshot was built. The recursive
OctoTree getter

```kotlin
val nodes: Array<Node<T>?>
```

therefore lost its complete signature family: recording `Node<T>[]` would have
required guessing a path from the nested class's display name.

## One prototype type grammar

The state-only snapshot is generalized to
`DotNetGenericOwnerPrototypeTypeSnapshot` and is now shared by state,
constructors, members, and masked-default helper tails. Its bounded nodes are:

- `void`, Boolean, Int32, String, object, and the explicit `System.Array`
  fallback;
- owner and method GenericParams;
- an invariant Kotlin-owned generic classifier identified only by its
  pre-lowering logical declaration key; and
- SZ arrays over another admitted node.

`DotNetGenericOwnerPrototypeMethodSignatureSnapshot` retains the complete slot
domain vector and checks the same return, parameter, receiver, void, owner-
domain, and method-GenericParam-index invariants as the final physical
signature. `bindProducerTypes` then resolves every logical classifier through
one complete logical-key-to-TypeDef-path map and creates the existing physical
signature record atomically.

The physical schema-7 artifact remains unchanged: it still serializes only
fully bound CLR types. Missing logical keys or selected paths fail before an
artifact can be published.

## Exact and semantic carriers

The mapper validates the complete bounded classifier tree before applying a
semantic fallback. It must not turn an unknown `Foreign<T>` into `object` merely
because the type mentions an owner parameter. Generic value classes and
unsupported classifiers/projections remain unavailable.

For the separate OctoTree producer this gives the `nodes` getter:

- typed return: path-unbound `SZ_ARRAY(LOGICAL_NODE(!T))`;
- capability return: `System.Array`; and
- final bound typed return: producer-scoped `Node<!T>[]` after the artifact
  selects the Node TypeDef path.

The typed getter signature and the field state bind to exactly the same
physical type. Binding either without the Node path fails. Same-compilation
snapshots which do not own a stable library declaration key remain unavailable
instead of deriving ABI from `OctoTree.Node` text.

The external-family resolver now binds consumer snapshots through the decoded
producer artifact's owner map before exact MethodDef comparison. The existing
hostile record-driven C# producer/subclass therefore consumes the same
path-unbound grammar without changing its final physical artifact.

## Boundary

This is production-inert architecture evidence. It changes no emitted Kotlin
owner, DLL/KLIB schema, Runtime surface, Common behavior, or public C# ABI. It
does not yet serialize the complete recursive OctoTree family or build its
candidate and direct C# consumer/subclass products; those are the next product
boundary.

## Verification

Focused PSI/LightTree execution on Framework 4.8 and .NET 10 covers both the
separate OctoTree producer/consumer and the hostile schema-7/C# physicalizer:
eight tests with zero failures, errors, or skips. The final strict aggregate
completed in 1,913.7 seconds. Direct audit covers 190 XML files and 2,238 tests
with zero failures, errors, or skips.
