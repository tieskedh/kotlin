package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
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
        val target = configuration.dotNetTarget
        val assemblyName = configuration.dotNetAssemblyName ?: output.nameWithoutExtension
        val emitsExecutable = !output.isDirectory && when (target) {
            DotNetTarget.NET_FRAMEWORK -> output.extension.equals("exe", ignoreCase = true)
            // Modern .NET has no directly runnable ilasm .exe story on this pipeline: the runnable
            // artifact is a .dll launched by the signed `dotnet` host, so both spellings of an
            // executable request produce one.
            DotNetTarget.NET -> output.extension.equals("exe", ignoreCase = true) || output.extension.equals("dll", ignoreCase = true)
        }
        val binaryOutput = if (emitsExecutable && target == DotNetTarget.NET && output.extension.equals("exe", ignoreCase = true)) {
            // An .exe was requested but the 'net' target only produces host-launched .dll files.
            // Renaming silently would leave the user looking for a file that never appears, so the
            // actual artifact is reported explicitly.
            output.siblingWithExtension("dll").also {
                configuration.messageCollector.report(
                    CompilerMessageSeverity.INFO,
                    "The 'net' target produces a .dll started via 'dotnet exec' instead of a standalone .exe; writing '${it.path}'."
                )
            }
        } else {
            output
        }
        val ilTarget = when {
            output.isDirectory -> output.resolve("$assemblyName.il")
            emitsExecutable -> binaryOutput.siblingWithExtension("il")
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
            moduleFileName = if (emitsExecutable) binaryOutput.name else ilTarget.name,
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
            DotNetIlAssembler.assembleExecutable(ilTarget, binaryOutput, target, configuration.messageCollector)
            return binaryOutput
        }

        return ilTarget
    }

    private fun File.siblingWithExtension(extension: String): File {
        return (parentFile ?: File(".")).resolve("$nameWithoutExtension.$extension")
    }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
