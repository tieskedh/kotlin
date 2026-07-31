# ADR: Profile-aware lowering of Kotlin interface default implementations

- Status: **Accepted**
- Date: 2026-07-20
- Scope: Kotlin-owned interface members with bodies on `net48`, `netstandard2.0`, and
  `net10.0`

This is a repository-local pre-ABI decision for the experimental .NET backend. It fixes the
semantic and physical direction before the current prototype ABI is published; it is not a KEEP
or a public Kotlin compatibility promise.

## Context

Kotlin interface defaults must preserve the same language semantics on every .NET profile. This
includes ordinary virtual dispatch, override resolution, reabstraction, conflict resolution, and
exact qualified calls such as `super<I>.f()`.

The CLR profiles have materially different capabilities:

- `net48` and `netstandard2.0` cannot rely on Default Interface Methods (DIMs);
- `net10.0` can store and dispatch interface implementations directly; and
- portable libraries must remain usable with the corresponding `net10.0` runtime and stdlib
  variants.

The representation therefore follows the general Kotlin/JVM transition without treating JVM
metadata as normative:

- the portable profiles use the semantic equivalent of `-jvm-default=disable`;
- `net10.0` uses an `enable`-derived compatibility representation; and
- a pure `no-compatibility` representation is deferred.

The shared invariant is Kotlin dispatch, not identical IL. The selected target profile owns the
physical placement before target lowerings run.

## Decision

### 1. Portable profiles use helper-owned implementations

On `net48` and `netstandard2.0`, an interface member with a Kotlin body remains an abstract CLR
interface slot. The body moves to a metadata-public static compiler-ABI helper associated with
the declaring interface:

```text
I
    abstract F()

I/__KotlinDefaultImpls
    static F(I self)
        // exact Kotlin body declared by I
```

A Kotlin class which inherits that default receives a hidden forwarding implementation of the
CLR interface slot. The forwarder is normally an explicit interface implementation rather than
an additional public class member:

```text
C : I
    private final virtual <bridge-for-I.F>()
        .override I.F
        call I/__KotlinDefaultImpls.F(this)
```

The helper and cross-assembly forwarder are compiler ABI. They are marked with
`KotlinCompilerAbiAttribute` and hidden from ordinary completion. They remain non-public Kotlin
declarations.

### 2. `net10.0` uses a real CLR default interface method

On `net10.0`, the canonical body is emitted directly in the CLR interface. A class which merely
inherits the default receives no compatibility forwarding method:

```text
I
    virtual F()
        // Kotlin body declared by I

C : I
    // no F forwarder
```

Ordinary dispatch can therefore use the CLR's native DIM machinery, and a C# implementation can
naturally inherit the default. The backend must still emit explicit slot mappings where Kotlin's
logical override result is not represented by the natural CLR mapping.

### 3. The static helper remains present on `net10.0`

The `net10.0` variant retains the same helper signature as the portable variants:

```text
I/__KotlinDefaultImpls
    static F(I self)
        // exact nonvirtual invocation of the DIM declared by I
```

It remains for:

- portable binaries which already call that helper;
- exact lowering of `super<I>.f()`;
- compiler-generated conflict-resolution and representation bridges; and
- the portable physical-ABI superset required of Kotlin-owned platform variants.

The modern helper invokes the implementation declared by exactly `I`. It must not perform
ordinary virtual dispatch. The portable helper owns a copy of that exact Kotlin body because no
interface body exists on those profiles.

This is `enable`-derived rather than a literal copy of the JVM representation: the CLR helper and
DIM spellings, interface slot mapping, and split generic-interface capabilities are .NET-owned.

### 4. Generic defaults have one body and one helper identity

Every generic Kotlin interface-default declaration has one logical member, one canonical semantic
body, and one stable helper ABI identity. The split erased, declared-variance, and exact CLR views
are physical projections of that member; they are not independent implementations.

