/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.dotnet.ir

import org.jetbrains.kotlin.dotnet.ir.convertors.CliIrToIlText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CliAssemblyReferenceTest {
    @Test
    fun rendersMinimalReferenceDeterministically() {
        assertEquals(
            ".assembly extern 'Library' {}\n",
            CliIrToIlText.render(CliAssemblyReference("Library")),
        )
    }

    @Test
    fun rendersVersionTokenAndEscapedIdentifier() {
        val reference = CliAssemblyReference(
            name = "Owner'\\Library",
            version = CliAssemblyVersion(1, 2, 3, 4),
            publicKeyToken = CliPublicKeyToken(listOf(0x00, 0x0a, 0x10, 0x7f, 0x80, 0xab, 0xfe, 0xff)),
        )

        assertEquals(
            """
                .assembly extern 'Owner\'\\Library'
                {
                  .ver 1:2:3:4
                  .publickeytoken = (00 0A 10 7F 80 AB FE FF)
                }
            """.trimIndent() + "\n",
            CliIrToIlText.render(reference),
        )
    }

    @Test
    fun parsesMetadataVersions() {
        val expected = CliAssemblyVersion(1, 20, 300, 4000)
        assertEquals(expected, CliAssemblyVersion.parse("1.20.300.4000"))
        assertEquals(
            CliAssemblyVersion(0, 0xffff, 0, 0xffff),
            CliAssemblyVersion.parse("0.65535.0.65535"),
        )
    }

    @Test
    fun rejectsMalformedVersions() {
        for (value in listOf(
            "",
            "1",
            "1.2.3",
            "1.2.3.4.5",
            "1:2:3:4",
            "1.two.3.4",
            "1.-1.3.4",
            "1.65536.3.4",
        )) {
            assertThrows(IllegalArgumentException::class.java) { CliAssemblyVersion.parse(value) }
        }
    }

    @Test
    fun tokenIsValidatedAndDefensivelyCopied() {
        val mutableBytes = mutableListOf(0, 1, 2, 3, 4, 5, 6, 7)
        val token = CliPublicKeyToken(mutableBytes)
        mutableBytes[0] = 255

        assertEquals(CliPublicKeyToken(listOf(0, 1, 2, 3, 4, 5, 6, 7)), token)
        assertNotEquals(CliPublicKeyToken(listOf(255, 1, 2, 3, 4, 5, 6, 7)), token)
        assertThrows(IllegalArgumentException::class.java) { CliPublicKeyToken(List(7) { 0 }) }
        assertThrows(IllegalArgumentException::class.java) { CliPublicKeyToken(List(9) { 0 }) }
        assertThrows(IllegalArgumentException::class.java) { CliPublicKeyToken(listOf(0, 1, 2, 3, 4, 5, 6, 256)) }
    }

    @Test
    fun rejectsInvalidAssemblyNames() {
        assertThrows(IllegalArgumentException::class.java) { CliAssemblyReference("") }
        assertThrows(IllegalArgumentException::class.java) { CliAssemblyReference("bad\nname") }
        assertThrows(IllegalArgumentException::class.java) { CliAssemblyReference("bad\u0000name") }
    }
}
