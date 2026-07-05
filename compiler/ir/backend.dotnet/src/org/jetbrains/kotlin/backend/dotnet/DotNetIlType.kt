package org.jetbrains.kotlin.backend.dotnet

internal enum class DotNetIlValueType(val nameInSignature: String) {
    Boolean("bool"),
    Int32("int32"),

    /** `kotlin.Long`. Mirrors the JVM backend's `long`; CLR `int64` occupies one stack slot. */
    Int64("int64"),

    /**
     * `kotlin.Double`. Mirrors the JVM backend's `double` (CLR `float64`). `Float`/`float32` is
     * deliberately deferred together with `Byte`/`Short`.
     */
    Float64("float64"),

    /**
     * `kotlin.Char`. Mirrors the JVM backend's `char`: a 16-bit unsigned code unit that lives on
     * the evaluation stack as an `int32` (the CLR, like the JVM, has no sub-int stack values).
     */
    Char("char"),
    String("string"),
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

internal data class DotNetIlMethodSignature(
    val returnType: DotNetIlReturnType,
    val parameterTypes: List<DotNetIlValueType>,
) {
    fun renderParameterTypes(): String =
        parameterTypes.joinToString(", ") { it.nameInSignature }
}

/**
 * A top-level function currently considered compilable to .NET IL: the file class it belongs to
 * and its mapped IL signature.
 */
internal class DotNetIlFunctionInfo(
    val className: String,
    val signature: DotNetIlMethodSignature,
) {
    fun renderCallInstruction(methodName: String): String =
        "call ${signature.returnType.nameInSignature} ${className.toIlIdentifier()}::${methodName.toIlIdentifier()}(${signature.renderParameterTypes()})"
}
