package org.jetbrains.kotlin.backend.dotnet

private data class DotNetStdlibSourceResource(
    val fileName: String,
    val path: String,
    val isCommon: Boolean = false,
)

// Match the ordinary-source producer's relative-path order: FIR file order affects deterministic
// declaration order in the emitted stdlib IL.
private val DOTNET_STDLIB_SOURCE_RESOURCES = listOf(
    DotNetStdlibSourceResource(
        "DotNetStdlibIo.kt",
        "dotnet/src/kotlin/io/DotNetStdlibIo.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetStdlibCollections.kt",
        "dotnet/src/kotlin/collections/DotNetStdlibCollections.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetStringBuilder.kt",
        "dotnet/src/kotlin/text/DotNetStringBuilder.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetStdlibKotlin.kt",
        "dotnet/src/kotlin/DotNetStdlibKotlin.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetEnum.kt",
        "dotnet/src/kotlin/DotNetEnum.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetEnumEntriesSerializationProxy.kt",
        "dotnet/src/kotlin/enums/DotNetEnumEntriesSerializationProxy.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetSerializationUtil.kt",
        "dotnet/src/kotlin/internal/DotNetSerializationUtil.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetStdlibCancellation.kt",
        "dotnet/src/kotlin/coroutines/cancellation/DotNetStdlibCancellation.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetExceptions.kt",
        "dotnet/src/kotlin/DotNetExceptions.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetKClass.kt",
        "dotnet/src/kotlin/reflect/DotNetKClass.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetKClasses.kt",
        "dotnet/src/kotlin/reflect/DotNetKClasses.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetThrowNoWhenBranchMatchedException.kt",
        "dotnet/src/kotlin/internal/DotNetThrowNoWhenBranchMatchedException.kt",
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapCollections.kt",
        "dotnet/common/src/generated/_DotNetBootstrapCollections.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapAppendable.kt",
        "dotnet/common/src/generated/_DotNetBootstrapAppendable.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapStringBuilder.kt",
        "dotnet/common/src/generated/_DotNetBootstrapStringBuilder.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapKotlin.kt",
        "dotnet/common/src/generated/_DotNetBootstrapKotlin.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapEnum.kt",
        "dotnet/common/src/generated/_DotNetBootstrapEnum.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapEnumEntries.kt",
        "dotnet/common/src/generated/_DotNetBootstrapEnumEntries.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "_DotNetBootstrapJsName.kt",
        "dotnet/common/src/generated/_DotNetBootstrapJsName.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "Exceptions.kt",
        "common-non-jvm/src/kotlin/Exceptions.kt",
    ),
    DotNetStdlibSourceResource(
        "SharedVariableBox.kt",
        "common-non-jvm/src/kotlin/internal/SharedVariableBox.kt",
    ),
    DotNetStdlibSourceResource(
        "SyntheticConstructorMarker.kt",
        "common-non-jvm/src/kotlin/internal/SyntheticConstructorMarker.kt",
    ),
    DotNetStdlibSourceResource(
        "ExceptionsH.kt",
        "common/src/kotlin/ExceptionsH.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "ioH.kt",
        "common/src/kotlin/ioH.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "JvmAnnotationsH.kt",
        "common/src/kotlin/JvmAnnotationsH.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "Multiplatform.kt",
        "src/kotlin/annotations/Multiplatform.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "Annotations.kt",
        "src/kotlin/internal/Annotations.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "AnnotationsBuiltin.kt",
        "src/kotlin/internal/AnnotationsBuiltin.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "WasExperimental.kt",
        "src/kotlin/annotations/WasExperimental.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "serializationUtil.kt",
        "src/kotlin/internal/serializationUtil.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "KClass.kt",
        "src/kotlin/reflect/KClass.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "KClasses.kt",
        "src/kotlin/reflect/KClasses.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "KClassifier.kt",
        "src/kotlin/reflect/KClassifier.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "throwNoWhenBranchMatchedException.kt",
        "src/kotlin/internal/throwNoWhenBranchMatchedException.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "AbstractCollection.kt",
        "src/kotlin/collections/AbstractCollection.kt",
        isCommon = true,
    ),
    DotNetStdlibSourceResource(
        "AbstractList.kt",
        "src/kotlin/collections/AbstractList.kt",
        isCommon = true,
    ),
).sortedBy(DotNetStdlibSourceResource::path)

/**
 * Canonical Kotlin/.NET standard-library source files.
 *
 * Repository stdlib production compiles the ordinary files under `libraries/stdlib/dotnet/src`
 * directly. The backend JAR also carries those same files as resources for the temporary
 * no-installed-stdlib bootstrap path used by compiler tests and standalone compiler invocations.
 * This map is therefore a read-only packaged-source view, not a second source implementation.
 */
val DOTNET_STDLIB_SOURCES: Map<String, String> =
    DOTNET_STDLIB_SOURCE_RESOURCES.associate { resource ->
        resource.fileName to readDotNetStdlibSourceResource(resource.path)
    }

/** Package-relative source paths used to keep direct and fallback FIR file ordering identical. */
val DOTNET_STDLIB_SOURCE_PATHS: Map<String, String> =
    DOTNET_STDLIB_SOURCE_RESOURCES.associate { resource ->
        resource.fileName to resource.path
    }

/** Exact Common source files compiled as the shared module of the temporary target product. */
val DOTNET_STDLIB_COMMON_SOURCE_NAMES: Set<String> =
    DOTNET_STDLIB_SOURCE_RESOURCES
        .filter(DotNetStdlibSourceResource::isCommon)
        .mapTo(linkedSetOf(), DotNetStdlibSourceResource::fileName)

private fun readDotNetStdlibSourceResource(path: String): String {
    val resourcePath = "/$DOTNET_STDLIB_RESOURCE_ROOT/$path"
    return checkNotNull(DotNetStdlibSourceResource::class.java.getResourceAsStream(resourcePath)) {
        "Kotlin/.NET stdlib source resource '$resourcePath' is missing from the compiler distribution"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

private const val DOTNET_STDLIB_RESOURCE_ROOT = "kotlin-dotnet-stdlib"
