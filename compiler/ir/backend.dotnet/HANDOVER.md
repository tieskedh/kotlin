# Handover — Kotlin/.NET backend, interim development

Written 2026-07-14 and updated 2026-07-15 for the next agent working on the `dotnet` branch
(any model/harness).
**Read `AGENTS.md` in this directory FIRST — it is the binding design law.** This file only adds
session state, process, and a curated task menu. Keep both files updated as you work.

## Branch state

- Branch `dotnet`; latest functional work is "[DotNet] Establish runtime assembly boundary",
  based directly on
  `origin/master` (`995cf26a0`, rebased 2026-07-13).
  HANDOVER/AGENTS updates that describe a feature belong in that functional commit; do not create
  handover-only follow-up commits.
- Full DotNet suite: **380 tests, 0 failures, 0 errors, 0 skips** across 8 XML suites
  (`FirLightTree`/`FirPsi` × IlText/Box(+Strings,Typealias)); the separate generated CLI suite is
  **10 tests, 0 failures, 0 errors, 0 skips**.
- Landed feature slices, in order: executing box gate, final classes, exceptions/try-catch-finally,
  top-level properties/objects/companions, class inheritance, interfaces, hybrid nullability
  (`Nullable<T>` in exact positions, box-collapse at `Any?` boundaries), reified generics stage 1,
  exhaustive Boolean/Boolean? `when` without source `else`, primitive-array CLR vectors and
  indexed loops, constrained generics stage 2, invariant generic arrays stage 3, generic
  interfaces and declaration-site variance stage 4, generic member functions stage 5, generic
  class inheritance stage 6, abstract and sealed classes, abstract interface redeclarations,
  interface delegation through FIR's frontend-owned forwarding artifacts, named nested classes
  with static-style JVM semantics and real CLR nested metadata, nested class modality and
  module-local nested-base inheritance, and recursive singleton initialization for named nested
  objects and companions of ordinary nested classes, plus nested all-abstract interfaces and
  static-style declarations inside interfaces, objects, and companions.
  Inner classes use the common/JVM explicit-outer representation on CLR nested metadata. Below a
  generic immediate outer, copied/remapped `Inner<own, outer...>` parameters provide the nested
  type's independent CLR slot space across inheritance, multi-level capture, and construction.
  Named local classes and anonymous object expressions now use common closure conversion/popup
  with immutable value/receiver and duplicated type-parameter captures. Anonymous base arguments
  are lifted at the expression call site. Explicit named local functions now lift to static
  facade/owner methods with parameterized captures; mutable captures and callable objects remain
  separate boundaries.
  Each has a design bullet in `AGENTS.md` — the bullets are accurate; trust but verify.
- Interim continuation landed `dff037283`: JVM-shaped intrinsic registration for fir2ir's
  `noWhenBranchMatchedException`, originally emitting `[mscorlib]InvalidOperationException`
  instead of Roslyn's modern-only `SwitchExpressionException`. `whenprobe_s1` settled the
  assembly-scope/Framework-compatibility decision; `whenprobe_s2` forced the exact golden's
  otherwise unreachable fallthrough with raw CLR `bool` value `2`, including a prior value on
  the evaluation stack. The new ilText/box pins cover both parser variants, statement/value
  positions, mapped catch handling, generic results and the non-first-argument stack shape. The
  later reviewed-semantic-gaps repair changes the temporary cross-target type to `System.Exception`
  so the synthetic `NoWhenBranchMatchedException` no longer acquires the false sibling edge
  `is IllegalStateException`.
- Interim continuation landed `b9fed511e`: `cli/dotnet` now generates into its own top-level
  `DotNetCliTestGenerated.java`, so upstream regeneration of the shared `CliTestGenerated.java`
  no longer conflicts with DotNet test data. The same 10 tests pass in the new suite, and an
  explicit smoke filter preserves their selection after the nested-to-top-level move (Smoke-mode
  dry-run discovers all 10). The fresh backend suite remains 270/0/0/0.
- Interim continuation landed `85e7df603`: `IntArray`, `LongArray`, `DoubleArray`,
  `BooleanArray`, and `CharArray` map to native CLR vectors. JVM-shaped registry intrinsics cover
  unary construction, literal factories, `size`, `get`, and `set`; direct `for` loops use the
  backend.common indexed-get shape. `arrprobe_s1` verified exact signatures/opcodes and runtime
  faults on modern CoreCLR and .NET Framework. A negative-size guard prevents CLR
  `OverflowException` from creating a false Kotlin `ArithmeticException` catch edge. Literal and
  indexed operands spill to locals because CLR protected regions require an empty entry stack.
  Nullable/object/generic storage and array identity equality work; generic `Array<T>`, unsupported
  scalar arrays, initializer constructors, spreads, escaping iterators, and copy/content helpers
  reject. Contrary to the old task-menu guess, no fake-stdlib declarations were needed: fir2ir
  already supplies the primitive-array builtins and `*ArrayOf` calls.
