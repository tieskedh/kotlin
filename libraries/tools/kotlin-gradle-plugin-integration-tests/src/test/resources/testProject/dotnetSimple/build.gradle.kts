@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.attributes.KotlinDotNetTargetFramework

plugins {
    kotlin("multiplatform")
}

kotlin {
    dotnet(KotlinDotNetTargetFramework.NETSTANDARD_2_0) {
        compilerOptions.moduleName.set("Sample.Library")
    }
}
