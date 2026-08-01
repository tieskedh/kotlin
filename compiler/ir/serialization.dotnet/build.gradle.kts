plugins {
    id("common-configuration")
    id("test-federation-convention")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
}

dependencies {
    api(project(":compiler:ir.serialization.common"))
}

sourceSets {
    "main" { projectDefault() }
}

optInToUnsafeDuringIrConstructionAPI()
