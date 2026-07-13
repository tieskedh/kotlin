package org.jetbrains.kotlin.platform

abstract class DotNetPlatform : SimplePlatform("DotNet")

object DotNetPlatforms {
    object DefaultSimpleDotNetPlatform : DotNetPlatform() {
        override val targetName: String
            get() = "dotnet"

        override val oldFashionedDescription: String
            get() = "Kotlin/.NET"
    }

    val defaultDotNetPlatform: TargetPlatform
        get() = DefaultSimpleDotNetPlatform.toTargetPlatform()

    val allDotNetPlatforms: List<TargetPlatform>
        get() = listOf(defaultDotNetPlatform)
}

fun TargetPlatform?.isDotNet(): Boolean = this?.singleOrNull() is DotNetPlatform
