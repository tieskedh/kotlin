# Pending upstream impact review — 2026-08-03

## Status and scope

This is a read-only impact snapshot, not an ABI decision and not evidence that
the pending upstream range has been rebased or verified. It reviews every one
of the 179 commits in:

```text
733a49b39d4daaf81ac61c7d9c73ffaf81ef4875..76ca9aa1af7247c4f091f2f0d10c6f25b6fa80b9
```

`733a49b39` is the upstream base already integrated by the private `dotnet`
branch. `76ca9aa1af` was `origin/master` after an explicit fetch on 2026-08-03.
The earlier 161-commit range ending at `733a49b39` is already integrated and is
covered by [`upstream-sync-2026-07-30.md`](upstream-sync-2026-07-30.md).

For this review, every commit subject and changed-path set was inspected. The
patches were then inspected for every compiler, FIR, IR, KLIB, metadata,
Analysis API, stdlib, test-infrastructure, Gradle/KMP, BTA, annotation, value
class, callable-reference, or interop candidate. Commits that are not an
implementation candidate are still accounted for in the thematic ledger at
the end; nothing in the 179-commit range is silently omitted.

A virtual merge of the current `dotnet` head with `76ca9aa1af` was also
constructed with `git merge-tree`. Upstream changes 2,519 paths and the target
branch changes 853 paths relative to the common base. Eleven paths overlap,
but only one produces a textual conflict:

```text
compiler/tests-integration/build.gradle.kts
```

The conflict is understood: upstream removes the former Javac integration
dependencies while the target adds `cli-dotnet`, factors shared integration
test configuration, and adds the short `dn` task. Resolution must retain the
.NET fixture and `dn` task while accepting removal of the Javac dependencies.

The complete overlap audit is:

| Overlapping path | Pending upstream commits | Expected treatment |
| --- | --- | --- |
| `compiler/arguments/resources/kotlin-compiler-arguments.json` | `73a5a163fc`, `23c730368e`, `029ad72aa9`, `a605ee48aa` | Regenerate/merge shared arguments; retain generated .NET arguments; no semantic conflict expected |
| `compiler/build-tools/kotlin-build-tools-api/api/kotlin-build-tools-api.api` | `73a5a163fc`, `a605ee48aa` | Regenerate the public BTA baseline after merging; do not hand-edit |
| `compiler/cli/cli-base/build.gradle.kts` | `e40661a69e` | Accept the test-input-check plugin rename beside the .NET module dependency |
| `compiler/fir/fir2ir/build.gradle.kts` | `e40661a69e` | Accept the plugin rename beside the .NET backend fixture dependency |
| `compiler/fir/fir2ir/src/org/jetbrains/kotlin/fir/backend/Fir2IrVisitor.kt` | `523a8a0a58` | Accept explicit return types; the .NET exhaustive-when lookup remains orthogonal |
| `compiler/fir/fir2ir/testFixtures/org/jetbrains/kotlin/test/TestGeneratorForFir2IrTests.kt` | `7dee81dcc6` | Retain .NET model declarations while accepting removal/relocation of scripting models |
| `compiler/tests-common-new/testFixtures/org/jetbrains/kotlin/test/runners/AbstractDirectivesValidatorTest.kt` | `87a0aff888` | Accept `@SmokeTest`; retain `.NET` in the backend coverage calculation |
| `compiler/tests-integration/build.gradle.kts` | `8e6c2b44d9`, `0fe5e3faac` | The sole textual conflict: remove Javac/test-input-check remnants and retain the `cli-dotnet` fixture plus `dn` task |
| `compiler/tests-integration/tests/org/jetbrains/kotlin/cli/LauncherScriptTest.kt` | `c3f05617ed` | Accept scripting test relocation; retain the unrelated .NET launcher assertions |
| `repo/kotlin-build-helpers/src/CompilerModules.kt` | `63b05745bc` | Remove Javac-wrapper registration; retain .NET compiler modules |
| `settings.gradle.kts` | `63b05745bc`, `8a2665237c`, `0fe5e3faac`, `7fa08d91b8` | Accept module removals/moves; retain the .NET module registrations |

## Executive verdict

The range is low-risk for the current physical CLR ABI, but materially relevant
to the target's direction. It contains one mechanical rebase conflict, three
near-term architectural alignments, and four longer-term constraints:

1. The newly shared callable-reference helper may replace a target-local copy,
   but only after checking its CLR override visibility and method shape.
2. KLIB metadata can now post-process annotations as well as types. This is a
   useful foreign-import hook, not permission to replace authoritative Kotlin
   metadata with inferred CLR attributes.
