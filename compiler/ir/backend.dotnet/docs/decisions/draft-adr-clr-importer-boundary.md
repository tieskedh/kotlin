# Draft ADR: Structured CLR importer boundary

- Status: **Draft — implemented candidate under pre-ABI evaluation**
- Scope: foreign CLR dependencies selected for Kotlin/.NET compilation

## Context

Kotlin/.NET consumes two fundamentally different kinds of DLL:

- a Kotlin-produced DLL, whose embedded KLIB is the authoritative Kotlin declaration model; and
- a foreign CLR DLL, whose ECMA-335 metadata is the authoritative physical declaration model.

The foreign importer must turn the second kind into Kotlin-facing declarations without loading
target code, guessing from C# spelling, or making CLR conventions redefine Kotlin Common. It must
also retain enough physical identity for FIR2IR and code generation to call the exact selected
member.

This boundary is deliberately pre-ABI. No published Kotlin/.NET foreign-declaration contract is
being preserved.

## Mature-target precedent

Kotlin/JVM separates class-file parsing, the Java declaration model, signature enhancement, lazy
FIR symbols, and backend linkage. Java annotations may enhance a foreign Kotlin view, while
Kotlin-produced class files retain Kotlin metadata as their semantic authority.

JS, Wasm, and Native likewise keep foreign declarations separate from KLIB-backed Kotlin
declarations. Their importers preserve target identity first and apply Kotlin-facing policy later.

The reusable precedent is the layering and authority split. The CLR is not forced into Java's
physical model: return types participate in CLR slot identity; properties, generic constraints,
custom modifiers, arrays, delegates, by-reference shapes, and assembly scopes differ materially.

## CLR-specific constraints

- The compiler runs on the JVM and must not use target-runtime reflection as its declaration
  database.
- Metadata is untrusted binary input and requires bounded decoding before semantic projection.
- A metadata token has meaning only in its owning module or assembly context.
- The build frontend, not the importer, selects the dependency and target-framework graph.
- CLR signatures contain physical forms that have no truthful ordinary Kotlin type.
- C# source spelling and Roslyn conventions are useful foreign-language evidence, not CLR
  declaration identity.
- Advisory attributes may be malformed even when the declaration's structural metadata is usable.

## Decision

### 1. One layered importer

```text
build-selected assembly graph
             |
bounded PE / ECMA-335 reader
             |
immutable physical CLR model
             |
resolution, classification, and import policy
             |
lazy target-owned FIR symbol provider
             |
IR with retained physical linkage
```

Each layer owns one kind of truth:

1. The build frontend supplies canonical files, target-framework context, and exact AssemblyRef
   bindings. It may implement profile-specific unification or retargeting.
2. The reader validates PE ranges, metadata streams and tables, coded handles, heaps, and blobs.
   It never loads the target assembly or starts a .NET process.
3. The physical model preserves CLR rows, flags, names, scopes, multiplicity, and structural
   signatures without Kotlin or C# interpretation.
4. Resolution and policy prove selected identities and decide which physical shapes have a
   truthful Kotlin-facing view.
5. The lazy FIR provider exposes only complete admitted declarations.
6. FIR2IR retains the selected assembly, owner, member, and signature; the backend never
   rediscovers a member from a display name.

No layer may compensate for missing evidence by probing directories, calling host `Type.GetType`,
choosing an assembly by simple name, or parsing textual IL.

### 2. DLL classification precedes declaration import

Classpath DLLs are classified by the reserved private `Kotlin.Metadata` resource:

- a valid resource selects the KLIB-backed Kotlin path;
- a resource-free assembly remains available to the foreign CLR importer; and
- a present but non-private, malformed, or inconsistent resource is an error.

There is no fallback from failed Kotlin metadata to foreign import. Kotlin-produced declarations
are not exposed a second time from their physical CLR rows, and foreign attributes never override
their KLIB nullability, contracts, visibility, or logical declaration identity.

### 3. Physical metadata is lossless and interpretation-free

The physical model preserves at least the metadata needed by every admitted declaration:

- assembly identity, full public key or token, AssemblyRef, TypeRef, TypeDef, ExportedType, and
  nested-owner edges;
- MethodDef, MemberRef, Field, Property, PropertyMap, MethodSemantics, Param, InterfaceImpl,
  GenericParam, and GenericParamConstraint rows;
- CustomAttribute, Constant, and FieldMarshal rows with their exact parents and multiplicity; and
- original signature/value blobs where diagnostics or unsupported-form rejection need them.

Rows are not grouped, deduplicated, or attached by naming convention. Property accessors come
only from MethodSemantics. Method and field ownership comes from table partitions. A missing Param
row remains distinguishable from a present row, and return Param sequence `0` remains distinct
from value parameters.

