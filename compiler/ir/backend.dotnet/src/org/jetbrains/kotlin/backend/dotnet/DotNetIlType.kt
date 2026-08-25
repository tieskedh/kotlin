package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.types.Variance

/**
 * The IL type of a value in a signature or on the evaluation stack. A sealed hierarchy rather
 * than an enum so that user-class types can carry their IL class name; the representation stays
 * structural on purpose so CLR reified generics can extend it later (never erasure).
 */
internal sealed class DotNetIlValueType(val nameInSignature: kotlin.String) {
    object Boolean : DotNetIlValueType("bool")

    /** `kotlin.Byte`: exact signed CLR `int8`, evaluated on the stack as an `int32`. */
    object Int8 : DotNetIlValueType("int8")

    /** `kotlin.Short`: exact signed CLR `int16`, evaluated on the stack as an `int32`. */
    object Int16 : DotNetIlValueType("int16")

    object Int32 : DotNetIlValueType("int32")

    /** `kotlin.Long`. Mirrors the JVM backend's `long`; CLR `int64` occupies one stack slot. */
    object Int64 : DotNetIlValueType("int64")

    /** `kotlin.Float`: exact CLR `float32`/`System.Single`. */
    object Float32 : DotNetIlValueType("float32")

    /** `kotlin.Double`. Mirrors the JVM backend's `double` (CLR `float64`). */
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
     * [DotNetIlExpressionCodegen]'s coercion layer). This is also the physical Kotlin `Any`
     * root: its three virtual members reuse System.Object's existing slots, while Kotlin
     * metadata retains the logical type.
     */
    object Object : DotNetIlValueType("object")

    /**
     * A canonical Kotlin-owned specialized primitive-array wrapper. The corresponding CLR vector
     * is available through [storageType] only while implementing compiler/runtime intrinsics; it
     * is never this type's signature spelling. Nullable and non-null Kotlin primitive arrays share
     * the wrapper reference representation, exactly like other CLR reference types.
     */
    data class PrimitiveArray(val elementType: DotNetIlValueType) :
        DotNetIlValueType("class ${DotNetPrimitiveArrays.entry(elementType).wrapperTypeRef}") {
        init {
            require(elementType.isSupportedPrimitiveArrayElement()) {
                "unsupported CLR vector element type ${elementType.nameInSignature}"
            }
        }

        val abi: DotNetPrimitiveArrays.Entry
            get() = DotNetPrimitiveArrays.entry(elementType)

        val storageType: GenericArray
            get() = abi.storageType

        val newStorageInstruction: kotlin.String
            get() = abi.newStorageInstruction

        val wrapStorageInstruction: kotlin.String
            get() = abi.wrapStorageInstruction

        val sizeCallInstruction: kotlin.String
            get() = abi.sizeCallInstruction

        val getCallInstruction: kotlin.String
            get() = abi.getCallInstruction

        val setCallInstruction: kotlin.String
            get() = abi.setCallInstruction

        val getStorageCallInstruction: kotlin.String
            get() = abi.getStorageCallInstruction
    }

    /**
     * A Kotlin `Array<E>` as a CLR zero-based vector. This stays a distinct structural type from
     * [PrimitiveArray]. Kotlin `Array<Int>` naturally becomes `int32[]`, while `IntArray` is the
     * runtime-owned `Kotlin.IntArray` wrapper. An open `Array<T>` substitutes `T = Int` without
     * losing either source type's nominal identity.
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
     * The element-erased Common `Array<*>` view. Exact generic arrays stay CLR SZ vectors; this
     * structural type names their common `System.Array` base without claiming that every
     * `System.Array` value has Kotlin generic-array identity. RTTI and casts therefore go through
     * the runtime SZ-array classifier rather than a bare `isinst`.
     */
    data class ErasedGenericArray(val coreLibraryReference: kotlin.String) :
        DotNetIlValueType("class ${coreLibraryReference}System.Array")

