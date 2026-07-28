/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package org.jetbrains.kotlin.cli.common.arguments

import com.intellij.util.xmlb.annotations.Transient

// This file was generated automatically. See generator in :compiler:cli:cli-arguments-generator
// Please declare arguments in compiler/arguments/src/org/jetbrains/kotlin/arguments/description/DotNetCompilerArguments.kt
// DO NOT MODIFY IT MANUALLY.

class K2DotNetCompilerArguments : CommonKlibBasedCompilerArguments() {
    @Argument(
        value = "-Xdotnet-export",
        valueDescription = "<kotlin-selector>=<clr-method-name>",
        description = "Export a public top-level function through an explicitly named CLR facade method. An overloaded selector adds fully qualified Kotlin parameter types in parentheses. Function0/1/2 positions use typed Func/Action shapes. May be repeated.",
        delimiter = Argument.Delimiters.none,
    )
    var dotNetExports: Array<String> = emptyArray()
        set(value) {
            checkFrozen()
            field = value
        }

    @Argument(
        value = "-Xdotnet-export-property",
        valueDescription = "<kotlin-fq-name>=<clr-property-name>",
        description = "Export a unique public top-level property through an explicitly named CLR property. This provisional POC option may be repeated.",
        delimiter = Argument.Delimiters.none,
    )
    var dotNetPropertyExports: Array<String> = emptyArray()
        set(value) {
            checkFrozen()
            field = value
        }

    @Argument(
        value = "-Xdotnet-friend-assembly",
        valueDescription = "<assembly-name>[, PublicKey=<full-public-key>]",
        description = "Authorize a CLR friend assembly through producer-emitted InternalsVisibleTo. May be repeated.",
        delimiter = Argument.Delimiters.none,
    )
    var dotNetFriendAssemblies: Array<String> = emptyArray()
        set(value) {
            checkFrozen()
            field = value
        }

    @Argument(
        value = "-Xdotnet-produce-library",
        description = "Produce an experimental self-describing <module>.dll library in the -d directory. The library uses the selected target profile and has no entry point.",
    )
    var dotNetProduceLibrary: Boolean = false
        set(value) {
            checkFrozen()
            field = value
        }

    @Argument(
        value = "-Xdotnet-produce-stdlib",
        description = "Produce the experimental self-describing Kotlin.Stdlib.dll in the -d directory. The library uses the selected net48, netstandard2.0, or net10.0 target profile. When the complete product source set is supplied, those files are compiled directly; otherwise the compiler uses its packaged bootstrap fallback.",
    )
    var dotNetProduceStdlib: Boolean = false
        set(value) {
            checkFrozen()
            field = value
        }

    @Argument(
        value = "-Xdotnet-target",
        valueDescription = "{net48|netstandard2.0|net10.0}",
        description = "Select the target-framework/API profile independently of product kind. net48 and net10.0 support applications and libraries; netstandard2.0 supports libraries only.",
    )
    var dotNetTarget: String? = null
        set(value) {
            checkFrozen()
            field = if (value.isNullOrEmpty()) null else value
        }

    @Argument(
        value = "-Xfriend-paths",
        valueDescription = "<path>",
        description = "Paths to producer-authorized Kotlin/.NET friend metadata libraries.",
    )
    var friendPaths: Array<String> = emptyArray()
        set(value) {
            checkFrozen()
            field = value
        }

    @Argument(
        value = "-classpath",
        shortName = "-cp",
        valueDescription = "<path>",
        description = "List of directories and archives to search for Kotlin metadata.",
    )
    var classpath: String? = null
        set(value) {
            checkFrozen()
            field = if (value.isNullOrEmpty()) null else value
        }

    @Argument(
        value = "-d",
        valueDescription = "<path>",
        description = "Destination .il file or output directory; library producers require a directory.",
    )
    var destination: String? = null
        set(value) {
            checkFrozen()
            field = if (value.isNullOrEmpty()) null else value
        }

    @Argument(
        value = "-module-name",
        valueDescription = "<name>",
        description = "Name of the generated .NET assembly.",
    )
    var moduleName: String? = null
        set(value) {
            checkFrozen()
            field = if (value.isNullOrEmpty()) null else value
        }

    @Argument(
        value = "-no-stdlib",
        description = "Don't automatically add the bundled Kotlin/.NET stdlib DLL to the classpath.",
    )
    var noStdlib: Boolean = false
        set(value) {
            checkFrozen()
            field = value
        }

    @get:Transient
    @field:kotlin.jvm.Transient
    override val configurator: CommonCompilerArgumentsConfigurator = K2DotNetCompilerArgumentsConfigurator()

    override fun copyOf(): Freezable = copyK2DotNetCompilerArguments(this, K2DotNetCompilerArguments())
}
