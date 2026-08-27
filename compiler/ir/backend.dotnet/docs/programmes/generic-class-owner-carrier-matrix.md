# Generic-owner carrier and admission matrix

- Status: **Architecture design aid — not production authority**
- Programme:
  [`generic-class-owner-reopening.md`](generic-class-owner-reopening.md)
- Shared physical model:
  [`../decisions/draft-adr-generic-owner-physical-authority.md`](../decisions/draft-adr-generic-owner-physical-authority.md)
- Class candidate:
  [`../decisions/draft-adr-reified-generic-class-owner.md`](../decisions/draft-adr-reified-generic-class-owner.md)
- Interface candidate:
  [`../decisions/draft-adr-reified-generic-interface-owner.md`](../decisions/draft-adr-reified-generic-interface-owner.md)
- Split-nullable result:
  [`../decisions/draft-adr-split-nullable-callable-result.md`](../decisions/draft-adr-split-nullable-callable-result.md)

This matrix is a compact test oracle for the candidate architecture. The ADRs
own the rules. Git and the dated archive own the earlier schema, recognizer,
exact-sibling, semantic-hook, and benchmark narratives.

## Vocabulary

For a Kotlin-owned generic declaration `C<A>`:

- `C<>` is its proposed one natural open CLR TypeDef;
- `P(A)` is an authority-proven truthful physical argument;
- `C<P(A)>` is an exact natural construction;
- a **semantic carrier** is an authority-recorded view used where no truthful
  constructed CLR spelling exists;
- `ProducedLayout` is the complete direct or split layout supplied by an
  expression or definition;
- `StorageLayout` is the independently fixed layout of its destination;
  and
- a **guaranteed view** is a real runtime view proven by emitted/retained CLR
  metadata or a successful physical check.

For direct values the two layouts contain the distinct `ProducedCarrier` and
`StorageCarrier`. Split-nullable results additionally contain the `bool` flag
carrier until the logical result is materialized. Kotlin IR/KLIB remains
authoritative for logical types, variance, nullability,
projections, stars, overrides, and operation semantics. A physical carrier is
execution evidence, not a second Kotlin type.

## Declaration admission

| Shape | Candidate physical declaration | Result |
| --- | --- | --- |
| complete class contract representable on one open `C<T...>` | one natural class, one state | admit to rehearsal |
| complete interface contract CLR-legal with declared variance | one natural variant interface | admit to rehearsal |
| input member makes declared interface variance illegal | one complete natural interface with weaker/invariant physical parameter | admit if all other obligations fit |
| member, constraint, base, or interface edge has no truthful open CLR form | none invented | remain erased/unadmitted |
| representation would need a wrapper, proxy, twin owner, or shadow state | forbidden | remain erased/unadmitted |
| representation would need `C<object>` while the object is only `C<int>` | fabricated construction | reject |

Admission is declaration-wide and open-world. One easy `C<Int>` construction
does not admit an open owner whose inheritance, state, public/protected writes,
defaults, reflection, or separate-compilation obligations remain unsupported.

Production stays on the accepted erased epoch until the complete selected
family and its exact inverse pass one atomic migration.

## Logical argument to physical construction

| Logical argument at this point | Truthful physical expression | Exact construction available? |
| --- | --- | --- |
| closed reference `String` | `string` plus nullability metadata | yes |
| primitive/value type `Int` | `int32` | yes |
| closed nullable value `Int?` | `Nullable<int32>` | yes |
| owner/method parameter without representation-changing transform | matching `!T` / `!!T` | yes |
| definitely non-null `T & Any` | matching physical parameter | yes; does not add CLR `class`/`struct` constraint |
| `T?` with an authority-proven reference-shaped substitution | reference carrier plus metadata | at that closed point |
| `T?` with an authority-proven value-shaped substitution | `Nullable<T>` | at that closed point |
| unconstrained open `T?` as an owner/base/field type | no single conditional CLR TypeSpec | no |
| star or use-site projection | no new constructed type follows from Kotlin logic alone | only an already-guaranteed selected view may survive |
| nested `G<A>` | `G<P(A)>` only when `G` and every argument are physically proven | recursive |
| logical variance `Producer<Int> -> Producer<Any?>` | same object; no CLR `Producer<object>` follows | exact original view may survive as provenance, widened construction does not exist |

