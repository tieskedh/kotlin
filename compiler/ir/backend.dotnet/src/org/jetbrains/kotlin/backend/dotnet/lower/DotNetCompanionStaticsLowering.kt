/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.isStaticMethodOfClass
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.isPublishedApi
import org.jetbrains.kotlin.name.Name

internal val DOTNET_COMPANION_STATIC_HOLDER: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_COMPANION_STATIC_HOLDER")

/**
 * Assigns companion-block declarations their CLR storage owner before any synthetic declarations
 * are derived from them.
 *
 * FIR2IR represents a companion-block property by class-parented receiver-free accessors and
 * currently marks its backing field static. Normalize that physical fact here before any shared
 * lowering classifies fields as instance state. A non-generic class remains the physical owner.
 * An interface or generic class instead receives one compiler-owned non-generic nested holder:
 * named CLR nested types do not capture the enclosing type's generic parameters, so this avoids
 * one static state per constructed generic owner. Moving the original IR declarations preserves
 * their symbols, metadata, default arguments, and callable-reference identity while giving every
 * later lowering one authoritative physical parent.
 *
 * Stateful holders are structurally created here as well, but codegen rejects their `.cctor`
 * until the companion-initialization graph can make owner construction and inherited obligations
 * enter the holder exactly once. Const and computed members need no such graph edge.
 */
internal class DotNetCompanionStaticsLowering(
    private val context: DotNetBackendContext,
) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.declarations.filterIsInstance<IrClass>().forEach(::lowerClass)
    }

    private fun lowerClass(irClass: IrClass) {
        irClass.declarations.filterIsInstance<IrClass>().forEach(::lowerClass)
        val companionDeclarations = irClass.declarations.filter { it.isCompanionBlockDeclaration() }
        for (declaration in companionDeclarations) {
            if (declaration is IrProperty) declaration.backingField?.isStatic = true
        }
        if (companionDeclarations.isEmpty()) return
        if (irClass.typeParameters.isEmpty() && irClass.kind != ClassKind.INTERFACE) {
            context.companionStaticOwners[irClass] = irClass
            return
        }

        val holder = createHolder(irClass, companionDeclarations)
        context.companionStaticOwners[irClass] = holder
        val firstIndex = irClass.declarations.indexOf(companionDeclarations.first())
        irClass.declarations.removeAll(companionDeclarations.toSet())
        irClass.declarations.add(firstIndex, holder)
        for (declaration in companionDeclarations) {
            declaration.moveTo(holder)
            holder.declarations += declaration
        }
    }

    private fun createHolder(owner: IrClass, declarations: List<IrDeclaration>): IrClass =
        context.irFactory.buildClass {
            startOffset = declarations.first().startOffset
            endOffset = declarations.last().endOffset
            origin = DOTNET_COMPANION_STATIC_HOLDER
            name = Name.special("<CompanionStatics>")
            kind = ClassKind.CLASS
            modality = Modality.FINAL
            visibility = declarations.holderVisibility()
        }.apply {
            parent = owner
            superTypes = listOf(context.irBuiltIns.anyType)
            createThisReceiverParameter()
        }

    private fun List<IrDeclaration>.holderVisibility() = when {
        any { declaration ->
            (declaration as IrDeclarationWithVisibility).visibility == DescriptorVisibilities.PUBLIC ||
                    declaration.isPublishedApi()
        } -> DescriptorVisibilities.PUBLIC
        any { (it as IrDeclarationWithVisibility).visibility == DescriptorVisibilities.PROTECTED } ->
            DescriptorVisibilities.PROTECTED
        any { (it as IrDeclarationWithVisibility).visibility == DescriptorVisibilities.INTERNAL } ->
            DescriptorVisibilities.INTERNAL
        else -> DescriptorVisibilities.PRIVATE
    }

    private fun IrDeclaration.isCompanionBlockDeclaration(): Boolean = when (this) {
        is IrSimpleFunction ->
            origin == IrDeclarationOrigin.DEFINED && correspondingPropertySymbol == null && isStaticMethodOfClass
        is IrProperty -> isCompanionBlockProperty()
        else -> false
    }

    private fun IrProperty.isCompanionBlockProperty(): Boolean {
        if (origin != IrDeclarationOrigin.DEFINED) return false
        val accessors = listOfNotNull(getter, setter)
        return accessors.isNotEmpty() && accessors.all(IrSimpleFunction::isStaticMethodOfClass) ||
                backingField?.isStatic == true
    }

    private fun IrDeclaration.moveTo(holder: IrClass) {
        parent = holder
        if (this !is IrProperty) return
        backingField?.parent = holder
        getter?.parent = holder
        setter?.parent = holder
    }
}
