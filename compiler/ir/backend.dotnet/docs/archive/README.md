# Kotlin/.NET documentation archive

This directory contains immutable review, rebase, probe, or superseded-design
snapshots. They preserve provenance for decisions and implementation history,
but they do not define current branch state or normative architecture.

Use:

- [`../../STATUS.md`](../../STATUS.md) for current branch, verification, and
  work state;
- [`../programmes/way-forward.md`](../programmes/way-forward.md) for future gates and
  ordering; and
- [`../decisions`](../decisions) for active draft and accepted decisions.

Archived snapshots:

- [`generic-owner-physical-split-nullable-repeated-input-placement-2026-09-02.md`](generic-owner-physical-split-nullable-repeated-input-placement-2026-09-02.md)
  records cardinality-independent strict-owner input vectors for one retained
  split result: repeated authenticated TypeDef slots, complete ordered
  declaration/instantiation/live-argument validation, order-sensitive unboxed
  value/reference/nullable-value execution, and the exact Framework 4.8/.NET 10
  production-erased inverse.
- [`generic-owner-physical-split-nullable-control-flow-placement-2026-09-02.md`](generic-owner-physical-split-nullable-control-flow-placement-2026-09-02.md)
  records the first flat exhaustive split-result initializer join: identity-
  keyed per-arm operation witnesses, an equality-only payload join, one shared
  payload/flag pair with an empty-stack CIL join, braced FIR2IR-arm transparency,
  unboxed value/reference/nullable-value execution, and the exact Framework
  4.8/.NET 10 production-erased inverse.
- [`generic-owner-physical-split-nullable-multiple-direct-returns-2026-09-02.md`](generic-owner-physical-split-nullable-multiple-direct-returns-2026-09-02.md)
  records positive-N same-function terminal-return authority for one retained
  split pair, the path-termination argument, exhaustive late live-use emitter
  revalidation, two unboxed return tails across value/reference/nullable-value
  execution, hostile non-return/protected controls, and the exact Framework
  4.8/.NET 10 production-erased inverse.
- [`generic-owner-physical-split-nullable-methodspec-placement-2026-09-02.md`](generic-owner-physical-split-nullable-methodspec-placement-2026-09-02.md)
  records the first retained MethodSpec-bearing split-result local: the exact
  `<R>(K, R): V?` composition, independent open TypeDef/MethodDef binders and
  instantiated carriers, one owner-bound MethodSpec, unboxed value/reference/
  nullable execution, and the exact Framework 4.8/.NET 10 erased inverse.
- [`generic-owner-physical-split-nullable-strict-input-placement-2026-09-02.md`](generic-owner-physical-split-nullable-strict-input-placement-2026-09-02.md)
  records the first retained argument-bearing split-result local: one final
  exact-natural operation witness, one identity-preserving
  `STRICT_OWNER_INPUT`, full MethodDef/receiver/parameter/result validation,
  independent interface `!K`/`!V` binders, unboxed value/reference/nullable
  execution, and the exact Framework 4.8/.NET 10 production-erased inverse.
- [`generic-owner-physical-split-nullable-local-placement-2026-09-01.md`](generic-owner-physical-split-nullable-local-placement-2026-09-01.md)
  records the first bounded two-slot local retention of a natural
  `SplitNullable(!T, out bool)` result, creation-site member-contract authority
  for executable producers, unboxed direct-return execution for reference,
  value, and nullable-value substitutions, ordinary-consumer and exception-
  region materialization negatives, and the exact Framework 4.8/.NET 10 erased
  inverse.
- [`generic-owner-methodspec-split-nullable-composition-2026-09-01.md`](generic-owner-methodspec-split-nullable-composition-2026-09-01.md)
  records the first structural composition of MethodDef binders, strict owner
  inputs, and split-nullable owner outputs: `!V lookup<!!R>(!K, !!R, out bool)`,
  exact and widened routing, producer records and PE, separate Kotlin
  assemblies, ordinary natural-only C# implementations, and the exact
  Framework 4.8/.NET 10 production-erased inverse.
- [`generic-owner-physical-methodspec-operation-2026-09-01.md`](generic-owner-physical-methodspec-operation-2026-09-01.md)
  records the first final-IR MethodSpec operation consumer: producer-recorded
  MethodDef arity, a BOUND current-owner `!T` argument vector, independent
  TypeDef/MethodDef substitution, exact natural value/reference execution,
  broad-semantic and caller-MethodDef hostile isolation, and the exact
  Framework 4.8/.NET 10 production-erased inverse.
- [`generic-owner-physical-owner-input-operation-2026-09-01.md`](generic-owner-physical-owner-input-operation-2026-09-01.md)
  records the first authoritative argument-bearing natural operation: a
  constructed `I<!T,!T>` typed entry, exact owner-dependent input, orthogonal
  split-nullable output, explicit semantic-result-policy priority, downstream
  conservative-route correction, hostile widened value-type isolation, and
  the exact Framework 4.8/.NET 10 production-erased inverse.
- [`generic-owner-physical-typed-call-result-2026-09-01.md`](generic-owner-physical-typed-call-result-2026-09-01.md)
  records the first authority-backed natural-MethodDef result transfer: a
  parameterless `Direct` result instantiated through an already-guaranteed
  receiver construction, retained owner `!T` local storage, independent live-
  emitter validation, semantic-route veto, hostile value-flow isolation, and
  the exact Framework 4.8/.NET 10 production-erased inverse.
- [`generic-owner-physical-typed-parameter-entry-2026-09-01.md`](generic-owner-physical-typed-parameter-entry-2026-09-01.md)
  records the first role-specific physical entry environment: exact natural
  owner `!T`, independent object-domain semantic-hook entry, direct live-slot
  validation, value/reference substitutions, hostile broad-route isolation,
  and the exact Framework 4.8/.NET 10 production-erased inverse.
- [`generic-owner-physical-control-flow-join-placement-2026-09-01.md`](generic-owner-physical-control-flow-join-placement-2026-09-01.md)
  records the bounded final-value control-flow consumer: identical-carrier
  retention or one unique common recorded natural-interface construction,
  selector-only lineage, fail-closed missing/ambiguous edges, fixed-boundary
  branch emission, hostile `Int`/`String` identity and semantic routing, and the
  exact Framework 4.8/.NET 10 production-erased inverse.
- [`generic-owner-physical-local-storage-consumption-2026-09-01.md`](generic-owner-physical-local-storage-consumption-2026-09-01.md)
  records the first authoritative final-value-to-local-placement consumer: one
  direct equal local owner-bound `C<!n>` carrier, independent live-emitter
  validation, origin-independent source/compiler aliases, hostile star and
  mutable exclusions, semantic-route preservation, and the exact erased inverse.
