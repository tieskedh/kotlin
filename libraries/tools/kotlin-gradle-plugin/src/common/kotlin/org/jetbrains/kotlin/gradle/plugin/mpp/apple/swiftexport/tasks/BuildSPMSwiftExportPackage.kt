/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftexport.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.*
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport.XcodebuildDefFileUtils.DUMP_FILE_ARGS_SEPARATOR
import org.jetbrains.kotlin.gradle.utils.getFile
import org.jetbrains.kotlin.gradle.utils.property
import org.jetbrains.kotlin.gradle.utils.relativeOrAbsolute
import org.jetbrains.kotlin.gradle.utils.runCommand
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import javax.inject.Inject

@DisableCachingByDefault(because = "Swift Export is experimental, so no caching for now")
internal abstract class BuildSPMSwiftExportPackage @Inject constructor(
    providerFactory: ProviderFactory,
    objectFactory: ObjectFactory,
) : DefaultTask() {
    init {
        onlyIf { HostManager.hostIsMac }
    }

    @get:Input
    abstract val swiftApiModuleName: Property<String>

    @get:Input
    abstract val swiftLibraryName: Property<String>

    @get:Input
    abstract val target: Property<KonanTarget>

    @get:Input
    abstract val configuration: Property<String>

    @get:Input
    val deploymentTargetSettingName: Property<String> = objectFactory.property<String>().convention(
        providerFactory.environmentVariable("DEPLOYMENT_TARGET_SETTING_NAME")
    )

    @get:Internal
    val deploymentTarget: Provider<String> = deploymentTargetSettingName.flatMap {
        providerFactory.environmentVariable(it)
    }

    @get:Optional
    @get:Input
    val targetDeviceIdentifier: Property<String> = objectFactory.property<String>().convention(
        providerFactory.environmentVariable("TARGET_DEVICE_IDENTIFIER")
    )

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packageRoot: DirectoryProperty

    /**
     * Dump files listing the module/framework search paths of the built SwiftPM-import synthetic package (empty
     * unless the project imports a Swift package). Their directories are added to the xcodebuild search paths so
     * the reexported `import <ObjCModule>` lines in the generated Swift API resolve.
     */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val swiftPMImportSearchPathDumps: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val packageDerivedData: DirectoryProperty

    @get:OutputDirectory
    abstract val packageBuildDir: DirectoryProperty

    @get:OutputDirectory
    val interfacesPath: DirectoryProperty = objectFactory.directoryProperty().apply {
        set(packageBuildDir.dir("dd-interfaces"))
    }

    @get:OutputDirectory
    val objectFilesPath: DirectoryProperty = objectFactory.directoryProperty().apply {
        set(packageBuildDir.dir("dd-o-files"))
    }

    @get:OutputDirectory
    val libraryFilesPath: DirectoryProperty = objectFactory.directoryProperty().apply {
        set(packageBuildDir.dir("dd-a-files"))
    }

    @get:OutputFile
    val packageLibrary: RegularFileProperty = objectFactory.fileProperty().apply {
        set(libraryFilesPath.file(swiftLibraryName.map { "lib${it}.a" }))
    }

    private val libraryTools by lazy { LibraryTools(logger) }

    private val packageRootPath get() = packageRoot.getFile()

    @TaskAction
    fun run() {
        buildSyntheticPackage()
        packObjectFilesIntoLibrary()
    }

    private fun buildSyntheticPackage() {
        val intermediatesDestination = mapOf(
            // Thin/universal object files
            "TARGET_BUILD_DIR" to objectFilesPath.getFile().absolutePath,
            // .swiftmodule interface
            "BUILT_PRODUCTS_DIR" to interfacesPath.getFile().absolutePath,
        )

        val swiftModuleName = swiftApiModuleName.get()
        val deploymentTargetSettingName = deploymentTargetSettingName.get()
        val deploymentTarget = deploymentTarget.get()

        val buildArguments = buildMap {
            put("ARCHS", target.map { it.appleArchitecture }.get().xcodebuildArch)
            put("CONFIGURATION", configuration.get())
            put("DEPLOYMENT_TARGET_SETTING_NAME", deploymentTargetSettingName)
            put(deploymentTargetSettingName, deploymentTarget)

            /*
            We need to add -public-autolink-library flag because bridge module is imported with @_implementationOnly
            All object files will be merged in `lib${swiftApiModuleName}.a`
            More information can be found here: https://github.com/swiftlang/swift/pull/35936
             */
            put("OTHER_SWIFT_FLAGS", "-Xfrontend -public-autolink-library -Xfrontend $swiftModuleName")

            // When Swift Export reexports SwiftPM-imported Objective-C modules, the generated Swift API contains
            // `import <ObjCModule>`. Those modules were built by the SwiftPM-import feature into the directories
            // captured in these dumps; add them to the search paths so the compile resolves the imports.
            val importSearchPaths = importModuleSearchPaths()
            if (importSearchPaths.isNotEmpty()) {
                val setting = (listOf("\$(inherited)") + importSearchPaths.map { "\"$it\"" }).joinToString(" ")
                put("FRAMEWORK_SEARCH_PATHS", setting)
                put("SWIFT_INCLUDE_PATHS", setting)
            }
        }

        val derivedData = packageDerivedData.getFile()

        val command = listOf(
            "xcodebuild",
            "-derivedDataPath", derivedData.relativeOrAbsolute(packageRootPath),
            "-scheme", swiftModuleName,
            "-destination", destination(),
        ) + (intermediatesDestination + buildArguments).map { (k, v) -> "$k=$v" }

        // FIXME: This will not work with dynamic libraries
        runCommand(
            command,
            logger = logger,
            processConfiguration = {
                environment().apply {
                    keys.filter {
                        AppleSdk.xcodeEnvironmentDebugDylibVars.contains(it)
                    }.forEach {
                        remove(it)
                    }
                }

                directory(packageRootPath)
            }
        )
    }

    private fun packObjectFilesIntoLibrary() {
        val objectFilePaths = objectFilesPath.asFileTree.filter {
            it.extension == "o"
        }.files.toList()

        if (objectFilePaths.isEmpty()) {
            error("Synthetic package build didn't produce any object files")
        }

        libraryTools.mergeLibraries(objectFilePaths, packageLibrary.getFile())
    }

    private fun importModuleSearchPaths(): List<String> =
        swiftPMImportSearchPathDumps.files
            .filter { it.isFile }
            .flatMap { it.readText().split(DUMP_FILE_ARGS_SEPARATOR) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun destination(): String {
        val deviceId = targetDeviceIdentifier.orNull
        if (deviceId != null) return "id=$deviceId"

        return target.get().appleTarget.genericPlatformDestination
    }
}
