package org.jetbrains.kotlin.backend.dotnet

/**
 * Assembles the class wrapper around already rendered members: the file class of one Kotlin file
 * (public and static — `abstract sealed` — like the JVM's file facades), a top-level user class
 * ([isStaticHolder] = false: instantiable, and `sealed` because only final classes are
 * supported), or, with [exported] = false, the module-private runtime helper class (see
 * [DotNetIlRuntimeHelper]). [renderedFields] are single `.field` lines emitted before the
 * methods and [renderedProperties] are `.property` blocks emitted after them (ilasm accepts any
 * member order — probe-verified — so this is the deterministic order the goldens freeze); only
 * user classes have either.
 */
internal class DotNetIlClassCodegen(
    private val className: String,
    private val renderedMethods: List<String>,
    private val renderedFields: List<String> = emptyList(),
    private val renderedProperties: List<String> = emptyList(),
    private val isStaticHolder: Boolean = true,
    private val exported: Boolean = true,
) {
    fun generate(builder: StringBuilder) {
        val visibility = if (exported) "public" else "private"
        // Both flag spellings (including their order) are ilasm-probe-verified; the static-holder
        // one is additionally frozen by the existing goldens.
        val flags =
            if (isStaticHolder) "$visibility abstract sealed auto ansi beforefieldinit"
            else "$visibility auto ansi sealed beforefieldinit"
        builder.appendLine(".class $flags ${className.toIlIdentifier()}")
        builder.appendLine("       extends ${CORE_LIB_REF}System.Object")
        builder.appendLine("{")
        for (field in renderedFields) {
            builder.appendLine("  $field")
        }
        for (method in renderedMethods) {
            builder.append(method)
        }
        for (property in renderedProperties) {
            builder.append(property)
        }
        builder.appendLine("}")
    }
}
