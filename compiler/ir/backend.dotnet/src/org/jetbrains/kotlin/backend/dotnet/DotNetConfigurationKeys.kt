package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import java.io.File

object DotNetConfigurationKeys {
    val OUTPUT: CompilerConfigurationKey<File> = CompilerConfigurationKey.create("output .NET IL file")
    val ASSEMBLY_NAME: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("output .NET assembly name")
    val TARGET: CompilerConfigurationKey<DotNetTarget> = CompilerConfigurationKey.create("target .NET runtime flavor")
    val EXPORTS: CompilerConfigurationKey<List<DotNetExport>> =
        CompilerConfigurationKey.create("explicit .NET exports")
    val PROPERTY_EXPORTS: CompilerConfigurationKey<List<DotNetPropertyExport>> =
        CompilerConfigurationKey.create("explicit .NET property exports")
}

/**
 * One provisional selection of a unique top-level property for CLR property-shape evaluation.
 *
 * This intentionally has no parameter/type grammar and is separate from [DotNetExport]. It is
 * POC control-plane state, not metadata or a candidate source annotation contract.
 */
data class DotNetPropertyExport(
    val kotlinFqName: String,
    val clrPropertyName: String,
) {
    companion object {
        private val KOTLIN_FQ_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
        private val CLR_PROPERTY_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        fun parse(value: String): DotNetPropertyExport {
            val separator = value.indexOf('=')
            require(separator > 0 && separator < value.lastIndex && value.indexOf('=', separator + 1) < 0) {
                "expected '<kotlin-fq-name>=<clr-property-name>'"
            }
            val kotlinFqName = value.substring(0, separator)
            val clrPropertyName = value.substring(separator + 1)
            require(KOTLIN_FQ_NAME.matches(kotlinFqName)) {
                "'$kotlinFqName' is not a supported Kotlin fully qualified property name"
            }
            require(CLR_PROPERTY_NAME.matches(clrPropertyName)) {
                "'$clrPropertyName' is not a supported CLR property name"
            }
            return DotNetPropertyExport(kotlinFqName, clrPropertyName)
        }
    }
}

/**
 * One explicit CLR-facing function export.
 *
 * This is compiler configuration, not a Kotlin source annotation: the POC can evaluate an export
 * boundary without adding a public Kotlin API. [kotlinFqName] selects one top-level function;
 * [clrMethodName] deliberately makes the CLR overload name an owner choice instead of a backend
 * heuristic. Callable positions, when present, are projected through typed Func/Action shapes.
 */
data class DotNetExport(
    val kotlinFqName: String,
    val kotlinParameterSignature: String?,
    val clrMethodName: String,
) {
    val kotlinSelector: String
        get() = buildString {
            append(kotlinFqName)
            kotlinParameterSignature?.let { signature ->
                append('(')
                append(signature)
                append(')')
            }
        }

    companion object {
        private val KOTLIN_FQ_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
        private val KOTLIN_PARAMETER_SIGNATURE = Regex("[A-Za-z0-9_.,?*<>]*")
        private val CLR_METHOD_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Parses `<kotlin-fq-name>[(<expanded-parameter-types>)]=<clr-method-name>`. */
        fun parse(value: String): DotNetExport {
            val separator = value.indexOf('=')
            require(separator > 0 && separator < value.lastIndex && value.indexOf('=', separator + 1) < 0) {
                "expected '<kotlin-selector>=<clr-method-name>'"
            }
            val kotlinSelector = value.substring(0, separator)
            val clrMethodName = value.substring(separator + 1)
            val signatureStart = kotlinSelector.indexOf('(')
            val kotlinFqName: String
            val kotlinParameterSignature: String?
            if (signatureStart < 0) {
                kotlinFqName = kotlinSelector
                kotlinParameterSignature = null
            } else {
                require(kotlinSelector.endsWith(')') && kotlinSelector.indexOf('(', signatureStart + 1) < 0) {
                    "expected a selector in '<kotlin-fq-name>(<parameter-types>)' form"
                }
                kotlinFqName = kotlinSelector.substring(0, signatureStart)
                kotlinParameterSignature = kotlinSelector.substring(signatureStart + 1, kotlinSelector.lastIndex)
                require(KOTLIN_PARAMETER_SIGNATURE.matches(kotlinParameterSignature)) {
                    "parameter signature '$kotlinParameterSignature' contains unsupported characters"
                }
                require(kotlinParameterSignature.hasBalancedTypeArguments()) {
                    "parameter signature '$kotlinParameterSignature' has unbalanced type arguments"
                }
            }
            require(KOTLIN_FQ_NAME.matches(kotlinFqName)) {
                "'$kotlinFqName' is not a supported Kotlin fully qualified function name"
            }
            require(CLR_METHOD_NAME.matches(clrMethodName)) {
                "'$clrMethodName' is not a supported CLR method name"
            }
            return DotNetExport(kotlinFqName, kotlinParameterSignature, clrMethodName)
        }

        private fun String.hasBalancedTypeArguments(): Boolean {
            var depth = 0
            for (character in this) {
                when (character) {
                    '<' -> depth++
                    '>' -> {
                        depth--
                        if (depth < 0) return false
                    }
                }
            }
            return depth == 0
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

var CompilerConfiguration.dotNetExports: List<DotNetExport>
    get() = get(DotNetConfigurationKeys.EXPORTS, emptyList())
    set(value) {
        put(DotNetConfigurationKeys.EXPORTS, value)
    }

var CompilerConfiguration.dotNetPropertyExports: List<DotNetPropertyExport>
    get() = get(DotNetConfigurationKeys.PROPERTY_EXPORTS, emptyList())
    set(value) {
        put(DotNetConfigurationKeys.PROPERTY_EXPORTS, value)
    }
