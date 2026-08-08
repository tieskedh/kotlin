/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

/**
 * One compiler/runtime encoding for the logical declaration facts exposed by KCallable.
 *
 * The source FIR/IR declaration is authoritative. These bits must never be reconstructed from a
 * selected CLR MethodDef: bridges and export adapters can have different physical flags without
 * changing the Kotlin declaration they implement.
 */
internal object DotNetCallableDeclarationFlags {
    const val VISIBILITY_PUBLIC: Int = 1 shl 10
    const val VISIBILITY_PROTECTED: Int = 1 shl 11
    const val VISIBILITY_INTERNAL: Int = 1 shl 12
    const val VISIBILITY_PRIVATE: Int = 1 shl 13

    const val MODALITY_FINAL: Int = 1 shl 14
    const val MODALITY_OPEN: Int = 1 shl 15
    const val MODALITY_ABSTRACT: Int = 1 shl 16

    private const val VISIBILITY_MASK: Int =
        VISIBILITY_PUBLIC or VISIBILITY_PROTECTED or VISIBILITY_INTERNAL or VISIBILITY_PRIVATE
    private const val MODALITY_MASK: Int = MODALITY_FINAL or MODALITY_OPEN or MODALITY_ABSTRACT

    fun encode(visibility: DescriptorVisibility, modality: Modality): Int {
        val visibilityFlag = when (visibility) {
            DescriptorVisibilities.PUBLIC -> VISIBILITY_PUBLIC
            DescriptorVisibilities.PROTECTED -> VISIBILITY_PROTECTED
            DescriptorVisibilities.INTERNAL -> VISIBILITY_INTERNAL
            DescriptorVisibilities.PRIVATE,
            DescriptorVisibilities.PRIVATE_TO_THIS -> VISIBILITY_PRIVATE
            else -> 0
        }
        val modalityFlag = when (modality) {
            Modality.FINAL -> MODALITY_FINAL
            Modality.OPEN -> MODALITY_OPEN
            Modality.ABSTRACT -> MODALITY_ABSTRACT
            Modality.SEALED -> error(
                "Internal .NET backend error: callable declarations cannot have sealed modality"
            )
        }
        check(Integer.bitCount(visibilityFlag and VISIBILITY_MASK) <= 1)
        check(Integer.bitCount(modalityFlag and MODALITY_MASK) == 1)
        return visibilityFlag or modalityFlag
    }
}

internal fun IrFunction.dotNetCallableDeclarationFlags(): Int =
    DotNetCallableDeclarationFlags.encode(
        visibility = visibility,
        modality = when (this) {
            is IrConstructor -> Modality.FINAL
            is IrSimpleFunction -> modality
        },
    )

internal fun IrProperty.dotNetCallableDeclarationFlags(): Int =
    DotNetCallableDeclarationFlags.encode(visibility, modality)

internal fun dotNetLocalCallableDeclarationFlags(): Int =
    DotNetCallableDeclarationFlags.encode(DescriptorVisibilities.LOCAL, Modality.FINAL)
