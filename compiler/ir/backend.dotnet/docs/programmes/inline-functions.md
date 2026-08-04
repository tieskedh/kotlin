# Ordinary inline functions programme

- Status: **Completed compiler foundation — selected Common collection adoption may begin**
- Scope: ordinary Kotlin `inline`, inline lambda control flow, and same-/cross-module compiler ABI
- Explicitly excluded: reified type parameters, `typeOf`, broad reflection, suspend inline functions,
  and target-specific source substitutes

## Kotlin authority

Kotlin `inline` is a language and compiler contract. The source declaration, visibility,
nullability, contracts, lambda modifiers, non-local control flow, evaluation order, and logical
identity remain the Common/FIR/KLIB declarations. A CLR method body or JIT inlining decision is not
Kotlin inlining: it cannot substitute lambda bodies, implement non-local returns, expose
`@PublishedApi` compiler ABI, or make an inline body available to a separately compiled consumer.

Common stdlib sources remain authoritative. The first collection beneficiaries are the generated
non-reified `any(predicate)`, `all`, `indexOfFirst`, and `indexOfLast` families required by
`AbstractCollection` and `AbstractList`. They land only after the compiler slice below is complete;
they are not copied into .NET source and are not published as non-inline substitutes.

## Mature-target precedent

JS, Wasm, and Native are the relevant KLIB targets. Their first compilation stage uses the shared
`loweringsOfTheFirstPhase` sequence:

1. avoid local fake overrides in inline bodies, materialize version overloads, and reject inline
   call cycles;
2. when intra-module inlining is enabled, lower `lateinit`, shared variables, local classes in
   inline lambdas, and array constructors;
3. inline private functions, validate inline declarations, create required outer-this and
   synthetic accessors, and validate the private-inline result;
4. inline the selected intra-/cross-module set, prepare the remaining public inline bodies for
   KLIB serialization, remove redundant casts, and validate the result.

Their binary stage begins with the corresponding common lowering/inliner prefix so bodies that
remain for the selected KLIB inliner mode receive the same semantics before target lowerings.
KLIB stores both normal serialized IR and, when produced by the first stage, the prepared
inlinable-functions component. The logical declaration in metadata remains authoritative.
Dependency deserialization on mature KLIB paths explicitly retains inline bodies when they may be
consumed without whole-world linking. Kotlin/.NET follows that ownership: every selected Kotlin
dependency keeps the required body components, while a foreign CLR assembly never receives a
synthetic Kotlin inline body.

JVM uses its class-file inline-body representation and JVM backend sequence rather than KLIB IR,
but establishes the same invariant: a consumer receives compiler-readable body information and
Kotlin compiler ABI is distinct from ordinary source-public API.

## Corrected baseline

The completed slice corrects the former false partial support rather than teaching the CLR JIT a
target substitute:

- the shared packed-KLIB adapter retains metadata, main IR, and prepared-inlinable IR while its
  metadata-only entry point deliberately keeps the old narrow contract;
- `compiler:ir:serialization.dotnet` owns the .NET mangler and target IR serializer;
- every self-describing Kotlin library DLL embeds main IR and embeds prepared inline copies when
  the selected Common first stage produces them;
- stdlib IR paths use Common KLIB relative-path serialization against the authoritative stdlib
  source layout, keeping repository-source and packaged-fallback DLLs byte-for-byte reproducible;
- the CLI runs the shared pre-serialization phases before serialization and CIL production;
- the binary stage runs the mature KLIB inline prefix when the first stage did not already mutate
  that same IR tree;
- cross-library body resolution treats prepared IR as authoritative, uses main IR as the legacy
  fallback, and binds exact public signatures through the frontend-selected dependency symbol
  finder without starting a general IR linker;
- captured mutable state uses Common's `kotlin.internal.SharedVariableBox<T>` compiler ABI; and
- ordinary non-reified generic inline methods are admitted while reified and suspend functions
  still fail explicitly.

An ordinary physical CLR method remains for each supported inline declaration. It is a callable
fallback and useful C# shape, but the tests require Kotlin call sites to disappear after IR
inlining rather than relying on that method or on JIT heuristics.

`@InlineOnly` is the deliberate exception documented by the
[`@InlineOnly` physical ABI ADR](../decisions/inline-only-physical-abi.md). Its logical declaration
and body remain public Kotlin KLIB API, while its physical CLR method is assembly-visible and not a
C# or cross-assembly fallback. JVM uses package visibility for the same contract; JS, Wasm, and
Native rely on KLIB linking/inlining rather than publishing an independently callable host-library
method. A separate .NET consumer must inline the body, and the existing post-inlining validation
rejects a call whose external body was unavailable.

Non-linking deserialization treats compiler-owned IR operators as part of `IrBuiltIns`, not as
library dependencies. It first matches their exact public signature and may use a unique
compiler-owned `CallableId` only when that operator has no overload ambiguity; genuinely missing
library declarations remain unbound and fail the selected-graph closure check. An inlined body's
external `GET_OBJECT kotlin.Unit` likewise loads the already selected runtime `Unit.INSTANCE`;
other object expressions must pass through the ordinary object lowering and remain rejected if
they survive it. These are shared-IR reconstruction rules, not .NET source or ABI declarations.

## CLR constraints and non-constraints

The CLR supplies ordinary generic methods, closures, arrays, exceptions, and public metadata
members. None requires a different logical inline model. CLR generic reification is useful for the
physical ordinary method that remains callable, but does not implement Kotlin reified-inline
substitution and is not a reason to couple the two features.

The genuine CLR constraint is visibility: code copied into a consumer assembly can call only
physically linkable producer declarations. `@PublishedApi internal` and any synthetic accessor
that Common makes public must therefore be CLR-public compiler ABI, retain Kotlin-internal
metadata, and carry `KotlinCompilerAbiAttribute` plus `EditorBrowsable(Never)`. An accessor for an
`internal inline` friend API remains internal and links through the producer's exact friend
authorization instead of being widened. Compiler-only surfaces remain absent from ordinary C#
exports and the C# implementation manifest.

