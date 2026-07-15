# Draft ADR: Kotlin Any foundation on CLR

- Status: **Draft candidate; minimal member model implemented in the prototype for evaluation**
- Date: 2026-07-15
- Scope: Kotlin `Any` representation, virtual members, and cross-assembly runtime semantics

This is a repository-local decision record for the experimental .NET backend. The entire `dotnet`
branch is a proof of concept; this document keeps that POC internally coherent while evidence is
collected. It does not claim acceptance by the Kotlin project and is not a public KEEP.

## Context

Kotlin `Any` is the logical supertype of every non-null Kotlin value. On CLR that set includes
ordinary generated reference classes, `System.String`, boxed primitives, CLR arrays, mapped CLR
exceptions, and eventually foreign CLR objects. It must also compose with Kotlin classes that
inherit a mapped exception and therefore physically extend `System.Exception`.

The CLR has one root class, `System.Object`, and single class inheritance. Its three virtuals are
the physical counterparts of Kotlin's members, but their names differ:

| Kotlin member | CLR slot |
| --- | --- |
| `equals(Any?): Boolean` | `System.Object.Equals(object): bool` |
| `hashCode(): Int` | `System.Object.GetHashCode(): int32` |
| `toString(): String` | `System.Object.ToString(): string` |

The current POC already maps `Any`/`Any?` storage to `object`, widens every reference to it without
an instruction, boxes values at that boundary, makes generated classes extend `System.Object`,
and maps Kotlin exceptions to `System.Exception` descendants. Adding a separate runtime root now
would contradict those established representations.

## Decision drivers

The foundation must:

1. make every supported Kotlin value representable as `Any`;
2. preserve CLR virtual dispatch for Kotlin overrides;
3. keep generated classes, mapped exceptions, strings, arrays, boxed values, and foreign objects
   in one physical hierarchy;
4. preserve the existing cross-assembly `Kotlin.Runtime` identity without copying helpers into
   generated modules;
5. run unchanged on .NET Framework 4.8 and modern CoreCLR; and
6. leave Kotlin metadata responsible for the logical `kotlin.Any` type even when CLR metadata
   exposes `System.Object`.

## Considered alternatives

### A public `Kotlin.Any` runtime base class

Rejected. A generated class could extend it, but `System.String`, CLR arrays, boxed primitives,
ordinary CLR objects, and `System.Exception` do not. A Kotlin exception class cannot extend both
`Kotlin.Any` and `System.Exception` because the CLR has single inheritance. This would create two
physical roots for one Kotlin type and make interop values fail ordinary assignability checks.

### A public `Kotlin.Any` marker interface

Rejected as the universal identity. Existing CLR types do not implement a new Kotlin interface,
and the compiler cannot retroactively add it. Wrapping every foreign, boxed, array, string, and
exception value would change identity and undermine the established `object` storage model. A
future capability interface may serve a narrower purpose, but it cannot represent `Any` itself.

### Direct `System.Object` mapping with compiler-local semantics only

The physical mapping is selected, but duplicating null-safe equality, Kotlin hash normalization,
and string conversion into every generated module is rejected. Those operations are stable
compiler/runtime behavior and belong once in `Kotlin.Runtime.Internal`, like JVM
`kotlin.jvm.internal.Intrinsics`.

## Candidate decision

Kotlin `Any` has no standalone CLR type definition. Its physical type is
`[mscorlib]System.Object`, following the JVM target's mapping of `kotlin.Any` to
`java.lang.Object`. Native and Wasm own their root classes because their object models require
one; the CLR already supplies the interoperable root.

Generated classes with no proper Kotlin base continue to extend `System.Object`, and their
constructors continue to call `System.Object::.ctor()`. No class hierarchy migration or new
AssemblyRef is required. Mapped exceptions remain in the `System.Exception` hierarchy and are
therefore ordinary `Any` values through `System.Object`.

Declarations overriding Kotlin Any reuse the existing CLR slots: the emitter maps the three
Kotlin names above and emits `virtual` without `newslot` for a class override. Calls through
`Any`, a fake override, a user override, or a base-class view use the same System.Object member
signature; normal calls dispatch virtually, while `super` calls remain non-virtual.

`Kotlin.Runtime.Internal.Intrinsics` owns three metadata-public compiler/runtime helpers:

```text
bool AreEqual(object left, object right)
int HashCode(object value)
string StringValueOf(object value)
```

`AreEqual` implements Kotlin `==`: if `left` is null it returns whether `right` is null; otherwise
it normally calls `left.Equals(right)` virtually. A boxed Double pair instead compares canonical
IEEE bits, matching `java.lang.Double.equals`: all NaN payloads compare equal while `-0.0` and
`0.0` remain distinct. Exact primitive, string, array, and nullable-primitive fast paths may remain
in codegen, but the universal fallback is this helper. A value or open type parameter is boxed only
when it reaches the object fallback. `===` remains a direct reference comparison and never calls
the helper.

