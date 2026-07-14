package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.types.Variance

/**
 * The IL type of a value in a signature or on the evaluation stack. A sealed hierarchy rather
 * than an enum so that user-class types can carry their IL class name; the representation stays
 * structural on purpose so CLR reified generics can extend it later (never erasure).
 */
internal sealed class DotNetIlValueType(val nameInSignature: kotlin.String) {
    object Boolean : DotNetIlValueType("bool")
    object Int32 : DotNetIlValueType("int32")

    /** `kotlin.Long`. Mirrors the JVM backend's `long`; CLR `int64` occupies one stack slot. */
    object Int64 : DotNetIlValueType("int64")

    /**
     * `kotlin.Double`. Mirrors the JVM backend's `double` (CLR `float64`). `Float`/`float32` is
     * deliberately deferred together with `Byte`/`Short`.
     */
    object Float64 : DotNetIlValueType("float64")

    /**
     * `kotlin.Char`. Mirrors the JVM backend's `char`: a 16-bit unsigned code unit that lives on
     * the evaluation stack as an `int32` (the CLR, like the JVM, has no sub-int stack values).
     */
    object Char : DotNetIlValueType("char")
    object String : DotNetIlValueType("string")

    /**
     * `kotlin.Any`/`kotlin.Any?` as a STORAGE type: CLR `object` (`System.Object`), the root
     * reference type. Every reference type widens to it for free ([isDotNetAssignableTo];
     * probe-verified, `nullprobe_s8`: string into object locals/params/fields, `ldnull`, and the
     * type-agnostic reference `ceq`), while value types ([Boolean]..[Char] and [NullableValue])
     * reach it only through an explicit `box` coercion (see
     * [DotNetIlExpressionCodegen]'s coercion layer). Member calls on `Any` stay rejected — this
     * is storage-and-identity only, not an Any model.
     */
    object Object : DotNetIlValueType("object")

    /**
     * A CLR zero-based vector for one of the five supported primitive element types. The vector
     * is a reference type even though its elements are values, so nullable and non-null Kotlin
     * primitive arrays share this representation and widen to [Object] without boxing.
     *
     * The element-specific instructions are deliberately carried by the structural type: every
     * array producer and consumer then derives its signature and opcode spelling from one source
     * of truth. `arrprobe_s1` assembled and executed all five spellings on both CoreCLR and the
     * .NET Framework CLR.
     */
    data class PrimitiveArray(val elementType: DotNetIlValueType) :
        DotNetIlValueType("${elementType.nameInSignature}[]") {
        init {
            require(elementType.isSupportedPrimitiveArrayElement()) {
                "unsupported CLR vector element type ${elementType.nameInSignature}"
            }
        }

        val newArrayInstruction: kotlin.String
            get() = "newarr ${elementType.nameInSignature}"

        val loadElementInstruction: kotlin.String
            get() = when (elementType) {
                Boolean -> "ldelem.u1"
                Int32 -> "ldelem.i4"
                Int64 -> "ldelem.i8"
                Float64 -> "ldelem.r8"
                Char -> "ldelem.u2"
                else -> error("Internal .NET backend error: unsupported vector element $elementType")
            }

        val storeElementInstruction: kotlin.String
            get() = when (elementType) {
                Boolean -> "stelem.i1"
                Int32 -> "stelem.i4"
                Int64 -> "stelem.i8"
                Float64 -> "stelem.r8"
                Char -> "stelem.i2"
                else -> error("Internal .NET backend error: unsupported vector element $elementType")
            }
    }

