plugins {
    id("common-configuration")
    id("test-federation-convention")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
    id("gradle-plugin-compiler-dependency-configuration")
}

dependencies {
    implementation(project(":core:language.targets.dotnet"))
    implementation(project(":compiler:config.dotnet"))
    implementation(project(":compiler:frontend.common.dotnet"))
    implementation(project(":compiler:fir:fir-dotnet"))
    implementation(project(":compiler:fir:fir2ir:dotnet-backend"))
    implementation(project(":compiler:util"))
    implementation(project(":compiler:cli"))
    implementation(project(":compiler:frontend"))
    implementation(project(":core:descriptors"))
    implementation(project(":compiler:ir.tree"))
    implementation(project(":compiler:ir.serialization.dotnet"))
    implementation(project(":compiler:ir.backend.common"))
    implementation(project(":compiler:backend.dotnet"))
    implementation(project(":compiler:serialization"))
    implementation(project(":compiler:plugin-api"))
    implementation(project(":compiler:fir:raw-fir:psi2fir"))
    implementation(project(":compiler:fir:resolve"))
    implementation(project(":compiler:fir:providers"))
    implementation(project(":compiler:fir:semantics"))
    implementation(project(":compiler:fir:entrypoint"))
    implementation(project(":compiler:fir:fir2ir"))
    implementation(project(":compiler:fir:fir-serialization"))
    implementation(project(":compiler:fir:checkers"))
    implementation(project(":compiler:fir:checkers:checkers.jvm"))
    implementation(project(":kotlin-util-klib-metadata"))
    implementation(project(":kotlin-build-common"))
    implementation(project(":kotlin-util-io"))

    compileOnly(toolsJarApi())
    compileOnly(intellijCore())
    compileOnly(commonDependency("org.jetbrains.kotlin:kotlin-reflect")) { isTransitive = false }
    compileOnly(commonDependency("org.jetbrains.intellij.deps:jdom:2.0.6"))
    compileOnly(libs.kotlinx.coroutines.core.jvm)
}

sourceSets {
    "main" { projectDefault() }
}

optInToExperimentalCompilerApi()
optInToK1Deprecation()
