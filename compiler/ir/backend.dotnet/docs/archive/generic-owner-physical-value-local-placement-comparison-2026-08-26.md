# Generic-owner physical-value local-placement comparison

Date: 2026-08-26

## Context

The two-epoch physical-value shadow could predict exact storage for source and
compiler-owned immutable aliases without consulting an IR origin. It still had
no evidence about the verifier-visible local carrier which the current emitter
actually selected. The existing emitter recognizer therefore remained an
unmeasured implementation fact: a prediction could agree with it, differ from
it, or fail to identify the same physical local.

The next slice needed a one-way comparison which could observe the current
emitter without becoming another representation authority.

## Decision

Rehearsal emission now records the final verifier-visible slot of each ordinary
`IrVariable` emitted through the existing variable-local path, together with
the reason which selected that slot. Catch parameters and other separately
declared IL locals are outside this slice.
Observations are transactional per emitter scope: a failed method/class render
and every superseded fixpoint round contribute nothing, while a successfully
completed stdlib or user emission scope retains only its final products.
Production emission creates no observation collectors and publishes no
observations or comparisons.

Correlation uses the object identity of the physical function and local IR
symbols. Names, slot numbers, declaration origins, generated-name conventions,
packages, collections, and stdlib declarations are not correlation authority.
An observation is normalized while emitter `ClassInfo` identities are still
available. The bounded structural vocabulary distinguishes:

- `object`;
- a local generic construction, including the physical TypeDef view, the
  type-parameter binder TypeDef/view, and every owner-parameter index;
- a recorded semantic capability; and
- an explicit unbindable carrier.

The physical MethodDef owner is retained independently from the local carrier.
This prevents `I<C.!0>` and `I<D.!0>` from becoming indistinguishable in the
IR-free result.

The comparison uses `POST_FINAL_ROUTING` as its prediction. A matching
pre-remap record contributes only `STABLE`, `DIVERGED`, or `NOT_OBSERVED`
continuity. Diagnostic evidence labels do not participate in value equality;
guaranteed construction identities and selected lineage do. Missing,
duplicate, unsupported, and structurally unbindable correlations remain
explicit. `MATCH` and `DIFFERENT` are deliberately neutral: this layer does not
classify a conversion as safe, semantic, boxing, or erroneous.

## Transfer closure needed by the comparison

Compiler aliases introduced inside the expression-container of a non-generic
result were not visible to the first shadow traversal. The analysis now enters
only sequential block/composite containers reached through a variable
initializer, return, or implicit wrapper. It does not treat calls, branches,
loops, throws, or arbitrary expression trees as transparent, and nested
storage does not escape its container.

An implicit cast/not-null wrapper may preserve an already direct null-reference
carrier when its target is `Any`/`Any?`, the current owner, or another non-value
class/interface declaration in the current IR module. That fact establishes
only that no boxing/materialization is required. It adds no target construction
to guaranteed views and authorizes no call through that logical view. External
and foreign classifiers still require retained physical category authority.

## Executable evidence

Two independent probes exercise the same comparison.

The two-epoch `ShadowOwner` hostile probe identifies the explicit source alias
`sourceDeclaredExactWidening`. Both epochs predict exact `ShadowOwner<!T>`
storage from the current receiver. The unchanged emitter instead selects its
declared semantic capability. The result is one stable `DIFFERENT` observation,
not an assertion that either policy may already become authoritative.

The inline probe uses a public covariant producer so its natural CLR-generic
TypeDef is admitted. The final shadow reaches two inliner-owned aliases through
their sequential expression container and predicts `InlineSelfView<!T>` for
both without reading their origins. Both actual slots report the existing
`EXACT_GENERIC_OWNER_OVERRIDE`, and both compare `MATCH`. Fixture markers and
declared owner/function names locate this probe, but the generated alias names
and origins do not participate in alias selection or comparison.

The focused matrix passes sixteen lanes: both probes with rehearsal enabled
and explicitly disabled, each through PSI and LightTree on Framework 4.8 and
.NET 10. The production-off lanes publish no comparisons.

The final normal target aggregate exits zero. Direct XML audit covers
194 suites and 2,431 tests with zero failures, errors, or skips. The FIR,
integration, and backend-unit roots freshly write 187 suites/2,239 tests, two
suites/127 tests, and four suites/59 tests respectively; the unchanged
`dotnet.ir` root retains six green tests.

## Boundary

A matching local proves only the emitter-selected `StorageCarrier`. It does
not prove the initializer's produced carrier, the conversion sequence, a
natural interface view, `InterfaceImpl` ancestry, selected-view lineage,
operation routing, field/state representation, a MethodImpl, or serialized
ABI.

In particular, this slice does not yet bind
`InlineSelfView<!T> : InlineProducer<!T>`. The exact concrete carrier can hold
the already-produced receiver identity without that edge, but a call through
the natural `InlineProducer<!T>` interface view is not justified yet. The
existing semantic-capability route remains available. The next natural-view
call/use proof must obtain the edge from admitted physical declaration
authority, never from the logical supertype alone.

Calls, fields, constructors, captures, real control-flow joins, retained
foreign carriers, separate assemblies, nullable/value-class substitutions,
and split-nullable composition remain open. The old IR-origin recognizer still
selects actual compiler-alias storage. For a fixed IR input, the comparison
changes no route, state choice, MethodImpl, code-generation decision, emitted
IL, production ABI, or atomic rollback boundary.

## Next step

Bind the exact current receiver's already admitted physical interface edge in
declaration authority and add an immutable source alias to the public
`InlineProducer` probe with one actual call/use through its natural interface
view. Selected lineage may choose only an independently guaranteed view; it
may never create that edge. Only after the same authoritative query covers the
positive call and hostile negatives may it replace the origin recognizer for
local placement.
