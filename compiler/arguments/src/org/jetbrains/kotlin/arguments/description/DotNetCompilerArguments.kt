/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.arguments.description

import org.jetbrains.kotlin.arguments.dsl.base.ExperimentalArgumentApi
import org.jetbrains.kotlin.arguments.dsl.base.KotlinCompilerArgument
import org.jetbrains.kotlin.arguments.dsl.base.KotlinReleaseVersion
import org.jetbrains.kotlin.arguments.dsl.base.asReleaseDependent
import org.jetbrains.kotlin.arguments.dsl.base.compilerArgumentsLevel
import org.jetbrains.kotlin.arguments.dsl.defaultEmpty
import org.jetbrains.kotlin.arguments.dsl.defaultFalse
import org.jetbrains.kotlin.arguments.dsl.defaultNull
import org.jetbrains.kotlin.arguments.dsl.types.BooleanType
import org.jetbrains.kotlin.arguments.dsl.types.PathListType
import org.jetbrains.kotlin.arguments.dsl.types.SearchPathType
import org.jetbrains.kotlin.arguments.dsl.types.StringArrayType
import org.jetbrains.kotlin.arguments.dsl.types.StringType

val actualDotNetArguments by compilerArgumentsLevel(CompilerArgumentsLevelNames.dotNetArguments) {
    compilerArgument {
        name = "d"
        compilerName = "destination"
        description = "Destination .il file or output directory; library producers require a directory.".asReleaseDependent()
        valueType = StringType.defaultNull
        valueDescription = "<path>".asReleaseDependent()

        lifecycle(introducedVersion = KotlinReleaseVersion.v2_5_0)
    }

    compilerArgument {
        name = "module-name"
        description = "Name of the generated .NET assembly.".asReleaseDependent()
        valueType = StringType.defaultNull
        valueDescription = "<name>".asReleaseDependent()

        lifecycle(introducedVersion = KotlinReleaseVersion.v2_5_0)
    }

    @OptIn(ExperimentalArgumentApi::class)
    compilerArgument {
        name = "classpath"
        shortName = "cp"
        description = "List of directories and archives to search for Kotlin metadata.".asReleaseDependent()
        valueType = StringType.defaultNull
        valueDescription = "<path>".asReleaseDependent()
        argumentType = SearchPathType()

        lifecycle(introducedVersion = KotlinReleaseVersion.v2_5_0)
    }

    @OptIn(ExperimentalArgumentApi::class)
    compilerArgument {
        name = "Xfriend-paths"
        description = "Paths to producer-authorized Kotlin/.NET friend metadata libraries.".asReleaseDependent()
        valueType = StringArrayType.defaultNull
        valueDescription = "<path>".asReleaseDependent()
        argumentType = PathListType.defaultEmpty

        lifecycle(introducedVersion = KotlinReleaseVersion.v2_5_0)
    }

    compilerArgument {
        name = "Xdotnet-friend-assembly"
        compilerName = "dotNetFriendAssemblies"
        description =
            "Authorize a CLR friend assembly through producer-emitted InternalsVisibleTo. May be repeated.".asReleaseDependent()
        valueType = StringArrayType.defaultNull
        valueDescription = "<assembly-name>[, PublicKey=<full-public-key>]".asReleaseDependent()
        delimiter = KotlinCompilerArgument.Delimiter.None

        lifecycle(introducedVersion = KotlinReleaseVersion.v2_5_0)
    }

    compilerArgument {
        name = "no-stdlib"
        description = "Don't automatically add the bundled Kotlin/.NET stdlib DLL to the classpath.".asReleaseDependent()
        valueType = BooleanType.defaultFalse

        lifecycle(introducedVersion = KotlinReleaseVersion.v2_5_0)
    }

    compilerArgument {
        name = "Xdotnet-produce-stdlib"
        compilerName = "dotNetProduceStdlib"
        description = (
                "Produce the experimental self-describing Kotlin.Stdlib.dll in the -d directory. " +
                        "The library uses the selected net48, netstandard2.0, or net10.0 target profile. " +
                        "This build mode accepts no user source files."
                ).asReleaseDependent()
        valueType = BooleanType.defaultFalse

        lifecycle(introducedVersion = KotlinReleaseVersion.v2_5_0)
    }

    compilerArgument {
        name = "Xdotnet-produce-library"
        compilerName = "dotNetProduceLibrary"
        description = (
                "Produce an experimental self-describing <module>.dll library in the -d directory. " +
                        "The library uses the selected target profile and has no entry point."
                ).asReleaseDependent()
        valueType = BooleanType.defaultFalse

        lifecycle(introducedVersion = KotlinReleaseVersion.v2_5_0)
    }

    compilerArgument {
        name = "Xdotnet-target"
        compilerName = "dotNetTarget"
        description = (
                "Select the target-framework/API profile independently of product kind. " +
                        "net48 and net10.0 support applications and libraries; netstandard2.0 supports libraries only."
                ).asReleaseDependent()
        valueType = StringType.defaultNull
        valueDescription = "{net48|netstandard2.0|net10.0}".asReleaseDependent()

        lifecycle(introducedVersion = KotlinReleaseVersion.v2_5_0)
    }

    compilerArgument {
        name = "Xdotnet-export"
        compilerName = "dotNetExports"
        description = (
                "Export a public top-level function through an explicitly named CLR facade method. " +
                        "An overloaded selector adds fully qualified Kotlin parameter types in parentheses. " +
                        "Function0/1/2 positions use typed Func/Action shapes. May be repeated."
                ).asReleaseDependent()
        valueType = StringArrayType.defaultNull
        valueDescription = "<kotlin-selector>=<clr-method-name>".asReleaseDependent()
        delimiter = KotlinCompilerArgument.Delimiter.None

        lifecycle(introducedVersion = KotlinReleaseVersion.v2_5_0)
    }

    compilerArgument {
        name = "Xdotnet-export-property"
        compilerName = "dotNetPropertyExports"
        description = (
                "Export a unique public top-level property through an explicitly named CLR property. " +
                        "This provisional POC option may be repeated."
                ).asReleaseDependent()
        valueType = StringArrayType.defaultNull
        valueDescription = "<kotlin-fq-name>=<clr-property-name>".asReleaseDependent()
        delimiter = KotlinCompilerArgument.Delimiter.None

        lifecycle(introducedVersion = KotlinReleaseVersion.v2_5_0)
    }
}
