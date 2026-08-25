# Closed non-generic semantic-input bridge

Date: 2026-08-25

## Context

After physical owner-arity closure, the source-built Stdlib rehearsal reached
its normal conversion census. A closed non-generic implementation of a
covariant interface received the declaration-semantic nested input, but its
canonical bridge then called the natural source member and inserted an invalid
conversion from the semantic carrier to one closed CLR construction. The first
observed instance was `SuppressedExceptionList.containsAll`, but the failure is
a representation-composition problem rather than a List or Throwable rule.

## Decision

A directly declared body in a physically final, non-generic class may receive
one compiler-owned object-input twin when all of these facts hold:

- exactly one regular parameter differs between the canonical interface slot
  and the natural implementation member;
- that parameter contains an admitted generic-interface application;
- the result is unchanged;
- the member has no defaults, varargs, method type parameters, property role,
  or upstream fixed wrong-shape barrier; and
- the generic-owner rehearsal epoch is enabled.

The natural MethodDef and its copied source body remain the exact Kotlin and C#
path. The canonical/capability bridge targets the object-input twin and builds
its argument against that physical object parameter, so it never first
reconstructs the closed natural construction. The existing local paired-entry
materializer is shared instead of duplicating its body-copy, provenance, or
publication rules. Non-final/open families remain outside this checkpoint.

## Proof and result

`genericOwnerClosedSemanticInputBridge.kt` is a name-independent separate-
compilation proof. A custom covariant owner has a constructed result, a nested
semantic input, and a primitive query. Its private closed String
implementation proves exact dispatch, widened dispatch, receiver and argument
identity, and matching/mismatching nested inputs. All four rehearsal lanes and
all four production-erased inverse lanes pass across PSI, LightTree, Framework
4.8, and .NET 10.

The actual source-built Stdlib rehearsal no longer reports either
`SuppressedExceptionList` or its dependent iterator. Its first remaining
conversion blocker is the independent open generic-owner self-view in
`AbstractMutableList.indexOf`. The final full target aggregate exits zero.
Direct XML audit covers 187 FIR suites/2,215 tests, two integration suites/127
tests, one backend suite/three tests, and one `dotnet.ir` suite/six tests: 191
suites/2,351 tests total, with zero failures, errors, or skips.

## Boundary

This does not authorize a semantic-to-natural CLR cast, a collection-specific
fallback, or a copied semantic body for an overridable family. It does not
close the later generic-owner self-view, mutable Map.Entry, Nothing-result, or
Map construction blockers from the source-product census. Production remains
erased until the atomic switch.
