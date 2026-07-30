package org.jetbrains.kotlin.backend.dotnet

private data class DotNetStdlibSourceResource(
    val fileName: String,
    val path: String,
)

// Match the ordinary-source producer's relative-path order: FIR file order affects deterministic
// declaration order in the emitted stdlib IL.
private val DOTNET_STDLIB_SOURCE_RESOURCES = listOf(
    DotNetStdlibSourceResource("DotNetStdlibIo.kt", "kotlin/io/DotNetStdlibIo.kt"),
    DotNetStdlibSourceResource(
        "DotNetStdlibCollections.kt",
        "kotlin/collections/DotNetStdlibCollections.kt",
    ),
    DotNetStdlibSourceResource("DotNetStdlibKotlin.kt", "kotlin/DotNetStdlibKotlin.kt"),
    DotNetStdlibSourceResource(
        "DotNetStdlibCancellation.kt",
        "kotlin/coroutines/cancellation/DotNetStdlibCancellation.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetThrowNoWhenBranchMatchedException.kt",
        "kotlin/internal/DotNetThrowNoWhenBranchMatchedException.kt",
    ),
    DotNetStdlibSourceResource(
        "DotNetStdlibInternalAnnotations.kt",
        "kotlin/internal/DotNetStdlibInternalAnnotations.kt",
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

private fun readDotNetStdlibSourceResource(path: String): String {
    val resourcePath = "/$DOTNET_STDLIB_RESOURCE_ROOT/$path"
    return checkNotNull(DotNetStdlibSourceResource::class.java.getResourceAsStream(resourcePath)) {
        "Kotlin/.NET stdlib source resource '$resourcePath' is missing from the compiler distribution"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

private const val DOTNET_STDLIB_RESOURCE_ROOT = "kotlin-dotnet-stdlib"