- [`generic-owner-local-natural-variance-authority-2026-09-01.md`](generic-owner-local-natural-variance-authority-2026-09-01.md)
  records the rehearsal-only repair which fixes one producer-selected CLR
  `GenericParam` variance vector for every admitted local natural TypeDef,
  makes BOUND declaration authority and emission consume the same record,
  restores the existing value/operation route proof, and preserves the exact
  production-erased inverse on Framework 4.8 and .NET 10.
- [`upstream-sync-2026-08-31.md`](upstream-sync-2026-08-31.md)
  records the tested 174-commit upstream integration through `2868cfb88a`,
  preservation of all 672 target patches, the 18-path and reverse-dependency
  audit, mandatory Common test-convention adaptation, the KGP API check, and
  the exact 2,621-test target gate and rollback boundary.
- [`generic-owner-callable-contract-composition-2026-08-31.md`](generic-owner-callable-contract-composition-2026-08-31.md)
  records bounded Stage 7 and physical-library ABI 65: independent semantic
  member role and `VOID`/`DIRECT`/`SPLIT_NULLABLE` result layout, the structural
  `Lookup<K, out V>` contract `!V lookup(!K, out bool)`, producer-recorded direct
  and foreign call routing, exact unboxed value calls, ordinary non-partial C#
  implementation, unchanged Runtime `Map`, and the exact erased inverse.
- [`generic-owner-producer-wide-state-fielddef-authority-2026-08-29.md`](generic-owner-producer-wide-state-fielddef-authority-2026-08-29.md)
  records the bounded Stage 6 producer-wide FieldDef authority on unchanged
  physical-library ABI 64: monotone family/helper/state/output closure, final
  per-field admission, live-module writer reproof, complete BOUND instance-field
  and exact writer/initializer lineage, pre-publication full-field validation,
  ILAsm-gated snapshots and cross-scope selected-FieldDef sealing, inherited no-
  shadow-state proof, the direct-`T` semantic-state output composition repair,
  unchanged schema 21/runtime surface 60, ordinary C# use, and the exact erased
  inverse.
- [`generic-owner-external-class-methoddef-authority-2026-08-29.md`](generic-owner-external-class-methoddef-authority-2026-08-29.md)
  records physical-library ABI 64's standalone `M` seal for one public open
  generic Kotlin class MethodDef, its exact constructed `N` InterfaceImpl,
  producer-final and consumer-PE validation, downstream split-nullable override
  preservation, hostile same-name overload exclusion, implicit CLR dispatch,
  unchanged runtime surface 60 and artifact schema 21, and the exact
  production-erased `H/N/M/J` inverse. The record owns the focused and target-
  wide verification totals.
- [`generic-owner-external-recorded-methoddef-dispatch-2026-08-29.md`](generic-owner-external-recorded-methoddef-dispatch-2026-08-29.md)
  records physical-library ABI 63 and generic-owner artifact schema 21's
  declaration-level self-sealing `N` natural TypeDef/MethodDef publication,
  producer-PE validation, external direct and split-nullable token dispatch,
  optional implementation-level `J` agreement, the closed hostile same-name/
  same-regular-arity overload gate, the root/edge-free first grammar, unchanged
  runtime surface 60 and erased production boundary. The focused candidate and
  production-erased inverse each cover eight parser/profile fixtures; the
  record owns their exact commands and the final target-wide gate evidence.
- [`generic-owner-local-recorded-methoddef-dispatch-2026-08-29.md`](generic-owner-local-recorded-methoddef-dispatch-2026-08-29.md)
  records the bounded same-producer natural-interface `ldtoken` route, mandatory
  two-handle MethodDef binding, ordinary and explicit C# MethodImpl dispatch,
  pre-invocation physical input validation, unchanged physical-library ABI 62,
  compiler-runtime surface 60, unchanged erased production inverse, and the
  still-open ABI-63 external descriptor and interface-overload hostile proof.
- [`upstream-sync-2026-08-27.md`](upstream-sync-2026-08-27.md)
  records the tested 253-commit upstream integration rehearsal from `f9a1706c` through
  `c72fbd7b`, preservation of all 640 target patches, shared-path and semantic
  FIR/IR audit, three bounded post-rebase adaptations, the exact 2,524-test
  target gate, rollback boundary, and the remaining Windows symlink-privilege
  gate for the repository-wide BTA forward suite.
- [`generic-owner-producer-sealed-library-abi-2026-08-27.md`](generic-owner-producer-sealed-library-abi-2026-08-27.md)
  records the rehearsal-only ABI-61 `J` envelope for one producer's actual
  4-TypeDef/6-MethodDef/2-MethodImpl sealed family, canonical role-based
  encoding, conjunctive `C/F/G/H/J` validation, separate-compilation
  publication, the production-erased inverse, and the remaining retained-
  foreign/static-operation route boundary.
- [`generic-owner-methodspec-call-binding-2026-08-27.md`](generic-owner-methodspec-call-binding-2026-08-27.md)
  records physical-library ABI 60 MethodDef-arity authority, independent
  TypeDef/MethodDef call-site substitution, executable natural and semantic
  MethodSpecs, bounded positive-only classifier-input twins, hidden versus
  assembly-local compiler ABI, natural-only C# dispatch, hostile arity/binder/
  variance/open-shape evidence, the production-erased aggregate/inverse, and
  the remaining producer/foreign/global authority gates.
- [`generic-owner-methoddef-genericparam-sealed-emission-2026-08-27.md`](generic-owner-methoddef-genericparam-sealed-emission-2026-08-27.md)
  records complete binder-owned MethodDef GenericParam rows in BOUND and final
  emission, exact raw metadata names, set-semantic constraints, the coherent
  `<R>(R): T` 4/6/2 family, hostile cross-binder and arity evidence, the
  production-empty inverse, and the remaining MethodSpec/call-route boundary.
- [`generic-owner-sealed-emission-signature-family-2026-08-26.md`](generic-owner-sealed-emission-signature-family-2026-08-26.md)
  records the fresh actual-only, non-additive seal for the bounded
  4-TypeDef/6-MethodDef/2-MethodImpl direct-producer family, exact final
  paths/names/CLI flags and coordinates, transactional hostile evidence,
  two-implementation isolation, the production-empty inverse, and the
  remaining producer/foreign/global authority gates.
- [`generic-owner-complete-emission-family-2026-08-26.md`](generic-owner-complete-emission-family-2026-08-26.md)
  records a bounded per-implementation 4-TypeDef/6-MethodDef/2-MethodImpl
  final-emission manifest, two shared-interface implementation families,
  transactional complete-row capture, atomic alias/constraint semantics,
  fail-closed hostile evidence, and the remaining non-additive sealed
  signature boundary.
- [`generic-owner-physical-methoddef-role-alias-2026-08-26.md`](generic-owner-physical-methoddef-role-alias-2026-08-26.md)
  records per-emission MethodDef roles, independently observed physical
  TypeDef aliases, expected-first one-way identity binding, hostile role/alias
  evidence, and the remaining complete-set sealed-emission boundary.
