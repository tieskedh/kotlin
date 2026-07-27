/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.tasks

import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.work.InputChanges
import org.gradle.workers.WorkerExecutor
import org.jetbrains.kotlin.cli.common.arguments.K2DotNetCompilerArguments
import org.jetbrains.kotlin.compilerRunner.GradleCompilerEnvironment
import org.jetbrains.kotlin.compilerRunner.OutputItemsCollectorImpl
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinDotNetCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinDotNetCompilerOptionsHelper
import org.jetbrains.kotlin.gradle.dsl.toCompilerValue
import org.jetbrains.kotlin.gradle.internal.tasks.allOutputFiles
import org.jetbrains.kotlin.gradle.logging.GradleErrorMessageCollector
import org.jetbrains.kotlin.gradle.logging.GradlePrintingMessageCollector
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerArgumentsProducer.CreateCompilerArgumentsContext
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerArgumentsProducer.CreateCompilerArgumentsContext.Companion.create
import org.jetbrains.kotlin.gradle.plugin.attributes.KotlinDotNetTargetFramework
import org.jetbrains.kotlin.gradle.report.BuildReportMode
import org.jetbrains.kotlin.gradle.utils.toPathsArray
import java.io.File
import javax.inject.Inject

@CacheableTask
@ExperimentalKotlinGradlePluginApi
abstract class KotlinDotNetCompile @Inject constructor(
    override val compilerOptions: KotlinDotNetCompilerOptions,
    workerExecutor: WorkerExecutor,
    objectFactory: ObjectFactory,
) : AbstractKotlinCompile<K2DotNetCompilerArguments>(objectFactory, workerExecutor),
    KotlinCompilationTask<KotlinDotNetCompilerOptions>,
    K2MultiplatformCompilationTask {

    init {
        compilerOptions.verbose.convention(logger.isDebugEnabled)
        authorizedFriendAssemblies.convention(emptySet())
        assemblyOutputFile.convention(
            compilerOptions.moduleName.flatMap { moduleName ->
                destinationDirectory.file("$moduleName.dll")
            }
        )
    }

    @get:Input
    abstract val targetFramework: Property<KotlinDotNetTargetFramework>

    @get:Input
    internal abstract val authorizedFriendAssemblies: SetProperty<String>

    @get:OutputFile
    internal abstract val assemblyOutputFile: RegularFileProperty

    @get:Internal
    internal var executionTimeFreeCompilerArgs: List<String>? = null

    override fun createCompilerArguments(context: CreateCompilerArgumentsContext) = context.create<K2DotNetCompilerArguments> {
        primitive { args ->
            args.multiPlatform = multiPlatformEnabled.get()
            args.dotNetTarget = targetFramework.get().targetFrameworkMoniker
            args.dotNetProduceLibrary = true
            args.dotNetFriendAssemblies = authorizedFriendAssemblies.get().sorted().toTypedArray()
            args.destination = destinationDirectory.get().asFile.normalize().absolutePath

            args.pluginOptions = (pluginOptions.toSingleCompilerPluginOptions() + kotlinPluginData?.orNull?.options)
                .arguments.toTypedArray()

            if (reportingSettings().buildReportMode == BuildReportMode.VERBOSE) {
                args.reportPerf = true
            }

            explicitApiMode.orNull?.run { args.explicitApi = toCompilerValue() }
            KotlinDotNetCompilerOptionsHelper.fillCompilerArguments(compilerOptions, args)

            executionTimeFreeCompilerArgs?.let { args.freeArgs = it }
        }

        pluginClasspath { args ->
            args.pluginClasspaths = runSafe {
                listOfNotNull(pluginClasspath, kotlinPluginData?.orNull?.classpath)
                    .reduce(FileCollection::plus)
                    .toPathsArray()
            } ?: emptyArray()
        }

        dependencyClasspath { args ->
            args.classpath = runSafe {
                libraries.files.filter { it.exists() }.joinToString(File.pathSeparator)
            }
            args.friendPaths = runSafe { friendPaths.files.toPathsArray() } ?: emptyArray()
        }

        sources { args ->
            args.freeArgs += sources.asFileTree.map { it.absolutePath }
            args.commonSources = commonSourceSet.asFileTree.toPathsArray()
        }
    }

    override fun callCompilerAsync(
        args: K2DotNetCompilerArguments,
        inputChanges: InputChanges,
        taskOutputsBackup: TaskOutputsBackup?,
    ) {
        val gradlePrintingMessageCollector = GradlePrintingMessageCollector(logger, args.allWarningsAsErrors)
        val gradleMessageCollector = GradleErrorMessageCollector(logger, gradlePrintingMessageCollector)
        val outputItemCollector = OutputItemsCollectorImpl()
        val compilerRunner = compilerRunner.get()
        val environment = GradleCompilerEnvironment(
            defaultCompilerClasspath,
            gradleMessageCollector,
            outputItemCollector,
            reportingSettings = reportingSettings(),
            outputFiles = allOutputFiles(),
            compilerArgumentsLogLevel = kotlinCompilerArgumentsLogLevel.get(),
            toolingDiagnosticsCollector = toolingDiagnosticsCollector,
            toolingDiagnosticsContext = toolingDiagnosticsContext,
        )
        compilerRunner.runDotNetCompilerAsync(args, environment, taskOutputsBackup)
        compilerRunner.errorsFiles?.let { gradleMessageCollector.flush(it) }
    }
}
