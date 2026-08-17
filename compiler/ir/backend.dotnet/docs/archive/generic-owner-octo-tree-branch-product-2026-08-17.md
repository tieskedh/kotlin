# Generic-owner OctoTree Branch product — 2026-08-17

## Scope

This checkpoint extends the schema-12 record-driven OctoTree candidate from the
sealed Node/Leaf construction edge to the recursive Branch state and both of
its constructors. It remains a test-owned physical product. Production generic
owners, emission, DLL/KLIB metadata, Runtime, Common behavior, and the current
erased public C# surface are unchanged.

## Physical product

The test facade canonical-encodes and decodes the complete four-owner artifact
before generation. The decoded Branch record supplies:

- the public final TypeDef identity and owner GenericParam;
- the exact constructed `Node<T>` base edge;
- one public base-delegating parameterless constructor;
- one public `(T, int)` constructor and its exact `this()` edge;
- one private true `Node<T>[]` field; and
- one fixed zeroed eight-element initializer attached only to the base
  constructor root.

The product fails before source generation if any of those identities,
signatures, delegation targets, carrier types, initializer roots, visibility,
or constraints differ. It therefore does not reconstruct an ABI from
`Branch`, `nodes`, source labels, or generic arity.

The generated bounded C# body is a scenario oracle, not ABI authority. The
parameterless constructor performs the recorded `new Node<T>[8]` initializer.
The `(T, int)` constructor delegates through the recorded `this()` edge and
executes the unchanged OctoTree behavior for that constructor: seven slots get
new recorded `Leaf<T>` instances and the excluded slot stays null.

## Direct C# evidence

A separately compiled consumer constructs both `Branch<int>()` and
`Branch<int>(7, 3)`. It proves by reflection and execution that:

- the open private field is an SZ array whose element is the recorded
  `Node<T>` construction and whose argument is Branch's own GenericParam;
- the closed field is exactly `Node<int>[]`, not `object[]`, `Array`, or an
  erased `Node[]`;
- each construction owns a distinct non-null vector of length eight;
- the parameterless vector is zeroed;
- the secondary constructor leaves index three null; and
- every other element is `Leaf<int>` whose true `int` field contains 7.

The existing positive Leaf and negative external Node-subclass probes remain
in the same product. Producer and consumers compile and execute independently
against .NET Framework 4.8 and .NET 10.

## Evidence

- FIR test-fixture compilation: green;
- focused OctoTree PSI/LightTree × Framework 4.8/.NET 10 × same/separate
  compilation: four XML suites, eight tests, zero failures, errors, or skips;
- combined hostile plus OctoTree matrix: four XML suites, 16 tests, zero
  failures, errors, or skips;
- whitespace/error audit: green;
- warm-cache strict aggregate: green in 641.6 seconds;
- direct aggregate audit: 190 XML suites, 2,238 tests, zero failures, errors,
  or skips.

## Remaining boundary

Node/Leaf/Branch declaration, constructor, and typed-state topology is now a
real product, but its callable families are not. The next slice must
physicalize the recorded Node abstract member plus Leaf/Branch overrides and
typed/capability state access without changing their MethodDef identities or
semantic authority. The outer Tree's private semantic root, public operations,
real application driver, and complete C# surface remain later parts of this
same product—not permission to select another owner or production ABI.
