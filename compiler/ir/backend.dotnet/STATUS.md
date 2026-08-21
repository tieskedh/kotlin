# Kotlin/.NET development status

Read [`AGENTS.md`](AGENTS.md) before changing Kotlin/.NET code. It is the
self-contained bootstrap contract; this file owns only current branch,
verification, and work state.

## Current branch

- Branch: `dotnet`
- Upstream base: exact reviewed upstream commit `d78e4a4c14`
- Last integration checkpoint: the complete 170-commit range after
  `0e8c5f3f53` was audited by subject, paths, shared contract, and target-owned
  reverse dependencies, then integrated by rebase on 2026-08-11. All 462
  target commits remain present: range-diff classified 460 patches as
  identical and exactly two inline patches as context-adjusted for upstream's
  shared `IrErrorModuleFragment`; no target patch was added or removed. The
  nine shared paths, architectural directions, stat-cache-only IL false
  positives, and post-rebase verification are recorded in
  [`docs/archive/upstream-impact-2026-08-11.md`](docs/archive/upstream-impact-2026-08-11.md).
- Cross-cutting semantic boundary: Kotlin remains authoritative when CLR RTTI
  is stronger, except for a deliberately accepted pre-ABI entry in the
  breaking-change ledger. Warnings, suppression, `@UnsafeVariance`, and
  physical reification are not general semantic waivers. BK-1 gives one narrow
  exception: warning-bearing parameterized `as` and `as?` operations on an
  admitted CLR-generic owner use the same recursive Kotlin-aware argument-
  subtyping predicate. Covariant `Producer<Int> -> Producer<Any>` succeeds in
  both forms and preserves identity even though CLR value-type variance cannot
  name `Producer<object>`; unrelated `Producer<Int> -> Producer<String>`
  throws or returns null respectively. `is Producer<*>`, ordinary variance,
  stars, and projections remain classifier/semantic operations. See
  [`docs/decisions/kotlin-semantic-authority-and-platform-freedom.md`](docs/decisions/kotlin-semantic-authority-and-platform-freedom.md)
  and
  [`docs/decisions/breaking-kotlin-changes.md`](docs/decisions/breaking-kotlin-changes.md).
- Latest generic-interface reopening proof: the test-only generic-owner epoch
  now emits the first structural Kotlin-owned CLR-generic interface family and
  propagates it across producer boundaries, independent interface
  intersections, and a child-owned member. Assembly A may declare two admitted
  producer roots, while a child in A or B combines both and may add one
  `T`-result member:
  `Child<out T> : Primary<T>, Secondary<T> { fun child(): T }`. Every logical
  interface remains a natural CLR generic interface, and that natural
  interface no longer inherits the compiler semantic capability. A member-free
  intersection has one memberless non-generic capability alias. A
  member-declaring child has one capability which inherits both root
  capabilities and owns exactly its new object-result semantic slot; it does
  not copy inherited slots, bodies, fields, or state. ABI 38 publishes the
  child capability's assembly-qualified physical identity, and library
  selection fails closed before emission if a named self-describing capability
  assembly is absent.

  The structural root rule remains unchanged.
  Any public top-level `Producer<out T>` shape with one abstract no-input
  `T`-result member becomes a natural covariant CLR `Producer<T>` plus one
  non-generic declaration-semantic capability. Exact final substitutions use
  the natural interface; stars, projections, type-parameter/open arguments,
  and widened value-type views use a broad `object` carrier. Kotlin and
  generator-authored objects take the semantic capability fast path from that
  carrier. An ordinary precompiled/non-partial CLR implementation may supply
  only the natural `Producer<T>` interface: the fallback resolves and caches
  exactly one closed construction by runtime type/open owner/member, invokes
  it without a wrapper, and preserves the original thrown exception. Zero
  constructions fail as an invalid cast and multiple distinct constructions
  fail deterministically as ambiguous. The ordinary foreign branch executes
  through PSI and LightTree on both Framework 4.8 and .NET 10: four focused
  tests and zero failures, errors, or skips. Classifier operations on that
  ordinary foreign family remain declaration-erased without becoming
  capability-only: `is Producer<*>`, `!is`, nullable `is`, and smart-cast
  member use accept the capability or any natural closed construction.
  Warning-bearing parameterized `as` and `as?` now additionally compare the
  discovered construction with the requested one using Kotlin declaration-
  site variance recursively. A natural `Producer<int>` therefore succeeds as
  `Producer<Any>` in both forms with identical object identity, but fails as
  `Producer<String>` at the cast boundary. Nested
  `Producer<Producer<Int>> -> Producer<Producer<Any>>` succeeds by the same
  rule. Multiple constructions may still pass a star/classifier check, while
  a capability-free foreign object remains ambiguous at a broad member call.
  The runtime caches each runtime type's interface vector and never fabricates
  a constructed CLR view for a Kotlin-compatible value-type widening. Runtime
  surface 39 owns the new public compiler-ABI compatibility predicate.
  The first exact-looking callable boundary is now closed as well. A function
  whose authoritative result is a classifier-derived `as? Producer<String>`
  keeps that logical KLIB result but publishes CLR `object`; an adjacent
  ordinary exact identity function still publishes and consumes the natural
  `Producer<string>`. ABI 39 generalizes the existing generic-owner function-
  carrier record so every selected return or parameter slot identifies either
  the semantic capability or `object`, rather than treating both as one kind
  of erasure. A separate Kotlin consumer propagates the producer-recorded
  object provenance through FIR's synthesized safe-call temporary, preserves
  identity, and dispatches an ordinary compatible precompiled producer exactly
  once. Incompatible warning-bearing views now fail at the cast boundary. C#
  reflection verifies both public signatures. ABI 40 closes the matching exact-
  looking input
  boundary without erasing that natural API. A final ordinary function keeps
  its `Producer<string>` MethodDef and direct body for exact Kotlin and C#
  calls, while one compiler-owned alternate MethodDef accepts `object` only
  when the argument carries classifier-derived foreign provenance. The
  producer records that MethodDef name and its object parameter index; a
  separate consumer reconstructs the exact MethodRef rather than inferring it.
  A successful safe-cast result survives FIR's `CHECK_NOT_NULL` call and an
  immutable local without losing provenance, reaches the alternate entry with
  identical object identity, and invokes a plain compatible foreign producer
  once. Arbitrary control-flow returns, classifier-derived fields, non-final/
  open inputs, and broader input graphs remain separate gates. The current full
  target aggregate covers
  190 XML suites and 2,287 tests with zero failures, errors, or skips: the 187
  FIR suites/2,155 tests and two integration suites/126 tests were freshly
  written, while the unchanged six-test `dotnet.ir` root remained up-to-date.
  A member-free local or external
  `Child<out T> : Producer<T>` closes at a
  fixpoint, remains a real CLR `Child<T>`, and reuses the inherited capability
  rather than adding another semantic representation. Multiple independent
  capabilities form the memberless alias above. If the child adds the admitted
  no-input `T` result, it receives one child capability even over a single
  parent because that declaration needs its own authoritative semantic slot.
  A later consumer rebuilds the capability graph from logical KLIB supertypes
  plus producer-recorded physical capability identities, without a schema
  addition or generated-name inference. Same-module and separate-compilation
  Kotlin implementations preserve exact calls, all widened root and child
  calls, boxing, `!T` implementation state, and identity on Framework 4.8 and
  .NET 10. The producer's public versioned manifest records only the child's
  declared member, while inherited contracts stay with their roots. The
  supported Roslyn generator therefore lets partial C# implementations author
  only their natural typed members; Kotlin widened dispatch reaches every body
  without source-visible compiler ABI. Admission contains no stdlib or
  declaration-name switch.
  The first independent input-bearing root is now complete under the same
  rule. A public top-level `Consumer<in T>` with one abstract `consume(T): Unit`
  member becomes a natural contravariant CLR `Consumer<T>` plus one non-generic
  capability whose slot is `void consume(object)`. Producer-proven exact
  implementations and locals retain the natural interface, their state remains
  one `!T` field, and ordinary reference contravariance such as
  `Consumer<object> -> Consumer<string>` stays a direct CLR conversion. A
  value-type construction cannot use CLR variance, so the legal Kotlin view
  `Consumer<Any?> -> Consumer<Int>` retains the same object through the
  capability and boxes only the call argument. This selection follows value
  provenance: a later semantic pass cannot degrade an exact `Consumer<int>`
  route merely because `Int` has another legal declaration-semantic source.
  Same-module and separately compiled Kotlin implementations, generated
  partial C# implementations of both `Consumer<object>` and `Consumer<int>`,
  identity, and semantic dispatch execute on both profiles. The portable C#
  source generator remains required for the hidden input adapter;
  precompiled, non-partial, and other-language consumer implementors are not
  claimed.
  Reified generic-interface slots also re-enter the ordinary covariant-return
  lowering: an inherited class body receives a typed MethodImpl only when its
  CLR return carrier actually differs, while exact signatures remain direct.
  Production remains on the accepted erased interface ABI. Defaults,
  read-only/open-nullable and broader/mixed property families, overloads,
  inheritance, mixed/multiple type parameters, Runtime/Stdlib closure, other
  CLR languages, ordinary foreign implementations outside the admitted
  producer and exact invariant families, classifier-derived fields/input
  boundaries, and deployment modes remain gates. The focused rehearsal and
  production-inverse matrix covers PSI
  and LightTree on .NET 10 and Framework 4.8: eight tests and zero failures,
  errors, or skips. The final inverse target aggregate covers 190 XML suites
  and 2,287 tests with zero failures, errors, or skips: 187 freshly written FIR
  suites/2,155 tests, two freshly written integration suites/126 tests, and the
  unchanged six-test `dotnet.ir` root. A deliberate whole-corpus rehearsal
  audit is not
  an all-green snapshot gate while the wider class-owner epoch remains
  incomplete; it exposed the covariant composition gap above, while its
  remaining failures retain production IL snapshots or already recorded
  class-owner/Stdlib gates. See
  [`docs/decisions/draft-adr-reified-generic-interface-owner.md`](docs/decisions/draft-adr-reified-generic-interface-owner.md).
  External-child and intersection ABI/IL evidence is archived in
  [`docs/archive/reified-generic-interface-external-child-2026-08-19.md`](docs/archive/reified-generic-interface-external-child-2026-08-19.md)
  and
  [`docs/archive/reified-generic-interface-intersection-2026-08-19.md`](docs/archive/reified-generic-interface-intersection-2026-08-19.md).
  The child-owned member proof is archived in
  [`docs/archive/reified-generic-interface-member-child-2026-08-19.md`](docs/archive/reified-generic-interface-member-child-2026-08-19.md).
  The ordinary foreign producer evidence is archived in
  [`docs/archive/reified-generic-interface-ordinary-foreign-producer-2026-08-19.md`](docs/archive/reified-generic-interface-ordinary-foreign-producer-2026-08-19.md).
  The ordinary foreign classifier evidence is archived in
  [`docs/archive/reified-generic-interface-foreign-classifier-2026-08-19.md`](docs/archive/reified-generic-interface-foreign-classifier-2026-08-19.md).
  The separate callable-result carrier evidence is archived in
  [`docs/archive/reified-generic-interface-classifier-result-boundary-2026-08-19.md`](docs/archive/reified-generic-interface-classifier-result-boundary-2026-08-19.md).
  The paired callable-input evidence is archived in
  [`docs/archive/reified-generic-interface-classifier-input-boundary-2026-08-19.md`](docs/archive/reified-generic-interface-classifier-input-boundary-2026-08-19.md).
