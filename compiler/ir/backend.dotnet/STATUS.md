# Kotlin/.NET development status

Read [`AGENTS.md`](AGENTS.md) before changing Kotlin/.NET code. It is the
self-contained bootstrap contract; this file owns only current branch,
verification, and work state.

## Current branch

- Branch: `dotnet`
- Upstream base: reviewed upstream commit `76ca9aa1af`
- Last integration checkpoint: the complete reviewed 179-commit range was
  rebased without semantic cleanup; later `origin/master` commits remain
  outside this deliberately selected boundary until they are reviewed
- Last completed feature: exact Common-contract projection onto standard
  CodeAnalysis metadata for explicit `net10.0` exports
- Maturity: high-quality pre-ABI prototype of an explicitly bounded Kotlin
  subset; no third-party binary compatibility is promised

This maturity statement measures the coherence and adversarial verification of
the admitted subset, not percentage completion of Kotlin as a language or
stdlib. The target is not close to 98% feature-complete: valued annotations
and annotation reflection, `KType`/member reflection, reified public APIs and
enum helpers, value classes, coroutines, ordinary mutable collections plus
broad Set/Map production, and Gradle/KMP product integration remain substantial
open programmes.

## Current green gate

The current exact-contract export production head passed:

```text
.\gradlew.bat :compiler:backend.dotnet:dotNetTest --rerun -q
```

The JUnit audit covered 24 fresh XML files and 1066 tests:

- 958 FIR, IL-text, and box tests
- 21 generated CLI tests
- 87 library-integration tests
- zero failures, errors, or skips

All four PSI/LightTree and Framework/CoreCLR runners execute the target-owned
contracts/scope corpus plus three selected upstream contract tests. The test
pipeline now runs the same shared pre-serialization KLIB lowerings as the CLI;
Common `SharedVariableBox` therefore replaces the obsolete test-only mutable
capture cell, and Common non-JVM `ThrowHelpers.kt` is a real stdlib compiler-ABI
dependency. Direct and installed stdlib products, including a separate local-
delegate consumer, prove that source closure without enabling the separately
parked `lateinit` lowering.

Source-defined exception subclasses now inherit standard `Throwable.message`
and `cause` through the universal `System.Exception` virtual slots. The
structural fallback accepts only fake overrides whose real chain remains in
the mapped standard exception hierarchy; a derived user-defined `message`
override remains ordinary Kotlin virtual dispatch. PSI, LightTree, Framework
CLR, CoreCLR, and an installed cross-assembly consumer cover both sides.

Ordinary Kotlin enums are one reference-class hierarchy, never CLR
`System.Enum` value types. Entry fields retain singleton identity and source
order; private entry subclasses implement bodies and abstract members;
`values()` is fresh, `entries` is stable, and `valueOf` uses exact Kotlin names
and failure semantics. Both frontend paths execute the complete adversarial
corpus on Framework CLR and CoreCLR. A portable library is separately consumed
by Kotlin and C# on both runtimes, including entry-field metadata, marker
attributes, virtual dispatch, static initialization, arrays, and widened
`Enum<*>`/`EnumEntries<*>` views. Reified enum helpers remain fail-closed.

The final gate additionally covers generic-interface erasure on both runtime
profiles. Both owner-dependent `C<T> : I<T>` and closed `C<T> : I<String>`
implement the one erased Kotlin-owned `I`. A modern default lives on that
erased DIM; portable profiles use its recorded helper/forwarder path. Neither
case publishes an implicit CLR `I<T>` sibling. PSI and LightTree agree on
every updated physical shape.

The formerly red generic-class probes are now positive regressions. Widened
Common `containsAll` compares a wrong-shaped element and returns `false`
without premature narrowing, while mutation through an unchecked
`Box<String> as Box<Int>` changes the same object and fails only when the
result is later consumed as `String`. The same corpus covers direct and nested
owner-type inputs, argument identity/evaluation, and a three-level widened
override chain on both frontends and runtime profiles.

