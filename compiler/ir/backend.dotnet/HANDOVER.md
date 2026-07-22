# Handover — Kotlin/.NET backend, interim development

Written 2026-07-14 and updated 2026-07-16 for the next agent working on the `dotnet` branch
(array content operations complete; explicit CLR function/property boundaries, nullability,
defaults, overload-aware function selection, immutable callable-provenance invocation, and the
bounded typed-argument callable capability implemented; bounded Kotlin property-reference values,
structural callable/property-reference Any semantics, and the coherent Function3/KMutableProperty2
continuation implemented; local delegated-property tokens, explicit user Iterator bridges, open
invariant array iterators, bodyless iterator subinterfaces, and Kotlin-owned Iterable
identity/bridges and the first physical target-stdlib assembly/ordinary Kotlin array iterator are
committed; bound stdlib metadata consumption, the explicit paired stdlib producer, and its
reproducibility boundary and default Kotlin-home discovery are committed, and the stronger
`netstandard2.0` platform-library profile is committed; ordinary user-library KLIB/DLL production
and bounded Kotlin cross-module consumption are implemented in the current feature slice).
**Read `AGENTS.md` in this directory FIRST — it is the binding design law.** This file only adds
session state, process, and a curated task menu. Keep both files updated as you work.

## Branch state

- Branch `dotnet`; latest committed functional work comprises runtime-helper ownership
  (`b54578fab`), capturing-callable state (`131161ca5`), callable-reference metadata
  (`fb6d43448`), the System.Object Any foundation (`4a78533ad`), and the hybrid Kotlin/CLR
  exception-identity foundation (`a4a862e45`) and exact source-visible NumberFormatException
  identity (`eb1fae21e`) and exact Kotlin Error identity (`b3fc89984`), followed by the
  RuntimeException migration-gate decision (`acde56d80`) and the bounded top-level data-class and
  masked-default implementation (`2660cc58e`) and named nested data classes (`c27ede97d`), followed
  by array-backed data classes (`a43d3de4d`), constructor defaults (`d6deff4f5`), and generic
  data-class equality (`c1597ef12`), data objects (`a2a418bfd`), local data classes (`4deb5e208`),
  the POC IL-assembly-pipeline direction (`1e9492c5f`), and general open-type-parameter default
  placeholders (`2c4bab040`), interface-owned argument-default helpers (`b9c83e0c2`), and general
  concrete varargs (`44ec10c33`) and concrete array initializer constructors (`fb8b20d0a`),
  followed by concrete array copying (`afd686b1f`), escaping array iterators (`603b6f46d`), the
  callable exact path (`e977cba1b`), the delegate-projection boundary freeze (`9e7c608d5`), and
  exact negative-array-size identity (`2448c404c`), shallow array-content equality (`359d01490`),
  recursive array-content equality (`3c65c82f4`), and array-content hashing (`3fa3ca5b8`), followed
  by the stringification slice, first explicit callable-factory export, callable-parameter
  adapters, nullable export metadata, default-argument export continuation, immutable
  callable-provenance exact invocation, ordinary top-level function exports, overload-aware
  export selection, top-level property exports, and the measured typed-argument callable
  capability described below, followed by the bounded erased property-reference representation,
  structural callable-reference identity (`d3433c768`), and the Function3/KMutableProperty2
  continuation (`ef279c65e`), followed by local delegated-property tokens (`417bd3c79`) and
  explicit user Iterator bridges (`e8cc1fc6d`), open invariant array iterators (`801c307a7`), and
  bodyless iterator subinterfaces (`f2ca42e73`), followed by Kotlin-owned Iterable identity and
  compiler-generated bridges (`87c9d7711`), and the first `Kotlin.Stdlib.dll`/ordinary Kotlin
  ArrayIterator implementation (`186af0b4d`), followed by the stdlib ArrayIterable/asIterable
  continuation (`ba7260521`) and the first top-level generated-common stdlib operations,
  `Iterable<T>.first()` and `last()` (`311b79bd7`), followed by separate-consumer stdlib metadata
  KLIB/CLR DLL binding (`df4ab474b`), one-compilation KLIB/DLL production (`21eb60d4e`), and
  producer reproducibility (`59c0b1d33`), with installed-pair discovery in the current feature
  slice.
  The stack is based directly on `origin/master` (`995cf26a0`, rebased 2026-07-13).
  HANDOVER/AGENTS updates that describe a feature belong in that functional commit; do not create
  handover-only follow-up commits.
- Last full DotNet suite at `f2ca42e73`: **498 tests, 0 failures, 0 errors, 0 skips** across 8 XML suites
  (`FirLightTree`/`FirPsi` × IlText/Box(+Strings,Typealias)); the separate generated CLI suite is
  **21 tests, 0 failures, 0 errors, 0 skips**.
  The Iterable slice adds one ilText and one box file (four generated parser tests); its focused
  six-test gate, including the existing rejection file, is 6/0/0/0. The next full count is expected
  to be 502; no fresh full suite was run because the user explicitly did not require one.
  The target-stdlib slice has focused passing IL-text and CoreCLR box pins for both parsers, plus
  the PSI Iterable box; the box harness now also inspects the physical stdlib artifact. No fresh
  full suite has been run.
  The ArrayIterable continuation passes the focused IL-text and CoreCLR box pins for both parsers;
  the updated IL golden also assembles with Framework 4.8 ILAsm. No fresh full suite was run.
  The `Iterable.first` continuation passes the focused IL-text and CoreCLR box pins for both
  parsers (4/0/0/0). The box harness verifies the physical generic CollectionsKt method and the
  assembled program covers user/stdlib producers, primitive widening, nullable elements, and the
  empty exception/message. No fresh full suite was run.
  The `Iterable.last` continuation extends the same pins with a looping common body, mutable
  generic local, repeated erased Iterator calls, and the same producer/type/empty boundaries. It
  is the final piecemeal operation proof; the following slice therefore targets metadata-backed
  consumption instead of accumulating copied bootstrap functions.
  The external-stdlib continuation passes its focused CLI integration pin. A user module compiled
  with `-no-stdlib` resolves `first()` from `Kotlin.Stdlib.klib`, maps imported Iterable through the
  erased runtime identity, and emits the generic call to the sibling bound DLL without regenerating
  CollectionsKt. A separate manual Framework executable also ran successfully against the real
  previously produced DLL. No fresh full suite was run.
  The explicit-producer continuation first audited JS/Wasm, Native, and JVM production lifecycles.
  It rejected an exploratory executable-side-effect writer and instead adds a POC-only explicit
  stdlib product route. Its focused integration pins produced packed target-bound KLIBs and real
  CoreCLR and Framework DLLs from one frontend/IR run per target, then compiled separate
  `-no-stdlib` consumers of both `first()` and `last()`. Together with the existing external-pair
  consumer pin: 3 tests, 0 failures, 0 errors, 0 skips. The cli-dotnet Kotlin compilation also
  passes. No full suite was run.
  The reproducibility continuation runs each target producer twice. Packed KLIB bytes and
  compiler-owned IL bytes are identical within each target; the same focused three-test class is
  3/0/0/0. DLL byte identity is deliberately excluded because both external ILAsm paths may stamp
  fresh PE module identity; stable assembly binding plus separate consumption is the current PE
  contract. No full suite was run.
  The installed-pair continuation follows JVM/JS `KotlinPaths` and Native distribution ownership.
  Each target pin installs the produced pair under a temporary
  `<kotlin-home>/lib/dotnet/<target>` and compiles an ordinary consumer without `-no-stdlib` or a
  manual classpath. Both select the bound pair and do not regenerate CollectionsKt; the same
  focused class remains 3/0/0/0. No full suite was run.
  The `netstandard_s1` representation audit then separated library TFM from executable target. The
  complete current runtime/stdlib plus a first/last program ran under modern and Framework ILAsm,
  CoreCLR 10, Framework 4.8, and both cross-assembler library pairings after isolated netstandard
  retargeting; an unchanged mscorlib application consumed the portable pair on both runtimes. The
  Framework C# compiler also consumed it against the actual netstandard2.0 reference assembly.
  This is probe evidence only; no production code uses textual retargeting. No full suite was run.
  The following implementation continuation made that profile explicit instead of retaining the
  probe transform. Runtime, stdlib codegen, mapped exceptions, nullable values/metadata, delegates,
  and runtime helpers now render through a selected core-library profile. Both platform assemblies
  emit the exact netstandard 2.0 AssemblyRef and TargetFrameworkAttribute; the KLIB binds
  `dotnet_library_tfm=netstandard2.0`; installed discovery uses the single
  `lib/dotnet/netstandard2.0` directory. Ordinary application IL remains mscorlib-scoped for the
  current executable targets. The backend and CLI Kotlin compilations pass, and the focused
  `DotNetLibraryIntegrationTest` passes under both runtime selections using the portable writer.
  A follow-up check found that Framework ILAsm injects an otherwise unused `mscorlib` AssemblyRef
  into a netstandard-scoped PE; platform-library production therefore always uses modern ILAsm.
  Framework ILAsm remains the Framework application writer and a source compatibility oracle. No
  full suite was run. The next continuation added that library product boundary:
  `-Xdotnet-produce-library` compiles ordinary sources to a bound `<module>.klib`/`<module>.dll`
  pair using the netstandard2.0 profile, consistent unsigned 1.0.0.0 candidate assembly identity,
  modern portable writer, and no entry point/runtimeconfig. A focused consumer assembled
  separately and invoked an
  explicit exported primitive method from the produced DLL on CoreCLR. General Kotlin
  cross-module calls were initially deferred until the KLIB owned durable facade/member identity
  rather than asking a consumer to infer it from a source filename. The current continuation now
  follows JS/Native logical linking with Kotlin public `IdSignature` keys and adds a bounded CLR
  binding index containing only owner path, method name, and dispatch shape. The loader accepts
  only explicitly ABI-marked, fully bound unsigned netstandard2.0 pairs; arbitrary metadata KLIBs
  stay compile-time-only. A focused Kotlin consumer constructs an external class, invokes an
  external member and top-level function, receives the producer-recorded facade, copies the DLL
  beside its executable, and runs successfully on CoreCLR. Backend and CLI Kotlin compilation
  pass. The fresh `DotNetLibraryIntegrationTest` run is 4/0/0/0, and the fresh complete .NET
  FIR2IR matrix is 502/0/0/0 across eight XML files.
- `docs/decisions/draft-adr-il-assembly-pipeline.md` records the assembly-writer direction. Keep
  textual IL plus modern ILAsm for the POC and Framework ILAsm as its target/compatibility oracle.
  The permanent direction is a structured compiler-owned CIL/metadata model with deterministic
  text and direct-PE sinks. Because the compiler is JVM-hosted, `System.Reflection.Metadata` is a
  reference or sidecar option rather than an in-process drop-in; do not add a sidecar merely to
  replace one external assembler process with another protocol.
