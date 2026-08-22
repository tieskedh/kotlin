# Reified generic-interface exact-input materialization

Date: 2026-08-22

## Question

Can the producer-recorded invariant sibling for a covariant Kotlin interface
become a real Kotlin-emitted CLR TypeDef without erasing ordinary owner state,
recursively contaminating nested generic signatures, or requiring authored C#
to implement compiler ABI methods?

The structural test family combines a direct `T` producer, an owner-independent
query, a constructed producer result, and a nested `@UnsafeVariance` input. It
therefore exercises the same physical conflict as a collection-shaped owner
without selecting `Collection`, `Set`, a package, or a declaration name.

## Physical family

The rehearsal epoch now emits three sibling views:

1. the natural covariant `I<out T>`, containing every CLR-legal producer and
   owner-independent member;
2. one producer-recorded invariant `I__KotlinExact<T>`, inheriting the natural
   view and owning only the input member which is illegal under CLR variance;
   and
3. the non-generic semantic capability used when Kotlin has a legal view which
   cannot be named as a constructed CLR interface.

The exact member's nested parameter remains the natural `I<T>`. Substituting
the exact sibling recursively would spread a compiler representation detail
through user signatures and create the generic contagion this design is meant
to avoid. Consumers bind the exact declaring TypeDef from ABI 45 and retain the
producer-recorded natural nested signature.

A generic Kotlin implementation supplies all three views on one object. Its
producer-proven field remains physical `!T`; there is no shadow field, wrapper,
or global object-state fallback. The constructed result and hostile nested
input use object-domain semantic slots only at their required boundaries.
Planning now observes inherited broad-input interface contracts, so a generic
class body is not incorrectly compiled as an exact-only body. Final signature
routing treats natural interface methods, exact MethodImpls, and their typed
parameters as authoritative and cannot later degrade them to `object`.

## Separate compilation and C# authoring

Library publication accepts a reified interface's full-arity natural owner and
persists its generic-owner contract. A separate consumer reconstructs both the
natural and exact declaring owners from producer data and accepts an instance
MethodDef on either recorded owner; arbitrary alternate owners still fail
closed.

The C# implementation manifest records the exact owner and selects the natural
or exact authoring slot independently for each member. The supported Roslyn
generator adds the invariant exact interface and the non-generic semantic
capability to a partial class. Authored C# declares only the natural Kotlin
interface and ordinary typed members. It does not name `__KotlinExact`, a
semantic capability, or a generated bridge.

The focused proof compiles `lib -> middle -> main` separately. Kotlin verifies
one object identity, a truthful constructed result, a typed exact nested input,
an incompatible widened nested input reaching the semantic body, and primitive
queries. A separately compiled C# consumer verifies ordinary typed calls,
Kotlin semantic dispatch through generated adapters, CLR reference covariance,
an exact value-type construction, and unchanged identity on Framework 4.8 and
.NET 10. The pre-existing full foreign-override C# rehearsal remains green on
both profiles.

## Verification

All four candidate lanes are green: PSI and LightTree on Framework 4.8 and
.NET 10. The existing foreign-override C# rehearsal remains green on both
profiles, and the focused C# manifest corruption test is green.

The first full aggregate found that a newly added external-stub origin guard
was too strict for cross-module inline IR. Deserialized inline bodies correctly
retain origins such as default accessor, synthetic accessor, and lambda rather
than becoming `IR_EXTERNAL_DECLARATION_STUB`; their physical callees still come
from the producer ABI. Local emitted functions already take precedence in the
local function table, so removing that redundant guard restores all three
affected inline/coroutine integration tests without weakening local binding.
Those three tests and all four exact-interface candidate lanes were rerun
before the final aggregate.

The final complete `dotNetTest` aggregate exits zero. Direct XML audit reports
191 suites and 2,293 tests with zero failures, errors, or skips: 187 FIR suites/
2,159 tests, two integration suites/127 tests, one backend resolver test, and
the unchanged six-test `dotnet.ir` root.

## Boundary and next gate

This does not migrate the Runtime/Stdlib collection graph. Built-in generic
interface mappings remain explicitly gated until the general family is closed.
Production emission also remains in the erased epoch.

Generated partial C# authoring is now supported, but a precompiled or non-
partial C# class which implements only the natural covariant interface cannot
be assumed to provide the exact input member or an arbitrary Kotlin semantic
body. The next interop gate must decide and prove its truthful public/export
presentation and fallback behavior before `Collection<T>` or `Set<T>` is
admitted.
