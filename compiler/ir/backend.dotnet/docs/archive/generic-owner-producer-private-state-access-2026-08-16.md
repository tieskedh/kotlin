# Generic-owner producer-private state access

## Scope

Generic-owner physical-family schema 9 distinguishes Kotlin logical member
access from exact producer-private physical state access. A physical owner can
now record a private typed identity read or write without inventing a KLIB
callable key, member-family role, or reflection-visible Kotlin declaration.

Production generic owners remain erased. The artifact is test-owned and is not
embedded in today's DLL or KLIB.

## Binding kinds

`LOGICAL_MEMBER_FAMILY` retains the existing complete join:

- a non-empty logical member key;
- `TYPED_ENTRY` or `SEMANTIC_HOOK`, exactly matching the access domain; and
- the exact MethodDef selected by that logical member family.

Its physical visibility is not repeated in the state path because the logical
member record owns the MethodDef identity and dispatch contract.

`PRODUCER_PRIVATE_METHOD` instead records no logical key and no member role.
It requires an exact private MethodDef on the same physical owner, a typed
domain, and an identity conversion. The MethodDef participates in complete
signature, GenericParam-scope, duplicate, and collision validation, but it is
absent from Kotlin member reflection and logical invocation records. A private
method may not reuse any logical-member MethodDef identity.

This is the required shape for physical implementation details such as the
recursive OctoTree owner's private `root` state paths: the producer can expose
one Kotlin property while its physical representation uses private helpers
that have no independent Kotlin source identity.

## Semantic-object state invariant

`SEMANTIC_OBJECT_REQUIRED` still stores `object` and cannot publish a typed
state initializer. Its access proof now has two explicit cases:

- a purely producer-private typed identity state requires exactly one READ and
  one WRITE; or
- the presence of any semantic-domain path or non-identity boundary conversion
  requires the complete paired typed/semantic READ and WRITE matrix.

Therefore a private exact object carrier does not need fictitious semantic
hooks, while any actual semantic crossing retains the old fail-closed paired
proof. Semantic producer-private access, non-private access, an access method
on another TypeDef, incomplete operations, and mixed partial semantic paths all
reject the family.

## Codec and validation

Schema 9 expands each state-access record with its binding kind and nullable
logical-member fields plus an exact physical visibility for producer-private
methods. Canonical encoding sorts the expanded records. Schema 8 is stale and
fails closed.

The positive codec oracle adds a semantic-object state with two private typed
identity methods on the same generic owner. It proves canonical round-trip,
exact private MethodDef retention, and exclusion from logical reflection. The
negative matrix rejects a private path carrying a logical key, non-private or
semantic private access, a foreign declaring TypeDef, and collision with a
logical member MethodDef.

An initial separately compiled oracle exposed that the test compared decoded
canonical ordering with construction ordering. The assertion was corrected to
compare canonical bytes and exact state content; the codec itself retained all
records correctly.

## Verification

The final PSI/LightTree x Framework 4.8/.NET 10 same/separate-compilation
matrix covered 16 tests with zero failures, errors, or skips. The strict
aggregate completed in 1,835.0 seconds and directly audited 190 XML files and
2,238 tests with zero failures, errors, or skips.

## Next gate

The next tranche must bind all four recursive OctoTree physical owners into
one complete schema-9 family. `Branch.nodes` must reuse its exact `Node<T>[8]`
initializer, while private `root` state access must use this new producer-
private binding rather than an invented KLIB member. Only then should the
record-driven candidate and direct C# products be built.