3. Common stdlib starts using `returnsResultOf`. That strengthens the need for
   real contract metadata but also confirms that contracts cannot be bootstrapped
   honestly without `InvocationKind` and therefore enum support.
4. KMP unsafe incremental compilation is becoming explicitly target-specific.
   A later .NET Gradle task needs its own conservative switch and dependency
   model, not reuse of a JVM/JS/Wasm property.
5. Analysis API is learning target-visible JVM names as a first-class query.
   A usable .NET IDE path will likewise need CLR owner/name queries backed by
   the .NET physical ABI model.
6. Upstream keeps moving linkers and backends away from descriptors and PSI.
   New .NET linkage state must remain on FIR/IR/KLIB and explicit physical ABI
   carriers.
7. Rich annotation arguments are becoming fully stubbed and decompilable.
   The target's parameterless marker slice is therefore a foundation, not a
   reason to invent a separate annotation syntax or IDE representation.

No pending commit justifies changing the accepted exception, generic-class,
array, interface, annotation-authority, or self-describing-DLL decisions.

## 1. Rebase, build lifecycle, tests, and Gradle/KMP

### Upstream direction

The build and compiler lifecycle changes form four subthemes:

- the Javac wrapper and its obsolete configuration keys are removed;
- BTA reintroduces `ApplicationEnvironment` reuse for an in-process
  `BuildSession`, after separating CRI operations that do not require that
  environment;
- Test Federation gains more typed domain annotations and smoke-test
  configuration, while the test generator stops writing `()` for
  argument-free Java annotations; and
- unsafe common-source incremental compilation is split into JVM, JS, and Wasm
  switches instead of one cross-target property.

The API baseline also moves to stable stdlib API 2.4 and language version 2.2
becomes deprecated. These are repository-wide version policies and must flow
through shared argument machinery; .NET must not keep a target-only language
version fork.

### Meaning for .NET

The only proven textual rebase conflict is the integration-test Gradle file.
Its correct resolution is mechanical and does not require a design decision:

- remove `cli-jvm:javac-integration` dependencies with upstream;
- retain `testFixturesImplementation(project(":compiler:cli-dotnet"))`;
- retain the factored integration-test setup and short `dn` task; and
- retain the .NET smoke-test class filters.

After the rebase, regenerate the .NET runners. The changed annotation renderer
will remove empty parentheses from any argument-free annotations generated on
those classes. Do not hand-edit the generated output.

BTA's environment reuse does not yet add a .NET operation. When it does, the
operation must explicitly declare whether it uses the shared application
environment and all target compilation state must remain session-scoped. The
new CRI separation is evidence against attaching unrelated .NET metadata or
assembly caches to that environment merely because it is reusable.

The KMP change is a direct future constraint. A .NET Gradle compilation must
eventually have a target-specific unsafe-incremental property, defaulting to
`false` until common-source visibility, embedded-KLIB invalidation, friend
assemblies, physical DLL dependencies, and inline-body invalidation are all
tracked. It must not consume the JVM, JS, or Wasm switch.

### Action

- **At the next rebase:** resolve the single Gradle conflict as described,
  regenerate tests, and run the strict .NET aggregate gate.
- **Before Gradle/KMP product work:** define .NET task inputs and invalidation;
  only then add a target-specific incremental switch.
- **No target patch now:** version deprecation, BTA internals, Test Federation,
  and test-input-check changes are inherited from upstream.

## 2. Callable and property references

### Upstream direction

JVM fixes two bound-receiver/property-delegation cases, Wasm adds contextual
callable-reference support, and common IR extracts `addBoundValueAtOverride`
from the Native and Wasm implementations. The shared helper emits the
`boundValueAt(index)` override that exposes captured receivers to a runtime
function-reference base.

### Meaning for .NET

`DotNetCallableReferenceLowering` already extends
`AbstractFunctionReferenceLowering` and contains its own
`addBoundValueAccess`. Its field selection and last-field fallback are
structurally the same algorithm now moved to common IR. This is a genuine
deduplication candidate and supports the rule that .NET should mirror shared
backend architecture where CLR constraints do not differ.

It is not safe to replace mechanically during the rebase. The target currently
emits a protected final override against its synthetic
`FunctionReferenceBase`, while the shared helper derives the overridden member
from supertypes and uses common builder defaults. Before adopting it, compare:

- Kotlin visibility and generated CLR accessibility;
- final/virtual/newslot flags and `MethodImpl` records;
- zero, one, and multiple bound-value cases;
- receiver order for member extensions and contextual references; and
- equality/hash behavior across source and separately compiled KLIBs.

