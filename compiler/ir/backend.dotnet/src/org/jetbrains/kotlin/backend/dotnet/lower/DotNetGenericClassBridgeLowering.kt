/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalDeclarations
import org.jetbrains.kotlin.backend.dotnet.DotNetLoweredGenericClassBridge
import org.jetbrains.kotlin.backend.dotnet.dotNetDirectOwnerRelativeMethodBoundsOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetExternalLibraries
import org.jetbrains.kotlin.backend.dotnet.dotNetUnsupported
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericClassDeclaration
import org.jetbrains.kotlin.backend.dotnet.isDotNetOwnerDependentConstraint
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.name.Name

internal val DOTNET_GENERIC_CLASS_CANONICAL_BRIDGE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_CLASS_CANONICAL_BRIDGE")

private fun IrType.referencesTypeParameterOf(owner: IrClass): Boolean {
    val simpleType = this as? IrSimpleType ?: return false
    val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
    if (parameter?.parent == owner) return true
    return simpleType.arguments.any { argument ->
        (argument as? IrTypeProjection)?.type?.referencesTypeParameterOf(owner) == true
    }
}

/**
 * Adds one private erased adapter for every directly declared non-private instance member of an
 * ordinary Kotlin generic class. The source-named method remains on the typed CLR class. The
 * adapter is attached to the non-generic canonical interface with an explicit MethodImpl during
 * emission. Private members are implementation details reached only through the exact typed
 * receiver; publishing canonical slots for them would broaden both Kotlin and C# metadata
 * surfaces without enabling any legal Kotlin call.
 *
 * A direct owner parameter erases to `Any?`. A nested occurrence keeps its logical classifier
 * only when that classifier has its own Kotlin-erased identity; otherwise the complete value
 * erases to `Any?`. This is the same barrier rule used by split generic interfaces and avoids
 * promising a closed CLR instantiation at an erased Kotlin boundary.
 */