## Kotlin-aligned target choice

### 1. Complete the embedded KLIB component adapter

Add a component-complete packed-KLIB loader in shared `compiler:util-klib` infrastructure. It
reuses the existing canonical ZIP, central-directory, duplicate-entry, CRC, and expansion-budget
validation, retains metadata, main IR, and prepared-inlinable IR components, and keeps the
containing DLL as `KotlinLibrary.path`.

Keep `loadPackedMetadataKlib` as the explicit metadata-only compatibility entry point and its test
that ignores unknown components. The .NET loader switches to the component-complete entry point.
Missing, partial, duplicated, malformed, or unexpectedly shaped IR components fail as KLIB
format errors; they do not silently downgrade a Kotlin library to metadata-only inline behavior.

### 2. Give IR serialization a target serialization owner

Create `compiler:ir:serialization.dotnet`, parallel to `serialization.js` and
`serialization.native`. It owns `DotNetIrMangler`, the global declaration table, file serializer,
and module serializer. Backend CIL code continues to consume the mangler for the same logical
identities, but CLI/FIR2IR no longer imports that identity implementation from `backend.dotnet`.

One validated source-product selection owns both metadata and IR serialization. A stdlib product
serializes exactly `DOTNET_STDLIB_SOURCES`; a user library excludes injected bootstrap stdlib
files. Prepared inline-function copies are filtered by the same file selection. Injected
resolution sources must never leak into a user KLIB merely because IR serialization was added.
HMPP Common FIR files are not assumed to have a one-to-one post-actualization IR file. Selection
therefore excludes the known injected source paths from the actualized main IR, preserving
plugin-generated files, rather than consulting a platform-only FIR-to-IR file cache.

The direct and packaged-fallback stdlib source products have different physical roots but the
same `DOTNET_STDLIB_SOURCE_PATHS` layout. The stdlib serializer derives that product root and feeds
it to Common `IrSerializationSettings.sourceBaseDirs`; no .NET-specific IR path encoding is
introduced.

The library packager includes `SerializedIrModule` next to metadata in the existing embedded KLIB.
Applications run the first-stage lowering sequence but do not manufacture a library IR resource.
Libraries serialize after that sequence, then produce their CLR DLL from the same transformed IR.
When intra-module first-stage inlining is enabled, the binary stage does not rerun that prefix on
the same declarations: doing so would attempt to freeze the generated outer-this/accessor shape
twice. With the first-stage inliner disabled, the binary prefix owns those transformations.

### 3. Run the shared first and second stages

Add a .NET `PreSerializationLoweringContext` using `PreSerializationKlibSymbols`,
`KlibSharedVariablesManager`, and `DotNetIrMangler`. Compile the authoritative Common
`SharedVariableBox.kt` and `SyntheticConstructorMarker.kt` into the stdlib source product. The
generic box is the first-stage ABI; primitive box specialization is an optimization, not a
prerequisite for correctness.

Run the shared first-stage phases before target lowering/serialization. Start the binary-stage
pipeline with the mature-target common prefix before callable-reference, returnable-block, local
declaration, and other .NET-specific transformations when the first stage did not already run that
prefix on the same IR. The inliner may introduce returnable blocks, captures, array constructors,
and accessors, so target lowerings must consume its output rather than precede it. Both stages
deliberately omit `LateinitLowering`: `lateinit` and its required target throw-helper contract
remain a separate parked feature. The shared first-stage builder keeps that lowering enabled by
default for every existing mature target and exposes only this narrow target-capability switch.

Support the CLI's existing `-Xklib-ir-inliner=disabled`, `intra-module`, and `full` modes. Prepared
inlinable IR serves the modern intra/full paths. The disabled/legacy path requires the binary-stage
resolver to read inline bodies from main IR; silently forcing `full`, ignoring the flag, or leaving
an external inline call as an ordinary call is not acceptable.

`-no-stdlib` remains a valid diagnostic and foreign-CLR compilation mode. When the Common
`SharedVariableBox` and `SyntheticConstructorMarker` compiler ABI classes are absent, the CLI does
not construct a pre-serialization KLIB lowering context. This does not define a reduced inline
semantics: ordinary inline support is available in a complete Kotlin stdlib environment, while a
stdlib-free diagnostic compilation retains its pre-existing behavior instead of crashing during
eager symbol lookup.

### 4. Separate ordinary generic inline from reified inline

Remove the blanket rejection only for non-reified generic inline functions once both stages are
active. They retain an ordinary CLR generic method as their non-inlined physical fallback. Keep
reified type parameters rejected until the remaining type-test/cast matrix, `KType`/`typeOf`,
enum/annotation operations, all later classifier families, and the physical fallback contract form
one complete feature. The selected class-literal/`KClass` and array-construction prerequisites do
not open that gate by themselves.

## Design attack and rejected shortcuts

- **Rely on CLR/JIT inlining.** This preserves neither Kotlin control flow nor cross-module body
  availability.
- **Inline only within the current source module.** Common public inline stdlib functions would
  still be unusable from a separate DLL and compiler ABI visibility would remain untested.
- **Store only annotations or source text.** Neither is the mature-target executable inline-body
  contract; KLIB IR already owns it.
- **Force the current default inliner mode.** The compiler already exposes three modes and KLIB
  consumers must interpret each honestly.
- **Reuse `MutableRef<T>` in the first stage.** That would serialize a target-private capture ABI
  where mature KLIB targets serialize the Common box and would make inline bodies target-pipeline
  dependent.
- **Treat CLR reified generics as Kotlin `reified`.** CLR type availability does not perform
  Kotlin call-site substitution or supply the missing reflection/type-token operations.