- Interim continuation landed `1767fe982`: supported direct, non-null, non-generic module-local
  class and all-abstract interface bounds now remain on CLR generic method/class metadata and on
  the backend's structural `!n`/`!!n` type. Bound virtual/interface calls spill receiver and
  arguments, reload the receiver address, and emit `constrained.` immediately before `callvirt`;
  non-virtual class members and bound/`Any` widening use `box !n`/`!!n`. The spills preserve source
  order and the CLR empty-stack rule around argument-side `try`. The bound walk also recovers an
  instantiated generic declaring owner inherited by a non-generic bound. `genconstraintprobe_s1`–
  `_s2` verified metadata, interface/class dispatch, boxing identity, and external value-type
  interface instantiations on CoreCLR 10.0.9 and .NET Framework 4.8; the final positive golden
  assembles under both ILAsm versions and executes on Framework. New ilText/rejection/box pins run
  under both FIR parser variants. Nullable, generic-instantiation, type-parameter, builtin, mapped,
  unavailable, equality/Any-member, unconstrained-widening, variance, and `T?` shapes still reject.
- Interim continuation landed `eb3651083`: invariant `Array<E>` maps to a structural CLR vector
  for reference-shaped or open `!n`/`!!n` elements. The JVM-shaped registry owns `arrayOf`,
  `emptyArray`, reference-element `arrayOfNulls`, `size`, `get`, and `set`; direct `for` loops
  reuse the indexed lowering. Literal/get/set operands spill for protected-region safety. The
  structural kind stays distinct from `PrimitiveArray`, so backend assignability never admits CLR
  covariance. Concrete primitive elements reject because CLR would collapse `Array<Int>` and
  `IntArray` to the same `int32[]` ABI; projections, nullable value elements/`Array<T?>`, nested
  arrays, initializer lambdas, spreads, iterator escape, casts, and copy/content helpers also stay
  out. `genarrayprobe_s1` verified `T[]` metadata, typed element opcodes for reference and value
  instantiations, bounded dispatch, and the CLR covariant-store check on CoreCLR 10.0.9 and
  Framework 4.8. Both final goldens assemble and execute on both runtimes. The feature also closes
  the existing main-detector gap: `main(args: Array<String>)` now emits the valid CLR
  `.entrypoint` `main(string[])` shape.
- Interim continuation landed `934f50a7c`: top-level all-abstract generic interfaces now emit
  as real reified CLR interfaces. Their `out`/`in` parameters preserve `+`/`-` metadata; full
  open, closed, transitive, and permuted interface instantiations remain in the structural
  supertype graph and on every `implements`/member-owner token. Generic classes may implement
  supported interface instantiations. Assignability applies CLR covariance/contravariance only
  when both differing arguments are statically reference-shaped; exact value instantiations
  remain supported, while value/open-parameter variant conversions, use-site projections/stars,
  nullable type-parameter slots, and unsupported bounds reject.
  `genifaceprobe_s1` verified metadata, constraints, interface inheritance, and dispatch on
  CoreCLR 10.0.9 and .NET Framework 4.8. Both new goldens assemble under both ILAsm versions;
  the positive golden and expanded box test execute on both runtimes. The final FIR suite is
  294/0/0/0 and the generated CLI suite remains 10/0/0/0.
- Interim continuation landed `3bcbe6c1f`: non-inline generic methods are now supported on every
  otherwise-supported class, object, companion, and all-abstract interface. Generic owners keep
  their `!n` space independent from a method's `!!n` space across declarations, calls, nested
  generic owner tokens, inherited interface views, and instantiated generic base overrides/super
  calls. The override return-type pre-pass now identity-substitutes open method parameters while
  substituting only the generic owner view; the new test exposed the old path's internal crash.
  Inline/reified methods, nullable type-parameter slots, generic/type-parameter bounds, and generic
  properties remain rejected. `genmemberprobe_s1` verified class/interface methods, combined
  owner/method parameters, constraints, and nested companion owner tokens on CoreCLR 10.0.9 and
  Framework 4.8. Both exact goldens assemble under both ILAsm versions; the positive golden runs
  identically on both runtimes. Runtime pins include inherited virtual methods satisfying generic
  interface slots, constrained calls, arity overloads, objects, companions, member extensions,
  nullable method instantiations, and generic virtual/super dispatch. The final FIR suite is
  300/0/0/0 and the generated CLI suite remains 10/0/0/0.
- Interim continuation landed `f719d1206`: supported generic classes may now extend module-local
  generic bases through mapped closed, open, permuted, nested, generic-array, concrete-nullable,
  fixed, and constrained instantiations across arbitrary chains. Full base tokens remain in the
  prelinked structural graph and are recursively substituted at each hop, so constructor calls,
  overrides/super calls, inherited generic methods, inherited interface slots, and open base or
  interface upcasts recover the exact declaring-owner view. Invalid base arguments and evicted
  bases reject the whole derived chain while unrelated valid instantiations survive.
  `geninheritprobe_s1` verified multi-hop tokens, constructors, overrides, generic methods,
  interfaces, and constraints on CoreCLR 10.0.9 and .NET Framework 4.8. Both new goldens assemble
  under modern and Framework ILAsm; the positive golden executes identically on both runtimes.
  The final FIR suite is 306/0/0/0 and the generated CLI suite remains 10/0/0/0.