- [`generic-owner-physical-methoddef-emission-comparison-2026-08-26.md`](generic-owner-physical-methoddef-emission-comparison-2026-08-26.md)
  records transactional final-fixpoint MethodDef-header capture, the atomic
  BOUND-to-emission comparison for both producer endpoints, hostile owner and
  signature evidence, production-off silence, and the remaining full-seal
  boundary.
- [`generic-owner-physical-operation-route-shadow-2026-08-26.md`](generic-owner-physical-operation-route-shadow-2026-08-26.md)
  records the opaque BOUND callable family, logical-before-provenance endpoint
  selection, pure exact-view/MethodDef route proof, final-router shadow,
  split-nullable composition model, and remaining sealed-emission boundary.
- [`generic-owner-local-physical-interface-view-2026-08-26.md`](generic-owner-local-physical-interface-view-2026-08-26.md)
  records the selection-site local class InterfaceImpl authority, monotone
  early/bound lineage, natural-interface alias selection, hostile broad and
  open-nullable controls, actual local and emitted-call-operand cross-check,
  and production-inert boundary.
- [`generic-owner-physical-value-local-placement-comparison-2026-08-26.md`](generic-owner-physical-value-local-placement-comparison-2026-08-26.md)
  records the transactional comparison between the two-epoch carrier shadow
  and final ordinary variable-local slots, independent source/compiler probes,
  production-off silence, and the remaining physical-interface-view boundary.
- [`generic-owner-physical-value-pre-remap-alias-2026-08-26.md`](generic-owner-physical-value-pre-remap-alias-2026-08-26.md)
  records the origin-independent pre-remap prediction for immutable exact
  aliases, two-epoch continuity, source/mutable exclusions, and the unchanged
  authoritative emitter recognizer.
- [`generic-owner-physical-value-shadow-first-slice-2026-08-26.md`](generic-owner-physical-value-shadow-first-slice-2026-08-26.md)
  records the first production-inert physical-value shadow, exact-receiver and
  genuinely broad candidate asymmetry, and fail-closed unsupported projection.
- [`generic-owner-physical-supertype-authority-2026-08-25.md`](generic-owner-physical-supertype-authority-2026-08-25.md)
  records complete-set symbolic edge algebra, detached generic-class artifact
  adaptation, and fail-closed absent published-interface/foreign authority.
- [`generic-owner-physical-value-provenance-foundation-2026-08-25.md`](generic-owner-physical-value-provenance-foundation-2026-08-25.md)
  records the shared carrier/provenance vocabulary, declaration-authority
  epochs, produced-versus-storage carrier boundary, and hostile join model.
- [`generic-owner-semantic-body-exact-current-receiver-capture-2026-08-25.md`](generic-owner-semantic-body-exact-current-receiver-capture-2026-08-25.md)
  records exact current-receiver capture by a reified non-ABI generic owner,
  the public-owner/broad-cache boundary, complete profile and frontend proof,
  and the next generated-owner static-initialization binding blocker.
- [`generic-owner-semantic-body-exact-result-chain-2026-08-25.md`](generic-owner-semantic-body-exact-result-chain-2026-08-25.md)
  records exact parameterless result-chain preservation inside a semantic
  body, the truthful MutableEntry-to-Entry Runtime edge, complete profile and
  frontend proof, and the next anonymous-object self-construction blocker.
- [`generic-owner-semantic-body-exact-helper-2026-08-25.md`](generic-owner-semantic-body-exact-helper-2026-08-25.md)
  records the output-only exact self-helper rule, semantic captured callback
  state, removal of an unneeded generated-callable class capability, complete
  profile/frontend proof, and the next semantic Map.Entry property blocker.
- [`generic-owner-inline-widened-temporary-carrier-2026-08-25.md`](generic-owner-inline-widened-temporary-carrier-2026-08-25.md)
  records exact natural carrier preservation through compiler-owned immutable
  inline aliases, the source/mutable semantic-view exclusion, the complete
  profile/frontend proof, and the next semantic-body self-conversion blocker.
- [`reified-generic-interface-split-nullable-result-2026-08-25.md`](reified-generic-interface-split-nullable-result-2026-08-25.md)
  records ABI 59's typed-payload plus `[out] bool&` result convention,
  producer-derived already-nullable payloads, exact/semantic routing,
  ordinary and generated C# authoring, and the deliberately separate Map
  lookup composition gate.
- [`generic-owner-closed-semantic-input-bridge-2026-08-25.md`](generic-owner-closed-semantic-input-bridge-2026-08-25.md)
  records the narrow paired object-input entry for a physically final closed
  generic-interface implementation, its name-independent separate-compilation
  proof, unchanged production inverse, and the next open-owner self-view
  conversion blocker.
- [`generic-owner-erased-bootstrap-interface-edge-2026-08-25.md`](generic-owner-erased-bootstrap-interface-edge-2026-08-25.md)
  records the physical-arity guard for same-module generic-class interface
  reconstruction, its name-independent unit proof, and the next source-product
  semantic-to-natural conversion census.
- [`generic-owner-post-representation-covariant-slots-2026-08-25.md`](generic-owner-post-representation-covariant-slots-2026-08-25.md)
  records physical-MethodDef authority for open-nullable class returns and
  Runtime ExactFunction slots, the two independent ablation proofs, unchanged
  production inverse, and the next illegal open interface edge on an erased
  physical owner.
- [`generic-owner-logical-suspend-superinterface-2026-08-24.md`](generic-owner-logical-suspend-superinterface-2026-08-24.md)
  records the shared Runtime-interface carrier predicate, generic suspend-
  callable capability proof, removed validator duplication, and the next
  post-representation covariant-return bridge boundary.
- [`generic-owner-external-function-authority-2026-08-24.md`](generic-owner-external-function-authority-2026-08-24.md)
  records the fail-closed boundary between local representation planning and
  producer-recorded external generic-owner function facts, its hostile mangler
  regression, and the next external-superinterface materialization boundary.
- [`generic-owner-private-semantic-result-routing-2026-08-24.md`](generic-owner-private-semantic-result-routing-2026-08-24.md)
  records the direct private semantic-hook route for exact same-owner reads,
  its Iterator-shaped ablation proof, unchanged production inverse, and the
  next local-versus-external function-carrier boundary.
- [`runtime-reified-map-2026-08-24.md`](runtime-reified-map-2026-08-24.md)
  records ABI/runtime surface 59's mixed-variance natural `Map<K,out V>`,
  exact value-input sibling, honest open-nullable lookup carrier, typed fields
  and constructed views, ordinary C# authoring, and coherent BK-1 boundary.
- [`runtime-reified-mutable-map-entry-2026-08-24.md`](runtime-reified-mutable-map-entry-2026-08-24.md)
  records ABI/runtime surface 58's invariant multiple-parameter child,
  natural `MutableMap.MutableEntry<K,V>`, typed input/output mutation and
  state, ordinary C# authoring, and the general dual-entry Runtime closure.
