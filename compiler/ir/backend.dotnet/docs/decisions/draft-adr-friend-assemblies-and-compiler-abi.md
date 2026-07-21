# Draft ADR: Kotlin internal visibility, CLR friends, and public compiler ABI

## Status

Selected pre-ABI design, implemented for unsigned assemblies. Strong-name consumption and Gradle
model integration remain incomplete. Nothing has shipped, so the manifest schema and emitted
metadata may still be replaced atomically if those implementations expose a flaw.

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

The provisional manifest schema 4 stores the deterministic producer-authorized identity set in
`dotnet_friend_assembly_identities`. The low-level CLI spellings are
`-Xdotnet-friend-assembly=<identity>` on the producer and `-Xfriend-paths=<KLIB>` on the consumer.
They are implementation controls, not the eventual public build architecture. A future .NET
Gradle target must expose a structured association and wire both producer authorization and
consumer friend dependency from one model relationship, analogous in intent to Kotlin target
compilation association.

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
- Gradle association tests once the .NET compilation model exists.