- **Hide compiler ABI behind ordinary `internal`.** A separately compiled non-friend consumer
  cannot legally link the copied body.

## Required adversarial evidence

The completed slice proves:

- same-module private and public inline calls and a separate self-describing producer DLL;
- top-level and member inline declarations, including a member body that reads producer state;
- `disabled`, `intra-module`, and `full` KLIB inliner modes;
- non-local return, `crossinline`, `noinline`, exactly-once argument evaluation, and side-effect
  order;
- mutable captured locals using the Common shared-variable box;
- private access through a Common-generated friend synthetic accessor and explicit
  `@PublishedApi` access;
- exact Kotlin metadata/IR identity and CLR compiler-ABI markers without accidental C# export;
- `net48` and `net10.0` execution plus `netstandard2.0` production and consumption;
- complete main/prepared component loading, partial-component rejection, and the existing packed
  archive's duplicate, non-canonical, truncated, CRC, and expansion-budget rejection;
- selected dependency KLIBs retain prepared/main inline bodies across separate-library loading,
  while foreign CLR dependencies contribute no invented inline body;
- no injected bootstrap stdlib IR in a user-produced library; and
- byte-identical embedded stdlib KLIBs for repository sources and packaged fallback sources, plus
  successful stdlib-free foreign/diagnostic compilation; and
- continued explicit rejection of reified and suspend-inline declarations outside this slice.

This matrix is green. Reified substitution, coroutine lowering, `lateinit`, reflection, and
adjacent parked features did not enter the slice. The selected Common collection inline families
may now enter the stdlib product through their exact dependency closures.

## Return control transfer from an expression operand

The shared inliner may place a caller-targeted `IrReturn` at any source-valid inline-lambda use,
including the right-hand side of an arithmetic expression or a later call argument. Kotlin
evaluation order remains authoritative: operands before that return are evaluated, operands after
it are not, and the return value leaves the caller immediately.

The mature backends preserve that rule through their own control-flow representation. JVM emits
the materialized return value and a typed JVM return (with its stack/frame machinery); JS emits a
structured `return`; Wasm emits the expected result followed by `return`; and Native evaluates into
the target return slot before generating the function return. None changes the Common inline body
or treats a pending outer operand as part of the return value.

CIL adds one physical constraint: `ret` must see only the method result, and `leave` requires an
empty evaluation stack. Kotlin/.NET therefore normalizes every function return whose expression
context has already pushed operands. It first evaluates and spills the return value, pops only the
older pending operands, reloads the return value, and emits `ret`; a return crossing protected
regions spills the same value, drains the older operands, and emits `leave` to the existing return
join. A void return just drains the older operands. This is emitter-owned control-transfer cleanup,
not an inline-only lowering and not permission to reorder or pre-spill ordinary expression
evaluation.

Rejected alternatives are changing a Common body such as `sum += selector(element)`, evaluating
the returning operand first, or special-casing one stdlib function. Each either changes Kotlin
side-effect order or leaves the same invalid-CIL shape available in another expression. Evidence
must cover value and void returns with one and multiple pending operands, reference and value
results, same- and cross-library inlining, protected-region transfer, skipped later operands, and
both CLR execution profiles where the product slice is portable.

## Selected dependency-graph breadth

The completed ordinary-inline breadth includes calls from a body serialized by Kotlin library A
to declarations owned by a distinct selected Kotlin library B. This is still ordinary non-reified
inlining; it does not admit a general IR linker or any parked inline family.

### Authority and mature-target precedent

An inline body retains the Kotlin declaration identities of every referenced symbol. The compiler's
already-resolved dependency graph, not physical CLR name lookup, is authoritative for binding those
identities.

- JS, Wasm, and Native construct their IR linking/inlining view from the dependency set selected by
  the frontend and KLIB resolver. A nested reference is never satisfied by searching an unrelated
  binary after body deserialization.
- JVM loads inline bodies through the selected Kotlin classpath and resolves their Kotlin metadata
  and bytecode references against that same compilation graph.
- The shared non-linking KLIB deserializer deliberately owns a smaller mechanism: prepared inline
  copies, optional main-IR fallback, same-library supporting declarations, and exact public-symbol
  lookup through the current IR built-ins' symbol finder. It remains generally usable without
  acquiring .NET assembly policy.

The CLR creates no semantic exception. It only means that a surviving B-owned call is later bound
through B's producer-recorded physical declaration index. Logical IR resolution must happen before
that physical mapping and must not reverse the authority direction.

### Existing implementation boundary

The audit corrected an understated implementation rather than adding a new resolver. When main-IR
fallback is enabled, `NonLinkingIrInlineFunctionDeserializer` already maps a requested public ID
signature and symbol kind through `IrBuiltIns.symbolFinder`. Despite its former
`findBuiltInSymbol` name, that finder contains the complete dependency graph selected by the
frontend, not only built-ins. The implementation now calls it `findSelectedDependencySymbol` and
names the corresponding nested-deserializer input `externalSymbolResolver`.

Resolution requires the complete public ID signature; package/name lookup merely supplies a
candidate set. The same `DotNetIrMangler` signature computer used for serialization selects the
exact candidate. The frontend and .NET library loader have already rejected duplicate logical
identities and invalid self-describing assemblies before FIR2IR constructs this symbol finder.

The result is an already-bound logical symbol from the selected graph. The non-linking deserializer
does not read PE metadata, discover another DLL, choose by physical classpath order, or emit a
physical reference. If the copied body contains an inline call into B, the ordinary resolver may
independently request B's inline body from B's own embedded KLIB. A non-inline B call remains an
external logical call for the existing producer-recorded physical ABI binder.

