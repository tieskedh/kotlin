package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.MessageCollectorAccess
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
        // The .NET backend has no IrDiagnosticReporter-based reporting yet; it deliberately talks
        // to the message collector directly, like DotNetIlEmitter and DotNetIlAssembler.
        @OptIn(MessageCollectorAccess::class)
        val messageCollector = configuration.messageCollector
        val output = configuration.dotNetOutput ?: error("Missing .NET output")
        val target = configuration.dotNetTarget
        val assemblyName = configuration.dotNetAssemblyName ?: output.nameWithoutExtension
        val producesStdlib = configuration.dotNetProducesStdlib
        val emitsExecutable = !producesStdlib && !output.isDirectory && when (target) {
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
                messageCollector.report(
                    CompilerMessageSeverity.INFO,
                    "The 'net' target produces a .dll started via 'dotnet exec' instead of a standalone .exe; writing '${it.path}'."
                )
            }
        } else {
            output
        }
        val ilTarget = when {
            producesStdlib -> output.resolve(DotNetStdlibLibrary.ASSEMBLY_IL_FILE_NAME)
            output.isDirectory -> output.resolve("$assemblyName.il")
            emitsExecutable -> binaryOutput.siblingWithExtension("il")
            else -> output
        }
        ilTarget.parentFile?.mkdirs()

        val reservedAssembly = if (producesStdlib) null else listOf(
            DotNetRuntimeLibrary.ASSEMBLY_NAME to DotNetRuntimeLibrary.ASSEMBLY_FILE_NAME,
            DotNetStdlibLibrary.ASSEMBLY_NAME to DotNetStdlibLibrary.ASSEMBLY_FILE_NAME,
        ).firstOrNull { reserved ->
            assemblyName.equals(reserved.first, ignoreCase = true) ||
                    (emitsExecutable && binaryOutput.name.equals(reserved.second, ignoreCase = true))
        }
        if (reservedAssembly != null) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "'${reservedAssembly.first}' is reserved for a Kotlin/.NET platform assembly; " +
                        "choose a different module name and output file."
            )
            if (emitsExecutable) binaryOutput.delete()
            ilTarget.delete()
            return if (emitsExecutable) binaryOutput else ilTarget
        }
        if (emitsExecutable) {
            // Clear module-specific artifacts before lowering. A lowering/emission/runtime/ILAsm
            // failure must never leave a previous successful program looking current. The shared
            // runtime is intentionally not removed: another valid program in the directory may
            // still depend on it.
            binaryOutput.delete()
            if (target == DotNetTarget.NET) binaryOutput.runtimeConfigFile().delete()
        } else if (producesStdlib) {
            output.resolve(DotNetStdlibLibrary.ASSEMBLY_FILE_NAME).delete()
            ilTarget.delete()
        }

        val context = DotNetBackendContext(irBuiltIns, configuration, symbolTable, irModuleFragment)
        try {
            configuration.perfManager.tryMeasurePhaseTime(PhaseType.IrLowering) {
                DotNetLoweringPhases.lower(irModuleFragment, context)
            }
        } catch (e: DotNetIlUnsupportedException) {
            // A lowering rejected the module up front (e.g. the local-class guard of
            // DotNetInitializersLowering). Unlike codegen-time rejections there is no function
            // granularity to skip at, so the whole compilation fails with one loud diagnostic
            // instead of an internal assertion crash further down.
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "The module is not supported by the .NET backend: ${e.reason}"
            )
            ilTarget.delete()
            return ilTarget
        }

        val stdlibIlText = if (DotNetStdlibLibrary.hasImplementation(irModuleFragment)) {
            DotNetIlEmitter(
                messageCollector = messageCollector,
                assemblyName = DotNetStdlibLibrary.ASSEMBLY_NAME,
                moduleFileName = DotNetStdlibLibrary.ASSEMBLY_FILE_NAME,
                producesExecutable = false,
                irBuiltIns = irBuiltIns,
                propertyReferenceFactoryFunctions = context.propertyReferenceSymbols.implementedFactories(),
                emissionScope = DotNetIlEmissionScope.STDLIB,
            ).emit(irModuleFragment) ?: return ilTarget
        } else {
            null
        }

        if (producesStdlib) {
            if (stdlibIlText == null) {
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    "The explicit stdlib build did not contain compiler-owned stdlib implementations."
                )
                return output.resolve(DotNetStdlibLibrary.ASSEMBLY_FILE_NAME)
            }
            return DotNetStdlibLibrary.assembleIn(output, stdlibIlText, target, messageCollector)
                ?: output.resolve(DotNetStdlibLibrary.ASSEMBLY_FILE_NAME)
        }

        val emitter = DotNetIlEmitter(
            messageCollector = messageCollector,
            assemblyName = assemblyName,
            moduleFileName = if (emitsExecutable) binaryOutput.name else ilTarget.name,
            producesExecutable = emitsExecutable,
            irBuiltIns = irBuiltIns,
            propertyReferenceFactoryFunctions = context.propertyReferenceSymbols.implementedFactories(),
            exports = configuration.dotNetExports,
            propertyExports = configuration.dotNetPropertyExports,
        )
        val ilText = emitter.emit(irModuleFragment)
        if (ilText == null) {
            // Emission failed (the error is in the message collector). Remove any stale output of
            // a previous successful compilation so callers never see outdated content.
            ilTarget.delete()
            return ilTarget
        }
        if ("[${DotNetStdlibLibrary.ASSEMBLY_NAME}]" in ilText && stdlibIlText == null) {
            val externalStdlib = configuration.dotNetExternalStdlib
            if (externalStdlib == null) {
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    "The generated module requires '${DotNetStdlibLibrary.ASSEMBLY_NAME}', but neither " +
                            "injected implementation source nor a bound metadata-KLIB/CLR-DLL pair was supplied. " +
                            "Compile without -no-stdlib or add the target stdlib metadata KLIB to the classpath."
                )
                ilTarget.delete()
                return ilTarget
            }
            if (!externalStdlib.implementationFile.isFile) {
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    "The Kotlin/.NET stdlib metadata '${externalStdlib.metadataFile.path}' is bound to " +
                            "missing CLR assembly '${externalStdlib.implementationFile.path}'."
                )
                ilTarget.delete()
                return ilTarget
            }
            if (emitsExecutable) {
                val packagedStdlib = (binaryOutput.parentFile ?: File("."))
                    .resolve(DotNetStdlibLibrary.ASSEMBLY_FILE_NAME)
                if (externalStdlib.implementationFile.canonicalFile != packagedStdlib.canonicalFile) {
                    externalStdlib.implementationFile.copyTo(packagedStdlib, overwrite = true)
                }
            }
        }
        // ilasm decodes a BOM-less file as ANSI, mangling every multi-byte UTF-8 sequence (e.g. in
        // string literals), so the .il file must be written as UTF-8 *with* a BOM.
        ilTarget.writeBytes(UTF8_BOM + ilText.toByteArray(Charsets.UTF_8))

        if (emitsExecutable) {
            if (DotNetRuntimeLibrary.assembleNextTo(binaryOutput, target, messageCollector) == null) return binaryOutput
            if (stdlibIlText != null &&
                DotNetStdlibLibrary.assembleNextTo(binaryOutput, stdlibIlText, target, messageCollector) == null
            ) {
                return binaryOutput
            }
            DotNetIlAssembler.assembleExecutable(ilTarget, binaryOutput, target, messageCollector)
            return binaryOutput
        }

        return ilTarget
    }

    private fun File.siblingWithExtension(extension: String): File {
        return (parentFile ?: File(".")).resolve("$nameWithoutExtension.$extension")
    }

    private fun File.runtimeConfigFile(): File =
        (parentFile ?: File(".")).resolve("$nameWithoutExtension.runtimeconfig.json")

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