Structural ECMA-335 violations fail as located bad images. Optional CLS naming or source-language
conventions are not reader errors; later policy may reject them from a Kotlin projection.

Constant rows retain their exact HasConstant parent and decoded scalar. A CLR constant is not
thereby a Kotlin default argument. FieldMarshal descriptors remain bounded opaque bytes until a
selected-profile policy understands the complete platform grammar; they never become Kotlin
types, modifiers, annotations, defaults, or storage rules by inference.

### 4. One structural signature algebra

Method, member-reference, field, property, and TypeSpec signatures share one lossless physical
type algebra. It retains:

- primitive encodings and `class` versus `valuetype` evidence;
- TypeDef, TypeRef, and TypeSpec handles;
- type- and method-parameter positions in their declaring scopes;
- constructed generic types and exact argument order;
- managed pointers, unmanaged pointers, by-reference and typed-reference forms;
- vectors versus general arrays, including rank, sizes, and signed lower bounds;
- required and optional custom modifiers at their physical positions; and
- function-pointer calling convention, receiver flags, generic arity, and vararg boundary.

The decoder follows ECMA-335 together with the official .NET augmentations used by current
reference assemblies. It enforces grammatical context, canonical compressed integers, bounded
recursion, and complete blob consumption. Raw blob equality is not Kotlin type equality.

Assembly-context-bearing resolution produces immutable structural views. Owner substitution is
explicit, cycle-safe, arity-checked, and scope-qualified. A type parameter is identified by its
actual TypeDef or MethodDef owner plus index, never by `(TYPE|METHOD, index)` alone.

### 5. The selected graph owns nominal identity

TypeRef scopes, nested types, TypeSpecs, ExportedTypes, and forwarding chains resolve only through
the frontend-selected graph. Missing bindings, ambiguity, cycles, unsupported multi-module edges,
and non-nominal shapes remain distinct results.

Strong-name evidence participates in identity. The importer does not silently substitute a
same-named core type or reference assembly. Profile facades and forwarding are graph inputs, not
namespace heuristics.

Serialized `System.Type` and enum names use a bounded structural parser for CLR reflection type
syntax and AssemblyName syntax. Parsing never binds. A frontend-owned binder receives the
attribute-owning assembly, the unqualified-name context, and any parsed qualifier; the type
resolver then verifies the returned selected identity and generic arity.

### 6. Physical classification is separate from Kotlin types

Resolved signatures are classified against exact selected core identities as:

- reference type;
- non-nullable value type;
- `System.Nullable<T>` physical value; and
- independently, by-ref-like or ordinary.

Compact primitive signatures resolve through one selected-core primitive catalog. Enum identity
requires an exact base edge to the selected `System.Enum` and one valid runtime-special `value__`
storage field. Delegate identity requires the selected sealed direct base edge to
`System.MulticastDelegate`. By-ref-like identity requires the exact decoded selected-profile
marker and valid multiplicity.

These are CLR facts. They do not create Kotlin primitive, enum, value-class, function, nullable,
or ref-like semantics without a separate import mapping.

### 7. Assignability and generic constraints preserve evidence

The shared foreign-signature assignability layer is bounded and returns structured outcomes, not
a Boolean guess. It supports:

- exact nominal base and interface reachability with owner substitution;
- reference-only variance for selected CLR interfaces and delegates;
- rank- and element-aware CLR array compatibility;
- the selected `System.Array` base hierarchy plus `IList<T>`, `ICollection<T>`, `IEnumerable<T>`,
  `IReadOnlyList<T>`, and `IReadOnlyCollection<T>` for vectors; and
- scope-qualified implications between open type and method parameters.

Value arguments remain invariant in CLR variance; boxing is not variance. CLR array compatibility
includes the CLI's reference covariance and reduced enum/integer storage relation. It stays at the
foreign physical boundary and does not change Kotlin `Array` invariance or primitive-array
identity.

Constraint validation keeps every direct nominal/TypeSpec row and every special flag. It reports a
sealed aggregate status with retained per-rule evidence: invalid, unsupported, violated, or
satisfied. Reference, value, default-constructor, `AllowByRefLike`, and dependent-parameter rules
are evaluated separately. CLR `class`, `struct`, and `.ctor` constraints are not reinterpreted as
Kotlin bounds or constructor syntax.

### 8. Custom attributes are decoded semantically

A CustomAttribute retains its exact parent, constructor handle, raw bytes, row order, and
multiplicity. The selected graph must prove the constructor, attribute ancestry, generic owner,
and substituted signature before values are interpreted.

The semantic value model covers:

- exact primitive values, including floating-point payload bits;
- nullable strings;
- bounded one-dimensional arrays and tagged `object` values;
- enums with complete resolved constructed identity and exact storage bits;
- nullable structural `System.Type` values; and
- ordered named field/property records with kind, name, declared type, and value.

