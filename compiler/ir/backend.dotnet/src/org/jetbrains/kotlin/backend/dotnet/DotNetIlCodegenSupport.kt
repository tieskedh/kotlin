package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isChar
import org.jetbrains.kotlin.ir.types.isDouble
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.types.isNullableString
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.render

/**
 * Thrown while rendering a single function into IL when a construct the prototype .NET backend
 * cannot compile is encountered. The emitter catches it, discards the partial render, skips the
 * function, and reports [reason] as a diagnostic.
 */
internal class DotNetIlUnsupportedException(val reason: String) : RuntimeException(reason)

internal fun dotNetUnsupported(reason: String): Nothing =
    throw DotNetIlUnsupportedException(reason)

internal fun IrSimpleFunction.dotNetSignature(): DotNetIlMethodSignature {
    val returnType = returnType.toDotNetIlReturnType()
        ?: dotNetUnsupported("return type ${returnType.render()} is not supported")
    val parameterTypes = parameters.map { parameter ->
        parameter.type.toDotNetIlValueType()
            ?: dotNetUnsupported("parameter '${parameter.name.asString()}' has unsupported type ${parameter.type.render()}")
    }
    return DotNetIlMethodSignature(returnType, parameterTypes)
}

private fun IrType.toDotNetIlReturnType(): DotNetIlReturnType? {
    if (isUnit()) return DotNetIlReturnType.Void
    return DotNetIlReturnType.Value(toDotNetIlValueType() ?: return null)
}

internal fun IrType.toDotNetIlValueType(): DotNetIlValueType? {
    return when {
        isBoolean() -> DotNetIlValueType.Boolean
        isInt() -> DotNetIlValueType.Int32
        isLong() -> DotNetIlValueType.Int64
        isDouble() -> DotNetIlValueType.Float64
        isChar() -> DotNetIlValueType.Char
        isDotNetStringType() -> DotNetIlValueType.String
        else -> null
    }
}

internal fun IrType.isDotNetNullableStringType(): Boolean {
    if (isNullableString()) return true
    val typeParameter = ((this as? IrSimpleType)?.classifier as? IrTypeParameterSymbol)?.owner ?: return false
    return typeParameter.superTypes.any { it.isNullableString() }
}

private fun IrType.isDotNetStringType(): Boolean {
    if (isString() || isNullableString()) return true
    val typeParameter = ((this as? IrSimpleType)?.classifier as? IrTypeParameterSymbol)?.owner ?: return false
    return typeParameter.superTypes.any { it.isString() || it.isNullableString() }
}

/**
 * Renders a `kotlin.Double` constant as an `ldc.r8` operand.
 *
 * Finite values use [Double.toString], the shortest decimal representation that round-trips
 * (same contract the JVM backend relies on for `.class` file constants); it always contains a
 * `.` or an exponent, and the exponent marker is lowercased to the ilasm-validated `1.0e300`
 * shape. NaN and the infinities have no decimal form, so they use ilasm's raw-bit
 * `float64(0x...)` operand syntax, which also pins the exact NaN payload.
 */
internal fun Double.toIlFloat64Literal(): String = when {
    isNaN() || isInfinite() -> "float64(0x%016X)".format(toRawBits())
    else -> toString().lowercase()
}

/**
 * Renders a user-derived name as a single-quoted ILAsm identifier, escaping backslashes and
 * single quotes. Quoting every identifier sidesteps the hundreds of ILAsm keywords and keeps
 * raw Kotlin names (including overload-unfriendly or exotic ones) intact.
 */
internal fun String.toIlIdentifier(): String = buildString {
    append('\'')
    for (char in this@toIlIdentifier) {
        when (char) {
            '\\' -> append("\\\\")
            '\'' -> append("\\'")
            else -> append(char)
        }
    }
    append('\'')
}

/**
 * Renders a string constant as an `ldstr` operand: a double-quoted ILAsm QSTRING, or the
 * `bytearray` form for strings a QSTRING cannot represent.
 *
 * Non-ASCII characters are kept raw: the .il file is written as UTF-8 with a BOM (see
 * [DotNetBackend]), which makes ilasm decode multi-byte sequences correctly. Control characters
 * are rendered with octal escapes, except NUL: ilasm silently truncates a QSTRING at an embedded
 * `\000`, so strings containing NUL fall back to the `bytearray` (UTF-16LE code units) form.
 */
internal fun String.toIlStringLiteral(): String {
    if (NUL_CHAR in this) return toIlByteArrayLiteral()
    return buildString {
        append('"')
        for (char in this@toIlStringLiteral) {
            when {
                char == '\\' -> append("\\\\")
                char == '"' -> append("\\\"")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' || char == DEL_CHAR -> append("\\" + "%03o".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }
}

private const val NUL_CHAR = '\u0000'
private const val DEL_CHAR = '\u007F'

private fun String.toIlByteArrayLiteral(): String =
    map { it.code }
        .flatMap { code -> listOf(code and 0xFF, (code shr 8) and 0xFF) }
        .joinToString(separator = " ", prefix = "bytearray (", postfix = ")") { "%02X".format(it) }
