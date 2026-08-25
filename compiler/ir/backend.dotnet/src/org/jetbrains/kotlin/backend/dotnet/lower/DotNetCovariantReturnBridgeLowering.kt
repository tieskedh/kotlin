/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryAbiCodec
import org.jetbrains.kotlin.backend.dotnet.DotNetLoweredCovariantReturnBridge
import org.jetbrains.kotlin.backend.dotnet.DotNetRuntimeTypes
import org.jetbrains.kotlin.backend.dotnet.dotNetBaseClassOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetExactFunctionArity
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerRehearsal
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericClassDeclaration
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericInterfaceDeclaration
import org.jetbrains.kotlin.backend.dotnet.isDotNetStringType
import org.jetbrains.kotlin.backend.dotnet.isDotNetVirtual
import org.jetbrains.kotlin.backend.dotnet.isSupportedDotNetPrimitiveArray
import org.jetbrains.kotlin.backend.dotnet.isReifiedByGenericOwnerRehearsal
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
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.AbstractIrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.defaultType as classDefaultType
import org.jetbrains.kotlin.ir.util.erasedUpperBound
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getAllSubstitutedSupertypes
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.isSubclassOf
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedMethodSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedPropertySource
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance

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
 * the same JVM-style bridge rule covers the complete affected slot signature. A final inherited
 * class method also needs an adapter when it satisfies an interface first declared by a derived
 * class: the CLR cannot bind that slot to a non-virtual inherited MethodDef, while Kotlin/JVM
 * semantics require the shape to work. Generic-interface
 * canonical/typed views remain the responsibility of [DotNetGenericInterfaceBridgeLowering].
 */
internal class DotNetCovariantReturnBridgeLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    private val externalDeclarations = context.externalDeclarationsForLowering()

    private fun IrClass.isErasedKotlinGenericOwner(): Boolean {
        if (!isDotNetGenericClassDeclaration) return false
        if (externalDeclarations.hasClass(this)) {
            return externalDeclarations.hasGenericClass(this)
        }
        return !context.configuration.dotNetGenericOwnerRehearsal ||
                context.genericOwnerArchitecturePlans[this]
                    ?.isReifiedByGenericOwnerRehearsal != true
    }

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
                    adapter.typeIn(
                        adapter.physicalReturnType(adapter.returnType),
                        owner,
                        adapter.typeParameters,
                        keepOwnerTypeParameters = false,
                    ).hasSameClrCarrierAs(
                        slot.typeIn(
                            slot.physicalReturnType(slot.returnType),
                            owner,
                            adapter.typeParameters,
                            keepOwnerTypeParameters = false,
                        ),
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
            val allPhysicalSlots = (target.allOverridden() + directClassSlots)
                .filter { slot ->
                    isOrdinaryPhysicalSlot(slot)
                }
                .distinctBy { it.symbol }
            val nearestClassSlots = allPhysicalSlots
                .filter { slot -> (slot.parent as? IrClass)?.isInterface != true }
                .filter { slot ->
                    val slotOwner = slot.parent as IrClass
                    allPhysicalSlots.none { candidate ->
                        if (candidate == slot) return@none false
                        val candidateOwner = candidate.parent as? IrClass ?: return@none false
                        !candidateOwner.isInterface && candidateOwner.isSubclassOf(slotOwner)
                    }
                }
            // A covariant declaration must fill the nearest physical class slot even when FIR
            // records that slot only transitively through an inherited fake override. More
            // distant class slots are already reached through the nearest slot's own MethodImpl
            // bridge, while every interface slot remains independently owned by its TypeDef.
            val slots = (allPhysicalSlots.filter { slot ->
                (slot.parent as? IrClass)?.isInterface == true
            } + nearestClassSlots).distinctBy { it.symbol }
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
        if (!slotOwner.isDotNetGenericInterfaceDeclaration) return true
        // The erased generic-interface lowering owns production Kotlin interface slots. A
        // rehearsal-reified I<T> instead has a natural CLR MethodDef and therefore participates
        // in the ordinary covariant-return rule: add a typed MethodImpl only when an inherited
        // class body has a genuinely different return carrier. Exact signatures remain direct.
        if (slotOwner in context.reifiedGenericInterfaces ||
            externalDeclarations.hasReifiedGenericInterface(slotOwner)
        ) {
            return true
        }
        // ExactFunctionN is an IR-only view of a real Runtime generic interface. Its closed
        // generic arguments select the physical slots; no erased Kotlin interface lowering owns
        // a second adapter for it.
        if (slotOwner.dotNetExactFunctionArity != null) return true
        // Resolution-only built-ins such as KProperty0 are logically generic, but their complete
        // physical owner is one dedicated non-generic Kotlin.Runtime interface rather than the
        // split generic-interface ABI. Its covariant accessor slots therefore belong to this
        // lowering. Collection-like built-in generic mappings still remain owned exclusively by
        // DotNetGenericInterfaceBridgeLowering.
        return DotNetRuntimeTypes.hasBuiltInPropertyInterfaceMapping(slotOwner)
    }

    private fun addBridgeIfRequired(
        owner: IrClass,
        slot: IrSimpleFunction,
        target: IrSimpleFunction,
    ) {
        if (slot.typeParameters.size != target.typeParameters.size) return
        // A foreign slot's retained CLR MethodDef is the physical authority. Its Kotlin view can
        // be flexible (`Array<out E>?`) even when the exact slot and the rigid Kotlin override
        // are both the same SZARRAY. Reinterpreting that logical projection through the ordinary
        // Kotlin type mapper would manufacture a System.Array MethodImpl whose body signature no
        // longer matches the foreign declaration. FIR2IR has already accepted this exact override
        // shape; suppress a bridge only after rechecking the complete retained physical signature.
        if (slot.hasSameImportedClrSignatureAs(target, owner)) return
        val slotOwner = slot.parent as? IrClass ?: return
        val keepsErasedSlotOwnerParameters =
            !slotOwner.isInterface &&
                    (slotOwner.isErasedKotlinGenericOwner() ||
                            externalDeclarations.hasGenericClass(slotOwner))
        val targetReturnType = target.typeIn(
                target.physicalReturnType(target.returnType),
                owner,
                target.typeParameters,
                keepOwnerTypeParameters = false,
        )
        val exactCallableSlot = slotOwner.dotNetExactFunctionArity != null
        fun exactCallableCarrier(type: IrType): IrType =
            if (type.usesExactCallableObjectCarrier()) context.irBuiltIns.anyNType else type
        val slotReturnType = if (exactCallableSlot) {
            exactCallableCarrier(targetReturnType)
        } else {
            slot.typeIn(
                    slot.physicalReturnType(slot.returnType),
                    owner,
                    target.typeParameters,
                    keepOwnerTypeParameters = keepsErasedSlotOwnerParameters,
            )
        }
        val slotParameters = slot.parameters.dropWhile { it.kind == IrParameterKind.DispatchReceiver }
        val targetParameters = target.parameters.dropWhile { it.kind == IrParameterKind.DispatchReceiver }
        val needsInheritedFinalInterfaceForwarder =
            slotOwner.isInterface && target.parent != owner && !target.isDotNetVirtual()
        val hasDifferentParameterCarrier =
                (slot in context.splitNullableResultPayloadTypes) !=
                        (target in context.splitNullableResultPayloadTypes) ||
                slotParameters.size != targetParameters.size ||
                slotParameters.zip(targetParameters).any { pair ->
                    val slotParameter = pair.first
                    val targetParameter = pair.second
                    val targetType = target.typeIn(
                        targetParameter.type,
                        owner,
                        target.typeParameters,
                        keepOwnerTypeParameters = false,
                    )
                    val slotType = if (exactCallableSlot) {
                        exactCallableCarrier(targetType)
                    } else {
                        slot.typeIn(
                            slotParameter.type,
                            owner,
                            target.typeParameters,
                            keepOwnerTypeParameters = keepsErasedSlotOwnerParameters,
                        )
                    }
                    !slotType.hasSameClrCarrierAs(targetType)
                }
        if (!needsInheritedFinalInterfaceForwarder &&
            !hasDifferentParameterCarrier &&
            slotReturnType.hasSameClrCarrierAs(targetReturnType)
        ) return
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

    /** Physical split-nullable members compare their producer-derived payload, not logical `T?`. */
    private fun IrSimpleFunction.physicalReturnType(type: IrType): IrType =
        context.splitNullableResultPayloadTypes[this] ?: type

    private fun IrSimpleFunction.typeIn(
        type: IrType,
        owner: IrClass,
        methodParameters: List<org.jetbrains.kotlin.ir.declarations.IrTypeParameter>,
        keepOwnerTypeParameters: Boolean,
    ): IrType {
        val declarationOwner = parent as? IrClass ?: return type
        val ownerSubstituted = if (
            declarationOwner != owner &&
            !keepOwnerTypeParameters &&
            (!keepsOpenNullableDeclarationCarrier(type) ||
                    this in context.splitNullableResultPayloadTypes)
        ) {
            type.substituteClassOwnerParameters(declarationOwner, owner)
        } else type
        if (typeParameters.isEmpty()) return ownerSubstituted
        val methodSubstitution = typeParameters.zip(methodParameters).associate { pair ->
            pair.first.symbol to pair.second.symbol.defaultType
        }
        return IrTypeSubstitutor(methodSubstitution, allowEmptySubstitution = true)
            .substitute(ownerSubstituted)
    }

    /** Substitutes a class slot through the concrete class-supertype chain retained by IR/KLIB. */
    private fun IrType.substituteClassOwnerParameters(
        declarationOwner: IrClass,
        useSiteOwner: IrClass,
    ): IrType {
        val ownerView = getAllSubstitutedSupertypes(useSiteOwner).singleOrNull { superType ->
            superType.classOrNull?.owner == declarationOwner
        } ?: return this
        if (ownerView.arguments.size != declarationOwner.typeParameters.size) return this
        val substitutions = declarationOwner.typeParameters.zip(ownerView.arguments).mapNotNull { pair ->
            val projection = pair.second as? IrTypeProjection ?: return@mapNotNull null
            pair.first.symbol to projection.type
        }
        if (substitutions.size != declarationOwner.typeParameters.size) return this
        return IrTypeSubstitutor(substitutions.toMap(), allowEmptySubstitution = true).substitute(this)
    }

    private fun IrSimpleFunction.hasSameImportedClrSignatureAs(
        target: IrSimpleFunction,
        owner: IrClass,
    ): Boolean {
        val physicalMethod = importedClrPhysicalMethodOrNull() ?: return false
        val physicalSignature = physicalMethod.signature
        val importedOwner = parent as? IrClass ?: return false
        val ownerSubstitutor = AbstractIrTypeSubstitutor.forSuperClass(
            importedOwner.symbol,
            owner.classDefaultType,
        ) ?: return false
        val ownerTypeArguments = importedOwner.typeParameters.map { parameter ->
            ownerSubstitutor.substitute(parameter.defaultType)
        }
        val targetParameters = target.parameters.dropWhile { it.kind == IrParameterKind.DispatchReceiver }
        if (physicalSignature.parameterTypes.size != targetParameters.size) return false
        val targetParameterTypes = targetParameters.map { parameter ->
            target.typeIn(
                parameter.type,
                owner,
                target.typeParameters,
                keepOwnerTypeParameters = false,
            )
        }
        if (!targetParameterTypes.zip(physicalSignature.parameterTypes).all { pair ->
                pair.first.hasImportedClrCarrier(
                    pair.second,
                    ownerTypeArguments,
                    target.typeParameters,
                )
            }
        ) {
            return false
        }
        val targetReturnType = target.typeIn(
            target.returnType,
            owner,
            target.typeParameters,
            keepOwnerTypeParameters = false,
        )
        return when (val physicalReturnType = physicalSignature.returnType) {
            DotNetClrTypeSignature.Void -> targetReturnType.isUnit()
            else -> targetReturnType.hasImportedClrCarrier(
                physicalReturnType,
                ownerTypeArguments,
                target.typeParameters,
            )
        }
    }

    private fun IrSimpleFunction.hasSameImportedClrReturnCarrierAs(
        target: IrSimpleFunction,
        owner: IrClass,
    ): Boolean {
        val physicalReturnType = importedClrPhysicalMethodOrNull()?.signature?.returnType ?: return false
        val importedOwner = parent as? IrClass ?: return false
        val ownerSubstitutor = AbstractIrTypeSubstitutor.forSuperClass(
            importedOwner.symbol,
            owner.classDefaultType,
        ) ?: return false
        val ownerTypeArguments = importedOwner.typeParameters.map { parameter ->
            ownerSubstitutor.substitute(parameter.defaultType)
        }
        val targetReturnType = target.typeIn(
            target.returnType,
            owner,
            target.typeParameters,
            keepOwnerTypeParameters = false,
        )
        return when (physicalReturnType) {
            DotNetClrTypeSignature.Void -> targetReturnType.isUnit()
            else -> targetReturnType.hasImportedClrCarrier(
                physicalReturnType,
                ownerTypeArguments,
                target.typeParameters,
            )
        }
    }

    private fun IrSimpleFunction.importedClrPhysicalMethodOrNull(): DotNetClrMethodDefinition? {
        return when (val source = containerSource) {
            is DotNetClrImportedMethodSource -> source.method
            is DotNetClrImportedPropertySource -> {
                val property = correspondingPropertySymbol?.owner ?: return null
                when (this) {
                    property.getter -> source.getter
                    property.setter -> source.setter
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun IrType.hasImportedClrCarrier(
        physicalType: DotNetClrTypeSignature,
        ownerTypeArguments: List<IrType>,
        methodTypeParameters: List<IrTypeParameter>,
    ): Boolean =
        when (physicalType) {
            is DotNetClrTypeSignature.Primitive ->
                classOrNull?.owner?.fqNameWhenAvailable?.asString() ==
                        physicalType.type.kotlinClassifierNameOrNull()
            is DotNetClrTypeSignature.SzArray -> {
                val simpleType = this as? IrSimpleType ?: return false
                if (simpleType.classOrNull?.owner?.fqNameWhenAvailable?.asString() != "kotlin.Array") {
                    return false
                }
                val elementProjection = simpleType.arguments.singleOrNull() as? IrTypeProjection
                    ?: return false
                elementProjection.variance == Variance.INVARIANT &&
                        elementProjection.type.hasImportedClrCarrier(
                            physicalType.elementType,
                            ownerTypeArguments,
                            methodTypeParameters,
                        )
            }
            is DotNetClrTypeSignature.GenericParameter -> {
                val simpleType = this as? IrSimpleType
                when (physicalType.kind) {
                    DotNetClrGenericParameterKind.TYPE ->
                        ownerTypeArguments.getOrNull(physicalType.index)
                            ?.let { ownerArgument ->
                                hasSameImportedClrCarrierAs(ownerArgument)
                            } == true
                    DotNetClrGenericParameterKind.METHOD ->
                        simpleType?.isMarkedNullable() == false &&
                                simpleType.classifier ==
                                methodTypeParameters.getOrNull(physicalType.index)?.symbol
                }
            }
            DotNetClrTypeSignature.Void,
            DotNetClrTypeSignature.TypedReference,
            is DotNetClrTypeSignature.Array,
            is DotNetClrTypeSignature.ByReference,
            is DotNetClrTypeSignature.FunctionPointer,
            is DotNetClrTypeSignature.GenericInstance,
            is DotNetClrTypeSignature.Modified,
            is DotNetClrTypeSignature.Named,
            is DotNetClrTypeSignature.Pointer,
                -> false
        }

    private fun IrType.hasSameImportedClrCarrierAs(other: IrType): Boolean {
        val left = this as? IrSimpleType ?: return this == other
        val right = other as? IrSimpleType ?: return false
        if (
            left.classifier != right.classifier ||
            left.isMarkedNullable() != right.isMarkedNullable() ||
            left.arguments.size != right.arguments.size
        ) {
            return false
        }
        return left.arguments.indices.all { index ->
            val leftArgument = left.arguments[index] as? IrTypeProjection ?: return@all false
            val rightArgument = right.arguments[index] as? IrTypeProjection ?: return@all false
            leftArgument.variance == rightArgument.variance &&
                    leftArgument.type.hasSameImportedClrCarrierAs(rightArgument.type)
        }
    }

    private fun DotNetClrPrimitiveType.kotlinClassifierNameOrNull(): String? = when (this) {
        DotNetClrPrimitiveType.BOOLEAN -> "kotlin.Boolean"
        DotNetClrPrimitiveType.CHAR -> "kotlin.Char"
        DotNetClrPrimitiveType.INT8 -> "kotlin.Byte"
        DotNetClrPrimitiveType.INT16 -> "kotlin.Short"
        DotNetClrPrimitiveType.INT32 -> "kotlin.Int"
        DotNetClrPrimitiveType.INT64 -> "kotlin.Long"
        DotNetClrPrimitiveType.FLOAT32 -> "kotlin.Float"
        DotNetClrPrimitiveType.FLOAT64 -> "kotlin.Double"
        DotNetClrPrimitiveType.STRING -> "kotlin.String"
        DotNetClrPrimitiveType.OBJECT -> "kotlin.Any"
        DotNetClrPrimitiveType.UINT8,
        DotNetClrPrimitiveType.UINT16,
        DotNetClrPrimitiveType.UINT32,
        DotNetClrPrimitiveType.UINT64,
        DotNetClrPrimitiveType.NATIVE_INT,
        DotNetClrPrimitiveType.NATIVE_UINT,
            -> null
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
                    (slotOwner.isErasedKotlinGenericOwner() ||
                            externalDeclarations.hasGenericClass(slotOwner))
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
            val slotMethodSubstitution = slot.typeParameters.zip(bridgeTypeParameters).associate { pair ->
                pair.first.symbol to pair.second.symbol.defaultType
            }
            val slotMethodSubstitutor = IrTypeSubstitutor(slotMethodSubstitution, allowEmptySubstitution = true)
            fun bridgeType(type: IrType): IrType {
                // The physical slot of a Kotlin-owned generic base was emitted in the base's
                // erased declaration context. Keep those owner parameters here so the shared
                // type mapper reproduces the exact object/upper-bound/erased-array carrier;
                // only method parameters are rebound to this synthetic method. An open nullable
                // parameter is likewise frozen to object by the CLR signature mapper even on a
                // reified owner; substituting its concrete derived argument must not reconstruct
                // a narrower carrier which the inherited MethodDef never declared.
                val ownerSubstituted = if (
                    keepsErasedClassOwnerParameters || slot.keepsOpenNullableDeclarationCarrier(type)
                ) {
                    type
                } else {
                    type.substituteClassOwnerParameters(slotOwner, owner)
                }
                return slotMethodSubstitutor.substitute(ownerSubstituted)
            }

            val retainedParameterTypes = slot.importedClrPhysicalMethodOrNull()?.signature?.parameterTypes
            val adaptsPrimitiveVararg = slotParameters.indices.any { index ->
                retainedParameterTypes?.getOrNull(index) is DotNetClrTypeSignature.SzArray &&
                        targetParameters[index].varargElementType != null &&
                        targetParameters[index].type.isSupportedDotNetPrimitiveArray()
            }
            returnType = if (slotOwner.dotNetExactFunctionArity != null) {
                val targetType = target.typeIn(
                    target.returnType,
                    owner,
                    bridgeTypeParameters,
                    keepOwnerTypeParameters = false,
                )
                if (targetType.usesExactCallableObjectCarrier()) context.irBuiltIns.anyNType else targetType
            } else if (
                adaptsPrimitiveVararg &&
                slot.hasSameImportedClrReturnCarrierAs(target, owner)
            ) {
                target.typeIn(
                    target.returnType,
                    owner,
                    bridgeTypeParameters,
                    keepOwnerTypeParameters = false,
                )
            } else {
                bridgeType(slot.returnType)
            }
            for (indexedParameter in slotParameters.withIndex()) {
                val index = indexedParameter.index
                val slotParameter = indexedParameter.value
                val targetParameter = targetParameters[index]
                val retainedParameterType = retainedParameterTypes?.getOrNull(index)
                val bridgeParameterType = if (slotOwner.dotNetExactFunctionArity != null) {
                    val targetType = target.typeIn(
                        targetParameter.type,
                        owner,
                        bridgeTypeParameters,
                        keepOwnerTypeParameters = false,
                    )
                    if (targetType.usesExactCallableObjectCarrier()) context.irBuiltIns.anyNType else targetType
                } else if (
                    retainedParameterType is DotNetClrTypeSignature.SzArray &&
                    targetParameter.varargElementType != null &&
                    targetParameter.type.isSupportedDotNetPrimitiveArray()
                ) {
                    val elementType = target.typeIn(
                        targetParameter.varargElementType!!,
                        owner,
                        bridgeTypeParameters,
                        keepOwnerTypeParameters = false,
                    )
                    context.irBuiltIns.arrayClass.typeWith(elementType)
                } else {
                    bridgeType(slotParameter.type)
                }
                addValueParameter(slotParameter.name.asString(), bridgeParameterType)
            }
            body = context.createIrBuilder(symbol).irBlockBody {
                val targetOwner = target.parent as? IrClass
                    ?: error("Internal .NET backend error: covariant-return target has no class owner")
                val targetMethodSubstitution = target.typeParameters.zip(bridgeTypeParameters)
                    .associate { pair ->
                        pair.first.symbol to pair.second.symbol.defaultType
                    }
                val targetMethodSubstitutor =
                    IrTypeSubstitutor(targetMethodSubstitution, allowEmptySubstitution = true)
                fun targetType(type: IrType): IrType {
                    val ownerSubstituted = if (targetOwner == owner) type
                    else type.substituteClassOwnerParameters(targetOwner, owner)
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
        val erasedClassParameterCarrier = erasedClassParameterCarrierOrNull()
        val otherErasedClassParameterCarrier = other.erasedClassParameterCarrierOrNull()
        if (erasedClassParameterCarrier != null || otherErasedClassParameterCarrier != null) {
            return (erasedClassParameterCarrier ?: this).hasSameClrCarrierAs(
                otherErasedClassParameterCarrier ?: other,
            )
        }
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

    /** Whether ExactFunctionN's canonical nested argument must use its universal object carrier. */
    private fun IrType.usesExactCallableObjectCarrier(): Boolean {
        if (!context.configuration.dotNetGenericOwnerRehearsal) return false
        val resultOwner = ((this as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner ?: return false
        if (!resultOwner.isDotNetGenericInterfaceDeclaration) return false
        return resultOwner in context.genericOwnerCapabilityInterfaces ||
                externalDeclarations.genericOwnerCapabilityInfoOrNull(resultOwner) != null ||
                DotNetRuntimeTypes.usesDeclaredViewByDefaultInRehearsal(resultOwner)
    }

    /** The declaration-context carrier used by an owner-erased generic class parameter. */
    private fun IrType.erasedClassParameterCarrierOrNull(): IrType? {
        val simpleType = this as? IrSimpleType ?: return null
        val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner ?: return null
        val parameterOwner = parameter.parent as? IrClass ?: return null
        if (parameterOwner.isInterface ||
            (!parameterOwner.isErasedKotlinGenericOwner() &&
                    !externalDeclarations.hasGenericClass(parameterOwner))
        ) {
            return null
        }
        if (simpleType.isMarkedNullable()) return context.irBuiltIns.anyNType
        val upperBound = simpleType.erasedUpperBound
        return if (upperBound == parameterOwner || upperBound.classDefaultType == simpleType) {
            context.irBuiltIns.anyNType
        } else {
            upperBound.classDefaultType
        }
    }

    private fun IrType.isOpenNullableTypeParameter(): Boolean {
        val simpleType = this as? IrSimpleType ?: return false
        return simpleType.isMarkedNullable() && simpleType.classifier is IrTypeParameterSymbol
    }

    /**
     * Kotlin-owned open `T?` slots are emitted as object and remain stable in their declaration
     * context. A retained foreign CLR MethodDef is different physical evidence: its reified `!T`
     * slot must be substituted through the implemented construction (for example `T = string`).
     */
    private fun IrSimpleFunction.keepsOpenNullableDeclarationCarrier(type: IrType): Boolean =
        type.isOpenNullableTypeParameter() && importedClrPhysicalMethodOrNull() == null

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
