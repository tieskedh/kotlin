plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":core:compiler.common.native"))
    implementation(project(":compiler:ir.serialization.native"))
    implementation(project(":core:descriptors"))
    implementation(project(":kotlinx-metadata-klib"))
    compileOnly(project(":kotlin-metadata")) // Only to fix IDE reporting unresolved references (KTI-3323).
}

optInToUnsafeDuringIrConstructionAPI()
