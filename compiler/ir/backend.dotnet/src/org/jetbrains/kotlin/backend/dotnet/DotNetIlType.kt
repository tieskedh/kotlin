package org.jetbrains.kotlin.backend.dotnet

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
     * that [isDotNetAssignableTo] can walk the [base-class chain][DotNetIlClassInfo.baseClass]
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
 * [DotNetIlValueType.Object] is instruction-free. False exactly for the primitive value types
 * and [DotNetIlValueType.NullableValue] (whose null test is `get_HasValue`, never `ldnull`/`ceq`).
 */
internal fun DotNetIlValueType.isDotNetReferenceShaped(): Boolean = when (this) {
    DotNetIlValueType.String, DotNetIlValueType.Object,
    is DotNetIlValueType.UserClass, is DotNetIlValueType.MappedClass,
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
     */
    fun renderMethodReference(methodName: String): String {
        val instancePrefix = if (isInstance) "instance " else ""
        return "$instancePrefix${signature.returnType.nameInSignature} " +
                "${owner.ilTypeRef}::${methodName.toIlIdentifier()}(${signature.renderParameterTypes()})"
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
     */
    fun renderCallInstruction(methodName: String, virtual: Boolean = false): String =
        "${if (virtual) "callvirt" else "call"} ${renderMethodReference(methodName)}"
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
) {
    /** Whether this is a nested class (a companion object) rather than a top-level one. */
    val isNested: Boolean
        get() = enclosingClass != null

    /**
     * The class info of this class's base class, or null when the class extends `kotlin.Any`
     * (IL `System.Object`). Linked by [DotNetIlEmitter]'s pre-pass after ALL gate-passing
     * classes are registered (a base may be declared after its derived class — forward
     * references are legal IL, probe-verified `inheritprobe_s1`) and consumed by
     * [isDotNetAssignableTo]'s upcast walk. Deliberately NOT consulted for the `extends` line:
     * the render re-resolves the base through the LIVE availableClasses map every fixpoint
     * round, so a base evicted mid-emission fails its derived classes instead of leaving a
     * stale link in emitted IL (the link itself is then unreachable — the derived class is
     * evicted with it).
     */
    var baseClass: DotNetIlClassInfo? = null

    /**
     * The class infos of this class's directly implemented interfaces (for an interface: its
     * directly extended super-interfaces). Linked by the same pre-pass as [baseClass] and, like
     * it, consumed ONLY by [isDotNetAssignableTo]'s upcast walk — the `implements` line is
     * re-resolved through the LIVE availableClasses map every render round, so an interface
     * evicted mid-emission cascades whole-class to its implementers and sub-interfaces instead
     * of leaving a stale link in emitted IL. Interfaces form a DAG rather than a chain, hence a
     * list next to the single [baseClass].
     */
    var interfaces: List<DotNetIlClassInfo> = emptyList()

    /**
     * Every proper supertype this class widens to by a pure reference upcast: the [baseClass]
     * chain, every directly or transitively implemented interface, and the interfaces of every
     * base-chain ancestor (probe-verified free widenings, `ifaceprobe_s6`/`_s7`). A breadth-first
     * walk over the supertype DAG (diamonds are legal Kotlin and legal IL), deduplicated by
     * [ilTypeRef] like [DotNetIlValueType.UserClass] equality.
     */
    fun allSupertypes(): Sequence<DotNetIlClassInfo> = sequence {
        val visited = hashSetOf<String>()
        val queue = ArrayDeque<DotNetIlClassInfo>()
        baseClass?.let(queue::add)
        queue.addAll(interfaces)
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!visited.add(next.ilTypeRef)) continue
            yield(next)
            next.baseClass?.let(queue::add)
            queue.addAll(next.interfaces)
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
     * prefixed with `newobj` at instantiation sites and with `call` in `this(...)` delegations
     * (`.ctor` is a bare keyword, not a quoted identifier; both spellings are probe-verified).
     */
    fun renderConstructorReference(parameterTypes: List<DotNetIlValueType>): String =
        "instance void ${ilTypeRef}::.ctor(${parameterTypes.joinToString(", ") { it.nameInSignature }})"

    /** The `<type> 'C'::'name'` field reference `ldfld`/`stfld` instructions take as operand. */
    fun renderFieldReference(fieldType: DotNetIlValueType, fieldName: String): String =
        "${fieldType.nameInSignature} ${ilTypeRef}::${fieldName.toIlIdentifier()}"
}
