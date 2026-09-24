# Generic-owner storage-cycle decision gate

- Status: **Active research gate; broad owner/census expansion paused**
- Parent: [generic-owner programme](generic-class-owner-reopening.md)
- Current evidence: [status](../../STATUS.md)
- Representation authority: [class owner](../decisions/draft-adr-reified-generic-class-owner.md)
  and [physical provenance](../decisions/draft-adr-generic-owner-physical-authority.md)

## Purpose and boundary

Decide whether natural CLR owners remain a useful general direction before
investing in more individual Runtime/Stdlib cases. Preserve existing working
features and the erased production ABI. This gate authorizes bounded research,
reproducers and comparison; it does not accept a broader C# signature, restricted
foreign view, new cast policy, pair layout or replacement storage ABI.

There are two decisions. This early strategic decision controls further
rehearsal investment and census scope. The later production/cutover decision
still requires the complete selected family, deployment, tooling and rollback
gates. A successful small cycle cannot freeze the target ABI.

## One complete cycle

Use structurally ordinary declarations with no collection-specific policy:

```kotlin
interface Producer<out T> { fun read(): T }
class Box<T>(var value: T)
fun <T> make(value: T): Box<T> = Box(value)
fun <T> makeNested(value: Producer<T>): Box<Producer<T>> = Box(value)
```

Compile the declaration/factory library once per candidate or erased epoch.
Compile a different Kotlin library against that DLL to perform legitimate
widening and replacement. Finally compile and run an ordinary C# consumer which
keeps an alias, triggers the Kotlin write and reads the same box afterwards.
Also reverse the allocation direction: an ordinary C# producer allocates the
box before Kotlin receives it. No rebuilding the first library at each closed
call site, replacement object or hidden source-authoring protocol may complete
the cycle.

Record the logical source type, actual CLR construction, FieldDef, factory,
writer and reader MethodDefs, and any semantic route at every boundary.
Distinguish C# reading the original receiver through a broad documented surface
from C# invoking `Producer<string>.Read()` on a receiver which lacks that view.
An invalid cast, successful store followed by a lying getter, diagnostic or
missing operation is not a completed positive cycle.

| Case | Required distinction |
| --- | --- |
| `make<Int>` and `make<String>` | Ordinary scalar/reference typed controls |
| Exact nested producer | Natural control, including later compatible replacement |
| `Producer<Nothing> -> Producer<String>` | Existing receiver, original throwing body/effects, no premature cast |
| `Producer<Int> -> Producer<Any>` | Non-bottom value-type covariance; returned value and call count |
| Reference subtype -> reference supertype producer | CLR-compatible variance control, not a substitute for the value case |
| `make<Producer<Any>>` versus `makeNested<Any>` | Caller-selected physical argument versus one separately compiled nested MethodDef |
| Mutable replacement and mixed constructions | Future legal writes, not specialization from the initializer |
| Already allocated C# narrow box | Frozen field and outward signature; cannot be retroactively broadened |
| Unchanged native CLR generic box | Retained foreign authority is distinct from a C# allocation of a Kotlin-owned box |
| Aliases, `Any`, stars and casts | Same box and element identity; explicit check strength and BK-1 scope |
| Nullable/value-class and open substitutions | Physical substitution; no remapping emitted signatures |

Run independently addressable cases so one failure does not hide later
observations. Freeze the same logical Kotlin sources for candidate and erased
runs. C# source may have explicitly recorded epoch-specific type syntax, but
must perform the same intended operation. Do not silently replace a natural
read with a reflective semantic read and call the contracts equivalent.

Use both FIR parsers, Framework 4.8 and .NET 10. Export artifacts per individual
parser/profile to avoid shared-path races. JVM/Common precedents and standalone
CLR models support the proof; they are not Kotlin/.NET cycle completion.

## General rule and hostile review

Before adding a storage fix, formulate one structural rule which predicts:

1. when a logical argument has a stable exact CLR carrier for its entire
   admitted value/write domain;
2. when a construction must select a broader physical argument and what its
   public C# signature becomes;
3. how `make<T>` and `makeNested<T>` compose without a consumer rewriting an
   existing generic MethodDef;