- Interim continuation landed `11cfd104b`: top-level plain abstract and sealed classes now emit
  as ordinary CLR `abstract` types; Kotlin sealing remains frontend-enforced. New abstract
  functions/accessors use `newslot abstract virtual`, abstract base overrides reuse their slot
  with `abstract virtual`, and open/concrete members, constructors, state, companions, generic
  owners/methods, constraints, and mapped inheritance keep their existing machinery. A pure
  abstract interface obligation may remain only as a fake override on an abstract owner with no
  emitted method; concrete descendants introduce or reuse the implementation slot, while
  concrete owners remain strict. `abstractprobe_s1` verified metadata, constructor chains,
  re-abstraction, interface mapping, and generic substitution; `abstractprobe_s2` verified both
  new-slot and slot-reuse concrete implementations after a methodless abstract interface carrier.
  Both probes and the exact positive golden assemble and execute identically on CoreCLR 10.0.9
  and .NET Framework 4.8. Runtime pins also cover mutable abstract properties, abstract generic
  calls through abstract views, generic sealed owners, constrained dispatch, companion factories,
  and state. The final FIR suite is 310/0/0/0; the generated CLI suite remains 10/0/0/0.
- Interim continuation landed `e748011b0`: an abstract interface function or accessor may now
  redeclare an inherited member, emitting another `newslot abstract virtual` slot. One class
  member with an exact signature fills the original and every redeclared slot, including repeated
  and diamond redeclarations, mutable properties, independent generic methods, composed generic
  owners, and implementations inherited virtually from a base class. The existing mapped-return
  pre-pass still rejects covariant redeclarations whose CLR return signatures differ, while
  nullability covariance mapping to the same IL type survives. `ifaceredeclareprobe_s1` and both
  exact goldens assemble and execute identically on CoreCLR 10.0.9 and .NET Framework 4.8.
  The preceding `dimprobe_s1` audit found the next hard boundary: modern ILAsm/CoreCLR runs a
  Default Interface Method, but Framework 4.8 ILAsm rejects a non-static interface method body,
  so DIM and `super<I>` remain loudly unsupported unless the runtime floor is deliberately raised.
  The final FIR suite is 316/0/0/0; the generated CLI suite remains 10/0/0/0.
- Interim continuation landed `67ac4b6c2`: FIR interface delegation now renders through the
  ordinary member pipeline. Constructor-property delegates reuse their private backing field;
  plain parameters, expressions, bounded type parameters, and `var` delegates use FIR's private
  `$$delegate_n` field, initialized after the base constructor and before later member state.
  Forwarding composes with functions/accessors, mutable properties, generic owners and methods,
  multiple delegates, inherited interface redeclarations, explicit overrides, constrained type
  parameters, and inherited virtual class implementations. Reassigning a `var` keeps forwarding
  to the initially captured delegate, matching JVM behavior. An unavailable delegated interface
  still cascades whole-class, including mixed supported/unsupported delegation.
  `delegationprobe_s1` and both exact goldens assemble and execute identically on CoreCLR 10.0.9
  and .NET Framework 4.8; runtime pins cover initialization order, one-time capture, constrained
  calls, and base/interface dispatch. The final FIR suite is 320/0/0/0; the generated CLI suite
  remains 10/0/0/0.
- Interim continuation landed `e5721e916`: FINAL named classes may now nest recursively inside
  plain classes as real CLR nested metadata types, following the JVM's static-nested semantics.
  Each declaration has its own simple arity-suffixed identity and independent generic parameter
  space, so named classes inside generic outers do not capture the outer's `!n` slots. Public,
  private, internal, and protected map to `nested public/private/assembly/family`; arbitrary depth,
  forward sibling references, generic nested classes, top-level base/interface links, and a direct
  companion alongside named nested siblings all compose through the existing member machinery.
  That slice initially made registration, rendering, and eviction operate on the whole top-level
  class family. The later narrow-eviction repair keeps recursive rendering and exact descendant
  attribution while removing only the failing metadata subtree and actual dependents.
  `nestedprobe_s1` verified names, independent
  generics, depth, visibility, construction, and member/field references; `nestedprobe_s2` verified
  CLR nested/enclosing access behavior. Both probes and both exact goldens assemble and execute
  identically on CoreCLR 10.0.9 and .NET Framework 4.8; the runtime box test covers generic value
  instantiations, visibility paths, inheritance/interface dispatch, forward references, and
  companion coexistence. That slice deliberately left companions OF ordinary nested classes and
  named nested objects rejected: the then-current lowering pipeline did not synthesize their
  `.cctor`, and the adversarial first attempt exposed a null singleton field before that output
  could be pinned. The later `1f43c5a4b` slice closes that boundary. The fresh FIR suite at this
  point was 326/0/0/0; the generated CLI suite remained 10/0/0/0.
