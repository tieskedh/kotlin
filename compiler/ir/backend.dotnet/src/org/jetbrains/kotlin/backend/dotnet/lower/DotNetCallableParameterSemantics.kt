/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedMethodSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedPropertySource
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The one logical optionality rule shared by KParameter metadata and reflective default dispatch.
 * Foreign CLR optional metadata is deliberately not promoted to Kotlin default-argument semantics.
 */
internal fun IrValueParameter.hasDotNetKotlinOptionalSemantics(
    visited: MutableSet<IrSimpleFunction> = Collections.newSetFromMap(IdentityHashMap()),
): Boolean {
    val function = parent as? IrSimpleFunction ?: return defaultValue != null
    if (function.containerSource is DotNetClrImportedMethodSource ||
        function.containerSource is DotNetClrImportedPropertySource
    ) return false
    if (defaultValue != null) return true
    if (!visited.add(function) || kind != IrParameterKind.Regular) return false
    val regularIndex = function.parameters.filter { it.kind == IrParameterKind.Regular }.indexOf(this)
    if (regularIndex < 0) return false
    return function.overriddenSymbols.any { overridden ->
        overridden.owner.parameters.filter { it.kind == IrParameterKind.Regular }
            .getOrNull(regularIndex)
            ?.hasDotNetKotlinOptionalSemantics(visited) == true
    }
}
