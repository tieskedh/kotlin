# Fixed projected-array state in a generic owner

This rehearsal starts from `af73c6519e`. Current integration belongs in
[`../../STATUS.md`](../../STATUS.md); the durable rule is in the
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md).

## Reproduced cause

The fresh candidate census after the native invariant-array feature contains
227 error lines, one fewer than the previous 228. A separate read-only
investigation established that several erased derived owners cannot name
their selected generic base. Mapping such a base to object is a value-domain
fallback, not a legal replacement superclass. Neither the MethodDef-view
guard nor the physical inheritance graph was the cause.

The custom `Window<T>(Array<out T>) : Base<T>` reproduces that problem without
collection names or stdlib source. Its constructor prototype already selects
System.Array, but the state graph loses the projected input's fixed carrier,
leaving the entire owner erased. The source-built ArrayAsList has this shape;
the custom proof is not itself a complete stdlib census.

The initial public-owner probe exposed a subsequent separate-consumer direct
Window.read routing gap. The bounded scope instead uses a compiler-private
implementation behind its public Base<T>, which implements Read<T>. This also avoids
admitting a new raw System.Array C# entry without its required bound guard.

## Physical rule and proof boundary

An output projection has the fixed non-generic System.Array carrier. It does
not have an exact T[] construction. Preserving that input through a complete
private writer graph need not erase its enclosing Window<T> or its Base<T>
edge. The one field can hold successively an int[] and a string[] when Kotlin
permits both through Array<out Any?>; neither vector is copied.

The BOUND declaration index now records that fixed core class. The shared
carrier binder checks its class/zero-arity shape and produces no element
view. State selection retains the ordinary private-field, initializer, live
writer and plain-memory obligations. A logical capability request on the
parameter does not replace an already-selected core carrier. Both its actual
FieldDef and every typed writer MethodDef parameter must independently match
the BOUND core identity in final emission. Rendered strings, object, an exact
vector, and another core type are not equivalent evidence.

The final carrier projection consumes the BOUND System.Array identity rather
than remapping the logical Kotlin element. MethodDef seal grammar outside the
state contract is not generalized by this addition; unsupported core callable
shapes remain unavailable. Existing emitted/retained MethodDefs, import rules,
and logical KLIB types are unchanged.

Ordinary foreign-accessible owner-dependent projected-array inputs are not
new state evidence in this slice. Privacy is checked with Common IR's
effective-privacy helper, at entry admission, not in the carrier coordinate.
The test exposes exact T[] factories to C# and performs legal projections
inside Kotlin; its PublicWindow<T> negative remains canonical. General raw
System.Array entry validation, stars/input projections, invariant open T?
arrays, and the public direct-owner routing gap require separate proofs.

Kotlin's Array projection semantics and the existing target projected-array
decision remain authoritative. JVM/JS/Native/Wasm preserve the original array
under projection; .NET uses System.Array because value vectors cannot covary
to object[]. No Common, Runtime/Stdlib source, serialized schema, repair wrapper,
shadow state, or production-selected mapping changes. Projected GetValue still
boxes value elements; this is not a claim of unboxed projected reads or a
measured performance gain.

## Verification

The candidate gate passed 32 tests over both FIR parsers and Framework 4.8/
.NET 10, plus 406 backend tests. The same 32-test production inverse also
passed. Direct JUnit XML audit found no failures, errors, or skips.

The eight fixtures are GenericOwnerProjectedArrayConstructor,
GenericOwnerArrayConstructor, GenericOwnerStateAuthoritySeparateCompilation,
GenericOwnerRehearsalStateCarriers, GenericOwnerRepresentativeArrayCopy,
GenericOwnerForeignNullableInput, GenericOwnerCanonicalStateReference, and
GenericOwnerSemanticBodyExactResultChain. Each generated method has the `test`
prefix and runs in these exact classes under
`org.jetbrains.kotlin.test.runners.codegen`:

- `FirLightTreeDotNetBoxTestGenerated$Box`
- `FirPsiDotNetBoxTestGenerated$Box`
- `FirLightTreeDotNetFrameworkBoxTestGenerated$Box`
- `FirPsiDotNetFrameworkBoxTestGenerated$Box`

The serial candidate invocation uses `--max-workers=1 --no-configuration-cache
-q`, `-Pkotlin.dotnet.genericOwnerRehearsal=true`, the actual
`:compiler:backend.dotnet:test --rerun`, then
`:compiler:fir:fir2ir:dotNetTest --rerun` with all 32 exact `--tests` filters.
The inverse runs that FIR task and the same filters without the rehearsal
property. Candidate XML is preserved in `candidate-32-backend-406.zip` and
inverse XML in `inverse-32.zip`. `semantic-hashes.json` records the 14 frozen
semantic source hashes, verified unchanged after both gates.

The new fixture assembles and executes separate Kotlin lib/main assemblies,
reads actual PE field/constructor signatures, requires the BOUND/final state
seal, and compiles/runs an ordinary C# consumer. It covers reference, value,
nullable-value, value-class and nested-array substitutions; a widened value
array; mutation through an exact alias; replacement by a different array
construction; stars; object/array identity; and a public-constructor admission
negative. Model negatives reject missing or malformed core TypeDef authority,
stars/input projections/open-nullable invariant arrays, mismatched field and
writer observations, and type-name text masquerading as physical identity.

The production full checkpoint `cafb56a4e8` (2,887 tests) is inherited, not
rerun or increased by this focused gate. New entry/write proof is explicitly
rehearsal-gated; BOUND projection and final observations consume only
rehearsal state, and production raw observation lists are required empty.
The test-harness addition is fixture-local. Schema 71/22 and surface 62 stay
unchanged. No target-wide, full stdlib, AOT or production-cutover claim is made.

Evidence directory: `D:\CodexTemp\projected-array-owner-20260911`.
`baseline.xml` and `admission-diagnostic.xml` isolate the initial erased owner
and unresolved writer. `fixed-storage-probe.xml` records the separate public
consumer gap; `private-owner-probe.xml` is the first private Kotlin execution.
`bound-stage-diagnostic.xml` shows the strict constructor still carried a
logical capability request, and `fixed-core-writer-probe.xml` exposes the
missing final core-carrier projection after BOUND became available. Temporary
diagnostics were removed. The earlier inheritance investigation is preserved
at `D:\CodexTemp\physical-base-view-20260911`.

`core-emitter-probe.xml` and `natural-read-consumer-probe.xml` record additional
limitations of the proposed test interface: the mixed multi-member surface
remained canonical, and even the simple generic Read<T> was returned as object
through the helper chain. The final fixture uses its already-declared Base<T>
as the factory contract and exercises Read<T> through ordinary inheritance.
No compiler change was made to hide or claim closure of those separate gaps.