- Interim continuation landed `7f1f9acc6`: named nested classes now support the same final, open,
  abstract, and sealed modality set as top-level classes. After the nested accessibility prefix,
  final emits CLR `sealed`, open omits it, and abstract/sealed emit CLR `abstract`. The inheritance
  gate at that slice distinguished every recursively declared module class from the interface set,
  which was still top-level-only then, so a top-level or nested class could extend any module-local
  nested class. This
  covers forward siblings, a nested metadata parent, deeper family members, independent generic
  bases under a generic outer, top-level-to-nested links, and cross-family links. Base resolution
  remains live: a nested base evicted during the member pre-pass takes both top-level and nested
  dependent families down in the render fixpoint with carried diagnostics. `nestedprobe_s3`
  verified every modality/base token and runtime dispatch shape; the probe and exact positive/
  rejection goldens assemble and run identically on CoreCLR 10.0.9 and .NET Framework 4.8. The
  focused six-test gate and fresh full FIR suite have zero skips/failures; the final baseline is
  330/0/0/0, and the generated CLI suite remains 10/0/0/0.
- Interim continuation landed `1f43c5a4b`: `DotNetStaticInitializersLowering` now visits every
  recursively declared class in postfix order, matching the common/JVM
  `ClassLoweringPass.runOnFilePostfix` precedent. Companions of non-generic ordinary nested classes
  receive their singleton field initialization in the immediate owner's `.cctor`; named objects
  inside plain classes receive an `INSTANCE` initializer in their own `.cctor`.
  A companion declared directly in a generic container stays rejected because its field would be
  per constructed owner. A named object is safe there because its `INSTANCE` lives on its own
  independently non-generic type; the reviewed-semantic-gaps repair lifts that over-broad gate.
  The gate at that slice also rejected declarations inside objects/companions/interfaces. The later
  narrow-eviction repair makes a nested singleton initializer that loses its callee evict its
  immediate singleton owner subtree while independent metadata ancestors survive.
  `nestedprobe_s4` verified direct/deep companions, a named object, a non-generic owner below a
  generic ancestor, open/abstract owners, laziness, and one-time construction; CoreCLR 10.0.9 and
  .NET Framework 4.8 both printed `0,1,1,1,2,3,4,5,6,6`. The exact positive and rejection goldens
  assemble under both ILAsm versions and execute identically on both runtimes. The fresh full FIR
  suite at that point was 334/0/0/0 across eight XML files; the generated CLI suite remained
  10/0/0/0.
- The reviewed-semantic-gaps repair corrects five independently audited edges: exhaustive `when`
  now throws `System.Exception` rather than becoming catchable as `IllegalStateException`;
  `main(Array<String>)` wins over a same-file parameterless overload; nullable-primitive identity
  against null uses the existing HasValue path; the facade clash index revalidates entries after a
  backing-property group eviction; and named objects directly below generic metadata parents are
  supported while generic-owner companions remain rejected. The fresh full suite is 336/0/0/0.
  All five changed/new exact goldens assemble with modern 10.0.9 and Framework 4.8 ILAsm; the
  Framework executions exit 0, and the FIR box suites provide the CoreCLR runtime pins.
- The narrow-nested-eviction repair replaces top-level-family rejection with the smallest sound
  metadata boundary. Shape-gate and member/render failures of an ordinary nested declaration
  remove only that declaration and its descendants; valid parents and siblings render without the
  omitted nested block. The live type/function maps still remove real users, including derived
  classes. Companion failures remain owner-sensitive because the immediate owner contains the
  singleton field and `.cctor`. `ilText/nestedClassesRejected.kt` pins independent parents and
  siblings, the surviving `BrokenFamily.Good` call path, derived-class cascades, deepest nested
  render attribution, and singleton-owner eviction. The final 336-test FIR filter is clean across
  both parsers. All three changed rejection goldens assemble under modern 10.0.9 and Framework
  4.8 ILAsm; their Framework executions exit 0 with output `rejected`, `rejected`, and `1,17`.
- The nested-interface continuation follows JVM static-nested semantics while preserving the
  Framework-compatible all-abstract boundary. `nestedifaceprobe_s1`–`_s3` verified nested
  interface flags and visibility, interface-owned nested classes/interfaces/objects/companions,
  independent generic parameter spaces, forward references, inheritance, implementation, and
  dispatch on modern CoreCLR 10.0.9 and .NET Framework 4.8. Named interfaces may now nest under
  any supported class/interface/object/companion; interfaces may contain static-style named
  classes, interfaces, objects, and a companion. A non-generic interface owns its companion field
  and `.cctor`; a generic interface companion remains rejected because CLR statics are per
  constructed owner, while a named object remains safe because it owns `INSTANCE`. Default
  interface bodies/private callable members remain rejected at the Framework floor. Nested
  declaration failures remove only their subtree and real dependents; only a failure OF a
  companion promotes to its immediate owner. `ilText/nestedInterfaces.kt`,
  `ilText/nestedInterfacesRejected.kt`, and `box/nestedInterfaces.kt` pin the feature and the
  adjacent rejection edges. Both new goldens and both affected rejection goldens assemble under
  modern and Framework ILAsm; Framework execution prints
  `0,1,2,1,three,2,4,3,5,object,3`, `13`, `1,17`, and `rejected` respectively. The fresh full
  DotNet suite is 342/0/0/0 across eight XML files.