The two JVM bound-receiver tests are useful Common semantic candidates. Their
JVM lowering is not portable, but their evaluation order and receiver identity
should be adopted when the .NET property-reference surface reaches the required
feature closure. Context-parameter references remain parked with context
parameters; this range does not justify a partial implementation.

### Action

- **Small follow-up after rebase:** probe whether the common helper preserves
  the current CLR method contract. Reuse it only if the IL and runtime identity
  remain byte-for-byte or semantically equivalent.
- **Test later:** adopt the bound-receiver semantic cases; do not port JVM
  accessor patching.

## 3. Annotation syntax, stubs, metadata, and decompilation

### Upstream direction

The PSI/stub series makes unary, binary, and parenthesized annotation argument
expressions stub-based, stops loading ASTs for constant string concatenation,
adds the previously missing annotation argument kinds, and builds decompiler
stub trees from those arguments. Decompiled declarations now print annotations
one per line and before context parameters.

Separately, `kotlinx-metadata-klib` can invoke a read-strategy callback for
every `KmAnnotation`, paralleling its existing `KmType` callback.

### Meaning for .NET

This strongly validates the current ownership split:

- Kotlin annotation declarations and applications remain authoritative in
  KLIB;
- IDE/decompiler support must reconstruct the Kotlin view from the embedded
  KLIB, including constants and use-site placement;
- CLR custom attributes remain an additional foreign-language projection; and
- a future CLR importer may normalize explicitly recognized foreign
  annotations while constructing Kotlin declarations, but must not rewrite
  Kotlin-owned annotation truth based on the projected CLR attribute.

The just-completed parameterless marker slice deliberately does not support
constructor parameters or rich application values. Upstream now supplies a
good canonical test reservoir for the later argument-bearing slice: unary and
binary constants, parenthesized expressions, class literals, arrays, enum
entries, nested annotations, strings, unsigned values, and invalid
non-constants. Those should be enabled from shared tests as each dependency
becomes real; copying them into .NET-only test data would create a fork.

The `KmAnnotation` callback is useful for a future importer-produced KLIB or
interop tool, especially when a foreign signature annotation needs structured
normalization. It is not a storage channel for the physical ABI index and does
not supersede the DLL's explicit .NET ABI resource.

### Action

- **Now:** no expansion beyond marker annotations in the current feature.
- **Later annotation slice:** use the upstream annotation-value and decompiler
  cases as the semantic matrix, after enums, arrays, nested annotations, and
  constant encoding exist.
- **IDE programme:** load the embedded KLIB for Kotlin stubs and combine it with
  the explicit CLR ABI model; never infer the Kotlin declaration solely from
  custom attributes.

## 4. Contracts and the Common stdlib

### Upstream direction

Common stdlib now applies `returnsResultOf` to result-forwarding functions such
as `run`, receiver `run`, `with`, `let`, context functions, and corresponding
JVM resource helpers. The old `@IgnorableReturnValue` marker is removed where
the richer contract lets the return-value checker inspect the invoked lambda.
Compiler-internal helpers also begin using the new `holdsIn` and calls-in-place
contracts.

### Meaning for .NET

This is logically important but does not require a CLR runtime operation.
Contracts are frontend/KLIB semantics; ordinary backend code generation emits
the function body after FIR has consumed the effect model. Kotlin-produced
libraries must serialize the exact effects in KLIB so a later Kotlin consumer
recovers them. A Roslyn-compatible attribute may additionally describe an
exact CLR-understood subset, but `returnsResultOf` itself has no faithful
CodeAnalysis equivalent and must not be discarded because C# cannot express
it.

The change also settles a sequencing question. The authoritative
`libraries/stdlib/src/kotlin/contracts/ContractBuilder.kt` declares both the
contract API and `InvocationKind` as an enum. `Standard.kt` now reaches more of
that API. Marker annotation support alone therefore cannot unlock an honest
contracts-only product. The coherent source closure still requires:

1. the Kotlin-owned enum representation;
2. truthful `Enum<E>`/`Comparable<E>` behavior and enum entries;
3. the Common contracts declarations and compiler effect serialization; and
4. the Common `Standard.kt` and builder/abstract-collection consumers.

A target-only `InvocationKind` stub or stripped contract body would make the
stdlib compile faster but would violate Common authority and separate
compilation.

### Action

- **Ordering:** keep the enum/contracts/builder closure atomic; implement the
  enum foundation before claiming public contracts support.