All compiler-argument, API, configuration-key, Gradle-option, and test-runner
generators owned by the affected upstream range were rerun through their
owning tasks and produced no tracked output changes. Upstream Test Federation
now treats the .NET FIR, IL-text, and box runners as compiler-domain tests
through the same shared target-specific runner pattern used by JVM, JS, Wasm,
Native, and JKLIB.

Focused evidence additionally covers component-complete packed-KLIB loading,
same- and cross-library inlining from prepared and main IR, all three KLIB
inliner modes, mutable capture and non-local control flow, compiler ABI and
friend access, stdlib-free diagnostics, reproducible direct/fallback stdlib
IR, explicit reified/suspend rejection, and every target/runtime profile. The
selected-graph closure additionally proves that an inline body from library A
binds exact public declarations and a nested inline body from explicitly
selected library B without a general linker; surviving B calls use B's exact
physical assembly while fully inlined A disappears as a runtime dependency.
Both prepared-IR and main-IR consumers reject an omitted B with named unbound
signatures before target lowering and leave no artifact. The
collection product now also proves empty Collection fast paths, exact
short-circuit and traversal counts, nullable/widened predicates, reverse List
search, inlined separate consumers, and direct CIL execution of all six
physical fallback methods on Framework CLR and CoreCLR. The classified
`CharSequence` carrier additionally proves unchanged `System.String` and
custom-implementation identity, shared operation/cast/type-test
classification, erased physical CLR bounds with authoritative KLIB bounds,
portable Kotlin-library consumption on both runtimes, and handwritten C#
implementation through the runtime manifest. The collection-to-array closure
additionally proves exact Common iteration, erased and typed results, nullable
and value elements, undersized allocation, oversized and empty destination
identity, non-Java tail preservation, covariant runtime vector identity,
negative-size failure, and hostile inaccurate-size behavior on Framework CLR
and CoreCLR.

The complete signed Common primitive-array family additionally proves exact
Kotlin-owned `BooleanArray`, `ByteArray`, `ShortArray`, `IntArray`,
`LongArray`, `FloatArray`, `DoubleArray`, and `CharArray` identities over their
private CLR vectors. Constructors, initializer order, literals, varargs and
spreads, direct and escaping specialized iteration, copies/content operations,
RTTI/casts, generic-array separation, bounds failures, portable Kotlin
libraries, and copy-free exact C# vector adapters execute on both frontends and
runtime profiles. In particular, Kotlin `ByteArray` projects as signed
`sbyte[]`/`int8[]`, never C# `byte[]`.

Concrete nullable primitive elements are now complete as ordinary invariant
generic arrays. `Array<Boolean?>` through `Array<Char?>` use exact closed CLR
`Nullable<V>[]` vectors and retain identity through literals, constructors,
generic substitution, nullable varargs, iteration, copies/content operations,
nested arrays, and exact casts on both frontends and runtimes. A portable
netstandard2.0 Kotlin library is consumed separately by Kotlin and Roslyn on
Framework CLR and CoreCLR; all eight natural C# `V?[]` signatures preserve
aliasing. Backend-reachable sentinels continue to reject open `Array<T?>`,
input projections, and value-vector covariance.

Star-projected generic arrays are now complete through one classified erased
view. Every exact reference, value, and nullable-value SZ vector widens to
`System.Array` without copying and retains identity for size, reads, iteration,
reference equality, and later exact casts. Runtime tests and checked/safe casts
share one SZ-array classifier; specialized Kotlin primitive-array wrappers,
rectangular CLR arrays, and rank-one non-zero-based CLR arrays remain outside
the Kotlin `Array<*>` identity. Portable netstandard2.0 Kotlin libraries are
consumed separately by Kotlin and Roslyn on Framework CLR and CoreCLR, while
input projections, open nullable elements, and value-vector covariance remain
negative.

Every Kotlin-owned ordinary generic class now has one canonical non-generic
owner, one erased runtime/virtual ABI, and one authoritative state. KLIB
remains authoritative for type parameters, bounds, variance, arguments,
projections, and nullability. Public/protected owner-dependent positions and
the current baseline storage use an accepted erased carrier, an erased upper
bound, or `object`; reads narrow only at their logical use site. Ordinary CLR
`castclass`/`isinst` over the one owner supplies Kotlin's declaration-erased
identity, including inherited and separate-library cases. The baseline private
layout is not an ABI freeze: removable measured specialization may later use
more exact CLR helpers or storage without changing any semantic observation.

