plugins {
    id("common-configuration")
    id("test-federation-convention")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
}

dependencies {
    api(project(":compiler:cli-base"))
    api(project(":compiler:ir.tree"))
    api(project(":compiler:ir.backend.common"))
    api(project(":compiler:ir.serialization.common"))
    implementation(project(":compiler:util"))
    implementation(project(":core:descriptors"))

    compileOnly(intellijCore())
}

optInToUnsafeDuringIrConstructionAPI()

sourceSets {
    "main" { projectDefault() }
    "test" {}
}

tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("libraries/stdlib/dotnet/src")) {
        into("kotlin-dotnet-stdlib/dotnet/src")
    }
    from(files(
        rootProject.file("libraries/stdlib/src/kotlin/internal/Annotations.kt"),
        rootProject.file("libraries/stdlib/src/kotlin/internal/throwNoWhenBranchMatchedException.kt"),
    )) {
        into("kotlin-dotnet-stdlib/src/kotlin/internal")
    }
}

tasks.register("dotNetTest") {
    group = "verification"
    description = "Runs the strict Kotlin/.NET semantic, IL, CLI, and library-integration gates."
    dependsOn(
        ":compiler:fir:fir2ir:dotNetTest",
        ":compiler:tests-integration:dn",
    )
}
