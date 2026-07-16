package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import java.io.File

object DotNetConfigurationKeys {
    val OUTPUT: CompilerConfigurationKey<File> = CompilerConfigurationKey.create("output .NET IL file")
    val ASSEMBLY_NAME: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("output .NET assembly name")
    val TARGET: CompilerConfigurationKey<DotNetTarget> = CompilerConfigurationKey.create("target .NET runtime flavor")
    val CALLABLE_EXPORTS: CompilerConfigurationKey<List<DotNetCallableExport>> =
        CompilerConfigurationKey.create("explicit .NET callable factory exports")
}

/**
 * One explicit CLR-facing callable factory export.
 *
 * This is compiler configuration, not a Kotlin source annotation: the POC can evaluate an export
 * boundary without adding a public Kotlin API. [kotlinFqName] selects one top-level function and
 * [clrMethodName] deliberately makes the CLR overload name an owner choice instead of a backend
 * heuristic.
 */
data class DotNetCallableExport(
    val kotlinFqName: String,
    val clrMethodName: String,
) {
    companion object {
        private val KOTLIN_FQ_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
        private val CLR_METHOD_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Parses the command-line/test spelling `<kotlin-fq-name>=<clr-method-name>`. */
        fun parse(value: String): DotNetCallableExport {
            val separator = value.indexOf('=')
            require(separator > 0 && separator < value.lastIndex && value.indexOf('=', separator + 1) < 0) {
                "expected '<kotlin-fq-name>=<clr-method-name>'"
            }
            val kotlinFqName = value.substring(0, separator)
            val clrMethodName = value.substring(separator + 1)
            require(KOTLIN_FQ_NAME.matches(kotlinFqName)) {
                "'$kotlinFqName' is not a supported Kotlin fully qualified function name"
            }
            require(CLR_METHOD_NAME.matches(clrMethodName)) {
                "'$clrMethodName' is not a supported CLR method name"
            }
            return DotNetCallableExport(kotlinFqName, clrMethodName)
        }
    }
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

var CompilerConfiguration.dotNetCallableExports: List<DotNetCallableExport>
    get() = get(DotNetConfigurationKeys.CALLABLE_EXPORTS, emptyList())
    set(value) {
        put(DotNetConfigurationKeys.CALLABLE_EXPORTS, value)
    }
