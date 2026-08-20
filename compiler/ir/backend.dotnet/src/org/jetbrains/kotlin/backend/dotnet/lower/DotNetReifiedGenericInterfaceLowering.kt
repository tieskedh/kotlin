/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.lower.VariableRemapper
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetBoundGenericOwnerFunctionInputEntry
import org.jetbrains.kotlin.backend.dotnet.DotNetBoundGenericOwnerPhysicalSlot
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalDeclarations
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerFunctionCarrierKind
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberFamilyRole
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryAbiCodec
import org.jetbrains.kotlin.backend.dotnet.DotNetPublishedGenericInterfaceCapabilityBindingKind
import org.jetbrains.kotlin.backend.dotnet.DotNetPublishedGenericInterfaceFamilyContract
import org.jetbrains.kotlin.backend.dotnet.DotNetPublishedGenericInterfaceFamilyKind
import org.jetbrains.kotlin.backend.dotnet.DotNetPublishedGenericInterfaceMemberContract
import org.jetbrains.kotlin.backend.dotnet.DotNetPublishedGenericInterfaceMemberRole
import org.jetbrains.kotlin.backend.dotnet.DotNetPublishedGenericInterfaceParentContract
import org.jetbrains.kotlin.backend.dotnet.dotNetDirectInterfaceTypes
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericArgumentHasProperClrValueSubtype
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericInterfaceCanonicalSlotId
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerPhysicalMemberName
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerRehearsal
import org.jetbrains.kotlin.backend.dotnet.dotNetIlMethodName
import org.jetbrains.kotlin.backend.dotnet.dotNetUnboxedValueClassTypeOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetValueClassOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetValueClassConstructorImplementationSourceOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetValueClassImplementationSourceOrNull
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericClassDeclaration
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericInterfaceDeclaration
import org.jetbrains.kotlin.backend.dotnet.isReifiedByGenericOwnerRehearsal
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getInlineClassBackingField
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

internal val DOTNET_GENERIC_OWNER_FUNCTION_INPUT_ENTRY: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_OWNER_FUNCTION_INPUT_ENTRY")

/**
 * Reopens the first structurally complete Kotlin generic-interface family during the atomic
 * generic-owner rehearsal. The natural interface remains the sole `I<T>` CLR/C# owner. A
 * separate non-generic interface carries only Kotlin views which cannot honestly name one CLR
 * construction, and every implementation supplies both views on the same object.
 *
 * Admission is intentionally independent of declaration names and library ownership. The first
 * tranche accepts a public covariant or invariant producer with one abstract no-input member
 * returning its owner parameter directly, a public contravariant consumer with one abstract
 * owner-parameter input and `Unit` result, or an invariant cell containing exactly one of each.
 * An invariant owner has no legal declaration-site sibling widening: exact and open
 * constructions stay on natural `I<T>`, while star/use-site-projected operations use the
 * semantic boundary. Covariant producer subinterfaces and exact invariant-property children
 * inherit their family at a fixpoint, including across a library boundary. An invariant child
 * may add either one matching property or one direct consumer to an exact property root. One
 * further direct-consumer edge is admitted above that exact consumer child, including when both
 * edges come from separate producer assemblies. External admission requires their versioned
 * physical owner and member-family records. A child owns only its new semantic slots; inherited
 * slots remain inherited. An intersection without new members receives one memberless capability
 * alias over its independent roots. Other families retain the accepted erased production ABI
 * until their complete semantic surface has its own proof.
 */
