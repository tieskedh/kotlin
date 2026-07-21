/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile

/** Physical CLR accessibility used where lowering and serialization must agree. */
internal enum class DotNetIlAccessibility(val keyword: String) {
    PUBLIC("public"),
    ASSEMBLY("assembly"),
    FAMILY("family"),
    PRIVATE("private"),
}

/**
 * Accessibility of the synthetic singleton field emitted for one object class.
 *
 * A private top-level object is represented by an assembly-visible type/field because its file
 * facade is a separate CLR type. Nested and compiler-local objects retain CLR-private fields.
 * Keep this as one producer of truth: [DotNetPrivateNestedAccessLowering][org.jetbrains.kotlin.backend.dotnet.lower.DotNetPrivateNestedAccessLowering]
 * uses it to decide when a bridge is required, and the emitter uses it for the field flag.
 */
internal fun IrClass.dotNetObjectInstanceFieldAccessibility(): DotNetIlAccessibility {
    if (parent is IrFile && visibility == DescriptorVisibilities.PRIVATE) {
        return DotNetIlAccessibility.ASSEMBLY
    }
    return visibility.dotNetIlAccessibility(default = DotNetIlAccessibility.PRIVATE)
}

private fun DescriptorVisibility?.dotNetIlAccessibility(
    default: DotNetIlAccessibility,
): DotNetIlAccessibility = when (this) {
    DescriptorVisibilities.PUBLIC -> DotNetIlAccessibility.PUBLIC
    DescriptorVisibilities.INTERNAL -> DotNetIlAccessibility.ASSEMBLY
    DescriptorVisibilities.PROTECTED -> DotNetIlAccessibility.FAMILY
    DescriptorVisibilities.PRIVATE -> DotNetIlAccessibility.PRIVATE
    else -> default
}
