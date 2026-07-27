# ADR: Generated Gradle compiler options for Kotlin/.NET

- Status: **Accepted**
- Date: 2026-07-27
- Scope: public Gradle compiler options before the built-in .NET target

## Context

The built-in Kotlin/.NET Gradle target needs typed compiler options. The compiler argument model
is now generated from the shared `compiler/arguments` description, but not every CLI argument is a
user-owned Gradle option. Output paths, dependency paths, product kind, target profile, and friend
authorization must be derived by the target and compilation model.

## 1. Other Kotlin targets

JVM, JS, Native, and Common generate public compiler-option interfaces, implementation classes,
and argument-fill helpers from the compiler-argument description. Their Gradle surfaces contain a
deliberately selected subset recorded by the generator rather than every CLI switch.

Native is the closest KLIB precedent. Its target-specific public surface currently adds only
`moduleName` to common Kotlin compiler options. Physical target, outputs, libraries, friend
modules, and product details are owned by the target, compilation, task, or binary model.

## 2. .NET platform differences

.NET additionally has:

- a target-framework profile that controls available BCL APIs and emitted metadata;
- a self-describing DLL carrying private Kotlin metadata;
- CLR assembly friendship through producer-emitted `InternalsVisibleTo`; and
- explicit C# export selection.

These concepts require .NET wiring, but they do not all belong to a generic compiler-options
block. In particular, profile and friend values participate in variant selection and compilation
association and must not be independently overridden on a compile task.

## 3. Kotlin Common invariant

The Gradle DSL must preserve the same common language, API version, opt-in, progressive-mode,
warning, and free-argument semantics as other targets. The generated .NET interface therefore
extends `KotlinCommonCompilerOptions`.

This option layer selects compiler behavior only. It does not alter Kotlin declaration identity,
multiplatform matching, visibility, or target-profile semantics.

## 4. .NET validity

`moduleName` maps to the CLR assembly name requested from the compiler and is a valid
compilation-level input, with a target or project convention supplied later by the built-in
target. Target framework, destination, classpath, product mode, standard-library selection, and
friend authorization remain operational inputs owned elsewhere.

The provisional export CLI switches remain available through `freeCompilerArgs`. They should gain
a typed public surface only together with a dedicated export DSL whose naming, overload
selection, and generated-facade ownership are coherent; exposing raw encoded selector strings now
would freeze the command-line transport format as Gradle API.

## 5. Alignment with compiler architecture

Extend `GenerateGradleOptions` with the .NET argument level. Generate:

- `KotlinDotNetCompilerOptions` in the KGP API;
- `KotlinDotNetCompilerOptionsDefault` in KGP; and
- `KotlinDotNetCompilerOptionsHelper` for common plus .NET argument filling.

Keep the generator metadata to `module-name`. Test that both the common and target-specific
properties reach `K2DotNetCompilerArguments`.

## 6. Core-team choice

The Kotlin-consistent choice is a generated, minimal compiler-options surface: common options plus
`moduleName`.

Classifications:

- generating the interface, implementation, and fill helper:
  **Correct direction**;
- exposing `moduleName` with a later target-owned convention:
  **Correct direction**;
- keeping target framework, outputs, dependencies, and friends task-owned:
  **Correct direction**;
- deferring a typed export DSL while retaining `freeCompilerArgs`:
  **Correct temporary implementation, but not a final design**;
- exposing every .NET CLI argument as a public Gradle property:
  **Architecturally wrong and should be changed**.

## Consequences

The upcoming target, compilation factory, and compile task can use a normal generated compiler
options type without creating a parallel API. The public surface remains small enough to evolve,
and operational values retain one authoritative owner.