Open `T?` as a direct method result is a callable-layout problem, not owner
construction. It may use `SplitNullable(!T, out bool)` without making an open
`C<T?>` base, field, or interface edge representable.

## Value provenance and placement

| Flow | Produced fact | Placement/join rule |
| --- | --- | --- |
| `new C<Int>()` | `C<int>` and recorded runtime views | exact storage allowed when destination admits it |
| immutable alias with one reaching definition | same carrier/views | may retain exact fact |
| compiler temporary or inlined alias | no privilege from origin | ordinary dataflow rules |
| mutable local overwritten sequentially | fact of latest reaching write | old fact is killed |
| branches produce `C<int>` and `C<string>` | intersection of guaranteed views | semantic/common carrier or `object`; never `C<object>` |
| logical widening with one selected guaranteed view | original exact view may remain in lineage | lineage selects, never proves |
| two different selected lineages join | no selected lineage | do not guess even for same runtime class |
| checked physical conversion succeeds | checked view becomes guaranteed | only for the checked path |
| unchecked/logical cast | existing evidence preserved | creates no physical view |
| null joins exact reference | null layout plus exact non-null view | do not turn null into object evidence |

`IR origin`, generated names, package membership, and stdlib declarations are
never proof sources. Provenance is structural and authority-backed.

## State selection

Every object has one authoritative state selected from all producer-visible
writes and escapes.

| State evidence | Permitted carrier |
| --- | --- |
| every constructor/write/override/external route stores physical `!T` | `!T` or another exact carrier |
| a legal semantic write can store a value outside physical `!T` | semantic carrier or `object` |
| two closed uses happen to be exact locally | no effect on open state selection |
| this escapes before all writers are known | fail closed to admitted broad carrier or reject owner |
| a proposed repair needs typed plus object shadow fields | forbidden |

A broad input to one operation does not contaminate unrelated receiver-derived
state. Conversely, exact provenance of one caller may not narrow a genuinely
broad field or parameter.

## Callable contracts and operation routing

Parameter domains and result layout are independent:

```text
CallableContract {
    MethodDef and virtual/MethodImpl authority
    parameterDomains[]
    resultLayout
    semanticPolicy
}
```

| Operation | Authoritative route |
| --- | --- |
| exact receiver and compatible strict input | natural constructed MethodDef |
| widened receiver retaining one guaranteed exact view | that recorded view's MethodDef; widen result afterward |
| broad candidate with compatible runtime value | convert to actual parameter carrier, then real interface/virtual MethodDef |
| broad candidate with recorded Common wrong-shape policy | recorded fixed result/behavior |
| general incompatible broad input with concrete Kotlin semantic body | authority-recorded semantic body on same object |
| general incompatible broad input on ordinary foreign implementation with no derivable behavior | explicit adapter, diagnostic, or unadmitted operation |
| direct logical `T?` output | `Direct` or producer-recorded `SplitNullable(payload, out bool)` |
| `Lookup<K,V>.get(K): V?` candidate | strict `!K` input plus independent `SplitNullable(!V, out bool)` result |
| virtual specialization after a base MethodDef exists | bridge/MethodImpl to the existing slot; never rewrite base signature |
| retained foreign call | retained TypeDef/MethodDef/MethodImpl metadata |

The compiler may use a semantic capability implemented by Kotlin-produced
objects. Ordinary CLR implementations and subclasses need only the complete
natural contract for all mechanically derivable behavior. They are never
required to implement a hidden hook, exact sibling, partial fragment, or
generated compiler ABI.

