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
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.createBlockBody
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrSetFieldImpl
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.name.Name

/**
 * The origin of the synthetic per-file `<clinit>` function produced by
 * [DotNetStaticInitializersLowering]. [DotNetIlEmitter][org.jetbrains.kotlin.backend.dotnet.DotNetIlEmitter]
 * keys on it to keep the function out of the callable surface (never a call target, a main
 * candidate, or a named method) and
 * [DotNetIlMethodCodegen][org.jetbrains.kotlin.backend.dotnet.DotNetIlMethodCodegen] keys on it
 * to render the CLR `.cctor` header instead of a named static method.
 */
internal val DOTNET_STATIC_INITIALIZER: IrDeclarationOrigin = IrDeclarationOriginImpl("DOTNET_STATIC_INITIALIZER")

/**
 * Collects the backing-field initializers of top-level properties of each file into one
 * synthetic file-level `<clinit>` function, in declaration order, and nulls the field
 * initializers — the JVM precedent is `StaticInitializersLowering`, which moves static field
 * initializers of a facade class into a `<clinit>` the same way. Stated deviation from the JVM
 * shape: that lowering is a `ClassLoweringPass` over the facade `IrClass` that
 * `FileClassLowering` created earlier, while this backend builds file facades at emission time,
 * so the pass runs per [IrFile] and parents the `<clinit>` to the file. The function is rendered
 * as the facade's `.cctor` by the emitter; giving the initializers a real function body here
 * (instead of rendering them at emission time) lets the later phases — the `for`-loop rewrite
 * and the string-concatenation lowerings — treat initializer code like any other body.
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
        val statements = mutableListOf<IrStatement>()
        for (declaration in irFile.declarations) {
            if (declaration !is IrProperty || declaration.isConst || declaration.isDelegated) continue
            val field = declaration.backingField ?: continue
            val initializer = field.initializer ?: continue
            statements += IrSetFieldImpl(
                initializer.startOffset,
                initializer.endOffset,
                field.symbol,
                null,
                initializer.expression,
                context.irBuiltIns.unitType,
                IrStatementOrigin.INITIALIZE_FIELD,
            )
            field.initializer = null
        }
        if (statements.isEmpty()) return

        val staticInitializer = context.irFactory.buildFun {
            name = CLINIT_NAME
            returnType = context.irBuiltIns.unitType
            visibility = DescriptorVisibilities.PRIVATE
            origin = DOTNET_STATIC_INITIALIZER
        }
        staticInitializer.parent = irFile
        staticInitializer.body = context.irFactory
            .createBlockBody(staticInitializer.startOffset, staticInitializer.endOffset, statements)
            .patchDeclarationParents(staticInitializer)
        irFile.declarations += staticInitializer
    }

    private companion object {
        val CLINIT_NAME = Name.special("<clinit>")
    }
}
