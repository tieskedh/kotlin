# DotNet (CIL) backend — design rules and working notes

Prototype Kotlin → .NET CIL target. Code lives in `compiler/ir/backend.dotnet/` (backend) and
`compiler/cli/cli-dotnet/` (CLI, K2 phased pipeline only). IL-text golden tests are the primary
validation: test data in `compiler/testData/codegen/dotnet/ilText/`, runners generated from
`compiler/fir/fir2ir/testFixtures/.../codegen/AbstractDotNetIlTextTest.kt` (`./gradlew generateTests`).
CLI tests in `compiler/testData/cli/dotnet/`. Box tests compile with target `net` to a dll and
execute it on the real CoreCLR runtime via `dotnet exec` (see "Box tests" below).

## Design rules

- Before implementing DotNet backend behavior, inspect how the mature JVM/JS/WASM/Native targets
  solve the same problem, then make an explicit .NET-specific decision. Do not invent a separate
  approach unless the CLR platform model gives a concrete reason.
- When reporting an implemented DotNet backend feature, state which mature target it follows. If the
  implementation deviates, state the target it deviates from and the CLR-specific reason.
- If JVM uses an intrinsic registry for a behavior, DotNet wires that behavior through
  `DotNetIlIntrinsicMethods` too, unless there is a concrete platform reason not to. "Needed later"
  is not a valid reason to skip the registry shape; add the registry entry now and let unsupported
  cases fail explicitly.
- IL codegen fails on unsupported IR (`dotNetUnsupported()`) instead of emitting fallback IL such as
  empty strings or zero values. `DotNetIlEmitter` skips uncompilable functions to a fixpoint
  (callers of skipped functions are skipped too) and errors only when the entry point is affected.

## Established decisions

- IL codegen split: `DotNetIlEmitter` (module orchestration), `DotNetIlClassCodegen` (class shell,
  method dispatch), `DotNetIlMethodCodegen` (bodies/statements), `DotNetIlExpressionCodegen`
  (expressions), `DotNetIlMethodContext` (slots/labels/maxstack/stack verification),
  `DotNetIlCodegenSupport` (type mapping, signatures, escaping), `DotNetIlType` (value types vs
  return types).
- String concatenation follows the mature target shape: `FlattenStringConcatenationLowering`, then
  `DotNetStringConcatenationLowering`, then IL codegen handles `String.plus`/`toString` intrinsics.
  Avoid ad-hoc IrWhen/boolean handling inside string emission.
- Lowerings run through `NamedCompilerPhase`/`PhaseEngine`, measured as `PhaseType.IrLowering`.
- Main selection uses `DotNetMainFunctionDetector`. No wrapper is generated when the selected
  Kotlin `main` shape already maps to a valid CLR `.entrypoint` method (ECMA-335 allows
  parameterless or `string[]` entry points); add a wrapper only when a supported source shape needs one.
- Kotlin `Unit` is not an IL value type. CLR `void` is only a return encoding; Unit-returning
  functions are emitted as `void`, and `IMPLICIT_COERCION_TO_UNIT` discards values with `pop`.
- Local `val`/`var` follows the JVM/WASM model conceptually: the method context maps IR value
  symbols to slots. CLR keeps argument slots (`ldarg`) separate from `.locals init` slots
  (`ldloc`/`stloc`).
- `if`/`when` follows JVM/WASM `IrWhen` handling: evaluate conditions, `brfalse` to next branch,
  `br` to the end label after a matched branch.
- Equality follows JVM's intrinsic-registry shape: `Int`/`Boolean` use `ceq`, `String ==` uses
  `System.String::op_Equality`, `String ===` uses reference `ceq`. On user-class instances, `===`
  and `==` against the `null` literal are a reference `ceq` (Kotlin defines `x == null` as a pure
  reference check that never calls `equals`; JVM precedent: the `Equals` intrinsic's `isNullConst`
  special case); base/derived-typed operand pairs of the inheritance model widen to the
  ancestor type — the reference `ceq` is type-agnostic, the user-class analogue of the
  mapped-exception common-supertype arm; sibling-typed pairs sharing a supertype (a common
  base class or, since the interface model, a common implemented interface — the shape a
  positive-identity smartcast routinely produces) widen to the FIRST common supertype of the
  left operand's breadth-first supertype walk (deterministic:
  `DotNetIlClassInfo.allSupertypes` — direct base class, then direct interfaces in
  declaration order, then the same per level, NOT the whole base chain first). Two FINAL
  sibling classes are not expressible operands — the frontend rejects them with
  EQUALITY_NOT_APPLICABLE (empty intersection type) — so the user-reachable sibling shape is
  two sibling INTERFACE views of an object; the widening and the no-common-supertype
  rejection are pinned by `ilText/interfaceEqualityWidening.kt` and runtime-pinned (true and
  false cases) by `box/interfaceHierarchy.kt`. Pairs with no common supertype (e.g. two
  unrelated interface types, whose only common supertype would need an Any model) stay
  rejected loudly. General `==` between two
  class instances stays rejected until an Any.equals model exists.
