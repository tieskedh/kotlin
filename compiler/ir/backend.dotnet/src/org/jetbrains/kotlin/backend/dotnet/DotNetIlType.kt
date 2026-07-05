package org.jetbrains.kotlin.backend.dotnet

internal enum class DotNetIlValueType(val nameInSignature: String) {
    Boolean("bool"),
    Int32("int32"),
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
