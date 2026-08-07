/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.ir.createArrayOfExpression
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.dotNetUnsupported
import org.jetbrains.kotlin.backend.dotnet.serialization.DotNetIrMangler
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.kClassReference
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.isTypeOfIntrinsic
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.toIrConst
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.types.Variance

/**
 * Materializes Common `KType` graphs after the shared inliner has substituted reified call-site
 * arguments. Declaration parameters are allocated before any bound is built, matching the JVM's
 * complete recursive-bound model rather than making a CLR generic handle authoritative.
 */
internal class DotNetTypeOfLowering(
    private val backendContext: DotNetBackendContext,
) : ModuleLoweringPass {
    private val kTypeBuilder = DotNetKTypeIrBuilder(backendContext, operation = "typeOf")

    override fun lower(irModule: IrModuleFragment) {
        irModule.transformChildrenVoid(object : IrElementTransformerVoidWithContext() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)
                if (!expression.symbol.isTypeOfIntrinsic()) return expression

                val representedType = expression.typeArguments.singleOrNull()
                    ?: dotNetUnsupported("typeOf intrinsic has no single type argument")
                val builder = backendContext.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol).at(expression)
                return kTypeBuilder.run {
                    builder.buildGraph(representedType, expression.type)
                }
            }
        })
    }
}

/**
 * Shared logical KType producer for `typeOf` and declaration-owned reflection facts. The caller
 * chooses the represented semantic IR type; this builder never inspects a physical CLR signature.
 */
