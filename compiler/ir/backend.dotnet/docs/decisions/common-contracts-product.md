# ADR: Common contracts as the Kotlin authority with additive CLR projections

- Status: **Accepted for the ordinary non-reified contracts product and the
  exact first CLR-export projection**
- Scope: the Common contracts DSL and effect interfaces, `InvocationKind`, the
  contract-bearing Common scope functions, and both Common `buildString`
  declarations
- Does not enable: reified or suspend inline functions, member-state contracts,
  general valued annotations, runtime contract enforcement, or a second
  .NET-specific contract store

## Decision

Kotlin/.NET compiles the ordinary Common contracts product unchanged. FIR owns
contract recognition and data-flow semantics, the shared KLIB serializer owns
separate-compilation effects, and Common owns the public DSL declarations and
inline bodies. The backend neither interprets contract calls at runtime nor
reimplements their effects.

The physical standard library contains the public `kotlin.contracts` types,
including the ordinary reference-class `InvocationKind` enum, and assembly-
visible fallbacks for `@InlineOnly` functions under the existing inline-only
ABI rule. Contract descriptions remain attached to logical Kotlin declarations
in KLIB even when no CLR metadata can express them.

For a Kotlin-produced DLL, the explicit export phase may additionally emit a standard
`System.Diagnostics.CodeAnalysis` attribute only when one resolved Common
effect has the same target and complete meaning. The attribute is a derived
C#/CLR view. Removing it must not change Kotlin compilation, and removing the
embedded KLIB must never leave a Kotlin library that pretends the attribute is
its authoritative declaration contract.

The first admissible projection design is restricted to these exact shapes:

| Common effect | CLR projection |
| --- | --- |
| normal return implies a reference parameter is non-null | parameter `NotNull` |
| Boolean result `b` implies a reference parameter is non-null | parameter `NotNullWhen(b)` |
| a named reference parameter is non-null implies a non-null result | return `NotNullIfNotNull(name)` |
| normal return implies Boolean parameter `b` is the opposite of `v` | parameter `DoesNotReturnIf(v)` |

The `NotNullIfNotNull` row is the same parameter-non-null-implies-result-
non-null implication in both effect models. Reversing that implication would
be unsound.

An unconditional non-returning Kotlin declaration uses method
`DoesNotReturn` only when its logical return is `Nothing` and its physical
signature remains independently correct. This is a type/signature projection,
not a substitute for a Common effect.

No attribute is emitted for `callsInPlace`, occurrence ranges,
`returnsResultOf`, type predicates stronger than non-nullness, Boolean
combinations that the attribute cannot carry, receiver facts, or mutable member
state. Multiple effects may yield multiple attributes only when each row is
independently exact and their combination does not strengthen the Kotlin
contract.

## Common source product

The target stdlib consumes these authoritative files:

- `libraries/stdlib/src/kotlin/contracts/ContractBuilder.kt`;
- `libraries/stdlib/src/kotlin/contracts/Effect.kt`;
- `libraries/stdlib/src/kotlin/util/Standard.kt` through `takeUnless`; and
- the complete `libraries/stdlib/src/kotlin/text/StringBuilder.kt`.

The temporary .NET source generator may copy or project Common declarations to
fit the bootstrap source layout, but it may not rewrite algorithms or contract
blocks. The earlier `Standard.kt` projection containing only
`NotImplementedError`, and the earlier `StringBuilder.kt` projection removing
`buildString`, cease to be valid once this phase lands.

Common `repeat` remains excluded as one exact final-file declaration until the
ordinary `Int.until`/range/progression product exists. Its Common body is not
rewritten to a target loop: `repeat` is enabled by admitting that real
dependency closure and then deleting the projection. This is an incomplete
stdlib surface, not a CLR semantic deviation or an alternative implementation.

`ExperimentalContracts` and `ExperimentalExtendedContracts` use the accepted
parameterless marker-annotation representation. The DSL interfaces use the
accepted erased Kotlin-owned interface ABI. `InvocationKind` uses the accepted
ordinary enum representation. These are compositions of existing general
decisions, not contract-specific physical exceptions.

## Cross-target evidence

- JVM, JS, Wasm, and Native all consume the Common contracts declarations and
  rely on shared frontend contract recognition rather than executing the DSL as
  a runtime checker.
- KLIB-producing targets serialize the compiler effect model for downstream
  Kotlin consumers; physical host metadata is not the source of Kotlin
  contracts.
- JVM's `@InlineOnly` implementation bodies use a non-public physical fallback
  while remaining logically public through metadata. Kotlin/.NET follows the
  already accepted CLR equivalent.
- Host-language annotations, where a target has them, are interoperability
  projections. They do not replace Common declarations or shared compiler
  data-flow.

The CLR-specific delta in the Common product is limited to physical facade
ownership. The separately gated export phase may add ordinary CLR attribute
rows for the exact Roslyn subset, subject to profile-specific availability of
those standard attribute TypeDefs.

## Semantics and runtime behavior

A source `contract { ... }` block is compiler syntax expressed through the
public DSL. Its effect is consumed before ordinary backend execution. No
`ContractBuilder` object is allocated, and no DSL member is called at runtime.
The empty Common `contract` body remains the fail-safe physical fallback.

The Common bodies of `run`, `with`, `apply`, `also`, `let`, `takeIf`,
`takeUnless`, and both `buildString` overloads determine evaluation
order, receiver identity, invocation count, exceptions, non-local returns, and
result values. BCL helpers cannot replace them merely because a similarly
named C# API exists.