- [`runtime-reified-map-entry-2026-08-24.md`](runtime-reified-map-entry-2026-08-24.md)
  records ABI/runtime surface 57's first multiple-owner-parameter family,
  nested natural `Map.Entry<K,V>`, two independent `!n` fields, coherent BK-1
  casts, ordinary C# implementation, and the atomic-switch boundary.
- [`runtime-reified-mutable-list-2026-08-24.md`](runtime-reified-mutable-list-2026-08-24.md)
  records ABI/runtime surface 56's invariant natural `MutableList<T>`,
  position-independent relative inputs, mixed typed mutation grammar, true
  `!T` state, ordinary C# implementation proof, and Runtime epoch correction.
- [`upstream-sync-2026-08-24.md`](upstream-sync-2026-08-24.md)
  records the pinned 461-commit upstream integration, preservation of all 603
  target patches, three bounded semantic adaptations, strict target evidence,
  and the remaining external MSVC lifecycle-generation gate.
- [`runtime-reified-mutable-set-2026-08-23.md`](runtime-reified-mutable-set-2026-08-23.md)
  records ABI/runtime surface 55's invariant natural `MutableSet<T>` diamond,
  dual MutableSet/MutableCollection MethodImpl binding, typed state, and one-
  MethodDef ordinary C# implementation proof.
- [`runtime-reified-mutable-collection-2026-08-23.md`](runtime-reified-mutable-collection-2026-08-23.md)
  records ABI/runtime surface 54's invariant natural `MutableCollection<T>`,
  relative method-generic bulk inputs, value-type widening, true `!T` state,
  and ordinary C# static mutation contract.
- [`runtime-reified-mutable-list-iterator-2026-08-23.md`](runtime-reified-mutable-list-iterator-2026-08-23.md)
  records ABI/runtime surface 53's invariant natural
  `MutableListIterator<T>`, typed input/output slots, operation-local projection
  boundary, `!T` state, and ordinary C# implementation proof.
- [`runtime-reified-mutable-iterator-foundation-2026-08-23.md`](runtime-reified-mutable-iterator-foundation-2026-08-23.md)
  records ABI/runtime surface 52's covariant natural `MutableIterator<T>` and
  `MutableIterable<T>` dependency foundation, declaration-independent Unit
  dispatch, typed `!T` fields, and the ordinary C# natural return bridge.
- [`runtime-reified-list-family-2026-08-23.md`](runtime-reified-list-family-2026-08-23.md)
  records ABI/runtime surface 51's natural/exact/semantic Runtime `List<T>`
  and `ListIterator<T>` closure, both iterator overloads, fixed `-1` candidate
  barriers, true `!T` implementation state, and natural-only C# authoring.
- [`runtime-reified-collection-set-family-2026-08-23.md`](runtime-reified-collection-set-family-2026-08-23.md)
  records ABI/runtime surface 50's natural/exact/semantic Runtime
  `Collection<T>` and `Set<T>` family, real `!T` implementation state,
  natural-only C# authoring, and Kotlin-correct incompatible `containsAll`
  fallback.
- [`runtime-reified-iterator-foundation-2026-08-23.md`](runtime-reified-iterator-foundation-2026-08-23.md)
  records ABI/runtime surface 49's additive Runtime-owned `Iterator<T>` and
  `Iterable<T>` identities, typed nested result/storage, ordinary C# authoring,
  semantic-capability isolation, and the remaining atomic collection boundary.
- [`reified-generic-interface-fixed-barrier-composition-2026-08-22.md`](reified-generic-interface-fixed-barrier-composition-2026-08-22.md)
  records ABI/runtime surface 48's upstream-authorized `contains(T)` barrier,
  mixed reified/erased Runtime parent validation, typed Kotlin and C# paths,
  ordinary precompiled C# fallback, and full-gate evidence.
- [`reified-generic-interface-owner-independent-property-2026-08-22.md`](reified-generic-interface-owner-independent-property-2026-08-22.md)
  records ABI/runtime surface 47's read-only primitive Property role, natural
  CLR Property row, method-only semantic capability, raw C# getter fallback,
  and full-gate evidence.
- [`reified-generic-interface-precompiled-exact-input-2026-08-22.md`](reified-generic-interface-precompiled-exact-input-2026-08-22.md)
  records the typed Kotlin class entry and separately compiled non-partial C#
  exact-input convention, exact overload resolution, missing-member fail-
  closed behavior, ABI/runtime surface 46, and full-gate evidence.
- [`reified-generic-interface-exact-input-materialization-2026-08-22.md`](reified-generic-interface-exact-input-materialization-2026-08-22.md)
  records emission of the natural, invariant exact, and semantic views on one
  object, typed `!T` storage, producer-recorded separate compilation, and the
  generated partial C# authoring path.
- [`reified-generic-interface-exact-input-family-record-2026-08-22.md`](reified-generic-interface-exact-input-family-record-2026-08-22.md)
  records ABI 45's atomic invariant-exact TypeDef identity and broad direct/
  nested member roles, including missing, unsolicited, aliased, and arity-
  mismatched fail-closed validation.
- [`reified-generic-interface-broad-input-composition-2026-08-22.md`](reified-generic-interface-broad-input-composition-2026-08-22.md)
  records the CLR-illegal single covariant input surface and the executable
  three-view exact/read/semantic composition for `Collection<T>`-shaped direct
  and nested broad inputs on Framework 4.8 and .NET 10.
- [`reified-generic-interface-constructed-result-family-2026-08-22.md`](reified-generic-interface-constructed-result-family-2026-08-22.md)
  records the first `Iterable<T>.iterator(): Iterator<T>`-shaped natural CLR
  result, operation-local semantic result/state escape hatch, ordinary C#
  implementation, ABI 44 role, and twelve-lane/full-gate evidence.
- [`reified-generic-interface-owner-independent-query-family-2026-08-22.md`](reified-generic-interface-owner-independent-query-family-2026-08-22.md)
  records the first general multi-member covariant CLR interface, its one
  owner-dependent producer plus owner-independent primitive query grammar,
  ordinary C# implementation, ABI 43 role, and twelve-lane/full-gate evidence.
- [`reified-generic-interface-nullable-owner-relative-method-constraint-2026-08-22.md`](reified-generic-interface-nullable-owner-relative-method-constraint-2026-08-22.md)
  records `<R : T?>` on a reified-interface default, authoritative Kotlin/KLIB
  nullability, deliberately absent CLR `R : T`, nullable-primitive body
  narrowing, and ordinary Kotlin/C# execution with one real method token.
- [`reified-generic-interface-non-null-method-constraint-2026-08-21.md`](reified-generic-interface-non-null-method-constraint-2026-08-21.md)
  records `<R : Any>` on a reified-interface default, authoritative Kotlin/KLIB
  nullability, physically unconstrained CLR slots/helper/overrides, and ordinary
  Kotlin/C# execution across reference and value substitutions.
