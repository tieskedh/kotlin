# Common I/O source partition review

Status: implemented and aggregate-validated, 2026-07-30.

## Question

After proving that the bootstrap stdlib can compile authoritative Common
sources beside narrow .NET actuals, which existing target mirror should move
next?

The two plausible candidates were the Common exception surface and the Common
I/O header. The choice must preserve Common as the Kotlin authority, follow the
mature targets, and introduce a CLR-specific semantic difference only where
the platform requires one.

## Rejected next step: the complete exception header

`libraries/stdlib/common/src/kotlin/ExceptionsH.kt` is one source product. It
does not only declare the exception classes currently mirrored by
`DotNetStdlibKotlin.kt`; it also commits a target to:

- `AssertionError`, `ConcurrentModificationException`, and
  `UninitializedPropertyAccessException`;
- the concrete Common `KotlinNothingValueException`;
- `Throwable.stackTraceToString` and `Throwable.printStackTrace`;
- `Throwable.addSuppressed` and `Throwable.suppressedExceptions`.

The current CLR model deliberately carries arbitrary Kotlin and foreign
throwables as `System.Exception`. Unlike Java `Throwable`, `System.Exception`
has no suppressed-exception contract. The .NET target must therefore choose a
durable storage and identity policy before actualising those operations. A
side table, `Exception.Data`, a Kotlin-owned wrapper, and an intentionally empty
implementation have materially different lifetime, foreign-exception,
thread-safety, and C#-visibility properties. Importing the header first would
hide that ABI/runtime decision inside a source-ownership cleanup.

The exception header is therefore not the next bounded feature. This does not
reverse the Common-authoritative endpoint: it identifies the platform issue
that must be resolved before the entire file can become authoritative.

## Selected continuation: the Common I/O header

Compile the exact
`libraries/stdlib/common/src/kotlin/ioH.kt` as a Common source and convert
`libraries/stdlib/dotnet/src/kotlin/io/DotNetStdlibIo.kt` into its .NET actual
surface once its Common exception dependency is authoritative.

The mature targets establish the contract:

| Target | Output | Input | Serializable marker |
| --- | --- | --- | --- |
| JVM | delegates to `System.out`; preserves primitive overloads | implements `readln` through `readlnOrNull` and throws the Common `ReadAfterEOFException` at EOF | typealias to `java.io.Serializable` |
| JS | host output implementation | explicitly unsupported because the host has no standard input contract | inert internal actual interface |
| WasmJS | host output implementation | explicitly unsupported because the host has no standard input contract | inert internal actual interface |
| WasmWASI | host descriptor implementation | implements input and the Common EOF behavior | inert internal actual interface |

