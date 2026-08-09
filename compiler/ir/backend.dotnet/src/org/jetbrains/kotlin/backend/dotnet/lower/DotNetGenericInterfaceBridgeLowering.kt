/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.lower.SpecialBridgeMethods
import org.jetbrains.kotlin.backend.common.lower.SpecialMethodWithDefaultInfo
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.dotnet.DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalDeclarations
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceMemberView
import org.jetbrains.kotlin.backend.dotnet.DotNetInterfaceDefaultPromotionView
import org.jetbrains.kotlin.backend.dotnet.DotNetLoweredGenericInterfaceViewBridge
import org.jetbrains.kotlin.backend.dotnet.DotNetLoweredInterfaceDefaultClassForwarder
import org.jetbrains.kotlin.backend.dotnet.DotNetRuntimeTypes
import org.jetbrains.kotlin.backend.dotnet.dotNetBaseClassOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetDirectOwnerRelativeMethodBoundsOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetExternalLibraries
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericInterfaceCanonicalSlotId
import org.jetbrains.kotlin.backend.dotnet.dotNetUnsupported
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericClassDeclaration
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericInterfaceDeclaration
import org.jetbrains.kotlin.backend.dotnet.isDotNetComparableClass
import org.jetbrains.kotlin.backend.dotnet.isDotNetOwnerDependentConstraint
import org.jetbrains.kotlin.backend.dotnet.isDotNetResolutionOnlyStdlibDeclaration
import org.jetbrains.kotlin.backend.dotnet.referencesTypeParameterOf
import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.config.dotNetTarget
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
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
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.Name

internal val DOTNET_GENERIC_INTERFACE_CANONICAL_BRIDGE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_INTERFACE_CANONICAL_BRIDGE")

internal val DOTNET_GENERIC_INTERFACE_DECLARED_BRIDGE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_INTERFACE_DECLARED_BRIDGE")

internal val DOTNET_GENERIC_INTERFACE_EXACT_BRIDGE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_INTERFACE_EXACT_BRIDGE")

