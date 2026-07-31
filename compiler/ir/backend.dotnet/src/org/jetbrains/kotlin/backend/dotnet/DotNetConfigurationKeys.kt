package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.load.dotnet.DotNetClrClasspathAssembly
import java.io.File

object DotNetConfigurationKeys {
    val OUTPUT: CompilerConfigurationKey<File> = CompilerConfigurationKey.create("output .NET IL file")
    val ASSEMBLY_NAME: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("output .NET assembly name")
    val PRODUCE_STDLIB: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("produce the bootstrap Kotlin/.NET stdlib assembly")
    val PRODUCE_LIBRARY: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey.create("produce a Kotlin/.NET library assembly")
    val TARGET: CompilerConfigurationKey<DotNetTarget> = CompilerConfigurationKey.create("target .NET API/runtime profile")
    val EXPORTS: CompilerConfigurationKey<List<DotNetExport>> =
        CompilerConfigurationKey.create("explicit .NET exports")
    val PROPERTY_EXPORTS: CompilerConfigurationKey<List<DotNetPropertyExport>> =
        CompilerConfigurationKey.create("explicit .NET property exports")
    val EXTERNAL_STDLIB: CompilerConfigurationKey<DotNetExternalStdlib> =
        CompilerConfigurationKey.create("external Kotlin/.NET stdlib assembly")
    val EXTERNAL_LIBRARIES: CompilerConfigurationKey<List<DotNetExternalLibrary>> =
        CompilerConfigurationKey.create("external Kotlin/.NET library assemblies")
    val EXTERNAL_CLR_ASSEMBLIES: CompilerConfigurationKey<List<DotNetClrClasspathAssembly.WithoutCarrier>> =
        CompilerConfigurationKey.create("external foreign CLR assemblies")
    val FRIEND_PATHS: CompilerConfigurationKey<List<String>> =
        CompilerConfigurationKey.create("Kotlin/.NET friend assembly paths")
    val FRIEND_ASSEMBLIES: CompilerConfigurationKey<List<DotNetFriendAssemblyIdentity>> =
        CompilerConfigurationKey.create("producer-authorized CLR friend assembly identities")
}

/** Canonical names of compiler-owned CLR assemblies; CLR assembly-name matching is case-insensitive. */
object DotNetPlatformAssemblyIdentity {
    const val RUNTIME_ASSEMBLY_NAME = "Kotlin.Runtime"
    const val STDLIB_ASSEMBLY_NAME = "Kotlin.Stdlib"

    fun canonicalNameOrNull(assemblyName: String): String? = when {
        isRuntime(assemblyName) -> RUNTIME_ASSEMBLY_NAME
        isStdlib(assemblyName) -> STDLIB_ASSEMBLY_NAME
        else -> null
    }

    fun isRuntime(assemblyName: String): Boolean =
        assemblyName.equals(RUNTIME_ASSEMBLY_NAME, ignoreCase = true)

    fun isStdlib(assemblyName: String): Boolean =
        assemblyName.equals(STDLIB_ASSEMBLY_NAME, ignoreCase = true)
}

/** Stable manifest and CLR identity shared by the CLI dependency loader and IL backend. */
object DotNetStdlibArtifact {
    const val DISTRIBUTION_DIRECTORY_NAME = "dotnet"
    const val ASSEMBLY_NAME = DotNetPlatformAssemblyIdentity.STDLIB_ASSEMBLY_NAME
    const val ASSEMBLY_FILE_NAME = "$ASSEMBLY_NAME.dll"
    const val ASSEMBLY_VERSION = "1.0.0.0"
    const val ASSEMBLY_CULTURE = "neutral"
    const val ASSEMBLY_PUBLIC_KEY_TOKEN = "null"
    const val METADATA_UNIQUE_NAME = ASSEMBLY_NAME
    fun distributionDirectory(kotlinLibDirectory: File, targetFramework: String): File =
        kotlinLibDirectory.resolve(DISTRIBUTION_DIRECTORY_NAME).resolve(targetFramework)
}

/** Stable pre-publication identity of the runtime half of an installed platform pair. */
object DotNetRuntimeArtifact {
    const val ASSEMBLY_NAME = DotNetPlatformAssemblyIdentity.RUNTIME_ASSEMBLY_NAME
    const val ASSEMBLY_FILE_NAME = "$ASSEMBLY_NAME.dll"
    const val ASSEMBLY_VERSION = "1.0.0.0"
    const val ASSEMBLY_CULTURE = "neutral"
}

/** The CLR identity of one self-describing Kotlin/.NET library. */
data class DotNetLibraryArtifact(
    val assemblyName: String,
    val targetFramework: String,
    val assemblyVersion: String = DEFAULT_ASSEMBLY_VERSION,
    val assemblyCulture: String = DEFAULT_ASSEMBLY_CULTURE,
    val assemblyPublicKeyToken: String = DEFAULT_ASSEMBLY_PUBLIC_KEY_TOKEN,
) {
    val assemblyFileName: String = "$assemblyName.dll"
    val assemblyIlFileName: String = "$assemblyName.il"
    val assemblyVersionIl: String = assemblyVersion.replace('.', ':')

    companion object {
        const val METADATA_ASSEMBLY_NAME_PROPERTY = "dotnet_assembly_name"
        const val METADATA_ASSEMBLY_VERSION_PROPERTY = "dotnet_assembly_version"
        const val METADATA_ASSEMBLY_CULTURE_PROPERTY = "dotnet_assembly_culture"
        const val METADATA_ASSEMBLY_PUBLIC_KEY_TOKEN_PROPERTY = "dotnet_assembly_public_key_token"
        const val METADATA_ASSEMBLY_FILE_PROPERTY = "dotnet_assembly_file"
        const val METADATA_LIBRARY_TARGET_FRAMEWORK_PROPERTY = "dotnet_library_tfm"
        const val DEFAULT_ASSEMBLY_VERSION = "1.0.0.0"
        const val DEFAULT_ASSEMBLY_CULTURE = "neutral"
        const val DEFAULT_ASSEMBLY_PUBLIC_KEY_TOKEN = "null"
    }
}

