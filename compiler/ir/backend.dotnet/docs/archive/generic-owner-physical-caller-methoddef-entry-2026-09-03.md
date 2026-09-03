# Generic-owner physical caller-MethodDef entry — 2026-09-03

This archive records the first BOUND current-MethodDef entry environment for a
real caller method parameter and its direct immutable local. It changes neither
Kotlin semantics nor the production-erased ABI and publishes no external
physical-library record.

## Closed gap

Earlier physical-value placement could preserve a current owner parameter
`!T`, but a generic caller parameter `!!R` had no authenticated current
MethodDef binder. Treating the logical `R` as enough would be unsound: `!0` and
`!!0` can have the same index while belonging to different metadata owners, and
the detached representation plan is created before the final MethodDef exists.

The local declaration authority now registers one complete current MethodDef
at the BOUND epoch. Its identity is the exact source function plus
`TYPED_ENTRY`; a capability dispatcher may be an orthogonal family endpoint but
does not become the binder. PRE-final-routing shadow analysis sees no current-
MethodDef fact. POST may seed the bare `!!0` parameter from that BOUND entry and
retain it through one equal immutable alias.

The value remains a direct substitution-dependent carrier with `MAYBE_NULL`
null state. It gains no guaranteed interface view or selected lineage. In
particular, the caller method parameter is not reinterpreted as an owner `!T`.

## First grammar

The admitted owner is one local rehearsal generic class. The callable is a
final concrete source-owned typed entry with exactly one invariant,
non-reified, unconstrained MethodDef parameter. Exactly one ordinary parameter
slot is the bare outer-unmarked logical `R`, recorded physically as
`DECLARATION_INDEPENDENT(!!0)`. The direct non-null result is a strict owner
parameter. Other slots may use only the already admitted fixed, bare-owner, or
one-level recorded generic-class/natural-interface construction vocabulary.

Semantic hooks, overrides, defaults, direct-super obligations, properties,
suspend and local functions, `@PublishedApi`, varargs, constraints, nullable or
value-class method parameters, multiple binders, mutation, joins, conversions,
captures, and state remain outside this grammar. This proof also grants no
callee operation or MethodSpec authority: the call may continue through the
existing guarded semantic route even while its caller entry/local is `!!0`.

## Final physical seal

Late local placement accepts the retained carrier only when the live source is
an exact `ldarg`/`ldloc` `!!0` belonging to the same current MethodDef identity
and generic arity. The emitter cannot normalize a roleless, sibling, owner
parameter, or out-of-range binder into that value.

After a scope emits successfully, a standalone current-MethodDef comparison
requires exactly one matching header for the same IR function and typed-entry
identity. Owner and scope, visibility and dispatch, generic arity, the
unconstrained GenericParam row, receiver, ordinary parameters, and result all
have to match the BOUND signature. Missing, duplicate, cross-scope, wrong-role,
same-role sibling-function, or structurally drifted evidence fails closed.
Failed emission scopes are not required to provide a seal, so this check does
not mask their original diagnostic.

## Executable and hostile evidence

The integration fixture retains both public and private generic caller aliases.
The private helper is important because it cannot borrow the older public
capability-dispatcher mapping. Runtime calls distinguish owner and caller
binders with `T=String, R=Any?` and `T=String, R=Int`; the latter exercises a
value-type `!!0` while the owner result remains a reference. The emitted entry
has a `!!0` parameter and local copied directly through `ldarg`/`stloc`/`ldloc`.
The test deliberately does not require a natural callee MethodSpec route.

Model negatives cover a sibling MethodDef, a different physical role, wrong
arity, missing current identity, and a live `!0` in place of `!!0`. BOUND
authority rejects an arity-only signature with no actual method-parameter slot.
The final-header regression additionally supplies the expected physical
identity and role with a sibling IR function and requires `Conflict`.

The production inverse forbids the candidate `InlineMethodProducer<T>`, its
semantic capability, and the generic route owner. It therefore proves that the
ordinary erased compilation publishes none of this rehearsal authority.

## Verification

Backend/FIR compilation passed. The physical-value model passed 96/96 tests and
the MethodDef-emission comparison model passed 14/14. Direct JUnit XML audit
found one focused fixture in every PSI/LightTree and .NET 10/Framework 4.8
suite, for both candidate and production-erased inverse modes: four tests per
mode, with zero failures, errors, or skips. Each candidate assembly was
assembled and executed by the fixture.

The focused commands were:

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin :compiler:backend.dotnet:compileTestKotlin :compiler:fir:fir2ir:compileTestKotlin --no-configuration-cache -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "*DotNetGenericOwnerPhysicalValueModelTest" --tests "*DotNetGenericOwnerPhysicalMethodDefEmissionComparisonTest" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun "-Pkotlin.dotnet.genericOwnerRehearsal=true" --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
```

## Next gate

Prove the separate operation use of an authenticated retained caller `!!R` as
a callee MethodSpec carrier. Additional non-materializing consumers, prefix-
bearing container obligations, broader MethodSpec/argument/result forms,
null/bottom/unknown joins, conversions, captures, properties, fields, state,
foreign/separate-consumer entry, and Runtime/Stdlib declarations remain later
gates.
