package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import java.io.File

object DotNetConfigurationKeys {
    val OUTPUT: CompilerConfigurationKey<File> = CompilerConfigurationKey.create("output .NET IL file")
    val ASSEMBLY_NAME: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("output .NET assembly name")
    val TARGET: CompilerConfigurationKey<DotNetTarget> = CompilerConfigurationKey.create("target .NET runtime flavor")
}

/** The .NET runtime flavor of a produced executable, selected via `-Xdotnet-target`. */
enum class DotNetTarget(val flagValue: String) {
    /** Legacy .NET Framework: an `.exe` assembled by the Framework ilasm, runnable directly. */
    NET_FRAMEWORK("netframework"),

    /** Modern .NET (CoreCLR): a `.dll` assembled by the modern ilasm plus a `runtimeconfig.json`, run via `dotnet exec`. */
    NET("net");

    companion object {
        fun fromFlagValue(value: String): DotNetTarget? = entries.firstOrNull { it.flagValue == value }
    }
}

var CompilerConfiguration.dotNetOutput: File?
    get() = get(DotNetConfigurationKeys.OUTPUT)
    set(value) {
        if (value != null) {
            put(DotNetConfigurationKeys.OUTPUT, value)
        }
    }

var CompilerConfiguration.dotNetAssemblyName: String?
    get() = get(DotNetConfigurationKeys.ASSEMBLY_NAME)
    set(value) {
        if (value != null) {
            put(DotNetConfigurationKeys.ASSEMBLY_NAME, value)
        }
    }

var CompilerConfiguration.dotNetTarget: DotNetTarget
    get() = get(DotNetConfigurationKeys.TARGET, DotNetTarget.NET_FRAMEWORK)
    set(value) {
        put(DotNetConfigurationKeys.TARGET, value)
    }
