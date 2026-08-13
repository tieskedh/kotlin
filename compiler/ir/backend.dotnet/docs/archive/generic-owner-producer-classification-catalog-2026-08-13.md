# Generic-owner producer classification catalog

- Date: 2026-08-13
- Scope: production-inert generic-owner architecture artifact
- Result: schema 7 records deterministic erased-only exclusions

## Question

The migration plan requires every generic-owner declaration to have one
producer-authoritative admission classification. An unsupported declaration
may stay erased, but that choice may not vary by consumer, call site, profile,
or optimization. Schema 6 serialized only declarations for which the hostile
test physicalizer built a complete CLR-generic family. Absence therefore did
not distinguish a deliberate exclusion from an omitted or unrelated producer
record.

The hardest example is `D<T> : C<T?>`. One CLR `D<T>` TypeDef cannot have
`C<Nullable<T>>` as its base for value substitutions and `C<T>` for reference
substitutions. The tested `C<object>` fallback preserves Kotlin execution but
does not expose truthful CLR/C# ancestry. This declaration must remain erased
unless a different one-owner representation is proven.

## Schema-7 result

The versioned architecture artifact now has two joined layers:

1. A complete catalog contains every logically bindable generic-owner snapshot
   from the producer. Each record carries the logical owner key, generic arity,
   current fail-closed disposition, and sorted logical constructor/member key
   sets.
2. Physical-family records remain optional prototype evidence. Every published
   family must match its catalog entry exactly in owner, arity, disposition,
   constructors, and members.

`HostileNullableDerived<T>` is encoded in the first layer with
`BLOCKED_METADATA_FIXED_CONDITIONAL_SUPERTYPE` and has no record in the second.
The physical-family lookup reports that exact producer disposition. External
member resolution likewise distinguishes a classified owner with no physical
family from an unknown logical member.

The artifact rejects duplicate classifications, a physical family whose
classification is absent, and any constructor/member key disagreement. It
still has no `ADMITTED` result and cannot influence production emission.

## Verification

The PSI and LightTree separate-compilation hostile lanes each regenerated,
decoded, and consumed the producer artifact successfully. Their embedded
negative matrix exercised schema round-trip determinism, classified
metadata-fixed absence, exact published-family lookup, catalog/family mismatch
rejection, and a consumer obligation whose known producer family was removed.

The initial incremental build attempt encountered a corrupt local Kotlin
incremental-cache `EOFException`. A cache-independent backend compilation with
incremental compilation disabled passed before the focused semantic tests; the
cache failure was not treated as feature evidence.

The complete PSI/LightTree, CoreCLR/Framework CLR, same/separate-compilation
hostile matrix then passed eight tests with no failure, error, or skip. A
fresh schema-7 measurement bundle kept the prior producer, source, project,
and SDK-selector hashes, changed only the physical-family artifact hash to
`765c8bd9d62c81d35eae0cb036c78677de6d5020afb2cd8e9508125eafcf422e`,
and reproduced checksum `2027804433` under JIT, ReadyToRun, and full trimming.
NativeAOT remained unrun and unproven because the platform linker is absent.

## Boundary

This closes the architecture-level requirement that an unsupported
metadata-fixed declaration be deterministically classified by its producer.
It does not serialize the catalog into today's DLL/KLIB, authorize a mixed
erased/reified ABI, or close NativeAOT and representative-application gates.
Production generic owners remain erased.
