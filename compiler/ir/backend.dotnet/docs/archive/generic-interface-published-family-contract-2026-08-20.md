# Generic-interface published-family contract

Date: 2026-08-20

## Outcome

ABI 41 replaces consumer-side reconstruction of a reified generic-interface
family with one versioned producer contract. Local analysis and external ABI
decoding now produce the same immutable value containing:

- the logical owner key and complete generic arity;
- root, derived, or independent-root intersection topology;
- sorted direct parent and root keys;
- exact parent-parameter to child-parameter identity mappings;
- the selected lineage depth;
- every directly declared producer, consumer, property-getter, or property-
  setter role;
- an owned capability or the exact direct parent whose capability is reused.

The companion physical record binds that contract to the natural CLR TypeDef
and the exact capability assembly/TypeDef. `hasReifiedGenericInterface`
remains only an existence and full-arity query.

## Fail-first boundary

The three-producer property/consumer chain was first changed to require an
external family contract while the decoder deliberately returned none. All
four PSI/LightTree and Framework/.NET 10 separate-compilation lanes failed at
`leaf.dll`: `ConsumerChild<T>` could no longer be treated as a CLR interface.
This is the same fail-closed point as the original three-assembly proof and
shows that the deployment test consumes the producer authority.

## Physical ABI

Each published classifier owns one `H:<logical-owner-key>` declaration-index
record separate from its Class and member-family records. The codec includes
counts for every variable block and constructs the validated typed contract
before returning it. The shared external index then rejects the whole family
unless all of the following agree:

- the Class record has the same natural TypeDef and complete arity;
- its generic-owner capability has the same assembly and TypeDef;
- every declared role has a producer-owned member-family record;
- every direct parent has its own complete published contract;
- parent mappings cover the parent's complete arity;
- root closure and depth equal the parent contracts;
- an owned capability differs from every parent capability, while a reused
  capability equals the selected direct parent's capability.

There is no partially usable decoded family. A malformed, missing, or stale
record fails before a lowering can bind one member.

## One admission consumer

The lowering validates the selected contract conjunctively against KLIB.
KLIB supplies the public interface, member declarations, direct parent edges,
and actual type arguments. The physical contract supplies the producer's
family kind, roots, depth, roles, and capability binding. Both local and
external parents pass through this same validator.

The bounded invariant consumer grandchild no longer asks whether an external
parent merely has a reified owner plus three loose member records. It requires
the validated derived depth-one consumer contract above the validated mutable-
property root contract. This preserves the bounded admission rule without
reconstructing producer decisions from mutated consumer IR.

Executables do not publish KLIB identities but still need the same local
contract during their one compilation. They receive deterministic local-only
owner/member keys; only declarations with producer KLIB identities enter the
ABI record.

## Evidence

The enabled rehearsal passes the eight focused direct/separate lanes across
PSI, LightTree, .NET Framework 4.8, and .NET 10. The epoch-off inverse passes
the same eight lanes using the erased production ABI. Both matrices have zero
failures, errors, or skips.

The final normal production aggregate directly audits 190 XML suites and
2,287 tests with zero failures, errors, or skips: 187 FIR suites/2,155 tests,
two integration suites/126 tests, and one `dotnet.ir` suite/six tests.

## Remaining boundary

The contract normalizes already admitted families; it does not widen them.
Changed parameter mappings, broader member roles, deeper invariant consumer
inheritance, defaults, constraints, multiple type parameters, and Runtime/
Stdlib migration remain fail-closed. Before that graph opens, final call/value
routing must also move after every body-producing lowering while consuming
these stable early declaration contracts.
