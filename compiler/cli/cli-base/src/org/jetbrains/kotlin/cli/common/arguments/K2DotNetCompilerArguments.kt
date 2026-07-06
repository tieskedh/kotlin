package org.jetbrains.kotlin.cli.common.arguments

import com.intellij.util.xmlb.annotations.Transient

class K2DotNetCompilerArguments : CommonCompilerArguments() {
    companion object {
        @JvmStatic
        private val serialVersionUID = 0L
    }

    @Argument(value = "-d", valueDescription = "<path>", description = "Destination .il file or output directory.")
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

    @Argument(value = "-no-stdlib", description = "Don't automatically add the bundled Kotlin stdlib metadata to the classpath.")
    var noStdlib: Boolean = false
        set(value) {
            checkFrozen()
            field = value
        }

    @Argument(
        value = "-Xdotnet-target",
        valueDescription = "{netframework|net}",
        description = "The .NET runtime flavor of the produced executable: " +
                "'netframework' assembles a .NET Framework .exe (default), " +
                "'net' assembles a modern .NET .dll with a runtimeconfig.json for 'dotnet exec'."
    )
    var dotNetTarget: String? = null
        set(value) {
            checkFrozen()
            field = if (value.isNullOrEmpty()) null else value
        }

    override fun copyOf(): Freezable {
        val copy = K2DotNetCompilerArguments()
        copyCommonCompilerArguments(this, copy)
        copy.destination = destination
        copy.moduleName = moduleName
        copy.classpath = classpath
        copy.noStdlib = noStdlib
        copy.dotNetTarget = dotNetTarget
        return copy
    }

    @get:Transient
    @field:kotlin.jvm.Transient
    override val configurator: CommonCompilerArgumentsConfigurator = K2DotNetCompilerArgumentsConfigurator()
}