- Landed feature slices, in order: executing box gate, final classes, exceptions/try-catch-finally,
  top-level properties/objects/companions, class inheritance, interfaces, hybrid nullability
  (`Nullable<T>` in exact positions, box-collapse at `Any?` boundaries), reified generics stage 1,
  exhaustive Boolean/Boolean? `when` without source `else`, primitive-array operations and
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
  facade/owner methods with parameterized captures. Callable classes now use that closure
  conversion together with shared mutable-reference cells; bound receivers use the same generated
  field model without introducing another callable ABI.
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
  `BooleanArray`, and `CharArray` initially mapped to native CLR vectors. JVM-shaped registry intrinsics cover
  unary construction, literal factories, `size`, `get`, and `set`; direct `for` loops use the
  backend.common indexed-get shape. `arrprobe_s1` verified exact signatures/opcodes and runtime
  faults on modern CoreCLR and .NET Framework. A negative-size guard prevents CLR
  `OverflowException` from creating a false Kotlin `ArithmeticException` catch edge. Literal and
  indexed operands spill to locals because CLR protected regions require an empty entry stack.
  Nullable/object/generic storage and array identity equality work; generic `Array<T>`, unsupported
  scalar arrays, initializer constructors, spreads, escaping iterators, and copy operations reject.
  Data-class content hash/string support now consumes these vectors through fir2ir's dedicated
  builtins. Contrary to the old task-menu guess, no fake-stdlib declarations were needed: fir2ir
  already supplies the primitive-array builtins and `*ArrayOf` calls. The later nominal-wrapper
  work deliberately supersedes only that raw-vector representation, not these lowering lessons.
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
  covariance. At that revision concrete primitive elements rejected because the then-current raw
  vector representation collapsed `Array<Int>` and `IntArray`; the later nominal wrapper permits
  `Array<Int>` as `int32[]` without collision. Projections and nullable value elements/`Array<T?>`
  still stay out; several nested, initializer, iterator, and copy slices have since been added.
  Data-class content hash/string support consumes the supported concrete reference-element vectors.
  `genarrayprobe_s1` verified `T[]` metadata, typed element opcodes for reference and value
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
  user metadata names first. At that slice, mutable captures rejected because shared-variable
  boxing was absent, while anonymous objects and local-function mixtures remained wholly
  unlowered; the later capturing-callable continuation removes that historical boundary.
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
  metadata constructors. At that slice, mutable captures still rejected and
  explicit-local-function mixtures remained wholly unlowered; the later shared-cell continuation
  removes those historical boundaries. Unsupported exception bases reject only their subtree and
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
  suffix. At that slice, mutable captures rejected and lambda/reference mixtures remained at the
  existing function boundary; the later callable/shared-cell continuation removes those
  historical boundaries. `localfunprobe_s1`–`_s3` all print `42` on CoreCLR 10.0.9 and
  Framework 4.8. `ilText/localFunctions.kt`, the now-renamed
  `ilText/localFunctionCallables.kt`, and
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
  one TFM-neutral IL definition by the selected target's ILAsm. The prototype artifact set uses the
  consistent culture-neutral, unsigned candidate identity
  `Kotlin.Runtime, Version=1.0.0.0, PublicKeyToken=null`; this is not a published ABI-major or an
  AssemblyVersion compatibility promise. Names, version policy, and signing remain deliberately
  breakable until the external-publication gate decides them together. After publication, changing
  any CLR identity component requires an explicit ABI transition. The API floor stays inside .NET
  Framework 4.8 `mscorlib` while remaining CoreCLR-compatible. Namespace
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
  interface. At that slice, direct references consumed as FunctionN dropped FIR's otherwise
  retained KFunctionN view and actual KFunction-typed storage rejected; the metadata continuation
  below supersedes that temporary boundary. Erased overload collisions use the existing
  method-identity gate. `callableabi_s2` assembled the runtime/consumer with modern 10.0.9 and
  Framework 4.8 ILAsm; all four same/cross-runtime pairings ran. Focused PSI/LightTree IL and
  CoreCLR box coverage passes for Function0/1/2, direct references, Unit, singleton reuse,
  reference/value variance, Boolean, nullable Int, reference casts, open T, Function/Function<*>
  marker storage, extension receivers, explicit implementations, nullable callable storage, and
  overload-clash survival. Negative pins cover suspend callables and arity above 2. Pins:
  `ilText/callableObjects.kt`, `ilText/callableObjectsRejected.kt`, and `box/callableObjects.kt`.
  The repo-local `docs/decisions/draft-adr-erased-callable-abi.md` records the decision drivers,
  rejected alternatives, costs, invariants, and the evidence required to promote or revise the
  draft; it is deliberately not presented as a public KEEP or an accepted Kotlin project ADR.
  Promotion requires three layers to be validated separately: the erased Kotlin identity/fallback,
  a measured exact-shape non-boxing execution path, and typed CLR export projections. Only ordinary
  Kotlin subtype upcasts must preserve identity; SAM/adapted references and foreign projections may
  allocate wrappers. Framework 4.8 is a required target profile; this particular probe is evidence
  for the callable representation, not by itself a claim that distribution and CI support are
  complete.
  The final assembly sanity check also caught that non-executable ilText output referenced runtime
  callable types without declaring the runtime AssemblyRef. Header emission now derives that ref
  from the final post-eviction IL body, so both ILAsm versions assemble callable-bearing library IL
  without autodetection; executables keep the runtime-foundation ref unconditionally.
- The capturing-callable continuation selects erased `Kotlin.Function0`/`Function1`/`Function2`
  as the only callable identity in the current pre-freeze ABI candidate. Immutable captures and
  bound receivers are private
  fields on freshly allocated generated callable classes; there are no delegate-like wrappers or
  additional callable interfaces. `SharedVariablesLowering` now runs between callable-reference
  lowering and local-declaration closure conversion. It rewrites each captured mutable variable to
  one invariant `Kotlin.Runtime.Internal.MutableRef<T>` cell whose public `element` field is the
  compiler/runtime cross-assembly layout. Closures capture the cell reference, so sibling closures
  and outer writes share storage; the generic element retains primitive, nullable-primitive,
  reference, and open-`T` storage shapes. Only constructor-empty non-capturing callables receive an
  `INSTANCE` cache. Generated generic callable construction recovers the instantiated class from
  the common lowering's constructor type arguments, and fall-through Unit bridges explicitly
  return `Kotlin.Unit.INSTANCE` from erased `Invoke`.
  `captureabi_s3` assembled compiler-produced consumers and runtimes with modern 10.0.9 and
  Framework 4.8 ILAsm. All four same-target/cross-runtime pairings printed the expected five
  `42` values. Focused PSI/LightTree IL and CoreCLR box coverage passes for immutable and multiple
  captures, mutable counters, sibling-cell sharing, outer writes, Unit mutation, nullable and
  generic cells, primitive/class bound receivers, allocation behavior, and variance identity.
  The same common phase also enables shared mutable captures in named local classes, anonymous
  objects, and lifted local functions; their existing exact fixtures now pin the cell shape and
  their box suites execute later outer writes through the captured cell.
  `ilText/callableCaptures.kt` pins the exact fields, cell type, generic construction, Unit bridge,
  erased interfaces, and absence of stateful singleton caches. At that slice KFunction metadata,
  suspend callables, arity above 2, delegate adapters, Kotlin metadata serialization, and typed fast
  paths remained separate decisions; the metadata continuation below implements only the minimal
  KFunction name view. All four new/changed exact goldens assemble under modern 10.0.9 and
  Framework 4.8 ILAsm. The fresh full DotNet suite is 382/0/0/0 across eight XML files.
- The callable-reference metadata continuation keeps the Phase-0 invariant explicit: erased
  `Kotlin.Function0`/`Function1`/`Function2` remains the sole Kotlin callable execution/identity
  ABI, and bound receivers remain private generated-class fields. Following JVM's KFunction
  mapping and `ReplaceKFunctionInvokeWithFunctionInvoke`, `Kotlin.Runtime` now exposes the
  orthogonal non-generic reflection interfaces `Kotlin.KCallable` (only `string get_name()`) and
  memberless `Kotlin.KFunction : KCallable, Function`. A direct function-reference object
  implements KFunction plus exactly one erased FunctionN on the same object. KFunctionN signatures
  map to KFunction; calls and KFunctionN-to-FunctionN widenings perform a checked interface view
  change and still execute only `object Invoke(...)`. No delegate, wrapper, specialized bound
  reference, or second execution slot exists. Lambdas remain FunctionN-only.
  `kfunction_s1` assembled a dual-interface runtime/consumer with modern 10.0.9 and Framework 4.8
  ILAsm; all four same/cross-runtime pairings read the name, invoked Function1, and preserved object
  identity. `ilText/callableReferences.kt` and `box/callableReferences.kt` pin name metadata,
  invocation, reflection/function identity, KFunction variance identity, non-capturing caching,
  bound receiver freshness, and private receiver storage. The local-function fixtures now pin and
  execute `::local.name` through the same view. Full signature/owner/parameter metadata, property
  references, reflective lookup/call APIs, suspend callables, arity above 2, adapters, typed fast
  paths, and Kotlin metadata serialization remain later slices. The fresh full DotNet suite is
  386/0/0/0 across eight XML files.
- The System.Object Any-foundation continuation follows the mature JVM representation choice:
  logical `kotlin.Any` has no standalone CLR type and is physically `[mscorlib]System.Object`.
  Generated `equals`/`hashCode`/`toString` overrides reuse CLR's existing
  `Equals`/`GetHashCode`/`ToString` slots (`virtual`, no `newslot`); ordinary calls use the same
  object boundary, while `super` calls remain non-virtual. Cross-assembly
  `Kotlin.Runtime.Internal.Intrinsics` now owns `AreEqual`, `HashCode`, and `StringValueOf`.
  They are deliberately primitive-aware rather than thin Object wrappers: `dotnet-any_s1` first
  proved the root/override/exception hierarchy across all four modern/Framework pairings, then a
  hostile boxed-value probe found that both CLRs equate signed-zero Doubles, collapse their hashes,
  and print boxed Double/Boolean as `2E+19`/`True`; Framework also hashes different NaN payloads
  differently. The helpers restore Kotlin's canonical Double equality/hash/string, Boolean hash
  constants/lowercase text, and invariant integer text before virtual fallback. General reference
  and open-`T` structural equality, nullable-primitive-to-Any equality, Any string conversion, Any
  member calls, unrelated-interface identity, and class Any overrides are enabled. Generic
  `T : Any` constraints remain rejected because CLR `class` would exclude value instantiations and
  erasing the constraint would admit null; interface Any redeclarations and data generated members
  remain separate audited slices. The draft rationale is
  `docs/decisions/draft-adr-system-object-any-foundation.md`. Both changed exact goldens assemble
  with modern 10.0.9 and Framework 4.8 ILAsm; the new box runs through both toolchain selections.
  The fresh full DotNet suite is 388/0/0/0 across eight XML files.
- The hybrid exception-identity continuation keeps deliberate BCL mappings for faults the CLR
  raises natively, while adding runtime-owned identities only where no faithful BCL class exists.
  `Kotlin.Runtime` now contains public `Kotlin.RuntimeException : System.Exception` with the mature
  four constructor shapes, nullable default-message behavior through a reused `get_Message` slot,
  and cause identity through `InnerException`. Source `RuntimeException` remains rejected because
  existing mapped logical children are not physical subclasses and would escape a parent catch.
  The first exact child is open `Kotlin.NoWhenBranchMatchedException : Kotlin.RuntimeException`;
  exhaustive-when fallthrough now constructs it through the existing JVM-shaped intrinsic rather
  than throwing plain `System.Exception`. Existing mappings for divide-by-zero, null, bounds, and
  invalid-cast faults have not moved. `exceptionabi_s1` assembled runtime/consumer pairs with
  modern 10.0.9 and Framework 4.8 ILAsm; all four same/cross-runtime pairings preserved exact and
  parent catches, null default message, cause identity, and the boundary from a foreign
  `InvalidOperationException`. The draft rationale is
  `docs/decisions/draft-adr-hybrid-exception-identity.md`; the rejection pin at that slice kept
  `RuntimeException` and `Error` unavailable until their distinct catch policies were coherent.
  The exact mappings below supersede the Error boundary. The fresh full DotNet suite was
  390/0/0/0 across eight XML files.
