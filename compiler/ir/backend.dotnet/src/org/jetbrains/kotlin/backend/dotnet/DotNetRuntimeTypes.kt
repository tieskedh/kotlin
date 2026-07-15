/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.invokeFun

/**
 * The callable portion of the Kotlin.Runtime ABI candidate evaluated by this POC.
 *
 * Common IR still speaks in synthetic `kotlin.Function$arity` classifiers. This registry maps
 * only fixed arities 0..2 to physical Kotlin-owned CLR interfaces. The CLR interfaces are erased
 * by arity and use object-shaped Invoke slots, following the JVM executable descriptor rather
 * than CLR generic variance: Kotlin's logical type arguments remain in IR/metadata, while every
 * legal source variance conversion is the same object reference at runtime. It deliberately does
 * not model the JVM's unrelated high-arity `FunctionN` fallback. CLR delegates remain an interop
 * concern and never appear in Kotlin-to-Kotlin signatures.
 */
internal object DotNetRuntimeTypes {
    private val unitClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.Unit",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )
    val unitType = DotNetIlValueType.UserClass(unitClass)

    private val functionBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.Function",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val fixedFunctionClasses = listOf(
        functionClassInfo(arity = 0),
        functionClassInfo(arity = 1),
        functionClassInfo(arity = 2),
    )

    init {
        fixedFunctionClasses.forEach { classInfo ->
            classInfo.interfaces = listOf(DotNetIlValueType.UserClass(functionBase))
        }
    }

    fun classInfoFor(irClass: IrClass): DotNetIlClassInfo? = when {
        irClass.isDotNetFunctionBase -> functionBase
        else -> irClass.dotNetFixedFunctionArityOrNull()?.let(fixedFunctionClasses::get)
    }

    fun mapFunctionType(type: IrType): DotNetIlValueType.UserClass? {
        val simpleType = type as? IrSimpleType ?: return null
        val irClass = simpleType.classifier.owner as? IrClass ?: return null
        val classInfo = if (irClass.isDotNetFunctionBase) {
            if (simpleType.arguments.size != 1) return null
            functionBase
        } else {
            val arity = irClass.dotNetFixedFunctionArityOrNull() ?: return null
            if (simpleType.arguments.size != arity + 1) return null
            fixedFunctionClasses[arity]
        }
        // Projections, including Function<*>, affect only Kotlin's logical view. A marker or
        // fixed-arity value still has the same erased physical interface and reference identity.
        return DotNetIlValueType.UserClass(classInfo)
    }

    fun registerInvokeFunctions(
        irBuiltIns: IrBuiltIns,
        typeMapper: DotNetIlTypeMapper,
        availableFunctions: MutableMap<IrSimpleFunction, DotNetIlFunctionInfo>,
    ) {
        for (arity in fixedFunctionClasses.indices) {
            val invoke = irBuiltIns.functionN(arity).invokeFun
                ?: error("Internal .NET backend error: kotlin.Function$arity has no invoke member")
            availableFunctions[invoke] = DotNetIlFunctionInfo(
                fixedFunctionClasses[arity],
                invoke.dotNetSignature(typeMapper),
            )
        }
    }

    val unitInstanceLoadInstruction: String
        get() = "ldsfld ${unitType.nameInSignature} " +
                "[${DotNetRuntimeLibrary.ASSEMBLY_NAME}]'Kotlin.Unit'::INSTANCE"

    private fun functionClassInfo(arity: Int): DotNetIlClassInfo = DotNetIlClassInfo(
        ilClassName = "Kotlin.Function$arity",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )
}

private val IrClass.isDotNetFunctionBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.Function" && typeParameters.size == 1

internal fun IrClass.dotNetFixedFunctionArityOrNull(): Int? {
    val fqName = fqNameWhenAvailable?.asString() ?: return null
    val arity = fqName.removePrefix("kotlin.Function").toIntOrNull() ?: return null
    return arity.takeIf { it in 0..2 && typeParameters.size == it + 1 }
}
