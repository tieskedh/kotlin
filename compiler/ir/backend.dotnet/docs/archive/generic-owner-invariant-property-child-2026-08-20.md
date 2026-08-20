# Generic-owner invariant property child

Date: 2026-08-20

## Outcome

The test-only CLR-generic-owner epoch now composes the exact mutable invariant
property family across one interface-inheritance edge:

```kotlin
interface Parent<T> {
    var parent: T
}

interface Child<T> : Parent<T> {
    var child: T
}
```

The natural physical contract is `Child<T> : Parent<T>`. `Child<T>` owns one
real mutable CLR `Property<T>` row for `child`; it inherits the `parent`
property and does not copy that row, its semantic slots, or implementation
state. A Kotlin implementation stores the two source properties in two
physical `!T` fields.

## Structural boundary

Admission remains structural and fail-closed. Both interfaces are public,
top-level, single-parameter invariant interfaces. The parent must be the
already admitted exact mutable-property root. The child must have exactly one
direct `Parent<T>` edge with an invariant use of its own unbounded `T` and must
declare exactly one additional abstract public mutable property whose getter
and setter use that same non-null `T`.

This proof deliberately excludes deeper inheritance, multiple parents,
changed arguments or variance, read-only or nullable properties, multiple
properties at one level, mixed method/property members, constraints, and
additional type parameters. An unadmitted child prevents the dependent family
from becoming a misleading partially reified product.

FIR represents an inherited property with a fake-override `IrProperty` in the
child's declaration list. Admission and family materialization now define a
declared property by at least one non-fake accessor. The fake property can
therefore neither make a valid child look multi-property nor become copied
child ABI.

## Natural and semantic paths

Exact and open paths use the natural CLR hierarchy throughout:

```text
Child<!!T> : Parent<!!T>
Box<Child<!!T>>
```

Star/output reads and input-projected writes select the compiler semantic
capability only at the individual parent or child operation. The child
capability owns exactly its child getter and setter slots and inherits the
parent capability; it contains no CLR Property row. A materialized
`Box<Child<out Any?>>` selects `Box<object>` for that projected construction
only. `Box<T>` retains its `!T` field, open/exact child nesting stays typed,
and the projected box can replace a String child with an Int child without a
wrapper, shadow state, or changed object identity.

## Ordinary C# implementation

Same-module and separate-compilation probes compile ordinary non-partial C#
classes implementing only `Child<string>` or `Child<object>`. Each class
defines two normal auto-properties and no compiler capability. Exact C# calls
and Kotlin projected parent/child reads and writes reach those natural
properties. The object construction proves that the projected input path is
not accidentally restricted to the string happy path.

Reflection and the authoring manifest prove that `Child<T>` directly names
only `Parent<T>`, owns only the child Property row, and publishes only the two
child accessor records in the producer assembly. In the separate product the
child capability has exactly one parent capability and two declared semantic
methods; the parent capability retains its own two methods. The Kotlin value
class has two and only two `!T` backing fields.

## Evidence

The fail-first separate-compilation probe rejected the child because the
external parent still mapped to a non-generic carrier. The first structural
implementation then exposed FIR's inherited fake `IrProperty`; filtering by
non-fake accessors repaired composition without a declaration-name exception.

PSI and LightTree execute the direct and separate fixtures on .NET Framework
4.8 and .NET 10: eight tests with zero failures, errors, or skips. The
production epoch-off inverse executes the same eight tests with zero failures,
errors, or skips. The final normal production aggregate directly audits 190
XML suites and 2,287 tests with zero failures, errors, or skips: 187 FIR
suites/2,155 tests and two integration suites/126 tests were freshly written,
while the unchanged six-test `dotnet.ir` model root remained Gradle up-to-date.

## Remaining boundary

This closes one exact invariant-property inheritance edge, not generic
interface inheritance as a whole. The next gates remain deeper chains,
input-bearing or mixed children, defaults, multiple properties/members,
changed type arguments, constraints, multiple type parameters, classifier-
derived fields, and the Runtime/Stdlib owner graph.
