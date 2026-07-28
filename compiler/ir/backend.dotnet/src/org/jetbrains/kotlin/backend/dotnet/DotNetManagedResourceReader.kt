package org.jetbrains.kotlin.backend.dotnet

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.math.max

data class DotNetManagedResource(
    val assemblyIdentity: DotNetManagedAssemblyIdentity,
    val name: String,
    val attributes: Int,
    val content: ByteArray,
) {
    val isPublic: Boolean
        get() = attributes and VISIBILITY_MASK == PUBLIC_ATTRIBUTE

    val isPrivate: Boolean
        get() = attributes and VISIBILITY_MASK == PRIVATE_ATTRIBUTE

    private companion object {
        const val VISIBILITY_MASK = 0x7
        const val PUBLIC_ATTRIBUTE = 0x1
        const val PRIVATE_ATTRIBUTE = 0x2
    }
}

data class DotNetManagedAssemblyIdentity(
    val name: String,
    val version: String,
    val culture: String,
    val hasPublicKey: Boolean,
)

class DotNetBadImageFormatException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Reads one embedded ECMA-335 ManifestResource directly from a PE image.
 *
 * The compiler is JVM-hosted, so library loading must not depend on a .NET sidecar. This reader
 * deliberately implements only the PE/CLI metadata needed to locate ManifestResource rows. Every
 * file offset, RVA, stream, table, heap index, row count, and resource length is checked before it
 * is used.
 */
object DotNetManagedResourceReader {
    fun read(file: File, resourceName: String): DotNetManagedResource? {
        require(resourceName.isNotEmpty()) { "Managed-resource name must not be empty" }
        try {
            RandomAccessFile(file, "r").use { input ->
                return PeImage(input, file.path).readManagedResource(resourceName)
            }
        } catch (exception: DotNetBadImageFormatException) {
            throw exception
        } catch (exception: IOException) {
            throw DotNetBadImageFormatException(
                "Could not read managed assembly '${file.path}': ${exception.message}",
                exception,
            )
        }
    }