.NET follows JVM/WASI because the CLR platform supplies standard input:
[`Console.ReadLine`](https://learn.microsoft.com/en-us/dotnet/api/system.console.readline?view=net-10.0)
returns the next line without its terminator and returns `null` when no lines
remain. That is the Common `readlnOrNull` contract without a semantic
translation.

The .NET actuals use these physical policies:

- `readlnOrNull` lowers directly to `System.Console.ReadLine()`;
- `readln` remains an ordinary stdlib implementation and reuses the Common
  `ReadAfterEOFException("EOF has already been reached")` behavior;
- `print` and `println` lower to `System.Console.Write`/`WriteLine` only after
  Kotlin string rendering;
- primitive `println` overloads remain target additions, like JVM's overloads,
  so existing source selection and efficient `Char` output remain stable;
- `Serializable` remains an inert internal interface, matching JS/Wasm/Native,
  rather than falsely claiming the CLR serialization protocol.

The output conversion is intentional. The CLR
[`Console.Write(Object)`](https://learn.microsoft.com/en-us/dotnet/api/system.console.write?view=net-10.0)
uses the object's `ToString`; numeric overloads use CLR formatting and
`Boolean.ToString` uses CLR casing. The target's established Kotlin rendering
path preserves locale-independent numbers, Kotlin floating-point spelling,
lowercase Boolean values, and `"null"`. Calling a CLR object overload directly
would be more .NET-native but less Kotlin-correct.

## Alternatives attacked

### Keep the target declarations non-actual

Rejected. They already mirror the Common vocabulary, so retaining a second
authority loses expect/actual validation and makes the emitted KLIB less
truthful.

### Treat input as unsupported like JS

Rejected. JS and WasmJS deviate because their host model lacks a standard input
contract. CLR has one. Copying their limitation would be a target limitation,
not a platform constraint.

### Map `Serializable` to a CLR serialization interface

Rejected. Common uses this internal declaration as an implementation marker,
not as a promise to participate in a foreign object serialization protocol.
The non-JVM targets deliberately use an inert actual interface.

### Emit all I/O functions as CLR calls in consumers

Rejected for `readln`. Keeping its EOF branch as ordinary Kotlin stdlib code
proves that a concrete Common declaration and a target actual can cooperate in
the physical stdlib product. Only the irreducible CLR operations remain
intrinsic during bootstrap.

## Dependency proved by compilation

An implementation probe classified `ioH.kt` as Common and the .NET I/O file as
its actual surface. FIR correctly rejected the product:

```text
ioH.kt: unresolved reference 'RuntimeException'
DotNetStdlibIo.kt: ReadAfterEOFException is not a Throwable
```

This is not a missing import. A Common source session cannot depend on the
target-only `RuntimeException` mirror. Supplying a new Common exception stub
would restore a second contract and violate the source-authority rule.
Therefore `ioH.kt` cannot precede the complete Common `ExceptionsH.kt` product.
The probe was reverted; the last pushed source product remains green.

## Completed prerequisite

The full Common exception product now compiles the authoritative
`ExceptionsH.kt` plus shared non-JVM actual class bodies. The narrow .NET
actual surface supplies only the remaining exception identities and Throwable
operations. A versioned weak identity-associated runtime service stores
suppressed exceptions for Kotlin, mapped BCL, and hostile foreign objects
without wrapping, cloning, translation, or mutation of `Exception.Data`.
Stack-trace formatting preserves the exact CLR diagnostic prefix and composes
the Kotlin suppressed graph with reference-identity cycle detection.

The product includes the missing exact identities, a non-empty immutable
snapshot `List<Throwable>`, installed-stdlib consumption, and Framework/CoreCLR
execution. The fresh strict gate is 889/0/0/0 across 16 XML suites. This closes
the dependency found by the failed `ioH.kt` probe. The implementation below
resumes the Common I/O partition and satisfies its validation obligations.

An intentionally empty `addSuppressed` implementation is technically allowed
by the Common documentation for platforms without suppression support, but is
rejected. JS, Wasm, and Native preserve the information even when the host does
not supply Java's mechanism; choosing the weakest permitted behavior would
create known semantic debt solely to make the source file compile.

## Implementation outcome

The exact Common `ioH.kt` is now a classified Common source in both the direct
stdlib product and the byte-identical packaged fallback. The .NET file contains
only actuals and target overloads. FIR actualisation retains `ioH.kt` as the
canonical IR owner, so both files map to one stable
`Kotlin.Io.ConsoleKt` stdlib facade.

`readln` and `readlnOrNull` are ordinary public `Kotlin.Stdlib` functions.
Their only target-private external helper is intrinsic to
`System.Console.ReadLine()`. The internal Common `ReadAfterEOFException` is a
real non-public stdlib class with truthful `Kotlin.RuntimeException` ancestry;
its physical binding remains in the private KLIB index because Kotlin
`internal` declarations are linkable by authorized friends. Separately compiled
ordinary consumers call the two public stdlib methods and never copy either the
EOF policy or the CLR input operation.

`print` and the existing `println` overloads remain resolution-only output
intrinsics. They use Kotlin value rendering before the CLR string overloads,
except for the already-equivalent direct `Char` `println` path. The inert
`Serializable` marker is now an actual. This follows JVM for available standard
input and its `ConsoleKt` facade, and JS/Wasm/Native for the marker. The only
CLR-specific mechanism is the proven `Console.ReadLine` host operation.

The new MemberRef was assembled and executed independently before codegen on
Framework CLR 4 and CoreCLR 10. A `netstandard2.0` library containing the same
reference was then consumed on both runtimes. This change adds ordinary
stdlib-function entries using the existing physical Function record; it changes
neither the physical-index grammar nor `Kotlin.Runtime`, so ABI schema 16 and
runtime surface level 9 remain current.

## Validation evidence

The implementation proves:

- explicit products reject `ioH.kt` when it is presented as a platform source;
- direct-source and packaged-fallback stdlibs remain byte-identical;
- both PSI and LightTree compile the expanded Common/actual product;
- `print` preserves Kotlin rendering rather than CLR culture/casing;
- redirected CRLF input loses its terminator;
- `readlnOrNull` returns `null` at EOF;
- `readln` at EOF throws a `RuntimeException` with the Common message;
- the behavior works on both CoreCLR and Framework CLR profiles.

The fresh strict gate is 889/0/0/0 across 16 XML suites: 802 FIR/IL/box tests,
21 generated CLI tests, and 66 library-integration tests.
