# Constrained generic owners: storage, substitution and public contracts

Reviewed compiler base: `ef5cac37283080d5716e6d973fa9c5affd063020`.
This continues the [storage-cycle investigation](generic-owner-storage-cycle-2026-09-24.md).
It is research, not an accepted replacement ABI, implementation change or
permission to resume the Runtime/Stdlib census. Production remains erased.

## Assessment

**CONSTRAIN remains a credible research direction, but “erase variant interfaces
and keep invariant classes generic” is not a sufficient admission rule.**
The useful general condition is closure under substitution and all permitted
values/writes, with an honest outward contract. This is stronger than exact
provenance for one expression or success for one closed construction.

A same-object canonical view can solve a real subset without replacing the
receiver or erasing every outer field. Conversely, open nullability, fixed
ancestry and loss of logical argument distinctions prevent declaring that
subset complete. Keep those obligations separate from measured dispatch costs.

## Four proof obligations

Let `R(A)` denote a proposed physical carrier for logical type `A`, and let
`P(t)` be the physical type expression recorded by a producer for an open
logical expression `t`. A proposed natural boundary needs all of:

1. **Value-domain inclusion.** Every admitted value of `A`, including legal
   subtypes, bottom-producing objects and future foreign values, can inhabit
   the promised carrier without changing its receiver.
2. **Substitution coherence.** Substituting physical arguments into the recorded
   `P(t)` agrees with the carrier required for the logically substituted type.
   An explicitly specified same-object operation boundary may adapt a value;
   it cannot convert one invariant outer construction into another.
3. **State and ancestry closure.** Constructors, later writes, aliases,
   overrides and separately compiled descendants obey one field/base contract.
   A precise initializer cannot prove an open mutable slot or future subclass.
4. **Observation authority.** C# signatures, allowed constructions, Kotlin
   casts and reflection have sufficient authoritative information. Do not
   recover logical type arguments from current mutable contents or pretend a
   broad public view is a narrow CLR construction.

These are admission obligations, not a new universal representation oracle.
The existing declaration authority, producer-wide state and value-provenance
layers retain their separate responsibilities. Per-value facts can select a
proven natural route; they cannot establish these declaration-wide obligations.

## What an erased inner declaration genuinely buys

If one logical variant interface is deliberately represented by one nominal
non-generic interface, every construction shares that real carrier:

```text
Producer<Int>, Producer<String>, Producer<Nothing> -> Producer
Box<Producer<...>>                                -> Box<Producer>
makeNested<T>(Producer<T>)                        -> Box<Producer>
```

The open factory now has a uniform physical signature, and the outer field can
remain `!T`, instantiated with the nominal `Producer` interface rather than
`object`. It can store the original bottom producer and later replacements.
This does not require a proxy, duplicated state or a special bottom rule.

The price is explicit: ordinary C# implements/consumes `Producer`, not
`Producer<T>`, and its result uses the canonical member contract. Typed bodies
may still exist, but their old natural-interface allocation savings cannot be
assumed for an erased interface call. Selective erasure is a new whole-family
choice, not simply switching off one lowering.

A plain C# implementation of that non-generic interface establishes its
classifier and object-return contract, not independently the Kotlin relation
`Producer<String>`. A source/KLIB binding, an explicit typed export contract or
an independently authenticated type-argument witness must own that relation.
A star view or classifier-only check establishes at most `Producer<*>` or
classifier membership, not that stronger argument. A broad honest CLR signature
is not equivalent to complete static enforcement of the Kotlin API.

Current [type mapping](../../src/org/jetbrains/kotlin/backend/dotnet/DotNetIlCodegenSupport.kt)
already recognizes a nominal erased interface inside an outer construction.
However, the [exact-carrier binder](../../src/org/jetbrains/kotlin/backend/dotnet/DotNetGenericOwnerExactCarrierBinding.kt)
currently discards logical arguments only for canonical arity-zero classes,
and early interface planning also influences owner/state admission. Those
authorities must agree before a selective-inner-erasure implementation exists.

## Invariance alone does not establish an exact nested carrier

Kotlin use-site variance applies even to an invariant declaration:

```kotlin
class Box<T>(var value: T)
val outer: Box<Box<out Any>> = Box(Box(1))
outer.value = Box("replacement")
```

The inner receivers may be actual `Box<int>` and `Box<string>`; neither becomes
`Box<object>`. A reference-only counterpart uses `Box<Box<in String>>` with
`Box<Any>` and `Box<String>`. No variant interface appears in either example.

This follows from [Common type checking](../../../../../core/compiler.common/src/org/jetbrains/kotlin/types/AbstractTypeChecker.kt),
where `effectiveVariance` uses the projection when declaration variance is
invariant, and from Kotlin's [mixed-site variance rules](https://kotlinlang.org/spec/type-system.html#mixed-site-variance).
CLR generic classes do not provide the corresponding construction conversions;
Microsoft's [variance documentation](https://learn.microsoft.com/en-us/dotnet/standard/generics/covariance-and-contravariance)
also distinguishes reference-type interface variance from invariant classes
and value-type arguments.

