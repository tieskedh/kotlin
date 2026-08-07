/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.dotnet.ir

/** The four unsigned 16-bit version columns of one ECMA-335 AssemblyRef row. */
data class CliAssemblyVersion(
    val major: Int,
    val minor: Int,
    val build: Int,
    val revision: Int,
) {
    init {
        for ([componentName, component] in listOf(
            "major" to major,
            "minor" to minor,
            "build" to build,
            "revision" to revision,
        )) {
            require(component in COMPONENT_RANGE) {
                "CLI assembly-version $componentName component must be in $COMPONENT_RANGE, but was $component"
            }
        }
    }

    companion object {
        private val COMPONENT_RANGE = 0..0xffff

        /** Accepts the four-part dotted form used by CLR assembly identity metadata. */
        fun parse(value: String): CliAssemblyVersion {
            require(value.isNotEmpty()) { "CLI assembly version must not be empty" }
            val components = value.split('.')
            require(components.size == 4) {
                "CLI assembly version must contain four components: '$value'"
            }
            val numbers = components.map { component ->
                component.toIntOrNull()
                    ?: throw IllegalArgumentException("CLI assembly-version component is not an integer: '$component'")
            }
            return CliAssemblyVersion(numbers[0], numbers[1], numbers[2], numbers[3])
        }
    }
}

/** The exact eight-byte public-key token carried by an ILAsm `.publickeytoken` clause. */
class CliPublicKeyToken(bytes: List<Int>) {
    val bytes: List<Int> = bytes.toList()

    init {
        require(this.bytes.size == TOKEN_SIZE) {
            "CLI public-key token must contain exactly $TOKEN_SIZE bytes, but contained ${this.bytes.size}"
        }
        this.bytes.forEachIndexed { index, byte ->
            require(byte in 0..0xff) {
                "CLI public-key token byte $index must be in 0..255, but was $byte"
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is CliPublicKeyToken && bytes == other.bytes

    override fun hashCode(): Int = bytes.hashCode()

    override fun toString(): String = bytes.joinToString("") { byte -> byte.toString(16).padStart(2, '0') }

    private companion object {
        const val TOKEN_SIZE = 8
    }
}

/**
 * Serializer-independent physical identity of one ECMA-335 AssemblyRef.
 *
 * Selection, target-profile policy, and Kotlin library meaning belong to the producer. This node
 * owns only the physical name, version, and optional public-key token that a CLI serializer needs.
 */
data class CliAssemblyReference(
    val name: String,
    val version: CliAssemblyVersion? = null,
    val publicKeyToken: CliPublicKeyToken? = null,
) {
    init {
        require(name.isNotEmpty()) { "CLI assembly-reference name must not be empty" }
        require(name.none(Char::isISOControl)) {
            "CLI assembly-reference name must not contain control characters"
        }
    }
}