The resolver first reuses a matching selected external symbol. If none exists, the deserializer may
materialize a declaration from the current inline producer's supporting main IR. That fallback is
needed for producer-owned prepared declarations not present in the consumer's ordinary external IR
view. Built-ins are eager candidates in the same exact-signature resolver. No branch links an
entire dependency module.

After the shared inline prefix, the backend traverses the actual module with the compiler's shared
IR-symbol visitor and rejects every remaining unbound symbol before the first .NET lowering. This
is the no-linker equivalent of the mature linking backends' end-of-linkage check. It deliberately
checks calls, types, annotations, overrides, and inline provenance instead of relying on one
symbol-table implementation: non-linking body deserialization can create a reference present in
the IR tree without registering it in a frontend symbol-table slice. Failure is a module diagnostic
which names the unresolved public signatures and removes the requested artifact.

### Design attack

- **Start a general .NET IR linker.** Rejected. This slice needs only identity binding for symbols
  referenced by an inline body; linking all dependency IR changes ownership, reachability, and
  emission semantics.
- **Search every loaded KLIB or DLL during body deserialization.** Rejected. It bypasses the validated
  assembly graph, makes classpath order semantic, and could bind a declaration with no selected
  physical implementation.
- **Match only package and source name.** Rejected. Overloads, accessors, constructors, nested
  declarations, and mangling require the complete public ID signature and symbol kind.
- **Deserialize a B top-level declaration into A's detached symbol table.** Rejected for ordinary
  B calls. It duplicates dependency declarations and can disconnect later CLR binding from the
  frontend's selected symbol. Only B's requested inline body is independently deserialized.
- **Leave the A call as a physical fallback.** Rejected. Kotlin inline semantics and non-local
  control flow cannot depend on whether a nested dependency happened to resolve.

### Adversarial evidence

The completed slice proves:

- A's prepared inline copy and main-IR fallback both bind B-owned declarations;
- an A inline body can call a B inline function whose body in turn calls B-owned compiler ABI and
  ordinary public functions;
- A's inline call disappears while the surviving B calls bind to B's exact assembly and methods;
- the consumer does not acquire a runtime reference to A when no non-inlined A declaration remains;
- `netstandard2.0` A/B producers execute from `net48` and `net10.0` consumers;
- existing same-library, built-in, friend-access, all-mode, reified-rejection, and suspend-rejection
  evidence remains green; and
- omitting explicitly required B rejects both prepared-IR and main-IR A consumers with the
  unresolved B signature, without an internal lowering crash or output artifact.

Existing dependency-selection tests separately reject duplicate, malformed, and wrong physical
identities before the symbol finder is built. This breadth closure does not add transitive
dependency discovery. The consumer must select every Kotlin DLL required by the inline graph
explicitly, just as its frontend must see those declarations to type-check the copied body.
Packaging may copy only assemblies still referenced by emitted CIL.

## Reified-inline prerequisite audit

Reified inline remains parked as a public language feature. The shared inliner is not the blocker:
`FunctionInlining` already substitutes every reified type parameter in copied IR types, class
references, and `typeOf` arguments. Kotlin/.NET currently disables that path in both the
pre-serialization context and the binary resolver so an incomplete target operation model cannot
be mistaken for support.

### Mature-target ownership

- JS, Wasm, and Native perform reified substitution in the shared IR inliner. JS and Wasm then
  remove declarations with reified type parameters because every valid Kotlin call was inlined.
- Native rewrites a surviving physical reified-inline body to an explicit unsupported-call throw.
- JVM retains a physical method containing reification markers; direct execution reaches
  `throwUndefinedForReified`, while the JVM inliner replaces those markers and operations at the
  Kotlin call site.

No mature target treats an ordinary generic method call as Kotlin reification. The logical KLIB
declaration and compiler-readable body remain authoritative even when the target omits or poisons
the physical fallback.

Kotlin/.NET libraries additionally require a producer-recorded physical declaration for each
published logical callable. That makes a hidden throwing CLR stub the likely target-aligned shape,
closer to JVM/Native than silent removal, but it is not selected until the complete reified feature
lands. An ordinary CLR generic method which happens to execute some `T` operations is not a valid
substitute: C# could call it directly, and unsupported body shapes would make the KLIB/physical
index depend on which reified operations happened to occur.

### Complete semantic closure

| Reified use | Existing foundation | Remaining requirement |
| --- | --- | --- |
| call-site type substitution and nested reified calls | shared first-/second-stage IR inliner and selected dependency graph | enable only after every surviving substituted operation is truthful |
| `value is T` / `!is T` | exact references, boxed scalars, arrays, open CLR parameters, classified exceptions/`CharSequence`, and declaration-erased Kotlin generic classes/interfaces | execute the substituted cross-classifier matrix when the public reified gate is enabled; no closed `C<T>` identity is permitted |
| `value as T` / `as? T` | the same classifier families, including identity-preserving generic-class checked/safe casts and typed-use barriers | execute checked, safe, nullable, failed, and later-member-use substitutions through both inliner stages |
| `arrayOfNulls<T>`, `emptyArray<T>`, varargs, and array constructors | typed CLR-vector intrinsics and Common array-constructor lowering | prove nested generic element, nullability, projection, and cross-module substitutions without assuming closed CLR generic identity equals Kotlin runtime identity |
| `T::class` | nominal Kotlin `KClass` identity, classified runtime checks, and a truthful compiler-ABI `System.Type` bridge | execute substituted class literals through both inliner stages when the complete public reified gate is enabled |
| `typeOf<T>()` | shared inliner preserves/substitutes its type argument | select `KType`, type arguments, variance, nullability, and reflection ownership |
| `enumValues<T>`, `enumValueOf<T>`, `enumEntries<T>` | selected builder/abstract-base phase followed by ordinary enums and non-reified `EnumEntries` | implement their reified intrinsic bodies without opening unrelated reified operations |
| annotation-associated and other reflection operations | parameterless annotation declarations, retention, and exact CLR-parent projection | valued annotations, reflection discovery, and target-specific association policy |
| surviving physical reified declaration | real CLR generic-method carrier exists | choose and mark an uncallable/throwing compiler-ABI stub without weakening KLIB/physical coverage |

