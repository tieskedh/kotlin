package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import java.io.File

object DotNetIlAssembler {
    private const val PROVISION_SCRIPT = "compiler/ir/backend.dotnet/tools/provision-dotnet-toolchain.ps1"

    fun assembleExecutable(ilFile: File, output: File, target: DotNetTarget, messageCollector: MessageCollector) {
        when (target) {
            DotNetTarget.NET_FRAMEWORK -> assembleForNetFramework(ilFile, output, messageCollector)
            DotNetTarget.NET -> assembleForNet(ilFile, output, messageCollector)
        }
    }

    private fun assembleForNetFramework(ilFile: File, output: File, messageCollector: MessageCollector) {
        val ilasm = findFrameworkIlasm()
        if (ilasm == null) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "Cannot assemble ${output.path}: ilasm was not found. Install a .NET Framework SDK or set ILASM to ilasm.exe."
            )
            return
        }
        runIlasm(ilasm, ilFile, output, dll = false, messageCollector)
    }

    private fun assembleForNet(ilFile: File, output: File, messageCollector: MessageCollector) {
        val ilasm = findModernIlasm()
        if (ilasm == null) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "Cannot assemble ${output.path}: no modern .NET ilasm was found for -Xdotnet-target=net. " +
                        "Provision the toolchain with $PROVISION_SCRIPT, " +
                        "or set KOTLIN_DOTNET_ILASM to an ilasm.exe / KOTLIN_DOTNET_ROOT to a toolchain root."
            )
            return
        }
        if (!runIlasm(ilasm, ilFile, output, dll = true, messageCollector)) return
        writeRuntimeConfig(output)
    }

    private fun runIlasm(ilasm: File, ilFile: File, output: File, dll: Boolean, messageCollector: MessageCollector): Boolean {
        output.parentFile?.mkdirs()
        // The legacy flag spelling is understood by both the .NET Framework ilasm and the modern
        // CoreCLR ilasm (probed on 10.0.9), so a single invocation shape covers both targets.
        val process = ProcessBuilder(
            ilasm.absolutePath,
            "/nologo",
            "/quiet",
            if (dll) "/dll" else "/exe",
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
            return false
        }
        return true
    }

    /**
     * Writes `<name>.runtimeconfig.json` next to [dll]; without it, `dotnet exec` refuses to run
     * the assembly (hostpolicy.dll resolution error). The framework version is the
     * `<major>.<minor>.0` family of the newest runtime installed in the discovered dotnet root
     * combined with `rollForward: LatestMinor`, so the config keeps working across runtime
     * servicing updates. Falls back to net10.0/10.0.0 when no dotnet host is discoverable (the
     * dll itself assembles fine and may be executed on another machine).
     */
    private fun writeRuntimeConfig(dll: File) {
        val runtimeVersion = findModernDotNetHost()?.let(::newestInstalledRuntimeVersion)
        val (major, minor) = runtimeVersion ?: listOf(10, 0)
        val configFile = (dll.parentFile ?: File(".")).resolve("${dll.nameWithoutExtension}.runtimeconfig.json")
        configFile.writeText(
            """
            {
              "runtimeOptions": {
                "tfm": "net$major.0",
                "framework": {
                  "name": "Microsoft.NETCore.App",
                  "version": "$major.$minor.0"
                },
                "rollForward": "LatestMinor"
              }
            }
            """.trimIndent()
        )
    }

    /** Returns `[major, minor]` of the newest `shared/Microsoft.NETCore.App/<version>` in [dotnetHost]'s root, or null. */
    private fun newestInstalledRuntimeVersion(dotnetHost: File): List<Int>? {
        val runtimeDirectory = dotnetHost.parentFile?.resolve("shared/Microsoft.NETCore.App") ?: return null
        return runtimeDirectory.listFiles()
            ?.filter(File::isDirectory)
            ?.mapNotNull { directory ->
                val components = directory.name.split('.').map { it.substringBefore('-') }
                val major = components.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val minor = components.getOrNull(1)?.toIntOrNull() ?: 0
                val patch = components.getOrNull(2)?.toIntOrNull() ?: 0
                listOf(major, minor, patch)
            }
            ?.maxWithOrNull(compareBy({ it[0] }, { it[1] }, { it[2] }))
            ?.take(2)
    }

    private fun findFrameworkIlasm(): File? {
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

    /**
     * Modern (CoreCLR) ilasm discovery, following the contract in `compiler/ir/backend.dotnet/AGENTS.md`:
     * `KOTLIN_DOTNET_ILASM` (direct path), then `KOTLIN_DOTNET_ROOT/ilasm/`, then the durable
     * per-user toolchain provisioned by [PROVISION_SCRIPT]. A system-wide .NET SDK/runtime install
     * is intentionally not probed here: it ships no ilasm (ilasm only comes from the NuGet package
     * the provision script downloads).
     */
    private fun findModernIlasm(): File? {
        System.getenv("KOTLIN_DOTNET_ILASM")?.let { path ->
            File(path).takeIf(File::isFile)?.let { return it }
        }
        return modernToolchainRoots().firstNotNullOfOrNull { root ->
            listOf("ilasm/ilasm.exe", "ilasm/ilasm").map(root::resolve).firstOrNull(File::isFile)
        }
    }

    /**
     * The `dotnet` host used to derive the runtimeconfig framework version: toolchain roots first,
     * then a system-wide installation (PATH, then the default `C:/Program Files/dotnet`).
     */
    private fun findModernDotNetHost(): File? {
        modernToolchainRoots().firstNotNullOfOrNull { root ->
            listOf("dotnet/dotnet.exe", "dotnet/dotnet").map(root::resolve).firstOrNull(File::isFile)
        }?.let { return it }

        val executableNames = listOf("dotnet.exe", "dotnet")
        System.getenv("PATH")
            ?.split(File.pathSeparatorChar)
            ?.asSequence()
            ?.flatMap { directory -> executableNames.asSequence().map { File(directory, it) } }
            ?.firstOrNull(File::isFile)
            ?.let { return it }

        return File("C:/Program Files/dotnet/dotnet.exe").takeIf(File::isFile)
    }

    /** Candidate toolchain roots containing `ilasm/` and `dotnet/` subdirectories, best first. */
    private fun modernToolchainRoots(): List<File> = buildList {
        System.getenv("KOTLIN_DOTNET_ROOT")?.let { add(File(it)) }
        System.getenv("LOCALAPPDATA")?.let { add(File(it).resolve("kotlinc-dotnet/toolchain")) }
    }
}
