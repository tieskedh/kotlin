/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
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

internal val IrClass.hasDotNetIteratorBridges: Boolean
    get() = functions.any { it.origin == DOTNET_ITERATOR_HAS_NEXT_BRIDGE } &&
            functions.any { it.origin == DOTNET_ITERATOR_NEXT_BRIDGE }

/**
 * Gives every user class with an `Iterator<T>` contract the existing erased runtime slots.
 *
 * Kotlin keeps the source members logically typed, most importantly `next(): T`. The CLR runtime
 * identity is deliberately non-generic and instead requires `object Next()`. This is the JVM
 * bridge pattern adapted to explicit CLR MethodImpl rows: private synthetic methods forward to
 * the typed Kotlin members, boxing only at the erased `Next` boundary. A `HasNext` bridge is also
 * necessary because the Kotlin-owned runtime contract uses CLR-style method names.
 *
 * The contract may arrive directly, through a user iterator subinterface, or through a base class.
 * A class declaring the contract owns the bridges when it has callable typed members. An abstract
 * declaration with no class-owned members may defer them to the first concrete descendant; an
 * already generated base bridge is inherited and dispatches to typed overrides. This pass does
 * not create iterator producers; array producer admission is owned by the intrinsic layer.
 */
internal class DotNetIteratorBridgeLowering(private val context: DotNetBackendContext) : ModuleLoweringPass {
    override fun lower(irModule: IrModuleFragment) {
        val implementers = mutableListOf<IrClass>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (!declaration.isInterface && declaration.hasIteratorContract()) {
                    implementers += declaration
                }
                declaration.acceptChildrenVoid(this)
            }
        })
        implementers.sortedBy { it.classInheritanceDepth() }.forEach { irClass ->
            if (!irClass.inheritsIteratorBridges()) addBridges(irClass)
        }
    }

    private fun IrClass.hasIteratorContract(visited: MutableSet<IrClass> = mutableSetOf()): Boolean {
        if (!visited.add(this)) return false
        return superTypes.any { superType ->
            val superClass = ((superType as? IrSimpleType)?.classifier?.owner as? IrClass) ?: return@any false
            when {
                superClass.isDotNetIteratorBase -> true
                superClass.isDotNetSupportedPrimitiveIterator -> false
                else -> superClass.hasIteratorContract(visited)
            }
        }
    }

    private fun IrClass.classInheritanceDepth(visited: MutableSet<IrClass> = mutableSetOf()): Int {
        if (!visited.add(this)) return 0
        val superClass = directSuperClassOrNull() ?: return 0
        return 1 + superClass.classInheritanceDepth(visited)
    }

    private fun IrClass.inheritsIteratorBridges(): Boolean {
        var superClass = directSuperClassOrNull()
        while (superClass != null) {
            if (superClass.hasDotNetIteratorBridges) return true
            superClass = superClass.directSuperClassOrNull()
        }
        return false
    }

    private fun IrClass.directSuperClassOrNull(): IrClass? = superTypes.firstNotNullOfOrNull { superType ->
        val superClass = ((superType as? IrSimpleType)?.classifier?.owner as? IrClass)
            ?: return@firstNotNullOfOrNull null
        superClass.takeUnless { it.isInterface }
    }

    private fun addBridges(irClass: IrClass) {
        val hasNext = irClass.iteratorImplementationOrNull("hasNext") ?: return
        val next = irClass.iteratorImplementationOrNull("next") ?: return
        irClass.addHasNextBridge(hasNext)
        irClass.addNextBridge(next)
    }

    private fun IrClass.iteratorImplementationOrNull(name: String): IrSimpleFunction? {
        val candidates = functions.filter { function ->
            function.name.asString() == name &&
                    function.parameters.size == 1 &&
                    function.allOverridden().any { overridden ->
                        (overridden.parent as? IrClass)?.isDotNetIteratorBase == true
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
        // An abstract class may inherit only Iterator's abstract declaration. There is then no
        // class-owned typed slot for a bridge body to call; leave the class unsupported instead
        // of generating a recursive call through the erased interface.
        if ((implementation?.parent as? IrClass)?.isDotNetIteratorBase == true) return null
        return candidate
    }

    private fun IrClass.addHasNextBridge(target: IrSimpleFunction) {
        addFunction {
            startOffset = target.startOffset
            endOffset = target.endOffset
            origin = DOTNET_ITERATOR_HAS_NEXT_BRIDGE
            name = Name.special("<IteratorHasNextBridge>")
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            returnType = context.irBuiltIns.booleanType
        }.apply bridge@{
            parameters += createDispatchReceiverParameterWithClassParent()
            body = context.createIrBuilder(symbol).irBlockBody {
                +irReturn(irCall(target).apply {
                    arguments[0] = irGet(this@bridge.parameters[0])
                })
            }
        }
    }

    private fun IrClass.addNextBridge(target: IrSimpleFunction) {
        addFunction {
            startOffset = target.startOffset
            endOffset = target.endOffset
            origin = DOTNET_ITERATOR_NEXT_BRIDGE
            name = Name.special("<IteratorNextBridge>")
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            returnType = context.irBuiltIns.anyNType
        }.apply bridge@{
            parameters += createDispatchReceiverParameterWithClassParent()
            body = context.createIrBuilder(symbol).irBlockBody {
                val typedValue = irCall(target).apply {
                    arguments[0] = irGet(this@bridge.parameters[0])
                }
                +irReturn(irImplicitCast(typedValue, context.irBuiltIns.anyNType))
            }
        }
    }
}