On the portable profiles, the helper owns the moved body. Interface views remain abstract, and
implementing classes or derived views contain only forwarding or representation adapters. On
`net10.0`, one strongly typed DIM owns the body. The exact invariant view is the normal strongly
typed C# surface and reaches that canonical DIM without an erased-result cast. A declared-variance
or erased view which cannot expose the same physical signature adapts by virtually dispatching to
the canonical typed slot, boxing, casting, or widening only when that view's own ABI requires it.
Explicit `MethodImpl` rows bind every physical view to the one logical override group.
When that body or adapter is a property accessor, every view which owns the corresponding CLR
Property row retains `specialname`; moving the body into a DIM must not degrade the property into
an ordinary method for reflection or C#.

When a concrete non-generic `net10.0` interface overrides a member inherited from a split generic
interface, that interface owns one complete canonical/declared/exact adapter bundle. Each final
adapter maps its inherited physical slot and dispatches virtually to the overriding DIM; none owns
a body copy. Implementing classes inherit the bundle and do not duplicate it. Because generated
adapter declarations are not logical KLIB members, a library records every adapter as structured
physical ABI keyed by the owning logical interface, inherited logical member, and physical view.
A separately compiled consumer uses only that record, never a generated name or rendered IL.

The helper preserves the declaration and method generic-parameter order, substitution, and stable
method identity across profiles. On `net10.0` it selects the canonical DIM nonvirtually. It is used
only for qualified-super selection, compatibility, and promotion of a selected portable
helper-owned default. No helper, promotion DIM, interface-view adapter, class bridge, or class
forwarder contains an independently lowered copy of the Kotlin body.

Kotlin bounds remain part of the KLIB signature and frontend type system. CLR metadata normally
carries the corresponding constraints, except for a method constraint which depends on a type
parameter of a split Kotlin generic interface. That relationship is deliberately omitted from all
executable CLR views of the member:

- ECMA-335 forbids using a variant interface type parameter in such a constraint, causing the
  declared-variance interface to fail type loading;
- retaining the constraint only on the invariant exact DIM rejects a valid Kotlin call made
  through a widened declaration-site-variance view; and
- a portable closed value-type bridge cannot express the substituted relationship as a CLR
  generic-parameter constraint.

The exact view still has strongly typed arguments and results; only the incompatible physical
constraint is weakened. Kotlin callers remain checked against the logical bound. An optional C#
export facade may later restate a convenience constraint, but it must forward to the Kotlin slot
and must not redefine Kotlin virtual dispatch or helper identity.
### 5. Ordinary calls always use virtual dispatch

An ordinary Kotlin call such as `value.f()` uses normal CLR class or interface dispatch. It is
never replaced by a direct helper call, because that would bypass class overrides,
derived-interface implementations, and foreign implementations.

### 6. Qualified interface-super calls use the helper

A qualified call `super<I>.f()` lowers to the helper associated with exactly `I`:

```text
call I/__KotlinDefaultImpls.F(this)
```

On portable profiles that helper executes the moved Kotlin body. On `net10.0` it invokes `I`'s
DIM nonvirtually. The result must be independent of any more-derived implementation on the
receiver.

Common Kotlin prohibits omitting arguments in any super call and reports
`SUPER_CALL_WITH_DEFAULT_PARAMETERS`. This target defines no exception and publishes no ABI for
that invalid construct. If malformed qualified-super default-stub IR escapes frontend validation,
the backend must reject it rather than reinterpret it as ordinary virtual dispatch.

### 7. Kotlin resolves interface conflicts before CLR emission

Kotlin override resolution remains authoritative. The backend must not rely on a runtime
`AmbiguousImplementationException` for a valid Kotlin program. Derived-interface overrides,
reabstraction, and conflicts between unrelated defaults are represented with explicit CLR slot
mappings, class overrides, or compiler-generated bridges.

The CLR's most-specific-default algorithm is an execution mechanism after Kotlin has selected a
valid logical hierarchy; it is not the Kotlin source-language conflict resolver.

