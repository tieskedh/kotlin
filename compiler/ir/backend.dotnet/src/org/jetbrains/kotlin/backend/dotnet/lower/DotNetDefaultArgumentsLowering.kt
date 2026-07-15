/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.lower.DefaultArgumentStubGenerator
import org.jetbrains.kotlin.backend.common.lower.DefaultParameterCleaner
import org.jetbrains.kotlin.backend.common.lower.DefaultParameterInjector
import org.jetbrains.kotlin.backend.common.lower.MaskedDefaultArgumentFunctionFactory
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.isInterface

/**
 * The common/JVM masked-default shape for ordinary functions and member functions. Missing
 * arguments carry their CLR zero/null placeholder plus one bit per defaultable parameter, and a
 * generated `$default` function resolves the mask before calling the original declaration.
 *
 * Unlike the common factory default, parameter types do not become nullable merely to carry the
 * ignored placeholder. The mask, not the placeholder, owns absence; this is the JVM primitive
 * precedent and keeps CLR signatures stable. Reference slots can already contain null, while
 * value slots use their ordinary zero-initialized value.
 */
internal class DotNetDefaultArgumentFunctionFactory(context: DotNetBackendContext) :
    MaskedDefaultArgumentFunctionFactory(context) {
    override fun IrType.hasNullAsUndefinedValue(): Boolean = false
}

internal class DotNetDefaultArgumentStubGenerator(
    context: DotNetBackendContext,
    private val factory: DotNetDefaultArgumentFunctionFactory = DotNetDefaultArgumentFunctionFactory(context),
) :
    DefaultArgumentStubGenerator<DotNetBackendContext>(
        context,
        factory,
        skipExternalMethods = true,
    ) {
    // CLR constructor stubs need a durable marker/collision policy of their own. Keep omitted
    // constructor arguments on the existing fail-loud path rather than minting an ad-hoc ABI.
    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        if (declaration is IrConstructor) return null
        if (declaration is IrFunction && declaration.defaultOwnerIsInterface(factory)) return null
        return super.transformFlat(declaration)
    }
}

internal class DotNetDefaultParameterInjector(
    context: DotNetBackendContext,
    private val dotNetFactory: DotNetDefaultArgumentFunctionFactory = DotNetDefaultArgumentFunctionFactory(context),
) :
    DefaultParameterInjector<DotNetBackendContext>(
        context,
        dotNetFactory,
        skipExternalMethods = true,
    ) {
    override fun shouldReplaceWithSyntheticFunction(functionAccess: IrFunctionAccessExpression): Boolean =
        functionAccess !is IrConstructorCall &&
                functionAccess !is IrDelegatingConstructorCall &&
                super.shouldReplaceWithSyntheticFunction(functionAccess) &&
                !functionAccess.symbol.owner.defaultOwnerIsInterface(dotNetFactory)
}

internal class DotNetDefaultParameterCleaner(
    context: DotNetBackendContext,
    private val factory: DotNetDefaultArgumentFunctionFactory = DotNetDefaultArgumentFunctionFactory(context),
) : DefaultParameterCleaner(context) {
    // Constructor defaults are intentionally not lowered. Retain their IR marker so the class
    // shape gate can reject data classes that would otherwise expose an incomplete constructor
    // ABI after this phase has cleaned ordinary function parameters.
    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? =
        if (declaration is IrValueParameter && declaration.defaultValue != null &&
            (declaration.parent is IrConstructor ||
                    (declaration.parent as? IrFunction)?.defaultOwnerIsInterface(factory) == true)
        ) {
            null
        } else {
            super.transformFlat(declaration)
        }
}

private fun IrFunction.defaultOwnerIsInterface(factory: DotNetDefaultArgumentFunctionFactory): Boolean =
    (factory.findBaseFunctionWithDefaultArgumentsFor(
        this,
        skipInlineMethods = true,
        skipExternalMethods = true,
    )?.parent as? IrClass)?.isInterface == true