    /**
     * A Kotlin `Array<E>` as a CLR zero-based vector. This stays a distinct structural type from
     * [PrimitiveArray] even when substitution produces the same CLR spelling: Kotlin
     * `Array<Int>` and `IntArray` are different invariant types, while CLR represents both as
     * `int32[]`. The mapper rejects that concrete source collision, but an open `Array<T>` must
     * still be able to substitute `T = Int` at a generic call site without losing its Kotlin
     * identity inside assignability checks.
     *
     * Typed element instructions work uniformly for reference tokens and open `!n`/`!!n`
     * tokens; `genarrayprobe_s1` assembles and executes both forms on CoreCLR and Framework.
     */
    data class GenericArray(val elementType: DotNetIlValueType) :
        DotNetIlValueType("${elementType.nameInSignature}[]") {
        val newArrayInstruction: kotlin.String
            get() = "newarr ${elementType.nameInSignature}"

        val loadElementInstruction: kotlin.String
            get() = "ldelem ${elementType.nameInSignature}"

        val storeElementInstruction: kotlin.String
            get() = "stelem ${elementType.nameInSignature}"
    }

    /**
     * A concrete nullable Kotlin primitive (`Int?`, `Long?`, `Double?`, `Boolean?`, `Char?`) in
     * an EXACT typed position: CLR `System.Nullable<T>` — the hybrid-representation decision
     * (see AGENTS.md "Nullability model"). Roslyn precedent: C# `int?` is
     * `valuetype System.Nullable`1<int32>` (corelib-qualified) in typed positions and collapses to
     * boxed-`int32`-or-null at the `object` boundary (the CLR does the collapse in `box`,
     * probe-verified `boxprobe_s3`/`nullprobe_s8`). [nameInSignature] doubles as the operand
     * spelling of every instruction touching the type (`newobj`/`call`/`initobj`/`box`),
     * probe-verified in every declaration and operand position (`boxprobe_s1`).
     *
     * CRITICAL emission rule (probe-verified, `boxprobe_s2`): the instance members
     * ([hasValueInstruction], [getValueOrDefaultInstruction]) require a HOME ADDRESS —
     * a freshly computed stack value MUST be spilled to a local first (`stloc`+`ldloca`); an
     * unspilled stack receiver assembles cleanly but is a FATAL, uncatchable CLR error
     * (0x80131506). Every emission site goes through
     * [DotNetIlExpressionCodegen.spillToSyntheticLocal].
     */
    data class NullableValue(val elementType: DotNetIlValueType) :
        DotNetIlValueType("valuetype ${CORE_LIB_REF}System.Nullable`1<${elementType.nameInSignature}>") {
        /** `newobj` of the `T -> T?` wrap; `!0` is the probe-verified spelling of `T` in member signatures. */
        val ctorInstruction: kotlin.String
            get() = "newobj instance void ${nameInSignature}::.ctor(!0)"

        /** The null test; call through a home address only (see the class KDoc). */
        val hasValueInstruction: kotlin.String
            get() = "call instance bool ${nameInSignature}::get_HasValue()"

        /**
         * The value extraction; call through a home address only. `GetValueOrDefault` (the
         * Roslyn choice) never throws — callers branch on [hasValueInstruction] first where
         * emptiness matters, so `get_Value`'s InvalidOperationException (the wrong exception
         * type for Kotlin `!!`) is never involved.
         */
        val getValueOrDefaultInstruction: kotlin.String
            get() = "call instance !0 ${nameInSignature}::GetValueOrDefault()"

        /** `initobj` producing the empty (`null`) value into an addressed local. */
        val initInstruction: kotlin.String
            get() = "initobj $nameInSignature"

        /**
         * The `T? -> Any?` boundary widening: the CLR collapses `box Nullable<T>` to
         * boxed-`T`-or-null (probe-verified for all five instantiations, `boxprobe_s3`,
         * `nullprobe_s8`).
         */
        val boxInstruction: kotlin.String
            get() = "box $nameInSignature"
    }

    /**
     * A user class emitted into this module, referenced assembly-locally — no bracketed
     * resolution-scope prefix (see [CORE_LIB_REF]) — through its already-rendered
     * [type reference][DotNetIlClassInfo.ilTypeRef] (`class 'demo.Point'`, or the nested
     * `class 'demo.Outer'/'Companion'` for a companion), the same convention the file facades
     * use. A nullable `C?` maps to the same reference type (CLR reference types are
     * structurally nullable, exactly like `string`).
     *
     * The value type carries the whole [classInfo] rather than just the rendered reference so
     * that [isDotNetAssignableTo] can walk the [base-type chain][DotNetIlClassInfo.baseType]
     * for reference upcasts (`Derived` used where `Base` is expected). Equality stays what the
     * old data class had — the rendered type reference, unique per class within one emission —
     * so two infos for the same class compare equal regardless of the base link's state.
     */
    class UserClass(val classInfo: DotNetIlClassInfo) : DotNetIlValueType("class ${classInfo.ilTypeRef}") {
        val ilTypeRef: kotlin.String
            get() = classInfo.ilTypeRef

