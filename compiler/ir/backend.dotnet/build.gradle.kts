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
    api(project(":compiler:dotnet.imports"))
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
        rootProject.file("libraries/stdlib/src/kotlin/internal/AnnotationsBuiltin.kt"),
        rootProject.file("libraries/stdlib/src/kotlin/internal/serializationUtil.kt"),
        rootProject.file("libraries/stdlib/src/kotlin/internal/throwNoWhenBranchMatchedException.kt"),
    )) {
        into("kotlin-dotnet-stdlib/src/kotlin/internal")
    }
    from(rootProject.file("libraries/stdlib/src/kotlin/util/Tuples.kt")) {
        into("kotlin-dotnet-stdlib/src/kotlin/util")
    }
    from(files(
        rootProject.file("libraries/stdlib/src/kotlin/reflect/KClass.kt"),
        rootProject.file("libraries/stdlib/src/kotlin/reflect/KClasses.kt"),
        rootProject.file("libraries/stdlib/src/kotlin/reflect/KClassifier.kt"),
    )) {
        into("kotlin-dotnet-stdlib/src/kotlin/reflect")
    }
    from(files(
        rootProject.file("libraries/stdlib/src/kotlin/collections/AbstractCollection.kt"),
        rootProject.file("libraries/stdlib/src/kotlin/collections/AbstractList.kt"),
        rootProject.file("libraries/stdlib/src/kotlin/collections/AbstractMap.kt"),
        rootProject.file("libraries/stdlib/src/kotlin/collections/AbstractSet.kt"),
        rootProject.file("libraries/stdlib/src/kotlin/collections/IndexedValue.kt"),
        rootProject.file("libraries/stdlib/src/kotlin/collections/Iterables.kt"),
        rootProject.file("libraries/stdlib/src/kotlin/collections/Iterators.kt"),
    )) {
        into("kotlin-dotnet-stdlib/src/kotlin/collections")
    }
    from(files(
        rootProject.file("libraries/stdlib/common/src/kotlin/collections/AbstractMutableCollection.kt"),
        rootProject.file("libraries/stdlib/common/src/kotlin/collections/AbstractMutableList.kt"),
        rootProject.file("libraries/stdlib/common/src/kotlin/collections/AbstractMutableMap.kt"),
        rootProject.file("libraries/stdlib/common/src/kotlin/collections/AbstractMutableSet.kt"),
        rootProject.file("libraries/stdlib/common/src/kotlin/collections/ArrayList.kt"),
        rootProject.file("libraries/stdlib/common/src/kotlin/collections/HashMap.kt"),
        rootProject.file("libraries/stdlib/common/src/kotlin/collections/HashSet.kt"),
        rootProject.file("libraries/stdlib/common/src/kotlin/collections/LinkedHashMap.kt"),
        rootProject.file("libraries/stdlib/common/src/kotlin/collections/LinkedHashSet.kt"),
    )) {
        into("kotlin-dotnet-stdlib/common/src/kotlin/collections")
    }
    from(files(
        rootProject.file("libraries/stdlib/src/kotlin/contracts/ContractBuilder.kt"),
        rootProject.file("libraries/stdlib/src/kotlin/contracts/Effect.kt"),
    )) {
        into("kotlin-dotnet-stdlib/src/kotlin/contracts")
    }
    from(rootProject.file("libraries/stdlib/src/kotlin/annotations/Multiplatform.kt")) {
        into("kotlin-dotnet-stdlib/src/kotlin/annotations")
    }
    from(rootProject.file("libraries/stdlib/src/kotlin/annotations/WasExperimental.kt")) {
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
        rootProject.file("libraries/stdlib/common-non-jvm/src/kotlin/internal/ThrowHelpers.kt"),
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