Physical ABI 20 records that one erased generic owner plus producer-owned enum
entry fields. It retains the removal of class capability paths, class-member
bridge records, canonical class interfaces, ancestry classifiers, and typed-
dispatch probes. Imported CLR generics remain reified. Typed C# generic-class
export is a separate fail-closed product rather than an implicit second
implementation ABI.

An erased generic class also no longer fabricates a typed generic-interface
edge: both `C<T> : I<T>` and `C<T> : I<String>` implement the one erased `I`
when `I` is Kotlin-owned. Only an explicitly mapped host capability or imported
CLR interface may retain a separate typed edge. KLIB preserves `I<T>` and
method bounds such as `<R : T>`; the latter omit an impossible CLR
owner-relative constraint.

The complete collection-facing accumulator-fold family now uses the generated
Common bodies for `Iterable.fold`/`foldIndexed` and
`List.foldRight`/`foldRightIndexed`. Adversarial execution pins empty behavior,
nullable and widened values, left/right order, exact iterator protocols, index
association, capture, exception identity/timing, and non-local return. Separate
and installed consumers inline the packaged KLIB bodies, while handwritten CIL
executes every physical fallback on Framework CLR and CoreCLR. A discarded
cross-library fold result retains the authoritative erased-accumulator
`IMPLICIT_CAST`; no failed-cast optimization or new classifier was introduced.

The complete collection-facing receiver-seeded reduction family now likewise
uses the exact generated Common bodies for `Iterable.reduce`, `reduceIndexed`,
their nullable empty variants, and all four corresponding right-reduction List
forms. Physical methods preserve the Common `S, T : S` bound as a real CLR
generic-parameter constraint; open `S?` uses the accepted boxed-or-null fallback
slot while embedded KLIB remains authoritative. Adversarial execution pins exact
empty exception messages or null, singleton no-callback behavior, widened and
nullable accumulators, left/right order and index association, hostile iterator
protocols, operation-failure identity, and non-local return. Separate consumers
inline every packaged body, and handwritten CIL executes all eight fallbacks on
Framework CLR and CoreCLR. Binary inlining's explicit `Nothing?` nullable branch
reuses the existing bottom/null-carrier emission rather than introducing a cast
or classifier.

Common `Iterable.forEach` and `forEachIndexed` now use their exact generated
inline loops. The embedded KLIB retains `forEach`'s binary `HidesMembers`
directive: a separate hostile consumer with a same-signature member still
resolves and inlines the Common extension, without requiring a CLR runtime
attribute. Adversarial execution pins empty, singleton, nullable/value,
mutation, order/index, exception identity, stopping point, and non-local-return
behavior. The indexed body retains the Common overflow helper, while
handwritten CIL executes both physical void fallbacks and checks full callback
traces on Framework CLR and CoreCLR. The completed `apply`/contracts product
now unblocks exact Common `onEach` and `onEachIndexed`; they are not yet an
admitted generated batch.

Common `Iterable.first(predicate)` and `firstOrNull(predicate)` now use their
exact generated first-match loops. Adversarial execution pins empty and
no-match behavior, the exact Common `NoSuchElementException` message,
short-circuit traversal, nullable match versus absence, widened elements,
capture, predicate-failure identity/timing, and non-local return. Separate and
installed consumers inline only the predicate overloads while existing no-arg
fallback calls remain; handwritten CIL executes both new physical overloads on
Framework CLR and CoreCLR. Open `T` and boxed-or-null `T?` reuse their existing
physical slots.

Common last-match predicates now use all four exact generated bodies for
`Iterable.last`/`lastOrNull` and their `List` overloads. Iterable receivers scan
forward to exhaustion and preserve a separate found flag where Common requires
one; List receivers request `listIterator(size)` and short-circuit in reverse.
Adversarial execution pins both exact no-match exception messages, empty and
nullable-match behavior, full versus reverse traversal, hostile iterator
protocols, widened/value elements, capture, predicate-failure identity/timing,
and non-local return. Separate and installed consumers inline all four bodies,
while handwritten CIL executes all four physical fallbacks on Framework CLR
and CoreCLR. The open-`T` cast uses the existing checked generic result barrier;
failed typed uses remain an exceptional correctness path and are not optimized.

