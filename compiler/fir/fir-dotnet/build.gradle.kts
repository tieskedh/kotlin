plugins {
    id("common-configuration")
    id("test-federation-convention")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
    id("require-explicit-types")
}

dependencies {
    api(project(":compiler:frontend.common.dotnet"))
    api(project(":compiler:fir:providers"))
    api(project(":compiler:fir:tree"))
    api(project(":core:names"))
    api(kotlinStdlib())

    implementation(project(":compiler:dotnet.imports"))
    implementation(project(":compiler:fir:cones"))
    implementation(project(":compiler:frontend.common"))
    implementation(project(":core:compiler.common"))
    implementation(project(":core:language.model"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { none() }
}
