/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.isKFunction
import org.jetbrains.kotlin.ir.util.isKSuspendFunction
import org.jetbrains.kotlin.ir.util.isSuspendFunction
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

/** The arity of a synthetic IR classifier mapped to Runtime.Internal.ExactFunctionN. */
internal var IrClass.dotNetExactFunctionArity: Int? by irAttribute(copyByDefault = false)

/**
 * IR-only symbols for the optional typed execution capability of compiler-generated callables.
 *
 * These interfaces are not Kotlin source declarations and are never emitted into a user module.
 * [DotNetRuntimeTypes] maps them to metadata-public types in Kotlin.Runtime. Their only purpose is
 * to let a generated callable object implement an exact CLR signature next to — never instead of
 * — its erased Kotlin.FunctionN identity.
 */
internal class DotNetExactCallableSymbols(
    private val irBuiltIns: IrBuiltIns,
    irFactory: IrFactory,
    irModuleFragment: IrModuleFragment,
) {
    private data class ExactFunction(
        val irClass: IrClass,
        val invoke: IrSimpleFunction,
    )

    private val exactFunctions: List<ExactFunction> = run {
        val runtimeInternalPackage = createEmptyExternalPackageFragment(
            irModuleFragment,
            FqName("kotlin.runtime.internal"),
        )
        List(4) { arity ->
            val irClass = irFactory.buildClass {
                origin = IrDeclarationOrigin.IR_BUILTINS_STUB
                name = Name.identifier("ExactFunction$arity")
                visibility = DescriptorVisibilities.PUBLIC
                modality = Modality.ABSTRACT
                kind = ClassKind.INTERFACE
            }.apply {
                parent = runtimeInternalPackage
                dotNetExactFunctionArity = arity
                superTypes = listOf(irBuiltIns.anyType)
                repeat(arity) { index ->
                    addTypeParameter {
                        name = Name.identifier("P$index")
                        variance = Variance.IN_VARIANCE
                        superTypes += irBuiltIns.anyNType
                    }
                }
                addTypeParameter {
                    name = Name.identifier("R")
                    variance = Variance.OUT_VARIANCE
                    superTypes += irBuiltIns.anyNType
                }
                createThisReceiverParameter()
            }
            val invoke = irClass.addFunction {
                origin = IrDeclarationOrigin.IR_BUILTINS_STUB
                name = Name.identifier("InvokeExact")
                visibility = DescriptorVisibilities.PUBLIC
                modality = Modality.ABSTRACT
                returnType = irClass.typeParameters.last().defaultType
            }.apply {
                parameters += createDispatchReceiverParameterWithClassParent()
                repeat(arity) { index ->
                    addValueParameter("p${index + 1}", irClass.typeParameters[index].defaultType)
                }
            }
            ExactFunction(irClass, invoke)
        }
    }

    /** Exact interface view for a non-suspend, non-Unit Function0/1/2/3-like type. */
    fun typeFor(callableType: IrType): IrType? {
        if ((!callableType.isFunction() && !callableType.isKFunction()) ||
            callableType.isSuspendFunction() || callableType.isKSuspendFunction()
        ) {
            return null
        }
        val simpleType = callableType as? IrSimpleType ?: return null
        val arity = simpleType.arguments.size - 1
        if (arity !in exactFunctions.indices) return null
        val resultType = (simpleType.arguments.lastOrNull() as? IrTypeProjection)?.type ?: return null
        if (resultType.isUnit()) return null
        return exactFunctions[arity].irClass.symbol.typeWithArguments(simpleType.arguments)
    }

    fun invokeForArity(arity: Int): IrSimpleFunction = exactFunctions[arity].invoke
}