- The object/companion-owner continuation closes the remaining static nested-parent gate: named
  classes and objects may now nest recursively inside objects and companions, using the same
  independent generic spaces, visibility mapping, inheritance/interface links, narrow eviction,
  and postfix singleton initialization as other named nested declarations. `nestedownerprobe_s1`
  verified direct/deep class/object placement, singleton `.cctor`s, and an independently generic
  child below an object under a generic metadata ancestor. `nestedownerprobe_s2` verified forward
  nested inheritance, virtual dispatch, a private nested type's public constructor from its owner,
  and nested→enclosing private-field access. It also reconfirmed the established CLR boundary:
  enclosing→nested private MEMBER access throws `MethodAccessException`, so only companion-private
  members retain IL-`assembly` widening. Both probes assemble and run identically on CoreCLR 10.0.9
  and Framework 4.8. `ilText/nestedObjectDeclarations.kt`,
  `ilText/nestedObjectDeclarationsRejected.kt`, and `box/nestedObjectDeclarations.kt` pin runtime
  laziness, access, generic independence, visibility, forward links, companions below object
  owners, and smallest-subtree failures. Both new and both affected goldens assemble under modern
  and Framework ILAsm; Framework outputs end at singleton count `6`, print `10` for rejection
  survivors, and preserve the existing `1,17`/`rejected` outputs. The fresh full DotNet suite is
  348/0/0/0 across eight XML files.
- The inner-class continuation reuses the common/JVM three-phase pipeline before initializer
  merging: synthesize a private `this$0` field plus leading constructor argument, rewrite
  outer-`this` reads into field chains, and rewrite constructor calls. `innerprobe_s1` proves the
  common pre-base-call outer-field store on both runtimes; `innerprobe_s2` covers inner inheritance,
  virtual dispatch, inner-owned generics, multi-level capture, and outer-dependent initialization.
  The committed tests additionally pin private/forward inner declarations, distinct outer
  instances, a class with only secondary constructors, delegated secondary construction, and
  narrow rejection of a bad inner member. An immediate generic outer stays shape-gate-rejected:
  the nested type cannot implicitly use its metadata parent's `!n` space, so support requires a
  later duplicate-and-substitute generic model. `ilText/innerClasses.kt`,
  `ilText/classShapeRejected.kt`, `ilText/nestedClassesRejected.kt`, and `box/innerClasses.kt`
  cover both parsers. All three changed goldens assemble under modern 10.0.9 and Framework 4.8
  ILAsm; the positive golden runs identically on CoreCLR and Framework with output
  `12,17,true,generic,10,22,19,13,15,13,22`. The fresh full DotNet suite is 352/0/0/0
  across eight XML files.
- The named-local-class continuation reuses common name invention, closure conversion, and popup
  before the inner/initializer phases, but deliberately enters the pipeline only for bodies with
  named locals and no anonymous object or explicit local function. Top-level-function locals emit
  as module-private top-level types; member/initializer locals emit as private nested types with
  public metadata constructors. Immutable parameter/local/receiver captures become constructor
  inputs and private fields as needed. Captured type parameters are duplicated on the local type,
  including a generic-owner receiver typed through the local's independent slot. Invented names
  use readable enclosing paths and add `$1` only on a real collision; the emitter also reserves
  user metadata names first. Mutable captures reject precisely because shared-variable boxing is
  absent, while anonymous objects and local-function mixtures remain wholly unlowered.
  `localprobe_s1` verifies facade construction of a private top-level local/captured field;
  `localprobe_s2` verifies a private nested local under `Outer<T>` with a duplicated generic slot.
  Both probes and both exact goldens assemble and run identically on CoreCLR 10.0.9 and Framework
  4.8. `ilText/localClasses.kt`, `ilText/localClassesRejected.kt`, and `box/localClasses.kt` pin
  top-level/member/initializer locals, same-named overload locals, immutable captures, generic
  function/owner captures, inheritance/interface dispatch, and the adjacent rejection boundaries.
  Positive output is `15,7,9,21,generic,owner,12`; the rejection survivor prints `17`. The fresh
  full DotNet suite is 358/0/0/0 across eight XML files.
