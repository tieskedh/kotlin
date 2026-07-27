# Draft ADR: CLR Default Arguments on Explicit Exports

## Status

Draft for the Kotlin/.NET proof-of-concept branch.

## Context

Kotlin default arguments and CLR optional parameters have different execution models. A Kotlin
default can be any expression, can refer to earlier parameters, and is evaluated by callee-owned
code on each call. The backend already preserves that contract with masked `$default` dispatchers.

C# optional arguments instead require metadata that a caller can turn into an argument. Their
source-language defaults are restricted to constants or default value-type expressions, and the
compiled caller embeds the selected value. The CLR metadata can also mark a parameter optional
without a constant, but Roslyn then supplies `default(T)`. That is not a representation of an
arbitrary Kotlin default expression.

The export boundary must not weaken Kotlin semantics merely to make a facade look idiomatic to
C#. It also must not change the canonical Kotlin method or callable ABI.

## Decision

An explicit `-Xdotnet-export` keeps its full facade method. Ordinary positions retain their mapped
CLR types, while Function0/1/2 positions use typed Func/Action shapes. When the selected Kotlin
function has a contiguous suffix of parameters with source defaults, the compiler also emits one
ordinary CLR overload for each progressively omitted trailing parameter.

Every shorter overload:

- retains the original exported parameter order and Func/Action adaptation for supplied callable
  parameters;
- supplies physical zero/null placeholders for omitted parameters, including zero-initialized
  locals for nullable value types;
- sets the same masked bits as Kotlin call lowering and invokes the already generated `$default`
  dispatcher; and
- applies the ordinary callable return projection after that dispatcher returns.

No overload evaluates or copies a default expression. Defaults that allocate, call other
functions, capture earlier parameters, or change in a later library version therefore retain the
same callee-owned behavior as Kotlin calls.

This slice emits neither `[opt]` nor constant `.param` values. Even a Kotlin literal default goes
through `$default`. A future, explicitly versioned export policy may opt into CLR constants, but
that would be a separate ABI decision because already compiled callers retain the old value.

Only a contiguous trailing default suffix produces overloads. A non-trailing Kotlin default still
works through the canonical Kotlin `$default` ABI, but the CLR facade requires the full argument
list. Generating every omission subset would be exponential and can produce indistinguishable CLR
signatures, such as omitting either one of two adjacent `Int` parameters. This draft does not invent
alternate method names for those cases.

An overload that collides with an existing facade method or another requested export is a
compilation error. The compiler never silently drops only the conflicting overload, because that
would make the requested export's default-argument surface depend on unrelated declarations.
For an overloaded Kotlin name, the explicit expanded-parameter selector first chooses one source
declaration; only that declaration's trailing default suffix generates shorter CLR overloads.
Selector types are not CLR optional-argument metadata and do not change masked dispatch.

### Generated `$default` identities are collision-checked

**1. Other-target rule.** JVM, JS, Native, Wasm, and .NET all build default stubs from the common
`DefaultArgumentStubGenerator`; JVM, Native, Wasm, and .NET use a masked dispatcher shape. JVM
spells the method `<name>$default` and has dedicated
`CONFLICTING_JVM_DECLARATIONS` tests where a backtick-named source function occupies exactly that
generated signature, including `copy$default`. JS/Native/Wasm use their own mangled physical
identities, but likewise keep the generated stub distinct from the source declaration.

**2. CLR-specific difference.** `$` is legal in a quoted CLR metadata identifier, while Kotlin can
deliberately declare the same spelling with backticks. CLR method identity on one TypeDef is
determined by name, generic arity, and parameter signature for this ABI; staticness, visibility,
and return type are not disambiguation mechanisms. A class member dispatcher and a top-level
dispatcher on its file facade therefore each have a real same-owner collision when a source
function supplies the same final shape.

**3. Kotlin Common invariant.** The dispatcher is callee-owned compiler ABI. It evaluates the
selected Kotlin default expressions on every call, in declaration order, and is the target of
cross-module omitted-argument calls. A separately declared backtick-named function is an unrelated
Kotlin declaration. Neither may be merged, dropped, or redirected to the other.

**4. .NET validity rule.** The same masked dispatcher representation is used on `net48`,
`netstandard2.0`, and `net10.0`; modern CLR metadata provides no additional legal duplicate-method
shape. A profile-specific rename would only create divergent ABI without a runtime benefit.

**5. Selected architecture.** Compare source and generated functions after lowering by complete
physical owner and method identity. An actual duplicate rejects the complete class, or every
colliding callable on a file facade, and library publication emits neither KLIB nor DLL. A
same-spelled declaration on another TypeDef remains legal. The compiler never chooses a winner or
renames the dispatcher according to declaration order.

**6. Core-team choice.** Follow the JVM diagnostic precedent and keep one deterministic
`$default` identity plus an explicit clash gate. The current emitter-time report is a **Correct
temporary implementation, but not a final design**; a target FIR/platform diagnostic should
eventually report both source declarations. Atomic rejection and preservation of the common
masked-dispatch semantics are final. Changing the suffix before ABI freeze would not remove the
need for this rule because Kotlin backtick identifiers can deliberately occupy any chosen
metadata spelling.

## Why not CLR optional constants now?

[C# named and optional argument rules](https://learn.microsoft.com/en-us/dotnet/csharp/programming-guide/classes-and-structs/named-and-optional-arguments)
make optional metadata attractive for host-language named omissions. The probe nevertheless found
three reasons not to use it as the default Kotlin mapping:

1. Roslyn substitutes the metadata value in the caller; it does not invoke Kotlin's dispatcher.
2. `[opt]` without a constant supplies `default(T)`, which is observably wrong for almost every
   nontrivial Kotlin default.
3. When a shorter overload and a longer optional method are both applicable, Roslyn prefers the
   shorter overload. Mixing both mechanisms would create two different default execution paths.

The overload-only rule is uniform, preserves Kotlin versioning behavior, and remains compatible
with both modern CoreCLR and .NET Framework metadata.

## Evidence

Probe series `defaultexport_s1` assembled optional constants, non-trailing optional metadata,
optional-without-constant metadata, overload preference, and generated `$default` overload calls.
Roslyn 5.6.0 compiled the consumers. The compiler-produced facade assembled with modern 10.0.9 and
.NET Framework 4.8 ILAsm, and C# consumers executed the full/trailing/zero-argument, dependent
default, callable-adaptation, and nullable-value cases on both runtimes.

Repository IL pins cover masks, placeholders, nullable locals, metadata on shortened signatures,
and the absence of a non-trailing overload. CLI pins cover a positive shorter overload and a loud
collision diagnostic.

## Deferred decisions

- an opt-in policy for caller-embedded CLR constants and its binary-versioning consequences;
- named omission of non-trailing Kotlin defaults;
- default arguments on future member, property, constructor, or class export kinds;
  and
- generic and suspend exports, which remain outside the current explicit boundary.
