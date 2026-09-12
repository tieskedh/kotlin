# Nominal value-class inputs of generic class-slot adapters

- Date: 2026-09-12
- Reviewed base: `9ada71a395d04a3768b15cdde64fb30cfe2decb6`
- Scope: selected MethodImpl input representation and nullable unboxing
- Production owner ABI: erased; generic-owner candidate remains opt-in
- Physical ABI 71, artifact schema 22 and surface 62 are unchanged

## Reproduced failures

The existing `covariantBridgeSpecialNominalInput.kt` candidate failed while
compiling a separately produced generic base and a specialized subclass. Its
inherited `!K` MethodDef, bound as `Base<Id, String>`, received nominal `Id`.
The forwarding body still had logical `Id`, so value-class usage tried to box
that already nominal input as if it were its underlying `int32`. The same
fixture was already green in the production-erased checkpoint.

The broader custom fixture exposed two related missing compositions:

1. Ordinary non-collection generic bases could omit an input adapter entirely.
   Comparing substituted logical `Id` types made a nominal `!A` input look the
   same as the precise override's underlying `int32`; a base call reached the
   base implementation instead of the Kotlin override.
2. Unboxing a nullable reference-underlying value class invoked the non-null
   helper on null. This failed independently in both candidate and production,
   with a `NullReferenceException` in `NullableNameLeaf`.

The declaration probe also confirmed that the ordinary inherited member in the
first fixture has no optional producer `M` implementation record. The fix does
not manufacture such a record or claim universal BOUND MethodDef coverage.

## Authority and implementation

Kotlin logical override and value-class semantics remain authoritative. The
Common value-usage transformer provides the adaptation traversal. JVM retains
nominal boxes at generic/bridge boundaries; JS's value-usage lowering queries
the actual parameter representation, and Wasm extends that lowering. Native
likewise distinguishes nominal reference and underlying value representations.
The CLR-specific fact is that a constructed `!A` denotes the nominal value-class
argument, not its underlying calculation carrier.

The selected covariant bridge relation records an early nominal-use obligation
for each bare owner parameter closed to a value class. It also accounts for
that physical difference when deciding whether an adapter is needed. This is
not new signature authority: the emitter independently binds the inherited
MethodDef through the actual receiver graph, checks the input is a real owner
binder, and checks the bound input equals the expected nominal carrier.
An inconsistent obligation fails closed.

Body adaptation then distinguishes nominal parameters from exact underlying
ones. A runtime type test sees an explicitly widened object occurrence of the
nominal input; a precise body call invokes the ordinary unbox helper. Neither
the declared parameter nor the inherited MethodDef is rewritten. Other inputs,
the original receiver and its state are unaffected.

The general nullable-unbox operation evaluates its input once, preserves null
for a nullable reference-underlying value class and unpacks only a present box.
Its temporary records the proven nominal object view before placement. A
present non-null value class containing a nullable payload remains distinct
from an absent nullable value class.

These rules use selected declarations, slots and value-class representation,
not package, collection, member-name or IR-origin-name exceptions. No generated
foreign source, proxy, second receiver, shadow state or fabricated construction
is introduced. The durable obligations are in the
[physical-authority draft](../decisions/draft-adr-generic-owner-physical-authority.md)
and [value-class decision](../decisions/value-classes.md).

## Executable coverage

`covariantBridgeNominalCarriers.kt` covers ordinary generic bases with no
collection contract, local and separately compiled classes, deeper overrides
and nonvirtual super calls, multiple owner binders and input positions, and
primitive/reference/nullable/generic value-class substitutions. A concrete
already-underlying `Id` parameter is a negative against double unboxing.
An effectful generic producer checks null preservation and single evaluation.

The fixture-local validator inspects the original `Base<A, B>` MethodDef and
its `!B` field. Ordinary C# consumers inspect the private nominal bridge
signatures and override both the natural `Base<Id, string>` slot and the
precise Kotlin middle member. Direct CLR calls and separately compiled Kotlin
calls must observe the ordinary C# override and preserve receiver/input
identity without a hidden compiler-ABI authoring obligation.

The original special-input fixture retains Common's wrong-key/null barrier and
call-count checks. The production inverse rejects rehearsal epoch records and
generic owner identities in the new fixture. This does not claim general
incoming foreign/Kotlin declaration-graph closure or new value-class export ABI.

## Verification

Both gates passed on the same seven frozen source hashes:

- candidate: 4 FIR suites / 36 tests over both parsers and runtimes, plus
  24 backend suites / 411 tests;
- full production: 214 suites / 2,924 tests (411 backend, 6 physical CLI model,
  2,379 FIR2IR and 128 CLI/library integration);
- zero failures, errors or skips in every audited XML root; and
- all 36 candidate-fixture inverses individually present and green in the
  full production FIR matrix.

The full invocation explicitly reran the unfiltered FIR Test task without the
rehearsal property before completing the target aggregate. The unchanged
physical CLI model remained up to date; its full XML was audited too. The
production-selected nullable-unbox repair requires this fresh full gate; the
previous production checkpoint is not inherited for this feature.

Workstation evidence is in `D:\CodexTemp\nominal-class-slot-20260912`: original
and expanded failures, the erased null failure, focused green XML, seven frozen
source hashes, exact run/audit scripts, `candidate-36-backend-411.zip` and
`full-2924.zip`. The owning full invocation exited successfully before the
four-root audit. Full FIR completed at 03:08 and integration at 03:41 local.

## Census context

The source-built census on the reviewed base contained 215 diagnostic lines,
versus 225 on `5ff1de08d0`, after the two preceding promoted fixes. `AbstractMap`
and its generated views no longer failed there. The missing physical base view
of `AbstractMutableMap` remained downstream of its unsupported foreign input
contracts. No base edge or construction was fabricated to advance that census.
This nominal-parameter feature is an independently reproduced representation
repair, not evidence that the complete Runtime/Stdlib graph is closed.
