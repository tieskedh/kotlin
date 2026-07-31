# ADR: Kotlin primitive scalar carriers

- Status: **Accepted — pre-ABI**
- Date: 2026-07-31
- Scope: primitive value representation, operations, boxing, nullability, generics, and CLR import

This is the selected direction for the experimental target. It is not a public KEEP or an
official Kotlin target commitment.

## Context and authority

Common Kotlin owns each primitive's range, conversions, operator result types, overflow, equality,
hashing, and observable string contract. Mature targets preserve those rules while using their
native machine carriers: JVM uses JVM `float` and `double`, Wasm uses `f32` and `f64`, Native uses
the corresponding native IEEE-754 types, and JS inserts Kotlin numeric semantics over its host
number carrier. Their finite digit-generation algorithms are not identical, so a particular
JVM-minimal decimal spelling is not cross-target Kotlin identity.

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

`Byte` and `Short` landed first. `Float` is a separate implementation gate because IEEE equality,
boxed equality, total ordering, hashing, conversion, and rendering are different contracts that
must not be inherited accidentally from `System.Single`. The table is the selected physical
direction; each row freezes only after its adversarial gate passes.

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

### Float semantic closure

Kotlin `Float` is an exact CLR `float32`/`System.Single` in signatures, fields, locals, generic
arguments, generic arrays, nullable positions, and boxes. CIL's floating evaluation-stack kind is
a physical execution detail: `float32` declaration and storage boundaries retain the public
32-bit identity, and `conv.r4` is used for Common conversions and mixed-operation promotion that
produce a `Float`. `FloatArray` remains a separately gated Kotlin-owned specialized-array wrapper;
landing the scalar does not silently publish it as `System.Single[]`.

Common's distinct floating contracts are implemented explicitly:

- primitive `==` and relational operators use IEEE-754 comparison: NaN is unequal/unordered and
  negative zero equals positive zero;
- `Float.equals`, object-boundary equality, and `hashCode` use canonical `Float.toBits` semantics:
  all NaNs compare equal, negative zero differs from positive zero, and the signed 32-bit bits are
  the hash;
- `compareTo` uses Kotlin's total ordering, matching the JVM/Wasm ordering in which negative zero
  precedes positive zero and canonical NaN follows every non-NaN value;
- `Float.toInt`/`toLong` truncate toward zero, map NaN to zero, and saturate infinities and
  out-of-range values. Explicit guards precede CIL `conv.i4`/`conv.i8`, whose out-of-range result
  ECMA-335 does not define uniformly. Deprecated direct `toByte`/`toShort` retain Common's
  `toInt()`-then-narrow definition when such calls survive frontend compatibility settings; and
- integer-to-Float and Double-to-Float conversion use `conv.r4`; Float-to-Double uses `conv.r8`.

String conversion cannot delegate to either `System.Single.ToString()` or its `"R"` format. The
former is culture-sensitive and has CLR notation; the latter is observably profile-dependent
(`Float.MIN_VALUE` and `Float.MAX_VALUE` produce different digit strings on the supported
Framework and CoreCLR hosts). The runtime therefore tries invariant `G7`, retains it only when
parsing reproduces the exact canonical `float32` bits, and otherwise falls back to invariant `G9`.
It then applies Kotlin/JVM lexical conventions: literal NaN/infinities,
preserved `-0.0`, a decimal point for integral finite values, uppercase `E`, a normalized exponent,
and the `[1e-3, 1e7)` plain-decimal window. The fallback's nine significant digits guarantee a
`float32` round-trip on both hosts. Digits can be longer than the JVM's minimum distinguishing string;
that bounded display difference is preferred to culture-, profile-, or bit-dependent output and
is documented rather than mislabeled as shortest formatting.

Exact foreign CLR `float32` metadata imports as Kotlin `Float`; no wrapper or widening to `Double`
is introduced. Kotlin metadata remains authoritative for Kotlin declarations, while the truthful
physical signature is directly usable as C# `float`.

## Rejected alternatives

- **Spell Byte and Short as `int32` everywhere.** This collapses legal overloads, changes generic
  reification, nullable layout, reflection, boxed type identity, and the C# signature.
- **Use `uint8` for Byte.** Kotlin `Byte` is signed; `System.Byte` is not its carrier.
- **Introduce Kotlin-owned scalar wrapper classes.** CLR already provides exact interoperable
  value types. Wrappers would add allocation and identity without preserving extra Kotlin
  semantics.
- **Use checked arithmetic because CLR offers it.** Kotlin integer arithmetic wraps unless a
  library contract explicitly performs a checked operation.
- **Represent Float as `float64`.** This changes overload identity, generic reification,
  nullability, rounding, boxing, C# signatures, and every value whose 32-bit rounding is
  observable.
- **Delegate all Float object behavior to `System.Single`.** CLR `Equals` treats signed zeros as
  equal, profile hash behavior is not the Kotlin ABI, and native formatting is culture/profile
  dependent. The carrier is reusable; these language contracts are not.
- **Use profile-native `Single.ToString("R")`.** It is not reproducible across the two supported
  runtime profiles, even for boundary constants.
- **Land FloatArray together with Float.** A specialized Kotlin array has nominal collection and
  iterator contracts beyond its scalar element carrier and remains governed by the primitive
  array ADR.
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

The Float gate additionally covers subnormal/minimum/maximum values, infinities, NaN payload
canonicalization, both zeros, 32-bit rounding after mixed operations, saturating integral
conversions, total `compareTo`, deterministic guarded-`G7`/`G9` rendering, `float32` constants, and direct C#
`float` consumption/import on both profiles.

The current foreign-CLR FIR provider still admits only its deliberately closed primitive,
string/object, and reference-param-array grammar. A C# `Nullable<SByte>` or `Nullable<Int16>` is a
nominal generic instantiation rather than a primitive signature, so importing that shape remains
part of the separately gated foreign constructed-type slice. This does not affect emitted Kotlin
`Byte?`/`Short?`: those are exact `System.Nullable<System.SByte/System.Int16>` carriers and are
consumed directly by C# in this gate.
