package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import java.io.File

data class DotNetModernCSharpToolchain(
    val dotNetHost: File,
    val compiler: File,
    val referenceDirectory: File,
)

object DotNetIlAssembler {
    private const val PROVISION_SCRIPT = "compiler/ir/backend.dotnet/tools/provision-dotnet-toolchain.ps1"

    fun assembleExecutable(ilFile: File, output: File, target: DotNetTarget, messageCollector: MessageCollector): Boolean {
        output.delete()
        if (target == DotNetTarget.NET10_0) runtimeConfigFile(output).delete()
        return when (target) {
            DotNetTarget.NET48 -> assembleForNetFramework(ilFile, output, dll = false, messageCollector)
            DotNetTarget.NET10_0 -> assembleForNet(ilFile, output, writeExecutableConfig = true, messageCollector)
            DotNetTarget.NETSTANDARD_2_0 -> {
                messageCollector.report(
                    CompilerMessageSeverity.ERROR,
                    "Target profile 'netstandard2.0' cannot produce an executable."
                )
                false
            }
        }
    }

    /** Assembles a non-entry-point assembly without creating an executable runtimeconfig. */
    fun assembleLibrary(ilFile: File, output: File, target: DotNetTarget, messageCollector: MessageCollector): Boolean {
        output.delete()
        runtimeConfigFile(output).delete()
        return when (target) {
            DotNetTarget.NET48 -> assembleForNetFramework(ilFile, output, dll = true, messageCollector)
            DotNetTarget.NETSTANDARD_2_0 -> assemblePortableLibrary(ilFile, output, messageCollector)
            DotNetTarget.NET10_0 -> assembleForNet(ilFile, output, writeExecutableConfig = false, messageCollector)
        }
    }

    /** Produces the selected runtime variant for independent metadata and loader conformance tests. */
    @TestOnly
    fun assembleRuntimeForTests(
        outputDirectory: File,
        target: DotNetTarget,
        messageCollector: MessageCollector,
    ): File? = DotNetRuntimeLibrary.assembleNextTo(
        outputDirectory.resolve("runtime-conformance-placeholder"),
        target,
        messageCollector,
    )

    /** Reassembles one profile-selected runtime definition with a test-selected compatibility writer. */
    @TestOnly
    fun assembleRuntimeWithExplicitIlasmForTests(
        outputDirectory: File,
        target: DotNetTarget,
        ilasm: File,
        messageCollector: MessageCollector,
    ): File? = DotNetRuntimeLibrary.assembleWithExplicitIlasmForTests(
        outputDirectory,
        target,
        ilasm,
        messageCollector,
    )

