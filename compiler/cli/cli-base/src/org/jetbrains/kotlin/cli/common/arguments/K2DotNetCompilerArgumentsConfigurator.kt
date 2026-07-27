package org.jetbrains.kotlin.cli.common.arguments

class K2DotNetCompilerArgumentsConfigurator : CommonKlibBasedCompilerArgumentsConfigurator() {
    override fun isSecondStage(arguments: CommonCompilerArguments): Boolean {
        require(arguments is K2DotNetCompilerArguments)

        // The backend links dependency IR into every CLR product, but has not registered the
        // common partial-linkage diagnostic names yet. Enabling second-stage warning mapping
        // before that integration makes every invocation fail during argument configuration.
        return false
    }
}