The central generic-class representation constraint is now resolved by the
[generic-class erased-identity ADR](../decisions/generic-class-erased-identity.md). Kotlin runtime
identity names the declaration through its one producer-recorded non-generic TypeDef. Owner
parameters are erased from storage and virtual members, and results narrow only at their logical
use sites. Checked casts therefore preserve Kotlin erasure and already leave the physical member
receiver required by the verifier. Reified substitution must route through these ordinary
operation paths rather than emit a closed `isinst C<String>` or invent an inliner-only
representation.

Foreign CLR generic types remain a separate importer question. Their closed CLR identity is an
objective platform fact and may justify a foreign-type rule; it must not silently define the
runtime identity of Kotlin-owned generic classes.

### Design attack

- **Set both support flags to true and rely on current CIL.** Rejected. Simple `is String` would
  work, but `is C<String>` would silently use stronger CLR identity and therefore change Kotlin
  results.
- **Enable only the stdlib functions that happen to instantiate `T` with current leaf types.**
  Rejected. A public reified KLIB declaration can be instantiated by a later consumer with every
  legal runtime-available type; producer success must not publish a body whose correctness depends
  on today’s tests.
- **Use the private data-class equality view for all generic tests.** Rejected. That view exists
  only on generated data classes, exposes equality properties rather than a general value carrier,
  and is explicitly not public runtime type identity.
- **Adopt closed CLR generic identity as a useful .NET deviation.** Rejected without a separate
  language decision. CLR makes it easy, but ease is not a technical impossibility of preserving
  Kotlin semantics.
- **Treat `System.Type` as `KClass` or `KType`.** Rejected. It cannot carry Kotlin nullability,
  projections, annotations, or declaration identity by itself.
- **Remove reified methods from a self-describing DLL without updating physical coverage.**
  Rejected. The embedded KLIB and producer-recorded physical index must describe one coherent
  product.

### Reversible work order

The reversible prerequisite order is:

1. complete explicit checked/safe casts for physically exact non-generic reference carriers;
2. complete boxed scalar casts and the exact reference/primitive array cast matrix;
3. adversarially validate existing concrete type tests and array intrinsics independently of
   reified declarations;
4. design the general erased runtime and member view for Kotlin-owned generic classes, including
   logical result recovery after a successful erased cast;
5. use the selected `KClass`/class-literal floor, then select `KType`/`typeOf`;
6. integrate the enum intrinsic family only after ordinary enums and the non-reified
   `EnumEntries` product; and
7. finally enable reified in both inliner stages, select the physical throwing-stub contract, and
   test source/library consumers over all three KLIB modes and both runtime profiles.

Steps 1–4 are implemented as ordinary language/runtime-operation features. They do not accept a
closed `DotNetIlValueType.GenericInstance` merely because CLR has a convenient instruction. The
public Common reified stdlib families remain excluded until the remaining array/reflection/enum
operations and the physical fallback contract form one truthful closure.

### Selected first prerequisite: physically exact reference casts

The first reversible slice is ordinary `as`/`as?` for a target whose Kotlin classifier has one
exact CLR reference carrier:

- `Any`/`Any?` (`System.Object`) and `String`/`String?`;
- non-generic Kotlin classes/interfaces and interfaces admitted by the current foreign CLR
  importer, represented by one `UserClass` token;
- Kotlin primitive-array wrapper classes; and
- invariant generic-array vectors whose complete element token is already representable.

FIR/Common remain authoritative for cast type, result nullability, and evaluation order. The CIL
operation is only the physical realization:

- `as?` widens/boxes the operand once and uses `isinst`, returning the original reference or null;
- `as T?` uses `castclass` and admits null;
- `as T` uses `castclass`, then applies the existing Kotlin not-null barrier because CLR
  `castclass` itself accepts null; and
- a wrong non-null cast throws CLR `InvalidCastException`, the target's exact physical carrier for
  Kotlin `ClassCastException`.

The slice reuses the existing classified `CharSequence` and erased generic-interface branches; it
does not replace them. It excluded mapped Kotlin exception classifiers because broad Kotlin
exception relationships require the versioned classifier, boxed scalar casts because their stack
and nullable-result shapes are different, open type parameters beyond the existing checked-cast
case, and every `GenericInstance`. The later exception and erased generic-class slices preserve
their classifier relationships without admitting a closed CLR generic construction as identity.

Adversarial evidence for this slice covers success, safe failure, checked failure caught as
`ClassCastException`, nullable/non-null null behavior, single operand evaluation, base/interface
and imported CLR carriers and primitive/generic arrays. Kotlin-owned generic-class casts were
added only by their later one-owner prerequisite. Both Framework CLR and CoreCLR execute the same
source.

This prerequisite is implemented. `exactReferenceCasts.kt` executes the Kotlin carrier matrix in
both FIR frontends and runtime profiles; the foreign-call integration test executes checked and
safe casts to an imported CLR interface under net48 and net10; and
The later generic-class slice has since replaced the original negative sentinel with its dedicated
erased-cast matrix. This prerequisite did not itself open either reified support gate.

### Selected second prerequisite: boxed scalar casts

Common cast semantics distinguish primitive identities; they do not perform numeric conversion.
The shared `asForConstants.kt`, `asSafeForConstants.kt`, and `boxing6.kt` tests require a boxed
`Int` to cast only to `Int`, for example, while `Byte`, `Short`, `Long`, `Float`, `Double`, and
`Char` remain different runtime types. Explicit conversion functions, not `as`/`as?`, move between
those identities. `Boolean` is distinct from every numeric type as well.