- Nullability model (hybrid representation; probe series `boxprobe_s1`–`_s7`, `nullprobe_s8` —
  a deliberate user-decided design): a concrete nullable CLR VALUE type uses
  `System.Nullable<T>` in exact typed positions and converts to boxed-underlying-or-null when
  widened to `Any?`/object-shaped contexts; a nullable REFERENCE type is the same IL type as its
  non-null flavor (a stated no-op at the type-mapping layer — `String?`/`C?`/`I?`/mapped
  exceptions all keep their reference type, `ldnull` is the null literal, `== null` is the
  existing reference `ceq`). Precedents: Roslyn (C# `int?` IS `Nullable<int32>` in typed
  positions, CLR-collapsed at `object` boundaries) and Kotlin's own inline value classes
  (context-sensitive representation is established Kotlin policy); the JVM's blanket boxing of
  `Int?` is a platform limitation, not language policy. Generic `T?` is a SEPARATE future ABI
  problem and deliberately stays unmapped — it must not force concrete nullable primitives into
  `object`. Representation table (`DotNetIlValueType.NullableValue`; the five concrete
  primitives Int/Long/Double/Boolean/Char):
  - EXACT positions (params, returns, locals, instance/static fields, ctor params):
    `valuetype [mscorlib]System.Nullable`1<int32|int64|float64|bool|char>` — the
    `nameInSignature` doubles as the operand spelling of every instruction touching the type
    (probe-verified in every position, boxprobe_s1). `T -> T?` is
    `newobj instance void valuetype ...Nullable`1<t>::.ctor(!0)`; the null literal / empty value
    is `ldloca`+`initobj`+`ldloc` through a synthetic local (also for returns and arguments);
    null tests are `call instance bool ...::get_HasValue()`, extraction is
    `GetValueOrDefault()` — never `get_Value`, whose InvalidOperationException would surface as
    the wrong Kotlin exception, so every extraction branches on HasValue first.
  - WIDENED positions (`kotlin.Any`/`Any?` maps to CLR `object` as pure STORAGE): `T? -> Any?`
    is `box valuetype ...Nullable`1<t>`, which the CLR COLLAPSES to boxed-`T`-or-null
    (boxprobe_s3, all five instantiations nullprobe_s8); `T -> Any?` is a plain
    `box [mscorlib]System.<Boxed>`; reference types widen to `object` instruction-free
    (`isDotNetAssignableTo`'s third widening). Conversions are emitted by a widening-coercion
    layer in `DotNetIlExpressionCodegen.emitExpression` (JVM precedent: StackValue coercion —
    boxing is never an IR node) plus the IMPLICIT_CAST arms; narrowing exists only as the
    `T? -> T` smartcast IMPLICIT_CAST / narrowed-slot read, emitted as the CHECKED unwrap
    (HasValue branch + mapped-NPE throw + GetValueOrDefault — JVM parity: CHECKCAST+`intValue()`
    NPEs on null there).
  - MANDATORY SPILL RULE (boxprobe_s2): every instance-member access on a `Nullable<T>` receiver
    needs a HOME ADDRESS — a freshly computed stack value MUST be spilled `stloc`+`ldloca`; the
    unspilled call assembles cleanly but is a FATAL, uncatchable CLR error (0x80131506), a
    poison shape codegen may never emit. Spilling costs a local and zero maxstack.
  `!!`/IMPLICIT_NOTNULL (JVM precedent: the checkNotNull intrinsic, registered via
  `checkNotNullSymbol` in the intrinsic registry): references get `dup`/`brtrue` past a
  `newobj instance void [mscorlib]System.NullReferenceException::.ctor()` + `throw`
  (boxprobe_s4; message-less, like `Intrinsics.checkNotNull`), Nullable<T> gets the HasValue
  branch + same throw + extraction — catchable as `catch (e: NullPointerException)` through the
  existing registry mapping (`box/nullableNotNullThrows.kt`). Null-aware structural `==`
  (JVM precedent: the `Intrinsics.areEqual` specializations) is the Roslyn lifted-equality
  shape, NO boxing (boxprobe_s5, incl. the (none, some(0)) corner): `T? == T?` compares
  GetValueOrDefault values ANDed with HasValue flags; `T? == T`/`T == T?` AND the value `ceq`
  with the nullable side's HasValue; `T? == null` is a negated HasValue; `Double?` arrives via
  the ieee754equals symbols and the extracted-`float64` `ceq` IS the JVM's
  areEqual(Double, Double) semantics (NaN? != NaN?, -0.0? == 0.0? — runtime-pinned). A null-only
  operand (`Nothing?` maps to `object` — the frontend narrows known-null when-subjects to it)
  against a plain primitive is statically false with operands still evaluated. Safe calls and
  elvis are the frontend's block+when shape — value-position `IrBlock` dispatches through the
  same statement-scope hook as `try` expressions, and a `?:` whose branches are `Int` and `Int?`
  unifies through the branch-level coercions; string templates of nullable primitives render
  through a HasValue branch selecting `"null"` or the existing per-type rendering
  (boxprobe_s7), nullable strings keep the existing dup/brtrue coalesce. A non-Unit `try`
  DISCARDED in statement position keeps the statement form when its type maps to `object`:
  branch types that merely LUB to `Any` (an Int-typed try branch next to a Unit-typed catch
  branch ending in an assignment — a routine statement shape) mix value- and statement-shaped
  branch results, and a discarded `Any` value is never materialized, so `emitDiscardedTry`
  treats `object` like an unmapped type (the exact pre-hybrid behavior; runtime-pinned by
  `box/tryDiscardedValue.kt`) — mapped NON-`object` types imply every branch result is
  value-shaped and keep the expression form. GATE INTERACTIONS
  (pinned by `ilText/nullableOverrides.kt`): `Int?`/`Int` are now DISTINCT IL types, so the
  Kotlin-covariant `Producer.value(): Int?` overridden by `value(): Int` flips to the
  covariant-return whole-class rejection, and `f(Int)`/`f(Int?)` overloads become two legal IL
  methods, while `g(String)`/`g(String?)` still clash (reference nullability erases). The
  member-clash gate has a FACADE analogue (`ilText/facadeMethodClash.kt`): top-level functions
  and property accessors of one file render into one facade class, so top-level
  `g(String)`/`g(String?)` (same IL `string`), `h(Any)`/`h(Any?)` (same IL `object`) and
  `val x` vs `fun get_x()` (accessor mangling) each produce duplicate IL method declarations
  ilasm rejects; the facade gate evicts EVERY callable of a clashing identity (keeping one
  half would be an arbitrary pick between legal Kotlin overloads) at facade granularity — a
  plain function per-function, an accessor with its whole property, a backing-field-bearing
  property with the file's whole property group. STAYS
  REJECTED, loudly: generic `T?`, Any member calls and Any string conversion (`object` is
  storage-only, no Any model), general `==` between reference/`Any?` operands, `===` with a
  nullable-primitive operand (the operands would box; identity of separately boxed values is
  unrelated to value equality — `ceq` on two boxed equal int32s is False, boxprobe_s6 — and
  Kotlin deprecates boxed identity), `==` mixing a nullable
  primitive with `Any?` (cross-primitive `Int? == Long?` needs no backend gate: the FRONTEND
  rejects it with EQUALITY_NOT_APPLICABLE, like `==` between unrelated final classes — not
  expressible in compilable Kotlin, so not pinnable in an ilText test), `object -> T?` narrowing (`unbox.any` accepts null and boxed T,
  boxprobe_s3, but no supported IR shape produces the cast — rejected like every downcast),
  `is`/`as`/safe-cast (existing type-operator rejections stay authoritative), `const val`
  of nullable type (defensive; frontend-rejected anyway), and exhaustive `when` WITHOUT `else`
  over a nullable (or plain Boolean — pre-existing) subject: fir2ir appends a synthetic call to
  the `noWhenBranchMatchedException` builtin, which has no intrinsic registration, so the
  containing function is evicted per-function with the loud availableFunctions miss (adversarial
  review, 2026-07; correct granularity, no crash). Deliberate follow-up, not part of this slice:
  register the intrinsic as an inline throw (JVM precedent: the
  `throwNoWhenBranchMatchedException` intrinsic; Roslyn precedent for the exception choice:
  switch expressions throw `SwitchExpressionException`, an `InvalidOperationException`) — the
  exception-mapping choice must be probed and design-decided first. Pins: `ilText/nullablePrimitives.kt`
  (every declaration-position spelling, wrap/initobj/HasValue/extraction incl. the
  address-taking `ldloca`, elvis/safe-call shapes, BOTH `!!` throw shapes — the Nullable<T>
  HasValue branch and the reference `dup`/`brtrue`/`newobj NullReferenceException`/`throw`
  spelling), `ilText/nullableEquality.kt` (every `==` sequence), `ilText/nullableBoxing.kt`
  (box-collapse widenings, `object` storage positions, templates), `ilText/nullableRejected.kt`
  (the rejection warnings' source shapes),
  `ilText/nullableOverrides.kt` (gate interactions), `ilText/facadeMethodClash.kt` (the facade
  clash gate, all three flavors); runtime: `box/nullableBasic.kt`, `box/nullableEquality.kt`,
  `box/nullableBoxing.kt`, `box/nullableNotNullThrows.kt`, `box/nullableObjectMembers.kt`
  (nullable primitives through the object/companion singleton and static-initializer
  machinery), `box/tryDiscardedValue.kt` (the discarded Any-LUB try statement shape).
- Class model (JVM precedent: the CLR has real classes, so like `JvmLoweringPhases` there is NO
  vtable/class lowering machinery): only top-level plain classes — final or, since
  the inheritance model (below), open; non-generic or, since the generics model (below), with
  stage-1 type parameters — and, since the interface model (below), top-level
  non-generic all-abstract interfaces pass the shape gate
  (`DotNetIlEmitter.checkClassShapeSupported` / `checkInterfaceShapeSupported`); objects and
  companions stay final-only with sole supertype `kotlin.Any`.
  Rejection granularity is always the whole class — a failing member (signature, body, or IL
  method- or field-identity clash) removes the entire class from the module so no call site can
  resolve to a partial class, and the removal cascades through the type mapper to every user of
  the class.
- Inheritance model (probe series `inheritprobe_s1`–`_s3`; JVM precedent: real CLR classes =
  real platform inheritance, no vtable lowering — the same argument as the class-model bullet):
  a top-level plain class may be `open` (drops `sealed` from the `.class` flags) and may extend
  EXACTLY ONE base class when the supertype resolves to another top-level class of the compiled
  module. The gate checks only the structural shape; whether the base itself compiles is
  re-resolved from the live class map at the top of every render round, so a failing base
  cascades whole-class down the chain, each derived class warned with a reason carrying the
  base's reason (the chain analogue of the companion pair warnings) — pinned by
  `ilText/inheritanceBaseEvicted.kt`. Member flags (order probe-verified; ilasm treats them as
  an unordered keyword set, the emitter standardizes on the s2 spellings): an `open` member
  introducing a slot is `hidebysig newslot virtual` (`specialname newslot virtual` for
  accessors), a Kotlin `override` REUSES the base slot — `virtual` with NO `newslot` — and a
  `final override` is `virtual final` (still dispatching under `callvirt`, the Roslyn `sealed
  override` shape). An `open` member of a FINAL class stays non-virtual (nothing can override
  it; `isDotNetVirtual` is the single predicate both the declaration flags and the call sites
  consult). CALL SITES: virtual callees use `callvirt` — a stated widening of the established
  call-for-final deviation from Roslyn: final members keep the plain null-check-free `call`,
  but virtual dispatch has no non-virtual substitute, and `callvirt` with the operand token
  naming the DECLARING class dispatches correctly even through base-typed values and to final
  overrides (probe-verified). Calls through fake overrides (inherited members referenced via
  the derived class) resolve to the real declaration first (`resolveFakeOverride`, the
  `findSuperDeclaration` analogue), so the operand names the declaring class — valid for both
  `call` (inherited final member, derived receiver) and `callvirt`. A `super.f()` call
  (`IrCall.superQualifierSymbol != null`) is a plain non-virtual `call` on the resolved target
  — the CLR runs the base implementation with the `this` receiver, exactly the JVM's
  invokevirtual/invokespecial split (probe-verified from inside both plain and final
  overrides). Constructor chaining to the base reuses the delegating-call shape (`ldarg.0`,
  args, `call instance void 'Base'::.ctor(...)`) — identical IL for `this(...)` and base
  delegation. UPCASTS are pure reference widenings needing NO instruction (probe-verified:
  base-typed locals, parameters, returns): `isDotNetAssignableTo` walks the
  `DotNetIlClassInfo.baseClass` chain (linked in a pre-pass AFTER all registrations — forward
  references are legal IL and legal Kotlin), and expression-position `IMPLICIT_CAST` that is
  such an upcast emits just its operand; every other type operator (CAST, SAFE_CAST,
  INSTANCEOF, IMPLICIT_NOTNULL, non-upcast IMPLICIT_CAST) stays rejected loudly. STAYS
  REJECTED, whole-class: `abstract` and `sealed` classes (no abstract-member/instantiability
  model), exception supertypes (existing message; interface supertypes are SUPPORTED since the
  interface model, see its bullet), out-of-module or
  non-top-level bases, objects/companions with any supertype, covariant-return overrides
  (ECMA-335 II.15.4.2.3 slot matching includes the RETURN type, so the override would land in
  a fresh slot and base-typed `callvirt` would silently run the BASE body — probe-verified;
  Roslyn's fix is `.override` + `PreserveBaseOverrides` machinery this backend does not emit;
  compared on MAPPED types in the member pre-pass, so `String?`-to-`String` covariance — same
  IL `string` — stays supported; pinned by `ilText/inheritanceCovariantReturnRejected.kt`),
  and overrides of `kotlin.Any`
  members (`toString`/`equals`/`hashCode` — no Any model; pinned by
  `ilText/inheritanceAnyOverride.kt`; detected by walking `allOverridden()` against the
  TYPE-based `isAny`, because `IrClass.isAny`/`findOverriddenMethodOfAny` compare IdSignatures
  and this pipeline's symbols carry none). `protected` visibility is untouched (renders with
  the historical default like before). End-to-end: `box/inheritanceBasic.kt` (three-level
  chain: polymorphic dispatch through base-typed values, super chains, final override,
  inherited state/methods, upcast positions) and `box/inheritanceInitOrder.kt` (base init runs
  before derived init; `beforefieldinit` semantics unchanged — instance init order is a
  constructor-chain property, not a `.cctor` one).
- Interface model (probe series `ifaceprobe_s1`–`_s10`; JVM precedent: real CLR interface types =
  no vtable/interface lowering, the same argument as the class and inheritance bullets): a
  top-level, non-generic Kotlin `interface` whose members are ALL abstract (abstract functions
  and abstract `val`/`var` properties; empty interfaces included) is emitted as
  `.class interface public abstract auto ansi` — no `extends` line, no `sealed`, no
  `beforefieldinit` (s1). A `sealed interface` is deliberately ACCEPTED and emitted as the
  same plain interface — unlike a sealed CLASS (whose rejection is the missing abstract-class
  model), interface sealedness is pure frontend-enforced metadata with no CLR counterpart
  needed (JVM precedent: the JVM backend emits an ordinary interface too), and the exhaustive
  `when` it enables is `is`-gated by the type-operator rejection anyway (pinned by
  `ilText/interfaceEqualityWidening.kt`). Abstract members are
  `.method public hidebysig [specialname ]newslot abstract virtual instance ... cil managed`
  with an EMPTY `{ }` block (s1/s2; the emitter keeps its established specialname-first flag
  order — ilasm treats the flags as an unordered keyword set); abstract accessors are bound by
  ordinary `.property` blocks targeting the interface's own accessor methods (s2). A class
  lists its DIRECT interfaces comma-separated on an `implements` line after `extends`
  (`extends 'Base'` / `implements 'A', 'B'`, s3); interface-extends-interface is the same
  `implements` list on the interface declaration, and transitively implied super-interfaces are
  never repeated (s6). MEMBER FLAGS: `isDotNetVirtual` widens — every interface member and
  every member overriding one is virtual even in a FINAL class, because a non-virtual
  implementation assembles cleanly but load-poisons the type (TypeLoadException at first JIT of
  a using method, s1b). A Kotlin override of ONLY interface members introduces a fresh class
  slot: `newslot virtual`, `newslot virtual final` for a Kotlin-`final` member (the Roslyn
  implicit-implementation shape); an override of a base-class member keeps the slot-reuse
  spelling `virtual` (no newslot) even when it also implements an interface — CLR interface
  mapping follows the class vtable slot (s4). Stated deviation from Roslyn: `final` follows
  Kotlin modality alone, so an implicit implementation in a final class whose member is not
  `final override` stays `newslot virtual` where Roslyn would seal it — s4 shows the non-final
  spelling dispatches identically (goldens + box pin it). CALL SITES: interface-typed receivers
  always use `callvirt` with the operand naming the DECLARING interface — `resolveFakeOverride`
  handles inherited members, with a maybe-abstract fallback because the plain resolution
  ignores abstract targets (a fake override whose only real declarations are abstract interface
  members resolves to null otherwise); naming a sub-interface that merely inherits the member
  is a runtime MissingMethodException (s6) — NO fake-override leniency, unlike the class-side
  rule. One class member implicitly fills every same-signature interface slot (the
  diamond/merge shape, s9). A base-class member satisfying a derived class's interface works
  iff the inherited member is VIRTUAL (s5a — Kotlin fake-override semantics for free) AND its
  mapped IL signature matches the interface slot EXACTLY, return type included: ECMA-335
  interface mapping matches the full signature and this backend emits no `.override`/bridge
  machinery (the JVM supports the covariant flavor via generated bridge methods), so the
  non-virtual variant is gated whole-class at compile time with a message citing s5b and the
  inherited covariant-return variant (`class Combo : Factory(), Maker` where
  `Factory.make(): Bottom` is meant to fill `Maker.make(): Top`) is gated whole-class in the
  member pre-pass with a message citing s10 — both flavors assemble without any ilasm
  diagnostic and throw TypeLoadException at first JIT of a using method (s5b/s10, the
  covariant probe covering the function and the property-accessor variants; pinned by
  `ilText/interfaceNonVirtualImplRejected.kt` and `ilText/interfaceCovariantImplRejected.kt`);
  same-IL-type Kotlin covariance (`String?` filled by an inherited `String` member) stays
  supported — the comparison runs on MAPPED types, like the declared-override covariant gate
  of the inheritance bullet. UPCASTS: class→interface and
  interface→super-interface are free reference widenings in every position (field, parameter,
  return, local — s7); `isDotNetAssignableTo` walks the supertype DAG
  (`DotNetIlClassInfo.baseClass` chain + `interfaces` lists, BFS with dedup — diamonds are
  legal — linked in the same post-registration pre-pass as the base chain), and the reference
  `ceq` is type-agnostic across interface-typed and class-typed views (s7; sibling widening —
  see the equality bullet). EVICTION: an evicted interface cascades whole-class to every
  implementing class and every sub-interface — the `implements` list is re-resolved from the
  LIVE class map at the top of every render round with chained reasons, the interface arm of
  the base-class cascade (pinned by `ilText/interfaceEvicted.kt` and
  `ilText/interfaceDefaultBodyRejected.kt`); evicting an implementer never affects the
  interface. STAYS REJECTED, loudly, whole-interface/whole-class: interface members WITH
  bodies — default methods and accessors with bodies (CoreCLR itself supports Default Interface
  Methods, s8, so the message says "not yet supported": lifting this is purely backend work),
  private interface members, abstract redeclarations of super-interface members (an unprobed
  double-slot shape), companion objects and any nested declaration in an interface,
  `fun interface` (no SAM-conversion model), generic interfaces, out-of-module or non-top-level
  interfaces, interface DELEGATION (`class C(...) : I by d`) in BOTH source spellings — the
  `val`-parameter and the plain-parameter form (which additionally synthesizes a loose
  `$$delegate_0` field): the frontend's forwarding members (origin DELEGATED_MEMBER) are gated
  whole-class with a real user-facing message so the two cosmetically different spellings never
  diverge in support (pinned by `ilText/interfaceDelegationRejected.kt`),
  `super<I>.f()` (needs the DIM model; rejected up front in `emitCall`),
  `is`/`as`/safe-cast on interface types (the existing type-operator rejection stays
  authoritative — including the IMPLICIT_CAST downcast a positive `===` smartcast inserts
  afterwards), and interface members overriding `kotlin.Any` members (no Any model). Failure-
  mode calibration: every interface-mapping mistake (missing virtual, wrong operand interface)
  assembles CLEAN and fails only lazily at runtime, so box coverage is mandatory per dispatch
  shape — `box/interfaceBasic.kt` (dispatch through interface-typed values, abstract property
  access, multiple interfaces + base class, final override, derived override via interface
  dispatch, the s5a inherited-member shape, interface-typed fields/returns, identity and
  null checks) and `box/interfaceHierarchy.kt` (inherited members through sub-interface
  receivers, the diamond, super-interface widening, sibling-interface identity); goldens
  `ilText/interfaces.kt` (including the `newslot virtual final` / `specialname newslot virtual
  final` spellings of a Kotlin `final override` implementing only interface members) and
  `ilText/interfaceHierarchy.kt` pin every new spelling,
  `ilText/interfaceNonVirtualImplRejected.kt` pins the s5b gate,
  `ilText/interfaceCovariantImplRejected.kt` the s10 gate,
  `ilText/interfaceDelegationRejected.kt` the delegation gate, and
  `ilText/interfaceEqualityWidening.kt` the sibling widening, the no-common-supertype
  rejection and the sealed-interface acceptance.
- Properties use the CLR's first-class property model: private backing fields, `get_x`/`set_x`
  `specialname` accessor methods, and a `.property` metadata block binding them (spellings
  ilasm-probe-verified) — a stated deviation from the JVM's `PropertiesLowering`, which the CLR
  makes unnecessary. Because of the accessor mangling, the member pre-pass rejects (whole-class)
  IL method-identity clashes such as `val x` vs a user-declared `fun get_x(): Int` — ilasm fails
  on the duplicate method declaration (probed on 10.0.9); the JVM analogue is the frontend
  `PLATFORM_DECLARATION_CLASH` diagnostic for `val x` vs `fun getX()`. The same identity gate
  runs over each file facade's top-level callables, at facade granularity (see the nullability
  bullet's facade analogue; pinned by `ilText/facadeMethodClash.kt`).
- Instance members of the final-class model are invoked with plain non-virtual `call`
  (probe-verified) — a stated deviation from Roslyn, which emits `callvirt` purely for its
  implicit null check.
- Top-level properties follow the JVM facade-statics shape (`StaticInitializersLowering`'s
  `<clinit>`): `DotNetStaticInitializersLowering` moves the backing-field initializers of each
  file into one synthetic file-parented `<clinit>` function, in declaration order, which the
  emitter renders as the facade's `.cctor`; the fields become `private static` facade fields
  (accessed with `ldsfld`/`stsfld`) with static `get_x`/`set_x` accessors and static `.property`
  blocks — all spellings probe-verified (`statprobe_s1`/`_s2`, including a user-class-typed
  static field). Stated deviation from the JVM lowering: it is a `ClassLoweringPass` over the
  facade class `FileClassLowering` created earlier, while this backend builds facades at
  emission time, so the pass is per-`IrFile` and the `<clinit>` is file-parented. The `.cctor`
  is lowered IR (not emission-time text) so initializer bodies pass through the later
  `for`-loop/string-concat phases like any other body.
- `beforefieldinit` is omitted exactly on classes that receive a `.cctor` and kept everywhere
  else. Decider (probe): with the flag the CLR defers the `.cctor` past static method calls, so
  calling only a top-level *function* of a facade would silently skip the file's
  property-initializer side effects; without it the `.cctor` runs before the first active use
  (`statprobe_s1`: `cctor-start` prints before `main-start`) = Kotlin/JVM first-active-use
  class-initialization parity, pinned end-to-end by `box/topLevelPropertyInitOrder.kt`.
  Accepted JVM-shared delta: a re-entrant initialization cycle observes default field values
  (CLR `.cctor` re-entrancy = JVM `<clinit>` behavior) — documented, not enforced.
- `const val` is a CLR `literal` field (`.field public static literal <t> 'C' = <literal>`) —
  the ConstantValue-attribute analogue of the JVM's `constantValue()` exclusion from `<clinit>`
  and `JvmPropertiesLowering`'s `!isConst` accessor suppression: no accessors, no `.property`
  block, no `.cctor` entry; every read is inlined by the frontend (golden-verified), and an
  exotic surviving accessor call fails loudly via the `availableFunctions` miss. All literal
  spellings probe-verified (`statprobe_s1`/`_s3`: int32, string incl. the `bytearray` fallback,
  bool, char, int64 incl. MIN_VALUE, float64 decimal and raw-bit forms). A `literal` field has
  no storage, so codegen rejects any direct backing-field access.
- Failing-initializer granularity is the whole per-file property group: declaration-order init
  interleaving cannot be partially preserved, so a failure anywhere in a file's `<clinit>` (or
  in any backing-field-bearing property) removes ALL backing-field-bearing top-level properties
  of that file together — fields, accessors, `.property` blocks and the `.cctor` — each warned
  with a shared reason carrying the original one. This is the facade-stateful analogue of the
  whole-class rejection granularity. Accessor-only (custom-getter) properties fail per-function;
  const `literal` fields are independent of the group.
- Plain top-level `object` declarations follow the JVM singleton model: `DotNetObjectClassLowering`
  synthesizes a static `INSTANCE` field on every module-declared non-companion object (origin
  `FIELD_FOR_OBJECT_INSTANCE`, the object's own type, initializer = a call to the primary
  constructor, which is private from the frontend) and rewrites every `IrGetObjectValue`
  targeting a module-declared object into an `IrGetField(INSTANCE)`; `kotlin.Unit` is guarded
  FIRST (the existing no-op/rejection paths stay authoritative) and out-of-module objects stay
  untouched for the existing loud failures. Stated packaging deviation: the JVM's three
  cooperating pieces (`ObjectClassLowering`, `SingletonReferencesLowering`, and the
  field-creation slice of `CachedFieldsForObjectInstances`) are merged into this one module pass
  — no intermediate producers of singleton references exist in this backend.
  `DotNetStaticInitializersLowering` sweeps static class fields (today exactly `INSTANCE`) into
  a class-parented `<clinit>` rendered as the class's `.cctor` — that slice matches the JVM
  `ClassLoweringPass` precedent even more directly than the facade slice. The IL shape is
  probe-verified (objprobe_s1): `.field public static initonly class 'C' 'INSTANCE'`, a private
  `.ctor` (`.ctor` visibility now follows the Kotlin declaration; a public one would let other
  .NET code mint second instances) `newobj`'d from the same class's own `.cctor`, and use sites
  are a bare `ldsfld` + the existing plain `call instance`. A bare object reference in statement
  position is `ldsfld` + `pop`, never a no-op — Kotlin makes it a first-active-use trigger, and
  the `.cctor` fires on a bare cross-class `ldsfld` (objprobe_s2). `beforefieldinit` is dropped
  on object classes by the existing decider, and the parity argument is exact here: every object
  use is an `ldsfld`, so CLR first-active-use semantics equal Kotlin/JVM object initialization
  (`box/objectInitOrder.kt` pins laziness + declaration order end-to-end). Non-const object
  state stays on the instance, initialized by the merged private constructor — a stated
  deviation from the JVM's static-state hoist (`MoveOrCopyCompanionObjectFieldsLowering` makes
  every object-parented property field static), which the CLR-side real singleton makes
  unnecessary. Rejections ride the existing gates, whole-class: `data object` (the same
  Any-model gap as data classes — the gate message names both), nested/local/anonymous objects,
  and IL accessor-identity clashes; `==` between objects stays rejected while `===` works via
  the existing reference `ceq`. The member pre-pass additionally gates IL FIELD-identity
  clashes, whole-class: the backing field of a user property named `INSTANCE` whose type maps to
  the object's own class (`val INSTANCE: A? = null` — nullability erases) collides with the
  synthesized singleton field in both name and field signature (staticness/visibility are flags,
  not identity), which ilasm rejects as a duplicate field declaration (probed on 10.0.9,
  fieldprobe); the identity key is name + mapped IL type, so a differently-typed `INSTANCE`
  property is a legal CLR shape and stays supported (same probe; pinned by
  `ilText/objectInstanceFieldClash.kt`). Stated deviation from the JVM backend, which RENAMES
  the clashing private backing field (`RenameFieldsLowering` → `INSTANCE$1`): this backend has
  no field-renaming machinery, so the clash is rejected like the accessor-identity clash.
- `const val` in an `object` is the same CLR `literal` field as on a facade, emitted on the
  object class (coexistence of `literal` fields with a `.cctor` and an `initonly` field on one
  class is probe-verified, objprobe_s9a): no accessors, no `.property` block, no `.cctor` entry,
  reads inlined by the frontend, exotic surviving accessor calls fail via the availableFunctions
  miss. One JVM-precedented slice of the static-state hoist IS needed for this:
  `DotNetInitializersLowering` marks object-parented const backing fields static before the
  shared initializer merge (the JVM does it in `MoveOrCopyCompanionObjectFieldsLowering` /
  `JvmCachedDeclarations.getStaticBackingField`), because the shared merge's `!isStatic` filter
  would otherwise copy the const initializer into the constructor as a write to a storage-less
  `literal` field.
- Object probe deltas, documented not enforced: CoreCLR 10 did NOT enforce `initonly` — an
  outside `stsfld` to the INSTANCE field succeeded at runtime (objprobe_s3), so `initonly` is
  declarative metadata only, kept for JVM-`final` parity of intent; self-reference during
  initialization observes a null INSTANCE (objprobe_s4 — the same `.cctor` re-entrancy delta the
  JVM shares, see the `beforefieldinit` bullet); a mutual A↔B object cycle resolves without
  deadlock, the first-touched object's constructor sees the other fully initialized while the
  other sees null (objprobe_s5 — exact JVM parity; only the acyclic shape is box-tested,
  `box/objectCrossReference.kt`).
- `companion object`s are real CLR nested types: `.class nested public auto ansi sealed` inside
  the enclosing class's body (spelling probe-verified in every operand position — field type,
  `newobj`, `ldsfld`/`stsfld`, `call`, method param/return signatures, `.locals` — as
  `'demo.Outer'/'Companion'`, slash OUTSIDE the quoted identifiers, enclosing name first;
  objprobe_s6; the spelling lives in exactly one place, `DotNetIlClassInfo.ilTypeRef`). The
  singleton field lives on the ENCLOSING class, named after the companion (default `Companion`;
  NAMED companions keep their own name — JVM precedent:
  `CachedFieldsForObjectInstances.getFieldForObjectInstance`'s not-mapped-companion branch
  parents the field to `singleton.parent`), and the enclosing class's `.cctor` does the
  `newobj`/`stsfld` — so the enclosing class drops `beforefieldinit` while the companion itself
  has NO `.cctor` and keeps it. Init-order parity is preserved: every companion access goes
  through the field on the enclosing class and triggers ITS `.cctor` (touching a companion
  initializes the enclosing class, like the JVM), and the enclosing `.cctor` runs exactly once
  before the first `newobj` of the enclosing class or first companion access (objprobe_s8,
  pinned end-to-end by `box/companionInitOrder.kt`). Stated deviation from the JVM's
  `MoveOrCopyCompanionObjectFieldsLowering`: companion backing fields and `init {}` blocks are
  NOT hoisted to the enclosing class — they stay on the companion instance, compiled by the
  unchanged existing machinery (`DotNetInitializersLowering` merges them into the companion's
  constructor), and no `RemapObjectFieldAccesses` analogue exists; the JVM hoist serves
  JVM-ABI/interop needs that the CLR's real nested type makes moot. Accepted delta (documented,
  not enforced): companion state initializes in the companion constructor invoked FROM the
  enclosing `.cctor` rather than as enclosing-class statics — indistinguishable except under
  initialization re-entrancy, the already-documented delta. VISIBILITY (probe-decided): the CLR
  grants nested→enclosing private access (objprobe_s7a) but NOT enclosing→nested — an IL-private
  companion `.ctor` `newobj`'d from the enclosing `.cctor` throws TypeInitializationException
  wrapping MethodAccessException, and a throwing `.cctor` permanently poisons the type
  (objprobe_s7b); an isolated enclosing→nested private field read throws FieldAccessException
  (objprobe_s7c). Therefore every Kotlin-private member OF A COMPANION — the `.ctor` (private
  from the frontend) and any private method/accessor — is emitted with IL `assembly` visibility,
  uniformly (`.method assembly hidebysig specialname rtspecialname instance void .ctor()`
  probe-verified working when `newobj`'d from the enclosing `.cctor`, objprobe_s7c); stated
  deviation from the JVM backend, whose analogue is the synthetic `access$` bridges for
  outer→companion-private access (pinned end-to-end by `box/companionPrivateAccess.kt`, both
  directions and every member-kind slice — the private method and BOTH accessors of a private
  property are called across the enclosing→nested boundary; the accessor
  `.method assembly hidebysig specialname` spelling is golden-pinned by
  `ilText/companionObject.kt`). `const val` in a companion is a `literal` field on the NESTED class (literal
  fields on a nested class probe-verified, objprobe_s9b), the same no-copy-to-enclosing
  deviation as objects. Rejection granularity is the linked WHOLE PAIR: the enclosing class and
  its companion are separate `availableClasses` entries (the companion needs its own identity
  for type mapping and member resolution), but every eviction site removes both entries and
  both member sets — a partial pair violates the whole-class rule in both directions (the
  singleton field on the enclosing class is typed as the companion and the enclosing `.cctor`
  news it). The pair warnings attribute the failure to the half that actually failed: a
  companion failure surfaces out of the enclosing class's render (the companion renders only
  recursively inside it), so the render fixpoint re-tags it with the companion
  (`DotNetIlUnsupportedClassException`) before evicting. Pair eviction is pinned in both
  phases: the member pre-pass by `ilText/companionMemberClash.kt`, the render fixpoint (a
  companion member body failing only after its callee's round-one eviction, plus the extra
  round that re-fails an already-rendered user of the evicted pair) by
  `ilText/companionFixpointEviction.kt`. The gate allows exactly one nested `IrClass` iff it `isCompanion` with kind OBJECT,
  recursively validated with the same constraint chain (sole supertype `Any`, final,
  non-generic, no nested classes of its own, not data); non-companion nested classes/objects
  stay rejected, and a companion inside an `object` cannot reach the gate (frontend-rejected).
  The companion singleton field participates in the ENCLOSING class's field-identity gate, but
  the colliding source shape (a user property named after the companion) is itself a frontend
  REDECLARATION — a companion also occupies the value namespace — so that slice is
  defense-in-depth; a same-named FIELD legally coexists with the nested TYPE (objprobe_s6,
  pinned by `ilText/companionObject.kt`). Companions are nested, not top-level, so they reserve
  no file-facade name (`buildFileClassNames` only seeds top-level classes — verified unchanged).
- Top-level extension properties emit their accessors as plain static methods with the receiver
  as a regular parameter but NO `.property` block: a CLR property with parameters is an indexer,
  which is out of scope.
- No top-level declaration is dropped silently: after gathering (classes, functions, properties
  — delegated and lateinit properties are rejected with specific messages), a closing sweep
  warns about any remaining top-level declaration kind. Typealiases are deliberately ignored
  WITHOUT a warning (the JVM backend emits no bytecode for typealiases either). The injected
  stdlib declarations stay exempt: `println`/`val Char.code` are intrinsic-excluded
  (`excludesDeclarationFromCodegen`, now also honored for property accessors) and the exception
  classes are registry-excluded.
- Initializer merging is `DotNetInitializersLowering`/`DotNetInitializersCleanupLowering`, the
  same one-line subclasses of the shared backend.common lowerings the JVM uses, plus a
  .NET-specific guard that turns the shared lowering's local-class `AssertionError` into the
  fail-loud diagnostic. The guard covers exactly what the shared lowering merges — non-static,
  class-parented fields and `init {}` blocks — never top-level property initializers, which no
  constructor merge can reach. The pair runs BEFORE `DotNetForLoopLowering` — a stated deviation
  from the JVM phase order, because the loop rewrite's builder only exists inside functions, so a
  `for` inside `init {}` must already have been inlined into a constructor.
- User-class type mapping is emission-scoped: one `DotNetIlTypeMapper` over the emitter's live
  `availableClasses` map per `DotNetIlEmitter.emit` call, no global class registry — removing a
  class during the render fixpoint automatically fails every declaration whose types mention it.
- File facade names are precomputed pre-gate (`DotNetIlEmitter.buildFileClassNames`): every
  declared top-level class reserves its IL name even when it is later skipped, so facade naming
  depends only on what the module declares, never on which classes survive support gates.
  Injected stdlib declarations are excepted — they are not module declarations and reserve no
  facade name (`DotNetMappedExceptions.isExceptionStdlibDeclaration` filters them out).
- The fake stdlib (`DotNetStdlibSource`) is a map of injected source files, one per package
  (`kotlin.io` for `println`, `kotlin` for `Char.code`), filtered through the intrinsic registry and
  never emitted as classes of their own. Injected declarations must compile without any diagnostics,
  including warnings: the FIR test infrastructure maps every reported diagnostic back to a test
  file and crashes on diagnostics in injected files (suppress e.g. deprecations locally).
- Exceptions follow the JVM model: `IrThrow` and `IrTry` map 1:1 onto the platform's
  exception machinery with NO lowering (no WASM/JS TryCatchCanonicalization or
  MultipleCatchesLowering). Built-in exception classes are TYPE-MAPPED onto the CLR hierarchy
  (JVM analogue: `JavaToKotlinClassMap`) via the curated `DotNetMappedExceptions` registry, so
  exceptions thrown by other .NET code stay catchable: `kotlin.Throwable` AND `kotlin.Exception`
  → `System.Exception` (the CLR has no Throwable/Exception split, so `catch (e: Exception)` ≡
  `catch (e: Throwable)`); IllegalArgumentException → ArgumentException; IllegalStateException →
  InvalidOperationException; UnsupportedOperationException → NotSupportedException;
  ArithmeticException → ArithmeticException (closing the divide-by-zero catchability debt: the
  CLR's DivideByZeroException IS-A System.ArithmeticException, probe-verified; its message
  "Attempted to divide by zero." is kept verbatim — JVM precedent, "/ by zero" IS the JVM's
  platform message); IndexOutOfBoundsException → IndexOutOfRangeException; NullPointerException
  → NullReferenceException; ClassCastException → InvalidCastException. RuntimeException, Error
  and NumberFormatException resolve (declared in the injected stdlib) but are REJECTED with
  per-type reasons — mapping them would observably break catch semantics (see the registry KDoc).
  Accepted deltas, documented on the registry: `message` keeps type `String?` but is never null
  on mapped exceptions (no-arg `Exception()` yields the CLR default text); the constructor
  whitelist is `()`/`(String?)` everywhere and `(String?, Throwable?)` where the registry's
  `hasMessageCauseCtor` flag is set — the flag mirrors the Kotlin stdlib's declared constructor
  surface, not CLR availability (the CLR `(string, Exception)` overload exists on every mapped
  type, probe-verified) — and the cause-only `(Throwable?)` constructor is rejected (no CLR
  overload). `throw e` inside a catch is a plain `ldloc`/`throw` preserving object identity; the
  IL `rethrow` instruction is never emitted (Kotlin has no bare rethrow; stack-trace-restart
  delta is moot until traces are surfaced). The injected exception declarations are excluded from codegen
  entirely — the class-level parallel of an intrinsic's `excludesDeclarationFromCodegen` — and
  user classes extending them are shape-gate-rejected until the inheritance model exists.
  Deferred: Roslyn-parity `RuntimeCompatibilityAttribute` (wrapping raw non-Exception throws)
  until interop with non-Exception-throwing code matters.
- try/catch follows the JVM model: `IrTry` maps 1:1 onto the CLR exception table — one `.try`
  block plus consecutive typed `catch` handlers in Kotlin source order (the CLR matches strictly
  first-to-last, probe-verified; the frontend owns unreachable-catch diagnostics) — with no
  lowering machinery. Regions are exited only via `leave` (a `ret` or `br` crossing a region
  boundary assembles but fails at runtime), and `leave` discards the evaluation stack, so a
  `try` expression drains its branch values into a synthetic result local reloaded at the join
  label; returns crossing protected regions drain into a synthetic return local and `leave` to a
  shared return-join epilogue (the Roslyn shape), and `break`/`continue` crossing regions emit
  `leave` straight to the loop labels — legal toward any enclosing-scope label, forward or
  backward, crossing nested regions in one hop (all probe-verified, `excprobe_s2`). One stated
  deviation from the JVM backend: the CLR requires an empty evaluation stack at `.try` entry
  (ECMA-335 I.12.4.2), so a `try` expression with operands already on the evaluation stack
  (e.g. a non-first call argument) is rejected rather than spilled.
- `finally` uses real CLR `leave`-driven finally handlers with NO JVM-style finally
  inlining/duplication — a CLR-forced deviation from the JVM backend: the CLR runs the finally
  automatically on every `leave` out of the region (normal completion, `break`/`continue`,
  return-join leaves) and on the exceptional path, inner-then-outer for nested regions
  (probe-verified, `excprobe_s3`). A `.try` carries either catch handlers or ONE `finally`,
  never both — combining them assembles silently but throws `InvalidProgramException` at
  runtime — so Kotlin `try`/`catch`/`finally` nests the try/catch construct inside an outer
  `.try { } finally { }`; catch-less `try`/`finally` is a single region. The finally body is
  emitted as void and exits only through `endfinally`, so `return`/`break`/`continue` crossing
  OUT of a finally body are rejected (`dotNetUnsupported`) — even `leave` may not exit a
  finally handler; exits within it (a loop or try/catch declared inside the finally body) work
  normally.
- Generics stance: the type representation stays structural so that generics target real
  CLR reified generics (Roslyn shape), not JVM-style erasure. Unsupported generic shapes are
  rejected, never erased.
- Generics model (stage 1) (probe series `genprobe_s1`–`_s9`; precedent: Roslyn — the CLR has REAL
  reified generics, so like every prior model bullet there is NO erasure/monomorphization/lowering
  machinery: the type mapper and emitters learn generic declarations, type-parameter references and
  instantiation tokens, and the frontend owns all type checking):
  - SUPPORTED: generic TOP-LEVEL FUNCTIONS (non-inline; unconstrained invariant type parameters —
    Kotlin bound `Any?` only, plus the pre-existing `T : String`/`String?` erosion of the
    string-concat lowering, kept for compatibility and pinned by the borrowed `box/strings/kt50140.kt`:
    such a `T` still declares its real arity but its SLOTS map to `string`); generic TOP-LEVEL PLAIN
    CLASSES (final or open, same bounds rule without the String exception); a NON-generic class
    extending an INSTANTIATED generic base — optionally ALSO implementing interfaces: the ordinary
    `implements` line composes with the instantiated `extends` and the gate only rejects interface
    supertypes on classes that are themselves generic (pinned by `ilText/genericInheritance.kt` +
    `box/genericInheritance.kt`, `LabeledBox`) — and a GENERIC class extending a plain base; what a
    `T`-typed value supports is exactly store/load (locals, params, returns, fields of the declaring
    class) and passing to another `T` position.
  - SPELLING CANON (all probe-verified): a generic class is ONE quoted identifier with the CLS
    backtick-arity suffix INSIDE the quotes (`.class ... 'demo.Box`1'<'T'>` — suffix outside the
    quotes is an ilasm syntax error, genprobe_s2c; the suffix is CLS convention, not CLR-required,
    genprobe_s2b — emitted for Roslyn/interop parity, which also makes plain-`Box`/generic-`Box`
    IL collisions impossible); quoted type-parameter NAMES assemble and run (genprobe_s8) and are
    decorative — CLR identity is positional (`!n` class / `!!n` method vars). A generic method is
    `.method ... !!0 'id'<'T'>(!!0 'x')`, self-contained, no class machinery (genprobe_s1). EVERY
    member reference on a generic class carries an instantiation on the OWNER token while its
    signature slots stay OPEN (`!0`/`!!0` verbatim): closed externally
    (`newobj instance void class 'Box`1'<string>::.ctor(!0)`,
    `call instance !0 class 'Box`1'<string>::'get'()`, `ldfld !0 class 'Box`1'<string>::'value'`),
    the OPEN self-instantiation `class 'Box`1'<!0>` inside the class's own bodies (genprobe_s2/_s7);
    generic-method call sites substitute only the `<inst>` list (`call !!0 ...::'id'<string>(!!0)`;
    `!!0` is itself a legal instantiation argument at generic→generic pass-through sites, and
    `class 'Box`1'<!!0>` composes inside generic methods, genprobe_s9). EXCEPTION: `.property`
    accessor references use the bare class name with NO type-args list (genprobe_s2). The `extends`
    line of an instantiated generic base is `extends class 'Box`1'<int32>` with the ordinary
    base-ctor chain (genprobe_s5). Nullable composes verbatim as an argument
    (`class 'Box`1'<valuetype [mscorlib]System.Nullable`1<int32>>`) in every operand position, and
    the mandatory home-address spill rule extends to `!0`-returning calls — spill to a local typed
    with the CLOSED substituted type, then `ldloca` (genprobe_s4). Reification is real:
    `Box`1<int32>` stores a raw int32, zero box/unbox, instantiations coexist and nest (genprobe_s3).
  - CODEGEN MODEL: mapped signatures stay OPEN (`TypeParameter`/`GenericInstance` arms of
    `DotNetIlValueType`); call sites derive the owner token from the RECEIVER's mapped type walked
    to the declaring class (`dotNetViewAsGenericOwner` — inherited members and super-calls through a
    derived receiver name the instantiated BASE, genprobe_s5) and emit argument VALUES against the
    IL-level SUBSTITUTED types (`substituteDotNetTypeParameters`). Assignability stays structural
    and INVARIANT for free (instantiation tokens compare as rendered strings; the supertype walk
    carries the base INSTANTIATION — `DotNetIlClassInfo.baseType`). CLR method identity includes
    generic ARITY, so the member/facade identity gates key on it (`fun <T> pick(x: String)` and
    `fun pick(x: String)` are legal coexisting overloads, pinned by `ilText/genericArity.kt`).
    OVERRIDES in a class with a generic base MUST be spelled with the SUBSTITUTED types — that
    spelling reuses the base slot for returns (genprobe_s5) AND parameters (genprobe_s8), and falls
    out of mapping the derived member's own concrete Kotlin types; the OPEN `!0` spelling in a
    non-generic derived class is a POISON SHAPE (assembles warning-free, silently splits the slot,
    base-typed `callvirt` runs the BASE body — the covariant-return failure family; genprobe_s5b)
    that this codegen can never emit, and the covariant-return member pre-pass gate compares the
    overridden return UNDER the substitution (`dotNetClassArgumentsFor`) so substituted overrides
    pass and real covariance still rejects. Eviction rides the existing fixpoint: instantiations
    map arguments through the LIVE class map, so an instantiation mentioning an evicted class fails
    its USE, and the `extends` re-resolution carries a type-argument-eviction reason down the chain
    (pinned by `ilText/genericEvicted.kt` — the generics analogue of `inheritanceBaseEvicted.kt`:
    an evicted class used as a function's instantiation argument evicts per-function, used as a
    generic-base argument evicts the derived class whole-class, while a sibling instantiation of
    the same base survives untouched).
  - STAYS REJECTED, loudly (each with a specific message; pinned by `ilText/genericRejected.kt`):
    declaration-site variance (`out`/`in` — ECMA-335 II.10.1.7 allows variance only on interfaces
    and delegates while Kotlin allows it on classes; Roslyn has no class-variance shape to follow,
    and emitting invariant would silently change assignability; a future interface-variance slice
    can widen), generic constraints (`T : Base` — next stage: bound-aware representation +
    `constrained.` calls), `T?` ANYWHERE in a generic declaration (NO uniform CLR representation:
    `T` may instantiate to a value type needing `Nullable<T>` and to a reference type needing
    nothing — the deferred ABI problem the nullability bullet reserved; the declaration is rejected,
    never given an ad-hoc representation), `==`/`===` on `T` operands and `x == null` on `T` (a
    value-type instantiation makes reference `ceq` meaningless; no lifted story without
    constraints), string templates/toString of `T` and member calls on `T` receivers (no
    constraints model / no Any model), widening `T` to `Any?` (boxing an unconstrained `T` is
    constraints-model territory), `is`/`as` on `T` or generic types (existing type-operator
    rejections stay authoritative), inline/reified generic functions (no inlining model), varargs
    of `T` (no arrays), generic MEMBER functions (unexercised combination, whole-class), generic
    (extension) properties, generic INTERFACES (unchanged from the interface model), generic
    classes containing companions/nested objects (untouched nested machinery), generic classes
    implementing interfaces, and generic-extends-generic chains (IL shape probed fine, genprobe_s7,
    but the gate interactions are unexercised — a later slice can widen the last three).
  - Pins: `ilText/genericFunctions.kt` (declaration + call-site spellings for every mapped
    type-arg kind incl. `<!!0>` pass-through, a nested instantiation as a generic-method type
    argument — `id<Box<String>>` carries `class 'Box`1'<string>` in the `<inst>` list — and the
    genprobe_s9 composition: `wrap` returning `class 'Box`1'<!!0>` with the
    `newobj instance void class 'Box`1'<!!0>::.ctor(!0)` body spelling), `ilText/genericClasses.kt`
    (full class shape, open
    self-instantiation, closed external operands, nested instantiation, `.property` bare-name
    accessor refs), `ilText/genericInheritance.kt` (instantiated-base extends/ctor-chain/override/
    super-call spellings + generic-extends-plain + the instantiated-base-with-`implements`
    combination), `ilText/genericArity.kt` (arity overloads +
    suffix coexistence), `ilText/genericRejected.kt` (every rejection above),
    `ilText/genericEvicted.kt` (the eviction cascade), `ilText/classShapeRejected.kt` (the
    variance flavor in the class-shape gate); runtime:
    `box/genericFunctions.kt` (every type-arg kind incl. both `Int?` flavors through `!!0`,
    multi-param, T pass-through, `wrap(x).v` round-trips of the `Box<!!0>` composition, the
    nested-instantiation type-arg), `box/genericClasses.kt` (state, coexisting instantiations,
    nesting, permuted self-instantiation `Pair2<B, A>`), `box/genericInheritance.kt` (dispatch
    through instantiated-base views, substituted overrides, super chains, inherited mutation,
    cross-view identity, generic-extends-plain dispatch, and the combined
    generic-base-plus-interface flavor: interface-view dispatch, inherited state and mutation
    through all views — interface-mapping mistakes fail only at JIT time, so the dispatch shape
    carries its own runtime pin).
- Shared runtime code (e.g. the Kotlin-parity `Double.toString` rendering) is hand-written IL on the
  synthetic module-private `'<KotlinIl>'` class (`DotNetIlRuntimeHelpers`) — the CLR-side stand-in
  for the JVM's `kotlin.jvm.internal.Intrinsics` runtime until a real .NET stdlib exists. The class
  is emitted at most once per module and only when a rendered method required one of its helpers.
  Every mscorlib member signature used in helper IL must be verified by assembling and running an
  ilasm probe before it lands in codegen.

## Box tests

- Like every mature target, box tests execute on the real runtime (JVM in-process, JS under Node,
  Native via its runner): the box suite (`AbstractDotNetBoxTestBase` in `AbstractDotNetIlTextTest.kt`)
  compiles with target `net` to `foo.dll` + `foo.runtimeconfig.json` and runs it via
  `<dotnet> exec foo.dll`. The signed `dotnet` host sidesteps Smart App Control blocking of freshly
  assembled unsigned exes; box never launches an `.exe` directly.
- The signed `dotnet` host only avoids SAC for direct `.exe` *execution*; it does NOT stop SAC from
  blocking the CLR from *loading* the freshly assembled unsigned dll. On a machine with Smart App
  Control ON, SAC makes a per-file cloud-reputation call the first time each unsigned dll is loaded
  and fails-closed on a negative verdict (`FileLoadException`, HRESULT `0x800711C7`, Code Integrity
  policy `VerifiedAndReputableDesktop`). Measured behavior (2026-07, SAC-enforced Win 11 host): the
  SmartScreen verdict is derived from the assembly CONTENT, not just its hash. The modern ilasm's
  output is non-deterministic (same `.il` assembles to a different hash every time), yet the exact
  IL of an affected test program reassembled under a fresh hash is blocked again, every time — the
  block is deterministic and effectively permanent per affected program on that machine, and
  re-running the suite does NOT clear it (an earlier "transient burst" theory is disproved).
  Concretely, of the 11 dotnet box programs existing at measurement time, 2 (`booleanShortCircuit`,
  `forLoopEdges`) were always blocked; the other 9 usually loaded but were occasionally blocked
  transiently too when a whole-suite run loads many fresh dlls in a burst (e.g. `charOperations`
  blocked in one parser variant and loaded in the other within the same run). The corpus has since
  grown well past those 11 programs; the newer programs have no measured SAC verdicts. The trigger
  is an opaque whole-file ML threshold, not a specific instruction pattern: each half of the
  flagged `booleanShortCircuit` assembly (helpers with the Int.MIN_VALUE-guarded `div` pattern
  alone, or the string-comparison half alone) passes when assembled separately; only the
  complete program is flagged, and the equally div-guard-heavy
  `intMinValueDivision` program passes.
- `DotNetBoxRunner` retries a blocked load a few times with a short delay to absorb a genuinely
  in-flight verdict, then aborts the test as SKIPPED (JUnit `TestAbortedException`) with a
  diagnostic that names SAC (any other non-zero exit fails immediately). Rationale (user decision,
  2026-07): a host whose OS refuses to load the assembly cannot execute the test — the same
  environmental-inability contract as a missing toolchain — and the test still executes on hosts
  without SAC. A block is never a silent pass, and never a test failure. Do NOT work around SAC by
  perturbing the artifact's hash — and do NOT rewrite or restructure a test program's content to
  dodge the classifier's false positive; both are reputation bypasses and out of bounds. SAC has no
  per-file or per-directory exclusion mechanism (Defender exclusions do not apply to it) and can
  only be turned off wholesale by the user, irreversibly. To execute the affected tests, the
  legitimate options are: run the gate on a host without Smart App Control, sign the test
  assemblies with a certificate SAC trusts, or have the user turn SAC off.
- When the modern toolchain (ilasm + dotnet host, discovered per the contract below) is missing,
  box tests SKIP via a JUnit 5 assumption before compiling; provision the toolchain with
  `compiler/ir/backend.dotnet/tools/provision-dotnet-toolchain.ps1`. The ilText suite never skips
  (it needs no toolchain) and stays on the NET_FRAMEWORK default so its goldens' `.module`
  directives are unchanged.
- The dotnet-owned box corpus lives in `compiler/testData/codegen/dotnet/box/`; a few borrowed JVM
  box files are additionally registered by pattern in `TestGeneratorForFir2IrTests.kt`.

## Target selection (`-Xdotnet-target`)

- `-Xdotnet-target={netframework|net}` (default `netframework`) selects the runtime flavor of the
  produced executable, carried as the `DotNetTarget` enum in `DotNetConfigurationKeys.TARGET`.
  Invalid values are a `COMPILER_ARGUMENTS_ERROR` from `DotNetConfigurationUpdater`.
- The target changes ONLY output packaging and assembler discovery, never the IL text: the emitted
  `.assembly extern mscorlib` is valid on both runtimes (verified), so ilText goldens are
  target-independent (apart from the `.module` directive naming the actual artifact file).
- `netframework`: `-d foo.exe` → Framework ilasm (`ILASM` env, PATH, then
  `C:\Windows\Microsoft.NET\Framework*\v4.0.30319\ilasm.exe`) assembles a directly runnable `.exe`.
- `net`: both `-d foo.exe` and `-d foo.dll` are executable requests; the artifact is always
  `foo.dll` plus `foo.runtimeconfig.json` (an `.exe` request is remapped to `.dll` with an INFO
  diagnostic naming the actual artifact — modern ilasm-produced exes have no self-hosting story,
  the runnable form is `dotnet exec foo.dll`). The modern ilasm is discovered per the contract
  below; when it is missing, a single ERROR names the provisioning script. The runtimeconfig
  framework version is the `<major>.<minor>.0` family of the newest runtime under the discovered
  dotnet root's `shared/Microsoft.NETCore.App` with `rollForward: LatestMinor` (fallback
  `net10.0`/`10.0.0` when no host is found — the dll may be run on another machine).
- Both ilasm flavors are invoked with the same legacy flag spelling
  (`/nologo /quiet /exe|/dll /output:...`); the modern ilasm accepts it (probed on 10.0.9).
- CLI tests for the flag only cover toolchain-independent behavior (invalid value error, `.il`
  output with `target=net`); an assembled-artifact CLI golden would fail on machines without the
  provisioned toolchain.

## Modern .NET toolchain

- A durable, per-user (no admin) modern toolchain lives at `%LOCALAPPDATA%\kotlinc-dotnet\toolchain\`:
  `dotnet\dotnet.exe` (.NET runtime, pinned 10.0.9) and `ilasm\ilasm.exe` (self-contained modern
  CoreCLR assembler from the NuGet package `runtime.win-x64.microsoft.netcore.ilasm`, pinned 10.0.9).
  Provision or repair it with the idempotent script
  `compiler/ir/backend.dotnet/tools/provision-dotnet-toolchain.ps1` (parameters: `-InstallDir`,
  `-RuntimeVersion`, `-IlasmVersion`).
- Discovery contract (for the assembler/test runner; implement lookups in this order):
  1. `KOTLIN_DOTNET_ILASM` — full path to an `ilasm.exe`; takes precedence for the assembler.
  2. `KOTLIN_DOTNET_ROOT` — a toolchain root containing `dotnet\` and `ilasm\` subdirs
     (i.e. `<root>\ilasm\ilasm.exe`, `<root>\dotnet\dotnet.exe`).
  3. The default durable location above.
  4. Legacy .NET Framework ilasm (`C:\Windows\Microsoft.NET\Framework64\v4.0.30319\ilasm.exe`).
- The modern ilasm accepts both the legacy flag spelling (`/nologo /quiet /exe /output:x.exe`) and
  the modern one (`-DLL -OUTPUT=x.dll`; quote `-OUTPUT=...` when calling from PowerShell, which
  otherwise mangles the `=`). It reads UTF-8 IL with or without BOM, so existing emitter output
  assembles unchanged.
- Running an assembled dll on CoreCLR requires `x.runtimeconfig.json` next to it:
  `{"runtimeOptions":{"tfm":"net10.0","framework":{"name":"Microsoft.NETCore.App","version":"10.0.0"}}}`
  then `<toolchain>\dotnet\dotnet.exe exec x.dll`. Without the runtimeconfig, `dotnet exec` fails
  with a hostpolicy.dll error. Prefer dll + `dotnet exec` over direct `.exe` execution: the signed
  `dotnet.exe` host avoids the Smart App Control blocking of freshly assembled unsigned exes.
- Known semantic delta vs. .NET Framework: raw .NET formatting renders `-0.0` as `"-0"` on CoreCLR
  but `"0"` on Framework. The backend's own `DoubleToString` helper makes this moot for compiled
  Kotlin programs, but raw formatting probes differ.
