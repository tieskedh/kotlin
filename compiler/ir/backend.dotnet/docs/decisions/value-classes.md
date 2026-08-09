# Value classes use one box owner and contextual underlying carriers

- Status: accepted for implementation; no public ABI freeze is implied
- Scope: single-field Kotlin value classes, including generic and nested value
  classes, their constructors and members, boxing boundaries, name mangling,
  reflection identity, and separate compilation
- Does not enable: multi-field value classes, unsigned arrays as a family,
  typed .NET export, private specialization, or suspend/coroutine lowering

## Common contract

A Kotlin value class is a nominal Kotlin classifier with value semantics. Its
primary constructor has one underlying property, referential equality is not a
legal Kotlin operation, and the frontend owns declaration legality. The
physical representation may vary by use site, but must not change equality,
nullability, casts, interface dispatch, generic behavior, reflection identity,
or separate-compilation results.

KLIB remains authoritative for the classifier, type arguments, nullability,
members, annotations, and logical callable signatures. A physical CLR carrier
or helper name is never sufficient to reconstruct that contract.

## Mature-target direction

The JVM keeps one wrapper class and lowers exact value-class operations to the
underlying carrier and static implementation methods. It boxes at erased,
interface, and nullable boundaries where the underlying carrier cannot encode
the logical value. Its stable function-name mangling prevents a value-class
parameter from colliding with its underlying type.

JS and Wasm share `InlineClassDeclarationLowering` and
`InlineClassUsageLowering`: constructors and ordinary members receive static
implementations, while a later value-usage pass inserts explicit box/unbox
operations. Wasm preserves a runtime box for erased and interface use even
though exact values use their underlying Wasm carrier. Native has its own
value-representation and autoboxing pipeline, but keeps the same semantic
split between exact values and boxed references. Foreign-language export is a
separate layer on the mature targets; it does not redefine Kotlin identity.

The reusable architectural rule is therefore not a particular JVM descriptor
or Wasm instruction. It is:

> Keep one nominal Kotlin box owner, use the underlying carrier only where the
> Kotlin type is statically exact, and make every representation transition
> explicit in target lowering.

## CLR constraints

The CLR provides both reference classes and value types, but neither is an
exact universal representation for Kotlin value classes:

- a CLR value type is always physically non-null and makes layout, generic
  instantiation, constrained calls, and host reflection part of the public
  representation;
- a Kotlin generic value-class owner must retain the target's established
  declaration-erased Kotlin class identity rather than become `C<T>`;
- an outer nullable value class cannot always reuse its underlying carrier: a
  primitive or nullable underlying value needs a distinct absence state;
- interface and `Any?` use requires a nominal boxed object so `is V`, virtual
  dispatch, and Kotlin reflection do not degrade to the underlying classifier;
- a consumer in another assembly must be able to box and unbox even when the
  source primary constructor or underlying property is private.

These are physical constraints. They do not authorize CLR reflection or a C#
surface to become Kotlin semantic authority.

## Selected representation

Every Kotlin-owned value class has one non-generic CLR reference-class owner.
For a generic value class, owner parameters are erased exactly like other
Kotlin-owned generic class parameters; its box stores the erased underlying
carrier. The logical generic declaration and every instantiation remain in
KLIB.

An exact, statically known, non-null value-class occurrence uses its recursively
substituted underlying carrier. A nullable occurrence may also use the
underlying carrier only when null cannot represent a valid underlying value
and the carrier is reference-shaped, matching the JVM rule. Otherwise it uses
the box owner. Erased `Any?`, interface, open generic, star-projected, and
runtime-type-test boundaries use the box owner.

The shared Common declaration and usage lowerings create the static
constructor/member implementation shape. A .NET value-usage lowering inserts
explicit compiler intrinsics at every required representation transition.
Codegen resolves those intrinsics to producer-published box/unbox helpers on
the single owner. Those helpers are compiler ABI, not C# export API, and must
work across assemblies regardless of source visibility.

