/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.ir.moveBodyTo
import org.jetbrains.kotlin.backend.common.lower.SpecialBridgeMethods
import org.jetbrains.kotlin.backend.common.lower.SpecialMethodWithDefaultInfo
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.dotnet.DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetBoundGenericOwnerPhysicalSlot
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalDeclarations
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceMemberView
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericInterfaceMemberView
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericInterfaceMemberViews
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerDirectForeignOverrideDispatch
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberFamilyRole
import org.jetbrains.kotlin.backend.dotnet.DotNetInterfaceDefaultPromotionView
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalInterfaceCapabilityDispatcherSelection
import org.jetbrains.kotlin.backend.dotnet.DotNetLoweredGenericInterfaceViewBridge
import org.jetbrains.kotlin.backend.dotnet.DotNetLoweredInterfaceDefaultClassForwarder
import org.jetbrains.kotlin.backend.dotnet.DotNetRuntimeTypes
import org.jetbrains.kotlin.backend.dotnet.dotNetBaseClassOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetDirectOwnerRelativeMethodBoundsOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericInterfaceCanonicalSlotId
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerRehearsal
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerPhysicalForeignOverrideProbeName
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerPhysicalMemberName
import org.jetbrains.kotlin.backend.dotnet.dotNetIlMethodName
import org.jetbrains.kotlin.backend.dotnet.dotNetUnsupported
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericClassDeclaration
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericInterfaceDeclaration
import org.jetbrains.kotlin.backend.dotnet.isReifiedByGenericOwnerRehearsal
import org.jetbrains.kotlin.backend.dotnet.requiresExactInputView
import org.jetbrains.kotlin.backend.dotnet.isDotNetComparableClass
import org.jetbrains.kotlin.backend.dotnet.isDotNetOwnerDependentConstraint
import org.jetbrains.kotlin.backend.dotnet.isDotNetResolutionOnlyStdlibDeclaration
import org.jetbrains.kotlin.backend.dotnet.referencesTypeParameterOf
import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.config.dotNetTarget
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irIs
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
import org.jetbrains.kotlin.ir.types.AbstractIrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

internal val DOTNET_GENERIC_INTERFACE_CANONICAL_BRIDGE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_INTERFACE_CANONICAL_BRIDGE")

internal val DOTNET_GENERIC_INTERFACE_DECLARED_BRIDGE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_INTERFACE_DECLARED_BRIDGE")

internal val DOTNET_GENERIC_INTERFACE_DECLARED_RELATIVE_GENERIC_INPUT_BRIDGE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_INTERFACE_DECLARED_RELATIVE_GENERIC_INPUT_BRIDGE")

internal val DOTNET_GENERIC_INTERFACE_EXACT_BRIDGE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_INTERFACE_EXACT_BRIDGE")

internal val DOTNET_REIFIED_GENERIC_INTERFACE_CLOSED_OWNER_RELATIVE_SEMANTIC_IMPLEMENTATION:
    IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_REIFIED_GENERIC_INTERFACE_CLOSED_OWNER_RELATIVE_SEMANTIC_IMPLEMENTATION")

internal val DOTNET_REIFIED_GENERIC_INTERFACE_CLOSED_OWNER_RELATIVE_NATURAL_BRIDGE:
    IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_REIFIED_GENERIC_INTERFACE_CLOSED_OWNER_RELATIVE_NATURAL_BRIDGE")

private val DOTNET_REIFIED_GENERIC_INTERFACE_EXTERNAL_OWNER_RELATIVE_FAMILY_MEMBER:
    IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_REIFIED_GENERIC_INTERFACE_EXTERNAL_OWNER_RELATIVE_FAMILY_MEMBER")

internal val IrDeclarationOrigin.dotNetGenericInterfaceBridgeMemberViewOrNull: DotNetGenericInterfaceMemberView?
    get() = when (this) {
        DOTNET_GENERIC_INTERFACE_DECLARED_BRIDGE,
        DOTNET_GENERIC_INTERFACE_DECLARED_RELATIVE_GENERIC_INPUT_BRIDGE,
        -> DotNetGenericInterfaceMemberView.DECLARED
        DOTNET_GENERIC_INTERFACE_EXACT_BRIDGE -> DotNetGenericInterfaceMemberView.EXACT
        else -> null
    }

internal val IrDeclarationOrigin.isDotNetGenericInterfaceRelativeGenericInputBridge: Boolean
    get() = this == DOTNET_GENERIC_INTERFACE_DECLARED_RELATIVE_GENERIC_INPUT_BRIDGE

internal val IrDeclarationOrigin.isDotNetGenericInterfaceBridge: Boolean
    get() = this == DOTNET_GENERIC_INTERFACE_CANONICAL_BRIDGE ||
            dotNetGenericInterfaceBridgeMemberViewOrNull != null

/**
 * Adds the canonical erased slots of Kotlin-owned generic interfaces.
 *
 * The user's typed member remains the implementation of either the declaration-variance-safe
 * generic sibling or the invariant exact capability. A private explicit MethodImpl bridge
 * implements the non-generic canonical identity slot on the same object. All declaration type
 * parameters erase at that boundary: Kotlin projections therefore remain reference copies and
 * never allocate adapters or depend on CLR generic variance.
 */
internal class DotNetGenericInterfaceBridgeLowering(private val context: DotNetBackendContext) : ModuleLoweringPass {
    private val specialBridgeMethods = SpecialBridgeMethods(context)
    private val nonGenericOwnerRelativeImplementations =
        linkedMapOf<IrSimpleFunction, NonGenericOwnerRelativeImplementation>()
    private val openNonGenericOwnerRelativeCapabilities = linkedMapOf<IrClass, IrClass>()
    private val openNonGenericOwnerRelativeDispatchers = linkedMapOf<IrSimpleFunction, IrSimpleFunction>()
    private val relativeGenericInputTypedBridges =
        linkedMapOf<RelativeGenericInputBridgeKey, IrSimpleFunction>()

    private data class RelativeGenericInputBridgeKey(
        val owner: IrClass,
        val target: IrSimpleFunction,
        val inputIndex: Int,
    )

    private data class NonGenericOwnerRelativeImplementation(
        val familyOwner: IrClass,
        val inheritedOwnerBound: IrType?,
        val typedImplementation: IrSimpleFunction,
        val semanticImplementation: IrSimpleFunction,
        val foreignOverrideProbe: IrSimpleFunction?,
        val ownedCapabilitySlot: IrSimpleFunction?,
    )

    private data class BridgePlan(
        val implementingClass: IrClass,
        val slot: IrSimpleFunction,
        val target: IrSimpleFunction,
        val interfaceClass: IrClass,
        val interfaceIdentity: String,
        val slotIdentity: String,
        val typedSubstitutor: AbstractIrTypeSubstitutor,
        val typedViews: List<DotNetGenericInterfaceMemberView>,
    )

