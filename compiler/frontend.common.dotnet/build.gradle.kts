plugins {
    id("common-configuration")
    id("test-federation-convention")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
}

// Deliberately no compiler-project dependencies: this module models and resolves objective CLR
// metadata and may not depend on FIR, IR, a backend, a CLI pipeline, Gradle, or Roslyn tooling.
dependencies {
    api(kotlinStdlib())
}

sourceSets {
    "main" { projectDefault() }
    "test" {}
}
