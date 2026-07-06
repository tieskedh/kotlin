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
     * A top-level user class emitted into this module, referenced assembly-locally — no
     * bracketed resolution-scope prefix (see [CORE_LIB_REF]) — as the dotted FqName inside one quoted
     * identifier (`class 'demo.Point'`), the same convention the file facades use. A nullable
     * `C?` maps to the same reference type (CLR reference types are structurally nullable,
     * exactly like `string`).
     */
    data class UserClass(val ilClassName: kotlin.String) : DotNetIlValueType("class ${ilClassName.toIlIdentifier()}")
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
 * or a member function/accessor of a user class: the IL class it belongs to and its mapped IL
 * signature.
 */
internal class DotNetIlFunctionInfo(
    val className: String,
    val signature: DotNetIlMethodSignature,
) {
    /** Whether this is an instance method of a user class (see [DotNetIlMethodSignature.hasThis]). */
    val isInstance: Boolean
        get() = signature.hasThis

    /**
     * The `<ret> 'C'::'name'(<params>)` member reference, prefixed with `instance` for instance
     * methods: the operand of `call`
     * instructions and of the `.get`/`.set` lines inside a `.property` block. Instance methods of
     * final classes on non-null Kotlin receivers are called with plain (non-virtual) `call`
     * (probe-verified) — a stated deviation from Roslyn, which emits `callvirt` purely for its
     * implicit null check.
     */
    fun renderMethodReference(methodName: String): String {
        val instancePrefix = if (isInstance) "instance " else ""
        return "$instancePrefix${signature.returnType.nameInSignature} " +
                "${className.toIlIdentifier()}::${methodName.toIlIdentifier()}(${signature.renderParameterTypes()})"
    }

    fun renderCallInstruction(methodName: String): String =
        "call ${renderMethodReference(methodName)}"
}

/**
 * A top-level user class currently considered compilable to .NET IL. The counterpart of
 * [DotNetIlFunctionInfo] for classes; it carries the IL class name and renders the member
 * references of the class model (constructors and fields so far — methods and accessors follow
 * with the member-function slice).
 */
internal class DotNetIlClassInfo(
    val ilClassName: String,
) {
    /**
     * The `instance void 'C'::.ctor(<params>)` member reference shared by every constructor use:
     * prefixed with `newobj` at instantiation sites and with `call` in `this(...)` delegations
     * (`.ctor` is a bare keyword, not a quoted identifier; both spellings are probe-verified).
     */
    fun renderConstructorReference(parameterTypes: List<DotNetIlValueType>): String =
        "instance void ${ilClassName.toIlIdentifier()}::.ctor(${parameterTypes.joinToString(", ") { it.nameInSignature }})"

    /** The `<type> 'C'::'name'` field reference `ldfld`/`stfld` instructions take as operand. */
    fun renderFieldReference(fieldType: DotNetIlValueType, fieldName: String): String =
        "${fieldType.nameInSignature} ${ilClassName.toIlIdentifier()}::${fieldName.toIlIdentifier()}"
}
