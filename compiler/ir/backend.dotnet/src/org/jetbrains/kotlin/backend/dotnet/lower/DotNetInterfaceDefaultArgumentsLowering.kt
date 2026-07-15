/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.ir.moveBodyTo
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.AbstractIrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.IrTypeParameterRemapper
import org.jetbrains.kotlin.ir.util.createStaticFunctionWithReceivers
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.remapTypes
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.utils.addToStdlib.assignFrom

private val DOTNET_DEFAULT_IMPLS: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_DEFAULT_IMPLS")

/**
 * Moves masked default-argument dispatchers off CLR interfaces while keeping the source member
 * abstract. This follows the JVM `DefaultImpls` model: one compiler-reserved nested helper owns
 * static dispatcher methods whose first ordinary parameter is the interface receiver. The
 * dispatcher evaluates defaults, then invokes the abstract slot through that receiver, so the
 * implementing class still owns virtual dispatch.
 *
 * The CLR-specific spelling lifts the interface's type parameters onto each static helper method.
 * A nested metadata type does not capture its generic parent, and method parameters give call
 * sites one explicit instantiation list without making the helper itself generic. Calls recover
 * that list from the receiver's instantiated interface view. The interface remains all-abstract,
 * so both modern CoreCLR and the .NET Framework 4.8 ILAsm floor accept the result; no Default
 * Interface Method body is introduced.
 */
internal class DotNetInterfaceDefaultArgumentsLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    override fun lower(irModule: IrModuleFragment) {
        val stubsByInterface = collectDefaultStubs(irModule)
        if (stubsByInterface.isEmpty()) return

        val replacements = mutableMapOf<IrSimpleFunctionSymbol, Replacement>()
        for (entry in stubsByInterface.entries) {
            val irInterface = entry.key
            val stubs = entry.value
            val helper = createHelper(irInterface)
            for (stub in stubs) {
                val helperFunction = context.irFactory.createStaticFunctionWithReceivers(
                    irParent = helper,
                    name = stub.name,
                    oldFunction = stub,
                    dispatchReceiverType = irInterface.symbol.defaultType,
                    origin = stub.origin,
                    modality = Modality.FINAL,
                    visibility = DescriptorVisibilities.PUBLIC,
                    isFakeOverride = false,
                    typeParametersFromContext = irInterface.typeParameters,
                )
                // CLR variance is declaration-site metadata only on interfaces and delegates.
                // The copied owner slots are ordinary invariant method parameters here.
                helperFunction.typeParameters.take(irInterface.typeParameters.size).forEach {
                    it.variance = Variance.INVARIANT
                }
                val typeParameterMap = mutableMapOf<IrTypeParameter, IrTypeParameter>()
                irInterface.typeParameters.zip(
                    helperFunction.typeParameters.take(irInterface.typeParameters.size)
                ).forEach { pair -> typeParameterMap[pair.first] = pair.second }
                stub.typeParameters.zip(
                    helperFunction.typeParameters.drop(irInterface.typeParameters.size)
                ).forEach { pair -> typeParameterMap[pair.first] = pair.second }
                helperFunction.body = stub.moveBodyTo(helperFunction)?.also { body ->
                    body.remapTypes(IrTypeParameterRemapper(typeParameterMap))
                }
                helper.declarations += helperFunction
                replacements[stub.symbol] = Replacement(irInterface, helperFunction)
            }
            irInterface.declarations.removeAll(stubs.toSet())
            irInterface.declarations += helper
        }

        irModule.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)
                val replacement = replacements[expression.symbol] ?: return expression
                return redirectCall(expression, replacement)
            }
        })
    }

    private fun collectDefaultStubs(irModule: IrModuleFragment): Map<IrClass, List<IrSimpleFunction>> {
        val result = linkedMapOf<IrClass, List<IrSimpleFunction>>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.isInterface) {
                    val stubs = declaration.declarations.filterIsInstance<IrSimpleFunction>().filter { function ->
                        !function.isFakeOverride &&
                                function.origin == IrDeclarationOrigin.FUNCTION_FOR_DEFAULT_PARAMETER &&
                                function.body != null
                    }
                    if (stubs.isNotEmpty()) result[declaration] = stubs
                }
                declaration.acceptChildrenVoid(this)
            }
        })
        return result
    }

    private fun createHelper(irInterface: IrClass): IrClass = context.irFactory.buildClass {
        startOffset = irInterface.startOffset
        endOffset = irInterface.endOffset
        origin = DOTNET_DEFAULT_IMPLS
        name = DEFAULT_IMPLS_NAME
        kind = ClassKind.CLASS
        modality = Modality.FINAL
        visibility = DescriptorVisibilities.PUBLIC
    }.apply {
        parent = irInterface
        createThisReceiverParameter()
    }

    private fun redirectCall(expression: IrCall, replacement: Replacement): IrCall {
        val interfaceArguments = if (replacement.owner.typeParameters.isEmpty()) {
            emptyList()
        } else {
            val receiverType = expression.dispatchReceiver?.type as? IrSimpleType
                ?: error("Internal .NET backend error: interface default call has no simple receiver type")
            val substitutor = AbstractIrTypeSubstitutor.forSuperClass(replacement.owner.symbol, receiverType)
                ?: error(
                    "Internal .NET backend error: default-call receiver is not a subtype of " +
                            "'${replacement.owner.name.asString()}'"
                )
            replacement.owner.typeParameters.map { typeParameter ->
                substitutor.substitute(typeParameter.defaultType)
            }
        }
        check(replacement.function.typeParameters.size == interfaceArguments.size + expression.typeArguments.size) {
            "Internal .NET backend error: interface default helper type-argument mismatch"
        }
        return IrCallImpl(
            expression.startOffset,
            expression.endOffset,
            expression.type,
            replacement.function.symbol,
            typeArgumentsCount = replacement.function.typeParameters.size,
            origin = expression.origin,
        ).apply {
            arguments.assignFrom(expression.arguments)
            interfaceArguments.forEachIndexed { index, argument ->
                typeArguments[index] = argument
            }
            expression.typeArguments.forEachIndexed { index, argument ->
                typeArguments[interfaceArguments.size + index] = argument
            }
        }
    }

    private data class Replacement(
        val owner: IrClass,
        val function: IrSimpleFunction,
    )

    private companion object {
        val DEFAULT_IMPLS_NAME: Name = Name.special("<DefaultImpls>")
    }
}