- **After enum support:** compile the exact Common contracts sources, verify
  KLIB producer/consumer effects (including `returnsResultOf`), then add only
  independently exact CLR attribute projections.

## 5. KLIB linking, foreign import, and descriptor removal

### Upstream direction

The range continues several related migrations:

- imported C-interop signatures can be extracted after annotation and type
  post-processing;
- JKLIB adds a field-shadowing overridability condition and loads dependency
  KLIBs with `WITH_INLINE_BODIES` where whole-world deserialization is not
  required;
- Native test infrastructure models KLIB dependency graphs explicitly;
- the optional fake-override validator flag and its common checker are removed;
- JVM and Native remove more descriptor and PSI dependencies from backend and
  serialization code; and
- metadata adapts to the revised companion-extension representation.

### Meaning for .NET

The imported-signature work reinforces a two-layer foreign model: structured
foreign evidence is processed during import, then stable Kotlin signatures are
serialized. For CLR this belongs in the shared .NET metadata/import layer and
FIR importer. It must not be inferred later by the CIL emitter.

JKLIB field shadowing is a useful warning, not reusable code. CLR fields,
properties, events, explicit interface implementations, and hide-by-name/sig
rules differ from Java fields. The eventual CLR importer will need its own
precisely named overridability conditions with adversarial tests for a Kotlin
property versus inherited CLR fields/properties. Do not register JKLIB's Java
condition for CLR symbols.

`WITH_INLINE_BODIES` supports the existing .NET inline programme: dependency
loading should deserialize declarations lazily but retain inline bodies needed
by shared inlining. The target already proves ordinary embedded-KLIB inline
consumption in its own programme; after rebase, repeat separate-library tests
to ensure upstream strategy changes do not silently strip bodies. Foreign C#
assemblies have no Kotlin inline bodies, so no synthetic body should be
invented for them.

The Native dependency-DAG utility is implementation-local. Its direction is
applicable—library order, cycles, and transitive identity must be explicit—but
the .NET selected assembly/KLIB graph belongs in shared .NET import/library
infrastructure, not in `backend.dotnet` and not behind a dependency on Native.

The fake-override validator removal means .NET must not add or preserve a
target spelling of that obsolete flag. Correctness remains covered by shared
linking plus target physical-ABI, `MethodImpl`, interface, and separate-library
tests.

Descriptor removal remains a durable architectural constraint. New .NET
linker, importer, inline, and ABI state must use FIR/IR/KLIB identities.
References to shared `DescriptorVisibilities` value objects are not by
themselves descriptor-era linkage; introducing a `ModuleDescriptor` or
descriptor-backed symbol index would be.

### Action

- **After rebase:** rerun separate-library inline and interface/fake-override
  coverage; accept shared metadata-format changes without a target fork.
- **Importer programme:** design CLR-specific field/property/event
  overridability and annotation normalization in FIR/import infrastructure.
- **Architecture:** keep dependency graph and embedded-KLIB loading outside
  CIL emission; continue avoiding descriptor/PSI ownership in new code.

## 6. FIR semantics inherited by the target

### Upstream direction

FIR centralizes and caches sealed-subclass/complement calculations, fixes an
expect declaration being reported as its own actualization match, tightens
`equals`/`hashCode`/`toString` implementation checks, fixes Java `Object`
methods in SAM resolution, updates context-parameter terminology, and reports
missing explicit return types for all non-local functions in explicit-API
mode.

### Meaning for .NET

Most of this is automatically inherited because .NET uses shared FIR and the
shared Common/platform session split. It should not be copied into the backend.
The sealed-subclass work is relevant to later sealed classes and enum
exhaustiveness, but it adds no CLR representation requirement.

The Java-`Object` SAM fix is not directly portable. A future CLR delegate/SAM
importer needs an analogous, explicitly CLR-owned rule for members inherited
from `System.Object`, while Kotlin-owned functional interfaces continue to use
Kotlin `Any` semantics. This belongs in the FIR CLR importer, not in generic
call emission.

The explicit-API change means target source and generated public helpers should
keep explicit return types. The virtual merge shows no conflict in the target's
FIR2IR runner changes, but compilation after rebase remains the authoritative
check.

### Action

- **No backend port:** inherit FIR behavior.
- **Future CLR delegate import:** add `System.Object` filtering only when the
  foreign declaration model exists.
- **Contribution hygiene:** use explicit return types on non-local target
  functions that participate in public or explicit-API builds.

## 7. Analysis API, target-visible names, value classes, and export