- [`reified-generic-interface-prepared-external-inherited-owner-relative-implementation-2026-08-21.md`](reified-generic-interface-prepared-external-inherited-owner-relative-implementation-2026-08-21.md)
  records consumer-side reuse of a producer-prepared external owner-relative
  family, exact MethodRef/MethodImpl composition without member copying, and
  ordinary C# override dispatch across Framework 4.8 and .NET 10.
- [`reified-generic-interface-inherited-owner-relative-implementation-2026-08-21.md`](reified-generic-interface-inherited-owner-relative-implementation-2026-08-21.md)
  records a local base-owned `<R : String>(R): String` body first bound to the
  reified interface by open and final derived classes, shared family reuse, and
  ordinary C# subclass dispatch across Framework 4.8 and .NET 10.
- [`reified-generic-interface-open-owner-relative-implementation-2026-08-21.md`](reified-generic-interface-open-owner-relative-implementation-2026-08-21.md)
  records an open non-generic Kotlin `<R : T>(R): T` implementation, its
  protected semantic/probe family, class-owned capability, and ordinary C#
  typed override dispatch across Framework 4.8 and .NET 10.
- [`reified-generic-interface-closed-owner-relative-implementation-2026-08-21.md`](reified-generic-interface-closed-owner-relative-implementation-2026-08-21.md)
  records final non-generic Kotlin implementations of `<R : T>(R): T`, one
  authoritative semantic body, per-slot natural MethodImpls, closed C# entries,
  and reference/value/nullable/dual-root widened dispatch.
- [`reified-generic-interface-owner-relative-method-default-2026-08-21.md`](reified-generic-interface-owner-relative-method-default-2026-08-21.md)
  records the defaulted direct `R : T` method bound, one cross-profile helper
  body, semantic owner closure without method-`R` loss, ordinary C# override
  dispatch, and generic foreign probes across three Kotlin products.
- [`reified-generic-interface-owner-relative-method-constraint-2026-08-21.md`](reified-generic-interface-owner-relative-method-constraint-2026-08-21.md)
  records one abstract direct `R : T` method bound, its deliberate executable
  CLR erasure, preserved actual method `R`, ordinary C# authoring, and widened
  Kotlin dispatch on Framework and .NET 10.
- [`reified-generic-interface-read-only-property-child-2026-08-21.md`](reified-generic-interface-read-only-property-child-2026-08-21.md)
  records one exact covariant read-only property-inheritance edge, independent
  parent/child Property ownership and `!T` fields, ordinary partial C# property
  authoring, and widened dispatch across producer assemblies.
- [`reified-generic-interface-multiple-invariant-properties-2026-08-21.md`](reified-generic-interface-multiple-invariant-properties-2026-08-21.md)
  records the first homogeneous multi-property invariant root, independent CLR
  Property rows and `!T` fields, operation-local projected access, and ordinary
  non-partial C# auto-properties on Framework and .NET 10.
- [`reified-generic-interface-nominal-method-constraints-2026-08-21.md`](reified-generic-interface-nominal-method-constraints-2026-08-21.md)
  records the first nominal-only class-plus-interface method constraint,
  structural admission without a self-bound, exact slot/helper metadata, and
  Kotlin/C# execution on Framework and .NET 10.
- [`reified-generic-interface-multiple-method-constraints-2026-08-21.md`](reified-generic-interface-multiple-method-constraints-2026-08-21.md)
  records the first composed recursive and nominal method constraints, exact
  slot/helper metadata, order-independent C# authoring, and Framework/.NET 10
  dispatch evidence.
- [`reified-generic-interface-constrained-method-generic-default-2026-08-21.md`](reified-generic-interface-constrained-method-generic-default-2026-08-21.md)
  records the first constrained owner-plus-method-generic interface default,
  retained helper/slot constraints, closed Framework MethodImpl binding, and
  ordinary C# default inheritance and override behavior.
- [`reified-generic-interface-constrained-method-generic-2026-08-21.md`](reified-generic-interface-constrained-method-generic-2026-08-21.md)
  records the first retained constructed method constraint on a reified
  generic-interface root, separate-compilation GenericParam remapping, ordinary
  C# authoring, and exact metadata/execution evidence.
- [`reified-generic-interface-abstract-method-generic-2026-08-21.md`](reified-generic-interface-abstract-method-generic-2026-08-21.md)
  records the first abstract owner-plus-method-generic interface root, exact
  natural and widened execution through Kotlin and ordinary C# implementations,
  and paired candidate/inverse execution.
- [`reified-generic-interface-method-generic-default-2026-08-20.md`](reified-generic-interface-method-generic-default-2026-08-20.md)
  records the first owner-plus-method-generic default, retained generic
  natural and semantic MethodDefs, two-parameter portable helper, ordinary C#
  inheritance/override behavior, and paired candidate/inverse execution.
- [`reified-generic-interface-default-property-2026-08-20.md`](reified-generic-interface-default-property-2026-08-20.md)
  records the first covariant read-only generic default Property, Framework
  helper and .NET 10 DIM placement, method-backed semantic adapter repair,
  ordinary C# inheritance/override behavior, and paired candidate/inverse
  execution.
- [`reified-generic-interface-default-hostile-inheritance-2026-08-20.md`](reified-generic-interface-default-hostile-inheritance-2026-08-20.md)
  records the three-product external default, generic Kotlin natural override,
  and ordinary non-partial C# subclass chain, exact/narrowed virtual dispatch,
  unchanged identity, and the absence of generated or C#-authored semantic
  bridges.
- [`reified-generic-interface-defaults-2026-08-20.md`](reified-generic-interface-defaults-2026-08-20.md)
  records the first contravariant generic-interface default, Framework helper
  and .NET 10 DIM placement, natural Kotlin/C# overrides, value-type-narrowed
  semantic dispatch, and paired candidate/inverse verification.
- [`generic-owner-final-call-value-routing-2026-08-20.md`](generic-owner-final-call-value-routing-2026-08-20.md)
  records the final monotone call/value router after all current lowerings
  which can introduce generic operations, generated value-class carrier
  propagation, local and separate hostile proofs, and preservation of
  authoritative early typed routes.
- [`generic-owner-capability-superinterface-closure-2026-08-20.md`](generic-owner-capability-superinterface-closure-2026-08-20.md)
  records ABI 42's producer-owned complete non-generic capability-interface
  closure, local and separate late-`for` hostile proofs, CLR-generic exclusion,
  and the remaining final call/value-router boundary.
- [`generic-interface-published-family-contract-2026-08-20.md`](generic-interface-published-family-contract-2026-08-20.md)
  records ABI 41's typed root/parent/member/capability contract, atomic
  external-index validation, shared local/external admission consumer, and
  paired direct/separate rehearsal matrices.
