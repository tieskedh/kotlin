package org.jetbrains.kotlin.backend.dotnet

/** Exact CLI accessibility selected for one emitted TypeDef. */
internal enum class DotNetIlRawTypeDefVisibility(
    val ilKeyword: String,
    val isNested: Boolean,
) {
    PUBLIC("public", false),
    NOT_PUBLIC("private", false),
    NESTED_PUBLIC("public", true),
    NESTED_PRIVATE("private", true),
    NESTED_ASSEMBLY("assembly", true),
    NESTED_FAMILY("family", true),
}

/** Exact CLI layout mask selected by the current TypeDef emitter. */
internal enum class DotNetIlRawTypeDefLayout(val ilKeyword: String) {
    AUTO("auto"),
}

/** Exact CLI string-format mask selected by the current TypeDef emitter. */
internal enum class DotNetIlRawTypeDefStringFormat(val ilKeyword: String) {
    ANSI("ansi"),
}

/**
 * Structured TypeDef flags consumed directly by IL rendering.
 *
 * Keeping this as the render input makes a later final-emission observation evidence from the
 * same decision, rather than a reconstruction from the emitted text. The current backend admits
 * only auto-layout ANSI classes/interfaces, but those masks remain explicit physical facts.
 */
internal data class DotNetIlRawTypeDefFlags(
    val visibility: DotNetIlRawTypeDefVisibility,
    val layout: DotNetIlRawTypeDefLayout,
    val stringFormat: DotNetIlRawTypeDefStringFormat,
    val isInterface: Boolean,
    val isAbstract: Boolean,
    val isSealed: Boolean,
    val isBeforeFieldInit: Boolean,
) {
    init {
        require(!isInterface || isAbstract && !isSealed && !isBeforeFieldInit) {
            "an interface TypeDef must be abstract, unsealed, and omit beforefieldinit"
        }
    }

    val ilKeywords: String
        get() = buildString {
            if (visibility.isNested) {
                append("nested ")
                append(visibility.ilKeyword)
                append(' ')
            }
            if (isInterface) {
                append("interface ")
                if (!visibility.isNested) {
                    append(visibility.ilKeyword)
                    append(' ')
                }
            } else if (!visibility.isNested) {
                append(visibility.ilKeyword)
                append(' ')
            }
            if (isAbstract) append("abstract ")
            // Preserve the probe-verified legacy spelling: static holders are
            // `abstract sealed auto ansi`, while ordinary final classes are
            // `auto ansi sealed`. The flags are a set in CLI metadata, but the
            // textual emitter and its goldens deliberately keep one stable order.
            if (isAbstract && isSealed) append("sealed ")
            append(layout.ilKeyword)
            append(' ')
            append(stringFormat.ilKeyword)
            if (!isAbstract && isSealed) append(" sealed")
            if (isBeforeFieldInit) append(" beforefieldinit")
        }
}

private fun legacyDotNetIlRawTypeDefFlags(
    isStaticHolder: Boolean,
    exported: Boolean,
    hasClassInitializer: Boolean,
    isNested: Boolean,
    nestedVisibility: String,
    isOpen: Boolean,
    isAbstract: Boolean,
    isInterface: Boolean,
): DotNetIlRawTypeDefFlags {
    val visibility = if (isNested) {
        when (nestedVisibility) {
            "public" -> DotNetIlRawTypeDefVisibility.NESTED_PUBLIC
            "private" -> DotNetIlRawTypeDefVisibility.NESTED_PRIVATE
            "assembly" -> DotNetIlRawTypeDefVisibility.NESTED_ASSEMBLY
            "family" -> DotNetIlRawTypeDefVisibility.NESTED_FAMILY
            else -> error("Internal .NET backend error: unsupported nested TypeDef visibility '$nestedVisibility'")
        }
    } else if (exported) {
        DotNetIlRawTypeDefVisibility.PUBLIC
    } else {
        DotNetIlRawTypeDefVisibility.NOT_PUBLIC
    }
    return DotNetIlRawTypeDefFlags(
        visibility = visibility,
        layout = DotNetIlRawTypeDefLayout.AUTO,
        stringFormat = DotNetIlRawTypeDefStringFormat.ANSI,
        isInterface = isInterface,
        isAbstract = isInterface || isStaticHolder || isAbstract,
        isSealed = !isInterface && (isStaticHolder || !isAbstract && !isOpen),
        isBeforeFieldInit = !isInterface && !hasClassInitializer,
    )
}

