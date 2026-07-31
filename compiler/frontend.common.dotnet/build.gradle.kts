plugins {
    id("common-configuration")
    id("test-federation-convention")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
}

// This module models and resolves objective CLR metadata. Its sole compiler-project dependency is
// the pure .NET language-target vocabulary; it may not depend on compiler configuration, FIR, IR,
// a backend, a CLI pipeline, Gradle, or Roslyn tooling.
dependencies {
    api(project(":core:language.targets.dotnet"))
    api(kotlinStdlib())
}

sourceSets {
    "main" { projectDefault() }
    "test" {}
}
