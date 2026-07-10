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
