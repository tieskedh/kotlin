/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createBlockBody
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrSetFieldImpl
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.name.Name

/**
 * The origin of the synthetic `<clinit>` functions produced by
 * [DotNetStaticInitializersLowering] — file-parented for the facade statics, class-parented for
 * the static fields of recursively declared classes (the `INSTANCE` field of an `object`, the
 * companion singleton field of a companion-bearing class).
 * [DotNetIlEmitter][org.jetbrains.kotlin.backend.dotnet.DotNetIlEmitter]
 * keys on it to keep the function out of the callable surface (never a call target, a main
 * candidate, or a named method) and
 * [DotNetIlMethodCodegen][org.jetbrains.kotlin.backend.dotnet.DotNetIlMethodCodegen] keys on it
 * to render the CLR `.cctor` header instead of a named static method.
 */
internal val DOTNET_STATIC_INITIALIZER: IrDeclarationOrigin = IrDeclarationOriginImpl("DOTNET_STATIC_INITIALIZER")

/**
 * Collects static-field initializers into synthetic `<clinit>` functions, in declaration order,
 * and nulls the field initializers — the JVM precedent is `StaticInitializersLowering`, which
 * moves static field initializers of a class into a `<clinit>` the same way. Two owner shapes
 * are handled:
 * - the backing fields of top-level properties of each file become one file-parented `<clinit>`,
 *   rendered as the file facade's `.cctor`. Stated deviation from the JVM shape: that lowering
 *   is a `ClassLoweringPass` over the facade `IrClass` that `FileClassLowering` created earlier,
 *   while this backend builds file facades at emission time, so this slice runs per [IrFile] and
 *   parents the `<clinit>` to the file.
 * - the static fields of each recursively declared class (today exactly the singleton fields
 *   [DotNetObjectClassLowering] synthesizes: `INSTANCE` on an `object` class, or the field
 *   named after the companion on a companion-bearing class) become one class-parented
 *   `<clinit>`, appended to the class's declarations and rendered as the class's `.cctor` —
 *   this slice matches the JVM `ClassLoweringPass` precedent directly. For a companion this
 *   `newobj`/`stsfld` in the ENCLOSING class's `.cctor` is what ties companion initialization
 *   to the enclosing class (objprobe_s8, nestedprobe_s4). Like the common
 *   `ClassLoweringPass` runner, the sweep is recursive and postfix, so companions of ordinary
 *   nested classes and named nested objects receive a `.cctor` on their actual static-field
 *   owner rather than leaving the synthesized field uninitialized.
 *
 * Giving the initializers a real function body here (instead of rendering them at emission time)
 * lets the later phases — the `for`-loop rewrite and the string-concatenation lowerings — treat
 * initializer code like any other body.
 *
 * Excluded properties, mirroring the JVM exclusions:
 * - `const val` (JVM: the `constantValue()` exclusion): the
 *   [IrConst][org.jetbrains.kotlin.ir.expressions.IrConst] initializer stays on the
 *   field so the emitter can render a CLR `literal` field — the ConstantValue-attribute
 *   analogue — with no `.cctor` entry.
 * - delegated properties: rejected by the emitter's property pre-pass; their delegate
 *   initializer must not end up in a `.cctor` that outlives the rejection.
 * - `lateinit` (rejected by the emitter too) never has an initializer to move.
 */
internal class DotNetStaticInitializersLowering(private val context: DotNetBackendContext) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        for (declaration in irFile.declarations) {
            if (declaration is IrClass) lowerClassStatics(declaration)
        }
        lowerFileStatics(irFile)
    }

    private fun lowerFileStatics(irFile: IrFile) {
        val statements = mutableListOf<IrStatement>()
        for (declaration in irFile.declarations) {
            if (declaration !is IrProperty || declaration.isConst || declaration.isDelegated) continue
            val field = declaration.backingField ?: continue
            statements += moveFieldInitializerOrNull(field) ?: continue
        }
        if (statements.isEmpty()) return
        irFile.declarations += buildStaticInitializer(irFile, statements)
    }

    private fun lowerClassStatics(irClass: IrClass) {
        // Match ClassLoweringPass.runOnFilePostfix: every nested declaration is lowered before
        // its metadata parent. Use a snapshot because each recursive call may append a <clinit>.
        val declarations = irClass.declarations.toList()
        declarations.filterIsInstance<IrClass>().forEach(::lowerClassStatics)

        val statements = mutableListOf<IrStatement>()
        for (declaration in declarations) {
            val field = when (declaration) {
                is IrField -> declaration
                is IrProperty -> if (declaration.isConst || declaration.isDelegated) null else declaration.backingField
                else -> null
            } ?: continue
            if (!field.isStatic) continue
            statements += moveFieldInitializerOrNull(field) ?: continue
        }
        if (statements.isEmpty()) return
        irClass.declarations += buildStaticInitializer(irClass, statements)
    }

    private fun moveFieldInitializerOrNull(field: IrField): IrStatement? {
        val initializer = field.initializer ?: return null
        field.initializer = null
        return IrSetFieldImpl(
            initializer.startOffset,
            initializer.endOffset,
            field.symbol,
            null,
            initializer.expression,
            context.irBuiltIns.unitType,
            IrStatementOrigin.INITIALIZE_FIELD,
        )
    }

    private fun buildStaticInitializer(parent: IrDeclarationParent, statements: List<IrStatement>): IrSimpleFunction {
        val staticInitializer = context.irFactory.buildFun {
            name = CLINIT_NAME
            returnType = context.irBuiltIns.unitType
            visibility = DescriptorVisibilities.PRIVATE
            origin = DOTNET_STATIC_INITIALIZER
        }
        staticInitializer.parent = parent
        staticInitializer.body = context.irFactory
            .createBlockBody(staticInitializer.startOffset, staticInitializer.endOffset, statements)
            .patchDeclarationParents(staticInitializer)
        return staticInitializer
    }

    private companion object {
        val CLINIT_NAME = Name.special("<clinit>")
    }
}
