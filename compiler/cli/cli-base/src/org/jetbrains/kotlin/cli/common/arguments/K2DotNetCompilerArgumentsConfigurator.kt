package org.jetbrains.kotlin.cli.common.arguments

import org.jetbrains.kotlin.config.AnalysisFlag
import org.jetbrains.kotlin.config.AnalysisFlags
import org.jetbrains.kotlin.config.LanguageVersion

class K2DotNetCompilerArgumentsConfigurator : CommonCompilerArgumentsConfigurator() {
    override fun configureAnalysisFlags(
        arguments: CommonCompilerArguments,
        reporter: Reporter,
        languageVersion: LanguageVersion,
    ): MutableMap<AnalysisFlag<*>, Any> {
        return super.configureAnalysisFlags(arguments, reporter, languageVersion).apply {
            if (arguments is K2DotNetCompilerArguments && !arguments.noStdlib) {
                putAnalysisFlag(AnalysisFlags.allowKotlinPackage, true)
            }
        }
    }
}
