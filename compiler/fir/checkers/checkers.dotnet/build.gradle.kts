plugins {
    id("common-configuration")
    id("test-federation-convention")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
    id("require-explicit-types")
}

dependencies {
    api(project(":compiler:fir:checkers"))
}

sourceSets {
    "main" {
        projectDefault()
    }
    "test" { none() }
}