- The exact NumberFormatException continuation is the first source-visible runtime-owned mapping.
  It follows the JVM/Native two-constructor surface, but uses the CLR-specific physical hierarchy
  `Kotlin.NumberFormatException : System.ArgumentException`: this preserves exact identity plus
  Kotlin's already-supported `IllegalArgumentException` value/catch edge, whereas
  `System.FormatException` is not an ArgumentException. A private nullable message field and reused
  virtual Message slot preserve the Kotlin null default through parent-typed calls. Mapped entries
  now record physical supertype refs, so the backend verifier handles the exact class's
  instruction-free ArgumentException/Exception widenings generically. A foreign FormatException
  remains distinct pending an explicit parsing/interop translation policy. Direct user use of
  NoWhenBranchMatchedException was audited and deliberately not exposed: common Kotlin deprecates
  it at error level as a compiler implementation exception. `exceptionabi_s2` assembled both
  consumer/runtime combinations with both ILAsm versions and ran all eight combinations across
  CoreCLR 10.0.9 and Framework 4.8. Exact IL and box pins cover constructors, exact/parent catches,
  parent/root value widening, identity, virtual message dispatch, and default message/cause state.
  The new exact golden assembles under both ILAsm versions, the embedded-runtime box also passes
  through the Framework toolchain selection, and the fresh full DotNet suite is 394/0/0/0.
- The exact Error continuation gives Kotlin-created `Error` values the runtime-owned identity
  `Kotlin.Error : System.Exception` and all four mature constructor forms. It deliberately does
  not map to deprecated `System.SystemException` and does not claim that foreign CLR
  OutOfMemoryException or StackOverflowException values are Kotlin Error instances. The nullable
  message field/reused virtual Message slot and InnerException preserve no-arg, explicit-message,
  message-plus-cause, and cause-only Kotlin behavior. The mapped-constructor registry now records a
  separate `hasCauseCtor` capability: Error enables it, while existing BCL mappings keep rejecting
  the cause-only shape. The existing Throwable/Exception -> System.Exception collapse means an
  exact Error is caught by both on this POC; the pins make that accepted root delta explicit.
  `exceptionabi_s3` assembled both consumer/runtime combinations with both ILAsm versions and ran
  all eight combinations across CoreCLR 10.0.9 and Framework 4.8, preserving exact/cause behavior
  and keeping a foreign OutOfMemoryException outside the exact catch. The exact golden assembles
  under both ILAsm versions, the embedded-runtime box passes through the Framework toolchain
  selection, and the fresh full DotNet suite is 398/0/0/0.
- The RuntimeException representation audit keeps source use rejected and records why catch unions
  alone do not solve it. A union can catch the exact root plus current BCL child mappings only by
  binding a System.Exception-shaped value, which erases RuntimeException from signatures, collides
  with Throwable/Exception, and admits arbitrary foreign exceptions. Pretending a caught BCL child
  has exact Kotlin.RuntimeException storage is worse: `exceptionabi_s4` assembled that
  unverifiable shape with both ILAsm versions, and both CoreCLR and Framework dispatched an
  exact-root method on an actual InvalidOperationException, demonstrating live type confusion.
  The draft ADR now requires either an exact owned-child hierarchy with comprehensive native-fault
  and interop translation, or a different representation proven coherent for storage, signatures,
  catches, rethrows, and type tests. Do not enable the source mapping piecemeal.