internal val IrDeclarationOrigin.dotNetGenericInterfaceBridgeMemberViewOrNull: DotNetGenericInterfaceMemberView?
    get() = when (this) {
        DOTNET_GENERIC_INTERFACE_DECLARED_BRIDGE -> DotNetGenericInterfaceMemberView.DECLARED
        DOTNET_GENERIC_INTERFACE_EXACT_BRIDGE -> DotNetGenericInterfaceMemberView.EXACT
        else -> null
    }

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
        val genericInterfaces = hashSetOf<IrClass>()
        val genericClasses = hashSetOf<IrClass>()
        val externalDeclarations = DotNetExternalDeclarations(context.configuration.dotNetExternalLibraries)
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.isDotNetResolutionOnlyStdlibDeclaration) {
                    declaration.acceptChildrenVoid(this)
                    return
                }
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
        context.erasedGenericInterfaces += genericInterfaces
        context.erasedGenericClasses += genericClasses
        fun isMappedKotlinGenericInterface(irClass: IrClass): Boolean =
            irClass in genericInterfaces ||
                    DotNetRuntimeTypes.hasBuiltInGenericInterfaceMapping(irClass) ||
                    externalDeclarations.hasGenericInterface(irClass)
        fun isErasedKotlinCarrier(irClass: IrClass): Boolean =
            isMappedKotlinGenericInterface(irClass) ||
                    irClass in genericClasses ||
                    externalDeclarations.hasGenericClass(irClass)
        for (irClass in bridgeOwners.sortedBy { it.classInheritanceDepth() }) {
            addBridges(
                irClass,
                ::isMappedKotlinGenericInterface,
                ::isErasedKotlinCarrier,
                externalDeclarations,
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
    ) {
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
            if ((implementation.parent as? IrClass)?.let(isMappedKotlinGenericInterface) == true) continue
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
                typedViews = if (
                    interfaceClass.isDotNetComparableClass() &&
                    !irClass.isDotNetGenericClassDeclaration
                ) {
                    // Comparable is an explicit BCL mapping rather than an ordinary
                    // Kotlin-owned generic-interface sibling. A non-generic implementor can
                    // truthfully name its exact IComparable<T> capability; an erased generic
                    // class cannot and must not fabricate IComparable<object>.
                    listOf(DotNetGenericInterfaceMemberView.DECLARED)
                } else {
                    emptyList()
                },
            )
            if (irClass.inheritsGenericInterfaceBridge(plan, externalDeclarations)) continue
            val canonicalBridge = createCanonicalBridge(
                plan,
                isMappedKotlinGenericInterface,
                isErasedKotlinCarrier,
            )
            val typedBridges = plan.typedViews.associateWith { view ->
                createTypedBridge(plan, view)
            }
            if (plan.implementingClass.isInterface) {
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
            if (overriddenSymbols.singleOrNull() != plan.slot.symbol) return false
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

    private fun createCanonicalBridge(
        plan: BridgePlan,
        isMappedKotlinGenericInterface: (IrClass) -> Boolean,
        isErasedKotlinCarrier: (IrClass) -> Boolean,
    ): IrSimpleFunction {
        val canonicalSubstitution = plan.interfaceClass.typeParameters.associate { typeParameter ->
            typeParameter.symbol to context.irBuiltIns.anyNType
        }
        val canonicalSubstitutor = IrTypeSubstitutor(canonicalSubstitution, allowEmptySubstitution = true)
        fun canonicalType(type: IrType): IrType {
            if (!type.referencesTypeParameterOf(plan.interfaceClass)) return type
            val simpleType = type as? IrSimpleType
                ?: return context.irBuiltIns.anyNType
            val directParameter = simpleType.classifier as? IrTypeParameterSymbol
            if (directParameter?.owner?.parent == plan.interfaceClass) return context.irBuiltIns.anyNType
            val carrier = (simpleType.classifier as? IrClassSymbol)?.owner
            return if (
                carrier?.let(isMappedKotlinGenericInterface) == true ||
                carrier?.isDotNetGenericClassDeclaration == true
            ) {
                // Both carriers have one erased physical identity. Substitute the interface
                // parameter out of the synthetic bridge IR while preserving the nested carrier;
                // the type mapper then selects the canonical interface or erased class owner.
                canonicalSubstitutor.substitute(type)
            } else {
                // A genuinely reified CLR carrier or array depending on an erased interface
                // parameter has no single closed instantiation. Its canonical carrier is object.
                context.irBuiltIns.anyNType
            }
        }

        return createForwardingBridge(
            irClass = plan.implementingClass,
            slot = plan.slot,
            target = plan.target,
            origin = DOTNET_GENERIC_INTERFACE_CANONICAL_BRIDGE,
            bridgeName = "<GenericInterfaceCanonicalBridge-${plan.interfaceIdentity}-" +
                    "${plan.slot.name.asString()}-${plan.slotIdentity}>",
            bridgeTypeTransform = ::canonicalType,
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
            val slotOwner = slot.parent as? IrClass
                ?: error("Internal .NET backend error: generic interface bridge slot has no class owner")
            bridgeTypeParameters.forEachIndexed { index, parameter ->
                parameter.superTypes = slot.typeParameters[index].superTypes
                    .filterNot { it.isDotNetOwnerDependentConstraint(slotOwner) }
                    .map(bridgeTypeTransform)
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
            val methodSubstitution = slot.typeParameters.zip(bridgeTypeParameters).associate { pair ->
                pair.first.symbol to pair.second.symbol.defaultType
            }
            val methodSubstitutor = IrTypeSubstitutor(methodSubstitution, allowEmptySubstitution = true)
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
                        targetParameter.origin != DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER &&
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
                val targetParameterTypes = targetParameters.map { parameter ->
                    targetType(parameter.type)
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