The first Common `@InlineOnly` batch now publishes 14 exact generated
declarations: List components 1 through 5, List `elementAt` and
`elementAtOrNull`, Iterable `find`, Iterable/List `findLast`, the two
first-non-null transforms, Collection `count`, and Iterable `asIterable`.
Their public logical declarations and bodies remain authoritative in embedded
KLIB, while each physical CLR MethodDef is assembly-visible and unavailable as
C# or cross-assembly fallback API. Separate, packaged, and installed Kotlin
consumers must inline every call. Tests prove direct/reverse access without
the wrong iterator, traversal and callback order, nullable and exception
behavior, object identity, non-local return, physical visibility, C#
inaccessibility, and absence of external inline-only calls on Framework CLR
and CoreCLR. The producer/consumer tests use an actual self-describing KLIB;
the same-frontend bootstrap box harness is not misrepresented as an external
library boundary.

Common `Iterable<T>.sumOf((T) -> Int)` now uses its exact generated body and
logical KLIB name. Its assembly-visible CLR fallback is named `sumOfInt`, the
explicit platform spelling already owned by the Common generator, because the
future selector-return overloads erase to the same CLR `Function1` parameter.
Separate and installed consumers inline the body and cannot call that method;
C# cannot bind it. Adversarial portable execution pins empty zero, wrapping
overflow, nullable/widened inputs, traversal and callback order, failure
identity, and non-local return across Framework CLR and CoreCLR. The Long and
Double selector variants remain outside the source product because their exact
Common declarations publish the still-parked type-inference annotation-class
closure; UInt and ULong additionally require unsigned value classes.

Common `Iterable.single(predicate)` and `singleOrNull(predicate)` now use their
exact generated bodies; Common defines no distinct List predicate overload.
Both retain the first match with a separate found flag and stop at the second
match. Adversarial execution pins zero, unique, and multiple-match behavior,
the exact Common no-match and multiple-match exceptions, second-match stopping,
nullable unique null, widened/value elements, capture, predicate-failure
identity/timing, and non-local return. Separate and installed consumers inline
both bodies, while handwritten CIL executes both physical fallbacks on Framework
CLR and CoreCLR. The existing open-`T` cast and boxed-or-null slot remain the
only physical adaptation; LINQ defaults and target-authored traversal were not
introduced.

Common `Iterable.none(predicate)` and `count(predicate)` now complete the
selected predicate-aggregate family alongside `all` and `any`. Exact Common
empty-Collection fast paths avoid iterator construction; `none` stops at the
first match, while predicate `count` consumes the receiver and calls Common
`checkCountOverflow` after each matching increment. Adversarial execution pins
fast paths, traversal/stopping counts, nullable and widened predicates, capture,
predicate-failure identity/timing, and non-local return. Separate and installed
consumers inline both bodies and retain exactly the required count-overflow
helper call; handwritten CIL executes both physical fallbacks on Framework CLR
and CoreCLR. No LINQ quantifier/count rewrite or target-owned loop was added.

The complete signed Common `Iterable.average` family now uses all six exact
generated bodies for Byte, Short, Int, Long, Float, and Double receivers. Each
physical fallback accumulates into `Double` in encounter order, increments an
`Int` count through Common `checkCountOverflow`, and returns `Double.NaN` for an
empty receiver. The logical overloads bind to the bounded Common/JVM platform
names `averageOfByte` through `averageOfDouble`; embedded KLIB remains the
authoritative logical `average` contract and no general .NET meaning was given
to `@JvmName`. Adversarial execution pins all six conversions, empty NaN,
floating-point order, full traversal, and iterator-failure identity. Separate
and installed consumers bind all six physical names, while handwritten CIL
executes every fallback on Framework CLR and CoreCLR. LINQ `Average`, wider or
checked counters, reordered summation, and target-owned bodies were not added.