`HashCode` normally dispatches `GetHashCode()` virtually. It supplies Kotlin/JVM-compatible
Boolean constants and canonical Double bit hashing so signed zero and all NaN payloads stay
consistent with `equals` on both CLR runtimes. `super.hashCode()` remains a direct, non-virtual
System.Object call.

`StringValueOf` returns the literal `"null"` for null and otherwise calls `ToString()` virtually.
String templates, concatenation, `println(Any?)`, and ordinary `Any.toString()` calls use it.
The latter shares the type-directed conversion path because FIR may retain the Any symbol for a
statically primitive receiver; that preserves Kotlin's lowercase Boolean and Double formatting.
At a boxed boundary the helper itself recognizes Boolean, Int, Long, and Double: Boolean stays
lowercase, integers use invariant culture, and Double uses the existing Kotlin formatting helper.
An explicit `super.toString()` call binds non-virtually to the System.Object slot.

The helper type is public only because generated callers live in other assemblies. Its
`Kotlin.Runtime.Internal` namespace identifies it as compiler/runtime ABI, not a Kotlin or C# user
API. `Kotlin.Any` remains logical compiler and Kotlin-metadata information; CLR reflection sees
`System.Object` in physical signatures.

## Validation

Probe series `dotnet-any_s1` assembled a runtime helper assembly, a separate library containing a
class that overrides all three System.Object slots plus a `System.Exception` subclass, and a
separate consumer. Modern 10.0.9 and .NET Framework 4.8 ILAsm both accepted every artifact. All
four consumer/dependency pairings ran on their corresponding CoreCLR/Framework runtime and
observed identical equality, hash, string, null, and exception-root behavior. A follow-up boxed
primitive probe demonstrated why the helpers cannot be thin Object calls: both CLRs equate signed
zero and render boxed `2.0e19`/Boolean as `2E+19`/`True`, both collapse signed-zero hashes, and
Framework hashes distinct NaN payloads differently. Runtime boxes pin the normalized Kotlin
results.

Compiler promotion requires exact-IL pins for slot names and flags, runtime boxes for virtual and
null-safe behavior, and continued assembly under both ILAsm implementations.

## Later consumer: bounded data classes

A later slice now consumes this foundation for non-generic top-level and named nested data classes
with supported primary-constructor properties. It does not revise the Any decision. Fir2ir's shared
generated bodies reuse the physical System.Object slots and runtime Any helpers: `equals` adds a
CLR `isinst` plus checked `castclass`, `hashCode` and `toString` use the existing normalized
helper/conversion paths, and `componentN`/`copy` remain ordinary members. Array properties retain
identity equality while dedicated runtime helpers provide the JVM-shaped content hash and text.
Ordinary function defaults, including `copy`, use a masked instance `$default` helper; constructor
defaults use the common/JVM masks plus the runtime-owned nullable marker. Neither adds an object
root or callable identity. A named nested data class uses the existing static-nested metadata
identity and captures no outer type argument, including below a generic outer.

The slice rejects generic data classes because CLR reified `isinst C<T>` is stricter than Kotlin's
erased class identity. Local data classes and data objects also remain gated. The gate runs before
class registration, so an unsupported generated body cannot leave behind a partial class.

## Deliberate boundaries

This foundation does not by itself enable data objects, unsupported data-class families, interface
redeclarations of Any members, Kotlin-owned exception classes, general type tests/casts, or a
complete foreign-object import model. The later bounded data-class consumer above adds only
non-generic module-class tests/downcasts. Generic `T : Any` constraints remain deferred: mapping
that bound to CLR `class` would
incorrectly reject value-type instantiations, while erasing it entirely would admit null. Those
features may consume this decision in later slices, but must keep the one physical System.Object
root. Default `System.Object.ToString()` text is platform-specific and is not an ABI promise.

## Consequences

- Kotlin and CLR values share one physical root with no wrapper allocation for reference upcasts.
- Kotlin Any overrides participate in ordinary CLR virtual dispatch and are visible naturally to
  C# callers as `Equals`, `GetHashCode`, and `ToString`.
- Cross-assembly helper calls add a small stable runtime contract but no new public Kotlin type.
- Value-shaped operands box at the universal Any fallback; specialized codegen remains free to
  avoid that cost without changing identity or semantics.
- A future change to a distinct `Kotlin.Any` class would be an incompatible representation break,
  not an incremental runtime-library addition.
