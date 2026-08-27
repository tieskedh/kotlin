# Inline functions maintenance programme

- Status: **Completed foundation; maintenance only**
- Ordinary/reified representation:
  [`../decisions/reified-inline-functions.md`](../decisions/reified-inline-functions.md)
- Physical `@InlineOnly` ABI:
  [`../decisions/inline-only-physical-abi.md`](../decisions/inline-only-physical-abi.md)
- Logical `KType` composition:
  [`../decisions/ktype-and-typeof.md`](../decisions/ktype-and-typeof.md)
- Suspend-inline composition:
  [`../decisions/kotlin-coroutines.md`](../decisions/kotlin-coroutines.md)

This page owns only the maintenance boundary for the completed shared inline
foundation. Git and the dated archive records retain its implementation
history; the linked ADRs own representation.

## Selected foundation

Kotlin/.NET uses the repository's Common first-/second-stage inliner,
non-linking KLIB IR deserialization, shared-variable lowering, and Common
default/callable infrastructure. A separately compiled inline body is selected
from the frontend's explicit logical library graph; physical DLL discovery or
CLR name lookup must not become a second linker.

Ordinary and reified inline calls, inline lambda control flow, `@InlineOnly`,
the three supported KLIB inliner modes, cross-library dependency binding,
`KType`/`typeOf`, and the selected suspend-inline path all compose through that
one foundation. Later feature programmes do not reopen the inliner merely
because they consume it.

## Invariants

- KLIB owns the inline body and logical declaration identity.
- A surviving external call binds only through producer-recorded physical ABI.
- After the shared inline prefix, every residual unbound symbol is rejected
  before target lowerings.
- Reified substitution uses the already selected physical carriers and leaves
  no Kotlin call to a compiler-only remainder.
- `@InlineOnly` remains logically public in KLIB but assembly-visible in CLR
  metadata; a separate Kotlin consumer must inline it.
- Inline lowering must preserve Kotlin evaluation order and non-local return
  semantics. Do not repair emitter stack behavior by rewriting Common source.
- Common's production default is the normal test mode. Retain extra mode lanes
  only where they prove disabled/intra-module/full compiler ABI or fallback
  behavior.

## Maintenance gate

An inline-affecting change must retain:

- same-module and self-describing separate-library execution;
- explicit selected-graph dependencies without transitive DLL discovery;
- friend and compiler-ABI visibility;
- ordinary, reified, `@InlineOnly`, default-argument, local-return, reflection,
  and suspend-inline consumers relevant to the change;
- both FIR parser paths and both runtime profiles; and
- failure before CIL emission for residual compiler-only inline constructs.

There is no pending inline tranche here. New reflection, coroutine, value-class,
generic-owner, or export work belongs to its own programme and must only consume
this foundation through the shared contracts above.
