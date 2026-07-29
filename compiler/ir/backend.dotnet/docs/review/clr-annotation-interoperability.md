# CLR annotation interoperability review

Date: 2026-07-29

Status: first foreign nullable-aware FIR slice implemented; public Kotlin/.NET source-annotation
names remain undecided.

## Governing rule

Kotlin Common remains authoritative for Kotlin semantics. At the foreign boundary, use each fact's
most native representation:

1. a CLR table, signature, or flag when it expresses the fact exactly;
2. an established .NET/Roslyn attribute when it is the shared language understood by C# and .NET
   tooling;
3. a small Kotlin-specific attribute when declaration-local runtime/tooling use justifies one;
4. embedded KLIB metadata for the exact Kotlin remainder.

The objective is not to maximize the number of attributes. It is to maximize the useful,
truth-preserving .NET view while keeping one authoritative producer model. Every redundant view
must be derived from that model and checked for drift.

For a foreign assembly, a well-formed standard attribute is an authored binary contract. It may
therefore change the Kotlin type or call-site view, just as a Java nullability annotation changes a
Kotlin/JVM enhanced type. This is compile-time trust, not proof that foreign runtime code obeys its
contract.

## Mature-target precedent

| Target | Foreign declaration to Kotlin | Kotlin declaration to foreign code | Exact Kotlin remainder |
|---|---|---|---|
| JVM | Java type-use nullability from JSpecify, JetBrains annotations, and configured JSR-305 qualifiers/defaults enhances platform types; Java members, properties, SAMs, and checked-exception metadata receive JVM-specific views | `@JvmName`, `@JvmStatic`, `@JvmField`, `@JvmOverloads`, `@JvmSynthetic`, `@JvmExposeBoxed`, and `@Throws` shape Java-facing bytecode | Kotlin metadata remains necessary because a class file and Java annotations do not reconstruct Kotlin declarations exactly |
| Native/Objective-C | Clang declarations and Objective-C nullability/header metadata create enhanced Kotlin foreign declarations | `@ObjCName`, `@HiddenFromObjC`, `@ShouldRefineInSwift`, `@Throws`, and `@CName` shape exported names and views | KLIB remains the Kotlin compiler representation |
| JS | `external` declarations plus `@JsModule`, `@JsQualifier`, and `@JsName` describe the foreign module/view | `@JsExport`, `@JsName`, and `@JsStatic` shape the JavaScript-facing surface | Kotlin metadata/IR remains necessary; JavaScript declarations are not a reversible Kotlin encoding |
| Wasm | `@WasmImport` declares a foreign Wasm boundary | `@WasmExport` and, for Wasm-JS, `@JsExport` shape the host-facing surface | Kotlin metadata/IR remains necessary |

The relevant precedent is therefore JVM foreign-type enhancement, not runtime reflection.
Reflection may later consume retained attributes, but the compiler can decode physical attributes
directly while constructing foreign FIR symbols.

Primary Kotlin references:

- <https://kotlinlang.org/docs/java-interop.html>
- <https://kotlinlang.org/docs/java-to-kotlin-interop.html>
- <https://kotlinlang.org/docs/native-objc-interop.html>
- <https://kotlinlang.org/docs/js-to-kotlin-interop.html>
- <https://kotlinlang.org/docs/js-interop.html>
- <https://kotlinlang.org/docs/wasm-configuration.html>

## CLR/Roslyn carrier audit

