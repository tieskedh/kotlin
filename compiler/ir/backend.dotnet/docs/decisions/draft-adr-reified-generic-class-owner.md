# Draft ADR: one natural CLR-generic class owner with semantic capability

- Status: **Draft — production-inert candidate; production remains erased**
- Current production authority:
  [`generic-class-erased-identity.md`](generic-class-erased-identity.md)
- Shared physical authority and value provenance:
  [`draft-adr-generic-owner-physical-authority.md`](draft-adr-generic-owner-physical-authority.md)
- Kotlin semantic boundary:
  [`kotlin-semantic-authority-and-platform-freedom.md`](kotlin-semantic-authority-and-platform-freedom.md)
- Programme:
  [`../programmes/generic-class-owner-reopening.md`](../programmes/generic-class-owner-reopening.md)
- Migration and inverse:
  [`../programmes/generic-class-owner-migration-plan.md`](../programmes/generic-class-owner-migration-plan.md)
- C# surface:
  [`../programmes/generic-class-owner-csharp-surface.md`](../programmes/generic-class-owner-csharp-surface.md)

## Context

The desired .NET representation of a Kotlin-owned generic class is an ordinary
CLR generic owner whenever the complete Kotlin contract permits it:

```text
Kotlin:  open class Box<T>(var value: T)
CLR:     open class Box<T> { ... }
```

That shape gives Kotlin typed fields and calls where they are truthful and gives
C# a natural type to construct, consume, and subclass. It is not sufficient by
itself. Kotlin also admits stars, projections, logical widening, broad candidate
inputs, unchecked casts, and override families which the CLR cannot always name
as one constructed class type.

The accepted production representation therefore remains the non-generic
erased owner. This draft defines the class-specific candidate that may replace
it only after one complete atomic rehearsal. The shared physical-authority ADR
owns declaration epochs, callable contracts, value provenance, joins, and late
operation routing. This ADR selects only the class owner, state, inheritance,
C# subclassing, and migration rules.

Historical schema evolution, bounded recognizers, measurements, and individual
proofs belong in the programme and dated archive. They are evidence for this
decision, not additional representation rules.

## Decision

### 1. One natural implementation owner

For an admitted logical declaration `class C<T...>`, the candidate emits:

1. one natural CLR implementation TypeDef `C<T...>`;
2. one receiver identity and one authoritative object state;
3. only where required, a non-generic compiler-ABI semantic capability
   implemented by that same object; and
4. producer-recorded physical owner, state, member, inheritance, and bridge
   identities joined to the logical KLIB declaration.

There is no second erased implementation owner, typed sibling class, wrapper,
proxy, or alternate Kotlin classifier. A semantic capability is only another
physical view of the same receiver. It is not the canonical class, does not own
state, and does not appear as another Kotlin declaration.

KLIB remains authoritative for logical type arguments, variance, nullability,
projections, bounds, overrides, and reflection identity. The emitted TypeDef,
MethodDefs, fields, base/interface edges, and MethodImpls are authoritative for
their physical CLR shape. Neither side may be reconstructed from the other by
name or by a later type approximation.

### 2. Admission is declaration-wide; exactness is construction-local

Admission is decided for the complete open declaration and every producer-
visible obligation. A class is not admitted merely because one closed use such
as `C<Int>` is easy. Unsupported inheritance, state, member, reflection, or
separate-compilation shapes keep that declaration on the production erased
representation until the missing mechanism exists.

After a class family is admitted, a particular value may carry an exact natural
construction when physical authority proves it, for example:

```text
C<String>       -> C<string>
C<Int>          -> C<int32>
C<R> in method  -> C<!!R>
```

Stars, projections, widened views, open-nullable arguments, or nested generic
arguments may have no single truthful constructed CLR spelling. Such a value is
carried through an authority-selected semantic capability or object boundary.
This is construction-local loss of precision; it does not erase the open
`C<T>` TypeDef, every construction of `C`, or every field whose type mentions
`T`.

An outer generic construction chooses its physical argument from the complete
logical and physical production contract for that construction. It may use an
exact nested construction only when every legal value at that boundary has the
same verifier-valid carrier. Otherwise it uses an honest semantic/object
carrier. It must never claim `I<object>` or `C<object>` as a runtime view of an
object known only to implement `I<int>` or `C<int>`.

A logical widening does not erase independently preserved exact provenance,
but the logical type alone never creates it. Public parameters, mutable joins,
fields, and separately compiled results receive exactness only from their
producer-recorded physical contract and the shared provenance model.