- [`generic-owner-three-assembly-consumer-chain-2026-08-20.md`](generic-owner-three-assembly-consumer-chain-2026-08-20.md)
  records the property-root/consumer-child/consumer-grandchild family across
  three Kotlin producer DLLs, producer-recorded external admission, exact
  TypeDef/capability ownership, ordinary non-partial C# grandchildren, and
  paired rehearsal/inverse matrices.
- [`generic-owner-invariant-consumer-grandchild-2026-08-20.md`](generic-owner-invariant-consumer-grandchild-2026-08-20.md)
  records the bounded second invariant consumer edge, natural three-level CLR
  hierarchy, one `!T` state field, 2-to-1-to-1 inherited capability chain,
  ordinary non-partial C# grandchildren, transitive authoring context, and
  paired rehearsal/inverse matrices.
- [`generic-owner-invariant-consumer-child-2026-08-20.md`](generic-owner-invariant-consumer-child-2026-08-20.md)
  records one natural invariant property-root/consumer-child edge, inherited
  parent capability and Property metadata, a child-owned `Consume(!T)` slot,
  retained `!T` state, ordinary non-partial C# implementations, the authoring
  composition repair, and paired rehearsal/inverse matrices.
- [`generic-owner-invariant-property-child-2026-08-20.md`](generic-owner-invariant-property-child-2026-08-20.md)
  records one natural `Child<T> : Parent<T>` invariant-property edge, inherited
  rather than copied CLR Property/capability slots, two `!T` implementation
  fields, ordinary non-partial C# child properties, operation-local projected
  access, and paired direct/separate rehearsal and inverse matrices.
- [`generic-owner-invariant-property-cell-2026-08-20.md`](generic-owner-invariant-property-cell-2026-08-20.md)
  records the real CLR `Property<T>` row and `!T` implementation field for the
  exact mutable invariant property family, operation-local projected access,
  ordinary non-partial C# properties, fail-closed broader shapes, and paired
  rehearsal/inverse Framework 4.8/.NET 10 matrices.
- [`generic-owner-mutable-invariant-cell-2026-08-20.md`](generic-owner-mutable-invariant-cell-2026-08-20.md)
  records the natural two-direction invariant `Cell<T>`, retained `!T` state
  and exact/open nesting, operation-local projected read/write dispatch,
  ordinary non-partial C# implementation, Runtime surface 40, and paired
  rehearsal/inverse Framework 4.8/.NET 10 matrices.
- [`generic-owner-invariant-projection-boundary-2026-08-20.md`](generic-owner-invariant-projection-boundary-2026-08-20.md)
  records the construction-local `object` carrier for invariant use-site
  projection, retained `Box<T>`/`!T` and exact/open invariant controls,
  exact-constructor provenance precedence, ordinary non-partial C# identity,
  and paired rehearsal/inverse Framework 4.8/.NET 10 matrices.
- [`generic-owner-invariant-producer-2026-08-20.md`](generic-owner-invariant-producer-2026-08-20.md)
  records the natural declaration-invariant `I<T>` owner, typed open
  `Box<I<!!T>>` control, object-carried star operation boundary, ordinary
  non-partial C# implementation, and paired rehearsal/inverse matrices.
- [`generic-owner-open-nested-construction-boundary-2026-08-20.md`](generic-owner-open-nested-construction-boundary-2026-08-20.md)
  records object-carried open `Box<Producer<T>>`/`Box<Consumer<T>>` callable
  boundaries, retained exact box identity and `!T` state, operation-local
  capability dispatch, the stable `Box<Box<!!T>>` control, and the paired
  rehearsal/inverse Framework 4.8/.NET 10 matrices.
- [`generic-owner-contravariant-construction-stability-2026-08-19.md`](generic-owner-contravariant-construction-stability-2026-08-19.md)
  records construction-local `Consumer<object> -> Consumer<int>` instability,
  retained reference-only CLR contravariance, typed natural interface
  receivers, deterministic separate-consumer subtype proofs, and the
  PSI/LightTree Framework 4.8/.NET 10 matrix.
- [`generic-owner-warning-checked-casts-2026-08-19.md`](generic-owner-warning-checked-casts-2026-08-19.md)
  records BK-1's shared Kotlin-aware predicate for warning-bearing
  parameterized `as`/`as?`, preserved `Int -> Any` covariance despite CLR
  value-type variance, early unrelated mismatch, recursive nested evidence,
  and Runtime surface 39 across Framework 4.8 and .NET 10.
- [`reified-generic-interface-classifier-input-boundary-2026-08-19.md`](reified-generic-interface-classifier-input-boundary-2026-08-19.md)
  records ABI 40's paired compiler-owned object-input MethodDef, retained
  natural Kotlin/C# entry, `CHECK_NOT_NULL` and immutable-local provenance,
  separate MethodRef binding, and delayed typed-result failure on both runtime
  profiles.
- [`reified-generic-interface-classifier-result-boundary-2026-08-19.md`](reified-generic-interface-classifier-result-boundary-2026-08-19.md)
  records ABI 39's distinct capability/object function carriers, a
  classifier-derived safe-cast view crossing separate compilation without a
  false constructed-generic cast, retained natural exact APIs, and the final
  2,287-test proof.
- [`reified-generic-interface-foreign-classifier-2026-08-19.md`](reified-generic-interface-foreign-classifier-2026-08-19.md)
  records declaration-erased `is`/`as?` over ordinary foreign CLR producers,
  same-object safe casts, delayed typed-result failure, classifier-admitted
  multi-construction objects, and Framework 4.8/.NET 10 execution.
- [`reified-generic-interface-ordinary-foreign-producer-2026-08-19.md`](reified-generic-interface-ordinary-foreign-producer-2026-08-19.md)
  records the sibling natural/semantic interface correction, cached unique-
  construction fallback for ordinary precompiled CLR producers, real star
  storage and identity, exception transparency, and ambiguity rejection on
  Framework 4.8 and .NET 10.
- [`generic-owner-foreign-override-deployment-2026-08-19.md`](generic-owner-foreign-override-deployment-2026-08-19.md)
  records the closed self-producing Kotlin base/override plus ordinary C#
  subclass deployment oracle, allocation-free probe audit, and successful JIT,
  ReadyToRun, full-trimming, and real NativeAOT execution.
- [`common-selector-min-max-family-2026-08-18.md`](common-selector-min-max-family-2026-08-18.md)
  records the complete 40-declaration selector min/max supported-classifier
  release, inline loop-entry stack-baseline correction, exact selector and
  floating semantics, direct Kotlin/C# evidence, and the final 2,262-test
  proof.
- [`common-natural-min-max-family-2026-08-18.md`](common-natural-min-max-family-2026-08-18.md)
  records the complete 52-declaration natural min/max supported-classifier
  release, bounded logical-element-derived physical naming, exact floating and
  empty/tie semantics, direct Kotlin/C# evidence, and the final 2,258-test
  proof.