    private class PeImage(
        private val input: RandomAccessFile,
        private val displayPath: String,
    ) {
        private val fileSize = input.length()

        fun readManagedResource(resourceName: String): DotNetManagedResource? {
            checkRange(0, DOS_HEADER_SIZE, "DOS header")
            if (readU2(0) != DOS_SIGNATURE) malformed("missing DOS signature")
            val peOffset = readU4(DOS_PE_POINTER_OFFSET)
            checkRange(peOffset, PE_SIGNATURE_SIZE + COFF_HEADER_SIZE, "PE and COFF headers")
            if (readU4(peOffset) != PE_SIGNATURE) malformed("missing PE signature")

            val coffOffset = peOffset + PE_SIGNATURE_SIZE
            val sectionCount = readU2(coffOffset + COFF_SECTION_COUNT_OFFSET)
            if (sectionCount !in 1..MAX_SECTION_COUNT) {
                malformed("invalid PE section count $sectionCount")
            }
            val optionalHeaderSize = readU2(coffOffset + COFF_OPTIONAL_HEADER_SIZE_OFFSET)
            val optionalHeaderOffset = coffOffset + COFF_HEADER_SIZE
            checkRange(optionalHeaderOffset, optionalHeaderSize.toLong(), "PE optional header")
            val optionalMagic = readU2(optionalHeaderOffset)
            val dataDirectoryOffset = when (optionalMagic) {
                PE32_MAGIC -> PE32_DATA_DIRECTORIES_OFFSET
                PE32_PLUS_MAGIC -> PE32_PLUS_DATA_DIRECTORIES_OFFSET
                else -> malformed("unsupported PE optional-header magic 0x${optionalMagic.toString(16)}")
            }
            val directoryCountOffset = dataDirectoryOffset - UINT32_SIZE
            if (optionalHeaderSize < dataDirectoryOffset + DATA_DIRECTORY_SIZE * (CLI_DIRECTORY_INDEX + 1)) {
                malformed("PE optional header has no CLI data directory")
            }
            val directoryCount = readU4(optionalHeaderOffset + directoryCountOffset)
            if (directoryCount <= CLI_DIRECTORY_INDEX) malformed("PE image has no CLI data directory")
            val cliDirectoryOffset =
                optionalHeaderOffset + dataDirectoryOffset + DATA_DIRECTORY_SIZE * CLI_DIRECTORY_INDEX
            val cliRva = readU4(cliDirectoryOffset)
            val cliSize = readU4(cliDirectoryOffset + UINT32_SIZE)
            if (cliRva == 0L || cliSize < CLI_HEADER_MINIMUM_SIZE) {
                malformed("PE image has no valid CLI header")
            }

            val sectionHeadersOffset = optionalHeaderOffset + optionalHeaderSize
            checkRange(
                sectionHeadersOffset,
                sectionCount.toLong() * SECTION_HEADER_SIZE,
                "PE section headers",
            )
            val sections = List(sectionCount) { index ->
                val offset = sectionHeadersOffset + index.toLong() * SECTION_HEADER_SIZE
                Section(
                    virtualSize = readU4(offset + SECTION_VIRTUAL_SIZE_OFFSET),
                    virtualAddress = readU4(offset + SECTION_VIRTUAL_ADDRESS_OFFSET),
                    rawSize = readU4(offset + SECTION_RAW_SIZE_OFFSET),
                    rawOffset = readU4(offset + SECTION_RAW_OFFSET_OFFSET),
                )
            }

            val cliOffset = rvaToFileOffset(cliRva, CLI_HEADER_MINIMUM_SIZE, sections, "CLI header")
            val metadataRva = readU4(cliOffset + CLI_METADATA_DIRECTORY_OFFSET)
            val metadataSize = readU4(cliOffset + CLI_METADATA_DIRECTORY_OFFSET + UINT32_SIZE)
            val resourcesRva = readU4(cliOffset + CLI_RESOURCES_DIRECTORY_OFFSET)
            val resourcesSize = readU4(cliOffset + CLI_RESOURCES_DIRECTORY_OFFSET + UINT32_SIZE)
            if (metadataRva == 0L || metadataSize < METADATA_ROOT_MINIMUM_SIZE) {
                malformed("CLI header has no valid metadata directory")
            }
            val metadataOffset =
                rvaToFileOffset(metadataRva, metadataSize, sections, "CLI metadata directory")
            val streams = readMetadataStreams(metadataOffset, metadataSize)
            val tables = streams["#~"] ?: streams["#-"]
                ?: malformed("CLI metadata has no tables stream")
            val strings = streams["#Strings"]
                ?: malformed("CLI metadata has no strings heap")
            val blobs = streams["#Blob"]
            val assemblyIdentity = readAssemblyIdentity(tables, strings, blobs)
            val resourceRow = findManifestResource(tables, strings, resourceName) ?: return null
            if (resourceRow.implementation != 0L) {
                malformed("managed resource '$resourceName' is linked instead of embedded")
            }
            if (resourcesRva == 0L || resourcesSize < UINT32_SIZE) {
                malformed("CLI header has no managed-resource directory")
            }
            if (resourceRow.offset > resourcesSize - UINT32_SIZE) {
                malformed("managed resource '$resourceName' has an invalid directory offset")
            }
            val lengthRva = checkedAdd(resourcesRva, resourceRow.offset, "managed-resource RVA")
            val lengthOffset =
                rvaToFileOffset(lengthRva, UINT32_SIZE, sections, "managed resource '$resourceName'")
            val contentSize = readU4(lengthOffset)
            if (contentSize > MAX_MANAGED_RESOURCE_SIZE) {
                malformed(
                    "managed resource '$resourceName' is too large " +
                            "($contentSize bytes; limit is $MAX_MANAGED_RESOURCE_SIZE)",
                )
            }
            val resourceEnd = checkedAdd(resourceRow.offset, UINT32_SIZE + contentSize, "managed-resource size")
            if (resourceEnd > resourcesSize) {
                malformed("managed resource '$resourceName' extends beyond the CLI resource directory")
            }
            val contentOffset = rvaToFileOffset(
                checkedAdd(lengthRva, UINT32_SIZE, "managed-resource content RVA"),
                contentSize,
                sections,
                "managed resource '$resourceName' content",
            )
            return DotNetManagedResource(
                assemblyIdentity = assemblyIdentity,
                name = resourceName,
                attributes = resourceRow.attributes,
                content = readBytes(contentOffset, contentSize.toInt()),
            )
        }

        private fun readMetadataStreams(
            metadataOffset: Long,
            metadataSize: Long,
        ): Map<String, MetadataStream> {
            if (readU4(metadataOffset) != METADATA_SIGNATURE) {
                malformed("CLI metadata has an invalid signature")
            }
            val versionLength = readU4(metadataOffset + METADATA_VERSION_LENGTH_OFFSET)
            val versionEnd = checkedAdd(
                METADATA_VERSION_STRING_OFFSET,
                versionLength,
                "metadata version string",
            )
            if (versionEnd > metadataSize) malformed("CLI metadata version string is truncated")
            var position = metadataOffset + align4(versionEnd)
            checkMetadataRange(metadataOffset, metadataSize, position, UINT16_SIZE * 2, "metadata stream count")
            position += UINT16_SIZE
            val streamCount = readU2(position)
            position += UINT16_SIZE
            if (streamCount !in 1..MAX_STREAM_COUNT) {
                malformed("invalid CLI metadata stream count $streamCount")
            }

            val streams = linkedMapOf<String, MetadataStream>()
            repeat(streamCount) {
                checkMetadataRange(
                    metadataOffset,
                    metadataSize,
                    position,
                    UINT32_SIZE * 2 + 1,
                    "metadata stream header",
                )
                val streamOffset = readU4(position)
                val streamSize = readU4(position + UINT32_SIZE)
                val nameOffset = position + UINT32_SIZE * 2
                var nameLength = 0
                while (nameLength < MAX_STREAM_NAME_BYTES) {
                    checkMetadataRange(
                        metadataOffset,
                        metadataSize,
                        nameOffset + nameLength,
                        1,
                        "metadata stream name",
                    )
                    if (readU1(nameOffset + nameLength) == 0) break
                    nameLength++
                }
                if (nameLength == MAX_STREAM_NAME_BYTES) {
                    malformed("CLI metadata stream name is not terminated")
                }
                val name = readBytes(nameOffset, nameLength).toString(Charsets.US_ASCII)
                if (name.isEmpty()) malformed("CLI metadata contains an empty stream name")
                val headerSize = align4(UINT32_SIZE * 2 + nameLength + 1L)
                checkMetadataRange(
                    metadataOffset,
                    metadataSize,
                    position,
                    headerSize,
                    "metadata stream header '$name'",
                )
                val absoluteOffset = checkedAdd(metadataOffset, streamOffset, "metadata stream '$name'")
                checkMetadataRange(
                    metadataOffset,
                    metadataSize,
                    absoluteOffset,
                    streamSize,
                    "metadata stream '$name'",
                )
                if (streams.put(name, MetadataStream(absoluteOffset, streamSize)) != null) {
                    malformed("CLI metadata contains duplicate stream '$name'")
                }
                position += headerSize
            }
            return streams
        }

        private fun findManifestResource(
            tables: MetadataStream,
            strings: MetadataStream,
            resourceName: String,
        ): ManifestResourceRow? {
            val table = locateMetadataTable(tables, MANIFEST_RESOURCE_TABLE) ?: return null
            var match: ManifestResourceRow? = null
            repeat(table.rowCount.toIntChecked("ManifestResource row count")) { rowIndex ->
                var rowOffset = table.offset + rowIndex.toLong() * table.rowSize
                val resourceOffset = readU4(rowOffset)
                rowOffset += UINT32_SIZE
                val attributes = readU4(rowOffset).toInt()
                rowOffset += UINT32_SIZE
                val nameIndex = readIndex(rowOffset, table.indexSizes.stringIndexSize)
                rowOffset += table.indexSizes.stringIndexSize
                val implementation = readIndex(rowOffset, table.indexSizes.implementationIndexSize)
                val name = readStringHeap(strings, nameIndex)
                if (name == resourceName) {
                    if (match != null) malformed("CLI metadata contains duplicate managed resource '$resourceName'")
                    match = ManifestResourceRow(resourceOffset, attributes, implementation)
                }
            }
            return match
        }

        private fun readAssemblyIdentity(
            tables: MetadataStream,
            strings: MetadataStream,
            blobs: MetadataStream?,
        ): DotNetManagedAssemblyIdentity {
            val table = locateMetadataTable(tables, ASSEMBLY_TABLE)
                ?: malformed("CLI metadata has no Assembly table")
            if (table.rowCount != 1L) malformed("CLI metadata has ${table.rowCount} Assembly rows")
            var position = table.offset
            position += UINT32_SIZE
            val major = readU2(position)
            position += UINT16_SIZE
            val minor = readU2(position)
            position += UINT16_SIZE
            val build = readU2(position)
            position += UINT16_SIZE
            val revision = readU2(position)
            position += UINT16_SIZE
            val flags = readU4(position)
            position += UINT32_SIZE
            val publicKeyIndex = readIndex(position, table.indexSizes.blobIndexSize)
            position += table.indexSizes.blobIndexSize
            val nameIndex = readIndex(position, table.indexSizes.stringIndexSize)
            position += table.indexSizes.stringIndexSize
            val cultureIndex = readIndex(position, table.indexSizes.stringIndexSize)
            val publicKeySize = readBlobHeapSize(blobs, publicKeyIndex)
            val hasPublicKeyFlag = flags and ASSEMBLY_PUBLIC_KEY_FLAG != 0L
            if (hasPublicKeyFlag != (publicKeySize != 0L)) {
                malformed("Assembly public-key flag and blob do not agree")
            }
            val name = readStringHeap(strings, nameIndex)
            if (name.isEmpty()) malformed("Assembly name is empty")
            return DotNetManagedAssemblyIdentity(
                name = name,
                version = "$major.$minor.$build.$revision",
                culture = readStringHeap(strings, cultureIndex).ifEmpty { "neutral" },
                hasPublicKey = hasPublicKeyFlag,
            )
        }

        private fun locateMetadataTable(
            tables: MetadataStream,
            targetTable: Int,
        ): MetadataTableLocation? {
            checkStreamRange(tables, tables.offset, TABLES_HEADER_SIZE, "metadata tables header")
            val heapSizes = readU1(tables.offset + TABLES_HEAP_SIZES_OFFSET)
            val validMask = readU8(tables.offset + TABLES_VALID_MASK_OFFSET)
            val rowCounts = LongArray(METADATA_TABLE_COUNT)
            var position = tables.offset + TABLES_ROW_COUNTS_OFFSET
            for (table in 0 until METADATA_TABLE_COUNT) {
                if (validMask hasTable table) {
                    checkStreamRange(tables, position, UINT32_SIZE, "row count for metadata table $table")
                    rowCounts[table] = readU4(position)
                    position += UINT32_SIZE
                }
            }
            if (!(validMask hasTable targetTable)) return null

            val sizes = MetadataIndexSizes(rowCounts, heapSizes)
            for (table in 0 until targetTable) {
                if (!(validMask hasTable table)) continue
                val tableSize = checkedMultiply(
                    rowCounts[table],
                    sizes.rowSize(table).toLong(),
                    "metadata table $table",
                )
                position = checkedAdd(position, tableSize, "metadata table $table")
                checkStreamRange(tables, position, 0, "metadata table $table")
            }

            val rowSize = sizes.rowSize(targetTable)
            val tableSize = checkedMultiply(
                rowCounts[targetTable],
                rowSize.toLong(),
                "metadata table $targetTable",
            )
            checkStreamRange(tables, position, tableSize, "metadata table $targetTable")
            return MetadataTableLocation(
                offset = position,
                rowCount = rowCounts[targetTable],
                rowSize = rowSize,
                indexSizes = sizes,
            )
        }

        private fun readStringHeap(strings: MetadataStream, index: Long): String {
            if (index == 0L) return ""
            if (index >= strings.size) malformed("metadata string index $index is out of bounds")
            val offset = strings.offset + index
            var length = 0L
            while (index + length < strings.size && readU1(offset + length) != 0) {
                length++
            }
            if (index + length >= strings.size) malformed("metadata string at index $index is not terminated")
            if (length > Int.MAX_VALUE.toLong()) malformed("metadata string at index $index is too large")
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(readBytes(offset, length.toInt())))
                    .toString()
            } catch (exception: CharacterCodingException) {
                throw DotNetBadImageFormatException(
                    "Managed assembly '$displayPath' contains invalid UTF-8 metadata",
                    exception,
                )
            }
        }

        private fun readBlobHeapSize(blobs: MetadataStream?, index: Long): Long {
            if (index == 0L) return 0
            if (blobs == null) malformed("metadata blob index $index exists without a #Blob heap")
            if (index >= blobs.size) malformed("metadata blob index $index is out of bounds")
            val offset = blobs.offset + index
            checkStreamRange(blobs, offset, 1, "metadata blob length")
            val first = readU1(offset)
            val encodedLength = when {
                first and 0x80 == 0 -> 1 to first.toLong()
                first and 0xc0 == 0x80 -> {
                    checkStreamRange(blobs, offset, 2, "metadata blob length")
                    2 to (((first and 0x3f) shl 8) or readU1(offset + 1)).toLong()
                }
                first and 0xe0 == 0xc0 -> {
                    checkStreamRange(blobs, offset, 4, "metadata blob length")
                    4 to (
                            ((first and 0x1f).toLong() shl 24) or
                                    (readU1(offset + 1).toLong() shl 16) or
                                    (readU1(offset + 2).toLong() shl 8) or
                                    readU1(offset + 3).toLong()
                            )
                }
                else -> malformed("metadata blob at index $index has an invalid compressed length")
            }
            val headerSize = encodedLength.first
            val contentSize = encodedLength.second
            checkStreamRange(
                blobs,
                offset + headerSize,
                contentSize,
                "metadata blob at index $index",
            )
            return contentSize
        }

        private fun rvaToFileOffset(
            rva: Long,
            size: Long,
            sections: List<Section>,
            description: String,
        ): Long {
            val section = sections.firstOrNull { candidate ->
                val mappedSize = max(candidate.virtualSize, candidate.rawSize)
                rva >= candidate.virtualAddress &&
                        rva - candidate.virtualAddress <= mappedSize &&
                        size <= mappedSize - (rva - candidate.virtualAddress)
            } ?: malformed("$description is outside every PE section")
            val delta = rva - section.virtualAddress
            if (delta > section.rawSize || size > section.rawSize - delta) {
                malformed("$description is not backed by PE file data")
            }
            val offset = checkedAdd(section.rawOffset, delta, description)
            checkRange(offset, size, description)
            return offset
        }

        private fun readIndex(offset: Long, size: Int): Long = when (size) {
            UINT16_SIZE.toInt() -> readU2(offset).toLong()
            UINT32_SIZE.toInt() -> readU4(offset)
            else -> error("Unsupported metadata index size $size")
        }

        private fun readU1(offset: Long): Int {
            checkRange(offset, 1, "byte")
            input.seek(offset)
            return input.readUnsignedByte()
        }

        private fun readU2(offset: Long): Int {
            checkRange(offset, UINT16_SIZE, "16-bit value")
            input.seek(offset)
            return input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
        }

        private fun readU4(offset: Long): Long {
            checkRange(offset, UINT32_SIZE, "32-bit value")
            input.seek(offset)
            var value = 0L
            repeat(4) { index ->
                value = value or (input.readUnsignedByte().toLong() shl (index * 8))
            }
            return value
        }

        private fun readU8(offset: Long): Long {
            checkRange(offset, UINT64_SIZE, "64-bit value")
            input.seek(offset)
            var value = 0L
            repeat(8) { index ->
                value = value or (input.readUnsignedByte().toLong() shl (index * 8))
            }
            return value
        }

        private fun readBytes(offset: Long, size: Int): ByteArray {
            checkRange(offset, size.toLong(), "byte sequence")
            input.seek(offset)
            return ByteArray(size).also(input::readFully)
        }

        private fun checkMetadataRange(
            metadataOffset: Long,
            metadataSize: Long,
            offset: Long,
            size: Long,
            description: String,
        ) {
            if (offset < metadataOffset || size < 0 || offset - metadataOffset > metadataSize ||
                size > metadataSize - (offset - metadataOffset)
            ) {
                malformed("$description is outside the CLI metadata directory")
            }
            checkRange(offset, size, description)
        }

        private fun checkStreamRange(
            stream: MetadataStream,
            offset: Long,
            size: Long,
            description: String,
        ) {
            if (offset < stream.offset || size < 0 || offset - stream.offset > stream.size ||
                size > stream.size - (offset - stream.offset)
            ) {
                malformed("$description is outside its metadata stream")
            }
            checkRange(offset, size, description)
        }

        private fun checkRange(offset: Long, size: Long, description: String) {
            if (offset < 0 || size < 0 || offset > fileSize || size > fileSize - offset) {
                malformed("$description is outside the PE file")
            }
        }

        private fun checkedAdd(left: Long, right: Long, description: String): Long {
            if (left < 0 || right < 0 || left > Long.MAX_VALUE - right) {
                malformed("$description overflows")
            }
            return left + right
        }

        private fun checkedMultiply(left: Long, right: Long, description: String): Long {
            if (left < 0 || right < 0 || left != 0L && right > Long.MAX_VALUE / left) {
                malformed("$description size overflows")
            }
            return left * right
        }

        private fun Long.toIntChecked(description: String): Int {
            if (this > Int.MAX_VALUE.toLong()) malformed("$description is too large")
            return toInt()
        }

        private fun malformed(reason: String): Nothing =
            throw DotNetBadImageFormatException("Managed assembly '$displayPath' is invalid: $reason.")
    }

    private class MetadataIndexSizes(
        private val rowCounts: LongArray,
        heapSizes: Int,
    ) {
        val stringIndexSize = if (heapSizes and STRING_HEAP_LARGE != 0) 4 else 2
        private val guidIndexSize = if (heapSizes and GUID_HEAP_LARGE != 0) 4 else 2
        val blobIndexSize = if (heapSizes and BLOB_HEAP_LARGE != 0) 4 else 2
        val implementationIndexSize = codedIndexSize(2, FILE_TABLE, ASSEMBLY_REF_TABLE, EXPORTED_TYPE_TABLE)

        fun rowSize(table: Int): Int = when (table) {
            0 -> 2 + stringIndexSize + guidIndexSize * 3
            1 -> resolutionScopeIndexSize + stringIndexSize * 2
            2 -> 4 + stringIndexSize * 2 + typeDefOrRefIndexSize + tableIndexSize(4) + tableIndexSize(6)
            3 -> tableIndexSize(4)
            4 -> 2 + stringIndexSize + blobIndexSize
            5 -> tableIndexSize(6)
            6 -> 8 + stringIndexSize + blobIndexSize + tableIndexSize(8)
            7 -> tableIndexSize(8)
            8 -> 4 + stringIndexSize
            9 -> tableIndexSize(2) + typeDefOrRefIndexSize
            10 -> memberRefParentIndexSize + stringIndexSize + blobIndexSize
            11 -> 2 + hasConstantIndexSize + blobIndexSize
            12 -> hasCustomAttributeIndexSize + customAttributeTypeIndexSize + blobIndexSize
            13 -> hasFieldMarshalIndexSize + blobIndexSize
            14 -> 2 + hasDeclSecurityIndexSize + blobIndexSize
            15 -> 6 + tableIndexSize(2)
            16 -> 4 + tableIndexSize(4)
            17 -> blobIndexSize
            18 -> tableIndexSize(2) + tableIndexSize(20)
            19 -> tableIndexSize(20)
            20 -> 2 + stringIndexSize + typeDefOrRefIndexSize
            21 -> tableIndexSize(2) + tableIndexSize(23)
            22 -> tableIndexSize(23)
            23 -> 2 + stringIndexSize + blobIndexSize
            24 -> 2 + tableIndexSize(6) + hasSemanticsIndexSize
            25 -> tableIndexSize(2) + methodDefOrRefIndexSize * 2
            26 -> stringIndexSize
            27 -> blobIndexSize
            28 -> 2 + memberForwardedIndexSize + stringIndexSize + tableIndexSize(26)
            29 -> 4 + tableIndexSize(4)
            30 -> 8
            31 -> 4
            32 -> 16 + blobIndexSize + stringIndexSize * 2
            33 -> 4
            34 -> 12
            35 -> 12 + blobIndexSize * 2 + stringIndexSize * 2
            36 -> 4 + tableIndexSize(35)
            37 -> 12 + tableIndexSize(35)
            38 -> 4 + stringIndexSize + blobIndexSize
            39 -> 8 + stringIndexSize * 2 + implementationIndexSize
            MANIFEST_RESOURCE_TABLE -> 8 + stringIndexSize + implementationIndexSize
            else -> error("ManifestResource offset requires unsupported metadata table $table")
        }

        private val resolutionScopeIndexSize
            get() = codedIndexSize(2, 0, 26, 35, 1)
        private val typeDefOrRefIndexSize
            get() = codedIndexSize(2, 2, 1, 27)
        private val memberRefParentIndexSize
            get() = codedIndexSize(3, 2, 1, 26, 6, 27)
        private val hasConstantIndexSize
            get() = codedIndexSize(2, 4, 8, 23)
        private val hasCustomAttributeIndexSize
            get() = codedIndexSize(
                5,
                6, 4, 1, 2, 8, 9, 10, 0, 14, 23, 20,
                17, 26, 27, 32, 35, 38, 39, 40, 42, 44, 43,
            )
        private val customAttributeTypeIndexSize
            get() = codedIndexSize(3, 6, 10)
        private val hasFieldMarshalIndexSize
            get() = codedIndexSize(1, 4, 8)
        private val hasDeclSecurityIndexSize
            get() = codedIndexSize(2, 2, 6, 32)
        private val hasSemanticsIndexSize
            get() = codedIndexSize(1, 20, 23)
        private val methodDefOrRefIndexSize
            get() = codedIndexSize(1, 6, 10)
        private val memberForwardedIndexSize
            get() = codedIndexSize(1, 4, 6)

        private fun tableIndexSize(table: Int): Int =
            if (rowCounts[table] < UINT16_INDEX_LIMIT) 2 else 4

        private fun codedIndexSize(tagBits: Int, vararg tables: Int): Int {
            val rowLimit = 1L shl (16 - tagBits)
            return if (tables.maxOf { table -> rowCounts[table] } < rowLimit) 2 else 4
        }
    }

    private data class Section(
        val virtualSize: Long,
        val virtualAddress: Long,
        val rawSize: Long,
        val rawOffset: Long,
    )

    private data class MetadataStream(val offset: Long, val size: Long)

    private data class ManifestResourceRow(
        val offset: Long,
        val attributes: Int,
        val implementation: Long,
    )

    private data class MetadataTableLocation(
        val offset: Long,
        val rowCount: Long,
        val rowSize: Int,
        val indexSizes: MetadataIndexSizes,
    )

    private infix fun Long.hasTable(table: Int): Boolean =
        this ushr table and 1L != 0L

    private fun align4(value: Long): Long = (value + 3L) and 3L.inv()

    private const val DOS_HEADER_SIZE = 64L
    private const val DOS_SIGNATURE = 0x5a4d
    private const val DOS_PE_POINTER_OFFSET = 0x3cL
    private const val PE_SIGNATURE = 0x00004550L
    private const val PE_SIGNATURE_SIZE = 4L
    private const val COFF_HEADER_SIZE = 20L
    private const val COFF_SECTION_COUNT_OFFSET = 2L
    private const val COFF_OPTIONAL_HEADER_SIZE_OFFSET = 16L
    private const val PE32_MAGIC = 0x10b
    private const val PE32_PLUS_MAGIC = 0x20b
    private const val PE32_DATA_DIRECTORIES_OFFSET = 96L
    private const val PE32_PLUS_DATA_DIRECTORIES_OFFSET = 112L
    private const val DATA_DIRECTORY_SIZE = 8L
    private const val CLI_DIRECTORY_INDEX = 14L
    private const val SECTION_HEADER_SIZE = 40L
    private const val SECTION_VIRTUAL_SIZE_OFFSET = 8L
    private const val SECTION_VIRTUAL_ADDRESS_OFFSET = 12L
    private const val SECTION_RAW_SIZE_OFFSET = 16L
    private const val SECTION_RAW_OFFSET_OFFSET = 20L
    private const val CLI_HEADER_MINIMUM_SIZE = 32L
    private const val CLI_METADATA_DIRECTORY_OFFSET = 8L
    private const val CLI_RESOURCES_DIRECTORY_OFFSET = 24L
    private const val METADATA_ROOT_MINIMUM_SIZE = 20L
    private const val METADATA_SIGNATURE = 0x424a5342L
    private const val METADATA_VERSION_LENGTH_OFFSET = 12L
    private const val METADATA_VERSION_STRING_OFFSET = 16L
    private const val TABLES_HEADER_SIZE = 24L
    private const val TABLES_HEAP_SIZES_OFFSET = 6L
    private const val TABLES_VALID_MASK_OFFSET = 8L
    private const val TABLES_ROW_COUNTS_OFFSET = 24L
    private const val METADATA_TABLE_COUNT = 64
    private const val MANIFEST_RESOURCE_TABLE = 40
    private const val ASSEMBLY_TABLE = 32
    private const val FILE_TABLE = 38
    private const val ASSEMBLY_REF_TABLE = 35
    private const val EXPORTED_TYPE_TABLE = 39
    private const val STRING_HEAP_LARGE = 0x1
    private const val GUID_HEAP_LARGE = 0x2
    private const val BLOB_HEAP_LARGE = 0x4
    private const val ASSEMBLY_PUBLIC_KEY_FLAG = 0x1L
    private const val UINT16_INDEX_LIMIT = 1L shl 16
    private const val UINT16_SIZE = 2L
    private const val UINT32_SIZE = 4L
    private const val UINT64_SIZE = 8L
    private const val MAX_SECTION_COUNT = 1024
    private const val MAX_STREAM_COUNT = 64
    private const val MAX_STREAM_NAME_BYTES = 32
    private const val MAX_MANAGED_RESOURCE_SIZE = 512L * 1024 * 1024
}
