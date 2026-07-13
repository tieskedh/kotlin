package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import java.io.File

object DotNetIlAssembler {
    fun assembleExecutable(ilFile: File, output: File, messageCollector: MessageCollector) {
        val ilasm = findIlasm()
        if (ilasm == null) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "Cannot assemble ${output.path}: ilasm was not found. Install a .NET Framework SDK or set ILASM to ilasm.exe."
            )
            return
        }

        output.parentFile?.mkdirs()
        val process = ProcessBuilder(
            ilasm.absolutePath,
            "/nologo",
            "/quiet",
            "/exe",
            "/output:${output.absolutePath}",
            ilFile.absolutePath,
        ).redirectErrorStream(true).start()
        val outputText = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "ilasm failed with exit code $exitCode${outputText.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
            )
        }
    }

    private fun findIlasm(): File? {
        System.getenv("ILASM")?.let { path ->
            File(path).takeIf(File::isFile)?.let { return it }
        }

        val executableNames = listOf("ilasm.exe", "ilasm")
        System.getenv("PATH")
            ?.split(File.pathSeparatorChar)
            ?.asSequence()
            ?.flatMap { directory -> executableNames.asSequence().map { File(directory, it) } }
            ?.firstOrNull(File::isFile)
            ?.let { return it }

        return listOf(
            File("C:/Windows/Microsoft.NET/Framework64/v4.0.30319/ilasm.exe"),
            File("C:/Windows/Microsoft.NET/Framework/v4.0.30319/ilasm.exe"),
        ).firstOrNull(File::isFile)
    }
}