Open-nullable construction and metadata-fixed ancestry are admission gates. If
one CLR TypeSpec cannot truthfully encode a base such as `D<T> : C<T?>` for all
legal substitutions, the backend may not repair it with runtime reflection or
pretend a fixed `C<object>` edge is exact. The declaration remains erased or
unadmitted until an honest one-owner representation is proved.

### 3. State is selected once from every legal producer

Each field or equivalent state slot has one producer-wide storage decision.
The decision considers at least:

- every constructor, initializer, and write path;
- broad and semantic entries which can reach the state;
- overrides, inherited obligations, and external callers;
- visibility, object escape, and separate compilation; and
- required memory and atomicity semantics.

The state may use `!T`, another exact constructed carrier, a semantic
capability, `System.Array`, or `object`. A `!T` field is selected only when every
legal producer and escape proves that carrier and no semantic path can install
an incompatible representation. One unsupported or genuinely broad writer
selects a truthful wider carrier for that state slot or leaves the declaration
unadmitted. It does not automatically widen unrelated fields.

The CLR-generic owner is therefore not a promise that every owner-dependent
field is typed. Conversely, one semantic field is not permission to route all
other state or calls through `object`. State is selected before local value
provenance; exact local facts may optimize access to the selected field but may
not specialize or duplicate it.

Typed and semantic accessors operate on the same field. Required conversions
occur at the real boundary selected by the callable contract. No typed cache,
erased mirror, shadow state, lazy synchronization, wrapper, or proxy may repair
an incompatible state plan.

### 4. Member routes compose with the shared callable model

This ADR does not assign one carrier to every `T`, `T?`, property, or method.
Each physical member uses the independent parameter-domain and result-layout
policies in the shared physical-authority ADR. In particular, a direct logical
`T?` result may use a producer-recorded split-nullable layout; this ADR does not
require it to return `object` merely because it belongs to a class.

The natural typed MethodDef or PropertyDef is the normal Kotlin-exact and C#
entry whenever its complete contract is truthful. A compiler-generated hook or
capability dispatcher exists only for an operation whose Kotlin view cannot be
served by that entry. Broad candidate inputs reach their semantic body or
shared Kotlin special-bridge result without first being narrowed to `!T`.
Strict inputs check only at their actual typed-use boundary. Output-safe calls
may retain an already-proven exact receiver construction through logical
widening and widen the result after the natural call.

A semantic lowering may not replace an already-proven natural route merely
because the logical view is broad. It may do so only when the operation's
recorded Kotlin contract requires a broader input, result, override, or state
domain, or when new physical authority invalidates the earlier proof. Equally,
an exact carrier may never narrow a genuinely broad source value.

Member roles and routes are derived from logical override families, producer-
recorded physical contracts, state decisions, and value provenance. Class,
package, stdlib, member name, IR origin, generated-name pattern, or one current
call shape is never authority.

### 5. Ordinary C# subclasses use the natural surface

For an admitted public/open class, C# constructs and subclasses the natural
`C<T>` owner directly. Source-visible typed constructors, methods, and
properties are the supported ordinary C# contract. A C# author must not need to
know or implement a generated Kotlin semantic capability merely to use a
semantic result which the compiler can derive mechanically from those natural
slots.

Kotlin semantic dispatch must observe the most-derived ordinary C# override
whenever the operation can be implemented from that override without changing
Kotlin behavior. The compiler owns any bridge, MethodImpl, dispatcher, or
override-detection mechanism needed to connect the natural slot to its semantic
view. Kotlin-produced subclasses emit their corresponding compiler ABI
automatically. Compatible exact and widened calls must not diverge merely
because the most-derived subclass was written in C#.

The compiler cannot invent user behavior or storage that is absent from the
natural CLR contract. If an abstract or broad Kotlin obligation admits values
or results that no natural typed override can represent, the class shape is not
therefore silently accepted by requiring every C# subclass to implement a
hidden convention. It remains unadmitted for wrapper-free subclassing, or an
explicitly specified adapter/authoring contract must own that semantic
boundary. A future source generator may provide such an explicitly selected
adapter; its absence is not an admission requirement for semantics that the
compiler can derive. Compiler/runtime code, rather than hidden foreign-source
ABI, owns optimization of derivable semantic routes. If generated code makes an
otherwise unsupported semantic contract possible, that code is a distinct,
visible interop adapter rather than a hidden completion of the natural owner.

Imported CLR generic classes are a separate case. Their retained CLR TypeDefs,
MethodDefs, inheritance, constraints, and variance are native physical
authority. They do not acquire this Kotlin-owned semantic capability merely
because Kotlin imports or uses them.

### 6. Inheritance and virtual dispatch retain emitted physical truth

