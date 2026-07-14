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
 * Materializes `object` declarations (including companion objects) as CLR singletons — the JVM
 * precedent packaged as one pass: like `ObjectClassLowering` it synthesizes a
 * `public static final`-shaped singleton field (origin
 * [IrDeclarationOrigin.FIELD_FOR_OBJECT_INSTANCE], typed with the object's own type, initialized
 * with a call to the object's primary constructor — private from the frontend, so no other code
 * can mint a second instance), and like `SingletonReferencesLowering` it rewrites every
 * [IrGetObjectValue] targeting a module-declared object into an [IrGetFieldImpl] of that field,
 * so codegen never sees [IrGetObjectValue] for supported objects. Stated packaging deviation from
 * the JVM backend: its three cooperating pieces (`ObjectClassLowering`,
 * `SingletonReferencesLowering` and the field-creation slice of `CachedFieldsForObjectInstances`)
 * are merged into this single module pass, because no intermediate producers of singleton
 * references exist in this backend between the two steps.
 *
 * The field's name and owner follow the JVM's `CachedFieldsForObjectInstances.getFieldForObjectInstance`
 * (its `isNotMappedCompanion` branch): a plain object gets `INSTANCE` on the object class itself,
 * while a COMPANION's field is named after the companion (default `Companion`; named companions
 * keep their own name) and is parented to the ENCLOSING class — so every companion access goes
 * through the enclosing class and triggers ITS `.cctor`, the Kotlin/JVM rule that touching a
 * companion initializes the enclosing class (pinned by objprobe_s8: the enclosing `.cctor`,
 * which `newobj`s the companion, runs exactly once before the first active use). Stated deviation
 * from the JVM's `MoveOrCopyCompanionObjectFieldsLowering`: companion backing fields and init
 * blocks are NOT hoisted to the enclosing class — they stay on the companion instance, so the
 * companion compiles through the unchanged existing machinery ([DotNetInitializersLowering]
 * merges its initializers into its constructor) and no `RemapObjectFieldAccesses` analogue is
 * needed; the JVM hoist exists for JVM-ABI/interop reasons that the CLR's real nested type makes
 * moot. Accepted delta (documented in AGENTS.md): companion state initializes in the companion
 * constructor invoked FROM the enclosing `.cctor` rather than as enclosing-class statics —
 * indistinguishable except under initialization re-entrancy, already a documented, unenforced
 * delta.
 *
 * [DotNetStaticInitializersLowering] later sweeps singleton-field initializers recursively into
 * each owning class's `<clinit>` (rendered as that class's `.cctor`), exactly like the JVM's
 * `StaticInitializersLowering`, giving Kotlin/JVM first-active-use initialization semantics (see
 * the `beforefieldinit` decision in AGENTS.md). For a companion that owning class is the
 * ENCLOSING one; the companion itself gets no `.cctor`. This composes at arbitrary metadata depth;
 * for a nested singleton the immediate plain-class container must be non-generic.
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
        // Collect, then create, then rewrite: a reference can precede its object's declaration
        // in traversal order, so every module-declared object gets its singleton field before
        // any rewrite — and creating a COMPANION's field mutates the ENCLOSING class's
        // declaration list, which must not happen while the visitor is iterating it.
        val singletons = mutableListOf<IrClass>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.kind == ClassKind.OBJECT) {
                    singletons += declaration
                }
                declaration.acceptChildrenVoid(this)
            }
        })
        val singletonFields = singletons.associateWith(::createSingletonField)
        irModule.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitGetObjectValue(expression: IrGetObjectValue): IrExpression {
                if (expression.type.isUnit()) return expression
                val singletonField = singletonFields[expression.symbol.owner] ?: return expression
                return IrGetFieldImpl(expression.startOffset, expression.endOffset, singletonField.symbol, expression.type)
            }
        })
    }

    /**
     * The static singleton field of [singleton], inserted at the owner's declarations index 0
     * (the JVM shape: `ObjectClassLowering` also adds the field at index 0). For a plain object
     * the owner is the object class itself and the field is named `INSTANCE`; for a companion
     * the owner is the ENCLOSING class and the field carries the companion's own name (the
     * `CachedFieldsForObjectInstances` shape — see the class KDoc). Its initializer calls the
     * primary constructor; [DotNetStaticInitializersLowering] moves that call into the owner's
     * `<clinit>`.
     */
    private fun createSingletonField(singleton: IrClass): IrField {
        val constructor = singleton.primaryConstructor
            ?: error("Internal .NET backend error: object '${singleton.name.asString()}' has no primary constructor")
        val owner = if (singleton.isCompanion) {
            singleton.parent as? IrClass
                ?: error("Internal .NET backend error: companion '${singleton.name.asString()}' is not nested in a class")
        } else {
            singleton
        }
        val singletonField = context.irFactory.buildField {
            name = if (singleton.isCompanion) singleton.name else INSTANCE_FIELD_NAME
            type = singleton.defaultType
            origin = IrDeclarationOrigin.FIELD_FOR_OBJECT_INSTANCE
            visibility = DescriptorVisibilities.PUBLIC
            isFinal = true
            isStatic = true
        }
        singletonField.parent = owner
        singletonField.initializer = context.irFactory.createExpressionBody(
            IrConstructorCallImpl.fromSymbolOwner(
                singletonField.startOffset,
                singletonField.endOffset,
                singleton.defaultType,
                constructor.symbol,
                classTypeParametersCount = 0,
            )
        )
        owner.declarations.add(0, singletonField)
        return singletonField
    }

    private companion object {
        val INSTANCE_FIELD_NAME = Name.identifier("INSTANCE")
    }
}