The post-substitution reified-array audit now proves that every admitted
ordinary array carrier remains truthful after the shared inliner has replaced
a type parameter with a concrete type. The adversarial matrix covers reference,
scalar, nullable-scalar, classified `CharSequence`, generic-class, split-
interface, nested, star-element, primitive-array-wrapper, and `Throwable`
elements; empty, nullable, initialized, negative-size, vararg, and spread
operations execute on both FIR frontends and runtime profiles. These operations
reuse the ordinary `Array<E>` mapper and intrinsics. No reified-only token,
wrapper, or `object[]` fallback was added, and both public reified gates remain
closed.

The Common `KClassifier`/`KClass` floor and class literals are now complete
without equating Kotlin reflection identity with `System.Type`. Static
`C::class` and single-evaluation dynamic `value::class` produce one nominal
Kotlin runtime value whose classifier is exact where CLR identity is exact and
classified where Kotlin already has a broader or erased relation. This covers
signed scalars, `String`, `Any`, `Unit`, `Nothing`, primitive and generic
arrays, `CharSequence`, `Number`, mapped and custom exceptions, ordinary and
generic Kotlin classes/interfaces, local/anonymous names, Common `cast` and
`safeCast`, and declaration-erased generic-class ancestry. Equality and hashes
use normalized classifier identity rather than names; two same-named CLR
classes from distinct assemblies remain distinct. Exact Kotlin exception
constructor identity reuses weak identity-associated throwable state and never
wraps or mutates foreign `Exception.Data`. Portable Kotlin libraries are
consumed separately by Kotlin and Roslyn, installed stdlib products expose only
the public Common surface, and the retained `System.Type` bridge remains
compiler ABI. `KType`, `typeOf`, member/annotation reflection, annotation-class
code generation, and public reified declarations remain separate programmes.

Common `Comparable<in T>` now retains its full logical identity and recursive
bounds in KLIB while one object exposes the profile-selected canonical
`System.IComparable` and truthful typed `System.IComparable<T>` views. Kotlin
implementations fill both slots through the explicit Comparable mapping bridge
lowering; ordinary C# consumes either interface without an adapter. Logical
interface and type-parameter calls use one versioned semantic helper so String
comparison remains ordinal and Float/Double retain Kotlin NaN and signed-zero
ordering. Direct exact primitive operations keep their unboxed intrinsics.
Tests cover every selected Common scalar, custom and inherited implementations,
recursive bounds, contravariance, type tests, checked/safe/unchecked casts,
delayed mismatches, exact carrier preservation through inferred array common
types, physical MethodImpl rows, portable Kotlin consumers, and both canonical-
only and typed CLR foreign boundaries on Framework CLR and CoreCLR. Runtime
surface level 12 owns the helper; typed polymorphic fast paths remain unselected.

The builder, contracts, and Common abstract-collection tranches publish the
authoritative Common `Appendable`, complete `StringBuilder` including both
`buildString` declarations, generated `Iterable.joinTo`/`joinToString`, Common
`AbstractCollection`/`AbstractList`, the public contracts DSL/effect model, and
`Standard.kt` through `takeUnless`. Only Common's final `repeat` declaration is
projected out until its real `Int.until`/range/progression closure exists.
The Kotlin-owned builder wraps private profile-selected BCL storage without
exposing `System.Text.StringBuilder` in public or protected metadata; its
colliding `Any?` overloads have the stable physical names `appendAny`,
`insertAny`, and `appendLineAny`, while KLIB retains the Kotlin names.
`ArrayAsList` inherits the Common base and owns only its retained array, size,
and indexed access. Logical class covariance remains in KLIB while each
Kotlin-owned generic class has its single erased CLR owner.
Source and actualized/deserialized inner generic declarations normalize to the
same outer-first TypeDef arity, closing the separate-library path exercised by
the Common iterator and sublist implementations. The exact Common bodies also
closed general backend gaps for `Int`/`Long` bitwise shifts, Unit-valued effects
in value positions, smartcasts from open type parameters, and projected generic
array reads and writes; none is encoded as a builder-specific rewrite.