The .NET value-usage lowering runs after every body-producing loop and string
rewrite. This follows the mature-target dependency rather than a local
workaround: JVM explicitly requires `ForLoopsLowering` before
`JvmInlineClassLowering`, and JS/Wasm likewise run loop lowering before their
inline-class/autoboxing stages. A shared lowering may introduce a substituted
`T -> V` cast or a new consumer even when the source body contained no such
node; running representation selection earlier would leave that generated IR
without an explicit nominal-box/carrier transition.

For a generic value class, static implementation methods copy owner type
parameters into genuine CLR method parameters. This preserves typed
calculation where the construction is exact without publishing a CLR-generic
class owner. Boxing into the owner erases its stored state; unboxing performs
the checked recovery required by the statically substituted Kotlin type.
Declaration-site variance remains authoritative on the logical class and in
KLIB, but the copied physical method parameters are invariant because
ECMA-335 permits variance only on interface and delegate type parameters. A
star-projected owner argument in generated implementation IR substitutes its
Kotlin erased upper bound for that method calculation; it does not make the
underlying carrier the value class's erased or reified identity.

A type parameter with a final primitive upper bound, such as the Common test
shape `T : Int`, keeps its logical parameter and the CLR method's generic
arity, but every physical value slot uses the sole possible primitive carrier.
The CLR generic parameter has no exact-primitive constraint because ECMA-335
cannot truthfully express one. This follows the JVM descriptor rule for
primitive-upper-bounded parameters; KLIB remains the authority that rejects
every substitution other than the bound.

A nullable final primitive bound such as `T : Int?` is different: both
`T = Int` and `T = Int?` are valid Kotlin substitutions, so no single scalar
carrier exists. Static implementation methods retain their genuine CLR method
parameter in those slots and omit the unrepresentable exact-primitive
constraint. This is safe because a CLR generic method token can carry both
`int32` and `System.Nullable<int32>` without creating a second class owner;
KLIB still owns the nullable bound and Kotlin substitution checks.

Every reified CLR-generic position denotes the nominal box owner when its
Kotlin argument is a value class. This includes generic methods, imported or
compiler-generated generic interfaces, and the optional `ExactFunctionN`
callable capabilities. The generated implementation may still calculate with
the exact carrier inside its body, but a bridge or explicit boundary
transition boxes incoming/outgoing values at the generic slot. Substituting
the underlying carrier as the CLR generic argument would make the host generic
system observe a different classifier and is therefore not an optimization.
The MethodDef signature mapper and call-site value adaptation must use this
same boundary classifier in both directions; otherwise a bridge can correctly
declare nominal `V` while its body incorrectly unboxes the argument before the
call.

Masked default dispatchers are the deliberate inverse case. An omitted
argument is accompanied by an authoritative mask bit and cannot be observed
before the dispatcher replaces it. Its physical placeholder is the CLR zero
value of the exact carrier, marked in IR as already unboxed; constructing a
nominal box would execute user initialization or make a dead null placeholder
fail during unboxing. This follows the JVM unsafe-coerce placeholder rule
without making that dead value part of Kotlin semantics.

Physical method names are mangled whenever a logical non-dispatch parameter or
relevant return type contains a value class. The suffix is derived from the
owner-independent Kotlin signature and is applied independently of whether a
collision happens in the current compilation. Overrides select one logical
slot root before deriving the name. KLIB and the producer ABI index retain the
unmangled Kotlin declaration identity.

## Identity and reflection

The wrapper object is not a second Kotlin object identity. Kotlin source cannot
observe value-class referential identity, and separately created boxes for the
same value remain equal according to the generated value semantics. `is`,
casts, interface dispatch, `KClass`, and `KType` observe the value-class
classifier, never the underlying CLR primitive/reference classifier.

Runtime reflection may use the box TypeDef as physical evidence. It must not
infer the underlying type, type arguments, nullability, or callable contract
from CLR signatures. Existing KLIB-derived reflection graphs remain authority.

## .NET interop

