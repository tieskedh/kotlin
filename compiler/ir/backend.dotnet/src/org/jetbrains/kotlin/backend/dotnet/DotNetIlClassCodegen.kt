package org.jetbrains.kotlin.backend.dotnet

/**
 * Assembles the file class wrapper around the already rendered methods of one Kotlin file.
 */
internal class DotNetIlClassCodegen(
    private val className: String,
    private val renderedMethods: List<String>,
) {
    fun generate(builder: StringBuilder) {
        builder.appendLine(".class public abstract sealed auto ansi beforefieldinit ${className.toIlIdentifier()}")
        builder.appendLine("       extends [mscorlib]System.Object")
        builder.appendLine("{")
        for (method in renderedMethods) {
            builder.append(method)
        }
        builder.appendLine("}")
    }
}
