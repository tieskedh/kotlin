# Generic-owner atomic cutover checkpoint (2026-08-17)

## Outcome

The first public-owner migration checkpoint records **no-go for now**.
Kotlin-owned generic classes keep the accepted non-generic production owner.
The true CLR-generic architecture remains the intended direction for every
declaration whose complete Kotlin contract is representable, but the current
evidence does not yet authorize replacing the production ABI.

This is a checkpoint decision, not a conclusion that CLR generics are too slow
or intrinsically incompatible with Kotlin. Schema 20 and the hostile products
establish that one `C<T>` TypeDef, true `!T` fields, exact ancestry, one object
identity, and a complete non-generic semantic capability are physically
possible. What is still missing is one complete Kotlin-emitted product which
uses that model through lowering, CIL emission, self-describing DLL/KLIB
binding, separate consumption, reflection, Runtime, and Stdlib together.

## Why the generated candidate is not the production rehearsal

The hostile and OctoTree candidates are generated C# physicalizations of a
compiler-derived, production-inert family record. They are valuable because
they validate the selected CLR metadata and behavior independently of today's
erased emitter. They do not prove that normal Kotlin IR bodies and all compiler
bridges have already been transformed to those member families.

In particular:

- detached typed, semantic, and capability prototype members have exact
  signatures but are deliberately absent from `IrClass.declarations` and do
  not contain production bodies;
- the test fixture, rather than a general production admission pass, selects
  and physicalizes the complete hostile/OctoTree family;
- normal class registration forces every Kotlin generic class to CLR arity
  zero and the self-describing physical binding records that erased owner;
- Runtime, Stdlib, external consumers, casts, reflection, and compiler bridges
  therefore still agree on the old production epoch; and
- generated C# size and body lowering cannot be compared with the complete
  Kotlin DLL/KLIB product.

Changing only TypeDef arity would create the previously rejected half-model.

## Rehearsal probes

Two temporary, uncommitted probes changed only local TypeDef registration and
were removed immediately after diagnosis.

The global probe gave every Kotlin-owned generic class its natural CLR arity
while leaving all existing lowerings and bindings unchanged. Platform
production failed before the selected test. The failures covered the exact
cross-cutting seams which an atomic migration must replace together:

- ordinary and generated member calls whose receiver still denotes the erased
  declaring owner rather than one constructed owner;
- Common collection canonical bridges, companion/static holders, lambdas,
  coroutine support, and Runtime/Stdlib helper owners;
- open-nullable nested carriers which have no fixed constructed CLR type;
- type tests which would incorrectly make a closed construction Kotlin runtime
  classifier identity; and
- downstream declarations evicted after one missing generic owner.

A second probe kept Runtime and Stdlib erased and gave only the simple
`Box<T>`/generic-inheritance test cluster natural CLR arity. It reached normal
test compilation, which confirms that the existing GenericParam, constructed
type, and member-reference infrastructure is useful. It then failed on the
first owner-dependent override seam: existing covariant-return bridges for
`Box<Int>` and `Box<String>` attempted to convert concrete `int32`/`string`
results to the open `!0` owner parameter. Those bridges were correctly formed
for today's erased contract; they cannot be repaired after emission. Typed and
semantic override families must be selected before bridge lowering.

The probes changed no committed source and no accepted test expectation.

## Decision boundary

The target must not merge a public `C<T>` TypeDef, per-owner switch, annotation
escape hatch, or KLIB schema row from this checkpoint. `DotNetName` or a future
C#-export naming annotation cannot close the missing semantic lowering or
identity work; public export naming remains an independent interop design.

The next owner checkpoint is reopened only by one coherent rehearsal which:

1. builds the complete family from compiler IR without fixture-name selection;
2. materializes typed bodies, semantic hooks, capability dispatchers, fields,
   constructors, properties, defaults, overrides, and direct `super` targets
   before ordinary bridge selection;
3. changes local and external type mapping plus the self-describing physical
   binding epoch in the same diff;
4. compiles Runtime, Stdlib, hostile, and representative Kotlin applications
   as real Kotlin-produced `C<T>` products on both profiles;
5. proves casts, `as?`, classifier normalization, callable reflection, C#
   construction/subclassing, artifact skew rejection, and one-state behavior;
6. repeats JIT, ReadyToRun, trim, NativeAOT, and Framework 4.8 measurements on
   the complete Kotlin products; and
7. rehearses the exact inverse rollback without dual owner identity.

Until that complete tranche exists, the honest choice is to keep the erased
production epoch, retain schema-20 architecture tests as regression evidence,
and continue growing ordinary language and application breadth. That work must
avoid adding a new assumption that Kotlin generic owners can never become
`C<T>`.