### Upstream direction

Analysis API adds `javaMethodName`, deprecates the older getter/setter-only
queries, and shares value-class name-mangling knowledge with JVM light classes.
It deliberately returns `null` where no Java-visible method exists or where a
physical name is invented only during lowering. Swift export separately gains
KDoc output and keyword escaping. Value-class serialization and reflection
fixes cover static properties, field lookup, upper bounds, and mangled names.

### Meaning for .NET

This is the clearest IDE direction in the range. Kotlin symbols alone do not
always determine a foreign-visible method name. A future .NET Analysis API
surface needs target-specific queries such as the physical CLR owner, method
name, property/event shape, and whether a declaration is intentionally absent
from the C# surface. For library declarations those answers should come from
the accepted physical ABI metadata in the self-describing DLL; for current
source they should share the compiler's name/placement model. They must not
reuse `javaMethodName` or recompute names independently in an IDE plugin.

Swift export's KDoc and keyword work is relevant only as an export-layer
pattern. A later explicit C# facade should preserve documentation and escape C#
keywords in the Roslyn-facing presentation without changing Kotlin identity or
the canonical CLR implementation ABI.

The value-class fixes show why value classes remain parked. Their physical
member placement, static/instance split, generic upper bounds, reflection field
lookup, name mangling, and IDE presentation form one representation problem.
None of these commits provides a CLR answer, and implementing only boxing or
only a mangling scheme would create another incomplete ABI.

Analysis API deprecations also argue for a small .NET platform component rather
than proliferating one-off type predicates or JVM-shaped light-class hooks.

### Action

- **IDE design:** plan a .NET interoperability component backed by the same
  physical ABI model as compilation and export.
- **C# export:** keep KDoc/escaping in the presentation layer.
- **Value classes:** remain on hold until one proposal covers storage,
  signatures, inheritance restrictions, reflection, mangling, and separate
  compilation together.

## 8. JS/Wasm semantic changes

### Upstream direction

JS begins throwing `ArithmeticException` for integer division by zero behind a
JS-specific compatibility flag, fixes duplicated evaluation of `IrComposite`,
moves while-condition folding into an IR optimization, and JS/Wasm preserve
exceptions from top-level property initializers. The rest of the group is test
movement or temporary muting.

### Meaning for .NET

CLR integer division already throws for the relevant signed integral cases and
the target has explicit Kotlin exception classification and min-value division
tests. The JS flag exists because JavaScript previously differed; .NET should
not add that flag unless shared language policy later makes it cross-target.
The new shared `divisionByZero` box data is nevertheless a candidate for direct
.NET runner adoption after verifying all represented numeric types and constant
folding paths.

The duplicate-expression fix is JS codegen-specific but reiterates the general
single-evaluation invariant already exercised by .NET inline/non-local-return
tests. The top-level-initializer change aligns with the target's accepted static
initialization failure model; compatible shared tests may be adopted, but the
JS/Wasm runtime implementation must not be ported.

### Action

- **Test adoption:** assess the shared division-by-zero and top-level
  initialization cases after rebase.
- **No feature flag or JS lowering port:** retain native CLR behavior plus
  Kotlin exception classification.

## 9. Lombok annotation-driven synthesis

### Upstream direction

The Lombok series improves builder visibility, generic substitution,
`@Singular` handling, and annotation availability on its dummy Java model.

### Meaning for .NET

The code is JVM-plugin-specific and must not become a dependency of the CLR
importer. Its architecture is still instructive: annotation-driven declaration
synthesis belongs with the frontend/plugin foreign declaration model, and
visibility, generic substitution, nullability, and generated-body behavior are
tested together. The future CLR annotation importer should follow that
ownership pattern while implementing CLR semantics directly.

### Action

- **No port and no test enablement now.** Reuse only the architectural lesson
  when annotation-driven CLR declaration enhancement is designed.

## 10. Scripting reorganization

### Upstream direction

Twenty-two commits remove K1 scripting support, reorganize scripting modules
and generated tests, and deprecate the scripting IDE services module.

### Meaning for .NET

Kotlin/.NET scripting is not in the active target programme, so none of the
scripting implementation is relevant. The module moves do, however, contribute
to the one integration-test Gradle conflict because upstream removes former
dependencies and relocates tests. Resolve that shared build file as described
in section 1; do not add a .NET scripting task or compatibility shim.

### Action

- **Screened out except for rebase mechanics.**

## 11. Platform-local changes with no .NET action

