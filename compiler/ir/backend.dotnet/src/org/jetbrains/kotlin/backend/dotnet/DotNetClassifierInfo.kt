/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.builtins.functions.BuiltInFunctionArity
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isKFunction
import org.jetbrains.kotlin.name.FqName
import java.util.IdentityHashMap

/**
 * Stable declaration facts used repeatedly while one lowered module is emitted.
 *
 * These facts deliberately stop before physical CLR type mapping: that mapping also depends on
 * type arguments, nullability, the selected generic-interface view, the target framework, and
 * the emitter's live declaration set. Caching those values here would turn an optimization into
 * hidden semantic state. Classifier parent/name structure, by contrast, is final by the time the
 * IL emitter creates its type mapper.
 */
internal data class DotNetClassifierInfo(
    val fqName: FqName?,
    val fqNameString: String?,
    val builtinKind: DotNetBuiltinClassifierKind?,
    val isCharSequence: Boolean,
    val isComparable: Boolean,
    val runtimeKind: DotNetRuntimeClassifierKind?,
    val fixedFunctionArity: Int?,
    val bigFunctionArity: Int?,
    val fixedKFunctionArity: Int?,
    val bigKFunctionArity: Int?,
    val fixedKPropertyArity: Int?,
    val fixedKMutablePropertyArity: Int?,
) {
    companion object {
        fun derive(
            irClass: IrClass,
            parentInfo: DotNetClassifierInfo? = (irClass.parent as? IrClass)?.let { derive(it) },
        ): DotNetClassifierInfo {
            val fqName = irClass.fqNameWhenAvailable
            val fqNameString = fqName?.asString()
            val typeParameterCount = irClass.typeParameters.size
            val builtinKind = when (fqNameString) {
                "kotlin.Any" -> DotNetBuiltinClassifierKind.ANY
                "kotlin.Nothing" -> DotNetBuiltinClassifierKind.NOTHING
                "kotlin.Unit" -> DotNetBuiltinClassifierKind.UNIT
                "kotlin.Boolean" -> DotNetBuiltinClassifierKind.BOOLEAN
                "kotlin.Byte" -> DotNetBuiltinClassifierKind.BYTE
                "kotlin.Short" -> DotNetBuiltinClassifierKind.SHORT
                "kotlin.Int" -> DotNetBuiltinClassifierKind.INT
                "kotlin.Long" -> DotNetBuiltinClassifierKind.LONG
                "kotlin.Float" -> DotNetBuiltinClassifierKind.FLOAT
                "kotlin.Double" -> DotNetBuiltinClassifierKind.DOUBLE
                "kotlin.Char" -> DotNetBuiltinClassifierKind.CHAR
                "kotlin.String" -> DotNetBuiltinClassifierKind.STRING
                "kotlin.Number" -> DotNetBuiltinClassifierKind.NUMBER
                "kotlin.Annotation" -> DotNetBuiltinClassifierKind.ANNOTATION
                "kotlin.Array" -> DotNetBuiltinClassifierKind.ARRAY
                "kotlin.BooleanArray" -> DotNetBuiltinClassifierKind.BOOLEAN_ARRAY
                "kotlin.ByteArray" -> DotNetBuiltinClassifierKind.BYTE_ARRAY
                "kotlin.ShortArray" -> DotNetBuiltinClassifierKind.SHORT_ARRAY
                "kotlin.IntArray" -> DotNetBuiltinClassifierKind.INT_ARRAY
                "kotlin.LongArray" -> DotNetBuiltinClassifierKind.LONG_ARRAY
                "kotlin.FloatArray" -> DotNetBuiltinClassifierKind.FLOAT_ARRAY
                "kotlin.DoubleArray" -> DotNetBuiltinClassifierKind.DOUBLE_ARRAY
                "kotlin.CharArray" -> DotNetBuiltinClassifierKind.CHAR_ARRAY
                else -> null
            }
            val fixedFunctionArity = fqNameString
                ?.removePrefix("kotlin.Function")
                ?.toIntOrNull()
                ?.takeIf {
                    it in 0 until BuiltInFunctionArity.BIG_ARITY && typeParameterCount == it + 1
                }
            val bigFunctionArity = fqNameString
                ?.removePrefix("kotlin.Function")
                ?.toIntOrNull()
                ?.takeIf {
                    it >= BuiltInFunctionArity.BIG_ARITY && typeParameterCount == it + 1
                }
            val fixedKFunctionArity = irClass.name.asString()
                .removePrefix("KFunction")
                .toIntOrNull()
                ?.takeIf {
                    irClass.symbol.isKFunction() && it in 0 until BuiltInFunctionArity.BIG_ARITY
                }
            val bigKFunctionArity = irClass.name.asString()
                .removePrefix("KFunction")
                .toIntOrNull()
                ?.takeIf {
                    irClass.symbol.isKFunction() && it >= BuiltInFunctionArity.BIG_ARITY
                }
            val fixedKPropertyArity = fqNameString
                ?.removePrefix("kotlin.reflect.KProperty")
                ?.toIntOrNull()
                ?.takeIf { it in 0..2 }
            val fixedKMutablePropertyArity = fqNameString
                ?.removePrefix("kotlin.reflect.KMutableProperty")
                ?.toIntOrNull()
                ?.takeIf { it in 0..2 }
            val runtimeKind = when {
                fqNameString == "kotlin.Enum" && typeParameterCount == 1 -> DotNetRuntimeClassifierKind.ENUM
                irClass.name.asString() == "Companion" && parentInfo?.runtimeKind == DotNetRuntimeClassifierKind.ENUM ->
                    DotNetRuntimeClassifierKind.ENUM_COMPANION
                irClass.name.asString() == "<CompanionStatics>" && parentInfo?.runtimeKind == DotNetRuntimeClassifierKind.ENUM ->
                    DotNetRuntimeClassifierKind.ENUM_COMPANION_STATICS
                fqNameString == "kotlin.Function" && typeParameterCount == 1 -> DotNetRuntimeClassifierKind.FUNCTION
                fqNameString == "kotlin.reflect.KCallable" && typeParameterCount == 1 -> DotNetRuntimeClassifierKind.K_CALLABLE
                fqNameString == "kotlin.reflect.KVisibility" && typeParameterCount == 0 -> DotNetRuntimeClassifierKind.K_VISIBILITY
                fqNameString == "kotlin.reflect.KClassifier" && typeParameterCount == 0 -> DotNetRuntimeClassifierKind.K_CLASSIFIER
                fqNameString == "kotlin.reflect.KAnnotatedElement" && typeParameterCount == 0 -> DotNetRuntimeClassifierKind.K_ANNOTATED_ELEMENT
                fqNameString == "kotlin.reflect.KDeclarationContainer" && typeParameterCount == 0 ->
                    DotNetRuntimeClassifierKind.K_DECLARATION_CONTAINER
                fqNameString == "kotlin.reflect.KClass" && typeParameterCount == 1 -> DotNetRuntimeClassifierKind.K_CLASS
                fqNameString == "kotlin.reflect.KType" && typeParameterCount == 0 -> DotNetRuntimeClassifierKind.K_TYPE
                fqNameString == "kotlin.reflect.KFunction" && typeParameterCount == 1 -> DotNetRuntimeClassifierKind.K_FUNCTION
                fqNameString == "kotlin.reflect.KProperty" && typeParameterCount == 1 -> DotNetRuntimeClassifierKind.K_PROPERTY
                fqNameString == "kotlin.reflect.KMutableProperty" && typeParameterCount == 1 -> DotNetRuntimeClassifierKind.K_MUTABLE_PROPERTY
                else -> null
            }
            return DotNetClassifierInfo(
                fqName = fqName,
                fqNameString = fqNameString,
                builtinKind = builtinKind,
                isCharSequence = fqNameString == "kotlin.CharSequence",
                isComparable = fqNameString == "kotlin.Comparable",
                runtimeKind = runtimeKind,
                fixedFunctionArity = fixedFunctionArity,
                bigFunctionArity = bigFunctionArity,
                fixedKFunctionArity = fixedKFunctionArity,
                bigKFunctionArity = bigKFunctionArity,
                fixedKPropertyArity = fixedKPropertyArity,
                fixedKMutablePropertyArity = fixedKMutablePropertyArity,
            )
        }
    }
}

