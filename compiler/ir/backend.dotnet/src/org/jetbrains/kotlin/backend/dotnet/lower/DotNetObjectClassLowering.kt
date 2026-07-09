/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.createExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

/**
 * Materializes non-companion `object` declarations as CLR singletons — the JVM precedent packaged
 * as one pass: like `ObjectClassLowering` it synthesizes a `public static final`-shaped `INSTANCE`
 * field on the object class itself (origin [IrDeclarationOrigin.FIELD_FOR_OBJECT_INSTANCE], typed
 * with the object's own type, initialized with a call to the object's primary constructor —
 * private from the frontend, so no other code can mint a second instance), and like
 * `SingletonReferencesLowering` it rewrites every [IrGetObjectValue] targeting a module-declared
 * object into an [IrGetFieldImpl] of that `INSTANCE` field, so codegen never sees
 * [IrGetObjectValue] for supported objects. Stated packaging deviation from the JVM backend: its
 * three cooperating pieces (`ObjectClassLowering`, `SingletonReferencesLowering` and the
 * field-creation slice of `CachedFieldsForObjectInstances`) are merged into this single module
 * pass, because no intermediate producers of singleton references exist in this backend between
 * the two steps.
 *
 * [DotNetStaticInitializersLowering] later sweeps the `INSTANCE` initializer into the object's
 * `<clinit>` (rendered as the class `.cctor`), exactly like the JVM's
 * `StaticInitializersLowering` sweeps the JVM `INSTANCE` field, giving Kotlin/JVM
 * first-active-use initialization semantics (see the `beforefieldinit` decision in AGENTS.md).
 *
 * Two reference categories are deliberately left untouched:
 * - `kotlin.Unit` (guarded FIRST): no `kotlin.Unit` class is ever emitted, and the existing
 *   codegen paths stay authoritative — a Unit reference in statement/discard position is a
 *   no-op, a value-position Unit stays rejected.
 * - objects declared outside the compiled module: they keep failing loudly through the existing
 *   codegen rejections instead of referencing a field of a class this module never emits.
 */
internal class DotNetObjectClassLowering(private val context: DotNetBackendContext) : ModuleLoweringPass {
    override fun lower(irModule: IrModuleFragment) {
        // Collect-then-rewrite: a reference can precede its object's declaration in traversal
        // order, so every module-declared object gets its INSTANCE field before any rewrite.
        val instanceFields = HashMap<IrClass, IrField>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.kind == ClassKind.OBJECT && !declaration.isCompanion) {
                    instanceFields[declaration] = createInstanceField(declaration)
                }
                declaration.acceptChildrenVoid(this)
            }
        })
        irModule.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitGetObjectValue(expression: IrGetObjectValue): IrExpression {
                if (expression.type.isUnit()) return expression
                val instanceField = instanceFields[expression.symbol.owner] ?: return expression
                return IrGetFieldImpl(expression.startOffset, expression.endOffset, instanceField.symbol, expression.type)
            }
        })
    }

    /**
     * The static `INSTANCE` field of [singleton], inserted at declarations index 0 (the JVM
     * shape: `ObjectClassLowering` also adds the field at index 0). Its initializer calls the
     * primary constructor; [DotNetStaticInitializersLowering] moves that call into the class
     * `<clinit>`.
     */
    private fun createInstanceField(singleton: IrClass): IrField {
        val constructor = singleton.primaryConstructor
            ?: error("Internal .NET backend error: object '${singleton.name.asString()}' has no primary constructor")
        val instanceField = context.irFactory.buildField {
            name = INSTANCE_FIELD_NAME
            type = singleton.defaultType
            origin = IrDeclarationOrigin.FIELD_FOR_OBJECT_INSTANCE
            visibility = DescriptorVisibilities.PUBLIC
            isFinal = true
            isStatic = true
        }
        instanceField.parent = singleton
        instanceField.initializer = context.irFactory.createExpressionBody(
            IrConstructorCallImpl.fromSymbolOwner(
                instanceField.startOffset,
                instanceField.endOffset,
                singleton.defaultType,
                constructor.symbol,
                classTypeParametersCount = 0,
            )
        )
        singleton.declarations.add(0, instanceField)
        return instanceField
    }

    private companion object {
        val INSTANCE_FIELD_NAME = Name.identifier("INSTANCE")
    }
}
