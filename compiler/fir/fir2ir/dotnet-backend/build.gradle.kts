plugins {
    id("common-configuration")
    id("test-federation-convention")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
    id("require-explicit-types")
}

dependencies {
    api(project(":core:compiler.common"))
    api(project(":compiler:fir:fir2ir"))
    api(project(":compiler:ir.tree"))
    api(kotlinStdlib())

    implementation(project(":compiler:dotnet.imports"))
    implementation(project(":compiler:frontend.common.dotnet"))
    implementation(project(":core:language.model"))
    implementation(project(":core:names"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { none() }
}

optInToUnsafeDuringIrConstructionAPI()