    override fun lower(irModule: IrModuleFragment) {
        val bridgeOwners = mutableListOf<IrClass>()
        val localClasses = linkedSetOf<IrClass>()
        val genericInterfaces = hashSetOf<IrClass>()
        val genericClasses = hashSetOf<IrClass>()
        val externalDeclarations = context.externalDeclarationsForLowering()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.isDotNetResolutionOnlyStdlibDeclaration) {
                    declaration.acceptChildrenVoid(this)
                    return
                }
                localClasses += declaration
                if (declaration.isInterface) {
                    if (declaration.isDotNetGenericInterfaceDeclaration) {
                        genericInterfaces += declaration
                    } else if (context.configuration.dotNetTarget == DotNetTarget.NET10_0) {
                        bridgeOwners += declaration
                    }
                } else {
                    bridgeOwners += declaration
                    if (declaration.typeParameters.isNotEmpty()) {
                        genericClasses += declaration
                    }
                }
                declaration.acceptChildrenVoid(this)
            }
        })
        check(genericClasses.all(context.genericOwnerArchitecturePlans::containsKey)) {
            "Internal .NET backend error: a Kotlin-owned generic class bypassed architecture planning"
        }
        context.erasedGenericInterfaces += genericInterfaces - context.reifiedGenericInterfaces
        context.erasedGenericClasses += if (context.configuration.dotNetGenericOwnerRehearsal) {
            genericClasses.filterNotTo(linkedSetOf()) { owner ->
                context.genericOwnerArchitecturePlans.getValue(owner)
                    .isReifiedByGenericOwnerRehearsal
            }
        } else {
            genericClasses
        }
        fun isMappedKotlinGenericInterface(irClass: IrClass): Boolean =
            irClass in genericInterfaces ||
                    DotNetRuntimeTypes.hasBuiltInGenericInterfaceMapping(irClass) ||
                    externalDeclarations.hasGenericInterface(irClass) ||
                    externalDeclarations.hasReifiedGenericInterface(irClass)
        fun isErasedKotlinGenericClass(irClass: IrClass): Boolean =
            irClass.isDotNetGenericClassDeclaration &&
                    (!context.configuration.dotNetGenericOwnerRehearsal ||
                            context.genericOwnerArchitecturePlans[irClass]
                                ?.isReifiedByGenericOwnerRehearsal != true)
        fun isErasedKotlinCarrier(irClass: IrClass): Boolean =
            (isMappedKotlinGenericInterface(irClass) &&
                    irClass !in context.reifiedGenericInterfaces &&
                    !externalDeclarations.hasReifiedGenericInterface(irClass) &&
                    !(context.configuration.dotNetGenericOwnerRehearsal &&
                            DotNetRuntimeTypes.usesDeclaredViewByDefaultInRehearsal(irClass))) ||
                    isErasedKotlinGenericClass(irClass) ||
                    externalDeclarations.hasGenericClass(irClass)
        for (irClass in bridgeOwners.sortedBy { it.classInheritanceDepth() }) {
            addBridges(
                irClass,
                ::isMappedKotlinGenericInterface,
                ::isErasedKotlinCarrier,
                externalDeclarations,
                localClasses,
            )
        }
    }

    private fun IrClass.classInheritanceDepth(): Int {
        val visited = hashSetOf<IrClass>()
        var depth = 0
        var current = dotNetBaseClassOrNull()
        while (current != null && visited.add(current)) {
            depth++
            current = current.dotNetBaseClassOrNull()
        }
        return depth
    }

    private fun addBridges(
        irClass: IrClass,
        isMappedKotlinGenericInterface: (IrClass) -> Boolean,
        isErasedKotlinCarrier: (IrClass) -> Boolean,
        externalDeclarations: DotNetExternalDeclarations,
        localClasses: Set<IrClass>,
    ) {
        // The semantic interface is a property of the implemented owner view, not merely of a
        // member which happens to declare a slot. In particular, a producer intersection can
        // inherit all of its methods and still has its own semantic identity. Previously the
        // natural interface's inheritance edge supplied that identity implicitly; now that
        // foreign CLR source may implement I<T> alone, Kotlin implementations must state every
        // directly implemented semantic owner explicitly.
        if (!irClass.isInterface) {
            irClass.superTypes
                .mapNotNull { type -> (type as? IrSimpleType)?.classifier as? IrClassSymbol }
                .map { symbol -> symbol.owner }
                .filter { interfaceClass ->
                    interfaceClass in context.reifiedGenericInterfaces ||
                            externalDeclarations.hasReifiedGenericInterface(interfaceClass)
                }
                .forEach { interfaceClass ->
                    irClass.addReifiedGenericInterfaceCapability(
                        interfaceClass,
                        externalDeclarations,
                    )
                }
        }
        val implementationFunctions = irClass.declarations.flatMap { declaration ->
            when (declaration) {
                is IrSimpleFunction -> listOf(declaration)
                is IrProperty -> listOfNotNull(declaration.getter, declaration.setter)
                else -> emptyList()
            }
        }
        val slots = implementationFunctions
            .flatMap { function -> function.allOverridden().asSequence() }
            .filter { overridden ->
                // A fake override is only an IR view of an inherited slot. Its interface does
                // not declare a corresponding CLR method, so emitting a MethodImpl for it makes
                // a MemberRef to a method that does not exist (notably Channel::produce and the
                // Any methods materialized on every interface). The real declaration appears in
                // the same allOverridden chain and is the only physical slot to implement.
                !overridden.isFakeOverride &&
                        (overridden.parent as? IrClass)?.let(isMappedKotlinGenericInterface) == true
            }
            .distinctBy { it.symbol }
            .toList()
        for (slot in slots) {
            if (implementationFunctions.any { bridge ->
                    bridge.origin == DOTNET_GENERIC_INTERFACE_CANONICAL_BRIDGE &&
                            bridge.overriddenSymbols.singleOrNull() == slot.symbol
                }
            ) {
                continue
            }
            val candidates = implementationFunctions
                .filter { candidate -> candidate.allOverridden().any { it.symbol == slot.symbol } }
            val declaredCandidate = candidates.firstOrNull { !it.isFakeOverride }
            // A concrete member declared by a non-generic net10 interface is a DIM and must
            // explicitly implement every inherited physical view. Fake-only inheritance already
            // has its selected DIM provider, while reabstraction must remain abstract.
            if (irClass.isInterface && declaredCandidate?.body == null) continue
            val target = declaredCandidate
                ?: candidates.firstOrNull()
                ?: continue
            val implementation = if (target.isFakeOverride) {
                target.resolveFakeOverride() ?: target.resolveFakeOverrideMaybeAbstract()
            } else {
                target
            } ?: continue
            val implementationOwner = implementation.parent as? IrClass
            val implementationOwnerIsReified = implementationOwner != null &&
                    (implementationOwner in context.reifiedGenericInterfaces ||
                            externalDeclarations.hasReifiedGenericInterface(implementationOwner))
            val implementationIsDefault =
                implementationOwnerIsReified &&
                        (implementation in context.interfaceDefaultImplementations ||
                                (externalDeclarations.hasGenericInterface(implementationOwner) ||
                                        externalDeclarations.hasReifiedGenericInterface(implementationOwner)) &&
                                externalDeclarations.interfaceDefaultImplementationOrNull(implementation) != null)
            val implementationUsesDefaultHelper =
                implementationIsDefault ||
                        implementation.origin == DOTNET_GENERIC_INTERFACE_DEFAULT_FORWARDER_TARGET
            val hasOwnerRelativeMethodBound =
                slot.dotNetDirectOwnerRelativeMethodBoundsOrNull(slot.parent as IrClass)
                    ?.any { bound -> bound != null } == true
            val ownerRelativeDefaultHelper = if (
                implementationUsesDefaultHelper &&
                hasOwnerRelativeMethodBound
            ) {
                context.genericInterfaceDefaultSemanticHelpers[implementation]
                    ?: context.genericInterfaceDefaultSemanticHelpers[slot]
                    ?: error(
                        "Internal .NET backend error: owner-relative generic-interface default " +
                                "'${slot.name}' has no semantic helper"
                    )
            } else {
                null
            }
            // A reified class owner may already have selected an object-domain dispatcher for
            // this implementation (for example a Nested<T> result backed by semantic state).
            // The interface capability must compose with that stronger decision instead of
            // calling the public typed entry and narrowing the result too early.
            val genericOwnerSemanticTarget = context.genericOwnerCapabilityDispatchers[implementation]
            if ((implementation.parent as? IrClass)?.let(isMappedKotlinGenericInterface) == true &&
                !implementationIsDefault
            ) {
                continue
            }
            val interfaceClass = slot.parent as? IrClass
                ?: error("Internal .NET backend error: generic interface slot has no interface owner")
            val typedSubstitutor = AbstractIrTypeSubstitutor.forSuperClass(
                interfaceClass.symbol,
                irClass.symbol.defaultType,
            ) ?: error(
                "Internal .NET backend error: '${irClass.name}' is not a subtype of " +
                        "generic interface '${interfaceClass.name}'"
            )
            val plan = BridgePlan(
                implementingClass = irClass,
                slot = slot,
                target = implementation,
                interfaceClass = interfaceClass,
                interfaceIdentity = interfaceClass.fqNameWhenAvailable?.asString()
                    ?: interfaceClass.name.asString(),
                slotIdentity = slot.dotNetGenericInterfaceCanonicalSlotId(),
                typedSubstitutor = typedSubstitutor,
                typedViews = when {
                    isErasedKotlinCarrier(irClass) -> emptyList()
                    interfaceClass.isDotNetComparableClass() -> {
                        // Comparable is an explicit BCL mapping rather than an ordinary
                        // Kotlin-owned generic-interface sibling.
                        listOf(DotNetGenericInterfaceMemberView.DECLARED)
                    }
                    DotNetRuntimeTypes.genericInterfaceInfoFor(
                        interfaceClass,
                        includeRehearsalDeclaredViews =
                            context.configuration.dotNetGenericOwnerRehearsal,
                    )?.declaredClassInfo != null ->
                        slot.dotNetGenericInterfaceMemberViews(
                            interfaceClass,
                            isErasedKotlinCarrier,
                        )
                    else -> emptyList()
                },
            )
            val closedSemanticInputEntry = prepareClosedNonGenericSemanticInputEntry(
                plan,
                isMappedKotlinGenericInterface,
                isErasedKotlinCarrier,
            )
            if (interfaceClass in context.reifiedGenericInterfaces ||
                externalDeclarations.hasReifiedGenericInterface(interfaceClass)
            ) {
                if (irClass.isInterface) continue
                irClass.addReifiedGenericInterfaceCapability(
                    interfaceClass,
                    externalDeclarations,
                )
                val capabilitySlot = context.genericOwnerCapabilitySlots[slot]
                    ?: materializeExternalReifiedGenericInterfaceCapabilitySlot(
                        context,
                        externalDeclarations,
                        slot,
                    )
                if (!irClass.inheritsReifiedGenericInterfaceCapabilityBridge(capabilitySlot)) {
                    if (ownerRelativeDefaultHelper != null) {
                        createOwnerRelativeDefaultCapabilityBridge(
                            plan,
                            capabilitySlot,
                            ownerRelativeDefaultHelper,
                        )
                    } else {
                        val nonGenericImplementation = if (
                            hasOwnerRelativeMethodBound &&
                            // Every local binding owner needs its own natural and interface
                            // MethodImpls. Reusing the base's private dispatcher would violate
                            // CLR accessibility even though the base family itself is shared.
                            (plan.target in nonGenericOwnerRelativeImplementations ||
                                    genericOwnerSemanticTarget == null)
                        ) {
                            createNonGenericOwnerRelativeImplementation(
                                plan,
                                localClasses,
                                externalDeclarations,
                            )
                        } else {
                            null
                        }
                        val semanticTarget = genericOwnerSemanticTarget
                            ?: nonGenericImplementation?.semanticImplementation
                            ?: closedSemanticInputEntry
                            ?: plan.target
                        val capabilityBridge = createForwardingBridge(
                            irClass = plan.implementingClass,
                            slot = capabilitySlot,
                            target = semanticTarget,
                            origin = DOTNET_GENERIC_OWNER_CAPABILITY_DISPATCHER,
                            bridgeName = "<ReifiedGenericInterfaceCapabilityBridge-${plan.interfaceIdentity}-" +
                                    "${plan.slot.name.asString()}-${plan.slotIdentity}>",
                            bridgeTypeTransform = { it },
                            ownerConstraintTypeTransform = { it },
                            specialMethodInfo = specialBridgeMethods.findSpecialWithOverride(
                                plan.slot,
                                includeSelf = true,
                            )?.second,
                        )
                        if (context.configuration.dotNetGenericOwnerRehearsal) {
                            val selection =
                                DotNetLocalGenericOwnerPhysicalInterfaceCapabilityDispatcherSelection(
                                    logicalInterfaceMember = plan.slot.symbol,
                                    implementationMember = plan.target.symbol,
                                    interfaceCapabilityMember = capabilitySlot.symbol,
                                    dispatcher = capabilityBridge.symbol,
                                )
                            check(context.localGenericOwnerPhysicalInterfaceCapabilityDispatcherSelections
                                .none { existing -> existing.dispatcher === capabilityBridge.symbol }) {
                                "Internal .NET backend error: one interface-capability dispatcher " +
                                        "received multiple physical selections"
                            }
                            context.localGenericOwnerPhysicalInterfaceCapabilityDispatcherSelections += selection
                        }
                        nonGenericImplementation?.foreignOverrideProbe?.let { probe ->
                            val directDispatch = DotNetGenericOwnerDirectForeignOverrideDispatch(
                                typedEntry = nonGenericImplementation.typedImplementation,
                                semanticHook = nonGenericImplementation.semanticImplementation,
                                foreignOverrideProbe = probe,
                            )
                            // Interface-typed Kotlin calls enter through the reified interface's
                            // capability. Calls compiled against this open class use its own
                            // producer-published capability. Both must observe one ordinary C#
                            // override of the public typed slot.
                            context.genericOwnerDirectForeignOverrideDispatches[capabilityBridge] = directDispatch
                            nonGenericImplementation.ownedCapabilitySlot?.let { ownedSlot ->
                                val ownedDispatcher = openNonGenericOwnerRelativeDispatchers.getOrPut(plan.target) {
                                    createForwardingBridge(
                                        irClass = nonGenericImplementation.familyOwner,
                                        slot = ownedSlot,
                                        target = nonGenericImplementation.semanticImplementation,
                                        origin = DOTNET_GENERIC_OWNER_CAPABILITY_DISPATCHER,
                                        bridgeName =
                                            "<ReifiedGenericInterfaceOpenOwnerRelativeCapabilityBridge-" +
                                                    "${plan.interfaceIdentity}-${plan.slotIdentity}>",
                                        bridgeTypeTransform = { it },
                                        ownerConstraintTypeTransform = { it },
                                    )
                                }
                                val existingSlot = context.genericOwnerCapabilitySlots.put(plan.target, ownedSlot)
                                check(existingSlot == null || existingSlot == ownedSlot) {
                                    "Internal .NET backend error: an open non-generic owner-relative " +
                                            "family produced multiple owned capability slots"
                                }
                                context.genericOwnerCapabilityDispatchers[plan.target] = ownedDispatcher
                                context.genericOwnerDirectForeignOverrideDispatches[ownedDispatcher] = directDispatch
                            }
                        }
                    }
                }
                val exactInputViewRequired = (
                        context.publishedGenericInterfaceFamilies[interfaceClass]
                            ?: externalDeclarations.publishedGenericInterfaceFamilyOrNull(interfaceClass)
                        )?.requiresExactInputView == true &&
                        plan.slot.dotNetGenericInterfaceMemberView(
                            interfaceClass,
                            isErasedKotlinCarrier,
                        ) == DotNetGenericInterfaceMemberView.EXACT
                if (exactInputViewRequired &&
                    !irClass.inheritsReifiedGenericInterfaceTypedBridge(
                        plan,
                        DotNetGenericInterfaceMemberView.EXACT,
                        externalDeclarations,
                    )
                ) {
                    val exactBridge = createTypedBridge(plan, DotNetGenericInterfaceMemberView.EXACT)
                    if (plan.implementingClass in context.preLoweringDeclarationKeys) {
                        context.genericInterfaceViewBridges += DotNetLoweredGenericInterfaceViewBridge(
                            owner = plan.implementingClass,
                            inheritedMember = plan.slot,
                            implementation = exactBridge,
                            physicalView = DotNetInterfaceDefaultPromotionView.EXACT,
                        )
                    }
                }
                continue
            }
            if (irClass.inheritsGenericInterfaceBridge(plan, externalDeclarations)) continue
            val canonicalBridge = createCanonicalBridge(
                plan,
                isMappedKotlinGenericInterface,
                isErasedKotlinCarrier,
            )
            val typedBridges = plan.typedViews.associateWith { view ->
                createTypedBridge(plan, view)
            }
            // A final bridge forwards through the target's virtual class slot, so a producer-
            // visible complete bundle remains authoritative when inherited from either an
            // interface or a class. Persist class forms only when the pre-lowering KLIB graph gave
            // the owner a cross-module logical identity. Synthetic continuations and adapters have
            // no external consumer and cannot be placed in the physical library index.
            //
            // Omitting an exported class-owned bundle makes a later compilation rebuild MethodImpls
            // on the derived class. Those rows can target an interface inherited only through the
            // external base class, a shape the CLR executes but ILLink cannot map during trimming.
            val recordsProducerVisibleBridgeFamily = plan.implementingClass.isInterface ||
                    plan.implementingClass in context.preLoweringDeclarationKeys
            if (recordsProducerVisibleBridgeFamily) {
                context.genericInterfaceViewBridges += DotNetLoweredGenericInterfaceViewBridge(
                    owner = plan.implementingClass,
                    inheritedMember = plan.slot,
                    implementation = canonicalBridge,
                    physicalView = DotNetInterfaceDefaultPromotionView.CANONICAL,
                )
                for (entry in typedBridges.entries) {
                    context.genericInterfaceViewBridges += DotNetLoweredGenericInterfaceViewBridge(
                        owner = plan.implementingClass,
                        inheritedMember = plan.slot,
                        implementation = entry.value,
                        physicalView = when (entry.key) {
                            DotNetGenericInterfaceMemberView.DECLARED ->
                                DotNetInterfaceDefaultPromotionView.DECLARED
                            DotNetGenericInterfaceMemberView.EXACT ->
                                DotNetInterfaceDefaultPromotionView.EXACT
                        },
                    )
                }
            }
            if (plan.target.origin == DOTNET_GENERIC_INTERFACE_DEFAULT_FORWARDER_TARGET) {
                context.interfaceDefaultClassForwarders.removeAll { forwarder ->
                    forwarder.owner == plan.implementingClass &&
                            forwarder.inheritedMember == plan.slot &&
                            forwarder.implementation == plan.target
                }
                context.interfaceDefaultClassForwarders += DotNetLoweredInterfaceDefaultClassForwarder(
                    owner = plan.implementingClass,
                    inheritedMember = plan.slot,
                    implementation = canonicalBridge,
                    physicalView = DotNetInterfaceDefaultPromotionView.CANONICAL,
                )
                for (entry in typedBridges.entries) {
                    val view = entry.key
                    val bridge = entry.value
                    context.interfaceDefaultClassForwarders += DotNetLoweredInterfaceDefaultClassForwarder(
                        owner = plan.implementingClass,
                        inheritedMember = plan.slot,
                        implementation = bridge,
                        physicalView = when (view) {
                            DotNetGenericInterfaceMemberView.DECLARED ->
                                DotNetInterfaceDefaultPromotionView.DECLARED
                            DotNetGenericInterfaceMemberView.EXACT ->
                                DotNetInterfaceDefaultPromotionView.EXACT
                        },
                    )
                }
            }
        }
        recordLocalGenericOwnerPhysicalClassEdgesAtBridgeSelection(context, irClass)
    }

    /**
     * A non-generic Kotlin implementation has no generic-owner planner from which to obtain the
     * paired object-domain body. Move its one authoritative body to a semantic twin; the natural
     * method remains the typed CLR entry and casts only its own result. A local inherited body
     * keeps that family on its real base owner while each derived interface binding receives its
     * own MethodImpls. A final family calls the twin directly. An open family additionally owns
     * one non-generic capability interface so its semantic hook and ordinary C# typed override
     * remain discoverable across separate compilation.
     */
    private fun createNonGenericOwnerRelativeImplementation(
        plan: BridgePlan,
        localClasses: Set<IrClass>,
        externalDeclarations: DotNetExternalDeclarations,
    ): NonGenericOwnerRelativeImplementation {
        val target = plan.target
        val owner = target.parent as? IrClass
            ?: dotNetUnsupported(
                "owner-relative generic-interface implementation '${target.name}' has no class owner"
            )
        val isInheritedImplementation = owner != plan.implementingClass
        val existingImplementation = nonGenericOwnerRelativeImplementations[target]
        val inheritedOwnerBound = if (isInheritedImplementation) {
            val slotOwner = plan.slot.parent as? IrClass
                ?: error("Internal .NET backend error: owner-relative slot has no class owner")
            plan.slot.dotNetDirectOwnerRelativeMethodBoundsOrNull(slotOwner)
                ?.singleOrNull()
                ?.let(plan.typedSubstitutor::substitute)
        } else {
            null
        }
        val inheritedOwnerBoundMatches = !isInheritedImplementation ||
                (inheritedOwnerBound != null &&
                        if (existingImplementation != null) {
                            existingImplementation.inheritedOwnerBound == inheritedOwnerBound
                        } else {
                            target.typeParameters.singleOrNull()?.superTypes?.singleOrNull() == inheritedOwnerBound
                        })
        if (isInheritedImplementation && owner !in localClasses) {
            return reusePreparedExternalNonGenericOwnerRelativeImplementation(
                plan,
                owner,
                inheritedOwnerBound,
                existingImplementation,
                externalDeclarations,
            )
        }
        // Kotlin IR may retain OPEN on an override declared inside a final class. The owner still
        // closes the physical family, so only a genuinely inheritable owner needs an open target.
        val isFinalFamily = owner.modality == Modality.FINAL
        val isOpenFamily = owner.modality == Modality.OPEN && target.modality == Modality.OPEN
        if (owner.typeParameters.isNotEmpty() || (!isFinalFamily && !isOpenFamily) ||
            target.isFakeOverride || target.body == null ||
            target.typeParameters.size != 1 || target.parameters.size != 2 ||
            (!isInheritedImplementation && target.typeParameters.single().origin !=
                    DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER) ||
            (isInheritedImplementation &&
                    (owner !in localClasses || plan.implementingClass.typeParameters.isNotEmpty() ||
                            !inheritedOwnerBoundMatches))
        ) {
            dotNetUnsupported(
                "owner-relative generic-interface implementation " +
                        "'${plan.implementingClass.name}.${target.name}' requires a directly declared " +
                        "or local inherited final/open non-generic semantic family"
            )
        }
        if (isInheritedImplementation) {
            target.typeParameters.single().origin =
                DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER
        }
        val implementation = nonGenericOwnerRelativeImplementations.getOrPut(target) {
            val logicalRoots = target.overriddenSymbols.map { overridden ->
                context.preLoweringDeclarationKeys[overridden.owner]
                    ?: "${plan.interfaceIdentity}:${plan.slotIdentity}"
            }.distinct().sorted().ifEmpty {
                listOf("${plan.interfaceIdentity}:${plan.slotIdentity}")
            }
            val implementation = owner.addFunction {
                startOffset = target.startOffset
                endOffset = target.endOffset
                origin = DOTNET_REIFIED_GENERIC_INTERFACE_CLOSED_OWNER_RELATIVE_SEMANTIC_IMPLEMENTATION
                name = Name.identifier(
                    dotNetGenericOwnerPhysicalMemberName(
                        target.dotNetIlMethodName(),
                        logicalRoots,
                        DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
                    )
                )
                visibility = if (isOpenFamily) {
                    DescriptorVisibilities.PROTECTED
                } else {
                    DescriptorVisibilities.PRIVATE
                }
                modality = if (isOpenFamily) Modality.OPEN else Modality.FINAL
                returnType = context.irBuiltIns.anyNType
            }.apply {
                parameters += createDispatchReceiverParameterWithClassParent()
                val semanticTypeParameters = copyTypeParametersFrom(target)
                semanticTypeParameters.forEach { parameter ->
                    parameter.origin = DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER
                    parameter.superTypes = listOf(context.irBuiltIns.anyNType)
                }
                val methodSubstitutor = IrTypeSubstitutor(
                    target.typeParameters.zip(semanticTypeParameters).associate { pair ->
                        pair.first.symbol to pair.second.symbol.defaultType
                    },
                    allowEmptySubstitution = true,
                )
                target.parameters.drop(1).forEach { parameter ->
                    addValueParameter(
                        parameter.name.asString(),
                        methodSubstitutor.substitute(parameter.type),
                    )
                }
            }
            implementation.body = target.moveBodyTo(implementation)
            if (isOpenFamily) {
                target.typeParameters.single().superTypes = listOf(context.irBuiltIns.anyNType)
            }
            target.body = context.createIrBuilder(target.symbol).irBlockBody {
                val call = irCall(implementation.symbol, implementation.returnType).apply {
                    arguments[0] = irGet(target.parameters[0])
                    target.typeParameters.forEachIndexed { index, parameter ->
                        typeArguments[index] = parameter.symbol.defaultType
                    }
                    target.parameters.drop(1).forEachIndexed { index, parameter ->
                        arguments[index + 1] = irGet(parameter)
                    }
                }
                +irReturn(irImplicitCast(call, target.returnType))
            }
            val probe = if (isOpenFamily) {
                owner.addFunction {
                    startOffset = target.startOffset
                    endOffset = target.endOffset
                    origin = DOTNET_GENERIC_OWNER_FOREIGN_OVERRIDE_PROBE
                    name = Name.identifier(
                        dotNetGenericOwnerPhysicalForeignOverrideProbeName(
                            target.dotNetIlMethodName(),
                            logicalRoots,
                        )
                    )
                    visibility = DescriptorVisibilities.PROTECTED
                    modality = Modality.OPEN
                    returnType = context.irBuiltIns.booleanType
                }.apply {
                    parameters += createDispatchReceiverParameterWithClassParent()
                    copyTypeParametersFrom(target)
                    body = context.createIrBuilder(symbol).irBlockBody {
                        +irReturn(irCall(context.irBuiltIns.eqeqeqSymbol).apply {
                            arguments[0] = irGet(parameters[0])
                            arguments[1] = irGet(parameters[0])
                        })
                    }
                }.also { foreignOverrideProbe ->
                    context.genericOwnerForeignOverrideProbeTargets[foreignOverrideProbe] = target
                }
            } else {
                null
            }
            val ownedCapabilitySlot = if (isOpenFamily) {
                createOpenNonGenericOwnerRelativeCapability(plan, logicalRoots)
            } else {
                null
            }
            context.genericOwnerSemanticHooks[target] = implementation
            context.genericOwnerCapabilityDeclarations += implementation
            context.genericOwnerCapabilityDeclarations += implementation.parameters.drop(1)
            NonGenericOwnerRelativeImplementation(
                owner,
                inheritedOwnerBound,
                target,
                implementation,
                probe,
                ownedCapabilitySlot,
            )
        }
        ensureNonGenericOwnerRelativeNaturalBridge(
            plan,
            if (isOpenFamily) target else implementation.semanticImplementation,
        )
        return implementation
    }

    /**
     * Reuses an open non-generic family whose semantic hook/probe were published by an earlier
     * artifact. The consumer emits only its own natural and interface-capability MethodImpls;
     * every callable family member remains a MethodRef to the producer base TypeDef.
     */
    private fun reusePreparedExternalNonGenericOwnerRelativeImplementation(
        plan: BridgePlan,
        owner: IrClass,
        inheritedOwnerBound: IrType?,
        existingImplementation: NonGenericOwnerRelativeImplementation?,
        externalDeclarations: DotNetExternalDeclarations,
    ): NonGenericOwnerRelativeImplementation {
        val target = plan.target
        val existingMatches = existingImplementation == null ||
                (existingImplementation.familyOwner == owner &&
                        existingImplementation.inheritedOwnerBound == inheritedOwnerBound &&
                        existingImplementation.ownedCapabilitySlot == null)
        val binding = externalDeclarations.genericOwnerMemberFamilyOrNull(target)
        val family = binding?.family
        val typedEntryBinding = binding?.let { preparedBinding ->
            externalDeclarations.genericOwnerTypedEntryPhysicalSlotOrNull(
                target,
                preparedBinding,
            )
        }
        val semanticOwnerPath = family?.semanticHookOwnerPath
        val semanticMethodName = family?.semanticHookMethodName
        val probeMethodName = family?.foreignOverrideProbeMethodName
        if (!existingMatches || !externalDeclarations.hasClass(owner) ||
            owner.typeParameters.isNotEmpty() || owner.modality != Modality.OPEN ||
            plan.implementingClass.typeParameters.isNotEmpty() ||
            target.isFakeOverride || target.body != null || target.modality != Modality.OPEN ||
            target.typeParameters.size != 1 || target.parameters.size != 2 ||
            inheritedOwnerBound == null ||
            target.typeParameters.single().superTypes.singleOrNull() != inheritedOwnerBound ||
            binding == null || typedEntryBinding == null || semanticOwnerPath == null ||
            semanticMethodName == null ||
            probeMethodName == null
        ) {
            dotNetUnsupported(
                "owner-relative generic-interface implementation " +
                        "'${plan.implementingClass.name}.${target.name}' requires a producer-prepared " +
                        "open non-generic semantic family"
            )
        }
        val implementation = existingImplementation ?: run {
            fun buildExternalFamilyMember(
                name: String,
                returnType: IrType,
                physicalBinding: DotNetBoundGenericOwnerPhysicalSlot,
                includeValueParameters: Boolean,
            ): IrSimpleFunction = context.irFactory.buildFun {
                startOffset = target.startOffset
                endOffset = target.endOffset
                origin =
                    DOTNET_REIFIED_GENERIC_INTERFACE_EXTERNAL_OWNER_RELATIVE_FAMILY_MEMBER
                this.name = Name.special(name)
                visibility = DescriptorVisibilities.PROTECTED
                modality = Modality.OPEN
                this.returnType = returnType
            }.apply {
                parent = owner
                parameters += createDispatchReceiverParameterWithClassParent()
                val copiedTypeParameters = copyTypeParametersFrom(target)
                copiedTypeParameters.forEach { parameter ->
                    parameter.origin = DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER
                    parameter.superTypes = listOf(context.irBuiltIns.anyNType)
                }
                if (includeValueParameters) {
                    val substitutor = IrTypeSubstitutor(
                        target.typeParameters.zip(copiedTypeParameters).associate { pair ->
                            pair.first.symbol to pair.second.symbol.defaultType
                        },
                        allowEmptySubstitution = true,
                    )
                    target.parameters.filter { parameter ->
                        parameter.kind == IrParameterKind.Regular
                    }.forEach { parameter ->
                        addValueParameter(
                            parameter.name.asString(),
                            substitutor.substitute(parameter.type),
                        )
                    }
                }
                context.externalGenericOwnerPhysicalSlots[this] =
                    physicalBinding
            }
            val typed = buildExternalFamilyMember(
                "<ExternalPreparedOwnerRelativeTyped-${nonGenericOwnerRelativeImplementations.size}>",
                target.returnType,
                typedEntryBinding,
                includeValueParameters = true,
            )
            val semantic = buildExternalFamilyMember(
                "<ExternalPreparedOwnerRelativeSemantic-${nonGenericOwnerRelativeImplementations.size}>",
                context.irBuiltIns.anyNType,
                DotNetBoundGenericOwnerPhysicalSlot(
                    binding.library,
                    family,
                    semanticOwnerPath,
                    semanticMethodName,
                ),
                includeValueParameters = true,
            )
            val probe = buildExternalFamilyMember(
                "<ExternalPreparedOwnerRelativeProbe-${nonGenericOwnerRelativeImplementations.size}>",
                context.irBuiltIns.booleanType,
                DotNetBoundGenericOwnerPhysicalSlot(
                    binding.library,
                    family,
                    semanticOwnerPath,
                    probeMethodName,
                ),
                includeValueParameters = false,
            )
            context.genericOwnerSemanticHooks[target] = semantic
            context.genericOwnerCapabilityDeclarations += semantic
            context.genericOwnerCapabilityDeclarations += semantic.parameters.drop(1)
            NonGenericOwnerRelativeImplementation(
                owner,
                inheritedOwnerBound,
                typed,
                semantic,
                probe,
                ownedCapabilitySlot = null,
            ).also { created ->
                nonGenericOwnerRelativeImplementations[target] = created
            }
        }
        ensureNonGenericOwnerRelativeNaturalBridge(
            plan,
            implementation.typedImplementation,
        )
        return implementation
    }

    private fun ensureNonGenericOwnerRelativeNaturalBridge(
        plan: BridgePlan,
        target: IrSimpleFunction,
    ) {
        val alreadyBindsNaturalSlot = plan.implementingClass.declarations
            .filterIsInstance<IrSimpleFunction>().any { function ->
                function.origin ==
                        DOTNET_REIFIED_GENERIC_INTERFACE_CLOSED_OWNER_RELATIVE_NATURAL_BRIDGE &&
                        function.overriddenSymbols.singleOrNull() == plan.slot.symbol
            }
        if (alreadyBindsNaturalSlot) return
        createForwardingBridge(
            irClass = plan.implementingClass,
            slot = plan.slot,
            target = target,
            origin = DOTNET_REIFIED_GENERIC_INTERFACE_CLOSED_OWNER_RELATIVE_NATURAL_BRIDGE,
            bridgeName = "<ReifiedGenericInterfaceClosedOwnerRelativeNaturalBridge-" +
                    "${plan.interfaceIdentity}-${plan.slotIdentity}>",
            bridgeTypeTransform = plan.typedSubstitutor::substitute,
            ownerConstraintTypeTransform = plan.typedSubstitutor::substitute,
        )
    }

    /**
     * Publishes the class-relative semantic slot needed by a later Kotlin consumer of an open
     * non-generic implementation. The class continues to implement the reified interface's own
     * capability independently; keeping the two slots explicit avoids claiming that a new CLR
     * interface MethodDef also implements an inherited MethodDef with the same signature.
     */
    private fun createOpenNonGenericOwnerRelativeCapability(
        plan: BridgePlan,
        logicalRoots: List<String>,
    ): IrSimpleFunction {
        val target = plan.target
        val owner = target.parent as? IrClass
            ?: error("Internal .NET backend error: open non-generic capability target has no class owner")
        val file = checkNotNull(owner.fileOrNull) {
            "Internal .NET backend error: open non-generic capability owner '${owner.name}' has no file"
        }
        val ownerIdentity = context.preLoweringDeclarationKeys[owner]
            ?: owner.fqNameWhenAvailable?.asString()
            ?: owner.name.asString()
        val suffix = Integer.toUnsignedString(ownerIdentity.hashCode(), 16)
        val capability = openNonGenericOwnerRelativeCapabilities.getOrPut(owner) {
            context.irFactory.buildClass {
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
                file.declarations += this
                owner.superTypes += symbol.defaultType
            }
        }

        return capability.addFunction {
            startOffset = target.startOffset
            endOffset = target.endOffset
            origin = DOTNET_GENERIC_OWNER_CAPABILITY_SLOT
            name = Name.identifier(
                dotNetGenericOwnerPhysicalMemberName(
                    target.dotNetIlMethodName(),
                    logicalRoots,
                    DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
                )
            )
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.ABSTRACT
            returnType = context.irBuiltIns.anyNType
        }.apply slot@{
            parameters += createDispatchReceiverParameterWithClassParent()
            val copiedTypeParameters = copyTypeParametersFrom(target)
            copiedTypeParameters.forEach { parameter ->
                parameter.superTypes = listOf(context.irBuiltIns.anyNType)
                parameter.origin = DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER
            }
            val substitutor = IrTypeSubstitutor(
                target.typeParameters.zip(copiedTypeParameters).associate { pair ->
                    pair.first.symbol to pair.second.symbol.defaultType
                },
                allowEmptySubstitution = true,
            )
            target.parameters.filter { parameter -> parameter.kind == IrParameterKind.Regular }
                .forEach { parameter ->
                    addValueParameter(parameter.name.asString(), substitutor.substitute(parameter.type))
                }
            context.genericOwnerCapabilityDeclarations += this
            context.genericOwnerCapabilityDeclarations += parameters
        }
    }

    /**
     * A natural `I<T>` must remain independently implementable by foreign CLR source. Kotlin-
     * emitted implementations still carry the optional semantic interface on the same object,
     * but do so directly rather than inheriting that obligation through the public `I<T>`.
     */
    private fun IrClass.addReifiedGenericInterfaceCapability(
        interfaceClass: IrClass,
        externalDeclarations: DotNetExternalDeclarations,
    ) {
        context.genericOwnerCapabilityInterfaces[interfaceClass]?.let { capability ->
            if (superTypes.none { type ->
                    (type as? IrSimpleType)?.classifier == capability.symbol
                }
            ) {
                superTypes += capability.symbol.defaultType
            }
            return
        }
        val provider = context.externalReifiedGenericInterfaceCapabilityProviders[interfaceClass]
            ?: interfaceClass.takeIf(externalDeclarations::hasReifiedGenericInterface)
            ?: error(
                "Internal .NET backend error: reified interface '${interfaceClass.name}' " +
                        "has no local or producer-recorded semantic capability"
            )
        context.externalGenericOwnerCapabilitySupertypeProviders[this] =
            (context.externalGenericOwnerCapabilitySupertypeProviders[this].orEmpty() + provider)
                .distinct()
    }

    private fun IrClass.inheritsReifiedGenericInterfaceCapabilityBridge(
        capabilitySlot: IrSimpleFunction,
    ): Boolean {
        val visited = hashSetOf<IrClass>()
        var current = dotNetBaseClassOrNull()
        while (current != null && visited.add(current)) {
            if (current.declarations.filterIsInstance<IrSimpleFunction>().any { function ->
                    function.origin == DOTNET_GENERIC_OWNER_CAPABILITY_DISPATCHER &&
                            function.overriddenSymbols.singleOrNull() == capabilitySlot.symbol
                }
            ) {
                return true
            }
            current = current.dotNetBaseClassOrNull()
        }
        return false
    }

    private fun IrClass.inheritsReifiedGenericInterfaceTypedBridge(
        plan: BridgePlan,
        view: DotNetGenericInterfaceMemberView,
        externalDeclarations: DotNetExternalDeclarations,
    ): Boolean {
        val physicalView = when (view) {
            DotNetGenericInterfaceMemberView.DECLARED -> DotNetInterfaceDefaultPromotionView.DECLARED
            DotNetGenericInterfaceMemberView.EXACT -> DotNetInterfaceDefaultPromotionView.EXACT
        }
        val visited = hashSetOf<IrClass>()
        val pending = mutableListOf<IrClass>()
        fun enqueueSupertypes(owner: IrClass) {
            owner.superTypes.mapNotNullTo(pending) { superType ->
                ((superType as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
            }
        }
        enqueueSupertypes(this)
        while (pending.isNotEmpty()) {
            val current = pending.removeAt(pending.lastIndex)
            if (!visited.add(current)) continue
            if (current.declarations.filterIsInstance<IrSimpleFunction>().any { bridge ->
                    bridge.origin.dotNetGenericInterfaceBridgeMemberViewOrNull == view &&
                            plan.slot.symbol in bridge.overriddenSymbols
                } || externalDeclarations.genericInterfaceViewBridgeOrNull(
                    current,
                    plan.slot,
                    physicalView,
                ) != null
            ) {
                return true
            }
            enqueueSupertypes(current)
        }
        return false
    }

    /** An inherited complete bridge bundle remains valid because every adapter dispatches virtually. */
    private fun IrClass.inheritsGenericInterfaceBridge(
        plan: BridgePlan,
        externalDeclarations: DotNetExternalDeclarations,
    ): Boolean {
        val requiredViews = listOf(DotNetInterfaceDefaultPromotionView.CANONICAL) +
                plan.typedViews.map { view ->
                    when (view) {
                        DotNetGenericInterfaceMemberView.DECLARED -> DotNetInterfaceDefaultPromotionView.DECLARED
                        DotNetGenericInterfaceMemberView.EXACT -> DotNetInterfaceDefaultPromotionView.EXACT
                    }
                }
        fun IrSimpleFunction.implements(view: DotNetInterfaceDefaultPromotionView): Boolean {
            if (plan.slot.symbol !in overriddenSymbols) return false
            return when (view) {
                DotNetInterfaceDefaultPromotionView.CANONICAL ->
                    origin == DOTNET_GENERIC_INTERFACE_CANONICAL_BRIDGE
                DotNetInterfaceDefaultPromotionView.DECLARED ->
                    origin.dotNetGenericInterfaceBridgeMemberViewOrNull == DotNetGenericInterfaceMemberView.DECLARED
                DotNetInterfaceDefaultPromotionView.EXACT ->
                    origin.dotNetGenericInterfaceBridgeMemberViewOrNull == DotNetGenericInterfaceMemberView.EXACT
            }
        }

        val visited = hashSetOf<IrClass>()
        val pending = mutableListOf<IrClass>()
        fun enqueueSupertypes(owner: IrClass) {
            owner.superTypes.mapNotNullTo(pending) { superType ->
                ((superType as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
            }
        }
        enqueueSupertypes(this)
        while (pending.isNotEmpty()) {
            val current = pending.removeAt(pending.lastIndex)
            if (!visited.add(current)) continue
            val localBridges = current.declarations.filterIsInstance<IrSimpleFunction>()
            if (requiredViews.all { view ->
                    localBridges.any { bridge -> bridge.implements(view) } ||
                            externalDeclarations.genericInterfaceViewBridgeOrNull(current, plan.slot, view) != null
                }
            ) {
                return true
            }
            enqueueSupertypes(current)
        }
        return false
    }

    /** The declaration-semantic type owned by the canonical interface MethodDef. */
    private fun canonicalBridgeTypeTransform(
        plan: BridgePlan,
        isErasedKotlinCarrier: (IrClass) -> Boolean,
    ): (IrType) -> IrType {
        val substitution = plan.interfaceClass.typeParameters.associate { typeParameter ->
            typeParameter.symbol to context.irBuiltIns.anyNType
        }
        val canonicalSubstitutor = IrTypeSubstitutor(substitution, allowEmptySubstitution = true)
        val canonicalObjectParameterTypes =
            DotNetRuntimeTypes.genericInterfaceCanonicalObjectParameterIndices(plan.slot)
                .mapNotNull { index ->
                    plan.slot.parameters
                        .filter { parameter -> parameter.kind == IrParameterKind.Regular }
                        .getOrNull(index)
                        ?.type
                }
        return canonicalType@{ type ->
            if (canonicalObjectParameterTypes.any { parameterType -> parameterType == type }) {
                return@canonicalType context.irBuiltIns.anyNType
            }
            if (!type.referencesTypeParameterOf(plan.interfaceClass)) return@canonicalType type
            val simpleType = type as? IrSimpleType
                ?: return@canonicalType context.irBuiltIns.anyNType
            val directParameter = simpleType.classifier as? IrTypeParameterSymbol
            if (directParameter?.owner?.parent == plan.interfaceClass) {
                return@canonicalType context.irBuiltIns.anyNType
            }
            val carrier = (simpleType.classifier as? IrClassSymbol)?.owner
            if (carrier?.let(isErasedKotlinCarrier) == true ||
                carrier?.let(DotNetRuntimeTypes::usesDeclaredViewByDefaultInRehearsal) == true
            ) {
                // Both carriers have one declaration-semantic identity. Substitute the interface
                // parameter while retaining the nested classifier; the mapper selects its
                // canonical identity rather than fabricating one natural closed construction.
                canonicalSubstitutor.substitute(type)
            } else {
                // A reified CLR carrier or array has no one honest canonical construction.
                context.irBuiltIns.anyNType
            }
        }
    }

    /**
     * Gives a closed non-generic implementation one object-input body only when its canonical
     * interface slot is broader than its natural source member. A physically final class makes
     * the copied body non-virtual and prevents a hidden compiler entry from splitting an override
     * family. Fixed wrong-shape members keep their upstream constant barrier instead.
     */
    private fun prepareClosedNonGenericSemanticInputEntry(
        plan: BridgePlan,
        isMappedKotlinGenericInterface: (IrClass) -> Boolean,
        isErasedKotlinCarrier: (IrClass) -> Boolean,
    ): IrSimpleFunction? {
        if (!context.configuration.dotNetGenericOwnerRehearsal) return null
        context.genericOwnerFunctionInputEntries[plan.target]?.let { return it }
        val target = plan.target
        val owner = target.parent as? IrClass ?: return null
        if (owner !== plan.implementingClass || owner.isInterface || owner.typeParameters.isNotEmpty() ||
            owner.modality != Modality.FINAL || target.body == null || target.isFakeOverride ||
            target.isSuspend || target.correspondingPropertySymbol != null ||
            target.typeParameters.isNotEmpty() || plan.slot.typeParameters.isNotEmpty()
        ) {
            return null
        }
        if (specialBridgeMethods.findSpecialWithOverride(target, includeSelf = true)
                ?.second?.argumentsToCheck?.let { count -> count > 0 } == true
        ) {
            return null
        }
        val slotParameters = plan.slot.parameters.filter { parameter ->
            parameter.kind == IrParameterKind.Regular
        }
        val targetParameters = target.parameters.filter { parameter ->
            parameter.kind == IrParameterKind.Regular
        }
        val canonicalType = canonicalBridgeTypeTransform(plan, isErasedKotlinCarrier)
        if (slotParameters.size != targetParameters.size || targetParameters.isEmpty() ||
            targetParameters.any { parameter ->
                parameter.defaultValue != null || parameter.varargElementType != null
            } || canonicalType(plan.slot.returnType) != target.returnType
        ) {
            return null
        }
        fun IrType.containsMappedInterface(): Boolean {
            val simpleType = this as? IrSimpleType ?: return false
            val typeOwner = (simpleType.classifier as? IrClassSymbol)?.owner
            return typeOwner?.let(isMappedKotlinGenericInterface) == true ||
                    simpleType.arguments.any { argument ->
                        (argument as? IrTypeProjection)?.type?.containsMappedInterface() == true
                    }
        }
        val mismatches = slotParameters.zip(targetParameters).mapNotNull { pair ->
            pair.second.takeIf { targetParameter ->
                canonicalType(pair.first.type) !=
                        targetParameter.type && targetParameter.type.containsMappedInterface()
            }
        }
        val targetParameter = mismatches.singleOrNull() ?: return null
        val parameterIndex = target.parameters.indexOf(targetParameter)
        if (parameterIndex < 0) return null
        val logicalKey = context.preLoweringDeclarationKeys[target]
            ?: "${plan.interfaceIdentity}:${plan.slotIdentity}:closed-semantic-input"
        return materializeLocalGenericOwnerFunctionInputEntry(
            context,
            target,
            setOf(parameterIndex),
            logicalKey,
        )
    }

    private fun createCanonicalBridge(
        plan: BridgePlan,
        isMappedKotlinGenericInterface: (IrClass) -> Boolean,
        isErasedKotlinCarrier: (IrClass) -> Boolean,
    ): IrSimpleFunction {
        // A generic-class owner may already have split the authoritative Kotlin body into an
        // object-domain semantic hook. The canonical interface is precisely that semantic view:
        // forwarding it back through the typed source entry would reintroduce an early
        // `Collection<object> -> Collection<!T>` cast for widened nested inputs. Exact/declared
        // bridges below continue to target the natural source member.
        val canonicalTarget = context.genericOwnerSemanticHooks[plan.target]
            ?: context.genericOwnerFunctionInputEntries[plan.target]
            ?: plan.target
        val canonicalType = canonicalBridgeTypeTransform(plan, isErasedKotlinCarrier)

        return createForwardingBridge(
            irClass = plan.implementingClass,
            slot = plan.slot,
            target = canonicalTarget,
            origin = DOTNET_GENERIC_INTERFACE_CANONICAL_BRIDGE,
            bridgeName = "<GenericInterfaceCanonicalBridge-${plan.interfaceIdentity}-" +
                    "${plan.slot.name.asString()}-${plan.slotIdentity}>",
            bridgeTypeTransform = canonicalType,
            ownerConstraintTypeTransform = plan.typedSubstitutor::substitute,
            isErasedKotlinCarrier = isErasedKotlinCarrier,
            specialMethodInfo = specialBridgeMethods.findSpecialWithOverride(
                plan.slot,
                includeSelf = true,
            )?.second,
        )
    }

    /**
     * Explicitly binds the source implementation to its declared or exact typed CLR slot.
     * Relying on implicit interface mapping is unsound for Kotlin covariant return refinement:
     * `P<Any>.get(): object` may be implemented in source by `get(): String` or `get(): Int`, and
     * CLR slot identity includes the return type. The forwarding bridge widens/boxes that result
     * while retaining the user's exact source member as the implementation body.
     */
    private fun createTypedBridge(
        plan: BridgePlan,
        memberView: DotNetGenericInterfaceMemberView,
    ): IrSimpleFunction {
        DotNetRuntimeTypes.genericInterfaceRelativeGenericInputParameterIndex(plan.slot)
            ?.let { inputIndex ->
                return createRelativeGenericInputTypedBridge(plan, memberView, inputIndex)
            }
        val viewName = memberView.name.lowercase()
            .replaceFirstChar(Char::uppercaseChar)
        return createForwardingBridge(
            irClass = plan.implementingClass,
            slot = plan.slot,
            target = plan.target,
            origin = when (memberView) {
                DotNetGenericInterfaceMemberView.DECLARED -> DOTNET_GENERIC_INTERFACE_DECLARED_BRIDGE
                DotNetGenericInterfaceMemberView.EXACT -> DOTNET_GENERIC_INTERFACE_EXACT_BRIDGE
            },
            bridgeName = "<GenericInterface${viewName}Bridge-${plan.interfaceIdentity}-" +
                    "${plan.slot.name.asString()}-${plan.slotIdentity}>",
            bridgeTypeTransform = plan.typedSubstitutor::substitute,
            ownerConstraintTypeTransform = plan.typedSubstitutor::substitute,
        )
    }

    /**
     * Materializes the CLR-only `<U : T>(..., Collection<U>, ...)` slot used by an invariant
     * natural interface for one nested covariant Kotlin input. The logical Kotlin member
     * deliberately has no method type parameter. Its producer-proven semantic hook owns the one
     * body which can accept a value-type widening such as
     * `Collection<Int> -> Collection<Any?>`; the physical bridge retains the original collection
     * object and every independent argument and adds only the CLR method-parameter shape.
     */
    private fun createRelativeGenericInputTypedBridge(
        plan: BridgePlan,
        memberView: DotNetGenericInterfaceMemberView,
        inputIndex: Int,
    ): IrSimpleFunction {
        if (memberView != DotNetGenericInterfaceMemberView.DECLARED ||
            plan.slot.typeParameters.isNotEmpty() ||
            plan.interfaceClass.typeParameters.size != 1
        ) {
            dotNetUnsupported(
                "relative collection input '${plan.slot.name}' requires one invariant declared owner"
            )
        }
        val slotParameters = plan.slot.parameters.filter { parameter ->
            parameter.kind == IrParameterKind.Regular
        }
        val sourceParameter = slotParameters.getOrNull(inputIndex)
            ?: dotNetUnsupported(
                "relative collection input '${plan.slot.name}' has no selected parameter"
            )
        val semanticTarget = context.genericOwnerSemanticHooks[plan.target]
            ?: dotNetUnsupported(
                "relative collection input '${plan.implementingClass.name}.${plan.target.name}' " +
                        "requires a producer-proven semantic hook"
            )
        val semanticParameters = semanticTarget.parameters.filter { parameter ->
            parameter.kind == IrParameterKind.Regular
        }
        if (semanticParameters.size != slotParameters.size) {
            dotNetUnsupported(
                "relative collection input '${plan.implementingClass.name}.${plan.target.name}' " +
                        "changed its semantic parameter count"
            )
        }
        val semanticParameter = semanticParameters.getOrNull(inputIndex)
            ?.takeIf { parameter -> parameter.type.isNullableAny() }
            ?: dotNetUnsupported(
                "relative collection input '${plan.implementingClass.name}.${plan.target.name}' " +
                        "does not have an object-domain semantic input at the selected position"
            )
        slotParameters.forEachIndexed { index, parameter ->
            if (index != inputIndex &&
                semanticParameters[index].type != plan.typedSubstitutor.substitute(parameter.type)
            ) {
                dotNetUnsupported(
                    "relative collection input '${plan.implementingClass.name}.${plan.target.name}' " +
                            "has an independently unrepresentable parameter"
                )
            }
        }
        val bridgeKey = RelativeGenericInputBridgeKey(
            plan.implementingClass,
            plan.target,
            inputIndex,
        )
        relativeGenericInputTypedBridges[bridgeKey]?.let { existing ->
            val existingRelativeParameter = existing.typeParameters.singleOrNull()
                ?: error("Internal .NET backend error: coalesced relative input bridge lost U")
            val candidateBound = plan.typedSubstitutor.substitute(
                plan.interfaceClass.typeParameters.single().symbol.defaultType
            )
            val existingBound = existingRelativeParameter.superTypes.singleOrNull()
            val relativeSubstitutor = IrTypeSubstitutor(
                mapOf(
                    plan.interfaceClass.typeParameters.single().symbol to
                            existingRelativeParameter.symbol.defaultType
                ),
                allowEmptySubstitution = false,
            )
            val candidateParameterTypes = slotParameters.mapIndexed { index, parameter ->
                if (index == inputIndex) relativeSubstitutor.substitute(parameter.type)
                else plan.typedSubstitutor.substitute(parameter.type)
            }
            val existingParameterTypes = existing.parameters
                .filter { parameter -> parameter.kind == IrParameterKind.Regular }
                .map { parameter -> parameter.type }
            val candidateReturnType = plan.typedSubstitutor.substitute(plan.slot.returnType)
            if (existingBound != candidateBound ||
                existingParameterTypes != candidateParameterTypes ||
                existing.returnType != candidateReturnType
            ) {
                dotNetUnsupported(
                    "relative collection input '${plan.implementingClass.name}.${plan.target.name}' " +
                            "has inherited natural slots with different physical shapes"
                )
            }
            if (plan.slot.symbol !in existing.overriddenSymbols) {
                existing.overriddenSymbols += plan.slot.symbol
            }
            return existing
        }
        val viewName = memberView.name.lowercase().replaceFirstChar(Char::uppercaseChar)
        return plan.implementingClass.addFunction {
            startOffset = plan.target.startOffset
            endOffset = plan.target.endOffset
            origin = DOTNET_GENERIC_INTERFACE_DECLARED_RELATIVE_GENERIC_INPUT_BRIDGE
            name = Name.special(
                "<GenericInterface${viewName}RelativeGenericInputBridge-${plan.interfaceIdentity}-" +
                        "${plan.slot.name.asString()}-${plan.slotIdentity}>"
            )
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            returnType = plan.typedSubstitutor.substitute(plan.slot.returnType)
        }.apply bridge@{
            overriddenSymbols = listOf(plan.slot.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            val relativeParameter = addTypeParameter {
                name = Name.identifier("U")
                variance = Variance.INVARIANT
                superTypes += plan.typedSubstitutor.substitute(
                    plan.interfaceClass.typeParameters.single().symbol.defaultType
                )
            }
            val relativeSubstitutor = IrTypeSubstitutor(
                mapOf(
                    plan.interfaceClass.typeParameters.single().symbol to
                            relativeParameter.symbol.defaultType
                ),
                allowEmptySubstitution = false,
            )
            slotParameters.forEachIndexed { index, parameter ->
                addValueParameter(
                    parameter.name.asString(),
                    if (index == inputIndex) {
                        relativeSubstitutor.substitute(sourceParameter.type)
                    } else {
                        plan.typedSubstitutor.substitute(parameter.type)
                    },
                )
            }
            body = context.createIrBuilder(symbol).irBlockBody {
                val call = irCall(semanticTarget.symbol, semanticTarget.returnType).apply {
                    arguments[0] = irGet(this@bridge.parameters[0])
                    this@bridge.parameters.drop(1).forEachIndexed { index, parameter ->
                        arguments[index + 1] = if (index == inputIndex) {
                            irImplicitCast(irGet(parameter), semanticParameter.type)
                        } else {
                            irGet(parameter)
                        }
                    }
                }
                +irReturn(
                    if (call.type == this@bridge.returnType) call
                    else irImplicitCast(call, this@bridge.returnType)
                )
            }
        }.also { bridge -> relativeGenericInputTypedBridges[bridgeKey] = bridge }
    }

    /**
     * A default body observes `R : T` through the logical interface view. A semantic call may
     * widen that view independently of the implementing class's natural CLR construction, so
     * routing through the class target would close the helper at the narrower physical `T` and
     * cast `R` too early. Invoke the same helper body with semantic owner arguments instead.
     */
    private fun createOwnerRelativeDefaultCapabilityBridge(
        plan: BridgePlan,
        slot: IrSimpleFunction,
        helper: IrSimpleFunction,
    ): IrSimpleFunction {
        val slotParameters = slot.parameters.dropWhile { it.kind == IrParameterKind.DispatchReceiver }
        return plan.implementingClass.addFunction {
            startOffset = plan.target.startOffset
            endOffset = plan.target.endOffset
            origin = DOTNET_GENERIC_OWNER_CAPABILITY_DISPATCHER
            name = Name.special(
                "<ReifiedGenericInterfaceOwnerRelativeDefaultCapabilityBridge-${plan.interfaceIdentity}-" +
                        "${plan.slot.name.asString()}-${plan.slotIdentity}>"
            )
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            returnType = slot.returnType
        }.apply bridge@{
            overriddenSymbols = listOf(slot.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            val bridgeTypeParameters = copyTypeParametersFrom(slot)
            val methodSubstitution = slot.typeParameters.zip(bridgeTypeParameters).associate { pair ->
                pair.first.symbol to pair.second.symbol.defaultType
            }
            val methodSubstitutor = IrTypeSubstitutor(methodSubstitution, allowEmptySubstitution = true)
            bridgeTypeParameters.forEachIndexed { index, parameter ->
                parameter.superTypes = slot.typeParameters[index].superTypes
                    .map(methodSubstitutor::substitute)
            }
            returnType = methodSubstitutor.substitute(slot.returnType)
            slotParameters.forEach { parameter ->
                addValueParameter(parameter.name.asString(), methodSubstitutor.substitute(parameter.type))
            }
            body = context.createIrBuilder(symbol).irBlockBody {
                check(
                    helper.typeParameters.size ==
                            plan.interfaceClass.typeParameters.size + bridgeTypeParameters.size
                ) {
                    "Internal .NET backend error: owner-relative default helper arity mismatch"
                }
                val call = irCall(helper.symbol, this@bridge.returnType).apply {
                    plan.interfaceClass.typeParameters.indices.forEach { index ->
                        typeArguments[index] = context.irBuiltIns.anyNType
                    }
                    bridgeTypeParameters.forEachIndexed { index, parameter ->
                        typeArguments[plan.interfaceClass.typeParameters.size + index] =
                            parameter.symbol.defaultType
                    }
                    arguments[0] = irGet(this@bridge.parameters[0])
                    this@bridge.parameters.drop(1).forEachIndexed { index, parameter ->
                        arguments[index + 1] = irGet(parameter)
                    }
                }
                +irReturn(call)
            }
        }
    }

    private fun createForwardingBridge(
        irClass: IrClass,
        slot: IrSimpleFunction,
        target: IrSimpleFunction,
        origin: IrDeclarationOrigin,
        bridgeName: String,
        bridgeTypeTransform: (IrType) -> IrType,
        ownerConstraintTypeTransform: (IrType) -> IrType,
        isErasedKotlinCarrier: (IrClass) -> Boolean = { false },
        specialMethodInfo: SpecialMethodWithDefaultInfo? = null,
    ): IrSimpleFunction {
        val targetParameters = target.parameters.dropWhile { it.kind == IrParameterKind.DispatchReceiver }
        val slotParameters = slot.parameters.dropWhile { it.kind == IrParameterKind.DispatchReceiver }
        if (slotParameters.size != targetParameters.size) {
            error("Internal .NET backend error: generic interface bridge parameter count mismatch")
        }
        return irClass.addFunction {
            startOffset = target.startOffset
            endOffset = target.endOffset
            this.origin = origin
            name = Name.special(bridgeName)
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            returnType = context.irBuiltIns.anyNType
        }.apply bridge@{
            overriddenSymbols = listOf(slot.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            val bridgeTypeParameters = copyTypeParametersFrom(slot)
            val methodSubstitution = slot.typeParameters.zip(bridgeTypeParameters).associate { pair ->
                pair.first.symbol to pair.second.symbol.defaultType
            }
            val methodSubstitutor = IrTypeSubstitutor(methodSubstitution, allowEmptySubstitution = true)
            val slotOwner = slot.parent as? IrClass
                ?: error("Internal .NET backend error: generic interface bridge slot has no class owner")
            bridgeTypeParameters.forEachIndexed { index, parameter ->
                parameter.superTypes = slot.typeParameters[index].superTypes
                    .filterNot { it.isDotNetOwnerDependentConstraint(slotOwner) }
                    .map { bound -> methodSubstitutor.substitute(bridgeTypeTransform(bound)) }
                    .ifEmpty { listOf(context.irBuiltIns.anyNType) }
            }
            val ownerBoundMethodArguments =
                slot.dotNetDirectOwnerRelativeMethodBoundsOrNull(
                    slotOwner,
                    isErasedKotlinCarrier,
                )
                    ?.map { bound -> bound?.let(ownerConstraintTypeTransform) }
                    ?: dotNetUnsupported(
                        "generic interface member '${slot.name.asString()}' requires an " +
                                "owner-relative generic adapter beyond direct method-parameter uses"
                    )
            fun bridgeType(type: IrType): IrType =
                methodSubstitutor.substitute(bridgeTypeTransform(type))

            returnType = bridgeType(slot.returnType)
            for (slotParameter in slotParameters) {
                addValueParameter(slotParameter.name.asString(), bridgeType(slotParameter.type))
            }
            body = context.createIrBuilder(symbol).irBlockBody {
                if (target.typeParameters.size != bridgeTypeParameters.size) {
                    error("Internal .NET backend error: generic interface bridge method-arity mismatch")
                }
                val targetMethodArguments = bridgeTypeParameters.mapIndexed { index, parameter ->
                    val targetParameter = target.typeParameters[index]
                    val targetRetainsPhysicalBound =
                        targetParameter.origin !=
                                DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER &&
                                targetParameter.superTypes.any { bound -> !bound.isNullableAny() }
                    ownerBoundMethodArguments[index]
                        ?.takeIf { targetRetainsPhysicalBound }
                        ?: parameter.symbol.defaultType
                }
                val hasOwnerBoundMethodArguments = targetMethodArguments.indices.any { index ->
                    targetMethodArguments[index] != bridgeTypeParameters[index].symbol.defaultType
                }
                val targetMethodSubstitution = target.typeParameters.zip(targetMethodArguments).associate { pair ->
                    pair.first.symbol to pair.second
                }
                val targetMethodSubstitutor =
                    IrTypeSubstitutor(targetMethodSubstitution, allowEmptySubstitution = true)
                val targetOwner = target.parent as? IrClass
                val targetOwnerSubstitutor = targetOwner
                    ?.takeIf { it != irClass }
                    ?.let { owner ->
                        AbstractIrTypeSubstitutor.forSuperClass(owner.symbol, irClass.symbol.defaultType)
                    }
                fun targetType(type: IrType): IrType {
                    val ownerSubstituted = targetOwnerSubstitutor?.substitute(type) ?: type
                    return targetMethodSubstitutor.substitute(ownerSubstituted)
                }
                val backendContext = this@DotNetGenericInterfaceBridgeLowering.context
                val objectInputParameterIndices = backendContext.genericOwnerFunctionInputEntries.entries
                    .singleOrNull { entry -> entry.value === target }
                    ?.let { entry ->
                        backendContext.genericOwnerFunctionInputEntryObjectParameters[entry.key]
                    }
                    .orEmpty()
                val targetParameterTypes = targetParameters.map { parameter ->
                    if (target.parameters.indexOf(parameter) in objectInputParameterIndices) {
                        backendContext.irBuiltIns.anyNType
                    } else {
                        targetType(parameter.type)
                    }
                }
                val targetReturnType = targetType(target.returnType)
                specialMethodInfo?.let { info ->
                    val bridgeParameters = this@bridge.parameters.drop(1)
                    if (info.argumentsToCheck > bridgeParameters.size) {
                        error("Internal .NET backend error: special bridge argument count mismatch")
                    }
                    bridgeParameters.take(info.argumentsToCheck).forEachIndexed { index, parameter ->
                        +irIfThen(
                            context.irBuiltIns.unitType,
                            irNot(irIs(irGet(parameter), targetParameterTypes[index])),
                            irReturn(info.defaultValueGenerator(this@bridge)),
                        )
                    }
                }
                val call = irCall(target.symbol, targetReturnType).apply {
                    arguments[0] = irGet(this@bridge.parameters[0])
                    targetMethodArguments.forEachIndexed { index, argument ->
                        typeArguments[index] = argument
                    }
                    for (index in targetParameters.indices) {
                        val bridgeArgument = irGet(this@bridge.parameters[index + 1])
                        arguments[index + 1] = if (bridgeArgument.type == targetParameterTypes[index]) {
                            bridgeArgument
                        } else if (hasOwnerBoundMethodArguments) {
                            // The physical slot erased R : T. Adapt through object so CLR emits
                            // box/cast or unbox.any as appropriate instead of requiring an
                            // unverifiable direct conversion between R and substituted T.
                            irImplicitCast(
                                irImplicitCast(bridgeArgument, context.irBuiltIns.anyNType),
                                targetParameterTypes[index],
                            )
                        } else {
                            irImplicitCast(bridgeArgument, targetParameterTypes[index])
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
