package org.jetbrains.kotlin.cli.dotnet

import org.jetbrains.kotlin.cli.common.CLICompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2DotNetCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.pipeline.dotnet.DotNetCliPipeline
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.metadata.deserialization.BinaryVersion
import org.jetbrains.kotlin.metadata.deserialization.MetadataVersion
import org.jetbrains.kotlin.platform.dotnet.DotNetPlatforms
import org.jetbrains.kotlin.platform.TargetPlatform

class K2DotNetCompiler : CLICompiler<K2DotNetCompilerArguments>() {
    override val platform: TargetPlatform
        get() = DotNetPlatforms.defaultDotNetPlatform

    override fun createMetadataVersion(versionArray: IntArray): BinaryVersion = MetadataVersion(*versionArray)

    override fun doExecutePhased(
        arguments: K2DotNetCompilerArguments,
        services: Services,
        basicMessageCollector: MessageCollector,
    ): ExitCode {
        return DotNetCliPipeline(defaultPerformanceManager).execute(arguments, services, basicMessageCollector)
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