FIR consumes Common contracts for data flow, and embedded KLIB retains their
effects across library boundaries. The backend executes neither the DSL nor a
target-authored approximation: `contract` has an assembly-visible fail-safe
physical body under the existing inline-only ABI, while executable consumers
contain no DSL call. The complete Common `run`, `with`, `apply`, `also`, `let`,
`takeIf`, and `takeUnless` bodies preserve calls-in-place analysis, receiver
identity, exceptions, and non-local returns. `InvocationKind` uses the ordinary
Kotlin enum representation.

The exact first CLR contract projection is now complete as an additive export
view. FIR2IR derives a versioned neutral five-effect carrier from resolved
Common contracts; only an explicitly selected export consumes it. `net10.0`
emits the exact `System.Diagnostics.CodeAnalysis` TypeDefs supplied by
`System.Runtime`, while `net48` and `netstandard2.0` omit them because their
selected contracts do not contain those identities. The gate proves both FIR
parsers, every admitted attribute target and constructor payload, Roslyn
nullable flow with warnings as errors, overlap normalization, default-overload
parameter omission, absence on ordinary Kotlin MethodDefs and compound
effects, and a Kotlin consumer of a reassembled DLL after every derived
CodeAnalysis row was stripped. KLIB remains the independent authority.

## Current architecture

- `:core:language.targets.dotnet` owns the logical .NET platform and the
  `net48`, `netstandard2.0`, and `net10.0` target vocabulary.
- `:compiler:config.dotnet` owns generated compiler keys and target-policy
  validation without depending on FIR, IR, backend, or CLI code.
- `:compiler:frontend.common.dotnet` owns objective PE/ECMA-335 facts and
  physical CLR validation; FIR owns Kotlin interpretation.
- `:compiler:dotnet.imports` owns versioned, self-validating neutral carriers
  for selected foreign CLR linkage and the already-derived exact contract
  export subset.
- `:compiler:fir:fir-dotnet` owns foreign Kotlin projection and lazy FIR symbol
  construction without depending on backend or CLI implementation packages.
- `:compiler:fir:fir2ir:dotnet-backend` owns the narrow target-specific IR
  overridability rule for retained flexible CLR array declarations and derives
  the neutral exact-contract projection while resolved FIR and IR coexist.
- `:compiler:ir:backend.dotnet` owns IR lowering, CIL mapping/emission, and
  backend product construction.
- `:compiler:ir:serialization.dotnet` owns .NET KLIB IR serialization and the
  logical IR mangler shared with backend identity mapping.
- `cli-base` owns the .NET content-root carrier; .NET compilation no longer
  represents CLR roots as JVM classpath roots.
- Common and generated stdlib sources remain semantically authoritative.
  .NET supplies narrow actuals and irreducible CLR operations.
- Kotlin-produced libraries are self-describing DLLs. KLIB remains the exact
  Kotlin declaration contract; CLR metadata and standard attributes provide
  the truthful physical and foreign-language view.

## Active state

