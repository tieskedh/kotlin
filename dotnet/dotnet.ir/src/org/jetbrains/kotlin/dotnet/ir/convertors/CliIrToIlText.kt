/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.dotnet.ir.convertors

import org.jetbrains.kotlin.dotnet.ir.CliAssemblyReference
import org.jetbrains.kotlin.dotnet.ir.CliAssemblyVersion

/** Deterministic ILAsm text serialization for the physical CLI forms admitted so far. */
object CliIrToIlText {
    fun render(reference: CliAssemblyReference): String = buildString {
        append(".assembly extern ")
        appendIdentifier(reference.name)
        if (reference.version == null && reference.publicKeyToken == null) {
            appendLine(" {}")
            return@buildString
        }
        appendLine()
        appendLine("{")
        reference.version?.let { version -> appendLine("  .ver ${version.render()}") }
        reference.publicKeyToken?.let { token ->
            append("  .publickeytoken = (")
            append(token.bytes.joinToString(" ") { byte -> byte.toString(16).uppercase().padStart(2, '0') })
            appendLine(")")
        }
        appendLine("}")
    }

    private fun StringBuilder.appendIdentifier(value: String) {
        append('\'')
        for (character in value) {
            when (character) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                else -> append(character)
            }
        }
        append('\'')
    }

    private fun CliAssemblyVersion.render(): String = "$major:$minor:$build:$revision"
}
