/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.isDotNetIterableBase
import org.jetbrains.kotlin.backend.dotnet.isDotNetIteratorBase
import org.jetbrains.kotlin.backend.dotnet.isDotNetSupportedPrimitiveIterator
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.Name

internal val DOTNET_ITERATOR_HAS_NEXT_BRIDGE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_ITERATOR_HAS_NEXT_BRIDGE")

internal val DOTNET_ITERATOR_NEXT_BRIDGE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_ITERATOR_NEXT_BRIDGE")

internal val DOTNET_ITERABLE_ITERATOR_BRIDGE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_ITERABLE_ITERATOR_BRIDGE")

internal enum class DotNetErasedCollectionBridgeResult {
    BOOLEAN,
    OBJECT,
    ITERATOR,
}

internal data class DotNetErasedCollectionBridgeSlot(
    val origin: IrDeclarationOrigin,
    val sourceMemberName: String,
    val bridgeName: String,
    val runtimeOwnerIlName: String,
    val runtimeMemberName: String,
    val result: DotNetErasedCollectionBridgeResult,
)

internal enum class DotNetErasedCollectionContract(
    val kotlinName: String,
    val slots: List<DotNetErasedCollectionBridgeSlot>,
) {
    ITERATOR(
        kotlinName = "Iterator",
        slots = listOf(
            DotNetErasedCollectionBridgeSlot(
                origin = DOTNET_ITERATOR_HAS_NEXT_BRIDGE,
                sourceMemberName = "hasNext",
                bridgeName = "<IteratorHasNextBridge>",
                runtimeOwnerIlName = "Kotlin.Collections.Iterator",
                runtimeMemberName = "HasNext",
                result = DotNetErasedCollectionBridgeResult.BOOLEAN,
            ),
            DotNetErasedCollectionBridgeSlot(
                origin = DOTNET_ITERATOR_NEXT_BRIDGE,
                sourceMemberName = "next",
                bridgeName = "<IteratorNextBridge>",
                runtimeOwnerIlName = "Kotlin.Collections.Iterator",
                runtimeMemberName = "Next",
                result = DotNetErasedCollectionBridgeResult.OBJECT,
            ),
        ),
    ),
    ITERABLE(
        kotlinName = "Iterable",
        slots = listOf(
            DotNetErasedCollectionBridgeSlot(
                origin = DOTNET_ITERABLE_ITERATOR_BRIDGE,
                sourceMemberName = "iterator",
                bridgeName = "<IterableIteratorBridge>",
                runtimeOwnerIlName = "Kotlin.Collections.Iterable",
                runtimeMemberName = "GetIterator",
                result = DotNetErasedCollectionBridgeResult.ITERATOR,
            ),
        ),
    ),
}

internal val IrDeclarationOrigin.dotNetErasedCollectionBridgeSlotOrNull: DotNetErasedCollectionBridgeSlot?
    get() = DotNetErasedCollectionContract.entries
        .asSequence()
        .flatMap { it.slots.asSequence() }
        .firstOrNull { it.origin == this }

internal val IrClass.dotNetErasedCollectionContractOrNull: DotNetErasedCollectionContract?
    get() = when {
        isDotNetIteratorBase -> DotNetErasedCollectionContract.ITERATOR
        isDotNetIterableBase -> DotNetErasedCollectionContract.ITERABLE
        else -> null
    }

internal fun IrClass.hasDotNetErasedCollectionBridges(contract: DotNetErasedCollectionContract): Boolean =
    contract.slots.all { slot -> functions.any { it.origin == slot.origin } }

/**
 * Gives user classes the erased runtime slots of compiler-owned Kotlin collection interfaces.
 *
 * Source members stay logically typed. The runtime owns one non-generic identity per supported
 * variant interface, and private explicit MethodImpl bridges connect each class to its physical
 * slots. Iterator.Next boxes only at its object result boundary; Iterable.GetIterator returns the
 * already-erased Iterator identity unchanged. This table-driven mechanism is intentionally for
 * Kotlin-owned contracts only. It does not adapt imported CLR generic interfaces.
 *
 * A contract may arrive directly, through a user subinterface, or through a base class. A class
 * declaring the contract owns the bridges when it has callable typed members. An abstract
 * declaration with no class-owned members may defer them to the first concrete descendant; an
 * already generated base bridge is inherited and dispatches to typed overrides.
 */