Native test-data deletion, a Compose-plugin crash fix, one behavior-neutral
C-interop variable rename, and conversion of Native LLDB tests to goldens do
not alter shared Kotlin semantics, shared IR contracts, target architecture,
or reusable test data for the current .NET programme.

The LLDB conversion does not imply that .NET needs a debugger programme now;
when debugger support exists, CLR/PDB and debugger-host constraints will own
its test architecture.

## Required sequence after this review

1. Finish and commit the current marker-annotation feature against the current
   base; do not mix its semantic patch with an upstream rebase.
2. Rebase onto a deliberately selected upstream head, resolving only the known
   integration-test build conflict unless new upstream commits change the
   result.
3. Regenerate public arguments/API and generated test runners; inspect rather
   than accept bulk generated churn blindly.
4. Run focused checks for separate-library inline bodies, callable references,
   annotation/KLIB round trips, interface/fake overrides, and both test
   frontends, followed by the strict aggregate gate.
5. Consider the shared `addBoundValueAtOverride` cleanup as a separate bounded
   feature with IL inspection.
6. Continue the stdlib programme with the enum/contracts/builder dependency
   closure; do not introduce a contracts-only or `InvocationKind` stub.
7. Keep KMP incremental, Analysis API/IDE, full annotation arguments, value
   classes, context parameters, scripting, and debugger work behind their
   explicit programme gates.

## Exhaustive thematic commit ledger

The counts in this ledger sum to 179. A commit appears in exactly one group.
The group assignment records the primary impact category; the analysis above
mentions cross-cutting consequences where needed.

### Build, lifecycle, testing, and Gradle — 44

- `e60c9c38c2` — mark `CompilerReferenceIndexIT` as BTA-affected
- `8b4fd5c441` — fully verify IntelliJ on BTA commits
- `8e6c2b44d9` — delete compiler uses of the Javac wrapper
- `63b05745bc` — delete the Javac-wrapper module
- `b4e5908b2c` — delete `USE_JAVAC` and `COMPILE_JAVA` keys
- `6564c7334f` — Test Federation typo fixes
- `408f3c87cb` — stop using another project's test source set
- `3cb530bc4c` — move the stable-stdlib dependent API version to 2.4
- `23c730368e` — deprecate language version 2.2
- `8a2665237c` — remove `compiler:fir:dump`
- `0c291b7429` — revert the implicit-blocking-context SDK setting
- `8031bd4a5d` — remove the hijacked progress manager
- `edaabce86b` — retain `ApplicationEnvironment` for an in-process BTA session
- `faa2edd460` — keep CRI independent of the compiler environment
- `dd219b1fcf` — document managed PSI test data
- `2850af0028` — unregister `BuildMetricsService` on close
- `ff4fdb48e3` — move `java-direct` to test-input-check v2
- `0fe5e3faac` — remove the old test-input-check implementation
- `b76ce1c8cd` — remove an obsolete line-number test
- `45f1214123` — refactor BTA tests and add a 2.4.20-Beta2 case
- `e40661a69e` — rename test-input-check v2 to test-input-check
- `7f3c681861` — omit empty parentheses on generated argument-free annotations
- `e206755302` — refine the compiler-plugins Test Federation domain
- `dc94137876` — annotate roots of test hierarchies with affected domains
- `87a0aff888` — make the directives validator a smoke test
- `aab272ed48` — move JS/Native paths to their Test Federation domains
- `72958e49bf` — include compiler plugins in Analysis API/IntelliJ domains
- `941556408f` — cache the ASM deprecation with an artifact transform
- `a37e05d6b9` — reuse the plugin-variant baseline JAR
- `865d83b51d` — remove redundant low-level API mutes
- `3c354d1103` — re-enable isolated projects in affected Gradle tests
- `baf0f46626` — expose smoke-test configuration as a Gradle property
- `0f7e3287fd` — mark Test Federation Gradle-extension APIs delicate
- `0413f1ceed` — pluralize `testFederationDomains`
- `60ebdafe32` — add the domain-dump update run configuration
- `b55076850d` — cache the ASM deprecation transform follow-up
- `f5c6e68333` — diagnose unmatched domain rules
- `c8ba65f674` — improve domain-dump ordering
- `dff320fbe1` — rename low-level compiler-like tests
- `76a6e97bd2` — expand JS/Wasm Test Federation paths
- `0ea7f6ea21` — mark the Native ObjC-export Analysis API test as smoke
- `970b228fc1` — update PostCSS in npm tooling
- `2b7f6d40b5` — use resolved npm dependency versions
- `a116512808` — split unsafe KMP incremental compilation by target