### 8. Class bridges are emitted only when physically necessary

`net10.0` does not receive a class forwarding method merely because a class inherits an interface
default. A class bridge is generated only when Kotlin's selected implementation cannot be
represented by natural CLR slot mapping, including:

- erased, declared, or exact generic-interface views;
- value or boxing representation adapters;
- covariant-return adapters;
- one Kotlin override satisfying multiple physical CLR slots;
- explicit Kotlin conflict resolution; or
- an explicit override whose lowered signature differs from the interface slot.

A coincidentally matching base-class method does not by itself require a bridge when Kotlin and
CLR dispatch already select that method.

Covariant returns preserve the same placement rule. If a selected `net10.0` DIM has a more
precise physical return than an inherited interface slot, the derived interface owns one private
final covariant-return adapter explicitly mapped to the wider slot. That adapter dispatches
virtually through the precise DIM. The ordinary default-slot adapter relinquishes the incompatible
slot, and an implementing class inherits the interface-owned mapping without either a default
forwarder or a duplicate covariant bridge. On portable profiles the precise default remains
helper-owned: the implementing class therefore owns the hidden precise helper forwarder and one
covariant-return adapter mapped to the wider slot. In neither representation is the Kotlin body
lowered twice.

A DIM being present in the interface graph is not sufficient when an inherited class MethodImpl
would take CLR precedence over it. If a base class contains a compiler-generated forwarder for an
ancestor default and Kotlin selects a more-specific interface default, the derived class emits a
resolver bridge to the selected helper. This is a physical ABI adapter: it prevents CLR class
precedence from changing Kotlin's selected implementation.

Producers record hidden compiler-generated class forwarders as structured physical dispatch
metadata. Consumer lowering and shape validation traverse those records through arbitrary
base-class depth, distinguish the forwarders from user-authored class overrides, and do not infer
them from the producer profile, source hierarchy, generated name, or rendered IL.
A producer records such a forwarder whenever it physically emits one, including a `net10.0` class
that implements a helper-only interface imported from a portable assembly.

The governing rule is:

> Generate a class bridge only when an ABI representation difference or explicit Kotlin override
> decision requires a physical CLR slot adapter.

### 9. Cross-profile compatibility is upward

Portable Kotlin output may run with the matching `net10.0` runtime or stdlib variant because:

- portable class forwarders remain valid class implementations;
- portable helper calls continue to resolve; and
- class implementations take precedence over CLR default-interface fallback.

The reverse is not guaranteed: `net10.0` output may contain DIM metadata and is not portable to
`net48`.

An interface defined only in a `netstandard2.0` assembly does not acquire a DIM merely because it
runs on .NET 10. A `net10.0` consumer respects the helper-owned representation recorded by that
dependency unless a Kotlin interface compiled for `net10.0` deliberately promotes the selected
default.

When a `net10.0` Kotlin interface inherits a selected Kotlin default whose declaring assembly
exposes only an abstract CLR slot plus helper, the derived interface emits a DIM explicitly mapped
to that inherited slot. The DIM forwards to the original declaring interface's recorded helper;
it does not copy or rediscover the body and does not perform ordinary virtual dispatch. The
promotion is recorded as structured physical ABI on the derived interface so a later separately
compiled consumer knows that the inherited default is physically DIM-backed.

Class-forwarder selection follows the effective physical implementation, not merely the consumer
profile:

- omit a `net10.0` class forwarder when the selected Kotlin default is physically available through
  a DIM, including a DIM promoted by a derived Kotlin interface, and no inherited class MethodImpl
  masks that selected DIM;
- emit a hidden class forwarder when a class directly or indirectly inherits a portable abstract
  interface slot whose selected Kotlin default remains helper-only and no inherited
  compiler-generated MethodImpl already implements that same selected default; and
- suppress that forwarder when a selected `net10.0` derived-interface promotion already supplies
  the slot.

