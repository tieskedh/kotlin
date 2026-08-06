/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.annotations.KotlinRetention
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.util.getAnnotationRetention
import org.jetbrains.kotlin.ir.util.getAnnotationTargets
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.primaryConstructor

internal fun IrAnnotationContainer.dotNetRuntimeAttributes(
    typeMapper: DotNetIlTypeMapper,
): List<String> = annotations.mapNotNull { annotation ->
    annotation.dotNetRuntimeAttributeOrNull(typeMapper)
}

private fun IrAnnotation.dotNetRuntimeAttributeOrNull(
    typeMapper: DotNetIlTypeMapper,
): String? {
    val annotationClass = classSymbol.owner
    if (!annotationClass.isAnnotationClass) return null
    if (!annotationClass.isDotNetRuntimeRetainedAnnotation()) return null

    // Built-in Kotlin meta annotations and other resolution-only declarations have no emitted
    // CLR TypeDef. They remain authoritative KLIB facts and are projected separately where exact.
    val classInfo = typeMapper.classInfoOrNull(annotationClass) ?: return null
    val constructor = annotationClass.primaryConstructor
        ?: dotNetUnsupported("annotation class '${annotationClass.name.asString()}' has no primary constructor")
    // Kotlin-owned generic annotation classes follow the target's erased class identity. A CLR
    // attribute row cannot spell the omitted logical type arguments, so construction remains
    // available to Kotlin but its application is deliberately KLIB-only.
    if (annotationClass.typeParameters.isNotEmpty()) return null

    val parameterTypes = constructor.dotNetSignature(typeMapper).parameterTypes
    if (parameterTypes.size != constructor.parameters.size) return null
    val fixedArguments = constructor.parameters.map { parameter ->
        arguments[parameter.indexInParameters]
            ?: parameter.defaultValue?.expression
            ?: return null
    }
    val blob = DotNetCustomAttributeEncoder.encode(parameterTypes, fixedArguments) ?: return null
    return ".custom ${classInfo.renderConstructorReference(parameterTypes)} = $blob"
}

/**
 * Exact producer for the ECMA-335 custom-attribute fixed-argument subset whose physical Kotlin
 * constructor signatures already match CLR metadata. Unsupported values return null so the
 * authoritative KLIB application survives without a partial or misleading CLR row.
 */
private object DotNetCustomAttributeEncoder {
    fun encode(
        parameterTypes: List<DotNetIlValueType>,
        arguments: List<IrExpression>,
    ): String? {
        if (parameterTypes.size != arguments.size) return null
        val bytes = mutableListOf(0x01, 0x00) // custom-attribute prolog
        for (argumentEntry in parameterTypes.zip(arguments)) {
            if (!bytes.addFixedArgument(argumentEntry.first, argumentEntry.second)) return null
        }
        bytes += 0x00 // NumNamed = 0
        bytes += 0x00
        return bytes.joinToString(" ", prefix = "(", postfix = ")") { byte ->
            byte.toString(16).padStart(2, '0')
        }
    }

    private fun MutableList<Int>.addFixedArgument(
        type: DotNetIlValueType,
        expression: IrExpression,
    ): Boolean = when (type) {
        DotNetIlValueType.Boolean -> addConst<Boolean>(expression) { value -> add(if (value) 1 else 0) }
        DotNetIlValueType.Int8 -> addConst<Byte>(expression) { value -> add(value.toInt() and 0xff) }
        DotNetIlValueType.Int16 -> addConst<Short>(expression) { value -> addLittleEndian(value.toLong(), 2) }
        DotNetIlValueType.Int32 -> addConst<Int>(expression) { value -> addLittleEndian(value.toLong(), 4) }
        DotNetIlValueType.Int64 -> addConst<Long>(expression) { value -> addLittleEndian(value, 8) }
        DotNetIlValueType.Float32 -> addConst<Float>(expression) { value ->
            addLittleEndian(value.toRawBits().toLong(), 4)
        }
        DotNetIlValueType.Float64 -> addConst<Double>(expression) { value ->
            addLittleEndian(value.toRawBits(), 8)
        }
        DotNetIlValueType.Char -> addConst<Char>(expression) { value -> addLittleEndian(value.code.toLong(), 2) }
        DotNetIlValueType.String -> addConst<String>(expression) { value -> addSerString(value) }
        is DotNetIlValueType.GenericArray -> addArray(type.elementType, expression)
        // PrimitiveArray is a Kotlin wrapper rather than the raw SZARRAY required by a custom
        // attribute constructor. KClass, Kotlin enums, nested annotations, erased classes and
        // open types likewise have no exact fixed-argument carrier in this tranche.
        DotNetIlValueType.Object,
        is DotNetIlValueType.PrimitiveArray,
        is DotNetIlValueType.ErasedGenericArray,
        is DotNetIlValueType.NullableValue,
        is DotNetIlValueType.UserClass,
        is DotNetIlValueType.MappedClass,
        is DotNetIlValueType.TypeParameter,
        is DotNetIlValueType.GenericInstance,
            -> false
    }

