# Constructor bodies and retained interface-view selection

Reviewed compiler base: `58fa1daa63cced3b6219d9e2a76cb2b6bf2cd279`.
This follows the [failed forwarding experiment](constructor-natural-view-loss-2026-09-12.md).
It records executable findings, not an accepted storage ABI or a completed
constructor feature. Production remains erased.

## Compiler experiment

The natural constructor was changed from a forwarder into a physical copy of
the logical constructor body. Shared Common initializer lowering and IR copying
were reused; no Common source changed. A local delegation could select the
already-created natural endpoint only when its complete actual physical input
vector fit the substituted natural signature. Logical argument types supplied
no such permission.

The hostile C# receiver implements both `Source<object>` and `Source<int>`.
The latter explicitly throws, so a wrong selection cannot silently pass.

1. Natural primary construction returned successfully. An immediate assertion
   verified its independent typed value and exactly one initializer call.
2. Natural `this(...)` delegation passed the corresponding value and call-count
   assertions. Ordinary C# subclass construction also returned.
3. A separately compiled Kotlin child still delegated to the parent's recorded
   object-domain `L` endpoint and failed with the original ambiguity. There is
   no authenticated external natural constructor endpoint yet.
4. Adding `StoredInput<T>(private val source: Source<T>)` exposed a separate
   problem. Early Common expansion had to be represented as explicit state
   writes, rather than also counted as a pending implicit initializer. The
   experiment recorded the source field/constructor/parameter relationship,
   consumed the field initializer through Common cleanup, checked exactly one
   write per paired physical constructor before BOUND, and retained the existing
   post-BOUND write-identity checks.
5. After that adjustment, construction completed but the later stored-value
   read failed:

```text
StoredInput<T>.read()
  -> StoredInput<T>.read__KotlinSemantic__...
  -> GenericInterfaceDispatch.InvokeRecordedMember
  -> A foreign Kotlin generic-interface view has multiple CLR constructions
```

Thus natural body execution fixes the direct entry, but cannot by itself retain
view selection after storing only the receiver in an object field. It is not
sufficient to add more constructor signatures or to remove the ambiguity check.
The expanded Kotlin `box()` and full constructor fixture are still red; their
later assertions must not be described as verified.

## Independent CLR storage probe

The [C# fixture](../../tools/fixtures/SelectedViewStorageProbe.cs) is hand-written
mechanism evidence, not Kotlin-generated code. An experimental value-type slot
holds one receiver reference plus a checked selected interface construction.
It does not implement the logical interface as a receiver proxy. The compiler
would have to generate boundary adaptation; ordinary foreign authors must not
be required to construct this compiler representation themselves.

Run it independently of compiler/Framework gates:

```powershell
./compiler/ir/backend.dotnet/tools/verify-selected-interface-view-storage.ps1 `
    -OutputDirectory D:/CodexTemp/selected-interface-view-storage-new-run
```

The [runner](../../tools/verify-selected-interface-view-storage.ps1) requires a
new output directory, freezes its source, compiles against actual net48 and
net10 reference assemblies with warnings as errors, checks both exit codes and
assertion output, and records source hashes, logs and installed runtimes.
It installs nothing and does not overwrite earlier evidence.

Both targets passed these single-threaded checks:

- the same receiver produces different results through its two selected views;
- selection survives storage, replacement and a conditional join;
- the stored receiver identities remain reference-equal;
- unrelated `Store<int>` and `Store<string>` fields retain their exact types;
- native reference covariance remains valid and identity-preserving;
- no additional receiver/proxy object is introduced; and
- a selector cannot fabricate `ISource<object>` for an `ISource<int>`-only
  object, or claim an unimplemented `ISource<long>` view.

The net48 executable ran on the installed serviced CLR4 runtime: registry
version `4.8.09221`, release `533509`, mscorlib file version `4.8.9345.0`
(`NET481REL1LAST_25H2_C`). That is not a measurement on an original unserviced
Framework 4.8 runtime. The modern toolchain was SDK `10.0.100`, net10 reference
pack `10.0.0`, and runtime `10.0.9`.

Evidence is in `D:\CodexTemp\selected-interface-view-storage-20260912-final`.
The runner's existing-directory rejection was also tested; the prior evidence
hash remained unchanged. Both executables report their actual runtime version.
This is not a performance/allocation, concurrency/volatile, trimming/NativeAOT,
Kotlin identity-lowering, separate-assembly transport, or ABI-readiness proof.
In particular, a multiword value-type field does not acquire atomic-reference
write semantics merely because its single-threaded tests pass.

## Architectural boundary

The compiler must preserve information that still affects future dispatch.
Two different selected views can contain the very same receiver reference;
keeping that reference alone cannot distinguish them later. Direct natural
execution avoids unnecessary information loss, but truly semantic storage and
calls need either retained selection or a specifically justified rejection.

A compound compiler-owned storage layout is a candidate, not an implicit
exception to the current no-wrapper/no-shadow-state rules. Its permission and
meaning must be settled in the owning physical-authority ADR before integration.
It would need to keep one authoritative receiver value, preserve Kotlin `===`
including `Any` conversions, retain natural foreign authoring, and leave
independently proven typed fields unchanged. Selector validity must come from
an existing physical view, never from the selector itself.

Before promotion, close mutable/volatile state, nulls, casts and loss of static
lineage at joins, nested generic storage, foreign overrides, helper/result
transport, exact external constructor endpoints, both profiles, AOT/trimming,
and the erased inverse. Do not freeze duplicated constructor bodies as the
general solution to a storage-information problem.

## Recovery

The nine-file compiler experiment is retained in stash
`8f3289fda4ca861b0cc55eef6079bcfecf0db20d`, based on the reviewed compiler base.
The prior forwarding stash is unchanged. Compiler/test sources were restored
before publishing this independent proof. The provisional ADR in the stash
still describes the earlier forwarder and must not be promoted verbatim.

`D:\CodexTemp\generic-owner-constructor-pair-20260912` contains
`natural-body-first.xml`, `natural-local-delegation.xml`,
`natural-stored-input.xml`, `materialized-stored-input.xml`,
`consumed-stored-input.xml`, and the detailed investigation checkpoint.
The constructor-materialization changes have not passed their full negative,
model or inverse gates. The previous full compiler checkpoint is inherited
unchanged; the standalone proof does not increase its test total.