- The anonymous-object continuation inserts a CLR-neutral adaptation of the JVM
  `AnonymousObjectSuperConstructorLowering` between name invention and closure conversion, then
  admits anonymous object expressions to the same local-class pipeline. Complex and
  named/reordered base arguments are evaluated into call-site temporaries and passed separately
  from immutable captures; object initializers retain their source position after the base call.
  Bare objects, interface implementations, supported module-local and generic base classes,
  top-level/member/init property contexts, generic-function and generic-owner captures, recursive
  object expressions, fresh-instance identity, and bodies mixing named and anonymous classes all
  compose. Lifted types remain module-private top-level or private nested metadata with public
  metadata constructors. Mutable captures still reject precisely, explicit-local-function
  mixtures remain wholly unlowered, and unsupported exception bases reject only their subtree and
  real users. `anonprobe_s1` verifies an inaccessible captured interface implementation;
  `anonprobe_s2` verifies captured-field storage plus a lifted base argument. Both produce `42` on
  CoreCLR 10.0.9 and Framework 4.8. `ilText/anonymousObjects.kt`,
  `ilText/anonymousObjectsRejected.kt`, and `box/anonymousObjects.kt` pin both parsers. Positive
  output is `13,6,9,42,generic-super,generic,owner,12,11,9,true,10`; the rejection survivor prints
  `23`. The fresh full DotNet suite is 364/0/0/0 across eight XML files.
- The explicit-local-function continuation adds common `InventNamesForLocalFunctions` immediately
  before the shared closure conversion/popup pass and admits only named local functions, keeping
  lambdas and function references wholly unlowered. Lifted functions on file facades are IL
  `assembly static` so lifted sibling types can call them (`localfunprobe_s3`); class-owned locals
  are `private static`. Immutable value, extension-receiver, and owner-receiver captures become
  parameters. Captured type parameters precede own parameters in the independent `!!n` method
  space. Static calls under generic classes now instantiate the containing type from a captured
  owner argument or the current open `Owner<!n>` view (`localfunprobe_s2`), instead of emitting an
  invalid bare generic owner. Direct and nested recursion, initializer locals, generic function
  and class scopes, extension locals, and named/anonymous local-class callers compose. User
  metadata names have priority; colliding generated methods alone receive the smallest `$n`
  suffix. Mutable captures reject precisely, while lambda/reference mixtures remain at the
  existing function boundary. `localfunprobe_s1`–`_s3` all print `42` on CoreCLR 10.0.9 and
  Framework 4.8. `ilText/localFunctions.kt`, `ilText/localFunctionsRejected.kt`, and
  `box/localFunctions.kt` pin both parsers. Positive output is
  `15,-1,10,10,7,9,generic,owner,no-owner,12,12,-2,12,13`; the rejection survivor prints `29`.
  The fresh full DotNet suite is 370/0/0/0 across eight XML files.
- The generic-outer-inner continuation inserts `DotNetInnerClassTypeParametersLowering` before the
  existing common/JVM inner pipeline. It appends the immediate outer's complete parameter list
  after the inner's own parameters, remaps the inner subtree, and processes outer-first, yielding
  positional shapes such as `Second<V, U, T>`. The copy map also types the later `this$0` field and
  leading constructor parameter. A narrow pre-call repair fills the source-implicit outer type
  arguments on ordinary, super, and `this(...)` constructor calls; a post-body repair substitutes
  synthetic multi-level outer-field reads through their instantiated receiver. Non-generic inners
  remain on the unchanged path. `genericinner_s1` verifies basic `Inner<U,T>` construction and
  outer access; `_s2` proves duplicate parameter names are legal; `_s3` verifies three-level
  ordering plus a generic base link. All assemble/run on CoreCLR 10.0.9 and Framework 4.8, with
  `_s1` printing `item,42` and `_s3` printing `42`. `ilText/genericInnerClasses.kt` and
  `box/genericInnerClasses.kt` pin both parsers, generic factories, inner inheritance, generic
  non-inner bases, outer identity, duplicate names, multi-level field chains, and delegated
  secondary construction. The golden prints
  `outer,true,7,outer,9,11,outer,13,outer,outer,middle,17,outer,19` identically on both runtimes.
  The fresh full DotNet suite is 374/0/0/0 across eight XML files.
- The runtime-foundation continuation deliberately precedes callable lowering. Every assembled
  executable now carries an AssemblyRef to and is emitted beside `Kotlin.Runtime.dll`, built from
  one TFM-neutral IL definition by the selected target's ILAsm. ABI-major-1 identity is fixed as
  culture-neutral, unsigned `Kotlin.Runtime, Version=1.0.0.0, PublicKeyToken=null`; compatible
  releases do not change AssemblyVersion, strong naming requires a new identity/major, and the API
  floor stays inside .NET Framework 4.8 `mscorlib` while remaining CoreCLR-compatible. Namespace
  ownership is `Kotlin` for language ABI, `Kotlin.Runtime` for runtime services, and
  `Kotlin.Runtime.Internal` for compiler support. The initial public type was a deliberately
  memberless `Kotlin.Runtime.RuntimeInfo` marker; the callable continuation below adds the first
  language ABI types without changing this assembly policy.
  `runtimeprobe_s1` resolved that type in consumers assembled by modern 10.0.9 and Framework 4.8
  ILAsm; all same-target and cross-runtime pairings ran, and both runtime binaries reported the
  same logical identity. Box infrastructure now requires both the sibling runtime and program
  AssemblyRef. The compiler reserves the runtime identity, omits a library runtimeconfig, and
  propagates ILAsm success so stale program artifacts cannot masquerade as fresh output.
