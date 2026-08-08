# Function declaration flags

- Status: Accepted (pre-ABI)
- Scope: `KFunction.isInline`, `isExternal`, `isOperator`, `isInfix`, and
  `isSuspend` on the admitted `KFunction0` through `KFunction3` closure
- Depends on:
  [`draft-adr-callable-and-reference-abi.md`](draft-adr-callable-and-reference-abi.md)
  and the declaration-owned callable signature decisions
- Does not enable: suspend callable references, external-function linkage,
  member enumeration, accessor objects, or `KCallable` visibility/modality
- Library ABI version: 23. Runtime surface level: 24.

## Cross-target contract

Common, JS, Wasm, and Native expose no function-declaration flags on
`KFunction`. JVM is the mature-target precedent for this deliberate platform
reflection extension. It reports five logical Kotlin declaration facts:
inline, external, operator, infix, and suspend. Constructors report all five
as false. Kotlin functions use serialized declaration metadata; Java methods
use importer-owned semantic enhancement and physical `native` evidence rather
than inferring Kotlin metadata from emitted bytecode.

.NET has enough declaration IR to provide the same surface without making CLR
reflection authoritative. The extension is useful on every supported
`KFunction0` through `KFunction3`, including the zero-argument form; arity does
not own or alter any flag.

## Decision

### Use the reflected declaration

The exact `reflectionTargetSymbol` selected while constructing a callable
reference is the only flag source. For a Kotlin declaration, its flags come
from source or KLIB-deserialized IR. For an imported CLR declaration, only the
FIR importer may translate exact retained metadata into Kotlin semantic IR.
The backend never reopens a DLL, searches a member, inspects a generated
adapter, or derives logical facts from CIL method attributes.

`operator` and `infix` use the resolved target status, including semantics
inherited by an override. Generated `invoke` bridges and inline-expanded
bodies are execution details and cannot replace the declaration target.
Constructors have no function modifiers and therefore return false for every
flag.

The currently admitted foreign CLR interface methods are ordinary abstract
declarations and report all flags false. A later P/Invoke, InternalCall, or
CLR-operator import may report another value only after the importer admits
that physical shape and records the corresponding semantic IR fact. It must
not be special-cased in callable-reference lowering.

### Publish one erased KFunction capability

The platform actual `KFunction<R>` declares the five properties once.
Synthetic `KFunction0` through `KFunction3` inherit them logically; the
physical target maps every arity to the same non-generic `Kotlin.KFunction`
interface. The existing private `FunctionReferenceBase.flags` carrier stores
five additional declaration bits selected from the exact target. Its five
virtual-final getters implement the interface slots through inheritance on a
generated direct reference. The base itself does not implement `KFunction`, so
an internal adapter that merely reuses the base does not acquire callable
reflection identity. There is no arity-specific API, per-reference getter
family, generic CLR reflection interface, or metadata-token lookup.

Runtime surface level 24 adds the five physical interface and base getters.
Library ABI 23 rejects old producer DLLs whose already-materialized references
lack the declaration bits; new consumers therefore fail before a reflective
property call can silently return stale values.

### Keep suspend and external execution closed

Publishing a declaration fact does not admit its execution model. Suspend
callable references remain rejected by the existing callable ABI gate, so all
currently constructible references report `isSuspend == false`. The getter is
present now to keep the `KFunction` surface coherent; a future suspend-callable
feature must make true values executable and test them through the same
property rather than add another reflection type.

Likewise, `isExternal` does not create .NET external-function linkage. A true
value becomes observable only for an independently admitted external
declaration whose importer or Kotlin frontend has already produced truthful
IR and binding.

## Design attack

### Read `MethodInfo` flags at runtime

Rejected. Kotlin inline, operator, and infix have no complete CLR encoding;
generated adapters may have different flags from their source declaration;
and runtime lookup would make derived physical metadata a second authority.

### Reuse callable execution/conversion bits

Rejected. Suspend conversion, Unit conversion, vararg conversion, and exact
execution capabilities describe the reference object's invocation path, not
the reflected declaration. Conflating them would make flags change under an
adapter even though JVM declaration reflection does not.

### Add properties separately to every KFunction arity

Rejected. Arity belongs to invocation. Duplicating the declaration surface
would grow logical and physical ABI, invite drift, and disagree with the
shared `KFunction<R>` model used by the mature target.

### Infer foreign operator names in the backend

Rejected. JVM performs foreign operator enhancement in its frontend/import
model. If .NET admits CLR operator methods or Kotlin-convention methods, that
mapping must be reusable by normal calls and callable reflection alike.

## Invariants

1. `reflectionTargetSymbol`, not a generated `invoke`, owns every flag.
2. KLIB/importer semantic IR is authoritative; CLR metadata is at most exact
   importer evidence for a foreign declaration.
3. `KFunction0` through `KFunction3` inherit one shared property and base-getter
   contract.
4. Constructors report all five flags false.
5. Resolved inherited operator/infix semantics survive separate compilation.
6. Publishing `isSuspend` and `isExternal` does not admit their execution
   features.
7. No runtime member lookup, token lookup, or modifier reconstruction occurs.
8. Old producer and runtime surfaces fail at version validation.

## Verification

The gate covers an explicitly typed `KFunction0`, ordinary `KFunction3`,
inline, inherited operator, infix, constructor, and negative flag shapes;
both FIR parsers and CLR profiles; emitted interface calls, base flag bits, and
inherited final getters;
consumer-created references from KLIB; producer-created references crossing a
DLL boundary; ordinary foreign CLR interface methods; direct C# property use;
packaged-versus-source stdlib equality; stale library/runtime rejection; and
the full audited aggregate.
