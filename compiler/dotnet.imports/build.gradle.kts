plugins {
    id("common-configuration")
    id("test-federation-convention")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
    id("require-explicit-types")
}

// Compiler transport for one already-selected foreign CLR declaration. This module does not
// select assemblies, interpret Kotlin semantics, construct FIR/IR, or map declarations to CIL.
dependencies {
    api(project(":core:compiler.common"))
    api(project(":compiler:frontend.common.dotnet"))
    api(kotlinStdlib())
}

sourceSets {
    "main" { projectDefault() }
    "test" { none() }
}
