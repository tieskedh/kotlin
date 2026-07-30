# ADR: Kotlin/.NET platform identity in common metadata and Gradle

- Status: **Accepted**
- Date: 2026-07-27
- Scope: logical Kotlin platform identity; target-framework profile selection is a separate
  follow-up

## Context

The compiler already models Kotlin/.NET as `DotNetPlatform` and creates a dedicated FIR session.
The CLI and backend therefore do not impersonate JVM, JS, Native, Wasm, or Common. Gradle and the
metadata compiler could not yet name that platform, however: `KotlinPlatformType` had no .NET
value and `-Xtarget-platform` did not recognize `DotNet`.

This decision records the required logical identity before introducing the built-in Gradle target
and its profile-aware compilation tasks.

## 1. Other Kotlin targets

JVM, JS, Native, and Wasm each have:

- a target-platform identity in compiler configuration;
- a distinct `KotlinPlatformType` value published in Gradle module metadata; and
- a metadata-compiler spelling used when common source sets are refined for those leaf platforms.

Target-specific capabilities are modeled below that platform identity. For example, Wasm JS and
Wasm WASI share `KotlinPlatformType.wasm` but remain distinguishable where their environments
matter. Native likewise does not turn each Konan target into a new `KotlinPlatformType`.

## 2. .NET platform differences

.NET has two separate axes:

- the Kotlin execution platform is CLR/.NET; and
- the target-framework/API profile is `net48`, `netstandard2.0`, or `net10.0`.

The profiles change available BCL surface, executable support, interface-body placement, assembly
references, and legal emitted metadata. They do not turn one Kotlin declaration into three
different Kotlin language platforms. In particular, `netstandard2.0` is a library API floor, not
an executable runtime or a replacement for Kotlin Common.

## 3. Kotlin Common invariant

Common source declarations and expect/actual matching must see .NET as a real leaf platform.
Calling it JVM would admit JVM-specific variants and conventions. Calling it Common would erase a
leaf from hierarchical multiplatform refinement. Splitting profiles into separate Kotlin platform
types would incorrectly make profile-specific code appear to define different Kotlin semantics.

The compiler and Gradle therefore use one logical `DotNet`/`dotnet` identity. Profile-specific
source APIs, when needed, use ordinary source-set refinement; profile-specific physical lowering
does not alter the common declaration's meaning.

## 4. .NET validity and compatibility

Gradle platform matching must never make a .NET consumer compatible with JVM, Android/JVM, JS,
Native, or Wasm artifacts. The existing Common metadata fallback remains applicable in the same
way as it is for every leaf platform.

The target-framework compatibility matrix is not encoded in `KotlinPlatformType`:

- `net48` may consume `net48` and `netstandard2.0`;
- `net10.0` may consume `net10.0` and `netstandard2.0`; and
- `netstandard2.0` may consume only `netstandard2.0`.

A separate Gradle attribute must own that matrix. Exact profile variants must be preferred over
the portable fallback. This also permits `net10.0` compilation and packaging to differ from
`net48` without inventing a second Kotlin platform.

## 5. Alignment with compiler architecture

Add `DotNet` to the metadata compiler's existing target-platform table and `dotnet` to
`KotlinPlatformType`. Let the existing exact-match and Common-fallback attribute rules apply;
introduce no .NET-specific exception in the platform compatibility rule.
KGP telemetry records that same `dotnet` value in the existing platform metric; it does not create
a separate product or profile spelling.

KGP must not select the JVM stdlib for the new value. Until the built-in target owns its
profile-specific stdlib pair, generic external-target plumbing uses only the common compiler-option
carrier and does not register a .NET compile task. That is a correct temporary integration seam,
not the final target implementation.

`compileOnly` remains available, as it is on JVM. CLR compilation legitimately consumes framework
reference assemblies and other contracts whose implementation is supplied by the selected runtime
or application host. The built-in target must eventually model reference and runtime assets
separately and diagnose a published Kotlin API that leaks an unavailable implementation; platform
identity alone cannot make that distinction.

KLIB platform marking, the profile attribute, built-in target DSL, compiler options, compile task,
artifact publication, and friend association remain separate features. Each must consume this
logical identity rather than introducing another one.

## 6. Kotlin-aligned target choice

The Kotlin-consistent choice is one `KotlinPlatformType.dotnet` plus one metadata spelling,
`DotNet`, with framework profiles represented by a second capability attribute.

Classifications:

- distinct compiler/KGP .NET platform identity: **Correct direction**;
- no cross-platform compatibility exception: **Correct direction**;
- managed-platform `compileOnly` availability: **Correct direction**, subject to explicit
  reference/runtime asset modeling in the built-in target;
- neutral options in generic external-target plumbing: **Correct temporary implementation, but
  not a final design**;
- separate profile compatibility attribute and exact-over-portable disambiguation:
  **Correct direction**, implemented by the accepted target-framework-attribute ADR;
- treating profiles as platform types, or treating .NET as JVM/Common: **Architecturally wrong and
  should be changed**.

## Consequences

Common metadata compilation can name .NET without masquerading as another backend. Gradle module
metadata can distinguish .NET artifacts before the built-in target is complete. The separate
profile attribute now owns compatibility; the next integration slice is a built-in target whose
tasks invoke the real .NET compiler and own the self-describing DLL artifact.