    /**
     * A concrete nullable Kotlin primitive (`Byte?`, `Short?`, `Int?`, `Long?`, `Float?`, `Double?`,
     * `Boolean?`, `Char?`) in
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
    data class NullableValue(
        val elementType: DotNetIlValueType,
        val coreLibraryReference: kotlin.String,
    ) : DotNetIlValueType("valuetype ${coreLibraryReference}System.Nullable`1<${elementType.nameInSignature}>") {
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
         * boxed-`T`-or-null (probe-verified for the scalar instantiations, `boxprobe_s3`,
         * `nullprobe_s8`).
         */
        val boxInstruction: kotlin.String
            get() = "box $nameInSignature"
    }

    /**
     * A user class emitted into this module, referenced assembly-locally — no bracketed
     * core-library resolution-scope prefix — through its already-rendered
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
     * [DotNetMappedExceptions]). [ilTypeRef] is the bare assembly-qualified reference — either a
     * core-library-prefixed `System.X` or a `Kotlin.Runtime` exact type, and the operand form a
     * `catch` clause takes. [nameInSignature] prefixes it with `class` for signature positions;
     * both spellings are ilasm-probe-verified. A nullable `T?` maps to the same reference type,
     * like [UserClass].
     */
    data class MappedClass(val ilTypeRef: kotlin.String) : DotNetIlValueType("class $ilTypeRef")

    /**
     * A reference to a type parameter of the enclosing generic declaration — CLR
     * ELEMENT_TYPE_VAR (`!n`, a class type parameter) or ELEMENT_TYPE_MVAR (`!!n`, a method
     * type parameter). The CLR identifies type parameters POSITIONALLY: the declared name is
     * decorative metadata, `!n`/`!!n` indices are authoritative (probe-verified, `genprobe_s1`:
     * the callsite signature keeps the `!!n` slots verbatim while only the `<...>` list is
     * substituted), which is why equality here is by index and kind alone. [upperBounds] carries
     * class/interface constraints without changing token identity, including an exact constructed
     * foreign CLR interface bound, while [relativeUpperBounds] retains positional `T : U`
     * relationships. An unconstrained value
     * supports exactly store/load/pass. A constrained one can additionally dispatch through a
     * bound with `constrained.` or box to a bound/`object`; it still is not intrinsically
     * reference-shaped because a CLR caller may instantiate an interface-bound parameter with a
     * value type.
     */
    class TypeParameter(
        val index: Int,
        val isMethodParameter: kotlin.Boolean,
        val upperBounds: List<DotNetIlValueType> = emptyList(),
        val relativeUpperBounds: Set<Identity> = emptySet(),
    ) : DotNetIlValueType(if (isMethodParameter) "!!$index" else "!$index") {
        data class Identity(val index: Int, val isMethodParameter: kotlin.Boolean)

        val identity: Identity
            get() = Identity(index, isMethodParameter)

        /** Whether metadata guarantees this parameter is assignable (when boxed) to [expected]. */
        fun isConstrainedTo(expected: DotNetIlValueType): kotlin.Boolean =
            upperBounds.any { it.isDotNetAssignableTo(expected) } ||
                    (expected is TypeParameter && expected.identity in relativeUpperBounds)

        override fun equals(other: Any?): kotlin.Boolean =
            other is TypeParameter && other.index == index && other.isMethodParameter == isMethodParameter

        override fun hashCode(): Int = 31 * index + isMethodParameter.hashCode()
        override fun toString(): kotlin.String = "TypeParameter($nameInSignature)"
    }

    /**
     * An instantiation of a genuinely reified CLR owner: an imported CLR generic or one of the
     * separately selected typed generic-interface capabilities. Kotlin-owned ordinary generic
     * classes never use this type; their physical owner is a [UserClass].
     * [nameInSignature] is the instantiation token
     * `class 'demo.Box`1'<string>` (the arity suffix lives INSIDE the quoted identifier, see
     * [DotNetIlClassInfo.ilTypeRef]; a suffix outside the quotes is an ilasm syntax error —
     * probe-verified, `genprobe_s2`/`_s2c`) and doubles as the operand spelling in every
     * position: locals, fields, params, returns, `newobj`, `ldfld`/`stfld` owner tokens and
     * `call`/`callvirt` owner tokens (`genprobe_s2`/`_s3`), composing with [NullableValue]
     * arguments (`genprobe_s4`) and nesting arbitrarily (`Box<Box<String>>`, `genprobe_s3`).
     * An open owner view may contain [TypeParameter] arguments. Like [UserClass], equality is the
     * rendered token. CLR generic classes stay structurally invariant; [isDotNetAssignableTo]
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

    /**
     * A managed pointer used in a CLR parameter signature (`T&`). It is not a Kotlin value
     * carrier and must never be selected by ordinary type mapping: only an explicitly chosen
     * physical calling convention may introduce it. Keeping the element structural lets open
     * `!n`/`!!n` references substitute exactly like every other signature type.
     */
    data class ByReference(val elementType: DotNetIlValueType) :
        DotNetIlValueType("${elementType.nameInSignature}&")
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
    // A NullableValue element is always concrete (open `T?` is rejected at the type mapper).
    // Substitution still recurses so a containing vector or generic instance remains structural.
    is DotNetIlValueType.NullableValue ->
        DotNetIlValueType.NullableValue(
            elementType.substituteDotNetTypeParameters(classArguments, methodArguments),
            coreLibraryReference,
        )
    is DotNetIlValueType.PrimitiveArray ->
        DotNetIlValueType.PrimitiveArray(elementType.substituteDotNetTypeParameters(classArguments, methodArguments))
    is DotNetIlValueType.GenericArray ->
        DotNetIlValueType.GenericArray(elementType.substituteDotNetTypeParameters(classArguments, methodArguments))
    is DotNetIlValueType.ByReference ->
        DotNetIlValueType.ByReference(elementType.substituteDotNetTypeParameters(classArguments, methodArguments))
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
 * This type's view as the genuinely generic CLR [owner] — itself when it is an instantiation of
 * [owner], otherwise the unique instantiated-owner entry of its supertype walk. It supplies the
 * declaring-owner token for imported generic members and typed interface capabilities. Kotlin-
 * owned generic classes have arity-zero owners and never need this recovery. A constrained type
 * parameter also walks its retained upper bounds, including exact selected foreign constructed
 * interface capabilities.
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
 * `T -> Any?` `box` instruction (the established spellings are probe-verified by
 * `boxprobe_s7`/`nullprobe_s8`; Byte/Short are frozen by the narrow-scalar product gate). Null for
 * every non-primitive type (reference types widen to `object` without an instruction; a
 * [DotNetIlValueType.NullableValue] boxes through its own [boxInstruction][DotNetIlValueType.NullableValue.boxInstruction]).
 */