internal class DotNetGenericClassBridgeLowering(private val context: DotNetBackendContext) : ModuleLoweringPass {
    override fun lower(irModule: IrModuleFragment) {
        val genericClasses = mutableListOf<IrClass>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.isDotNetGenericClassDeclaration) genericClasses += declaration
                declaration.acceptChildrenVoid(this)
            }
        })
        val ownedErasedClassifiers = genericClasses.toHashSet()
        val externalDeclarations = DotNetExternalDeclarations(context.configuration.dotNetExternalLibraries)
        for (irClass in genericClasses) {
            val sourceMembers = irClass.declarations.flatMap { declaration ->
                when (declaration) {
                    is IrSimpleFunction -> listOf(declaration)
                    is IrProperty -> listOfNotNull(declaration.getter, declaration.setter)
                    else -> emptyList()
                }
            }.filter { member ->
                // Common default-argument dispatchers are compiler implementation helpers, not
                // source member slots. Their bodies recover an exact typed owner from the call's
                // authoritative IR receiver and cross the ordinary call-result coercion barrier;
                // publishing a second canonical slot here would duplicate the Kotlin contract.
                !member.isFakeOverride &&
                        member.parameters.firstOrNull()?.kind == IrParameterKind.DispatchReceiver &&
                        member.origin != DOTNET_GENERIC_CLASS_CANONICAL_BRIDGE &&
                        member.origin != IrDeclarationOrigin.FUNCTION_FOR_DEFAULT_PARAMETER &&
                        !member.name.asString().endsWith("\$default") &&
                        member.visibility != DescriptorVisibilities.PRIVATE &&
                        member.visibility != DescriptorVisibilities.PRIVATE_TO_THIS
            }
            for (source in sourceMembers) {
                val bridge = createBridge(irClass, source, ownedErasedClassifiers, externalDeclarations)
                context.genericClassBridges += DotNetLoweredGenericClassBridge(irClass, source, bridge)
            }
        }
    }

    private fun createBridge(
        irClass: IrClass,
        source: IrSimpleFunction,
        ownedErasedClassifiers: Set<IrClass>,
        externalDeclarations: DotNetExternalDeclarations,
    ): IrSimpleFunction {
        val ownerSubstitution = irClass.typeParameters.associate { parameter ->
            parameter.symbol to context.irBuiltIns.anyNType
        }
        val ownerSubstitutor = IrTypeSubstitutor(ownerSubstitution, allowEmptySubstitution = true)
        fun canonicalType(type: IrType): IrType {
            if (!type.referencesTypeParameterOf(irClass)) return type
            val simpleType = type as? IrSimpleType ?: return context.irBuiltIns.anyNType
            val directParameter = simpleType.classifier as? IrTypeParameterSymbol
            if (directParameter?.owner?.parent == irClass) return context.irBuiltIns.anyNType
            val classifier = (simpleType.classifier as? IrClassSymbol)?.owner
            return when {
                classifier in ownedErasedClassifiers ||
                        classifier?.let(externalDeclarations::hasGenericClass) == true -> type
                classifier?.isInterface == true -> ownerSubstitutor.substitute(type)
                else -> context.irBuiltIns.anyNType
            }
        }

        val sourceParameters = source.parameters.dropWhile { it.kind == IrParameterKind.DispatchReceiver }
        return irClass.addFunction {
            startOffset = source.startOffset
            endOffset = source.endOffset
            origin = DOTNET_GENERIC_CLASS_CANONICAL_BRIDGE
            name = Name.special("<GenericClassCanonicalBridge-${source.name.asString()}>")
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            returnType = context.irBuiltIns.anyNType
        }.apply bridge@{
            parameters += createDispatchReceiverParameterWithClassParent()
            val bridgeTypeParameters = copyTypeParametersFrom(source)
            bridgeTypeParameters.forEachIndexed { index, parameter ->
                parameter.superTypes = source.typeParameters[index].superTypes
                    .filterNot { bound -> bound.isDotNetOwnerDependentConstraint(irClass) }
                    .map(::canonicalType)
                    .ifEmpty { listOf(context.irBuiltIns.anyNType) }
            }
            val ownerBoundMethodArguments =
                source.dotNetDirectOwnerRelativeMethodBoundsOrNull(irClass)
                    ?: dotNetUnsupported(
                        "generic class member '${source.name.asString()}' requires an owner-relative " +
                                "generic adapter beyond direct method-parameter uses"
                    )
            val methodSubstitution = source.typeParameters.zip(bridgeTypeParameters).associate { pair ->
                pair.first.symbol to pair.second.symbol.defaultType
            }
            val methodSubstitutor = IrTypeSubstitutor(methodSubstitution, allowEmptySubstitution = true)
            fun bridgeType(type: IrType): IrType = methodSubstitutor.substitute(canonicalType(type))

            returnType = bridgeType(source.returnType)
            sourceParameters.forEach { parameter ->
                addValueParameter(parameter.name.asString(), bridgeType(parameter.type))
            }
            body = context.createIrBuilder(symbol).irBlockBody {
                val targetMethodArguments = bridgeTypeParameters.mapIndexed { index, parameter ->
                    val sourceParameter = source.typeParameters[index]
                    val sourceRetainsPhysicalBound =
                        sourceParameter.origin != DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER &&
                                sourceParameter.superTypes.any { bound -> !bound.isNullableAny() }
                    ownerBoundMethodArguments[index]
                        ?.takeIf { sourceRetainsPhysicalBound }
                        ?: parameter.symbol.defaultType
                }
                val hasOwnerBoundMethodArguments = targetMethodArguments.indices.any { index ->
                    targetMethodArguments[index] != bridgeTypeParameters[index].symbol.defaultType
                }
                val targetMethodSubstitution = source.typeParameters.zip(targetMethodArguments).associate { pair ->
                    pair.first.symbol to pair.second
                }
                val targetMethodSubstitutor =
                    IrTypeSubstitutor(targetMethodSubstitution, allowEmptySubstitution = true)
                val targetParameterTypes = sourceParameters.map { parameter ->
                    targetMethodSubstitutor.substitute(parameter.type)
                }
                val targetReturnType = targetMethodSubstitutor.substitute(source.returnType)
                val call = irCall(source.symbol, targetReturnType).apply {
                    arguments[0] = irGet(this@bridge.parameters[0])
                    targetMethodArguments.forEachIndexed { index, argument ->
                        typeArguments[index] = argument
                    }
                    sourceParameters.indices.forEach { index ->
                        val argument = irGet(this@bridge.parameters[index + 1])
                        val targetType = targetParameterTypes[index]
                        arguments[index + 1] = if (argument.type == targetType) {
                            argument
                        } else if (hasOwnerBoundMethodArguments) {
                            irImplicitCast(
                                irImplicitCast(argument, context.irBuiltIns.anyNType),
                                targetType,
                            )
                        } else {
                            irImplicitCast(argument, targetType)
                        }
                    }
                }
                val result = if (call.type == this@bridge.returnType) {
                    call
                } else if (hasOwnerBoundMethodArguments) {
                    irImplicitCast(
                        irImplicitCast(call, context.irBuiltIns.anyNType),
                        this@bridge.returnType,
                    )
                } else {
                    irImplicitCast(call, this@bridge.returnType)
                }
                +irReturn(result)
            }
        }
    }
}
