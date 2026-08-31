plugins {
    id("common-configuration")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
    id("test-inputs-check")
}

dependencies {
    api(kotlinStdlib())
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

projectTests {
    testTask()
}
