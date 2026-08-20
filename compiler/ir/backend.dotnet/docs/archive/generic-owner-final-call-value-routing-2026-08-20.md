# Generic-owner final call/value routing

Date: 2026-08-20

## Outcome

The generic-owner rehearsal now separates early declaration-family authority
from final call/value routing. Early planning still fixes natural and semantic
TypeDefs, MethodDefs, override families, and capability slots before lowerings
which consume those declarations. A final router runs after every current
body-producing lowering which can introduce a generic operation and re-derives
only the concrete routes whose choice depends on the provenance of generated
receivers or values.

The final pass is deliberately monotone. It may add a proven capability or
foreign route, but it cannot create a declaration family, remove a planner
route, or replace a stronger exact-storage proof with a general semantic
fallback. Persistent exact-declaration facts and producer-published external
capability slots are shared between the two phases.

## Fail-first boundary

The local hostile proof wraps a logically widened `Producer<Int>` in a private
generic value class and invokes `produce()` after Common value-class lowering.
All four PSI/LightTree and Framework 4.8/.NET 10 lanes initially failed: the
generated reinterpret path lost the object/capability provenance and attempted
an invalid `Producer<object>` cast.

A second hostile wrapper carries a widened generic class whose physical value
is `Store<int>` but whose Kotlin view is `Store<Any?>`. Its first failures
located each generated identity in turn: the static constructor
implementation, property getter, logical reinterpret, unbox helper, and the
late `read()` call. The same proofs then failed across a producer/consumer DLL
boundary until the final scan consumed the producer's published class
capability rather than reconstructing `Store<object>`.

One attempted repair exposed a separate composition bug. Making external
class slots visible to the early interface pass let that pass remove a more
precise planner route for an external open nested return. External class slots
are consequently consumed only by final routing, and the final pass never
removes an earlier route. This makes the ordering invariant executable rather
than advisory.

## Architecture

The final scan visits call children before their consumer so a generated
getter or helper establishes carrier provenance before an enclosing member
call is classified. Generic value-class backing carriers flow through the
generated constructor implementation, trivial getter, compiler-owned box and
unbox helpers, and `REINTERPRET_CAST`. Exact logical reinterpretation emits no
CLR cast when the physical carriers are already identical.

The scan repeats until the sizes of its declaration-provenance and call-route
sets stop changing. This closes reverse declaration-order dependencies while
retaining an idempotent result. A semantic generic-class call which remains
without a published capability slot fails closed rather than falling back to
an emitter guess.

## Evidence

The enabled rehearsal passes eight focused local/separate lanes across PSI,
LightTree, .NET Framework 4.8, and .NET 10. Exported IL proves the local hostile
value-class field, constructor/getter/read implementations, and box/unbox
helpers retain the local class capability; the separate product retains the
producer-owned capability. Both call the semantic `read` capability and
neither contains a `Store<int> -> Store<object>` cast. The interface hostile
case retains its existing object carrier and dispatches through the published
interface capability.

The epoch-off inverse passes the same eight lanes with zero failures, errors,
or skips, proving that the production-erased ABI remains unchanged. The backend
compilation and `dotnet.ir` model suite also pass.

The final normal production aggregate directly audits 190 XML suites and
2,287 tests with zero failures, errors, or skips: 187 FIR suites/2,155 tests
and two integration suites/126 tests were freshly written, while the unchanged
six-test `dotnet.ir` model root remained up-to-date.

## Remaining boundary

This is a routing-completeness proof for the currently admitted rehearsal
families, not a production generic-owner switch or broader family admission.
Public value-class object-carrier ABI publication remains a separate gate; the
hostile wrappers are private so this slice does not silently extend it. Any
future body-producing lowering which can introduce a generic operation must
run before the final router or supply a new explicit completeness proof.
Defaults, broader input-bearing inheritance, mixed/multiple families,
constraints, and the atomic Runtime/Stdlib graph stay fail-closed.