This is required for upward compatibility: targeting `net10.0` does not make an abstract portable
slot executable by itself, while a recorded DIM promotion is a real implementation the CLR can
select.

Cross-profile metadata verification compares semantic slot obligations, not `MethodImpl` table
rows. One obligation is keyed by the existing Kotlin logical member identity, its canonical,
erased, declared, exact, or helper role, and the complete normalized CLR signature recorded by
the producer. The manifest locator must resolve to exactly one MethodDef by owner, method name,
generic arity, return type, and parameter types; a name-only match is invalid.

For each externally consumable concrete portable type, every manifest-addressable interface-map
entry whose target is concrete is an upward-compatibility obligation. The corresponding runtime
type may satisfy it through:

- an explicit class `MethodImpl`;
- a natural CLR class implementation;
- the selected DIM; or
- an interface-owned `MethodImpl` which records a promotion or representation adapter.

The selected target must remain concrete and unambiguous. Raw row identity and row counts are not
ABI: a correct `net10.0` variant normally removes a portable class forwarder when a selected DIM
is effective. Non-public implementation types are still checked locally for resolvable concrete
interface maps, but their physical type names are not promoted into cross-profile ABI identities.
Mappings outside the C# authoring manifest are compared separately by the implementing CLR type
and complete constructed interface signature. This is a physical slot locator, not a second
Kotlin identity namespace; Kotlin logical identity remains in KLIB and
`DotNetPhysicalDeclaration`. See
[`../verification/interface-abi-conformance.md`](../verification/interface-abi-conformance.md).

Physical provider selection is set-based and uses only the most-specific DIM providers for the
selected logical Kotlin default:

- with no provider, a derived `net10.0` interface promotes the helper-owned default and a class
  emits the hidden helper forwarder;
- with exactly one most-specific provider, that DIM is the selected physical implementation, so a
  derived interface emits no duplicate promotion and a class emits no forwarder; and
- with multiple incomparable providers, natural CLR dispatch would be ambiguous, so a derived
  interface emits one resolving DIM and a class emits one hidden resolving forwarder.

A more-derived provider shadows an ancestor provider for this calculation. Two physical DIMs that
implement the same logical Kotlin declaration are not assumed to be harmless: unless one is more
specific, the backend must materialize Kotlin's already-resolved choice before CLR execution.

This provider rule also applies independently to every canonical, declared, and exact view of a
generic Kotlin interface. If two `net10.0` interfaces promote the same portable generic default,
a derived diamond emits one new resolver bundle whose adapters all call the original declaring
interface's helper identity. It does not inherit CLR ambiguity, choose one branch arbitrarily, or
lower another copy of the body. Kotlin source cannot implicitly combine an unrelated concrete
default with a separate abstract or concrete declaration: common override resolution requires an
explicit derived override, which then follows the ordinary declared-body path above.

### 10. Pure no-compatibility mode is deferred

A future DIM-only ABI could remove the helper and compatibility machinery, corresponding in
purpose to Kotlin/JVM `no-compatibility`. It is not adopted while portable binaries or qualified
super calls depend on the helper surface. Removing it later is an ABI decision, not an optimizer
choice.

## ABI and ownership

The source interface member remains the logical Kotlin declaration. Its physical declaration
record must additionally describe:

- whether the defining slot is abstract/helper-owned or DIM-bearing;
- the exact helper owner and method identity;
- the exact owner and method identity of the ordinary masked default-argument dispatcher,
  independently of whether the interface member has a body;
- the helper's receiver and generic-parameter mapping;
- every canonical/declared/exact interface slot supplied by the logical member;
- every final interface view adapter inherited by downstream implementors, keyed by its owning
  logical interface, inherited logical member, and physical view; and
- every covariant-return `MethodImpl`, keyed by its logical owner and inherited logical member, so
  downstream classes can recognize an interface-owned mapping without inspecting generated IL; and