- The callable continuation chose a physically erased Kotlin-owned ABI candidate rather than
  CLR-reified generic interfaces or permanent reference-only variance. `Kotlin.Runtime` now exposes
  non-generic `Kotlin.Function` plus fixed `Function0`/`Function1`/`Function2`, whose sole call
  slots are `object Invoke(...)`. Logical Kotlin function arguments stay in compiler IR (and must
  later be serialized in Kotlin metadata), while CLR signatures erase by arity. Primitive,
  nullable-primitive, and open-generic values box at Invoke exit and unbox at entry; references
  cast at entry; Unit returns the real `Kotlin.Unit.INSTANCE`. Consequently both result covariance
  and parameter contravariance, including value types, are instruction-free reference copies and
  preserve `===`. Non-capturing lambdas and direct top-level references lower to local callable
  classes, cache one instance through a generated `.cctor`, and invoke through the runtime
  interface. Direct references consumed as FunctionN drop FIR's otherwise retained KFunctionN
  view; actual KFunction-typed storage still rejects. Erased overload collisions use the existing
  method-identity gate. `callableabi_s2` assembled the runtime/consumer with modern 10.0.9 and
  Framework 4.8 ILAsm; all four same/cross-runtime pairings ran. Focused PSI/LightTree IL and
  CoreCLR box coverage passes for Function0/1/2, direct references, Unit, singleton reuse,
  reference/value variance, Boolean, nullable Int, reference casts, open T, Function/Function<*>
  marker storage, extension receivers, explicit implementations, nullable callable storage, and
  overload-clash survival. Negative pins also cover captures, bound references, suspend callables,
  arity above 2, and inferred KFunction storage. Pins: `ilText/callableObjects.kt`,
  `ilText/callableObjectsRejected.kt`, and
  `box/callableObjects.kt`. Captures/bound references, KFunction metadata, suspend callables,
  arity above 2, delegates, Kotlin metadata serialization, and typed fast paths remain separate.
  The repo-local `docs/decisions/draft-adr-erased-callable-abi.md` records the decision drivers,
  rejected alternatives, costs, invariants, and the evidence required to promote or revise the
  draft; it is deliberately not presented as a public KEEP or an accepted Kotlin project ADR.
  Promotion requires three layers to be validated separately: the erased Kotlin identity/fallback,
  a measured exact-shape non-boxing execution path, and typed CLR export projections. Only ordinary
  Kotlin subtype upcasts must preserve identity; SAM/adapted references and foreign projections may
  allocate wrappers. Framework 4.8 is probe coverage for this POC, not a product-support promise.
  The final assembly sanity check also caught that non-executable ilText output referenced runtime
  callable types without declaring the runtime AssemblyRef. Header emission now derives that ref
  from the final post-eviction IL body, so both ILAsm versions assemble callable-bearing library IL
  without autodetection; executables keep the runtime-foundation ref unconditionally.
  The fresh full DotNet suite is 380/0/0/0 across eight XML files.
- The user requested continued autonomous feature work until explicitly stopped. The next repair
  and feature audits below have not yet landed.
- `git stash@{0}` holds a superseded partial implementation (object-boxing nullability, replaced
  by the hybrid model). It is droppable; do not build on it, do not touch it otherwise.
- `.claude/settings.json` contains `"worktree": {"bgIsolation": "none"}` — deliberate; leave it.
- `.gradle/codex-backend-dotnet.stop` is the ignored autonomous-work stop flag. It is currently
  `false`; set it to `true` to have the agent finish its current coherent feature, update this
  handover, and stop before starting another one.

## Hard rules (the ones that bit us; violations have all caused real damage)

1. **Probe first.** Any IL spelling not already golden-pinned must be verified by assembling and
   RUNNING an ilasm probe before it lands in codegen. Probe series naming: one series per feature
   (`statprobe`, `excprobe`, `objprobe`, `fieldprobe`, `inheritprobe`, `ifaceprobe`, `boxprobe`,
   `genprobe`, `genconstraintprobe`, `genarrayprobe`, `genifaceprobe`, `genmemberprobe`,
   `geninheritprobe`, `abstractprobe`, `dimprobe`, `ifaceredeclareprobe`, `delegationprobe`,
   `nestedprobe`, `nestedifaceprobe`, `nestedownerprobe`, `innerprobe`, `localprobe`, `whenprobe`,
   `arrprobe` are taken). Keep
   probe files OUT of the repo (use a temp dir).
2. **Diagnostics, not crashes.** Unsupported IR fails via `dotNetUnsupported()` with a specific
   message; rejection granularity is the class metadata subtree, the companion's immediate owner
   subtree, or the property group where AGENTS.md says so; live-map eviction cascades to actual
   dependents with chained reasons. Never emit fallback IL. Never let a
   construction reach
   ilasm-rejected or JIT-poisoned output: interface/generic mapping mistakes characteristically
   assemble CLEAN and throw `TypeLoadException`/`MissingMethodException` lazily — that is why box
   coverage per dispatch shape is mandatory, and why reviews must assemble suspicious goldens.
