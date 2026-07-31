# ADR: Kotlin primitive scalar carriers

- Status: **Accepted — pre-ABI**
- Date: 2026-07-31
- Scope: primitive value representation, operations, boxing, nullability, generics, and CLR import

This is the selected direction for the experimental target. It is not a public KEEP or an
official Kotlin target commitment.

## Context and authority

Common Kotlin owns each primitive's range, conversions, operator result types, overflow, equality,
hashing, and rendering. Mature targets preserve those rules while using their native machine
carriers: JVM and Wasm normalize narrow integers on an `int`/`i32` evaluation stack, Native uses
native-width values, and JS inserts Kotlin narrowing semantics over its numeric carrier.

CLR already has exact signed `System.SByte` and `System.Int16` value types, spelled `int8` and
`int16` in CIL. Like JVM and Wasm, its evaluation stack represents their loaded values as `int32`.
This is a representation fact, not permission to change their metadata signatures into Kotlin
`Int`.

## Decision

Kotlin primitive values use the natural CLR value carrier in every profile:

| Kotlin | CIL signature | Boxed CLR type |
| --- | --- | --- |
| `Boolean` | `bool` | `System.Boolean` |
| `Byte` | `int8` | `System.SByte` |
| `Short` | `int16` | `System.Int16` |
| `Int` | `int32` | `System.Int32` |
| `Long` | `int64` | `System.Int64` |
| `Float` | `float32` | `System.Single` |
| `Double` | `float64` | `System.Double` |
| `Char` | `char` | `System.Char` |

The current implementation slice closes `Byte` and `Short`; `Float` remains a separate gate
because Kotlin floating equality, hashing, and shortest-roundtrip rendering require their own
adversarial closure. The table is the selected physical direction, not a claim that every row is
already implemented.

Fields, parameters, returns, generic arguments, generic-array elements, overloads, locals, and
nullable instantiations retain the exact carrier. `Byte?` and `Short?` are
`System.Nullable<int8>` and `System.Nullable<int16>` in typed positions and boxed scalar-or-null at
an `object` boundary, following the accepted hybrid-nullability decision.

Loaded narrow integers compute as signed `int32`, matching CLR stack rules. Common builtin
signatures still determine the logical result:

- arithmetic returning `Int` keeps the stack result as `int32`;
- `Byte`/`Short` results such as `inc`, `dec`, and explicit conversions narrow with `conv.i1` or
  `conv.i2` at the result boundary;
- integer overflow remains unchecked two's-complement behavior; `add.ovf` and other checked CIL
  operations are not Kotlin semantics; and
- comparisons sign-extend operands and return Common's `Int`/`Boolean` result.

Boxing uses `System.SByte`/`System.Int16`; equality and hashing therefore keep Kotlin numeric type
identity and signed value hashes. Kotlin string conversion uses invariant formatting rather than
the current CLR culture. CLR imports map exact `sbyte`/`short` metadata to these Kotlin builtins;
they do not synthesize user classes or silently widen the foreign signature to `Int`.

## Rejected alternatives

- **Spell Byte and Short as `int32` everywhere.** This collapses legal overloads, changes generic
  reification, nullable layout, reflection, boxed type identity, and the C# signature.
- **Use `uint8` for Byte.** Kotlin `Byte` is signed; `System.Byte` is not its carrier.
- **Introduce Kotlin-owned scalar wrapper classes.** CLR already provides exact interoperable
  value types. Wrappers would add allocation and identity without preserving extra Kotlin
  semantics.
- **Use checked arithmetic because CLR offers it.** Kotlin integer arithmetic wraps unless a
  library contract explicitly performs a checked operation.
- **Publish only the scalar overloads needed by one collection function.** Primitive support is a
  language/ABI capability. Collection generators may consume it only after the complete scalar
  boundary is green.

## Ownership and consequences

- Common/frontend owns primitive types and operator/conversion signatures.
- The backend owns exact CIL types, stack normalization, narrowing, boxing, and code generation.
- The runtime owns cross-assembly `Any?` equality/hash/string helpers.
- The CLR importer owns exact foreign primitive decoding.
- KLIB remains authoritative for Kotlin logical types; CLR metadata exposes the truthful physical
  and C# view.

No profile-specific scalar meaning is allowed. Specialized primitive arrays remain Kotlin-owned
wrappers under the separate primitive-array ADR, while generic `Array<Byte>` and `Array<Short>`
use natural `int8[]` and `int16[]` vectors.

## Freeze conditions

Before these carriers freeze, tests must cover:

- minimum, maximum, zero, sign extension, wraparound, conversions, and mixed arithmetic;
- distinct Byte/Short/Int overloads and cross-module physical signatures;
- fields, properties, generic classes/functions/interfaces, and generic arrays;
- nullable typed positions and boxed-or-null `Any?` boundaries;
- equality, hash, invariant string rendering, type tests, smart-cast recovery, and hostile
  widening cases (general explicit `as` lowering remains a separate backend gate);
- exact C# production/consumption and imported CLR `sbyte`/`short` declarations; and
- identical behavior on Framework CLR and CoreCLR, including portable libraries.

The current foreign-CLR FIR provider still admits only its deliberately closed primitive,
string/object, and reference-param-array grammar. A C# `Nullable<SByte>` or `Nullable<Int16>` is a
nominal generic instantiation rather than a primitive signature, so importing that shape remains
part of the separately gated foreign constructed-type slice. This does not affect emitted Kotlin
`Byte?`/`Short?`: those are exact `System.Nullable<System.SByte/System.Int16>` carriers and are
consumed directly by C# in this gate.