### Callable and property references — 5

- `10c24e7c1c` — remap inlined bound receivers on JVM
- `fc07d7bf53` — handle rich references in JVM external-package patching
- `f29840c2a5` — inline cast bound receivers in JVM property delegation
- `b72920e62b` — support contextual callable references on Wasm
- `6c4cff3af1` — share `boundValueAt` generation across reference lowerings

### Lombok — 9

- `e507e6503d` — unify Java/Kotlin member-function synthesis
- `03e07b26eb` — test Lombok builder access levels
- `3b2c6bbc33` — align generated builder visibility
- `88a9bbd9ce` — expose annotations on dummy Java class types
- `95a74e81b7` — extract singular-field method generation
- `b80b96a0a6` — rework Kotlin-origin `@Singular` builders
- `72377019c7` — substitute generic singular-field types
- `c04b3251cb` — test singularization behavior
- `4b8f3d1f1d` — use Lombok's singularizer

### JS/Wasm semantics and optimization — 13

- `83bf018d62` — throw on JS integer division by zero
- `73a5a163fc` — put JS integer division behavior behind a flag
- `1155fbfeec` — avoid duplicated `IrComposite` evaluation in JS
- `c24567deb8` — prepare while-folding tests for Kotlin implementation
- `87ec330a58` — rewrite while-folding tests in Kotlin
- `d76a3e81e3` — move JS while folding to an IR optimization
- `fbad9a178a` — show JS serialization output in the test plugin
- `4494513fbc` — add JS while-folding cases
- `4553492aa5` — improve the JS while-folding lowering
- `691d7997e4` — mark `kotlin.js.VOID` eager
- `f964c76fd1` — preserve JS/Wasm top-level initializer exceptions
- `6225d45546` — mute Wasm-JS forward-compatibility tests
- `749ff4dfde` — mute Wasm stepping tests

### FIR and shared frontend semantics — 21

- `54201ff9bd` — update FIR cache recursion contracts
- `c5abfe2f05` — extract sealed-subclass collection
- `8b893f1b07` — cache sealed-subclass collection
- `7011dd9253` — centralize complementary sealed symbols
- `69584acd12` — improve complementary-symbol computation
- `d281b799e1` — suppress a redundant anonymous-type `else`
- `605588a6a0` — rename sealed-sibling APIs
- `03b8b2ca79` — reproduce expect/actual self-matching
- `4258db6a7b` — stop reporting an expect library class as its own expect
- `ff356c2260` — error obsolete context-parameter bridges
- `8e4eca452b` — fix private-interface PSI use scope
- `18a5e3fe13` — fix delegated `Any` implementation diagnostics
- `2eadeabcf1` — fix Java `Object` methods in FIR SAM resolution
- `4e3b77c596` — use context-parameter terminology
- `a02b1cc93b` — remove an obsolete value-class context-receiver diagnostic
- `a122ae6197` — fix suppress-cache nullability
- `639ddcdb4c` — fix plugin-structure-provider nullability
- `5f8a66ecf1` — warn on deprecated concurrent maps
- `4f57c64098` — test explicit return types on private functions
- `029ad72aa9` — diagnose non-local implicit types in explicit-API mode
- `523a8a0a58` — add explicit return types repository-wide

### Annotation PSI, stubs, and decompiler — 11

- `f0e117ca56` — make unary expressions stub-based
- `e6f730d9a6` — make binary expressions stub-based
- `65f70d4543` — make parenthesized expressions stub-based
- `b2bba6ca0f` — fold stubbed string concatenations without loading ASTs
- `c754b19732` — declare annotation test fixtures explicitly
- `b3c036d665` — split compilable and invalid annotation values
- `ed5c3c1cff` — cover annotation argument kinds absent from stub trees
- `b8ef63307b` — build decompiler stubs for annotation arguments
- `ebf26f0d8c` — print decompiled annotations one per line
- `8bcd7ce5a3` — print annotations before context parameters
- `30b98e4628` — remove orphaned PSI baselines

### KLIB, metadata, linker, and IR architecture — 21