- The data-class model enables top-level and named nested classes whose
  primary-constructor properties have supported mapped types.
  Fir2ir's mature generated bodies are used unchanged: Equals reuses the System.Object slot
  through CLR `isinst`/`castclass`, hash/string behavior reuses the Any runtime helpers,
  componentN reads fields, and copy calls the primary constructor. Ordinary top-level/member
  defaults follow the common masked-stub algorithm; `copy(x = ...)` reaches an instance
  `copy$default`, whose `int32` mask uses intrinsic `Int.and`/CLR `and` and whose selected defaults
  update generated argument slots with `starg` before dispatch. Constructor defaults now use the
  same common/JVM algorithm: the synthetic `.ctor` repeats the original parameters, appends one
  `int32` mask per 32 value parameters, and ends with nullable
  `[Kotlin.Runtime]Kotlin.Runtime.Internal.DefaultConstructorMarker`. The runtime type is public
  metadata with a private constructor, and generated calls pass only null; it is a compiler ABI,
  not a Kotlin-facing API. This keeps the stub distinct from real mask-shaped overloads. The CLR
  constructor-identity pre-pass rejects a class whole when mapped original/generated signatures
  still collide, such as `String` versus `String?`. Primary/secondary, delegating, data, generic,
  named nested, inner, lifted local/capturing, multi-mask, named-argument, and evaluation-order
  paths run on CoreCLR. Interface defaults remain unlowered so Framework interfaces stay
  all-abstract. Generic data classes keep normal reified CLR `C<T>` storage, signatures,
  constructors, component members, and copying, but generated equality uses the CLR-specific split
  in `docs/decisions/draft-adr-generic-data-class-equality.md`. Each declaration owns a private,
  non-generic nested equality interface with one object-valued slot per primary property. Private
  explicit MethodImpl bridges expose those values only to `Equals`; all constructed `C<T>` types
  share that declaration-local view, so Kotlin/JVM's erased equality identity survives without a
  public runtime protocol. The call resolver also reports the receiver-substituted CLR return type
  for generated member calls: `C<Int>.copy$default` produces `C<Int>` even when common IR retains
  the open `C<T>` result. A named nested data class follows the established static-nested model: it
  captures no outer instance and owns only its own type parameters, so even below
  `GenericOuter<T>` its independently non-generic metadata identity and every generated member
  token are `'GenericOuter`1'/'Entry'` with no outer `!0`. Runtime coverage composes that shape
  below classes, generic classes, interfaces, objects, companions, data classes, and deeper named
  parents. Array properties now follow the JVM-generated-member split:
  equality remains reference identity, while fir2ir's dedicated array hash/string builtins route
  through the DotNet intrinsic registry to runtime-owned `System.Array` helpers. The helpers fold
  elements with the established Kotlin object-boundary hash/string operations, giving null 0/
  `null`, empty 1/`[]`, Kotlin Boolean/Double/Char semantics, and content rendering for all five
  supported primitive vectors plus supported reference-element `Array<E>`. Unsupported vector
  shapes still reject through their owning mapper and evict the data class whole. The Char-array
  pin exposed CLR's duplicated-bits boxed Char hash; `Intrinsics.HashCode` now restores the Kotlin
  numeric code centrally, with direct Any coverage. Ordinary unsupported local classes remain
  gated whole-class.
  `dataclass_s1` assembled the exact positive golden with modern 10.0.9 and Framework 4.8 ILAsm;
  the focused positive/hostile two-parser matrix is 10/0/0/0, including real CoreCLR execution,
  and the fresh full DotNet suite is 408/0/0/0.
  `dataclass_s2` assembled the generic-outer nested golden and the updated rejection golden under
  both ILAsm implementations; its focused two-parser IL/CoreCLR matrix is 6/0/0/0, and the fresh
  full DotNet suite is 412/0/0/0.
  `dataclass_array_probe_s1` assembled and ran the runtime-helper signatures/loops under modern
  10.0.9 and Framework 4.8 ILAsm/CLR with identical null, primitive, and reference-vector output.
  The exact array golden pins primitive/generic helper calls and identity equality; runtime pins
  cover all five primitive vectors, reference/null/empty arrays, canonical Double/Boolean/Char
  hashes, and content text. Both parser variants run on CoreCLR, and the Framework-selected ILAsm
  also assembles and runs the new box. The fresh full DotNet suite is 416/0/0/0.
  `ctor_default_probe_s1` assembled a runtime marker plus default/real-overload consumer with
  modern 10.0.9 and Framework 4.8 ILAsm; all four same/cross-runtime pairings executed both paths.
  The exact constructor golden assembles with both ILAsm versions, both parser variants execute
  the comprehensive box on CoreCLR, the Framework-selected ILAsm also executes it, and the fresh
  full DotNet suite is 420/0/0/0.
  `generic_data_probe_s1` established the selected private nested-interface shape before codegen.
  Both generic equality goldens assemble with modern 10.0.9 and Framework 4.8 ILAsm; all five new
  runtime boxes execute when Framework ILAsm is selected as well as under the normal modern path.
  Reflection sees zero public component bridges and zero public erased-view types, two private
  bridges plus one private view in the two-property pin, and the ordinary public generic property
  remains open `T`. The focused two-parser generic/callable-regression matrix is 16/0/0/0, and the
  fresh `--rerun-tasks` full DotNet suite is 434/0/0/0 across eight XML files.
  Data objects now consume the same common generated-member machinery and the established CLR
  singleton shape. Top-level and named nested forms emit a sealed class, private constructor,
  public static initonly `INSTANCE`, declaration-wide `Equals(object)`, compile-time
  `FqName.hashCode()` constant, and simple-name text. A nested data object below a generic owner
  remains static-style and captures no outer type argument. The separate general object-supertype
  gate remains unchanged, so data objects with proper class/interface supertypes still reject for
  the same reason as ordinary objects. The exact golden assembles with modern 10.0.9 and Framework
  4.8 ILAsm. Reflection over both outputs confirms the singleton surface and that a second instance
  created through the private constructor compares equal with the same hash/text. Both parser box
  variants pass with modern and Framework-selected ILAsm, and the fresh full DotNet suite is
  436/0/0/0 across eight XML files.
  Local data classes now compose with common local-declaration lifting and closure conversion.
  Their private lifted CLR class stores immutable, mutable-cell, outer-receiver, and local-function
  captures, and constructor/default/copy paths propagate that state. Generated components,
  equality, hash, and text still observe only source primary-constructor properties. Generic local
  data classes keep the reified class/private-erased-view split; the generic-data lowering filters
  common `BOUND_RECEIVER_PARAMETER`/`BOUND_VALUE_PARAMETER` constructor state before selecting
  equality properties. The exact golden assembles with modern 10.0.9 and Framework 4.8 ILAsm, both
  parser boxes pass with modern and Framework-selected ILAsm, and the fresh full DotNet suite is
  440/0/0/0 across eight XML files. The follow-up now handles the deliberately separate omitted-
  generic-default boundary in the shared call emitter. It recognizes only the common/JVM
  `DEFAULT_VALUE` null composite, then emits the resolved parameter's physical zero/null/empty
  nullable or an `initobj !0`/`!!0` temporary. Functions, constructors, members, local/generic
  data `copy`, class and method type parameters, concrete primitive/reference/nullable
  substitutions, and explicit-argument non-evaluation are pinned. The exact golden assembles
  with modern 10.0.9 and Framework 4.8 ILAsm, and both parser boxes pass with both assembler
  selections. The fresh full-suite count for this slice is recorded in Branch state above.
  Interface-owned argument defaults now consume the same masked lowering without putting a body
  on the CLR interface. A module pass moves each real dispatcher into the public, compiler-only
  nested `<DefaultImpls>` helper, makes the interface receiver explicit, lifts owner type
  parameters (constraints included) into invariant helper-method parameters, remaps the moved
  body, and redirects calls using the receiver's instantiated interface view. The original slot
  stays abstract and dispatches to the implementation through `callvirt`; actual DIM bodies and
  `super<I>` remain rejected. Direct, interface, inherited, constrained, delegated, variant,
  bounded, generic-method, extension, nested, mask/order, explicit-argument, and source-name
  collision shapes are pinned. `interfacedefaultprobe_s1`, the exact golden, and both parser boxes
  pass with modern 10.0.9 and Framework 4.8 ILAsm. The fresh full-suite count for this slice is
  recorded in Branch state above.
  General concrete varargs now lower before closure conversion/default stubs into the established
  vector ABI. Reference vararg parameters lose only their source `out` projection; aliases and
  captures follow the invariant physical array while `vararg T` remains rejected. Omitted and
  expanded arguments allocate fresh arrays, spread values/sizes are evaluated once and copied by
  ordinary typed array loops, and no-spread `arrayOf` calls retain their compact intrinsic. The
  JVM-style null placeholder also makes a vararg's own default interoperate with masked defaults.
  Primitive/reference/nullable/user/generic-class elements; empty/literal/multiple/empty spreads;
  alias, evaluation and exception order; top-level/member/extension/constructor/interface/local/
  captured/non-final/default shapes; and spread-bearing `arrayOf` are pinned. The exact golden and
  both parser boxes pass with modern 10.0.9 and Framework 4.8 ILAsm. The fresh full-suite count for
  this slice is recorded in Branch state above.
  Concrete array initializer constructors now reuse backend.common `ArrayConstructorLowering`
  before callable-class generation. Direct rich lambdas/references inline into one guarded
  allocate-and-fill loop; function values and explicit callable classes invoke the existing erased
  `Function1` slot. A common fallback uses `Int.plus(1)` only when the built-ins surface omits
  `Int.inc()`, and concrete invokable receivers take their result from their actual `invoke`
  declaration. Size and callable/bound expressions now evaluate in source order before allocation,
  including a `Nothing`-typed initializer. The mature returnable-block transformer removes local
  returns before IL emission.
  All five primitive vectors plus concrete reference/nullable/user/generic-class arrays; single
  size/initializer evaluation; zero/negative sizes; ascending index and exception order; captures;
  local returns; direct/local references; and callable values/objects are pinned. Open/reified,
  nested, and mapper-rejected families remain gated. The exact golden assembles with modern 10.0.9
  and Framework 4.8 ILAsm, and both parser boxes pass with both assembler selections. The fresh
  full-suite count for this slice is recorded in Branch state above.
  Concrete array copying now follows the JVM stdlib's platform-operation boundary through the
  DotNet registry. Resolution-only external declarations add `copyOf`/`copyInto` to the temporary
  .NET stdlib without emitting a facade. Exact typed allocation plus `System.Array.Copy` gives
  fresh copies for all five primitive vectors and concrete reference arrays, including
  truncating/zero-or-null-padding resized copies. `copyInto` preserves defaults,
  source evaluation, destination identity, projected concrete reference sources, and overlapping
  self-copies. `arraycopyprobe_s1` found identical raw behavior on CoreCLR and Framework, but also
  proved raw destination range failures map to CLR `ArgumentException`; the single runtime-owned
  `Intrinsics.ArrayCopyInto` helper therefore performs overflow-safe source/destination validation
  and throws the existing IndexOutOfBounds mapping before calling `System.Array.Copy`. Resized
  open `Array<T>` copying (including its unrepresentable open `Array<T?>` resize result) and
  content operations stay rejected; concrete nullable-reference resize results are supported.
  The exact golden assembles with modern 10.0.9 and Framework 4.8 ILAsm, and both parser boxes pass
  with modern and Framework-selected assemblers. The fresh full-suite count for this slice is
  recorded in Branch state above.
  Escaping array iterators now follow the erased Kotlin-owned representation recorded in
  `docs/decisions/draft-adr-erased-iterator-abi.md`. Source `Iterator<T>` and the five primitive
  iterator classes share the non-generic runtime interface `Kotlin.Collections.Iterator` with
  `bool HasNext()` and `object Next()`; logical element types remain in IR/metadata and call sites
  cast or `unbox.any` the result. This preserves the same object/cursor across Kotlin covariance,
  including `Iterator<Int> -> Iterator<Any>`, where CLR generic variance cannot help. Explicit
  `iterator()` over the five primitive vectors, concrete reference arrays, and open invariant
  `Array<T>` now constructs the corresponding closed generic
  `[Kotlin.Stdlib]Kotlin.Collections.ArrayIterator<T>`; open vectors retain their exact
  `!n[]`/`!!n[]` signature and consumers use `unbox.any !n`/`!!n`. Nullable type parameters,
  projections, concrete primitive-element generic arrays, and nested arrays still fail in the
  structural array mapper. Direct array `for` loops remain allocation-free.
  Exhaustion uses exact runtime-owned `Kotlin.NoSuchElementException` rather than CLR
  InvalidOperationException, which would create a false IllegalStateException edge.
  `iteratorabi_s1` assembled generic/primitive/reference consumers and the exact catch with modern
  and Framework ILAsm; all four same/cross-runtime pairings ran. Both parser IL/box pins pass on
  CoreCLR. User classes which directly implement `Iterator<T>` now retain their typed Kotlin
  methods and receive private explicit MethodImpl bridges for runtime `HasNext()` and erased
  `object Next()`. This follows the JVM typed-member-plus-erased-bridge pattern; the additional
  HasNext forwarder exists because the Kotlin-owned CLR slots use CLR-style names. Reference
  results pass unchanged, primitive/open-generic results box once, and derived classes inherit a
  bridge-owning base's methods. An abstract obligation-only base defers bridge ownership to the
  first concrete descendant. The same object therefore preserves state and identity through
  primitive/reference covariance without an adapter. A bodyless module-local iterator
  subinterface now inherits the same erased runtime identity, may add unrelated abstract members,
  and gives its implementing classes the same bridges. Calls through inherited fake overrides use
  the erased slots. Redeclaring `next`/`hasNext` on the subinterface remains rejected because it
  would create a second typed execution contract; interface bodies remain outside the Framework
  4.8 floor. A subinterface's own generic variance remains CLR/reference-only, but widening its
  value-shaped instance to the erased base Iterator remains identity-preserving.
  Kotlin-owned `Iterable<T>` now follows the same invariant through the non-generic runtime
  `Kotlin.Collections.Iterable { Iterator GetIterator() }` identity. The former Iterator-specific
  lowering is a table-driven erased-collection bridge policy: ordinary classes retain typed
  `iterator()` and receive a private explicit GetIterator bridge; base ownership, abstract
  obligation deferral, bodyless subinterfaces, fake-override calls, and redeclaration rejection
  match Iterator. Real `for` loops over user-defined iterables execute through erased
  GetIterator/HasNext/Next. Primitive and reference covariance, including
  `IterableView<Int> -> Iterable<Any>`, preserves the producer object without a wrapper. That
  identity assertion exposed a general codegen omission: reference smartcasts may narrow a local
  read while the CLR slot retains its wider declared type. Bare reads and explicit IR smartcasts
  now emit checked `castclass`, including instantiated generic interface targets. Imported CLR
  generic interfaces and IEnumerable/IEnumerator remain a separate deferred interop problem; no
  foreign-view or BCL mapping was introduced.
  Primitive-specialized subclasses, collections, and CLR enumeration adapters remain rejected.
  The first target-stdlib migration now implements the previously stated boundary:
  `Kotlin.Runtime` keeps the erased Iterator/Iterable identities and exact exhaustion exception,
  while an ordinary generic Kotlin `ArrayIterator<T>` is emitted only into the new reserved
  `Kotlin.Stdlib, Version=1.0.0.0` assembly. Explicit array iterator intrinsics construct the
  corresponding closed generic stdlib class, so primitive elements remain typed until the
  compiler-generated erased Next bridge; the handwritten System.Array runtime producer is gone.
  The default bootstrap producer still shares injected stdlib and user sources through
  frontend/lowering, then separate USER/STDLIB emitter ownership scopes produce the two assemblies.
  Every executable is supplied both platform dlls. Separately compiled consumers can now resolve
  the bounded stdlib API through a metadata KLIB and execute against its manifest-bound sibling
  DLL; this is deliberately not a claim that arbitrary Kotlin libraries have general physical
  member mapping.
  `docs/decisions/draft-adr-target-stdlib-bootstrap.md` records the boundary. Separate primitive
  implementations remain a later performance decision.
  The first follow-on stdlib class is generic `ArrayIterable<T>`. Exact `asIterable()` overloads
  for the five established primitive arrays plus generic arrays construct it across the stdlib
  boundary. It holds the original exact vector, observes mutations, creates an independent
  `ArrayIterator<T>` for each iterator request through ordinary stdlib Kotlin code, and preserves
  erased Iterable covariance identity. Empty arrays currently get the same view instead of the
  common stdlib's `emptyList()` optimization because List has no coherent ABI yet; no observable
  Iterable semantics are changed.
  The next stdlib slices add the first top-level operations: `Iterable<T>.first()` and `last()` are
  emitted only into `[Kotlin.Stdlib]Kotlin.Collections.CollectionsKt`, and user call sites invoke
  the real generic methods across the assembly boundary. Their Kotlin bodies are the universal
  iterator portions of the stdlib generator's common `Elements.f_first` and `f_last` templates.
  Their List fast paths are deliberately omitted until List has a coherent ABI; this changes only
  optimization. Empty input preserves the common `Collection is empty.` NoSuchElementException
  behavior. Together they prove straight-line and looping generic common bodies; adding more
  individual operations would not by themselves prove standalone stdlib consumption. The
  generator currently has no .NET target; do not add one merely to generate an unsupported broad
  corpus. The eventual standalone stdlib build should consume generated common sources and narrow
  .NET actuals.
- The callable exact-path slice preserves erased `Kotlin.Function0/1/2/3` as the only Kotlin
  callable identity and universal fallback. Following the JVM typed-body-plus-erased-bridge
  pattern, eligible generated non-Unit callables keep their original typed body as `InvokeExact`
  and make erased `Invoke` call it. A CLR-specific optional
  `Kotlin.Runtime.Internal.ExactFunctionN<P..., R>` interface on the same object lets calls through
  FunctionN discover that typed member across assemblies. It is metadata-public for assembly
  access, but is not a Kotlin source or storage ABI. Call sites with a complete logical shape
  evaluate receiver/arguments once, use a closed-interface `isinst`, call without value boxing on
  a hit, and otherwise use erased Invoke. The call-site-shaped probe is no longer treated as the
  only possible typed capability. When an immutable local initializer chain retains the generated
  callable's original shape, a second guarded probe invokes that interface and performs only legal
  widenings; `(Int) -> Int` viewed as `(Int) -> Any` therefore keeps its primitive argument and
  boxes only the result. Discarded non-Unit calls use the same path. CLR-compatible reference
  variance needs no second probe. Mutable locals, parameters, fields, and returns lack this local
  provenance and retain the erased fallback, as do older-module and explicit-user implementations
  that fail every `isinst`. Unit remains erased because CLR void cannot close the generic result.
  Function3 now receives the same ExactFunctionN execution capability. It deliberately receives no
  TypedArgumentsFunction3: the cross-module measurement admitted that metadata-public partial
  contract only for arities 1/2. The separate Func/Action export boundary also remains capped at
  arity 2.
  `callableexact_s1` assembled exact, fallback, reference-variant, and value-variant cases with
  modern and Framework ILAsm and ran all four runtime pairings. Goldens cover ordinary, capture,
  reference, local-function, and array-initializer contexts; CoreCLR coverage additionally pins
  parameter/open-generic calls, nullable primitives, variance, captures, bound references, Unit,
  explicit fallback, and receiver/argument/invocation order. The new
  `ilText/callableInvocationProvenance.kt` pin separately covers exact primitive calls, primitive
  result widening, parameter widening, immutable alias chains, function references, mutable and
  parameter boundaries, and user implementations. Raw probe `callable_capability_s1` proved the
  partial shape. The separately compiled `typed_arguments_crossmodule_s1` runtime, producer, and
  consumer then measured 2,000,000 `(Int) -> Any` calls: the partial path saved one 24-byte
  argument box per call (96,000,040 versus 48,000,040 allocated bytes), with stable timings moving
  from 40–43 ms to 30–31 ms on CoreCLR and 200–213 ms to 141–142 ms on Framework. A producer gains
  a 13-byte Function1 or 17-byte Function2 bridge plus one InterfaceImpl row. The branch therefore
  implements metadata-public `TypedArgumentsFunction1/2` only on generated non-Unit callables with
  at least one concrete primitive/nullable-primitive parameter. Object-result/value-argument calls
  probe it first, then ExactFunctionN, then erased Invoke. Exact primitive calls are unchanged;
  older/user objects retain guarded erased fallback. The Unit experiment saved allocation but
  regressed Framework time, so Unit deliberately remains erased. `box/callableObjects.kt` pins
  cross-boundary Function1/2 hits, nullable primitive arguments, and the explicit-user miss.
  Delegate projection remains a separate export layer; the boundary audit follows below.
