# ADR: Gradle attribute for Kotlin/.NET target frameworks

- Status: **Accepted**
- Date: 2026-07-27
- Scope: Gradle variant compatibility for `net48`, `netstandard2.0`, and `net10.0`

## Context

Kotlin/.NET has one logical Kotlin platform identity but three deliberately supported target
frameworks. Gradle needs a second attribute to select a physically compatible KLIB/DLL pair
without splitting Kotlin Common semantics into three platforms.

## 1. Other Kotlin targets

Kotlin targets place physical or environment capabilities below `KotlinPlatformType`:

- JVM publishes Gradle's target JVM version and environment attributes;
- Native publishes its Konan target and uses separate commonizer-target attributes;
- Wasm publishes a target-environment attribute for JS versus WASI; and
- variant compatibility and preference are expressed through Gradle compatibility and
  disambiguation rules rather than encoded in artifact names.

These attributes do not redefine Kotlin declarations. They select a physical artifact that can
satisfy the consumer.

## 2. .NET platform differences

.NET target framework compatibility is not a single ordered version:

- `net48` can consume `net48` and `netstandard2.0`;
- `net10.0` can consume `net10.0` and `netstandard2.0`;
- `netstandard2.0` can consume only `netstandard2.0`; and
- `net48` and `net10.0` cannot consume one another.

The two runtime profiles have different BCL contracts and CLR capabilities. `netstandard2.0` is
their shared library API floor, not a runtime and not an intermediate Kotlin platform.

## 3. Kotlin Common invariant

Profile selection must not affect Kotlin language semantics, logical declaration identity,
expect/actual matching, or the meaning of common APIs. All variants retain
`KotlinPlatformType.dotnet`.

A library may publish different physical implementations for the profiles, including portable
interface helpers and modern default interface methods. Those implementations must expose the same
Kotlin declarations and obey the same Kotlin contracts.

## 4. .NET validity

The attribute value is the canonical target framework moniker: `net48`, `netstandard2.0`, or
`net10.0`. Exact variants are preferred. A runtime consumer may fall back to
`netstandard2.0`; no other cross-profile fallback is legal.

A consumer that does not declare a target framework receives no implicit default. Choosing
`net48`, `net10.0`, or even the portable surface without knowing the consumer's runtime would hide
a build-model error. Gradle should retain ambiguity until the future built-in target supplies the
attribute.

The attribute applies to both halves of the Kotlin/.NET library artifact pair. KLIB metadata and
the CLR DLL must never resolve from different target-framework variants.

## 5. Alignment with compiler architecture

Define an experimental typed KGP attribute and register ordinary Gradle compatibility and
disambiguation rules. This follows the established secondary-attribute mechanism. Do not encode
the profile in `KotlinPlatformType`, artifact names, Gradle usage, or a private resolution pass.

The enum keeps conventional Kotlin enum-entry names and exposes the canonical TFM separately as
`targetFrameworkMoniker`; Gradle's `Named.getName()` and `toString()` return that moniker. Kotlin's
inherited `Enum.name` remains the source identifier and is not treated as a published TFM.

The KGP enum and compiler backend's `DotNetTarget` remain boundary-specific representations:
Gradle owns published variant selection, while the compiler owns lowering and output validation.
The built-in target must map them exhaustively by canonical moniker and test that mapping.

## 6. Core-team choice

The Kotlin-consistent choice is a typed
`org.jetbrains.kotlin.dotnet.targetFramework` attribute with exact-first selection and only the
two documented `netstandard2.0` fallback edges.

Classifications:

- a secondary target-framework attribute: **Correct direction**;
- exact-over-portable disambiguation: **Correct direction**;
- different physical output on `net10.0`: **Reasonable platform-specific divergence**;
- leaving an unspecified consumer ambiguous: **Correct direction**;
- boundary-local compiler and Gradle enums with exhaustive mapping:
  **Correct temporary implementation, but not a final design** until the target owns that mapping;
- treating target frameworks as Kotlin platforms or ordering them numerically:
  **Architecturally wrong and should be changed**.

## Consequences

Gradle can model the real CLR compatibility graph without weakening Kotlin platform identity.
The future built-in target must place this attribute on every resolvable and consumable
profile-specific configuration, configure the matching schema, publish KLIB and DLL artifacts from
the same variant, and pass the selected moniker to `-Xdotnet-target`.
