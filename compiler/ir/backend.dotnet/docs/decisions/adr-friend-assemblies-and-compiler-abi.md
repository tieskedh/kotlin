# ADR: Kotlin/.NET internal visibility, CLR friends, and public compiler ABI

## Status

Accepted pre-ABI design, implemented for unsigned assemblies. Cross-profile production
mechanically retains friend-dependent CLR surface in both executable variants. The built-in
Kotlin/.NET Gradle target owns structured compilation association; raw friend flags remain CLI
implementation controls. Strong-name consumption remains incomplete. Nothing has shipped, so the
manifest schema and emitted metadata may still be replaced atomically if those implementations
expose a flaw.

## 1. Other Kotlin targets

Kotlin's common module-visibility machinery grants internal source visibility to explicitly
declared friend dependencies:

- JVM supplies friend paths to the frontend, while ordinary JVM bytecode cannot express Kotlin's
  module boundary as a producer-authorized runtime access rule;
- JS and Wasm load friend KLIB modules through their KLIB and linker state; and
- Native supplies friend KLIBs to the frontend and linker, with target-specific symbol visibility
  handled by the Native backend.

KGP compilation association is the build-model owner of those relationships. Users do not have
to coordinate independent producer and consumer compiler flags for the ordinary associated
`main`/`test` case.

## 2. .NET platform difference

CLR assembly accessibility is enforced by the runtime and ordinary .NET tooling. A consumer-side
friend path can grant Kotlin source visibility, but it cannot make an `assembly` member callable.
The producer must also emit `InternalsVisibleTo` for the consumer's actual CLR assembly identity.

The Kotlin/.NET dependency is also a content-bound KLIB/DLL pair. Friend visibility is granted
from the exact metadata KLIB, while the sibling DLL owns the physical internal declarations. An
output directory is not a friend identity and must not replace the exact KLIB path.

## 3. Kotlin Common invariant

`internal`, `@PublishedApi internal`, public API, and private declarations retain their Kotlin
meanings. Association may grant an authorized compilation access to ordinary `internal`
declarations; it must not make them Kotlin-public, widen private declarations, or turn
compiler-linkable ABI into user API.

The same source relationship has the same Kotlin meaning on all three CLR profiles. Profile
selection may change emitted metadata and IL, but not which Kotlin declarations association makes
visible.

## 4. CLR validity

An unsigned friend is authorized by its assembly simple name. A signed friend requires the full
public key, not a public-key token. The producer authorization and consumer friend dependency must
name the same output identity, and self-authorization is invalid.

The built-in Gradle association therefore derives both sides from compilation-owned module names:

- the producer receives the associated consumer identity;
- the consumer receives the producer's exact generated KLIB as both a dependency and a friend;
- the producer task is an inferred dependency of the consumer task; and
- the compiler validates the KLIB/DLL content binding and producer authorization before FIR grants
  internal visibility.

## 5. Alignment with compiler architecture

Use a target-specific `KotlinCompilationAssociator` and friend-path resolver, as Native already
does where its physical dependency model differs from the default JVM-shaped association.
Retain the shared `associateWith` graph, compilation option conventions, task dependency
inference, and common FIR friend-dependency machinery.

Do not add CLR authorization to the common associator, expose raw friend flags as public Gradle
compiler options, or infer friend authority by scanning arbitrary output directories.

## 6. Core-team choice

Treat `associateWith` as the single logical relationship and derive both CLR sides from it. Keep
the extra producer authorization entirely inside the .NET target implementation. This is the
smallest platform-specific divergence that preserves both Kotlin module semantics and CLR runtime
validity.

Classifications:

- shared Kotlin friend-dependency semantics:
  **Correct direction**;
- target-specific associator and exact-KLIB friend resolver:
  **Correct direction**;
- producer-emitted `InternalsVisibleTo`:
  **Reasonable platform-specific divergence**;
- unsigned simple-name authorization before signing support:
  **Correct temporary implementation, but not a final design**;
- consumer-only friend flags or directory-shaped friend paths:
  **Architecturally wrong and should be changed**;
- strong-name key derivation and signed publication:
  **Deferred problem that must be recorded before the ABI becomes stable**.

## Semantic invariant

Kotlin has three distinct linkage surfaces that must not be collapsed:

1. an ordinary `internal` declaration is available only inside its Kotlin module and explicitly
   associated friend compilations;
2. a declaration needed by public inline code or another cross-assembly compiler protocol is
   Kotlin-internal but physically linkable by separately compiled Kotlin code;
3. a Kotlin `public` declaration is user API.

CLR accessibility is authoritative at execution and for ordinary .NET tooling. Kotlin metadata is
authoritative for Kotlin source visibility. Both descriptions must agree on which consumers are
authorized; a consumer-only friend switch must never manufacture producer authorization.

## Decision

### Ordinary internal declarations