- every generated class `MethodImpl` mapping needed to preserve Kotlin resolution, keyed by its
  logical owner and inherited logical member.

Consumers use this structured metadata. They must not derive `__KotlinDefaultImpls` or `$default`
names, assume that a non-abstract metadata declaration contains a DIM, or inspect rendered IL text.
A library or stdlib cannot be published successfully if a metadata-visible default lacks its
required physical body record or if a callable with default parameters lacks its required
dispatcher record.
Generated `__KotlinDefaultImpls` classes and helper methods are physical compiler ABI only. They do not
receive independent logical declaration keys, because there are no corresponding KLIB
declarations; their identities are reachable through the records of the real source members.
The helper type name is deliberately a valid C# identifier. Portable C# source-authoring tools
must be able to forward an inherited default to that single Kotlin body; copying the body into
generated C# is forbidden. The name remains producer-recorded ABI and must not be reconstructed
from the interface name.
The supported generated implementation path and its DLL-owned helper/view metadata are specified
by [`adr-csharp-interface-source-authoring.md`](adr-csharp-interface-source-authoring.md).


The implementation body belongs in target lowering and generated compiler ABI. The runtime owns
only reusable marker attributes or services which cannot be expressed per declaration. Ordinary
stdlib interface bodies remain ordinary Kotlin declarations compiled by this mechanism.

## Required validation

Before the representation is considered implemented, tests must cover:

- exact IL layout for all three profiles;
- ordinary override dispatch and a derived-interface override;
- `super<I>.f()` selecting exactly `I` when all arguments are supplied;
- reabstraction and unrelated-default conflict resolution;
- abstract and concrete Kotlin implementors;
- separately compiled producer and consumer modules in every compatible profile pairing;
- portable output running with the modern platform variants;
- a C# implementor inheriting a DIM on `net10.0` and the expected compile-time obligation on
  both `net48` and `netstandard2.0`;
- generic declared/canonical/exact views, boxing adapters, and covariant returns, including a
  precise derived default mapped to a wider inherited slot without a redundant class bridge;
- a portable generic default promoted through two incomparable `net10.0` branches and resolved by
  a derived generic-interface diamond, including Kotlin and C# calls through root, branch, derived,
  exact, method-generic, and widened views;
- property accessors, generic methods, default arguments, and nested interfaces, including a
  portable generic interface dispatcher consumed on both runtime profiles with its declaring
  interface type context intact;
- reflection evidence for abstract slots, DIM bodies, helpers, visibility, attributes, and
  `MethodImpl` rows;
- a separately compiled direct or indirect portable base-class forwarder combined with a
  more-specific net10 DIM, proving the Kotlin-selected body through every interface view; and
- direct IL/runtime proof that the modern helper invokes the requested DIM nonvirtually.

Unsupported shapes fail before publication and identify the missing representation. An IL golden
showing syntactically valid output is not sufficient evidence.

## Consequences

The design provides uniform Kotlin semantics, native DIMs on `net10.0`, portable helper
compatibility, exact qualified-super behavior, natural modern C# default inheritance, and no
unconditional modern class forwarders.

Its costs are a compiler-ABI helper on `net10.0`, profile-specific generated shapes, no DIM-style
binary interface evolution for portable profiles, and explicit physical metadata for helpers and
slot adapters. Exact nonvirtual DIM invocation must remain pinned by direct IL and runtime tests.

## Final rule

> `net48` and `netstandard2.0` use a Kotlin/JVM-`disable`-style representation: interface slots are
> abstract, the one canonical body resides in a static helper, and Kotlin implementations receive
> only physically necessary forwarding or representation adapters. `net10.0` uses an
> `enable`-derived representation: one strongly typed CLR interface slot owns the canonical body,
> erased and secondary typed views virtually adapt to it, and the same stable helper selects it
> nonvirtually for compatibility and exact qualified-super calls. No adapter owns another lowered
> body.
