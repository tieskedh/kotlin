# Generic-owner post-representation covariant slots

Date: 2026-08-25

## Context

After the logical suspend-interface carrier was closed, the source-built
Stdlib rehearsal reached a repeated physical override mismatch. Unrelated
collection members, generated callables, and iterator-producing lambdas kept a
narrow natural result while the inherited physical MethodDef used `object`.

The logical Kotlin type after generic-owner rewriting was not sufficient to
reconstruct that inherited slot. In particular, an open nullable owner
parameter is emitted as `object` in its declaring class even when a derived
class later fixes the parameter to `String`. The synthetic `ExactFunctionN`
IR interfaces present a second instance of the same rule: they denote real
generic Runtime TypeDefs, but their closed capability construction can use
`object` for a nested Kotlin generic-interface result.

## Decision

Covariant-return closure treats the already selected physical MethodDef as
authority after representation lowering. An open nullable owner parameter is
kept in its declaration context while the bridge signature is formed; a leaf
substitution may not retroactively narrow the inherited CLR slot.

That rule is limited to a Kotlin-owned slot with no retained foreign CLR
MethodDef. A foreign reified declaration such as `EchoApi<T>.Echo(T)` remains
authoritative as its constructed `EchoApi<string>.Echo(string)` signature,
even when the Kotlin import view carries flexible/open nullable types. Treating
that logical `T?` as the Kotlin object carrier would manufacture an invalid
`object Echo(object)` MethodImpl against the foreign string slot.

`ExactFunctionN.InvokeExact` is admitted as an ordinary physical interface
slot. Its MethodImpl construction derives parameter and result arguments from
the typed target callable, then applies the canonical Runtime capability
carrier where the generic-owner rehearsal requires it. Only MethodImpl
declaration rendering receives the synthetic ExactFunction member mapping;
ordinary function references and calls retain their established resolution.
The capability rule is rehearsal-epoch guarded, so the production-erased
inverse cannot accidentally implement a different closed ExactFunction owner.

The original typed method remains the sole body and natural entry. The added
method is private, final, and forwards to that body with the required carrier
conversion. No body or state is copied, and no collection, callable, lambda,
package, or member name selects the rule.

## Executable proof

`covariantReturnPhysicalSlots.kt` combines two independent regressions:

- a generic lambda returning `Iterator<T>` requires its typed `InvokeExact`
  body plus an object-carrier Runtime MethodImpl; and
- `OpenNullableReturnBase<E>.readNullable(): E?` fixed to `String?` in a leaf
  requires an adapter for the declaration-stable inherited `object` slot.

PSI and LightTree execute both regressions on Framework 4.8 and .NET 10 under
the generic-owner rehearsal. The same four production-erased inverse lanes
remain green. Removing either physical rule reproduces its own exact missing-
MethodImpl diagnostic, so neither half is protected only by the other.

The existing foreign-interface integration proof implements
`OpenNullableEcho<String>` from Kotlin. Its retained CLR slot stays
`string Echo(string)` on both Framework 4.8 and .NET 10. Broadening the
declaration-carrier rule reproduces a Framework TypeLoadException whose exact
invalid MethodImpl is `object Echo(object)` against `EchoApi<string>`.

The final full target aggregate exits zero. Direct XML audit covers 191 suites
and 2,346 tests with no failures, errors, or skips: 187 FIR suites/2,211 tests,
two integration suites/127 tests, and the two-test backend resolver suite are
fresh; the unchanged six-test `dotnet.ir` root remains up-to-date.

The source-built Stdlib rehearsal passes the previous collection, callable,
and iterator-result mismatch group. It next rejects an erased class owner
whose emitted interface edge contains open `!0`/`!1` arguments even though the
class has no CLR GenericParams.

## Result and next boundary

Post-representation covariant slots now close against their actual declaring
carrier instead of a later logical substitution. This is correctness closure,
not a claim that every canonical capability construction is already optimal.
In particular, naturalizing stable closed nested callable results remains a
separate optimization proof; this checkpoint deliberately retains the current
universal capability construction.

The next source-product blocker is an illegal open reified-interface edge on
an erased physical owner, first observed as
`HashMapEntrySetBase : MutableSet<Entry<!0,!1>>`. An erased owner cannot emit
owner generic-parameter references it does not physically declare. Resolve
that edge from the physical owner representation without a HashMap, Set,
stdlib, or declaration-name exception.
