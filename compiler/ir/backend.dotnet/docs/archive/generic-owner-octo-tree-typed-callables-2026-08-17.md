# Generic-owner OctoTree typed callables — 2026-08-17

## Scope

This checkpoint extends the schema-12 record-driven OctoTree candidate with its
first real CLR virtual family and the typed identity accessors for its already
physicalized fields. It remains a test-owned product. Production generic
owners, emission, DLL/KLIB metadata, Runtime, Common behavior, and the current
erased public C# surface are unchanged.

## Recorded callable family

The physicalizer joins the Node, Leaf, and Branch `set` prototypes to their
decoded logical member keys, then consumes the selected `TYPED_ENTRY` slots.
The family must be one exact instance MethodDef signature:

```text
bool set(int, int, int, !T, int)
```

The owner path changes with each declaring TypeDef, but the MethodDef name,
slot domains, carrier tree, visibility, and signature must agree. The Node slot
is public abstract. Leaf and Branch are public overrides whose dispatch remains
`OVERRIDABLE` in the record.

That last detail is intentional. Leaf and Branch are sealed/final TypeDefs, so
the TypeDef already prevents another override. Their MethodDefs need not also
carry the CLR final flag. The C# product therefore uses ordinary `override`,
not `sealed override`, preserving the producer record rather than inferring a
method flag from owner modality.

## Typed state access

No accessor name is selected from a source label. For each state operation the
physicalizer follows the record's logical-member binding to the exact typed
identity slot:

- `Leaf.value` READ returns `!T`;
- `Leaf.value` WRITE accepts `!T` and returns void; and
- `Branch.nodes` READ returns the exact recursive `Node<T>[]` field.

Each generated accessor reads or writes the same already-proved private field.
An object field, `System.Array`, copied vector, or second store cannot satisfy
the equality checks.

## Direct C# execution

A separately compiled C# consumer:

- mutates and reads `Leaf<int>` through the recorded true-int accessors;
- reads the same Branch vector through the recorded typed getter and reflection;
- invokes `set` through an `OctoTreeNode<int>` base reference whose runtime
  object is Branch, then observes the updated true `Node<int>[]`/Leaf<int>
  state; and
- invokes the same base slot on Leaf and observes the bounded throwing body.

The negative external subclass now implements the exact recorded abstract slot
it inherits. Its compilation must still fail at the `FamilyAndAssembly` base
constructor, so a missing abstract override cannot accidentally satisfy the
sealed-construction oracle.

The bounded C# method bodies are scenario oracles. All callable identities,
signatures, dispatch, visibility, override roots, and state access joins come
from the decoded artifact.

## Evidence

- FIR test-fixture compilation: green;
- focused OctoTree PSI/LightTree × Framework 4.8/.NET 10 × same/separate
  compilation: four XML suites, eight tests, zero failures, errors, or skips;
- combined hostile plus OctoTree matrix: four XML suites, 16 tests, zero
  failures, errors, or skips;
- whitespace/error audit: green;
- warm-cache strict aggregate: green in 628.4 seconds;
- direct aggregate audit: 190 XML suites, 2,238 tests, zero failures, errors,
  or skips.

## Remaining boundary

This is the typed entry only. The same strict family also records non-generic
capability interface slots and private-final explicit dispatchers with an
object carrier for `T`. The next slice must physicalize those records and prove
that compatible boxed values reach the most-derived typed override while an
incompatible object fails at the strict conversion. Broader semantic-hook
families and the outer Tree's semantic root remain later parts of this same
product.