- The explicit CLR export boundary is compiler configuration, not a Kotlin annotation or automatic
  whole-module policy: repeatable `-Xdotnet-export=<kotlin-selector>=<clr-method-name>` selects one
  public, non-generic top-level function. Its canonical Kotlin method remains unchanged; a
  user-named method on the existing file facade retains ordinary mapped positions and exposes only
  Function0/1/2 positions as typed Func/Action. Ordinary functions with no callable positions now
  use the same explicitly named facade, nullability, default-overload, and collision policies. This
  follows the JVM naming/default and Wasm/JS export-wrapper boundary semantically. The selector
  syntax itself has no target precedent: it is provisional POC control-plane machinery because
  this branch has intentionally not added a public source annotation. Unique declarations retain
  the short `pkg.name` selector;
  overloaded names require a fully qualified, whitespace-free expanded Kotlin parameter list,
  for example `pkg.name(kotlin.Int,kotlin.Function1<kotlin.Int,kotlin.Int>)`. The extension
  receiver is first, the return is omitted, and no CLR tokens or declaration-order indexes enter
  this provisional control-plane spelling. A bare overloaded name remains a loud error, while
  separately signed overloads can be exported under independent CLR names. A future
  declaration-bound export model
  should remove this textual disambiguator rather than standardize it as public ABI. The runtime
  projection helper binds exact Func directly to InvokeExact, falls back through closed generic
  box/unbox thunks for erased implementations, and discards Kotlin.Unit in Action thunks. The
  reverse helper wraps Func/Action in private runtime-owned FunctionN adapters; Func adapters also
  expose ExactFunctionN, Func1/2 adapters expose TypedArgumentsFunctionN, and Action adapters
  remain erased. Projecting an adapter back to the
  same closed shape returns its stored original delegate object. Different shapes and the
  Kotlin-callable -> delegate -> Kotlin-callable direction have no identity promise. A null foreign
  delegate at a non-null position throws ArgumentNullException; nullable positions pass null in
  both directions. User exceptions pass unchanged because no boundary helper catches. Explicit
  Roslyn-compatible NullableAttribute metadata now describes every non-empty exported parameter
  and return type shape directly from source IR, with preorder nested flags and value-type
  skipping; no NullableContext compression or inference from erased FunctionN is used. The
  attribute class is synthesized and reserved only when one of those shapes needs it, so a
  primitive-only explicit export can coexist with a source-owned class of that name. Missing or
  ambiguous selectors, exported name/signature collisions, generic/suspend functions,
  KFunction/suspend callable positions, and marker/high-arity callables are loud errors. `delegateexport_s1`,
  `delegateadapter_s1`, and `nullableexport_s1` executed the full arity/Func/Action/nullability
  matrix with both runtimes. Compiler-produced facades plus the landed runtime executed invocation
  and same-object round trips on modern and Framework. Dual-parser goldens pin both return and
  parameter directions, and the generated CLI suite pins the option and nullable-parameter path.
  A negative CLI fixture reserves the synthesized NullableAttribute metadata identity against a
  source declaration in an exporting module, while a primitive-only positive fixture proves that
  the unused identity is not reserved.
  A contiguous trailing suffix of source defaults now adds progressively shorter overloads only to
  that explicit facade. They pass physical placeholders and the established masks to `$default`,
  preserving arbitrary/dependent callee-side expressions and nullable-value slots. No CLR
  optional-constant metadata is emitted, because Roslyn either embeds its constant in the caller or
  supplies `default(T)`. Non-trailing defaults stay full-argument-only, and a generated overload
  collision is a loud error for the whole export. `defaultexport_s1` used Roslyn 5.6.0 plus both
  ILAsm/runtime flavors; compiler-produced C# consumers ran full, suffix, zero-argument, dependent,
  callable-adaptation, and nullable-value cases. The exact golden and positive/collision CLI pins
  cover the repository surface. The separate default-export draft ADR records the reasoning.
  `plainfunctionexport_s1` assembled compiler-produced ordinary aliases under both ILAsm versions;
  Roslyn 5.6.0 consumers executed primitive, nullable-reference, progressively defaulted, and
  extension-receiver calls on both runtimes. `ilText/plainFunctionExports.kt` pins that facade.
  `overloadedexport_s1` assembled the signature-selected facade with both ILAsm versions; Roslyn
  5.6.0 consumers executed primitive/reference overloads, callable adaptation, a defaulted
  extension, and a nested nullable generic argument on CoreCLR and Framework. The dual-parser
  golden pins the canonical signatures, while CLI fixtures pin successful independent selection
  and rejection of the legacy bare-name spelling for an overloaded group.
  The function selector is frozen at that scope: it stays compiler-only and provisional, no later
  feature may depend on its textual type grammar, and a future source-bound annotation should
  remove it.
  Top-level property representation now has a separate minimal POC selection path with no type
  grammar: `-Xdotnet-export-property=<kotlin-fq-name>=<clr-property-name>`. One unique public,
  non-extension, non-const property becomes a real static CLR `.property` plus public
  `specialname` wrappers. The original Kotlin declaration stays unchanged; ordinary types retain
  their mapped shape, callable getters/setters reuse Func/Action projection/adaptation, and a
  non-public Kotlin setter yields a getter-only exported alias. Explicit nullable flags live on
  the property row, getter return, and setter value. Cross-kind property/method/const-field and
  accessor collisions reject the whole export. `propertyexport_s1` assembled with both ILAsm
  versions; Roslyn 5.6.0 consumers executed mutability, nullable values, callable identity and
  invocation, and read-only reflection on CoreCLR and Framework. The property draft ADR records
  representation, selection limits, evidence, and the deferred extension/indexer and const-field
  policies.
- The property-reference slice follows Native/Wasm's runtime-wrapper model while retaining the
  existing callable invariant. Source KProperty0/1/2 and KMutableProperty0/1/2 map to non-generic
  Kotlin.Runtime interfaces; Get/Set are object-shaped (Set returns CLR void), and each KPropertyN
  inherits the matching erased FunctionN. This CLR-specific erasure is required because generic
  variance cannot preserve identity through primitive instantiations. One private runtime wrapper
  stores the name plus ordinary lowered getter/setter callable objects; common lowering evaluates
  a bound receiver once and shares its generated storage. Cross-assembly construction goes through
  metadata-public PropertyReferenceFactory methods in Kotlin.Runtime.Internal, not through the
  export selector and not through a public Kotlin surface. Immutable and mutable arities 0..2
  construct. Mutable arity 2 uses the ordinary erased Function3 setter unlocked by the coherent
  callable-family extension; it introduces no property-specific callable shape. Direct
  get/set, inherited invocation, primitive result widening, bound mutation, and explicit user
  implementations are pinned by `ilText/propertyReferences.kt` and `box/propertyReferences.kt`.
  The structural-identity continuation follows Native for function references and Native/Wasm for
  property wrappers. Every rich reference with a declaration target extends metadata-public,
  compiler-internal `FunctionReferenceBase`; its stable declaration signature, arity, adaptation
  flags, and structurally compared bound values drive Equals/GetHashCode, while lambdas remain
  System.Object identity values. Fun-interface constructor references remain rejected at the
  established no-SAM-model boundary. The base constructor and bound-value hook are protected CLR
  `family` members, so the runtime adds no new public hook beyond the required public Any-member
  overrides. Property wrappers compare exact private wrapper kind, name, getter, and optional
  setter, so equivalent expressions at different source sites compare equal without exposing
  implementation class names. Rendering is `function <name>`/`constructor` and
  `property <name> (Kotlin reflection is not available)`.
  Explicit user implementations retain their own Any behavior. The callable and
  property-reference draft ADRs record why the runtime base is an implementation class rather than
  another callable ABI. Focused PSI/LightTree box and IL-text tests pass; the box path assembled the
  runtime and program with modern and Framework 4.8 ILAsm selections and executed both products on
  CoreCLR.
  The follow-up accessor audit intentionally added no `getter`/`setter` members: common, Native,
  Wasm, and JS KProperty surfaces omit JVM's target-specific accessor objects. JVM accessors are
  KFunctions with an `Accessor.property` back-reference, so exposing the wrapper's private stored
  FunctionN values would be semantically false. Accessor objects wait for a coherent .NET
  reflection metadata model.
  Local delegated-property tokens now follow Native/Wasm's separate name-only shape. Private
  KProperty0/KMutableProperty0 implementations expose truthful name, mutability, and rendering;
  Get/Invoke/Set throw the mapped UnsupportedOperationException with the mature-target message and
  the token retains System.Object identity equality. Metadata-public compiler-internal factories
  construct the private wrappers. Common LocalDelegatedPropertiesLowering now runs before local
  closure conversion, and delegated accessors are eligible local functions, so val/var accessors
  capture their delegate normally. An IR-only throw-helper stub exists solely because common
  callable-reference upgrade builds temporary unsupported accessor bodies before property
  lowering discards them; it is not emitted. The box also covers provideDelegate.
- Negative dynamic array sizes now construct compiler-owned
  `Kotlin.NegativeArraySizeException : Kotlin.RuntimeException` before CLR `newarr`. The common
  Kotlin API promises the RuntimeException parent while JVM's named child is a Java platform type,
  so the CLR child is metadata-public for generated consumers but intentionally absent from the
  fake stdlib and source map. It replaces the neutral System.Exception approximation without
  exposing raw OverflowException's false ArithmeticException edge or inventing argument/state
  edges, and inherits the exact root's null default message. Constructors, initializer arrays,
  varargs, reference arrays, and resized copyOf share the guard. `negativearray_s1` assembled and
  ran exact/parent/sibling/message checks with both ILAsm versions in all four runtime pairings;
  repository goldens and both parser boxes cover tokens, categories, and evaluation order.
- Shallow `contentEquals` now follows the JVM/common stdlib contract without changing array
  identity equality. Resolution-only external declarations cover the five supported primitive
  arrays and generic `Array`; all calls route to one runtime-owned `ArrayContentEquals` helper.
  It handles same/null/length cases, traverses through `System.Array`, and delegates element
  semantics to `Intrinsics.AreEqual`, preserving Kotlin reference equality, NaN canonicalization,
  and signed-zero distinction. Nested arrays deliberately remain identity-compared; recursive
  semantics are reserved for `contentDeepEquals`. Open invariant `Array<T>` consumers work because
  traversal selects no consumer-side element opcode. `arraycontent_s1` assembled and ran on modern
  CoreCLR and Framework, and the exact golden assembles with both ILAsm versions. The focused
  PSI/LightTree IL and box matrix is clean; the full-suite result is recorded in Branch state.
- `contentDeepEquals` now implements the separate common/JVM recursive contract for nullable
  generic arrays without changing their exact CLR vector ABI. One runtime helper recursively
  compares nested reference vectors, routes matching supported primitive vectors through shallow
  content equality, and uses `Intrinsics.AreEqual` for scalar elements; mixed primitive kinds fail.
  Null/same-reference/length handling, Kotlin scalar equality, NaN canonicalization, signed-zero
  distinction, concrete/projected vectors, open invariant `Array<T>`, and evaluation order are
  pinned. Same-reference cyclic arrays return immediately; no cycle detector is added because the
  common stdlib explicitly leaves self-containing arrays undefined. `arraydeep_s1` ran on modern
  CoreCLR and Framework, the exact golden assembles under both ILAsm versions, and the focused
  PSI/LightTree IL and box matrix is clean.