No implementation slice is half-landed. Common Int-selector `sumOf` is
published under the generated logical declaration and its pinned `sumOfInt`
physical spelling. The Common `@InlineOnly` physical ABI remains selected and
its first 14-declaration generated collection batch remains published with
assembly-visible physical bodies and mandatory external KLIB inlining. The
generated Common signed numeric averages remain published with
all six bounded physical names and exact KLIB bodies. The generated Common
first-match predicate pair remains published with
both physical fallbacks and inlinable KLIB bodies. The preceding iteration-
action pair remains published with both void
fallbacks and the authoritative `HidesMembers` compiler directive. The
receiver-seeded reduction family remains published with all eight fallbacks;
an inlined empty nullable branch uses the existing nullable-bottom carrier
path. The accumulator-fold family likewise remains published, and a discarded
substituted generic fold result performs its existing checked recovery before
being discarded. Kotlin-owned generic classes and interfaces use physical ABI
20's one erased owner; the superseded bounded typed-dispatch experiment
remains only as Git history and design evidence. Ordinary
non-reified inline bodies now
bind exact signatures throughout the complete frontend-selected dependency
graph. Resolution remains non-linking, and an incomplete graph fails at the
post-inline/pre-target-lowering boundary instead of crashing an arbitrary
lowering. The non-reified Common collection-to-array closure uses the exact
shared loops. Its narrow CLR actual reproduces a supplied vector's runtime
element type, retains sufficiently large destination identity without JVM's
Java-specific tail terminator, and keeps public reified `toTypedArray` outside
the admitted surface. The backend's
explicit erased-object cast to an open type parameter uses `unbox.any`; safe
generic casts remain unsupported. The Kotlin-owned builder, exact generated
joins, Common abstract bases, and migrated array-backed list are published.
The public Common contracts DSL/effects, ordinary `InvocationKind`, scope
functions through `takeUnless`, and both `buildString` declarations are now in
the same self-describing stdlib product. Runtime surface level 14 owns the
erased compiler mutable cell and the erased Kotlin collection-interface
surface.
The exact Common-contract export subset is additive and complete for
`NotNull`, `NotNullWhen`, `NotNullIfNotNull`, `DoesNotReturnIf`, and
`DoesNotReturn`. Its neutral carrier contains neither FIR/IR nodes nor the
authoritative contract graph; ordinary Kotlin declarations and profiles
without the exact standard TypeDefs remain physically unchanged.
Parameterless annotation classes are admitted generally; ordinary enums and
the non-reified `EnumEntries` core are now published. The
classified `CharSequence` carrier, Common collection predicates, and ordinary
inline-function boundary remain intact; reified and suspend inline are still
explicit errors. The nominal `KClass` floor is selected and published; it does
not imply `KType`, member reflection, annotation discovery, or reified support.

Parameterless marker annotation classes now use the shared Common annotation-
member generator on one concrete sealed CLR `System.Attribute` subtype. KLIB
remains authoritative for declaration identity, targets, retention, and
applications; only runtime-retained applications receive an additional exact
CLR row on class, constructor, method, property, field, parameter, getter, or
setter parents. Source/binary applications remain absent from CLR reflection.
Because every public declaration is one `System.Attribute` subtype, a C#-
authored application of a non-runtime marker is foreign CLR metadata rather
than a Kotlin-produced application; it does not alter KLIB retention. The gate
includes two compatible shared upstream annotation box tests in all four .NET
runners, plus target-owned CIL, C# reflection/application, and portable Kotlin
producer/consumer evidence. Valued annotations and annotation discovery remain
outside this foundation.

The general Common Comparable mapping is independently published and the enum
product consumes the same KLIB identity, canonical classifier, typed C# view,
and semantic operation boundary rather than an enum-private substitute.

The reified audit established that shared IR substitution is ready. Its
ordinary runtime prerequisites now include declaration-erased Kotlin generic
classes and classified star-projected arrays, and the complete admitted array-
operation substitution matrix has passed without a target-specific reified
representation. Public reified support remains parked while `KType`,
enum/valued-annotation reflection, final substituted type-test/cast, and
physical throwing-stub contracts remain unselected. Physically exact
non-generic reference casts are
complete for Kotlin classes/interfaces, imported CLR interfaces, strings,
`Any`, primitive-array wrappers, and exact CLR vectors without admitting closed
generic instances.
Boxed-scalar casts are now complete for all eight selected Common primitives:
exact boxed identity, nullable unboxing for checked nullable casts, and
`isinst` plus nullable unboxing for safe casts, with no numeric-conversion or
value-class widening. Ordinary runtime type tests now have an explicit
exact-carrier admission boundary and an adversarial matrix across both FIR
frontends and runtime profiles. Exact scalars, classes/interfaces, strings,
supported primitive-array wrappers, imported CLR interfaces, nullable forms,
smart-cast use, and single evaluation are covered; classified exceptions,
`CharSequence`, and erased generic interfaces retain their dedicated paths.
Closed `GenericInstance` checks remain forbidden as Kotlin identity; ordinary
Kotlin-owned generic-class tests instead use the one producer-recorded erased
TypeDef and return the same object.

