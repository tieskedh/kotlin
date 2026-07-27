/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.name.Name

/**
 * Gives same-named CLR fields deterministic distinct names by renaming private implementation
 * storage only.
 *
 * This deliberately follows the JVM [RenameFieldsLowering][org.jetbrains.kotlin.backend.jvm.lower.RenameFieldsLowering]
 * policy rather than treating a private backing-field spelling as Kotlin ABI. CLR metadata can
 * distinguish same-named fields by type, but C# cannot declare that shape naturally and common
 * reflection/tooling APIs are name-oriented. Public/protected fields therefore reserve their
 * source or compiler-ABI name first, followed by static implementation fields for stable output,
 * while later private fields receive `$n` suffixes.
 *
 * Unlike the JVM pipeline, the .NET pipeline retains [IrProperty] declarations through codegen,
 * so their backing fields must be included explicitly instead of reading only loose [IrField]
 * declarations.
 */
internal class DotNetRenameFieldsLowering(
    @Suppress("UNUSED_PARAMETER") context: DotNetBackendContext,
) : ClassLoweringPass {
    override fun lower(irClass: IrClass) {
        val fields = irClass.declarations.flatMap { declaration ->
            when (declaration) {
                is IrField -> listOf(declaration)
                is IrProperty -> listOfNotNull(declaration.backingField)
                else -> emptyList()
            }
        }.toMutableList()
        fields.sortBy {
            when {
                // Never rename fields which form the CLR or compiler ABI.
                it.visibility.isPublicAPI -> 0
                // Match the JVM's stable preference for static implementation storage.
                it.isStatic -> 1
                else -> 2
            }
        }

        // A hostile but legal source field may already own a spelling such as `this$0$1`.
        // Reserve every original spelling before allocating suffixes so a generated name never
        // creates a second collision with a field encountered later in the stable ordering.
        val reservedOriginalNames = fields.mapTo(hashSetOf()) { it.name }
        val usedNames = hashSetOf<Name>()
        val nextSuffixes = hashMapOf<Name, Int>()
        for (field in fields) {
            val oldName = field.name
            if (usedNames.add(oldName) || field.visibility.isPublicAPI) continue

            var suffix = nextSuffixes[oldName] ?: 1
            while (true) {
                val candidate = Name.identifier("$oldName$$suffix")
                suffix++
                if (candidate in reservedOriginalNames || !usedNames.add(candidate)) continue
                field.name = candidate
                nextSuffixes[oldName] = suffix
                break
            }
        }
    }
}
