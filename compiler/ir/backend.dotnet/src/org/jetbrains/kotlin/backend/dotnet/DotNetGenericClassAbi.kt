/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.util.isInterface

/** The one non-generic physical CLR owner of a Kotlin-owned ordinary generic class. */
internal data class DotNetGenericClassInfo(
    val classInfo: DotNetIlClassInfo,
)

/** Ordinary Kotlin-owned classes whose declaration parameters use the erased class ABI. */
internal val IrClass.isDotNetGenericClassDeclaration: Boolean
    get() = !isInterface && typeParameters.isNotEmpty()

/** Whether a logical type still mentions a parameter owned by [owner]. */
internal fun IrType.referencesTypeParameterOf(owner: IrClass): Boolean {
    val simpleType = this as? IrSimpleType ?: return false
    val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
    if (parameter?.parent == owner) return true
    return simpleType.arguments.any { argument ->
        (argument as? IrTypeProjection)?.type?.referencesTypeParameterOf(owner) == true
    }
}
