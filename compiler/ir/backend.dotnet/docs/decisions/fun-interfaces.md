# ADR: Kotlin fun interfaces use Common SAM wrappers

- Status: **Accepted pre-ABI**
- Scope: Kotlin-owned `fun interface` declarations, implicit and explicit SAM
  conversion, generic and suspend abstract methods, wrapper equality, separate
  Kotlin libraries, and the ordinary CLR interface surface
- Does not enable: implicit conversion to or from CLR delegates, foreign CLR
  SAM conversion, delegate export, or C# lambda conversion to a Kotlin
  interface

## Decision

Kotlin/.NET uses the repository's Common
`SingleAbstractMethodLowering` as the semantic owner of Kotlin fun-interface
conversion. A `fun interface` declaration remains the same ordinary
Kotlin-owned interface selected by the generic-interface ABI. Each conversion
creates an ordinary compiler-generated class which implements that interface
and stores the converted Kotlin `FunctionN` object:

```text
fun interface Action { fun run(value: String): Int }
val action = Action { it.length }

internal class sam$Action(private val function: Function1) : Action {
    override fun run(value: String): Int = function.invoke(value)
}
```

The notation above is illustrative. Names and erased member spellings are
compiler ABI, not Kotlin source API. The generated wrapper is an
assembly-private implementation detail and never becomes a second declaration
identity for `Action`.

The backend does not reconstruct the conversion from CLR metadata and does
not map the interface to `System.Delegate`, `System.Func`, or `System.Action`.
FIR supplies the authoritative `SAM_CONVERSION` and selected abstract method;
Common IR supplies the wrapper class, constructor, field, forwarding method,
null handling, and equality/hash-code protocol. The .NET target supplies only
the target visibility, raw Kotlin-interface view, suspend-method lookup, and
physical compiler/runtime binding required by that shared lowering.

## Cross-target architecture

JVM, JS, Wasm, and Native all consume Common
`SingleAbstractMethodLowering`; JVM and Native provide target subclasses, and
Wasm reuses the JS subclass. JVM lowers optimized function references before
the general SAM pass, Native makes function-reference lowering a prerequisite,
and JS/Wasm may order the shared SAM transformation earlier in their local
declaration pipelines.

.NET follows the JVM/Native ownership boundary. Its callable-reference pass
first materializes the established Kotlin-owned `FunctionN` object while
deliberately preserving the surrounding `SAM_CONVERSION`. The Common SAM pass
then wraps that value before local-declaration closure conversion, coroutine
continuation lowering, default dispatch, generic-interface bridges, and final
code generation. Later target passes therefore see only ordinary classes,
fields, constructors, virtual interface methods, and calls.

The Common pass asks each target for the wrapper's interface view. .NET uses
the declaration-erased default type, matching Native and JS/Wasm. Logical type
arguments and substitutions remain authoritative in IR/KLIB; the existing
generic-interface lowering supplies the canonical erased slot and any
independently truthful physical bridge. SAM conversion does not reopen the
accepted erased identity of Kotlin-owned generic classes or interfaces.

## Wrapper equality and runtime capability

Kotlin fun-interface wrappers follow the Common/JVM/JS/Wasm equality model.
A wrapper generated from a functional value implements the reserved
`FunctionAdapter` capability and returns its stored Kotlin `Function` delegate.
Common-generated `equals` first requires the same SAM interface and the
capability, then compares the underlying function delegates; `hashCode`
delegates to the same value. Distinct lambda objects therefore remain
distinct, while conversions of function values and references retain the
shared lowering's established equality behavior.

`FunctionAdapter` is a metadata-public, non-generic interface in
`Kotlin.Runtime.Internal`, because generated wrappers live in arbitrary user
assemblies. It is marked as compiler ABI and exposes exactly one method:

```text
Kotlin.Function getFunctionDelegate()
```

The logical symbol is an IR-only compiler stub. It is not a Kotlin source
declaration, is not serialized into a user KLIB, and is not an API for user or
C# code. Adding the capability increments the runtime surface contract; a
consumer must reject a runtime which predates it rather than silently changing
wrapper equality.

## Declaration and conversion semantics