        override fun equals(other: Any?): kotlin.Boolean = other is UserClass && other.ilTypeRef == ilTypeRef
        override fun hashCode(): Int = ilTypeRef.hashCode()
        override fun toString(): kotlin.String = "UserClass(ilTypeRef=$ilTypeRef)"
    }

    /**
     * A Kotlin exception class type-mapped onto a CLR exception type (see
     * [DotNetMappedExceptions]). [ilTypeRef] is the bare corelib-qualified reference — the
     * [CORE_LIB_REF]-prefixed `System.X`, the operand form a `catch` clause takes — while
     * [nameInSignature] prefixes it with `class` for signature positions; both spellings are
     * ilasm-probe-verified. A nullable `T?` maps to the same reference type, like [UserClass].
     */
    data class MappedClass(val ilTypeRef: kotlin.String) : DotNetIlValueType("class $ilTypeRef")

    /**
     * A reference to a type parameter of the enclosing generic declaration — CLR
     * ELEMENT_TYPE_VAR (`!n`, a class type parameter) or ELEMENT_TYPE_MVAR (`!!n`, a method
     * type parameter). The CLR identifies type parameters POSITIONALLY: the declared name is
     * decorative metadata, `!n`/`!!n` indices are authoritative (probe-verified, `genprobe_s1`:
     * the callsite signature keeps the `!!n` slots verbatim while only the `<...>` list is
     * substituted), which is why equality here is by index and kind alone. [upperBounds] carries
     * the stage-2 module-local class/interface constraints without changing token identity. An
     * unconstrained value supports exactly store/load/pass. A constrained one can additionally
     * dispatch through a bound with `constrained.` or box to a bound/`object`; it still is not
     * intrinsically reference-shaped because a CLR caller may instantiate an interface-bound
     * parameter with a value type.
     */
    class TypeParameter(
        val index: Int,
        val isMethodParameter: kotlin.Boolean,
        val upperBounds: List<UserClass> = emptyList(),
    ) : DotNetIlValueType(if (isMethodParameter) "!!$index" else "!$index") {
        /** Whether metadata guarantees this parameter is assignable (when boxed) to [expected]. */
        fun isConstrainedTo(expected: DotNetIlValueType): kotlin.Boolean =
            upperBounds.any { it.isDotNetAssignableTo(expected) }

        override fun equals(other: Any?): kotlin.Boolean =
            other is TypeParameter && other.index == index && other.isMethodParameter == isMethodParameter

        override fun hashCode(): Int = 31 * index + isMethodParameter.hashCode()
        override fun toString(): kotlin.String = "TypeParameter($nameInSignature)"
    }

