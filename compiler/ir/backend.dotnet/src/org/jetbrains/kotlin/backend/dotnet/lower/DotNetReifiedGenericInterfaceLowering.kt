/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetBoundGenericOwnerPhysicalSlot
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalDeclarations
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberFamilyRole
import org.jetbrains.kotlin.backend.dotnet.dotNetDirectInterfaceTypes
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerPhysicalMemberName
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerRehearsal
import org.jetbrains.kotlin.backend.dotnet.dotNetIlMethodName
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericInterfaceDeclaration
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

/**
 * Reopens the first structurally complete Kotlin generic-interface family during the atomic
 * generic-owner rehearsal. The natural interface remains the sole `I<T>` CLR/C# owner. A
 * separate non-generic interface carries only Kotlin views which cannot honestly name one CLR
 * construction, and every implementation supplies both views on the same object.
 *
 * Admission is intentionally independent of declaration names and library ownership. The first
 * tranche accepts a public covariant producer with one abstract no-input member returning its
 * owner parameter directly. Transparent covariant subinterfaces in the same product inherit that
 * physical family at a fixpoint. Other families retain the accepted erased production ABI until
 * their complete semantic surface has its own proof.
 */
internal class DotNetReifiedGenericInterfaceLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    override fun lower(irModule: IrModuleFragment) {
        if (!context.configuration.dotNetGenericOwnerRehearsal) return
        val externalDeclarations = context.externalDeclarationsForLowering()

