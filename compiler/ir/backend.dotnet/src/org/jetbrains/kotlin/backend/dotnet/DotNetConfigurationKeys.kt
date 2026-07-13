package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import java.io.File

object DotNetConfigurationKeys {
    val OUTPUT: CompilerConfigurationKey<File> = CompilerConfigurationKey.create("output .NET IL file")
    val ASSEMBLY_NAME: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("output .NET assembly name")
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