    /**
     * An INSTANTIATION of a generic user class or interface of this module (`Box<String>`,
     * `Producer<Item>`): real CLR reified generics, the Roslyn shape — never erasure.
     * [nameInSignature] is the instantiation token
     * `class 'demo.Box`1'<string>` (the arity suffix lives INSIDE the quoted identifier, see
     * [DotNetIlClassInfo.ilTypeRef]; a suffix outside the quotes is an ilasm syntax error —
     * probe-verified, `genprobe_s2`/`_s2c`) and doubles as the operand spelling in every
     * position: locals, fields, params, returns, `newobj`, `ldfld`/`stfld` owner tokens and
     * `call`/`callvirt` owner tokens (`genprobe_s2`/`_s3`), composing with [NullableValue]
     * arguments (`genprobe_s4`) and nesting arbitrarily (`Box<Box<String>>`, `genprobe_s3`).
     * Inside the declaring class's own bodies the self-reference is the OPEN instantiation
     * (`class 'Box`1'<!0>`, `genprobe_s2`/`_s7`), which falls out of mapping the class's own
     * `defaultType` — the [arguments] are then [TypeParameter]s. Like [UserClass], equality is
     * the rendered token. Generic classes stay structurally invariant; [isDotNetAssignableTo]
     * additionally interprets a generic interface's recorded declaration-site variance for
     * reference-shaped arguments.
     */
    class GenericInstance(val classInfo: DotNetIlClassInfo, val arguments: List<DotNetIlValueType>) :
        DotNetIlValueType("class ${classInfo.ilTypeRef}<${arguments.joinToString(", ") { it.nameInSignature }}>") {
        override fun equals(other: Any?): kotlin.Boolean =
            other is GenericInstance && other.nameInSignature == nameInSignature

        override fun hashCode(): Int = nameInSignature.hashCode()
        override fun toString(): kotlin.String = "GenericInstance($nameInSignature)"
    }
}

/**
 * Substitutes [DotNetIlValueType.TypeParameter] leaves of this mapped type with the given
 * instantiation — [classArguments] for `!n` references, [methodArguments] for `!!n` — recursing
 * through [DotNetIlValueType.GenericInstance] arguments (nested instantiations). This is the
 * IL-level counterpart of the CLR's own signature instantiation: declared member signatures stay
 * OPEN in every member-reference operand while the VALUES flowing at a call site have the
 * substituted types (probe-verified, `genprobe_s1`/`_s2`), so codegen emits arguments and checks
 * results against the substituted form. An out-of-range index is an internal error: every
 * substitution site derives its argument lists from the same declaration the open type came from.
 */
internal fun DotNetIlValueType.substituteDotNetTypeParameters(
    classArguments: List<DotNetIlValueType>,
    methodArguments: List<DotNetIlValueType> = emptyList(),
): DotNetIlValueType = when (this) {
    is DotNetIlValueType.TypeParameter -> {
        val arguments = if (isMethodParameter) methodArguments else classArguments
        arguments.getOrNull(index)
            ?: error("Internal .NET backend error: no substitution for type parameter $nameInSignature")
    }
    is DotNetIlValueType.GenericInstance -> DotNetIlValueType.GenericInstance(
        classInfo,
        arguments.map { it.substituteDotNetTypeParameters(classArguments, methodArguments) },
    )
    // A NullableValue element is always concrete (`T?` is rejected at the type mapper), so this
    // arm is defensive symmetry.
    is DotNetIlValueType.NullableValue ->
        DotNetIlValueType.NullableValue(elementType.substituteDotNetTypeParameters(classArguments, methodArguments))
    is DotNetIlValueType.PrimitiveArray ->
        DotNetIlValueType.PrimitiveArray(elementType.substituteDotNetTypeParameters(classArguments, methodArguments))
    is DotNetIlValueType.GenericArray ->
        DotNetIlValueType.GenericArray(elementType.substituteDotNetTypeParameters(classArguments, methodArguments))
    else -> this
}

/** The return-type wrapper of [substituteDotNetTypeParameters]; `void` never substitutes. */
internal fun DotNetIlReturnType.substituteDotNetTypeParameters(
    classArguments: List<DotNetIlValueType>,
    methodArguments: List<DotNetIlValueType> = emptyList(),
): DotNetIlReturnType = when (this) {
    DotNetIlReturnType.Void -> this
    is DotNetIlReturnType.Value ->
        DotNetIlReturnType.Value(type.substituteDotNetTypeParameters(classArguments, methodArguments))
}

/**
 * The proper supertypes this class-like type widens to, as full instantiated type tokens — the
 * value-type view of [DotNetIlClassInfo.allSupertypes] for [DotNetIlValueType.UserClass] and
 * [DotNetIlValueType.GenericInstance]; empty for every other type.
 */