Ordinary Kotlin `internal` types and members are emitted with genuine CLR assembly accessibility:
top-level types are non-public, members are `assembly`, and nested types use `nested assembly`.
The KLIB continues to record Kotlin `internal` visibility.

An associated or explicitly declared friend compilation is a two-sided relationship:

- the producer records a structured CLR friend identity and emits one
  `System.Runtime.CompilerServices.InternalsVisibleToAttribute` for it;
- the consumer records the producer KLIB as a friend dependency, not merely as an ordinary
  classpath dependency;
- before FIR sessions are created, the .NET pipeline resolves that friend path to the bound
  KLIB/DLL pair and verifies that the producer manifest authorizes the actual output assembly
  identity;
- only after that check does FIR grant Kotlin internal source visibility.

Manifest schema 4 stores the deterministic producer-authorized identity set in
`dotnet_friend_assembly_identities`. The low-level CLI spellings are
`-Xdotnet-friend-assembly=<identity>` on the producer and `-Xfriend-paths=<KLIB>` on the consumer.
They are implementation controls, not public Gradle compiler options. The built-in .NET Gradle
target exposes the relationship through ordinary compilation association and derives producer
authorization, the consumer's exact friend KLIB, dependency inheritance, and task ordering from
that single relationship. The target and compilation model independently own assembly naming,
profile variants, artifacts, compilation outputs, and task dependencies; friend authorization
does not define those concepts indirectly.

An ordinary C# assembly with the authorized CLR identity receives the same access to assembly
internals. That is the deliberate CLR meaning of `InternalsVisibleTo`, not an interop leak to be
hidden with Kotlin-only wrappers. An unsigned simple name is not a security boundary: another
assembly can choose it. Strong-name identities are the CLR mechanism when identity authenticity
matters.

### Strong-name identities

The identity grammar is `<assembly-name>` or
`<assembly-name>, PublicKey=<full-hex-public-key>`. `PublicKeyToken` is rejected: CLR friend
authorization for a signed consumer requires the full public key. The current compiler emits
unsigned assemblies and therefore validates consumers only against unsigned identities. The
manifest and custom-attribute serializer already retain long full keys without truncation so
signing can be added without changing the association model, but signed output identity and key
derivation remain a required implementation before strong-name consumption is claimed.

### Public compiler ABI

`@PublishedApi internal` declarations and compiler-generated cross-assembly entry points are CLR
`public`, remain Kotlin-internal in metadata, and carry
`Kotlin.Runtime.Internal.KotlinCompilerAbiAttribute` plus
`System.ComponentModel.EditorBrowsable(Never)`. Published classes/interfaces carry the marker on
the type; published functions, accessors, and constant fields carry it on the member. Public
Kotlin API and explicit C# export facades do not receive the marker.

This surface is public because CLR linking requires it, not because C# conventions redefine the
Kotlin API. `EditorBrowsable` is a tooling hint, not access control. The marker is the mechanical
contract used by ABI inspection and future tooling.

### Private declarations

Friend authorization does not justify widening source-private declarations. Private members stay
private and illegal cross-type accesses use compiler-generated assembly bridges. The current
file-facade representation still emits a private top-level callable as an assembly member when
same-file generated types require it; general synthetic file accessors remain a pre-freeze cleanup
item. An authorized CLR friend can observe assembly bridges, so their compiler-generated naming
and marker policy must be audited before ABI freeze.

## Rejected alternatives

- **Make every internal declaration CLR-public and rely on Kotlin metadata.** This exposes an
  accidental C# API and prevents CLR verification from enforcing the module boundary.
- **Grant friend access only in the consumer frontend.** Generated IL then targets inaccessible
  members and fails at load/JIT time; it also lets a consumer claim authority the producer never
  granted.
- **Generate wrapper assemblies or identity bridges for friends.** This creates duplicate surface,
  complicates object/type identity, and does not match ordinary CLR tooling.
- **Treat every compiler-required declaration as ordinary internal.** Public inline and other
  cross-assembly compiler protocols would not link from a non-friend consumer.
- **Use a public-key token in `InternalsVisibleTo`.** The CLR contract requires the full public
  key for signed friend identity.

## Required validation

- manifest encode/decode and deterministic ordering of unsigned and signed identities;
- successful IL assembly with a long full-key custom-attribute payload;
- authorized Kotlin friend source access and execution across a KLIB/DLL boundary;
- rejection before FIR/codegen when the producer does not authorize the output identity;
- rejection by FIR when the producer authorizes the name but the dependency is not declared as a
  consumer friend;
- ordinary C# negative access, trusted-friend C# positive access, and reflection inspection;
- `@PublishedApi internal` type/member/field accessibility and marker inspection;
- cross-profile retention of friend-dependent internal types, members, hierarchy, generic shape,
  and attributes, with a negative executable-profile variant that omits portable internals;
- Gradle model tests proving that association wires producer authorization and producer-task
  dependencies, plus an integration build in which the associated test compilation resolves and
  calls an ordinary internal producer declaration through the exact KLIB/DLL pair.
