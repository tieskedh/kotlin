# ADR: DLL manifest and optional CLR-language tooling for Kotlin-owned interfaces

- Status: **Accepted for non-generic Kotlin-owned interfaces**
- Date: 2026-02-20
- Amended: 2026-08-27 to make the complete natural CLR contract the admission
  authority and to demote source generation to defaults and explicit adapters
- Scope: Kotlin/.NET library publication and source authoring from C#, F#, VB, and other CLR languages
- Related: [self-describing Kotlin/.NET DLLs](adr-self-describing-dotnet-library-dll.md), [profile-aware interface defaults](adr-profile-aware-interface-default-implementations.md), [foreign CLR generic identities](foreign-clr-generic-type-identities.md), [erased production generic-interface identity](generic-interface-erased-identity.md), [draft reified generic-interface owner](draft-adr-reified-generic-interface-owner.md), and [draft explicit C# export surface](draft-adr-explicit-csharp-export-surface.md)

## Decision boundary

This ADR accepts the manifest, physical-member binding, and default-forwarding contract for **non-generic Kotlin-owned interfaces**.

It does not accept a CLR-generic owner representation. In particular, historical `canonical` / `declared` / `exact` split-surface experiments are not normative here. Generic-owner representation and authoring remain governed by the [draft reified generic-interface owner ADR](draft-adr-reified-generic-interface-owner.md); production remains governed by the [accepted erased-identity ADR](generic-interface-erased-identity.md) until an atomic replacement is accepted.

Historical rehearsal reports remain available through the [archive index](../archive/README.md) and Git history. They are evidence, not additional rules.

The active analyzer still predates this amendment for several shapes and asks
for `partial` plus generated capability ABI outside a narrow natural fallback.
That is an implementation gap tracked in [`../../STATUS.md`](../../STATUS.md),
not a competing accepted authoring contract. The diagnostic may be removed only
after the producer and Kotlin caller can uphold this ADR without generated
completion.

## Context

Three different interop cases must not be conflated.

1. **Importing an existing CLR declaration.** A CLR interface or class from a C#, F#, VB, or IL library already has an authoritative CLR identity and member surface. Kotlin imports that metadata directly. It does not retrofit a Kotlin-owned ABI, semantic capability, source generator, or implementation manifest onto the declaration.
2. **Implementing a Kotlin-owned interface from another CLR language.** The Kotlin producer owns the emitted interface and must publish one complete, statically checkable natural CLR contract for the accepted non-generic case.
3. **Using optional authoring tooling.** An analyzer or source generator may forward defaults, emit diagnostics and boilerplate, or create an explicitly requested adapter, but it must not be the admission mechanism for an otherwise ordinary implementation.

The distinction matters because a source generator is a C# convenience, not a CLR type-system feature. F#, VB, Reflection.Emit, and hand-authored IL must see the same required contract in ordinary metadata.

Kotlin metadata remains the logical language authority. CLR metadata remains the physical declaration authority. The manifest connects the two where ordinary CLR metadata cannot express the Kotlin-specific relationship by itself.

## Decision

### 1. A Kotlin-owned interface publishes one complete natural CLR contract

For the accepted non-generic case, the producer emits one natural CLR interface whose ordinary MethodDefs and Property rows describe every member a foreign implementation must satisfy.

Consequently:

- an ordinary non-partial C#, F#, or VB type can implement the interface without Kotlin-specific code generation;
- omission or signature mismatch is rejected by the host-language compiler or CLR verifier, not discovered later by Kotlin runtime convention;
- a compiler-private helper or capability may accelerate an operation, but correctness cannot require a foreign author to know about or implement it;
- public methods found by name, arity, or reflection convention are not substitutes for a declared interface obligation;
- implementing the natural CLR contract is sufficient admission to the interface.

If Kotlin semantics cannot be derived from the complete natural contract and ordinary CLR dispatch, the producer must do one of the following:

1. publish an explicit, statically checkable adapter contract;
2. require an explicit user-selected adapter/export mechanism; or
3. issue a precise unsupported-interop diagnostic.

It must not silently require a generated hidden ABI, a partial declaration, a specially named public method, or a runtime name-and-arity probe.

This rule does not prohibit compiler-private semantic interfaces on Kotlin-produced objects. It prohibits making such an interface a hidden admission requirement for ordinary foreign implementations when the compiler can derive the required behavior from the natural MethodDef family.

### 2. Imported CLR declarations remain native CLR contracts

An existing foreign CLR declaration is consumed from its retained metadata:

- its TypeDef/TypeRef identity is authoritative;
- its declared variance, generic constraints, MethodDefs, Property rows, and MethodImpl rows remain authoritative;
- Kotlin does not manufacture a Kotlin-owned exact sibling or semantic capability for it;
- no Kotlin C#-implementation manifest is required from the foreign producer;
- no Kotlin generator is required to implement or consume it.

Adapters may still be requested explicitly where Kotlin source semantics exceed what the imported CLR contract represents. That is an importer or explicit-export concern, not evidence that the foreign declaration itself needs Kotlin ABI augmentation.

### 3. The producer DLL carries a versioned implementation manifest

Every compiler-produced Kotlin library DLL carries a versioned C#-implementation manifest in the same PE artifact. The private Kotlin metadata resource is not an input to the supported foreign-source authoring path.

Ordinary CLR metadata remains authoritative for:

- physical owner identity;
- MethodDef and Property signatures;
- generic arity and constraints where present on members;
- visibility and accessibility;
- MethodImpl relationships;
- interface inheritance.

The manifest records only Kotlin-specific facts that cannot be reconstructed reliably from those rows, including:

- logical Kotlin interface and member identity;
- exact physical MethodDef locators;
- Kotlin override and intersection relationships;
- default-body ownership and helper locators;
- Kotlin special-bridge or wrong-shape policy where applicable;
- the logical-to-physical association needed for diagnostics and optional tooling.

For this accepted non-generic contract, there is one natural owner identity. Generic rehearsal fields or split-owner roles in an experimental schema do not become accepted ABI merely by being present in a development manifest.

The manifest is not a second type system. A logical identity is an IdSignature or another compiler-defined stable identity, never a source-name heuristic. A physical member locator resolves against the open declaring TypeDef before any substitution and includes enough information to identify the MethodDef uniquely:

```text
assembly identity
+ declaring type identity and nesting path
+ method name
+ method generic arity
+ return type
+ ordered parameter types, including by-reference and custom-modifier shape
```

Resolution by name alone, or by name plus parameter count, is forbidden. IL quoting differences are normalized before comparison. Equivalent identities for the owning assembly are normalized; unrelated external assembly qualifiers remain significant. For `System.*` signatures only, the standard CLR facade set (`mscorlib`, `netstandard`, `System.Runtime`, and `System.Private.CoreLib`) may be unified through type forwarding across profiles.

The current logical-identity scheme is `kotlin-public-id-signature-legacy-v1`. Interface and member records use the public `IdSignature` rendered by `PublicIdSignatureComputer(DotNetIrMangler)`; runtime, Roslyn, and tooling do not create a competing logical identity. A derived intersection key identifies the physical composition record, not a new Kotlin declaration.

If the manifest schema is unsupported, malformed, ambiguous, stale relative to the DLL, or inconsistent with the actual CLR metadata, the consumer fails closed with a targeted diagnostic.

### 4. Accessibility is decided from emitted CLR metadata

The tooling uses the actual TypeDef and MethodDef visibility flags. Kotlin source visibility is not reinterpreted independently.

Internal implementation access is permitted only when the emitted assembly relationship makes it legal, including any producer-recorded friend relationship. A generated declaration must never depend on an inaccessible helper.

`@PublishedApi` alone does not make a compiler helper part of the foreign implementation ABI. A helper is usable only when the producer explicitly records it for that role and the CLR accessibility rules permit the access.

Nested owner paths are structural. Each containing CLR type, its accessibility, and any arity relevant to a locator are represented separately; source display names are not parsed to recover nesting.

### 5. Tooling is optional and never the admission authority

The supported analyzer/generator artifact targets `netstandard2.0` as a tooling-host contract. It is a consumer build-time tool, not a runtime dependency, and that host target is independent of the producer's Kotlin/.NET profile. It consumes the referenced DLL and its embedded manifest. It does not require the producer KLIB or compiler checkout.

The tool may:

- diagnose a malformed or incompatible manifest earlier than runtime;
- generate a forwarder to a producer-recorded portable default helper;
- generate explicit adapters selected by the user;
- improve diagnostics for intersections and inherited member families.

The tool may not:

- make a hidden interface implementation necessary for correctness;
- implement compiler-private semantic capability on a foreign type merely as
  an optimization;
- turn `partial` into a requirement for implementing the natural interface;
- discover obligations from public-method naming conventions;
- copy a Kotlin method body into generated source;
- introduce a second receiver, wrapper, proxy, state store, or object identity;
- reinterpret retained CLR metadata.

If generation is unavailable or the source type is not partial, a type that already satisfies the natural CLR contract remains a valid implementation. It may lose an automatically emitted default forwarder or another source convenience, but not semantic admission.

Because source generators are language-specific, generated convenience cannot define semantics shared by C#, F#, VB, and IL. Any cross-language requirement must exist in CLR metadata or in an explicit adapter contract.

### 6. Logical override identity and physical MethodDef identity stay distinct

Kotlin override analysis determines which logical member is selected. CLR emission and foreign authoring determine which physical slot is implemented.

The manifest therefore preserves both identities. Consumers:

- compare complete physical signatures after applying the actual owner substitution;
- preserve distinct logical obligations even when display names coincide;
- may let one source body satisfy multiple coincident CLR slots when the CLR permits it;
- otherwise require explicit adapters or reject the unrepresentable collision;
- never merge obligations solely by method name or source declaration spelling.

Once emitted, a MethodDef signature is physical authority. Later logical specialization must use an adapter or MethodImpl relationship; it must not pretend that the already emitted slot has a different signature.

### 7. Kotlin properties remain real CLR properties

A Kotlin property is represented by its CLR Property row and accessor MethodDefs. Tooling binds the accessors by exact locator, not by deriving `get_` or `set_` names.

Getter and setter default ownership are tracked independently. A generated convenience layer may forward either accessor to a producer-recorded helper, but it must not infer C#-specific `init`, `required`, or backing-field semantics that Kotlin did not declare.

Body ownership remains single:

- the user-authored implementation, or
- the producer-recorded default body/helper, or
- the runtime's direct interface default where the target profile supports it.

No adapter may create shadow property state.

### 8. Defaults are profile-aware and body-single-owned

The [profile-aware defaults ADR](adr-profile-aware-interface-default-implementations.md) remains authoritative.

For a portable profile without usable direct interface bodies, optional tooling may generate a forwarding implementation that calls the producer-recorded static default helper. It never copies the body and never re-derives the helper name.

For a profile with an applicable CLR default interface method, a redundant generated implementation is omitted. Default promotion is decided from the actual emitted interface graph, MethodImpl relationships, and producer-recorded locators—not from the consumer project's target moniker alone.

The manifest's Kotlin logical override keys select the most-derived default when several inherited declarations share a CLR signature. On portable profiles, generated adapters for the inherited slots forward to that one selected helper. On a modern profile, the adapter is omitted only when the actual CLR graph and MethodImpl rows prove an effective default. Missing, incomplete, or ambiguous physical mappings fail closed. If unrelated roots contribute competing defaults and Kotlin owns no resolving declaration, a foreign implementation supplies one explicit body; tooling does not invent a parent preference.

If no tooling is used on a portable profile, the foreign type implements the natural slot itself. That may call a documented helper explicitly, but no hidden convention is assumed.

Forwarders must preserve virtual dispatch, including calls from one default member to another. A default helper cannot bypass a foreign override merely because it is convenient to call the base implementation directly.

Portable helper types and methods are marked compiler ABI and `EditorBrowsable(Never)`, while remaining physically nameable by generated source where accessibility permits. Ordinary function helpers retain their recorded CLR name. Property-accessor helpers use physical-name-grammar 3:

```text
get_<property>__KotlinDefault__<logical-identity-digest>
set_<property>__KotlinDefault__<logical-identity-digest>
```

The public interface still owns the normal CLR Property row and accessors. Tooling consumes the exact recorded helper locator and never derives the helper name.

### 9. Manifest transport and trust boundary

The manifest is embedded in the producer DLL as a versioned managed resource named:

```text
Kotlin.CSharpImplementationManifest
```

The payload begins with the eight-byte magic `KDNCSM01`, schema version, little-endian payload length, raw SHA-256 payload digest, and bounded UTF-8 record payload. The exact schema may evolve only under explicit versioning. The current unshipped schema has no compatibility promise; an incompatible change increments the version and stale artifacts fail explicitly.

No sidecar file is authoritative. If an external staging file is used while building the DLL, packaging must consume it and remove it from the distributable output.

Consumer tooling treats referenced assemblies as untrusted input:

- parse with a metadata-only PE reader;
- never load or execute producer code during analysis;
- bound lengths, nesting, entry counts, and string-table sizes;
- reject duplicate or contradictory records;
- verify every recorded physical locator against the actual metadata;
- emit deterministic diagnostics for unsupported or corrupt payloads.

## Invariants

1. **Kotlin logical authority:** Kotlin IR/KLIB determines Kotlin declarations, override relations, defaults, and special semantics.
2. **CLR physical authority:** emitted or retained CLR metadata determines physical owners, signatures, variance, constraints, and MethodImpl relations.
3. **One complete natural contract:** an ordinary foreign implementation is admitted by a statically checkable CLR interface surface.
4. **No generated admission ABI:** optional generation may forward declared
   defaults or build an explicit adapter; compiler/runtime lowering owns
   optimization of compiler-derivable semantic routes.
5. **No convention dispatch:** public name/arity discovery is not a substitute for an interface slot.
6. **One object and one state:** no wrapper, proxy, or shadow state repairs an ABI mismatch.
7. **Exact physical binding:** manifest records resolve to one actual metadata declaration or fail closed.
8. **Single body ownership:** adapters forward; they do not duplicate Kotlin bodies.
9. **Imported CLR stays imported CLR:** native declarations are not retrofitted with Kotlin-owned ABI.
10. **Generic-owner neutrality:** this ADR neither accepts nor requires a generic split-surface representation.

## Rejected alternatives

### Require producer KLIBs in MSBuild

Rejected. A published DLL must be self-describing for downstream CLR authoring.

### Infer Kotlin ABI from generated names

Rejected. Names are implementation details and cannot carry overload, nesting, visibility, default, or override identity reliably.

### Use public method name/arity as a semantic fallback

Rejected. It is not a statically checked interface contract, is ambiguous under overloads, and is fragile under trimming and NativeAOT.

### Require a partial type or source generator for every implementation

Rejected. That excludes ordinary F#, VB, IL, and non-partial C# implementations and makes tooling—not CLR metadata—the admission authority.

### Preserve host-visible variance by moving required members to a hidden exact sibling

Not accepted by this ADR. That was a generic-owner rehearsal trade-off, not a proven non-generic authoring rule. Any future generic design must be justified in the [draft reified generic-interface owner ADR](draft-adr-reified-generic-interface-owner.md) and must still provide a complete, statically enforceable natural authoring contract or an explicit adapter boundary.

### Add hidden ABI by runtime convention

Rejected. Compiler-derivable semantic paths must be derived from declared MethodDefs. Genuinely non-derivable paths require an explicit adapter or diagnostic.

### Copy Kotlin default bodies into generated source

Rejected. It creates duplicate bodies, version skew, and divergent dispatch behavior.

### Treat a generator as a universal CLR implementation mechanism

Rejected. A generator is an optional C# productivity and explicit-adapter
feature. It is not a replacement for a sound CLR ABI.

## Consequences

- Existing CLR libraries remain ordinary native CLR dependencies.
- Kotlin-owned non-generic interfaces are implementable by ordinary CLR languages from the DLL alone.
- Missing obligations are found statically instead of by runtime reflection convention.
- The manifest remains necessary for exact Kotlin-to-CLR member identity, defaults, intersections, and diagnostics.
- C# tooling can remain useful without making C# partial declarations mandatory.
- Portable default forwarding remains possible without copying bodies.
- The accepted contract is deliberately silent about which CLR-generic owner representation should replace erased production ABI.
- Generic rehearsal schema fields and generated sibling surfaces may continue experimentally, but they have no accepted authority through this ADR.

## Validation required for changes to this contract

Changes must preserve at least these gates:

1. DLL-only consumption with no producer KLIB or compiler source checkout.
2. Ordinary non-partial C# implementation of a Kotlin-owned non-generic interface without source generation.
3. At least one additional CLR-language or direct-IL implementation proving that C# tooling is not semantically privileged.
4. Host-compiler rejection when a required natural member is omitted or has the wrong signature.
5. Exact binding for overloads, inherited members, properties, nested owners, and MethodImpl-backed slots.
6. Portable-profile default forwarding and modern-profile direct interface defaults with one authoritative body.
7. Virtual redispatch from Kotlin defaults into foreign overrides.
8. Internal/friend accessibility enforcement from raw metadata.
9. Fail-closed behavior for malformed, stale, ambiguous, or unsupported manifests.
10. Absence of runtime name/arity lookup, wrapper identity, and shadow state from the normal implementation path.

Any proposed generic-owner extension requires its own hostile Kotlin/C#/F#/VB, separate-compilation, trimming, NativeAOT, and inverse-rollback evidence under the draft generic-owner programme before it can amend this accepted ADR.
