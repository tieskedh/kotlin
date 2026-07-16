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
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
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

/** The arity of a synthetic IR classifier mapped to Runtime.Internal.TypedArgumentsFunctionN. */
internal var IrClass.dotNetTypedArgumentsFunctionArity: Int? by irAttribute(copyByDefault = false)

/**
 * IR-only symbols for the optional primitive-argument execution capability.
 *
 * Unlike [DotNetExactCallableSymbols], this capability deliberately erases only the result. It
 * exists for a narrow CLR gap: a `Function1<Int, Int>` object cannot be viewed as
 * `ExactFunction1<Int, Any>` because CLR generic variance does not apply through a value-type
 * result, even though its `Int` argument can still be passed without boxing. The capability is
 * added only to compiler-generated non-Unit Function1/2 objects with a concrete primitive-shaped
 * parameter. Kotlin.FunctionN remains their sole identity and storage ABI.
 */
internal class DotNetTypedArgumentsCallableSymbols(
    private val irBuiltIns: IrBuiltIns,
    irFactory: IrFactory,
    irModuleFragment: IrModuleFragment,
) {
    private data class TypedArgumentsFunction(
        val irClass: IrClass,
        val invoke: IrSimpleFunction,
    )

    private val functionsByArity: Map<Int, TypedArgumentsFunction> = run {
        val runtimeInternalPackage = createEmptyExternalPackageFragment(
            irModuleFragment,
            FqName("kotlin.runtime.internal"),
        )
        (1..2).associateWith { arity ->
            val irClass = irFactory.buildClass {
                origin = IrDeclarationOrigin.IR_BUILTINS_STUB
                name = Name.identifier("TypedArgumentsFunction$arity")
                visibility = DescriptorVisibilities.PUBLIC
                modality = Modality.ABSTRACT
                kind = ClassKind.INTERFACE
            }.apply {
                parent = runtimeInternalPackage
                dotNetTypedArgumentsFunctionArity = arity
                superTypes = listOf(irBuiltIns.anyType)
                repeat(arity) { index ->
                    addTypeParameter {
                        name = Name.identifier("P$index")
                        variance = Variance.IN_VARIANCE
                        superTypes += irBuiltIns.anyNType
                    }
                }
                createThisReceiverParameter()
            }
            val invoke = irClass.addFunction {
                origin = IrDeclarationOrigin.IR_BUILTINS_STUB
                name = Name.identifier("InvokeTyped")
                visibility = DescriptorVisibilities.PUBLIC
                modality = Modality.ABSTRACT
                returnType = irBuiltIns.anyNType
            }.apply {
                parameters += createDispatchReceiverParameterWithClassParent()
                repeat(arity) { index ->
                    addValueParameter("p${index + 1}", irClass.typeParameters[index].defaultType)
                }
            }
            TypedArgumentsFunction(irClass, invoke)
        }
    }

    /** Partial interface view for the deliberately bounded generated-callable subset. */
    fun typeFor(callableType: IrType): IrType? {
        if ((!callableType.isFunction() && !callableType.isKFunction()) ||
            callableType.isSuspendFunction() || callableType.isKSuspendFunction()
        ) {
            return null
        }
        val simpleType = callableType as? IrSimpleType ?: return null
        val arity = simpleType.arguments.size - 1
        val function = functionsByArity[arity] ?: return null
        val logicalTypes = simpleType.arguments.map { argument ->
            (argument as? IrTypeProjection)?.type ?: return null
        }
        if (logicalTypes.last().isUnit()) return null
        val parameterTypes = logicalTypes.take(arity)
        if (parameterTypes.none(IrType::isDotNetConcretePrimitiveShape)) return null
        return function.irClass.symbol.typeWithArguments(simpleType.arguments.take(arity))
    }

    fun invokeForArity(arity: Int): IrSimpleFunction =
        functionsByArity[arity]?.invoke
            ?: error("Internal .NET backend error: no typed-arguments callable interface for arity $arity")
}

private fun IrType.isDotNetConcretePrimitiveShape(): Boolean =
    classFqName?.asString() in DOTNET_TYPED_ARGUMENT_PRIMITIVE_FQ_NAMES

private val DOTNET_TYPED_ARGUMENT_PRIMITIVE_FQ_NAMES = setOf(
    "kotlin.Boolean",
    "kotlin.Int",
    "kotlin.Long",
    "kotlin.Double",
    "kotlin.Char",
)
