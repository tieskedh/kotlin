/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

private object DotNetReflectionSourceResource

/**
 * Packaged-source view of the optional Kotlin/.NET reflection product.
 *
 * The ordinary sources under `libraries/reflect/dotnet` remain authoritative. This view lets
 * target-owned pre-distribution orchestration build the same product without moving reflection
 * policy into the backend.
 */
val DOTNET_REFLECTION_SOURCES: Map<String, String> = mapOf(
    "ReflectionFactoryImpl.kt" to readDotNetReflectionSource(
        "dotnet/src/kotlin/reflect/dotnet/internal/ReflectionFactoryImpl.kt",
    ),
)

private fun readDotNetReflectionSource(path: String): String {
    val resourcePath = "/$DOTNET_REFLECTION_RESOURCE_ROOT/$path"
    return checkNotNull(DotNetReflectionSourceResource::class.java.getResourceAsStream(resourcePath)) {
        "Kotlin/.NET reflection source resource '$resourcePath' is missing from the compiler distribution"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

private const val DOTNET_REFLECTION_RESOURCE_ROOT = "kotlin-dotnet-reflect"