Named arguments are validated against selected public instance members, but their encoded
kind/name/type/value remains authoritative. Resolution does not replace that evidence with a
FieldDef or Property token that the blob never contained. Legal duplicate rows and argument order
are retained. Semantic equality uses decoded constructor/named values and attribute identity, not
raw bytes.

Validation follows the documented ordinary CLR attribute contract. Runtime quirks that happen to
accept malformed attribute metadata are not imported as language semantics.

Generic attributes are physically retained profile-neutrally and projected only when the selected
profile proves a complete, closed, legal attribute type and constructor.

### 9. Nullable-reference evidence enhances only foreign views

The importer recognizes Roslyn's top-level
`System.Runtime.CompilerServices.NullableAttribute`, `NullableContextAttribute`, and
`NullablePublicOnlyAttribute` by exact namespace/name and constructor/value shape. A fixed defining
assembly is not required because compilers may embed these types. Look-alikes with a wrong shape,
ancestry, target, multiplicity, or payload do not become evidence.

Local flags, containing context, module public-only policy, declaration accessibility, and the
resolved signature tree are selected before Kotlin qualification. Duplicate or contradictory
compiler evidence is invalid rather than first-wins.

`NullablePublicOnlyAttribute` follows Roslyn's physical-parent convention. Property and Event rows
have no CLR accessibility, so their inclusion is derived from the containing type; their accessor
MethodDefs are filtered independently by their own visibility. Effective visibility always folds
through the complete containing-type chain.

Roslyn preorder is aligned against the original resolved CLR signature. Reference nodes consume a
flag; non-generic value types, `Nullable<T>`, by-reference wrappers, and modifiers do not. Generic
value-type padding is validated as physical evidence but does not become Kotlin nullability.
GenericParam declaration evidence remains separate from each constraint transform, which is
aligned before substitution. A parameter marker alone does not create a Kotlin definitely-non-null
type before Common enhancement has considered its bounds.

Valid flags project through Kotlin's established foreign qualifier vocabulary:

- `0` -> forced flexible/oblivious;
- `1` -> not-null; and
- `2` -> nullable.

Absent or accessibility-suppressed evidence retains the unchanged foreign physical type and
therefore a flexible Kotlin view. Malformed evidence is retained for diagnostics and falls back to
flexibility; an advisory attribute must neither invent non-nullness nor erase an otherwise usable
CLR declaration.

### 10. Standard CodeAnalysis contracts map only when exact

A well-formed standard attribute on a foreign declaration is an authored compile-time contract,
not proof of the body. It may enhance Kotlin's logical call view when its meaning fits Common
types, contracts, or control-flow exactly.

| CLR evidence | Kotlin-facing foreign view |
| --- | --- |
| parameter `NotNull` | `returns() implies (parameter != null)` without changing the declaration input type |
| parameter `NotNullWhen(v)` | `returns(v) implies (parameter != null)` |
| return `NotNullIfNotNull(name)` | matching parameter implies non-null result; bind the exact Param name |
| parameter `DoesNotReturnIf(v)` | normal return implies the Boolean parameter had value `!v` |
| method `DoesNotReturn` | logical `Nothing` result while retaining the original physical return signature |
| return `NotNull` / `MaybeNull` | non-null/nullable call-result view after ordinary declaration nullability |
| by-value `DisallowNull` / `AllowNull` | non-null/nullable input view after ordinary declaration nullability |

Multiple `NotNullIfNotNull` rows bind independently; duplicates normalize, and one recognized but
invalid item prevents partial strengthening. `DoesNotReturn` takes logical precedence over return
null-state evidence.

A `DoesNotReturn` invocation still uses the retained MethodDef return signature. If a dishonest
foreign body completes, code generation follows Common `Nothing` behavior instead of treating the
returned physical value as an ordinary Kotlin result.

For exact conflicting return evidence, `NotNull` precedes `MaybeNull` as Roslyn's call-result state
does. For exact conflicting input evidence, `DisallowNull` precedes `AllowNull`. Invalid
strengthening evidence is ignored; invalid weakening evidence forces flexibility so malformed
metadata cannot preserve an unjustified non-null restriction.

Properties and `ref`/`out` parameters are excluded from the one-type input/output mapping. They
need distinct read/write or pre/post views. `MaybeNullWhen` needs explicit caller-state
invalidation. `MemberNotNull` and `MemberNotNullWhen` are retained but cannot bypass Common
smart-cast stability or name arbitrary members through Kotlin's parameter-only contract model.

Kotlin-produced declarations keep their complete contracts in KLIB. Any emitted CodeAnalysis
attribute is a derived foreign view; stripping it does not change Kotlin semantics.

### 11. Other exact foreign projections remain target-bounded