But this counterexample does **not** defeat the stronger mapping:

```text
R(Box<out A>)      = a genuine same-object Box read view
R(Box<Box<out A>>) = Box<Box read view>
```

The outer field is still a real generic field, now holding an interface
reference. A projected read does not authorize an arbitrary write through that
view. Kotlin's permitted operations and original typed aliases remain decisive.

C# must see that contract honestly: an explicit nominal read view or a broad
boundary, not an invented `Box<Box<object>>`. Naming the view compiler-private
does not make it disappear from a public `Box<View>` signature. Existing narrow
C# boxes still need a separately specified entry contract.

## Open nullability is a substitution problem

Consider separately compiled source:

```kotlin
fun <T> wrapNullable(x: T?): Box<T?> = Box(x)
val inner: Box<Int?> = wrapNullable<Int>(1)
val outer: Box<Box<Int?>> = Box(inner)
```

The current one-slot rule can produce:

```text
open T?                     -> object
factory's actual allocation -> Box<object>
closed Int?                 -> Nullable<int>
naive outer field type      -> Box<Nullable<int>>
```

Those inner constructions differ. An `object`/capability result preserves the
original box, but it does not make that receiver fit the later narrow field.
Simply preserving more local provenance is insufficient.

With natural closed nullable primitives and references, unrestricted `T?` has
no single ordinary CLR type expression. `Nullable<T>` requires a non-nullable
value type; a `where T : struct` alternative excludes valid reference and
nullable substitutions. C# also cannot use a nullable value type as the
underlying type of another nullable value type. See the
[nullable-value documentation](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/builtin-types/nullable-value-types).
Kotlin nullability is idempotent (`T??` is `T?`), so blindly nesting a custom
optional carrier is not a general solution either.

The corresponding fixed-ancestry example is:

```kotlin
open class Base<T>(var value: T)
class Derived<T>(x: T?) : Base<T?>(x)
```

A metadata-fixed `Base<object>` edge cannot later become `Base<Nullable<int>>`.
Erasing only the difficult descendant does not repair a natural base promise.

The [one-slot nullability ADR](../decisions/adr-hybrid-generic-nullability-and-covariant-returns.md)
already recognizes this nested invariant boundary. The
[split-nullable result convention](../decisions/draft-adr-split-nullable-callable-result.md)
does not remove it: `T + out bool` is a callable result layout, not one CLR
type argument that can be substituted into an arbitrary field or base TypeSpec.

Honest options are to broaden affected domains consistently, use a canonical
operation/storage boundary, transport additional physical carrier authority,
or leave the unsupported shape unadmitted. Each has a cost or outward-contract
consequence. None may silently rewrite a previously emitted MethodDef.

## Other boundaries which a subset must retain

- Value classes are not automatically excluded. The existing
  [nominal generic-position rule](../decisions/value-classes.md) is a useful
  stable mapping; remapping a nominal argument to its scalar payload after
  substitution would destroy that stability.
- Captured existential arguments require supported projection operations;
  they are not an invitation to invent a CLR MethodSpec argument.
- Native foreign declarations retain their existing metadata and do not
  acquire a Kotlin capability by assumption. C# allocating a Kotlin-owned
  `Box<T>` is a different boundary from importing an unrelated native box.
- Merging logical arguments into one physical carrier can lose distinctions
  needed by BK-1. If those casts are admitted, the runtime needs authentic
  logical-instantiation evidence; current element values are not that evidence.
  This is not historical interface-selection transport and does not by itself
  require a pair attached to every reference.

For example, two outer boxes declared as `Box<Box<out Any>>` and
`Box<Box<out Number>>` may both contain the very same `Box<Int>` receiver. The
outer declaration is invariant: those distinct logical instantiations are not
made equal by inner covariance. If both map to `Box<BoxView>`, neither that CLR
construction nor the shared current element distinguishes them for an admitted
BK-1 check. This is a requirement on a proposed broader cast grammar, not a
demonstrated failure of the currently bounded BK-1 implementation. The simplest
storage proof must not quietly promise those additional checks.

## Alternative architecture assessment

| Candidate | What it can preserve | What it does not establish |
| --- | --- | --- |
| Selectively erased variant declarations + exact invariant owners + canonical projected views | Real scalar fields; nominal nested interface fields; same-object projected storage | Open-nullable/substitution closure, general ancestry, runtime argument checks, clean complete C# signatures |
| Natural physical owners with broadly canonical operation boundaries | One object/state and selected exact storage/calls | Universal natural public `C<T>` results; a complete rule for existing narrow foreign objects |
| Erased canonical owner with private specialization | Stable erased public contract; possible internal typed work | The natural-call benchmark gains, public `C<T>`, or cheap escaped mutable storage |
| Erased Kotlin ABI plus explicit typed export | Working tested-cycle baseline with a separately delimited host contract | Wrapper-free natural access to every arbitrary Kotlin instance |