Every base-class and implemented-interface edge must be a truthful, metadata-
fixed CLR construction. Later logical substitution cannot rewrite an emitted
base MethodDef, foreign MethodDef, InterfaceImpl, or MethodImpl. When a derived
Kotlin body has a different physical signature, the producer records and emits
the required bridge or MethodImpl against the authoritative inherited slot.

The complete override family must preserve:

- most-derived Kotlin and C# virtual dispatch through natural and semantic
  routes;
- non-virtual `super` calls to the producer-recorded base endpoint;
- abstract members, reabstraction, defaults, and diamonds where admitted;
- constructor delegation and one inherited state graph; and
- the original declaring owner and slot across separately compiled assemblies.

A bridge may convert around a call; it may not narrow a broad candidate before
the Kotlin operation has made its semantic decision, bypass an override, open a
sibling virtual slot, or reinterpret an inherited signature from the current
consumer's substituted Kotlin type.

CLR classes remain invariant. Kotlin declaration- or use-site variance is a
logical rule serviced by an exact natural construction only when the CLR has a
verifier-valid physical view, and otherwise by the same object's semantic
route. No fictitious variant class edge is emitted.

### 7. Casts use only the accepted BK-1 boundary

This class candidate does not create a general “CLR casts are stricter” rule.
The accepted [BK-1 entry](breaking-kotlin-changes.md) alone governs
structurally unchecked parameterized `as`, `as?`, and any admitted
parameterized `is` for a true CLR-generic owner. They use the same Kotlin-aware
compatibility predicate; only mismatch behavior differs. Diagnostic suppression
does not select runtime behavior. Valid Kotlin variance and projection
conversions continue to succeed with the same object even when their logical
view has no constructed CLR spelling.

Star tests and casts classify the recorded open declaration/capability, not
`C<object>`. A successful conversion preserves identity, state, synchronization,
and virtual dispatch. No warning-free Kotlin operation, `@UnsafeVariance`, or
available CLR `isinst` check extends BK-1.

### 8. Reflection exposes one Kotlin declaration

All recorded constructions of the natural open `C<>` TypeDef normalize to one
Kotlin `KClass`. `KType` obtains logical arguments, projections, nullability,
and bounds from KLIB rather than reverse-engineering a closed CLR construction.
Callable reflection maps every typed, semantic, bridge, default, and helper
MethodDef in one recorded family to its one logical callable, or hides it when
it has no Kotlin declaration.

Raw CLR reflection may truthfully show `C<T>` and compiler-generated physical
members. Those members must be marked and named as compiler ABI, stay out of
ordinary supported C# authoring and IntelliSense where the platform permits,
and never appear as extra Kotlin declarations.

### 9. Separate compilation consumes producer facts, not guesses

The producer serializes the admitted open TypeDef, generic binders and
constraints, exact base/interface constructions, constructor and member
families, state decisions, virtual slots, MethodImpls, capability endpoints,
visibility, memory semantics, and callable layouts. A consumer binds those
facts by artifact, logical declaration key, and physical owner/member identity.

The consumer does not regenerate compiler names, infer a slot from arity or
current erased metadata, substitute a later logical type into an old MethodDef,
or fill missing final-emission evidence from an earlier plan. Missing, stale,
ambiguous, contradictory, or incomplete family records fail closed before a
physical route is selected.

Per-value provenance remains compilation-local and follows the shared ADR. It
is not serialized as hidden state. A cross-assembly boundary retains an exact
carrier only when its producer-recorded physical signature guarantees it.

### 10. Production cutover and rollback are atomic

This draft authorizes production-inert planning, physical families, metadata
records, hostile products, shadow provenance, and inverse tests only. The
accepted non-generic class owner remains production authority.

Production may switch only after the complete selected class/interface,
Runtime, Stdlib, reflection, importer/exporter, compiler, build-tool, C#,
deployment, and separate-compilation family passes the migration gates. The
switch must:

1. replace the erased owner family and its physical binding in one schema
   epoch;
2. reject stale artifacts instead of supporting a mixed erased/generic period;
3. contain no per-class or easy-owner pilot that gives one logical declaration
   different physical identities across modules; and
4. retain and execute the exact inverse which restores the production-erased
   owner, routes, artifacts, and tests without a compatibility shim.

A failed rehearsal or inverse keeps production erased. A successful bounded
owner is evidence, not authority to migrate it alone.

## Non-negotiable invariants

1. Kotlin IR/KLIB owns logical Kotlin semantics; emitted or retained CLR
   metadata owns physical declarations.
2. An admitted logical class has one natural `C<T...>` implementation owner.
3. Every object has one identity and one authoritative state.
4. A semantic capability is a same-object compiler view, never a second owner
   or Kotlin classifier.
