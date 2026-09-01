# Generic-owner MethodSpec split-nullable placement — 2026-09-02

This archive records the first bounded local transport of a MethodSpec-bearing
exact natural `SplitNullable` operation in the generic-owner rehearsal. It
changes no physical-library ABI, artifact schema, Runtime/Stdlib declaration,
production representation, or Kotlin semantics.

## Boundary

The operation model already composed a MethodDef binder, owner input, and
split-nullable owner output. Earlier pair placement deliberately rejected every
MethodSpec because instantiated carriers alone cannot prove the open MethodDef
signature: at one call site both declared `!K` and `!!R` may become the same
outer `!T`.

This closes the local-placement boundary left by the earlier
[strict-input pair](generic-owner-physical-split-nullable-strict-input-placement-2026-09-02.md),
[MethodSpec operation](generic-owner-physical-methodspec-operation-2026-09-01.md),
and
[MethodSpec/split composition](generic-owner-methodspec-split-nullable-composition-2026-09-01.md)
proofs. Those dated archives remain unchanged because their then-current
boundaries are historical evidence, not active architecture.

The new admitted form is exactly:

```text
logical declaration       = <R>(K, R): V?
final logical route       = EXACT_NATURAL
final physical route      = BOUND + DIRECT_NATURAL + MATCH
open ordinary parameters  = STRICT_OWNER_INPUT(!K),
                            DECLARATION_INDEPENDENT(!!0)
MethodDef parameter       = one fully unconstrained !!0
MethodSpec vector         = one bare current-owner !m
instantiated parameters   = current-owner !n, !m
result                    = SplitNullable(STRICT_OWNER_OUTPUT(!V), bool)
instantiated payload      = current-owner !p
logical local             = immutable T?
local uses                = one read, directly returned to this MethodDef
return region             = outside try/catch/finally
enclosing result          = identical SplitNullable(!p, bool)
```

The retained placement token contains the exact local MethodDef identity and
both layers of physical truth:

- the open declaring receiver, ordinary parameter vector, and split payload;
- the instantiated receiver, ordinary parameter vector, MethodSpec vector, and
  split payload.

The binder origins are authenticated independently. The first ordinary slot and
payload must come from the selected interface TypeDef; the second ordinary slot
must be exactly that MethodDef's `!!0`. The MethodSpec must bind it to a bare
parameter of the current physical owner. Equal instantiated carriers cannot
therefore hide a swapped or reinterpreted open signature.

The late emitter re-resolves the exact virtual MethodDef and validates its open
signature, generic arity, receiver construction, MethodSpec, instantiated
parameter vector, payload, hidden Boolean parameter, and direct live storage
reads. It passes only the compiler-private pair flag by address. Every other
generic shape—including caller-MethodDef, concrete, nullable, nested, foreign,
constrained, or multiple MethodSpec arguments—remains unavailable. Arbitrary
multi-input operations, semantic or `super` routes, computed values, mutation,
joins, captures, multiple reads, and protected returns still materialize.

## Executable evidence

`genericOwnerInlineWidenedTemporary.kt` reuses the structural
`InlineMethodLookup<K, out V>` contract inside a genuine split-result override.
The exact anonymous receiver is `InlineMethodLookup<T,T>`; immutable `!T` locals
supply both `K` and the method-generic marker, and the logical `T?` result is
forwarded through one retained payload/flag pair.

The isolated MethodDef contains the open operand:

```text
callvirt instance !1 class 'InlineMethodLookup`2'<!0, !0>::
    'lookup'<!0>(!0, !!0, bool&)
```

`!1` is the interface MethodDef's independent `V`; `!0` in the first parameter
is its `K`; `!!0` remains the open MethodDef parameter; and `<!0>` is the exact
MethodSpec which instantiates that parameter for this call. The immediately
preceding instruction is `ldloca` for the private Boolean local. The method has
distinct outer-`!0` payload and Boolean locals and contains no boxing,
`System.Nullable` materializer, or semantic-capability crossing.

Runtime coverage includes `T = Int`, `T = String`, and `T = Int?` with both
non-null and null results. The physical-value model also proves independent
TypeDef/MethodDef substitution and rejects a mismatched instantiated marker
carrier.

## Verification

```text
.\gradlew.bat :compiler:backend.dotnet:compileKotlin :compiler:fir:fir2ir:compileTestKotlin --no-configuration-cache -q
.\gradlew.bat :compiler:backend.dotnet:test --tests "org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalValueModelTest" --no-configuration-cache -q
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --rerun --tests "*testGenericOwnerInlineWidenedTemporary" --no-configuration-cache -q
```

The physical-value model reported 91 tests with zero failures, errors, or
skips. Candidate and fresh production-erased inverse each passed four test
methods across FIR PSI, FIR LightTree, .NET 10, and Framework 4.8. The inverse
published no candidate natural/capability owner, operation or placement
authority, route class, anonymous generic owner, or retained pair flag.

## Next boundary

Generalize pair use and storage independently: multiple consumers and
control-flow joins come before captures or fields. General multi-input and other
MethodSpec carriers need their own structural grammar. Do not advance the
Stdlib census or add a Map/member/package/IR-origin recognizer for any of them.
