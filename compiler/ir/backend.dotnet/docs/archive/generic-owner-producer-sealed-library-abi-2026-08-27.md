# Generic-owner producer-sealed library ABI

Date: 2026-08-27

## Context

The bounded generic-owner rehearsal already sealed one complete family from a
successful final emission transaction. That certificate was deliberately
local: a downstream compiler could read the ordinary physical-library records,
but it could not receive the producer's actual-only `TypeDef`, `MethodDef`, and
`MethodImpl` family as one independently validated authority product.

This checkpoint adds that separate-compilation envelope. It does not broaden
the admitted family, change a call route, or switch the production ABI. It
publishes the same post-emission family that was already proved locally:

```text
4 TypeDefs
  natural interface
  interface semantic capability
  implementation class
  class semantic capability

6 MethodDefs
  natural interface slot
  interface semantic-capability slot
  typed implementation entry
  class semantic-capability slot
  class semantic-capability dispatcher
  interface semantic-capability dispatcher

2 MethodImpls
  class semantic-capability implementation
  interface semantic-capability implementation
```

The natural interface implementation remains ordinary implicit CLR mapping;
the producer does not fabricate a third `MethodImpl` for it.

## Publication transaction and identity

Only the final successful emission product may publish the certificate. Its
actual rows are retained after the emitter fixpoint, validated as one complete
4/6/2 body, and joined to three exact pre-lowering KLIB identities:

```text
logical interface member key
implementation owner key
implementation member key
```

All three keys are mandatory, non-empty, and NUL-free. If both implementation
keys are absent because the implementation is not exportable, no certificate
is published. A partially available key triple conflicts rather than being
completed from a physical name, selected view, IR origin, or current consumer
inference.

The physical-library envelope uses declaration kind `J` and a structural index
key derived from the complete length-delimited three-key tuple. Consequently,
different tuple boundaries cannot alias and no diagnostic or emitted name
participates in identity.

The inner producer record has its own versioned binary codec. It uses fixed
role identities and canonical role order for the four TypeDefs, six MethodDefs,
and two MethodImpls. Metadata sets such as aliases, direct edges, and generic
constraints are canonicalized as sets; ordered construction arguments,
parameters, and binder positions retain their semantic order. A decoder
reconstructs a fresh sealed-signature index from the actual rows. It does not
recover missing rows from BOUND or from another physical-library record.

## Conjunctive physical-library validation

`J` is actual emitter evidence, not an independent parallel declaration graph.
The complete ABI index admits it only when all of these records agree:

```text
C  logical interface and implementation Class records
F  exact logical-interface and implementation Function records
G  interface and implementation generic-owner member-family records
H  the root-owned published generic-interface family
J  the canonical actual-only 4/6/2 sealed family
```

The join verifies the exact KLIB keys, owner paths and arities, capability
paths and direct capability edge, MethodDef names/instance shape/generic arity,
member-family endpoints, admitted root-owned `H` shape, and producer result
role. An absent, wrong-kind, stale, auxiliary, or structurally disagreeing peer
record rejects the complete index. A valid `J` payload therefore cannot by
itself prove that any logical or physical declaration exists.

Portable ABI comparison continues to use structural equality for every
portable declaration. A runtime-profile variant may add declarations under the
existing rule, but it may not silently change a portable `J` record or any of
the `C/F/G/H` facts on which that record depends.

## ABI 61 and fail-closed decoding

The physical-library schema is now ABI version 61. The version bump is
intentional even for a library that contains no `J` record: a version-61
consumer may distinguish a producer that understood the `J` schema and
classified the family as absent from an older producer that could not publish
such evidence. ABI 60 is therefore not treated as trustworthy negative `J`
evidence.

The outer envelope requires exact field count, a canonical URL-safe Base64
payload, equality between the three outer keys and the decoded inner keys, and
a bounded payload size. The inner decoder is likewise bounded and rejects bad
magic or version, malformed UTF-8, truncation, trailing bytes, invalid roles,
missing/duplicate/extra rows, wrong endpoints, cross-binder references,
and unsupported carriers. The inner codec may decode a structurally valid but
non-canonical byte sequence. The outer `J` envelope closes that gap by
re-encoding the decoded publication and requiring exact canonical payload
equality.

## Separate-compilation proof

The dedicated rehearsal library owns an ordinary Kotlin generic producer
interface and implementation. After successful library emission it publishes
one `J` record for that implementation, joined to the exact `C/F/G/H` records
in the same KLIB metadata index. Encoding and decoding the complete physical
index preserves the certificate exactly. A separately compiled executable
consumer uses the producer's ordinary Kotlin declaration and value, observes
the expected result, and does not republish the producer's certificate as if
it were locally emitted evidence. No wrapper or replacement receiver is added
by this publication feature.

This proves publication and transport across the library boundary. It does
**not** yet prove a consumer operation route sourced from `J`. Current consumer
routing remains unchanged; the retained-foreign seal and static/file-facade
operation-authority route are the next authority steps.

## Production inverse and rollback

The certificate is rehearsal-only. Production and explicit-off compilation
remain on the atomically erased generic-interface ABI and assert that no `J`
record is emitted. This feature introduces no wrapper, proxy, shadow state,
second receiver identity, fabricated CLR construction, or route change.

Before a production cutover, rollback remains exact and atomic: remove the
rehearsal publication/consumer schema as one epoch and return the provisional
physical-library version to its preceding schema. No per-interface mixed
generic/erased production state or compatibility shim is permitted. Once a
schema is externally frozen, normal versioning rules replace this provisional
inverse; this checkpoint does not claim that freeze.

## Evidence

The focused proof set covers:

- canonical producer-record round-trip and role ordering;
- malformed, truncated, trailing, duplicate, cross-binder, oversized,
  outer/inner-key-disagreement, and outer-envelope non-canonical-payload
  rejection;
- structural index-key separation for adversarial key boundaries;
- whole-index round-trip and `C/F/G/H/J` conjunctive rejection;
- portable-ABI equality/difference behavior;
- PSI and LightTree library/application publication checks on Framework 4.8
  and .NET 10; and
- the production-erased and consumer-no-republication inverses.

The property-absent full aggregate exits zero on the final semantic tree.
Direct XML audit records 201 suites/2,524 tests with zero failures, errors, or
skips:

```text
backend       11 suites /   148 tests
dotnet.ir      1 suite  /     6 tests
FIR          187 suites / 2,243 tests
integration    2 suites /   127 tests
total        201 suites / 2,524 tests
```

The exact 2-suite/22-test increase over the preceding checkpoint is the two
new producer-seal unit suites. In addition, focused rehearsal and explicit-off
separate-compilation runs each cover PSI and LightTree on Framework 4.8 and
.NET 10: four suites/four tests per mode, with no failure, error, or skip.

## Boundary and next work

This is the first independently transported Kotlin-producer sealed adapter. It
is still restricted to the admitted root-owned direct-producer family and does
not establish arbitrary overlapping/compiler-wide family ownership.

Next add the retained-foreign sealed adapter and the static/file-facade
operation-authority route. Both must use their own final-live evidence and must
join, rather than reconstruct, the producer's recorded facts. Then close
overlapping/global-family ownership before using the shared authority to
replace bounded recognizers or resume the source-built Stdlib census.
