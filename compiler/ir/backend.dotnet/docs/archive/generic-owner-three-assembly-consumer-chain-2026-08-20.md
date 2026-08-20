# Generic-owner three-assembly consumer chain

Date: 2026-08-20

## Outcome

The test-only CLR-generic-owner epoch now preserves the bounded invariant
consumer family across three Kotlin producer assemblies:

```text
lib.dll                 PropertyCell<T>
                              |
middle.dll              ConsumerChild<T>
                              |
leaf.dll                ConsumerGrandchild<T>
                              |
main                    Kotlin and ordinary C# consumers
```

Each DLL owns only its declaration. The natural CLR hierarchy remains
`ConsumerGrandchild<T> : ConsumerChild<T> : PropertyCell<T>`. The root owns
one mutable `Property<T>` row, each descendant owns one `Consume(!T)`
MethodDef, and the three non-generic semantic capabilities own respectively
two, one, and one declared methods.

## Fail-first boundary

The preceding depth-two proof placed both descendants in `middle.dll`.
Moving only the grandchild and its implementation to `leaf.dll` made all four
PSI/LightTree and Framework/.NET 10 rehearsal lanes fail before leaf emission.
The backend reported that the producer-recorded `ConsumerChild<T>` mapped to
a non-interface CLR carrier.

The local admission rule had reconstructed the first consumer edge from a
local `IrFile` declaration. An external KLIB declaration does not have that
local parent, even though its companion DLL records a complete natural
`I<T>` owner and semantic capability. Reapplying the local ownership test to
that declaration therefore discarded stronger producer evidence.

## Producer-recorded admission

The correction keeps local declaration analysis and external physical
evidence distinct. A local consumer parent must still satisfy the complete
structural property-root rule. An external parent is eligible only when all
of the following hold:

- KLIB retains the exact public invariant one-parameter consumer declaration;
- its direct type argument is the child's own non-null invariant `T`;
- its direct parent is the exact mutable invariant property root;
- both external owners have full-arity generic-owner physical declarations;
- the parent consumer and both root accessors have producer-recorded member
  families.

The check does not accept an arbitrary external reified interface and does
not inspect names, assembly names, C# source, call sites, or function bodies.
The same lowering-local external resolver supplies all checks, preserving its
fresh IR-key caches and the shared immutable declaration index.

## Identity, state, and C#

The Kotlin leaf implementation retains one physical `!T` field. Exact child
and grandchild calls plus projected secondary writes reach that same field.
Ordinary non-partial C# string/object grandchildren implement one property
and two natural methods; they name no capability and require no generated
adapter.

Reflection pins assembly ownership rather than merely observing compatible
signatures. The natural root, child, and grandchild TypeDefs live in `lib`,
`middle`, and `leaf` respectively. Their corresponding 2-to-1-to-1 capability
TypeDefs live in the same three assemblies. No inherited Property row,
MethodDef, capability, or state is copied downstream.

## Evidence

The enabled rehearsal passes four focused lanes: PSI and LightTree on .NET
Framework 4.8 and .NET 10. The epoch-off inverse passes the same four-module
source on those four lanes using the accepted erased production ABI. Both
matrices have zero failures, errors, or skips.

The final normal production aggregate audits 190 XML suites and 2,287 tests:
187 FIR suites/2,155 tests, two integration suites/126 tests, and one IR
suite/six tests, with zero failures, errors, or skips.

## Remaining boundary

This is a deployment proof for the already-bounded second consumer edge. It
does not recursively admit a third edge or generalize to broader/multiple
members, multiple parents, changed substitutions, defaults, constraints,
mixed variance, classifier-derived fields, or the Runtime/Stdlib graph. The
next consolidation should serialize one typed published-family contract with
the family kind, root/parent relation, identity parameter mapping, bounded
depth, declared roles, and capability binding. Local analysis and external
ABI decoding should then feed the same admission consumer instead of leaving
this proof as a conjunction of structural and physical-record predicates.