internal class DotNetKTypeIrBuilder(
    private val backendContext: DotNetBackendContext,
    private val operation: String,
) {
    fun IrBuilderWithScope.buildGraph(
        representedType: IrType,
        resultType: IrType = backendContext.irBuiltIns.kTypeClass.defaultType,
    ): IrExpression = buildParameterGraph(
        representedTypes = listOf(representedType),
        declaredParameters = emptyList(),
        resultType = resultType,
    ) { parameterVariables ->
        buildKType(representedType, parameterVariables)
    }

    /**
     * Encodes one declaration-owned callable signature as `[returnType, own type parameters...]`.
     * The private runtime transport is deliberately a single value: all exposed classifiers are
     * allocated in this block and therefore retain JVM-style object identity across the graph.
     */
    fun IrBuilderWithScope.buildCallableSignature(
        returnType: IrType,
        declaredParameters: List<IrTypeParameter>,
    ): IrExpression {
        val signatureType = backendContext.irBuiltIns.arrayClass.typeWithArguments(
            listOf(backendContext.irBuiltIns.anyNType),
        )
        return buildParameterGraph(
            representedTypes = listOf(returnType),
            declaredParameters = declaredParameters,
            resultType = signatureType,
        ) { parameterVariables ->
            backendContext.createArrayOfExpression(
                startOffset,
                endOffset,
                backendContext.irBuiltIns.anyNType,
                listOf(buildKType(returnType, parameterVariables)) +
                        declaredParameters.map { parameter -> irGet(parameterVariables.getValue(parameter)) },
            )
        }
    }

    private fun IrBuilderWithScope.buildParameterGraph(
        representedTypes: List<IrType>,
        declaredParameters: List<IrTypeParameter>,
        resultType: IrType,
        buildResult: IrBuilderWithScope.(Map<IrTypeParameter, IrVariable>) -> IrExpression,
    ): IrExpression {
        val parameters = linkedSetOf<IrTypeParameter>()
        representedTypes.forEach { type -> collectTypeParameters(type, parameters) }
        declaredParameters.forEach { parameter -> collectTypeParameter(parameter, parameters) }

        return irBlock(resultType = resultType) {
            val parameterVariables = linkedMapOf<IrTypeParameter, IrVariable>()
            for (parameter in parameters) {
                val createParameter = irCall(backendContext.symbols.dotNetCreateKTypeParameter).apply {
                    arguments[0] = parameter.name.asString().toIrConst(backendContext.irBuiltIns.stringType)
                    arguments[1] = parameter.variance.runtimeName.toIrConst(backendContext.irBuiltIns.stringType)
                    arguments[2] = parameter.isReified.toIrConst(backendContext.irBuiltIns.booleanType)
                    arguments[3] = parameter.containerKey.toIrConst(backendContext.irBuiltIns.stringType)
                }
                parameterVariables[parameter] = irTemporary(
                    createParameter,
                    nameHint = "<${operation.replace(' ', '-')}-${parameter.name.asString()}>",
                )
            }

            for (parameter in parameters) {
                +irCall(backendContext.symbols.dotNetInitializeKTypeParameterUpperBounds).apply {
                    arguments[0] = irGet(parameterVariables.getValue(parameter))
                    arguments[1] = backendContext.createArrayOfExpression(
                        startOffset,
                        endOffset,
                        backendContext.irBuiltIns.kTypeClass.defaultType,
                        parameter.superTypes.map { upperBound -> buildKType(upperBound, parameterVariables) },
                    )
                }
            }

            +buildResult(parameterVariables)
        }
    }

    private fun collectTypeParameter(parameter: IrTypeParameter, result: MutableSet<IrTypeParameter>) {
        if (result.add(parameter)) {
            parameter.superTypes.forEach { upperBound -> collectTypeParameters(upperBound, result) }
        }
    }

    private fun collectTypeParameters(type: IrType, result: MutableSet<IrTypeParameter>) {
        val simpleType = type as? IrSimpleType
            ?: dotNetUnsupported("$operation cannot represent non-denotable type '${type.render()}' yet")
        val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
        if (parameter != null) collectTypeParameter(parameter, result)
        for (argument in simpleType.arguments) {
            if (argument is IrTypeProjection) collectTypeParameters(argument.type, result)
        }
    }

    private fun IrBuilderWithScope.buildKType(
        type: IrType,
        parameterVariables: Map<IrTypeParameter, IrVariable>,
    ): IrExpression {
        val simpleType = type as? IrSimpleType
            ?: dotNetUnsupported("$operation cannot represent non-denotable type '${type.render()}' yet")
        val classifier = when (val symbol = simpleType.classifier) {
            is IrClassSymbol -> kClassReference(symbol.defaultType)
            is IrTypeParameterSymbol -> irGet(
                parameterVariables[symbol.owner]
                    ?: error("Internal .NET backend error: unallocated $operation parameter '${symbol.owner.name}'")
            )
            else -> dotNetUnsupported("$operation has unsupported classifier '${symbol.owner.render()}'")
        }
        val projections = simpleType.arguments.map { argument ->
            when (argument) {
                is IrStarProjection -> irCall(backendContext.symbols.dotNetStarKTypeProjection)
                is IrTypeProjection -> {
                    val factory = when (argument.variance) {
                        Variance.INVARIANT -> backendContext.symbols.dotNetInvariantKTypeProjection
                        Variance.IN_VARIANCE -> backendContext.symbols.dotNetContravariantKTypeProjection
                        Variance.OUT_VARIANCE -> backendContext.symbols.dotNetCovariantKTypeProjection
                    }
                    irCall(factory).apply {
                        arguments[0] = buildKType(argument.type, parameterVariables)
                    }
                }
            }
        }
        return irCall(backendContext.symbols.dotNetCreateKType).apply {
            arguments[0] = classifier
            arguments[1] = backendContext.createArrayOfExpression(
                startOffset,
                endOffset,
                backendContext.symbols.dotNetStarKTypeProjection.owner.returnType,
                projections,
            )
            arguments[2] = simpleType.isMarkedNullable().toIrConst(backendContext.irBuiltIns.booleanType)
        }
    }

    private val IrTypeParameter.containerKey: String
        get() {
            val container = parent as? IrDeclaration
                ?: error("Internal .NET backend error: $operation parameter '$name' has no declaration container")
            return with(DotNetIrMangler) { container.mangleString(compatibleMode = false) }
        }

    private val Variance.runtimeName: String
        get() = when (this) {
            Variance.INVARIANT -> "invariant"
            Variance.IN_VARIANCE -> "in"
            Variance.OUT_VARIANCE -> "out"
        }
}
