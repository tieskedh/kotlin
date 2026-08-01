/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.serialization

import org.jetbrains.kotlin.backend.common.serialization.mangle.KotlinExportChecker
import org.jetbrains.kotlin.backend.common.serialization.mangle.KotlinMangleComputer
import org.jetbrains.kotlin.backend.common.serialization.mangle.MangleMode
import org.jetbrains.kotlin.backend.common.serialization.mangle.ir.IrBasedKotlinManglerImpl
import org.jetbrains.kotlin.backend.common.serialization.mangle.ir.IrExportCheckerVisitor
import org.jetbrains.kotlin.backend.common.serialization.mangle.ir.IrMangleComputer
import org.jetbrains.kotlin.ir.declarations.IrDeclaration

object DotNetIrMangler : IrBasedKotlinManglerImpl() {
    private class DotNetIrExportChecker(compatibleMode: Boolean) : IrExportCheckerVisitor(compatibleMode) {
        override fun IrDeclaration.isPlatformSpecificExported(): Boolean = false
    }

    override fun getExportChecker(compatibleMode: Boolean): KotlinExportChecker<IrDeclaration> =
        DotNetIrExportChecker(compatibleMode)

    override fun getMangleComputer(mode: MangleMode, compatibleMode: Boolean): KotlinMangleComputer<IrDeclaration> =
        IrMangleComputer(StringBuilder(256), mode, compatibleMode)
}