`System.ObsoleteAttribute` maps to Common `kotlin.Deprecated` only after selected-core identity,
constructor, target, multiplicity, and payload validation. Message and warning/error severity are
preserved; error does not mean `HIDDEN`. Type, property, getter, setter, and method channels remain
distinct, and foreign deprecation does not propagate to Kotlin overrides.

One exact final Param carrying selected-core `System.ParamArrayAttribute()` maps to Common
`vararg` only for the admitted `string[]` and `object[]` vector shapes. FIR retains independent
vector/element nullability, while physical linkage retains the original vector signature.
Primitive parameter arrays and `ParamCollectionAttribute` require separate representations.

Extension, optional/default, indexer, required/init/read-only, event, marshalling, and broader
attribute projections land only after their complete physical and Common semantics are specified.
No marker name alone creates a Kotlin declaration role.

### 12. FIR exposure is complete within an admitted grammar

The first admitted classifier family is a public, top-level, non-generic CLR interface with a
closed method/property grammar over `void`, supported primitive scalars, `string`, `object`, and
the explicitly admitted reference-vector vararg forms.

A classifier is withheld when a public declared member, signature, property association,
attribute-dependent call view, or selected identity lies outside that grammar. It is never exposed
with silently missing members. Duplicate classifier identities across selected assemblies are
withheld rather than resolved by classpath order.

A Property row becomes one Kotlin `val` or `var` only when its signature and exact public abstract
MethodSemantics accessors agree. Indexers and split input/output states are not flattened. Exact
physical method/accessor linkage survives FIR2IR and code generation.

Provider growth must proceed by complete declaration families with adversarial metadata, separate
producer/consumer compilation, and physical binding tests. A successful FIR call may reach the
backend only when its retained signature grammar is also implemented there.

## Kotlin Common invariant

- Kotlin-owned declarations retain KLIB identity, nullability, contracts, and declaration shape.
- Unannotated foreign references are flexible, not guessed non-null.
- CLR variance and overload rules shape foreign declarations but do not redefine Kotlin variance,
  equality, override, exception, or stability rules.
- CLR values, pointers, byrefs, arrays, delegates, and ref-like types require explicit mappings.
- Profile selection may change API availability and physical legality, never the meaning of the
  same Common declaration.

## Profile rule

The physical reader is profile-neutral. The selected graph supplies the exact `net48`,
`netstandard2.0`, or `net10.0` reference identities and legality policy. A portable compilation
sees only its portable contract; facade and forwarding resolution never substitutes a type from a
different profile by name.

## Rejected alternatives

### Reuse the Java physical model

This loses CLR properties, return-type slot identity, constraints, modifiers, nested metadata
identity, and CLR-specific signatures. Shared frontend architecture does not imply one foreign
binary model.

### Invoke Roslyn or target reflection in production

This would load or execute target-side infrastructure, add a private sidecar protocol, and make
the compiler depend on a target runtime. Roslyn and reflection remain independent test oracles.

### Import by C# names or textual IL

Display names are not metadata identities. This loses overloads, owner scope, exact signatures,
and MethodImpl linkage.

### Merge KLIB and foreign attributes for Kotlin-produced declarations

That would make Kotlin semantics depend on attribute preservation, force KLIB tools to parse CLR
metadata, and create ambiguous ownership across bridges and split declarations. Derived foreign
views are validated against KLIB instead.

### Approximate an unsupported shape

Erasing a pointer, byref, array, constraint, property state, or invalid attribute into a plausible
Kotlin type is less safe than withholding the declaration with a located diagnostic.

## Consequences

- Foreign imports gain normal Kotlin analysis and calls without giving up exact CLR identity.
- The importer carries more structured evidence than a reflection-shaped convenience API.
- Malformed structural metadata fails early; malformed advisory metadata cannot strengthen types.
- Some otherwise valid CLR declarations remain unavailable until a complete Kotlin-facing mapping
  exists.
- Kotlin-produced and foreign declarations may normalize to the same Common effect while retaining
  different semantic authorities.

## Conditions before acceptance and ABI freeze

1. Move the physical model, selected graph, and importer policy behind dependency directions that
   do not make the frontend conceptually depend on backend implementation packages.
2. Complete the selected foreign declaration families or document precise withholding boundaries.
3. Keep every admitted FIR type paired with exact cross-module backend binding.
4. Prove nullable and CodeAnalysis mappings against real Framework and modern Roslyn metadata plus
   malformed, duplicate, contradictory, wrong-target, and look-alike attributes.
5. Test assembly ambiguity, forwarding, hostile blobs, cycles, excessive recursion, and resource
   bounds without loading target code.
6. Test properties, generic scopes, variance, constraints, arrays, delegates, and profile crossings
   through semantic CLR identity rather than rendered names.
7. Resolve the open programme items in
   [`../programmes/clr-annotations.md`](../programmes/clr-annotations.md) before declaring the
   foreign surface stable.
