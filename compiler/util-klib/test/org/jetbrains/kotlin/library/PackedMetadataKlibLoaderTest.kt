/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.library

import org.jetbrains.kotlin.library.components.ir
import org.jetbrains.kotlin.library.components.inlinableFunctionsIr
import org.jetbrains.kotlin.library.components.metadata
import org.jetbrains.kotlin.library.impl.IrArrayWriter
import org.jetbrains.kotlin.library.loader.loadPackedKlib
import org.jetbrains.kotlin.library.loader.loadPackedMetadataKlib
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackedMetadataKlibLoaderTest {
    @field:TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `loads metadata while retaining the physical container path`() {
        val libraryPath = physicalContainer("Sample.Library.dll")
        val library = loadPackedMetadataKlib(
            libraryPath,
            packedKlib(
                "default/manifest" to "unique_name=Sample.Library\n".toByteArray(),
                "default/linkdata/module" to byteArrayOf(1, 2, 3),
                "default/linkdata/root_package/root.knm" to byteArrayOf(4),
                "default/linkdata/package_sample/first.knm" to byteArrayOf(5, 6),
                "default/linkdata/package_sample/second.knm" to byteArrayOf(7),
                "default/unknown/future-component.bin" to byteArrayOf(8),
                *packedIrComponent("default/ir/", 10),
            ),
        )

        assertEquals(libraryPath, library.path)
        assertEquals(libraryPath.toRealPath(), library.canonicalPath)
        assertEquals("Sample.Library", library.uniqueName)
        assertArrayEquals(byteArrayOf(1, 2, 3), library.metadata.moduleHeaderData)
        assertEquals(setOf("root"), library.metadata.getPackageFragmentNames(""))
        assertEquals(setOf("first", "second"), library.metadata.getPackageFragmentNames("sample"))
        assertArrayEquals(byteArrayOf(5, 6), library.metadata.getPackageFragment("sample", "first"))
        assertNull(library.ir)
    }

    @Test
    fun `accepts a central directory whose order differs from the local entries`() {
        val library = loadPackedMetadataKlib(
            physicalContainer("Reordered.dll"),
            packedKlib(
                "default/manifest" to "unique_name=Reordered\n".toByteArray(),
                "default/linkdata/module" to byteArrayOf(1),
                "default/linkdata/root_package/root.knm" to byteArrayOf(2),
            ).reversingCentralDirectoryEntries(),
        )

        assertEquals("Reordered", library.uniqueName)
        assertArrayEquals(byteArrayOf(2), library.metadata.getPackageFragment("", "root"))
    }

    @Test
    fun `component-complete loader exposes main and prepared IR`() {
        val mainIr = packedIrComponent("default/ir/", 10)
        val preparedIr = packedIrComponent("default/ir_inlinable_functions/", 20)
        val library = loadPackedKlib(
            physicalContainer("Inline.Library.dll"),
            packedKlib(
                "default/manifest" to "unique_name=Inline.Library\n".toByteArray(),
                "default/linkdata/module" to byteArrayOf(1),
                *mainIr,
                *preparedIr,
            ),
        )

        assertIrComponent(library.ir!!, 10)
        assertIrComponent(library.inlinableFunctionsIr!!, 20)
    }

    @Test
    fun `component-complete loader rejects a partial IR component`() {
        val exception = assertThrows<IllegalArgumentException> {
            loadPackedKlib(
                Paths.get("Partial.dll"),
                packedKlib(
                    "default/manifest" to "unique_name=Partial\n".toByteArray(),
                    "default/linkdata/module" to byteArrayOf(),
                    "default/ir/files.knf" to irArray(byteArrayOf(1)),
                ),
            )
        }

        assertEquals(
            "Invalid packed KLIB 'Partial.dll': IR component 'default/ir/' is incomplete; " +
                    "missing bodies.knb, irDeclarations.knd, signatures.knt, strings.knt, types.knt",
            exception.message,
        )
    }

    @Test
    fun `rejects duplicate entries`() {
        val exception = assertThrows<IllegalArgumentException> {
            loadPackedMetadataKlib(
                Paths.get("Duplicate.dll"),
                packedKlib(
                    "default/manifest" to "unique_name=Duplicate\n".toByteArray(),
                    "default/manifesX" to "unique_name=Other\n".toByteArray(),
                    "default/linkdata/module" to byteArrayOf(),
                ).replacingAscii("default/manifesX", "default/manifest"),
            )
        }

        assertEquals(
            "Invalid packed metadata KLIB 'Duplicate.dll': archive contains duplicate entry 'default/manifest'",
            exception.message,
        )
    }

    @Test
    fun `rejects non-canonical entries`() {
        val exception = assertThrows<IllegalArgumentException> {
            loadPackedMetadataKlib(
                Paths.get("Traversal.dll"),
                packedKlib(
                    "default/manifest" to "unique_name=Traversal\n".toByteArray(),
                    "default/linkdata/module" to byteArrayOf(),
                    "default/linkdata/../outside.knm" to byteArrayOf(),
                ),
            )
        }

        assertEquals(
            "Invalid packed metadata KLIB 'Traversal.dll': " +
                    "archive contains non-canonical entry 'default/linkdata/../outside.knm'",
            exception.message,
        )
    }

    @Test
    fun `requires the complete metadata component`() {
        val exception = assertThrows<IllegalArgumentException> {
            loadPackedMetadataKlib(
                Paths.get("Incomplete.dll"),
                packedKlib("default/manifest" to "unique_name=Incomplete\n".toByteArray()),
            )
        }

        assertEquals(
            "Invalid packed metadata KLIB 'Incomplete.dll': archive has no 'default/linkdata/module'",
            exception.message,
        )
    }

    @Test
    fun `rejects a truncated central directory`() {
        val packedKlib = packedKlib(
            "default/manifest" to "unique_name=Truncated\n".toByteArray(),
            "default/linkdata/module" to byteArrayOf(),
        )
        val exception = assertThrows<IllegalArgumentException> {
            loadPackedMetadataKlib(
                Paths.get("Truncated.dll"),
                packedKlib.copyOf(packedKlib.size - 22),
            )
        }

        assertEquals(
            "Invalid packed metadata KLIB 'Truncated.dll': " +
                    "archive has no valid end-of-central-directory record",
            exception.message,
        )
    }

    private fun packedKlib(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { archive ->
                for (entry in entries) {
                    val name = entry.first
                    val content = entry.second
                    archive.putNextEntry(ZipEntry(name))
                    archive.write(content)
                    archive.closeEntry()
                }
            }
            bytes.toByteArray()
        }

    private fun physicalContainer(name: String): Path =
        Files.createFile(tempDirectory.resolve(name))

    private fun packedIrComponent(directory: String, seed: Int): Array<Pair<String, ByteArray>> {
        val fileData = byteArrayOf(seed.toByte())
        val fileEntry = byteArrayOf((seed + 1).toByte())
        val declaration = byteArrayOf((seed + 2).toByte())
        val body = byteArrayOf((seed + 3).toByte())
        val type = byteArrayOf((seed + 4).toByte())
        val signature = byteArrayOf((seed + 5).toByte())
        val debugInfo = byteArrayOf((seed + 6).toByte())
        val stringLiteral = byteArrayOf((seed + 7).toByte())
        return arrayOf(
            "${directory}files.knf" to irArray(fileData),
            "${directory}fileEntries.knf" to irArray(irArray(fileEntry)),
            "${directory}irDeclarations.knd" to irArray(declaration),
            "${directory}bodies.knb" to irArray(irArray(body)),
            "${directory}types.knt" to irArray(irArray(type)),
            "${directory}signatures.knt" to irArray(irArray(signature)),
            "${directory}debugInfo.knd" to irArray(irArray(debugInfo)),
            "${directory}strings.knt" to irArray(irArray(stringLiteral)),
        )
    }

    private fun assertIrComponent(component: org.jetbrains.kotlin.library.components.KlibIrComponent, seed: Int) {
        assertEquals(1, component.irFileCount)
        assertArrayEquals(byteArrayOf(seed.toByte()), component.irFile(0))
        assertArrayEquals(byteArrayOf((seed + 1).toByte()), component.irFileEntry(0, 0))
        assertArrayEquals(byteArrayOf((seed + 2).toByte()), component.declarations(0))
        assertArrayEquals(byteArrayOf((seed + 3).toByte()), component.body(0, 0))
        assertArrayEquals(byteArrayOf((seed + 4).toByte()), component.type(0, 0))
        assertArrayEquals(byteArrayOf((seed + 5).toByte()), component.signature(0, 0))
        assertArrayEquals(byteArrayOf((seed + 6).toByte()), component.signatureDebugInfo(0, 0))
        assertArrayEquals(byteArrayOf((seed + 7).toByte()), component.stringLiteral(0, 0))
    }

    private fun irArray(vararg entries: ByteArray): ByteArray =
        IrArrayWriter(entries.toList(), useVarInt = false).writeIntoMemory()

    private fun ByteArray.replacingAscii(original: String, replacement: String): ByteArray {
        val originalBytes = original.toByteArray(Charsets.US_ASCII)
        val replacementBytes = replacement.toByteArray(Charsets.US_ASCII)
        require(originalBytes.size == replacementBytes.size)
        val result = copyOf()
        var offset = 0
        var replacements = 0
        while (offset <= result.size - originalBytes.size) {
            if (originalBytes.indices.all { index -> result[offset + index] == originalBytes[index] }) {
                replacementBytes.copyInto(result, offset)
                replacements++
                offset += originalBytes.size
            } else {
                offset++
            }
        }
        require(replacements == 2) { "Expected local and central ZIP entry names, found $replacements" }
        return result
    }

    private fun ByteArray.reversingCentralDirectoryEntries(): ByteArray {
        val endOffset = size - 22
        require(readU4(endOffset) == 0x06054b50L)
        val centralOffset = readU4(endOffset + 16).toInt()
        val centralEnd = centralOffset + readU4(endOffset + 12).toInt()
        val entries = mutableListOf<ByteArray>()
        var offset = centralOffset
        while (offset < centralEnd) {
            require(readU4(offset) == 0x02014b50L)
            val entrySize = 46 + readU2(offset + 28) + readU2(offset + 30) + readU2(offset + 32)
            entries += copyOfRange(offset, offset + entrySize)
            offset += entrySize
        }
        require(offset == centralEnd)

        val result = copyOf()
        offset = centralOffset
        for (entry in entries.asReversed()) {
            entry.copyInto(result, offset)
            offset += entry.size
        }
        return result
    }

    private fun ByteArray.readU2(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.readU4(offset: Int): Long =
        (0 until 4).fold(0L) { value, index ->
            value or ((this[offset + index].toLong() and 0xff) shl (index * 8))
        }
}
