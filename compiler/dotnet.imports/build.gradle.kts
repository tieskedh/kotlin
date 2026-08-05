plugins {
    id("common-configuration")
    id("test-federation-convention")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
    id("require-explicit-types")
}

// Neutral compiler transport for already-selected CLR-facing facts. This module does not select
// assemblies, interpret Kotlin semantics, construct FIR/IR, or map declarations to CIL.
dependencies {
    api(project(":core:compiler.common"))
    api(project(":compiler:frontend.common.dotnet"))
    api(kotlinStdlib())
}

sourceSets {
    "main" { projectDefault() }
    "test" { none() }
}