Mature targets preserve that rule through their runtime representation:

- JVM materializes both sides in boxed form for an explicit cast. Its safe-cast lowering performs
  the corresponding runtime type test once and returns the boxed value or null before ordinary
  unboxing at a typed use site.
- JS performs its Kotlin runtime predicate once and returns the unchanged value or null/throws; it
  does not reinterpret a successful scalar cast as numeric conversion.
- Wasm performs a Kotlin type check, then narrows the cached value or returns null/throws.
- Native erases non-reified type parameters for the check and lowers `as?` to one cached `is` test
  plus the successful implicit cast; scalar boxing/unboxing remains target representation work.

The CLR has exact value types and exact boxes for all eight selected Common scalars, so no new
logical identity is needed. The target-specific requirement is only to bridge the object boundary
to its hybrid nullable carrier:

| Kotlin operation | CIL realization |
| --- | --- |
| `value as T` | evaluate as `object` once, then `unbox.any System.<boxed T>` |
| `value as T?` | evaluate as `object` once, then `unbox.any Nullable<T>` |
| `value as? T` or `value as? T?` | evaluate as `object` once, `isinst System.<boxed T>`, then `unbox.any Nullable<T>` |

CLR nullable boxing makes the last two rows exact: unboxing a boxed `T` as `Nullable<T>` produces
a present value, unboxing null produces an empty value, and `isinst` changes an unrelated object
to null before the nullable unbox. The two-instruction safe shape assembled and executed with
present, wrong-type, and null inputs on CLR 4 and CoreCLR. A checked wrong type remains CLR
`InvalidCastException`, the physical Kotlin `ClassCastException`; a checked null to non-null `T`
remains CLR `NullReferenceException`, the physical Kotlin `NullPointerException`.

If the cast result is consumed as `T?` or `Any?`, the normal widening layer then constructs
`Nullable<T>` or boxes it. This is part of the cast closure: restricting explicit casts to a local
whose physical type happens to equal the cast target would be an emitter limitation, not Kotlin
semantics.

#### Design attack

- **Use CLR numeric conversion instructions after unboxing.** Rejected. That would turn Kotlin
  casts into conversions and make `1 as Long` succeed contrary to Common.
- **Implement safe casts by catching `InvalidCastException`.** Rejected. It obscures ordinary
  control flow, does not naturally distinguish a null success for a nullable target, and diverges
  from every mature target's test-then-result shape.
- **Return a raw scalar from `as?`.** Rejected. Failure requires a representable null; the selected
  physical result is the existing `Nullable<T>` carrier.
- **Use `isinst Nullable<T>` as a new identity.** Rejected. Nullable values box as the underlying
  `T` or null. The runtime test must target the exact underlying box, matching the already-selected
  `is T` implementation.
- **Admit `UInt` and other value classes because they also have scalar storage.** Rejected. Value
  classes are a parked language programme whose Kotlin identity cannot be inferred from storage.

The bounded implementation includes `Boolean`, `Byte`, `Short`, `Int`, `Long`, `Float`, `Double`,
and `Char`, both nullable spellings, outer nullable/object widening, exact-type success, distinct-
scalar rejection, null behavior, exception mapping, and single evaluation. It changes neither
numeric conversion nor value-class identity and does not enable reified inline support.

This prerequisite is implemented. `scalarCasts.kt` runs the complete bounded matrix in both FIR
frontends and runtime profiles. The emitter deliberately obtains an `as?` result carrier from the
IR expression type: FIR keeps the requested type in `typeOperand` (`Int`) and makes the expression
result nullable (`Int?`). Treating the operand as the physical result would reject every ordinary
safe scalar cast. Reified inline remains disabled; the next reversible work is the existing
concrete type-test and array-intrinsic matrix.

### Selected third prerequisite: ordinary runtime type tests

Common source and FIR remain authoritative for the legality of `is`/`!is`, the tested Kotlin
classifier, smart-cast facts, and nullable-target semantics. A target realizes those facts without
making its physical generic representation part of Kotlin runtime identity:

- JVM lowers a nullable target to one cached null-or-non-null check and emits `instanceof` against
  the non-null erased JVM classifier;
- Native explicitly erases type parameters before casts and leaves runtime tests on Kotlin type
  information rather than closed generic arguments;
- JS caches an effectful operand once and selects Kotlin predicates for primitives, arrays,
  functions, and interfaces instead of treating JavaScript's native `instanceof` as universal;
  and
- Wasm likewise caches once, handles nullable targets separately, and selects Kotlin predicates or
  subclass/interface checks according to the logical classifier.

The .NET realization follows the same rule. It widens or boxes the operand to `object` once,
accepts null exactly when the target is nullable, and checks a non-null value through either the
existing Kotlin classifier (`CharSequence` and mapped exceptions) or one physically exact carrier.
The exact ordinary set is `Any`, `String`, non-generic Kotlin/foreign classes and interfaces, the
eight exact boxed Common scalars, supported primitive-array wrappers, and a CLR vector whose full
element token is already known. Generic Kotlin interfaces use their one declaration-erased
identity.

Two convenient CLR checks were deliberately outside this slice:

- a Kotlin-owned `C<T>` could not use the prototype's closed CLR construction as identity; the
  later generic-class prerequisite replaced that prototype with one non-generic owner; and
- `Array<*>` has no single truthful CLR vector token. `object[]` excludes value vectors such as
  `Array<Int>`, while `System.Array` is broader than Kotlin's one-dimensional generic array and
  does not by itself provide the typed element operations promised after a successful smart cast.

Both were rejected here until their later erased identity and successful-use carriers were
designed.
Concrete `Array<E>` checks can become observable after reified substitution, but ordinary source
cannot name them in a legal runtime check; their nested/nullability/projection matrix therefore
remains in the later array/reified prerequisite.

#### Design attack

