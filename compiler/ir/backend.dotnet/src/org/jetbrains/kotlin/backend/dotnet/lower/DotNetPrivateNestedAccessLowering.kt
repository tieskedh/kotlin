/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.lower.inline.KlibSyntheticAccessorGenerator
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetIlAccessibility
import org.jetbrains.kotlin.backend.dotnet.dotNetObjectInstanceFieldAccessibility
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Restores Kotlin-private lexical access where CLR nesting reverses an access rule.
 *
 * CLR nested types may access private members of their enclosing type, but an enclosing CLR type
 * may not access private members declared by its nested type. Two lowered Kotlin shapes need a
 * bridge:
 *
 * - enclosing declarations may call private companion constructors, functions, and accessors;
 * - object lowering replaces an object reference with a read of its singleton field. The emitter
 *   keeps that field private when the object is a private nested declaration, so an enclosing
 *   declaration cannot read it directly.
 *
 * Keep the source member and singleton field private. The common KLIB accessor generator supplies
 * the established `access$...` function/field-getter shape and constructor-marker overload; this
 * target phase redirects only CLR-illegal reads/calls and narrows the generated bridge to assembly
 * visibility because none of these bridges cross the Kotlin module boundary.
 *
 * This phase runs after object lowering so it sees both the synthesized companion constructor call
 * and every synthesized singleton-field read.
 */
internal class DotNetPrivateNestedAccessLowering(
    context: DotNetBackendContext,
) : ModuleLoweringPass {
    private val accessorGenerator = KlibSyntheticAccessorGenerator(context)

    override fun lower(irModule: IrModuleFragment) {
        val generatedAccessors = linkedSetOf<IrFunction>()
        irModule.transformChildrenVoid(object : IrElementTransformerVoid() {
            private var currentClass: IrClass? = null

            override fun visitClass(declaration: IrClass): IrStatement {
                val previousClass = currentClass
                currentClass = declaration
                return try {
                    super.visitClass(declaration)
                } finally {
                    currentClass = previousClass
                }
            }

            override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
                expression.transformChildrenVoid(this)
                val target = expression.symbol.owner
                val nestedOwner = when (val parent = target.parent) {
                    is IrClass -> parent
                    is IrProperty -> parent.parent as? IrClass
                    else -> null
                } ?: return expression
                if (
                    nestedOwner.origin != DOTNET_STATIC_HOLDER && !nestedOwner.isCompanion ||
                    target.visibility != DescriptorVisibilities.PRIVATE
                ) return expression
                if (currentClass.isNestedWithin(nestedOwner)) return expression

                val accessor = accessorGenerator.getSyntheticFunctionAccessor(expression, null).asAssemblyBridge()
                generatedAccessors += accessor
                return accessorGenerator.modifyFunctionAccessExpression(expression, accessor.symbol)
            }

            override fun visitGetField(expression: IrGetField): IrExpression {
                expression.transformChildrenVoid(this)
                val field = expression.symbol.owner
                if (field.origin != IrDeclarationOrigin.FIELD_FOR_OBJECT_INSTANCE) return expression
                val singleton = field.type.classOrNull?.owner ?: return expression
                if (singleton.dotNetObjectInstanceFieldAccessibility() != DotNetIlAccessibility.PRIVATE) return expression
                if (currentClass.isNestedWithin(singleton)) return expression

                val accessor = accessorGenerator.getSyntheticGetter(expression, null).asAssemblyBridge()
                generatedAccessors += accessor
                return accessorGenerator.modifyGetterExpression(expression, accessor.symbol)
            }
        })

        for (accessor in generatedAccessors) {
            val parent = accessor.parent as IrDeclarationContainer
            if (accessor !in parent.declarations) parent.declarations += accessor
        }
    }

    private fun <T : IrFunction> T.asAssemblyBridge(): T = apply {
        visibility = DescriptorVisibilities.INTERNAL
    }

    private fun IrClass?.isNestedWithin(outer: IrClass): Boolean {
        var current = this
        while (current != null) {
            if (current == outer) return true
            current = current.parent as? IrClass
        }
        return false
    }
}
