# Pending upstream impact review — 2026-08-03

## Scope and evidence

This snapshot records the evidence needed to integrate the upstream range:

```text
733a49b39d4daaf81ac61c7d9c73ffaf81ef4875..76ca9aa1af7247c4f091f2f0d10c6f25b6fa80b9
```

The range contains 179 commits. Every commit subject and changed-path set was
reviewed; candidate compiler, FIR, IR, KLIB, metadata, Analysis API, stdlib,
test, Gradle/KMP, BTA, annotation, callable-reference, value-class, and interop
patches were inspected. The prior 161-commit integration ending at `733a49b39`
is covered by [`upstream-sync-2026-07-30.md`](upstream-sync-2026-07-30.md).

This file is not an ABI decision and does not claim that the range has been
rebased or tested. The exhaustive per-commit ledger used during review remains
recoverable from Git and from the exact range above; retaining it here would
duplicate Git history without helping the next integration.

## Rebase evidence

A virtual merge with `76ca9aa1af` found 11 paths changed by both sides and one
textual conflict:

```text
compiler/tests-integration/build.gradle.kts
```

The conflict is mechanical. Accept upstream's removal of the Javac integration
and old test-input-check dependencies, while retaining the `cli-dotnet` test
fixture, shared .NET integration setup, smoke-test selection, and short `dn`
task.

The other overlaps require owner-driven regeneration or a simple combined
edit:

| Concern | Paths | Treatment |
| --- | --- | --- |
| Generated compiler API | `compiler/arguments/resources/kotlin-compiler-arguments.json`, BTA API baseline | Regenerate through the owning generators; never hand-edit the baseline |
| Test plugins and runners | `cli-base`, `fir2ir`, FIR2IR test generator, directives validator | Accept upstream plugin/annotation changes and retain the .NET fixtures/models |
| Shared compiler source | `Fir2IrVisitor.kt`, `LauncherScriptTest.kt` | Accept upstream source changes beside the orthogonal .NET additions |
| Module registry | `CompilerModules.kt`, `settings.gradle.kts` | Accept removed/moved modules and retain all .NET registrations |

After the rebase, regenerate target arguments/API and test runners, inspect the
generated diff, and run the strict .NET aggregate gate.

## Lasting conclusions and owners

Only conclusions that affect future target work survive below. Their normative
form lives in the linked owner; this snapshot retains the upstream evidence.

