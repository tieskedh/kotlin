/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.apple.swiftimport

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.appleArchitecture
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.appleTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.sdk
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import org.jetbrains.kotlin.gradle.tasks.locateTask
import org.jetbrains.kotlin.gradle.utils.lowerCamelCaseName
import java.io.File

/**
 * The artifacts the SwiftPM-import feature produces for a target when the project imports a Swift package,
 * exposed as a typed contract so consumers (e.g. Swift Export) need not know the cinterop name or task wiring.
 */
internal class SwiftPMImportProducts(
    /** Module name of the reexported cinterop klib. */
    val cinteropModuleName: Provider<String>,
    /** The cinterop klib bundling the imported package's Objective-C modules (klib manifest `interop=true`). */
    val cinteropKlib: Provider<File>,
    /**
     * Dump files (one per line, `DUMP_FILE_ARGS_SEPARATOR`-joined) listing the module/framework search paths of
     * the built synthetic package — the directories where SwiftPM dropped the imported Objective-C modules.
     * A consumer that compiles the reexported Swift API (`import <ObjCModule>`) must add these to its search paths.
     */
    val searchPathDumps: List<Provider<RegularFile>>,
)

/**
 * Invokes [onAvailable] if and when this target has SwiftPM-import products — i.e. the project imports a Swift
 * package, which produces the `swiftPMImport` cinterop. Does nothing otherwise.
 *
 * Lazy and configuration-order independent: it reacts to the cinterop's creation rather than requiring it to
 * exist already, so consumers can wire task inputs without depending on setup-action ordering.
 */
internal fun KotlinNativeTarget.whenSwiftPMImportAvailable(onAvailable: (SwiftPMImportProducts) -> Unit) {
    val mainCompilation = compilations.getByName(KotlinCompilation.MAIN_COMPILATION_NAME)
    val architecture = konanTarget.appleArchitecture
    val sdk = konanTarget.appleTarget.sdk
    mainCompilation.cinterops
        .matching { it.name == GenerateSyntheticLinkageImportProject.SWIFT_PM_IMPORT_CINTEROP_NAME }
        .all { cinterop ->
            val cinteropTask = project.locateTask<CInteropProcess>(cinterop.interopProcessingTaskName) ?: return@all
            val defFileTask = project.locateTask<ConvertSyntheticSwiftPMImportProjectIntoDefFile>(
                lowerCamelCaseName(ConvertSyntheticSwiftPMImportProjectIntoDefFile.TASK_NAME, sdk)
            ) ?: return@all
            onAvailable(
                SwiftPMImportProducts(
                    cinteropModuleName = cinteropTask.map { it.moduleName },
                    cinteropKlib = cinteropTask.flatMap { it.klibOutput },
                    searchPathDumps = listOf(
                        defFileTask.flatMap { it.frameworkSearchpathFilePath(architecture) },
                        defFileTask.flatMap { it.librarySearchpathFilePath(architecture) },
                    ),
                )
            )
        }
}
