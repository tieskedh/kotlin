package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import java.io.File

/**
 * The first physical Kotlin/.NET runtime boundary.
 *
 * This deliberately contains no callable ABI yet. It establishes the durable assembly identity
 * and namespace/type ownership before function objects start depending on it. The same TFM-neutral
 * IL source is assembled with the selected target's ILAsm, so both targets produce their own PE
 * while exposing exactly the same logical assembly identity.
 */
internal object DotNetRuntimeLibrary {
    const val ASSEMBLY_NAME = "Kotlin.Runtime"
    const val ASSEMBLY_FILE_NAME = "$ASSEMBLY_NAME.dll"
    const val ASSEMBLY_VERSION_IL = "1:0:0:0"

    fun assembleNextTo(
        executableOutput: File,
        target: DotNetTarget,
        messageCollector: MessageCollector,
    ): File? {
        val outputDirectory = executableOutput.parentFile ?: File(".")
        outputDirectory.mkdirs()
        val output = outputDirectory.resolve(ASSEMBLY_FILE_NAME)
        val ilFile = File.createTempFile("Kotlin.Runtime-", ".il", outputDirectory)
        return try {
            // ILAsm decodes BOM-less input as ANSI; keep the runtime source on the same UTF-8+BOM
            // path as generated program IL even though its current text is ASCII-only.
            ilFile.writeBytes(UTF8_BOM + IL_TEXT.toByteArray(Charsets.UTF_8))
            output.takeIf { DotNetIlAssembler.assembleLibrary(ilFile, output, target, messageCollector) }
        } finally {
            ilFile.delete()
        }
    }

    private val IL_TEXT = """
        .assembly extern mscorlib {}
        .assembly Kotlin.Runtime
        {
          .ver $ASSEMBLY_VERSION_IL
        }
        .module Kotlin.Runtime.dll

        .namespace Kotlin.Runtime
        {
          .class public abstract sealed auto ansi beforefieldinit RuntimeInfo
                 extends [mscorlib]System.Object
          {
          }
        }
    """.trimIndent() + "\n"

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