- **Let every mapped type reach CLR `isinst`.** Rejected. It accidentally turns the backend's
  closed generic storage choice into stricter Kotlin runtime semantics.
- **Map `C<*>` to `C<object>`.** Rejected. CLR generic classes are invariant, and value/reference
  instantiations remain physically different even though Kotlin's runtime check erases arguments.
- **Map `Array<*>` to `object[]`.** Rejected. It makes `Array<Int>` fail the same Common check that
  succeeds on mature targets.
- **Map `Array<*>` directly to `System.Array`.** Parked, not selected. Admission alone is
  insufficient: the smart-cast result must still support Kotlin `size`, indexed reads, iteration,
  and subsequent casts without copying or losing identity.
- **Rely on existing incidental tests.** Rejected. Scalar families, nullable positive/negative
  forms, distinct primitive-array wrappers, inheritance/interface checks, null, and effectful
  operands need one adversarial matrix so later reified work cannot regress them independently.

The bounded implementation makes accepted carrier kinds explicit in the emitter and retains the
classified exception/`CharSequence` and erased-interface paths. Its executable matrix covers all
eight scalar boxes, ordinary class and
interface inheritance, `Any`/`String`, every currently selected primitive-array wrapper, nullable
and negative tests, distinct-carrier failures, smart-cast use, and single evaluation on both CLR
profiles and both FIR frontends. It does not enable either reified-inline gate.

This prerequisite is implemented. `runtimeTypeTests.kt` owns the ordinary exact-carrier matrix,
and the foreign-call integration fixture executes imported-interface tests on net48 and net10.
The emitter lists admitted physical carrier kinds explicitly and rejects any closed
`GenericInstance` as Kotlin-owned class identity. The next reversible prerequisite is the concrete array-
intrinsic matrix, starting with the remaining signed `ByteArray`, `ShortArray`, and `FloatArray`
wrappers now that their scalar carriers exist; this does not select an `Array<*>` carrier.

### Selected fourth prerequisite: complete signed primitive arrays

The [primitive-array ADR](../decisions/primitive-arrays.md) selects one Kotlin-owned wrapper model
for the complete eight-family Common surface. `ByteArray`, `ShortArray`, and `FloatArray` were
previously evicted only because their scalar carriers were incomplete; that reason no longer
exists. This slice adds those three to the single wrapper/runtime registry and closes every
already-selected consumer: constructors and initializer loops, literals and varargs, get/set and
size, direct and escaping iteration, copies/content operations, exact type tests/casts, portable
Kotlin libraries, and explicit aliasing C# export adapters on both runtime profiles.

The slice does not generalize generic arrays. In particular, it neither admits nullable primitive
elements in `Array<T?>` nor selects a star-projected `Array<*>` carrier. It also does not infer the
unsigned value-class array families from signed storage: `UByteArray`, `UShortArray`, `UIntArray`,
and `ULongArray` remain part of the parked value-class programme.

This prerequisite is implemented. The complete wrapper registry now drives all eight signed
families; `remainingPrimitiveArrays.kt` executes their missing constructor, initializer, literal,
vararg, iterator, copy/content, RTTI/cast, generic-separation, and failure matrix in both FIR
frontends and runtime profiles. The portable-library integration fixture additionally proves exact
Kotlin-to-Kotlin identity plus copy-free `sbyte[]`, `short[]`, and `float[]` C# adapters on net48
and net10. The next reversible prerequisite is an audit of concrete generic-array constructors and
intrinsics for already-exact element tokens. It must not select `Array<*>` or a general erased
Kotlin generic-class view by accident.

### Selected fifth prerequisite: concrete nullable-primitive generic arrays

The audit found one closed element family that the current representation already models exactly
but the generic-array mapper still rejects: `Array<Boolean?>` through `Array<Char?>`. Common makes
these ordinary invariant generic arrays. The accepted hybrid-nullability decision already maps each
concrete nullable scalar to closed CLR `Nullable<V>`, and ECMA-335 vectors compose that value token
without erasure as `Nullable<V>[]`. This is also the natural C# `V?[]` surface.

The bounded slice must cover all eight families across `arrayOf`, `arrayOfNulls`, `emptyArray`,
initializer constructors, concrete nullable varargs/spreads, get/set/size, direct and escaping
iteration, copy/content operations, exact casts, nesting, generic functions/classes, portable KLIB
consumption, and C# signatures/aliasing on both profiles. It must preserve the existing rejection of
nested open `Array<T?>`, input projections, and value-vector widening to `Array<out Any?>`.
Star projection remains a separate erased-view prerequisite; closed `Nullable<V>[]` does not
answer any of those shapes and must not be used as a pretext to weaken their gates.

Implemented evidence now covers that complete matrix on both FIR frontends and runtime profiles.
The portable-library test additionally executes all eight exact signatures from separate Kotlin
consumers, including generic substitution, and from one Roslyn consumer on Framework CLR and
CoreCLR. Open nullable elements and the parked projection/covariance shapes remain rejected by
backend-reachable negative sentinels rather than frontend-invalid stand-ins.

### Selected sixth prerequisite: classified star-projected arrays

The cross-target audit found a shared semantic rule and one CLR-specific carrier split. JVM, JS,
Wasm, and Native all erase the element argument of `Array<*>` while preserving generic-array
identity, excluding specialized primitive arrays, permitting `size` plus `Any?` reads/iteration,
and retaining the original object for later exact casts. JVM's `object[]` works only because its
generic value elements are boxed. The .NET target's exact `int32[]` and `Nullable<Int32>[]` vectors
therefore require CLR's common `System.Array` base as their identity-preserving erased storage view.