- [`common-all-distinct-family-2026-08-18.md`](common-all-distinct-family-2026-08-18.md)
  records the complete 20-declaration supported-classifier distinct aggregate
  release, carrier-neutral byte-domain helper, absence of public unsigned .NET
  surface, direct Kotlin/C# evidence, and the final 2,254-test proof.
- [`common-all-equal-family-2026-08-18.md`](common-all-equal-family-2026-08-18.md)
  records the complete 20-declaration supported-classifier equality aggregate
  release, exact selector/floating semantics, the `allDistinct` unsigned-helper
  blocker, direct Kotlin/C# evidence, and the final 2,250-test proof.
- [`common-eager-iterable-sequence-consumers-2026-08-18.md`](common-eager-iterable-sequence-consumers-2026-08-18.md)
  records the exact seven-declaration eager Sequence-consumer release, stable
  logical-selector physical names, direct Kotlin/C# evidence, the physical
  Sequence-owner boundary, and the final 2,246-test proof.
- [`common-eager-iterable-windowing-2026-08-18.md`](common-eager-iterable-windowing-2026-08-18.md)
  records the exact four-member eager Iterable window/chunk family, its Common
  sized-list factory prerequisite, both execution routes, and the final
  2,242-test Framework/CoreCLR proof.
- [`generic-owner-octo-tree-ordinary-body-closure-2026-08-17.md`](generic-owner-octo-tree-ordinary-body-closure-2026-08-17.md)
  records schema-13 declaration-independent fields, private non-KLIB
  implementation MethodDefs, the complete OctoTree algorithms, and executable
  state/body evidence on CLR 4 and CoreCLR.
- [`generic-owner-octo-tree-metadata-reflection-product-2026-08-17.md`](generic-owner-octo-tree-metadata-reflection-product-2026-08-17.md)
  records classifier-contextual physical MethodDef normalization, hidden
  capability/private methods, exhaustive raw ECMA-335 table inspection, and
  the missing Leaf/Branch rendering MethodDef repair.
- [`generic-owner-octo-tree-root-product-2026-08-17.md`](generic-owner-octo-tree-root-product-2026-08-17.md)
  records the outer open Tree, semantic object root, compiler-census capability
  calls, typed/non-generic C# surface, inherited base dispatchers, and explicit
  declaration-independent scenario-body boundary.
- [`generic-owner-octo-tree-state-capabilities-2026-08-17.md`](generic-owner-octo-tree-state-capabilities-2026-08-17.md)
  records the Leaf object-boundary read/write and Branch `System.Array` read
  capabilities over the same true generic state, incompatible pre-mutation
  failure, array identity, and private/final interface maps.
- [`generic-owner-octo-tree-strict-capability-2026-08-17.md`](generic-owner-octo-tree-strict-capability-2026-08-17.md)
  records the strict set non-generic interface slots, object-to-`!T` explicit
  dispatchers, most-derived C# override routing, incompatible pre-mutation
  failure, and private/final interface maps.
- [`generic-owner-octo-tree-typed-callables-2026-08-17.md`](generic-owner-octo-tree-typed-callables-2026-08-17.md)
  records the decoded abstract/override Node.set typed family, exact Leaf/
  Branch identity accessors, direct base-reference C# dispatch, and retained
  non-final virtual slots on final TypeDefs.
- [`generic-owner-octo-tree-branch-product-2026-08-17.md`](generic-owner-octo-tree-branch-product-2026-08-17.md)
  records the decoded Branch `Node<T>[]` field, fixed eight-element base-root
  initializer, exact base/this constructors, and direct Framework/.NET C#
  open/closed carrier and execution evidence.
- [`generic-owner-sealed-construction-closure-2026-08-17.md`](generic-owner-sealed-construction-closure-2026-08-17.md)
  records schema 12's abstract Kotlin-sealed base plus
  `FamilyAndAssembly` constructor closure, record-driven true-`T` Leaf product,
  positive C# construction/reflection, and rejected external C# subclass.
- [`generic-owner-complete-octo-tree-family-2026-08-17.md`](generic-owner-complete-octo-tree-family-2026-08-17.md)
  records schema 11's complete logical-keyed recursive OctoTree TypeDef,
  MethodDef, constructor, state/initializer, reflection, and atomic-closure
  graph plus the remaining record-driven candidate/C# product gate.
- [`generic-owner-physical-visibility-dispatch-2026-08-16.md`](generic-owner-physical-visibility-dispatch-2026-08-16.md)
  records schema 10's exact owner TypeDef and member MethodDef visibility/
  dispatch envelope, semantic/capability visibility invariants, and the
  remaining complete OctoTree family gate.
- [`generic-owner-producer-private-state-access-2026-08-16.md`](generic-owner-producer-private-state-access-2026-08-16.md)
  records schema 9's strict separation between logical member bindings and
  exact producer-private state MethodDefs, the semantic-object access
  invariant, canonical codec evidence, and the remaining complete OctoTree
  product gate.
- [`generic-owner-physical-state-initializers-2026-08-16.md`](generic-owner-physical-state-initializers-2026-08-16.md)
  records schema 8's fixed zeroed SZ-array initializer, exact constructor
  roots, typed state access/init composition, canonical codec evidence, and
  the remaining complete OctoTree product gate.
- [`generic-owner-state-initializer-recipes-2026-08-16.md`](generic-owner-state-initializer-recipes-2026-08-16.md)
  records compiler-derived fixed-vector recipes, separate-producer carrier
  binding, and the opposing unchecked ArrayCopy fail-closed oracle.
- [`generic-owner-path-unbound-member-signatures-2026-08-16.md`](generic-owner-path-unbound-member-signatures-2026-08-16.md)
  records the shared state/callable prototype type tree, atomic logical-key to
  TypeDef binding, recursive OctoTree getter evidence, and the remaining full
  physical-family/C# product gate.
- [`generic-owner-nonexact-call-carriers-2026-08-15.md`](generic-owner-nonexact-call-carriers-2026-08-15.md)
  records truthful object/System.Array MethodDef carriers for open-nullable and
  projected generic positions, retained exact GenericParam cases, hostile C#
  override evidence, and the then-open constructed-member binding gate.
- [`generic-owner-structural-state-carrier-2026-08-15.md`](generic-owner-structural-state-carrier-2026-08-15.md)
  records the path-unbound exact state-carrier grammar, logical producer-key
  binding, open-nullable rejection, separate OctoTree producer/consumer
  evidence, and the still-open serialized candidate/C# product gate.
- [`projected-generic-array-join-fast-path-2026-08-15.md`](projected-generic-array-join-fast-path-2026-08-15.md)
  records the retained projected `System.Array` ABI, compatible `T[]` read
  path, Framework/.NET 10 causal boxing evidence, and strict widened-value
  fallback boundary.
- [`generic-owner-octo-tree-application-census-2026-08-15.md`](generic-owner-octo-tree-application-census-2026-08-15.md)
  records the second exact repository-application census, full array joining,
  declaration-stable erased-owner classifier vectors, mixed exact/capability
  routes, and the still-open production-owner and paired-product gates.
