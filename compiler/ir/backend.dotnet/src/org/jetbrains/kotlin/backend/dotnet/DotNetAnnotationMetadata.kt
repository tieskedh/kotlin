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
import org.jetbrains.kotlin.ir.expressions.IrAnnotation
import org.jetbrains.kotlin.ir.util.getAnnotationRetention
import org.jetbrains.kotlin.ir.util.getAnnotationTargets
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.primaryConstructor

/** The selected custom-attribute grammar contains exactly a prolog and zero arguments. */
private const val EMPTY_CUSTOM_ATTRIBUTE_BLOB = "(01 00 00 00)"

internal fun IrAnnotationContainer.dotNetRuntimeMarkerAttributes(
    typeMapper: DotNetIlTypeMapper,
): List<String> = annotations.mapNotNull { annotation ->
    annotation.dotNetRuntimeMarkerAttributeOrNull(typeMapper)
}

private fun IrAnnotation.dotNetRuntimeMarkerAttributeOrNull(
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
    if (constructor.parameters.isNotEmpty() || argumentMapping.isNotEmpty()) {
        dotNetUnsupported(
            "annotation class '${annotationClass.name.asString()}' has values; " +
                    "only parameterless marker annotations are supported"
        )
    }
    return ".custom ${classInfo.renderConstructorReference(emptyList())} = $EMPTY_CUSTOM_ATTRIBUTE_BLOB"
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

private fun IrClass.isDotNetRuntimeRetainedAnnotation(): Boolean {
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
