/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalDeclarations
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryAbiCodec
import org.jetbrains.kotlin.backend.dotnet.DotNetLoweredCovariantReturnBridge
import org.jetbrains.kotlin.backend.dotnet.dotNetBaseClassOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetExternalLibraries
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericClassDeclaration
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericInterfaceDeclaration
import org.jetbrains.kotlin.backend.dotnet.isDotNetStringType
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
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
import org.jetbrains.kotlin.ir.types.AbstractIrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isSubclassOf
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.Name

internal val DOTNET_COVARIANT_RETURN_BRIDGE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_COVARIANT_RETURN_BRIDGE")

/**
 * Materializes floor-compatible CLR MethodImpl adapters for Kotlin override signatures.
 *
 * The source declaration remains the one exact Kotlin implementation. Each generated method has
 * the substituted signature of one wider physical slot, calls the exact implementation
 * virtually, and contains no copied source body. Ordinary CLR inheritance usually needs this
 * only for covariant returns. An erased Kotlin-owned generic base can additionally widen
 * parameters in its declaration context (`Base<T>.write(T)` is physically `write(object)`), so
 * the same JVM-style bridge rule covers the complete affected slot signature. Generic-interface
 * canonical/typed views remain the responsibility of [DotNetGenericInterfaceBridgeLowering].
 */
internal class DotNetCovariantReturnBridgeLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    private val externalDeclarations =
        DotNetExternalDeclarations(context.configuration.dotNetExternalLibraries)

    override fun lower(irModule: IrModuleFragment) {
        val classes = mutableListOf<IrClass>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                classes += declaration
                declaration.acceptChildrenVoid(this)
            }
        })
        // Interface MethodImpl adapters must exist before implementing classes are considered:
        // a class inheriting that selected DIM already receives the wider physical slot through
        // the interface and must not acquire a redundant class forwarder.
        classes.filter(IrClass::isInterface).forEach(::addBridges)
        classes.filterNot(IrClass::isInterface).forEach(::addBridges)
    }

    private fun addBridges(owner: IrClass) {
        // The default-interface lowering runs first and cannot decide CLR return-carrier
        // compatibility. Relinquish only its incompatible MethodImpl arrows here; the
        // covariant-return adapter will own those slots. Delete a now-empty forwarding adapter
        // so it does not survive as an unrelated private virtual method.
        owner.declarations.filterIsInstance<IrSimpleFunction>()
            .filter { it.origin == DOTNET_INTERFACE_DEFAULT_SLOT_BRIDGE }
            .forEach { adapter ->
                adapter.overriddenSymbols = adapter.overriddenSymbols.filter { overridden ->
                    val slot = overridden.owner
                    adapter.returnType.hasSameClrCarrierAs(
                        slot.returnTypeIn(owner, adapter.typeParameters),
                    )
                }
                if (adapter.overriddenSymbols.isEmpty()) {
                    owner.declarations.remove(adapter)
                }
            }
        val members = owner.declarations.flatMap { declaration ->
            when (declaration) {
                is IrSimpleFunction -> listOf(declaration)
                is IrProperty -> listOfNotNull(declaration.getter, declaration.setter)
                else -> emptyList()
            }
        }
        val declaredTargets = members.filter { member ->
            !member.isFakeOverride &&
                    member.origin != DOTNET_COVARIANT_RETURN_BRIDGE &&
                    member.origin != DOTNET_INTERFACE_DEFAULT_SLOT_BRIDGE &&
                    (member.body != null || member.modality == Modality.ABSTRACT && !owner.isInterface) &&
                    member.parameters.firstOrNull()?.kind == IrParameterKind.DispatchReceiver
        }
        for (target in declaredTargets) {
            val directClassSlots = target.overriddenSymbols.mapTo(linkedSetOf()) { symbol ->
                val member = symbol.owner
                if (member.isFakeOverride) {
                    // `resolveFakeOverride()` deliberately ignores abstract declarations.
                    // An abstract member inherited through an erased intermediate class is
                    // nevertheless the real CLR slot that a concrete covariant leaf must fill.
                    member.resolveFakeOverride()
                        ?: member.resolveFakeOverrideMaybeAbstract()
                        ?: member
                } else {
                    member
                }
            }
            // `allOverridden()` may retain only an intermediate fake override while the direct
            // physical class slot is available through its resolved declaration. Include both
            // sets before filtering; otherwise an erased abstract base's object-return slot can
            // survive without an implementation on a concrete covariant leaf.
            val slots = (target.allOverridden() + directClassSlots)
                .filter { slot ->
                    isOrdinaryPhysicalSlot(slot) &&
                            ((slot.parent as? IrClass)?.isInterface == true || slot in directClassSlots)
                }
                .distinctBy { it.symbol }
            for (slot in slots) {
                if (owner.inheritsInterfaceCovariantBridgeFor(slot)) continue
                addBridgeIfRequired(owner, slot, target)
            }
        }

        if (owner.isInterface) return
        for (fakeOverride in members.filter { it.isFakeOverride }) {
            val target = fakeOverride.resolveFakeOverride()?.takeIf { candidate ->
                candidate.body != null ||
                        candidate.modality != Modality.ABSTRACT &&
                        (candidate.parent as? IrClass)?.isInterface != true
            } ?: continue
            val slots = fakeOverride.allOverridden()
                .filter { slot ->
                    isOrdinaryPhysicalSlot(slot) &&
                            (slot.parent as? IrClass)?.isInterface == true &&
                            !owner.baseClassAlreadyImplements(slot.parent as IrClass)
                }
                .distinctBy { it.symbol }
            for (slot in slots) {
                if (owner.inheritsInterfaceCovariantBridgeFor(slot)) continue
                addBridgeIfRequired(owner, slot, target)
            }
        }
    }

    private fun isOrdinaryPhysicalSlot(slot: IrSimpleFunction): Boolean {
        if (slot.isFakeOverride) return false
        val slotOwner = slot.parent as? IrClass ?: return false
        return !slotOwner.isDotNetGenericInterfaceDeclaration
    }

    private fun addBridgeIfRequired(
        owner: IrClass,
        slot: IrSimpleFunction,
        target: IrSimpleFunction,
    ) {
        if (slot.typeParameters.size != target.typeParameters.size) return
        val slotOwner = slot.parent as? IrClass ?: return
        val requiresErasedClassOverrideBridge =
            !slotOwner.isInterface &&
                    (slotOwner.isDotNetGenericClassDeclaration || externalDeclarations.hasGenericClass(slotOwner)) &&
                    slot.signatureReferencesTypeParameterOf(slotOwner)
        val slotReturnType = slot.returnTypeIn(owner, target.typeParameters)
        val targetReturnType = target.returnTypeIn(owner, target.typeParameters)
        if (!requiresErasedClassOverrideBridge && slotReturnType.hasSameClrCarrierAs(targetReturnType)) return
        if (context.covariantReturnBridges.any { existing ->
                existing.owner == owner && existing.inheritedMember == slot && existing.target == target
            }
        ) {
            return
        }
        val bridge = createBridge(owner, slot, target)
        context.covariantReturnBridges += DotNetLoweredCovariantReturnBridge(
            owner = owner,
            inheritedMember = slot,
            target = target,
            implementation = bridge,
            requiresNewSlotOnTarget = target.parent == owner &&
                    slot.symbol in target.overriddenSymbols &&
                    (slot.parent as? IrClass)?.isInterface != true,
        )
    }

    private fun IrSimpleFunction.returnTypeIn(
        owner: IrClass,
        methodParameters: List<org.jetbrains.kotlin.ir.declarations.IrTypeParameter>,
    ): IrType {
        val declarationOwner = parent as? IrClass ?: return returnType
        val ownerSubstitutor = declarationOwner.takeIf { it != owner }?.let { declaringClass ->
            AbstractIrTypeSubstitutor.forSuperClass(declaringClass.symbol, owner.symbol.defaultType)
        }
        val ownerSubstituted = ownerSubstitutor?.substitute(returnType) ?: returnType
        if (typeParameters.isEmpty()) return ownerSubstituted
        val methodSubstitution = typeParameters.zip(methodParameters).associate { pair ->
            pair.first.symbol to pair.second.symbol.defaultType
        }
        return IrTypeSubstitutor(methodSubstitution, allowEmptySubstitution = true)
            .substitute(ownerSubstituted)
    }

    private fun createBridge(
        owner: IrClass,
        slot: IrSimpleFunction,
        target: IrSimpleFunction,
    ): IrSimpleFunction {
        val slotOwner = slot.parent as? IrClass
            ?: error("Internal .NET backend error: physical override slot has no class owner")
        val keepsErasedClassOwnerParameters =
            !slotOwner.isInterface &&
                    (slotOwner.isDotNetGenericClassDeclaration || externalDeclarations.hasGenericClass(slotOwner))
        val targetParameters = target.parameters.dropWhile { it.kind == IrParameterKind.DispatchReceiver }
        val slotParameters = slot.parameters.dropWhile { it.kind == IrParameterKind.DispatchReceiver }
        if (targetParameters.size != slotParameters.size) {
            error("Internal .NET backend error: covariant-return bridge parameter count mismatch")
        }
        val slotOwnerName = slotOwner.fqNameWhenAvailable?.asString() ?: slotOwner.name.asString()
        val slotIdentity = slot.covariantReturnSlotId()
        return owner.addFunction {
            startOffset = target.startOffset
            endOffset = target.endOffset
            origin = DOTNET_COVARIANT_RETURN_BRIDGE
            name = Name.special(
                "<CovariantReturnBridge-$slotOwnerName-${slot.name.asString()}-$slotIdentity>"
            )
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            returnType = context.irBuiltIns.anyNType
        }.apply bridge@{
            overriddenSymbols = listOf(slot.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            val bridgeTypeParameters = copyTypeParametersFrom(slot)
            val slotOwnerSubstitutor = slotOwner.takeIf { it != owner }?.let { declaringClass ->
                AbstractIrTypeSubstitutor.forSuperClass(declaringClass.symbol, owner.symbol.defaultType)
            }
            val slotMethodSubstitution = slot.typeParameters.zip(bridgeTypeParameters).associate { pair ->
                pair.first.symbol to pair.second.symbol.defaultType
            }
            val slotMethodSubstitutor = IrTypeSubstitutor(slotMethodSubstitution, allowEmptySubstitution = true)
            fun bridgeType(type: IrType): IrType {
                // The physical slot of a Kotlin-owned generic base was emitted in the base's
                // erased declaration context. Keep those owner parameters here so the shared
                // type mapper reproduces the exact object/upper-bound/erased-array carrier;
                // only method parameters are rebound to this synthetic method.
                val ownerSubstituted = if (keepsErasedClassOwnerParameters) {
                    type
                } else {
                    slotOwnerSubstitutor?.substitute(type) ?: type
                }
                return slotMethodSubstitutor.substitute(ownerSubstituted)
            }

            returnType = bridgeType(slot.returnType)
            for (slotParameter in slotParameters) {
                addValueParameter(slotParameter.name.asString(), bridgeType(slotParameter.type))
            }
            body = context.createIrBuilder(symbol).irBlockBody {
                val targetOwner = target.parent as? IrClass
                    ?: error("Internal .NET backend error: covariant-return target has no class owner")
                val targetOwnerSubstitutor = targetOwner.takeIf { it != owner }?.let { declaringClass ->
                    AbstractIrTypeSubstitutor.forSuperClass(declaringClass.symbol, owner.symbol.defaultType)
                }
                val targetMethodSubstitution = target.typeParameters.zip(bridgeTypeParameters)
                    .associate { pair ->
                        pair.first.symbol to pair.second.symbol.defaultType
                    }
                val targetMethodSubstitutor =
                    IrTypeSubstitutor(targetMethodSubstitution, allowEmptySubstitution = true)
                fun targetType(type: IrType): IrType {
                    val ownerSubstituted = targetOwnerSubstitutor?.substitute(type) ?: type
                    return targetMethodSubstitutor.substitute(ownerSubstituted)
                }

                val targetReturnType = targetType(target.returnType)
                val targetParameterTypes = targetParameters.map { parameter -> targetType(parameter.type) }
                val call = irCall(target.symbol, targetReturnType).apply {
                    arguments[0] = irGet(this@bridge.parameters[0])
                    bridgeTypeParameters.forEachIndexed { index, parameter ->
                        typeArguments[index] = parameter.symbol.defaultType
                    }
                    targetParameterTypes.forEachIndexed { index, parameterType ->
                        val argument = irGet(this@bridge.parameters[index + 1])
                        arguments[index + 1] = if (argument.type == parameterType) {
                            argument
                        } else {
                            irImplicitCast(argument, parameterType)
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

    /** Reference nullability erases in CLR metadata; value/nullability and reified arguments do not. */
    private fun IrType.hasSameClrCarrierAs(other: IrType): Boolean {
        if (this == other) return true
        if (!isOpenNullableTypeParameter() && !other.isOpenNullableTypeParameter() &&
            isDotNetStringType() && other.isDotNetStringType()
        ) {
            return true
        }
        val left = this as? IrSimpleType ?: return false
        val right = other as? IrSimpleType ?: return false
        if (left.classifier != right.classifier) return false
        if (left.classifier is IrTypeParameterSymbol) {
            return left.isMarkedNullable() == right.isMarkedNullable()
        }
        if (left.isPrimitiveType() || right.isPrimitiveType() || left.isUnit() || right.isUnit() ||
            left.isNothing() || right.isNothing()
        ) {
            return left.isMarkedNullable() == right.isMarkedNullable()
        }
        if ((left.classifier as? IrClassSymbol)?.owner?.isDotNetGenericInterfaceDeclaration == true) {
            // Ordinary Kotlin ABI uses the interface's non-generic canonical identity. Its
            // declared/exact typed views and any required result adapters are owned by the
            // preceding generic-interface lowering, not by an ordinary return bridge.
            return true
        }
        if (left.arguments.size != right.arguments.size) return false
        return left.arguments.zip(right.arguments).all { pair ->
            val leftArgument = pair.first
            val rightArgument = pair.second
            when {
                leftArgument is IrStarProjection && rightArgument is IrStarProjection -> true
                leftArgument is IrTypeProjection && rightArgument is IrTypeProjection ->
                    leftArgument.variance == rightArgument.variance &&
                            leftArgument.type.hasSameClrCarrierAs(rightArgument.type)
                else -> false
            }
        }
    }

    private fun IrType.isOpenNullableTypeParameter(): Boolean {
        val simpleType = this as? IrSimpleType ?: return false
        return simpleType.isMarkedNullable() && simpleType.classifier is IrTypeParameterSymbol
    }

    /** Whether declaration-context erasure can change any physical carrier in this class slot. */
    private fun IrSimpleFunction.signatureReferencesTypeParameterOf(owner: IrClass): Boolean =
        returnType.referencesTypeParameterOf(owner) ||
                parameters.asSequence()
                    .filter { parameter -> parameter.kind != IrParameterKind.DispatchReceiver }
                    .any { parameter -> parameter.type.referencesTypeParameterOf(owner) }

    private fun IrType.referencesTypeParameterOf(owner: IrClass): Boolean {
        val simpleType = this as? IrSimpleType ?: return false
        val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
        if (parameter?.parent == owner) return true
        return simpleType.arguments.any { argument ->
            (argument as? IrTypeProjection)?.type?.referencesTypeParameterOf(owner) == true
        }
    }

    private fun IrClass.baseClassAlreadyImplements(interfaceClass: IrClass): Boolean {
        val baseClass = dotNetBaseClassOrNull() ?: return false
        val visited = hashSetOf<IrClass>()
        val pending = mutableListOf(baseClass)
        while (pending.isNotEmpty()) {
            val current = pending.removeAt(pending.lastIndex)
            if (!visited.add(current)) continue
            if (current == interfaceClass) return true
            current.superTypes.mapNotNullTo(pending) { superType ->
                ((superType as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
            }
        }
        return false
    }

    /** Whether a local or producer-recorded interface already owns this wider MethodImpl slot. */
    private fun IrClass.inheritsInterfaceCovariantBridgeFor(slot: IrSimpleFunction): Boolean {
        if (context.covariantReturnBridges.any { bridge ->
                bridge.owner.isInterface &&
                        isSubclassOf(bridge.owner) &&
                        bridge.inheritedMember == slot
            }
        ) {
            return true
        }
        val visited = hashSetOf<IrClass>()
        val pending = superTypes.mapNotNullTo(mutableListOf()) { superType ->
            ((superType as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
        }
        while (pending.isNotEmpty()) {
            val candidate = pending.removeAt(pending.lastIndex)
            if (!visited.add(candidate)) continue
            if (candidate.isInterface &&
                externalDeclarations.covariantReturnBridgeOrNull(candidate, slot) != null
            ) {
                return true
            }
            candidate.superTypes.mapNotNullTo(pending) { superType ->
                ((superType as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
            }
        }
        return false
    }

    /** Frontend-independent private name key; public ABI identity is carried by the MethodImpl row. */
    private fun IrSimpleFunction.covariantReturnSlotId(): String {
        fun IrType.stableTypeKey(): String {
            val simpleType = this as? IrSimpleType ?: return javaClass.simpleName
            val classifierKey = when (val classifier = simpleType.classifier) {
                is IrClassSymbol -> classifier.owner.fqNameWhenAvailable?.asString()
                    ?: classifier.owner.name.asString()
                is IrTypeParameterSymbol -> {
                    val parameter = classifier.owner
                    val ownerParameters = when (val parameterOwner = parameter.parent) {
                        is IrClass -> parameterOwner.typeParameters
                        is IrSimpleFunction -> parameterOwner.typeParameters
                        else -> emptyList()
                    }
                    "${parameter.parent.javaClass.simpleName}#${ownerParameters.indexOf(parameter)}"
                }
                else -> classifier.javaClass.simpleName
            }
            val arguments = simpleType.arguments.joinToString(",", prefix = "<", postfix = ">") { argument ->
                when (argument) {
                    is IrStarProjection -> "*"
                    is IrTypeProjection -> "${argument.variance}:${argument.type.stableTypeKey()}"
                }
            }
            return classifierKey + arguments + if (simpleType.isMarkedNullable()) "?" else ""
        }

        val logicalKey = buildString {
            append((parent as? IrClass)?.fqNameWhenAvailable?.asString().orEmpty())
            append('|')
            append(name.asString())
            append('|')
            append(typeParameters.size)
            append('|')
            parameters.forEach { parameter ->
                append(parameter.kind)
                append(':')
                append(parameter.type.stableTypeKey())
                append(';')
            }
            append("->")
            append(returnType.stableTypeKey())
        }
        return DotNetLibraryAbiCodec.logicalIdentityDigest(logicalKey)
    }
}