        val genericInterfaces = mutableListOf<IrClass>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.isDotNetGenericInterfaceDeclaration) genericInterfaces += declaration
                declaration.acceptChildrenVoid(this)
            }
        })
        val slotsBySource = linkedMapOf<IrSimpleFunction, IrSimpleFunction>()
        for (owner in genericInterfaces.filter { candidate ->
            candidate.isFirstReifiedProducerCandidate()
        }) {
            val file = checkNotNull(owner.fileOrNull) {
                "Internal .NET backend error: reified generic interface '${owner.name}' has no file"
            }
            val identity = context.preLoweringDeclarationKeys[owner]
                ?: owner.fqNameWhenAvailable?.asString()
                ?: owner.name.asString()
            val suffix = Integer.toUnsignedString(identity.hashCode(), 16)
            val capability = context.irFactory.buildClass {
                startOffset = owner.startOffset
                endOffset = owner.endOffset
                origin = DOTNET_GENERIC_OWNER_CAPABILITY_INTERFACE
                name = Name.identifier("I${owner.name.asString()}KotlinSemantic$suffix")
                kind = ClassKind.INTERFACE
                modality = Modality.ABSTRACT
                visibility = DescriptorVisibilities.PUBLIC
            }.apply {
                parent = file
                superTypes = listOf(context.irBuiltIns.anyType)
                createThisReceiverParameter()
            }
            file.declarations += capability
            owner.superTypes += capability.symbol.defaultType
            context.reifiedGenericInterfaces += owner
            context.genericOwnerCapabilityInterfaces[owner] = capability

            for (source in owner.declaredProducerMembers()) {
                val logicalRoot = context.preLoweringDeclarationKeys[source]
                    ?: "$identity.${source.name.asString()}"
                val physicalName = dotNetGenericOwnerPhysicalMemberName(
                    source.dotNetIlMethodName(),
                    listOf(logicalRoot),
                    DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
                )
                val slot = capability.addFunction {
                    startOffset = source.startOffset
                    endOffset = source.endOffset
                    origin = DOTNET_GENERIC_OWNER_CAPABILITY_SLOT
                    name = Name.identifier(physicalName)
                    visibility = DescriptorVisibilities.PUBLIC
                    modality = Modality.ABSTRACT
                    returnType = context.irBuiltIns.anyNType
                }.apply {
                    parameters += createDispatchReceiverParameterWithClassParent()
                }
                slotsBySource[source] = slot
                context.genericOwnerCapabilitySlots[source] = slot
                context.genericOwnerCapabilityDeclarations += slot
                context.genericOwnerCapabilityDeclarations += slot.parameters
            }
        }

        // The physical choice is closed over transparent interface inheritance. Otherwise an
        // arity-zero Child would have to name the already-reified Parent<T> without owning a CLR
        // T, which is impossible. Reusing the parent's one capability also avoids duplicate
        // semantic slots: Child<T> and Parent<T> remain two natural CLR identities over the same
        // Kotlin declaration-semantic domain.
        var changed: Boolean
        do {
            changed = false
            for (owner in genericInterfaces) {
                if (owner in context.reifiedGenericInterfaces) continue
                val parent = owner.transparentReifiedProducerParentOrNull() ?: continue
                val capability = context.genericOwnerCapabilityInterfaces[parent] ?: continue
                context.reifiedGenericInterfaces += owner
                context.genericOwnerCapabilityInterfaces[owner] = capability
                changed = true
            }
        } while (changed)

        fun IrType.semanticInterfaceOwnerOrNull(): IrClass? {
            val simpleType = this as? IrSimpleType ?: return null
            val owner = (simpleType.classifier as? IrClassSymbol)?.owner
                ?.takeIf { candidate ->
                    candidate in context.reifiedGenericInterfaces ||
                            externalDeclarations.hasReifiedGenericInterface(candidate)
                }
                ?: return null
            val argument = simpleType.arguments.singleOrNull()
            val projection = argument as? IrTypeProjection ?: return owner
            if (projection.variance != Variance.INVARIANT) return owner
            val argumentClassifier = (projection.type as? IrSimpleType)?.classifier
            return when (argumentClassifier) {
                is IrTypeParameterSymbol -> owner
                is IrClassSymbol -> owner.takeUnless { argumentClassifier.owner.modality == Modality.FINAL }
                else -> owner
            }
        }

        fun recordSemanticDeclaration(declaration: IrDeclaration, type: IrType) {
            if (type.semanticInterfaceOwnerOrNull() != null) {
                context.genericOwnerCapabilityDeclarations += declaration
            }
        }

        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: org.jetbrains.kotlin.ir.declarations.IrFunction) {
                if (declaration is IrSimpleFunction) {
                    recordSemanticDeclaration(declaration, declaration.returnType)
                }
                declaration.acceptChildrenVoid(this)
            }

            override fun visitValueParameter(declaration: IrValueParameter) {
                recordSemanticDeclaration(declaration, declaration.type)
                declaration.acceptChildrenVoid(this)
            }

            override fun visitVariable(declaration: IrVariable) {
                recordSemanticDeclaration(declaration, declaration.type)
                declaration.acceptChildrenVoid(this)
            }

            override fun visitField(declaration: IrField) {
                recordSemanticDeclaration(declaration, declaration.type)
                declaration.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                val source = expression.symbol.owner.let { candidate ->
                    candidate.resolveFakeOverride() ?: candidate.resolveFakeOverrideMaybeAbstract() ?: candidate
                }
                val sourceOwner = source.parent as? IrClass
                val slot = slotsBySource[source] ?: sourceOwner
                    ?.takeIf(externalDeclarations::hasReifiedGenericInterface)
                    ?.let {
                        materializeExternalReifiedGenericInterfaceCapabilitySlot(
                            context,
                            externalDeclarations,
                            source,
                        )
                    }
                if (slot != null && expression.dispatchReceiver?.type?.semanticInterfaceOwnerOrNull() != null) {
                    context.genericOwnerCapabilityCallTargets[expression] = slot
                }
                expression.acceptChildrenVoid(this)
            }
        })
    }

    private fun IrClass.declaredProducerMembers(): List<IrSimpleFunction> =
        declarations.filterIsInstance<IrSimpleFunction>().filterNot(IrSimpleFunction::isFakeOverride)

    private fun IrClass.transparentReifiedProducerParentOrNull(): IrClass? {
        if (!hasFirstReifiedProducerOwnerShape()) return null
        if (declarations.any { declaration -> declaration is IrProperty } ||
            declaredProducerMembers().isNotEmpty()
        ) {
            return null
        }
        val parameter = typeParameters.single()
        val parentType = dotNetDirectInterfaceTypes().singleOrNull() ?: return null
        val parent = (parentType.classifier as? IrClassSymbol)?.owner ?: return null
        val argument = parentType.arguments.singleOrNull() as? IrTypeProjection ?: return null
        val argumentParameter = (argument.type as? IrSimpleType)?.classifier as? IrTypeParameterSymbol
        return parent.takeIf {
            argument.variance == Variance.INVARIANT && argumentParameter?.owner === parameter
        }
    }

    private fun IrClass.isFirstReifiedProducerCandidate(): Boolean {
        if (!hasFirstReifiedProducerOwnerShape()) return false
        if (parent !is IrFile || dotNetDirectInterfaceTypes().isNotEmpty()) return false
        if (declarations.any { declaration -> declaration is IrProperty }) return false
        val parameter = typeParameters.single()
        val members = declaredProducerMembers()
        val member = members.singleOrNull() ?: return false
        if (member.visibility != DescriptorVisibilities.PUBLIC || member.modality != Modality.ABSTRACT ||
            member.body != null || member.typeParameters.isNotEmpty() ||
            member.parameters.singleOrNull()?.kind != IrParameterKind.DispatchReceiver
        ) {
            return false
        }
        val resultParameter = (member.returnType as? IrSimpleType)?.classifier as? IrTypeParameterSymbol
        return resultParameter?.owner === parameter
    }

    private fun IrClass.hasFirstReifiedProducerOwnerShape(): Boolean {
        if (!isDotNetGenericInterfaceDeclaration || visibility != DescriptorVisibilities.PUBLIC ||
            parent !is IrFile
        ) {
            return false
        }
        val parameter = typeParameters.singleOrNull()
            ?.takeIf { it.variance == Variance.OUT_VARIANCE }
            ?: return false
        return parameter.superTypes.all(IrType::isNullableAny)
    }
}

