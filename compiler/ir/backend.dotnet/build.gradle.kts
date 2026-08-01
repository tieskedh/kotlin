plugins {
    id("common-configuration")
    id("test-federation-convention")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
}

dependencies {
    api(project(":core:language.targets.dotnet"))
    api(project(":compiler:config.dotnet"))
    api(project(":compiler:frontend.common.dotnet"))
    implementation(project(":compiler:dotnet.imports"))
    api(project(":compiler:cli-base"))
    api(project(":compiler:ir.tree"))
    api(project(":compiler:ir.backend.common"))
    implementation(project(":compiler:ir.inline"))
    api(project(":compiler:ir.serialization.common"))
    api(project(":compiler:ir.serialization.dotnet"))
    implementation(project(":compiler:util"))
    implementation(project(":core:descriptors"))

    compileOnly(intellijCore())
}

optInToUnsafeDuringIrConstructionAPI()

sourceSets {
    "main" { projectDefault() }
    "test" {}
}

tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("libraries/stdlib/dotnet/src")) {
        into("kotlin-dotnet-stdlib/dotnet/src")
    }
    from(rootProject.file("libraries/stdlib/dotnet/common/src")) {
        into("kotlin-dotnet-stdlib/dotnet/common/src")
    }
    from(files(
        rootProject.file("libraries/stdlib/src/kotlin/internal/Annotations.kt"),
        rootProject.file("libraries/stdlib/src/kotlin/internal/throwNoWhenBranchMatchedException.kt"),
    )) {
        into("kotlin-dotnet-stdlib/src/kotlin/internal")
    }
    from(rootProject.file("libraries/stdlib/src/kotlin/annotations/Multiplatform.kt")) {
        into("kotlin-dotnet-stdlib/src/kotlin/annotations")
    }
    from(files(
        rootProject.file("libraries/stdlib/common/src/kotlin/ExceptionsH.kt"),
        rootProject.file("libraries/stdlib/common/src/kotlin/JvmAnnotationsH.kt"),
    )) {
        into("kotlin-dotnet-stdlib/common/src/kotlin")
    }
    from(rootProject.file("libraries/stdlib/common/src/kotlin/ioH.kt")) {
        into("kotlin-dotnet-stdlib/common/src/kotlin")
    }
    from(rootProject.file("libraries/stdlib/common-non-jvm/src/kotlin/Exceptions.kt")) {
        into("kotlin-dotnet-stdlib/common-non-jvm/src/kotlin")
    }
    from(files(
        rootProject.file("libraries/stdlib/common-non-jvm/src/kotlin/internal/SharedVariableBox.kt"),
        rootProject.file("libraries/stdlib/common-non-jvm/src/kotlin/internal/SyntheticConstructorMarker.kt"),
    )) {
        into("kotlin-dotnet-stdlib/common-non-jvm/src/kotlin/internal")
    }
}

tasks.register("dotNetTest") {
    group = "verification"
    description = "Runs the strict Kotlin/.NET semantic, IL, CLI, and library-integration gates."
    dependsOn(
        ":compiler:fir:fir2ir:dotNetTest",
        ":compiler:tests-integration:dn",
    )
}
