package org.jetbrains.kotlin.backend.dotnet

/**
 * Contract for the Kotlin declaration metadata carried by a compiler-produced CLR assembly.
 *
 * The payload is a complete packed KLIB so the existing Kotlin metadata model remains
 * authoritative while the CLR DLL is the single physical library artifact.
 */
object DotNetKotlinMetadataResource {
    const val MANAGED_RESOURCE_NAME = "Kotlin.Metadata"
    const val CONTAINER_FORMAT_PROPERTY = "dotnet_metadata_container"
    const val IMPLEMENTATION_BINDING_PROPERTY = "dotnet_implementation_binding"
    const val EMBEDDED_KLIB_FORMAT = "managed-resource-klib-v1"
    const val SELF_IMPLEMENTATION_BINDING = "self"
}
