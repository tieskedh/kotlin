package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.config.perfManager
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.util.PhaseType
import org.jetbrains.kotlin.util.tryMeasurePhaseTime
import java.io.File

object DotNetBackend {
    fun compile(
        irModuleFragment: IrModuleFragment,
        irBuiltIns: IrBuiltIns,
        symbolTable: SymbolTable,
        configuration: CompilerConfiguration,
    ): File {
        val output = configuration.dotNetOutput ?: error("Missing .NET output")
        val assemblyName = configuration.dotNetAssemblyName ?: output.nameWithoutExtension
        val emitsExecutable = output.extension.equals("exe", ignoreCase = true)
        val ilTarget = when {
            output.isDirectory -> output.resolve("$assemblyName.il")
            emitsExecutable -> output.siblingWithExtension("il")
            else -> output
        }
        ilTarget.parentFile?.mkdirs()

        val context = DotNetBackendContext(irBuiltIns, configuration, symbolTable)
        configuration.perfManager.tryMeasurePhaseTime(PhaseType.IrLowering) {
            DotNetLoweringPhases.lower(irModuleFragment, context)
        }

        val emitter = DotNetIlEmitter(
            messageCollector = configuration.messageCollector,
            assemblyName = assemblyName,
            moduleFileName = if (emitsExecutable) output.name else ilTarget.name,
            producesExecutable = emitsExecutable,
            irBuiltIns = irBuiltIns,
        )
        val ilText = emitter.emit(irModuleFragment)
        if (ilText == null) {
            // Emission failed (the error is in the message collector). Remove any stale output of
            // a previous successful compilation so callers never see outdated content.
            ilTarget.delete()
            return ilTarget
        }
        // ilasm decodes a BOM-less file as ANSI, mangling every multi-byte UTF-8 sequence (e.g. in
        // string literals), so the .il file must be written as UTF-8 *with* a BOM.
        ilTarget.writeBytes(UTF8_BOM + ilText.toByteArray(Charsets.UTF_8))

        if (emitsExecutable) {
            DotNetIlAssembler.assembleExecutable(ilTarget, output, configuration.messageCollector)
            return output
        }

        return ilTarget
    }

    private fun File.siblingWithExtension(extension: String): File {
        return (parentFile ?: File(".")).resolve("$nameWithoutExtension.$extension")
    }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
