/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.serialization

import org.jetbrains.kotlin.backend.common.serialization.DeclarationTable
import org.jetbrains.kotlin.backend.common.serialization.GlobalDeclarationTable
import org.jetbrains.kotlin.backend.common.serialization.IrFileSerializer
import org.jetbrains.kotlin.backend.common.serialization.IrModuleSerializer
import org.jetbrains.kotlin.backend.common.serialization.IrSerializationSettings
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.fileOrNull

class DotNetGlobalDeclarationTable(builtIns: IrBuiltIns) : GlobalDeclarationTable(DotNetIrMangler) {
    init {
        loadKnownBuiltins(builtIns)
    }
}

class DotNetIrModuleSerializer(
    settings: IrSerializationSettings,
    diagnosticReporter: IrDiagnosticReporter,
    builtIns: IrBuiltIns,
    private val fileFilter: (IrFile) -> Boolean = { true },
) : IrModuleSerializer<IrFileSerializer>(settings, diagnosticReporter) {
    override val globalDeclarationTable = DotNetGlobalDeclarationTable(builtIns)

    override fun createFileSerializer(settings: IrSerializationSettings): IrFileSerializer =
        IrFileSerializer(settings, DeclarationTable.Default(globalDeclarationTable))

    override fun backendSpecificFileFilter(file: IrFile): Boolean = fileFilter(file)

    override fun backendSpecificPreparedInlineFunctionFilter(function: IrSimpleFunction): Boolean =
        function.fileOrNull?.let(fileFilter) == true
}