## Exact projection carrier and profile behavior

Attribute projection is not required to publish the Common contracts product.
FIR contract descriptions are not backend IR facts, and the backend does not
rediscover them from lowered bodies or DSL calls. FIR2IR selects a versioned,
neutral carrier while the resolved FIR effect and corresponding IR declaration
are both available. The carrier contains only the exact derived CLR view, never
the authoritative Kotlin contract graph. It has no dependency on FIR, IR, CIL,
or Roslyn models.

Carrier version 1 contains exactly `ParameterNotNull`,
`ParameterNotNullWhen`, `ReturnNotNullIfParameterNotNull`,
`DoesNotReturnIf`, and `DoesNotReturn`. Parameter indices address ordinary
Kotlin value parameters; receiver, context-parameter, member-state, compound,
type-predicate, and calls-in-place facts are structurally unrepresentable.
Before transport, equivalent or overlapping conditional facts are normalized
to the standard attributes' multiplicity rules. Unconditional `NotNull`
subsumes `NotNullWhen`; both Boolean return values become one `NotNull` fact.
Conflicting `DoesNotReturnIf` values for one parameter are omitted rather than
emitted twice, while an independently established `DoesNotReturn` subsumes
every conditional non-return fact.

Only an explicitly selected .NET export consumes this carrier. The ordinary
Kotlin MethodDef remains unchanged. A generated default-argument overload
retains an attribute only when every parameter named or attributed by that
fact remains in the overload. In particular, a `NotNullIfNotNull` projection
never names a parameter omitted by the default wrapper.

The backend resolves every CodeAnalysis attribute against the selected target
framework profile and emits its exact constructor signature and parent row. If
an exact standard TypeDef is unavailable, the projection is omitted or the
selected product fails closed according to the profile contract; the backend
must not synthesize a look-alike public attribute type.

The first profile matrix is deliberately closed:

| Profile | Exact CodeAnalysis owner | Projection |
| --- | --- | --- |
| `net48` | none in the selected framework contract | omitted |
| `netstandard2.0` | none in the 2.0 reference contract | omitted |
| `net10.0` | `[System.Runtime]System.Diagnostics.CodeAnalysis` | emitted |

The pinned .NET 10 reference pack is metadata-verified for all five TypeDefs
and exact constructors. A later profile may be added only with the same
objective evidence; documentation that lists a type for some other framework
version is not sufficient.

The foreign importer may decode the same standard attributes as evidence under
the importer ADR. That symmetry does not create a round-trip authority loop:

```text
Kotlin source -> FIR/Common effect -> KLIB (authoritative)
                                \-> CLR attribute (derived view)

foreign CLR attribute -> FIR enhancement (verified evidence)
```

Kotlin-produced libraries are always loaded through their KLIB identity and
effects. The importer must not discard those effects and reconstruct them from
their derived CLR attributes.

## Design attack and rejected alternatives

### Use CodeAnalysis attributes as the contract store

Rejected. They cannot represent invocation ranges, `returnsResultOf`, Kotlin
type predicates, general logical expressions, or all receiver facts. Depending
on them would also make external attribute stripping change Kotlin semantics.

### Publish only compiler-private contract stubs

Rejected. The DSL and effect interfaces are public Common declarations used by
ordinary Kotlin source and library metadata. A private cycle breaker would make
the target's stdlib a different API.

### Execute or validate contracts at runtime

Rejected. Kotlin contracts describe compiler reasoning; they are not runtime
assertions. Runtime enforcement would change exception behavior, evaluation,
and performance compared with Common and every mature target.

### Translate every Kotlin effect approximately

Rejected. A weaker or stronger Roslyn view can miscompile C# null-state flow or
promise facts Kotlin never established. Unrepresentable effects remain only in
KLIB.

### Let Roslyn member-state attributes relax Kotlin smart casts

Rejected. Kotlin stability remains authoritative. In particular, a mutable
property cannot become smart-castable merely because C# accepts a
`MemberNotNullWhen` statement about it.

### Handwrite .NET variants of `Standard.kt` or `buildString`

Rejected. The CLR supplies no constraint that changes their algorithms. A
target copy would drift from Common contracts and inline bodies.

## Completion evidence

The feature gate must prove both FIR frontends, both target profiles, direct
and installed stdlib products, and separate producer/consumer libraries:

- all four `InvocationKind` entries, stable enum identity, and DSL signatures;
- smart casts from unconditional and Boolean-conditioned return effects;
- definite assignment and control flow from every calls-in-place range;
- KLIB preservation of effects across a DLL boundary;
- exact evaluation count, receiver identity, exceptions, and non-local returns
  for the Common `Standard.kt` functions;
- both `buildString` overloads, capacity errors, receiver behavior, and
  assembly-visible inline fallbacks;
- absence of runtime DSL calls in emitted executable paths;
- continued fail-closed behavior for reified, suspend-inline, valued-
  annotation, and member-state features outside this phase.

The CLR-export projection gate must separately prove:

- exact CLR attribute identity, constructor payload, and parameter/return
  placement for every admitted projection;
- absence of attributes for inexpressible, unstable, malformed, or
  profile-unavailable effects;
- normalization of overlapping effects without duplicate non-repeatable
  attributes;
- identical Kotlin behavior with projection disabled; and
- continued KLIB contract behavior after an external tool strips every derived
  CodeAnalysis attribute.