- [`generic-owner-array-copy-application-census-2026-08-15.md`](generic-owner-array-copy-application-census-2026-08-15.md)
  records the first exact repository-application route/state distribution,
  open/erased generic-array copying correction, four-lane equality, and the
  still-open candidate/C# and representative-breadth gates.
- [`generic-array-fill-specialization-2026-08-15.md`](generic-array-fill-specialization-2026-08-15.md)
  records the exact-vector fill correction, cross-profile route measurement,
  retained erased-owner boundary, and final strict gate.
- [`generic-resized-array-copy-2026-08-15.md`](generic-resized-array-copy-2026-08-15.md)
  records the closed nullable-vector correction, bounded open projected copy
  carrier, FIR bottom-capture read boundary, and final strict gate.
- [`review-2026-07-17.md`](review-2026-07-17.md) is the consolidated review of
  branch commit `8dd89907d`.
- [`upstream-sync-2026-07-28.md`](upstream-sync-2026-07-28.md) and
  [`upstream-sync-2026-07-30.md`](upstream-sync-2026-07-30.md) preserve dated
  rebase-impact evidence.
- [`upstream-impact-2026-08-03.md`](upstream-impact-2026-08-03.md) records the
  exact 179-commit pending range, virtual-merge evidence, screened directions,
  and durable Kotlin/.NET consequences before a later rebase. Git and the exact
  range own its reproducible per-commit ledger.
- [`upstream-impact-2026-08-07.md`](upstream-impact-2026-08-07.md) records the
  exact 195-commit reviewed and integrated range, conflict-free three-path
  virtual merge, contract-level reverse-dependency and architecture audit,
  normalized compiler/export/test directions, strict post-rebase gate, and
  pure-rebase evidence. Git and the exact range own its exhaustive per-commit
  ledger.
- [`upstream-impact-2026-08-11.md`](upstream-impact-2026-08-11.md) records the
  exact 170-commit range through `d78e4a4c14`, the one non-linking-deserializer
  conflict, contract-level reverse-dependency audit, and normalized inline,
  export, IDE/KLIB, BTA, Gradle, and test implications. Git and the exact range
  own its exhaustive per-commit ledger.
- [`common-io-source-partition.md`](common-io-source-partition.md) preserves a
  completed programme whose durable rules now live in the runtime/stdlib ADR.
- [`superseded-hybrid-exception-identity.md`](superseded-hybrid-exception-identity.md)
  preserves the exception design replaced by the classified-carrier model.
- [`generic-owner-history-audit-2026-08-12.md`](generic-owner-history-audit-2026-08-12.md)
  audits the removed typed-owner/canonical-capability implementation, its exact
  widened-candidate bridge failure, reusable infrastructure, and the new
  hardest-model-first constraints.
- [`generic-owner-runtime-compilation-probe-2026-08-12.md`](generic-owner-runtime-compilation-probe-2026-08-12.md)
  records the bounded JIT/ReadyToRun/trimming success and the explicitly
  incomplete NativeAOT open-nullable construction probe.
- [`generic-owner-measurement-corpus-2026-08-13.md`](generic-owner-measurement-corpus-2026-08-13.md)
  records the fingerprinted record-driven hostile corpus, its reproducible
  JIT/ReadyToRun/full-trimming baseline, and the still-open NativeAOT link/run.
- [`generic-owner-producer-classification-catalog-2026-08-13.md`](generic-owner-producer-classification-catalog-2026-08-13.md)
  records schema 7's complete producer candidate catalog, explicit
  metadata-fixed erased-only classification, and fail-closed family join.
- [`generic-owner-native-aot-measurement-2026-08-13.md`](generic-owner-native-aot-measurement-2026-08-13.md)
  records workload version 2's race-free working-set handshake, explicit
  signed MSVC provenance, and the successful four-mode NativeAOT link/run.
- [`generic-owner-application-corpus-2026-08-13.md`](generic-owner-application-corpus-2026-08-13.md)
  records the closed paired production-erased/candidate application products,
  direct C# erased-owner surface, two-profile execution, and strict
  cross-frontend reproducibility boundary.
- [`generic-owner-paired-application-measurement-2026-08-14.md`](generic-owner-paired-application-measurement-2026-08-14.md)
  records the independent Framework CLR 4 and .NET 10 erased-versus-candidate
  measurements, the current semantic-routing cost, and the direct-interface
  reimplementation repair required by full trimming.
- [`generic-owner-route-attribution-2026-08-14.md`](generic-owner-route-attribution-2026-08-14.md)
  isolates typed entry, semantic capability, object-state, construction,
  array, method-generic, compatible override, and hostile failure costs across
  Framework 4.8 and all four .NET 10 deployment modes.
- [`generic-owner-typed-storage-attribution-2026-08-14.md`](generic-owner-typed-storage-attribution-2026-08-14.md)
  proves one compiler-derived `!T` field and exact identity access, retains the
  strict one-state capability boundary, and attributes scalar, struct, and
  nullable costs across Framework 4.8 and all four .NET 10 deployment modes.
- [`generic-owner-call-route-census-2026-08-14.md`](generic-owner-call-route-census-2026-08-14.md)
  records the production-inert fixed-point receiver provenance analysis and
  its 40-site hostile producer census.
- [`generic-owner-call-route-manifest-2026-08-14.md`](generic-owner-call-route-manifest-2026-08-14.md)
  records the versioned diagnostic-name-free application route artifact,
  closed bundle schema 2, and cross-frontend/profile reproducibility proof.
- [`generic-owner-call-route-trace-2026-08-14.md`](generic-owner-call-route-trace-2026-08-14.md)
  records exact-IR-call instrumentation, the closed compiler-indexed dynamic
  route profile across Framework CLR 4/net10 and both frontends, and unchanged
  normal-product byte evidence.
- [`generic-owner-call-route-counter-flush-2026-08-15.md`](generic-owner-call-route-counter-flush-2026-08-15.md)
  replaces per-call console transport with exact-sized thread-safe primitive
  counters and one final flush, retaining the exact four-lane route oracle and
  unchanged normal-product bytes.
- [`common-sequence-builder-closure-2026-08-15.md`](common-sequence-builder-closure-2026-08-15.md)
  records the exact Common builder/window closure, its bounded local-array and
  runtime-fill dependencies, two strict-gate regressions found and repaired,
  and the final 2,216-test Framework/CoreCLR proof.
- [`upstream-sync-2026-08-24-followup.md`](upstream-sync-2026-08-24-followup.md)
  records the five-commit follow-up rebase, complete 610-patch preservation
  audit, target-owned JDK 8 test-policy adaptation, and post-rebase gates.

Line references inside a snapshot resolve against the commit named by that
snapshot, not necessarily against the current tree. Do not rewrite snapshots
to make them look current. If later evidence changes a conclusion, record the
new evidence in an active programme or ADR and keep the old snapshot intact.