    /**
     * Assembles a portable library with the modern ILAsm. Framework ILAsm accepts netstandard
     * source but injects an `mscorlib` AssemblyRef into the PE, so it is a compatibility oracle,
     * not a writer for the canonical netstandard2.0 asset.
     */
    fun assemblePortableLibrary(ilFile: File, output: File, messageCollector: MessageCollector): Boolean {
        output.delete()
        runtimeConfigFile(output).delete()
        val ilasm = findModernIlasm()
        if (ilasm == null) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "Cannot assemble portable library ${output.path}: no modern .NET ilasm was found. " +
                        "Provision the toolchain with $PROVISION_SCRIPT, " +
                        "or set KOTLIN_DOTNET_ILASM to an ilasm.exe / KOTLIN_DOTNET_ROOT to a toolchain root."
            )
            return false
        }
        return runIlasm(ilasm, ilFile, output, dll = true, deterministic = true, messageCollector)
    }

    /**
     * Runs an explicitly selected ILAsm as a compatibility oracle over already-targeted IL.
     *
     * Production must select an assembler through [assembleExecutable] or [assembleLibrary],
     * because an accepting tool does not change the IL's target profile. This hook exists only
     * for same-/cross-assembler tests that need to vary the PE writer independently of the
     * compiler-selected profile and artifact kind.
     */
    @TestOnly
    fun assembleWithExplicitIlasm(
        ilasm: File,
        ilFile: File,
        output: File,
        dll: Boolean,
        messageCollector: MessageCollector,
    ): Boolean {
        output.delete()
        runtimeConfigFile(output).delete()
        return runIlasm(ilasm, ilFile, output, dll, deterministic = true, messageCollector)
    }

    private fun assembleForNetFramework(
        ilFile: File,
        output: File,
        dll: Boolean,
        messageCollector: MessageCollector,
    ): Boolean {
        val ilasm = findFrameworkIlasm()
        if (ilasm == null) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "Cannot assemble ${output.path}: ilasm was not found. Install a .NET Framework SDK or set ILASM to ilasm.exe."
            )
            return false
        }
        return runIlasm(ilasm, ilFile, output, dll, deterministic = true, messageCollector)
    }

    private fun assembleForNet(
        ilFile: File,
        output: File,
        writeExecutableConfig: Boolean,
        messageCollector: MessageCollector,
    ): Boolean {
        val ilasm = findModernIlasm()
        if (ilasm == null) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "Cannot assemble ${output.path}: no modern .NET ilasm was found for -Xdotnet-target=net10.0. " +
                        "Provision the toolchain with $PROVISION_SCRIPT, " +
                        "or set KOTLIN_DOTNET_ILASM to an ilasm.exe / KOTLIN_DOTNET_ROOT to a toolchain root."
            )
            return false
        }
        if (!runIlasm(ilasm, ilFile, output, dll = true, deterministic = true, messageCollector)) return false
        if (writeExecutableConfig) writeRuntimeConfig(output)
        return true
    }

    private fun runIlasm(
        ilasm: File,
        ilFile: File,
        output: File,
        dll: Boolean,
        deterministic: Boolean,
        messageCollector: MessageCollector,
    ): Boolean {
        output.parentFile?.mkdirs()
        // The legacy flag spelling is understood by both the .NET Framework ilasm and the modern
        // CoreCLR ilasm (probed on 10.0.9), so a single invocation shape covers both targets.
        val arguments = buildList {
            add(ilasm.absolutePath)
            add("/nologo")
            add("/quiet")
            if (deterministic) add("/det")
            add(if (dll) "/dll" else "/exe")
            add("/output:${output.absolutePath}")
            add(ilFile.absolutePath)
        }
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        val outputText = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            output.delete()
            runtimeConfigFile(output).delete()
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
        val [major, minor] = runtimeVersion ?: listOf(10, 0)
        val configFile = runtimeConfigFile(dll)
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

    private fun runtimeConfigFile(dll: File): File =
        (dll.parentFile ?: File(".")).resolve("${dll.nameWithoutExtension}.runtimeconfig.json")

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

    /** Framework ILAsm discovery, exposed so target-specific integration gates can skip coherently. */
    fun findFrameworkIlasm(): File? {
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
     * .NET Framework C# compiler discovery for cross-language ABI tests. This is intentionally
     * separate from ILAsm discovery: producing Kotlin IL must not acquire a Roslyn dependency,
     * while the interop test lane must compile an ordinary foreign consumer rather than infer
     * C# accessibility from IL text alone.
     */
    fun findFrameworkCSharpCompiler(): File? {
        System.getenv("CSC")?.let { path ->
            File(path).takeIf(File::isFile)?.let { return it }
        }

        System.getenv("PATH")
            ?.split(File.pathSeparatorChar)
            ?.asSequence()
            ?.map { directory -> File(directory, "csc.exe") }
            ?.firstOrNull(File::isFile)
            ?.let { return it }

        return listOf(
            File("C:/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe"),
            File("C:/Windows/Microsoft.NET/Framework/v4.0.30319/csc.exe"),
        ).firstOrNull(File::isFile)
    }

    /**
     * Finds the signed Windows PowerShell host used by tests to load net48 assemblies on CLR 4.
     * Loading the managed entry point through this host avoids direct activation of an unsigned
     * executable while preserving the exact assembly and Framework runtime semantics.
     */
    fun findFrameworkPowerShellHost(): File? =
        System.getenv("WINDIR")
            ?.let(::File)
            ?.resolve("System32/WindowsPowerShell/v1.0/powershell.exe")
            ?.takeIf(File::isFile)

    /**
     * Finds the Roslyn compiler and net10 reference pack installed by the developer toolchain.
     * This remains test-only infrastructure: Kotlin IL production has no C# compiler dependency.
     */
    fun findModernCSharpCompiler(): DotNetModernCSharpToolchain? {
        val dotNetRoots = buildList {
            modernToolchainRoots().mapTo(this) { root -> root.resolve("dotnet") }
            findModernDotNetHost()?.parentFile?.let(::add)
        }.distinctBy { root -> root.absolutePath.lowercase() }

        for (root in dotNetRoots) {
            val dotNetHost = listOf(root.resolve("dotnet.exe"), root.resolve("dotnet"))
                .firstOrNull(File::isFile)
                ?: continue
            val sdkDirectory = newestVersionedDirectory(root.resolve("sdk")) { directory ->
                directory.resolve("Roslyn/bincore/csc.dll").isFile
            } ?: continue
            val referencePackDirectory = newestVersionedDirectory(
                root.resolve("packs/Microsoft.NETCore.App.Ref")
            ) { directory ->
                directory.resolve("ref/net10.0").isDirectory
            } ?: continue
            return DotNetModernCSharpToolchain(
                dotNetHost = dotNetHost,
                compiler = sdkDirectory.resolve("Roslyn/bincore/csc.dll"),
                referenceDirectory = referencePackDirectory.resolve("ref/net10.0"),
            )
        }
        return null
    }

    /**
     * Modern (CoreCLR) ilasm discovery, following the contract in `compiler/ir/backend.dotnet/AGENTS.md`:
     * `KOTLIN_DOTNET_ILASM` (direct path), then `KOTLIN_DOTNET_ROOT/ilasm/`, then the durable
     * per-user toolchain provisioned by [PROVISION_SCRIPT]. A system-wide .NET SDK/runtime install
     * is intentionally not probed here: it ships no ilasm (ilasm only comes from the NuGet package
     * the provision script downloads).
     *
     * Public because the box-test runner reuses this discovery contract to decide whether the
     * modern toolchain is available (skipping the tests when it is not).
     */
    fun findModernIlasm(): File? {
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
     *
     * Public because the box-test runner reuses this discovery contract to launch assembled dlls
     * via `dotnet exec` (and to skip the tests when no host is available).
     */
    fun findModernDotNetHost(): File? {
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

    private fun newestVersionedDirectory(parent: File, accepts: (File) -> Boolean): File? =
        parent.listFiles()
            ?.filter { directory -> directory.isDirectory && accepts(directory) }
            ?.maxWithOrNull(
                compareBy<File>({ it.versionComponent(0) }, { it.versionComponent(1) },
                    { it.versionComponent(2) }, { it.versionComponent(3) })
            )

    private fun File.versionComponent(index: Int): Int =
        name.substringBefore('-').split('.').getOrNull(index)?.toIntOrNull() ?: 0

    /** Candidate toolchain roots containing `ilasm/` and `dotnet/` subdirectories, best first. */
    private fun modernToolchainRoots(): List<File> = buildList {
        System.getenv("KOTLIN_DOTNET_ROOT")?.let { add(File(it)) }
        System.getenv("LOCALAPPDATA")?.let { add(File(it).resolve("kotlinc-dotnet/toolchain")) }
    }
}
