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

tasks.register("dotNetTest") {
    group = "verification"
    description = "Runs the strict Kotlin/.NET semantic, IL, CLI, and library-integration gates."
    dependsOn(
        ":compiler:fir:fir2ir:dotNetTest",
        ":compiler:tests-integration:dn",
    )
}
