# Draft ADR: explicit CLR property exports

## Status

Draft for the `dotnet` POC branch. This records a representation experiment, not a public Kotlin
annotation or a stable command-line contract.

## Context

The backend already emits ordinary top-level Kotlin properties as static accessor methods plus a
CLR `.property` row on the file facade. That Kotlin-facing surface is not sufficient for an
intentional CLR export when the property contains a Kotlin function type: its ordinary accessor
uses erased `Kotlin.FunctionN`, while a C# consumer needs a typed delegate. An export also needs an
owner for CLR naming and explicit nullable-reference metadata.

The overloaded-function selector is frozen at its current POC scope. Property evaluation must not
extend that selector's textual type grammar or turn it into a replacement annotation language.

## Decision

Use the separate repeatable provisional option:

```text
-Xdotnet-export-property=<kotlin-fq-name>=<clr-property-name>
```

It selects exactly one top-level property by fully qualified name. It has no receiver, parameter,
return-type, generic-constraint, or annotation grammar. The selector is consumed only during the
current compilation; none of its text enters metadata or `Kotlin.Runtime`. It exists only so the
POC can exercise property representation before a declaration-bound export annotation is chosen.

The durable output shape is a real static CLR property on the declaration's existing file facade:

```text
.method public hidebysig specialname static T get_Name()
.method public hidebysig specialname static void set_Name(T value)
.property T Name() { .get ... .set ... }
```

CLR properties do not carry a static flag themselves; the static accessor references establish
the shape. The original Kotlin property, backing state, accessors, and metadata remain unchanged.
The exported accessors are wrappers that call the originals. A `val` has only a getter. A `var`
gets an exported setter only when its Kotlin setter is public, so a public property with a private
setter becomes read-only through the explicit alias.

Ordinary property types retain their mapped CLR representation. Function0/1/2 property types use
the established typed Func/Action boundary: getters project the canonical erased `FunctionN`
object, and setters adapt a delegate back to it. The same runtime-owned adapters and same-shape
delegate round-trip identity rules apply; property export introduces no callable representation.

Nullable-reference flags are emitted explicitly in Roslyn's preorder encoding on all three
metadata owners that describe the property contract:

- the `.property` row;
- the exported getter return parameter; and
- the exported setter value parameter, when present.

The backend does not use `NullableContextAttribute` compression. Value-only properties need no
nullable metadata and do not cause `NullableAttribute` synthesis or reservation.

All facade member identities are checked before output. An export fails as a whole when its CLR
property name, `get_`/`set_` methods, or cross-kind member name collides with an existing property,
const field, function, or another requested export. Function exports now perform the reciprocal
property/const-field check. The backend never emits a facade that merely satisfies ILAsm while
remaining ambiguous or unusable from C#.

## Deliberately excluded

- Extension properties: their receiver requires a separate decision between CLR indexer metadata
  and named methods.
- `const val`: it already has CLR literal-field semantics, and wrapping it as a property would
  conflate two host contracts.
- Non-public properties and non-public getters.
- Delegated, `lateinit`, generic, or otherwise unsupported properties inherited from the ordinary
  backend shape gate.
- Member properties, automatic whole-module export, and a permanent source annotation.
- Any further textual selector grammar.

## Evidence

`ilText/propertyExports.kt` pins mutable primitive and nullable properties, callable projection
and adaptation, getter-only `val`, private-setter omission, `specialname` accessors, and property-
row/accessor nullable attributes under both FIR parsers. The generated IL assembles with modern
10.0.9 and .NET Framework 4.8 ILAsm.

Probe `propertyexport_s1` uses a Roslyn 5.6.0 consumer on CoreCLR and .NET Framework. It executes
reads/writes, a nullable round trip, callable invocation, original-delegate recovery, and
reflection of the getter-only restricted property. Reflection also observes the expected
`NullableAttribute` on the property row, getter return, and setter value. CLI fixtures pin the
minimal option, extension-property rejection, and the reciprocal function/property collision.

## Consequences

The POC can now validate ordinary and callable CLR property semantics without committing to the
future Kotlin annotation design. The cost is one additional explicitly provisional option and
wrapper accessor pair per export. The option has reached its intended maximum scope: future work
may extend the durable property representation, but must not grow this selection language.