4. what happens to an existing narrower foreign construction; and
5. what runtime checks can truthfully know when logical arguments share a
   physical carrier.

Test the rule against cases not used to derive it. No `Nothing`, `Producer`,
`Box`, member-name, package, IR-origin or stdlib recognizer is a general rule.
Bottom is a counterexample, not a special exemption. `T + out bool` solves
nullness, not the capacity of an incompatible nested field.

Keep declaration-wide admission separate from construction-local precision.
Observing only `Box<int>` does not prove the open declaration safe. A constrained
construction grammar needs complete boundaries for separate generic calls,
legal writes and foreign allocations; otherwise retain the unsupported whole
declaration erased/unadmitted. Do not introduce a mixed production epoch.

An argument dictionary, witness or broader carrier must state its purpose,
lifespan, propagation and ABI cost. Extra metadata alone does not invalidate a
design, but accumulating special mappings without a compositional rule is
negative evidence. Do not add historical selection to all values to solve a
storage mismatch.

## Paired comparison

First establish correctness. A rejected/crashing candidate route is unavailable,
not a fast measurement. Compare actual compiler-generated candidate and erased
products using the same supported operations and workload weights. Publish
exclusions instead of giving a partial-overlap score as whole-target coverage.
Older hostile/hand-authored candidates and the reported semantic-route slowdown
motivate investigation; they do not measure the current architecture.

| Dimension | Evidence to retain |
| --- | --- |
| Runtime | Cold/startup and warmed distributions; direct, widened and mixed paths; effects/checksums |
| Allocation/boxing | Per-operation bytes, allocation API limits, retained storage and physical boxing sites |
| Code size | User CIL/native code and metadata; itemized Runtime/Stdlib/KLIB dependencies |
| Compilation | Kotlin frontend/backend, metadata, ILAsm and C# time/memory; warm/cold caches separated |
| Compiler complexity | Added/removed mechanisms, schemas, lowerings, runtime protocols and exceptional branches; LOC only supporting evidence |
| C# interoperability | Actual signatures, ordinary source required, reads/writes/overrides, adapters/reflection and excluded constructions |

Pin commits, source/artifact hashes, compiler/runtime versions, configuration,
iterations and machine conditions. Keep tracing out of timing runs. Alternate
order, repeat samples and report variation; one long Gradle invocation is not
compiler throughput. Reuse existing measurement machinery only after checking
that both sides are comparable Kotlin products. Separate setup/cache/toolchain
costs from compilation and execution.

Start with installed Framework and .NET 10 profiles; mark AOT, trimming and
ReadyToRun unmeasured until exercised. They remain mandatory before production.
Do not manufacture one percentage from coverage, speed and subjective complexity.

## Early strategic outcome

- **GO to further rehearsal:** a relatively small compositional rule, truthful
  public contracts and worthwhile measured benefits justify the selected census
  scope. This retains every final migration gate.
- **CONSTRAIN:** a mechanically proved subset provides useful typed state/calls
  and interop. State whether the boundary is declaration or construction based,
  prove its complete composition, and keep the remainder erased or unadmitted
  under explicit rules. Passing examples alone are not a grammar.
- **NO-GO for the broad replacement:** retain erased Kotlin-owned generics;
  evaluate native imports, private specialization and explicit C# export on
  their own merits. Preserve reusable correct work and evidence rather than
  automatically reverting every prior feature.

A fixed-field contradiction can reject a contract combination before measuring
it. The early choice must not require implementing every rejected alternative
or finishing the full stdlib census first. Conversely, one failed construction
does not prove all CLR generics unhelpful. If a useful cycle requires a new
public/semantic contract, present that concrete choice before integration;
until then record **unresolved**, not a manufactured GO.

## Exit

Publish the full cycle matrix, general rule and counterexamples, paired
measurements with limits, complexity/interop assessment, and a reasoned early
GO/CONSTRAIN/NO-GO recommendation. Record the user's decision in the owning ADR
before implementing a changed contract. Only then select further census or an
alternative workstream. Dated run details belong in the archive, not here.
