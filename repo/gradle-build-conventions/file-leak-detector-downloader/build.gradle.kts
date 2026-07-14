import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    `java-gradle-plugin`
    `embedded-kotlin`
}

description = "Download file-leak-detector JAR."

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalBuildToolsApi::class)
    compilerVersion = libs.versions.kotlin.`for`.gradle.plugins.compilation
    jvmToolchain(17)
}

dependencies {
    compileOnly(gradleApi())
    compileOnly(gradleKotlinDsl())
}

gradlePlugin {
    plugins {
        register("FileLeakDetectorDownloader") {
            id = "kotlin-git.gradle-build-conventions.file-leak-detector-downloader"
            implementationClass = "org.jetbrains.kotlin.build.FileLeakDetectorDownloaderPlugin"
        }
    }
}

project.configurations.named(org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME + "Main") {
    resolutionStrategy {
        eachDependency {
            if (this.requested.group == "org.jetbrains.kotlin") useVersion(libs.versions.kotlin.`for`.gradle.plugins.compilation.get())
        }
    }
}

kotlin.compilerOptions.moduleName.value(project.name)
