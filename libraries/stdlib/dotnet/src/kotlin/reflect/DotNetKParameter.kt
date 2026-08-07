/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.reflect

import kotlin.internal.UsedFromCompilerGeneratedCode

/**
 * Represents one parameter exposed by a function, constructor, or property reference, including
 * the instance and extension-receiver positions which are not ordinary source value parameters.
 */
public interface KParameter : KAnnotatedElement {
    /** Zero-based position in the containing callable's exposed [KCallable.parameters] list. */
    public val index: Int

    /** Source name, or `null` for receivers and declarations whose name is unavailable. */
    public val name: String?

    /** Logical Kotlin type; a vararg exposes its array type rather than its element type. */
    public val type: KType

    /** The semantic role of this parameter. */
    public val kind: Kind

    /**
     * `true` when Kotlin default-call semantics allow this value parameter to be omitted.
     * A corresponding default on an overridden function also makes the parameter optional.
     */
    public val isOptional: Boolean

    /** `true` when this is a Kotlin vararg parameter. */
    @SinceKotlin("1.1")
    public val isVararg: Boolean

    public enum class Kind {
        /** Member instance, or the outer instance required by an inner-class constructor. */
        INSTANCE,

        /** Context parameter. Context parameters remain a separately admitted language feature. */
        @ExperimentalContextParameters
        CONTEXT,

        /** Extension receiver of an extension function or property. */
        EXTENSION_RECEIVER,

        /** Ordinary named value parameter. */
        VALUE,
    }
}

private class DotNetKParameter(
    private val owner: KCallable<*>,
    override val index: Int,
    override val name: String?,
    override val type: KType,
    override val kind: KParameter.Kind,
    override val isOptional: Boolean,
    override val isVararg: Boolean,
    override val annotations: List<Annotation>,
) : KParameter {
    override fun equals(other: Any?): Boolean =
        other is DotNetKParameter && owner == other.owner && index == other.index

    override fun hashCode(): Int = owner.hashCode() * 31 + index

    @OptIn(ExperimentalContextParameters::class)
    override fun toString(): String = when (kind) {
        KParameter.Kind.INSTANCE -> "instance parameter of $owner"
        KParameter.Kind.CONTEXT -> "context parameter $name of $owner"
        KParameter.Kind.EXTENSION_RECEIVER -> "extension receiver parameter of $owner"
        KParameter.Kind.VALUE -> "parameter #$index $name of $owner"
    }
}

/**
 * Returns the erased factory passed into Kotlin.Runtime's callable bases. Runtime invokes it once
 * with the actual callable object, so parameter equality never needs a copied callable key.
 */
@PublishedApi
@UsedFromCompilerGeneratedCode
internal fun dotNetKParameterFactory(): (KCallable<*>, Array<Any?>) -> List<KParameter> =
    ::dotNetCreateKParameters

@OptIn(ExperimentalContextParameters::class)
@Suppress("UNCHECKED_CAST")
private fun dotNetCreateKParameters(
    owner: KCallable<*>,
    signature: Array<Any?>,
): List<KParameter> {
    val descriptors = signature[2] as Array<*>
    return Array<KParameter>(descriptors.size) { index ->
        val descriptor = descriptors[index] as Array<*>
        DotNetKParameter(
            owner = owner,
            index = index,
            name = descriptor[0] as String?,
            type = descriptor[1] as KType,
            kind = when (descriptor[2] as Int) {
                0 -> KParameter.Kind.INSTANCE
                1 -> KParameter.Kind.CONTEXT
                2 -> KParameter.Kind.EXTENSION_RECEIVER
                else -> KParameter.Kind.VALUE
            },
            isOptional = descriptor[3] as Boolean,
            isVararg = descriptor[4] as Boolean,
            annotations = descriptor[5] as List<Annotation>,
        )
    }.asList()
}