/** The self-describing Kotlin/.NET standard-library assembly selected for compilation. */
data class DotNetExternalStdlib(
    val assemblyFile: File,
    val targetFramework: String,
    val runtimeAssemblyFile: File? = null,
)

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

/** Target-framework/API profile, independent of executable versus library product kind. */
enum class DotNetTarget(val flagValue: String) {
    NET48("net48"),
    NETSTANDARD_2_0("netstandard2.0"),
    NET10_0("net10.0");

    val supportsExecutables: Boolean
        get() = this != NETSTANDARD_2_0

    internal val coreLibrary: DotNetCoreLibraryProfile
        get() = when (this) {
            NET48 -> DotNetCoreLibraryProfile.NET48
            NETSTANDARD_2_0 -> DotNetCoreLibraryProfile.NETSTANDARD_2_0
            NET10_0 -> DotNetCoreLibraryProfile.NET10_0
        }

    fun canConsumeLibrary(targetFramework: String): Boolean = when (this) {
        NET48 -> targetFramework == NET48.flagValue || targetFramework == NETSTANDARD_2_0.flagValue
        NETSTANDARD_2_0 -> targetFramework == NETSTANDARD_2_0.flagValue
        NET10_0 -> targetFramework == NET10_0.flagValue || targetFramework == NETSTANDARD_2_0.flagValue
    }

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

var CompilerConfiguration.dotNetProducesStdlib: Boolean
    get() = getBoolean(DotNetConfigurationKeys.PRODUCE_STDLIB)
    set(value) {
        put(DotNetConfigurationKeys.PRODUCE_STDLIB, value)
    }

var CompilerConfiguration.dotNetProducesLibrary: Boolean
    get() = getBoolean(DotNetConfigurationKeys.PRODUCE_LIBRARY)
    set(value) {
        put(DotNetConfigurationKeys.PRODUCE_LIBRARY, value)
    }

val CompilerConfiguration.dotNetProducedLibraryArtifact: DotNetLibraryArtifact?
    get() = when {
        dotNetProducesStdlib -> DotNetLibraryArtifact(
            assemblyName = DotNetStdlibArtifact.ASSEMBLY_NAME,
            targetFramework = dotNetTarget.flagValue,
            assemblyVersion = DotNetStdlibArtifact.ASSEMBLY_VERSION,
            assemblyCulture = DotNetStdlibArtifact.ASSEMBLY_CULTURE,
            assemblyPublicKeyToken = DotNetStdlibArtifact.ASSEMBLY_PUBLIC_KEY_TOKEN,
        )
        dotNetProducesLibrary -> dotNetAssemblyName?.let { assemblyName ->
            DotNetLibraryArtifact(assemblyName, dotNetTarget.flagValue)
        }
        else -> null
    }

var CompilerConfiguration.dotNetTarget: DotNetTarget
    get() = get(DotNetConfigurationKeys.TARGET, DotNetTarget.NET48)
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

var CompilerConfiguration.dotNetExternalStdlib: DotNetExternalStdlib?
    get() = get(DotNetConfigurationKeys.EXTERNAL_STDLIB)
    set(value) {
        if (value != null) {
            put(DotNetConfigurationKeys.EXTERNAL_STDLIB, value)
        }
    }

var CompilerConfiguration.dotNetExternalLibraries: List<DotNetExternalLibrary>
    get() = get(DotNetConfigurationKeys.EXTERNAL_LIBRARIES, emptyList())
    set(value) {
        put(DotNetConfigurationKeys.EXTERNAL_LIBRARIES, value)
    }

var CompilerConfiguration.dotNetExternalClrAssemblies: List<DotNetClrClasspathAssembly.WithoutCarrier>
    get() = get(DotNetConfigurationKeys.EXTERNAL_CLR_ASSEMBLIES, emptyList())
    set(value) {
        put(DotNetConfigurationKeys.EXTERNAL_CLR_ASSEMBLIES, value)
    }

var CompilerConfiguration.dotNetFriendPaths: List<String>
    get() = get(DotNetConfigurationKeys.FRIEND_PATHS, emptyList())
    set(value) {
        put(DotNetConfigurationKeys.FRIEND_PATHS, value)
    }

var CompilerConfiguration.dotNetFriendAssemblies: List<DotNetFriendAssemblyIdentity>
    get() = get(DotNetConfigurationKeys.FRIEND_ASSEMBLIES, emptyList())
    set(value) {
        put(DotNetConfigurationKeys.FRIEND_ASSEMBLIES, value)
    }