internal class DotNetErasedCollectionBridgeLowering(private val context: DotNetBackendContext) : ModuleLoweringPass {
    override fun lower(irModule: IrModuleFragment) {
        val implementers = mutableListOf<Pair<IrClass, DotNetErasedCollectionContract>>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (!declaration.isInterface) {
                    for (contract in DotNetErasedCollectionContract.entries) {
                        if (declaration.hasContract(contract)) implementers += declaration to contract
                    }
                }
                declaration.acceptChildrenVoid(this)
            }
        })
        implementers.sortedBy { it.first.classInheritanceDepth() }
            .forEach { implementer ->
                val irClass = implementer.first
                val contract = implementer.second
                if (!irClass.inheritsBridges(contract)) addBridges(irClass, contract)
            }
    }

    private fun IrClass.hasContract(
        contract: DotNetErasedCollectionContract,
        visited: MutableSet<IrClass> = mutableSetOf(),
    ): Boolean {
        if (!visited.add(this)) return false
        return superTypes.any { superType ->
            val superClass = ((superType as? IrSimpleType)?.classifier?.owner as? IrClass) ?: return@any false
            when {
                superClass.dotNetErasedCollectionContractOrNull == contract -> true
                contract == DotNetErasedCollectionContract.ITERATOR &&
                        superClass.isDotNetSupportedPrimitiveIterator -> false
                else -> superClass.hasContract(contract, visited)
            }
        }
    }

    private fun IrClass.classInheritanceDepth(visited: MutableSet<IrClass> = mutableSetOf()): Int {
        if (!visited.add(this)) return 0
        val superClass = directSuperClassOrNull() ?: return 0
        return 1 + superClass.classInheritanceDepth(visited)
    }

    private fun IrClass.inheritsBridges(contract: DotNetErasedCollectionContract): Boolean {
        var superClass = directSuperClassOrNull()
        while (superClass != null) {
            if (superClass.hasDotNetErasedCollectionBridges(contract)) return true
            superClass = superClass.directSuperClassOrNull()
        }
        return false
    }

    private fun IrClass.directSuperClassOrNull(): IrClass? = superTypes.firstNotNullOfOrNull { superType ->
        val superClass = ((superType as? IrSimpleType)?.classifier?.owner as? IrClass)
            ?: return@firstNotNullOfOrNull null
        superClass.takeUnless { it.isInterface }
    }

    private fun addBridges(irClass: IrClass, contract: DotNetErasedCollectionContract) {
        val targets = contract.slots.map { slot ->
            slot to (irClass.implementationOrNull(contract, slot) ?: return)
        }
        for (target in targets) irClass.addBridge(target.first, target.second)
    }

    private fun IrClass.implementationOrNull(
        contract: DotNetErasedCollectionContract,
        slot: DotNetErasedCollectionBridgeSlot,
    ): IrSimpleFunction? {
        val candidates = functions.filter { function ->
            function.name.asString() == slot.sourceMemberName &&
                    function.parameters.size == 1 &&
                    function.allOverridden().any { overridden ->
                        (overridden.parent as? IrClass)?.dotNetErasedCollectionContractOrNull == contract
                    }
        }.toList()
        val candidate = candidates.singleOrNull { !it.isFakeOverride }
            ?: candidates.singleOrNull()
            ?: return null
        val implementation = if (candidate.isFakeOverride) {
            candidate.resolveFakeOverride() ?: candidate.resolveFakeOverrideMaybeAbstract()
        } else {
            candidate
        }
        // An abstract class may inherit only the runtime contract's abstract declaration. There
        // is then no class-owned typed slot for a bridge body to call; the first concrete
        // descendant which supplies one becomes the bridge owner.
        if ((implementation?.parent as? IrClass)?.dotNetErasedCollectionContractOrNull == contract) return null
        return candidate
    }

    private fun IrClass.addBridge(slot: DotNetErasedCollectionBridgeSlot, target: IrSimpleFunction) {
        addFunction {
            startOffset = target.startOffset
            endOffset = target.endOffset
            origin = slot.origin
            name = Name.special(slot.bridgeName)
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            returnType = when (slot.result) {
                DotNetErasedCollectionBridgeResult.BOOLEAN -> context.irBuiltIns.booleanType
                DotNetErasedCollectionBridgeResult.OBJECT -> context.irBuiltIns.anyNType
                DotNetErasedCollectionBridgeResult.ITERATOR -> target.returnType
            }
        }.apply bridge@{
            parameters += createDispatchReceiverParameterWithClassParent()
            body = context.createIrBuilder(symbol).irBlockBody {
                val typedValue = irCall(target).apply {
                    arguments[0] = irGet(this@bridge.parameters[0])
                }
                val bridgeValue = when (slot.result) {
                    DotNetErasedCollectionBridgeResult.OBJECT ->
                        irImplicitCast(typedValue, context.irBuiltIns.anyNType)
                    DotNetErasedCollectionBridgeResult.BOOLEAN,
                    DotNetErasedCollectionBridgeResult.ITERATOR,
                        -> typedValue
                }
                +irReturn(bridgeValue)
            }
        }
    }
}
