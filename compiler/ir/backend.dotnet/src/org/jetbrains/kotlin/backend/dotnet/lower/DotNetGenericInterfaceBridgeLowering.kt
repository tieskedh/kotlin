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
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalDeclarations
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceMemberView
import org.jetbrains.kotlin.backend.dotnet.DotNetRuntimeTypes
import org.jetbrains.kotlin.backend.dotnet.dotNetBaseClassOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetExternalLibraries
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericInterfaceCanonicalSlotId
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericInterfaceMemberViews
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericInterfaceDeclaration
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
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.defaultType
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

private fun IrType.referencesTypeParameterOf(owner: IrClass): Boolean {
    val simpleType = this as? IrSimpleType ?: return false
    val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
    if (parameter?.parent == owner) return true
    return simpleType.arguments.any { argument ->
        (argument as? IrTypeProjection)?.type?.referencesTypeParameterOf(owner) == true
    }
}

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
        val classes = mutableListOf<IrClass>()
        val genericInterfaces = hashSetOf<IrClass>()
        val externalDeclarations = DotNetExternalDeclarations(context.configuration.dotNetExternalLibraries)
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.isInterface) {
                    if (declaration.isDotNetGenericInterfaceDeclaration) genericInterfaces += declaration
                } else {
                    classes += declaration
                }
                declaration.acceptChildrenVoid(this)
            }
        })
        fun isKotlinOwnedGenericInterface(irClass: IrClass): Boolean =
            irClass in genericInterfaces ||
                    DotNetRuntimeTypes.genericInterfaceInfoFor(irClass) != null ||
                    externalDeclarations.declaredClassInfoOrNull(irClass) != null
        for (irClass in classes.sortedBy { it.classInheritanceDepth() }) {
            addBridges(irClass, ::isKotlinOwnedGenericInterface)
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
        isKotlinOwnedGenericInterface: (IrClass) -> Boolean,
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
                        (overridden.parent as? IrClass)?.let(isKotlinOwnedGenericInterface) == true
            }
            .distinctBy { it.symbol }
            .toList()
        for (slot in slots) {
            if (irClass.inheritsGenericInterfaceBridge(slot)) continue
            if (implementationFunctions.any { bridge ->
                    bridge.origin == DOTNET_GENERIC_INTERFACE_CANONICAL_BRIDGE &&
                            bridge.overriddenSymbols.singleOrNull() == slot.symbol
                }
            ) {
                continue
            }
            val candidates = implementationFunctions
                .filter { candidate -> candidate.allOverridden().any { it.symbol == slot.symbol } }
            val target = candidates.firstOrNull { !it.isFakeOverride }
                ?: candidates.firstOrNull()
                ?: continue
            val implementation = if (target.isFakeOverride) {
                target.resolveFakeOverride() ?: target.resolveFakeOverrideMaybeAbstract()
            } else {
                target
            } ?: continue
            if ((implementation.parent as? IrClass)?.let(isKotlinOwnedGenericInterface) == true) continue
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
                typedViews = slot.dotNetGenericInterfaceMemberViews(
                    interfaceClass,
                    isKotlinOwnedGenericInterface,
                ),
            )
            createCanonicalBridge(plan, isKotlinOwnedGenericInterface)
            for (view in plan.typedViews) {
                createTypedBridge(plan, view)
            }
        }
    }

    /** A base bridge remains valid because it forwards through the target's virtual class slot. */
    private fun IrClass.inheritsGenericInterfaceBridge(slot: IrSimpleFunction): Boolean {
        val visited = hashSetOf<IrClass>()
        var current = dotNetBaseClassOrNull()
        while (current != null && visited.add(current)) {
            if (current.declarations.filterIsInstance<IrSimpleFunction>().any { bridge ->
                    bridge.origin == DOTNET_GENERIC_INTERFACE_CANONICAL_BRIDGE &&
                            bridge.overriddenSymbols.singleOrNull() == slot.symbol
                }
            ) {
                return true
            }
            current = current.dotNetBaseClassOrNull()
        }
        return false
    }

    private fun createCanonicalBridge(
        plan: BridgePlan,
        isKotlinOwnedGenericInterface: (IrClass) -> Boolean,
    ) {
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
            return if (carrier?.let(isKotlinOwnedGenericInterface) == true) {
                canonicalSubstitutor.substitute(type)
            } else {
                // A reified generic class/array depending on an erased interface parameter has
                // no single closed CLR instantiation. Its canonical carrier is object.
                context.irBuiltIns.anyNType
            }
        }

        createForwardingBridge(
            irClass = plan.implementingClass,
            slot = plan.slot,
            target = plan.target,
            origin = DOTNET_GENERIC_INTERFACE_CANONICAL_BRIDGE,
            bridgeName = "<GenericInterfaceCanonicalBridge-${plan.interfaceIdentity}-" +
                    "${plan.slot.name.asString()}-${plan.slotIdentity}>",
            bridgeTypeTransform = ::canonicalType,
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
    ) {
        val viewName = memberView.name.lowercase()
            .replaceFirstChar(Char::uppercaseChar)
        createForwardingBridge(
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
        )
    }

    private fun createForwardingBridge(
        irClass: IrClass,
        slot: IrSimpleFunction,
        target: IrSimpleFunction,
        origin: IrDeclarationOrigin,
        bridgeName: String,
        bridgeTypeTransform: (IrType) -> IrType,
        specialMethodInfo: SpecialMethodWithDefaultInfo? = null,
    ) {
        val targetParameters = target.parameters.dropWhile { it.kind == IrParameterKind.DispatchReceiver }
        val slotParameters = slot.parameters.dropWhile { it.kind == IrParameterKind.DispatchReceiver }
        if (slotParameters.size != targetParameters.size) {
            error("Internal .NET backend error: generic interface bridge parameter count mismatch")
        }
        irClass.addFunction {
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
            bridgeTypeParameters.forEach { parameter ->
                parameter.superTypes = parameter.superTypes.map(bridgeTypeTransform)
            }
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
                val targetMethodSubstitution = target.typeParameters.zip(bridgeTypeParameters).associate { pair ->
                    pair.first.symbol to pair.second.symbol.defaultType
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
                    bridgeTypeParameters.forEachIndexed { index, parameter ->
                        typeArguments[index] = parameter.symbol.defaultType
                    }
                    for (index in targetParameters.indices) {
                        val bridgeArgument = irGet(this@bridge.parameters[index + 1])
                        arguments[index + 1] = if (bridgeArgument.type == targetParameterTypes[index]) {
                            bridgeArgument
                        } else {
                            irImplicitCast(bridgeArgument, targetParameterTypes[index])
                        }
                    }
                }
                val result = if (call.type == this@bridge.returnType) {
                    call
                } else {
                    irImplicitCast(call, this@bridge.returnType)
                }
                +irReturn(result)
            }
        }
    }
}