- `contentHashCode` and `contentDeepHashCode` now share the List-compatible 31-fold and the
  Kotlin-owned scalar hash boundary. The shallow operation covers generic arrays plus all five
  supported primitive arrays and keeps nested arrays identity-hashed; the deep generic-array
  operation recurses into reference vectors and shallow-hashes supported nested primitive vectors.
  Null, empty, primitive, scalar, NaN, signed-zero, equality/hash invariants, open generic vectors,
  and one-time receiver evaluation are pinned. Existing data-class array hashing delegates to the
  general shallow helper through its compatibility wrapper. Self-containing deep arrays remain
  undefined with no cycle detector. `arrayhash_s1` ran on modern CoreCLR and Framework, the exact
  golden assembles under both ILAsm versions, and the focused PSI/LightTree IL and box matrix is
  clean.
- `contentToString` and `contentDeepToString` now share the Kotlin-owned scalar string boundary.
  The shallow operation covers generic arrays plus all five supported primitive arrays and keeps
  nested arrays identity-rendered. The deep generic-array operation recursively renders supported
  nested arrays and tracks only the active reference-array path in a Framework-compatible
  `ArrayList`: actual cycles become `[...]`, while repeated non-cyclic children render fully each
  time. Null, empty, scalar, primitive, Double special values, nested references, the canonical
  cyclic graph, repeated children, open generic vectors, and one-time receiver evaluation are
  pinned. Existing data-class array rendering delegates to the general shallow helper through its
  compatibility wrapper. `arraystring_s1` ran on modern CoreCLR and Framework, the exact golden
  assembles under both ILAsm versions, and the focused PSI/LightTree IL and box matrix is clean.
- The last module-local runtime helper has moved into the established runtime boundary. Generated
  code now calls the cross-assembly member
  `Kotlin.Runtime.Internal.DoubleFormatting.DoubleToString`; its CLR type and method are public
  because consumers live in other assemblies, but the reserved namespace identifies it as a
  compiler/runtime contract rather than Kotlin-facing API. The runtime owns the helper body once,
  generated modules no longer contain `<KotlinIl>`, and the now-empty method/class helper-tracking
  path has been removed. `runtimehelper_s1` compiled the same caller and runtime with modern 10.0.9
  and Framework 4.8 ILAsm; all four same-target/cross-runtime pairings printed
  `1.0,-0.0,1.0E20,1.0E-5`. All seven affected exact goldens assemble cleanly under both ILAsm
  versions, and the read-only PSI/LightTree IL matrix is clean. The fresh full DotNet suite remains
  380/0/0/0 across eight XML files.
- The user requested continued autonomous feature work until explicitly stopped. The next repair
  and feature audits below have not yet landed.
- The current uncommitted architectural repair replaces the raw-vector specialized-array ABI with
  Kotlin-owned sealed wrappers while retaining natural `T[]` substitution for generic arrays. It
  also establishes the classified CLR exception model and the three-tier visibility/friend/compiler
  ABI model in their draft ADRs and implementation slices. Canonical Kotlin array signatures,
  content operations, cross-module calls, explicit C# vector facades, foreign exception identity,
  user exception ancestry, producer IVT, and marked compiler ABI have focused PSI/LightTree and
  netstandard2.0 -> net48/net10 integration coverage. Repeated inbound C# vector conversion now
  uses a runtime-owned `ConditionalWeakTable<vector, wrapper>` per specialized type: same-call,
  cross-call, and Kotlin-wrapper -> vector -> wrapper identity are stable while distinct vectors
  remain distinct. Outbound projection registers the canonical wrapper; ordinary Kotlin
  construction pays no table cost. `arrayinternprobe_s1` assembled and ran the exact lock/table IL
  on Framework and CoreCLR, and the cross-language integration test executes the sequential and
  concurrent identity matrix on both profiles. The fresh complete DotNet FIR2IR matrix is
  530/0/0/0 across eight XML files; the full `DotNetLibraryIntegrationTest` is 28/0/0/0 with no
  toolchain skips. `op_Implicit` remains a separate API-surface decision, not an identity blocker.
- P0-D now has a structured physical declaration-index superset comparator. Generated `net48` and
  `net10.0` stdlib pairs must contain every `netstandard2.0` logical declaration with the same
  assembly-independent CLR owner/member binding, identity scheme, name grammar, and no lower
  runtime-surface floor; profile-only additions are allowed. Synthetic negative coverage pins both
  missing and changed entries. The same production test now uses an isolated CoreCLR reflection
  verifier over the assembled PEs. Each executable-profile runtime/stdlib pair must retain the
  portable public/protected types, hierarchy edges, constraints, methods, fields, properties, and
  events with compatible access and overridability. Portable abstract interface slots may become
  modern DIMs. Normalized custom-attribute identities and constructor/named payloads on assemblies
  and exposed declarations must also retain the portable floor; `TargetFrameworkAttribute` is the
  deliberate profile-specific exception. The verifier is test data, not a compiler/assembler
  sidecar, and a target-owned `@TestOnly` hook produces standalone runtime variants for it. Raw
  attribute-blob encoding, MethodImpl rows, resources, and friend-only internals remain for the
  structured metadata audit.
- `docs/decisions/adr-profile-aware-interface-default-implementations.md` is accepted,
  and the non-generic implementation is now present. Portable profiles move each Kotlin interface
  body to a marked public `<DefaultImpls>` compiler-ABI helper, keep the CLR slot abstract, and
  give Kotlin classes hidden explicit MethodImpl forwarders. `net10.0` keeps the body as a real
  DIM and the same exact-call helper, with no class forwarder when the selected DIM is physically
  available.
- CoreCLR rejects a non-final interface method carrying a MethodImpl row. The backend therefore
  keeps a Kotlin-visible DIM overridable and emits a separate private `newslot virtual final`
  interface bridge to map inherited slots. A net10 interface inheriting an external portable
  helper-only default emits the corresponding final promotion DIM, maps it to the original slot,
  and calls the producer-recorded helper.
- Physical ABI schema 8 stores default body placement/helper owner/helper method on function
  records, independently stores the physical owner/method of the masked default-argument dispatcher,
  stores derived-interface promotions as structured `P` records, final generic-interface view
  adapters as structured `B` records, and hidden class MethodImpl forwarders as structured `W`
  records. Downstream lowering and whole-class shape validation
  traverse those records through arbitrary base-class depth before deciding whether a selected DIM
  is physically effective or is masked by an inherited class implementation; they never derive
  `<DefaultImpls>` or `$default` names from Kotlin declarations or infer producer lowering from its
  target profile. A `W` record is emitted in any profile whose class physically needs a helper-backed
  MethodImpl, including net10 consumers of portable interfaces. It is a dispatch fact rather than a
  callable portable-superset requirement. User-library helper bindings are scoped out of the
  separately emitted stdlib assembly, and surviving helper calls record their producer AssemblyRef
  through the normal codegen fixpoint.
- Provider selection is now based on the set of most-specific DIM providers, rather than a
  boolean “some promotion exists” test. Zero providers requires promotion/forwarding, one selected
  provider suppresses redundant emission even through an intermediate interface, and multiple
  incomparable providers require a resolving DIM or class forwarder so valid Kotlin does not reach
  CLR `AmbiguousImplementationException`.
- `DotNetLibraryIntegrationTest.testNet10PromotesPortableInterfaceDefaultAndSuppressesOnlyCoveredForwarders`
  passes with a netstandard producer, net10 promotion library, downstream consumer, portable
  producer forwarder executing on CoreCLR, an indirect single-provider inheritance chain, competing
  promotions resolved by a required class forwarder, a derived-interface diamond resolved by one
  new DIM, the required unpromoted consumer forwarder, and no redundant promoted consumer
  forwarders. It also proves the CLR-specific masking edge through an intermediate producer class:
  a class inheriting a portable compatibility MethodImpl and a more-specific net10 interface
  default receives one resolver bridge, so both interface views observe the net10 body. The test
  covers the inherited MethodImpl when its producer is portable and when a net10 intermediate
  assembly emitted it against the portable slot. A separately compiled subclass also inherits an
  implementation of the same selected default without redeclaring an identical MethodImpl. A real
  user-authored class override remains a Kotlin source-level conflict and follows the explicit
  Kotlin resolution. Both generated DotNet box variants for `interfaceDefaultImplementations.kt`
  also pass.
- The same cross-module test now executes qualified interface-super calls in a portable producer,
  in a net10 library against a portable producer, and between two net10 DIMs. Portable calls target
  the producer-recorded helper. A net10 DIM-qualified call also targets the helper, whose body uses
  plain nonvirtual `call` to the owning DIM; an IL assertion forbids `callvirt` for that exact
  helper edge. Ordinary calls on the same receiver still dispatch to the more-derived DIM, proving
  that exact and virtual paths have not been collapsed.
- The interface-default integration coverage now additionally proves that a separately compiled
  class override wins through both the portable base and promoted interface views, and that an
  explicit net10 reabstraction is not promoted. Both generated DotNet box variants execute local
  reabstraction through the original and redeclared interface views.
- Local interface-default box coverage also executes property getter/setter bodies, nested
  interfaces, ordinary default-argument calls, and omitted arguments dispatching virtually to a
  class override after helper-owned mask decoding.
- Common Kotlin rejects omitted arguments in any super call with
  `SUPER_CALL_WITH_DEFAULT_PARAMETERS`. Qualified-super coverage therefore supplies every argument;
  the .NET lowering does not define a target-specific masked-dispatcher extension for invalid IR.
  `DotNetExpressionCheckers` now registers the existing FIR checker through the metadata session
  factory's DotNet platform branch. The integration regression proves the standard diagnostic and
  compilation failure in both PSI and LightTree modes before FIR2IR.
- Cross-module coverage promotes portable property getter/setter bodies, records and binds the
  producer's masked dispatcher, and executes omitted/named arguments through inherited defaults and
  consumer overrides. A separate abstract interface method with default parameters proves that the
  dispatcher ABI is independent of default-body/DIM metadata. The focused integration test executes
  on CoreCLR and asserts three promotions and only the three physically required portable class
  forwarders.
  The same test asserts that no `<DefaultImpls>` class or helper function is published as an
  invented logical declaration key. Compiler helpers exist only as physical identities attached
  to real KLIB members; this corrected the earlier manifest leak while leaving their marked public
  compiler-ABI IL callable.
- The obsolete `interfaceDefaultBodyRejected` IL fixture has been replaced by
  `interfaceDefaultBodiesPortable`. Its exact portable abstract-slot/helper/`$default`/hidden-
  forwarder IL passes both PSI and LightTree variants and assembles successfully; do not restore
  the former whole-interface rejection expectation.
- Modern C# DIM consumption is now an executed integration lane. The provisioner installs a pinned
  10.0.100 SDK beside the 10.0.9 runtime and ILAsm; discovery binds its `csc.dll` and net10
  reference pack without making Kotlin assembly production depend on Roslyn.
  `testModernCSharpConsumesProfileAwareGenericInterfaceDefault` compiles invariant and covariant
  generic Kotlin interfaces for all three profiles. C# classes that declare no methods inherit
  both the ordinary typed DIM and the distinct `__KotlinExact<int>` operation-view DIM, executing
  `echo(int): int` without an erased-result cast. The same sources fail with `CS0535` against
  `netstandard2.0` under Roslyn and against `net48` under Framework csc.
- Generic interface and generic-method defaults now follow the coordinated split-view ABI. Every
  declaration has one canonical semantic body and one stable helper identity. Portable helpers own
  the moved body; on `net10.0` one strongly typed DIM owns it. The exact view is the normal
  strongly typed C# surface, and erased or declared-variance slots use final MethodImpl adapters
  which dispatch virtually to that body. The net10 helper selects the same DIM nonvirtually.
  Neither secondary views, promotions, class bridges, nor class forwarders copy the body.
