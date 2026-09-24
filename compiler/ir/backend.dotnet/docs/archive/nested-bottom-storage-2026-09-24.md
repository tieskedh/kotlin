# Coherent bottom values at nested generic storage boundaries

## Scope

Reviewed compiler checkpoint: `c4dcde568b63389fa1dd54700f5dfcf723c5ac41`.
These are test-only negative experiments against the existing separately
compiled generic-owner fixture. No compiler, Runtime/Stdlib, Common, schema,
natural C# expectation, or admission policy is changed. Production remains
erased. Neither a pair representation nor a conflicting-foreign dispatch
policy is involved.

The question is independent of the preceding successful
[coherent-operation feature](coherent-interface-bottom-dispatch-2026-09-24.md):
can the same legal Kotlin value enter a nested, physically typed field?

## Two isolated experiments

The existing library declares a mutable `RehearsalSeparateNestedBox<T>` with
one private `value: T`, a constructor, `read(): T`, and `write(T)`. Its closed
factory accepts `RehearsalSeparateProducer<String>` and returns a box of that
same logical element type. The normal control stores and reads a genuine
String producer. Existing C# consumers expect the natural
`NestedBox<Producer<string>>` surface; those expectations remain untouched.

Both experiments add a consumer-owned coherent Kotlin implementation of
`Producer<Nothing>`. Its method increments a counter and throws an existing
exception object. Widening it to `Producer<String>` is ordinary Kotlin
covariance, not an unchecked cast and not a BK-1 operation.

1. **Constructor:** pass the bottom view to the separately compiled factory.
2. **Later write:** first create and successfully read the normal exact box;
   alias that existing box and write the bottom view through the alias. This
   experiment does not first execute the failing constructor case. Restore the
   String producer after successful assertions so later existing controls are
   unchanged.

On success each experiment requires stored-receiver identity, zero producer
calls before dispatch, the original thrown exception, and exactly one call
after dispatch. Identity uses the existing non-inline, contract-free helper,
so it does not accidentally smartcast the subsequent operation to the concrete
bottom implementation. The allocation/write itself is deliberately uncaught.
Its failure must not be confused with the expected producer exception.

The minimal test deltas are preserved separately:

- [constructor.patch](nested-bottom-storage-2026-09-24/constructor.patch)
- [later-write.patch](nested-bottom-storage-2026-09-24/later-write.patch)

Apply either patch, not both, to the reviewed checkpoint's unchanged
`compiler/testData/codegen/dotnet/box/genericOwnerForeignOverrideSeparateCompilation.kt`.
They are archived reproducers, not failing additions to the active test corpus.
No generated runner change is required.
The patches use zero context; verify the base, then use
`git apply --check --unidiff-zero <patch>` before applying with `--unidiff-zero`.

## Observed physical boundary

The constructor candidate fails at runtime with `System.InvalidCastException`
from `RehearsalSeparateBottomProducer` to
`RehearsalSeparateProducer<string>`. Its first application frame is the
factory's object-input `__KotlinClassifierInput__` entry. The later-write
candidate fails with the same conversion in the consumer's `box()` body.

The emitted library and consumer CIL identify the cause. The inspected coherent
artifact set is the dedicated LightTree/Framework export under
`write-candidate-net48-lighttree-artifacts`, not the initial shared export:

```text
NestedBox<T>:
    field !0 value
    constructor(!0)
    !0 read()
    void write(!0)

factory's object-input entry:
    ldarg.0
    castclass Producer<string>
    newobj NestedBox<Producer<string>>::.ctor(!0)

later-write consumer:
    load existing NestedBox<Producer<string>>
    load bottom receiver
    castclass Producer<string>
    call NestedBox<Producer<string>>::write(!0)
```

The semantic box writer also ultimately uses `unbox.any !0` before calling
the natural writer. Selecting that capability instead would not enlarge the
fixed field's capacity. Removing the failing cast alone would not make the
store verifier-valid.

This is not an IL-assembly failure or an ambiguity between two implementations.
The failure prevents the subsequent counter assertions from running; the
negative XML alone is not an independent measurement of the counter. The
emitted failing conversion precedes the intended producer call.

## Verification and reproduction

Local raw evidence is under
`D:\CodexTemp\nested-bottom-storage-20260924`. Each XML result identifies its
parser and runtime. The constructor fixture SHA-256 is
`465BE361762960FD56415C49C62BCFB341A9435CD1DE17DBBC665F23BE2C7767`;
the later-write fixture SHA-256 is
`8860F4D27296A0C227412F7DBFA00CE11E28903892F1EAFD11E0562BBBED2AE9`.
These are captured Windows working-file hashes, not newline-independent Git
blob identities.

For each patch the focused candidate command is:

```text
.\gradlew.bat --no-parallel "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --tests "*testGenericOwnerForeignOverrideSeparateCompilation" -q
```

Omit the rehearsal property for the corresponding production-erased inverse.
Run serially against the shared test outputs. Copy each XML set before the
next filtered invocation replaces it. These experiments do not establish a new
full target gate; the 2,979-test production checkpoint remains inherited.

