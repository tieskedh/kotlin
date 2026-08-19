# Generic-owner contravariant construction stability

## Result

Nested generic-owner carrier selection now handles the input-variant dual of
the covariant value-carrier mismatch without erasing the open outer owner.

The hostile relation is:

```text
Consumer<Any?> -> Consumer<Int>              Kotlin contravariance
Consumer<object> -/-> Consumer<int>           CLR value-type variance
Box<Consumer<Int>>                            becomes Box<object>
```

The object in `Box<object>` is still the original Kotlin consumer. A read
selects its non-generic semantic capability only when `consume(Int)` is
invoked; identity and storage do not fabricate `Consumer<int>`, add a wrapper,
or duplicate state.

The paired reference-only control is:

```text
Consumer<Animal> -> Consumer<Cat>             Kotlin and CLR contravariance
Box<Consumer<Cat>>                            remains exact
```

`RehearsalNestedBox<T>` and its separately compiled counterpart still expose
one physical `!T` field. Thus this decision changes only the unstable closed
instantiation, never all `Box<T>`, `List<T>`, or generic fields.

## Compiler corrections

The input-variant construction is unstable when its logical argument has an
exact supported primitive or nullable-value CLR carrier. Reference-only
arguments retain their natural construction. Exact interface aliases also
record whether their proven Kotlin producer is emitted with the sibling
capability, allowing a later semantic view to cast the same object to that
capability without granting the same assumption to arbitrary imported C#
implementations.

Two composition bugs were exposed and closed:

- the natural `I<T>` interface MethodDef's dispatch receiver had been included
  in the general semantic-declaration scan, which made a call with no semantic
  target nevertheless expect the capability as `this`; and
- the emitter disabled its proper-CLR-value-subtype predicate when the current
  module owned no local capability, allowing a separate consumer to derive a
  different nested signature from the producer.

Natural interface receivers now remain typed, representation-aware routing can
remove an earlier conservative semantic fallback, and the cached subtype proof
is available in every rehearsal producer and consumer emitter without adding
epoch-off compiler work.

## Evidence

Same-module and separate-KLIB products exercise both the unstable
`Consumer<Any?> -> Consumer<Int>` case and the stable
`Consumer<Animal> -> Consumer<Cat>` control. Kotlin execution checks identity,
write dispatch, and state. C# reflection checks `Box<object>` for the unstable
factory, `Box<Consumer<Cat>>` for the reference-only factory, and the open
outer `!T` field.

PSI and LightTree execute on .NET 10 and .NET Framework 4.8. The same eight
sources also pass with the rehearsal epoch disabled, and both production
platform assemblies rebuild successfully without the rehearsal property.
The final normal production aggregate covers 190 XML suites and 2,287 tests
with zero failures, errors, or skips; its FIR and integration roots were
freshly written and the unchanged six-test physical-model root remained
up-to-date.

A global epoch-on aggregate is deliberately not claimed as green. It switches
unadmitted generic-owner families and existing production IL goldens beyond
this bounded gate; the attempted run produced those expected unrelated golden
and unsupported-shape failures. The scoped eight-lane epoch-on matrix is the
representation evidence, while the normal aggregate is the production
regression gate.

The fail-first sequence found three distinct false paths: a stable cat call
retained a sibling semantic route, the natural consumer MethodDef accidentally
used a capability receiver even after that route was removed, and a separate
consumer reconstructed the pre-existing `Comparable<Int>` return differently
from its producer. Each correction is structural and independent of source or
stdlib declaration names.

## Remaining boundary

This proof does not cover recursive open enclosing arguments, Kotlin value
classes, unsigned carriers, invariant/mixed/multi-parameter owners, or
arbitrary imported CLR structs. The next gate is the nested open argument.
