# Upstream sync impact review — 2026-07-30

## Scope and evidence

The private `dotnet` branch was rebased from upstream commit `6fb64e0c0` onto
`733a49b39`. The review covered all 161 upstream commit messages and the
relevant diffs. Before the rebase:

- `dotnet` contained 310 target commits and no merge commits;
- a virtual merge produced tree `6ca0cb694df871098aa503adef6836a6773b5fee`;
- only seven files changed on both sides, and all seven auto-merged; and
- no textual conflict was reported.

The rebase retained all 310 target patches as `=` entries in `git range-diff`.
Its final tree is exactly the virtual-merge tree above. The old tip remains
available locally as
`refs/backup/dotnet-before-upstream-rebase-20260730`.

The pure rebase passed the strict target gate: 882 tests in 16 suites, with
zero failures, errors, or skips. Compiler-argument, Gradle-option, BTA-source,
and public-API regeneration produced no tracked diff.

## Immediate Common semantic change

Upstream added the Kotlin 2.5 Common declaration
`kotlin.internal.throwNoWhenBranchMatchedException(subject: Any)` and
platform implementations. JVM and Native/Wasm throw:

```text
NoWhenBranchMatchedException("No branch matched for subject: $subject")
```

FIR2IR uses this function when the new language feature is enabled and a
matching stdlib symbol is present. If it is absent, FIR2IR deliberately falls
back to the older parameterless builtin. The rebased .NET target therefore
still compiles, but would otherwise keep the old null-message behavior.

JS substitutes a class simple name where its object rendering needs a
platform-specific presentation. The CLR has no corresponding technical
constraint, so .NET follows the JVM/Native/Wasm body exactly rather than
inventing a target message.

Kotlin Common owns the declaration contract and Kotlin stdlib code owns the
message construction. They must not be duplicated in a .NET-only emission
intrinsic. The implemented ownership split is:

- the target stdlib helper follows the Common signature, visibility,
  `PublishedApi`, compiler-generated-use marker, version gate, and the
  JVM/Native/Wasm body;
- FIR2IR calls that declaration normally;
- `Kotlin.Stdlib` physically owns the helper facade in both the explicit
  product and bootstrap flows, and separately compiled consumers bind to that
  assembly rather than re-emitting the helper;
- an internal, error-deprecated target declaration supplies the four Common
  exception constructors, matching the internal actual used by Native and
  Wasm; and
- the ordinary exception mapping binds that declaration to the existing exact
  `[Kotlin.Runtime]Kotlin.NoWhenBranchMatchedException`, while string
  interpolation continues through ordinary Kotlin codegen.

The current bootstrap stdlib compiles target source directly rather than a
Common/actual source partition. It therefore carries a target copy of the
helper and the binary-retained `UsedFromCompilerGeneratedCode` annotation.
Both are exact mirrors of the Common source contract, not alternative
semantics. They should disappear into the planned Common-plus-.NET-actual
source partition once the bootstrap can compile that product shape.

The annotation definition is resolution-only target-stdlib input. It is
retained for frontend and stdlib-KLIB serialization, but excluded from the IL
shape gate, file-facade name reservation, and physical declaration index. In
particular, it must not become a class in either a user library or
`Kotlin.Stdlib`. The strict publication gate caught this distinction when the
first implementation treated the annotation like a user declaration; the
dedicated resolution-only source classification is the resulting guard.
The packaged fallback catalog is sorted by relative source path and injects
each file under that same package-relative temp path. This matches the
ordinary-source producer because FIR orders actual source paths: equal source
contents or insertion order alone do not guarantee byte-identical IL.

The rejected alternatives are instructive:

- keeping the old parameterless backend intrinsic would silently ignore the
  new Common semantic when the matching stdlib is present;
- formatting the message in IL would create a second semantic implementation;
- exposing the exception as ordinary public Kotlin API would contradict
  Common's error deprecation and Native/Wasm's internal actual; and
- using Roslyn's `SwitchExpressionException` would change Kotlin identity and
  is not portable across the supported Framework and CoreCLR profiles.

Thus the only .NET-specific mechanism is physical CLR ownership: the runtime
owns the exact exception type, the stdlib owns the Common-shaped helper, and
the backend maps between their declared Kotlin and physical CLR identities.

## Immediate IR invariant change

Upstream added `IrClassSuperTypesChecker`: every `IrClass` except `Any` and
`Nothing` must have at least one supertype. FIR2IR and pre-lowering KLIB
validation now enforce that rule.

Seven .NET synthetic classes were constructed after those validation points
without explicitly recording `Any`:

- the synthetic-constructor and default-constructor markers;
- the property-reference factory;
- the shared mutable-reference cell; and
- three interface-default helper owners.

There is no CLR reason to deviate. Each class must record
`irBuiltIns.anyType`, matching the existing .NET synthetic callable, generic
view, companion-static, and static-initialization classes.

## Longer-term upstream direction

The reviewed commits reinforce existing target decisions:

- IR and KLIB continue removing descriptor-era linker state. New .NET
  dependency identity must remain on IR/container carriers, not a parallel
  descriptor linker.
- KLIB metadata can now post-process every deserialized type and extract
  imported foreign signatures. This supports authoritative KLIB identity plus
  a structured physical CLR binding, rather than inference from CLR
  annotations.
- Analysis API binary stubs had to preserve a semantic type attribute which
  compiler metadata deserialization already knew. A future .NET IDE path
  therefore needs CLR-aware binary/stub reconstruction; compiler import alone
  is insufficient.
- Native's broader APINotes/Swift-name import keeps foreign presentation
  separate from Kotlin declaration and override identity. CLR attributes must
  follow the same split.
- BTA gained a typed JavaScript export toolchain. A later .NET BTA surface
  should expose typed compile/link/export operations rather than add more
  unstructured CLI coupling.
- Reusable plugin classloaders make per-compilation target state important.
  The immediately reverted BTA environment cache shows that session lifetime
  is still unsettled; no .NET-specific global cache should be built on it.

These are sequencing constraints, not new ABI commitments.

## Required validation for the compatibility slice

The implementation must:

1. regenerate the exhaustive-`when` IL golden and inspect the complete
   message-producing stack shape;
2. assemble the changed golden with both supported ILAsm implementations;
3. force an unreachable fallthrough with a noncanonical CLR Boolean and
   verify the exact exception type and message on Framework CLR 4 and
   CoreCLR 10;
4. cover both bootstrap-source and separately produced stdlib consumption;
5. audit every .NET `buildClass` call for an explicit supertype; and
6. pass the strict aggregate gate and all 16 XML-suite checks before commit.

## Validation result

The implemented slice satisfies those gates:

- current and feature-disabled exhaustive-when IL tests pass under PSI and
  LightTree (4/0/0/0), and their complete goldens were inspected;
- the assembler matrix forces a noncanonical Boolean fallthrough across
  explicit/bootstrap stdlib production, both ILAsm implementations, and both
  CLR hosts, observing the exact exception and message;
- direct-source and packaged-fallback stdlib products are byte-identical again
  for both net48 and net10 after preserving package-relative fallback paths;
- all 13 target `buildClass` sites now record a supertype; and
- the final strict aggregate gate passes 884 tests in 16 XML suites, with zero
  failures, errors, or skips.