Concrete public-method name/arity discovery is not a route. Operation binding
uses the actual constructed interface/virtual MethodDef and producer/retained
MethodImpl authority.

## Split-nullable composition

For a direct logical nullable result whose exact payload is `P(T)`, the
producer may select:

```text
P(T) Read(..., out bool isNull)
```

The payload is a producer-recorded physical type expression. Consumers bind it
through the actual owner/method construction; they do not remap a later logical
`IrType` and therefore do not accidentally unbox a value class or rewrite a
sealed MethodDef.

Examples:

| Logical callable | Candidate physical callable |
| --- | --- |
| `Source<Int>.read(): Int?` | `int Read(out bool)` |
| `Source<String>.read(): String?` | `string Read(out bool)` |
| `Lookup<Int,Long>.get(Int): Long?` | `long Get(int key, out bool isNull)` |

Nullable-payload and value-class substitutions require their own hostile proof
against the exact producer-recorded payload contract before this layout is
generalized; the matrix does not infer a nested-nullability convention.

## Imported CLR declarations

Imported TypeDefs and MethodDefs are physical authority. Kotlin does not create
Kotlin-owned capabilities or siblings for them.

CLR declaration variance is physically valid only for reference-shaped changed
arguments. Therefore:

| Conversion | Physical result |
| --- | --- |
| `IOut<string> -> IOut<object>` | verifier-valid when retained CLR metadata declares `out` |
| `IIn<object> -> IIn<string>` | verifier-valid when retained metadata declares `in` |
| `IOut<int> -> IOut<object>` | reject; boxing is not generic variance |
| open/value-uncertain changed argument | reject until reference shape is proven |
| equal constructed arguments | identity conversion |

A mandatory post-lowering physical-conversion validator must cover every
consumption edge. The emitter remains a final assertion, not the first user
diagnostic.

Raw `System.Array` also requires an SZ-array guard at each foreign entry;
bounded projected arrays additionally validate the element domain.

## Cast and runtime classification

| Kotlin operation | Required classifier evidence |
| --- | --- |
| `value is C<*>`, `as C<*>`, `as? C<*>` | recorded open TypeDef ancestry/capability normalization |
| admitted parameterized `is`, unchecked `as`, unchecked `as?` | one BK-1 Kotlin-aware argument-subtyping predicate |
| ordinary variance/projection conversion | logical Kotlin relation plus same-object semantic routing |

BK-1 may reject an incompatible unchecked construction earlier, but it does
not reject valid Kotlin declaration variance. It never creates a second object
or a fictitious constructed carrier.

## Host-language admission

| Foreign source shape | Result |
| --- | --- |
| complete natural interface implementation | admitted without generator or hidden ABI |
| missing natural obligation | host compile/verifier failure |
| explicit interface implementation | admitted; dispatch by MethodDef |
| complete natural class subclass | admitted where all semantic calls are compiler-derivable |
| non-derivable raw semantic behavior | explicit adapter/diagnostic or constrain admission |
| optional generator forwarding defaults | convenience only |
| optional explicit adapter generation | visible opt-in boundary, not silent admission |

## Required hostile matrix

Promotion requires structural, declaration-name-independent proofs for:

- mutable locals holding different constructions and control-flow joins;
- value-type widening, stars, projections, and one object implementing multiple
  constructed interfaces;
- broad candidate inputs and general `@UnsafeVariance` inputs;
- mixed exact/semantic captures and generated or anonymous classes;
- fields, properties, defaults, diamonds, MethodImpls, and reabstraction;
- deeper Kotlin/C# inheritance and explicit-interface implementations;
- retained foreign generics and reference-only variance;
- separate assemblies, stale/partial manifests, inlined bodies, and rollback;
- nullable-value and value-class substitutions in direct and split results; and
- Framework 4.8, .NET 10, trimming, ReadyToRun, and NativeAOT.

Every positive proof must have a hostile negative demonstrating that the same
rule loses precision or fails closed instead of inventing physical truth.
