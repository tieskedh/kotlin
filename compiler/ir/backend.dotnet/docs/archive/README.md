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

Line references inside a snapshot resolve against the commit named by that
snapshot, not necessarily against the current tree. Do not rewrite snapshots
to make them look current. If later evidence changes a conclusion, record the
new evidence in an active programme or ADR and keep the old snapshot intact.