The implementation owner, mangled helpers, and contextual underlying
signatures are Kotlin compiler ABI. They are not promised as an idiomatic C#
surface. A future explicit .NET export may expose a CLR struct, facade, or
other typed projection only where it can preserve the selected Kotlin
contract. Disabling such an export must not change Kotlin DLL signatures,
boxing, casts, reflection, or object behavior.

Imported CLR value types remain foreign CLR declarations and retain their
native identity. This decision concerns only Kotlin-owned value classes.

## Rejected alternatives

### Emit every value as the reference box

Rejected as the final ABI. It is semantically viable, but permanently boxes
the main `UInt`, `Duration`, `Result`, and user-value paths and makes a later
underlying-carrier ABI a binary change. It is useful only as a diagnostic
baseline while implementing the complete lowering.

### Make the Kotlin owner a CLR value type

Rejected. It makes one host representation authoritative for Kotlin identity,
reopens generic-owner layout, and forces nullable/constrained-call rules into
the semantic ABI. It also diverges from the established Kotlin box plus
contextual-unboxing architecture without a CLR necessity.

### Erase the classifier completely to its underlying type

Rejected. `Meters` and `Int` would become indistinguishable at erased,
interface, type-test, reflection, and overload boundaries.

### Publish both a CLR struct and a Kotlin box as runtime owners

Rejected. That is a dual-runtime model with competing identity, state, casts,
and reflection. A separate explicit export artifact may project a host-native
value, but it is not the Kotlin implementation owner.

### Reuse JVM physical names and descriptors verbatim

Rejected. JVM mangling supplies the semantic precedent, not a cross-platform
physical ABI. Kotlin/.NET uses its existing stable Kotlin-signature digest and
producer-recorded CLR identity, with CLR-valid names and method signatures.

## Invariants

1. There is one boxed TypeDef per Kotlin-owned value-class declaration and no
   CLR-generic value-class owner.
2. Every underlying-carrier use is statically exact; erased and interface use
   has the nominal box classifier.
3. Every box/unbox transition is explicit after lowering and survives separate
   compilation through producer-recorded helper identities.
4. Nullable representation never uses null for both an underlying value and
   outer absence.
5. Two logical overloads cannot collide merely because a value class unwraps
   to the same CLR carrier as another Kotlin type.
6. Generated equality, hash, string form, interface dispatch, and casts are
   invariant across boxed and unboxed occurrences. Floating underlying values
   follow Kotlin's generic total-order equality (`NaN` reflexive and signed
   zero distinct), not source-level IEEE `Float == Float` lowering.
7. KLIB owns logical identity and reflection; CLR metadata owns only physical
   executable evidence and optional additive foreign-language metadata.
8. Any future optimization must be removable without changing DLL signatures,
   casts, reflection, cross-module behavior, or Kotlin-visible results.

## Completion evidence

Feature completion requires selected upstream Common/JVM value-class tests,
not only target-authored examples, across both FIR parsers and CLR profiles.
The matrix must include:

- primitive, reference, nullable-reference, and nullable underlying values;
- nullable outer values, including the null-collision cases;
- nested value classes and generic value classes with reference and value
  substitutions;
- constructors, `init` validation, secondary constructors, properties,
  ordinary members, extensions, defaults, varargs, lambdas, and callable
  references;
- generated/custom `equals`, `hashCode`, and `toString`, plus boxed values in
  `Any?`, collections, and interfaces;
- overloads differing only by value class versus underlying type, overrides,
  generic-interface bridges, and failure of invalid casts;
- `is`, `as`, `as?`, reified operations, `KClass`, `KType`, and annotations
  that can legally mention the admitted value-class shapes;
- same-module, separate-KLIB, installed-library, stale-ABI, and direct CIL/C#
  inspection of the one owner and its hidden compiler helpers;
- absence of a public CLR-generic owner or accidental idiomatic C# promise;
- the complete ordinary Kotlin/.NET aggregate with zero failures, errors, or
  skips.

Unsigned scalar and array publication is a consumer of this foundation, not
proof that the full language feature is complete. Multi-field value classes
require a later Common language/representation decision and remain rejected at
the located boundary.
