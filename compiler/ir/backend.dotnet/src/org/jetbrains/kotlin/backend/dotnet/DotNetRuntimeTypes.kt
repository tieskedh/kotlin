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
import org.jetbrains.kotlin.ir.util.isKFunction
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.types.Variance

/**
 * The callable portion of the Kotlin.Runtime ABI candidate evaluated by this POC.
 *
 * Common IR speaks in synthetic `kotlin.Function$arity` and `kotlin.reflect.KFunction$arity`
 * classifiers. This registry maps fixed execution arities 0..2 to erased Kotlin-owned CLR
 * interfaces and every supported KFunction arity to one orthogonal, non-generic reflection view.
 * FunctionN uses object-shaped Invoke slots, following the JVM executable descriptor rather than
 * CLR generic variance: Kotlin's logical type arguments remain in IR/metadata, while every legal
 * function-type variance conversion is the same object reference at runtime. It deliberately
 * does not model the JVM's unrelated high-arity `FunctionN` fallback. CLR delegates remain an
 * interop concern and never appear in Kotlin-to-Kotlin signatures.
 */
internal object DotNetRuntimeTypes {
    val DEFAULT_CONSTRUCTOR_MARKER_FQ_NAME = FqName("kotlin.runtime.internal.DefaultConstructorMarker")

    private val unitClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.Unit",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )
    val unitType = DotNetIlValueType.UserClass(unitClass)

    private val functionBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.Function",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val kCallableBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.KCallable",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val kFunctionBase = DotNetIlClassInfo(
        ilClassName = "Kotlin.KFunction",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val mutableRefClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.Runtime.Internal.MutableRef`1",
        typeParameterVariances = listOf(Variance.INVARIANT),
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val defaultConstructorMarkerClass = DotNetIlClassInfo(
        ilClassName = "Kotlin.Runtime.Internal.DefaultConstructorMarker",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )

    private val fixedFunctionClasses = listOf(
        functionClassInfo(arity = 0),
        functionClassInfo(arity = 1),
        functionClassInfo(arity = 2),
    )

    init {
        kFunctionBase.interfaces = listOf(
            DotNetIlValueType.UserClass(kCallableBase),
            DotNetIlValueType.UserClass(functionBase),
        )
        fixedFunctionClasses.forEach { classInfo ->
            classInfo.interfaces = listOf(DotNetIlValueType.UserClass(functionBase))
        }
    }

    fun classInfoFor(irClass: IrClass): DotNetIlClassInfo? = when {
        irClass.isDotNetMutableRefStub == true -> mutableRefClass
        irClass.isDotNetKCallableBase -> kCallableBase
        irClass.isDotNetKFunctionBase || irClass.dotNetFixedKFunctionArityOrNull() != null -> kFunctionBase
        irClass.isDotNetFunctionBase -> functionBase
        else -> irClass.dotNetFixedFunctionArityOrNull()?.let(fixedFunctionClasses::get)
    }

    fun mapCallableType(type: IrType): DotNetIlValueType.UserClass? {
        val simpleType = type as? IrSimpleType ?: return null
        val irClass = simpleType.classifier.owner as? IrClass ?: return null
        val classInfo = when {
            irClass.isDotNetFunctionBase -> {
                if (simpleType.arguments.size != 1) return null
                functionBase
            }
            irClass.isDotNetKCallableBase -> {
                if (simpleType.arguments.size != 1) return null
                kCallableBase
            }
            irClass.isDotNetKFunctionBase -> {
                if (simpleType.arguments.size != 1) return null
                kFunctionBase
            }
            else -> {
                val functionArity = irClass.dotNetFixedFunctionArityOrNull()
                if (functionArity != null) {
                    if (simpleType.arguments.size != functionArity + 1) return null
                    fixedFunctionClasses[functionArity]
                } else {
                    val kFunctionArity = irClass.dotNetFixedKFunctionArityOrNull() ?: return null
                    if (simpleType.arguments.size != kFunctionArity + 1) return null
                    kFunctionBase
                }
            }
        }
        // Projections, including Function<*>, affect only Kotlin's logical view. A marker or
        // fixed-arity value still has the same erased physical interface and reference identity.
        return DotNetIlValueType.UserClass(classInfo)
    }

    fun mapCompilerRuntimeType(type: IrType): DotNetIlValueType.UserClass? {
        val simpleType = type as? IrSimpleType ?: return null
        val irClass = simpleType.classifier.owner as? IrClass ?: return null
        if (irClass.fqNameWhenAvailable == DEFAULT_CONSTRUCTOR_MARKER_FQ_NAME && simpleType.arguments.isEmpty()) {
            return DotNetIlValueType.UserClass(defaultConstructorMarkerClass)
        }
        return mapCallableType(type)
    }

    fun registerCallableFunctions(
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
        val nameGetter = irBuiltIns.kCallableClass.owner.properties
            .single { it.name.asString() == "name" }
            .getter
            ?: error("Internal .NET backend error: kotlin.reflect.KCallable.name has no getter")
        availableFunctions[nameGetter] = DotNetIlFunctionInfo(
            kCallableBase,
            nameGetter.dotNetSignature(typeMapper),
        )
    }

    val unitInstanceLoadInstruction: String
        get() = "ldsfld ${unitType.nameInSignature} " +
                "[${DotNetRuntimeLibrary.ASSEMBLY_NAME}]'Kotlin.Unit'::INSTANCE"

    fun isFixedFunctionType(type: DotNetIlValueType, arity: Int): Boolean =
        arity in fixedFunctionClasses.indices && type == DotNetIlValueType.UserClass(fixedFunctionClasses[arity])

    private fun functionClassInfo(arity: Int): DotNetIlClassInfo = DotNetIlClassInfo(
        ilClassName = "Kotlin.Function$arity",
        assemblyName = DotNetRuntimeLibrary.ASSEMBLY_NAME,
    )
}

private val IrClass.isDotNetFunctionBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.Function" && typeParameters.size == 1

private val IrClass.isDotNetKCallableBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.reflect.KCallable" && typeParameters.size == 1

private val IrClass.isDotNetKFunctionBase: Boolean
    get() = fqNameWhenAvailable?.asString() == "kotlin.reflect.KFunction" && typeParameters.size == 1

internal fun IrClass.dotNetFixedFunctionArityOrNull(): Int? {
    val fqName = fqNameWhenAvailable?.asString() ?: return null
    val arity = fqName.removePrefix("kotlin.Function").toIntOrNull() ?: return null
    return arity.takeIf { it in 0..2 && typeParameters.size == it + 1 }
}

internal fun IrClass.dotNetFixedKFunctionArityOrNull(): Int? {
    if (!symbol.isKFunction()) return null
    val arity = name.asString().removePrefix("KFunction").toIntOrNull() ?: return null
    // Unlike kotlin.FunctionN, the synthetic KFunctionN classifiers exposed by the common
    // built-ins do not reliably carry their logical type parameters on the IrClass itself.
    // The instantiated IrSimpleType still carries, and mapCallableType validates, arity + 1
    // arguments. Class identity therefore comes from the canonical built-in FQ name here.
    return arity.takeIf { it in 0..2 }
}