    private inline fun <reified T> MutableList<Int>.addConst(
        expression: IrExpression,
        addValue: MutableList<Int>.(T) -> Unit,
    ): Boolean {
        val value = (expression as? IrConst)?.value as? T ?: return false
        addValue(value)
        return true
    }

    private fun MutableList<Int>.addArray(
        elementType: DotNetIlValueType,
        expression: IrExpression,
    ): Boolean {
        // ECMA-335 permits one SZARRAY layer whose element is an admitted fixed scalar/string,
        // not an array-of-array. Reject the nested shape here even if malformed or future Kotlin
        // IR manages to present one, rather than emitting a blob the CLR will misdecode.
        if (elementType is DotNetIlValueType.GenericArray ||
            elementType is DotNetIlValueType.PrimitiveArray ||
            elementType is DotNetIlValueType.ErasedGenericArray
        ) {
            return false
        }
        // Kotlin annotation arrays are serialized in IR as IrVararg. Spreads or executable array
        // builders are not fixed constants and therefore cannot be projected safely here.
        val vararg = expression as? IrVararg ?: return false
        val elements = vararg.elements.map { element -> element as? IrExpression ?: return false }
        addLittleEndian(elements.size.toLong(), 4)
        return elements.all { element -> addFixedArgument(elementType, element) }
    }

    private fun MutableList<Int>.addLittleEndian(value: Long, byteCount: Int) {
        repeat(byteCount) { index -> add(((value ushr (index * 8)) and 0xff).toInt()) }
    }

    private fun MutableList<Int>.addSerString(value: String) {
        val encoded = value.encodeToByteArray()
        when (encoded.size) {
            in 0..0x7f -> add(encoded.size)
            in 0x80..0x3fff -> {
                add(0x80 or (encoded.size ushr 8))
                add(encoded.size and 0xff)
            }
            in 0x4000..0x1fff_ffff -> {
                add(0xc0 or (encoded.size ushr 24))
                add((encoded.size ushr 16) and 0xff)
                add((encoded.size ushr 8) and 0xff)
                add(encoded.size and 0xff)
            }
            else -> error("Internal .NET backend error: custom-attribute string exceeds ECMA-335 SerString limit")
        }
        encoded.forEach { byte -> add(byte.toInt() and 0xff) }
    }
}

/**
 * Conservative C# authoring view. A CLR bit is set only where that bit cannot authorize a wider
 * source use than the admitted Kotlin target. Accessor-only and annotation-class-only targets
 * therefore contribute no Method/Class bit even though exact Kotlin applications still emit on
 * the accessor/type metadata parent selected by FIR.
 */
internal fun IrClass.dotNetAttributeUsageAttribute(typeMapper: DotNetIlTypeMapper): String {
    check(isAnnotationClass)
    val targets = getAnnotationTargets().orEmpty()
    var mask = 0
    if (KotlinTarget.CLASS in targets) {
        mask = mask or 4 or 8 or 16 or 1024 // Class, Struct, Enum, Interface
    }
    if (KotlinTarget.CONSTRUCTOR in targets) mask = mask or 32
    if (KotlinTarget.FUNCTION in targets) mask = mask or 64
    if (KotlinTarget.PROPERTY in targets) mask = mask or 128
    if (KotlinTarget.FIELD in targets || KotlinTarget.BACKING_FIELD in targets) mask = mask or 256
    if (KotlinTarget.VALUE_PARAMETER in targets) mask = mask or 2048

    val allowMultiple = hasAnnotation(StandardNames.FqNames.repeatable)
    val bytes = buildList {
        add(0x01)
        add(0x00)
        repeat(4) { byteIndex -> add((mask ushr (byteIndex * 8)) and 0xff) }
        add(0x02)
        add(0x00)
        addBooleanNamedProperty("AllowMultiple", allowMultiple)
        addBooleanNamedProperty("Inherited", false)
    }.joinToString(" ") { byte -> byte.toString(16).padStart(2, '0') }
    return ".custom instance void ${typeMapper.coreLibrary.reference}System.AttributeUsageAttribute::" +
            ".ctor(valuetype ${typeMapper.coreLibrary.reference}System.AttributeTargets) = ($bytes)"
}

internal fun IrClass.isDotNetRuntimeRetainedAnnotation(): Boolean {
    val retention = getAnnotationRetention()
    return retention != KotlinRetention.SOURCE && retention != KotlinRetention.BINARY
}

private fun MutableList<Int>.addBooleanNamedProperty(name: String, value: Boolean) {
    add(0x54) // PROPERTY
    add(0x02) // ELEMENT_TYPE_BOOLEAN
    val encoded = name.encodeToByteArray()
    check(encoded.size < 0x80)
    add(encoded.size)
    encoded.forEach { byte -> add(byte.toInt() and 0xff) }
    add(if (value) 1 else 0)
}