A hidden typed subclass is not a free implementation trick: a currently sealed
CLR canonical class cannot have one without changing its public modality.
A private storage cell avoids that inheritance problem but introduces another
allocation/indirection and a state-transition/concurrency proof for incompatible
erased writes. No such transition implementation was found. One fixed typed
field alone does not provide a complete erased fallback.

## Executable evidence and cost attribution

The [reproduction bundle](generic-owner-hybrid-boundaries-2026-09-24/README.md)
contains sources, drivers, output and manifests for three distinct evidence
layers. None changes the Kotlin/.NET compiler or supersedes its archived failed
storage cycle.

1. **Common/JVM precedent: six groups passed.** An existing hashed
   `kotlinc-jvm 2.5.255-SNAPSHOT` distribution on JRE 21.0.9 compiled three
   separate jars with `-Werror`. Out/in projections, open projected factories
   and writers, nested nullable storage, nullable idempotence and nullable base
   substitution all execute correctly. No unsafe cast makes those examples
   work. This proves required source behavior, not a CLR representation.
2. **Hand-authored CLR mechanism: eight groups passed on each profile.**
   Three separately compiled C# assemblies run on serviced Framework CLR
   `4.0.30319.42000` and .NET `10.0.9`. Six groups demonstrate scalar `!T` state,
   nominal erased-producer storage, same-object read-only projected views,
   broad/closed nullable views and a separate C# virtual override. Two groups
   assert the expected incompatible-construction casts. A separate compilation
   confirms `CS0453` for unrestricted `Nullable<T>` on both profiles.
3. **Actual Kotlin/.NET products: matched cost attribution.** The following
   measurements reuse the frozen compiler-generated candidate/erased libraries,
   not the hand-authored CLR model. The original narrow-storage failures remain
   uncorrected and are not timed as working paths.

The CLR model's `IBoxView` is explicitly public and read-only, not generated
hidden authoring ABI. Projection writes use original typed aliases or replace
the outer element; the view offers no arbitrary write. Its open factories'
unused physical `T` does not enforce the original logical type relationship.
It therefore proves useful CLR mechanisms **with a broader stated contract**,
not general Kotlin substitution or complete interop. Both types and calls are
observed; one field and original receiver identity are checked. The initial
Framework attempt failed only while saving results beside the PowerShell host;
the corrected observer writes beside its own assembly and both fresh runs pass.

The actual emitted broad measurement path contains no semantic capability
dispatch, reflective invocation or runtime argument-mapping function. It calls
the natural generic factory and box accessors, then converts object results to
the known actual producer interfaces. The nested variant adds a recorded
alternate factory and result-view cast. The dominant shared Framework slowdown
must not be labeled semantic-dispatch overhead merely because the box is broad.

Matched diagnostic variants keep one allocation, two writes, three reads and
the same producer effects. All variants also have identical identity guards.
They compare freshly checked interfaces, pre-established interfaces and concrete
receivers. Removing repeated conversion also changes optimizer knowledge, so
these are attribution probes, not isolated opcode timings or compiler fixes.

Framework results, seven samples per epoch/case/variant, ten million measured
cycles after one million warmup cycles:

| Broad receiver source | Candidate ns/cycle | Erased ns/cycle | Candidate/erased bytes/cycle |
| --- | ---: | ---: | ---: |
| Newly checked interface | 127.899 | 25.514 | 24 / 72 |
| Previously captured interface | 13.258 | 20.331 | 24 / 72 |
| Original concrete receiver | 9.685 | 9.874 | 24 / 24 |

The independent audit covers 84 samples, matching raw logs, effects/checksums,
unchanged input hashes and identical identity guards. Captured receivers still
make the same interface calls: repeated interface conversion/source provenance
and its JIT consequences localize the large penalty, not an inevitable generic
storage or semantic-dispatcher cost. The concrete erased variant also bypasses
its result-boxing bridge; its change is not dispatch-only. No native JIT output
was inspected. Background applications and this bounded workload prevent a
whole-target performance conclusion. See the
[full attribution and limits](generic-owner-hybrid-boundaries-2026-09-24/ATTRIBUTION.md).

This corrects the interpretation of the earlier fivefold Framework overlap
result, not its measurement. It neither closes the failed storage contract nor
establishes that a proposed hybrid matches the measured natural allocation gains.

## Recommended next decision

Do not implement a new general representation engine or restart the census on
these examples alone. First choose and prove the outward contract at the
canonical-view boundary, then close open-nullable substitution and the required
runtime argument checks over the same separately compiled cycle.

The preferred research candidate is a **bounded exact/canonical hybrid**:
retain exact physical owners/state when all four obligations hold, and specify
canonical views for genuinely non-representable operations and nested domains.
Selective declaration erasure is one possible way to obtain a stable nominal
view, not a conclusion that every variant interface must be erased.

This recommendation does not accept public generated capability names, restrict
previously supported C# constructions, weaken BK-1, add wrappers, or authorize
mixed production epochs. If the necessary complete canonical ABI outweighs the
remaining natural benefit, choose the simpler erased/export model instead of
preserving generic shells at any cost.