3. **State your precedent.** Every feature decision names the mature target it follows
   (JVM/Roslyn/JS/Native) or states the CLR-specific reason for deviating. This is a real design
   gate, not paperwork — it caught several wrong designs.
4. **Do not pin frontend-rejected shapes.** If the Kotlin frontend rejects a construction
   (e.g. `Int? == Long?` → EQUALITY_NOT_APPLICABLE), it cannot appear in an ilText test; document
   it in AGENTS.md instead. The test harness crashes on frontend diagnostics.
5. **Never** edit `*Generated.java` by hand; regenerate with
   `./gradlew :compiler:fir:fir2ir:generateTests` (the aggregate `generateTests` may pull in
   unrelated broken modules — stay scoped). Runners generate into `build/tests-gen` (not committed).
6. **Never** bypass or dodge Smart App Control (no hash perturbation, no content restructuring to
   dodge the classifier). A SAC-blocked box test SKIPs; that is the designed behavior.
7. **Git:** never push; never touch other branches; per-feature commits directly on `dotnet` with
   a detailed what/why/how message (look at `git log` for the house style) ending with your own
   `Co-Authored-By:` trailer. HANDOVER/AGENTS changes describing the feature belong in the same
   functional commit; unrelated non-functional changes stay separate. No worktrees — work directly
   in this checkout.
8. **Bootstrap syntax:** this repo compiles with a 2.5 bootstrap that uses name-based
   destructuring `[a, b]` (several dotnet files already do). Positional `(a, b)` over data-like
   classes will not compile.

## Rituals

- **Run tests:** `./gradlew :compiler:fir:fir2ir:test --tests "*DotNet*" -q` — but do NOT trust
  the quiet console alone. Verify from the JUnit XML:
  `compiler/fir/fir2ir/build/test-results/test/TEST-*DotNet*.xml` (sum tests/failures/skipped).
  SKIPs are acceptable only for SAC/toolchain reasons; failures never.
- **Update goldens:** add/modify the `.kt`, then run with `-Pkotlin.test.update.test.data=true`
  (QUOTE the whole `-P...` argument in PowerShell or it gets mangled), then READ the generated
  `.txt` critically — auto-generated goldens will happily pin broken output (this happened: a
  golden once pinned duplicate IL methods that ilasm rejects).
- **Before every commit:** fresh `--rerun` of the full suite + XML verification; `git status`
  shows only intended files (no scratch test data — a prior session leaked `zzrev*` files);
  if you added/changed goldens, assemble at least the new ones with ilasm as a sanity check.
- **Toolchain** (modern, pinned 10.0.9): `%LOCALAPPDATA%\kotlinc-dotnet\toolchain\ilasm\ilasm.exe`
  and `...\toolchain\dotnet\dotnet.exe`. Run a dll: put
  `{"runtimeOptions":{"tfm":"net10.0","framework":{"name":"Microsoft.NETCore.App","version":"10.0.0"}}}`
  in `x.runtimeconfig.json` next to it, then `dotnet.exe exec x.dll`. Repair with
  `compiler/ir/backend.dotnet/tools/provision-dotnet-toolchain.ps1`.
- **Self-review adversarially.** The process that repeatedly caught real bugs here: after
  implementing, actively try to break your own gates with hostile Kotlin constructions, assemble
  the emitted IL, and run dispatch shapes on the real CoreCLR. Budget real time for this.

## Task menu (recommended order)

1. **Move `<KotlinIl>` helpers into `Kotlin.Runtime.Internal`.** The runtime assembly and canonical
   callable boundary are now available; migrate shared helper IL without changing behavior or
   leaving a compatibility call back into generated modules. Preserve the Framework-4.8 API floor,
   probe every moved member reference on both runtimes, and keep helper methods compiler-internal.
2. **Add capturing callable objects and mutable reference cells.** Reuse the common/JVM closure
   conversion shape, keep the erased Invoke ABI unchanged, and separate immutable capture fields
   from Kotlin mutable local cells. Bound references belong in this slice; KFunction metadata,
   suspend callables, delegate adapters, and typed fast paths still remain later decisions.

## Known warts (fine to leave; do not "fix" casually)

- `x != null` on `Int?` emits a redundant double negation — semantically correct, cosmetic only.
- `emitTypeOperatorCall`'s outer-coercion tail is mostly dead code (interception happens earlier).
- The upstream sync recipe (shallow clone!) is in the commit `ea4c43a26` message and boils down
  to: `git fetch origin`, dry-run with
  `git merge-tree --write-tree --merge-base=<current-base> dotnet origin/master`, then
  `git rebase --onto origin/master <current-base> dotnet` where current-base = `995cf26a0`.
  Not urgent; skip unless asked.

## When handing back

Leave this file updated: what you landed (commit hashes), what you started but did not finish,
any new probe series/decisions, and anything you discovered that contradicts AGENTS.md.
