# OctoTree metadata/reflection product checkpoint (2026-08-17)

## Scope

This checkpoint closes the production-inert schema-12 OctoTree whole-family
metadata and reflection gate. It changes no production Kotlin owner, DLL/KLIB
schema, Runtime/Stdlib contract, Common semantics, or public C# export.

The decoded four-owner artifact now has an operational inverse reflection
join. KLIB first selects the logical classifier. Within that classifier, every
recorded typed entry, semantic/capability dispatcher, capability-interface
slot, and default helper normalizes by its complete physical owner/name/
signature identity to one logical Kotlin callable. Producer-private state
methods and foreign methods return no callable; capability TypeDefs return no
Kotlin classifier.

Classifier context is mandatory. The hostile multi-level inheritance product
proves that a derived owner may reuse its base capability TypeDef and inherited
private dispatcher while retaining a distinct logical override declaration.
An artifact-global MethodDef-to-callable map would therefore be incorrect.

## Raw metadata product

Each separately compiled Framework 4.8 or .NET 10 candidate DLL is read by an
independently compiled net10 `System.Reflection.Metadata` executable. The
reader consumes the PE/CLI tables directly; it does not consume textual IL,
invoke ILAsm, load the candidate for semantic execution, or infer rows from C#
source names.

The inspector requires the candidate namespace to contain exactly:

- four open generic implementation TypeDefs and four non-generic capability
  interface TypeDefs;
- four ordered owner GenericParam rows with their exact attributes and
  constraints;
- the exact base type and direct capability InterfaceImpl on every owner;
- every recorded/private/scenario Field row with exact carrier and flags;
- every constructor and MethodDef with exact signature, access, static,
  abstract, virtual, and final flags; and
- every explicit capability MethodImpl declaration/body pair, with no extra
  row in any of those per-TypeDef tables.

For this bounded product that is eight family TypeDefs, four GenericParams,
four InterfaceImpls, eight MethodImpls, four fields, and 36 family MethodDefs.
Framework Roslyn additionally embeds exactly
`Microsoft.CodeAnalysis.EmbeddedAttribute` and
`System.Runtime.CompilerServices.RefSafetyRulesAttribute`; net10 embeds
neither. The assembly-wide TypeDef set is profile-exact, so no other nested,
closure, or helper type can escape the family-namespace check. The declaration-
independent `__scenarioDepth` field and `ScenarioNumber` helper are included
in the exhaustive product check but remain explicitly excluded from generic-
owner ABI evidence.

The raw audit found a real prior false positive: the artifact recorded Leaf
and Branch `ToString` overrides, but Branch did not emit its MethodDef and the
execution oracle passed only because inherited `System.Object.ToString()` was
non-empty. Both exact override slots now materialize with bounded executable
bodies, and unexpected or missing MethodDefs fail the table audit.

## Runtime evidence

Raw metadata is additional evidence, not a replacement for execution. The
existing separately compiled consumer still runs on Framework CLR 4 and .NET
10 and proves the one Tree/Branch/Leaf graph, true `T` and `Node<T>[]` state,
semantic object root, pre-mutation incompatible-input failure, override
dispatch, interface maps, and external Tree subclass behavior.

The Framework candidate is inspected as Framework metadata and then executed
on Framework CLR 4. The net10 metadata reader does not substitute CoreCLR
semantics for that execution lane.

## Verification

- focused OctoTree PSI/LightTree x Framework 4.8/.NET 10 x same/separate
  matrix: 4 XML suites, 8 tests, zero failures/errors/skips in 128.9 seconds;
- combined hostile plus OctoTree matrix: 4 XML suites, 16 tests, zero
  failures/errors/skips in 155.5 seconds; and
- final strict aggregate: 636.5 seconds; direct audit covers 190 XML files
  and 2,238 tests with zero failures/errors/skips.

## Remaining boundary

This closes reflection normalization for the detached candidate; it does not
authorize production owner cutover. The next hard product must compose the
accepted generic-owner family with the ordinary declaration/body closure.
It must record or otherwise truthfully emit private non-KLIB helpers such as
`Branch.canClusterize`, declaration-independent state such as `depth` and
`actual`, and the complete original algorithms without scenario substitutes.
Only that semantically complete paired product can become representative
allocation/dispatch measurement input.