- The first nested-construction storage gate is now closed without changing
  the open owner's field model. `RehearsalNestedBox<T>` still contains exactly
  one physical `!T` field. Exact `Box<Int>`, `Box<String>`, and
  `Box<Producer<String>>` constructions therefore retain `int`, `string`, and
  `Producer<string>` arguments. Only the concrete logical
  `Box<Producer<Any?>>` construction selects `Box<object>`: that slot may hold
  the same Kotlin-widened `Producer<int>`, a `Producer<string>`, or an ordinary
  capability-free CLR producer, while no truthful `Producer<object>` contains
  all three. Reads remain `object` through identity/null consumers and enter
  the generic-owner dispatcher only when a producer operation is requested;
  they are never narrowed to a fabricated constructed interface. Same-module
  and separate-KLIB mutation, dispatch, identity, open-field reflection, and
  C# factory signatures execute under both frontends on Framework 4.8 and
  .NET 10. The rule is structural and contains no `Box`, `List`, or `Producer`
  name switch. The construction-stability predicate now also finds a proper
  supported CLR-value Kotlin subtype beneath a non-universal reference
  argument. Thus `Producer<Int> -> Producer<Comparable<Int>>` makes only the
  enclosing construction `Box<object>`, while the reference-only
  `Producer<Cat> -> Producer<Animal>` keeps the exact
  `Box<Producer<Animal>>` construction. The predicate uses Kotlin IR subtyping
  over all eight admitted signed Common scalar carriers and is cached per
  logical argument type; it contains no `Number` or `Comparable` name check.
  The dual contravariant construction is now closed as well. A physical
  `Consumer<object>` may be the valid Kotlin view `Consumer<Int>`, but CLR
  variance cannot convert it to `Consumer<int>`; only the enclosing
  `Box<Consumer<Int>>` therefore selects `Box<object>`. A reference-only
  `Consumer<Animal> -> Consumer<Cat>` view remains the exact
  `Box<Consumer<Cat>>` construction. Natural interface MethodDefs retain their
  typed `I<T>` receiver, and only the selected input-bearing operation crosses
  the sibling capability. Every rehearsal producer and separate consumer now
  runs the cached proper-value-subtype predicate even when the consumer owns no
  local capability, so their physical signatures cannot diverge. Value classes,
  mixed/multi-parameter owners, and nested open arguments remain separate
  gates. Evidence is archived in
  [`docs/archive/generic-owner-nested-construction-carrier-2026-08-19.md`](docs/archive/generic-owner-nested-construction-carrier-2026-08-19.md).
  The proper-value-subtype extension is archived in
  [`docs/archive/generic-owner-value-subtype-construction-stability-2026-08-19.md`](docs/archive/generic-owner-value-subtype-construction-stability-2026-08-19.md).
  The contravariant extension is archived in
  [`docs/archive/generic-owner-contravariant-construction-stability-2026-08-19.md`](docs/archive/generic-owner-contravariant-construction-stability-2026-08-19.md).
  The nested open-argument gate is now closed without converting the open
  owner or stable nesting to object state. `Producer<T>` and `Consumer<T>` use
  `object` at an open callable boundary because no single natural `I<!!T>`
  construction admits every later value/reference substitution. An open
  factory constructs a concrete `Box<object>` but publishes the result as
  `object`; an identity boundary over `Box<Producer<T>>` or
  `Box<Consumer<T>>` is physically `object -> object`, so the same MethodDef
  accepts both an existing exact `Box<Producer<string>>` and a semantic
  `Box<object>`. Both retain identity and mutation on their original physical
  box. Member use crosses the existing class/interface capability only from
  that proven object carrier. Closed exact and reference-only constructions
  stay typed, `RehearsalNestedBox<T>` still has one `!T` field, and the stable
  negative control `Box<Box<T>>` remains `Box<Box<!!T>>`. Same-module and
  separate-KLIB execution plus C# metadata/runtime checks pass under PSI and
  LightTree on Framework 4.8 and .NET 10; the epoch-off inverse passes the same
  eight-test matrix. Invariant, mixed, multi-parameter, and value-class owners
  remain separate gates. Evidence is archived in
  [`docs/archive/generic-owner-open-nested-construction-boundary-2026-08-20.md`](docs/archive/generic-owner-open-nested-construction-boundary-2026-08-20.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 FIR suites/2,155 tests
  and two integration suites/126 tests were freshly written, while the
  unchanged six-test `dotnet.ir` model root remained up-to-date.
- The first declaration-invariant generic-interface owner is now reified in
  the test-only epoch. A public top-level `InvariantProducer<T>` with one
  abstract no-input `T` result publishes natural invariant CLR `I<T>` metadata
  plus the existing non-generic object-result operation capability. Exact and
  open substitutions stay natural: the hostile control
  `<T>(Box<InvariantProducer<T>>) -> Box<InvariantProducer<T>>` remains
  `Box<InvariantProducer<!!T>> -> Box<InvariantProducer<!!T>>`, retains the
  original box/producer identities, and does not enter the open-variant object
  boundary. A public `InvariantProducer<*>` parameter is physically `object`;
  its read selects the capability or exactly one ordinary natural CLR
  construction. Both direct and separate-KLIB C# probes use non-partial
  classes which implement only `InvariantProducer<string>`. The Roslyn
  authoring tool now skips an implementation whose Kotlin contracts are all
  admitted invariant owners, while variant contracts retain their generated-
  capability `partial` requirement. PSI/LightTree execution on Framework 4.8
  and .NET 10 passes the eight-test rehearsal matrix and the same eight-test
  epoch-off inverse with zero failures, errors, or skips. Mutable/multi-member,
  mixed, multi-parameter, and value-class interface owners remain separate
  gates. Evidence is archived in
  [`docs/archive/generic-owner-invariant-producer-2026-08-20.md`](docs/archive/generic-owner-invariant-producer-2026-08-20.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 FIR suites/2,155 tests
  and two integration suites/126 tests were freshly written, while the
  unchanged six-test `dotnet.ir` model root remained up-to-date.
- The declaration-invariant producer now also has a closed use-site-projection
  boundary. `InvariantProducer<String>` may be viewed in Kotlin as
  `InvariantProducer<out Any?>`, but CLR invariance cannot turn the same object
  into `InvariantProducer<object>`. A public projected parameter/result is
  consequently `object` and selects the semantic capability or exactly one
  natural foreign construction only when `produceInvariant()` is called.
  This does not erase the interface family or the enclosing generic class.
  Constructing `Box<InvariantProducer<out Any?>>` selects the concrete
  `Box<object>` construction only; `Box<T>` retains its single `!T` field, and
  exact/open `Box<InvariantProducer<T>>` remains
  `Box<InvariantProducer<!!T>>`. The producer-origin planner now treats an
  exact constructor's complete logical type as evidence for the already-
  selected physical construction, so a later general semantic pass cannot
  replace that `Box<object>` result with the whole non-generic `Box`
  capability. Same-module and separate-KLIB Kotlin mutation store both String
  and Int invariant producers through the projected box without a wrapper or
  second identity. Ordinary non-partial C# implementations cross both the
  projected operation and nested storage boundary with the same identity.
  PSI/LightTree execution on Framework 4.8 and .NET 10 passes the eight-test
  rehearsal matrix and the same eight-test epoch-off inverse with zero
  failures, errors, or skips. Mutable/multi-member invariant owners remain the
  next separate gate. Evidence is archived in
  [`docs/archive/generic-owner-invariant-projection-boundary-2026-08-20.md`](docs/archive/generic-owner-invariant-projection-boundary-2026-08-20.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 FIR suites/2,155 tests
  and two integration suites/126 tests were freshly written, while the
  unchanged six-test `dotnet.ir` model root remained up-to-date.
- The first broader declaration-invariant family is now closed in the test-
  only epoch. `InvariantCell<T>` owns one abstract `T` result and one abstract
  `T` input/`Unit` member on the same natural invariant CLR interface. Exact
  and open calls remain ordinary `InvariantCell<!!T>` calls, the Kotlin value
  implementation retains one physical `!T` field, and
  `Box<InvariantCell<!!T>>` remains fully typed. Output/star reads and input
  projections select the semantic capability only at the operation. An
  ordinary non-partial C# `InvariantCell<string>` or `InvariantCell<object>`
  implements only the two natural members; a cached unique-construction
  fallback invokes either member when a projected Kotlin boundary receives
  that foreign object. The
  Roslyn authoring tool skips exactly this structurally admitted one-producer/
  one-consumer invariant bundle, while all broader shapes remain fail-closed.
  Materializing `InvariantCell<out Any?>` in a box selects `Box<object>` for
  that construction only; it does not erase the interface family, the box
  TypeDef, or unrelated fields. Runtime surface 40 adds the generalized
  argument-bearing foreign-member dispatcher while retaining the surface-39
  producer entry. PSI/LightTree execution on Framework 4.8 and .NET 10 passes
  the eight-test rehearsal matrix and the same eight-test epoch-off inverse
  with zero failures, errors, or skips. Defaults, overloads, inheritance around
  this broader family, mixed/multiple parameters, and value-class substitutions
  remain separate gates. Evidence is archived in
  [`docs/archive/generic-owner-mutable-invariant-cell-2026-08-20.md`](docs/archive/generic-owner-mutable-invariant-cell-2026-08-20.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 FIR suites/2,155 tests
  and two integration suites/126 tests were freshly written, while the
  unchanged six-test `dotnet.ir` model root remained up-to-date.
- The property-syntax equivalent of that mutable invariant family is now
  closed in the test-only epoch. A public top-level
  `InvariantPropertyCell<T> { var value: T }` emits one natural invariant CLR
  interface with a real mutable `Property<T>` row, typed getter/setter slots,
  and a Kotlin implementation whose backing field remains physical `!T`.
  Exact/open access and `Box<InvariantPropertyCell<!!T>>` stay typed; star/out
  reads and in writes cross the semantic boundary only for that operation, and
  only a materialized projected box becomes `Box<object>`. Ordinary non-partial
  C# string/object implementations use one normal auto-property and need no
  compiler capability. The manifest and authoring tool recognize exactly the
  paired getter/setter property shape. Read-only `val T`, open-nullable `var
  T?`, and a property mixed with another member remain fail-closed and erased.
  Missing/ambiguous foreign constructions and accessor exceptions retain the
  surface-40 dispatcher behavior. PSI/LightTree execution on Framework 4.8 and
  .NET 10 passes the eight-test rehearsal matrix and the same eight-test epoch-
  off inverse with zero failures, errors, or skips. The homogeneous multiple-
  property root extension is recorded below; defaults, inheritance around that
  broader root, mixed members/parameters, constraints, and value-class
  substitutions remain separate gates. Evidence is archived in
  [`docs/archive/generic-owner-invariant-property-cell-2026-08-20.md`](docs/archive/generic-owner-invariant-property-cell-2026-08-20.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 FIR suites/2,155 tests
  and two integration suites/126 tests were freshly written, while the
  unchanged six-test `dotnet.ir` model root remained up-to-date.
- One exact invariant-property inheritance edge is now closed in the test-only
  epoch. `Child<T> : Parent<T>` remains a natural CLR generic interface edge;
  the child owns only its new mutable `Property<T>` row and inherits the parent
  Property row rather than copying it. Its semantic capability likewise owns
  only the child getter/setter slots and inherits the parent capability. A
  Kotlin implementation stores the two properties in two physical `!T`
  fields. Exact/open child access and `Box<Child<!!T>>` stay typed; projected
  parent/child reads and writes cross only their operation boundary, and only a
  materialized projected box becomes `Box<object>`. Ordinary non-partial C#
  `Child<string>` and `Child<object>` implementations define two normal auto-
  properties and no compiler ABI. The separate producer manifest publishes
  only the child-owned accessors. FIR inherited fake `IrProperty` declarations
  are excluded by accessor provenance, so they neither block admission nor
  become copied ABI. PSI/LightTree execution on Framework 4.8 and .NET 10
  passes the eight-test rehearsal matrix and the same epoch-off inverse with
  zero failures, errors, or skips. Deeper, multi-parent, changed-argument,
  mixed/multi-property, nullable, constrained, and multi-parameter inheritance
  remain fail-closed. Evidence is archived in
  [`docs/archive/generic-owner-invariant-property-child-2026-08-20.md`](docs/archive/generic-owner-invariant-property-child-2026-08-20.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 FIR suites/2,155 tests
  and two integration suites/126 tests were freshly written, while the
  unchanged six-test `dotnet.ir` model root remained up-to-date.
- One input-bearing invariant child is now closed above that exact property
  root. `ConsumerChild<T> : PropertyCell<T>` remains a natural CLR generic
  hierarchy with one child-owned `Consume(!T)` MethodDef and an inherited
  mutable Property row. A Kotlin implementation retains one authoritative
  physical `!T` field. The child semantic capability owns only one object-
  input method and inherits the parent's two accessor slots; it copies neither
  Property metadata nor state. Exact/open access stays `ConsumerChild<!!T>`;
  only an `in String` operation has an object receiver boundary. Ordinary non-
  partial C# string/object implementations supply one auto-property and one
  natural method. The first C# run exposed an authoring composition defect:
  the analyzer required every invariant manifest fragment to own a producer
  and therefore demanded `partial` despite the inherited property root. It now
  recognizes the structurally exact one-consumer child fragment only when its
  CLR interface inherits a bound complete producer manifest. The full
  integration gate caught and rejected the initially broader rule because it
  suppressed the required adapter for a standalone synthetic `Shape<T>`.
  Standalone consumers still use the generator. PSI/LightTree execution
  on Framework 4.8 and .NET 10 passes the eight-test rehearsal matrix and the
  same epoch-off inverse with zero failures, errors, or skips. Broader mixed,
  multi-member, changed-argument, deeper, constrained, and multi-parameter
  children remain excluded. Evidence is archived in
  [`docs/archive/generic-owner-invariant-consumer-child-2026-08-20.md`](docs/archive/generic-owner-invariant-consumer-child-2026-08-20.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 FIR suites/2,155 tests
  and two integration suites/126 tests were freshly written, while the
  unchanged six-test `dotnet.ir` model root remained up-to-date.
- One bounded second consumer edge is now closed above the property-root/
  consumer-child family. `ConsumerGrandchild<T> : ConsumerChild<T> :
  PropertyCell<T>` remains a natural invariant CLR generic hierarchy. The root
  owns the mutable Property row, each descendant owns one natural `Consume(!T)`
  MethodDef, and a Kotlin implementation retains one authoritative `!T` field.
  The compiler capabilities inherit without copied slots or Property rows and
  expose exactly a 2-to-1-to-1 declared-method chain. Exact/open calls stay
  typed; only the input-projected secondary call uses an object receiver.
  Ordinary non-partial C# string/object grandchildren implement one property
  and two methods with no compiler interface. The existing context-aware
  authoring rule finds the transitive complete producer root while standalone
  consumers retain their adapter. The deployment proof now places the root,
  first consumer, and second consumer in `lib.dll`, `middle.dll`, and
  `leaf.dll`. Its fail-first run exposed that local declaration admission was
  reapplied to an external parent and discarded its stronger producer ABI.
  External admission now requires the exact KLIB parent/root shapes, full-
  arity generic-owner records, and producer-recorded member families for the
  consumer plus both property accessors. Reflection proves the natural and
  2-to-1-to-1 capability TypeDefs remain owned by their three producer DLLs;
  no inherited member or capability is copied. PSI/LightTree execution on
  Framework 4.8 and .NET 10 passes the eight-test rehearsal matrix and the
  same epoch-off inverse with zero failures, errors, or skips. A third edge,
  broader members, multiple
  parents, changed arguments, nullable/defaulted/constrained input, and extra
  parameters remain fail-closed. Evidence is archived in
  [`docs/archive/generic-owner-invariant-consumer-grandchild-2026-08-20.md`](docs/archive/generic-owner-invariant-consumer-grandchild-2026-08-20.md).
  The three-producer deployment evidence is archived in
  [`docs/archive/generic-owner-three-assembly-consumer-chain-2026-08-20.md`](docs/archive/generic-owner-three-assembly-consumer-chain-2026-08-20.md).
  ABI 41 now normalizes local analysis and external decoding to one immutable
  published-family contract. It records family kind, exact root/parent
  relation, identity parameter mapping, bounded depth, declared roles, and
  owned/reused capability binding. One validator conjunctively checks that
  contract against KLIB for both local and external parents; the external
  index first validates its Class, capability, member-family, and parent-
  contract closure atomically. `hasReifiedGenericInterface` remains only an
  existence check. The candidate direct/separate matrix and epoch-off inverse
  each pass eight PSI/LightTree, Framework 4.8/.NET 10 lanes. Evidence is in
  [`docs/archive/generic-interface-published-family-contract-2026-08-20.md`](docs/archive/generic-interface-published-family-contract-2026-08-20.md).
  ABI 42 now also publishes the complete non-generic physical interface
  closure of every generic-class capability. `ForLoopsLowering` creates its
  ordinary `Iterator` calls after generic-owner materialization; a widened
  `C<Int> -> C<Any?>` iterator must therefore remain assignable to the one
  erased Kotlin `Iterator` TypeDef without reconstructing `C<object>`. Local
  materialization derives this universal edge structurally from the declared
  interface family. A separate consumer rebuilds it only from the producer's
  ABI 42 record, not from mutable consumer KLIB inference. Constructed imported
  CLR generic interfaces remain excluded because no single construction is
  universally truthful. The enabled rehearsal and epoch-off inverse each pass
  eight PSI/LightTree, Framework 4.8/.NET 10 lanes. This closes an early stable
  declaration prerequisite for late body-generated operations. The final
  idempotent call/value router now runs after every current body-producing
  lowering which can introduce a generic operation. It retains the early
  family plan as authority, visits generated calls in post-order, and
  propagates generic value-class backing carriers through
  their constructor/getter/box/unbox/reinterpret products, and reaches a
  monotone fixpoint for reverse declaration-order dependencies. It may only
  add a proven late route: it cannot create a family or remove a stronger
  planner route. Local and separate hostile value-class wrappers over widened
  interface and generic-class values pass all eight enabled and epoch-off
  lanes; exported IL retains the producer capability and contains no
  `C<object>` reconstruction. Evidence is in
  [`docs/archive/generic-owner-capability-superinterface-closure-2026-08-20.md`](docs/archive/generic-owner-capability-superinterface-closure-2026-08-20.md).
  The final-router evidence is in
  [`docs/archive/generic-owner-final-call-value-routing-2026-08-20.md`](docs/archive/generic-owner-final-call-value-routing-2026-08-20.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 FIR suites/2,155 tests
  and two integration suites/126 tests were freshly written, while the
  unchanged six-test `dotnet.ir` model root remained up-to-date.
- The first reified generic-interface default is now closed for one
  contravariant input root. `DefaultConsumer<in T>.consumeDefault(T)` remains
  the one natural CLR and ordinary-C# member. Framework 4.8 moves its one
  Kotlin body to a manifest-recorded top-level digest-named helper and emits
  the required natural-slot forwarder; .NET 10 places the body on the natural
  DIM. Exact `DefaultConsumer<object>` and legal value-type-narrowed
  `DefaultConsumer<Int>` Kotlin views retain the same object and body. The
  semantic capability dispatches through a Kotlin or ordinary C# natural
  override instead of bypassing it for the interface body. Ordinary C# never
  authors that capability: the portable generator supplies its helper-backed
  implementation, while modern C# inherits the DIM or overrides the natural
  method normally. The first fail-first product exposed both a missing modern
  capability implementation and C#'s inability to source-name a nested type
  inside a generic interface (`CS0648`). Generic helpers are consequently
  top-level physical compiler ABI; non-generic helpers remain nested and the
  already-recorded exact helper owner avoids a new schema contract. A
  temporarily broad default exception made the epoch-off modern canonical
  bridge recurse; the final rule is limited to defaults whose implementation
  owner is itself proven reified. The enabled candidate and epoch-off inverse
  each pass eight PSI/LightTree, Framework 4.8/.NET 10 lanes with zero
  failures, errors, or skips. The ordinary profile IL matrix passes nine tests
  and pins the moved generic helpers plus unchanged non-generic layout.
  The first full integration pass then exposed one stale assertion which still
  required the former nested generic-helper spelling; the corrected oracle
  now requires the namespace-qualified 32-hex top-level helper identity. The
  final normal production aggregate covers 190 XML suites and 2,287 tests with
  zero failures, errors, or skips: 187 FIR suites/2,155 tests and two
  integration suites/126 tests were freshly written, while the unchanged
  six-test `dotnet.ir` model root remained up-to-date.
  Evidence is archived in
  [`docs/archive/reified-generic-interface-defaults-2026-08-20.md`](docs/archive/reified-generic-interface-defaults-2026-08-20.md).
- The first reified generic-interface default now also survives hostile
  inheritance across three products. `lib.dll` owns the contravariant
  interface and default, `middle.dll` owns the real generic
  `OpenDefaultConsumer<T>` natural override, and an ordinary non-partial C#
  class derives from `OpenDefaultConsumer<object>` and overrides only
  `consumeDefault(object)`. Calls through exact
  `DefaultConsumer<object>` and Kotlin's legal value-type-narrowed
  `DefaultConsumer<Int>` view both reach the C# override; the Kotlin middle
  body remains untouched and the receiver keeps one identity. No C# semantic
  member, partial class, generated bridge, reflection, or new runtime/physical
  ABI is involved. The first test formulation incorrectly expected the
  optional interface authoring generator to augment this inherited class
  case; all four lanes rejected that assertion. The corrected proof makes the
  C# class non-partial and asserts that no generated source mentions it. The
  enabled candidate and erased epoch-off inverse each pass four
  PSI/LightTree, Framework 4.8/.NET 10 lanes with zero failures, errors, or
  skips. This closes one generic Kotlin-override-to-C#-subclass default chain,
  not multiple defaults, properties, method generics, diamonds,
  reabstraction, changed type arguments, or deeper/multiple inheritance.
  Evidence is archived in
  [`docs/archive/reified-generic-interface-default-hostile-inheritance-2026-08-20.md`](docs/archive/reified-generic-interface-default-hostile-inheritance-2026-08-20.md).
- The first reified generic-interface default property is now closed for one
  covariant read-only root.
  `DefaultPropertyProducer<out T>.defaultPropertyValue` remains one natural
  CLR `Property<T>` and the ordinary C# API. Framework 4.8 keeps its
  body in the recorded generic helper; .NET 10 keeps it on the natural getter
  DIM. The hidden declaration-semantic capability deliberately remains a
  method-only compiler ABI. The C# authoring generator now distinguishes that
  physical MethodDef from the natural Property row instead of treating every
  slot in a Kotlin property contract as CLR Property metadata. It emits only
  the required explicit semantic method adapter, converging it and any natural
  property adapter on the same C# source property, helper, or DIM. A partial C#
  `DefaultPropertyProducer<int>` with no source property observes the Kotlin
  body through exact and Kotlin-widened reads; a class with one ordinary `int`
  property override is observed through both paths, without duplicate body
  execution or changed object identity. The fail-first run exposed the
  method/Property metadata confusion as `KDNCS006`; no manifest or runtime ABI
  change was needed. The enabled candidate and erased epoch-off inverse each
  pass four PSI/LightTree, Framework 4.8/.NET 10 lanes with zero failures,
  errors, or skips. The abstract read-only property-inheritance extension is
  recorded below; default-property inheritance, multiple/mixed properties,
  diamonds, reabstraction, changed arguments, and deeper/multiple inheritance
  remain separate gates. Evidence is archived in
  [`docs/archive/reified-generic-interface-default-property-2026-08-20.md`](docs/archive/reified-generic-interface-default-property-2026-08-20.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 freshly written FIR
  suites/2,155 tests, two freshly written integration suites/126 tests, and the
  unchanged six-test `dotnet.ir` model root.
- The first owner- and method-generic interface default is now closed for one
  covariant root. `DefaultMethodGenericProducer<out T>.<R>(R): T` retains an
  ordinary CLR `produceDefaultGeneric<R>(R): T` as its natural Kotlin/C# API.
  Its non-generic semantic capability retains the same method-owned `R` and
  changes only the owner-dependent result to `object`; the Framework helper
  carries both owner `T` and method `R`, while .NET 10 retains the natural DIM.
  Separate C# classes can inherit the Kotlin default or override the ordinary
  generic method. Exact C# calls and Kotlin-widened calls observe the same
  body or C# override and keep one receiver identity. The manifest oracle now
  requires arity one on both interface slots and arity two on the helper, so a
  superficially callable erased method cannot satisfy the proof. The enabled
  candidate and erased epoch-off inverse each pass four PSI/LightTree,
  Framework 4.8/.NET 10 lanes with zero failures, errors, or skips. That first
  checkpoint was limited to a default with one non-reified invariant method
  parameter having exactly the universal bound, one direct non-defaulted/non-
  vararg `R` input, and one non-null direct owner-`T` result. The abstract and
  direct-self-bound extensions are recorded below; nullable results, child
  inheritance, overloads, and multiple or mixed members remain separate
  gates. Evidence is archived in
  [`docs/archive/reified-generic-interface-method-generic-default-2026-08-20.md`](docs/archive/reified-generic-interface-method-generic-default-2026-08-20.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 freshly written FIR
  suites/2,155 tests, two freshly written integration suites/126 tests, and
  the unchanged up-to-date six-test `dotnet.ir` model root.
- The exact owner-plus-method-generic producer shape is now also closed for an
  abstract interface root. `AbstractMethodGenericProducer<out T>.<R>(R): T`
  retains generic arity one and the method-owned `R` on both its natural
  `<R>(R): T` slot and declaration-semantic `<R>(R): object` slot. A generic
  Kotlin implementation in a second DLL and an ordinary partial C#
  implementation each author only the natural generic method. Exact calls and
  Kotlin-widened calls from later consumers reach that same implementation and
  retain one receiver identity; C# never names the semantic capability. All
  four candidate and four erased epoch-off PSI/LightTree, Framework 4.8/.NET
  10 lanes pass. Admission remains limited to an abstract member or proven
  default with one non-reified invariant method parameter having exactly the
  universal bound, one direct non-defaulted/non-vararg `R` input, and one
  non-null direct owner-`T` result. The direct self-bound extension is recorded
  below; further constraints, nullable results, child inheritance, overloads,
  and multiple or mixed members remain separate gates. The final normal
  production aggregate directly audits 190 XML suites
  and 2,287 tests with zero failures, errors, or skips: 187 freshly written
  FIR suites/2,155 tests, two freshly written integration suites/126 tests,
  and the unchanged up-to-date six-test `dotnet.ir` model root. Evidence is
  archived in
  [`docs/archive/reified-generic-interface-abstract-method-generic-2026-08-21.md`](docs/archive/reified-generic-interface-abstract-method-generic-2026-08-21.md).
- The first constructed method constraint is now retained on a reified
  generic-interface root. An abstract
  `ConstrainedProducer<out T>.<R : Consumer<R>>(R): T`, where `Consumer<in R>`
  is independently admitted as the exact one-member consumer root, emits
  `Consumer<!!R>` on both the natural `<R>(R): T` MethodDef and the semantic
  `<R>(R): object` MethodDef. Every copied semantic or implementation bridge
  remaps that bound to its own method GenericParam; a separate consumer never
  retains a producer-IR parameter identity. Generic Kotlin and ordinary
  partial C# implementations each author only the natural method. Exact and
  Kotlin-widened calls execute that method, preserve receiver/value identity,
  and invoke the constraint operation. Roslyn source and metadata method
  parameters are compared alpha-equivalently by kind and ordinal inside the
  otherwise exact recursive constraint type. Reflection independently proves
  one self-bound GenericParamConstraint on both interfaces implemented by the
  C# class. All four candidate and four erased epoch-off PSI/LightTree,
  Framework 4.8/.NET 10 lanes pass. Admission remains structural and limited
  to this direct non-null invariant self-bound. The constrained-default
  extension is recorded below; nominal/special/multiple bounds, nullable
  results, inheritance, overloads, and mixed members remain separate gates.
  Evidence is archived in
  [`docs/archive/reified-generic-interface-constrained-method-generic-2026-08-21.md`](docs/archive/reified-generic-interface-constrained-method-generic-2026-08-21.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 freshly written FIR
  suites/2,155 tests, two freshly written integration suites/126 tests, and
  the unchanged up-to-date six-test `dotnet.ir` model root.
- The same exact `R : Consumer<R>` constructed method constraint is now closed
  for a reified generic-interface default. Natural `<R>(R): T`, semantic
  `<R>(R): object`, and portable helper `<T, R>(object, R): T` MethodDefs all
  retain the exact `Consumer<!!R>` constraint on their own method parameter.
  A Kotlin class in a second DLL inherits the body without declaring a source
  method. On Framework its private helper-forwarder is explicitly bound to the
  closed natural interface slot after substituting owner `T`; comparing the
  concrete `int` result to the still-open interface `!T` had previously
  suppressed that MethodImpl and load-poisoned the class. .NET 10 retains the
  natural DIM. Ordinary partial C# classes may either inherit the Kotlin
  default or override only the natural `where R : Consumer<R>` method. Exact
  and Kotlin-widened Kotlin/C# calls reach the same selected body, invoke the
  constraint operation, and preserve receiver and argument identity.
  Reflection independently proves both interface slots plus the two-parameter
  helper retain the exact self-bound. All four candidate and four erased
  epoch-off PSI/LightTree, Framework 4.8/.NET 10 lanes pass. The multiple-bound
  and nominal-only/class-bound extensions are recorded below; further special,
  owner-relative, nullable, and other constructed constraints, inheritance,
  overloads, and mixed members remain separate gates. Evidence is archived in
  [`docs/archive/reified-generic-interface-constrained-method-generic-default-2026-08-21.md`](docs/archive/reified-generic-interface-constrained-method-generic-default-2026-08-21.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 freshly written FIR
  suites/2,155 tests, two freshly written integration suites/126 tests, and
  the unchanged up-to-date six-test `dotnet.ir` model root.
- A method-generic interface default may now retain its admitted direct
  `R : Consumer<R>` self-bound together with direct public non-generic nominal
  interface bounds. The first proof composes `Consumer<R>` with an independent
  marker on one `R`; natural `<R>(R): T`, semantic `<R>(R): object`, and
  portable helper `<T, R>(object, R): T` MethodDefs each expose both exact
  GenericParamConstraint rows after local and separate-compilation remapping.
  The structural gate admits one self-bound plus one or more direct nominal
  interface bounds; it does not infer eligibility from declaration names or
  marker member shape, and excludes the declaration-erased `CharSequence`
  capability. A Kotlin class and an ordinary C# class inherit the default;
  another C# class overrides only the natural method while spelling its two
  independent constraints in the reverse source order. The Roslyn authoring
  matcher now treats constraint order as non-semantic and compares the exact
  recursive types as a multiset. Without that repair, .NET 10 DIM happened to
  reach the override but Framework's generated portable bridge bypassed it and
  called the Kotlin helper. Exact and Kotlin-widened calls now select the same
  body on both runtimes, retain receiver/value identity, and execute both bound
  operations once. Reflection verifies both unordered constraints on both
  interface slots and on the helper. All four candidate and four erased epoch-
  off PSI/LightTree, Framework 4.8/.NET 10 lanes pass. The nominal-only and
  class-bound extension is recorded below; further special, owner-relative,
  nullable, and other constructed constraints, inheritance, overloads, and
  mixed members remain separate gates. Evidence is archived in
  [`docs/archive/reified-generic-interface-multiple-method-constraints-2026-08-21.md`](docs/archive/reified-generic-interface-multiple-method-constraints-2026-08-21.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 freshly written FIR
  suites/2,155 tests, two freshly written integration suites/126 tests, and
  the unchanged up-to-date six-test `dotnet.ir` model root.
- A method-generic producer root no longer needs a constructed
  `R : Consumer<R>` self-bound merely to preserve ordinary nominal constraints.
  A nonempty method-bound set may now contain direct public non-generic nominal
  interfaces and at most one non-final class, optionally together with the
  admitted self-bound. The first nominal-only proof uses Kotlin source order
  `R : Marker, R : Base`. Natural `<R>(R): T`, semantic `<R>(R): object`, and
  portable helper `<T, R>(object, R): T` MethodDefs each retain the exact
  `Base` and `Marker` GenericParamConstraint rows; class-first metadata and C#
  `where R : Base, Marker` spelling are canonicalization, not a semantic change.
  The structural gate excludes `Any`, declaration-erased `Number` and
  `CharSequence`, generic or nullable classifiers, final classes, and non-public
  declarations. Kotlin and ordinary C# implementations inherit the default,
  while another C# class overrides only the natural constrained method. Exact
  and Kotlin-widened calls converge on the selected body on Framework 4.8 and
  .NET 10, preserve receiver/value identity, and execute both constraint
  operations; reflection independently proves both bounds on both slots and the
  helper. All four candidate and four erased epoch-off PSI/LightTree/runtime
  lanes pass. The abstract owner-relative extension is recorded below; special,
  nullable, other constructed, and defaulted owner-relative constraints remain
  closed. Evidence is archived in
  [`docs/archive/reified-generic-interface-nominal-method-constraints-2026-08-21.md`](docs/archive/reified-generic-interface-nominal-method-constraints-2026-08-21.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 freshly written FIR
  suites/2,155 tests, two freshly written integration suites/126 tests, and
  the unchanged up-to-date six-test `dotnet.ir` model root.
- An invariant reified generic-interface root may now own one or more complete
  abstract mutable property families instead of exactly one. The first
  composition proof uses two independent `var T` properties. Its natural CLR
  interface exposes two real `Property<T>` rows and four `!T` accessor slots; a
  generic Kotlin implementation retains two distinct physical `!T` backing
  fields. Exact/open access stays typed. Star/output reads and input writes use
  object only at their individual operation boundary and preserve one receiver
  identity; they do not erase either property, field, interface construction,
  or unrelated generic state. Ordinary non-partial C# `string` and `object`
  implementations supply two normal auto-properties and require no compiler-
  ABI interface. The authoring analyzer now recognizes an arbitrary nonempty
  manifest only when every member partitions by source property name into one
  exact abstract getter/setter pair; method bundles retain their previous one-
  producer/optional-consumer limit, and mixed or incomplete shapes stay closed.
  Reflection verifies both Property rows, all four typed slots, both Kotlin
  `!T` fields, and operation-local projected signatures. All four candidate and
  four erased epoch-off PSI/LightTree, Framework 4.8/.NET 10 lanes pass.
  Defaulted, inherited, nullable, covariant, mixed, and constrained multiple-
  property roots remain separate gates. Evidence is archived in
  [`docs/archive/reified-generic-interface-multiple-invariant-properties-2026-08-21.md`](docs/archive/reified-generic-interface-multiple-invariant-properties-2026-08-21.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 freshly written FIR
  suites/2,155 tests, two freshly written integration suites/126 tests, and
  the unchanged up-to-date six-test `dotnet.ir` model root.
- One covariant read-only property inheritance edge is now closed across
  producer assemblies. `Child<out T> : Parent<T>` adds one public abstract
  `val T` property with an exact owner-parameter substitution. The child natural
  TypeDef owns one typed CLR Property/getter and inherits the parent Property
  from `lib.dll`; its non-generic semantic capability owns only the child getter
  and inherits the parent capability. No Property, accessor, or semantic slot is
  copied into the consuming assembly. A Kotlin implementation retains two
  independent physical `!T` backing fields. Exact child calls plus widened
  parent and child calls select those fields and preserve receiver identity.
  An ordinary partial C# `Child<string>` implements only the two natural read-
  only properties; generated compiler-ABI adapters make both Kotlin-widened
  views reach the same C# object. Reflection verifies covariance, the exact
  `Child<T> : Parent<T>` edge, one child-owned and one parent-owned read-only
  Property row, assembly ownership, and both `!T` fields. All four candidate and
  four erased epoch-off PSI/LightTree, Framework 4.8/.NET 10 lanes pass.
  Defaulted, multi-property, changed-argument, mixed-member, and deeper/multiple
  read-only inheritance remain closed. Evidence is archived in
  [`docs/archive/reified-generic-interface-read-only-property-child-2026-08-21.md`](docs/archive/reified-generic-interface-read-only-property-child-2026-08-21.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 freshly written FIR
  suites/2,155 tests, two freshly written integration suites/126 tests, and
  the unchanged up-to-date six-test `dotnet.ir` model root.
- The first direct owner-relative method constraint is now closed for one
  abstract covariant root: `<R : @UnsafeVariance T>(R): T`. Kotlin and KLIB
  retain the authoritative `R : T` relationship. The natural variant CLR slot,
  non-generic semantic slot, and Kotlin implementation override deliberately
  omit that executable GenericParamConstraint: CoreCLR rejects `R : T` on the
  covariant TypeDef and load-fails an implementation which retains a stronger
  constraint than its interface slot. Both slots remain genuinely generic in
  the same `R`; neither substitutes owner `T`, erases the value parameter, uses
  reflection, nor requires IL weaving. Schema 7 publishes the normalized
  `(method R=0, owner T=0)` relationship and C# authoring emits `KDNCS009`
  guidance. An ordinary partial C# class implements only the unconstrained
  natural generic method; its `typeof(R)` result proves exact and Kotlin-widened
  calls preserve the actual `Int32` or `String` method construction and the
  same receiver. A Kotlin `Producer<Marker>` implementation also survives a
  legal widened `Producer<Any?>` call with a `String` method argument.
  Reflection proves covariance, typed `T` results, `R` value parameters, and
  zero physical constraints on both slots and the Kotlin override. All four
  candidate and four erased epoch-off PSI/LightTree, Framework 4.8/.NET 10
  lanes pass. Nested or multiple relative bounds, nullable bounds, mixed
  members, and inherited owner-relative forms remain closed; the direct
  default form is recorded next. Evidence is
  archived in
  [`docs/archive/reified-generic-interface-owner-relative-method-constraint-2026-08-21.md`](docs/archive/reified-generic-interface-owner-relative-method-constraint-2026-08-21.md).
  The final normal production aggregate directly audits 190 XML suites and
  2,287 tests with zero failures, errors, or skips: 187 freshly written FIR
  suites/2,155 tests, two freshly written integration suites/126 tests, and
  the unchanged up-to-date six-test `dotnet.ir` model root.
- The same direct owner-relative family may now own one Kotlin default body.
  Framework and .NET 10 share one authoritative helper body; the modern natural
  DIM is a typed wrapper, while the Kotlin semantic bridge closes owner `T` at
  `object` and retains the actual method `R`. Natural and semantic interface
  MethodDefs, Kotlin overrides, and ordinary C# methods remain generic in that
  `R` and carry no illegal or stronger CLR `R : T` constraint. Generated C#
  adapters close the helper at the exact owner argument for natural calls and
  at `object` for the recorded semantic owner-relative route. An ordinary C#
  implementation can inherit the default or override only the natural generic
  method. Generic Kotlin class implementations now expose their planned class
  dispatcher to the interface bridge, and the allocation-free foreign-
  override probe carries the same method GenericParam. Direct and widened
  calls therefore observe a normal C# override even after a separate
  `lib` -> `middle` -> `leaf` Kotlin chain. Reflection pins two generic slots,
  zero constraints, typed/object results, and helper `<T, R>` order. The four
  candidate and four production-inverse PSI/LightTree, Framework 4.8/.NET 10
  lanes pass with zero failures, errors, or skips. The final normal production
  aggregate directly audits 190 XML suites and 2,287 tests: 187 FIR suites/
  2,155 tests, two integration suites/126 tests, and the unchanged six-test
  `dotnet.ir` model root, with zero failures, errors, or skips. A non-generic
  Kotlin implementor of the abstract sibling remains an explicit next gate rather
  than permission to degrade the interface or unrelated state to `object`.
  Evidence is archived in
  [`docs/archive/reified-generic-interface-owner-relative-method-default-2026-08-21.md`](docs/archive/reified-generic-interface-owner-relative-method-default-2026-08-21.md).
- A final non-generic Kotlin class may now implement the abstract owner-relative
  sibling without narrowing a legal widened method construction to the class's
  closed owner argument. Its one authoritative Kotlin body moves to a private
  unconstrained `<R>(R): object` semantic twin. The public class method remains
  the closed typed C# entry, while separate private natural and semantic
  MethodImpls forward the actual `R` to that same body; only the natural result
  is adapted back to the closed owner result. Semantic bodies are cached per
  Kotlin target, but natural MethodImpls are emitted per physical interface
  slot, so one override may implement two independent reified roots without
  losing either slot or duplicating the body. Separate `lib` -> `middle` -> C#
  consumption covers closed `String`, `Int`, nullable `String`, and dual-root
  implementations. Exact natural calls, legal widened Kotlin calls, null,
  boxing, identity, and both interface maps execute on Framework 4.8 and .NET
  10 with both FIR parsers. The four candidate, four explicit epoch-off, and
  four property-absent lanes pass with zero failures, errors, or skips. The
  final normal production aggregate directly audits 190 XML suites and 2,287
  tests with zero failures, errors, or skips: 187 FIR suites/2,155 tests, two
  integration suites/126 tests, and the unchanged six-test `dotnet.ir` root.
  Open non-generic implementations, inherited non-generic bodies, overloads,
  and broader parameter graphs remain explicit gates; in particular, the
  private semantic twin is not an override-family solution. Evidence is
  archived in
  [`docs/archive/reified-generic-interface-closed-owner-relative-implementation-2026-08-21.md`](docs/archive/reified-generic-interface-closed-owner-relative-implementation-2026-08-21.md).
- A locally declared open non-generic Kotlin implementation of that abstract
  owner-relative sibling now preserves both Kotlin widening and ordinary C#
  subclassing. Its public virtual class method is an unconstrained `<R>(R):
  String` CLR entry. One protected virtual `<R>(R): object` semantic hook owns
  the Kotlin body, and one protected generic probe detects a later C# override
  of only the public method without reflection or allocation. The existing
  reified-interface capability and a new class-owned separate-compilation
  capability retain independent private final dispatchers; both select the C#
  override when present and otherwise reach the raw semantic body. C# neither
  implements nor names the capabilities or hook. Final implementations retain
  their prior closed typed C# entry. The separate Kotlin producer plus ordinary
  C# subclass proves exact base calls, widened base calls with `R=Int`, direct
  and interface-typed C# override calls, widened Kotlin dispatch to that same
  C# override, identity, and the public virtual unconstrained method signature.
  PSI and LightTree execute candidate, explicit epoch-off, and property-absent
  lanes on Framework 4.8 and .NET 10 with zero failures, errors, or skips.
  The full `dotNetTest` aggregate exits zero; direct audit reports 190 XML
  suites and 2,287 tests with zero failures, errors, or skips. The 126-test
  integration root was regenerated by that run, while Gradle retained the
  already-green 2,155-test FIR and six-test model roots as up-to-date. The
  feature test is present in all four PSI/LightTree by Framework/.NET box
  suites.
  Inherited non-generic implementations, overloads, multiple method parameters
  or method type parameters, nested/multiple relative bounds, and mixed member
  families remain explicit gates. Evidence is archived in
  [`docs/archive/reified-generic-interface-open-owner-relative-implementation-2026-08-21.md`](docs/archive/reified-generic-interface-open-owner-relative-implementation-2026-08-21.md).
- A local non-generic base method may now satisfy the owner-relative interface
  only first declared by an open or final derived class. The base, rather than
  either binding owner, receives the single public unconstrained entry,
  protected semantic hook/probe, class-owned capability, and private
  dispatcher. Each derived class receives only its natural MethodImpl and its
  own private interface-capability dispatcher. A second final binding owner
  proves that the base family and its original `R : String` proof are reused:
  it first exposed an illegal call to the first binding owner's private
  dispatcher and then exposed revalidation against the already-reopened IR
  bound. The compiler now retains the pre-lowering bound in the family record
  and routes each binding owner directly through the shared protected
  hook/probe instead. An ordinary separately compiled C# subclass of the open
  binding owner overrides only the inherited public `<R>(R): String` entry;
  exact, natural-interface, and widened Kotlin calls all reach it with the
  actual `R`, while reflection proves the entry remains declared on the base.
  PSI and LightTree execute candidate, explicit epoch-off, and property-absent
  lanes on Framework 4.8 and .NET 10 with zero failures, errors, or skips.
  The full `dotNetTest` aggregate exits zero; direct audit reports 190 XML
  suites and 2,287 tests with zero failures, errors, or skips. The 126-test
  integration root was regenerated by that run, while Gradle retained the
  already-green 2,155-test FIR and six-test model roots as up-to-date. The
  feature test is present in all four PSI/LightTree by Framework/.NET box
  suites.
  A base supplied by an earlier artifact, including reuse of a producer-
  published prepared family, remains a separate gate. Evidence is archived in
  [`docs/archive/reified-generic-interface-inherited-owner-relative-implementation-2026-08-21.md`](docs/archive/reified-generic-interface-inherited-owner-relative-implementation-2026-08-21.md).
- Latest compiler-work audit: nine lowering-local external-declaration
  resolvers rebuilt the same three immutable library indexes during every
  ordinary backend compilation. `DotNetBackendContext` now builds one
  compilation-local `DotNetExternalDeclarationIndex`; each lowering still
  receives a fresh resolver and therefore retains its own IR-derived ABI-key
  and class-info caches. This removes eight complete declaration/member-family/
  function-carrier hash-index builds without sharing stale mutable-IR facts.
  The emitter retains its separate index because bootstrap Stdlib and user
  emission may consume different library sets. Two entirely unused class-info
  maps were also removed from every resolver. The focused publication test and
  final target aggregate are green. The aggregate covers 190 XML suites and
  2,286 tests with zero failures, errors, or skips; FIR and integration roots
  were freshly written and the unchanged six-test `dotnet.ir` root remained
  up-to-date. The fresh 861-second FIR JFR contains 7,232 execution samples and
  still attributes 32 self samples to the remaining shared/emitter index
  construction. This establishes material compiler work but is not presented
  as a wall-time percentage because the external-tool-heavy focused runs were
  too noisy for a trustworthy before/after timing.
- Latest completed Common collection feature: all 24 generated Map min/max
  adapters are now published on `Kotlin.Collections.MapsKt`: selector element,
  generic/Float/Double selector-result, element-comparator, and comparator-
  result throwing/nullable forms. Every declaration is the exact Common
  `@InlineOnly` adapter over `entries`; no target algorithm or BCL substitute
  was added. Empty/singleton evaluation, first-tie/result identity, callback
  stopping, nullable comparator results, and Float/Double NaN/signed-zero
  ordering execute on Framework CLR 4 and .NET 10. The bounded return-only
  collision router now admits only the exact MapsKt/package/Map triple. Raw
  metadata contains 24 assembly-visible MethodDefs, installed Kotlin inlines
  all adapters, and Roslyn is rejected from calling their non-public helpers.
  The generated Maps source is byte-stable at
  `FE0417C410404B28E6CE53F7027CCCC25966098FE0DE95D1B65CCF25042C5EF2`.
  The final aggregate plus explicit model-suite freshness rerun wrote all
  three roots: 190 XML suites and 2,278 tests with zero failures, errors, or
  skips. This closes the selected erased-owner stdlib breadth interlude; next
  major work is the complete Kotlin-emitter/inverse-rollback generic-owner
  rehearsal, not another leaf family or per-owner switch. See
  [`docs/archive/common-map-min-max-family-2026-08-18.md`](docs/archive/common-map-min-max-family-2026-08-18.md).
- First Kotlin-emitted CLR-generic-owner rehearsal checkpoint: the test-only
  `kotlin.dotnet.genericOwnerRehearsal` epoch now replaces admitted ordinary
  Kotlin owners with real `C<T>` TypeDefs and maps producer-proven ordinary
  state to `!T` fields. Volatile/open semantic state remains one object-domain
  field on that same owner; no shadow or copied state is introduced. The
  emitter consumes planner-owned typed/semantic/capability families, physical
  inner-class parameters, constructed owner calls and fields, value-class
  carriers, classifier-only Kotlin `is`/`as?` behavior, and the permitted
  earlier failure of explicitly unchecked throwing parameterized casts.
  Physical ABI 38 carries owner arity, assembly-qualified capability/member-
  family bindings,
  producer-selected capability slots in ordinary function signatures, and
  foreign-override probe identities to separate Kotlin consumers. The focused
  PSI/LightTree and Framework
  4.8/.NET 10 matrix executes 36 products with zero failures, errors, or skips,
  covering value/reference/nullable fields, inner classes, value classes whose
  backing value is `C<T>`, reflection, C# construction/dispatch, and separate
  compilation. Production remains on the unchanged erased epoch. The
  source-built whole-Stdlib rehearsal still fails closed in later emission at
  residual canonical/capability owner joins, covariant returns, and exact
  runtime-intrinsic field requirements. The first whole-Stdlib composition
  correction establishes `PROVEN_TYPED > semantic fallback`: anonymous/field
  initializers own an exact newly constructed receiver, and a private final
  default accessor over producer-proven typed state retains its natural CLR
  carrier. Custom/overridable accessors, setters, semantic state, and widened
  receivers are unchanged. This removes the `AbstractList.SubList`,
  `AbstractMutableList.SubList`, and `ArrayList` owner cascade and reduces the
  rehearsal diagnostics from 216 to 92. The first foreign-subclass dispatch
  condition is now closed for concrete no-input owner-dependent outputs. A
  widened Kotlin call observes the natural typed C# override without C#
  overriding the protected semantic hook, including C# after an intervening
  Kotlin override. Each open Kotlin declaration emits one protected virtual
  last-Kotlin probe; allocation-free `ldvirtftn`/`ldftn` comparison detects a
  later foreign typed override, while the unchanged path invokes the raw
  semantic hook. Consequently an `@UnsafeVariance` widened write/read still
  returns its incompatible object and fails only at a later real typed use.
  PSI/LightTree on Framework 4.8 and .NET 10 execute both the direct Kotlin
  producer/C# consumer and the actual Kotlin base DLL -> Kotlin override DLL ->
  warnings-as-errors C# subclass chain. The correct `-P` rehearsal disproved
  the earlier ABI-36 conclusion. An ordinary external function first lost its
  producer-selected capability parameter, and the intervening Kotlin override
  then failed to reuse the inherited semantic-hook/probe slots. ABI-37 records
  both facts. The consumer reconstructs only explicitly recorded capability
  slots and makes the Kotlin semantic hook and last-Kotlin probe override their
  producer MethodDefs; a still-later C# override changes only the natural typed
  target. A self-producing closed verifier now audits that allocation-free IL,
  publishes the actual Kotlin base/override plus ordinary C# subclass product,
  and executes both probe outcomes on JIT, ReadyToRun, full trim, and a real
  Windows x64 NativeAOT executable. All four modes return `OK`; the NativeAOT
  link uses the signed Microsoft 14.44 linker. Broad inputs, abstract semantic
  obligations, interfaces, and method-generic entries remain separate gates.
  This remains a rehearsal slice, not evidence for a per-owner rollout. The
  final normal target aggregate covers 190 XML suites and 2,286
  tests with zero failures, errors, or skips; FIR and integration roots were
  freshly written, while the unchanged six-test `dotnet.ir` root remained
  up-to-date from its prior green checkpoint.
- Preceding completed Common collection feature: all 28 generated CharSequence
  min/max aggregates are now published on `Kotlin.Text.StringsKt`, together
  with the exact Common `CharSequence.lastIndex` prerequisite discovered by
  the first compile. Natural, selector, generic/Float/Double selector-result,
  element-comparator, and selector-result-comparator throwing/nullable forms
  all retain their Common bodies. Empty/singleton evaluation, first-result
  identity, callback stopping, nullable selector results, Float/Double NaN and
  signed-zero ordering, real `System.String`, and a custom Kotlin
  `CharSequence` capability are pinned on Framework CLR 4 and .NET 10. The
  bounded selector-result physical-name router is now keyed by exact façade,
  package, and receiver; no public `DotNetName` is introduced. Raw metadata
  contains the 28 exact MethodDefs plus one static extension getter, with 12
  public and 16 assembly-visible aggregate methods. Installed Kotlin calls the
  eight ordinary fallbacks and inlines the other twenty. Roslyn calls natural,
  selector, and comparator fallbacks on both classifier arms, while
  `@InlineOnly` fallbacks remain inaccessible. The generated Strings source is
  byte-stable at
  `159B686D6BB9654A323C6FC200A6C26AB9A62DB7111B3756E51B1BE36F0706A0`.
  The final aggregate plus explicit model-suite freshness rerun wrote all
  three roots: 190 XML suites and 2,274 tests with zero failures, errors, or
  skips. See
  [`docs/archive/common-char-sequence-min-max-family-2026-08-18.md`](docs/archive/common-char-sequence-min-max-family-2026-08-18.md).
- Preceding completed Common collection feature: the comparator aggregate
  `minWith`/`maxWith` and `minOfWith`/`maxOfWith` families now publish 72
  additional object-/primitive-array declarations, completing all eight
  throwing/nullable functions over Iterable, generic object arrays, and all
  eight signed primitive-array wrappers (80 MethodDefs total). Exact Common
  empty/singleton comparator and selector counts, first-tie identity, callback
  failure timing, nullable selector results, contravariant broad comparators,
  all ten receivers, and explicit Float/Double ordering are pinned. No new
  physical-name mapping, Runtime surface, or generic-owner representation was
  needed. Installed Kotlin calls all 40 ordinary element-selection fallbacks
  and inlines all 40 `@InlineOnly` selector-result bodies. Roslyn implements
  the truthful erased `Kotlin.Comparator` interface and directly calls signed
  `IntArray` element-selection fallbacks; it is explicitly rejected from the
  assembly-visible selector-result fallbacks. The generated collections source
  is byte-stable at
  `B43658C78A58274ED13D776DC6EDE50E6CB6DF7C825113268D4F84F1756096D1`.
  The final aggregate plus explicit model-suite freshness rerun wrote all three
  roots: 190 XML suites and 2,270 tests with zero failures, errors, or skips.
  See
  [`docs/archive/common-comparator-min-max-family-2026-08-18.md`](docs/archive/common-comparator-min-max-family-2026-08-18.md).
- Preceding completed Common collection feature: the selector-result
  `minOf`/`maxOf` family now publishes all 120 generic/Float/Double throwing
  and nullable declarations over Iterable, generic object arrays, and all
  eight signed primitive-array wrappers, in addition to the previously
  completed Sequence forms. Boolean is included because the selector result
  supplies ordering. Exact Common empty/singleton selector counts, first-result
  identity for comparison ties, callback failure timing, every receiver, and
  Float/Double NaN/signed-zero ordering are pinned. The first compile exposed
  the missing nullable counterpart of generic substitution recovery: an
  upper-bound reference carrying boxed `R` or null can now recover a FIR-proven
  concrete nullable scalar through `unbox.any Nullable<R>`. This is confined
  to `IMPLICIT_CAST` and does not broaden explicit `as`/`as?` semantics. Raw
  metadata contains ten MethodDefs for each of the twelve bounded physical
  names; installed Kotlin inlines all 120 `@InlineOnly` bodies, and Roslyn is
  explicitly rejected from calling their assembly-visible fallbacks. The
  generated collections source is byte-stable at
  `9FE8BA48CEB95AEF50DE72F631CDC97F0432F8040F81D88B233DBDACBAFFF907`.
  The final aggregate plus explicit model-suite freshness rerun wrote all
  three roots: 190 XML suites and 2,266 tests with zero failures, errors, or
  skips. This does not change the erased physical Sequence/generic-owner
  model. See
  [`docs/archive/common-selector-result-min-max-family-2026-08-18.md`](docs/archive/common-selector-result-min-max-family-2026-08-18.md).
- Preceding completed Common collection feature: the selector-generated
  `minBy`/`maxBy` family now publishes all 40 throwing/nullable declarations
  over Iterable, generic object arrays, and all eight signed primitive-array
  wrappers, in addition to the previously completed Sequence forms. Boolean
  is included because ordering belongs to selector result `R`. Common empty/
  singleton selector elision, first-tie identity, one selector call per visited
  element otherwise, callback failure timing, and generic Comparable
  Float/Double NaN/signed-zero total ordering are pinned. The first hostile
  compile exposed and fixed a general CIL stack bug: an inlined local return
  lowered to a synthetic loop break now preserves operands pending before loop
  entry while discarding only loop-local values. Raw metadata contains ten
  MethodDefs per name; installed Kotlin inlines all 40, while Roslyn directly
  calls signed `IntArray` fallbacks through an erased `Kotlin.Function1` class.
  The generated collections source is byte-stable at
  `421E43BD3F42F377EA5AF2E615D9C135BAB532D26B9403977ECB658089E54A96`.
  The final aggregate plus explicit model-suite freshness rerun wrote all three
  roots: 190 XML suites and 2,262 tests with zero failures, errors, or skips. See
  [`docs/archive/common-selector-min-max-family-2026-08-18.md`](docs/archive/common-selector-min-max-family-2026-08-18.md).
- Preceding completed Common collection feature: the natural generated
  `min`/`max` family now publishes all 52 declarations over generic, Float,
  and Double Iterable/object-array receivers plus the seven naturally ordered
  signed primitive-array wrappers, with throwing and nullable forms together.
  Boolean remains absent exactly as in the upstream templates; the Sequence
  forms were already complete. Empty-input failure/nullability, first-tie
  identity, single traversal, and Kotlin Float/Double NaN and signed-zero
  ordering are pinned. Iterable/object-array return-only collisions reuse the
  bounded logical-element-derived physical names already proven for Sequence;
  this adds no public `DotNetName` policy. Raw metadata and installed Kotlin
  calls account for all 52 MethodDefs, while Roslyn calls signed `IntArray`
  overloads directly. The generated collections source is byte-stable at
  `A17BCD007BE004F4B780E1FB5ADBDB52A43AFE857F13227EF65D2D68D7516BC3`.
  The final aggregate plus explicit model-suite freshness rerun wrote all three
  roots: 190 XML suites and 2,258 tests with zero failures, errors, or skips. See
  [`docs/archive/common-natural-min-max-family-2026-08-18.md`](docs/archive/common-natural-min-max-family-2026-08-18.md).
- Preceding completed Common collection feature: the complete generated
  `allDistinct`/`allDistinctBy` family now publishes 20 declarations over
  Iterable, generic object arrays, and all eight signed primitive-array
  wrappers, in addition to the previously completed Sequence variants. The
  shared Common byte-domain bit set now accepts normalized Int indices, so
  signed Byte retains its allocation-free 256-bit algorithm without depending
  on public `UByte`; the same refactor remains exact for upstream unsigned
  callers. The .NET product contains one non-public internal helper and no
  `Kotlin.UByte` TypeDef. Exact Common singleton selector elision, first-
  duplicate short circuit, nullable keys, callback failure timing, and
  equals-consistent Float/Double NaN/signed-zero behavior are pinned. Raw
  metadata contains ten ordinary and ten selector MethodDefs; installed Kotlin
  calls ten ordinary fallbacks and inlines all selector bodies, while Roslyn
  calls signed `IntArray` directly. The final aggregate freshly wrote all three
  roots: 190 XML suites and 2,254 tests with zero failures, errors, or skips.
  See
  [`docs/archive/common-all-distinct-family-2026-08-18.md`](docs/archive/common-all-distinct-family-2026-08-18.md).
- Preceding completed Common collection feature: the complete generated
  `allEqual`/`allEqualBy` family now publishes 20 declarations over Iterable,
  generic object arrays, and all eight signed primitive-array wrappers, in
  addition to the previously completed Sequence variants. The exact Common
  bodies preserve zero selector calls for empty/singleton inputs, first-
  mismatch short circuit, nullable selector keys, callback exception timing,
  and equals-consistent Float/Double NaN and signed-zero behavior. Raw product
  metadata pins ten ordinary and ten selector MethodDefs; installed Kotlin
  emits ten public ordinary fallback calls and inlines all ten selector
  bodies, while Roslyn calls the signed `IntArray` overload directly. At this
  checkpoint the sibling `allDistinct` family remained deferred rather than
  omitting Byte or adding a target HashSet substitute; the latest tranche above
  resolves that exact shared dependency. The final full target
  aggregate covers 190 XML suites and 2,250 tests with zero failures, errors,
  or skips; FIR and integration were freshly written and the unchanged six-
  test `dotnet.ir` root remained up-to-date. See
  [`docs/archive/common-all-equal-family-2026-08-18.md`](docs/archive/common-all-equal-family-2026-08-18.md).
- Earlier completed Common collection feature: the full seven-declaration eager
  Iterable/Sequence-consumer closure now publishes four Sequence-result
  `flatMap*` variants, `minus(Sequence)`, and both Iterable/Collection
  `plus(Sequence)` overloads. Existing Iterable-result flatMap physical names
  remain unchanged; only the new colliding siblings receive deterministic
  logical-selector-derived `...Sequence...` names. KLIB inlines all four
  flatMap bodies, installed Kotlin calls the three ordinary operators, and
  Roslyn implements the erased Kotlin Sequence interface and calls those
  operators directly. PSI/LightTree and Framework CLR/.NET 10 execution pin
  eager inner traversal, destination identity, exception and materialization
  order, nullable/widened values, and single RHS consumption. The logical
  `Sequence<T>` API remains generic; this tranche does not authorize a partial
  physical Sequence-owner cutover. The final full aggregate direct audit
  covers 190 XML suites and 2,246 tests with zero failures, errors, or skips;
  FIR and integration were freshly written and the unchanged six-test
  `dotnet.ir` root remained up-to-date. See
  [`docs/archive/common-eager-iterable-sequence-consumers-2026-08-18.md`](docs/archive/common-eager-iterable-sequence-consumers-2026-08-18.md).
- Earlier completed Common collection feature: all four eager generated
  `Iterable.windowed`/`chunked` variants and the exact Common
  `List(size, init)`/`MutableList(size, init)` prerequisite are published on
  `Kotlin.Collections.CollectionsKt`. Common retains both its RandomAccess and
  iterator/RingBuffer algorithms, snapshot versus reused-transform-view
  behavior, traversal/failure timing, and validation messages. Direct,
  installed, and portable products execute on Framework CLR 4 and .NET 10.
  The final full target aggregate exited successfully. Its direct audit covers
  190 XML suites and 2,242 tests with zero failures, errors, or skips: the 187
  FIR suites and two integration suites were freshly written, while the
  unchanged six-test `dotnet.ir` unit suite remained up-to-date from its prior
  green checkpoint. See
  [`docs/archive/common-eager-iterable-windowing-2026-08-18.md`](docs/archive/common-eager-iterable-windowing-2026-08-18.md).
- Atomic generic-owner public migration checkpoint: **no-go for now**. The
  schema-20 hostile and OctoTree candidates prove the CLR representation, but
  they remain generated C# physicalizations rather than complete Kotlin-
  emitted DLL/KLIB products. Two temporary, uncommitted rehearsal probes
  confirmed that changing only TypeDef arity recreates the rejected half-model.
  A global switch makes Runtime/Stdlib bodies, bridges, statics, open-nullable
  carriers, and classifier tests disagree with their constructed owners. A
  bounded `Box<T>` switch reaches normal compilation but then fails where the
  existing erased-contract covariant-return bridge tries to convert concrete
  `int32`/`string` to open owner `!0`. Typed/semantic member families must be
  materialized before bridge lowering, and the self-describing binding epoch,
  consumers, Runtime, and Stdlib must change in that same tranche. Production
  Kotlin generic owners therefore remain erased; no per-owner switch or naming
  annotation is authorized. Reopen only for a complete Kotlin-emitter cutover
  and inverse-rollback rehearsal. Ordinary language/application development
  continues without freezing new erased-owner assumptions. See
  [`docs/archive/generic-owner-atomic-cutover-checkpoint-2026-08-17.md`](docs/archive/generic-owner-atomic-cutover-checkpoint-2026-08-17.md).
- Last completed generic-owner migration condition: physical-family schema 20
  now closes exact `TypeDef.BaseType`/`InterfaceImpl` ancestry and its nullable-
  reference metadata. Live IR contributes the complete logical supertype tree.
  The bound producer artifact records one exact physical base and every direct
  interface, resolves class and generic-interface TypeDefs from the producer
  catalog, and requires base-delegating constructors to target that same row.
  Capability interfaces appear directly only on the TypeDef which owns their
  dispatchers; inherited dispatchers do not duplicate `InterfaceImpl`, and
  method-free owners receive no empty capability. The hostile exact case emits
  `ReferenceBase<TypedStore<T>?>` plus
  `Marker<AbstractPropertyStorage<T>?>`. Its Kotlin-logical nullable vector is
  `1,2,1`, while raw Roslyn ancestry metadata truthfully uses the physical root
  sentinel `0,2,1`; schema 20 preserves that layer boundary instead of
  flattening either edge. The counterexample `Base<T?>` remains deterministically
  erased-only because one unconstrained CLR `T` cannot select a single fixed
  nullable base for both value and reference substitutions. Its classification
  records the exact blocked classifier and owner-parameter index.
  A record-driven C# producer and raw `System.Reflection.Metadata` consumer
  verify both constructed signatures, nullable blobs, the generic marker
  TypeDef, constructor/base agreement, and non-duplicated capabilities on .NET
  10 and Framework 4.8. The general recursive metadata inspector now consumes
  the recorded ancestry as well. Schema-negative tests reject missing bases,
  mismatched constructor targets, invalid root flags, missing/colliding generic
  interfaces, unknown supertype flags, capability duplication/omission, and
  invalid conditional blockers. The PSI/LightTree x profile ordinary/separate
  hostile matrix covers four suites and eight products with zero failures,
  errors, or skips. The regenerated closed-application bundles are equivalent
  across PSI/LightTree on both profiles and execute every candidate/erased/C#
  product. The four-lane dynamic trace remains exactly 18 exact, 12 semantic-
  capability, 24 erased-owner, and one intentional missing route: 55 producer
  plus 11 unrelated events. Production Kotlin-owned owners remain erased. The
  then-remaining gate was the one atomic public-owner migration checkpoint,
  whose no-go-for-now outcome is recorded above. The final strict aggregate
  and explicit freshness rerun cover 190 freshly written XML suites and 2,238
  tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-direct-supertype-metadata-2026-08-17.md`](docs/archive/generic-owner-direct-supertype-metadata-2026-08-17.md).
- The preceding generic-owner migration condition: physical-family schema 19
  now closes overload/generated-name collisions without sacrificing natural C#
  overloads. Two hostile Kotlin `collide` declarations have distinct typed CLR
  parameter types but identical semantic `object` parameter types. Typed
  entries retain `collide`; semantic hooks and capability slots receive an
  unconditional digest of the complete sorted logical override-root set, while
  masked-default helpers use the logical declaration key. Adding an overload
  therefore cannot rename an existing generated slot, and a separately
  compiled override shares the producer's family identity. The artifact is the
  sole binding authority; consumers reject the old reconstructible suffixes.
  Validation now applies C# overload identity as well as CLR MethodDef identity:
  return type, static/instance distinction, and nullable metadata cannot hide a
  duplicate method, and methods/properties/fields cannot collide by source
  name. A generated producer and external C# subclass exercise both the natural
  typed overload and its protected semantic hook; raw metadata, reflection,
  interface maps, ordinary Kotlin, and separate Kotlin consumers pin every
  role on Framework 4.8 and .NET 10. The closed route corpus now contains 24
  erased-owner, 18 exact-entry, 12 semantic-capability, and one missing route.
  Its four-lane PSI/LightTree x profile dynamic verifier observes exactly 55
  producer events plus 11 unrelated events with identical sparse manifests.
  The closed application verifier regenerated PSI/LightTree bundles for both
  profiles, proved their executable/KLIB equivalence, and executed the
  candidate, erased Kotlin, and erased C# products. The focused ordinary,
  separate, and recursive matrix covers four suites and 12 products with zero
  failures, errors, or skips.
  This compiler-owned ABI allocation intentionally does not invent a public
  `DotNetName`; explicit export naming remains a separate design. Production
  Kotlin-owned generic owners remain erased; base/interface nullable transforms
  and the atomic ABI migration remain open. The final strict aggregate exited
  successfully; all 187 result suites were freshly written, and their 2,107
  tests contain zero failures, errors, or skips. See
  [`docs/archive/generic-owner-overload-family-names-2026-08-17.md`](docs/archive/generic-owner-overload-family-names-2026-08-17.md).
- The preceding generic-owner migration condition: physical-family schema 18
  now closes abstract broad-property obligations across Kotlin and foreign C#
  subclasses. A new hostile `HostileAbstractProperty<out T>.exposed` has no
  body or state from which the old planner could infer a raw getter. Its
  abstract broad setter now induces an explicit
  `ABSTRACT_BROAD_PROPERTY_OBLIGATION` on the paired getter. Both typed
  PropertyDef accessors and both protected semantic hooks remain abstract,
  while the two explicit capability dispatchers remain private/final concrete
  methods on the base. Roslyn rejects a typed-property-only concrete C#
  subclass on Framework 4.8 and .NET 10; a complete subclass implements both
  domains and preserves compatible typed overrides, incompatible semantic
  writes, raw reads, delayed typed failure, and recovery with one `object`
  field. This gate exposed and fixes a real planner ordering defect: a concrete
  Kotlin override inherited the semantic methods only after its field had
  already been classified as typed. Inherited logical semantic obligations
  now taint the complete local call/field graph before storage selection, so
  the concrete override records one semantic field and no typed shadow.
  Schema validation rejects half-abstract or falsely concrete obligation
  families, and the hostile census changes by exactly two exact and three
  semantic property calls with no new missing capability. The focused
  ordinary/separate/recursive PSI/LightTree x profile matrix covers 12
  products with zero failures, errors, or skips. Production Kotlin-owned
  generic owners remain erased; overload/name collisions, base/interface
  nullable transforms, and the atomic ABI migration remain open. The final
  strict aggregate exited successfully; the direct XML audit covers 187
  suites and 2,107 tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-abstract-broad-property-obligation-2026-08-17.md`](docs/archive/generic-owner-abstract-broad-property-obligation-2026-08-17.md).
- The preceding generic-owner migration condition: physical-family schema 17
  now records complete semantic routing for direct CLR properties whose Kotlin
  domain is broader than their natural C# type. The hostile covariant
  `HostileUnsafeStore<out T>.exposed` remains one `object` state carrier:
  ordinary C# sees virtual `T exposed { get; set; }`; a compatible capability
  write dispatches through that property and therefore observes an external
  C# override; an incompatible widened write dispatches through the protected
  semantic setter without narrowing; widened read uses the paired raw semantic
  getter; and the next typed property read fails at its actual checked-cast/
  unbox boundary. An external C# subclass overrides both the typed property and
  semantic setter on Framework 4.8 and .NET 10. Schema 17 stores getter routing
  (`TYPED_ENTRY` or `SEMANTIC_HOOK`) and setter routing (`ABSENT`,
  `TYPED_ENTRY`, or `COMPATIBLE_TYPED_ELSE_SEMANTIC_HOOK`) beside the existing
  typed PropertyDef accessor identities. Family validation joins those routes
  to the exact semantic/capability member roles and the same physical state;
  unknown routes, typed-only routing of a broad accessor, partial accessors,
  and broken state joins fail closed. The hostile static census now attributes
  one exact and three semantic property calls without creating a missing-
  capability route. The focused ordinary/separate/recursive PSI/LightTree x
  profile matrix covers 12 products with zero failures, errors, or skips.
  Production Kotlin-owned generic owners remain erased; overload/name
  collisions, base/interface nullable transforms, and the atomic ABI migration
  remain open. The final strict aggregate exited successfully; the direct XML
  audit covers 187 suites and 2,107 tests with zero failures, errors, or skips.
  See
  [`docs/archive/generic-owner-broad-property-routing-2026-08-17.md`](docs/archive/generic-owner-broad-property-routing-2026-08-17.md).
- The preceding generic-owner migration condition: physical-family schema 16
  now preserves Roslyn nullable-reference transforms at every direct generic-
  owner value position. Nullability is attached to each use, not folded into
  the physical type identity: the same `object` carrier may be non-null,
  nullable, or oblivious. Compiler-derived transforms are captured while live
  IR is still available, carried through path-unbound prototypes, atomically
  bound into MethodDef, Property, and state records, and rejected when their
  structural count or accessor/state join disagrees. The complete OctoTree
  product emits `Node<T>?`, `Node<T>?[]`, exact `T`, and the deliberately
  widened nullable `object` carriers under `#nullable enable`; producer
  compilation treats every warning as an error. A raw ECMA-335 inspector
  decodes scalar/vector `NullableAttribute` blobs and effective
  `NullableContextAttribute` fallback on fields, properties, returns, and
  parameters on Framework 4.8 and .NET 10. This exposed and fixed an existing
  export defect: an unmarked exact CLR type-parameter position used flag `0`,
  while Roslyn's truthful encoding is non-null flag `1`; nullable substitutions
  remain permitted because no CLR generic constraint was added. Open `T?`
  stays on its semantic nullable `object` carrier. Unknown flags, malformed
  transform lengths, property/accessor disagreement, state/access-path
  disagreement, and partial schema records fail closed. The focused
  PSI/LightTree x profile matrix covers eight products with zero failures,
  errors, or skips. Production Kotlin-owned generic owners remain erased; this
  closes nullable direct-surface metadata, not the atomic public ABI migration.
  The final strict aggregate exited successfully; the direct XML audit covers
  187 suites and 2,107 tests with zero failures, errors, or skips.
  See
  [`docs/archive/generic-owner-nullable-surface-2026-08-17.md`](docs/archive/generic-owner-nullable-surface-2026-08-17.md).
- The preceding generic-owner migration condition: physical-family schema 15
  now records a real direct CLR Property surface over existing typed-entry
  MethodDefs. Compiler-derived prototype facts carry getter/setter kind and the
  producer-selected physical Property name; the artifact binds that name and
  type to the logical getter/setter KLIB keys without inventing a property key,
  shadow state, or consumer name reconstruction. Semantic hooks and explicit
  non-generic capability dispatchers cannot appear as ordinary property
  accessors. The decoded OctoTree family exposes getter-only `depth: int`,
  get/set `Leaf.value: T`, and getter-only `Branch.nodes: Node<T>[]`; separate
  C# consumers use property syntax and verify the same typed fields remain
  visible through capability calls on Framework 4.8 and .NET 10. The exhaustive
  raw ECMA-335 reader now pins Property/PropertyMap rows, signatures, and
  getter/setter MethodSemantics. The first full run found one concrete Roslyn
  authoring defect: private fields and public properties shared the same C#
  names. Producer-selected hidden backing-field names fixed the collision while
  retaining exactly one field and one object identity. Duplicate/partial/
  mismatched records, capability accessors, and fake-override republication
  fail closed. The focused PSI/LightTree x profile matrix covers four products
  with zero failures, errors, or skips. Production owners remain erased;
  nullable annotations, collision policy, broad properties, and atomic ABI
  migration remain open. The final strict aggregate exited successfully; the
  direct audit covers 190 XML files and 2,238 tests with zero failures, errors,
  or skips. See
  [`docs/archive/generic-owner-direct-property-surface-2026-08-17.md`](docs/archive/generic-owner-direct-property-surface-2026-08-17.md).
- The preceding generic-owner migration condition: physical-family schema 14
  now makes memory semantics and constructor input conversion explicit for
  every state. In the hostile owner, plain `stored: T` remains a true CLR `!T`
  field, while owner-dependent volatile `published: T` uses one volatile
  `object` field with widening/boxing construction and writes plus checked
  cast/unbox reads. Typed and non-generic semantic-capability access share that
  field; an incompatible capability write fails before mutation. A semantic-
  widened volatile field retains semantic object authority, and one volatile
  sibling does not erase independently proven typed fields. Separate C#
  consumers run multi-threaded handoff on Framework 4.8 and CoreCLR. The
  focused PSI/LightTree x profile matrix covers eight tests with zero failures,
  errors, or skips; the correctness-only Framework/JIT/ReadyToRun/trimmed/
  NativeAOT deployment run agrees on checksum `16564`. Net10/net48 manifest
  hashes are respectively
  `ebf81a1c283c75eca8f02d7187ca11be508382e3717b982a97b2bbb8c97ecd9a`
  and `c6297589b7940cf828ba17fd4e762b80921e49d34d9946d840ed60bcd62f19f3`;
  physical artifact hash is
  `f4440e253d8f476342afc15da79ec07683c647812e42786769cb56fbbdf72ad0`.
  A fresh four-lane instrumented trace confirms 42 producer plus nine unrelated
  events and rejects an internally inconsistent route oracle before generation.
  The strict aggregate exited successfully; direct audit covers 190 XML files
  and 2,238 tests with zero failures, errors, or skips.
  This closes a migration condition only: production Kotlin-owned generic
  owners remain erased and the public concurrency/atomic surface remains
  parked. See
  [`docs/archive/generic-owner-one-state-memory-model-2026-08-17.md`](docs/archive/generic-owner-one-state-memory-model-2026-08-17.md).
- Last completed product correction: Runtime surface 38 and production
  structural-equality codegen now provide one exact same-open-`T` generic
  entry. References, null, Float/Double, and nullable floating types retain
  the universal object helper; other value types box the right operand and
  use constrained left-biased `Object.Equals`, never `IEquatable<T>` or a CLR
  comparer. Mixed physical parameters remain on the object fallback. The
  emitted generic caller has no `box !!0`. A deliberately conflicting
  `IEquatable<T>`/`object.Equals` struct, asymmetric reference equality,
  nullable floating rules, Framework 4.8, JIT, ReadyToRun, full trimming, and
  real NativeAOT pin the boundary. The corrected OctoTree candidate saves
  5,861,184 bytes on Framework/JIT/ReadyToRun/trimmed and 11,722,368 on
  NativeAOT versus its two-box baseline. Aggregate candidate/erased ratios are
  1.65x Framework, 0.87x JIT, 1.02x ReadyToRun, 0.84x trimmed, and 0.73x
  NativeAOT; managed allocation excess falls to about 34%, while NativeAOT
  allocates 11.25% less. Capability allocation and Framework dispatch remain
  material independent costs, so equality is closed without selecting the
  owner ABI. Production Kotlin-owned class owners remain erased. The final
  strict aggregate direct audit covers 190 XML files and 2,238 tests with zero
  failures, errors, or skips. See
  [`docs/archive/generic-open-equality-lower-boxing-2026-08-17.md`](docs/archive/generic-open-equality-lower-boxing-2026-08-17.md).
- The preceding product correction: the large-only audit found that the
  generated OctoTree candidate used CLR `EqualityComparer<T>.Default` for
  ordinary generic Kotlin `==`, while the real backend boxes open `T` and
  calls `Kotlin.Runtime.Internal.Intrinsics.AreEqual(object, object)`. The old
  lowering was semantically wrong for signed zero and made the typed-root
  timing/allocation result invalid. The candidate now calls the real Runtime
  helper, deploys Runtime in every lane, and is guarded by generic Float/Double
  signed-zero plus distinct-NaN-payload oracles; the corpus rejects any CLR
  comparer. The corrected aggregate candidate/erased ratios are 1.68x
  Framework, 0.91x JIT, 1.10x ReadyToRun, 0.92x trimmed, and 0.89x NativeAOT,
  with 72.3%-76.6% more allocation. Typed/capability/cluster routes expose
  243%-343% excess allocation from ordinary equality boxing; rendering remains
  separately lowering-confounded. The structural private `Node<T>` root proof
  remains valid, but all preceding OctoTree performance evidence is superseded
  for owner selection. Production generic owners and ordinary Kotlin emission
  remain unchanged. The final strict aggregate direct audit covers 190 XML
  files and 2,238 tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-octo-tree-kotlin-equality-measurement-2026-08-17.md`](docs/archive/generic-owner-octo-tree-kotlin-equality-measurement-2026-08-17.md).
- The preceding product slice: the separate-compilation OctoTree candidate
  now stores its private `root: Node<T>?` as a real `OctoTreeNode<T>` CLR
  reference and keeps the non-generic semantic capability only at the
  open-world boundary. Null supplies typed write evidence only for a proven
  local non-value generic class reference with an exact invariant carrier;
  constructor writes must independently prove the same carrier. Bare
  unconstrained `T?`, `C<T?>`, external/projected/unresolved classifiers, and
  same-compilation owners without a selected logical TypeDef path remain
  fail-closed semantic. Private typed identity accessors stay outside KLIB and
  logical reflection. The regenerated PSI/LightTree x Framework 4.8/.NET 10
  schema-3 corpus retains 21 exact plus nine semantic static sites and proves
  open/closed root fields and private accessors use `Node<T>`/`Node<int>`.
  Its first five-lane measurement reported broad speed/allocation wins, but
  that evidence used a non-Kotlin CLR equality lowering and is superseded by
  the corrected measurement above. Typed and cluster owner routes still have
  zero internal semantic calls/checks; the external capability retains its
  required boundary.
  Production owners/emission, DLL/KLIB, Runtime, Common semantics, and public
  C# ABI remain unchanged. The final strict aggregate direct audit covers 190
  XML files and 2,238 tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-octo-tree-typed-private-root-2026-08-17.md`](docs/archive/generic-owner-octo-tree-typed-private-root-2026-08-17.md).
- The preceding product slice: the schema-13 OctoTree candidate now has one
  closed, paired schema-3 application corpus generated independently through
  PSI/LightTree on Framework 4.8 and .NET 10. It fingerprints the unchanged
  Kotlin/Native source, separate Kotlin oracle, candidate/erased producers,
  checksum-identical C# applications, physical-family artifact, and the
  compiler's 21 exact plus nine semantic resolved call sites. The verifier
  pins the mixed field model (`root: object`, `Leaf.value: T0`, and
  `Branch.nodes: Node<T0>[]`) and found real standalone-product defects in
  Framework argument invocation, dependency staging, assembly identity, and
  logical-key path validation. The older hostile schema-2 corpus remains
  accepted. At 200,000 iterations and five throughput runs the aggregate
  candidate/erased ratios are 2.41x Framework, 1.05x JIT, 1.29x ReadyToRun,
  1.23x trimmed, and 1.06x NativeAOT, with 23.0%-27.3% more allocation. Route
  attribution shows typed/capability costs of 1.38x-1.83x on modern lanes and
  5.14x/5.59x on Framework, while clusterization reaches 0.73x JIT and 0.77x
  NativeAOT. Rendering is faster but remains lowering-confounded because the
  candidate is generated C#, not a complete Kotlin product. These performance
  values are also superseded because the same candidate used non-Kotlin CLR
  generic equality. The large-only
  audit found one material next proof: the private `Node<T>?` root's null
  initializer is currently classified from `Nothing?` as semantic object,
  even though null is representation-neutral for that proven reference
  carrier. Bare unconstrained `T?` must remain excluded. Production emission,
  public ABI, KLIB, Runtime, and Common semantics remain unchanged. The final
  strict aggregate direct audit covers 190 XML files and 2,238 tests with zero
  failures, errors, or skips. See
  [`docs/archive/generic-owner-octo-tree-paired-measurement-2026-08-17.md`](docs/archive/generic-owner-octo-tree-paired-measurement-2026-08-17.md).
- The preceding product slice: schema 13 closes the ordinary state/helper/body
  graph of the decoded OctoTree candidate. The compiler now projects every
  direct generic-owner field while restricting generic semantic taint to
  owner-parameter-dependent carriers. Declaration-independent fields retain
  their exact non-owner type, initializer, access paths, and init-only flag;
  `Tree.depth` is readonly `int`, while mutable `Tree.actual` is real `bool`
  state with an explicit default-zero recipe and transitive `Tree.set` write.
  A real private non-KLIB helper has no logical binding or capability role and
  is instead recorded as an exact private-final implementation MethodDef hidden
  from reflection. The record-driven C# producer now implements the complete
  recursive Tree/Branch algorithms rather than scenario substitutes. A
  separately compiled consumer reflects the real fields, proves set resets
  `actual`, executes 512 checkerboard writes/reads, and proves eight unique
  depth-one writes collapse a Branch to a Leaf. The exhaustive metadata model
  now covers eight family TypeDefs, four GenericParams, four InterfaceImpls,
  eight MethodImpls, five fields, and 37 MethodDefs. The focused matrix covers
  8 tests and the combined hostile-plus-OctoTree matrix covers 16, both with
  zero failures, errors, or skips. Production owners/emission, DLL/KLIB,
  Runtime, Common semantics, and the public C# surface remain unchanged. The
  final coherent post-restart strict aggregate completed in 1,895.0 seconds;
  direct audit covers 190 XML files and 2,238 tests with zero failures, errors,
  or skips. See
  [`docs/archive/generic-owner-octo-tree-ordinary-body-closure-2026-08-17.md`](docs/archive/generic-owner-octo-tree-ordinary-body-closure-2026-08-17.md).
- The preceding product slice: schema 12 closes whole-family metadata
  and reflection normalization for the decoded OctoTree candidate. KLIB first
  selects the logical classifier; within that context every exact typed,
  capability-interface, and dispatcher MethodDef maps to one logical callable.
  Capability TypeDefs and producer-private state methods remain hidden. The
  classifier context is necessary: the hostile derived owner legally shares
  its base capability TypeDef and inherited dispatcher while retaining a
  distinct logical override declaration. A separate net10
  `System.Reflection.Metadata` executable reads each Framework 4.8 or .NET 10
  candidate PE and requires the exact exhaustive TypeDef, GenericParam,
  InterfaceImpl, MethodImpl, field, method, signature, and flag rows without
  textual IL or ILAsm. Assembly TypeDefs are profile-exact, including only
  Framework Roslyn's two required embedded support attributes beyond the
  family. That audit found that recorded Leaf/Branch `ToString`
  slots were absent from the prior C# product while inherited Object rendering
  let execution pass; both overrides now materialize. Runtime execution still
  independently proves the same one-object graph on Framework CLR 4 and
  CoreCLR. The focused matrix covers 8 tests and the combined hostile-plus-
  OctoTree matrix covers 16, both with zero failures, errors, or skips.
  Production owners/emission, DLL/KLIB, Runtime, Common semantics, and the
  public C# surface remain unchanged. The final warm-cache strict aggregate
  completed in 636.5 seconds; direct audit covers 190 XML files and
  2,238 tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-octo-tree-metadata-reflection-product-2026-08-17.md`](docs/archive/generic-owner-octo-tree-metadata-reflection-product-2026-08-17.md).
- The preceding product slice: the schema-12 decoded OctoTree candidate now
  materializes its outer open `OctoTree<T>` owner, public constructor and
  typed member slots over the recorded private semantic `object` root. Its
  non-generic get/set interface uses truthful object carriers, and the set
  dispatcher casts/unboxes before typed entry. The physicalizer consumes the
  real compiler census and requires Tree.get-to-Leaf-read, Tree.get-to-Branch-
  read, and Tree.set-to-Node.set to remain semantic-capability calls. A
  separately compiled C# product proves typed/capability calls share one
  Branch/Leaf graph, incompatible input fails before root/child mutation, the
  Tree remains externally subclassable, and an external child inherits the
  base-declared private/final dispatchers. Declaration-independent depth
  storage/calculation remain explicitly named scenario-body details, not
  generic-owner ABI evidence. The focused hostile-plus-OctoTree PSI/LightTree
  x Framework 4.8/.NET 10 same/separate-compilation matrix covers 16 tests with
  zero failures, errors, or skips. Production owners/emission, DLL/KLIB,
  Runtime, Common semantics, and the public C# surface remain unchanged. The
  final warm-cache strict aggregate completed in 660.2 seconds; direct audit
  covers 190 XML files and 2,238 tests with zero failures, errors, or skips.
  See
  [`docs/archive/generic-owner-octo-tree-root-product-2026-08-17.md`](docs/archive/generic-owner-octo-tree-root-product-2026-08-17.md).
- The preceding product slice: the schema-12 decoded OctoTree candidate now
  materializes the recorded state-access capability families over its one
  physical generic state. Leaf's non-generic capability reads and writes the
  same private `!T` field through `object`; its explicit write dispatcher
  casts/unboxes before invoking the typed writer. Branch's capability returns
  the same private `Node<T>[]` through `System.Array`, without copying or
  wrapping. Separately compiled C# proves Leaf value boxing at only the
  capability boundary, an incompatible string fails before mutation, and the
  Branch capability/typed getter/reflected field all expose the identical
  `Node<int>[]` object. Interface maps prove the three new targets are
  private/virtual/final on their closed Leaf or Branch TypeDefs. The focused
  hostile-plus-OctoTree PSI/LightTree x Framework 4.8/.NET 10 same/separate-
  compilation matrix covers 16 tests with zero failures, errors, or skips.
  Production owners/emission, DLL/KLIB, Runtime, Common semantics, and the
  public C# surface remain unchanged. The final warm-cache strict aggregate
  completed in 626.2 seconds; direct audit covers 190 XML files and 2,238
  tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-octo-tree-state-capabilities-2026-08-17.md`](docs/archive/generic-owner-octo-tree-state-capabilities-2026-08-17.md).
- The preceding product slice: the schema-12 decoded OctoTree candidate now
  completes the strict `Node.set` family with each owner's recorded
  non-generic capability interface and private-final explicit dispatcher. The
  capability signature uses `object` only for the strict owner input; each
  dispatcher casts/unboxes to its typed `!T` slot and invokes virtual dispatch.
  Separately compiled C# proves that an inherited Node capability on Branch and
  Branch's owner-specific capability both reach Branch's most-derived typed
  override and the same true `Node<int>[]` state. An incompatible string throws
  before mutation, while Leaf's compatible capability reaches its throwing
  typed override. Interface maps prove private/virtual/final targets with the
  inherited Node dispatcher declared on `Node<int>` and the Branch dispatcher
  on `Branch<int>`. The focused hostile-plus-OctoTree PSI/LightTree x Framework
  4.8/.NET 10 same/separate-compilation matrix covers 16 tests with zero
  failures, errors, or skips. Production owners/emission, DLL/KLIB, Runtime,
  Common semantics, and the public C# surface remain unchanged. The final
  warm-cache strict aggregate completed in 641.5 seconds; direct audit covers
  190 XML files and 2,238 tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-octo-tree-strict-capability-2026-08-17.md`](docs/archive/generic-owner-octo-tree-strict-capability-2026-08-17.md).
- The preceding product slice: the schema-12 decoded OctoTree candidate now
  materializes the public abstract `Node.set(int, int, int, !T, int): bool`
  typed MethodDef and the exact Leaf/Branch overrides. The final child TypeDefs
  close inheritance, while their recorded override slots correctly remain
  non-final virtual (`OVERRIDABLE`), so the C# product uses `override`, not an
  inferred `sealed override`. Recorded typed identity state paths also become
  real `Leaf.value` `!T` read/write and `Branch.nodes` `Node<T>[]` read methods
  over the same private fields. Separately compiled C# mutates `Leaf<int>`,
  observes getter/reflection field identity, dispatches through a `Node<int>`
  base reference to Branch's most-derived body and true typed state, and
  observes Leaf's throwing override. The negative external subclass now
  implements the exact abstract slot and still fails only at the sealed-base
  constructor boundary. The focused hostile-plus-OctoTree PSI/LightTree x
  Framework 4.8/.NET 10 same/separate-compilation matrix covers 16 tests with
  zero failures, errors, or skips. Production owners/emission, DLL/KLIB,
  Runtime, Common semantics, and the public C# surface remain unchanged. The
  final warm-cache strict aggregate completed in 628.4 seconds; direct audit
  covers 190 XML files and 2,238 tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-octo-tree-typed-callables-2026-08-17.md`](docs/archive/generic-owner-octo-tree-typed-callables-2026-08-17.md).
- The preceding product slice: the schema-12 decoded OctoTree candidate now
  physicalizes final `Branch<T> : Node<T>` with its two exact public
  constructors, true private `Node<T>[]` field, and fixed zeroed eight-element
  initializer on only the base-delegating constructor root. The secondary
  `(T, int)` constructor follows its recorded `this()` edge before its bounded
  source-body oracle populates seven recorded `Leaf<T>` values. A separately
  compiled C# consumer constructs empty and filled `Branch<int>` instances and
  proves the open field is `Node<T>[]`, the closed field is `Node<int>[]`, each
  vector is distinct and length eight, the excluded slot stays null, and every
  other true-`int` Leaf state is 7. The existing sealed-base positive/negative
  probes remain active. The focused hostile-plus-OctoTree PSI/LightTree x
  Framework 4.8/.NET 10 same/separate-compilation matrix covers 16 tests with
  zero failures, errors, or skips. Production owners/emission, DLL/KLIB,
  Runtime, Common semantics, and the public C# surface remain unchanged. The
  final warm-cache strict aggregate completed in 641.6 seconds; direct audit
  covers 190 XML files and 2,238 tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-octo-tree-branch-product-2026-08-17.md`](docs/archive/generic-owner-octo-tree-branch-product-2026-08-17.md).
- The preceding foundation: generic-owner physical-family schema 12 now
  gives Kotlin sealed generic owners an exact CLR construction closure. Kotlin
  `sealed` is recorded as logical closed-subclass policy, not the unusable CLI
  sealed TypeDef flag: the physical base is abstract and its constructors must
  be `FamilyAndAssembly` (`private protected`), so only a derived TypeDef in the
  producer assembly can invoke them. Plain protected, assembly-only, and
  protected-or-assembly forms fail closed. A canonical decoded OctoTree record
  drives a bounded real C# producer with abstract `Node<T>`, sealed `Leaf<T>`,
  true private `T` state, and exact positional constructor initialization. A
  separately compiled C# executable constructs `Leaf<int>(42)` and reflects
  the open GenericParam/closed int field plus constructor flags; an independent
  external C# subclass must fail compilation. Both products are proved on
  Framework 4.8 and .NET 10. The focused hostile-plus-OctoTree PSI/LightTree x
  Framework 4.8/.NET 10 same/separate-compilation matrix covers 16 tests with
  zero failures, errors, or skips. Production owners/emission, DLL/KLIB,
  Runtime, Common semantics, and the public C# surface remain unchanged. The
  final strict aggregate completed in 1,890.8 seconds; direct audit covers 190
  XML files and 2,238 tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-sealed-construction-closure-2026-08-17.md`](docs/archive/generic-owner-sealed-construction-closure-2026-08-17.md).
- The preceding foundation: generic-owner physical-family schema 11 now
  binds the complete logical-keyed recursive OctoTree family as one atomic
  producer graph. Its four owner TypeDefs, MethodDefs, GenericParams,
  constructors, member roles, state, initialization, capabilities, and
  reflection records must close over the same recorded physical owners.
  `root` remains private object state with hidden producer-private access,
  `Branch.nodes` is exact `Node<T>[]` with its fixed eight-element initializer,
  and `Leaf.value` is true `!T` state initialized from its exact logical
  constructor parameter. A proven explicit null is normalized to the CLR
  object-field default; unsupported initializers, phantom producer TypeDefs/
  MethodDef owners, missing recursive owners, and constructor/state carrier
  mismatches fail closed. Canonical schema-11 bytes and diagnostic-label
  invariance are pinned in the separate producer. The focused hostile-plus-
  OctoTree PSI/LightTree x Framework 4.8/.NET 10 same/separate-compilation
  matrix covers 16 tests with zero failures, errors, or skips. Production
  owners/emission, DLL/KLIB, Runtime, Common semantics, and the public C#
  surface remain unchanged. The final strict aggregate completed in 1,879.0
  seconds; direct audit covers 190 XML files and 2,238 tests with zero
  failures, errors, or skips. See
  [`docs/archive/generic-owner-complete-octo-tree-family-2026-08-17.md`](docs/archive/generic-owner-complete-octo-tree-family-2026-08-17.md).
- The preceding foundation: generic-owner physical-family schema 10 now
  serializes the compiler-derived physical visibility and dispatch of every
  owner TypeDef and the exact visibility of every member MethodDef slot. A
  decoded producer no longer needs compiler-local prototypes to distinguish
  public/internal or final/open/abstract/sealed owners. Typed entries retain
  source visibility, semantic hooks are required to be protected, and explicit
  capability dispatchers are required to be private and final. Canonical
  schema-10 bytes retain the declaration envelope; stale schema 9, a public
  semantic hook, and a public capability implementation fail closed. The
  focused PSI/LightTree x Framework 4.8/.NET 10 same/separate-compilation
  matrix covers eight tests with zero failures, errors, or skips. Production
  owners/emission, DLL/KLIB, Runtime, and Common semantics remain unchanged.
  The final strict aggregate completed in 1,860.0 seconds; direct audit covers
  190 XML files and 2,238 tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-physical-visibility-dispatch-2026-08-16.md`](docs/archive/generic-owner-physical-visibility-dispatch-2026-08-16.md).
- The preceding foundation: generic-owner physical-family schema 9 now
  distinguishes logical member-family state paths from exact producer-private
  MethodDefs. A private typed identity read or write records no fictitious
  KLIB callable key, member role, or reflection entry; it must be private, live
  on the same physical TypeDef, obey the complete carrier/GenericParam grammar,
  and not collide with any logical member MethodDef. Semantic-object state may
  use exactly paired private typed identity READ/WRITE paths, but the presence
  of any semantic path or boundary conversion still requires the complete
  paired typed/semantic READ and WRITE matrix. The schema-9 codec retains the
  binding kind, nullable logical identity, and exact private visibility and
  rejects stale schema 8. Positive round-trip/reflection evidence plus five
  opposing malformed families pin the boundary. The focused PSI/LightTree x
  Framework 4.8/.NET 10 same/separate-compilation matrix covers 16 tests with
  zero failures, errors, or skips. Production owners/emission, DLL/KLIB,
  Runtime, and Common semantics remain unchanged. The final strict aggregate
  completed in 1,835.0 seconds; direct audit covers 190 XML files and 2,238
  tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-producer-private-state-access-2026-08-16.md`](docs/archive/generic-owner-producer-private-state-access-2026-08-16.md).
- The preceding foundation: generic-owner physical-family schema 8 now
  serializes a bounded fixed zeroed SZ-array state initializer with its exact
  non-negative element count and sorted base-delegating logical constructor
  roots. Member access and initialization paths jointly cover typed state READ
  and WRITE, so `Branch.nodes` will not need a fictitious setter MethodDef.
  Initializers require exact owner-dependent SZ-array storage; semantic-object
  state, missing or `this`-delegating constructor roots, incomplete access,
  duplicate recipes, and stale schema 7 fail closed. A fully validated
  synthetic `T[]` family updates its read MethodDef/reflection record, replaces
  its ordinary write path with an eight-element initializer, and round-trips
  canonical schema-8 bytes. The unchanged OctoTree graph independently pins
  one base and one `this` constructor edge. The focused PSI/LightTree x
  Framework 4.8/.NET 10 matrix covers 16 tests with zero failures, errors, or
  skips. Production owners/emission, DLL/KLIB, Runtime, and Common semantics
  remain unchanged. The final strict aggregate completed in 1,895.4 seconds;
  direct audit covers 190 XML files and 2,238 tests with zero failures, errors,
  or skips. See
  [`docs/archive/generic-owner-physical-state-initializers-2026-08-16.md`](docs/archive/generic-owner-physical-state-initializers-2026-08-16.md).
- The preceding foundation: explicit generic-owner field initializers now
  retain a bounded compiler-derived physical recipe instead of being reduced
  to write provenance. A fixed `arrayOfNulls` vector is admitted only when its
  invariant element is a local Kotlin generic classifier retaining the current
  owner parameter and its length is an exact integer constant; every other
  explicit initializer is recorded as unsupported. The unchanged recursive
  OctoTree producer therefore couples `Branch.nodes: Node<T>[]` to an exact
  zeroed eight-element vector recipe. The unchanged ArrayCopy benchmark's
  `arrayOfNulls<Any>(capacity) as Array<T?>` remains an unsupported initializer
  with semantic-object state, so a logical cast cannot manufacture physical
  evidence. Same-compilation classifiers without a stable producer key also
  fail closed instead of deriving a TypeDef path from a display name. The
  focused PSI/LightTree x Framework 4.8/.NET 10 plus IL-text repair matrix
  covers 14 tests with zero failures, errors, or skips. Production owners/
  emission, physical artifact schema, DLL/KLIB, Runtime, and Common semantics
  remain unchanged. The final strict aggregate completed in 1,859.7 seconds;
  direct audit covers 190 XML files and 2,238 tests with zero failures, errors,
  or skips. See
  [`docs/archive/generic-owner-state-initializer-recipes-2026-08-16.md`](docs/archive/generic-owner-state-initializer-recipes-2026-08-16.md).
- The preceding foundation: generic-owner state and callable evidence now use
  one path-unbound prototype type tree. Constructors, members, and masked-
  default helper tails retain owner/method GenericParams, exact arrays,
  explicit `System.Array` fallbacks, and invariant Kotlin-owned generic
  classifiers by pre-lowering logical key. A complete signature binds
  atomically only after the artifact selects every referenced TypeDef path;
  missing paths, unsupported classifiers/projections, invalid method-parameter
  indices, and generic value classes fail closed. In the separately compiled
  recursive OctoTree producer, the `nodes` getter records typed `Node<T>[]` and
  capability `System.Array`; its typed return rejects an empty path map and
  binds exactly to the field's structural carrier once the Node path is
  supplied. Same-compilation snapshots without a stable library key do not
  derive ABI from a display name. External override binding now resolves local
  path-unbound signatures through the decoded producer owner map before exact
  MethodDef comparison; the hostile schema-10/C# physicalizer remains green.
  The focused PSI/LightTree x Framework 4.8/.NET 10 matrix covers eight tests
  with zero failures, errors, or skips. Production owners/emission, physical
  artifact format, DLL/KLIB, Runtime, and Common semantics remain unchanged.
  The final strict aggregate completed in 1,913.7 seconds; direct audit covers
  190 XML files and 2,238 tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-path-unbound-member-signatures-2026-08-16.md`](docs/archive/generic-owner-path-unbound-member-signatures-2026-08-16.md).
- The preceding foundation: future CLR-generic owner MethodDef evidence no
  longer claims one exact vector/GenericParam carrier where Kotlin permits
  several physical substitutions. An unconstrained open nullable `T?` input
  or output uses `object`: it can carry both a nullable reference and CLR's
  boxed `Nullable<V>` value/null form, whereas `!T` cannot. `Array<T?>` and any
  projected `Array<out/in E>` use `System.Array`; neither `!T[]` nor
  `object[]` accepts every Kotlin-valid value/reference vector. Direct non-null
  `T`, invariant `Array<T>`, and independent method-generic `Array<R>` remain
  true GenericParam carriers. OctoTree `get(): T?`, nullable-array constructor/
  result signatures, and the hostile projected `echo` family pin the boundary.
  The record-driven C# producer and derived subclass now expose `System.Array`
  on both echo MethodDefs, while the capability dispatcher still selects typed
  versus semantic override authority from one `is T[]` compatibility probe.
  Separate-compilation binding no longer treats the presence of `!T` in a
  physical type as proof of logical owner dependence: it merges the producer's
  Kotlin slot domains, then requires exact typed and semantic/capability
  physical-signature equality before recording external override MethodDefs.
  Production owners/emission, DLL/KLIB and schema format, Runtime, and Common
  semantics remain unchanged. The final cold-cache strict aggregate completed
  in 3,123.2 seconds; direct audit covers 190 XML files and 2,238 tests with
  zero failures, errors, or skips. See
  [`docs/archive/generic-owner-nonexact-call-carriers-2026-08-15.md`](docs/archive/generic-owner-nonexact-call-carriers-2026-08-15.md).
- The preceding foundation: generic-owner state evidence now retains its
  exact owner-dependent CLR carrier as a bounded, path-unbound type tree.
  Scalars, owner parameters, invariant Kotlin-owned generic classifiers, and
  SZ arrays compose structurally; nested classifiers use their pre-lowering
  logical producer keys and bind only after the complete artifact selects
  TypeDef paths. Missing paths, projections, unsupported classifiers, and open
  nullable `T?` fail closed. The hostile artifact now consumes this record
  instead of assuming every typed field is `T`. A real library/consumer split
  stages the unchanged recursive OctoTree producer independently: published
  `Branch.nodes` records `Node<T>[]`, `Leaf.value` records `T`, and semantic
  `root` retains its structural `Node<T>` candidate without changing its
  requirement. The producer owns 21 exact, nine semantic-capability, and nine
  external-family static routes; the four remaining exact sites belong to the
  separately compiled consumer. The focused PSI/LightTree x Framework
  4.8/.NET 10 matrix covers 16 tests with zero failures, errors, or skips.
  Production generic owners, physical fields, DLL/KLIB schema, Runtime, and
  C# surface remain unchanged; no OctoTree candidate product exists yet. The
  final strict aggregate completed in 2,676.6 seconds; direct audit covers 190
  XML files and 2,238 tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-structural-state-carrier-2026-08-15.md`](docs/archive/generic-owner-structural-state-carrier-2026-08-15.md).
- The preceding optimization: Common-faithful `Array<out T>.joinTo` retains
  its public `System.Array` receiver for every Kotlin-valid projected view, but
  no longer forces every compatible exact vector through per-element
  `GetValue`/box/unbox. One non-throwing `isinst T[]` selects an inline
  `ldelem T` copy of the same Common algorithm; widened value vectors and
  incompatible erased-owner state retain the original semantic fallback.
  Exact/widened value, nullable-value, transform, limit, identity, live-read,
  and failure behavior executes through PSI/LightTree on Framework 4.8 and
  .NET 10. Both stdlib products physically prove the unchanged receiver, typed
  arm, fallback arm, and absence of a helper MethodDef. A source-controlled
  checksum-identical causal tool holds `T = Int` and rendering constant: over
  800,000 loads it removes exactly 19,200,000 bytes on each runtime, one
  24-byte box per element. Median local load speedup was 1.457x on Framework
  and 1.245x on .NET 10; this is route evidence, not representative
  application performance. No cast result, KLIB, Runtime surface, public ABI,
  array identity, or erased generic-owner state changed. The final strict
  aggregate completed in 5,500.1 seconds; direct audit covers 190 XML files
  and 2,234 tests with zero failures, errors, or skips. See
  [`docs/decisions/projected-generic-array-join-fast-path.md`](docs/decisions/projected-generic-array-join-fast-path.md)
  and
  [`docs/archive/projected-generic-array-join-fast-path-2026-08-15.md`](docs/archive/projected-generic-array-join-fast-path-2026-08-15.md).
- The preceding foundation: the second exact repository application stages
  Kotlin/Native's unchanged recursive OctoTree source as a declared Gradle
  input. A bounded driver performs 512 writes, 512 checked reads, and real
  recursive rendering. Its 25 local exact sites receive 5,941 events and nine
  semantic-capability sites receive 3,096; nine external family records and
  2,728 unrelated/external events remain distinct. The exact sparse count
  vector, route bytes, and instrumented assembly agree across PSI/LightTree
  and Framework 4.8/.NET 10. The source also closed two generic-array
  foundations. Common-faithful `Array.joinTo`/`joinToString` now preserve all
  `appendElement`, transform, limit, live-view, identity, and failure
  semantics without copying the receiver to `object[]`. An erased owner still
  uses `System.Array` for direct `Array<T>`, but
  `Array<Node<T>?>` truthfully uses `Node[]` because erased Kotlin `Node<T>`
  has one declaration-stable CLR classifier. The proof requires a concrete
  classifier and rejects object, open GenericParams, constructed CLR generics,
  and unstable nested arrays; Framework's `EnumEntriesList<T>` guards the
  bounded-direct-`T` exclusion. OctoTree `root` remains semantic,
  `Branch.nodes` has an exact typed initializer, and production `Leaf.value`
  remains object-backed. This removes avoidable `System.Array` indirection but
  is correctness/state evidence, not performance, representative completion,
  a paired C# product, or production-`C<T>` authority. The final strict
  aggregate completed in 5,208.0 seconds; direct audit covers 190 XML files
  and 2,234 tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-octo-tree-application-census-2026-08-15.md`](docs/archive/generic-owner-octo-tree-application-census-2026-08-15.md).
- The preceding foundation: the compiler-indexed generic-owner counter now
  exports any same-compilation application census instead of depending on the
  hostile separate-compilation physicalizer. The first repository-owned input
  stages the exact Kotlin/Native ArrayCopy benchmark source as a declared
  Gradle input and executes its real `CustomArray<T>.add` implementation 512
  times. Its 16 local static routes and 5,664 dynamic local events are all
  exact typed-entry candidates on PSI/LightTree and Framework 4.8/.NET 10;
  the route and count artifacts are byte-identical across all four lanes.
  Eleven external static sites remain outside the local manifest; only one is
  visited and contributes the remaining 512 dynamic events. This
  does not imply typed state: the benchmark's unchecked `Any[] as Array<T?>`
  initialization correctly forces one semantic `System.Array` state and
  `add` retains a strict typed entry plus a non-narrowing capability
  dispatcher. Common `copyInto` now accepts both open `!!T[]` and that erased
  array capability while retaining evaluation order, destination identity,
  overlap safety, and Kotlin range failures. The verifier also now validates
  the codec's actual canonical Base64URL alphabet rather than accidentally
  accepting only the hostile subset. This is route/state distribution
  evidence, not timing, complete representative breadth, candidate-product,
  or production-`C<T>` authority. The final strict aggregate exited
  successfully; direct audit covers 190 XML files and 2,220 tests with zero
  failures, errors, or skips. See
  [`docs/archive/generic-owner-array-copy-application-census-2026-08-15.md`](docs/archive/generic-owner-array-copy-application-census-2026-08-15.md).
- The preceding optimization: exact Common generic `Array.fill` no longer
  erases its CLR vector or element into `System.Array`/`object` and no longer
  calls virtual `SetValue` per slot. Evaluation and Common range-check
  precedence remain unchanged. Framework 4.8 and netstandard use a typed
  `stelem E` loop; every statically known reference vector uses that same loop;
  .NET 10 value, nullable-value, and open `T` vectors call generic
  `System.Array.Fill<E>`. Only an already-erased capability, explicitly proved
  by a canonical generic-owner sentinel, retains Runtime surface 37. All eight
  scalar carriers, nullable/null/reference/open substitutions, empty/partial/
  full ranges, evaluation order, and failure categories execute through both
  frontends on Framework 4.8 and .NET 10. Framework IL proves typed stores and
  the erased boundary; CoreCLR executes the closed/open generic BCL MemberRefs.
  A committed checksum-identical measurement tool records the route tradeoffs
  rather than treating .NET 10's optimized `object` path as Framework evidence.
  The final strict aggregate exited successfully; direct audit covers 190 XML
  files and 2,216 tests with zero failures, errors, or skips. No public ABI,
  KLIB, Runtime surface, or generic-owner representation changed. See
  [`docs/decisions/generic-array-fill.md`](docs/decisions/generic-array-fill.md)
  and
  [`docs/archive/generic-array-fill-specialization-2026-08-15.md`](docs/archive/generic-array-fill-specialization-2026-08-15.md).
- The preceding correction: Common generic resized array copies now preserve
  Kotlin's nullable-result contract on CLR value vectors. A closed
  `Array<V>.copyOf(newSize): Array<V?>` allocates `Nullable<V>[]`, copies its
  prefix with typed `ldelem`/`newobj`/`stelem`, and leaves genuine null padding;
  it neither boxes nor casts the source `V[]` to an incompatible nullable
  vector. Output-projected copies retain exact reference/already-nullable
  vectors and every non-growing vector. A growing non-null value vector behind
  open `Array<out T?>` uses a new output-only `object[]`, because retaining
  `V[]` would expose default `V`; the physical ABI remains `System.Array` and
  KLIB remains authoritative. FIR's singular `Array<in Nothing?>` bottom
  capture receives only that read capability, while an explicit null write is
  still rejected unchanged. Direct execution covers all eight scalar families,
  concrete/projected/open/reference/nullable/widened substitutions and
  truncation through PSI/LightTree on Framework 4.8 and .NET 10. IL goldens
  prove the boxing-free closed loop and bounded runtime branch. The final
  strict aggregate exited successfully; direct audit covers 190 XML files and
  2,216 tests with zero failures, errors, or skips. No runtime-surface, library-
  codec, or public physical-signature change was introduced. See
  [`docs/decisions/primitive-arrays.md`](docs/decisions/primitive-arrays.md),
  [`docs/decisions/open-nullable-array-views-and-varargs.md`](docs/decisions/open-nullable-array-views-and-varargs.md),
  and
  [`docs/archive/generic-resized-array-copy-2026-08-15.md`](docs/archive/generic-resized-array-copy-2026-08-15.md).
- The preceding foundation: the complete Common Sequence builder/window
  closure is now published from exact `SequenceBuilder.kt`, `SlidingWindow.kt`,
  `Sequences.kt`, and generated source. `SequenceScope` remains one erased
  Kotlin class over the established continuation/sentinel ABI; `sequence`,
  `iterator`, every `yieldAll` route, `ifEmpty`, both lazy `flatMapIndexed`
  overloads, running fold/reduce/scan, `zipWithNext`, `windowed`, and `chunked`
  execute without a target-authored iterator or BCL enumeration identity.
  Common `RingBuffer.toArray` receives one structural local-only carrier: an
  immutable conditional `Array<T?>` selection retains its resized-or-supplied
  exact vector through `System.Array`, and writes require the original logical
  `T`; ordinary casts, nullable writes, and declaration-stable invariant/input
  open-nullable arrays remain rejected. Erased generic `Array.fill` preserves
  Common range exception categories through runtime surface 37. The full gate
  found and repaired a second foundational bug: source-aligned Stdlib shards
  now own private top-level properties as well as helper functions, preventing
  the six Sequence state constants from leaking into every user producer DLL.
  Five generated outputs remained SHA-256-identical across the owning-
  generator rerun. PSI/LightTree and Framework 4.8/.NET 10 direct lanes, the
  portable netstandard Stdlib plus separate consumer, physical metadata, and
  rejection sentinels are green. The final strict aggregate completed in
  2,653.0 seconds; direct audit covers 190 XML files and 2,216 tests with zero
  failures, errors, or skips. See
  [`docs/decisions/sequence-foundation.md`](docs/decisions/sequence-foundation.md)
  and
  [`docs/archive/common-sequence-builder-closure-2026-08-15.md`](docs/archive/common-sequence-builder-closure-2026-08-15.md).
- The preceding foundation: generic-owner execution tracing now records into
  a fixed primitive counter table and emits one final line per visited compiler
  site instead of one console line per call. The table is sized from the exact
  complete-census route count; each call performs one CLR
  `Interlocked.Increment(Int64&)`, and the final snapshot uses
  `Interlocked.Read(Int64&)`. The physical helper and its private recorder/
  flusher method bodies exist only in an explicitly instrumented executable:
  there is still no CLI option, Runtime/KLIB ABI, published type, or normal
  emitter policy. Trace-manifest schema 2 records `counterProtocol=FINAL_FLUSH`.
  Its independently verified PSI/LightTree × Framework 4.8/net10 corpus
  retains byte-identical route/count artifacts and the exact prior 49/40/9
  event vector: 24 producer-erased, 11 exact, four capability, and one missing-
  capability producer event, including the zero-hit and two-hit sites. Normal
  net10 PSI and LightTree bundles remain SHA-256-identical to the pre-feature
  baseline across all 17 files per lane (34 comparisons). Collection output is
  now O(visited sites), not O(executed calls), but instrumentation remains
  correctness/frequency evidence rather than timing evidence; a workload must
  join its own workers before `box()` returns and triggers the snapshot. The
  strict aggregate completed in 2,465.4 seconds; direct audit covers 190 XML
  files and 2,216 tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-call-route-counter-flush-2026-08-15.md`](docs/archive/generic-owner-call-route-counter-flush-2026-08-15.md).
- The preceding foundation: explicit architecture-test instrumentation now
  attaches runtime events to the compiler's original generic-owner call-site
  indices. The planner retains the exact analyzed `IrCall`; only a test-owned,
  private module-local `(Int) -> Unit` recorder enables rewriting. Every
  receiver and argument is first evaluated once into a temporary in original
  order, then the event is recorded immediately before invocation, so callee
  failures count as attempts without moving the event before argument failure.
  No CLI option, Runtime ABI, KLIB field, physical name, or emitter policy was
  added. A closed schema-1 trace bundle joins the static route fingerprint to
  all 40 sparse producer counters and the instrumented assembly fingerprint.
  PSI/LightTree on Framework CLR 4 and .NET 10 produce byte-identical route and
  count artifacts: 49 total events comprise 40 producer and nine unrelated
  events; the dynamic producer distribution is 24 producer-erased, 11 exact,
  four capability, and one missing-capability call. The exact per-site oracle
  includes one zero-hit and one two-hit typed site, preventing aggregate-only
  false agreement. A fresh non-instrumented application corpus is byte-for-
  byte identical to the pre-feature baseline across all 17 files per frontend,
  including compiler DLLs and manifests. Console tracing is correctness
  evidence only, not timing or representative-application evidence. The strict
  aggregate completed in 3,063.2 seconds; direct audit covers 190 XML files and
  2,216 tests with zero failures, errors, or skips. See
  [`docs/archive/generic-owner-call-route-trace-2026-08-14.md`](docs/archive/generic-owner-call-route-trace-2026-08-14.md).
- The preceding foundation: the compiler-derived generic-owner census now
  crosses into the closed paired application bundle as a canonical schema-1
  route artifact. Its 40 producer-owned records retain original compilation
  indices 0 through 48 (nine unrelated-owner gaps), optional caller KLIB keys,
  required producer-member KLIB keys, provenance, and resolved requirements;
  diagnostic/physical names, unresolved routes, target profile, and temporary
  paths cannot enter. Application manifest schema 2 fingerprints the file.
  Its independent verifier decodes canonical grammar, pins 24 producer-erased,
  11 exact, four capability, and one missing-capability site, and requires byte
  identity across PSI/LightTree and Framework 4.8/net10. All current caller
  bindings are honestly absent rather than reconstructed from source labels;
  the compiler index is the same-compilation instrumentation join and the
  callee key is semantic aggregation identity. Exact separate application
  lanes and all candidate/erased/direct-C# executions pass on both CLR
  profiles. The strict aggregate completed in 2,819.9 seconds; direct audit
  covers 190 XML files and 2,216 tests with zero failures, errors, or skips.
  This exports static evidence, not dynamic frequency or representative-app
  authority. See
  [`docs/archive/generic-owner-call-route-manifest-2026-08-14.md`](docs/archive/generic-owner-call-route-manifest-2026-08-14.md).
- The preceding foundation: a production-inert Kotlin IR census now derives
  generic-owner receiver routes instead of assigning benchmark weights by
  hand. Exact construction provenance propagates through definitions,
  assignments, closed-call arguments, returns, fields, branches, casts, and
  lowered default helpers; casts preserve evidence and never create it. Closed
  invariant public signatures remain exact, while star/projected/variant and
  unresolved views require a capability. External calls resolve only through
  exact logical member keys claimed by the decoded producer catalog;
  unrelated producer artifacts leave them pending, classified absent families
  remain producer-erased, and diagnostic names have no authority. The hostile
  separate corpus records 40 producer-owned static sites: 24 producer-erased,
  11 exact typed-entry candidates, four semantic-capability routes, and one
  missing capability. These are structural sites, not runtime frequency or
  migration authority. PSI/LightTree direct and separate oracles pass on
  Framework CLR 4 and .NET 10: eight tests and zero failures, errors, or skips.
  The strict aggregate completed in 2,935.6 seconds and its direct audit covers
  190 XML files, 2,216 tests, and zero failures, errors, or skips. Production
  generic owners and emission remain unchanged. See
  [`docs/archive/generic-owner-call-route-census-2026-08-14.md`](docs/archive/generic-owner-call-route-census-2026-08-14.md).
- The preceding foundation: the schema-7 hostile generic-owner family now
  physicalizes the compiler's existing `TYPED_STORAGE_PRODUCER_GRAPH_PROVEN`
  outcome. `HostileTypedStore<T>` has one actual owner-GenericParam `!T` field;
  exact typed reads/writes use identity access and never cross its non-generic
  capability. The same object's strict widened/star capability checks before
  mutation and widens or boxes after read; an incompatible string write to
  `HostileTypedStore<int>` fails without changing state. Artifact validation
  and direct C# reflection pin the field/signatures and private virtual final
  dispatchers on Framework CLR 4 and CoreCLR. Paired `Int32`, `Int32 + Guid`,
  and `Nullable<Int32>` routes execute against the actual production-erased
  owner on Framework 4.8, .NET 10 JIT, ReadyToRun, full trim, and NativeAOT.
  Exact routes remove every per-iteration allocation; capability routes retain
  two internal object-domain conversions and one check and take 1.943–4.215
  times erased. Exact scalar/nullable routes win in every lane, while the
  allocation-free larger-struct result remains deployment-sensitive. The
  strict aggregate completed in 849.5 seconds and its direct audit covers 190
  XML files, 2,216 tests, and zero failures, errors, or skips. Production
  generic owners remain erased; this closes bounded typed-storage feasibility,
  not representative applications or atomic migration. See
  [`docs/archive/generic-owner-typed-storage-attribution-2026-08-14.md`](docs/archive/generic-owner-typed-storage-attribution-2026-08-14.md).
- The preceding foundation: the complete signed primitive-array and remaining
  object-array sorting graph now publishes the exact Common whole/range
  `sort`/`sortWith` expects plus the complete generated reverse, descending,
  snapshot, selector, and sortedness closure. All seven naturally ordered
  signed primitive wrappers execute the Native/Wasm per-wrapper quicksort over
  their existing exact private vectors; generic object arrays retain the
  stable merge lineage and runtime-component-typed buffer. Open producer-
  generic `copyOf`/`sortedArray` snapshots allocate from the source vector's
  exact runtime component type through `System.Array.CreateInstance` and copy
  through `System.Array.Copy`, while primitive snapshots allocate independent
  wrapper/vector storage. Boolean receives only its authoritative reversal and
  explicit comparator/selector variants. PSI/LightTree direct and separate-
  compilation consumers execute on Framework CLR 4 and .NET 10, including
  NaN/signed-zero order, range failure timing, mutation, stability, aliasing,
  and open value/reference substitutions. The same portable
  `netstandard2.0` Stdlib and C# sorting workload execute on both hosts; C#
  directly calls generic CLR-vector and specialized Kotlin-wrapper facades.
  Two owning-generator runs retained four identical hashes. The strict
  aggregate completed in 2,662.9 seconds and its direct audit covers 190 XML
  files, 2,216 tests, and zero failures, errors, or skips. No
  `System.Array.Sort`, unsigned sorting, Random, sequence-builder dependency,
  Runtime surface change, or library-codec change was introduced. See
  [`docs/decisions/stable-list-and-array-sorting.md`](docs/decisions/stable-list-and-array-sorting.md).
- The preceding foundation: Kotlin-owned `Grouping<T, out K>` now publishes
  the complete Common aggregate/fold/reduce/count source, the Native/Wasm
  `eachCount` actual, and all four generated factories over Iterable,
  Sequence, object arrays, and CharSequence. KLIB retains both type parameters
  and key covariance; one non-generic erased CLR interface owns only source
  iteration and key selection, with no LINQ, `IGrouping<TKey,TElement>`, or
  `IEnumerable<T>` identity. Common absent-versus-present-null accumulator,
  first-element, seeded-destination, encounter-order, and exception semantics
  execute through both FIR parsers on Framework CLR 4 and .NET 10. The same
  portable `netstandard2.0` Stdlib DLL and hostile consumer execute separately
  on both runtimes, including object-carried primitive/Char paths; Roslyn
  directly implements the erased interface and calls `GroupingKt.eachCount`.
  The strict aggregate completed in 1,961.0 seconds and its direct audit covers
  190 XML files, 2,212 tests, and zero failures, errors, or skips. See
  [`docs/decisions/grouping-foundation.md`](docs/decisions/grouping-foundation.md).
- The preceding foundation: Kotlin-owned `Sequence<out T>` publishes one
  non-generic erased CLR interface plus the authoritative Common non-builder
  implementation objects, adapters, and complete generated inventory outside
  its original builder/random/`Grouping`/unsigned exclusion partition;
  `groupingBy` is now admitted by the later Grouping foundation. KLIB keeps
  the covariant logical type and original overloads; deterministic logical-
  type-derived physical names resolve CLR-erased collisions. A portable
  consumer exposed the generic-return boundary where `Sequence<T>.min/max`
  instantiated with `Int` arrives through its physical `IComparable` upper-
  bound view; frontend-proven implicit substitution recovery now emits
  `unbox.any` without widening explicit or safe cast policy. The same
  `netstandard2.0` Stdlib DLL executes laziness, one-shot behavior, covariance,
  adapters, both flatten/flatMap routes, reified filtering, sorting, numeric
  selection, NaN, and primitive recovery on the registered Framework 4.8
  runtime (`4.8.09221`, CLR `4.0.30319.42000`) and separately on .NET 10.
  PSI/LightTree direct products add all four runtime lanes; Roslyn implements
  the erased Sequence interface and calls its Common facade without observing
  `IEnumerable<T>`. See
  [`docs/decisions/sequence-foundation.md`](docs/decisions/sequence-foundation.md).
- The preceding foundation: the paired hostile generic-owner application now
  has fail-closed route attribution on the registered Framework 4.8 family
  runtime (`4.8.09221`, release `533509`, CLR `4.0.30319.42000`) and separately
  on .NET 10 JIT, ReadyToRun, full trim, and NativeAOT. The candidate's real
  generic TypeDef and typed entries still use the compiler-required semantic
  object state. Direct typed value state therefore retains the same boxing as
  erased and takes 1.623–2.315 times the erased workload in the higher-
  resolution regular-route run. Capability/reference and semantic-array paths
  remain 2.260–14.634 times erased without per-iteration allocation, isolating
  dispatch and compatibility checks rather than boxing. Compatible value
  capability and exact construction each add one 24-byte box per iteration;
  an equal-layout `Int32 + Guid` fallback proves its remaining cost with equal
  allocation. Owner-independent method generics remain 0.909–1.180 times the
  erased baseline, while NativeAOT typed arrays and compatible overrides reach
  parity or a small win. The hostile failure route differs materially by
  runtime: Framework adds 336 candidate bytes per failure, JIT/ReadyToRun/
  NativeAOT add none, and full trim adds about 23. Production owners stay
  erased; representative applications are the next reopening gate. See
  [`docs/archive/generic-owner-route-attribution-2026-08-14.md`](docs/archive/generic-owner-route-attribution-2026-08-14.md).
- The preceding foundation: the closed hostile generic-owner application
  corpus now drives one paired production-erased versus test-owned `C<T>`
  measurement on the real Framework CLR 4 and independently on .NET 10 JIT,
  ReadyToRun, full trimming, and NativeAOT. All modes produce checksum
  `-365770154`. The candidate takes 1.622–2.958 times the erased workload time
  and allocates 6.891–7.523% more in this semantic-route-heavy workload. This
  does not show that CLR generics are intrinsically slower: only three regular
  candidate routes per iteration are typed while 24 deliberately exercise the
  complete semantic capability. It shows that true owner identity alone does
  not pay for the current bridge architecture. Framework evidence records the
  actual Windows PowerShell CLR `4.0.30319.42000` host and explicit .NET
  Framework 4.8 assemblies; .NET 10 evidence remains separate, so CoreCLR
  `object`, boxing, or interface-dispatch optimizations cannot be generalized
  to Framework. The candidate is not a complete Kotlin product, therefore
  published bytes and end-to-end compile costs are not comparable. The first
  full-trim run also found that a class-owned canonical collection MethodImpl
  bundle rebuilt over an external base lacked direct CLR InterfaceImpl edges.
  New physical indexes now retain producer-visible class-owned bridge records
  while excluding lowering-created synthetic owners; emission adds
  intentional direct interface reimplementation for old/bootstrap indexes,
  and the application verifier pins the four collection edges. CoreCLR, CLR 4,
  ILLink, and all five measurement lanes pass with one object and one state.
  Production generic owners remain erased; the then-open semantic-route cost
  attribution is closed by the foundation above, while representative
  applications remain open. See
  [`docs/archive/generic-owner-paired-application-measurement-2026-08-14.md`](docs/archive/generic-owner-paired-application-measurement-2026-08-14.md).
- The preceding foundation: the generic-owner reopening has one closed paired
  application corpus containing the exact hostile Kotlin source, actual
  production-erased producer and separately compiled Kotlin consumer, a direct
  C# consumer/two-level subclass, and the compiler-record-driven candidate.
  Framework/user structs, nullable and mixed state, arrays, method generics,
  reflection, and override paths execute on PSI/LightTree × Framework CLR/
  CoreCLR. Every profile bundle is closed and fingerprinted; executable CLR,
  non-body KLIB, binding, and downstream frontend equivalence fail closed.
  Framework C# products use modern Roslyn against explicit CLR 4 references
  and run on the real CLR 4. See
  [`docs/archive/generic-owner-application-corpus-2026-08-13.md`](docs/archive/generic-owner-application-corpus-2026-08-13.md).
- The preceding foundation: the exact schema-7 generic-owner measurement
  bundle now links and runs as a real Windows x64 NativeAOT executable. A
  workload-version-2 stdin handshake holds every child only after its protocol
  line, allowing a live `PeakWorkingSet64` sample without adding the hold to
  workload or wall time; version-1 bundles now fail closed. Explicit native
  toolchains record and validate the Microsoft linker signature, version,
  SHA-256, three distinct library roots, and every required CRT/SDK import
  library. The result also records the measurement-tool hash and repository
  dirty state. JIT, ReadyToRun, full trimming, and NativeAOT all produced
  checksum `2027804433`; the native mode published one 971,264-byte executable,
  with 11.900-ms startup, 14.440-ms workload, 19,431,608 allocated bytes, and
  14,368,768-byte peak-working-set medians. All publish logs were warning-free.
  This closes bounded-corpus NativeAOT proof, not representative product
  comparison or production `C<T>` admission. See
  [`docs/archive/generic-owner-native-aot-measurement-2026-08-13.md`](docs/archive/generic-owner-native-aot-measurement-2026-08-13.md).
- The preceding foundation: generic-owner schema 7 now separates complete
  producer candidate classification from optional physical-family
  publication. Every logically bindable producer snapshot records its owner
  key, arity, disposition, and sorted constructor/member binding keys. A
  published physical family must match that catalog entry exactly. The
  metadata-fixed `HostileNullableDerived<T> : HostileCell<T?>` candidate is
  therefore serialized with
  `BLOCKED_METADATA_FIXED_CONDITIONAL_SUPERTYPE` but no dishonest CLR-generic
  family. Exact family lookup and external member resolution distinguish this
  recorded absence from an unknown or malformed producer and report the
  authoritative disposition; consumers never infer a family from absence.
  Duplicate classifications, omitted physical-owner classifications, and
  catalog/family key disagreement fail before binding. The exact PSI and
  LightTree separate-compilation lanes pass. Production emission remains
  erased and neither DLL nor KLIB consumes the architecture artifact. See
  [`docs/archive/generic-owner-producer-classification-catalog-2026-08-13.md`](docs/archive/generic-owner-producer-classification-catalog-2026-08-13.md).
- The preceding foundation: the exact compiler-record-driven finite generic-
  owner factory now supplies one reproducible measurement corpus rather than a
  second handwritten AOT/performance model. The net10 correctness test exports
  only through an explicit per-test JVM property into a required-empty
  directory. Its generated source, exact producer DLL, version-7 family
  record, pinned project, pinned SDK selector, and closed-shape manifest form
  an exact six-file bundle; five content fingerprints and the exact entry set
  are verified before and after publication. Build intermediates and results
  remain outside the bundle. Workload version 1 composes exact `int`, already-
  nullable, reference, and consumer-struct roots; unlisted struct/reference
  semantic fallback; paired state; typed and semantic arrays; multi-level
  hostile dispatch; and an incompatible broad write followed by its delayed
  typed-read failure. The new tool rejects unbounded reflection, stale or
  extra files, malformed protocol fields, wrong iterations, unstable or
  cross-mode checksums, missing counters, and any NativeAOT publish/link/run
  failure. One hash-identical bundle produced checksum `2027804433` under JIT,
  ReadyToRun, and full trimming and recorded startup, workload time,
  allocation, peak working set, publication time, and footprint. These are a
  bounded local baseline, not directly comparable dependent/self-contained
  sizes or representative product evidence. At that version-1 checkpoint,
  NativeAOT was not run and `nativeAotProven` was false; workload version 2
  closes that gate above. The focused
  separate-compilation correctness test and its strict input audit passed with
  one test and no failure, error, or skip. See
  [`docs/archive/generic-owner-measurement-corpus-2026-08-13.md`](docs/archive/generic-owner-measurement-corpus-2026-08-13.md).
- The preceding foundation: generic-owner physical-family ABI construction no
  longer reconstructs CLR members from the hostile fixture's declaration
  names. The
  production planner now derives an exact, profile-neutral signature family
  from lowered IR for every selected typed, semantic, and capability role; the
  bounded carrier grammar covers `Unit` returns, Boolean, Int, String, Any,
  owner/method parameters, and recursively exact arrays, with `System.Array`
  retained for owner-dependent semantic array routes. Constructors likewise
  record exact owner and independent Int slots, while the actual lowered static
  default dispatcher supplies its method arity, return, ordinary parameters,
  and mask tail. Star/unsupported projection shapes, unknown classifiers,
  nullable value types, nested carriers, Unit parameters, and dispatcher shapes return no proof
  instead of guessing `object` or crashing. Physical base names come from the
  compiler naming rule and role suffixes are uniform. State read/write MethodDefs
  are selected from the recorded transitive field-access graph, not from
  `writeUnsafe` or `read`. A user declaration which occupies the same name and
  signature as a generated role rejects the whole artifact; two logical
  members can never claim one physical MethodDef identity. After external
  producer binding widens a consumer's domains or roles, its stale local exact
  signatures are explicitly invalidated and producer records remain
  authoritative. The hostile oracle rewrites every producer member's
  diagnostic source label in-memory and requires the complete physical
  artifact to remain equal. Metadata-captured inner parameters whose pre-normalization
  slot domain disagrees with the structural carrier now retain no exact proof
  instead of reaching an invalid record constructor. The oracle then executes
  PSI/LightTree × Framework/CoreCLR × same/separate
  compilation: four suites, eight tests, zero failures, errors, or skips.
  Production emission remains erased and does not consume these snapshots.
- The preceding schema-6 family/constraint foundation, carried forward by
  schema 7, feeds a compiler-derived external Kotlin subclass physicalizer.
  Every typed entry, semantic hook,
  capability dispatcher, direct-`super` target, and static masked-default helper
  carries an exact MethodDef owner/name, dispatch, complete value-position
  domain vector, and profile-neutral structural signature. The type vocabulary
  recursively represents built-ins, owner `!T`, method `!!T`, producer/core/
  assembly named generic instances, and SZ arrays without textual IL. A
  capability dispatcher separately names the exact non-generic interface
  MethodDef it implements and must carry an equal signature; capability records
  cannot leak owner parameters. Owner and method parameter indexes, nested
  state carriers, direct-super signatures, role-vector consistency, parameter
  arity, and return-domain compatibility fail closed. Broad candidate inputs
  propagate to a fixed point across local overrides and are inherited from the
  producer record by a separate consumer. The hostile owner now includes `echo(Array<out T>)`
  and method-generic `relay(Array<R>)`: its temporary CLR producer exposes
  `T[]`/`R[]` typed entries while the semantic echo path uses `System.Array`.
  Producer reflection verifies those MethodDefs and exact explicit
  InterfaceImpl mapping on Framework CLR and CoreCLR. A separately compiled C#
  consumer renders its override signatures only from the decoded record, keeps
  array identity, and rejects a producer whose typed signature disagrees with
  the consumer snapshot. Schema 4 additionally records the exact target
  profile, open-TypeDef runtime-classification mode, the admitted construction
  modes, and each constructor's MethodDef, visibility, closed producer owner,
  and exact `this`/`base` target. Only `STATIC_EXACT` is admitted; runtime exact
  and semantic fallback construction are not claimed. The one semantic state
  record now names its private field carrier and exact paired typed/semantic
  read/write MethodDefs, including the widening/boxing and checked cast/unbox
  boundary conversions. The record-driven C# consumer obtains its immediate
  generic base construction, constructor signature, and all four state paths
  from the decoded artifact. Wrong-profile, incomplete, mismatched-delegation,
  malformed-construction, unpaired-state, and unrecorded-access artifacts fail
  atomically. Schema 5 maps each exact producer open implementation TypeDef to
  its existing KLIB logical classifier key without copying Kotlin names or
  logical type arguments into the physical record. Closed constructions of the
  same open owner normalize to one classifier; exact classifier lookup rejects
  capability and foreign subclass TypeDefs, while logical instance checks use
  objective open-TypeDef ancestry. KLIB remains the sole authority for type
  arguments, variance, projections, nullability, and bounds. Capability
  interfaces remain hidden compiler ABI. Each source callable collapses every
  typed, semantic, capability, and default-helper MethodDef in its family into
  one logical declaration and selects the semantic dispatcher for broad
  families or the typed entry for strict families. Constructor reflection
  continues through the schema-4 construction records. The separately compiled
  C# consumer executes the record-generated normalization registry for open and
  multiple closed owners, ancestry, capability/foreign rejection, and the
  selected private explicit-interface dispatcher on Framework CLR and CoreCLR.
  The physicalizer accepts only a fully resolved external-subclass snapshot and
  a distinct current-compilation TypeDef path. It selects the immediate generic
  base from the compiler-recorded delegated constructor, requires the child's
  exact admitted constructor signature to equal the producer record, requires
  positional identity forwarding of every delegated argument, and emits
  typed/semantic child slots only from exact producer MethodDef identities.
  Fake overrides join their real declaring KLIB member rather than inventing a
  consumer key; a MethodDef may therefore belong to an ancestor while the
  constructed immediate base remains the derived producer owner. Source
  visibility/modality is retained, semantic hooks stay protected, legal final
  overrides become sealed, and final producer slots fail closed. Producer and
  child owners also carry an ordered GenericParam constraint row per `!T`; the
  current bounded grammar admits the hostile unconstrained vector and rejects
  unsupported Kotlin bounds or any producer/child constraint mismatch. Arity
  is never used as constraint proof. Producer artifacts cannot contain the new
  current-compilation type scope. The child must be public, open, non-inner,
  have one direct external base and one constructor, and add no interface,
  field, initializer, nested type, state, or non-fake member. Inherited fake
  overrides/default helpers remain inherited. The hostile record-driven C#
  oracle materializes the physicalized Kotlin subclass and a
  further C# generic grandchild, then verifies exact base/constructor metadata,
  typed and semantic multi-level dispatch, direct `super`, delayed typed-read
  failure, reflection ancestry, and classifier non-normalization on Framework
  CLR and CoreCLR. A separate consumer-side construction plan now keeps final-
  compilation roots out of producer schema 7. It derives a finite exact
  runtime-token table from the decoded unconstrained owner/capability/public
  strict constructor, normalizes already-nullable values idempotently, and
  requires one default
  `C<object>` semantic fallback for every unlisted value/reference type. The
  generated factory contains no `MakeGenericType` or `Activator` closure.
  Exact value/reference/consumer-struct routes and unlisted struct/reference
  fallbacks execute through the same state/capability on both CLRs. The later
  workload-version-2 bounded corpus is warning-clean with IL3050/IL2026 as
  errors and completes NativeAOT native link and execution. Normal production
  generic owners remain erased; these records are not emitted in DLL/KLIB, represented
  in `dotnet.ir`, or consumed by the production emitter. Representative
  erased-versus-candidate application measurements remain required before an
  atomic migration.
  The preceding foundation: open-nullable projected array reads and
  Kotlin-owned nullable generic varargs now use two distinct truthful CLR
  carriers. Ordinary `Array<out T?>` retains the original exact vector through
  the classified `System.Array` read view, so reference and nullable-value
  vectors keep identity, component type, aliasing, and mutation visibility.
  Every expanded `vararg T?`, including omitted and spread calls, instead
  creates one fresh declaration-stable `object[]`, independent of reference or
  value substitution; KLIB retains the logical `Array<out T?>` contract.
  Deserialized producer declarations keep their logical ABI key while consumer
  call arguments carry the exact physical vector, and imported CLR varargs
  retain their selected foreign metadata. The bounded release restores the
  authoritative object-array `filterNotNull`/`filterNotNullTo` pair and
  `setOfNotNull(vararg T?)`. Same- and separate-module hostile tests cover
  reference/value/null/widened/spread/empty/evaluation-order/freshness and
  destination-identity behavior on both parsers and CLR profiles. Roslyn calls
  the emitted `System.Array` and `object[]` signatures directly; no wrapper,
  unchecked `T[]` cast, or placeholder helper was added. Method-owned
  invariant/input open nullable arrays remain rejected, while closed exact and
  declaration-erased owner-array rules are unchanged. See
  [`docs/decisions/open-nullable-array-views-and-varargs.md`](docs/decisions/open-nullable-array-views-and-varargs.md).
  The completed sorting foundation actualizes the exact Common
  `MutableList.sort`/`sortWith`, generic object-array whole/range contracts,
  and all seven naturally ordered signed primitive-wrapper whole/range
  contracts, then releases the dependency-closed eager/reverse/descending/
  selector/snapshot/sortedness consumers. The generator retains the
  authoritative Native/Wasm stable merge and primitive quicksort lineages but
  applies exact CLR carrier
  corrections: list and eager Iterable snapshots remain private
  `Array<Any?>` storage; array merge buffers retain the input vector's runtime
  element type; and array traversal uses the classified `System.Array`
  `GetValue`/`SetValue` path. This preserves both CLR reference vectors such as
  `Entry[]` and the target's value vectors such as `Array<Int>`/`int[]` without
  the invalid `object[] -> T[]`, `IComparable[] -> T[]`, or `int[] -> object[]`
  casts exposed by the first exact source transplant. Sorting is stable,
  arbitrary MutableLists write back through their iterator, a comparator
  failure occurs before list mutation, Kotlin String/Float/Double ordering is
  retained, and direct/separate consumers agree. Open `copyOf` snapshots use
  the source vector's runtime component type rather than `object[]` or open
  `newarr !T`. C# directly sorts raw generic CLR vectors, exact Kotlin
  primitive wrappers, a C# reference-array range stably, and Kotlin
  `ArrayList` through the current public facade and Comparator ABI on both
  Framework CLR 4 and .NET 10. No BCL unstable sort, `IComparer<T>` identity,
  emitted placeholder intrinsic, open `T[]` merge carrier, runtime-surface
  change, or library-codec change was introduced. The direct-native interop
  direction is now explicit: compatible user actuals should retain imported
  CLR identity, while adapters/exports are for real shape or semantic
  mismatches. Infrastructure for greater CLR-owner reification may land
  incrementally, but a canonical public owner migration remains one coherent
  pre-ABI decision rather than a per-class identity split. See
  [`docs/decisions/stable-list-and-array-sorting.md`](docs/decisions/stable-list-and-array-sorting.md).
  The preceding foundation: Common `Comparator<T>` is published as the
  exact invariant Kotlin-owned fun interface, backed by the already accepted
  erased generic-interface and SAM-wrapper ABIs rather than a BCL comparer or
  CLR delegate identity. The complete authoritative Common
  `Comparisons.kt` source owns comparison combinators, nullable/natural/reverse
  order, comparator chaining, and reversal. The Common generator now also
  owns comparator `minOf`/`maxOf`, Iterable `minWith`/`maxWith` and selector
  variants, plus non-mutating `isSorted*`; the .NET allowlist contains no
  algorithm body. Kotlin Float/Double total order, String ordinal order,
  first-element tie retention, traversal/short-circuit timing, failure
  identity, use-site `Comparator<in T>`, and separate KLIB consumption execute
  through the ordinary erased canonical slot. Roslyn proves that C# can
  implement and call that exact interface and drive the Kotlin scalar
  functions, while also proving why its hashed erased slot is compiler ABI
  rather than an ergonomic export. A future explicit C# projection should use
  `IComparer<T>` adapters without replacing Kotlin identity. Runtime surface
  36 and library codec 35 remain unchanged. Stable mutation/signed-array
  snapshots subsequently landed through the completed sorting foundation.
  The preceding foundation: Kotlin `fun interface` declarations now use the
  repository's Common `SingleAbstractMethodLowering`, after callable-reference
  materialization and before local-class, interface, generic, and continuation
  lowering. The declaration remains one ordinary Kotlin-owned CLR interface;
  each conversion becomes an assembly-private ordinary implementation class
  over the established `FunctionN` object, never a CLR delegate or second
  interface identity. Common owns nullable conversion, exactly-once
  evaluation, forwarding, and stored-function/reference equality. Runtime
  surface 36 adds the metadata-public compiler-ABI
  `Kotlin.Runtime.Internal.FunctionAdapter` capability used by that equality
  protocol; the KLIB/library codec remains version 35. Generic owner variance,
  inherited abstract slots, receiver/primitive adaptation, suspend forwarding,
  public inline bodies, and three-module consumption all reuse existing
  foundations. The multimodule producer test also corrected the .NET test
  fixture to supply and stage transitive binary dependencies, matching the
  other KLIB environments instead of weakening non-linking symbol resolution.
  Roslyn proves that C# can implement and call the ordinary interface and can
  consume a Kotlin-produced wrapper; direct C# lambda conversion remains
  deliberately unsupported until an explicit delegate export exists. No
  erased Kotlin generic-class owner was reopened.
  The preceding foundation: top-level delegated properties now reuse the
  exact operator calls and property-reference identity already produced by
  FIR/Common, matching the established member and local paths. One private
  static delegate field lives on the file facade; the existing static-
  initializer lowering executes its initializer and optional
  `provideDelegate` once, in source order, while ordinary generated accessors
  call `getValue`/`setValue`. The backend no longer rejects this already-
  supported IR shape and adds no target delegation protocol, value mirror,
  CLR attribute authority, or manifest contract. A failed `provideDelegate`
  follows the accepted JVM-shaped file-initialization state machine:
  non-`Error` failures retain cause identity inside
  `ExceptionInInitializerError`, and later access receives
  `NoClassDefFoundError`; accessor-time failures propagate unchanged. Five
  unchanged compatible Common tests plus a target-owned hostile failure test
  run through PSI and LightTree on Framework CLR and CoreCLR. A portable
  producer is also consumed by separate Kotlin and Roslyn applications, with
  physical assertions for the single private delegate field, facade `.cctor`,
  operator calls, and ordinary CLR property. Common's optional unbound-
  property-reference cache stays off until raw synthetic file fields have a
  complete facade owner; `KProperty.getDelegate` remains a separate reflection
  closure. No runtime-surface or library-codec version changes because this
  tranche only admits existing logical and physical contracts.
  The earlier foundation: Common-owned `lateinit` uses the repository's
  ordinary nullable-carrier lowering for member, top-level, local, captured,
  inherited, generic-reference, and separately compiled declarations. KLIB
  retains the non-null Kotlin property and exact `isLateinit` fact; executable
  storage alone becomes nullable and uses CLR `null` as the one uninitialized
  sentinel. Every Kotlin read follows Common's generated check and exact
  `UninitializedPropertyAccessException` message, while `isInitialized` tests
  the same carrier directly. There is no Boolean state field, emitter-owned
  check, CLR-reflection inference, or second representation. The lowering runs
  before shared-variable/closure conversion in both KLIB stages, matching
  JS/Wasm/Native and JVM's Common semantic owner. Property references reuse the
  checked accessor; the previously published exact reflection bit now produces
  positive observations. The CLR property remains the truthful C# boundary:
  its setter initializes the same state and its getter throws the physical
  Kotlin exception before initialization. Shared PSI/LightTree tests, both CLR
  profiles, separate Kotlin libraries, physical IL assertions, and a Roslyn
  consumer cover the boundary. No runtime-surface or library-codec version bump
  is needed because the exception/helper, property payload, and manifest schema
  already existed; this tranche enables producer lowering and adds the Common
  Stdlib intrinsic source.
  The earlier foundation: JVM-shaped declaration-owned type-use reflection
  now extends the .NET `KType` actual with `KAnnotatedElement` while retaining
  the existing Common structural graph. Return, parameter, extension-receiver,
  nested projected argument, and callable upper-bound nodes receive only the
  runtime-retained Kotlin annotations attached to that exact semantic IR/KLIB
  node; repeat order and read-only list identity are preserved. Source/binary
  applications, nearby CLR rows, Roslyn nullable metadata, and FIR's synthetic
  type-enhancement markers never become Kotlin annotation objects. Annotated
  `typeOf` stays empty in alignment with JVM, and annotations do not alter
  `KType` equality, hashing, or rendering. One existing annotation-value
  pipeline constructs all objects; no CLR reflection decoder or flattened
  type path was added. Runtime/Stdlib surface 35 versions the physical
  `KType : KAnnotatedElement` edge and four-argument graph factory. Separate
  Kotlin and C# consumers plus the PSI/LightTree and Framework/CoreCLR matrix
  cover the authority and physical capability boundaries.
  The preceding foundation: Common `Number` is now an executable abstract
  superclass family rather than a classifier-name special case over `object`.
  Broad `Number` signatures retain the identity-preserving `System.Object`
  carrier required by the CLR, while the runtime classifier admits exactly the
  six signed built-in numeric boxes and instances of one runtime-owned abstract
  `Kotlin.Number` base. Kotlin-written subclasses physically extend that base;
  their six abstract conversion slots and open deprecated `toChar` slot use
  ordinary CLR virtual dispatch, including a non-virtual `super.toChar()` path.
  Broad and `T : Number` calls share one runtime operation boundary whose
  Float/Double NaN, infinity, truncation, and saturation behavior is the same
  as the established direct scalar intrinsics. KLIB retains the logical
  `T : Number` bound while CLR metadata omits the unsound constraint that would
  reject built-in value types. Checked/safe casts, nullable and negative type
  tests, and `Number::class.isInstance` now use the same classifier and preserve
  successful object identity. The generated Stdlib reflection catalog treats
  `IrBuiltIns.numberClass` as its own complete family and invokes those ordinary
  helpers for boxed and user-defined receivers; it is never inferred from the
  eight scalar entries. Library ABI/runtime surface 34 version the new base,
  classifier, helpers, physical subclass edge, and catalog dependency. The
  adversarial separate-module and member-reflection matrix is green on PSI and
  LightTree with Framework CLR and CoreCLR.
  The preceding foundation made the generated Stdlib member catalog expose all
  eight concrete Common scalar built-ins, mapped `kotlin.String`, all sixteen
  built-in collection interfaces, and the complete current Kotlin-owned
  collection implementation family executable through `KClass.members`. Scalar
  entries come directly from `IrBuiltIns`, preserving complete logical Kotlin
  scopes and signatures over their boxed/unboxed CLR value carriers while
  excluding CLR-only names. Their complete scopes exposed two ordinary backend
  gaps rather than reflection exceptions: eager `Boolean.and`/`or`/`xor` now
  follow JVM's primitive intrinsic registry, and physically retained deprecated
  signed-number `toChar` members preserve Byte/Short sign extension, Int/Long
  truncation, and Float/Double `toInt()` saturation when called reflectively.
  Mixed promotions and Kotlin Float/Double NaN/signed-zero total ordering execute
  through the existing callable pipeline. `Number` remains a separate family
  because its logical supertype uses a classified `object` carrier; it is not
  inferred from the eight concrete entries. Interface members likewise come
  directly from `IrBuiltIns`, preserving logical read-only/mutable and nested-
  entry identities over the canonical/exact split-interface representation.
  The implementation family
  covers the four read-only abstract bases, their four mutable counterparts,
  `ArrayList`, `HashMap`, and `HashSet`. `LinkedHashMap`/`LinkedHashSet` retain
  the same `KClass` and catalog identity as their actual typealias targets. The
  complete member sets come from the Kotlin built-ins/actualized Stdlib class
  scopes after KLIB serialization; arbitrary `System.String`/BCL members never
  enter the result. One Stdlib-owned catalog feeds the existing compact
  callable/property pipeline, while optional `Kotlin.Reflection.dll` owns
  lookup order and Runtime retains neither a Stdlib dependency nor catalog
  policy. Inherited fake overrides remain reflection identity and use their
  resolved real override only for execution, fixing the `Collection` versus
  `AbstractCollection` receiver boundary generically. Abstract skeletal-class
  members conversely require a real subclass receiver: reflection does not
  pretend that direct `MutableMap` implementor `HashMap` inherits
  `AbstractMutableMap`. One- and two-parameter owner graphs, concrete, abstract,
  and exact interface dispatch, Common annotations, mutation, nested entries,
  and per-`KClass` caching are covered.
  The selected collection members also pulled authoritative Common
  `ReturnValue.kt` into Stdlib, so
  runtime `@IgnorableReturnValue` applications are preserved rather than
  filtered. Library ABI/runtime surface 33 and provider `getMembersV2` version
  the cross-product change. Focused PSI/LightTree, Framework/CoreCLR, malformed
  provider, packaged-source, reproducibility, and installed-product checks are
  green.
  The preceding foundation, private member-factory protocol 2, emits one
  generated dispatcher TypeDef per reflected Kotlin producer class instead of
  one callable TypeDef per function/getter/setter. Shared Runtime carriers
  implement `KFunction` plus exactly one matching `Function0..22` capability,
  or `FunctionN` with its physical `arity` slot. The dispatcher contains
  direct ordinary-IR thunks and is completed only after Common suspend and
  masked-default lowering select their physical signatures; CLR reflection is
  never an invocation fallback. Existing property wrappers retain getter/
  setter identity and backlinks. The focused semantic matrix covers exact
  function-type invocation, defaults, empty and supplied varargs, suspend
  continuation forwarding, and stable suspend `callBy` rejection. Library
  ABI/runtime surface 32 and private factory method
  `<GetKotlinMembers-v2>` version the change. Producer opt-in remains required
  pending broader product/authority work.
  The preceding foundation moved common callable-reflection bodies once
  in Runtime's `FunctionReferenceBase`, following the JVM/Wasm runtime-base
  ownership direction. Generated direct/member references retain only
  invocation, bound-value, default, vararg, and suspend-specific hooks;
  property accessors inherit the same bodies. The base deliberately remains
  outside `KFunction`, so an adapted `FunctionN`-only reference does not gain
  reflective identity. The five affected generated-IL baselines lost 693
  repeated lines without changing callable behavior. Library ABI/runtime
  surface 31 rejects an old Runtime/new producer combination. This is a
  prerequisite reduction for the compact dispatcher above.
  The preceding feature completed JVM-shaped `KClass.members` for
  explicitly opted-in Kotlin-produced user/library classifiers through an
  optional `Kotlin.Reflection.dll`. Producers use `-Xdotnet-reflection` while
  this executable representation remains pre-ABI; ordinary compilation emits
  no member factory. Runtime owns the physical
  `KDeclarationContainer`/`KClass` slot, exact provider bootstrap, stable
  absence/mismatch failure, and per-`KClass` cache without a reverse static
  dependency. The optional product owns discovery; the backend emits only a
  private versioned producer factory from the post-KLIB logical class scope.
  Enumerated values are the established callable/property objects, so exact
  KLIB-derived annotations, parameters and type graphs, accessors, visibility,
  invocation, `callBy`, equality, mutation, dispatch, and exception identity
  retain one implementation. Separate compilation covers inheritance,
  interfaces/defaults, generic erasure, overloads, member extensions, nested
  classes, objects, and enums. Local/anonymous, foreign, and unadmitted
  mapped/Stdlib classifiers fail closed rather than exposing partial
  CLR-derived members.
  Library ABI/runtime surface 30 and private factory protocol 1 identify this
  pre-ABI opt-in closure. The actual packaged reflection source builds as
  `Kotlin.Reflection.dll` with only Runtime/Stdlib Kotlin dependencies; those
  base products have no reverse AssemblyRef. Reproducible stdlib production
  additionally pins upstream `IdSignature.visibleCrossFile` as the only
  cross-module callable-identity boundary, excluding absolute source paths.
  The private executable factory is a semantic proof, not a frozen compact
  encoding. General emission demonstrated material producer expansion; a
  compact KLIB-derived descriptor plus reflection-product decoder or equally
  compact shared thunks is therefore required before default enablement or ABI
  freeze.
  The preceding feature completed JVM-shaped direct property declaration facts
  and accessor objects for `KProperty0` through `KProperty2` and their mutable
  counterparts. One cached getter and optional setter call the owning
  property's established execution path, preserving bound receivers, virtual
  dispatch, mutation, exception identity, and separate compilation. Exact
  KLIB/importer IR owns `isConst`, `isLateinit`, accessor signatures,
  parameters, annotations, visibility, modality, and function flags; runtime
  CLR reflection owns none of them. A private callable-reference getter reads
  retained `const` literals without changing their ordinary field-only CLR
  ABI. Library ABI and runtime surface 29 own the payload, nested interfaces,
  and implementation classes. The later Common `lateinit` foundation completes
  the positive `isLateinit` path without changing that ABI. `getDelegate`
  remains a separate programme; type-use annotations were selected later.
  The preceding
  feature completed exact nominal constrained constructions and
  constructed-interface bounds for admitted foreign generic interfaces and
  methods. The FIR importer reuses the shared declaration-qualified CLR
  constraint resolver/validator; it does not reconstruct satisfaction from
  Kotlin types. Constrained members and InterfaceImpls, method and owner bounds,
  nested Roslyn nullability, Kotlin implementations, open generic bound dispatch,
  and the original TypeSpec/GenericParamConstraint slots agree on Framework CLR
  and CoreCLR. An intentionally invalid IL producer proves that an unbounded
  `T` cannot smuggle `KeyBox<T>` into lookup. The backend retains constructed
  foreign bounds as structural GenericInstance capabilities, and a non-null
  assertion on an open CLR parameter checks a boxed probe while preserving the
  original `!n`/`!!n` value. Special constraints, nullable constraint roots, and
  unsupported constructed shapes remain fail-closed. The preceding feature
  completed exact InterfaceImpl nullability and substitution for admitted
  foreign generic interfaces. The selected graph validates each
  retained InterfaceImpl row, implementing owner, target TypeDef, and assembly by
  identity. FIR consumes Roslyn's row-local preorder while retaining the original
  TypeSpec, so closed, reordered, mixed fixed/open, and inherited `T?` owner-
  parameter views agree across Kotlin supertypes, overrides, calls, MethodImpl
  slots, and CIL. Concrete oblivious reference arguments remain platform types;
  an oblivious owner parameter remains `T` rather than becoming a synthetic `T!`.
  Kotlin and C# implementations dispatch both ways on Framework CLR and CoreCLR.
  The preceding feature completed exact foreign `System.Nullable<V>` import for
  all eight signed Common primitive carriers. Only the selected core TypeDef retained in
  the shared declaration graph receives the Kotlin view `V?`; the original
  `Nullable<V>` signature remains authoritative for direct methods, mutable
  properties, nested `Box<V?>` constructions, overrides, and CIL. The nullable
  wrapper consumes no Roslyn reference-nullability flag and is never inferred
  from a namespace/name spelling or unconstrained annotated `T?`. Kotlin and C#
  implement and call the complete scalar family on Framework CLR and CoreCLR;
  unsupported nullable user structs remain fail-closed. The preceding feature
  completed exact constructed foreign CLR member types and open generic-interface
  inheritance. Resolved `Named` and recursively nested `GenericInstance` trees
  such as `Producer<Box<T>>` remain attached from selected metadata through FIR2IR
  and CIL; the backend never rebinds TypeRefs or TypeSpecs by name. Direct
  `Derived<T> : Base<T>`-style edges enter FIR and exact CLR interface metadata,
  including Kotlin implementations of inherited slots. All declaration carriers
  share one identity-indexed, compilation-local selected graph instead of
  retaining and validating the whole classpath per member. Roslyn nullable flags
  are consumed across the complete resolved type preorder. Callable reflection
  remains declaration-owned (`Box<T>`), while invocation on `NestedBox<String>`
  emits the substituted physical `Box<string>` carrier. C# calls into Kotlin and
  Kotlin calls into C# prove recursively constructed dispatch on Framework CLR
  and CoreCLR. Unsigned carriers, special constraints, nullable constraint roots,
  unsupported constructed bounds, and explicitly nullable generic leaves on
  declared members still reject the complete classifier. The preceding exact
  foreign generic-TypeDef slice retains
  public top-level generic interfaces as their selected native CLR identity,
  with declaration variance, admitted bounds, owner `!n` and method `!!n`
  substitution, properties, vectors, `params`, Kotlin implementations, reverse
  C# dispatch, and primitive-vararg MethodImpl adapters; imported owners never
  enter the Kotlin-owned erased/split-interface ABI. The preceding exact foreign
  method-generic feature emits
  ordinary MethodSpec calls and keeps declaration-owned callable type parameters
  on the same retained binding. The preceding feature supplied
  coroutine-specific physical-ABI evidence. One portable producer DLL proves
  through objective CLR metadata that public
  top-level and virtual suspend entries append the erased `Continuation` and
  return `Object`; non-tail bodies become private sealed
  `DotNetCoroutineImpl` subclasses with callable public-in-private-type
  constructors. Separate `net48` and `net10.0` consumers park and resume both
  top-level and member calls through that producer, so KLIB suspend semantics,
  physical binding, and execution agree across compilation and profile
  boundaries without freezing generated names, capture layout, fields, or
  state labels. The preceding selected runtime-surface authentication proves
  the actual monotone surface of each profile-paired `Kotlin.Runtime.dll`
  through exactly one standard CLR
  `AssemblyMetadata("Kotlin.RuntimeSurfaceLevel", "<level>")` value. The
  objective reader validates the assembly-level attribute parent, external
  constructor/type/assembly edge, exact `(string, string)` signature, and
  ECMA-335 blob without requiring a loaded BCL graph; the CLI then rejects
  missing, duplicate, malformed, stale, and future values before FIR. The
  target-profile core AssemblyRef name is configuration-owned and shared with
  CIL emission. KLIB remains authoritative for a library's required floor,
  while the C# implementation manifest deliberately does not duplicate the
  runtime's actual value. The preceding exhaustive final coroutine-IR
  validation extends Common's final validation with a target-specific phase
  placed after every .NET declaration/body producer. It rejects residual suspend
  declarations, calls, ordinary/raw/rich function references, suspension
  pseudo-expressions, and compiler-only coroutine/context intrinsic calls
  before CIL emission. Ordinary `Continuation` and `COROUTINE_SUSPENDED`
  runtime operations remain valid, and the emitter keeps its unconditional
  production guard for compilations without `-Xverify-ir`. The focused
  coroutine/bridge/callable-reference matrix covers 112 tests in 12 suites
  across both FIR parsers and both target profiles with zero failures, errors,
  or skips. The preceding shared Common/JVM suspend default, interface,
  and callable-reference closure executes unchanged
  upstream tests for member defaults, suspending default lambdas, generic
  interface default bodies and specialization, adapted default references,
  structure/name identity, direct execution views, and the full
  `KSuspendFunction`/`SuspendFunction`/`KFunction(N+1)`/`Function(N+1)` cast
  distinction. These tests consume the existing one continuation/state-machine
  pipeline and Common default/interface lowerings; no target-specific source
  copy or alternate suspend ABI was added. The preceding Common/JVM
  callable-arity closure spans fixed
  `Function0`/`KFunction0` through `Function22`/`KFunction22` and the vararg
  execution-arity-23-and-above `FunctionN` boundary. Big ordinary functions,
  explicit and transitive-interface implementations, callable references,
  arity-classified tests/casts, positional and named reflection, separate
  libraries, and logical suspend arity 22 now use one same-object erased
  capability. Reflective defaults use one Common-lowered dispatcher template,
  32-bit `IntArray` omission words, and a late linear exposed-to-physical mask
  translation; 33 dependent defaults cross the first word boundary without
  combinatorial helpers. Runtime surface and library ABI version 28 own the
  new `FunctionN`/multiword-mask contract; the physical-name grammar is
  unchanged. The executable Kotlin coroutine foundation remains complete through its current
  liveness/member/extension/context/primitive-carrier closure. Target-owned
  compiler performance reporting remains active
- Maturity: high-quality pre-ABI prototype of an explicitly bounded Kotlin
  subset; no third-party binary compatibility is promised

This maturity statement measures the coherence and adversarial verification of
the admitted subset, not percentage completion of Kotlin as a language or
stdlib. The target is not close to 98% feature-complete: remaining
mapped/Stdlib and foreign member reflection, constructors and declared-member
APIs, the remaining
coroutine programme beyond its executable continuation/state-machine
foundation, multi-field value classes, random/remaining sorting families, and
Gradle/KMP product
integration remain substantial open programmes.

## Current green gate

The CharSequence min/max aggregate head passed every constituent of
the strict target gate. The normal aggregate command remains:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

The latest aggregate plus explicit model-suite freshness rerun completed
successfully on 2026-08-18. Backend, FIR2IR, stdlib product,
Framework/CoreCLR, Roslyn, and integration inputs were executed for the final
semantic head. Direct audit of all three freshly written result roots covers
190 XML files and 2,274 tests:

- 6 policy-free physical CLI model/serializer tests
- 2,143 FIR, IL-text, and box tests
- 125 generated CLI and library-integration tests
- zero failures, errors, or skips

The earlier profile-specialized generic-array-fill aggregate and explicit
model constituent exited successfully. That historical head additionally
proves all eight exact scalar fill carriers, nullable/null/
reference/open substitutions, empty/partial/full ranges, ordered evaluation,
both range-failure categories, Framework typed stores, CoreCLR generic BCL
execution, retained erased-owner fallback, and unchanged generic-copy,
Sequence/RingBuffer, and declaration-eviction evidence.
Relative to schema version 4, the 2,216-test inventory also executes exact
producer-open-TypeDef classifier normalization, multiple closed constructions,
ancestry-based logical instance classification, capability/foreign rejection,
KLIB-only logical type-argument authority, complete physical-callable-family
collapse, and selected semantic-dispatch invocation. Schema version 6 adds the
compiler-derived external Kotlin subclass proof, exact child/base constructor
and positional-delegation records, ordered GenericParam constraints, fake-
override declaration-root joins, current-compilation owner scope, inherited
fake/default omission, sealed child overrides, and the hostile C# generic
grandchild. It retains exact target profiles; statically exact constructor
MethodDefs, visibility, constructed
owners, and `this`/`base` edges; duplicate/cyclic-constructor rejection; exact
paired typed/semantic state access and conversions; producer reflection; and a
record-driven separate C# consumer through PSI and LightTree on Framework CLR
and CoreCLR. The separate finite construction plan adds four statically rooted
exact routes, one mandatory semantic fallback, idempotent nullable-value
normalization, and exact/fallback state and classifier execution in all eight
hostile lanes without changing the producer artifact or production emitter.
All report zero failures, errors, or skips. The existing
generic-class Kotlin/C# integration
test retains its user-defined struct subclass, virtual star reads, candidate
rejection without premature narrowing, same-state mutation, and Kotlin `<T>
nullableBox(T?)` value/reference substitutions. The unchanged negative IL-text
case continues to reject method-owned invariant/input open generic arrays.

Schema version 7 adds the complete producer candidate-classification catalog
and requires every optional physical family to match its owner disposition,
arity, constructor keys, and member keys. The hostile metadata-fixed derived
owner round-trips as a known erased-only exclusion without a physical family;
known absence, unknown logical members, and malformed family/catalog joins are
separate rejected states. Both frontends and both CLR profiles execute that
record in the eight hostile lanes.

The current schema-14 family also contains the compiler-proven typed-state
control. Codec and reflection oracles require the private field plus exact read
and write signatures to use the owner GenericParam, and require both exact
state paths to be identity operations. Direct C# execution proves the explicit
capability dispatchers remain private/virtual/final and reject an incompatible
write before mutation. Int, non-trivial struct, and nullable exact/capability
routes execute with identical paired checksums on Framework 4.8 and .NET 10
JIT, ReadyToRun, full trim, and NativeAOT. Declaration-independent fields and
private ordinary helpers now complete the representative OctoTree body graph
without widening the generic semantic-state rules. The production emitter still
ignores the artifact and emits the accepted erased owner.

Before that aggregate, the final ordinary hostile matrix was explicitly
rerun without the measurement property: PSI/LightTree × Framework/CoreCLR ×
same/separate compilation produced four XML suites, eight tests, and zero
failures, errors, or skips. The opt-in clean-room export then reran the exact
net10 separate-compilation lane and passed its strict task-input audit. The
resulting six-file bundle retained all five hashes after JIT, ReadyToRun, and
full-trimming publication and execution; all modes produced checksum
`2027804433`. Its schema-7 physical-family artifact hash is
`765c8bd9d62c81d35eae0cb036c78677de6d5020afb2cd8e9508125eafcf422e`;
the producer DLL remained unchanged. That version-1 result left NativeAOT
false. Workload version 2 changes only the generated source for a post-protocol
working-set handshake and subsequently completes the four-mode native link/run
recorded in
[`docs/archive/generic-owner-native-aot-measurement-2026-08-13.md`](docs/archive/generic-owner-native-aot-measurement-2026-08-13.md).

This aggregate also includes compiler-derived exact constructor, member-role,
and masked-default signatures for the temporary generic-owner physicalizer.
Diagnostic source labels no longer select carriers, role signatures, state
routes, or physical targets; hostile in-memory relabeling must preserve the
complete artifact. Unsupported carrier shapes and captured inner parameters
whose pre-normalization slot domain disagrees with their structural carrier
return no exact proof. Duplicate physical MethodDef identities reject the
artifact. The focused generic-inner and hostile matrix passed 14 tests before
the aggregate, with zero failures, errors, or skips.

This aggregate includes the first production-inert owner-admission analysis,
immediately before erased generic-interface bridge construction. It
records a plan for every local Kotlin-owned generic class, classifies direct
member inputs as strict, shared type-safe barriers, or authoritative semantic
bodies, detects explicit owner-parameter `T?` in metadata-fixed supertypes,
and records direct semantic writes to owner-dependent fields plus open typed
outputs. Its result vocabulary contains only blockers and outstanding proof
obligations—there is deliberately no admitted/reified result—and the emitter
does not consume it. The following erased bridge lowering rejects any generic
class which bypassed planning, then still registers every planned class under
the accepted erased ABI. The open unsafe-store oracle now makes both mutation
and output virtual so the hardest state/output family passes through this
analysis. Backend compilation and all eight hostile PSI/LightTree,
Framework/CoreCLR, same/separate lanes pass with zero failures or skips.

Focused work after that aggregate extends the plan into compiler-produced but
detached prototype member IR. Each logical member now has an explicit typed
entry, semantic hook, and/or capability-dispatcher role; direct semantic field
writes select a required one-object state carrier, and open owner-dependent
outputs require a paired semantic hook. Detached signatures preserve the real
generic-owner receiver and typed explicit inputs/results while erasing only
the semantic explicit domain. They also retain selected masked-default
dispatchers, actual direct-`super` call counts, and pre-lowering owner/member
logical keys. A hard invariant proves no prototype member enters the owner's
declaration list. The backend returns only immutable, IR-free in-memory
snapshots; codegen ignores them and no DLL/KLIB schema or compiler option is
changed. The hostile test fixture validates the exact nullable-inheritance and
unsafe-store snapshots in all eight parser/profile/module lanes. One initial
assertion correctly failed because it counted the typed generic `this`
receiver as a semantic argument; the observation was fixed to erase only
explicit inputs, and the full focused matrix then passed. The test-owned CLR
physicalizer now also validates GenericParam, InterfaceImpl/MethodImpl,
typed/protected semantic virtual slots, one object field, and paired override
base definitions on CLR 4 and CoreCLR. These focused additions are newer than
the 2,204-test aggregate above; normal production emission remains erased.

The latest focused tranche replaces the direct-write approximation with one
shared module-wide producer graph. It indexes ordinary functions, constructors,
all function-access edges, field initializers, and anonymous initializers once,
then projects each generic owner's fields without rescanning the module. Private
helpers remain strict nodes and acquire semantic reachability only through an
exposed semantic entry. The hostile store now routes `writeUnsafe` through
`installUnchecked(Any?)`, the lowered private setter, and finally `stored`; the
snapshot proves that full chain, its declaration initializer, and the exact
semantic writer. The read-only unsafe producer independently proves typed
private storage because its sole write is a direct owner-typed declaration
initializer. An ordinary function-written field with no semantic-reachable
writer now remains `TYPED_WRITE_VALUE_PROVENANCE_REQUIRED`; absence of a broad
entry cannot prove a private `Any? as T` helper safe. Externally accessible
fields remain incomplete. No field result admits an owner.

The hostile test facade now consumes those exact immutable snapshots. It emits
a temporary CLR-generic producer from the recorded state carrier and
typed/hook/dispatcher roles, compiles a separate C# subclass/consumer assembly,
and executes delayed typed-read failure, compatible typed overrides,
incompatible semantic overrides, paired output overrides, GenericParam, one
object state, and explicit InterfaceImpl/MethodImpl behavior on CLR 4 and
CoreCLR. This is test-owned physicalization only: the production emitter,
structured CLI model, DLL/KLIB schemas, and compiler options remain unchanged.
Backend/fixture compilation and one modern snapshot/semantic lane pass after
the complete initializer-aware graph. The final combined snapshot physicalizer
and semantic oracle then passed all eight PSI/LightTree, Framework/CoreCLR,
same/separate-compilation lanes: four XML suites, eight tests, and zero
failures, errors, or skips. At that point a fresh full aggregate was still
required; the subsequent fresh aggregates below include this tranche.

The first fresh aggregate after the producer-graph tranche correctly rejected one planner
invariant in `genericClasses` across four parser/profile lanes. The cause was
an owner-projection bug: `WidenedProbe.inspects` writes owner-independent
`lastPacket: Any?`, but the invariant compared that field with the carrier map
which intentionally contains only owner-dependent state. Restricting field
effects to `expected: T` fixed the planner. A following test assertion was also
tightened from the incorrect “no state” expectation to exactly one typed
`expected` state and no `lastPacket`. The focused generic-classes matrix now
passes with zero failures. The following coherent fresh aggregate, build
`82c6d9d9-ad1a-4ab0-88a3-b6223fb21ac6`, was a clean 2,332.2-second run.

The next completed architecture slice adds fail-closed typed-write value
provenance to that graph. Every actual owner-dependent field write is now
classified as `PHYSICALLY_TYPED`, `SEMANTIC_OBJECT`, or `UNRESOLVED`. Boundary
seeds propagate through call arguments, local definitions/assignments,
returns, and type operators; a cast to logical `T` preserves its input domain
and cannot manufacture typed evidence. The new invariant hostile store routes
exact `T` through a private `Any?` helper and `as T`, proving both its
initializer and lowered setter typed. The covariant hostile store uses the same
final cast shape but remains object-backed because its exposed input is
widened. Mixed semantic writes select the one object carrier, unresolved paths
retain an explicit proof obligation, externally accessible state remains
cross-assembly incomplete, and no result admits an owner or affects emission.
Backend/CLI/fixture compilation, the eight hostile lanes, and the six broader
generic-class lanes passed before the fresh aggregate above; its final strict
audit is 190 XML files, 2,204 tests, and zero failures, errors, or skips.

Focused work after that aggregate extends detached families through local
Kotlin-produced generic subclasses. A derived typed entry now overrides only
the ancestor typed prototype; an inherited semantic-hook obligation adds a
derived semantic prototype and links it only to the ancestor semantic
prototype. Private final capability dispatchers remain outside the override
graph. For an external generic base, the consumer records the producer’s
logical member key and receives
`REQUIRES_EXTERNAL_OVERRIDE_BINDING_SCHEMA` instead of guessing a physical
MethodDef or silently dropping a semantic obligation. The hostile oracle adds
local `HostileUnsafeDerived<T>`/`HostileUnsafeMid<T>` chains and an external
`ConsumerUnsafeLeaf<T>` chain. Backend/CLI/fixture compilation, all eight
hostile lanes, and all six broader generic-class lanes pass with zero failures,
errors, or skips. The following fresh aggregate, build
`ce6a7dcb-8b33-4c68-9cd6-073dea4657b4`, completed in 2,409.5 seconds; its strict
audit covers 190 XML files, 2,204 tests, and zero failures, errors, or skips.
Production emission and serialized schemas remain unchanged.

The next production-inert slice supplies the missing first cross-assembly
family record without changing that production boundary. A deterministic
version-1 artifact is tied to the exact temporary producer DLL by SHA-256 and
records logical owner/member keys, physical implementation and semantic-
capability paths, arity, candidate disposition, state-carrier requirements,
complete member roles and semantic reasons, selected MethodDef names, and
final/virtual/abstract dispatch. Decoding constructs and validates the complete
artifact before returning it. The hostile fixture rejects stale, truncated,
wrong-producer, duplicate-owner, incomplete-role, and missing-member records.
The separate Kotlin consumer's external logical obligations then resolve to
the producer-selected typed and semantic names, while the final capability
dispatcher stays outside the override set. A separately compiled C# subclass
uses only those resolved paths/names and executes compatible typed overrides,
incompatible semantic overrides, delayed typed-read failure, and one inherited
state on CLR 4 and CoreCLR. Backend and fixture compilation, all eight hostile
lanes, and all six broader generic-class lanes pass with zero failures, errors,
or skips. The following fresh aggregate, build
`e2478b09-9969-47e3-b928-68025e4be5a9`, completed in 2,420.5 seconds; its strict
audit covers 190 XML files, 2,204 tests, and zero failures, errors, or skips.
The artifact is not emitted into the erased production DLL/KLIB, consumed by the
normal planner/emitter, or represented in `dotnet.ir`; the complete production
binding still needs slot-domain, direct-super/default, construction/profile,
reflection, and physical-signature records plus the atomic owner migration.

Focused work after that clean aggregate advances the architecture artifact to
schema version 2. An external override binding now retains the exact producer
owner path as well as method name/dispatch. Each member records a sorted set of
root logical keys rather than collapsing Kotlin intersections to one invented
family ID. Exact source-level `super` calls are retained as logical callee plus
super qualifier and serialized into distinct typed/semantic physical owner+
method targets; capability dispatchers are rejected as super targets. Masked
default dispatch is recorded separately as a static helper owner/method, never
as an override role. The hostile store adds defaulted `label`; the temporary
producer supplies `LabelDefault`, and the separately compiled C# consumer
proves that this recorded static helper still reaches its derived virtual
`Label` override. Schema version 1, invalid root sets, and dispatcher-as-super
records fail closed. Backend/fixture compilation and all eight hostile lanes
pass with zero failures, errors, or skips. The following fresh aggregate,
build `a71561d8-73bc-453d-94c1-ebd836726575`, completed in 2,865.0 seconds;
its strict audit covers 190 XML files, 2,204 tests, and zero failures, errors,
or skips. Production emission remains erased and the remaining architecture
record still needs complete slot-domain/physical-signature,
construction/profile, state-access, and reflection-normalization identities
before any atomic migration can be considered.

Focused work after that aggregate advances the architecture artifact to schema
version 3 and closes the slot-domain/physical-signature item. Each physical
role now records its exact MethodDef owner/name, dispatch, instance/static and
generic arity, return/parameter domains, and a recursive neutral type
expression. Capability dispatchers separately record the exact non-generic
interface MethodDef they implement with an equal signature. Owner `!T`, method
`!!T`, producer/core/assembly named instances, and SZ arrays are structural
rather than textual IL. Direct-super targets and the static masked-default
helper carry complete signatures, and state records name their physical type.
The hostile `echo(Array<out T>)` and `relay(Array<R>)` members prove nested
`!T[]`, semantic `System.Array`, and `!!R[]` records. Producer reflection and a
record-driven separate C# consumer validate the emitted MethodDefs and exact
interface map on Framework CLR and CoreCLR; inconsistent role-domain vectors,
missing or mismatched capability slots, out-of-range type parameters, and
direct-super signature disagreement all fail closed. A locally strict consumer
override inherits the producer's authoritative broad input rather than
narrowing the override family. Backend/fixture
compilation and the eight hostile parser/profile/module lanes pass with zero
failures, errors, or skips. The fresh schema-version-3 aggregate above includes
this tranche. Production emission remains erased; construction/profile,
state-access, and reflection-normalization records remain outstanding.

Focused work after the schema-version-3 aggregate advances the architecture
artifact to schema version 4 and closes the static construction/profile and
state-access record items. The compiler snapshot retains every source
constructor's domain vector and exact logical `this`/`base` join. The physical
record selects one target profile and open-TypeDef classification mode, admits
only `STATIC_EXACT`, and retains each constructor MethodDef, visibility,
constructed `C<!T>`, and exact delegated constructor identity. Local
delegations must resolve to a recorded constructor with the same signature;
`this` stays on the same construction and `base` targets a different owner.
The hostile semantic-object state now records one private field and paired
typed/semantic read/write MethodDefs with explicit identity, widening/boxing,
and checked cast/unbox conversions. The separate C# consumer derives its
immediate generic base, constructor input, and four state operations only from
the decoded record; producer reflection verifies both public constructor
overloads, the generic base edge, field visibility, and interface maps. Stale,
wrong-profile, incomplete-constructor, malformed-delegation, wrong-construction,
unpaired-state, conversion-mismatch, and unrecorded-access inputs fail closed.
Backend/fixture compilation and all eight hostile parser/profile/module lanes
pass with zero failures, errors, or skips. Production emission remains erased;
runtime-selected/fallback construction, reflection normalization, and a
Kotlin-produced subclass physicalizer remain outstanding.

Focused work after schema version 4 advances the architecture artifact to
schema version 5 and closes the reflection-normalization record item. Each
producer open implementation TypeDef maps to the pre-existing KLIB logical
classifier key; the physical artifact intentionally carries no copied Kotlin
name or logical type-argument graph. Exact open/closed classifier lookup and
ancestry-based logical instance classification are separate operations.
Capability interfaces and foreign subclasses cannot normalize as Kotlin
classifiers, while two different closed constructions of the same open owner
normalize identically. KLIB remains authoritative for logical type arguments,
variance, projections, nullability, and bounds. Every logical source callable
records its complete typed/semantic/capability/default-helper MethodDef family
as one declaration and selects either the semantic dispatcher or strict typed
entry for invocation; constructors retain their distinct schema-4 construction
records. Malformed physical/logical classifier joins, omitted callables,
typed invocation of semantic families, and incomplete MethodDef families fail
atomically. The record-generated separate C# registry executes exact and
ancestry normalization, capability/foreign rejection, and private explicit-
interface dispatcher discovery. All eight hostile PSI/LightTree,
Framework/CoreCLR, same/separate-compilation lanes passed in four fresh XML
suites with eight tests and zero failures, errors, or skips. Production
emission remains erased; runtime-selected/fallback construction and a
Kotlin-produced subclass physicalizer remain outstanding.

Focused work after schema version 5 advances the artifact to schema version 6
and closes that bounded subclass-physicalizer item without changing production
ABI. The consumer planner resolves fake overrides to the real declaring
Kotlin/KLIB root. A pure
physicalization step then combines the compiler snapshot with the completely
decoded producer artifact: the caller chooses only a distinct output TypeDef
path, the delegated constructor selects the immediate `Base<!T>` construction,
and exact producer records select every typed/semantic override and direct-
`super` MethodDef. A distinct current-compilation type scope prevents the child
from masquerading as a producer TypeDef and is rejected inside producer
artifacts. The bounded constructor grammar records direct owner-parameter
signatures, ordered GenericParam constraints, and whether the real delegating
call forwards each child parameter positionally. Matching domains, a mismatched
signature, transformed arguments, or reordered arguments fail closed. The child
must be public/open/non-inner, contain one direct base and constructor, and add
no interface, field, initializer, nested type, state, or non-fake member.
Inherited fake overrides/default helpers remain inherited. Final child member
overrides are retained as sealed while final producer slots remain illegal. The
record-generated C# consumer now contains the physicalized open Kotlin subclass
and a further C# generic grandchild;
reflection proves the immediate base differs from the ultimate MethodDef owner,
and execution covers compatible typed dispatch, incompatible semantic dispatch,
direct `super`, default dispatch, and delayed typed-read failure. Backend/
fixture compilation and all eight hostile PSI/LightTree, Framework/CoreCLR,
same/separate-compilation lanes pass in four fresh XML suites with eight tests
and zero failures, errors, or skips. Production emission remains erased. The
then-current 2,204-test aggregate included this tranche.

Focused work after the schema-6 subclass physicalizer adds a separate finite
open-nullable construction plan without changing the producer artifact. The
caller supplies only a logical construction identity and unique concrete
final-compilation runtime types. The decoded producer supplies the open
unconstrained owner, semantic capability, and public strict one-`!T`
constructor. Listed value types map to
statically visible `C<Nullable<V>>`, listed references to `C<R>`, and an
already-nullable value remains idempotently nullable. Exactly one `C<object>`
fallback handles every unlisted type through the same capability; open roots,
duplicates, invalid/nested nullable roots, constrained owners, a missing
fallback, owner/constructor mixing, and dynamic closure are unrepresentable or
rejected. The record-driven C# factory executes exact
`int`/`int?`/`string`/consumer-struct routes and unlisted struct/reference
fallbacks on all eight hostile lanes, preserving mutation and honest physical
reflection/classifier normalization. A .NET 10 NativeAOT control promotes
IL3050/IL2026 to errors: the old `MakeGenericType` control fails at IL3050,
while the finite factory passes managed AOT analysis. A later explicit signed
MSVC toolchain run links and executes that bounded factory; representative
applications remain required, so this tranche has not changed production
admission. Backend/fixture compilation, the
explicit six-test model refresh, all eight focused hostile lanes, and the
then-current 190-suite/2,204-test aggregate was green with zero failures,
errors, or skips.

Focused evidence additionally produced and consumed the self-describing net10
Stdlib, executed the eight hostile open-nullable cases and continued negative
case, and ran the Roslyn authoring case independently before the aggregate.
The emitted Stdlib exposes projected filtering through `System.Array` and the
nullable generic Set vararg through `object[]`; the latter contains no `T[]`
signature or cast. C# passes a `string[]`, `Nullable<int>[]`, and `object[]`
directly into those APIs. The separate source-inventory product and C#
physical boundary each passed once with no skip.

Focused generic-owner evidence also executed the eight hostile oracle lanes
and the strengthened portable Kotlin/C# boundary independently before this
aggregate. The oracle composes open mutable invariant state, widened
`Collection<Any?>` candidate operations, star/output/input projections, open
nullable value/reference construction, metadata-fixed `D<T> : C<T?>`
inheritance for value and reference substitutions, arbitrary widened
`@UnsafeVariance` bodies, owner-relative methods, generic interfaces,
multi-level overrides, defaults, arrays, classifier normalization, and
  separate modules. The expanded oracle passed all eight PSI/LightTree and
  Framework/CoreCLR lanes in the aggregate. The historical typed-owner audit identifies the removed
model's exact failure—erased arguments were narrowed to physical `T` before
the Common semantic body. The draft replacement now classifies member-slot
domains: strict inputs/outputs may use natural typed virtuals, while broad
candidates use distinct typed, semantic-hook, and capability-dispatcher roles.
No production CLR-generic Kotlin owner is emitted by this checkpoint.

Two direct CLR integration probes in this aggregate establish additional
architecture evidence on CLR 4 and CoreCLR. A guarded statically emitted
`Nullable<!!T>` construction still fails at execution, while runtime closure
creates exact `Owner<Nullable<int>>`/`Owner<string>` instances that retain null
and mutation through one semantic capability. A second probe validates strict
typed read/write, broad incompatible candidates, nullable/reference/user-struct
state, typed C# overrides, Kotlin-like semantic overrides, and multi-level
dispatch on one generic owner. Separate focused evidence from a bounded .NET
10 application also passed
ReadyToRun and full-trimming execution. NativeAOT analysis reported IL3050 for
runtime `MakeGenericType`; at that checkpoint native linking was unavailable.
That unbounded dynamic route remains invalid for NativeAOT even though the
later statically rooted finite factory now links and runs natively.
The three CLR integration methods were included in that 2,204-test aggregate;
the external publish analysis is evidence rather than another test.

The dispatch probe now also exercises the metadata-fixed
`D<T> : C<T?>` fallback directly. A single `C<object>` base preserves one
state, virtual/`super` behavior, and nullable value/reference mutation on CLR
4 and CoreCLR, but reflection and C# necessarily see only `C<object>` and no
assignability to the logical closed base. The programme therefore classifies
this as an honest erased/fallback declaration, not a successfully reified
owner shape.

A focused separate-assembly C# producer/consumer probe also passes on CLR 4
and CoreCLR. It preserves typed overrides for compatible values, semantic-hook
dispatch for general incompatible widened values, shared fixed-result barriers
which do not enter that hook, multi-level state identity, and the explicit
abstract broad-member obligation. Both C# compilers reject a deliberately
typed-only abstract subclass. Kotlin IR has not yet produced these member
families, so this closes physical feasibility rather than the compiler gate.

Focused delegated-property evidence additionally reports 24 Common/hostile
semantic executions across PSI, LightTree, Framework CLR, and CoreCLR plus one
separate Kotlin/Roslyn boundary execution, all with zero failures, errors, or
skips. Post-rebase focused evidence also retains 8 packed-KLIB loader tests,
4 prepared/main/selected/reified inline-library tests, and 2 installed
`kotlinc-dotnet` launcher tests. The compiler-argument JSON, BTA API, FIR2IR
test, and KGP API owners produced no tracked generated churn.

At the preceding coroutine head, test-only evidence was strengthened to require the
imported member entries to retain their CLR virtual slots and to dispatch a
base-typed consumer call to a distinct suspending override. The exact
portable-coroutine test repeated green in 49.3 seconds; all production
compiler/runtime code was unchanged from the aggregate head.

The final test-only strengthening also exposed an avoidable invalidation:
`compiler:tests-integration:dn` declares all of `compiler/testData/codegen` as
an input even though the selected .NET integration sources directly reference
only `dotnet/portableSurfaceVerifier.cs` from that root. Changing one .NET box
test therefore reran the complete 671-second `dn` suite. Narrow this only
through an upstream-compatible test-data input boundary that keeps every real
consumer tracked; do not hide the cost with an ad-hoc task exclusion.

The next profile after fixing the emitter showed a separate foreign-loading
cost: every 1/2/4-byte metadata-table value and every byte scanned from
`#Strings` performed a `RandomAccessFile.seek`. Buffering only the bounded CLI
metadata directory reduced the exact physical-metadata/signature testcase from
123.031 to 3.417 seconds. The two largest real importer cases changed from
208.84 to 18.953 seconds for cross-profile foreign interface binding and from
234.61 to 11.576 seconds for Common deprecation enhancement; a hostile
`NotNullWhen` case changed from 31.98 to 5.814 seconds. The complete gate kept
the same 1305-test matrix while wall time fell from 34m43s to 16m19s and `dn`
JUnit time from 1721.71 to 627.14 seconds. A live heap inspection during the
integration run showed 387 MB old generation and 868 MB young generation. That
single snapshot is consistent with a large committed/transient young heap and
provided no evidence that the full process working set was a retained assembly
graph; it is not a complete allocation or leak profile.

This is the CLR analogue of mature targets consuming bounded binary metadata
from memory, not permission for a compiler-wide cache. Selected graph identity,
file freshness, target profile, and compilation lifetime still require an
explicit shared .NET platform/import owner before any cross-read cache is
considered. Re-profile before adding string/blob memoization or test bundling.

The performance investigation distinguished a hotspot from a correctness
loop. In the baseline JFR, 521 of 1961 CPU samples (26.6%) ended in
`computeFqNameString`; a compilation-local `IrClass` identity cache reduced
that leaf count to 100 and the 60-second sampled TLAB allocation weight from
about 120 GB to 69.85 GB. The final instrumented producer observed 320,458
cache hits among 320,718 classifier queries over only 260 unique declarations
(99.92%). No mapped CLR type, target-profile decision, assembly-reference side
effect, or live emitability result is cached.

A later profile of the heaviest library-publication testcase found a separate
repeated-identity cost in the consumer's Kotlin-to-CLR binding index. Shared
KLIB serialization computes one public `IdSignature` per IR declaration in a
declaration table; the .NET consumer instead rebuilt and rendered that same
signature for repeated class, function, enum-entry, interface-default, generic
bridge, and covariant-bridge queries. `DotNetExternalDeclarations` now retains
the final kind-prefixed binding key by IR identity for the lifetime of that one
binding index, including negative results, and rejects inconsistent kind reuse.
It is deliberately not shared across lowering phases because local IR can still
change, matching the JVM bridge-cache warning against retaining a physical
signature beyond the IR shape from which it was derived.

On the same JFR-instrumented publication case, recording duration changed from
67 to 62 seconds and total sampled allocation weight from about 15.30 to 14.42
GB. More importantly, the directly attributable external-binder ABI-key stacks
changed from three samples/53.9 MB to zero: the previous samples came from
interface-default class forwarders, generic-interface view bridges, and
covariant-return bridges. Overall wall time and sampled totals remain noisy and
are not a promise of a fixed five-second gain. Remaining ABI-key samples belong
to producer-index construction and canonical-interface slot naming and must be
profiled as separate owners before either is changed.

The shared compiler performance reporter now recognizes `DotNet` as its own
`PlatformType`; `-Xreport-perf` and JSON performance dumps no longer fail with
`Unexpected platform DotNet (dotnet)`. The correction also exposed and removed
overlapping phase accounting. In-memory KLIB metadata/IR serialization is
reported as `IR SERIALIZATION`, backend lowerings as `IR LOWERING`, and CIL
emission plus assembly as `BACKEND`. Packing the self-describing KLIB resource
is a dynamic backend subphase because its physical declaration index exists
only during emission; it is not misreported as an overlapping top-level KLIB
writer.

Two isolated installed-stdlib scale series exercised generic interfaces,
default bodies, inheritance, method generics, function types, properties, and
publication at 25/50/100 and 100/200/400 declaration families. In the larger
series, 1,404/2,804/5,604 user lines changed backend time from 809 to 983 to
1,252 ms and IR lowering from 385 to 554 to 820 ms. The sum of the measured
top-level compiler phases changed from about 5.50 to 7.11 to 9.67 seconds.
Four-times the source therefore produced roughly linear variable cost and no
quadratic emitter or lowering signal. A 20 ms process observation deliberately
distorted wall time and is not a benchmark, but established that the heaviest
publication test starts five ILAsm processes; inspection confirmed five
different producer/profile products rather than repeated assembly of one
fixture. Ordinary codegen tests already consume the reusable profile products
selected by the test-product ADR. Direct PE writing, emitter parallelism,
broader caches, and cross-test product sharing remain unjustified without a new
profile showing a material cost and a preserved isolation/freshness proof.

That cache did not explain the apparent multi-hour product emission. A nested
Stdlib declaration removed from the live codegen set was repeatedly
reconstructed by a resolution-only fallback, so the diagnostic fixpoint made
thousands of rounds without changing state and never reached ILAsm. One
diagnostic run was stopped after nearly four hours; it was not a completed
timing baseline. Making the selected local declaration set authoritative and
requiring monotonic fixpoint progress reduced an exact cold net48
Runtime+Stdlib producer to 23.6 seconds including assembly; the corresponding
net10 producer completed in 65.6 seconds. The next performance work remains
profile-guided and should measure per-test product, ILAsm, and CLR process
counts rather than infer another emitter hotspot from aggregate wall time.

An earlier structured-CLI review replaced the serializer input's
ILAsm-shaped version string
with the equivalent dotted CLR assembly-identity value; emitted CIL is
unchanged. That head repeated all 6 model tests, the backend
compile, and the two exact affected end-to-end consumers: portable
Kotlin-library `AssemblyRef` production and foreign-CLR reference production
across both runtime profiles. Those focused checks completed green in 18.5s
and 4m21s respectively.

The FIR/IL/box cumulative JUnit suite time fell from 3713.33 seconds on the
callable-parameter head to 490.85 seconds on that structured-CLI head. Ordinary
test modules no longer rebuild `Kotlin.Stdlib`; two exact-profile fixture
producers do so once.
The retained explicit source-product case still validates the complete emitted
stdlib IL. Moving modern ILAsm compatibility to its eight-shape class removes
318 redundant external writer invocations without dropping canonical assembly
of any golden. Compiling `arrayIterators` through the normal DLL consumer path
also exposed and now pins the required erased `Iterator.Next(): object` bridge
for an `IntIterator` subclass, which the former same-run bootstrap path hid.

Before the aggregate, the function-declaration-flags candidate separately
passed an explicitly typed `KFunction0`, inline, inherited operator, infix,
constructor, and ordinary negative shapes across both parsers and runtime
profiles; the complete IL suite on both parsers; and separate
portable-KLIB/imported-CLR/Roslyn/C# boundaries. It also passed producer- and
consumer-created references, stale-library-ABI and runtime-surface rejection,
and complete source-product publication. The preceding named-call candidate
passed eight semantic function and property lanes across both parsers and
runtime profiles, ordinary and generic-interface default boundaries, and its
corrected publication matrix. The earlier structured-CLI head passed the
packed-KLIB loader owner suite (8 tests), the
BTA API-dump and FIR2IR test-generation owners without tracked generated
churn, focused callable-parameter execution across both parsers and profiles,
twelve updated callable/property IL golden cases across both parsers and every
available compatible assembler, the separate KLIB/Roslyn/C# boundary test,
and five focused stdlib-source/product/portable-ABI tests.

The target now compiles the authoritative Common `ClosedRange`,
`OpenEndRange`, floating/comparable range, signed `Char`/`Int`/`Long`
range/progression, progression-utility, and primitive-iterator sources. The
repository's shared `RangeContainsLowering` and `ForLoopsLowering` replace the
former counted-loop matcher; materialized ranges and optimized loops therefore
share the same Kotlin model used by the mature targets. Primitive arrays and
progressions return the real eight Common primitive-iterator base classes
rather than aliases of erased `Iterator`.

The generator-owned product includes signed non-random range operations,
array `lastIndex`/`indices`, exact `Char.code`, and Common `repeat`; private
`until`, `downTo`, and counted-loop bootstrap markers are gone. Adversarial
tests cover empty/extreme ranges, positive and negative steps, iteration and
exhaustion, nullable/mixed contains, non-local return, exact physical fields
and signatures, separate and installed consumers, portable execution, and a
truthful raw C# concrete-class view on Framework CLR and CoreCLR. A general
array-intrinsic result-type correction preserves exact concrete/method-generic
vector elements while treating a star-projection capture as its fixed
`Any?`/`object` read; both IL text and execution pin the absence of an invalid
free CLR `!n` token.

`Set`, `MutableSet`, `Map`, `MutableMap`, `Map.Entry`, and
`MutableMap.MutableEntry` now use one declaration-erased CLR TypeDef and one
virtual slot family per Kotlin declaration. Runtime surface level 16 owns that
public contract. Kotlin `HashMap` follows the shared Native/Wasm open-addressed
algorithm with erased private object-vector key/value storage; `HashSet` is its
set facade. Null keys/values, collisions, resize and upstream compaction, live
views, mutable entries, fail-fast iteration, ordering and builder sealing run
on Framework CLR and CoreCLR. Logical `LinkedHashMap`/`LinkedHashSet` remain
aliases over the insertion-ordered implementation and do not invent extra CLR
TypeDefs.

The bootstrap generator now owns separate Common `MapsKt` and `SetsKt`
facades beside `CollectionsKt`, mirroring source ownership and avoiding CLR
generic-receiver erasure clashes. It admits exact Common factories,
conversions, Map filters/transforms/plus/minus and generated Iterable/object-
array association, eager grouping, distinct, snapshots and Set algebra. A
staged Common `Kotlin.Test.dll` compiles `IteratorsTest.kt` and
`HashMapCompactTest.kt` unchanged against one portable stdlib and executes the
product on both runtimes.

The same tranche closes compiler-wide boundaries exposed by those Common
bodies: a final inherited method can satisfy a newly declared interface
through one private forwarding MethodImpl; a terminal literal `while (true)`
has no verifier-visible fallthrough; Common AbstractMap cache accesses emit
the exact CLR `volatile.` prefix; and stdlib emission binds admitted helpers
locally without a self AssemblyRef. The volatile annotation is resolution-
only and contributes no TypeDef or physical ABI row. The gate covers both
frontends, both runtime profiles, exact IL, portable/separate/installed
products, C# metadata/calls, stale runtime rejection, and hostile callback,
iterator, collision, nullable and non-local-control-flow cases.

All four PSI/LightTree and Framework/CoreCLR runners execute the target-owned
contracts/scope corpus plus three selected upstream contract tests. The test
pipeline now runs the same shared pre-serialization KLIB lowerings as the CLI;
Common `SharedVariableBox` therefore replaces the obsolete test-only mutable
capture cell, and Common non-JVM `ThrowHelpers.kt` is a real stdlib compiler-ABI
dependency. Direct and installed stdlib products, including a separate local-
delegate consumer, originally proved that source closure without enabling
`lateinit`; the later language-feature tranche now enables the Common lowering
at the same pre-closure phase boundary.

Source-defined exception subclasses now inherit standard `Throwable.message`
and `cause` through the universal `System.Exception` virtual slots. The
structural fallback accepts only fake overrides whose real chain remains in
the mapped standard exception hierarchy; a derived user-defined `message`
override remains ordinary Kotlin virtual dispatch. PSI, LightTree, Framework
CLR, CoreCLR, and an installed cross-assembly consumer cover both sides.

Ordinary Kotlin enums are one reference-class hierarchy, never CLR
`System.Enum` value types. The one erased physical `Kotlin.Enum` base now lives
in `Kotlin.Runtime`; Runtime has no upward Stdlib reference, while concrete
Stdlib/user enums retain their own owners. Runtime now also owns the physical
member-free erased `EnumEntries` interface needed by its `KVisibility` enum;
the authoritative Common declaration, `EnumEntriesList` implementation,
factories, and algorithms remain Stdlib-owned, and Stdlib emits no duplicate
interface TypeDef. Entry fields retain
singleton identity and source order; private entry subclasses implement bodies
and abstract members; `values()` is fresh, `entries` is stable, and `valueOf`
uses exact Kotlin names and failure semantics. Both frontend paths execute the
complete adversarial corpus on Framework CLR and CoreCLR. A portable library
is separately consumed by Kotlin and C# on both runtimes, including exact
nested TypeRefs, entry-field metadata, marker attributes, virtual dispatch,
static initialization, arrays, widened `Enum<*>`/`EnumEntries<*>` views, and
same-declaration ordering across distinct entry subclasses. A different enum
presented through an unchecked Kotlin generic view or C# `IComparable` fails at
comparison with the classified `InvalidCastException`/`ClassCastException`.
Reified enum helpers remain fail-closed.

The final gate additionally covers generic-interface erasure on both runtime
profiles. Both owner-dependent `C<T> : I<T>` and closed `C<T> : I<String>`
implement the one erased Kotlin-owned `I`. A modern default lives on that
erased DIM; portable profiles use its recorded helper/forwarder path. Neither
case publishes an implicit CLR `I<T>` sibling. PSI and LightTree agree on
every updated physical shape.

The formerly red generic-class probes are now positive regressions. Widened
Common `containsAll` compares a wrong-shaped element and returns `false`
without premature narrowing, while mutation through an unchecked
`Box<String> as Box<Int>` changes the same object and fails only when the
result is later consumed as `String`. The same corpus covers direct and nested
owner-type inputs, argument identity/evaluation, and a three-level widened
override chain on both frontends and runtime profiles.

All compiler-argument, API, configuration-key, Gradle-option, and test-runner
generators owned by the affected upstream range were rerun through their
owning tasks and produced no tracked output changes. Upstream Test Federation
now treats the .NET FIR, IL-text, and box runners as compiler-domain tests
through the same shared target-specific runner pattern used by JVM, JS, Wasm,
Native, and JKLIB.

Focused evidence additionally covers component-complete packed-KLIB loading,
same- and cross-library inlining from prepared and main IR, all three KLIB
inliner modes, mutable capture and non-local control flow, compiler ABI and
friend access, stdlib-free diagnostics, reproducible direct/fallback stdlib
IR, explicit reified/suspend rejection, and every target/runtime profile. The
selected-graph closure additionally proves that an inline body from library A
binds exact public declarations and a nested inline body from explicitly
selected library B without a general linker; surviving B calls use B's exact
physical assembly while fully inlined A disappears as a runtime dependency.
Both prepared-IR and main-IR consumers reject an omitted B with named unbound
signatures before target lowering and leave no artifact. The
collection product now also proves empty Collection fast paths, exact
short-circuit and traversal counts, nullable/widened predicates, reverse List
search, inlined separate consumers, and direct CIL execution of all six
physical fallback methods on Framework CLR and CoreCLR. The classified
`CharSequence` carrier additionally proves unchanged `System.String` and
custom-implementation identity, shared operation/cast/type-test
classification, erased physical CLR bounds with authoritative KLIB bounds,
portable Kotlin-library consumption on both runtimes, and handwritten C#
implementation through the runtime manifest. The collection-to-array closure
additionally proves exact Common iteration, erased and typed results, nullable
and value elements, undersized allocation, oversized and empty destination
identity, non-Java tail preservation, covariant runtime vector identity,
negative-size failure, and hostile inaccurate-size behavior on Framework CLR
and CoreCLR.

The complete signed Common primitive-array family additionally proves exact
Kotlin-owned `BooleanArray`, `ByteArray`, `ShortArray`, `IntArray`,
`LongArray`, `FloatArray`, `DoubleArray`, and `CharArray` identities over their
private CLR vectors. Constructors, initializer order, literals, varargs and
spreads, direct and escaping specialized iteration, copies/content operations,
RTTI/casts, generic-array separation, bounds failures, portable Kotlin
libraries, and copy-free exact C# vector adapters execute on both frontends and
runtime profiles. In particular, Kotlin `ByteArray` projects as signed
`sbyte[]`/`int8[]`, never C# `byte[]`.

Concrete nullable primitive elements are now complete as ordinary invariant
generic arrays. `Array<Boolean?>` through `Array<Char?>` use exact closed CLR
`Nullable<V>[]` vectors and retain identity through literals, constructors,
generic substitution, nullable varargs, iteration, copies/content operations,
nested arrays, and exact casts on both frontends and runtimes. A portable
netstandard2.0 Kotlin library is consumed separately by Kotlin and Roslyn on
Framework CLR and CoreCLR; all eight natural C# `V?[]` signatures preserve
aliasing. Backend-reachable sentinels continue to reject open `Array<T?>`,
input projections, and value-vector covariance.

Star-projected generic arrays are now complete through one classified erased
view. Every exact reference, value, and nullable-value SZ vector widens to
`System.Array` without copying and retains identity for size, reads, iteration,
reference equality, and later exact casts. Runtime tests and checked/safe casts
share one SZ-array classifier; specialized Kotlin primitive-array wrappers,
rectangular CLR arrays, and rank-one non-zero-based CLR arrays remain outside
the Kotlin `Array<*>` identity. Portable netstandard2.0 Kotlin libraries are
consumed separately by Kotlin and Roslyn on Framework CLR and CoreCLR, while
input projections, open nullable elements, and value-vector covariance remain
negative.

Every Kotlin-owned ordinary generic class now has one canonical non-generic
owner, one erased runtime/virtual ABI, and one authoritative state. KLIB
remains authoritative for type parameters, bounds, variance, arguments,
projections, and nullability. Public/protected owner-dependent positions and
the current baseline storage use an accepted erased carrier, an erased upper
bound, or `object`; reads narrow only at their logical use site. Ordinary CLR
`castclass`/`isinst` over the one owner supplies Kotlin's declaration-erased
identity, including inherited and separate-library cases. The baseline private
layout is not an ABI freeze: removable measured specialization may later use
more exact CLR helpers or storage without changing any semantic observation.

Physical ABI 20 records that one erased generic owner plus producer-owned enum
entry fields. It retains the removal of class capability paths, class-member
bridge records, canonical class interfaces, ancestry classifiers, and typed-
dispatch probes. Imported CLR generics remain reified. Typed C# generic-class
export is a separate fail-closed product rather than an implicit second
implementation ABI.

An erased generic class also no longer fabricates a typed generic-interface
edge: both `C<T> : I<T>` and `C<T> : I<String>` implement the one erased `I`
when `I` is Kotlin-owned. Only an explicitly mapped host capability or imported
CLR interface may retain a separate typed edge. KLIB preserves `I<T>` and
method bounds such as `<R : T>`; the latter omit an impossible CLR
owner-relative constraint.

The complete collection-facing accumulator-fold family now uses the generated
Common bodies for `Iterable.fold`/`foldIndexed` and
`List.foldRight`/`foldRightIndexed`. Adversarial execution pins empty behavior,
nullable and widened values, left/right order, exact iterator protocols, index
association, capture, exception identity/timing, and non-local return. Separate
and installed consumers inline the packaged KLIB bodies, while handwritten CIL
executes every physical fallback on Framework CLR and CoreCLR. A discarded
cross-library fold result retains the authoritative erased-accumulator
`IMPLICIT_CAST`; no failed-cast optimization or new classifier was introduced.

The complete collection-facing receiver-seeded reduction family now likewise
uses the exact generated Common bodies for `Iterable.reduce`, `reduceIndexed`,
their nullable empty variants, and all four corresponding right-reduction List
forms. Physical methods preserve the Common `S, T : S` bound as a real CLR
generic-parameter constraint; open `S?` uses the accepted boxed-or-null fallback
slot while embedded KLIB remains authoritative. Adversarial execution pins exact
empty exception messages or null, singleton no-callback behavior, widened and
nullable accumulators, left/right order and index association, hostile iterator
protocols, operation-failure identity, and non-local return. Separate consumers
inline every packaged body, and handwritten CIL executes all eight fallbacks on
Framework CLR and CoreCLR. Binary inlining's explicit `Nothing?` nullable branch
reuses the existing bottom/null-carrier emission rather than introducing a cast
or classifier.

Common `Iterable.forEach` and `forEachIndexed` use their exact generated inline
loops, and the completed `apply`/contracts product now composes them into the
exact generated `onEach`/`onEachIndexed` same-receiver pair. The embedded KLIB
retains `forEach`'s binary `HidesMembers` directive: a separate hostile consumer
with a same-signature member still resolves and inlines the Common extension,
without requiring a CLR runtime attribute. Adversarial execution pins empty,
singleton, nullable/value, mutation, identity, order/index, exception identity,
stopping point, and non-local-return behavior. Both indexed bodies retain the
Common overflow helper, while handwritten CIL executes all four physical
fallbacks and checks full callback traces on Framework CLR and CoreCLR.

Common `Iterable.first(predicate)` and `firstOrNull(predicate)` now use their
exact generated first-match loops. Adversarial execution pins empty and
no-match behavior, the exact Common `NoSuchElementException` message,
short-circuit traversal, nullable match versus absence, widened elements,
capture, predicate-failure identity/timing, and non-local return. Separate and
installed consumers inline only the predicate overloads while existing no-arg
fallback calls remain; handwritten CIL executes both new physical overloads on
Framework CLR and CoreCLR. Open `T` and boxed-or-null `T?` reuse their existing
physical slots.

Common last-match predicates now use all four exact generated bodies for
`Iterable.last`/`lastOrNull` and their `List` overloads. Iterable receivers scan
forward to exhaustion and preserve a separate found flag where Common requires
one; List receivers request `listIterator(size)` and short-circuit in reverse.
Adversarial execution pins both exact no-match exception messages, empty and
nullable-match behavior, full versus reverse traversal, hostile iterator
protocols, widened/value elements, capture, predicate-failure identity/timing,
and non-local return. Separate and installed consumers inline all four bodies,
while handwritten CIL executes all four physical fallbacks on Framework CLR
and CoreCLR. The open-`T` cast uses the existing checked generic result barrier;
failed typed uses remain an exceptional correctness path and are not optimized.

The first Common `@InlineOnly` batch now publishes 14 exact generated
declarations: List components 1 through 5, List `elementAt` and
`elementAtOrNull`, Iterable `find`, Iterable/List `findLast`, the two
first-non-null transforms, Collection `count`, and Iterable `asIterable`.
Their public logical declarations and bodies remain authoritative in embedded
KLIB, while each physical CLR MethodDef is assembly-visible and unavailable as
C# or cross-assembly fallback API. Separate, packaged, and installed Kotlin
consumers must inline every call. Tests prove direct/reverse access without
the wrong iterator, traversal and callback order, nullable and exception
behavior, object identity, non-local return, physical visibility, C#
inaccessibility, and absence of external inline-only calls on Framework CLR
and CoreCLR. The producer/consumer tests use an actual self-describing KLIB;
the same-frontend bootstrap box harness is not misrepresented as an external
library boundary.

Common `Iterable<T>.sumOf` now publishes its complete signed selector family:
`Int`, `Long`, and `Double`. Their assembly-visible CLR fallbacks use the exact
generator-owned `sumOfInt`, `sumOfLong`, and `sumOfDouble` spellings because the
three logical overloads erase to the same CLR `Function1` parameter shape.
KLIB retains logical `sumOf`; separate and installed consumers inline every
body and cannot call the fallbacks, while C# cannot bind them. The exact Common
`ExperimentalTypeInference` and `OverloadResolutionByLambdaReturnType` marker
declarations now participate in the stdlib source product and receive truthful
physical TypeDefs, but their BINARY applications remain KLIB-only rather than
CLR custom attributes. Adversarial portable execution pins empty zero, `Int`
and `Long` wrapping overflow, ordered IEEE `Double` addition and NaN,
nullable/widened inputs, traversal and callback order, failure identity, and
non-local return across Framework CLR and CoreCLR. UInt and ULong remain
outside this closure because their scalar/runtime and generated-stdlib product
has not yet been admitted on top of the completed single-field value-class
foundation.

Common `Iterable.single(predicate)` and `singleOrNull(predicate)` now use their
exact generated bodies; Common defines no distinct List predicate overload.
Both retain the first match with a separate found flag and stop at the second
match. Adversarial execution pins zero, unique, and multiple-match behavior,
the exact Common no-match and multiple-match exceptions, second-match stopping,
nullable unique null, widened/value elements, capture, predicate-failure
identity/timing, and non-local return. Separate and installed consumers inline
both bodies, while handwritten CIL executes both physical fallbacks on Framework
CLR and CoreCLR. The existing open-`T` cast and boxed-or-null slot remain the
only physical adaptation; LINQ defaults and target-authored traversal were not
introduced.

Common `Iterable.none(predicate)` and `count(predicate)` now complete the
selected predicate-aggregate family alongside `all` and `any`. Exact Common
empty-Collection fast paths avoid iterator construction; `none` stops at the
first match, while predicate `count` consumes the receiver and calls Common
`checkCountOverflow` after each matching increment. Adversarial execution pins
fast paths, traversal/stopping counts, nullable and widened predicates, capture,
predicate-failure identity/timing, and non-local return. Separate and installed
consumers inline both bodies and retain exactly the required count-overflow
helper call; handwritten CIL executes both physical fallbacks on Framework CLR
and CoreCLR. No LINQ quantifier/count rewrite or target-owned loop was added.

The complete signed Common `Iterable.average` family now uses all six exact
generated bodies for Byte, Short, Int, Long, Float, and Double receivers. Each
physical fallback accumulates into `Double` in encounter order, increments an
`Int` count through Common `checkCountOverflow`, and returns `Double.NaN` for an
empty receiver. The logical overloads bind to the bounded Common/JVM platform
names `averageOfByte` through `averageOfDouble`; embedded KLIB remains the
authoritative logical `average` contract and no general .NET meaning was given
to `@JvmName`. Adversarial execution pins all six conversions, empty NaN,
floating-point order, full traversal, and iterator-failure identity. Separate
and installed consumers bind all six physical names, while handwritten CIL
executes every fallback on Framework CLR and CoreCLR. LINQ `Average`, wider or
checked counters, reordered summation, and target-owned bodies were not added.

The post-substitution reified-array audit now proves that every admitted
ordinary array carrier remains truthful after the shared inliner has replaced
a type parameter with a concrete type. The adversarial matrix covers reference,
scalar, nullable-scalar, classified `CharSequence`, generic-class, split-
interface, nested, star-element, primitive-array-wrapper, and `Throwable`
elements; empty, nullable, initialized, negative-size, vararg, and spread
operations execute on both FIR frontends and runtime profiles. These operations
reuse the ordinary `Array<E>` mapper and intrinsics. No reified-only token,
wrapper, or `object[]` fallback was added. The later complete reified tranche
now consumes this carrier matrix without changing it.

The Common `KClassifier`/`KClass` floor and class literals are now complete
without equating Kotlin reflection identity with `System.Type`. Static
`C::class` and single-evaluation dynamic `value::class` produce one nominal
Kotlin runtime value whose classifier is exact where CLR identity is exact and
classified where Kotlin already has a broader or erased relation. This covers
signed scalars, `String`, `Any`, `Unit`, `Nothing`, primitive and generic
arrays, `CharSequence`, `Number`, mapped and custom exceptions, ordinary and
generic Kotlin classes/interfaces, local/anonymous names, Common `cast` and
`safeCast`, and declaration-erased generic-class ancestry. Equality and hashes
use normalized classifier identity rather than names; two same-named CLR
classes from distinct assemblies remain distinct. Exact Kotlin exception
constructor identity reuses weak identity-associated throwable state and never
wraps or mutates foreign `Exception.Data`. Portable Kotlin libraries are
consumed separately by Kotlin and Roslyn, installed stdlib products expose only
the public Common surface, and the retained `System.Type` bridge remains
compiler ABI. This floor now supports substituted `T::class`; member and
annotation reflection remain separate programmes.

The Common logical `KType`/`typeOf` graph is now complete as a layer above that
nominal floor. A post-inlining lowering builds classifiers, nested arguments,
stars, variance, nullability, declaration parameters, and recursive bounds in
two phases, so recursive parameter identity is preserved without using CLR
generic instantiations as Kotlin identity. Exact CLR classifiers reuse their
`KClass` evidence; logical classifiers without a truthful `System.Type` carry a
separate KLIB-mangled identity key and never compare by display name. Runtime
surface 17 pins that compiler/runtime construction ABI.

The selected upstream matrix runs on both FIR frontends and both runtime
profiles. It covers nested and reified types, projections, equality and hashes,
recursive and nullable relative bounds, and a real self-describing portable
library consumed independently by Kotlin on Framework CLR/CoreCLR and by
Roslyn. A bound such as `X : Y?` remains exact in KLIB and `KType`, while its
unrepresentable CLR `GenericParamConstraint` is deliberately omitted rather
than strengthened to `X : Y`.

Common `Comparable<in T>` now retains its full logical identity and recursive
bounds in KLIB while one object exposes the profile-selected canonical
`System.IComparable` and truthful typed `System.IComparable<T>` views. Kotlin
implementations fill both slots through the explicit Comparable mapping bridge
lowering; ordinary C# consumes either interface without an adapter. Logical
interface and type-parameter calls use one versioned semantic helper so String
comparison remains ordinal and Float/Double retain Kotlin NaN and signed-zero
ordering. Direct exact primitive operations keep their unboxed intrinsics.
Tests cover every selected Common scalar, custom and inherited implementations,
recursive bounds, contravariance, type tests, checked/safe/unchecked casts,
delayed mismatches, exact carrier preservation through inferred array common
types, physical MethodImpl rows, portable Kotlin consumers, and both canonical-
only and typed CLR foreign boundaries on Framework CLR and CoreCLR. Runtime
surface level 12 owns the helper; typed polymorphic fast paths remain unselected.

The builder, contracts, and Common abstract-collection tranches publish the
authoritative Common `Appendable`, complete `StringBuilder` including both
`buildString` declarations, generated `Iterable.joinTo`/`joinToString`, Common
`AbstractCollection`/`AbstractList`, the public contracts DSL/effect model, and
the complete Common `Standard.kt`, including `repeat` over the real signed
range/progression closure.
The Kotlin-owned builder wraps private profile-selected BCL storage without
exposing `System.Text.StringBuilder` in public or protected metadata; its
colliding `Any?` overloads have the stable physical names `appendAny`,
`insertAny`, and `appendLineAny`, while KLIB retains the Kotlin names.
`ArrayAsList` inherits the Common base and owns only its retained array, size,
and indexed access. Logical class covariance remains in KLIB while each
Kotlin-owned generic class has its single erased CLR owner.
Source and actualized/deserialized inner generic declarations normalize to the
same outer-first TypeDef arity, closing the separate-library path exercised by
the Common iterator and sublist implementations. The exact Common bodies also
closed general backend gaps for `Int`/`Long` bitwise shifts, Unit-valued effects
in value positions, smartcasts from open type parameters, and projected generic
array reads and writes; none is encoded as a builder-specific rewrite.

FIR consumes Common contracts for data flow, and embedded KLIB retains their
effects across library boundaries. The backend executes neither the DSL nor a
target-authored approximation: `contract` has an assembly-visible fail-safe
physical body under the existing inline-only ABI, while executable consumers
contain no DSL call. The complete Common `run`, `with`, `apply`, `also`, `let`,
`takeIf`, and `takeUnless` bodies preserve calls-in-place analysis, receiver
identity, exceptions, and non-local returns. `InvocationKind` uses the ordinary
Kotlin enum representation.

The exact first CLR contract projection is now complete as an additive export
view. FIR2IR derives a versioned neutral five-effect carrier from resolved
Common contracts; only an explicitly selected export consumes it. `net10.0`
emits the exact `System.Diagnostics.CodeAnalysis` TypeDefs supplied by
`System.Runtime`, while `net48` and `netstandard2.0` omit them because their
selected contracts do not contain those identities. The gate proves both FIR
parsers, every admitted attribute target and constructor payload, Roslyn
nullable flow with warnings as errors, overlap normalization, default-overload
parameter omission, absence on ordinary Kotlin MethodDefs and compound
effects, and a Kotlin consumer of a reassembled DLL after every derived
CodeAnalysis row was stripped. KLIB remains the independent authority.

## Current architecture

- `:core:language.targets.dotnet` owns the logical .NET platform and the
  `net48`, `netstandard2.0`, and `net10.0` target vocabulary.
- `:compiler:config.dotnet` owns generated compiler keys and target-policy
  validation without depending on FIR, IR, backend, or CLI code.
- `:compiler:frontend.common.dotnet` owns objective PE/ECMA-335 facts and
  physical CLR validation; FIR owns Kotlin interpretation.
- `:compiler:dotnet.imports` owns versioned, self-validating neutral carriers
  for selected foreign CLR linkage and the already-derived exact contract
  export subset.
- `:compiler:fir:fir-dotnet` owns foreign Kotlin projection and lazy FIR symbol
  construction without depending on backend or CLI implementation packages.
- `:compiler:fir:fir2ir:dotnet-backend` owns narrow success-only IR
  overridability rules for retained flexible CLR array and method-generic
  declarations and derives the neutral exact-contract projection while resolved
  FIR and IR coexist.
- `:compiler:ir:backend.dotnet` owns Kotlin-to-CLR representation policy,
  target-profile legalization, IR lowering, physical-form construction, and
  backend product orchestration.
- `:dotnet:dotnet.ir` owns migrated policy-free physical ECMA-335 vocabulary,
  structural validation, deterministic CIL serialization, and eventually the
  already-selected JVM-hosted direct PE sink. Its first production-owned form
  is external `AssemblyRef` metadata.
- `:compiler:ir:serialization.dotnet` owns .NET KLIB IR serialization and the
  logical IR mangler shared with backend identity mapping.
- `cli-base` owns the .NET content-root carrier; .NET compilation no longer
  represents CLR roots as JVM classpath roots.
- Common and generated stdlib sources remain semantically authoritative.
  .NET supplies narrow actuals and irreducible CLR operations.
- Kotlin-produced libraries are self-describing DLLs. KLIB remains the exact
  Kotlin declaration contract; CLR metadata and standard attributes provide
  the truthful physical and foreign-language view.

## Active state

The complete signed primitive/object-array sorting closure is published from
exact Common generator and Native/Wasm-derived sources. Whole/range natural
sorts cover Byte, Short, Int, Long, Float, Double, and Char wrappers; the full
reverse, descending, snapshot, selector, and `isSorted*` consumer graph is
present, with Boolean limited to reversal and explicit comparator/selector
operations. Primitive in-place operations retain wrapper/backing-vector
identity, object sorting remains stable, invalid ranges fail before mutation,
and snapshots follow Common aliasing rules. Generic copy snapshots allocate
from the source CLR vector's runtime component type and therefore work while
the producer's `T` is still open; no `object[]`, `newarr !T`, or
`System.Array.Sort` shortcut is used. Direct and separate PSI/LightTree
consumers are green on Framework CLR 4 and .NET 10, and one portable
netstandard Stdlib plus direct C# workload executes on both hosts over raw CLR
generic vectors and Kotlin specialized-array wrappers.

The complete Kotlin-owned Grouping foundation is published from exact Common,
generated, and Native/Wasm-derived sources. One non-generic erased CLR
interface preserves the logical `Grouping<T, out K>` contract and owns only
source iteration plus key selection; it neither maps to LINQ/BCL grouping nor
duplicates state. Iterable, Sequence, object-array, and CharSequence factories
are admitted together under their source-aligned facades, and GroupingKt owns
the complete aggregate/fold/reduce/count family. Portable Framework CLR 4 and
.NET 10 execution, all four PSI/LightTree profile lanes, deterministic product
metadata, nullable/seeded map semantics, and direct Roslyn implementation and
calls are green. Modern `System.Object`, boxing, interface-dispatch, or generic
optimizations are not treated as Framework proof.

The complete Kotlin-owned Sequence foundation is published from exact Common/
generated sources. One erased non-generic CLR interface preserves the
canonical Kotlin object identity and covariant KLIB contract; it neither maps
to `IEnumerable<T>` nor uses LINQ. Exact Common coroutine builders,
`SequenceScope`, `SlidingWindow`, running/window/chunk operations, and
`flatMapIndexed` now compose with the completed coroutine foundation without a
target-authored iterator. Generator-inventoried exclusions retain only Random-
backed `shuffled` and unsigned selector sums behind their independent
substrates; `groupingBy` is admitted by the Grouping foundation. The
physical facade derives every erased collision
name from the logical receiver/selector domain. Portable Framework CLR 4 and
.NET 10 execution, all four PSI/LightTree profile lanes, deterministic product
metadata, and direct Roslyn implementation/calls are green. A substituted
generic value result may be recovered from its physical upper-bound reference
view only at a frontend-proven implicit-cast boundary; explicit and safe cast
semantics remain unchanged.

The complete eager generated `Iterable.windowed`/`chunked` classifier family
is now published from its four exact Common templates. Their first compile
identified the exact upstream `List(size, init)` and delegated
`MutableList(size, init)` declarations as the one missing source prerequisite;
both are now generated from Common rather than replaced by a target factory.
The RandomAccess fast path retains Common snapshots and moving sublist views,
while hostile Iterables retain the shared `windowedIterator`/RingBuffer path.
Callback/view reuse, traversal and failure timing, partial/gapped windows,
validation messages, physical `CollectionsKt` methods, installed calls, and
one portable netstandard product are green on Framework CLR 4 and .NET 10.
This does not select CharSequence/array windowing, Random, unsigned, reified, or
BCL enumeration/export families.

The completed eager Iterable/Sequence-consumer family publishes exactly seven
generated declarations: four Sequence-result `flatMap*` methods,
`minus(Sequence)`, and Iterable plus Collection `plus(Sequence)`. Their Common
bodies use the already admitted `MutableCollection.addAll(Sequence)`,
Sequence-to-list, filtering, and snapshot foundations. Physical naming keeps
all old Iterable-result flatMap names and pins only the new siblings to
`flatMapSequence`, `flatMapIndexedSequence`,
`flatMapIndexedSequenceTo`, and `flatMapSequenceTo`. Installed KLIB consumers
inline those bodies and call the public plus/minus fallbacks; direct Roslyn
implements the erased Kotlin Sequence contract and calls both plus receiver
forms and minus. This is no general `@JvmName`/`DotNetName` policy and no
authority for a separate physical `Sequence<T>` migration.

The complete generated equality aggregate family is now published for every
currently supported classifier. Its 20 new declarations cover `allEqual` and
`allEqualBy` over Iterable, generic object arrays, and all eight signed
primitive-array wrappers; the Sequence pair was already present in the
completed Sequence foundation. Exact Common selector counts, first-mismatch
short circuit, nullable selector-key state, exception propagation, and boxed
and primitive Float/Double equality remain authoritative. Product metadata,
installed Kotlin fallback/inlining shape, direct Roslyn signed-array calls,
and both Framework CLR 4 and .NET 10 execution are pinned.

The corresponding complete `allDistinct`/`allDistinctBy` supported-classifier
family is now published as a second 20-declaration closure. Its only new source
dependency is Common's exact allocation-free byte-domain bit set. That shared
helper now consumes normalized Int indices rather than an unnecessary public
UByte carrier, preserving both signed and upstream unsigned algorithms while
allowing the .NET product to remain free of a `Kotlin.UByte` TypeDef. Empty and
singleton behavior, first-duplicate short circuit, callback failure timing,
nullable keys, primitive and boxed floating equality, the ten fallback/ten
inline installed shapes, direct Roslyn signed-array calls, and both runtime
profiles are pinned.

The complete natural generated `min`/`max` family is now published as one
52-declaration closure. Iterable and object arrays each contribute generic,
Float, and Double throwing/nullable min/max variants; Byte, Short, Int, Long,
Float, Double, and Char arrays contribute all four forms. Upstream supplies no
natural Boolean-array template, so none is fabricated. Common empty behavior,
first-tie identity, traversal, NaN propagation, and signed-zero total ordering
remain exact. Because Iterable and object-array erasure makes their logical
return-only siblings collide, the physical facade reuses Sequence's bounded
logical-element-derived `...OrThrow[OfFloat|OfDouble]` and
`...OrNull[OfFloat|OfDouble]` names. KLIB keeps every logical Kotlin name;
there is no general public naming annotation or partial generic-owner change.
Raw metadata, all 52 installed calls, direct Roslyn signed-array calls, and
both runtime profiles are pinned.

The complete selector-generated `minBy`/`maxBy` family is now published as a
40-declaration closure. Iterable, generic object arrays, and all eight signed
primitive-array wrappers each contribute throwing/nullable minimum and maximum
forms; Boolean is deliberately present because the selector result provides
the Comparable domain. Common zero-selector behavior for empty/singleton
inputs, first-tie identity, exact traversal/callback failure stopping, and
generic Float/Double total ordering remain exact. All declarations retain
ordinary public inline fallback MethodDefs: installed Kotlin consumers inline
all 40, while direct C# implements the existing erased `Kotlin.Function1` and
calls the signed IntArray fallbacks. The tranche also closes the general
inline-control-flow defect it exposed: same-region break/continue preserves
the loop-entry evaluation-stack prefix, so a synthetic inline-return loop used
as a later arithmetic/call operand cannot discard already evaluated operands.
No collection-specific rewrite, Runtime surface, naming router, or ABI schema
was added.

No implementation slice is half-landed. The exact Common `Comparator<T>` fun
interface, complete Common comparison combinators, six comparator scalar
selection functions, eight Iterable comparator selection functions, and five
Iterable sortedness traversals are published from their authoritative sources.
They reuse the existing erased generic-interface/SAM/Comparable boundaries and
add no Runtime capability or library schema. Stable mutation and signed-array
snapshots now compose with that foundation through the completed sorting
closure above.

The complete Common signed-selector
`sumOf` family is published under its generated logical declarations and the
pinned `sumOfInt`, `sumOfLong`, and `sumOfDouble` physical spellings. Its exact
type-inference markers are present as Kotlin declaration TypeDefs while their
BINARY applications remain KLIB-only. The Common `@InlineOnly` physical ABI
remains selected and
its first 14-declaration generated collection batch remains published with
assembly-visible physical bodies and mandatory external KLIB inlining. The
generated Common signed numeric averages remain published with
all six bounded physical names and exact KLIB bodies. The generated Common
first-match predicate pair remains published with
both physical fallbacks and inlinable KLIB bodies. The preceding iteration-
action family now includes generated `forEach`/`forEachIndexed` and
`onEach`/`onEachIndexed`; the latter pair preserves its open method-owned `C`
through one erased `Iterable` constraint and same-object result. The void pair
retains the authoritative `HidesMembers` compiler directive. The
receiver-seeded reduction family remains published with all eight fallbacks;
an inlined empty nullable branch uses the existing nullable-bottom carrier
path. The accumulator-fold family likewise remains published, and a discarded
substituted generic fold result performs its existing checked recovery before
being discarded. Kotlin-owned generic classes and interfaces use physical ABI
20's one erased owner; the superseded bounded typed-dispatch experiment
remains only as Git history and design evidence. Ordinary
non-reified inline bodies now
bind exact signatures throughout the complete frontend-selected dependency
graph. Resolution remains non-linking, and an incomplete graph fails at the
post-inline/pre-target-lowering boundary instead of crashing an arbitrary
lowering. The non-reified Common collection-to-array closure uses the exact
shared loops. Its narrow CLR actual reproduces a supplied vector's runtime
element type, retains sufficiently large destination identity without JVM's
Java-specific tail terminator. Public reified `toTypedArray` now composes that
same loop with shared call-site substitution. The backend's
explicit erased-object cast to an open type parameter uses `unbox.any`; safe
generic casts remain unsupported. The Kotlin-owned builder, exact generated
joins, Common abstract bases, and migrated array-backed list are published.
The public Common contracts DSL/effects, ordinary `InvocationKind`, scope
functions through `takeUnless`, and both `buildString` declarations are now in
the same self-describing stdlib product. Runtime surface level 16 owns the
erased compiler mutable cell and the complete admitted erased Kotlin
collection-interface surface, including Set/Map and both nested entry
interfaces.
The Common Map/Set source and generated families are published through their
own source-aligned facades. The Native/Wasm-derived `HashMap`/`HashSet`
implementation keeps one erased state, while Roslyn sees only truthful
non-generic low-level Kotlin types. External BCL generic collection identity
and future typed exports remain separate interop products.
The exact Common-contract export subset is additive and complete for
`NotNull`, `NotNullWhen`, `NotNullIfNotNull`, `DoesNotReturnIf`, and
`DoesNotReturn`. Its neutral carrier contains neither FIR/IR nodes nor the
authoritative contract graph; ordinary Kotlin declarations and profiles
without the exact standard TypeDefs remain physically unchanged.
Valued annotation classes are admitted generally; ordinary enums, the
non-reified `EnumEntries` core, and the reified Common enum helpers are now
published. The classified `CharSequence` carrier, Common collection
predicates, and complete ordinary/reified inline boundary remain intact;
suspend inline now composes with the continuation/state-machine foundation.
The nominal `KClass` floor and
logical `KType`/`typeOf` graph are selected and published; they do not imply
member reflection.

Foreign CLR method generics now enter the Kotlin model through the same
frontend-first boundary as JVM foreign generics. One method-owned type-parameter
graph supplies inference, bounds, overloads, calls, overrides, callable types,
and reflection, while the selected MethodDef supplies exact `!!n`/MethodSpec
binding. The admitted grammar is intentionally closed; unsupported special or
constructed constraints and explicitly nullable unconstrained generic leaves
evict the complete interface rather than creating a partially truthful API.

The coroutine continuation ABI now has an objective portable-library proof in
addition to semantic execution and final-IR validation. Public top-level and
virtual suspend MethodDefs keep ordinary parameters followed by the one erased
`Continuation` and return `Object`; private non-tail state machines extend the
one Stdlib base without publishing their layout. Both runtime profiles consume
the same portable producer and execute delayed top-level and member resumption.
`Task`/`ValueTask` remain export adapters rather than an alternate lowering.

Kotlin annotation classes use the shared Common annotation-member generator on
one concrete sealed CLR `System.Attribute` subtype. Ordinary Kotlin
construction, defaults, nested values, arrays, equality/hash/string behavior,
NaNs, signed zero, and separate KLIB consumption therefore share one runtime
identity. KLIB remains authoritative for declaration identity, complete
values, targets, retention, and applications. Runtime-retained applications
receive an additional CLR row only when the complete parent, constructor, and
fixed-argument blob are exact; unsupported `KClass`, Kotlin enum, nested
annotation, primitive-array-wrapper, open, generic, or non-constant shapes
remain KLIB-only. Source/binary applications remain absent from CLR reflection.
The gate directly adopts seven compatible upstream annotation-instance tests
in all four .NET runners, adds exact IL blobs and nested metadata parents, and
proves portable Kotlin defaults plus bidirectional typed C# application and
reflection on Framework CLR and CoreCLR.

Class- and callable-level runtime annotation discovery are now selected as
JVM-shaped platform extensions above Common `KClass` and `KCallable`.
Kotlin-produced values are reconstructed from private executable factories
derived from the KLIB-authoritative IR, so exact and KLIB-only values share the
existing annotation objects and projected CLR rows never become a duplicate
authority. Classifiers retain the producer-assembly marker and foreign-only
class path. Function, constructor, and property references carry their
declaration annotations on the existing executable object; imported CLR
methods and properties use the retained declaring type plus exact metadata
token and read only that direct row. Property applications are never merged
with getter/setter applications. Runtime surface level 18 owns this callable
transport; the physical declaration-index schema remains unchanged.
Adversarial coverage exercises defaults, nested values, arrays, enums,
`KClass`, repetition, retention, local/generic/interface classes, empty and
bound/unbound callable references, invocation/mutation identity,
property/accessor separation, read-only list behavior, separate KLIB
consumption, exact foreign CLR method/property attributes, both runtime
profiles, and `-no-stdlib` compilation. Member enumeration/invocation remained
separate reflection decisions; declaration-owned type-use annotations were
selected later under their own ADR.

`KCallable.returnType` now follows Native's declaration-target boundary rather
than the generated invocation adapter. Functions and constructors use the rich
reference's reflection target; properties use the original getter return type;
local delegated properties retain their declared value type. All paths reuse
the `typeOf` graph producer, including nested arguments, projections, stars,
nullability, method-owned parameters, and recursive bounds. Kotlin libraries
derive that target from embedded KLIB, while supported foreign declarations use
the importer-enhanced IR type; the runtime never reopens CLR reflection or
nullable attributes to reconstruct a signature.

The typed `KCallable` slot exposed an assembly cycle: callable interfaces live
in Runtime while `KType` previously lived physically in Stdlib. Runtime surface
level 19 therefore owns only the minimal physical `KType` interface beside
`KClass` and `KCallable`; Common behavior, `KTypeImpl`, projections, parameter
objects, equality, hashing, and rendering remain in Stdlib. Separate Kotlin
and C# consumers prove one type identity without an object bridge, wrapper, or
Runtime-to-Stdlib dependency. Target-owned adversarial coverage and two
unchanged upstream override tests execute across both FIR parsers and CLR
profiles; exact IL pins the additional graph and getter shape.

Runtime/Stdlib surface 35 later extends that same physical interface with
JVM-shaped `KAnnotatedElement`. Declaration-derived graph nodes receive their
exact runtime-retained KLIB/IR applications through the existing annotation
value pipeline. `typeOf` nodes remain empty as on JVM, and compiler-internal
foreign type-enhancement markers are explicitly non-reflective.

`KCallable.typeParameters` now deliberately extends that Native-shaped floor
with JVM's declaration-owned rule. Function and generic-extension-property
references expose only their own parameters in declaration order; constructors
expose the constructed class's own parameters. Enclosing parameters remain
reachable through return types and recursive bounds without leaking into the
own list. Return types, exposed parameters, and bounds are allocated in one
graph, so a classifier is the exact same object across every public view.
Bound and unbound references retain the unbound declaration owner. Runtime
surface level 20 transports that graph as one erased compiler/runtime value;
physical CLR generic parameters and runtime reflection remain non-authoritative.

`KCallable.parameters` and the JVM-shaped `KParameter` surface now extend the
same declaration graph. Unbound references expose instance, future context,
extension, and value positions in JVM order; bound receivers are omitted and
the remainder is reindexed. Types share the callable's exact type-parameter
objects, inherited Kotlin defaults remain optional, varargs retain their array
type, and equality/hashing use the actual callable object plus exposed index.
Kotlin parameter annotations come from their KLIB-derived declaration target;
foreign names, `ParamArray`, and annotations come only from exact CLR Param
rows, while CLR optional flags do not invent Kotlin default-call semantics.
Runtime surface level 21 passes one erased Stdlib factory into the Runtime-owned
callable and caches the resulting read-only list, preserving the one-way
Runtime/Stdlib dependency. Direct member-extension references remain rejected
by the Common frontend and wait for member enumeration rather than a .NET-only
syntax exception.

Positional `KCallable.call` now follows JVM's public invocation contract above
that Common reflection floor. Runtime surface level 22 checks the exact exposed
parameter count and invokes the callable's already generated erased `FunctionN`
capability; it never rediscovers a CLR member by reflection, name, token, or
signature. Defaults remain required positions, one vararg array is one
argument, a property call invokes its getter, bound receivers stay omitted,
and the original target exception propagates unchanged. The logical return
type remains KLIB-authoritative while the physical runtime slot returns
`object`. Functions, constructors, properties, virtual dispatch, generic and
extension references, wrong arity/type, separate KLIB consumption, imported
CLR declaration references, and direct C# invocation are covered independently
of the named/default invocation layer described below.

Named `KCallable.callBy` originally completed that invocation pair at runtime
surface level 23; surface 27 extended the fixed `KFunction0` to `KFunction22`
range, and surface 28 adds the big-arity `FunctionN` path plus multiword
omission masks. Exact parameter-object
presence distinguishes
explicit null from absence; omitted optional values select Kotlin defaults;
omitted varargs receive fresh arrays of the exact substituted physical type;
missing required parameters use JVM's failure contract; and unknown map keys
remain inert. Runtime owns only exposed-position interpretation. Each generated
reference makes one ordinary IR call with every optional absent; shared Common
and class/interface default lowerings select the authoritative dispatcher and
placeholder layout, after which one late .NET pass translates the runtime mask
words and selects supplied values. Generated size is therefore linear rather
than one helper per omission combination; 22 fixed and 33 big-arity dependent
defaults are covered, including supplied values on both sides of bit 31/32.
The separate-library proof also
normalized ordinary Kotlin class `$default` dispatchers to one static compiler
ABI with the receiver explicit. Kotlin-owned class parameters stay physically
erased, while genuine method parameters retain their CLR generic slots. Normal
source calls and reflection now share that helper, including inherited
defaults, virtual overrides, erased
generic owners, constructors, and both runtime profiles. Foreign CLR optional
metadata still does not invent Kotlin defaults, while foreign `ParamArray`
omission creates its truthful invariant vector carrier. No System.Reflection,
name lookup, target-exception wrapping, or second reflection-default ABI is
introduced.

The five JVM-shaped `KFunction` declaration properties now use the exact
KLIB/importer-IR target for inline, external, operator, infix, and suspend
status. They are declared once on `KFunction` and inherited by every admitted
`KFunction` arity; the physical view remains one non-generic
`Kotlin.KFunction` interface. The existing private function-reference flag
carrier supplies five inherited virtual-final getters; its base does not
implement `KFunction`, so internal adapters gain no reflection identity.
Constructors and ordinary imported CLR interface methods report false, while
resolved inherited operator status survives KLIB boundaries. Generated invoke
adapters and runtime CLR reflection never become flag authority. Publishing
`isSuspend` and `isExternal` does not itself define suspend execution or
external linkage. Library ABI 23 rejects old references without declaration
bits, and runtime surface 24 gates the five new physical getters before
execution.

Direct callable visibility and modality now follow the same JVM-shaped
declaration-fact rule. Public, protected, internal, private, final, open, and
abstract functions, properties, and constructors retain their exact logical
FIR/IR/KLIB facts across producer- and consumer-created references; local
function and delegated-property tokens return null visibility and final
modality. Admitted foreign CLR interface functions and properties obtain
public/abstract from importer IR rather than from backend-selected MethodDefs.
One shared reference payload serves functions and properties, and property
factory arguments are now bound by parameter name so later payload extensions
cannot silently corrupt the annotation-factory slot. Runtime surface 26 owns
the typed getters and ordinary `KVisibility` enum; library ABI 25 rejects old
materialized references. Separate Kotlin/C#/Roslyn and physical metadata tests
also pin Runtime's lack of a Stdlib reference and Stdlib's implementation of
the one Runtime-owned `EnumEntries` interface.

Direct property reflection now publishes JVM-shaped `isConst`, `isLateinit`,
getter, and mutable setter capabilities for the fixed property arities. Each
property wrapper creates one cached accessor object whose `KFunction`
signature and declaration facts come from the exact accessor IR and whose
execution delegates to the owning property's existing `Get`/`Set` path.
Getter/setter invocation therefore cannot diverge from property invocation in
bound-receiver handling, virtual dispatch, mutation, exception identity, or
separate libraries. A `const` reference reads its retained literal in the
private reference body and does not add a public CLR accessor MethodDef.
Runtime surface and library ABI 29 pin the new interfaces and factory payload.
The later Common-owned `lateinit` foundation makes the positive declaration
fact observable without changing that payload; broad member discovery and
`getDelegate` remain independent. Declaration-owned type-use discovery was
selected later.

Library ABI version 23 also retains the version-22 static ordinary-class
default-dispatch shape; runtime surface level 24 includes the version-23
`KCallable.CallBy` slot and helpers. A consumer therefore rejects both an old
library callable ABI and an old runtime instead of discovering a missing
method at execution.

The general Common Comparable mapping is independently published and the enum
product consumes the same KLIB identity, canonical classifier, typed C# view,
and semantic operation boundary rather than an enum-private substitute.

Reified inline functions now use shared IR call-site substitution as their only
semantic mechanism. The target-stage completion consumes selected KLIB bodies
after pre-serialization has preserved bodyless compiler intrinsics. Substituted
type tests/casts, nullable and bottom types, arrays, `T::class`, nested calls,
erased Kotlin generic classes/interfaces, and Common enum helpers all reuse
their ordinary runtime paths. Truthfully representable declarations receive
assembly-visible throwing remainders; signatures without one truthful open CLR
shape are omitted. Neither form enters the physical Kotlin declaration index
or explicit C# export, and cross-library calls disappear in all three KLIB
inliner modes. The completed `KType`/`typeOf` graph composes this same
substitution path; declaration-owned type annotations were selected later,
while future classifier families and coroutine-aware reflection/export remain
separate programmes.
Physically exact non-generic reference casts are
complete for Kotlin classes/interfaces, imported CLR interfaces, strings,
`Any`, primitive-array wrappers, and exact CLR vectors without admitting closed
generic instances.
Boxed-scalar casts are now complete for all eight selected Common primitives:
exact boxed identity, nullable unboxing for checked nullable casts, and
`isinst` plus nullable unboxing for safe casts, with no numeric-conversion or
value-class widening. Ordinary runtime type tests now have an explicit
exact-carrier admission boundary and an adversarial matrix across both FIR
frontends and runtime profiles. Exact scalars, classes/interfaces, strings,
supported primitive-array wrappers, imported CLR interfaces, nullable forms,
smart-cast use, and single evaluation are covered; classified exceptions,
`CharSequence`, and erased generic interfaces retain their dedicated paths.
Closed `GenericInstance` checks remain forbidden as Kotlin identity; ordinary
Kotlin-owned generic-class tests instead use the one producer-recorded erased
TypeDef and return the same object.

All eight signed Common primitive-array wrappers are now complete through one
runtime registry and the symmetric .NET stdlib declaration surface. The new
three families retain exact `SByte[]`, `Int16[]`, and `Single[]` private
storage, remain distinct from `Array<Byte>`, `Array<Short>`, and
`Array<Float>`, and cross portable Kotlin-library and explicit C# export
boundaries without copying. Unsigned arrays remain deliberately outside this
specialized-wrapper closure; `Array<*>` now sees the wrappers only as non-array
objects and never exposes their private vectors.

The separate generic-array closure now admits all eight concrete nullable
primitive element types as exact `Nullable<V>[]` vectors and admits
`Array<*>` through their classified `System.Array` base without changing the
specialized-wrapper identities above. It does not admit open nullable
elements, input projections, or value-vector covariance. Those parked shapes
still require successful typed-use carriers rather than inference from the
star read-only view.

The erased generic-class closure covers final/open/abstract/sealed,
nested/inner, data, inherited, nullable/scalar, bounded/multiple owner
parameters, generic members, default arguments, projected and erased-overload
shapes. It additionally covers widened direct and nested generic-bearing
inputs, multi-level portable overrides, same-object mutation after an
unchecked cast, delayed incompatible reads, one physical owner, and absence of
an implicit CLR `C<T>` surface.

Single-field Kotlin value classes now follow the same box-plus-contextual-
carrier architecture as the mature targets rather than becoming CLR value
types. Common's declaration and usage lowerings own constructor/member
semantics. One late .NET representation pass, ordered after loop and string
body rewrites, inserts every explicit box/unbox transition. Exact non-null
uses calculate with the recursively substituted underlying carrier; erased,
interface, nullable-collision, runtime-test, callable, generic-method, and
array/vararg generic positions use the one nominal non-generic box owner.
Generic value-class implementation helpers remain genuine CLR generic methods,
without creating a generic class owner. `T : Int` uses its sole primitive
carrier; `T : Int?` preserves a generic helper token while the erased owner
stores the boxed-or-null universal carrier.

Logical-signature mangling prevents underlying-carrier overload collisions,
generated floating equality retains Kotlin's total-order rule, and producer
ABI 26 records primary-constructor, box, and unbox MethodDef identities for
separate consumers. The selected upstream Common/JVM root matrix currently
executes 45 adversarial scenarios on both FIR parsers and both CLR profiles:
180 executions with zero failures, errors, or skips. Multi-field value classes,
unsigned stdlib/runtime publication, typed .NET export, and private
specialization remain separate consumers rather than being inferred from this
foundation. See [`docs/decisions/value-classes.md`](docs/decisions/value-classes.md).

## Open architectural blockers

- A true CLR-generic Kotlin-owned class owner with a complete erased Kotlin
  capability ABI is now the intended destination wherever the complete Kotlin
  contract can be represented truthfully. Production emission is blocked on
  the hardest-model-first semantic spike and an atomic migration plan: open
  mutable invariant state, value/reference/nullable substitutions, stars and
  both projections, candidate-accepting erased methods, Kotlin/C# inheritance,
  casts, reflection, nested carriers, and separate assemblies must work with
  one owner and one state before any easy owner lands. The current erased owner
  remains binding meanwhile. The hostile erased oracle, deterministic
  carrier/slot matrix, direct C# surface, migration/rollback plan, and the
  first CLR construction/dispatch probes now exist. Runtime construction is
  explicitly insufficient for metadata-fixed shapes such as
  `D<T> : C<T?>`; their fixed fallback must preserve override, `super`, state,
  casts, reflection, and honest C# ancestry or the declaration remains erased.
  Compiler-produced Kotlin member families, physical bindings, reflection, and
  separate-assembly C# inheritance now have bounded production-inert evidence.
  The record-driven JIT/ReadyToRun/full-trimming/NativeAOT measurement baseline
  now exists, but representative erased-versus-candidate application
  comparison remains open. This design gate does not block current stdlib,
  reflection, CLI-IR, imported CLR generics, generic methods, explicit exports,
  or removable specialization. See
  [`docs/programmes/generic-class-owner-reopening.md`](docs/programmes/generic-class-owner-reopening.md).
- Typed .NET export for Kotlin-owned generics remains a separate product
  programme. It may publish a facade, read-only interface, adapter, or
  same-object CLR subtype for export-created instances, but it must not
  reintroduce a second Kotlin runtime identity, competing state, or virtual
  ABI. Arbitrary existing instances require an adapter. The concrete export
  surface and identity policy remain open; the erased Kotlin runtime ABI does
  not.
- Private generic specialization and devirtualization may be prototyped as
  non-production evidence for the hostile owner model, but production policy
  still requires representative boxing/allocation/JIT/AOT measurements and
  the concurrency/memory model. The erased call remains the semantic fallback
  at open assembly boundaries.
- KLIB-in-DLL and physical ABI codecs still need neutral serialization owners
  as those additional compiler/tooling consumers appear.
- Broad CLR property/member-state enhancement, `ref`/`out`, events, and
  collection-shaped params each require separate Kotlin-stability decisions.
- Foreign CLR generic methods and TypeDefs beyond their exact admitted grammars
  remain fail-closed. Unsigned carriers, special constraints, nullable constraint
  roots, constructed shapes outside the admitted interface grammar, and explicit
  nullable generic leaves on declared members require complete semantic, binding,
  override, and reflection mappings rather than backend exceptions or private
  reflection decoders.
- Gate A and ABI-freeze work remain open; current prototype identities may be
  corrected rather than compatibility-shimmed.

## Next bounded work

1. Continue the hardest-model-first generic-owner architecture spike while
   keeping production emission erased. The erased hostile oracle, historical
   failure audit, deterministic carrier/slot admission matrix, one-owner
   dispatch probe, direct C# surface, and atomic migration/rollback boundary
   are now recorded. The first fail-closed IR planner is also active in the
   production pipeline but cannot admit an owner or affect emission; direct
   writes are evidence only, never a substitute for the complete access graph.
   The bounded compiler prototype now generates detached typed/semantic/
   dispatcher IR families and validates its state, signature, default,
   `super`, logical-binding, cast/reflection, and CLR metadata roles. The
   complete local producer graph, typed-write value provenance, snapshot-
   driven separate C# physicalizer, and versioned cross-assembly family
   artifact now exist. Version 5 retains exact external MethodDef owners,
   override-root sets, complete typed/semantic/capability slot-domain and
   structural signature records, exact interface slot identity, nested `!T[]`/
   `!!T[]` carriers, typed/semantic direct-`super` targets, and the separate
   static masked-default helper, target-profile/runtime-classification
   identity, statically exact constructor/delegation records, and exact
   one-state typed/semantic access paths, exact producer-open-TypeDef to KLIB
   classifier normalization, KLIB-only logical type-argument authority, hidden
   capability reflection, and one logical callable per complete MethodDef
   family. Version 6 additionally records ordered producer GenericParam
   constraints. Version 7 records the complete producer candidate catalog;
   optional physical families must join it exactly, and metadata-fixed
   `D<T> : C<T?>` remains a serialized erased-only exclusion rather than an
   ambiguous omission. Version 8 records fixed zeroed SZ-array state
   initializers and their exact base-delegating constructor roots; initializer
   writes compose with ordinary typed state access instead of requiring a
   fabricated setter. Version 9 separates logical member-family state paths
   from exact producer-private MethodDefs without inventing KLIB/reflection
   identity. Version 10 adds exact physical owner visibility/dispatch and
   member-slot visibility, including protected semantic hooks and private/final
   capability implementations. Version 11 adds positionally exact constructor-
   parameter state initialization. The complete logical-keyed OctoTree family
   now atomically records its four TypeDefs, recursive types, constructors,
   MethodDefs, state, initialization, and reflection graph; producer type and
   MethodDef references to missing physical owners reject the whole artifact.
   Version 12 records the non-CLI-sealed representation of Kotlin sealed
   generic bases and requires every such base constructor to be
   `FamilyAndAssembly`. A decoded record now drives an abstract `Node<T>` plus
   sealed `Leaf<T>` C# producer with true `T` state; a direct external C#
   consumer constructs and reflects `Leaf<int>`, while an external subclass is
   rejected on Framework 4.8 and .NET 10.
   A compiler-derived external Kotlin subclass physicalizer now
   records its exact current-compilation owner, delegated producer base/
   constructor, typed/semantic overrides, fake-override declaration roots,
   modality/visibility, constraints, and role-specific direct-`super` targets
   without consumer name, signature, or arity-based constraint inference. A separate
   finite construction-site record now selects statically rooted exact open-
   nullable routes plus one mandatory semantic fallback without unbounded
   reflection. Its fingerprinted measurement corpus is green under Framework
   CLR 4, JIT, ReadyToRun, full trimming, and real NativeAOT link/run. The same
   corpus now physicalizes compiler-proven `!T` state and attributes exact and
   capability routes for Int, non-trivial struct, and nullable values. The
   compiler-derived static route census now enters closed application bundle
   schema 2 through original call-site indices and KLIB member keys, with exact
   frontend/profile byte equality. Explicit test-only exact-`IrCall` tracing
   now proves the same-compilation join and exact hostile per-site vector on
   Framework 4.8/net10 and both frontends while leaving normal products byte-
   identical. The per-call console transport has now been replaced by an
   exact-sized thread-safe primitive counter table and one final flush. The
   first two exact repository application sources now supply complementary
   ArrayCopy and recursive OctoTree censuses. ArrayCopy's 5,664 local dynamic
   events are exact-entry candidates even though its unchecked object-vector
   initialization correctly keeps semantic array state; OctoTree mixes 5,941
   exact and 3,096 semantic-capability events. A published OctoTree producer
   now also retains exact path-unbound `Node<T>[]` and `T` state carriers by
   logical producer key, while rejecting `Array<T?>` as an exact CLR `T[]`.
   Open-nullable MethodDef positions now use `object`, and open-nullable or
   projected array positions use `System.Array`; the hostile projected echo
   artifact no longer claims `!T[]`. State and callable signatures now share
   one path-unbound logical-classifier grammar, and the separate OctoTree
   `nodes` getter binds typed `Node<T>[]` only after the Node TypeDef path is
   selected. The complete schema-12 OctoTree physical family now binds that
   recursive graph plus exact `Leaf.value: !T` constructor initialization.
   Its Node/Leaf sealed construction and true-`T` state plus Branch's exact
   base/this constructors and private `Node<T>[8]` state are now record-driven
   and executable. Their exact abstract/override typed MethodDefs and identity
   state accessors are also executable through direct C# base dispatch. The
   strict set capability interfaces and private-final object-to-`!T`
   dispatchers now preserve that most-derived override path and fail before
   incompatible mutation. Leaf's state capability now reads/writes the same
   true `T` field through `object`, while Branch's state capability returns the
   same `Node<T>[]` through `System.Array`. The same product now includes the
   outer open Tree, its semantic object root, compiler-census capability calls,
   and typed/non-generic direct C# surface. Whole-family metadata/reflection,
   ordinary body closure, and the closed paired schema-3 measurement are now
   complete. That checkpoint includes Framework 4.8 plus JIT, ReadyToRun,
   trimmed, and real NativeAOT compile/startup/throughput/allocation/working-set
   evidence with route attribution. It does not select the ABI because the
   candidate remains a generated C# physicalization rather than a complete
   Kotlin product.
   The private-root proof is now closed: null is physically neutral only for a
   proven local generic class reference, every non-null constructor/write still
   requires exact provenance, and the selected OctoTree family stores
   `Node<T>` with private typed identity access while preserving the external
   semantic capability. The five-lane paired rerun is faster than erased on
   every modern lane and allocates less everywhere; Framework remains 1.62x
   slower. Perform only a large-impact audit of the remaining capability and
   Framework costs. That audit found a material ordinary-body defect: the C#
   candidate used `EqualityComparer<T>.Default` instead of Runtime
   `AreEqual(object, object)`. The corrected candidate is now semantically
   aligned and exposes 72.3%-76.6% excess aggregate allocation. The bounded
   production-used equality helper is now closed: exact same-open-`T` calls
   remove one box without changing Kotlin reference, null, floating, nullable,
   or hostile struct behavior on any deployment lane. Candidate excess falls
   to about 34% on managed lanes and becomes an 11.25% NativeAOT saving, but
   capability dispatch and Framework timing remain independently expensive.
   Do not pursue small equality variants. The one-state concurrency/memory-
   model migration condition is now closed at schema 14: typed entries,
   semantic capabilities, constructor publication, mutation, and failed-write
   rollback observe one authoritative field graph on Framework 4.8 and
   CoreCLR. The owner-dependent volatile state uses one reference-safe object
   field while its ordinary sibling remains true `T` storage; no shadow/copy
   state, wrapper identity, or representation-dependent synchronization is
   admitted. Schema 15 then binds ordinary typed accessors to real PropertyDef
   rows, schema 16 preserves per-use nullable-reference transforms, and schema
   17 closes broad property routing: compatible capability writes observe the
   typed C# property override, incompatible writes retain the semantic hook,
   raw reads retain semantic state, and a later typed read checks at its true
   use boundary. Schema 18 then closes the abstract form: both typed accessors
   and protected semantic hooks remain obligations, and inherited logical
   semantic reachability selects one object state before physical storage is
   chosen. Schema 19 closes overload-family naming: natural typed overloads
   remain idiomatic C#, generated semantic/capability/default members receive
   stable logical-family identities, and CLR-valid but C#-invalid collisions
   fail closed. Schema 20 closes the remaining direct-supertype condition:
   exact `BaseType` and `InterfaceImpl` constructions, generic interface
   TypeDefs, constructor delegation, capability ownership, and their physical
   nullable blobs are producer facts, while bare `T?` ancestry remains an
   explicit deterministic exclusion. The atomic checkpoint now records no-go
   for production cutover until one complete Kotlin-emitter and rollback
   rehearsal replaces the generated C# physicalizer; do not pursue a per-owner
   rollout or small performance variant in the meantime.
   Kotlin/Native VTA and Swift SIL remain optional proof engines for private/
   direct paths and never replace the open-world capability. Do not emit a
   production `C<T>` TypeDef or roll out an easy owner before the hostile
   prototype and real-app measurement checkpoint select the one atomic
   cutover. The first normal Kotlin-emitter checkpoint now emits real `C<T>`
   owners, true `!T` ordinary state, semantic/capability families, physical
   inner parameters, generic value-class carriers, and ABI-37 separate-library
   bindings behind the test-only rehearsal epoch. The source-built Stdlib has
   now reached the generic-interface boundary at `AbstractMap<K, V>.keys`:
   treating the erased `Set<K>` carrier as permanently canonical would merely
   preserve an obsolete constraint. Next emit and consume the general natural
   `I<T>` plus declaration-semantic capability family, including its versioned
   C# authoring record; do not add a `Map`, `Set`, or `Sequence` representation
   exception. Then continue the complete Runtime/Stdlib owner graph and its
   residual capability joins, covariant returns, and intrinsic state
   requirements before executing representative products and exact inverse
   rollback for the next go/no-go decision.
2. The dependency recomputation after eager windowing selected and completed
   both the exact seven-declaration Iterable/Sequence-consumer closure and the
   complete supported-classifier `allEqual`/`allEqualBy` and
   `allDistinct`/`allDistinctBy` closures. The next recomputation selected and
   completed the full 52-declaration natural `min`/`max` family and the
   40-declaration selector `minBy`/`maxBy` family. The following recomputation
   selected and completed the full 120-declaration selector-result
   `minOf`/`maxOf` family, then the remaining 72 array declarations completing
   the 80-method comparator min/max closure. The next classifier recomputation
   completed all 28 CharSequence min/max aggregates and their exact Common
   `lastIndex` prerequisite, then the separate complete 24-declaration Map
   adapter family. Do not select another erased-owner stdlib tranche before
   the complete Kotlin-emitter/inverse-rollback generic-owner rehearsal reaches
   its next go/no-go checkpoint. Random and entropy, unsigned value-class/range
   representation, other CharSequence/array variants, and still dependency-
   blocked reified variants remain separate. Do not
   infer that signed sorting authorizes unsigned overloads, binary search,
   shuffle, or a target-authored one-function approximation.
3. Continue the generated catalog only by complete classifier families, not by
   handwritten members. The concrete Common scalars, classified `Number`,
   built-in collection interfaces, and Kotlin-owned collection implementations
   are complete. Select later mapped/Stdlib families from Kotlin declaration
   scopes and foreign classifiers from exact importer identities and
   enhancement. Reuse the established callable/property objects and never
   expose a partial CLR MethodDef/Property scan. Constructors and declared-
   member convenience APIs remain separate selections.
4. Keep `Task`/`ValueTask` and C# `async` as a future explicit export product;
   they may adapt the Kotlin continuation boundary but never replace its
   internal ABI or create a second state-machine representation.
5. Keep coroutine scheduling, `kotlinx.coroutines`, debugger metadata, and
   coroutine-aware reflection outside this completed continuation/state-
   machine foundation until selected independently. Common Sequence builders
   are now a completed consumer of that one foundation.

The post-rebase callable-reference probe found that common IR's new
`addBoundValueAtOverride` helper cannot directly replace the .NET lowering:
the shared helper discovers Kotlin-named `boundValueAt`, while the established
CLR runtime ABI deliberately exposes `BoundValueAt` as `protected final`.
Retain the local implementation unless the shared helper is separately
generalized to accept the exact override identity and member flags, with IL,
runtime-identity, and separate-library evidence proving no ABI change.

## Navigation

- Current sequencing and release gates:
  [`docs/programmes/way-forward.md`](docs/programmes/way-forward.md)
- Documentation and evidence index:
  [`docs/README.md`](docs/README.md)
- Collections programme:
  [`docs/programmes/common-collections.md`](docs/programmes/common-collections.md)
- Architecture ownership audit:
  [`docs/programmes/compiler-architecture.md`](docs/programmes/compiler-architecture.md)
- Durable representation decisions: [`docs/decisions`](docs/decisions)

Update this file when branch state, the latest full gate, active work, blockers,
or the next bounded items change. Put rationale in ADRs, future ordering in the
way forward, chronological history in Git, and executable evidence in tests.
