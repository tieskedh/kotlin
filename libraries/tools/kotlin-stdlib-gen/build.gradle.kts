plugins {
    id("common-configuration")
    id("test-federation-convention")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
}

val copyrightDirectory = project.layout.buildDirectory.dir("copyright")

dependencies {
    api("org.jetbrains.kotlin:kotlin-stdlib:$bootstrapKotlinVersion")
    api("org.jetbrains.kotlin:kotlin-reflect:$bootstrapKotlinVersion")
}

val copyCopyrightProfile = tasks.register("copyCopyrightProfile", Copy::class) {
    from("$rootDir/.idea/copyright")
    into(copyrightDirectory)
    include("apache.xml")
}

tasks {
    compileKotlin {
        compilerOptions {
            freeCompilerArgs.addAll(listOf("-version", "-Xdont-warn-on-error-suppression"))
        }
    }

    register<JavaExec>("run") {
        group = "application"
        mainClass = "generators.GenerateStandardLibKt"
        classpath = sourceSets.main.get().runtimeClasspath
        args = listOf("$rootDir")
        systemProperty("line.separator", "\n")
    }

    register<JavaExec>("generateDotNetBootstrapCollections") {
        group = "application"
        description = "Generates the supported Common collection slice for the experimental .NET stdlib."
        mainClass = "generators.GenerateDotNetBootstrapCollectionsKt"
        classpath = sourceSets.main.get().runtimeClasspath
        args = listOf("$rootDir")
        systemProperty("line.separator", "\n")
        outputs.files(
            listOf(
                "_DotNetBootstrapCollections.kt",
                "_DotNetBootstrapAppendable.kt",
                "_DotNetBootstrapStringBuilder.kt",
                "_DotNetBootstrapKotlin.kt",
                "_DotNetBootstrapEnum.kt",
                "_DotNetBootstrapEnumEntries.kt",
                "_DotNetBootstrapJsName.kt",
            ).map { fileName ->
                rootProject.file("libraries/stdlib/dotnet/common/src/generated/$fileName")
            }
        )
        inputs.file(
            rootProject.file("libraries/stdlib/src/kotlin/collections/Collections.kt")
        )
        inputs.file(
            rootProject.file("libraries/stdlib/common/src/kotlin/collections/CollectionsH.kt")
        )
        inputs.file(
            rootProject.file("libraries/stdlib/common/src/generated/_Arrays.kt")
        )
        inputs.files(
            rootProject.file("libraries/stdlib/src/kotlin/text/Appendable.kt"),
            rootProject.file("libraries/stdlib/src/kotlin/text/StringBuilder.kt"),
            rootProject.file("libraries/stdlib/src/kotlin/util/Standard.kt"),
            rootProject.file("libraries/stdlib/src/kotlin/Enum.kt"),
            rootProject.file("libraries/stdlib/src/kotlin/enums/EnumEntries.kt"),
            rootProject.file("libraries/stdlib/common/src/kotlin/JsAnnotationsH.kt"),
        )
    }

    register<JavaExec>("generateStdlibTests") {
        group = "application"
        mainClass = "generators.GenerateStandardLibTestsKt"
        classpath = sourceSets.main.get().runtimeClasspath
        workingDir = rootDir
        systemProperty("line.separator", "\n")
    }

    register<JavaExec>("generateUnicodeData") {
        group = "application"
        mainClass = "generators.unicode.GenerateUnicodeDataKt"
        classpath = sourceSets.main.get().runtimeClasspath
        args = listOf("$rootDir")
    }
}

sourceSets {
    "main" {
        kotlin.srcDir("src")
        resources.srcDir(copyCopyrightProfile)
    }
}
