# Generic-owner invariant property cell

Date: 2026-08-20

## Outcome

The test-only CLR-generic-owner epoch now admits the property-syntax equivalent
of the proven mutable invariant method family:

```kotlin
interface InvariantPropertyCell<T> {
    var value: T
}
```

The normal physical contract is one natural invariant CLR
`InvariantPropertyCell<T>` with one real mutable CLR `Property` row. Its getter
returns `!T` and its setter accepts `!T`. An exact Kotlin implementation retains
one physical `!T` backing field. Property syntax does not force the interface,
its implementation state, or an enclosing exact generic construction into the
object domain.

## Structural admission

Admission is deliberately structural and narrow. The owner must be a public
top-level interface with one unbounded invariant parameter and exactly one
abstract public mutable property whose getter result and setter value are that
non-null owner parameter. The getter and setter must both point back to that
same property declaration.

Negative controls keep the following owners on the erased production ABI:

- a read-only `val T` property;
- an explicitly open-nullable `var T?` property; and
- a mutable property combined with an unrelated abstract member.

This is not a library-name or standard-library exception. Defaults, overloads,
constraints, inheritance, multiple properties, mixed members, and broader type
parameter shapes remain unadmitted until separately proven.

## Natural and semantic paths

Exact and open access remains ordinary CLR-generic property access:

```text
InvariantPropertyCell<!!T>.value
Box<InvariantPropertyCell<!!T>>
```

Kotlin views which cannot name one honest invariant CLR construction use the
existing operation-local semantic boundary:

```text
InvariantPropertyCell<*>.value                 object -> object
InvariantPropertyCell<out Any?>.value          object -> object
InvariantPropertyCell<in String>.value = text  (object, string) -> void
```

A Kotlin-emitted object uses its compiler capability. An ordinary foreign
object selects exactly one closed natural interface construction and invokes
the recorded getter or setter MethodDef. Missing and multiply constructed
foreign objects fail deterministically, and an exception from the selected
property accessor is unwrapped and rethrown. The feature reuses Runtime surface
40's generalized zero-or-one-argument dispatcher; it introduces no runtime
surface change.

Materializing `InvariantPropertyCell<out Any?>` in a box selects `Box<object>`
for that projected construction only. The open `Box<T>` still has one `!T`
field, and exact/open `Box<InvariantPropertyCell<!!T>>` remains typed. Kotlin
may replace a String property cell with an Int property cell through the broad
projected box without a wrapper, shadow state, or changed object identity.

## Ordinary C# implementation

Same-module and separate-compilation probes use ordinary non-partial C# classes
which implement only:

```csharp
InvariantPropertyCell<string>
InvariantPropertyCell<object>
```

with an ordinary C# auto-property. Exact C# access and Kotlin star/output reads
and input writes all reach that natural property and preserve the foreign
object identity. The C# authoring manifest records two logical members, one
getter and one setter, whose natural slots share the same property name while
their semantic slots remain compiler methods without Property rows. The
authoring tool skips capability generation only after proving that complete
shape; broader contracts remain fail-closed.

## Evidence

The fail-first probe rejected `InvariantPropertyCell<string>` as a non-generic
CLR type on both Framework 4.8 and .NET 10. After admission, reflection proves:

- invariant CLR generic metadata and one mutable `Property<T>` row;
- typed getter/setter MethodDefs and a `!T` implementation backing field;
- exact/open calls and nested generic signatures;
- object boundaries only for projected getter/setter operations and projected
  nested construction;
- same-module and separate-KLIB Kotlin identity and mutation;
- ordinary non-partial C# string/object implementations;
- missing and ambiguous foreign-construction rejection plus accessor exception
  identity; and
- the exact property-shaped authoring manifest.

PSI and LightTree execute both fixtures on .NET Framework 4.8 and .NET 10:
eight tests with zero failures, errors, or skips. The production epoch-off
inverse executes the same eight tests with zero failures, errors, or skips.
The final normal production aggregate directly audits 190 XML suites and 2,287
tests with zero failures, errors, or skips: 187 FIR suites/2,155 tests and two
integration suites/126 tests were freshly written, while the unchanged six-
test `dotnet.ir` model root remained Gradle up-to-date.

## Remaining boundary

This proof does not admit read-only, nullable, defaulted, overloaded, inherited,
multi-property, or mixed method/property families. It also does not close
classifier-derived fields, broader input parameters, mixed/multiple type
parameters, constraints, or value-class substitutions. Those remain separate
whole-family gates.