internal fun DotNetIlValueType.dotNetAllSupertypes(): Sequence<DotNetIlValueType> = when (this) {
    is DotNetIlValueType.UserClass -> classInfo.allSupertypes()
    is DotNetIlValueType.GenericInstance -> classInfo.allSupertypes(arguments)
    else -> emptySequence()
}

/**
 * This type's view AS the generic class [owner] — itself when it is an instantiation of [owner],
 * otherwise the (unique) instantiated-[owner] entry of its supertype walk: the member-reference
 * owner token for calls to and field accesses on members a generic class DECLARES, reached
 * through any receiver (its own instantiations, open or closed, and derived classes — the
 * operand must name the DECLARING class with its instantiation, `genprobe_s2`/`_s5`). Null when
 * this type does not widen to [owner] at all. A constrained type parameter also walks its
 * module-local upper bounds, allowing a bound that inherits an instantiated generic owner to
 * provide the correct member-reference token.
 */
internal fun DotNetIlValueType.dotNetViewAsGenericOwner(
    owner: DotNetIlClassInfo,
): DotNetIlValueType.GenericInstance? {
    if (this is DotNetIlValueType.GenericInstance && classInfo.ilTypeRef == owner.ilTypeRef) return this
    val ownerViews = if (this is DotNetIlValueType.TypeParameter) {
        upperBounds.asSequence().flatMap { bound -> sequenceOf(bound) + bound.dotNetAllSupertypes() }
    } else {
        dotNetAllSupertypes()
    }
    return ownerViews
        .filterIsInstance<DotNetIlValueType.GenericInstance>()
        .firstOrNull { it.classInfo.ilTypeRef == owner.ilTypeRef }
}

/**
 * The corelib reference of the boxed form of a primitive value type, the operand of the
 * `T -> Any?` `box` instruction (all five spellings probe-verified: Int32/Int64 in landed code
 * and `boxprobe_s7`; Boolean/Char/Double in `nullprobe_s8`, runtime types confirmed). Null for
 * every non-primitive type (reference types widen to `object` without an instruction; a
 * [DotNetIlValueType.NullableValue] boxes through its own [boxInstruction][DotNetIlValueType.NullableValue.boxInstruction]).
 */
internal fun DotNetIlValueType.dotNetBoxedCorelibRefOrNull(): String? = when (this) {
    DotNetIlValueType.Boolean -> "${CORE_LIB_REF}System.Boolean"
    DotNetIlValueType.Int32 -> "${CORE_LIB_REF}System.Int32"
    DotNetIlValueType.Int64 -> "${CORE_LIB_REF}System.Int64"
    DotNetIlValueType.Float64 -> "${CORE_LIB_REF}System.Double"
    DotNetIlValueType.Char -> "${CORE_LIB_REF}System.Char"
    else -> null
}

/**
 * Whether values of this IL type live on the evaluation stack as object REFERENCES — `ldnull` is
 * a valid value, reference `ceq` is a valid identity/null test, and widening to
 * [DotNetIlValueType.Object] is instruction-free. False exactly for the primitive value types,
 * [DotNetIlValueType.NullableValue] (whose null test is `get_HasValue`, never `ldnull`/`ceq`)
 * and [DotNetIlValueType.TypeParameter] — an unconstrained or interface-bound `T` may instantiate
 * to a value type, so a `!n`-typed value is neither reference- nor value-shaped statically.
 * Bound widening therefore uses `box !n` even when every Kotlin-side instantiation is a
 * reference. An INSTANTIATED generic class ([DotNetIlValueType.GenericInstance]) is an ordinary
 * reference type.
 */
internal fun DotNetIlValueType.isDotNetReferenceShaped(): Boolean = when (this) {
    DotNetIlValueType.String, DotNetIlValueType.Object,
    is DotNetIlValueType.UserClass, is DotNetIlValueType.MappedClass,
    is DotNetIlValueType.GenericInstance, is DotNetIlValueType.PrimitiveArray,
    is DotNetIlValueType.GenericArray,
        -> true
    else -> false
}

