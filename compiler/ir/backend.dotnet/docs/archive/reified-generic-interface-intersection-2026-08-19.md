# Reified generic-interface intersection (2026-08-19)

## Result

The test-only generic-owner rehearsal now preserves a transparent covariant
subinterface which intersects two independently reified producer families:

```text
Primary<out T>       Secondary<out T>
       \                 /
        \               /
         Child<out T>
```

All three logical interfaces remain natural CLR generic interfaces. `Child<T>`
implements both constructed parent interfaces and one non-generic, memberless
child capability. That capability inherits the two parent declaration-semantic
capabilities. It is an honest carrier for a widened `Child<T>` value, not a
wrapper, state owner, copied member family, or fabricated `Child<object>`.

Production remains on the accepted erased generic-interface ABI.

## General compiler rule

The transparent-child fixpoint now considers every direct interface parent.
Each edge must apply the child's sole covariant parameter invariantly to an
already admitted local or external reified producer. Physical capability
identities are deduplicated before the child decision:

- one semantic domain reuses its existing capability;
- multiple independent domains synthesize one memberless intersection
  capability; and
- an incomplete, member-declaring, projected, or otherwise unsupported child
  remains erased with the rest of its family.

The rule is based only on the interface graph and producer ABI. It contains no
Map, Set, Sequence, stdlib, package, or declaration-name branch.

## Separate compilation

For a child in assembly B over roots from assembly A, B's capability TypeDef
directly implements both assembly-qualified capabilities from A. ABI 38 already
records the complete physical identity of B's selected capability; no schema
change or consumer-side name inference is required.

A later assembly C reconstructs the memberless capability's supertype graph
from two authoritative inputs: the child's logical direct-interface graph in
the embedded KLIB and each parent's producer-recorded physical capability
identity. Reusing an existing single-parent capability is detected by physical
assembly and TypeDef identity and does not create a self-edge.

## Kotlin and C# evidence

The hostile corpus covers both physical construction paths:

- two roots and their intersecting child in assembly A; and
- two roots in assembly A with their intersecting child in assembly B.

Assembly C executes exact calls through both natural parent slots, widened
calls through both semantic roots, and same-object identity. Kotlin
implementations retain one `!T` value field. The emitted child capability
aliases contain zero methods and zero fields.

The producer manifests expose the natural child as the only C# source
contract. The supported Roslyn generator supplies both inherited semantic
bridges for ordinary partial C# implementations. C# authors implement only
`produce(): T` and `produceSecondary(): T`; Kotlin widened calls reach both
authored methods without source code naming either capability.

PSI and LightTree execute the rehearsal and the production inverse on .NET 10
and Framework 4.8. The focused matrix covers eight tests with zero failures,
errors, or skips. An exported .NET 10 product additionally verifies the exact
TypeDef edges, memberless aliases, `!T` state, and root-specific widened calls.

## Remaining boundary

The child is still transparent and member-free. A child which introduces a new
slot needs an authoritative combined member family rather than an inherited
alias alone. Inputs, defaults, properties, invariant/contravariant and
multi-parameter interfaces, hostile generic substitutions, Runtime/Stdlib
closure, non-partial/precompiled implementors, other CLR languages, trimming,
NativeAOT, and the eventual atomic production cutover remain separate gates.