5. Typed state is selected only from the complete producer-wide write, escape,
   inheritance, memory, and separate-compilation proof.
6. Construction-local loss of exactness never globally erases unrelated
   constructions, fields, or calls.
7. Logical subtyping or a later substitution never fabricates a CLR
   construction or rewrites an emitted/foreign MethodDef.
8. Broad semantic inputs do not contaminate unrelated exact receiver-derived
   state, and exact provenance never narrows a genuinely broad value.
9. Ordinary C# subclassing uses the natural surface; compiler ABI is not a
   hidden default author obligation.
10. Natural and semantic routes preserve one override family, including C#
    overrides and `super`.
11. Callable parameter/result layout, including split-nullable results, follows
    the shared physical-authority ADR rather than a class-specific rule.
12. BK-1 is the only accepted parameterized-cast incompatibility.
13. No wrapper, proxy, copied store, shadow state, or reflective fiction repairs
    representation.
14. Production stays erased until one atomic cutover, and the exact erased
    inverse remains executable.

## Rejected alternatives

- **Typed primary members with narrowing erased bridges.** They reject ordinary
  widened candidate inputs before Kotlin can produce its required result.
- **A permanent erased implementation twin.** It creates competing owner and
  state identities rather than a semantic view of one object.
- **`C<object>` as star, projection, or universal fallback identity.** CLR
  class invariance and value-type constructions make that claim false.
- **Local allocation or static Kotlin type selects field/ABI layout.** It
  ignores other writers, escapes, overrides, and separately compiled producers.
- **Two stores or representation-repair wrappers.** They break identity,
  mutation, synchronization, dispatch, or memory semantics.
- **Hidden compiler ABI as the ordinary C# subclass contract.** Mechanically
  derivable behavior belongs in compiler-generated routing; non-derivable
  semantics require an explicit contract or remain unadmitted.
- **Unbounded runtime generic construction as the correctness mechanism.** It
  is not a truthful metadata-fixed ancestry model and is incompatible with the
  required AOT and trimming contract.
- **Per-owner production pilots.** They create a mixed ABI before the complete
  family and inverse have proved the selected representation.

## Evidence required before acceptance

Acceptance requires one declaration- and package-independent hostile matrix
covering at least:

- open, abstract, final, and sealed classes with one and multiple parameters;
- reference, primitive, nullable primitive, enum, tuple, framework struct,
  user struct, value-class, bounded, and open-nullable substitutions;
- exact, semantic, star, projected, widened, nested-generic, array, and joined
  constructions, including multiple incompatible reaching constructions;
- constructor, initializer, typed-state, semantic-state, volatile-state, field,
  custom-property, broad-input, broad-output, and `@UnsafeVariance` paths;
- Kotlin/Kotlin, Kotlin/C#, and C#/Kotlin inheritance across multiple levels and
  assemblies, including abstract obligations, defaults, reabstraction,
  diamonds, overloads, direct `super`, and ordinary C# subclasses which do not
  implement hidden compiler ABI;
- exact MethodDef, TypeDef, GenericParam, constraint, base/interface,
  MethodImpl, field, property, nullability, visibility, and memory-semantics
  metadata;
- classifier, `KClass`, `KType`, callable, and member-reflection normalization;
- BK-1 mismatch and valid variance/projection identity on the same objects;
- stale, incomplete, conflicting, and version-skewed producer records;
- both FIR parsers, Framework 4.8, .NET 10 JIT and ReadyToRun, trimming, and
  NativeAOT; and
- representative Kotlin and C# applications measuring boxing, allocation,
  throughput, startup, code/metadata size, compilation cost, and memory.

Every positive state case proves one physical field/store and every positive
route identifies the selected MethodDef or MethodImpl. Every negative case
fails closed without a fabricated construction, wrapper, shadow state, or
silent fallback. The final rehearsal must execute both the complete candidate
and its exact production-erased inverse before this draft may replace the
current production ADR.

## Consequences

- Truthful `C<T>` identity, typed state, and typed calls become the normal class
  surface where complete proof permits them.
- Semantic/object routing remains a narrow operation or storage escape hatch,
  not a contagious default and not a second implementation model.
- Some Kotlin class declarations may remain erased because their metadata-fixed
  inheritance or open semantic obligations cannot yet support one honest CLR
  owner.
- C# receives a normal generic owner for admitted declarations; explicit export
  remains useful for naming, overload conveniences, BCL projections, and real
  semantic adapters, not to duplicate an already truthful owner.
- The candidate remains free to improve callable layouts and value provenance
  through their shared ADR without reopening the class-owner decision.