/** The scalar value types whose CLR vector forms are part of the supported primitive-array slice. */
internal fun DotNetIlValueType.isSupportedPrimitiveArrayElement(): Boolean = when (this) {
    DotNetIlValueType.Boolean,
    DotNetIlValueType.Int32,
    DotNetIlValueType.Int64,
    DotNetIlValueType.Float64,
    DotNetIlValueType.Char,
        -> true
    else -> false
}

internal sealed class DotNetIlReturnType {
    abstract val nameInSignature: String

    object Void : DotNetIlReturnType() {
        override val nameInSignature: String = "void"
    }

    data class Value(val type: DotNetIlValueType) : DotNetIlReturnType() {
        override val nameInSignature: String = type.nameInSignature
    }
}

/**
 * A mapped IL method signature. For an instance method [hasThis] is set and the receiver type
 * stays at `parameterTypes[0]` — CLR argument numbering makes `this` slot 0, so keeping it in the
 * list lets the argument zip and the call-site pop count treat receivers and declared parameters
 * uniformly — while [renderParameterTypes] drops it, because a printed IL parameter list never
 * contains the implicit `this`.
 */
internal data class DotNetIlMethodSignature(
    val returnType: DotNetIlReturnType,
    val parameterTypes: List<DotNetIlValueType>,
    val hasThis: Boolean = false,
) {
    fun renderParameterTypes(): String =
        parameterTypes.drop(if (hasThis) 1 else 0).joinToString(", ") { it.nameInSignature }
}

/**
 * A function currently considered compilable to .NET IL — a top-level function of a file facade
 * or a member function/accessor of a user class: the [owner] IL class it belongs to and its
 * mapped IL signature.
 */
internal class DotNetIlFunctionInfo(
    val owner: DotNetIlClassInfo,
    val signature: DotNetIlMethodSignature,
) {
    /** Whether this is an instance method of a user class (see [DotNetIlMethodSignature.hasThis]). */
    val isInstance: Boolean
        get() = signature.hasThis

    /**
     * The `<ret> 'C'::'name'(<params>)` member reference, prefixed with `instance` for instance
     * methods: the operand of `call`/`callvirt`
     * instructions and of the `.get`/`.set` lines inside a `.property` block. Instance methods of
     * final classes on non-null Kotlin receivers are called with plain (non-virtual) `call`
     * (probe-verified) — a stated deviation from Roslyn, which emits `callvirt` purely for its
     * implicit null check.
     *
     * The signature slots are always the DECLARED (open) ones — `!n`/`!!n` stay verbatim in
     * member references per CLR member-ref rules (probe-verified, `genprobe_s1`/`_s2`) — while
     * generic contexts substitute only the tokens around them: [ownerToken] carries the
     * instantiated owner for members of a generic class (`class 'Box`1'<string>` at external
     * call sites, the open `class 'Box`1'<!0>` inside the class's own bodies; the default is the
     * established bare non-generic spelling, which `.property` accessor references also require
     * for generic owners — bare name, NO type-arguments list, `genprobe_s2`), and
     * [methodInstantiation] renders the `<inst>` list of a generic METHOD between its name and
     * parameter list (`'id'<string>(!!0)`, `genprobe_s1`; `!!0` itself is a legal instantiation
     * argument at generic→generic call sites). A generic owner and generic member compose those
     * tokens independently (`class 'Picker`1'<string>::'pick'<int32>(!0, !!0)`,
     * `genmemberprobe_s1`).
     */
    fun renderMethodReference(
        methodName: String,
        ownerToken: String = owner.ilTypeRef,
        methodInstantiation: List<DotNetIlValueType> = emptyList(),
    ): String {
        val instancePrefix = if (isInstance) "instance " else ""
        val instantiation =
            if (methodInstantiation.isEmpty()) ""
            else methodInstantiation.joinToString(", ", "<", ">") { it.nameInSignature }
        return "$instancePrefix${signature.returnType.nameInSignature} " +
                "$ownerToken::${methodName.toIlIdentifier()}$instantiation(${signature.renderParameterTypes()})"
    }

    /**
     * The call instruction of one call site. [virtual] selects `callvirt` — used exactly for
     * [virtual callees][isDotNetVirtual] outside a `super` qualifier, so an overridden member
     * dispatches on the runtime type (probe-verified, `inheritprobe_s1`/`_s2`: `callvirt` with
     * the operand token naming the DECLARING class dispatches to the override even through a
     * base-typed local, including to a `final` override). Everything else — final members
     * (the stated call-not-callvirt deviation from Roslyn documented on
     * [renderMethodReference]), static facade functions, and `super`-qualified calls, whose
     * non-virtual `call` to a virtual method with the `this` receiver runs the BASE
     * implementation (probe-verified, `inheritprobe_s1`) — keeps the plain `call`.
     * [ownerToken]/[methodInstantiation] are the generic-context tokens documented on
     * [renderMethodReference].
     */
    fun renderCallInstruction(
        methodName: String,
        virtual: Boolean = false,
        ownerToken: String = owner.ilTypeRef,
        methodInstantiation: List<DotNetIlValueType> = emptyList(),
    ): String =
        "${if (virtual) "callvirt" else "call"} ${renderMethodReference(methodName, ownerToken, methodInstantiation)}"
}