A fun-interface declaration is emitted as its ordinary CLR interface TypeDef.
Its single abstract method, inherited abstract method, default members,
properties, variance, generic constraints, nested placement, visibility, and
separate-library binding use the existing interface pipelines unchanged. The
`fun` modifier changes source conversion eligibility; it does not add a CLR
delegate base, custom attribute, hidden state, or alternative TypeDef.

The Common lowering owns:

- implicit argument, assignment, return, and nullable SAM conversions;
- explicit fun-interface constructor syntax;
- receiver and primitive adaptation already represented in the selected
  function type;
- exactly-once evaluation of the converted function value;
- generic-owner substitutions of the selected abstract method;
- inherited single-abstract-method selection; and
- suspend abstract methods, whose generated forwarding function enters the
  existing Kotlin continuation/state-machine pipeline.

The backend must reject residual `SAM_CONVERSION` IR before emission. It must
not add an emitter fast path, infer a SAM from “one abstract CLR MethodDef,” or
accept a foreign CLR interface merely because its metadata happens to have one
abstract member.

## CLR and C# view

C# sees a Kotlin fun interface as the same ordinary CLR interface it would see
without the `fun` modifier. C# may call it or implement it with an ordinary
class when the existing interface authoring rules admit that declaration.
C# does not receive implicit lambda conversion because CLR delegate identity
is a separate interop contract. Generated SAM wrapper types and
`FunctionAdapter` are compiler infrastructure and are not supported authoring
surfaces.

Any future explicit delegate export must be opt-in and fail closed. It may
adapt a Kotlin fun interface at the boundary, but it cannot replace the Kotlin
interface identity, change Kotlin casts or equality, or make arbitrary CLR
delegates satisfy Kotlin `is Action`.

## Separate compilation

The producer KLIB records the fun-interface declaration and logical abstract
method exactly as other targets do. The producer DLL records the ordinary
interface owner and member binding. A consumer which performs a SAM conversion
generates its wrapper in the consumer assembly and binds its implemented slot
through the producer's self-describing physical ABI. No wrapper TypeDef,
constructor name, or private field crosses the library boundary.

An inline producer body may contain a logical conversion in serialized IR.
After inlining, the consuming compilation runs the same Common SAM lowering
and creates a local wrapper against its selected runtime surface. This is why
wrapper visibility and naming are never public Kotlin ABI.

## Alternatives rejected

### Map every fun interface to a CLR delegate

Rejected. A delegate is a sealed CLR class identity, not a Kotlin interface.
It cannot preserve Kotlin interface inheritance, user implementations,
defaults, casts, reflection identity, or generic-interface ABI.

### Let the emitter construct an anonymous CLR type

Rejected. The Common lowering is the cross-target semantic authority, and
later .NET passes already know how to lower ordinary generated classes.
Emitter-owned construction would bypass closure conversion, coroutine
lowering, interface bridges, KClass behavior, and IR validation.

### Omit `FunctionAdapter` equality

Rejected. Identity-only wrappers would diverge from the shared Kotlin
fun-interface contract for stored functions and callable references. The
small versioned runtime capability is preferable to a target-specific semantic
fork.

### Reuse `System.Delegate` as the stored value

Rejected. Kotlin `FunctionN` is the established runtime execution identity.
CLR delegate projection is an explicit interop adapter and cannot become the
internal callable representation.

## Completion evidence

The selected gate must cover both FIR parsers and both runtime profiles,
including:

- direct lambdas, stored function values, bound and unbound function
  references, receiver adaptation, and exactly-once evaluation;
- nullable conversion, primitive parameters/results, generic fun interfaces,
  inheritance, default members, and suspend abstract methods;
- wrapper equality/hash behavior and negative cross-interface equality;
- `is`/`as` through the ordinary interface identity;
- physical absence of a CLR delegate base or extra public wrapper TypeDef;
- the compiler-ABI `Kotlin.Runtime.Internal.FunctionAdapter` surface;
- a portable producer consumed by Kotlin on both CLR profiles; and
- Roslyn inspection and execution against the ordinary interface surface,
  without claiming C# lambda conversion.