internal fun DotNetIlValueType.dotNetBoxedCorelibRefOrNull(coreLibraryReference: String): String? = when (this) {
    DotNetIlValueType.Boolean -> "${coreLibraryReference}System.Boolean"
    DotNetIlValueType.Int8 -> "${coreLibraryReference}System.SByte"
    DotNetIlValueType.Int16 -> "${coreLibraryReference}System.Int16"
    DotNetIlValueType.Int32 -> "${coreLibraryReference}System.Int32"
    DotNetIlValueType.Int64 -> "${coreLibraryReference}System.Int64"
    DotNetIlValueType.Float32 -> "${coreLibraryReference}System.Single"
    DotNetIlValueType.Float64 -> "${coreLibraryReference}System.Double"
    DotNetIlValueType.Char -> "${coreLibraryReference}System.Char"
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
 * reference. A genuinely instantiated CLR generic ([DotNetIlValueType.GenericInstance]) is an
 * ordinary reference type.
 */
internal fun DotNetIlValueType.isDotNetReferenceShaped(): Boolean = when (this) {
    DotNetIlValueType.String, DotNetIlValueType.Object,
    is DotNetIlValueType.UserClass, is DotNetIlValueType.MappedClass,
    is DotNetIlValueType.GenericInstance, is DotNetIlValueType.PrimitiveArray,
    is DotNetIlValueType.GenericArray, is DotNetIlValueType.ErasedGenericArray,
        -> true
    else -> false
}

/** The scalar value types whose CLR vector forms are part of the supported primitive-array slice. */
internal fun DotNetIlValueType.isSupportedPrimitiveArrayElement(): Boolean = when (this) {
    DotNetIlValueType.Boolean,
    DotNetIlValueType.Int8,
    DotNetIlValueType.Int16,
    DotNetIlValueType.Int32,
    DotNetIlValueType.Int64,
    DotNetIlValueType.Float32,
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
    /** Physical-only final `out bool` carrying whether the logical Kotlin result was null. */
    val hasSplitNullableResult: Boolean = false,
) {
    val physicalParameterTypes: List<DotNetIlValueType>
        get() = buildList {
            addAll(parameterTypes)
            if (hasSplitNullableResult) {
                add(DotNetIlValueType.ByReference(DotNetIlValueType.Boolean))
            }
        }

    fun renderParameterTypes(): String =
        physicalParameterTypes
            .drop(if (hasThis) 1 else 0)
            .joinToString(", ", transform = DotNetIlValueType::nameInSignature)

    val physicalParameterCount: Int
        get() = physicalParameterTypes.size
}

/**
 * A function currently considered compilable to .NET IL — a top-level function of a file facade
 * or a member function/accessor of a user class: the [owner] IL class it belongs to and its
 * mapped IL signature.
 */
internal class DotNetIlFunctionInfo(
    val owner: DotNetIlClassInfo,
    val signature: DotNetIlMethodSignature,
    val physicalMethodName: String? = null,
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
     * instantiated owner for members of a genuinely generic CLR declaration; the default is the
     * established bare non-generic spelling, which `.property` accessor references also require,
     * and
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
        val instantiation =
            if (methodInstantiation.isEmpty()) ""
            else methodInstantiation.joinToString(", ", "<", ">") { it.nameInSignature }
        return renderMethodReferenceWithSuffix(methodName, ownerToken, instantiation)
    }

    /**
     * The method-declaration reference accepted by ILAsm's `.override method` grammar. A generic
     * declaration is identified by arity (`<[n]>`), not by a MethodSpec instantiation such as
     * `<!!0>`; the latter is valid on call operands but is a syntax error in a MethodImpl row.
     */
    fun renderOverrideMethodReference(
        methodName: String,
        ownerToken: String = owner.ilTypeRef,
        genericArity: Int = 0,
    ): String {
        val arity = if (genericArity == 0) "" else "<[$genericArity]>"
        return renderMethodReferenceWithSuffix(methodName, ownerToken, arity)
    }

    private fun renderMethodReferenceWithSuffix(
        methodName: String,
        ownerToken: String,
        genericSuffix: String,
    ): String {
        val instancePrefix = if (isInstance) "instance " else ""
        return "$instancePrefix${signature.returnType.nameInSignature} " +
                "$ownerToken::${methodName.toIlIdentifier()}$genericSuffix(${signature.renderParameterTypes()})"
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
 * The emitted Property-row name paired with a source property and its selected physical accessor.
 *
 * Canonical generic-interface accessors are disambiguated, so their Property row must carry the
 * same suffix. Keep emission and DLL implementation-manifest recording on this single function;
 * consumers use the recorded result and never reconstruct it.
 */
internal fun dotNetPhysicalPropertyName(
    sourcePropertyName: String,
    physicalAccessorName: String?,
): String {
    val canonicalSlotSuffix = physicalAccessorName
        ?.takeIf { "__KotlinErased__" in it }
        ?.substringAfter("__KotlinErased__")
    return canonicalSlotSuffix?.let { suffix ->
        "${sourcePropertyName}__KotlinErased__$suffix"
    } ?: sourcePropertyName
}

/**
 * A user class currently considered compilable to .NET IL — top-level (including a popped-up
 * module-private local class), or, with [enclosingClass] set, a recursively nested named/local
 * class, named object, or companion object. The counterpart of
 * [DotNetIlFunctionInfo] for classes; it carries the IL class name ([ilClassName] — the dotted
 * FqName for a top-level class, the simple arity-suffixed name for a nested one, i.e. what the
 * `.class` directive declares) and renders the member references of the class model.
 */
internal class DotNetIlClassInfo(
    val ilClassName: String,
    private val enclosingClass: DotNetIlClassInfo? = null,
    val typeParameterVariances: List<Variance> = emptyList(),
    val assemblyName: String? = null,
) {
    val typeParameterCount: Int
        get() = typeParameterVariances.size

    /** Whether this is a named nested class/object or companion rather than a top-level class. */
    val isNested: Boolean
        get() = enclosingClass != null

    /**
     * The base TYPE of this class as a full type token — a [DotNetIlValueType.UserClass] for a
     * plain or Kotlin-erased base, and a [DotNetIlValueType.GenericInstance] only for a genuinely
     * reified CLR base. Any CLR instantiation must be part of the link because assignability is
     * invariant, or null when the class extends `kotlin.Any` (IL `System.Object`). Linked
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
     * [DotNetIlValueType.GenericInstance] equality. [selfArguments] is this CLR TypeDef's own
     * instantiation, substituted into every base/interface link that mentions its parameters.
     * This remains active for imported/reified CLR inheritance and generic-interface
     * capabilities; Kotlin-owned generic classes pass an empty list because their physical owner
     * is non-generic.
     */
    fun allSupertypes(selfArguments: List<DotNetIlValueType> = emptyList()): Sequence<DotNetIlValueType> = sequence {
        val visited = hashSetOf<String>()
        val queue = ArrayDeque<DotNetIlValueType>()
        fun enqueueSupertypesOf(classInfo: DotNetIlClassInfo, arguments: List<DotNetIlValueType>) {
            fun substitute(linkKind: String, link: DotNetIlValueType): DotNetIlValueType =
                try {
                    link.substituteDotNetTypeParameters(arguments)
                } catch (failure: IllegalStateException) {
                    error(
                        "Internal .NET backend error: cannot instantiate $linkKind " +
                                "${link.nameInSignature} of ${classInfo.ilTypeRef} with " +
                                "[${arguments.joinToString { it.nameInSignature }}]: ${failure.message}"
                    )
                }
            classInfo.baseType?.let { queue.add(substitute("base", it)) }
            classInfo.interfaces.mapTo(queue) { substitute("interface", it) }
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
     * `'demo.Outer'/'Middle'/'Deep'` for recursively nested classes: each slash sits OUTSIDE the
     * quoted identifiers, enclosing name first (probe-verified in every operand position —
     * field types, `newobj`, `ldsfld`/`stsfld`, `call`, method parameter/return signatures
     * and `.locals`; objprobe_s6, nestedprobe_s1). Every member-reference renderer and every
     * [UserClass][DotNetIlValueType.UserClass] signature name routes through this single
     * property, so the nested spelling exists in exactly one place.
     */
    val ilTypeRef: String = run {
        require(assemblyName == null || enclosingClass == null) {
            "External CLR type '$ilClassName' cannot also be a nested module-local type"
        }
        val localRef = enclosingClass
            ?.let { "${it.ilTypeRef}/${ilClassName.toIlIdentifier()}" }
            ?: ilClassName.toIlIdentifier()
        assemblyName?.let { "[$it]$localRef" } ?: localRef
    }

    /** Assembly-independent owner path persisted in the DLL's Kotlin declaration index. */
    fun physicalPathComponents(): List<String> =
        enclosingClass?.physicalPathComponents().orEmpty() + ilClassName

    /**
     * The `instance void 'C'::.ctor(<params>)` member reference shared by every constructor use:
     * prefixed with `newobj` at instantiation sites and with `call` in `this(...)` and base
     * delegations (`.ctor` is a bare keyword, not a quoted identifier; both spellings are
     * probe-verified). For a genuinely generic CLR owner, [ownerToken] carries its instantiation;
     * a Kotlin-owned generic class passes its one erased owner token.
     */
    fun renderConstructorReference(parameterTypes: List<DotNetIlValueType>, ownerToken: String = ilTypeRef): String =
        "instance void ${ownerToken}::.ctor(${parameterTypes.joinToString(", ") { it.nameInSignature }})"

    /**
     * The `<type> 'C'::'name'` field reference `ldfld`/`stfld` instructions take as operand.
     * [fieldType] is the declared field type and [ownerToken] may be an instantiated genuinely
     * generic CLR owner. Kotlin-owned generic-class fields use their erased owner and carrier.
     */
    fun renderFieldReference(fieldType: DotNetIlValueType, fieldName: String, ownerToken: String = ilTypeRef): String =
        "${fieldType.nameInSignature} ${ownerToken}::${fieldName.toIlIdentifier()}"
}