internal class DotNetReifiedGenericInterfaceLowering(
    private val context: DotNetBackendContext,
    private val finalRoutingOnly: Boolean = false,
) : ModuleLoweringPass {
    private val externalDeclarations = context.externalDeclarationsForLowering()

    private data class ReifiedInterfaceChildShape(
        val parents: List<IrClass>,
        val declaredMembers: List<IrSimpleFunction>,
    )

    private fun IrClass.requiredPublishedLogicalKey(role: String): String =
        context.preLoweringDeclarationKeys[this]
            ?: fqNameWhenAvailable?.asString()
            ?: error("Internal .NET backend error: published generic-interface $role has no logical identity")

    private fun IrSimpleFunction.requiredPublishedLogicalKey(role: String): String =
        context.preLoweringDeclarationKeys[this]
            ?: (parent as? IrClass)?.let { owner ->
                "${owner.requiredPublishedLogicalKey("member owner")}#${dotNetGenericInterfaceCanonicalSlotId()}"
            }
            ?: error("Internal .NET backend error: published generic-interface $role has no logical identity")

    private fun IrClass.publishedMemberContractsOrNull(
        members: List<IrSimpleFunction>,
    ): List<DotNetPublishedGenericInterfaceMemberContract>? {
        val parameter = typeParameters.singleOrNull() ?: return null
        return buildList {
            for (member in members) {
                val property = member.correspondingPropertySymbol?.owner
                val role = when {
                    property?.getter === member && member.isDirectProducerMember(parameter) ->
                        DotNetPublishedGenericInterfaceMemberRole.PROPERTY_GETTER
                    property?.setter === member && member.isDirectConsumerMember(parameter) ->
                        DotNetPublishedGenericInterfaceMemberRole.PROPERTY_SETTER
                    member.isDirectProducerMember(parameter) -> DotNetPublishedGenericInterfaceMemberRole.PRODUCER
                    member.isDirectConsumerMember(parameter) -> DotNetPublishedGenericInterfaceMemberRole.CONSUMER
                    else -> return null
                }
                val logicalMemberKey = context.preLoweringDeclarationKeys[member]
                    ?: externalDeclarations.genericOwnerMemberFamilyOrNull(member)?.family?.logicalMemberKey
                    ?: member.takeIf { candidate -> candidate.fileOrNull != null }
                        ?.requiredPublishedLogicalKey("member")
                    ?: return null
                add(DotNetPublishedGenericInterfaceMemberContract(
                    logicalMemberKey,
                    role,
                ))
            }
        }.sortedBy { member -> member.logicalMemberKey }
    }

    private fun rawPublishedFamilyOrNull(owner: IrClass): DotNetPublishedGenericInterfaceFamilyContract? =
        context.publishedGenericInterfaceFamilies[owner]
            ?: externalDeclarations.publishedGenericInterfaceFamilyOrNull(owner)

    private fun publishedFamilyOrNull(
        owner: IrClass,
        visiting: Set<IrClass> = emptySet(),
    ): DotNetPublishedGenericInterfaceFamilyContract? {
        if (owner in visiting || !owner.hasLogicalReifiedInterfaceOwnerShape()) return null
        val contract = rawPublishedFamilyOrNull(owner) ?: return null
        if (contract.genericArity != owner.typeParameters.size) return null
        val declaredMembers = owner.publishedMemberContractsOrNull(owner.declaredInterfaceMembers())
            ?: return null
        if (contract.declaredMembers != declaredMembers) return null
        val parentContracts = mutableListOf<DotNetPublishedGenericInterfaceFamilyContract>()
        val expectedParents = owner.dotNetDirectInterfaceTypes().map { parentType ->
            val parent = (parentType.classifier as? IrClassSymbol)?.owner ?: return null
            val mapping = parentType.arguments.map { argument ->
                val projection = argument as? IrTypeProjection ?: return null
                val parameter = (projection.type as? IrSimpleType)?.classifier as? IrTypeParameterSymbol
                    ?: return null
                if (projection.variance != Variance.INVARIANT || parameter.owner.parent !== owner) return null
                owner.typeParameters.indexOf(parameter.owner).takeIf { index -> index >= 0 } ?: return null
            }
            if (mapping != mapping.indices.toList()) return null
            val parentContract = publishedFamilyOrNull(parent, visiting + owner) ?: return null
            parentContracts += parentContract
            DotNetPublishedGenericInterfaceParentContract(parentContract.logicalOwnerKey, mapping)
        }.sortedBy { parent -> parent.logicalOwnerKey }
        if (contract.directParents != expectedParents) return null
        val expectedRoots = if (parentContracts.isEmpty()) {
            listOf(contract.logicalOwnerKey)
        } else {
            parentContracts.flatMap { parent -> parent.rootLogicalOwnerKeys }.distinct().sorted()
        }
        val expectedDepth = parentContracts.maxOfOrNull { parent -> parent.lineageDepth + 1 } ?: 0
        val expectedKind = when {
            parentContracts.isEmpty() -> DotNetPublishedGenericInterfaceFamilyKind.ROOT
            expectedRoots.size == 1 -> DotNetPublishedGenericInterfaceFamilyKind.DERIVED
            else -> DotNetPublishedGenericInterfaceFamilyKind.INTERSECTION
        }
        return contract.takeIf {
            it.rootLogicalOwnerKeys == expectedRoots &&
                    it.lineageDepth == expectedDepth &&
                    it.kind == expectedKind
        }
    }

    private fun publishFamily(
        owner: IrClass,
        parents: List<IrClass>,
        declaredMembers: List<IrSimpleFunction>,
        capabilityBindingKind: DotNetPublishedGenericInterfaceCapabilityBindingKind,
        reusedParent: IrClass? = null,
    ) {
        val logicalOwnerKey = owner.requiredPublishedLogicalKey("owner")
        val parentContracts = parents.map { parent ->
            publishedFamilyOrNull(parent)
                ?: error(
                    "Internal .NET backend error: published generic-interface parent " +
                            "'${parent.name}' has no family contract"
                )
        }
        val roots = if (parentContracts.isEmpty()) {
            listOf(logicalOwnerKey)
        } else {
            parentContracts.flatMap { contract -> contract.rootLogicalOwnerKeys }.distinct().sorted()
        }
        val contract = DotNetPublishedGenericInterfaceFamilyContract(
            logicalOwnerKey = logicalOwnerKey,
            genericArity = owner.typeParameters.size,
            kind = when {
                parentContracts.isEmpty() -> DotNetPublishedGenericInterfaceFamilyKind.ROOT
                roots.size == 1 -> DotNetPublishedGenericInterfaceFamilyKind.DERIVED
                else -> DotNetPublishedGenericInterfaceFamilyKind.INTERSECTION
            },
            rootLogicalOwnerKeys = roots,
            directParents = parentContracts.map { parent ->
                DotNetPublishedGenericInterfaceParentContract(
                    parent.logicalOwnerKey,
                    (0 until parent.genericArity).toList(),
                )
            }.sortedBy { parent -> parent.logicalOwnerKey },
            lineageDepth = parentContracts.maxOfOrNull { contract -> contract.lineageDepth + 1 } ?: 0,
            declaredMembers = checkNotNull(owner.publishedMemberContractsOrNull(declaredMembers)) {
                "Internal .NET backend error: published generic-interface member roles changed"
            },
            capabilityBindingKind = capabilityBindingKind,
            reusedParentLogicalOwnerKey = reusedParent?.let { parent ->
                checkNotNull(publishedFamilyOrNull(parent)) {
                    "Internal .NET backend error: reused capability parent has no published contract"
                }.logicalOwnerKey
            },
        )
        check(context.publishedGenericInterfaceFamilies.put(owner, contract) == null) {
            "Internal .NET backend error: '${owner.name}' published multiple generic-interface contracts"
        }
    }

    override fun lower(irModule: IrModuleFragment) {
        if (!context.configuration.dotNetGenericOwnerRehearsal) return

        val genericInterfaces = mutableListOf<IrClass>()
        val sourceFunctions = mutableListOf<IrSimpleFunction>()
        val localClasses = linkedSetOf<IrClass>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                localClasses += declaration
                if (declaration.isDotNetGenericInterfaceDeclaration) genericInterfaces += declaration
                declaration.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: org.jetbrains.kotlin.ir.declarations.IrFunction) {
                if (declaration is IrSimpleFunction) sourceFunctions += declaration
                declaration.acceptChildrenVoid(this)
            }
        })
        val slotsBySource = linkedMapOf<IrSimpleFunction, IrSimpleFunction>().apply {
            if (finalRoutingOnly) {
                putAll(context.genericOwnerCapabilitySlots)
                putAll(context.externalGenericOwnerCapabilitySlots)
            }
        }
        if (!finalRoutingOnly) {
            for (owner in genericInterfaces.filter { candidate ->
                candidate.isFirstReifiedInterfaceCandidate()
            }) {
                val file = checkNotNull(owner.fileOrNull) {
                    "Internal .NET backend error: reified generic interface '${owner.name}' has no file"
                }
                val identity = context.preLoweringDeclarationKeys[owner]
                    ?: owner.fqNameWhenAvailable?.asString()
                    ?: owner.name.asString()
                val suffix = Integer.toUnsignedString(identity.hashCode(), 16)
                val capability = buildSemanticCapability(owner, file, suffix, emptyList())
                file.declarations += capability
                context.reifiedGenericInterfaces += owner
                context.genericOwnerCapabilityInterfaces[owner] = capability
                materializeDeclaredSemanticSlots(
                    capability,
                    identity,
                    owner.declaredInterfaceMembers(),
                    slotsBySource,
                )
                publishFamily(
                    owner,
                    parents = emptyList(),
                    declaredMembers = owner.declaredInterfaceMembers(),
                    capabilityBindingKind = DotNetPublishedGenericInterfaceCapabilityBindingKind.OWNED,
                )
            }

            // The physical choice is closed over interface inheritance. Otherwise an arity-zero Child
            // would have to name an already-reified Parent<T> without owning a CLR T, which is
            // impossible. A memberless child in one semantic domain reuses that capability. A child
            // which adds an admitted producer, invariant property, or direct invariant consumer, or
            // joins independent domains, receives one child capability inheriting the parent
            // capabilities. It owns only its declared slots: no inherited slot, implementation, or
            // state is copied.
            var changed: Boolean
            do {
                changed = false
                for (owner in genericInterfaces) {
                    if (owner in context.reifiedGenericInterfaces) continue
                    val shape = owner.reifiedInterfaceChildShapeOrNull() ?: continue
                    if (shape.parents.any { parent -> publishedFamilyOrNull(parent) == null }) continue
                    if (shape.parents.any { parent ->
                            parent !in context.genericOwnerCapabilityInterfaces &&
                                    parent !in context.externalReifiedGenericInterfaceCapabilityProviders &&
                                    !externalDeclarations.hasReifiedGenericInterface(parent)
                        }
                    ) {
                        continue
                    }
                    val localCapabilities = shape.parents.mapNotNull(context.genericOwnerCapabilityInterfaces::get)
                        .distinct()
                    val externalProviders = shape.parents.mapNotNull { parent ->
                        context.externalReifiedGenericInterfaceCapabilityProviders[parent]
                            ?: parent.takeIf(externalDeclarations::hasReifiedGenericInterface)
                    }.distinctBy { provider ->
                        val capability = checkNotNull(
                            externalDeclarations.genericOwnerCapabilityInfoOrNull(provider)
                        )
                        capability.assemblyName to capability.physicalPathComponents()
                    }
                    if (shape.declaredMembers.isEmpty() &&
                        localCapabilities.size == 1 && externalProviders.isEmpty()
                    ) {
                        context.reifiedGenericInterfaces += owner
                        context.genericOwnerCapabilityInterfaces[owner] = localCapabilities.single()
                        val reusedParent = shape.parents.first { parent ->
                            context.genericOwnerCapabilityInterfaces[parent] == localCapabilities.single()
                        }
                        publishFamily(
                            owner,
                            shape.parents,
                            shape.declaredMembers,
                            DotNetPublishedGenericInterfaceCapabilityBindingKind.REUSED_PARENT,
                            reusedParent,
                        )
                        changed = true
                        continue
                    }
                    if (shape.declaredMembers.isEmpty() &&
                        externalProviders.size == 1 && localCapabilities.isEmpty()
                    ) {
                        context.reifiedGenericInterfaces += owner
                        context.externalReifiedGenericInterfaceCapabilityProviders[owner] = externalProviders.single()
                        val reusedProvider = externalProviders.single()
                        val reusedParent = shape.parents.first { parent ->
                            context.externalReifiedGenericInterfaceCapabilityProviders[parent] == reusedProvider ||
                                    parent == reusedProvider
                        }
                        publishFamily(
                            owner,
                            shape.parents,
                            shape.declaredMembers,
                            DotNetPublishedGenericInterfaceCapabilityBindingKind.REUSED_PARENT,
                            reusedParent,
                        )
                        changed = true
                        continue
                    }
                    val file = checkNotNull(owner.fileOrNull) {
                        "Internal .NET backend error: reified generic interface '${owner.name}' has no file"
                    }
                    val identity = context.preLoweringDeclarationKeys[owner]
                        ?: owner.fqNameWhenAvailable?.asString()
                        ?: owner.name.asString()
                    val suffix = Integer.toUnsignedString(identity.hashCode(), 16)
                    val capability = buildSemanticCapability(owner, file, suffix, localCapabilities)
                    file.declarations += capability
                    context.reifiedGenericInterfaces += owner
                    context.genericOwnerCapabilityInterfaces[owner] = capability
                    if (externalProviders.isNotEmpty()) {
                        context.externalGenericOwnerCapabilitySupertypeProviders[capability] = externalProviders
                    }
                    materializeDeclaredSemanticSlots(
                        capability,
                        identity,
                        shape.declaredMembers,
                        slotsBySource,
                    )
                    publishFamily(
                        owner,
                        shape.parents,
                        shape.declaredMembers,
                        DotNetPublishedGenericInterfaceCapabilityBindingKind.OWNED,
                    )
                    changed = true
                }
            } while (changed)

            closeGenericClassCapabilityInterfaceSupertypes()
        }

        fun IrType.hasClrValueGenericArgumentCarrier(): Boolean {
            if (isPrimitiveType() || isPrimitiveType(nullable = true)) return true
            val valueClassCarrier = dotNetUnboxedValueClassTypeOrNull() ?: return false
            return valueClassCarrier.isPrimitiveType() ||
                    valueClassCarrier.isPrimitiveType(nullable = true)
        }

        val hasProperClrValueSubtype =
            dotNetGenericArgumentHasProperClrValueSubtype(context.irBuiltIns)

        fun IrType.reifiedInterfaceOwnerOrNull(): IrClass? =
            ((this as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                ?.takeIf { candidate ->
                    candidate in context.reifiedGenericInterfaces ||
                            externalDeclarations.hasReifiedGenericInterface(candidate)
                }

        fun IrType.potentialSemanticInterfaceOwnerOrNull(): IrClass? {
            val simpleType = this as? IrSimpleType ?: return null
            val owner = reifiedInterfaceOwnerOrNull() ?: return null
            val argument = simpleType.arguments.singleOrNull()
            val projection = argument as? IrTypeProjection ?: return owner
            if (projection.variance != Variance.INVARIANT) return owner
            val argumentClassifier = (projection.type as? IrSimpleType)?.classifier
            return when (owner.typeParameters.single().variance) {
                Variance.OUT_VARIANCE -> when {
                    argumentClassifier is IrTypeParameterSymbol -> owner
                    projection.type.hasClrValueGenericArgumentCarrier() -> owner
                    hasProperClrValueSubtype(projection.type) -> owner
                    else -> null
                }
                Variance.IN_VARIANCE -> when {
                    argumentClassifier is IrTypeParameterSymbol -> owner
                    projection.type.hasClrValueGenericArgumentCarrier() -> owner
                    else -> null
                }
                Variance.INVARIANT -> null
            }
        }

        fun IrType.hasOpenReifiedInterfaceArgument(): Boolean {
            val simpleType = this as? IrSimpleType ?: return false
            reifiedInterfaceOwnerOrNull() ?: return false
            val projection = simpleType.arguments.singleOrNull() as? IrTypeProjection ?: return false
            return projection.variance == Variance.INVARIANT &&
                    (projection.type as? IrSimpleType)?.classifier is IrTypeParameterSymbol
        }

        fun IrType.hasStarReifiedInterfaceArgument(): Boolean {
            val simpleType = this as? IrSimpleType ?: return false
            reifiedInterfaceOwnerOrNull() ?: return false
            return simpleType.arguments.singleOrNull() !is IrTypeProjection
        }

        fun IrType.hasUseSiteProjectedReifiedInterfaceArgument(): Boolean {
            val simpleType = this as? IrSimpleType ?: return false
            reifiedInterfaceOwnerOrNull() ?: return false
            val projection = simpleType.arguments.singleOrNull() as? IrTypeProjection ?: return false
            return projection.variance != Variance.INVARIANT
        }

        fun IrType.reifiedCovariantInterfaceOwnerOrNull(): IrClass? {
            val owner = reifiedInterfaceOwnerOrNull() ?: return null
            return owner.takeIf {
                it.typeParameters.singleOrNull()?.variance == Variance.OUT_VARIANCE
            }
        }

        fun IrCall.checkNotNullArgumentOrNull(): IrExpression? =
            takeIf { call -> call.symbol == context.irBuiltIns.checkNotNullSymbol }
                ?.arguments
                ?.filterNotNull()
                ?.singleOrNull()

        fun IrExpression.classifierErasedInterfaceOwnerOrNull(): IrClass? = when (this) {
            is IrCall -> checkNotNullArgumentOrNull()?.classifierErasedInterfaceOwnerOrNull()
                ?: type.reifiedCovariantInterfaceOwnerOrNull().takeIf {
                    symbol.owner in context.genericOwnerForeignDispatchDeclarations ||
                            externalDeclarations.genericOwnerFunctionCarrierOrNull(symbol.owner)
                                ?.carrier?.returnCarrier == DotNetGenericOwnerFunctionCarrierKind.OBJECT
                }
            is IrTypeOperatorCall -> when (operator) {
                IrTypeOperator.CAST,
                IrTypeOperator.SAFE_CAST,
                    -> typeOperand.reifiedCovariantInterfaceOwnerOrNull()
                IrTypeOperator.IMPLICIT_CAST,
                IrTypeOperator.IMPLICIT_NOTNULL,
                    -> argument.classifierErasedInterfaceOwnerOrNull()
                else -> null
            }
            else -> null
        }

        fun IrType.sameInvariantTypeAs(other: IrType): Boolean {
            val left = this as? IrSimpleType ?: return false
            val right = other as? IrSimpleType ?: return false
            if (left.classifier != right.classifier || left.nullability != right.nullability ||
                left.arguments.size != right.arguments.size
            ) {
                return false
            }
            return left.arguments.indices.all { index ->
                val leftProjection = left.arguments[index] as? IrTypeProjection ?: return@all false
                val rightProjection = right.arguments[index] as? IrTypeProjection ?: return@all false
                leftProjection.variance == Variance.INVARIANT &&
                        rightProjection.variance == Variance.INVARIANT &&
                        leftProjection.type.sameInvariantTypeAs(rightProjection.type)
            }
        }

        fun IrType.hasExactPhysicalInterfaceView(expected: IrType): Boolean {
            val pending = ArrayDeque<IrType>()
            val visited = hashSetOf<IrType>()
            pending += this
            while (pending.isNotEmpty()) {
                val candidate = pending.removeFirst()
                if (!visited.add(candidate)) continue
                if (candidate.sameInvariantTypeAs(expected)) return true
                val simple = candidate as? IrSimpleType ?: continue
                val classifier = (simple.classifier as? IrClassSymbol)?.owner ?: continue
                if (simple.arguments.size != classifier.typeParameters.size) continue
                val substitutions = classifier.typeParameters.zip(simple.arguments).mapNotNull { pair ->
                    val argument = pair.second as? IrTypeProjection ?: return@mapNotNull null
                    pair.first.symbol to argument.type
                }
                if (substitutions.size != classifier.typeParameters.size) continue
                val isErasedKotlinOwner = when {
                    classifier.isInterface ->
                        classifier.isDotNetGenericInterfaceDeclaration &&
                                classifier !in context.reifiedGenericInterfaces &&
                                !externalDeclarations.hasReifiedGenericInterface(classifier)
                    classifier.isDotNetGenericClassDeclaration ->
                        context.genericOwnerArchitecturePlans[classifier]
                            ?.isReifiedByGenericOwnerRehearsal == false ||
                                externalDeclarations.hasGenericClass(classifier)
                    else -> false
                }
                val physicalSubstitutions = if (isErasedKotlinOwner) {
                    classifier.typeParameters.associate { parameter ->
                        parameter.symbol to context.irBuiltIns.anyNType
                    }
                } else {
                    substitutions.toMap()
                }
                val substitutor = IrTypeSubstitutor(
                    physicalSubstitutions,
                    allowEmptySubstitution = true,
                )
                classifier.superTypes.mapTo(pending, substitutor::substitute)
            }
            return false
        }

        fun IrSimpleFunction.externalReturnCarrierOrNull(): DotNetGenericOwnerFunctionCarrierKind? =
            externalDeclarations.genericOwnerFunctionCarrierOrNull(this)?.carrier?.returnCarrier

        fun IrType.genericOwnerValueClassCarrierDeclarationOrNull(): IrField? {
            dotNetUnboxedValueClassTypeOrNull() ?: return null
            val valueClass = dotNetValueClassOrNull() ?: return null
            return getInlineClassBackingField(valueClass)
        }

        fun IrSimpleFunction.hasValueClassCarrierSourceIn(
            declarations: Set<IrDeclaration>,
        ): Boolean {
            val source = dotNetValueClassImplementationSourceOrNull() ?: return false
            if (source in declarations) return true
            if (source.correspondingPropertySymbol?.owner?.backingField?.let { field ->
                    field in declarations
                } == true
            ) {
                return true
            }
            val backingField = getInlineClassBackingField(parent as IrClass)
            val returnsBackingClassifier =
                (returnType as? IrSimpleType)?.classifier == (backingField.type as? IrSimpleType)?.classifier
            return returnsBackingClassifier && backingField in declarations
        }

        fun IrExpression.readsSemanticInterfaceDeclaration(): Boolean = when (this) {
            is IrGetValue -> symbol.owner in context.genericOwnerCapabilityDeclarations
            is IrGetField -> symbol.owner in context.genericOwnerCapabilityDeclarations
            is IrCall -> checkNotNullArgumentOrNull()?.readsSemanticInterfaceDeclaration()
                ?: when {
                    symbol.owner.externalReturnCarrierOrNull() != null -> true
                    (symbol.owner.parent as? IrClass)?.isDotNetGenericClassDeclaration == true ->
                        this in context.genericOwnerCapabilityCallTargets
                    else -> symbol.owner in context.genericOwnerCapabilityDeclarations ||
                            symbol.owner.hasValueClassCarrierSourceIn(
                                context.genericOwnerCapabilityDeclarations,
                            )
                }
            is IrTypeOperatorCall -> when (operator) {
                IrTypeOperator.IMPLICIT_CAST,
                IrTypeOperator.IMPLICIT_NOTNULL,
                    -> argument.readsSemanticInterfaceDeclaration()
                IrTypeOperator.REINTERPRET_CAST ->
                    argument.readsSemanticInterfaceDeclaration() ||
                            argument.type.genericOwnerValueClassCarrierDeclarationOrNull()?.let { field ->
                                field in context.genericOwnerCapabilityDeclarations
                            } == true
                else -> false
            }
            else -> false
        }

        fun IrExpression.readsForeignDispatchDeclaration(): Boolean = when (this) {
            is IrGetValue -> symbol.owner in context.genericOwnerForeignDispatchDeclarations
            is IrGetField -> symbol.owner in context.genericOwnerForeignDispatchDeclarations
            is IrCall -> checkNotNullArgumentOrNull()?.readsForeignDispatchDeclaration()
                ?: (symbol.owner in context.genericOwnerForeignDispatchDeclarations ||
                        symbol.owner.externalReturnCarrierOrNull() ==
                        DotNetGenericOwnerFunctionCarrierKind.OBJECT)
            is IrTypeOperatorCall ->
                argument.readsForeignDispatchDeclaration() ||
                        (operator == IrTypeOperator.REINTERPRET_CAST &&
                                argument.type.genericOwnerValueClassCarrierDeclarationOrNull()?.let { field ->
                                    field in context.genericOwnerForeignDispatchDeclarations
                                } == true) ||
                        ((operator == IrTypeOperator.CAST ||
                                operator == IrTypeOperator.SAFE_CAST ||
                                operator == IrTypeOperator.IMPLICIT_CAST) &&
                                type.reifiedCovariantInterfaceOwnerOrNull() != null)
            else -> false
        }

        // An interface-typed alias is exact only when its producer was already proven exact.
        // Merely matching the alias's static type would make visit order observable: a forward
        // reference to a later semantic field could otherwise be stored as Consumer<int>. A
        // concrete class is independent evidence because the invariant InterfaceImpl edge is
        // fixed in its physical ancestry (after erased owners above have been normalized).
        val exactInterfaceDeclarationTypes = context.genericOwnerExactInterfaceDeclarationTypes
        val capabilityBearingExactInterfaceDeclarations = context.genericOwnerCapabilityBearingDeclarations

        fun IrExpression.provesExactPhysicalInterfaceView(expected: IrType): Boolean {
            if (readsSemanticInterfaceDeclaration()) return false
            when (this) {
                is IrGetValue -> exactInterfaceDeclarationTypes[symbol.owner]?.let { exactType ->
                    return exactType.hasExactPhysicalInterfaceView(expected)
                }
                is IrGetField -> exactInterfaceDeclarationTypes[symbol.owner]?.let { exactType ->
                    return exactType.hasExactPhysicalInterfaceView(expected)
                }
                is IrTypeOperatorCall -> if (operator == IrTypeOperator.IMPLICIT_CAST) {
                    return argument.provesExactPhysicalInterfaceView(expected)
                }
            }
            val producer = ((type as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                ?: return false
            return !producer.isInterface && type.hasExactPhysicalInterfaceView(expected)
        }

        fun IrExpression.provesCapabilityBearingImplementation(): Boolean {
            if (readsSemanticInterfaceDeclaration()) return true
            when (this) {
                is IrGetValue ->
                    if (symbol.owner in capabilityBearingExactInterfaceDeclarations) return true
                is IrGetField ->
                    if (symbol.owner in capabilityBearingExactInterfaceDeclarations) return true
                is IrTypeOperatorCall -> if (operator == IrTypeOperator.IMPLICIT_CAST) {
                    return argument.provesCapabilityBearingImplementation()
                }
            }
            val producerType = when (this) {
                is IrGetValue -> symbol.owner.type
                is IrGetField -> symbol.owner.type
                else -> type
            }
            val producer = ((producerType as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                ?: return false
            // A class emitted in this module receives the semantic InterfaceImpl from this
            // lowering. An arbitrary imported CLR class may implement only the natural I<T>, so
            // its exact construction is deliberately not evidence for the sibling capability.
            return !producer.isInterface && producer in localClasses
        }

        fun recordSemanticDeclaration(
            declaration: IrDeclaration,
            type: IrType,
            exactProducer: IrExpression? = null,
        ) {
            type.genericOwnerValueClassCarrierDeclarationOrNull()?.let { backingField ->
                if (backingField in context.genericOwnerCapabilityDeclarations) {
                    context.genericOwnerCapabilityDeclarations += declaration
                }
                if (backingField in context.genericOwnerForeignDispatchDeclarations) {
                    context.genericOwnerForeignDispatchDeclarations += declaration
                }
            }
            if (declaration in exactInterfaceDeclarationTypes) return
            val classifierErasedOwner = exactProducer?.classifierErasedInterfaceOwnerOrNull()
            val declaredOwner = type.reifiedInterfaceOwnerOrNull()
            if (classifierErasedOwner == null &&
                declaredOwner != null &&
                exactProducer?.provesExactPhysicalInterfaceView(type) == true
            ) {
                exactInterfaceDeclarationTypes[declaration] = type
                if (exactProducer.provesCapabilityBearingImplementation()) {
                    capabilityBearingExactInterfaceDeclarations += declaration
                    context.genericOwnerCapabilityBearingDeclarations += declaration
                }
            } else {
                val semanticOwner = classifierErasedOwner
                    ?: type.potentialSemanticInterfaceOwnerOrNull()
                    ?: return
                context.genericOwnerCapabilityDeclarations += declaration
                if (semanticOwner.typeParameters.single().variance == Variance.OUT_VARIANCE ||
                    type.hasOpenReifiedInterfaceArgument() || type.hasStarReifiedInterfaceArgument() ||
                    (semanticOwner.typeParameters.single().variance == Variance.INVARIANT &&
                            type.hasUseSiteProjectedReifiedInterfaceArgument())
                ) {
                    // An open, star, or admitted invariant projected I<T> occurrence cannot
                    // promise one natural construction. Object admits both the Kotlin capability
                    // and an ordinary natural CLR implementation. Star/projected input is not
                    // callable for this no-input family; its output member uses the capability-
                    // or-natural foreign dispatcher at the operation.
                    context.genericOwnerForeignDispatchDeclarations += declaration
                }
            }
        }

        // Publish an object result only from one authoritative producer expression. A logical
        // exact-looking return type is insufficient evidence, and nested/mixed control flow
        // remains fail-closed until its complete result graph is proven.
        fun IrSimpleFunction.singlePhysicalReturnProducerOrNull(): IrExpression? = when (val functionBody = body) {
            is IrExpressionBody -> functionBody.expression
            is IrBlockBody -> functionBody.statements.filterIsInstance<IrReturn>()
                .singleOrNull { expression -> expression.returnTargetSymbol == symbol }
                ?.value
            else -> null
        }

        fun IrType.isNaturalClassifierInput(): Boolean {
            reifiedCovariantInterfaceOwnerOrNull() ?: return false
            val projection = (this as? IrSimpleType)?.arguments?.singleOrNull() as? IrTypeProjection
                ?: return false
            return projection.variance == Variance.INVARIANT &&
                    potentialSemanticInterfaceOwnerOrNull() == null
        }

        fun IrSimpleFunction.classifierInputParameterIndicesOrEmpty(): List<Int> {
            if (visibility != DescriptorVisibilities.PUBLIC || modality != Modality.FINAL || body == null ||
                isFakeOverride || isSuspend || typeParameters.isNotEmpty() ||
                correspondingPropertySymbol != null || returnType.reifiedCovariantInterfaceOwnerOrNull() != null ||
                parameters.any { parameter ->
                    parameter.kind != IrParameterKind.DispatchReceiver &&
                            parameter.kind != IrParameterKind.Regular ||
                            parameter.defaultValue != null || parameter.varargElementType != null
                }
            ) {
                return emptyList()
            }
            val owner = parent as? IrClass
            if (owner != null && (owner.isInterface || owner.typeParameters.isNotEmpty())) return emptyList()
            return parameters.mapIndexedNotNull { index, parameter ->
                index.takeIf {
                    parameter.kind == IrParameterKind.Regular && parameter.type.isNaturalClassifierInput()
                }
            }.takeIf { indices -> indices.size == 1 }.orEmpty()
        }

        // Keep the natural MethodDef and its direct typed body. The alternate compiler ABI owns
        // an IR copy of that body with only the classifier-derived input widened to object. This
        // avoids making ordinary Kotlin/C# calls pay an object-domain wrapper while retaining one
        // compiler-authored semantic definition for the same source body.
        if (!finalRoutingOnly) {
            for (source in sourceFunctions) {
                val objectParameterIndices = source.classifierInputParameterIndicesOrEmpty()
                if (objectParameterIndices.isEmpty()) continue
                val logicalKey = context.preLoweringDeclarationKeys[source] ?: continue
                val physicalName = "${source.dotNetIlMethodName()}__KotlinClassifierInput__" +
                        DotNetLibraryAbiCodec.logicalIdentityDigest(logicalKey)
                val inputEntry = context.irFactory.buildFun {
                    startOffset = source.startOffset
                    endOffset = source.endOffset
                    origin = DOTNET_GENERIC_OWNER_FUNCTION_INPUT_ENTRY
                    name = Name.identifier(physicalName)
                    visibility = DescriptorVisibilities.PUBLIC
                    modality = Modality.FINAL
                    returnType = source.returnType
                }.apply inputEntry@{
                    parent = source.parent
                    source.parameters.forEach { parameter ->
                        parameters += parameter.copyTo(this@inputEntry, defaultValue = null)
                    }
                    val parameterMapping = source.parameters.zip(parameters).toMap()
                    body = source.body!!.deepCopyWithSymbols(this@inputEntry).transform(
                        object : VariableRemapper(parameterMapping) {
                            override fun visitReturn(expression: IrReturn): IrExpression = super.visitReturn(
                                if (expression.returnTargetSymbol == source.symbol) {
                                    IrReturnImpl(
                                        expression.startOffset,
                                        expression.endOffset,
                                        expression.type,
                                        this@inputEntry.symbol,
                                        expression.value,
                                    )
                                } else {
                                    expression
                                }
                            )
                        },
                        null,
                    )
                }
                when (val owner = source.parent) {
                    is IrClass -> owner.declarations += inputEntry
                    is IrFile -> owner.declarations += inputEntry
                    else -> error(
                        "Internal .NET backend error: classifier-input function '${source.name}' has no physical owner"
                    )
                }
                objectParameterIndices.forEach { index ->
                    val parameter = inputEntry.parameters[index]
                    context.genericOwnerCapabilityDeclarations += parameter
                    context.genericOwnerForeignDispatchDeclarations += parameter
                }
                context.genericOwnerFunctionInputEntries[source] = inputEntry
            }
        }

        fun recordExternalFunctionCarrier(function: IrSimpleFunction) {
            val carrier = externalDeclarations.genericOwnerFunctionCarrierOrNull(function)?.carrier ?: return
            when (carrier.returnCarrier) {
                null -> Unit
                DotNetGenericOwnerFunctionCarrierKind.SEMANTIC_CAPABILITY ->
                    context.genericOwnerCapabilityDeclarations += function
                DotNetGenericOwnerFunctionCarrierKind.OBJECT -> {
                    context.genericOwnerCapabilityDeclarations += function
                    context.genericOwnerForeignDispatchDeclarations += function
                }
            }
            for (entry in carrier.parameterCarriers.entries) {
                val index = entry.key
                val carrierKind = entry.value
                val parameter = function.parameters.getOrNull(index)
                    ?: error(
                        "External generic-owner function '${function.name}' records missing parameter $index"
                    )
                context.genericOwnerCapabilityDeclarations += parameter
                if (carrierKind == DotNetGenericOwnerFunctionCarrierKind.OBJECT) {
                    context.genericOwnerForeignDispatchDeclarations += parameter
                }
            }
        }

        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: org.jetbrains.kotlin.ir.declarations.IrFunction) {
                if (declaration is IrSimpleFunction) {
                    if (declaration.dotNetValueClassConstructorImplementationSourceOrNull() != null) {
                        val backingField = getInlineClassBackingField(declaration.parent as IrClass)
                        val carrierParameter = declaration.parameters.singleOrNull { parameter ->
                            parameter.kind == IrParameterKind.Regular
                        }
                        if (backingField in context.genericOwnerCapabilityDeclarations) {
                            carrierParameter?.let(context.genericOwnerCapabilityDeclarations::add)
                        }
                        if (backingField in context.genericOwnerForeignDispatchDeclarations) {
                            carrierParameter?.let(context.genericOwnerForeignDispatchDeclarations::add)
                        }
                    }
                    if (declaration.origin == DOTNET_VALUE_CLASS_UNBOX_HELPER ||
                        declaration.origin == DOTNET_VALUE_CLASS_BOX_HELPER
                    ) {
                        val backingField = getInlineClassBackingField(declaration.parent as IrClass)
                        val carrierDeclaration = if (declaration.origin == DOTNET_VALUE_CLASS_UNBOX_HELPER) {
                            declaration
                        } else {
                            declaration.parameters.singleOrNull { parameter ->
                                parameter.kind == IrParameterKind.Regular
                            }
                        }
                        if (backingField in context.genericOwnerCapabilityDeclarations) {
                            carrierDeclaration?.let(context.genericOwnerCapabilityDeclarations::add)
                        }
                        if (backingField in context.genericOwnerForeignDispatchDeclarations) {
                            carrierDeclaration?.let(context.genericOwnerForeignDispatchDeclarations::add)
                        }
                    }
                    declaration.dotNetValueClassImplementationSourceOrNull()?.let { source ->
                        if (declaration.hasValueClassCarrierSourceIn(context.genericOwnerCapabilityDeclarations)) {
                            context.genericOwnerCapabilityDeclarations += declaration
                        }
                        if (declaration.hasValueClassCarrierSourceIn(context.genericOwnerForeignDispatchDeclarations)) {
                            context.genericOwnerForeignDispatchDeclarations += declaration
                        }
                        exactInterfaceDeclarationTypes[source]?.let { exactType ->
                            exactInterfaceDeclarationTypes[declaration] = exactType
                        }
                    }
                    recordSemanticDeclaration(
                        declaration,
                        declaration.returnType,
                        declaration.singlePhysicalReturnProducerOrNull(),
                    )
                    if (finalRoutingOnly &&
                        declaration.dotNetValueClassImplementationSourceOrNull() != null &&
                        declaration.returnType.potentialSemanticInterfaceOwnerOrNull() != null
                    ) {
                        check(declaration in context.genericOwnerCapabilityDeclarations) {
                            "Internal .NET backend error: late value-class implementation " +
                                    "'${declaration.name}' lost its semantic result carrier"
                        }
                    }
                }
                declaration.acceptChildrenVoid(this)
            }

            override fun visitValueParameter(declaration: IrValueParameter) {
                val sourceOwner = (declaration.parent as? IrSimpleFunction)?.parent as? IrClass
                val isNaturalReifiedInterfaceReceiver =
                    declaration.kind == IrParameterKind.DispatchReceiver &&
                            sourceOwner?.isInterface == true &&
                            (sourceOwner in context.reifiedGenericInterfaces ||
                                    externalDeclarations.hasReifiedGenericInterface(sourceOwner))
                if (!isNaturalReifiedInterfaceReceiver) {
                    recordSemanticDeclaration(declaration, declaration.type)
                }
                declaration.acceptChildrenVoid(this)
            }

            override fun visitVariable(declaration: IrVariable) {
                recordSemanticDeclaration(
                    declaration,
                    declaration.type,
                    declaration.initializer.takeUnless { declaration.isVar },
                )
                declaration.acceptChildrenVoid(this)
            }

            override fun visitField(declaration: IrField) {
                recordSemanticDeclaration(
                    declaration,
                    declaration.type,
                    declaration.initializer?.expression?.takeIf { declaration.isFinal },
                )
                declaration.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                // Route producers before their consuming call. Later Common lowerings commonly
                // build `outer(generatedGetter(receiver))`; the outer generic-owner decision may
                // depend on the physical capability returned by that new child call.
                expression.acceptChildrenVoid(this)
                val source = expression.symbol.owner.let { candidate ->
                    candidate.resolveFakeOverride() ?: candidate.resolveFakeOverrideMaybeAbstract() ?: candidate
                }
                recordExternalFunctionCarrier(source)
                // The physical carrier is part of the callable ABI, so a separately compiled
                // caller must derive the same decision from its external declaration stub. The
                // producer module has already published explicitly selected non-natural views;
                // recording the called stub here prevents the consumer from naming a constructed
                // CLR interface which the published method deliberately does not accept.
                recordSemanticDeclaration(source, source.returnType)
                source.parameters
                    .filter { parameter -> parameter.kind == IrParameterKind.Regular }
                    .forEach { parameter -> recordSemanticDeclaration(parameter, parameter.type) }
                val classifierInputEntry = context.genericOwnerFunctionInputEntries[source]
                    ?: externalDeclarations.genericOwnerFunctionInputEntryOrNull(source)?.let { binding ->
                        materializeExternalGenericOwnerFunctionInputEntry(
                            context,
                            source,
                            binding,
                        )
                    }
                if (classifierInputEntry != null && classifierInputEntry.parameters.indices.any { index ->
                        classifierInputEntry.parameters[index] in
                                context.genericOwnerForeignDispatchDeclarations &&
                                expression.arguments.getOrNull(index)?.let { argument ->
                                    argument.readsForeignDispatchDeclaration() ||
                                            argument.classifierErasedInterfaceOwnerOrNull() != null
                                } == true
                    }
                ) {
                    context.genericOwnerCapabilityCallTargets[expression] = classifierInputEntry
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
                val receiver = expression.dispatchReceiver
                val usesSemanticCarrier = when (receiver) {
                    null -> false
                    is IrGetValue -> receiver.symbol.owner in context.genericOwnerCapabilityDeclarations
                    is IrGetField -> receiver.symbol.owner in context.genericOwnerCapabilityDeclarations
                    is IrCall,
                    is IrTypeOperatorCall,
                        -> receiver.readsSemanticInterfaceDeclaration() ||
                            (receiver.type.potentialSemanticInterfaceOwnerOrNull() != null &&
                                    !receiver.provesExactPhysicalInterfaceView(receiver.type))
                    else -> receiver.type.potentialSemanticInterfaceOwnerOrNull() != null
                }
                if (finalRoutingOnly && usesSemanticCarrier &&
                    sourceOwner?.isDotNetGenericClassDeclaration == true
                ) {
                    check(slot != null) {
                        "Internal .NET backend error: late semantic call to " +
                                "'${sourceOwner.name}.${source.name}' lacks its published capability slot"
                    }
                }
                if (slot != null) {
                    if (usesSemanticCarrier) {
                        context.genericOwnerCapabilityCallTargets[expression] = slot
                        if (receiver?.readsForeignDispatchDeclaration() == true) {
                            context.genericOwnerForeignDispatchCallTargets[expression] = slot
                        }
                    } else if (!finalRoutingOnly) {
                        // This representation-aware pass has more information than the generic
                        // owner route planner: a stable CLR interface construction must not retain
                        // an earlier conservative semantic fallback selected for a sibling call.
                        // The final rescan is deliberately monotonic: absence of late evidence
                        // cannot invalidate an authoritative route selected before body copying.
                        context.genericOwnerCapabilityCallTargets.remove(expression)
                        context.genericOwnerForeignDispatchCallTargets.remove(expression)
                    }
                }
            }

            override fun visitConstructorCall(expression: IrConstructorCall) {
                // Constructors have their own IR node and therefore do not pass through
                // visitCall. Their regular parameters nevertheless publish the same physical
                // ABI and must be re-derived from an external stub by a separate compilation.
                expression.symbol.owner.parameters
                    .filter { parameter -> parameter.kind == IrParameterKind.Regular }
                    .forEach { parameter -> recordSemanticDeclaration(parameter, parameter.type) }
                expression.acceptChildrenVoid(this)
            }
        })
    }

    private fun buildSemanticCapability(
        owner: IrClass,
        file: IrFile,
        suffix: String,
        inheritedCapabilities: List<IrClass>,
    ): IrClass = context.irFactory.buildClass {
        startOffset = owner.startOffset
        endOffset = owner.endOffset
        origin = DOTNET_GENERIC_OWNER_CAPABILITY_INTERFACE
        name = Name.identifier("I${owner.name.asString()}KotlinSemantic$suffix")
        kind = ClassKind.INTERFACE
        modality = Modality.ABSTRACT
        visibility = DescriptorVisibilities.PUBLIC
    }.apply {
        parent = file
        superTypes = listOf(context.irBuiltIns.anyType) +
                inheritedCapabilities.map { it.symbol.defaultType }
        createThisReceiverParameter()
    }

    /**
     * Preserves every direct interface fact which is valid for all closed constructions of C<T>.
     *
     * A non-generic class capability cannot inherit one arbitrary construction of a reified CLR
     * interface. It can, however, inherit that interface's semantic capability. Kotlin generic
     * interfaces which remain declaration-erased have one physical identity for every argument,
     * so substituting the class parameters with Any? is truthful as well. Imported CLR generics
     * are deliberately excluded: C<Int> implementing IFoo<Int> does not imply IFoo<object>.
     */
    private fun closeGenericClassCapabilityInterfaceSupertypes() {
        for (entry in context.genericOwnerCapabilityInterfaces.toList()) {
            val owner = entry.first
            val capability = entry.second
            if (!owner.isDotNetGenericClassDeclaration) continue
            val ownerSubstitutor = IrTypeSubstitutor(
                owner.typeParameters.associate { parameter ->
                    parameter.symbol to context.irBuiltIns.anyNType
                },
                allowEmptySubstitution = true,
            )
            for (superType in owner.dotNetDirectInterfaceTypes()) {
                val superOwner = (superType.classifier as? IrClassSymbol)?.owner
                    ?: continue
                if (superOwner === capability ||
                    superOwner.origin == DOTNET_GENERIC_OWNER_CAPABILITY_INTERFACE
                ) {
                    continue
                }
                val localCapability = context.genericOwnerCapabilityInterfaces[superOwner]
                when {
                    localCapability != null ->
                        capability.superTypes += localCapability.symbol.defaultType
                    externalDeclarations.hasReifiedGenericInterface(superOwner) -> {
                        val providers = context.externalGenericOwnerCapabilitySupertypeProviders[capability]
                            .orEmpty()
                        val provider = context.externalReifiedGenericInterfaceCapabilityProviders[superOwner]
                            ?: superOwner
                        if (provider !in providers) {
                            context.externalGenericOwnerCapabilitySupertypeProviders[capability] =
                                providers + provider
                        }
                    }
                    superOwner.isDotNetGenericInterfaceDeclaration ->
                        capability.superTypes += ownerSubstitutor.substitute(superType)
                    superOwner.typeParameters.isEmpty() -> capability.superTypes += superType
                }
            }
            capability.superTypes = capability.superTypes.distinct()
        }
    }

    private fun materializeDeclaredSemanticSlots(
        capability: IrClass,
        ownerIdentity: String,
        sources: List<IrSimpleFunction>,
        slotsBySource: MutableMap<IrSimpleFunction, IrSimpleFunction>,
    ) {
        for (source in sources) {
            val logicalRoot = context.preLoweringDeclarationKeys[source]
                ?: "$ownerIdentity.${source.name.asString()}"
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
                returnType = source.returnType.semanticInterfaceSlotType(
                    (source.parent as IrClass).typeParameters.single(),
                )
            }.apply {
                parameters += createDispatchReceiverParameterWithClassParent()
                val ownerParameter = (source.parent as IrClass).typeParameters.single()
                source.parameters.filter { parameter -> parameter.kind == IrParameterKind.Regular }
                    .forEach { parameter ->
                        addValueParameter(
                            parameter.name.asString(),
                            parameter.type.semanticInterfaceSlotType(ownerParameter),
                        )
                    }
            }
            slotsBySource[source] = slot
            context.genericOwnerCapabilitySlots[source] = slot
            context.genericOwnerCapabilityDeclarations += slot
            context.genericOwnerCapabilityDeclarations += slot.parameters
        }
    }

    private fun IrClass.declaredInterfaceMembers(): List<IrSimpleFunction> =
        declarations.flatMap { declaration ->
            when (declaration) {
                is IrSimpleFunction -> listOf(declaration)
                is IrProperty -> listOfNotNull(declaration.getter, declaration.setter)
                else -> emptyList()
            }
        }.filterNot(IrSimpleFunction::isFakeOverride)

    private fun IrClass.declaredInterfaceProperties(): List<IrProperty> =
        declarations.filterIsInstance<IrProperty>().filter { property ->
            listOfNotNull(property.getter, property.setter).any { accessor -> !accessor.isFakeOverride }
        }

    private fun IrClass.reifiedInterfaceChildShapeOrNull(): ReifiedInterfaceChildShape? {
        if (!hasFirstReifiedInterfaceOwnerShape()) return null
        val parameter = typeParameters.single()
        val members = when (parameter.variance) {
            Variance.OUT_VARIANCE -> declaredInterfaceMembers().takeIf { declared ->
                declaredInterfaceProperties().isEmpty() &&
                        declared.size <= 1 &&
                        declared.all { member -> member.isDirectProducerMember(parameter) }
            }
            Variance.INVARIANT -> directInvariantPropertyChildMembersOrNull(parameter)
            Variance.IN_VARIANCE -> null
        } ?: return null
        val parentTypes = dotNetDirectInterfaceTypes().takeIf { it.isNotEmpty() } ?: return null
        val parents = parentTypes.map { parentType ->
            val parent = (parentType.classifier as? IrClassSymbol)?.owner ?: return null
            val argument = parentType.arguments.singleOrNull() as? IrTypeProjection ?: return null
            val argumentParameter = (argument.type as? IrSimpleType)?.classifier as? IrTypeParameterSymbol
            parent.takeIf {
                argument.variance == Variance.INVARIANT && argumentParameter?.owner === parameter
            } ?: return null
        }
        if (parameter.variance == Variance.INVARIANT) {
            val parent = parents.singleOrNull() ?: return null
            val parentParameter = parent.typeParameters.singleOrNull() ?: return null
            val parentPropertyMembers = parent.directInvariantPropertyMembersOrNull(parentParameter)
            val isDirectConsumer = directInvariantConsumerMembersOrNull(parameter) != null
            val parentIsConsumerChild = parent.isExactInvariantPropertyConsumerChildOfRoot()
            if (parentParameter.variance != Variance.INVARIANT ||
                !((parent.dotNetDirectInterfaceTypes().isEmpty() &&
                        parentPropertyMembers != null) ||
                        (isDirectConsumer && parentIsConsumerChild))
            ) {
                return null
            }
        }
        return ReifiedInterfaceChildShape(parents, members)
    }

    private fun IrClass.isFirstReifiedInterfaceCandidate(): Boolean {
        if (!hasFirstReifiedInterfaceOwnerShape()) return false
        if (parent !is IrFile || dotNetDirectInterfaceTypes().isNotEmpty()) return false
        val parameter = typeParameters.single()
        val members = declaredInterfaceMembers()
        val properties = declaredInterfaceProperties()
        if (properties.size == 1) {
            return when (parameter.variance) {
                Variance.OUT_VARIANCE -> directCovariantPropertyMembersOrNull(parameter) != null
                Variance.INVARIANT -> directInvariantPropertyMembersOrNull(parameter) != null
                Variance.IN_VARIANCE -> false
            }
        }
        if (properties.isNotEmpty() ||
            members.map { member -> member.name }.distinct().size != members.size
        ) {
            return false
        }
        return when (parameter.variance) {
            Variance.OUT_VARIANCE -> members.singleOrNull()?.isDirectProducerMember(parameter) == true
            Variance.IN_VARIANCE -> members.singleOrNull()?.isDirectConsumerMember(parameter) == true
            Variance.INVARIANT ->
                members.singleOrNull()?.isDirectProducerMember(parameter) == true ||
                        (members.size == 2 &&
                                members.count { member ->
                                    member.isDirectProducerMember(parameter)
                                } == 1 &&
                                members.count { member ->
                                    member.isDirectConsumerMember(parameter)
                                } == 1)
        }
    }

    private fun IrClass.directCovariantPropertyMembersOrNull(
        parameter: IrTypeParameter,
    ): List<IrSimpleFunction>? {
        val property = declaredInterfaceProperties().singleOrNull() ?: return null
        val getter = property.getter ?: return null
        val members = declaredInterfaceMembers()
        return members.takeIf {
            !property.isVar && property.setter == null && members == listOf(getter) &&
                    getter.correspondingPropertySymbol?.owner === property &&
                    getter.isDirectProducerMember(parameter)
        }
    }

    private fun IrClass.directInvariantPropertyMembersOrNull(
        parameter: IrTypeParameter,
    ): List<IrSimpleFunction>? {
        val property = declaredInterfaceProperties().singleOrNull() ?: return null
        val getter = property.getter ?: return null
        val setter = property.setter ?: return null
        val members = declaredInterfaceMembers()
        return members.takeIf {
            property.isVar && members.size == 2 && members.toSet() == setOf(getter, setter) &&
                    getter.correspondingPropertySymbol?.owner === property &&
                    setter.correspondingPropertySymbol?.owner === property &&
                    getter.isDirectProducerMember(parameter) &&
                    setter.isDirectConsumerMember(parameter)
        }
    }

    private fun IrClass.directInvariantPropertyChildMembersOrNull(
        parameter: IrTypeParameter,
    ): List<IrSimpleFunction>? {
        directInvariantPropertyMembersOrNull(parameter)?.let { return it }
        return directInvariantConsumerMembersOrNull(parameter)
    }

    private fun IrClass.directInvariantConsumerMembersOrNull(
        parameter: IrTypeParameter,
    ): List<IrSimpleFunction>? {
        if (declaredInterfaceProperties().isNotEmpty()) return null
        return declaredInterfaceMembers().takeIf { members ->
            members.size == 1 && members.single().isDirectConsumerMember(parameter)
        }
    }

    private fun IrClass.isExactInvariantPropertyConsumerChildOfRoot(): Boolean {
        val contract = publishedFamilyOrNull(this) ?: return false
        val parameter = typeParameters.singleOrNull() ?: return false
        val consumerMembers = directInvariantConsumerMembersOrNull(parameter)
        if (parameter.variance != Variance.INVARIANT || consumerMembers == null ||
            contract.kind != DotNetPublishedGenericInterfaceFamilyKind.DERIVED ||
            contract.lineageDepth != 1 ||
            contract.directParents.size != 1 ||
            contract.capabilityBindingKind != DotNetPublishedGenericInterfaceCapabilityBindingKind.OWNED
        ) {
            return false
        }
        val parentType = dotNetDirectInterfaceTypes().singleOrNull() ?: return false
        val parent = (parentType.classifier as? IrClassSymbol)?.owner ?: return false
        val argument = parentType.arguments.singleOrNull() as? IrTypeProjection ?: return false
        val argumentParameter = (argument.type as? IrSimpleType)?.classifier as? IrTypeParameterSymbol
        if (argument.variance != Variance.INVARIANT || argumentParameter?.owner !== parameter) {
            return false
        }
        val parentParameter = parent.typeParameters.singleOrNull() ?: return false
        val parentPropertyMembers = parent.directInvariantPropertyMembersOrNull(parentParameter)
            ?: return false
        val rootContract = publishedFamilyOrNull(parent) ?: return false
        return parentParameter.variance == Variance.INVARIANT &&
                parent.dotNetDirectInterfaceTypes().isEmpty() &&
                parent.hasLogicalReifiedInterfaceOwnerShape() &&
                rootContract.kind == DotNetPublishedGenericInterfaceFamilyKind.ROOT &&
                rootContract.lineageDepth == 0 &&
                contract.rootLogicalOwnerKeys == listOf(rootContract.logicalOwnerKey) &&
                contract.directParents.single().logicalOwnerKey == rootContract.logicalOwnerKey &&
                parentPropertyMembers.size == rootContract.declaredMembers.size
    }

    private fun IrSimpleFunction.isDirectProducerMember(parameter: IrTypeParameter): Boolean {
        val hasDefaultImplementation = this in context.interfaceDefaultImplementations
        if (visibility != DescriptorVisibilities.PUBLIC ||
            (!hasDefaultImplementation && (modality != Modality.ABSTRACT || body != null)) ||
            typeParameters.isNotEmpty() ||
            parameters.singleOrNull()?.kind != IrParameterKind.DispatchReceiver
        ) {
            return false
        }
        val resultType = returnType as? IrSimpleType ?: return false
        if (resultType.isMarkedNullable()) return false
        val resultParameter = resultType.classifier as? IrTypeParameterSymbol
        return resultParameter?.owner === parameter
    }

    private fun IrSimpleFunction.isDirectConsumerMember(parameter: IrTypeParameter): Boolean {
        val hasDefaultImplementation = this in context.interfaceDefaultImplementations
        if (visibility != DescriptorVisibilities.PUBLIC ||
            (!hasDefaultImplementation && (modality != Modality.ABSTRACT || body != null)) ||
            typeParameters.isNotEmpty() || !returnType.isUnit()
        ) {
            return false
        }
        val regular = parameters.singleOrNull { it.kind == IrParameterKind.Regular } ?: return false
        if (parameters.size != 2) return false
        val inputType = regular.type as? IrSimpleType ?: return false
        if (inputType.isMarkedNullable()) return false
        val inputParameter = inputType.classifier as? IrTypeParameterSymbol
        return inputParameter?.owner === parameter
    }

    private fun IrClass.hasFirstReifiedProducerOwnerShape(): Boolean {
        if (!hasFirstReifiedInterfaceOwnerShape()) return false
        return typeParameters.single().variance == Variance.OUT_VARIANCE
    }

    private fun IrClass.hasFirstReifiedInterfaceOwnerShape(): Boolean {
        if (!hasLogicalReifiedInterfaceOwnerShape() || parent !is IrFile) return false
        return true
    }

    private fun IrClass.hasLogicalReifiedInterfaceOwnerShape(): Boolean {
        if (!isDotNetGenericInterfaceDeclaration || visibility != DescriptorVisibilities.PUBLIC) return false
        val parameter = typeParameters.singleOrNull() ?: return false
        return parameter.superTypes.all(IrType::isNullableAny)
    }

    private fun IrType.semanticInterfaceSlotType(ownerParameter: IrTypeParameter): IrType {
        val classifier = (this as? IrSimpleType)?.classifier as? IrTypeParameterSymbol
        return if (classifier?.owner === ownerParameter) context.irBuiltIns.anyNType else this
    }
}

/**
 * Re-applies value/call routing after every body-producing lowering which can introduce a generic
 * operation has completed.
 *
 * Generated declarations need not precede their users. Iterate the monotone identity sets/maps to
 * a fixpoint so a carrier proven in a later declaration can route an earlier call on the next
 * round; no round creates a declaration family or removes an authoritative early route.
 */
internal class DotNetGenericOwnerFinalRoutingLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    override fun lower(irModule: IrModuleFragment) {
        fun stateSizes(): List<Int> = listOf(
            context.genericOwnerCapabilityDeclarations.size,
            context.genericOwnerForeignDispatchDeclarations.size,
            context.genericOwnerExactInterfaceDeclarationTypes.size,
            context.genericOwnerCapabilityBearingDeclarations.size,
            context.genericOwnerCapabilityCallTargets.size,
            context.genericOwnerForeignDispatchCallTargets.size,
        )

        var previousState: List<Int>
        do {
            previousState = stateSizes()
            DotNetReifiedGenericInterfaceLowering(context, finalRoutingOnly = true).lower(irModule)
        } while (stateSizes() != previousState)
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
            source.parameters.firstOrNull()?.kind == IrParameterKind.DispatchReceiver) {
        "External reified-interface member '${source.name}' is outside the admitted structural family"
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
        val ownerParameter = owner.typeParameters.single()
        val resultParameter = (source.returnType as? IrSimpleType)?.classifier as? IrTypeParameterSymbol
        returnType = if (resultParameter?.owner === ownerParameter) {
            context.irBuiltIns.anyNType
        } else {
            source.returnType
        }
    }.apply {
        parent = owner
        parameters += createDispatchReceiverParameterWithClassParent()
        val ownerParameter = owner.typeParameters.single()
        source.parameters.filter { parameter -> parameter.kind == IrParameterKind.Regular }
            .forEach { parameter ->
                val inputParameter = (parameter.type as? IrSimpleType)?.classifier as? IrTypeParameterSymbol
                addValueParameter(
                    parameter.name.asString(),
                    if (inputParameter?.owner === ownerParameter) context.irBuiltIns.anyNType else parameter.type,
                )
            }
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

/** Reconstructs one producer-recorded object-input MethodDef without adding a logical callable. */
private fun materializeExternalGenericOwnerFunctionInputEntry(
    context: DotNetBackendContext,
    source: IrSimpleFunction,
    binding: DotNetBoundGenericOwnerFunctionInputEntry,
): IrSimpleFunction {
    context.externalGenericOwnerFunctionInputEntries.entries.singleOrNull { entry ->
        entry.value == binding
    }?.let { entry -> return entry.key }
    val physicalEntry = binding.entry
    require(source.typeParameters.isEmpty() &&
            physicalEntry.objectParameterIndices.all(source.parameters.indices::contains)) {
        "External generic-owner function '${source.name}' has an invalid classifier-input entry"
    }
    return context.irFactory.buildFun {
        startOffset = source.startOffset
        endOffset = source.endOffset
        origin = DOTNET_GENERIC_OWNER_FUNCTION_INPUT_ENTRY
        name = Name.special("<ExternalGenericOwnerFunctionInput-${source.name.asString()}>")
        visibility = DescriptorVisibilities.PRIVATE
        modality = Modality.FINAL
        returnType = source.returnType
    }.apply inputEntry@{
        parent = source.parent
        source.parameters.forEach { parameter ->
            parameters += parameter.copyTo(this@inputEntry, defaultValue = null)
        }
        physicalEntry.objectParameterIndices.forEach { index ->
            val parameter = parameters[index]
            context.genericOwnerCapabilityDeclarations += parameter
            context.genericOwnerForeignDispatchDeclarations += parameter
        }
        context.externalGenericOwnerFunctionInputEntries[this] = binding
    }
}