/** Creates one consumer-side IR symbol while retaining the producer's recorded CLR owner/name. */
internal fun materializeExternalReifiedGenericInterfaceCapabilitySlot(
    context: DotNetBackendContext,
    externalDeclarations: DotNetExternalDeclarations,
    source: IrSimpleFunction,
): IrSimpleFunction = context.externalReifiedGenericInterfaceCapabilitySlots.getOrPut(source) {
    val owner = source.parent as? IrClass
        ?: error("Internal .NET backend error: external reified-interface member has no owner")
    require(source.typeParameters.isEmpty() &&
            source.parameters.singleOrNull()?.kind == IrParameterKind.DispatchReceiver) {
        "External reified-interface member '${source.name}' is outside the admitted producer family"
    }
    val binding = externalDeclarations.genericOwnerMemberFamilyOrNull(source)
        ?: error("External reified-interface member '${source.name}' has no producer semantic family")
    context.irFactory.buildFun {
        startOffset = source.startOffset
        endOffset = source.endOffset
        origin = DOTNET_GENERIC_OWNER_CAPABILITY_SLOT
        name = Name.special("<ExternalReifiedGenericInterfaceCapability-${source.name.asString()}>")
        visibility = DescriptorVisibilities.PRIVATE
        modality = Modality.ABSTRACT
        returnType = context.irBuiltIns.anyNType
    }.apply {
        parent = owner
        parameters += createDispatchReceiverParameterWithClassParent()
        context.genericOwnerCapabilityDeclarations += this
        context.genericOwnerCapabilityDeclarations += parameters
        context.externalGenericOwnerPhysicalSlots[this] = DotNetBoundGenericOwnerPhysicalSlot(
            binding.library,
            binding.family,
            binding.family.ownerPath,
            binding.family.capabilityMethodName,
        )
    }
}
