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
  special case). General `==` between two class instances stays rejected until an Any.equals
  model exists.
- Class model (JVM precedent: the CLR has real classes, so like `JvmLoweringPhases` there is NO
  vtable/class lowering machinery): only top-level, final, non-generic plain classes whose sole
  supertype is `kotlin.Any` pass the shape gate (`DotNetIlEmitter.checkClassShapeSupported`).
  Rejection granularity is always the whole class — a failing member (signature, body, or IL
  method- or field-identity clash) removes the entire class from the module so no call site can
  resolve to a partial class, and the removal cascades through the type mapper to every user of
  the class.
- Properties use the CLR's first-class property model: private backing fields, `get_x`/`set_x`
  `specialname` accessor methods, and a `.property` metadata block binding them (spellings
  ilasm-probe-verified) — a stated deviation from the JVM's `PropertiesLowering`, which the CLR
  makes unnecessary. Because of the accessor mangling, the member pre-pass rejects (whole-class)
  IL method-identity clashes such as `val x` vs a user-declared `fun get_x(): Int` — ilasm fails
  on the duplicate method declaration (probed on 10.0.9); the JVM analogue is the frontend
  `PLATFORM_DECLARATION_CLASH` diagnostic for `val x` vs `fun getX()`.
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
- Generics stance: the type representation stays structural so that future generics can target real
  CLR reified generics (Roslyn shape), not JVM-style erasure. Unsupported generic shapes are
  rejected, never erased.
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
