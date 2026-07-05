package org.jetbrains.kotlin.backend.dotnet

/**
 * Assembles the class wrapper around already rendered methods: the file class of one Kotlin file
 * (public, like the JVM's file facades), or, with [exported] = false, the module-private runtime
 * helper class (see [DotNetIlRuntimeHelper]).
 */
internal class DotNetIlClassCodegen(
    private val className: String,
    private val renderedMethods: List<String>,
    private val exported: Boolean = true,
) {
    fun generate(builder: StringBuilder) {
        val visibility = if (exported) "public" else "private"
        builder.appendLine(".class $visibility abstract sealed auto ansi beforefieldinit ${className.toIlIdentifier()}")
        builder.appendLine("       extends [mscorlib]System.Object")
        builder.appendLine("{")
        for (method in renderedMethods) {
            builder.append(method)
        }
        builder.appendLine("}")
    }
}
