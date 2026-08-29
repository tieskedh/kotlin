# External Kotlin class MethodDef authority checkpoint (2026-08-29)

This archive record preserves the bounded rehearsal checkpoint which publishes
one separately compiled Kotlin implementation-class MethodDef as physical
base-slot authority. It is not retained foreign CLR authority, a general class-
owner grammar, or a production generic-owner cutover.

## Authority split

Physical library ABI 64 adds the standalone `M` record. The ABI-63 `N` record
remains the declaration authority for the natural-interface MethodDef. `M`
independently seals:

- the logical natural-interface member, implementation owner, and
  implementation member identities;
- the final public open generic implementation TypeDef and its invariant,
  unconstrained owner binder;
- the exact direct construction through which that class implements the `N`
  owner; and
- the final public open implementation MethodDef, including its complete
  physical signature and direct or split-nullable result layout.

`M` is projected from final-emission observations selected by exact pre-lowering
declaration identities. KLIB substitution, method name/arity, a later consumer
type, or the optional complete semantic-family `J` record cannot create or
rewrite it. `M` claims neither a MethodImpl nor a semantic capability family.

Generic-owner artifact schema remains 21 and compiler-runtime surface level
remains 60.

## Complete library-index join

The codec admits `M` only when the same producer index establishes:

- exact implementation owner agreement with the ordinary `C` record;
- exact implementation MethodDef identity agreement with the ordinary `F`
  record;
- exact natural member, MethodDef name, owner construction, parameter carriers,
  and result-layout agreement with `N`; and
- no implementation-level `J` claim on the same implementation owner/member or
  physical MethodDef; the bounded `J` and `M` owner grammars are disjoint.

Orphan, cross-wired, duplicate-claim, current-compilation-relative, fabricated-
construction, and signature-mismatched records fail closed. The interface
construction may contain only parameters of the recorded implementation-class
binder; it is never reconstructed as an apparent `I<object>` view.

## Producer-final and consumer-PE validation

The producer admits the bounded record only for a top-level public,
non-abstract, non-sealed generic class with invariant unconstrained parameters,
an ordinary public virtual non-final non-abstract instance hidebysig MethodDef
with no specialname/runtime-specialname flags or method-generic parameters, and
one exact direct construction of the selected `N` owner. Multiple candidate
`N` roots or constructions are unavailable. Final emission must contain exactly
one matching TypeDef and MethodDef in one scope and no MethodImpl whose body is
the recorded method.

Before exposing `M` to a separate consumer, the dependency loader validates the
class TypeDef, GenericParam rows and constraints, complete MethodDef signature
and flags, split-nullable `[out] bool&` parameter row where applicable, and the
exact constructed InterfaceImpl against the producer DLL. Objective MethodImpl
rows are retained as MethodDefOrRef endpoints; validation rejects redirection
of either the recorded class body or the selected natural-interface
construction, including structurally equivalent MemberRefs. Positive explicit
MethodImpl authority remains outside `M`.

The text codec rejects oversized physical fields, arities, collections,
recursive depth, and aggregate type-node counts before unbounded allocation or
recursion. Exact same-module `TypeRef` aliases are canonicalized through
signatures, `TypeSpec` constructions, InterfaceImpls, and MethodImpl endpoints;
cyclic or over-deep local scope chains reject the record, while foreign and
same-name near misses remain non-authoritative.

## Downstream override proof

The hostile producer declares:

```kotlin
open class ExternalSplitBase<T>(private val base: T) : NullableSource<T> {
    override fun read(missing: Boolean): T? = if (missing) null else base
    open fun read(index: Int): T? = if (index < 0) null else base
}
```

The separately compiled consumer overrides the Boolean member while also
implementing a memberless child of `NullableSource<Int>`. It must bind the
producer-recorded class MethodDef before bridge planning and retain:

```text
!T read(bool, [out] bool&)
```

The same-name, same-regular-arity `read(Int)` method is a hostile decoy.
Producer PE validation, downstream IL, reflection `GetBaseDefinition`, the
natural-interface map, exact and widened Kotlin calls, stored-null behavior,
and `===` establish one ordinary implicit CLR override chain. No class/root
MethodImpl, private adapter, wrapper, proxy, shadow state, or fabricated
construction repairs the representation.

## Production-erased inverse

Without the rehearsal property, the same source retains the accepted erased
Kotlin-owned owner ABI. `NullableSource`, `OpenChild`, and `ExternalSplitBase`
remain arity-zero physical TypeDefs, the class methods retain their erased
one-parameter shapes, and the library index publishes no `H`, `N`, `M`, or `J`
record. Production Kotlin semantics and object identity remain unchanged.

## Verification

The exact figures below come from direct JUnit XML audits.

- Focused backend authority/codec/metadata tests: 2 suites, 36 tests, zero
  failures, errors, or skips.
- Candidate PSI/LightTree x Framework 4.8/.NET 10 matrix: 4 suites, 4 tests,
  zero failures, errors, or skips.
- Production-erased inverse over the same matrix: 4 suites, 4 tests, zero
  failures, errors, or skips.
- Fresh unqualified production-erased target aggregate: 204 suites, 2,583
  tests, zero failures, errors, or skips (backend 14/198, `dotnet.ir` 1/6,
  FIR2IR 187/2,251, integration 2/128). The backend and FIR Test tasks were
  explicitly rerun after the focused filters before the aggregate audit.
- Promoted checkpoint identity: the feature commit containing this record; Git
  supplies its immutable hash.

Candidate command:

```text
.\gradlew.bat "-Pkotlin.dotnet.genericOwnerRehearsal=true" :compiler:fir:fir2ir:dotNetTest --tests "*testGenericOwnerSplitNullableResultSeparateCompilation" -q
```

Production-erased inverse command:

```text
.\gradlew.bat :compiler:fir:fir2ir:dotNetTest --tests "*testGenericOwnerSplitNullableResultSeparateCompilation" -q
```

Full aggregate command:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest -q
```

## Remaining boundary

This checkpoint proves only one implicit exact construction on a public open
unconstrained invariant generic Kotlin class. Deeper base chains, multiple or
distinct natural constructions, constraints, method-generics, explicit
MethodImpls, general non-split callable forms, retained foreign CLR authority,
trimming, NativeAOT, the complete Runtime/Stdlib selected family, and atomic
rollback remain open. Production Kotlin-owned generic classes and interfaces
remain erased.