| Fact | Shared CLR/.NET carrier | Current Kotlin/.NET state | Decision |
|---|---|---|---|
| Nominal types and members | TypeDef, MethodDef, Field, Property, Event, MethodSemantics | Physical rows retained; no foreign FIR provider | CLR is authoritative for a foreign library. Prefer real Property/Event rows over inferred naming |
| Signatures and generic constraints | Signature blobs, GenericParam, GenericParamConstraint | Lossless physical and selected-graph models exist | CLR is authoritative for the physical foreign signature. Apply a separate Kotlin usability policy |
| Declaration nullability | Roslyn `NullableAttribute`, `NullableContextAttribute`, and `NullablePublicOnlyAttribute` | Fully decoded, selected, aligned, and projected to Kotlin qualifier vocabulary below FIR | Trust valid evidence for foreign enhanced types; use flexible types when absent, suppressed, contradictory, or malformed |
| Conditional/flow nullability | `AllowNull`, `DisallowNull`, `MaybeNull`, `NotNull`, `MaybeNullWhen`, `NotNullWhen`, `NotNullIfNotNull`, `MemberNotNull`, and `MemberNotNullWhen` | Raw custom attributes only | Preserve first. Integrate with FIR data flow only per exact contract; do not flatten conditional facts into declaration nullability |
| Kotlin contracts | No general CLR contract format; CodeAnalysis attributes exactly cover a null-state subset such as `returns(true) implies (x != null)` and non-returning functions | KLIB contract metadata only | Bidirectionally map only the exact subset. Keep `callsInPlace`, arbitrary type predicates, and general multi-value implications in KLIB |
| Extension call view | `ExtensionAttribute` plus the physical static signature; newer C# extension declarations require a Roslyn metadata probe | Raw custom attributes only | Consume only after receiver, generic ownership, accessibility, and collision rules are proven. Do not infer from the first parameter alone |
| Variable argument call view | Param-array/collection attributes plus the physical array/collection parameter | Raw custom attributes only | Consume as a Kotlin call-site view only where element and spread semantics are representable. Do not confuse it with Kotlin declaration identity |
| Optional/default values | Param flags and Constant rows | Physical values retained; Kotlin export uses `$default` dispatchers | A CLR constant is authoritative for a foreign CLR optional parameter, but it is not a Kotlin default declaration. Kotlin defaults keep KLIB/dispatcher semantics |
| Indexers/default member | Property signatures and `DefaultMemberAttribute` | Property rows retained | Prefer the property row; use the attribute only for the default/indexer call view after collision rules are specified |
| Compiler-generated declarations | `CompilerGeneratedAttribute` and physical flags/names | Kotlin compiler ABI marker plus `EditorBrowsable(Never)` on selected internals; no general marker | May guide tooling, but never infer a Kotlin logical role or hide ABI solely from this marker |
| Deprecation | `ObsoleteAttribute` | Raw custom attributes only | Project to Kotlin deprecation diagnostics after constructor/named-argument policy is specified |
| Required/init/read-only/by-ref-like semantics | `RequiredMemberAttribute`, `IsExternalInit`, `IsReadOnlyAttribute`, `IsByRefLikeAttribute`, modreqs, and physical flags | Several physical/semantic classifiers exist; no FIR view | Consume only as the selected profile defines them. Attribute names without exact selected identity are insufficient |
| Friend access | `InternalsVisibleToAttribute` | KLIB friend list and emitted CLR attribute are both validated | Deliberate dual view: KLIB grants Kotlin compiler friendship; CLR metadata grants runtime/C# access |
| Target framework | `TargetFrameworkAttribute` | Emitted and validated with the selected product/profile | CLR-facing artifact identity; not a Kotlin declaration fact |
| Kotlin logical declaration identity | No standard CLR carrier | Embedded KLIB declaration keys and physical binding index | Keep KLIB-authoritative |
| Split-interface implementation graph | No standard CLR carrier for logical keys, owner views, intersection contributors, wrong-shape policy, or one-logical-to-many-physical slot relations | Public implementation manifest | Keep the manifest. Encoding the same graph in one custom attribute would only reserialize the manifest |

Microsoft references:

- <https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/attributes/nullable-analysis>
- <https://learn.microsoft.com/en-us/dotnet/api/system.runtime.compilerservices.compilergeneratedattribute>
- <https://learn.microsoft.com/en-us/dotnet/api/system.runtime.compilerservices>
- <https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/language-specification/classes>
- <https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/keywords/extension>