- A netstandard generic producer, net10 generic and closed non-generic promotion interfaces, a
  separately compiled closed implementor, and a separately compiled net10 consumer execute narrow
  typed, method-generic, exact, widened-erased, direct-portable, producer, and closed-promotion
  paths in
  `DotNetLibraryIntegrationTest.testGenericInterfaceDefaultsAcrossPortableAndNet10Assemblies`.
  The closed promotion puts all canonical/declared/exact MethodImpl adapters on its single CLR
  interface owner, and the implementor proves those DIMs suppress helper-backed class forwarders.
  The same producer now declares a closed non-generic interface override with one DIM body and a
  complete final view-adapter bundle. Schema-8 `B` records let the separately compiled implementor
  inherit that bundle without duplicate `value` bridges; the consumer executes the overriding body
  through the derived, typed producer, and widened producer views. Portable IL goldens cover
  helper-owned generic bodies plus canonical/declared/exact class bridges. Local net10 boxes cover
  direct, inherited, reabstracted, variant, invariant, value, widened, and closed-interface-
  override views.
- Generic capability calls now obtain the canonical erased-fallback method name from the bound
  physical function record before considering a Kotlin-owned hash. This fixed runtime collection
  interfaces whose stable CLR members are `HasNext`, `Next`, or other mapped names: the old
  synthesized `__KotlinErased__` token assembled but failed lazily with `MissingMethodException`.
  Exact typed capability dispatch still runs first and reaches the strongly typed virtual slot;
  only the fallback binding changed. `iterables` and the complete collection box matrix pass in
  both PSI and LightTree.
- An owner-relative method bound such as `<R : T>` remains part of the logical Kotlin/KLIB
  signature but is deliberately omitted from executable CLR views of a split generic interface.
  Direct runtime probes established both failure modes: a variant CLR interface containing that
  GenericParamConstraint fails type loading, while retaining it only on the invariant exact DIM
  makes a valid widened Kotlin call fail verification. Portable closed value-type bridges cannot
  preserve the substituted constraint either. The exact ABI still keeps typed arguments/results,
  all other representable constraints remain physical, and a future C# convenience facade may
  restate the bound only as an export adapter.
- The full-suite audit after general library linking exposed two earlier regressions. The new
  core-library profile had split the established empty `mscorlib` AssemblyRef over two lines,
  invalidating every IL golden; the shared renderer now preserves the canonical one-line form.
  More importantly, the Iterable continuation had broadened `IMPLICIT_CAST` and bare-local
  smartcast recovery to every reference pair. That emitted impossible `castclass` conversions for
  `Producer<Int> -> Producer<Any>` and analogous open/value variance. Smartcast recovery is now
  limited to a target that is physically assignable to the source view; the variant-interface
  rejection and Iterable execution pins both pass. This is deliberately conservative. The future
  Kotlin-common direction is the per-declaration erased identity plus same-object exact capability
  in `docs/decisions/draft-adr-variant-interface-abi.md`, not broader casts. Settle that model before
  committing a broad variant stdlib abstraction such as `List<out T>`.
- Covariant returns now follow the accepted floor-compatible ABI on all profiles. The source
  declaration owns one precise virtual slot; each physically wider ordinary class or interface
  slot receives a private final forwarding method with an explicit `MethodImpl`. Forwarders call
  the precise implementation virtually and contain no copy of the Kotlin body. Direct class and
  property overrides, inherited class implementations satisfying interfaces, multilevel chains,
  abstract class/interface refinements, generic methods, `Int?`-to-`Int`, and same-carrier
  reference nullability execute in both generated box pipelines. An exact method is marked
  `newslot` only for a direct physical class-return mismatch; a transitive wider ancestor must not
  split an otherwise matching immediate abstract slot. Multilevel classes bridge only their
  immediate wider class slot; inherited bridge chains route older class slots without quadratic
  rebinding. Interface slots remain explicit because abstract interfaces own no forwarding body.
- `testCovariantReturnsAcrossPortableLibraryBoundary` compiles a `netstandard2.0` producer and
  separate `net48`/`net10.0` consumers. Both Kotlin applications execute base, interface,
  inherited-interface, abstract, generic, and multilevel dispatch. Framework csc and modern
  Roslyn compile and run direct consumers; reflection confirms the precise methods are public and
  every compiler bridge is private. The cross-module case also pins that a concrete external
  target is selected from metadata and called normally—the consumer never needs the producer's IR
  body. Its covariant-default case pins profile-aware ownership: `net48` uses a hidden precise
  helper forwarder plus a class-owned wider-return adapter; `net10.0` places the wider-return
  MethodImpl on the derived DIM interface, emits no class forwarder or duplicate class adapter,
  and lets a foreign C# implementation inherit the selected default. ABI schema 12 records every
  covariant MethodImpl as a structured `R` entry keyed by logical owner and inherited member. A
  third Kotlin assembly consumes that record and emits no duplicate class bridge; a foreign C#
  implementation of the external interface inherits the same DIM.
- The accepted open-nullable ADR has no string-bound ordering exception now. In type mapping, the
  outer-open-`T?` object rule precedes `isDotNetStringType`: a method-level `T : String` therefore
  exposes `T?` as `object`, while non-null `T` retains its established `string` slot. `!!` checks
  the object for Kotlin nullability before `castclass string`. The ordinary box suite and the
  `netstandard2.0` open-nullable producer consumed by net48/net10 Kotlin and modern C# pin the
  null, non-null, and recovery paths.
- The IL-text handler now assembly-validates every emitted module, not only its textual golden.
  All 158 cases in both FIR parser pipelines (316 tests) assemble automatically with each
  available Framework and modern ILAsm; strict toolchain runs require both. The modern pass uses
  the unchanged net48 golden as an assembler-compatibility oracle, not as net10 profile evidence.
  Cross-platform hosts without either tool retain deterministic text comparison without claiming
  assembly validation. This closes P0-F's assemble-all accepted-goldens item and dual-assembler
  source acceptance; assembly acceptance alone is not runtime-pairing evidence.
- `DotNetLibraryIntegrationTest.testNet48AssemblerMatrix`
  turns that source oracle into runtime evidence. The exact compiler-produced net48 application,
  stdlib, and runtime IL are each written by Framework and modern ILAsm; all eight artifact-writer
  combinations execute on Framework CLR 4 and CoreCLR and print `OK`, for 16 executions total.
  Framework execution uses the signed Windows PowerShell CLR 4 host to load and invoke the
  unchanged entry point, avoiding direct unsigned-exe activation without altering the artifact.
  The backend exposes only `@TestOnly` explicit-writer hooks; canonical production selection
  remains profile-owned. This closes retained net48 runtime-writer substitution while keeping
  net10-specific evidence separate. `testNet10AssemblerBoundary` proves why: Framework ILAsm 4.8
  rejects a non-abstract interface method body, while the profile-selected modern writer executes
  the same DIM-bearing program on CoreCLR. Requiring a net10 cross-writer matrix would therefore
  constrain modern codegen to Framework capabilities and is explicitly rejected. The failed
  alternate-writer attempt also exposed that ILAsm may leave a partial PE on nonzero exit;
  `runIlasm` now deletes that PE and its runtimeconfig before reporting failure.
- The generated semantic box matrix now runs the same 116 cases for `net48` and `net10.0` in both
  PSI and LightTree pipelines. Net48 artifacts use Framework ILAsm and execute on real CLR 4 via
  the signed Windows PowerShell host, which loads and invokes the exact managed entry point; the
  existing net10 lane remains dll plus signed `dotnet exec`. Both share output checking,
  dependency checks, timeouts, and the strict SAC skip/fail contract. The standalone net48 run is
  232/0/0/0 across six XML suites; the expanded strict DotNet matrix is 780/0/0/0 across fourteen
  XML suites. Generated Java remains build output and is not committed.
- A full 35-test library-integration audit exposed an NPE in external interface default-argument
  dispatcher synthesis: the synthetic static binding forced a null dispatch type into the shared
  IR receiver copier. The binding now follows the structured function record. Instance members
  copy the selected owner receiver and declaration type parameters before method parameters;
  truly static companion/top-level records copy neither, and inconsistent IR/metadata shapes fail
  explicitly. A new portable generic-interface producer executes its recorded dispatcher from
  separate net48 and net10 consumers. The expanded integration class is 36/0/0/0; this enforces,
  rather than changes, the accepted interface-default ADR's generic-parameter mapping rule.
- `:compiler:backend.dotnet:dotNetTest` is now the build-owned strict commit gate. It combines the
  780 FIR/IL/semantic tests with all 21 generated CLI tests and all 44 library-integration tests,
  enables required-toolchain behavior in both owner projects, and currently records 845/0/0/0
  across 16 JUnit XML suites. The tests-integration child is privately named `dn`: Gradle embeds
  the task name in test temporary roots, and even the ordinary four-character `test`/`dnet` shape
  can reach exactly 260 characters for the longest CLR4 execution path when the random suffix has
  20 digits. The aggregate task is the supported entry point. This is validation infrastructure,
  not an ABI decision, so it updates evidence rather than an ADR.
- The foreign-exception cross-language integration now sends one exact C# exception through Kotlin
  classification, catch/return, and catch/rethrow on both runtime profiles. C# observes the same
  object, exact type, message, `InnerException`, `Data`, and non-empty CLR stack trace throughout;
  after Kotlin source `throw e`, the trace names the Kotlin rethrow site as required by the accepted
  plain-`throw` policy. This closes the raw-state/rethrow portion of P0-F without introducing a
  wrapper or pretending Kotlin has CLR bare-rethrow syntax.
- The portable exception library now owns a classifier-backed catch function and receives values
  constructed by separately compiled net48 and net10 applications. It distinguishes an
  application-defined subclass of the portable exact root, a mapped `IllegalStateException`, a
  broad `Exception`, and an exact `Error` identically on both runtimes. This closes the ADR's
  portable-library-catching-application-values validation item rather than merely proving that a
  portable library can construct exceptions for consumers.
- The raw C# verifier now calls the runtime exception classifier directly with null, foreign,
  mapped-runtime, fatal, and exact Kotlin objects for ids `Int32.MinValue`, `-1`, `0`, every
  assigned id 1 through 14, 15, and `Int32.MaxValue`. Both runtime variants always return a Boolean
  and never throw. Together with the helper's branch/`isinst`-only implementation, this closes the
  filter-totality validation item; no user virtual member or exception payload is consulted.
- Physical-name grammar version 2 now disambiguates Kotlin methods whose distinct logical
  exception parameters share the `System.Exception` carrier. Every affected method is named from
  its owner-independent Kotlin signature before any collision is observed, so later overloads do
  not rename existing methods and overrides retain their slots. A portable producer executes
  direct and nested-generic overloads through top-level, class-virtual, ordinary-interface, and
  split-generic-interface calls from separate net48 and net10 consumers. The accepted classified-
  exception ADR records the representation rule. A generic-base `f(T)` override at
  `T = Throwable` also retains and executes through its unmangled base slot; when that same body
  implements a directly exception-typed interface slot, an explicit `MethodImpl` maps the
  differently named slot without copying the body. Non-renamable constructor collisions remain a
  separate compiler-ABI factory decision. The strict gate is
  840/0/0/0 across 16 XML suites (780 FIR/IL/semantic plus 60 integration/CLI).
- A separate portable signature-position lane returns `Throwable`, `Exception`,
  `RuntimeException`, and `Error`, mutates public properties of each logical type, and carries a
  runtime exception through a nested generic return. Net48 and net10 consumers preserve logical
  classification and exact reference identity across the KLIB/DLL boundary. Direct IL assertions
  pin the four shared `System.Exception` property carriers and grammar-v2 setter names. This is
  validation of the accepted model rather than a new representation decision; foreign/narrow C#
  admission, array/callable positions, and constructor collisions remain open. The resulting
  strict baseline is 841/0/0/0 across 16 XML suites.