/**
 * A user class currently considered compilable to .NET IL — top-level, or, with [enclosingClass]
 * set, the companion object nested inside a top-level class. The counterpart of
 * [DotNetIlFunctionInfo] for classes; it carries the IL class name ([ilClassName] — the dotted
 * FqName for a top-level class, the simple name for a nested one, i.e. what the `.class`
 * directive declares) and renders the member references of the class model.
 */
internal class DotNetIlClassInfo(
    val ilClassName: String,
    private val enclosingClass: DotNetIlClassInfo? = null,
    val typeParameterVariances: List<Variance> = emptyList(),
) {
    val typeParameterCount: Int
        get() = typeParameterVariances.size

    /** Whether this is a nested class (a companion object) rather than a top-level one. */
    val isNested: Boolean
        get() = enclosingClass != null

    /**
     * The base TYPE of this class as a full type token — a [DotNetIlValueType.UserClass] for a
     * plain base, a [DotNetIlValueType.GenericInstance] for an instantiated generic base
     * (`class D : Box<Int>()` links `class 'Box`1'<int32>`; `class D<T> : Box<T>()` links the
     * open `class 'Box`1'<!0>`). The instantiation must be part of the link because assignability
     * is INVARIANT — `D` widens only to its exact base view — or null when the class extends
     * `kotlin.Any` (IL `System.Object`). Linked
     * by [DotNetIlEmitter]'s pre-pass after ALL gate-passing classes are registered (a base may
     * be declared after its derived class — forward references are legal IL, probe-verified
     * `inheritprobe_s1`) and consumed by [isDotNetAssignableTo]'s upcast walk. Deliberately NOT
     * consulted for the `extends` line: the render re-resolves the base through the LIVE
     * availableClasses map every fixpoint round, so a base evicted mid-emission fails its
     * derived classes instead of leaving a stale link in emitted IL (the link itself is then
     * unreachable — the derived class is evicted with it).
     */
    var baseType: DotNetIlValueType? = null

    /**
     * The full types of this class's directly implemented interfaces (for an interface: its
     * directly extended super-interfaces). A generic edge retains its instantiation —
     * `class C : Producer<String>` links `class 'Producer`1'<string>`, while
     * `class C<T> : Producer<T>` links the open `class 'Producer`1'<!0>` — because both member
     * owner lookup and variance-aware assignability depend on the arguments. Linked by the same
     * pre-pass as [baseType] and, like it, consumed ONLY by [isDotNetAssignableTo]'s upcast walk;
     * the `implements` line is re-resolved through the LIVE availableClasses map every render
     * round, so an interface evicted mid-emission cascades whole-class to its implementers and
     * sub-interfaces instead of leaving a stale link in emitted IL. Interfaces form a DAG rather
     * than a chain, hence a list next to the single [baseType].
     */
    var interfaces: List<DotNetIlValueType> = emptyList()

    /**
     * Every proper supertype this class widens to by a pure reference upcast, as full type
     * tokens: the [baseType] chain, every directly or transitively implemented interface, and
     * the interfaces of every base-chain ancestor (probe-verified free widenings,
     * `ifaceprobe_s6`/`_s7`; the instantiated-generic-base widening `genprobe_s5`). A
     * breadth-first walk over the supertype DAG (diamonds are legal Kotlin and legal IL),
     * deduplicated by the rendered token like [DotNetIlValueType.UserClass]/
     * [DotNetIlValueType.GenericInstance] equality. [selfArguments] is this class's own
     * instantiation, substituted into every base/interface link that mentions its parameters;
     * this is active machinery for generic-to-generic class inheritance, generic classes
     * implementing open generic interfaces, and generic-interface inheritance. A non-generic
     * class's own links are always closed.
     */
    fun allSupertypes(selfArguments: List<DotNetIlValueType> = emptyList()): Sequence<DotNetIlValueType> = sequence {
        val visited = hashSetOf<String>()
        val queue = ArrayDeque<DotNetIlValueType>()
        fun enqueueSupertypesOf(classInfo: DotNetIlClassInfo, arguments: List<DotNetIlValueType>) {
            classInfo.baseType?.let { queue.add(it.substituteDotNetTypeParameters(arguments)) }
            classInfo.interfaces.mapTo(queue) { it.substituteDotNetTypeParameters(arguments) }
        }
        enqueueSupertypesOf(this@DotNetIlClassInfo, selfArguments)
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!visited.add(next.nameInSignature)) continue
            yield(next)
            when (next) {
                is DotNetIlValueType.UserClass -> enqueueSupertypesOf(next.classInfo, emptyList())
                is DotNetIlValueType.GenericInstance -> enqueueSupertypesOf(next.classInfo, next.arguments)
                else -> {}
            }
        }
    }

    /**
     * The rendered IL type reference of this class — `'demo.Outer'` for a top-level class,
     * `'demo.Outer'/'Companion'` for a nested one: the slash sits OUTSIDE the quoted
     * identifiers, enclosing name first (probe-verified in every operand position —
     * field types, `newobj`, `ldsfld`/`stsfld`, `call`, method parameter/return signatures
     * and `.locals`; objprobe_s6). Every member-reference renderer and every
     * [UserClass][DotNetIlValueType.UserClass] signature name routes through this single
     * property, so the nested spelling exists in exactly one place.
     */
    val ilTypeRef: String =
        enclosingClass?.let { "${it.ilTypeRef}/${ilClassName.toIlIdentifier()}" } ?: ilClassName.toIlIdentifier()

    /**
     * The `instance void 'C'::.ctor(<params>)` member reference shared by every constructor use:
     * prefixed with `newobj` at instantiation sites and with `call` in `this(...)` and base
     * delegations (`.ctor` is a bare keyword, not a quoted identifier; both spellings are
     * probe-verified). For a generic class, [ownerToken] carries the instantiation
     * (`newobj instance void class 'Box`1'<string>::.ctor(!0)` — the parameter slots stay OPEN,
     * probe-verified `genprobe_s2`; base-ctor chaining `genprobe_s5`).
     */
    fun renderConstructorReference(parameterTypes: List<DotNetIlValueType>, ownerToken: String = ilTypeRef): String =
        "instance void ${ownerToken}::.ctor(${parameterTypes.joinToString(", ") { it.nameInSignature }})"

    /**
     * The `<type> 'C'::'name'` field reference `ldfld`/`stfld` instructions take as operand.
     * [fieldType] is the DECLARED (open) field type and [ownerToken] the instantiated owner for
     * fields of a generic class (`ldfld !0 class 'Box`1'<string>::'value'` — open type slot,
     * closed owner; probe-verified `genprobe_s2`/`_s3`, the derived-receiver flavor `_s5`).
     */
    fun renderFieldReference(fieldType: DotNetIlValueType, fieldName: String, ownerToken: String = ilTypeRef): String =
        "${fieldType.nameInSignature} ${ownerToken}::${fieldName.toIlIdentifier()}"
}
