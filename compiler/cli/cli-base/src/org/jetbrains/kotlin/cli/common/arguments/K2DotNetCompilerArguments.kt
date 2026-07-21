package org.jetbrains.kotlin.cli.common.arguments

import com.intellij.util.xmlb.annotations.Transient

class K2DotNetCompilerArguments : CommonCompilerArguments() {
    companion object {
        @JvmStatic
        private val serialVersionUID = 0L
    }

    @Argument(
        value = "-d",
        valueDescription = "<path>",
        description = "Destination .il file or output directory; library producers require a directory."
    )
    var destination: String? = null
        set(value) {
            checkFrozen()
            field = if (value.isNullOrEmpty()) null else value
        }

    @Argument(value = "-module-name", valueDescription = "<name>", description = "Name of the generated .NET assembly.")
    var moduleName: String? = null
        set(value) {
            checkFrozen()
            field = if (value.isNullOrEmpty()) null else value
        }

    @Argument(
        value = "-classpath",
        shortName = "-cp",
        valueDescription = "<path>",
        description = "List of directories and JAR/ZIP archives to search for Kotlin metadata."
    )
    var classpath: String? = null
        set(value) {
            checkFrozen()
            field = if (value.isNullOrEmpty()) null else value
        }

    @Argument(
        value = "-Xfriend-paths",
        valueDescription = "<path>",
        description = "Paths to producer-authorized Kotlin/.NET friend metadata libraries."
    )
    var friendPaths: Array<String> = emptyArray()
        set(value) {
            checkFrozen()
            field = value
        }

    @Argument(
        value = "-Xdotnet-friend-assembly",
        delimiter = Argument.Delimiters.none,
        valueDescription = "<assembly-name>[, PublicKey=<full-public-key>]",
        description = "Authorize a CLR friend assembly through producer-emitted InternalsVisibleTo. May be repeated."
    )
    var dotNetFriendAssemblies: Array<String>? = null
        set(value) {
            checkFrozen()
            field = value
        }

    @Argument(value = "-no-stdlib", description = "Don't automatically add the bundled Kotlin stdlib metadata to the classpath.")
    var noStdlib: Boolean = false
        set(value) {
            checkFrozen()
            field = value
        }

    @Argument(
        value = "-Xdotnet-produce-stdlib",
        description = "Produce the experimental Kotlin.Stdlib.klib/Kotlin.Stdlib.dll pair in the -d directory. " +
                "The library uses the selected net48, netstandard2.0, or net10.0 target profile. " +
                "This build mode accepts no user source files."
    )
    var dotNetProduceStdlib: Boolean = false
        set(value) {
            checkFrozen()
            field = value
        }

    @Argument(
        value = "-Xdotnet-produce-library",
        description = "Produce an experimental <module>.klib/<module>.dll library pair in the -d directory. " +
                "The library uses the selected target profile and has no entry point."
    )
    var dotNetProduceLibrary: Boolean = false
        set(value) {
            checkFrozen()
            field = value
        }

    @Argument(
        value = "-Xdotnet-target",
        valueDescription = "{net48|netstandard2.0|net10.0}",
        description = "Select the target-framework/API profile independently of product kind. " +
                "net48 and net10.0 support applications and libraries; netstandard2.0 supports libraries only."
    )
    var dotNetTarget: String? = null
        set(value) {
            checkFrozen()
            field = if (value.isNullOrEmpty()) null else value
        }

    @Argument(
        value = "-Xdotnet-export",
        delimiter = Argument.Delimiters.none,
        valueDescription = "<kotlin-selector>=<clr-method-name>",
        description = "Export a public top-level function through an explicitly named CLR facade method. " +
                "An overloaded selector adds fully qualified Kotlin parameter types in parentheses. " +
                "Function0/1/2 positions use typed Func/Action shapes. May be repeated."
    )
    var dotNetExports: Array<String>? = null
        set(value) {
            checkFrozen()
            field = value
        }

    @Argument(
        value = "-Xdotnet-export-property",
        delimiter = Argument.Delimiters.none,
        valueDescription = "<kotlin-fq-name>=<clr-property-name>",
        description = "Export a unique public top-level property through an explicitly named CLR property. " +
                "This provisional POC option may be repeated."
    )
    var dotNetPropertyExports: Array<String>? = null
        set(value) {
            checkFrozen()
            field = value
        }

    override fun copyOf(): Freezable {
        val copy = K2DotNetCompilerArguments()
        copyCommonCompilerArguments(this, copy)
        copy.destination = destination
        copy.moduleName = moduleName
        copy.classpath = classpath
        copy.friendPaths = friendPaths.copyOf()
        copy.dotNetFriendAssemblies = dotNetFriendAssemblies?.copyOf()
        copy.noStdlib = noStdlib
        copy.dotNetProduceStdlib = dotNetProduceStdlib
        copy.dotNetProduceLibrary = dotNetProduceLibrary
        copy.dotNetTarget = dotNetTarget
        copy.dotNetExports = dotNetExports?.copyOf()
        copy.dotNetPropertyExports = dotNetPropertyExports?.copyOf()
        return copy
    }

    @get:Transient
    @field:kotlin.jvm.Transient
    override val configurator: CommonCompilerArgumentsConfigurator = K2DotNetCompilerArgumentsConfigurator()
}
