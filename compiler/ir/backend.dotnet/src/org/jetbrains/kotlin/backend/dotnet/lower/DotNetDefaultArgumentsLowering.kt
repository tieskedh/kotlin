/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.defaultArgumentsOriginalFunction
import org.jetbrains.kotlin.backend.common.ir.moveBodyTo
import org.jetbrains.kotlin.backend.common.lower.DefaultArgumentStubGenerator
import org.jetbrains.kotlin.backend.common.lower.DefaultParameterCleaner
import org.jetbrains.kotlin.backend.common.lower.DefaultParameterInjector
import org.jetbrains.kotlin.backend.common.lower.MaskedDefaultArgumentFunctionFactory
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.createStaticFunctionWithReceivers
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.utils.addToStdlib.assignFrom

/** Source parameters whose default expressions are removed before IL emission. */
internal var IrSimpleFunction.dotNetDefaultParameterIndices: List<Int>? by irAttribute(copyByDefault = false)

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
    private val dotNetContext: DotNetBackendContext,
    factory: DotNetDefaultArgumentFunctionFactory = DotNetDefaultArgumentFunctionFactory(dotNetContext),
) :
    DefaultArgumentStubGenerator<DotNetBackendContext>(
        dotNetContext,
        factory,
        skipExternalMethods = true,
    ) {
    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        val transformed = super.transformFlat(declaration)
        if (declaration is IrSimpleFunction) {
            transformed
                ?.filterIsInstance<IrSimpleFunction>()
                ?.singleOrNull { it.origin == IrDeclarationOrigin.FUNCTION_FOR_DEFAULT_PARAMETER }
                ?.takeIf { it.dispatchReceiverParameter == null }
                ?.let { dispatcher ->
                    check(dotNetContext.defaultArgumentDispatchers.put(declaration, dispatcher) == null) {
                        "Internal .NET backend error: function has multiple default-argument dispatchers"
                    }
                }
        }
        return transformed
    }
}

internal class DotNetDefaultParameterInjector(
    context: DotNetBackendContext,
    factory: DotNetDefaultArgumentFunctionFactory = DotNetDefaultArgumentFunctionFactory(context),
) :
    DefaultParameterInjector<DotNetBackendContext>(
        context,
        factory,
        skipExternalMethods = true,
    ) {
    // The common injector intentionally leaves omitted vararg defaults absent. Like the JVM,
    // DotNet needs a physical null placeholder for the masked dispatcher array parameter.
    override fun nullConst(startOffset: Int, endOffset: Int, irParameter: IrValueParameter): IrExpression =
        nullConst(startOffset, endOffset, irParameter.type)
}

internal class DotNetDefaultParameterCleaner(
    context: DotNetBackendContext,
) : DefaultParameterCleaner(context) {
    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        if (declaration is IrValueParameter && declaration.defaultValue != null) {
            val function = declaration.parent as? IrSimpleFunction
            val parameterIndex = function?.parameters?.indexOf(declaration) ?: -1
            if (function != null && parameterIndex >= 0) {
                function.dotNetDefaultParameterIndices =
                    function.dotNetDefaultParameterIndices.orEmpty() + parameterIndex
            }
        }
        return super.transformFlat(declaration)
    }
}

/**
 * Gives ordinary Kotlin class members the JVM-shaped static `$default` ABI.
 *
 * The common factory initially produces an instance dispatcher whose dispatch receiver remains
 * an IR receiver. CLR can execute that shape locally, but it cannot be described by the target's
 * existing cross-module dispatcher record. Move the receiver into the static helper's ordinary
 * parameter list, exactly as JVM does. Kotlin-owned class parameters deliberately remain erased;
 * only method-owned type parameters are copied into the CLR method signature. KLIB remains
 * authoritative for the logical owner construction, and the helper still calls the original
 * member virtually.
 */
internal class DotNetClassDefaultArgumentsLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    override fun lower(irModule: IrModuleFragment) {
        val classes = mutableListOf<IrClass>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (!declaration.isInterface) classes += declaration
                declaration.acceptChildrenVoid(this)
            }
        })

        val replacements = mutableMapOf<IrSimpleFunctionSymbol, Replacement>()
        val helpers = linkedMapOf<IrClass, MutableList<Pair<IrSimpleFunction, IrSimpleFunction>>>()
        for (irClass in classes) {
            val defaultStubs = irClass.memberFunctions().filter { function ->
                !function.isFakeOverride &&
                        function.origin == IrDeclarationOrigin.FUNCTION_FOR_DEFAULT_PARAMETER &&
                        function.dispatchReceiverParameter != null &&
                        function.body != null
            }
            for (stub in defaultStubs) {
                val original = stub.defaultArgumentsOriginalFunction as? IrSimpleFunction
                    ?: error("Internal .NET backend error: class default dispatcher has no original function")
                val helper = context.irFactory.createStaticFunctionWithReceivers(
                    irParent = irClass,
                    name = stub.name,
                    oldFunction = stub,
                    dispatchReceiverType = irClass.symbol.defaultType,
                    origin = stub.origin,
                    modality = Modality.FINAL,
                    visibility = DescriptorVisibilities.PUBLIC,
                    isFakeOverride = false,
                )
                helper.body = stub.moveBodyTo(helper)
                val previousDispatcher = context.defaultArgumentDispatchers.put(original, helper)
                check(previousDispatcher == null || previousDispatcher === stub) {
                    "Internal .NET backend error: class member has multiple default-argument dispatchers"
                }
                replacements[stub.symbol] = Replacement(helper)
                helpers.getOrPut(irClass, ::mutableListOf) += stub to helper
            }
        }

        irModule.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)
                val replacement = replacements[expression.symbol] ?: return expression
                return redirectCall(expression, replacement)
            }
        })

        for (mapEntry in helpers) {
            val irClass = mapEntry.key
            val entries = mapEntry.value
            irClass.declarations.removeAll(entries.mapTo(hashSetOf()) { it.first })
            irClass.declarations += entries.map { it.second }
        }
    }

    private fun redirectCall(expression: IrCall, replacement: Replacement): IrCall {
        check(replacement.helper.typeParameters.size == expression.typeArguments.size) {
            "Internal .NET backend error: class default helper type-argument mismatch"
        }
        return IrCallImpl(
            expression.startOffset,
            expression.endOffset,
            expression.type,
            replacement.helper.symbol,
            typeArgumentsCount = replacement.helper.typeParameters.size,
            origin = expression.origin,
        ).apply {
            arguments.assignFrom(expression.arguments)
            expression.typeArguments.forEachIndexed { index, argument ->
                typeArguments[index] = argument
            }
        }
    }

    private fun IrClass.memberFunctions(): List<IrSimpleFunction> = declarations.flatMap { declaration ->
        when (declaration) {
            is IrSimpleFunction -> listOf(declaration)
            is IrProperty -> listOfNotNull(declaration.getter, declaration.setter)
            else -> emptyList()
        }
    }

    private data class Replacement(
        val helper: IrSimpleFunction,
    )
}