/**
 * Declaration-stable Common built-in identity used by the physical type mapper.
 *
 * Nullability and type arguments deliberately stay on [org.jetbrains.kotlin.ir.types.IrType]:
 * this enum replaces repeated signature-based classifier tests, but does not cache a mapped CLR
 * type whose meaning varies by mapper view or live emission state.
 */
internal enum class DotNetBuiltinClassifierKind {
    ANY,
    NOTHING,
    UNIT,
    BOOLEAN,
    BYTE,
    SHORT,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    CHAR,
    STRING,
    NUMBER,
    ANNOTATION,
    ARRAY,
    BOOLEAN_ARRAY,
    BYTE_ARRAY,
    SHORT_ARRAY,
    INT_ARRAY,
    LONG_ARRAY,
    FLOAT_ARRAY,
    DOUBLE_ARRAY,
    CHAR_ARRAY,
}

internal enum class DotNetRuntimeClassifierKind {
    ENUM,
    ENUM_COMPANION,
    ENUM_COMPANION_STATICS,
    FUNCTION,
    K_CALLABLE,
    K_VISIBILITY,
    K_CLASSIFIER,
    K_ANNOTATED_ELEMENT,
    K_DECLARATION_CONTAINER,
    K_CLASS,
    K_TYPE,
    K_FUNCTION,
    K_PROPERTY,
    K_MUTABLE_PROPERTY,
}

/** One compilation-local identity cache, shared by every view derived from a type mapper. */
internal class DotNetClassifierInfoCache {
    private val entries = IdentityHashMap<IrClass, DotNetClassifierInfo>()

    operator fun get(irClass: IrClass): DotNetClassifierInfo {
        entries[irClass]?.let { return it }
        val parentInfo = (irClass.parent as? IrClass)?.let(::get)
        return DotNetClassifierInfo.derive(irClass, parentInfo).also { entries[irClass] = it }
    }
}