`System.Array` alone is too broad for Kotlin RTTI because it includes rectangular and non-zero-based
arrays. The selected design pairs that physical view with one runtime SZ-array classifier, and uses
`Length`/`GetValue` plus erased iterator/iterable adapters for successful star use. Kotlin-owned
primitive-array wrappers remain outside the classifier. No path copies, wraps, tags, or changes an
exact vector; KLIB keeps the star projection authoritative. The full decision and adversarial gate
are in the [star-projected-array ADR](../decisions/star-projected-arrays.md).

This prerequisite remains bounded to `Array<*>`. It does not infer support for input projections,
open `Array<T?>`, value-vector covariance to `Array<out Any?>`, or declaration-erased identity for
ordinary Kotlin generic classes.

This prerequisite is implemented. `starProjectedArrays.kt` executes reference, value,
nullable-value, nested, and empty vectors across both FIR frontends and runtime profiles, including
identity, mutation aliasing, erased reads/iteration, exact follow-up casts, nullable RTTI, and
single evaluation. The portable-library test additionally proves `System.Array` signatures and
copy-free Kotlin/Roslyn consumption on Framework CLR and CoreCLR, accepts foreign SZ vectors, and
rejects rectangular and non-zero-based CLR arrays through the shared classifier. The existing
backend-reachable sentinels keep input projections, open nullable elements, and value-vector
covariance outside the feature. The next reversible prerequisite is declaration-erased runtime
identity for ordinary Kotlin generic classes, not either reified support gate.

### Selected seventh prerequisite: erased generic-class identity

Common and every mature target give all constructions of one ordinary generic class one runtime
classifier. The CLR instead reifies invariant `C<T>` constructions. A closed CLR cast is too
strict, while a reflection predicate alone cannot support member use after Kotlin accepts an
unchecked cast between different logical arguments.

The selected model gives each Kotlin generic class one non-generic physical class. Its storage,
constructors, base edge, and virtual slots use erased carriers; ordinary JVM-direction bridges
cover substituted narrow overrides. Runtime tests and casts use that class or its ordinary erased
base ancestry directly. There is no canonical class interface, closed `C<T>` capability, ancestry
helper, wrapper, or duplicate storage.

KLIB keeps arguments, variance, projections, bounds, and member types authoritative. Imported CLR
generics remain reified, while typed C# export is a separate fail-closed product. The complete
representation, rejected alternatives, schema consequences, and adversarial gate are in the
[generic-class erased-identity ADR](../decisions/generic-class-erased-identity.md).

The bounded implementation covers ordinary generic classes already admitted by the class model.
It does not infer support for value classes, foreign CLR generic classes as Kotlin-owned
declarations, currently rejected open-nullable nested constructions, reflection, or either public
reified-inline gate.

This prerequisite is being migrated atomically. The local and portable matrices cover reference,
scalar, nullable-scalar, nested, inherited, inner, data, generic-member, projected, cast, identity,
single-evaluation, wrong-argument, default-dispatch, interface composition, erased mutation, and
erased-overload shapes. The portable producer additionally proves one owner in metadata, raw
object-based C# subclassing, absence of an implicit `C<T>` surface, deterministic CIL, and
continued reification of imported CLR generics. The current feature gate, not the superseded ABI
17 evidence, decides when this prerequisite is complete.

The next reversible work is to re-audit the remaining reified array-operation substitution matrix
against the now-complete array and generic-class carriers. That audit must distinguish an operation
which is already truthful after ordinary IR substitution from the still-unselected `KType`, enum,
annotation, and physical throwing-stub contracts; it must not enable either public reified gate
piecemeal. The separately selected `KClass` floor is an ordinary language/runtime feature, not
permission to expose a partial reified surface.

### Selected eighth prerequisite: post-substitution array operations

The [reified-array decision](../decisions/reified-array-operations.md) completes that audit. Common
and the shared inliner remain authoritative: once a valid call site has replaced `T`, array
allocation must use the same ordinary `Array<E>` mapping as non-inline Kotlin. The current
`arrayOf`, generic-vararg, `emptyArray`, `arrayOfNulls`, and Common `Array(size) { ... }` paths are
representation-ready for every classifier already admitted by the target. Nested exact vectors,
nullable scalar vectors, specialized-array wrappers, `Array<*>` element views, erased generic
interfaces, and declaration-erased generic classes need no reified-only carrier.

This finding narrows the blocker; it does not enable support. Actual reified call sites still need
the final substituted type-test/cast matrix, and a public declaration may use `T::class`,
`typeOf<T>()`, enum or annotation operations, or later instantiate with a classifier whose target
representation is parked. A published logical reified declaration also needs the still-unselected
physical throwing-stub contract. Both .NET support gates therefore remain false.

`arraySubstitutionCarriers.kt` adversarially executes the concrete post-substitution shapes across
both FIR frontends and runtime profiles. Existing portable-library matrices provide cross-module
evidence for the underlying carriers. When the complete feature is enabled, an actual reified
producer/consumer test across all KLIB inliner modes remains mandatory; the concrete matrix is a
prerequisite, not a substitute for that final gate.

### Selected ninth prerequisite: nominal `KClass` and class literals

The [KClass decision](../decisions/kclass-and-class-literals.md) selects the Common
`KClassifier`/`KClass` floor as an ordinary language/runtime feature. Static and dynamic class
literals now produce a Kotlin-owned value over exact or classified CLR evidence; `System.Type`
remains a compiler-ABI bridge rather than Kotlin identity. Generic-class arguments remain erased,
classified arrays/`CharSequence`/exceptions share the existing type-operator semantics, dynamic
receivers are evaluated once, and local/anonymous source names use a narrow naming attribute.

This closes the representation prerequisite for substituted `T::class`, but it still does not
enable a public reified declaration. The remaining closure includes `KType`/`typeOf`, enum and
annotation operations, the final substituted type-test/cast matrix, every later-admitted
classifier, and the physical throwing-stub contract. An actual reified producer/consumer matrix
through both inliner stages remains mandatory when that complete boundary is selected.
