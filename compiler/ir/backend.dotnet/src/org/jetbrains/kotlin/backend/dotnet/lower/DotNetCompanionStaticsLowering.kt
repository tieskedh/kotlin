/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty

/**
 * Normalizes companion-block property storage before common instance-initializer lowering.
 *
 * FIR2IR represents a companion-block property by class-parented receiver-free accessors and
 * currently marks its backing field static. Normalize that physical fact here before any shared
 * lowering classifies fields as instance state. The CLR representation has one static field on
 * the semantic owner, and [DotNetStaticInitializersLowering] later moves its initializer into
 * that owner's `.cctor`.
 *
 * This lowering deliberately does not move declarations. Generic and interface owners need
 * dedicated non-generic holders; the emitter rejects those shapes until holder lowering assigns
 * their final physical owner.
 */
internal class DotNetCompanionStaticsLowering(
    @Suppress("UNUSED_PARAMETER") context: DotNetBackendContext,
) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.declarations.filterIsInstance<IrClass>().forEach(::lowerClass)
    }

    private fun lowerClass(irClass: IrClass) {
        irClass.declarations.filterIsInstance<IrClass>().forEach(::lowerClass)
        for (property in irClass.declarations.filterIsInstance<IrProperty>()) {
            if (property.isCompanionBlockProperty()) {
                property.backingField?.isStatic = true
            }
        }
    }

    private fun IrProperty.isCompanionBlockProperty(): Boolean {
        val accessors = listOfNotNull(getter, setter)
        return accessors.isNotEmpty() && accessors.all { it.dispatchReceiverParameter == null }
    }
}