For CIL/assembly export, select **one parser/runtime test** and a fresh directory
using `-Pkotlin.dotnet.genericOwnerRehearsalDir=...`. The export helper uses
shared filenames, so one path across the four concurrently scheduled JUnit
cases is not a safe export matrix. `--no-parallel` controls Gradle tasks, not
that JUnit scheduling. The initial later-write run exposed exactly this race:
three runtime reproductions and one `FileAlreadyExistsException`. It is not
four runtime reproductions. The missing lane was rerun by itself:

```text
.\gradlew.bat --no-parallel "-Pkotlin.dotnet.genericOwnerRehearsal=true" "-Pkotlin.dotnet.genericOwnerRehearsalDir=D:/CodexTemp/nested-bottom-storage-20260924/write-candidate-net48-lighttree-artifacts" :compiler:fir:fir2ir:dotNetTest --tests "*FirLightTreeDotNetFrameworkBoxTestGenerated*testGenericOwnerForeignOverrideSeparateCompilation" -q
```

That execution also reproduces the write's `InvalidCastException`. Its XML is
under `write-candidate-net48-lighttree`; the failed export attempt remains under
`write-candidate-initial`. The source SHA-256 did not change between them.

| Probe | Candidate execution | Production-erased inverse |
| --- | --- | --- |
| Constructor | Four runtime cast failures, both parsers/profiles | Four pass, no failures/errors/skips |
| Later write | Three runtime cast failures plus the dedicated fourth reproduction | Four pass, no failures/errors/skips |

The constructor XML/source sets are `constructor-candidate` and
`constructor-erased`; the later-write inverse is under `write-erased`.
All executions completed on 2026-09-24. Both unchanged probes pass all four
erased parser/runtime cases, including identity, effects and the existing C#
controls. No unsuccessful or filtered invocation replaces the full checkpoint
in `STATUS.md`.

After preserving the source and XML, both experiments were removed from the
active fixture, which again matches the reviewed Git blob. Only this archive,
its reproducers, the index and current-status pointer are promoted. Local link,
patch-applicability and whitespace checks plus independent evidence review are
the documentation checkpoint's gate; no compiler implementation is promoted.

## Architectural conclusion, not a new contract

The current nested-argument mapper treats some closed reference covariance as
stable after excluding proper CLR value subtypes. `Producer<Nothing>` shows
why that negative test is not a proof of complete representability. A sound
exact initializer does not establish a sound mutable element domain.

An already allocated `Box<Producer<string>>` with an authoritative `!T` field
cannot both accept every Kotlin `Producer<String>` value and retain that
physical field/return contract. Reallocating the box, wrapping its element, or
adding a side store violates the existing identity/state requirements. The
same fixed-field limit applies to an ordinary C# allocation, although these
new compiler experiments allocate the box from Kotlin.

A fresh `Box<object>` could store the original bottom receiver with one `!T`
field, leaving `Box<int>` and `Box<string>` physically typed. That is a possible
construction strategy, not a view cast of the already allocated narrow box.
Its returned CLR type and separate generic factories must be truthful too.
The already supported broad nested constructions do not prove that every
closed natural C# result or existing foreign allocation can switch to them.

There is no evidence here that extra per-value selectors or universal runtime
logical-type tags are required. Physical argument information can be lost by
a broader mapping, but each cast/reflection contract must establish whether
that information is observable. BK-1 is a bounded check using physical facts,
not a promise of universal logical reification. A new per-argument erased
fallback law would still need an explicit decision; existing checked cases
must not silently weaken.

The existing class ADR permits withholding generic admission when complete
state obligations are unproved. That is a safe current boundary, not a solution
which retains every desired natural generic surface.

Before integration, the remaining material choice is the outward contract for
constructions whose Kotlin element domain exceeds the natural CLR argument:

- investigate a broader physical argument and honest broader public boundary
  for those constructions only, preserving unrelated typed constructions; or
- keep the narrow native container as a restricted interop view rather than
  promise unrestricted Kotlin mutation, with explicit adaptation/admission
  rules; or
- retain erased/unadmitted owners where neither complete contract is proved.

The first two need to compose: choosing a broad new allocation does not settle
the existing narrow foreign object. No choice is accepted by this archive.
The next proof must include C# allocation, Kotlin mutation, aliases across
assemblies, C# reads after mutation, open generic factories, and cast outcomes.
Changing test expectations alone cannot settle those user-visible contracts.

The owning boundaries remain the
[class-state ADR](../decisions/draft-adr-reified-generic-class-owner.md),
[physical-authority ADR](../decisions/draft-adr-generic-owner-physical-authority.md),
and [operation/storage decision boundary](../decisions/draft-adr-selected-interface-view-transport.md#operation-directed-investigation-boundary).
The source-built stdlib census stays paused. This is a reproduced storage gap,
not a rejection of truthful CLR generics, a performance measurement, or a
production cutover.