`<Nullable>enable</Nullable>` activates Roslyn's annotation and warning contexts. The annotation
context gives unadorned references a non-null contract and permits `?`; the warning context runs
null-state data-flow analysis. `annotations` and `warnings` may also be enabled independently, and
`#nullable` may override either within a file. The project property is not itself stored in a DLL.
Roslyn writes its declaration-level consequences as compressed nullable context/local attributes;
CodeAnalysis attributes add the conditional flow vocabulary.

## Why KLIB is not the preferred carrier for shared facts

A C# author can write standard attributes or use syntax that makes Roslyn emit them. That makes
attributes a bidirectional interoperability vocabulary, not merely an output encoding. Kotlin
should consume that vocabulary and Kotlin exports should populate it when the representation is
truthful.

KLIB remains necessary where lowering is many-to-one or one-to-many. CLR execution can preserve
behavior without preserving which method was the Kotlin declaration, which was a default
dispatcher, which methods are bridges, or which physical slots jointly implement one logical
member. A large assembly-level attribute containing that graph would be Kotlin metadata under a
different envelope, not greater C# interoperability.

## First FIR slice

The first provider slice is intentionally closed:

- resource-free foreign assemblies only; a Kotlin metadata resource continues to select KLIB;
- public, top-level, non-generic CLR interfaces;
- only public abstract instance methods with ordinary names, default managed calling convention,
  no body/runtime implementation flags, no method type parameters, no varargs, and no
  by-reference/pointer/function-pointer types;
- the initial type grammar is `void`, CLR primitive scalars, `string`, and `object`;
- every public method declared by an imported interface must fit the grammar. Otherwise the entire
  classifier is withheld so the provider cannot present a falsely complete contract;
- Roslyn nullable evidence enhances `string` and `object` occurrences to non-null, nullable, or
  flexible Kotlin types;
- absent, suppressed, invalid, contradictory, or unresolvable nullable evidence becomes flexible;
  it is never guessed as non-null;
- duplicate classifier identities across selected assemblies are withheld;
- the provider is lazy at classifier construction and has no backend-call mapping yet.

This slice is useful before backend-call support because source analysis can resolve foreign
contracts and issue the same nullability errors Kotlin/JVM issues for enhanced Java declarations.
A successful foreign call must not reach code generation until physical IR/backend binding is
implemented; the first integration fixture therefore proves FIR resolution with a deliberate
frontend type error.

The selected assembly graph is owned by the build frontend. This first slice binds only an exact,
unique AssemblyRef identity already present on the classpath. It does not probe directories,
retarget versions, or load an unselected framework assembly.

Implementation evidence: `DotNetClrFirSymbolProvider` is installed only for assemblies already
classified as foreign. The Roslyn fixture exercises nullable, non-null, and oblivious returns and
parameters; a property-bearing interface proves whole-classifier withholding, and a second
assembly defining the same package/class proves collision withholding. The focused test is
1/0/0/0. The fresh strict gate is 872/0/0/0 across 16 XML suites (796 FIR/IL/box, 21 generated
CLI, and 55 library integration tests).

## Adversarial review

The implementation and fixture must attack these ideas:

- an unannotated CLR reference is not silently non-null;
- malformed or duplicate nullable attributes do not strengthen a type;
- a nullable return cannot be assigned to non-null Kotlin `String`;
- an annotated non-null return can participate in the same source without an additional
  nullability error;
- a partly unsupported public interface is not exposed with members silently missing;
- generic, nested, static/default-interface, by-reference, and invalid-name shapes remain outside
  the closed slice;
- two assemblies defining the same Kotlin classifier identity do not win by classpath accident;
- Kotlin-produced DLLs remain on the embedded-KLIB path and are never re-imported as foreign CLR
  declarations.

## Deferred big decision

Public Kotlin source annotations analogous to the JVM/Native/JS export families are desirable, but
their package, names, targets, retention, ABI stability, and interaction with ordinary Kotlin
semantics are public-language decisions. Do not invent `DotNetName`, `DotNetExport`, or related
names as part of the foreign provider. Established CLR/Roslyn attributes can be consumed without
that decision.