- `abdab53c13` — add `-jvm-target` to JKLIB
- `f48bd8435a` — post-process each deserialized `KmAnnotation`
- `6c65c297f3` — extract imported C-interop signatures, part 2
- `935795491f` — behavior-neutral naming cleanup in that extractor
- `6c46ba7c72` — handle Java field shadowing in JKLIB linking
- `35a2d0cc2b` — adapt metadata to companion-extension format changes
- `a605ee48aa` — remove the fake-override-validator compiler flag
- `0b8786d9ff` — remove the shared Native fake-override checker
- `2effb6df26` — remove descriptor use from the JVM backend resolver
- `56110a730a` — remove PSI from JVM inline member-access expressions
- `fb1344c0dc` — allow regular-to-C-interop KLIB test dependencies
- `912ad89004` — allow C-interop-to-C-interop KLIB test dependencies
- `c032361815` — add a Native KLIB dependency-DAG utility
- `37c86315d3` — deserialize JKLIB dependencies with inline bodies
- `5d06b7de9b` — restore an obsolete-descriptor annotation on one IR API
- `36ffbb686a` — simplify function-interface-file detection
- `534e98f367` — remove the last deprecated descriptor use from one Native backend
- `4b5b5e7f9c` — remove `DescriptorMetadataSource.File` use in Native
- `1414a57767` — remove descriptors from Native serialization classes
- `135778078b` — remove unused descriptor-era utilities
- `76ca9aa1af` — remove unreachable partial-linkage classifier code

### Analysis API, export, and value classes — 27

- `f8a369def0` — remove `lookupLocally` from Analysis API
- `57e72a1e58` — enable KDoc in Swift export
- `0d0fa61d4c` — escape Swift keywords in trampolines
- `6f3dd2d77e` — serialize static value-class properties correctly
- `8d611c37c0` — fix a value-class reflection property clash
- `47ad7b6db1` — reproduce KT-88141
- `b8465da0ed` — reproduce KT-88142
- `8a470cd791` — add Analysis API `javaMethodName`
- `383c7c5bff` — deprecate getter/setter-only Java-name queries
- `f9b2b6852b` — add missing stdlib to Java-name tests
- `65e6b4982b` — render `javaMethodName` in Analysis API debug output
- `3b698a75a1` — remove the old dedicated accessor-name test
- `ba4e0a85bd` — return no accessor name for const properties
- `3bc5dd3974` — test names Java cannot express directly
- `7abfcaa776` — return no name Java cannot reference
- `5bc26bb6f1` — test Java names with value classes
- `bebde9ef53` — hide value-class-mangled methods from `javaMethodName`
- `72e43da865` — test `Result` in signatures
- `83114402eb` — avoid name mangling for `Result` parameters
- `31ec087fe4` — add the light-class internals bridge
- `2313610964` — test value classes as upper bounds
- `3dea048aaa` — share value-class mangling with Java-name computation
- `c106245a16` — inline `KaTypeNullability.create`
- `0acee48cc8` — hide deprecated Analysis API declarations
- `3120a71a84` — remove hidden Analysis API declarations
- `52ce301dc7` — hide deprecated Analysis API utility declarations
- `dc9b76dcbb` — fix inline-class field lookup in JVM reflection

### Contracts — 3

- `a2145c4db5` — apply extended contracts to compiler helpers
- `5217139a53` — apply `returnsResultOf` in stdlib
- `c64e33d6fa` — apply `returnsResultOf` in C-interop runtime helpers

### Scripting — 22

- `b2c282c4f1` — remove obsolete K1 scripting tests
- `5e8c01f201` — clean scripting-compiler tests
- `3726f11e68` — remove K1 mode from shared scripting tests
- `1c777c7c90` — remove K1 scripting compiler support
- `60be8999b0` — remove an obsolete scripting codegen test
- `09d62d1681` — move CLI scripting tests, part 1
- `7f31875b5b` — move CLI scripting tests, part 2
- `589c03565d` — move scripting test utilities to fixtures
- `7fa08d91b8` — extract scripting runtime entities
- `c3f05617ed` — move CLI compiler scripting tests
- `ace3255e3d` — rename script CLI compilation tests
- `68f7c385ce` — move host compiler tests
- `5dca8ec9dc` — move definition tests
- `26ab7d76d7` — move uncategorized scripting tests
- `2b82e97325` — move K2 REPL tests
- `0f7ca6068e` — remove the scripting `tests-organized` source set
- `6d44ddacf4` — move generated scripting tests to dedicated packages
- `016f505f1b` — reorganize scripting test utilities
- `d229d32e84` — rename the custom-definition test-data root
- `7dee81dcc6` — move generated scripting codegen tests
- `d8d8f7ca52` — move generated scripting diagnostic tests
- `3b8deaed2c` — deprecate scripting IDE services

### Screened platform-local changes — 3

- `c2509f174e` — remove unused Native test data
- `92a88fcde8` — fix a Compose-plugin default-super-call crash
- `0cbd53d742` — convert Native LLDB tests to golden tests