- A portable array/callable lane overloads on `Array<RuntimeException>` versus `Array<Error>` and
  on function types accepting those categories. Direct IL assertions pin distinct grammar-v2
  names after nested carrier mapping; net48 and net10 consumers select every overload and receive
  the exact original object back through the callback. This validates Kotlin-owned array and
  function-type positions without deciding foreign projection guards. The strict baseline becomes
  842/0/0/0 across 16 XML suites.
- Cancellation now resolves the classifier model's sibling-root forcing case.
  `CancellationException` maps exactly to `OperationCanceledException`, while its logical
  `IllegalStateException` parent uses the shared `System.Exception` carrier and still constructs
  `InvalidOperationException`. Foreign `OperationCanceledException` and `TaskCanceledException`
  objects remain unchanged and classify under Cancellation/IllegalState/Runtime/Exception, never
  Error. A portable producer plus Kotlin and C# consumers pin exact catches, parent returns,
  construction, message/cause identity, Kotlin subclass ancestry, and object identity on both
  application profiles. Classifier id 14, runtime surface level 7, and physical ABI schema 13
  reject the incompatible unshipped mapping. The
  resulting strict baseline is 843/0/0/0 across 16 XML suites.
- A portable generic-interface library now has an executable ordinary-C# implementor contract on
  both Framework CLR 4 and CoreCLR 10. One C# object implements
  `Collection__KotlinExact<int>` plus its inherited canonical identity and coordinates typed
  `Contains(int)` with the documented `ContainsErased(object)` false barrier. A second implements
  a user `@UnsafeVariance` exact/canonical pair and lets a wrong shape throw the original CLR
  `InvalidCastException`; Kotlin catches it as `ClassCastException`, proving that collection
  barriers are not generalized to user annotations. Structured KLIB records select the foreign
  method/owner spellings. The variant-interface ADR records this validation; generated C#
  implementor tooling and the broader foreign/clash matrix remain open.
- Generic-interface publication now has explicit collision regressions for both typed capability
  views and generated type names. A property accessor colliding with a user method on the
  declared view, the same shape colliding only on the invariant exact view, and a user interface
  occupying its neighbor's generated exact TypeDef identity each fail with a specific diagnostic
  and produce neither KLIB nor DLL. The existing atomic canonical/declared/exact registration and
  per-view member gates required no representation change; the variant-interface ADR records the
  evidence while keeping overload, inheritance, and reserved-member cases open.
- Commit `898f995f5` extends that publication gate across one physical inheritance edge. A
  frontend-valid derived generic interface whose local property accessor and distinct inherited
  method map to the same declared-capability slot now fails atomically, while a genuine Kotlin
  property override remains valid. The gate walks the emitted capability graph rather than
  assuming every logical supertype is present on every physical view. The variant-interface ADR
  records whole-declaration rejection as temporary; stable typed-slot disambiguation remains the
  final direction, and inherited-only collisions are still open. The strict baseline remains
  845/0/0/0 across 16 XML suites.
- The next collision slice also rejects a derived generic interface that inherits a method and a
  property accessor with distinct Kotlin IR names but one declared-capability CLR identity. A
  same-name two-parent intersection remains admitted because it may be one Kotlin override
  obligation; the following slice executes its parent slots and identifies the remaining derived
  adapter. The owning variant-interface ADR and `AGENTS.md` state that boundary explicitly.
- The same-name intersection is now executable evidence rather than an assumed safe case. A
  portable `Intersection<T> : IntersectionLeft<T>, IntersectionRight<T>` and one implementation
  run through both parent slot bundles and the derived Kotlin view from Kotlin and C# consumers on
  Framework CLR 4 and CoreCLR 10, preserving identity and one result. The implementation already
  emits explicit canonical/declared bridges for both logical parent slots. Direct C# lookup on
  `Intersection<int>` was demonstrably CS0121 before the adapter, motivating the stable
  producer-recorded derived typed slot implemented in the following slice. The strict baseline
  remains 845/0/0/0 across 16 XML suites.
- Physical ABI schema 14 now has the dedicated producer/consumer-neutral record needed by that
  adapter. `GenericInterfaceIntersectionSlot` records the typed owner path, declared/exact view,
  source-facing CLR method name, logical owner, and sorted unique contributing member keys; its
  stable index hashes the normalized group. The codec round-trip is pinned. Do not reuse the
  existing `B` view-bridge record: `I` describes an additional slot obligation, while `B`
  describes an implementation already supplied by an interface.
- The emitter now writes and consumes `I` for the conservative bodyless intersection slice:
  generic parents, no default bodies, and one resolved substituted signature.
  Parameterless, value-parameter, and method-generic members with owner-independent constraints are
  covered; method parameters and bounds are normalized positionally before admission. Properties
  emit recorded accessor obligations plus a real derived CLR property row; a mutable property is
  admitted only when getter and setter both exist on that same view. KLIB already retains the
  accessor/property association, so no second physical record is needed. Parent-parameter
  permutations and contributors reached through bodyless intermediate interfaces are normalized
  through the derived owner and admitted when they resolve to one signature. At least two direct
  branches must contribute; a parent already containing the complete intersection suppresses a
  redundant descendant slot and schema record. The producer emits one abstract source-named method
  on the first common declared/exact typed view. One deterministic parent forwarding bridge
  receives an additional `MethodImpl`; a separately compiled `Intersection<Any>` implementation refining `read()` to
  `String` proves the record is needed and consumed. Kotlin parent/derived calls and direct C#
  derived calls execute on Framework CLR 4 and CoreCLR 10 with one object and one body. Exact-only
  unsafe input dispatch is covered through the invariant exact capability. Owner-dependent generic
  constraints, split-view property, default, and non-identical resolved-signature cases remain
  open. Property accessors are
  filtered atomically so a Kotlin `var` never acquires only a derived getter while its inherited
  setter remains ambiguous.
- The split generic-interface ABI now has an adversarial cross-module arity pin beyond both common
  machine-word boundaries. A `netstandard2.0` producer declares a 65-parameter covariant interface
  with a high-index unsafe operation, its invariant exact view, and one same-object implementation.
  Kotlin and C# consumers on Framework CLR 4 and CoreCLR 10 verify all 65 metadata parameters,
  declared/exact variance, exact-capability implementation, identity-preserving widening, typed
  reads through canonical fallback, and the original `InvalidCastException` for a wrong-shaped
  high-index argument. The representation uses positional collections, not a fixed-width mask;
  no ABI or ADR rule changed. The strict baseline becomes 844/0/0/0 across 16 XML suites.
- The same portable variant-interface fixture now completes the required one-through-four
  parameter matrix with `Quad<in I, out O, X, out N>`. Its implementation combines a reference
  input, primitive result, invariant reference state, concrete `Int?` covariant result, and
  exact-only unsafe primitive input. Both Kotlin and C# consumers verify the four CLR variance flags,
  invariant exact parameters, same-object simultaneous widening/narrowing, open generic
  pass-through, canonical fallback, and wrong-shape cast failure on both application profiles.
  This is additional evidence for the existing ADR and leaves the strict count at 844/0/0/0.
- The quad forcing case now uses `Int?` for its second covariant result. Its implementation exposes
  `Nullable<int>` on the exact capability, while widening that argument to `Any?` retains the same
  object and deliberately takes canonical fallback because CLR value-type variance cannot perform
  the conversion. Kotlin and C# execute the boxed result and reflect the exact nullable argument
  on both profiles. This tightens evidence only; the 844-test baseline and ADR rule are unchanged.
- Canonical-only generic-interface providers now execute against a separately compiled portable
  Kotlin producer on both application profiles. A raw CLR class implements only the non-generic
  `Source` identity and its producer-recorded erased slot—no declared or exact construction. The
  portable `readAsAny` function invokes that object unchanged on Framework CLR 4 and CoreCLR 10,
  proving capability absence is a supported fallback rather than a load-time requirement. No ABI
  rule changed; the strict baseline becomes 845/0/0/0 across 16 XML suites.
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
    `arrprobe`, `arraycopyprobe`, `arrayinternprobe` are taken). Keep
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

- **Run tests:** `./gradlew :compiler:backend.dotnet:dotNetTest --rerun -q --no-daemon` is the
  strict commit gate. Do NOT trust the quiet console alone. Verify the JUnit XML under
  `compiler/fir/fir2ir/build/test-results/dotNetTest/` and
  `compiler/tests-integration/build/test-results/dn/`; the current total is 845 tests across 16
  files with zero failures, errors, or skips. Strict mode turns missing tools and SAC refusal into
  failures. The internal `dn` task name preserves CLR4/Framework ILAsm path-length budget; invoke
  the backend-owned aggregate rather than treating that child as public API.
- **Update goldens:** add/modify the `.kt`, then run with `-Pkotlin.test.update.test.data=true`
  (QUOTE the whole `-P...` argument in PowerShell or it gets mangled), then READ the generated
  `.txt` critically — auto-generated goldens will happily pin broken output (this happened: a
  golden once pinned duplicate IL methods that ilasm rejects).
- **Before every commit:** a fresh aggregate gate + XML verification; `git status`
  shows only intended files (no scratch test data — a prior session leaked `zzrev*` files);
  the IL-text suite automatically assembles all net48 goldens with every available supported ILAsm.
  Still assemble and execute profile-specific net10 output, cross-assembler cases, and integration
  artifacts manually when the relevant feature is not represented by that suite.
- **Toolchain** (modern, pinned 10.0.9): `%LOCALAPPDATA%\kotlinc-dotnet\toolchain\ilasm\ilasm.exe`
  and `...\toolchain\dotnet\dotnet.exe`. Run a dll: put
  `{"runtimeOptions":{"tfm":"net10.0","framework":{"name":"Microsoft.NETCore.App","version":"10.0.0"}}}`
  in `x.runtimeconfig.json` next to it, then `dotnet.exe exec x.dll`. Repair with
  `compiler/ir/backend.dotnet/tools/provision-dotnet-toolchain.ps1`.
- **Self-review adversarially.** The process that repeatedly caught real bugs here: after
  implementing, actively try to break your own gates with hostile Kotlin constructions, assemble
  the emitted IL, and run dispatch shapes on the real CoreCLR. Budget real time for this.

## Task menu (recommended order)

1. **Prototype the variant-interface representation needed by the real stdlib.** Use
   `docs/decisions/draft-adr-variant-interface-abi.md`: erased Kotlin identity, declaration-owned
   exact capability, no subtype-conversion wrappers. Keep the current reified path and rejection
   until the primitive/open/reference, identity, cross-module, and dual-runtime matrix proves the
   replacement.
2. **Turn the portable pair into the build-owned .NET stdlib input.** Discovery already uses
   `lib/dotnet/netstandard2.0`; add an explicit host-capability-aware producer/install task. Do not
   make cross-platform `distKotlinc` depend unconditionally on a host ILAsm. Then compile the
   generated common stdlib sources plus narrow .NET actuals through the ordinary library producer
   instead of expanding the handwritten bootstrap corpus.
3. **Grow collection abstractions only from a concrete stdlib implementation need.** Reuse the
   table-driven erased-interface bridge policy for the next ordinary collection implementation;
   do not add a runtime interface speculatively or map imported CLR collection interfaces as part
   of Kotlin-owned stdlib bootstrapping.
4. **Move resolution-only stubs behind the real stdlib boundary incrementally.** A declaration
   should become emitted Kotlin code only when its implementation is supported and tested; keep
   platform operations in the intrinsic registry where the mature JVM stdlib does so.

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
