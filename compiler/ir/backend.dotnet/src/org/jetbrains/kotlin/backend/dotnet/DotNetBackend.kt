package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import java.io.File

object DotNetBackend {
    fun compile(irModuleFragment: IrModuleFragment, configuration: CompilerConfiguration): File {
        val output = configuration.dotNetOutput ?: error("Missing .NET output")
        val assemblyName = configuration.dotNetAssemblyName ?: output.nameWithoutExtension
        val emitsExecutable = output.extension.equals("exe", ignoreCase = true)
        val ilTarget = when {
            output.isDirectory -> output.resolve("$assemblyName.il")
            emitsExecutable -> output.siblingWithExtension("il")
            else -> output
        }
        ilTarget.parentFile?.mkdirs()

        val emitter = DotNetIlEmitter(configuration.messageCollector, assemblyName)
        ilTarget.writeText(emitter.emit(irModuleFragment))

        if (emitsExecutable) {
            DotNetIlAssembler.assembleExecutable(ilTarget, output, configuration.messageCollector)
            return output
        }

        return ilTarget
    }

    private fun File.siblingWithExtension(extension: String): File {
        return (parentFile ?: File(".")).resolve("$nameWithoutExtension.$extension")
    }
}
