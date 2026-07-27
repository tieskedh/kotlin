package org.jetbrains.kotlin.backend.dotnet

/**
 * Contract for the Kotlin declaration metadata carried by a compiler-produced CLR assembly.
 *
 * The payload is a complete packed KLIB so the existing Kotlin metadata model remains
 * authoritative during the transition to DLL-first library resolution. Its manifest records
 * whether the implementation is the containing assembly or a separately hashed sibling.
 */
object DotNetKotlinMetadataResource {
    const val MANAGED_RESOURCE_NAME = "Kotlin.Metadata"
    const val CONTAINER_FORMAT_PROPERTY = "dotnet_metadata_container"
    const val IMPLEMENTATION_BINDING_PROPERTY = "dotnet_implementation_binding"
    const val EMBEDDED_KLIB_FORMAT = "managed-resource-klib-v1"
    const val SIBLING_KLIB_FORMAT = "sibling-klib-v1"
    const val SELF_IMPLEMENTATION_BINDING = "self"
    const val SIBLING_SHA256_IMPLEMENTATION_BINDING = "sibling-sha256-v1"
}
