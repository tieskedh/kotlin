import org.jetbrains.kotlin.testFederation.DelicateTestFederationApi
import org.jetbrains.kotlin.testFederation.Domain
import org.jetbrains.kotlin.testFederation.testFederationDomains

plugins {
    id("common-configuration")
    id("test-federation-convention")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
    id("java-test-fixtures")
    id("project-tests-convention")
    id("test-inputs-check")
    id("require-explicit-types")
}

dependencies {
    implementation(project(":core:descriptors"))
    implementation(project(":core:descriptors.jvm"))
    implementation(project(":compiler:fir:cones"))
    implementation(project(":compiler:fir:resolve"))
    implementation(project(":compiler:fir:providers"))
    implementation(project(":compiler:fir:semantics"))
    implementation(project(":compiler:fir:tree"))
    implementation(project(":compiler:ir.tree"))
    implementation(project(":compiler:ir.backend.common"))
    implementation(project(":compiler:ir.serialization.common"))
    implementation(project(":compiler:fir:fir-serialization"))
    implementation(project(":compiler:fir:fir-deserialization"))
    implementation(project(":compiler:frontend.common.jvm"))
    implementation(project(":compiler:config.jvm"))
    implementation(project(":compiler:fir:fir-jvm"))
    implementation(project(":compiler:frontend"))
    implementation(project(":core:compiler.common.web"))

    compileOnly(intellijCore())

    testCompileOnly(kotlinTest("junit"))
    testFixturesApi(testFixtures(project(":compiler:test-infrastructure")))
    testFixturesApi(testFixtures(project(":compiler:test-infrastructure-utils")))
    testFixturesApi(testFixtures(project(":compiler:tests-compiler-utils")))
    testFixturesApi(testFixtures(project(":compiler:tests-common-new")))
    testFixturesApi(testFixtures(project(":compiler:fir:analysis-tests")))
    testFixturesImplementation(testFixtures(project(":generators:test-generator")))
    testFixturesImplementation(testFixtures(project(":compiler:tests-spec")))
    testFixturesImplementation(project(":core:language.targets.dotnet"))
    testFixturesImplementation(project(":compiler:backend.dotnet"))
    testFixturesImplementation(project(":compiler:cli-dotnet"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)

    testRuntimeOnly(project(":compiler:fir:fir2ir:jvm-backend"))
    testRuntimeOnly(project(":kotlin-util-klib-abi"))
    testRuntimeOnly(project(":generators"))

    testCompileOnly(intellijCore())
    testRuntimeOnly(intellijCore())

    testRuntimeOnly(toolsJar())
    testRuntimeOnly(commonDependency("org.jetbrains.intellij.deps.jna:jna"))
    testRuntimeOnly(libs.intellij.fastutil)
    testRuntimeOnly(commonDependency("one.util:streamex"))

    testRuntimeOnly(jpsModel())
    testRuntimeOnly(jpsModelImpl())
}

kotlin {
    compilerOptions.optIn.addAll(
        listOf(
            "org.jetbrains.kotlin.fir.symbols.SymbolInternals",
            "org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess",
            "org.jetbrains.kotlin.types.model.K2Only",
        )
    )
}
optInToObsoleteDescriptorBasedAPI()

sourceSets {
    "main" { projectDefault() }
    "testFixtures" { projectDefault() }
}

fun Test.configure(
    domain: Domain = Domain.Jvm,
    configureJUnit: JUnitPlatformOptions.() -> Unit = {},
) {
    javaLauncher = project.getToolchainLauncherFor(JdkMajorVersion.JDK_1_8)
    useJUnitPlatform {
        configureJUnit()
    }

    @OptIn(DelicateTestFederationApi::class)
    testFederationDomains = listOf(domain)
}

val dotNetTestPlatformProfiles = linkedMapOf(
    "net48" to "Net48",
    "net10.0" to "Net100",
)
val dotNetTestPlatformDirectories = dotNetTestPlatformProfiles.keys.associateWith { targetFramework ->
    layout.buildDirectory.dir("dotnet-test-platform/$targetFramework")
}
val dotNetTestPlatformTasks = dotNetTestPlatformProfiles.map { (targetFramework, taskSuffix) ->
    val outputDirectory = dotNetTestPlatformDirectories.getValue(targetFramework)
    tasks.register<JavaExec>("produceDotNetTestPlatform$taskSuffix") {
        group = "verification"
        description = "Produces the reusable $targetFramework Kotlin/.NET test runtime and stdlib."

        classpath = sourceSets["testFixtures"].runtimeClasspath
        mainClass.set("org.jetbrains.kotlin.cli.dotnet.K2DotNetCompiler")
        javaLauncher.set(project.getToolchainLauncherFor(JdkMajorVersion.JDK_1_8))
        workingDir = rootDir
        maxHeapSize = "2g"
        args(
            "-Xdotnet-produce-stdlib",
            "-Xdotnet-target=$targetFramework",
            "-d", outputDirectory.get().asFile.absolutePath,
        )

        outputs.files(
            outputDirectory.map { it.file("Kotlin.Runtime.dll") },
            outputDirectory.map { it.file("Kotlin.Stdlib.dll") },
            outputDirectory.map { it.file("Kotlin.Stdlib.il") },
        )
        doFirst {
            outputDirectory.get().asFile.deleteRecursively()
        }
    }
}
dotNetTestPlatformTasks.zipWithNext().forEach { (earlier, later) ->
    later.configure { mustRunAfter(earlier) }
}

val dotNetCSharpAuthoringProjectDirectory = rootProject.file(
    "compiler/ir/backend.dotnet/csharp-authoring/Kotlin.DotNet.CSharpAuthoring",
)
val dotNetCSharpAuthoringArtifacts = layout.buildDirectory.dir("dotnet-csharp-authoring/artifacts")
val dotNetCSharpAuthoringAssembly = dotNetCSharpAuthoringArtifacts.map { artifacts ->
    artifacts.file(
        "bin/Kotlin.DotNet.CSharpAuthoring/release/Kotlin.DotNet.CSharpAuthoring.dll",
    )
}
val dotNetCSharpAuthoringHost = listOfNotNull(
    System.getenv("KOTLIN_DOTNET_ROOT")?.let(::File),
    System.getenv("LOCALAPPDATA")?.let { File(it, "kotlinc-dotnet/toolchain") },
).asSequence()
    .flatMap { root -> sequenceOf(root.resolve("dotnet/dotnet.exe"), root.resolve("dotnet/dotnet")) }
    .firstOrNull(File::isFile)
    ?.absolutePath
    ?: "dotnet"
val buildDotNetCSharpAuthoringTooling = tasks.register<Exec>("buildDotNetCSharpAuthoringTooling") {
    group = "verification"
    description = "Builds the Roslyn tooling used by Kotlin/.NET C# authoring tests."
    workingDir = rootDir

    inputs.files(fileTree(dotNetCSharpAuthoringProjectDirectory) {
        exclude("bin/**", "obj/**")
    }).withPropertyName("dotNetCSharpAuthoringSources")
    outputs.file(dotNetCSharpAuthoringAssembly)
    commandLine(
        dotNetCSharpAuthoringHost,
        "build",
        dotNetCSharpAuthoringProjectDirectory.resolve("Kotlin.DotNet.CSharpAuthoring.csproj").absolutePath,
        "--configuration", "Release",
        "-p:RestoreLockedMode=true",
        "--artifacts-path", dotNetCSharpAuthoringArtifacts.get().asFile.absolutePath,
        "--nologo",
    )
}

projectTests {
    testData(project(":compiler").isolated, "testData/codegen")
    testData(project(":compiler").isolated, "testData/diagnostics")
    testData(project(":compiler").isolated, "testData/ir")
    testData(project(":compiler").isolated, "testData/klib")
    testData(project(":compiler").isolated, "testData/debug")
    testData(project(":compiler").isolated, "testData/checkLocalVariablesTable")
    testData(project(":compiler").isolated, "testData/writeSignature")
    testData(project(":compiler").isolated, "testData/writeFlags")
    testData(project(":compiler:tests-spec").isolated, "testData/codegen")

    val environment = listOf(JdkMajorVersion.JDK_1_8, JdkMajorVersion.JDK_11_0, JdkMajorVersion.JDK_17_0, JdkMajorVersion.JDK_21_0)
    testTask(defineJDKEnvVariables = environment) {
        configure()
    }

    testTask(
        "aggregateTests",
        defineJDKEnvVariables = environment,
        skipInLocalBuild = true,
        maxHeapSize = testMaxHeapSizeLarge,
        garbageCollector = GarbageCollector.Parallel
    ) {
        configure {
            excludeTags("FirPsiCodegenTest")
        }
    }

    testTask(
        "nightlyTests",
        defineJDKEnvVariables = environment,
        skipInLocalBuild = true,
    ) {
        configure {
            includeTags("FirPsiCodegenTest")
        }
    }

    testTask(
        "dotNetTest",
        defineJDKEnvVariables = listOf(JdkMajorVersion.JDK_1_8),
        skipInLocalBuild = false,
    ) {
        configure(domain = Domain.DotNet) {
            dependsOn(dotNetTestPlatformTasks)
            val genericOwnerRehearsal = providers.gradleProperty(
                "kotlin.dotnet.genericOwnerRehearsal",
            ).orNull == "true"
            if (genericOwnerRehearsal) {
                dependsOn(buildDotNetCSharpAuthoringTooling)
                systemProperty(
                    "kotlin.dotnet.test.csharpAuthoringTooling.path",
                    dotNetCSharpAuthoringAssembly.get().asFile.absolutePath,
                )
            }
            inputs.files(
                rootProject.file(
                    "kotlin-native/performance/ring/src/commonMain/kotlin/org/jetbrains/ring/" +
                            "ArrayCopyBenchmark.kt",
                ),
                rootProject.file(
                    "kotlin-native/performance/ring/src/commonMain/kotlin/org/jetbrains/ring/" +
                            "OctoTest/ocTree.kt",
                ),
            ).withPropertyName("dotNetRepresentativeApplicationSources")
            filter {
                includeTestsMatching("*DotNet*")
            }
            environment("KOTLIN_DOTNET_REQUIRE_TOOLCHAIN", "1")
            dotNetTestPlatformDirectories.forEach { (targetFramework, outputDirectory) ->
                systemProperty(
                    "kotlin.dotnet.test.platform.$targetFramework.path",
                    outputDirectory.get().asFile.absolutePath,
                )
            }
            providers.gradleProperty("kotlin.dotnet.genericOwnerMeasurementDir").orNull?.let { exportDirectory ->
                systemProperty("kotlin.dotnet.genericOwnerMeasurementDir", exportDirectory)
            }
            providers.gradleProperty("kotlin.dotnet.genericOwnerApplicationDir").orNull?.let { exportDirectory ->
                systemProperty("kotlin.dotnet.genericOwnerApplicationDir", exportDirectory)
            }
            providers.gradleProperty("kotlin.dotnet.genericOwnerCallRouteTraceDir").orNull?.let { exportDirectory ->
                systemProperty("kotlin.dotnet.genericOwnerCallRouteTraceDir", exportDirectory)
            }
            providers.gradleProperty("kotlin.dotnet.genericOwnerRehearsal").orNull?.let { enabled ->
                systemProperty("kotlin.dotnet.genericOwnerRehearsal", enabled)
            }
            providers.gradleProperty("kotlin.dotnet.genericOwnerRehearsalDir").orNull?.let { exportDirectory ->
                systemProperty("kotlin.dotnet.genericOwnerRehearsalDir", exportDirectory)
            }
        }
    }

    testGenerator("org.jetbrains.kotlin.test.TestGeneratorForFir2IrTestsKt", generateTestsInBuildDirectory = true)

    withJvmStdlibAndReflect()
    withScriptRuntime()
    withMockJdkAnnotationsJar()
    withTestJar()
    withScriptingPlugin()
    withMockJdkRuntime()
    withStdlibCommon()
    withAnnotations()
    withThirdPartyAnnotations()
    withThirdPartyJsr305()
    withThirdPartyJava8Annotations()
}

testsJarToBeUsedAlongWithFixtures()
