package org.jetbrains.kotlin.backend.dotnet

/**
 * Assembles the class wrapper around already rendered members: the file class of one Kotlin file
 * (public and static — `abstract sealed` — like the JVM's file facades), a top-level user class
 * ([isStaticHolder] = false: instantiable, and `sealed` because only final classes are
 * supported), a companion object ([isNested] = true: a real CLR nested type declared inside the
 * enclosing class's body), or, with [exported] = false, the module-private runtime helper class
 * (see [DotNetIlRuntimeHelper]). [renderedNestedClasses] are complete, already indented `.class`
 * blocks (a top-level user class carries at most its companion) emitted first in the body;
 * [renderedFields] are single `.field` lines emitted before the methods and
 * [renderedProperties] are `.property` blocks emitted after them (ilasm accepts any member order
 * — probe-verified — so this is the deterministic order the goldens freeze).
 *
 * [hasClassInitializer] drops `beforefieldinit` from the flags: with the flag, the CLR may defer
 * (or even skip) the `.cctor` around static METHOD calls, so a caller touching only a top-level
 * function of a facade would silently skip the file's property-initializer side effects; without
 * it the CLR runs the `.cctor` before the first active use of the class (probe-verified,
 * `statprobe_s1`) — Kotlin/JVM first-active-use class-initialization parity. It is omitted
 * exactly on classes that receive a `.cctor` and kept everywhere else, so classes without static
 * state keep the relaxed (cheaper) semantics and their goldens. A companion never has one — its
 * singleton field and the `newobj`/`stsfld` live on the ENCLOSING class (which therefore drops
 * `beforefieldinit`), while the companion itself has no statics and keeps the flag.
 */
internal class DotNetIlClassCodegen(
    private val className: String,
    private val renderedMethods: List<String>,
    private val renderedFields: List<String> = emptyList(),
    private val renderedProperties: List<String> = emptyList(),
    private val isStaticHolder: Boolean = true,
    private val exported: Boolean = true,
    private val hasClassInitializer: Boolean = false,
    private val isNested: Boolean = false,
    private val renderedNestedClasses: List<String> = emptyList(),
) {
    fun generate(builder: StringBuilder) {
        val visibility = if (exported) "public" else "private"
        // All flag spellings (including their order, with and without beforefieldinit) are
        // ilasm-probe-verified (the nested one by objprobe_s6); the static-holder one is
        // additionally frozen by the goldens.
        val beforeFieldInit = if (hasClassInitializer) "" else " beforefieldinit"
        val flags = when {
            // A nested class is never a static holder here: the only nested shape emitted is
            // the companion object, an instantiable singleton.
            isNested -> "nested $visibility auto ansi sealed$beforeFieldInit"
            isStaticHolder -> "$visibility abstract sealed auto ansi$beforeFieldInit"
            else -> "$visibility auto ansi sealed$beforeFieldInit"
        }
        builder.appendLine(".class $flags ${className.toIlIdentifier()}")
        builder.appendLine("       extends ${CORE_LIB_REF}System.Object")
        builder.appendLine("{")
        for (nestedClass in renderedNestedClasses) {
            builder.append(nestedClass)
        }
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