All eight signed Common primitive-array wrappers are now complete through one
runtime registry and the symmetric .NET stdlib declaration surface. The new
three families retain exact `SByte[]`, `Int16[]`, and `Single[]` private
storage, remain distinct from `Array<Byte>`, `Array<Short>`, and
`Array<Float>`, and cross portable Kotlin-library and explicit C# export
boundaries without copying. Unsigned arrays remain deliberately outside this
specialized-wrapper closure; `Array<*>` now sees the wrappers only as non-array
objects and never exposes their private vectors.

The separate generic-array closure now admits all eight concrete nullable
primitive element types as exact `Nullable<V>[]` vectors and admits
`Array<*>` through their classified `System.Array` base without changing the
specialized-wrapper identities above. It does not admit open nullable
elements, input projections, or value-vector covariance. Those parked shapes
still require successful typed-use carriers rather than inference from the
star read-only view.

The erased generic-class closure covers final/open/abstract/sealed,
nested/inner, data, inherited, nullable/scalar, bounded/multiple owner
parameters, generic members, default arguments, projected and erased-overload
shapes. It additionally covers widened direct and nested generic-bearing
inputs, multi-level portable overrides, same-object mutation after an
unchecked cast, delayed incompatible reads, one physical owner, and absence of
an implicit CLR `C<T>` surface.

## Open architectural blockers

- Typed .NET export for Kotlin-owned generics remains a separate product
  programme. It may publish a facade, read-only interface, adapter, or
  same-object CLR subtype for export-created instances, but it must not
  reintroduce a second Kotlin runtime identity, competing state, or virtual
  ABI. Arbitrary existing instances require an adapter. The concrete export
  surface and identity policy remain open; the erased Kotlin runtime ABI does
  not.
- Private generic specialization remains on hold until core feature coverage,
  representative boxing/allocation/JIT/AOT measurements, and the concurrency
  and memory model can justify it. Scalar replacement and immutable/private
  shapes precede any escaped mutable typed-storage/deoptimization system.
- Common `repeat` remains outside the exact `Standard.kt` projection until the
  ordinary `Int.until`/range/progression closure lands; no target loop stands
  in for that dependency.
- KLIB-in-DLL and physical ABI codecs still need neutral serialization owners
  as those additional compiler/tooling consumers appear.
- Broad CLR property/member-state enhancement, `ref`/`out`, events, and
  collection-shaped params each require separate Kotlin-stability decisions.
- Foreign C# `Nullable<T>` signatures are nominal generic instantiations and
  remain outside the closed primitive importer until constructed-type identity
  is retained from the selected assembly graph through backend binding.
- Gate A and ABI-freeze work remain open; current prototype identities may be
  corrected rather than compatibility-shimmed.

## Next bounded work

1. Continue the Common collection programme by exact dependency closure,
   preferring families that exercise enum/contracts foundations or unlock
   ordinary application code without introducing target-owned algorithms.
2. Extend CLR contract projection only when a new standard attribute has an
   exact Common effect, stable target rule, verified profile identity, and the
   same strip-without-Kotlin-semantic-change evidence as the closed first set.

The post-rebase callable-reference probe found that common IR's new
`addBoundValueAtOverride` helper cannot directly replace the .NET lowering:
the shared helper discovers Kotlin-named `boundValueAt`, while the established
CLR runtime ABI deliberately exposes `BoundValueAt` as `protected final`.
Retain the local implementation unless the shared helper is separately
generalized to accept the exact override identity and member flags, with IL,
runtime-identity, and separate-library evidence proving no ABI change.

## Navigation

- Current sequencing and release gates:
  [`docs/programmes/way-forward.md`](docs/programmes/way-forward.md)
- Documentation and evidence index:
  [`docs/README.md`](docs/README.md)
- Collections programme:
  [`docs/programmes/common-collections.md`](docs/programmes/common-collections.md)
- Architecture ownership audit:
  [`docs/programmes/compiler-architecture.md`](docs/programmes/compiler-architecture.md)
- Durable representation decisions: [`docs/decisions`](docs/decisions)

Update this file when branch state, the latest full gate, active work, blockers,
or the next bounded items change. Put rationale in ADRs, future ordering in the
way forward, chronological history in Git, and executable evidence in tests.
