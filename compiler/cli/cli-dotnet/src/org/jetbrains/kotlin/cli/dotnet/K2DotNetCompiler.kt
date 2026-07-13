package org.jetbrains.kotlin.cli.dotnet

import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.cli.common.CLICompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2DotNetCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetCliPipeline
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.config.messageCollector
import org.jetbrains.kotlin.metadata.deserialization.BinaryVersion
import org.jetbrains.kotlin.metadata.deserialization.MetadataVersion
import org.jetbrains.kotlin.platform.DotNetPlatforms
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.utils.KotlinPaths

class K2DotNetCompiler : CLICompiler<K2DotNetCompilerArguments>() {
    override val platform: TargetPlatform
        get() = DotNetPlatforms.defaultDotNetPlatform

    override fun createMetadataVersion(versionArray: IntArray): BinaryVersion = MetadataVersion(*versionArray)

    override fun setupPlatformSpecificArgumentsAndServices(
        configuration: CompilerConfiguration,
        arguments: K2DotNetCompilerArguments,
        services: Services,
    ) {
    }

    override fun doExecutePhased(
        arguments: K2DotNetCompilerArguments,
        services: Services,
        basicMessageCollector: MessageCollector,
    ): ExitCode {
        return DotNetCliPipeline(defaultPerformanceManager).execute(arguments, services, basicMessageCollector)
    }

    override fun doExecute(
        arguments: K2DotNetCompilerArguments,
        configuration: CompilerConfiguration,
        rootDisposable: Disposable,
        paths: KotlinPaths?,
    ): ExitCode {
        configuration.messageCollector.report(
            org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.ERROR,
            "Kotlin/.NET target is supported only by the K2 phased pipeline."
        )
        return ExitCode.COMPILATION_ERROR
    }

    override fun MutableList<String>.addPlatformOptions(arguments: K2DotNetCompilerArguments) {
    }

    override fun createArguments(): K2DotNetCompilerArguments = K2DotNetCompilerArguments()

    override fun executableScriptFileName(): String = "kotlinc-dotnet"

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            doMain(K2DotNetCompiler(), args)
        }
    }
}

fun main(args: Array<String>) = K2DotNetCompiler.main(args)
