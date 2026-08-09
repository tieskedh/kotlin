/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.defaultArgumentsDispatchFunction
import org.jetbrains.kotlin.backend.common.defaultArgumentsOriginalFunction
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallOp
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNotEquals
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

internal data class DotNetReflectiveDefaultParameter(
    val exposedIndex: Int,
    val targetParameterIndex: Int,
    val physicalMaskIndex: Int,
)

internal data class DotNetReflectiveDefaultCallPlan(
    val target: IrFunction,
    val optionalParameters: List<DotNetReflectiveDefaultParameter>,
)

internal var IrSimpleFunction.dotNetReflectiveDefaultCallPlan: DotNetReflectiveDefaultCallPlan?
        by irAttribute(copyByDefault = false)

/**
 * Replaces the exponential reflective-default branch family with one dynamic masked-dispatch
 * call after the ordinary default and interface lowerings have selected its final helper.
 *
 * The earlier callable-reference lowering deliberately emits one call with every optional
 * argument absent. Common then supplies the authoritative default dispatcher, placeholders,
 * marker, and physical mask layout. This pass changes only those placeholders and that mask to
 * select between the runtime `object[]` value and omission. The amount of generated IR is linear
 * in the optional parameter count, while default-expression order and dependency remain owned by
 * the same dispatcher as an ordinary Kotlin call.
 */
internal class DotNetReflectiveDefaultMaskLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    override fun lower(irModule: IrModuleFragment) {
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: org.jetbrains.kotlin.ir.IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                declaration.dotNetReflectiveDefaultCallPlan?.let { plan ->
                    patchDefaultCall(declaration, plan)
                }
                declaration.acceptChildrenVoid(this)
            }
        })
    }

    private fun patchDefaultCall(
        capability: IrSimpleFunction,
        plan: DotNetReflectiveDefaultCallPlan,
    ) {
        val regularParameters = capability.parameters.filter { it.kind == IrParameterKind.Regular }
        val argumentsParameter = regularParameters[0]
        val runtimeMaskParameter = regularParameters[1]
        val arrayGet = context.irBuiltIns.arrayClass.owner.functions.single { function ->
            function.name.asString() == "get"
        }
        var matchingCalls = 0
        capability.body?.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
                expression.transformChildrenVoid(this)
                if (expression.origin != IrStatementOrigin.DEFAULT_DISPATCH_CALL ||
                    !isDispatcherFor(expression.symbol.owner, plan.target)
                ) return expression
                matchingCalls++
                val dispatcher = expression.symbol.owner
                val maskParameters = dispatcher.parameters.filter { parameter ->
                    parameter.origin == IrDeclarationOrigin.MASK_FOR_DEFAULT_FUNCTION
                }
                check(maskParameters.size == 1) {
                    "Internal .NET backend error: fixed-arity reflective default call has " +
                            "${maskParameters.size} physical masks instead of one"
                }
                val builder = context.createIrBuilder(capability.symbol).at(expression)
                for (optional in plan.optionalParameters) {
                    val placeholder = expression.arguments[optional.targetParameterIndex]
                        ?: error("Internal .NET backend error: reflective default dispatcher has no placeholder")
                    val supplied = builder.irCall(arrayGet.symbol, placeholder.type).apply {
                        arguments[0] = builder.irGet(argumentsParameter)
                        arguments[1] = builder.irInt(optional.exposedIndex)
                    }
                    expression.arguments[optional.targetParameterIndex] = builder.irIfThenElse(
                        placeholder.type,
                        runtimeBitIsSet(builder, runtimeMaskParameter, optional.exposedIndex),
                        placeholder,
                        supplied,
                    )
                }
                val physicalMaskContributions: List<IrExpression> = plan.optionalParameters
                    .map { optional ->
                        builder.irIfThenElse(
                            context.irBuiltIns.intType,
                            runtimeBitIsSet(builder, runtimeMaskParameter, optional.exposedIndex),
                            builder.irInt(1 shl optional.physicalMaskIndex),
                            builder.irInt(0),
                        )
                    }
                val physicalMask = physicalMaskContributions.reduce { left, right ->
                        builder.irCallOp(context.irBuiltIns.intPlusSymbol, context.irBuiltIns.intType, left, right)
                    }
                expression.arguments[maskParameters.single().indexInParameters] = physicalMask
                return expression
            }
        })
        check(matchingCalls == 1) {
            "Internal .NET backend error: reflective default capability '${capability.name}' found " +
                    "$matchingCalls default dispatch calls instead of one"
        }
    }

    private fun isDispatcherFor(dispatcher: IrFunction, target: IrFunction): Boolean {
        val sources = buildList {
            add(target)
            if (target is IrSimpleFunction) addAll(target.allOverridden())
        }
        return sources.any { source ->
            source.defaultArgumentsDispatchFunction === dispatcher ||
                    dispatcher.defaultArgumentsOriginalFunction === source ||
                    (source is IrSimpleFunction && context.defaultArgumentDispatchers[source] === dispatcher)
        }
    }

    private fun runtimeBitIsSet(
        builder: org.jetbrains.kotlin.ir.builders.IrBuilderWithScope,
        runtimeMaskParameter: org.jetbrains.kotlin.ir.declarations.IrValueParameter,
        exposedIndex: Int,
    ): IrExpression = builder.irNotEquals(
        builder.irCallOp(
            context.irBuiltIns.intAndSymbol,
            context.irBuiltIns.intType,
            builder.irGet(runtimeMaskParameter),
            builder.irInt(1 shl exposedIndex),
        ),
        builder.irInt(0),
    )
}
