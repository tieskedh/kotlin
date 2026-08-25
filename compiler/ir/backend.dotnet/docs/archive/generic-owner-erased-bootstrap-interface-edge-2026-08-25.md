# Generic-owner erased bootstrap interface edge

Date: 2026-08-25

## Context and decision

After post-representation return closure, the source-built Stdlib rehearsal
failed while walking `HashMapEntrySetBase` supertypes. Its same-module Stdlib
fallback has one arity-zero physical TypeDef, but reconstructed the logical
`MutableSet<E>` edge with open `!n` arguments which do not exist on that owner.

The ordinary module emitter already suppresses owner-dependent natural edges
for erased classes. The leak came from the separate bootstrap generic-class
link builder, which rebuilt its assignability graph from logical IR without
checking recorded physical arity. It now retains a direct interface type only
when the physical TypeDef declares GenericParams or the edge is closed. The
ordinary non-generic bootstrap builder is unchanged.

## Proof and result

A backend unit proof fixes the invariant independently of stdlib names:
arity-zero plus an owner-parameter edge is rejected, arity-one accepts it, and
arity-zero still accepts a closed edge. The source-product census no longer
throws during supertype substitution and reaches the normal unsupported-shape
census.

The final target aggregate exits zero. XML audit covers 191 suites/2,347 tests
with no failures, errors, or skips: 187 FIR suites/2,211 tests, two integration
suites/127 tests, the three-test backend suite, and six `dotnet.ir` tests.

The next bounded work is to classify the first repeated semantic-to-natural
typed-interface conversion family from that census without a Throwable,
collection, or stdlib-name exception.
