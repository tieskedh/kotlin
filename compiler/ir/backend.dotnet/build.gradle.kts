plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":compiler:cli-base"))
    api(project(":compiler:ir.tree"))
    api(project(":compiler:ir.backend.common"))
    api(project(":compiler:ir.serialization.common"))
    implementation(project(":compiler:util"))

    compileOnly(intellijCore())
}

optInToUnsafeDuringIrConstructionAPI()

sourceSets {
    "main" { projectDefault() }
    "test" {}
}