| Upstream direction | Kotlin/.NET consequence | Active owner |
| --- | --- | --- |
| Shared callable-reference lowering | `addBoundValueAtOverride` may replace the target-local equivalent only if CLR accessibility, virtual/final flags, `MethodImpl`, receiver order, and cross-library identity stay equivalent | [way forward](../programmes/way-forward.md), [callable/reference draft](../decisions/draft-adr-callable-and-reference-abi.md) |
| Rich annotation stubs and `KmAnnotation` post-processing | Reuse shared annotation-value/decompiler tests later; use the KLIB callback only for structured normalization, never as physical ABI or as a replacement for KLIB authority | [annotation programme](../programmes/clr-annotations.md), [importer draft](../decisions/draft-adr-clr-importer-boundary.md) |
| Common `returnsResultOf` adoption | Contracts remain KLIB/Common semantics. Their honest source closure still includes the `InvocationKind` enum and therefore the enum/contracts/builder/abstract-collection cluster | [collections programme](../programmes/common-collections.md), [way forward](../programmes/way-forward.md) |
| KLIB dependency loading with inline bodies | Preserve inline bodies for every selected Kotlin dependency and verify prepared/main-IR separate-library consumption after integration; foreign CLR assemblies never acquire synthetic Kotlin bodies | [inline programme](../programmes/inline-functions.md) |
| Explicit KLIB dependency graphs and descriptor removal | The selected CLR assembly/KLIB graph belongs in shared .NET import/library infrastructure and new linkage remains FIR/IR/KLIB-based, not descriptor-, PSI-, or emitter-owned | [architecture programme](../programmes/compiler-architecture.md) |
| Analysis API target-visible names | A future .NET query must obtain physical owner/name/property/event shape from the same physical ABI model as compiler/export, not from Java APIs or an IDE-side recomputation | [architecture programme](../programmes/compiler-architecture.md), [C# export draft](../decisions/draft-adr-explicit-csharp-export-surface.md) |
| BTA environment reuse and target-specific KMP incremental switches | .NET compilation state stays build-session scoped; a future unsafe-incremental switch is .NET-specific and defaults off until its complete invalidation model exists | [compiler/Gradle ADR](../decisions/compiler-and-gradle-integration.md) |
| Shared FIR semantic fixes | Inherit them through shared FIR. CLR field/property/event overridability and `System.Object` delegate/SAM filtering remain explicit FIR-import policies rather than Java-condition reuse or backend patches | [importer draft](../decisions/draft-adr-clr-importer-boundary.md) |

## Bounded follow-ups

### Callable references

Commit `6c4cff3af1` extracts `addBoundValueAtOverride` from Native and Wasm into
common IR. `DotNetCallableReferenceLowering` has a structurally similar helper,
but its protected final CLR override is observable physical behavior. Probe the
shared helper after the rebase and adopt it only with equivalent IL, metadata,
zero/one/multiple-bound-value behavior, member-extension receiver order, and
separate-KLIB equality/hash behavior. This is a cleanup candidate, not a new
ABI choice.

### Annotations and tooling

Commit `f48bd8435a` adds `KmAnnotation` read-strategy post-processing. The
annotation PSI/decompiler series supplies a future shared matrix for constants,
class literals, arrays, enum entries, nested annotations, strings, unsigned
values, and invalid non-constants. Enable that shared evidence as each real
dependency lands; do not copy it into a .NET-only semantic suite.

Kotlin tooling reconstructs the logical declaration from embedded KLIB and
combines it with the explicit physical CLR ABI model. Custom attributes alone
cannot reconstruct Kotlin identity, retention, split interfaces, or compiler
ABI.

### Contracts, enums, and collections

Commit `5217139a53` adds `returnsResultOf` to Common result-forwarding functions.
There is no exact Roslyn CodeAnalysis equivalent, so the effect remains in KLIB.
The authoritative contracts source itself declares `InvocationKind`; it cannot
be replaced by a target stub merely to compile `Standard.kt`. This confirms,
rather than changes, the atomic enum/contracts/builder/abstract-collections/
`EnumEntries` source closure.

### Import, linkage, and inline bodies

JKLIB's Java field-shadowing condition is architectural warning only. CLR
fields, properties, events, MethodSemantics, explicit implementations, and
hide-by-name/signature rules need their own FIR importer conditions. Native's
KLIB dependency-DAG helper is likewise precedent for explicit graph ownership,
not reusable .NET code.

The JKLIB `WITH_INLINE_BODIES` change confirms that dependency deserialization
must retain Kotlin inline bodies without turning the .NET non-linking inliner
into a general linker. Re-run the separate-library prepared/main-IR matrix after
the rebase.

### IDE, export, and value classes

Analysis API's `javaMethodName` demonstrates that a foreign-visible name can be
target-specific and sometimes unavailable before lowering. Future .NET tooling
therefore needs a target component backed by the compiler's physical ABI
placement/name model. Swift export's KDoc and escaping changes are presentation
precedent only: C# documentation and keyword escaping belong in the explicit
facade/tooling layer and never change canonical identity.

The value-class commits touch serialization, static placement, reflection
lookup, upper bounds, mangling, and tooling together. They provide no CLR
representation answer and reinforce keeping value classes parked until one
coherent proposal covers that whole boundary.

## Screened directions with no target work

- Scripting reorganization affects the shared Gradle conflict but does not
  justify a .NET scripting product.
- Lombok changes reinforce frontend ownership for annotation-driven foreign
  synthesis but are Java-plugin code, not a CLR importer dependency.
- JS division-by-zero and top-level-initializer changes offer shared semantic
  tests; their JS flags and lowerings are not portable. CLR behavior and the
  accepted exception/static-initialization models remain authoritative.
- Native debugger-test conversion, Native-local cleanup, and the Compose crash
  fix create no current .NET action.

No reviewed commit changes the accepted exception, generic-class, array,
interface, annotation-authority, static-initialization, or self-describing-DLL
decisions.

## Integration checklist

1. Rebase onto the deliberately selected upstream head and resolve the known
   integration-test conflict without losing the .NET fixtures.
2. Regenerate compiler arguments/API and test runners through their owners.
3. Inspect focused separate-library inline, callable-reference, annotation/KLIB,
   interface/override, and both-frontend evidence.
4. Run and audit the strict aggregate gate.
5. Treat shared-helper adoption and shared-test enablement as separate bounded
   follow-ups; neither belongs in the mechanical rebase.
