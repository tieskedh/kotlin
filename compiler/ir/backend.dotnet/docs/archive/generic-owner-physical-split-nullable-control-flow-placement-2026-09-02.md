# Generic-owner split-nullable control-flow placement — 2026-09-02

This archive records the first bounded retention of one exact natural
`SplitNullable` pair across a control-flow initializer in the generic-owner
rehearsal. It changes no physical-library ABI, artifact schema, Runtime/Stdlib
declaration, production representation, or Kotlin semantics.

## Boundary

An immutable logical `T?` local may now be initialized by a flat exhaustive
`IrWhen` with at least two statically reachable operation arms when every arm
independently produces the same verifier-visible layout:

```text
SplitNullable(P, out bool) join SplitNullable(P, out bool)
    = SplitNullable(P, out bool)
```

There is no common-carrier selector in this join. Different payloads, direct or
materialized nullable values, semantic routes, null, bottom, unknown layouts,
or any attempt to widen to `object` make the transfer unavailable. The existing
local and enclosing MethodDef rules still require `P` to be the same current-
owner parameter and every later read to be a bare, unprotected direct return.

Each branch retains its own complete final operation witness. Branches need not
derive authority from one shared call identity; no MethodDef, receiver,
parameter, or MethodSpec fact is inferred from the joined payload. Placement
folds the independently produced facts and grants one destination only when the
fold equals the shadow analysis result.

## Final-IR shape and late authority

FIR2IR represents a braced source arm as an `IrBlock` containing its one result
expression. A shared origin-independent extractor therefore accepts exactly:

```text
IrCall

or

IrBlock (not IrReturnableBlock)
    with exactly one IrCall statement
    and block.type == call.type
```

It does not recurse and does not accept composites, declarations, extra
statements, casts, nested control flow, `try`, null, throw/bottom, or a changed
type boundary. This prevents source formatting from changing the physical
result while keeping a real block semantic.

The final placement token stores the ordered exact `IrCall` identities and one
authoritative operation per call. Immediately before pair declaration, the
emitter reruns the shape predicate, requires the identical ordered call set,
binds every operation against the live physical owner, independently resolves
every live call, and repeats the existing exhaustive use and enclosing-result
checks. A structurally similar replacement call receives no authority.

## Pair-aware emission

The dedicated method-scope emitter declares one payload local and one private
Boolean local. Every selected arm calls its own natural MethodDef with the same
Boolean address and immediately stores the returned payload into the same
payload local. Every branch edge reaches the join with the original empty stack
and exception-region depth. The common return tail then copies the private flag
to the enclosing final `out bool` and returns the typed payload.

The ordinary expression `when` emitter is deliberately not reused: it promises
one stack value and would materialize the logical nullable result. This slice
introduces no tuple, wrapper, proxy, field, shadow state, boxing, `Nullable<T>`,
semantic-capability crossing, or fabricated CLR construction.

## Executable evidence

`genericOwnerInlineWidenedTemporary.kt` selects between two distinct exact call
identities through an ordinary braced source `if`. Both selector paths execute
for non-null and null `Int`, `String`, and `Int?` substitutions.

Candidate CIL contains one adjacent `!T`/Boolean pair, two natural
`read(bool&)` calls, the same flag address immediately before each call, the
same payload store immediately after each call, one branch join after both
stores, and one common typed return tail. It contains no representation-changing
instruction or logical nullable/semantic materializer. Operation-route evidence
contains two separately BOUND exact-natural calls.

The physical-value model additionally rejects a different split payload, a
direct layout, and a materialized null layout. Existing materialization,
protected-region, mutable, broad, star/projected, and erased-inverse controls
remain unchanged.

## Verification

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin :compiler:fir:fir2ir:compileTestKotlin --no-configuration-cache -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest" --no-configuration-cache -q
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
```

The physical-value model reported 92 tests with zero failures, errors, or
skips. Candidate and fresh production-erased inverse each passed four generated
test methods across FIR PSI, FIR LightTree, .NET 10, and Framework 4.8. The
inverse retained no candidate generic owner or pair flag.

## Next boundary

Close the remaining parameter-entry and general multi-input operation forms.
Null and flow-bottom split arms, non-return consumers, captures, fields, and
arbitrary MethodSpec carriers retain their independent later proof obligations.