/**
 * Assembles the class wrapper around already rendered members: the file class of one Kotlin file
 * (public and static — `abstract sealed` — like the JVM's file facades), a top-level user class
 * ([isStaticHolder] = false: instantiable, `sealed` for a final Kotlin class, unsealed with
 * [isOpen], or non-instantiable `abstract` with [isAbstract] — the CLR expresses Kotlin modality
 * directly in metadata, unlike the JVM access flags which the JVM backend derives from the same
 * modality), a named nested class, interface, or companion object ([isNested] = true: a real CLR
 * nested type declared inside the enclosing type's body, with [nestedVisibility]), a Kotlin
 * interface
 * ([isInterface]: `.class interface public abstract
 * auto ansi` with NO `extends` line, no `sealed`, no `beforefieldinit` — the exact flag set
 * probe-verified, `ifaceprobe_s1`), or a compiler-synthesized local class with [exported] = false.
 * [baseClassRef] is the already-rendered IL type
 * reference of the base class of the inheritance model (`extends 'demo.Base'`; assembly-local,
 * forward references legal — probe-verified, `inheritprobe_s1`); without one the class extends
 * the corelib `System.Object`, the IL spelling of `kotlin.Any`. [interfaceRefs] are the
 * already-rendered IL type references of the directly implemented interfaces: bare non-generic
 * refs or full generic instantiations (`class 'Producer`1'<!0>`), printed as a comma-separated
 * `implements` line after `extends` (spelling probe-verified, `ifaceprobe_s3` and
 * `genifaceprobe_s1`; on an interface the same line lists its direct super-interfaces —
 * transitively implied super-interfaces are never repeated).
 * [renderedNestedClasses] are complete, already indented `.class` blocks emitted first in the body;
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
 * state keep the relaxed (cheaper) semantics and their goldens. A companion never has one: its
 * singleton field and the `newobj`/`stsfld` live on the classifier's selected static owner, while
 * the companion type itself has no statics and keeps the flag. An interface may own this event
 * directly or through a non-generic holder; interfaces never carry `beforefieldinit`.
 */
internal class DotNetIlClassCodegen(
    private val className: String,
    private val renderedMethods: List<String>,
    private val renderedFields: List<String> = emptyList(),
    private val renderedProperties: List<String> = emptyList(),
    private val renderedAttributes: List<String> = emptyList(),
    private val isStaticHolder: Boolean = true,
    private val exported: Boolean = true,
    private val hasClassInitializer: Boolean = false,
    private val isNested: Boolean = false,
    private val nestedVisibility: String = "public",
    private val renderedNestedClasses: List<String> = emptyList(),
    private val isOpen: Boolean = false,
    private val isAbstract: Boolean = false,
    private val baseClassRef: String? = null,
    private val isInterface: Boolean = false,
    private val interfaceRefs: List<String> = emptyList(),
    private val genericParameters: String? = null,
    private val coreLibraryReference: String = DEFAULT_EXECUTABLE_CORE_LIBRARY.reference,
    /** Assembly-independent exact metadata path; the final component is the declared name. */
    val physicalTypePath: List<String> = listOf(className),
    /** Exact TypeDef flag decision shared by rendering and final-emission observation. */
    val flags: DotNetIlRawTypeDefFlags = legacyDotNetIlRawTypeDefFlags(
        isStaticHolder = isStaticHolder,
        exported = exported,
        hasClassInitializer = hasClassInitializer,
        isNested = isNested,
        nestedVisibility = nestedVisibility,
        isOpen = isOpen,
        isAbstract = isAbstract,
        isInterface = isInterface,
    ),
) {
    init {
        require(physicalTypePath.isNotEmpty() && physicalTypePath.all(String::isNotEmpty) &&
                physicalTypePath.last() == className) {
            "a TypeDef header requires an exact physical path ending in its declared name"
        }
        require(flags.visibility.isNested == (physicalTypePath.size > 1 || isNested)) {
            "a TypeDef visibility and physical nesting path must agree"
        }
    }

    fun generate(builder: StringBuilder) {
        // All flag spellings (including their order, with and without beforefieldinit) are
        // ilasm-probe-verified (the nested one by objprobe_s6, the non-sealed open-class one by
        // inheritprobe_s1, the abstract-class one by abstractprobe_s1, the interface one by
        // ifaceprobe_s1); the static-holder one is additionally frozen by the goldens.
        // A reified CLR TypeDef (for example an explicitly mapped host capability) appends its formal
        // type-parameter list right after the arity-suffixed name. Kotlin-owned ordinary generic
        // classes pass no list here: their one physical owner is declaration-erased. A genuinely
        // generic CLR base reference still carries its complete instantiation token.
        builder.appendLine(
            ".class ${flags.ilKeywords} ${physicalTypePath.last().toIlIdentifier()}${genericParameters.orEmpty()}"
        )
        if (!flags.isInterface) {
            builder.appendLine("       extends ${baseClassRef ?: "${coreLibraryReference}System.Object"}")
        }
        if (interfaceRefs.isNotEmpty()) {
            builder.appendLine("       implements ${interfaceRefs.joinToString(", ")}")
        }
        builder.appendLine("{")
        for (attribute in renderedAttributes) {
            builder.appendLine("  $attribute")
        }
        for (nestedClass in renderedNestedClasses) {
            builder.append(nestedClass)
        }
        for (field in renderedFields) {
            for (line in field.lineSequence()) {
                builder.appendLine("  $line")
            }
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
