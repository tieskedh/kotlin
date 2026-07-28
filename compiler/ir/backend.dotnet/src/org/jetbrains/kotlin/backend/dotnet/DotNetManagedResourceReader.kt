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

object DotNetClrMetadataReader {
    fun read(file: File): DotNetClrAssemblyMetadata =
        DotNetPeMetadataReader.readClrMetadata(file)
}

class DotNetBadImageFormatException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

object DotNetManagedResourceReader {
    fun read(file: File, resourceName: String): DotNetManagedResource? =
        DotNetPeMetadataReader.readManagedResource(file, resourceName)
}

/**
 * Reads bounded ECMA-335 metadata directly from a PE image.
 *
 * The compiler is JVM-hosted, so library loading must not depend on a .NET sidecar. This reader
 * currently exposes managed resources plus the first physical CLR importer model. Every file
 * offset, RVA, stream, table, heap index, row count, coded handle, and resource length is checked
 * before it is used.
 */
private object DotNetPeMetadataReader {
    fun readManagedResource(file: File, resourceName: String): DotNetManagedResource? {
        require(resourceName.isNotEmpty()) { "Managed-resource name must not be empty" }
        return read(file) { image -> image.readManagedResource(resourceName) }
    }

    fun readClrMetadata(file: File): DotNetClrAssemblyMetadata =
        read(file, PeImage::readClrMetadata)

    private fun <T> read(file: File, action: (PeImage) -> T): T {
        try {
            RandomAccessFile(file, "r").use { input ->
                return action(PeImage(input, file.path))
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
            val metadata = readMetadataImage()
            val resourceRow =
                findManifestResource(metadata.tables, metadata.strings, resourceName) ?: return null
            if (resourceRow.implementation != 0L) {
                malformed("managed resource '$resourceName' is linked instead of embedded")
            }
            if (metadata.resourcesRva == 0L || metadata.resourcesSize < UINT32_SIZE) {
                malformed("CLI header has no managed-resource directory")
            }
            if (resourceRow.offset > metadata.resourcesSize - UINT32_SIZE) {
                malformed("managed resource '$resourceName' has an invalid directory offset")
            }
            val lengthRva = checkedAdd(
                metadata.resourcesRva,
                resourceRow.offset,
                "managed-resource RVA",
            )
            val lengthOffset = rvaToFileOffset(
                lengthRva,
                UINT32_SIZE,
                metadata.sections,
                "managed resource '$resourceName'",
            )
            val contentSize = readU4(lengthOffset)
            if (contentSize > MAX_MANAGED_RESOURCE_SIZE) {
                malformed(
                    "managed resource '$resourceName' is too large " +
                            "($contentSize bytes; limit is $MAX_MANAGED_RESOURCE_SIZE)",
                )
            }
            val resourceEnd = checkedAdd(resourceRow.offset, UINT32_SIZE + contentSize, "managed-resource size")
            if (resourceEnd > metadata.resourcesSize) {
                malformed("managed resource '$resourceName' extends beyond the CLI resource directory")
            }
            val contentOffset = rvaToFileOffset(
                checkedAdd(lengthRva, UINT32_SIZE, "managed-resource content RVA"),
                contentSize,
                metadata.sections,
                "managed resource '$resourceName' content",
            )
            return DotNetManagedResource(
                assemblyIdentity = metadata.assemblyIdentity,
                name = resourceName,
                attributes = resourceRow.attributes,
                content = readBytes(contentOffset, contentSize.toInt()),
            )
        }

        fun readClrMetadata(): DotNetClrAssemblyMetadata {
            val metadata = readMetadataImage()
            val typeReferences = readTypeReferences(metadata.tables, metadata.strings)
            val typeDefinitions = readTypeDefinitions(metadata.tables, metadata.strings)
            val fieldDefinitions = readFieldDefinitions(
                metadata.tables,
                metadata.strings,
                metadata.blobs,
            )
            val methodDefinitions = readMethodDefinitions(
                metadata.tables,
                metadata.strings,
                metadata.blobs,
            )
            val propertyDefinitions = readPropertyDefinitions(
                metadata.tables,
                metadata.strings,
                metadata.blobs,
            )
            val genericParameterDefinitions = readGenericParameterDefinitions(
                metadata.tables,
                metadata.strings,
                methodDefinitions,
            )
            return DotNetClrAssemblyMetadata(
                identity = metadata.assemblyIdentity,
                assemblyReferences = readAssemblyReferences(metadata.tables, metadata.strings, metadata.blobs),
                typeReferences = typeReferences,
                typeDefinitions = typeDefinitions,
                typeSpecifications = readTypeSpecifications(metadata.tables, metadata.blobs),
                fieldDefinitions = fieldDefinitions,
                methodDefinitions = methodDefinitions,
                memberReferences = readMemberReferences(
                    metadata.tables,
                    metadata.strings,
                    metadata.blobs,
                ),
                propertyDefinitions = propertyDefinitions,
                methodSemantics = readMethodSemantics(
                    metadata.tables,
                    methodDefinitions,
                    propertyDefinitions,
                ),
                genericParameterDefinitions = genericParameterDefinitions,
                genericParameterConstraints = readGenericParameterConstraints(
                    metadata.tables,
                    genericParameterDefinitions,
                ),
            )
        }

        private fun readMetadataImage(): MetadataImage {
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
            return MetadataImage(
                assemblyIdentity = assemblyIdentity,
                sections = sections,
                tables = tables,
                strings = strings,
                blobs = blobs,
                resourcesRva = resourcesRva,
                resourcesSize = resourcesSize,
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

        private fun readAssemblyReferences(
            tables: MetadataStream,
            strings: MetadataStream,
            blobs: MetadataStream?,
        ): List<DotNetClrAssemblyReference> {
            val table = locateMetadataTable(tables, ASSEMBLY_REF_TABLE) ?: return emptyList()
            return List(table.rowCount.toIntChecked("AssemblyRef row count")) { rowIndex ->
                var position = table.offset + rowIndex.toLong() * table.rowSize
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
                val publicKeyOrTokenIndex = readIndex(position, table.indexSizes.blobIndexSize)
                position += table.indexSizes.blobIndexSize
                val nameIndex = readIndex(position, table.indexSizes.stringIndexSize)
                position += table.indexSizes.stringIndexSize
                val cultureIndex = readIndex(position, table.indexSizes.stringIndexSize)
                position += table.indexSizes.stringIndexSize
                val hashValueIndex = readIndex(position, table.indexSizes.blobIndexSize)
                val name = readStringHeap(strings, nameIndex)
                if (name.isEmpty()) malformed("AssemblyRef row ${rowIndex + 1} has an empty name")
                DotNetClrAssemblyReference(
                    handle = metadataHandle(
                        ASSEMBLY_REF_TABLE,
                        rowIndex.toLong() + 1,
                        tables,
                        "AssemblyRef",
                    ),
                    name = name,
                    version = "$major.$minor.$build.$revision",
                    culture = readStringHeap(strings, cultureIndex).ifEmpty { "neutral" },
                    flags = flags,
                    publicKeyOrToken = readBlobHeap(blobs, publicKeyOrTokenIndex)
                        .map { byte -> byte.toInt() and 0xff },
                    hashValue = readBlobHeap(blobs, hashValueIndex)
                        .map { byte -> byte.toInt() and 0xff },
                )
            }
        }

        private fun readTypeReferences(
            tables: MetadataStream,
            strings: MetadataStream,
        ): List<DotNetClrTypeReference> {
            val table = locateMetadataTable(tables, TYPE_REF_TABLE) ?: return emptyList()
            return List(table.rowCount.toIntChecked("TypeRef row count")) { rowIndex ->
                var position = table.offset + rowIndex.toLong() * table.rowSize
                val resolutionScope = readIndex(position, table.indexSizes.resolutionScopeIndexSize)
                position += table.indexSizes.resolutionScopeIndexSize
                val nameIndex = readIndex(position, table.indexSizes.stringIndexSize)
                position += table.indexSizes.stringIndexSize
                val namespaceIndex = readIndex(position, table.indexSizes.stringIndexSize)
                val metadataName = readStringHeap(strings, nameIndex)
                if (metadataName.isEmpty()) malformed("TypeRef row ${rowIndex + 1} has an empty name")
                DotNetClrTypeReference(
                    handle = metadataHandle(
                        TYPE_REF_TABLE,
                        rowIndex.toLong() + 1,
                        tables,
                        "TypeRef",
                    ),
                    namespaceName = readStringHeap(strings, namespaceIndex),
                    metadataName = metadataName,
                    resolutionScope = decodeCodedHandle(
                        resolutionScope,
                        tagBits = 2,
                        tablesByTag = intArrayOf(MODULE_TABLE, MODULE_REF_TABLE, ASSEMBLY_REF_TABLE, TYPE_REF_TABLE),
                        metadataTables = tables,
                        description = "TypeRef resolution scope",
                    ),
                )
            }
        }

        private fun readTypeDefinitions(
            tables: MetadataStream,
            strings: MetadataStream,
        ): List<DotNetClrTypeDefinition> {
            val table = locateMetadataTable(tables, TYPE_DEF_TABLE) ?: return emptyList()
            val declaringTypes = readNestedTypeOwners(tables)
            return List(table.rowCount.toIntChecked("TypeDef row count")) { rowIndex ->
                var position = table.offset + rowIndex.toLong() * table.rowSize
                val attributes = readU4(position)
                position += UINT32_SIZE
                val nameIndex = readIndex(position, table.indexSizes.stringIndexSize)
                position += table.indexSizes.stringIndexSize
                val namespaceIndex = readIndex(position, table.indexSizes.stringIndexSize)
                position += table.indexSizes.stringIndexSize
                val extends = readIndex(position, table.indexSizes.typeDefOrRefIndexSize)
                val handle = metadataHandle(
                    TYPE_DEF_TABLE,
                    rowIndex.toLong() + 1,
                    tables,
                    "TypeDef",
                )
                val metadataName = readStringHeap(strings, nameIndex)
                if (metadataName.isEmpty()) malformed("TypeDef row ${rowIndex + 1} has an empty name")
                DotNetClrTypeDefinition(
                    handle = handle,
                    namespaceName = readStringHeap(strings, namespaceIndex),
                    metadataName = metadataName,
                    attributes = attributes,
                    baseType = decodeCodedHandle(
                        extends,
                        tagBits = 2,
                        tablesByTag = intArrayOf(TYPE_DEF_TABLE, TYPE_REF_TABLE, TYPE_SPEC_TABLE),
                        metadataTables = tables,
                        description = "TypeDef base type",
                    ),
                    declaringType = declaringTypes[handle],
                )
            }
        }

        private fun readTypeSpecifications(
            tables: MetadataStream,
            blobs: MetadataStream?,
        ): List<DotNetClrTypeSpecification> {
            val table = locateMetadataTable(tables, TYPE_SPEC_TABLE) ?: return emptyList()
            return List(table.rowCount.toIntChecked("TypeSpec row count")) { rowIndex ->
                val signatureIndex = readIndex(
                    table.offset + rowIndex.toLong() * table.rowSize,
                    table.indexSizes.blobIndexSize,
                )
                val rawSignature = readBlobHeap(blobs, signatureIndex)
                if (rawSignature.isEmpty()) {
                    malformed("TypeSpec row ${rowIndex + 1} has an empty signature")
                }
                if (rawSignature.size > MAX_SIGNATURE_BLOB_SIZE) {
                    malformed("TypeSpec row ${rowIndex + 1} has an oversized signature")
                }
                val handle = metadataHandle(
                    TYPE_SPEC_TABLE,
                    rowIndex.toLong() + 1,
                    tables,
                    "TypeSpec",
                )
                DotNetClrTypeSpecification(
                    handle = handle,
                    signature = SignatureBlobReader(
                        bytes = rawSignature,
                        metadataTables = tables,
                        description = "TypeSpec token 0x${handle.token.toUInt().toString(16)}",
                    ).readTypeSpecification(),
                    rawSignature = rawSignature.map { byte -> byte.toInt() and 0xff },
                )
            }
        }

        private fun readMethodDefinitions(
            tables: MetadataStream,
            strings: MetadataStream,
            blobs: MetadataStream?,
        ): List<DotNetClrMethodDefinition> {
            val table = locateMetadataTable(tables, METHOD_DEF_TABLE) ?: return emptyList()
            val owners = readTypeDefinitionMemberOwners(
                tables = tables,
                memberTable = METHOD_DEF_TABLE,
                memberCount = table.rowCount,
                memberDescription = "MethodDef",
            )
            return List(table.rowCount.toIntChecked("MethodDef row count")) { rowIndex ->
                var position = table.offset + rowIndex.toLong() * table.rowSize
                val relativeVirtualAddress = readU4(position)
                position += UINT32_SIZE
                val implementationAttributes = readU2(position)
                position += UINT16_SIZE
                val attributes = readU2(position)
                position += UINT16_SIZE
                if (attributes and METHOD_ACCESS_MASK == METHOD_ACCESS_MASK) {
                    malformed("MethodDef row ${rowIndex + 1} has invalid accessibility flags")
                }
                val nameIndex = readIndex(position, table.indexSizes.stringIndexSize)
                position += table.indexSizes.stringIndexSize
                val signatureIndex = readIndex(position, table.indexSizes.blobIndexSize)
                val name = readStringHeap(strings, nameIndex)
                if (name.isEmpty()) malformed("MethodDef row ${rowIndex + 1} has an empty name")
                val rawSignature = readBlobHeap(blobs, signatureIndex)
                if (rawSignature.isEmpty()) {
                    malformed("MethodDef row ${rowIndex + 1} has an empty signature")
                }
                if (rawSignature.size > MAX_SIGNATURE_BLOB_SIZE) {
                    malformed("MethodDef row ${rowIndex + 1} has an oversized signature")
                }
                val handle = metadataHandle(
                    METHOD_DEF_TABLE,
                    rowIndex.toLong() + 1,
                    tables,
                    "MethodDef",
                )
                val signature = SignatureBlobReader(
                    bytes = rawSignature,
                    metadataTables = tables,
                    description = "MethodDef token 0x${handle.token.toUInt().toString(16)}",
                ).readMethodDefinitionSignature()
                val isStatic = attributes and METHOD_STATIC_ATTRIBUTE != 0
                if (isStatic == signature.hasThis) {
                    malformed(
                        "MethodDef token 0x${handle.token.toUInt().toString(16)} has inconsistent " +
                                "static and has-this flags"
                    )
                }
                DotNetClrMethodDefinition(
                    handle = handle,
                    declaringType = owners[rowIndex],
                    name = name,
                    relativeVirtualAddress = relativeVirtualAddress,
                    implementationAttributes = implementationAttributes,
                    attributes = attributes,
                    signature = signature,
                    rawSignature = rawSignature.map { byte -> byte.toInt() and 0xff },
                )
            }
        }

        private fun readFieldDefinitions(
            tables: MetadataStream,
            strings: MetadataStream,
            blobs: MetadataStream?,
        ): List<DotNetClrFieldDefinition> {
            val table = locateMetadataTable(tables, FIELD_TABLE) ?: return emptyList()
            val owners = readTypeDefinitionMemberOwners(
                tables = tables,
                memberTable = FIELD_TABLE,
                memberCount = table.rowCount,
                memberDescription = "Field",
            )
            return List(table.rowCount.toIntChecked("Field row count")) { rowIndex ->
                var position = table.offset + rowIndex.toLong() * table.rowSize
                val attributes = readU2(position)
                position += UINT16_SIZE
                if (attributes and FIELD_ATTRIBUTE_MASK.inv() != 0 ||
                    attributes and FIELD_ACCESS_MASK == FIELD_ACCESS_MASK
                ) {
                    malformed("Field row ${rowIndex + 1} has invalid attribute flags")
                }
                if (attributes and FIELD_INIT_ONLY_ATTRIBUTE != 0 &&
                    attributes and FIELD_LITERAL_ATTRIBUTE != 0
                ) {
                    malformed("Field row ${rowIndex + 1} is both init-only and literal")
                }
                if (attributes and FIELD_LITERAL_ATTRIBUTE != 0 &&
                    attributes and FIELD_STATIC_ATTRIBUTE == 0
                ) {
                    malformed("Field row ${rowIndex + 1} is literal but not static")
                }
                if (attributes and FIELD_RUNTIME_SPECIAL_NAME_ATTRIBUTE != 0 &&
                    attributes and FIELD_SPECIAL_NAME_ATTRIBUTE == 0
                ) {
                    malformed("Field row ${rowIndex + 1} has runtime-special-name without special-name")
                }
                val nameIndex = readIndex(position, table.indexSizes.stringIndexSize)
                position += table.indexSizes.stringIndexSize
                val signatureIndex = readIndex(position, table.indexSizes.blobIndexSize)
                val name = readStringHeap(strings, nameIndex)
                if (name.isEmpty()) malformed("Field row ${rowIndex + 1} has an empty name")
                val rawSignature = readBlobHeap(blobs, signatureIndex)
                if (rawSignature.isEmpty()) {
                    malformed("Field row ${rowIndex + 1} has an empty signature")
                }
                if (rawSignature.size > MAX_SIGNATURE_BLOB_SIZE) {
                    malformed("Field row ${rowIndex + 1} has an oversized signature")
                }
                val handle = metadataHandle(
                    FIELD_TABLE,
                    rowIndex.toLong() + 1,
                    tables,
                    "Field",
                )
                DotNetClrFieldDefinition(
                    handle = handle,
                    declaringType = owners[rowIndex],
                    name = name,
                    attributes = attributes,
                    signature = SignatureBlobReader(
                        bytes = rawSignature,
                        metadataTables = tables,
                        description = "Field token 0x${handle.token.toUInt().toString(16)}",
                    ).readFieldSignature(),
                    rawSignature = rawSignature.map { byte -> byte.toInt() and 0xff },
                )
            }
        }

        private fun readMemberReferences(
            tables: MetadataStream,
            strings: MetadataStream,
            blobs: MetadataStream?,
        ): List<DotNetClrMemberReference> {
            val table = locateMetadataTable(tables, MEMBER_REF_TABLE) ?: return emptyList()
            return List(table.rowCount.toIntChecked("MemberRef row count")) { rowIndex ->
                var position = table.offset + rowIndex.toLong() * table.rowSize
                val parentIndex = readIndex(position, table.indexSizes.memberRefParentIndexSize)
                position += table.indexSizes.memberRefParentIndexSize
                val nameIndex = readIndex(position, table.indexSizes.stringIndexSize)
                position += table.indexSizes.stringIndexSize
                val signatureIndex = readIndex(position, table.indexSizes.blobIndexSize)
                val parent = decodeCodedHandle(
                    value = parentIndex,
                    tagBits = 3,
                    tablesByTag = intArrayOf(
                        TYPE_DEF_TABLE,
                        TYPE_REF_TABLE,
                        MODULE_REF_TABLE,
                        METHOD_DEF_TABLE,
                        TYPE_SPEC_TABLE,
                    ),
                    metadataTables = tables,
                    description = "MemberRef parent",
                ) ?: malformed("MemberRef row ${rowIndex + 1} has a nil parent")
                val name = readStringHeap(strings, nameIndex)
                if (name.isEmpty()) malformed("MemberRef row ${rowIndex + 1} has an empty name")
                val rawSignature = readBlobHeap(blobs, signatureIndex)
                if (rawSignature.isEmpty()) {
                    malformed("MemberRef row ${rowIndex + 1} has an empty signature")
                }
                if (rawSignature.size > MAX_SIGNATURE_BLOB_SIZE) {
                    malformed("MemberRef row ${rowIndex + 1} has an oversized signature")
                }
                val handle = metadataHandle(
                    MEMBER_REF_TABLE,
                    rowIndex.toLong() + 1,
                    tables,
                    "MemberRef",
                )
                DotNetClrMemberReference(
                    handle = handle,
                    parent = parent,
                    name = name,
                    signature = SignatureBlobReader(
                        bytes = rawSignature,
                        metadataTables = tables,
                        description = "MemberRef token 0x${handle.token.toUInt().toString(16)}",
                    ).readMemberReferenceSignature(),
                    rawSignature = rawSignature.map { byte -> byte.toInt() and 0xff },
                )
            }
        }

        private fun readTypeDefinitionMemberOwners(
            tables: MetadataStream,
            memberTable: Int,
            memberCount: Long,
            memberDescription: String,
        ): List<DotNetClrMetadataHandle> {
            val listDescription = when (memberTable) {
                FIELD_TABLE -> "FieldList"
                METHOD_DEF_TABLE -> "MethodList"
                else -> error("Unsupported TypeDef member table $memberTable")
            }
            val typeTable = locateMetadataTable(tables, TYPE_DEF_TABLE)
                ?: malformed("$memberDescription rows exist without a TypeDef table")
            val typeCount = typeTable.rowCount.toIntChecked("TypeDef row count")
            val memberStarts = List(typeCount) { typeIndex ->
                var position = typeTable.offset + typeIndex.toLong() * typeTable.rowSize
                position += UINT32_SIZE
                position += typeTable.indexSizes.stringIndexSize * 2L
                position += typeTable.indexSizes.typeDefOrRefIndexSize
                if (memberTable == METHOD_DEF_TABLE) {
                    position += typeTable.indexSizes.tableIndexSize(FIELD_TABLE)
                }
                readIndex(position, typeTable.indexSizes.tableIndexSize(memberTable))
            }
            var previousStart = 1L
            for ([typeIndex, start] in memberStarts.withIndex()) {
                if (start !in 1..memberCount + 1) {
                    malformed(
                        "TypeDef row ${typeIndex + 1} has invalid $listDescription index $start"
                    )
                }
                if (start < previousStart) {
                    malformed("TypeDef $listDescription indices are not ordered")
                }
                previousStart = start
            }
            val owners = arrayOfNulls<DotNetClrMetadataHandle>(
                memberCount.toIntChecked("$memberDescription row count")
            )
            for (typeIndex in memberStarts.indices) {
                val start = memberStarts[typeIndex]
                val end = memberStarts.getOrNull(typeIndex + 1) ?: (memberCount + 1)
                val owner = metadataHandle(
                    TYPE_DEF_TABLE,
                    typeIndex.toLong() + 1,
                    tables,
                    "$memberDescription owner",
                )
                for (memberRow in start until end) {
                    owners[(memberRow - 1).toInt()] = owner
                }
            }
            if (owners.any { owner -> owner == null }) {
                malformed("one or more $memberDescription rows have no declaring TypeDef")
            }
            return owners.map { owner -> checkNotNull(owner) }
        }

        private fun readPropertyDefinitions(
            tables: MetadataStream,
            strings: MetadataStream,
            blobs: MetadataStream?,
        ): List<DotNetClrPropertyDefinition> {
            val table = locateMetadataTable(tables, PROPERTY_TABLE) ?: return emptyList()
            val owners = readPropertyOwners(tables, table.rowCount)
            return List(table.rowCount.toIntChecked("Property row count")) { rowIndex ->
                var position = table.offset + rowIndex.toLong() * table.rowSize
                val attributes = readU2(position)
                position += UINT16_SIZE
                if (attributes and PROPERTY_ATTRIBUTE_MASK.inv() != 0) {
                    malformed("Property row ${rowIndex + 1} has invalid attribute flags")
                }
                val nameIndex = readIndex(position, table.indexSizes.stringIndexSize)
                position += table.indexSizes.stringIndexSize
                val signatureIndex = readIndex(position, table.indexSizes.blobIndexSize)
                val name = readStringHeap(strings, nameIndex)
                if (name.isEmpty()) malformed("Property row ${rowIndex + 1} has an empty name")
                val rawSignature = readBlobHeap(blobs, signatureIndex)
                if (rawSignature.isEmpty()) {
                    malformed("Property row ${rowIndex + 1} has an empty signature")
                }
                if (rawSignature.size > MAX_SIGNATURE_BLOB_SIZE) {
                    malformed("Property row ${rowIndex + 1} has an oversized signature")
                }
                val handle = metadataHandle(
                    PROPERTY_TABLE,
                    rowIndex.toLong() + 1,
                    tables,
                    "Property",
                )
                DotNetClrPropertyDefinition(
                    handle = handle,
                    declaringType = owners[rowIndex],
                    name = name,
                    attributes = attributes,
                    signature = SignatureBlobReader(
                        bytes = rawSignature,
                        metadataTables = tables,
                        description = "Property token 0x${handle.token.toUInt().toString(16)}",
                    ).readPropertySignature(),
                    rawSignature = rawSignature.map { byte -> byte.toInt() and 0xff },
                )
            }
        }

        private fun readPropertyOwners(
            tables: MetadataStream,
            propertyCount: Long,
        ): List<DotNetClrMetadataHandle> {
            val mapTable = locateMetadataTable(tables, PROPERTY_MAP_TABLE)
                ?: malformed("Property rows exist without a PropertyMap table")
            val maps = List(mapTable.rowCount.toIntChecked("PropertyMap row count")) { rowIndex ->
                var position = mapTable.offset + rowIndex.toLong() * mapTable.rowSize
                val parentIndex = readIndex(
                    position,
                    mapTable.indexSizes.tableIndexSize(TYPE_DEF_TABLE),
                )
                position += mapTable.indexSizes.tableIndexSize(TYPE_DEF_TABLE)
                val propertyStart = readIndex(
                    position,
                    mapTable.indexSizes.tableIndexSize(PROPERTY_TABLE),
                )
                PropertyOwnerRun(
                    owner = metadataHandle(
                        TYPE_DEF_TABLE,
                        parentIndex,
                        tables,
                        "PropertyMap parent",
                    ),
                    propertyStart = propertyStart,
                )
            }
            var previousParentRow = 0
            var previousPropertyStart = 0L
            for ([mapIndex, map] in maps.withIndex()) {
                if (map.owner.row <= previousParentRow) {
                    malformed("PropertyMap Parent indices are not strictly ordered")
                }
                if (map.propertyStart !in 1..propertyCount + 1) {
                    malformed(
                        "PropertyMap row ${mapIndex + 1} has invalid PropertyList index ${map.propertyStart}"
                    )
                }
                if (map.propertyStart <= previousPropertyStart) {
                    malformed("PropertyMap PropertyList indices are not strictly ordered")
                }
                previousParentRow = map.owner.row
                previousPropertyStart = map.propertyStart
            }
            if (maps.firstOrNull()?.propertyStart != 1L) {
                malformed("one or more Property rows have no declaring TypeDef")
            }
            val owners = arrayOfNulls<DotNetClrMetadataHandle>(
                propertyCount.toIntChecked("Property row count")
            )
            for (mapIndex in maps.indices) {
                val map = maps[mapIndex]
                val end = maps.getOrNull(mapIndex + 1)?.propertyStart ?: (propertyCount + 1)
                for (propertyRow in map.propertyStart until end) {
                    owners[(propertyRow - 1).toInt()] = map.owner
                }
            }
            if (owners.any { owner -> owner == null }) {
                malformed("one or more Property rows have no declaring TypeDef")
            }
            return owners.map { owner -> checkNotNull(owner) }
        }

        private fun readMethodSemantics(
            tables: MetadataStream,
            methodDefinitions: List<DotNetClrMethodDefinition>,
            propertyDefinitions: List<DotNetClrPropertyDefinition>,
        ): List<DotNetClrMethodSemantics> {
            val table = locateMetadataTable(tables, METHOD_SEMANTICS_TABLE) ?: return emptyList()
            val methodsByHandle = methodDefinitions.associateBy(DotNetClrMethodDefinition::handle)
            val propertiesByHandle = propertyDefinitions.associateBy(DotNetClrPropertyDefinition::handle)
            return List(table.rowCount.toIntChecked("MethodSemantics row count")) { rowIndex ->
                var position = table.offset + rowIndex.toLong() * table.rowSize
                val semantics = readU2(position)
                position += UINT16_SIZE
                val methodIndex = readIndex(
                    position,
                    table.indexSizes.tableIndexSize(METHOD_DEF_TABLE),
                )
                position += table.indexSizes.tableIndexSize(METHOD_DEF_TABLE)
                val associationIndex = readIndex(position, table.indexSizes.hasSemanticsIndexSize)
                val methodHandle = metadataHandle(
                    METHOD_DEF_TABLE,
                    methodIndex,
                    tables,
                    "MethodSemantics method",
                )
                val associationHandle = decodeCodedHandle(
                    value = associationIndex,
                    tagBits = 1,
                    tablesByTag = intArrayOf(EVENT_TABLE, PROPERTY_TABLE),
                    metadataTables = tables,
                    description = "MethodSemantics association",
                ) ?: malformed("MethodSemantics row ${rowIndex + 1} has a nil association")
                val kind = decodeMethodSemanticsKind(
                    semantics,
                    associationHandle,
                    rowIndex,
                )
                val method = methodsByHandle[methodHandle]
                    ?: malformed("MethodSemantics row ${rowIndex + 1} refers to an unread MethodDef")
                if (associationHandle.table == PROPERTY_TABLE) {
                    val property = propertiesByHandle[associationHandle]
                        ?: malformed("MethodSemantics row ${rowIndex + 1} refers to an unread Property")
                    if (method.declaringType != property.declaringType) {
                        malformed(
                            "MethodSemantics row ${rowIndex + 1} associates members from different types"
                        )
                    }
                }
                DotNetClrMethodSemantics(
                    handle = metadataHandle(
                        METHOD_SEMANTICS_TABLE,
                        rowIndex.toLong() + 1,
                        tables,
                        "MethodSemantics",
                    ),
                    kind = kind,
                    method = methodHandle,
                    association = associationHandle,
                )
            }
        }

        private fun decodeMethodSemanticsKind(
            semantics: Int,
            association: DotNetClrMetadataHandle,
            rowIndex: Int,
        ): DotNetClrMethodSemanticsKind {
            val kind = when (semantics) {
                METHOD_SEMANTICS_SETTER -> DotNetClrMethodSemanticsKind.SETTER
                METHOD_SEMANTICS_GETTER -> DotNetClrMethodSemanticsKind.GETTER
                METHOD_SEMANTICS_OTHER -> DotNetClrMethodSemanticsKind.OTHER
                METHOD_SEMANTICS_ADD_ON -> DotNetClrMethodSemanticsKind.ADD_ON
                METHOD_SEMANTICS_REMOVE_ON -> DotNetClrMethodSemanticsKind.REMOVE_ON
                METHOD_SEMANTICS_FIRE -> DotNetClrMethodSemanticsKind.FIRE
                else -> malformed("MethodSemantics row ${rowIndex + 1} has invalid semantics flags")
            }
            val validForAssociation = when (association.table) {
                PROPERTY_TABLE ->
                    kind == DotNetClrMethodSemanticsKind.SETTER ||
                            kind == DotNetClrMethodSemanticsKind.GETTER ||
                            kind == DotNetClrMethodSemanticsKind.OTHER
                EVENT_TABLE ->
                    kind == DotNetClrMethodSemanticsKind.ADD_ON ||
                            kind == DotNetClrMethodSemanticsKind.REMOVE_ON ||
                            kind == DotNetClrMethodSemanticsKind.FIRE ||
                            kind == DotNetClrMethodSemanticsKind.OTHER
                else -> false
            }
            if (!validForAssociation) {
                malformed(
                    "MethodSemantics row ${rowIndex + 1} has semantics incompatible with its association"
                )
            }
            return kind
        }

        private fun readGenericParameterDefinitions(
            tables: MetadataStream,
            strings: MetadataStream,
            methodDefinitions: List<DotNetClrMethodDefinition>,
        ): List<DotNetClrGenericParameterDefinition> {
            val table = locateMetadataTable(tables, GENERIC_PARAM_TABLE)
                ?: return validateMethodGenericParameterCounts(emptyList(), methodDefinitions)
            val parameters = List(table.rowCount.toIntChecked("GenericParam row count")) { rowIndex ->
                var position = table.offset + rowIndex.toLong() * table.rowSize
                val number = readU2(position)
                position += UINT16_SIZE
                val attributes = readU2(position)
                position += UINT16_SIZE
                if (attributes and GENERIC_PARAMETER_ATTRIBUTE_MASK.inv() != 0 ||
                    attributes and GENERIC_PARAMETER_VARIANCE_MASK == GENERIC_PARAMETER_VARIANCE_MASK
                ) {
                    malformed("GenericParam row ${rowIndex + 1} has invalid attribute flags")
                }
                val ownerIndex = readIndex(position, table.indexSizes.typeOrMethodDefIndexSize)
                position += table.indexSizes.typeOrMethodDefIndexSize
                val nameIndex = readIndex(position, table.indexSizes.stringIndexSize)
                if (nameIndex == 0L) {
                    malformed("GenericParam row ${rowIndex + 1} has a null name")
                }
                val owner = decodeCodedHandle(
                    value = ownerIndex,
                    tagBits = 1,
                    tablesByTag = intArrayOf(TYPE_DEF_TABLE, METHOD_DEF_TABLE),
                    metadataTables = tables,
                    description = "GenericParam owner",
                ) ?: malformed("GenericParam row ${rowIndex + 1} has a nil owner")
                if (owner.table == METHOD_DEF_TABLE &&
                    attributes and GENERIC_PARAMETER_VARIANCE_MASK != 0
                ) {
                    malformed("GenericParam row ${rowIndex + 1} gives a method parameter variance")
                }
                DotNetClrGenericParameterDefinition(
                    handle = metadataHandle(
                        GENERIC_PARAM_TABLE,
                        rowIndex.toLong() + 1,
                        tables,
                        "GenericParam",
                    ),
                    number = number,
                    attributes = attributes,
                    owner = owner,
                    name = readStringHeap(strings, nameIndex),
                )
            }
            validateGenericParameterRows(parameters)
            return validateMethodGenericParameterCounts(parameters, methodDefinitions)
        }

        private fun validateGenericParameterRows(
            parameters: List<DotNetClrGenericParameterDefinition>,
        ) {
            val ownerAndName = mutableSetOf<Pair<DotNetClrMetadataHandle, String>>()
            val ownerAndNumber = mutableSetOf<Pair<DotNetClrMetadataHandle, Int>>()
            for (parameter in parameters) {
                if (!ownerAndName.add(parameter.owner to parameter.name)) {
                    malformed(
                        "GenericParam owner token 0x${parameter.owner.token.toUInt().toString(16)} " +
                                "has duplicate parameter name '${parameter.name}'"
                    )
                }
                if (!ownerAndNumber.add(parameter.owner to parameter.number)) {
                    malformed(
                        "GenericParam owner token 0x${parameter.owner.token.toUInt().toString(16)} " +
                                "has duplicate parameter number ${parameter.number}"
                    )
                }
            }
            for ([owner, ownedParameters] in parameters.groupBy(DotNetClrGenericParameterDefinition::owner)) {
                val numbers = ownedParameters.map(DotNetClrGenericParameterDefinition::number).sorted()
                for ([expectedNumber, actualNumber] in numbers.withIndex()) {
                    if (actualNumber != expectedNumber) {
                        malformed(
                            "GenericParam owner token 0x${owner.token.toUInt().toString(16)} " +
                                    "has a gap before parameter number $actualNumber"
                        )
                    }
                }
            }
        }

        private fun validateMethodGenericParameterCounts(
            parameters: List<DotNetClrGenericParameterDefinition>,
            methodDefinitions: List<DotNetClrMethodDefinition>,
        ): List<DotNetClrGenericParameterDefinition> {
            val methodParameterCounts = parameters
                .filter { parameter -> parameter.owner.table == METHOD_DEF_TABLE }
                .groupingBy(DotNetClrGenericParameterDefinition::owner)
                .eachCount()
            for (method in methodDefinitions) {
                val actualCount = methodParameterCounts[method.handle] ?: 0
                val expectedCount = method.signature.genericParameterCount
                if (actualCount != expectedCount) {
                    malformed(
                        "MethodDef token 0x${method.handle.token.toUInt().toString(16)} declares " +
                                "$expectedCount generic parameters in its signature but owns $actualCount GenericParam rows"
                    )
                }
            }
            return parameters
        }

        private fun readGenericParameterConstraints(
            tables: MetadataStream,
            genericParameters: List<DotNetClrGenericParameterDefinition>,
        ): List<DotNetClrGenericParameterConstraint> {
            val table = locateMetadataTable(tables, GENERIC_PARAM_CONSTRAINT_TABLE) ?: return emptyList()
            val genericParametersByHandle =
                genericParameters.associateBy(DotNetClrGenericParameterDefinition::handle)
            val seenConstraints =
                mutableSetOf<Pair<DotNetClrMetadataHandle, DotNetClrMetadataHandle>>()
            val completedOwners = mutableSetOf<DotNetClrMetadataHandle>()
            var currentOwner: DotNetClrMetadataHandle? = null
            return List(table.rowCount.toIntChecked("GenericParamConstraint row count")) { rowIndex ->
                var position = table.offset + rowIndex.toLong() * table.rowSize
                val ownerIndex = readIndex(
                    position,
                    table.indexSizes.tableIndexSize(GENERIC_PARAM_TABLE),
                )
                position += table.indexSizes.tableIndexSize(GENERIC_PARAM_TABLE)
                val constraintIndex = readIndex(position, table.indexSizes.typeDefOrRefIndexSize)
                val owner = metadataHandle(
                    GENERIC_PARAM_TABLE,
                    ownerIndex,
                    tables,
                    "GenericParamConstraint owner",
                )
                if (owner !in genericParametersByHandle) {
                    malformed("GenericParamConstraint row ${rowIndex + 1} refers to an unread GenericParam")
                }
                if (owner != currentOwner) {
                    currentOwner?.let(completedOwners::add)
                    if (owner in completedOwners) {
                        malformed("GenericParamConstraint rows for one owner are not contiguous")
                    }
                    currentOwner = owner
                }
                val constraint = decodeCodedHandle(
                    value = constraintIndex,
                    tagBits = 2,
                    tablesByTag = intArrayOf(TYPE_DEF_TABLE, TYPE_REF_TABLE, TYPE_SPEC_TABLE),
                    metadataTables = tables,
                    description = "GenericParamConstraint constraint",
                ) ?: malformed("GenericParamConstraint row ${rowIndex + 1} has a nil constraint")
                if (!seenConstraints.add(owner to constraint)) {
                    malformed("GenericParamConstraint row ${rowIndex + 1} duplicates a constraint")
                }
                DotNetClrGenericParameterConstraint(
                    handle = metadataHandle(
                        GENERIC_PARAM_CONSTRAINT_TABLE,
                        rowIndex.toLong() + 1,
                        tables,
                        "GenericParamConstraint",
                    ),
                    owner = owner,
                    constraint = constraint,
                )
            }
        }

        private fun readNestedTypeOwners(
            tables: MetadataStream,
        ): Map<DotNetClrMetadataHandle, DotNetClrMetadataHandle> {
            val table = locateMetadataTable(tables, NESTED_CLASS_TABLE) ?: return emptyMap()
            val result = linkedMapOf<DotNetClrMetadataHandle, DotNetClrMetadataHandle>()
            repeat(table.rowCount.toIntChecked("NestedClass row count")) { rowIndex ->
                var position = table.offset + rowIndex.toLong() * table.rowSize
                val nestedIndex = readIndex(position, table.indexSizes.tableIndexSize(TYPE_DEF_TABLE))
                position += table.indexSizes.tableIndexSize(TYPE_DEF_TABLE)
                val enclosingIndex = readIndex(position, table.indexSizes.tableIndexSize(TYPE_DEF_TABLE))
                val nested = metadataHandle(TYPE_DEF_TABLE, nestedIndex, tables, "nested TypeDef")
                val enclosing = metadataHandle(TYPE_DEF_TABLE, enclosingIndex, tables, "enclosing TypeDef")
                if (result.put(nested, enclosing) != null) {
                    malformed("TypeDef token 0x${nested.token.toUInt().toString(16)} has multiple declaring types")
                }
            }
            return result
        }

        private inner class SignatureBlobReader(
            private val bytes: ByteArray,
            private val metadataTables: MetadataStream,
            private val description: String,
        ) {
            private var position = 0

            fun readTypeSpecification(): DotNetClrTypeSignature {
                val signature = readType(
                    depth = 0,
                    allowVoid = false,
                    allowByReference = false,
                    allowTypedReference = false,
                )
                if (position != bytes.size) {
                    malformed("$description has ${bytes.size - position} trailing signature bytes")
                }
                return signature
            }

            fun readMethodDefinitionSignature(): DotNetClrMethodSignature {
                val signature = readMethodSignature(depth = 0, allowSentinel = false)
                if (signature.callingConvention != DotNetClrSignatureCallingConvention.DEFAULT &&
                    signature.callingConvention != DotNetClrSignatureCallingConvention.VARARG
                ) {
                    malformed("$description has a non-managed calling convention")
                }
                if (position != bytes.size) {
                    malformed("$description has ${bytes.size - position} trailing signature bytes")
                }
                return signature
            }

            fun readMemberReferenceSignature(): DotNetClrMemberReferenceSignature {
                val signature = if (peekByte() == SIGNATURE_FIELD) {
                    DotNetClrMemberReferenceSignature.Field(
                        signature = readFieldSignature()
                    )
                } else {
                    val method = readMethodSignature(depth = 0, allowSentinel = true)
                    if (method.callingConvention != DotNetClrSignatureCallingConvention.DEFAULT &&
                        method.callingConvention != DotNetClrSignatureCallingConvention.VARARG
                    ) {
                        malformed("$description has a non-managed calling convention")
                    }
                    DotNetClrMemberReferenceSignature.Method(method)
                }
                if (position != bytes.size) {
                    malformed("$description has ${bytes.size - position} trailing signature bytes")
                }
                return signature
            }

            fun readFieldSignature(): DotNetClrFieldSignature {
                if (readByte() != SIGNATURE_FIELD) {
                    malformed("$description has an invalid field signature header")
                }
                val fieldType = readType(
                    depth = 0,
                    allowVoid = false,
                    allowByReference = true,
                    allowTypedReference = true,
                )
                if (position != bytes.size) {
                    malformed("$description has ${bytes.size - position} trailing signature bytes")
                }
                return DotNetClrFieldSignature(fieldType)
            }

            fun readPropertySignature(): DotNetClrPropertySignature {
                val header = readByte()
                if (header and SIGNATURE_PROPERTY_MASK != SIGNATURE_PROPERTY) {
                    malformed("$description has an invalid property signature header")
                }
                val parameterCount = readCompressedUnsigned("property index-parameter count")
                ensureCollectionFits(parameterCount, "property index parameters")
                val propertyType = readType(
                    depth = 0,
                    allowVoid = false,
                    allowByReference = true,
                    allowTypedReference = true,
                )
                val indexParameterTypes = List(parameterCount) {
                    readType(
                        depth = 0,
                        allowVoid = false,
                        allowByReference = true,
                        allowTypedReference = true,
                    )
                }
                if (position != bytes.size) {
                    malformed("$description has ${bytes.size - position} trailing signature bytes")
                }
                return DotNetClrPropertySignature(
                    hasThis = header and SIGNATURE_HAS_THIS != 0,
                    propertyType = propertyType,
                    indexParameterTypes = indexParameterTypes,
                )
            }

            private fun readType(
                depth: Int,
                allowVoid: Boolean,
                allowByReference: Boolean,
                allowTypedReference: Boolean,
            ): DotNetClrTypeSignature {
                if (depth > MAX_SIGNATURE_DEPTH) {
                    malformed("$description exceeds the maximum signature nesting depth")
                }
                val modifiers = mutableListOf<DotNetClrCustomModifier>()
                while (peekByte() == ELEMENT_TYPE_CMOD_REQD || peekByte() == ELEMENT_TYPE_CMOD_OPT) {
                    val kind = readByte()
                    modifiers += DotNetClrCustomModifier(
                        isRequired = kind == ELEMENT_TYPE_CMOD_REQD,
                        modifierType = readTypeDefOrRefHandle(
                            part = "custom modifier",
                            allowTypeSpecification = true,
                        ),
                    )
                }
                val unmodified = when (val elementType = readByte()) {
                    ELEMENT_TYPE_VOID -> {
                        if (!allowVoid) malformed("$description contains void where a type is required")
                        DotNetClrTypeSignature.Void
                    }
                    ELEMENT_TYPE_BOOLEAN -> primitive(DotNetClrPrimitiveType.BOOLEAN)
                    ELEMENT_TYPE_CHAR -> primitive(DotNetClrPrimitiveType.CHAR)
                    ELEMENT_TYPE_I1 -> primitive(DotNetClrPrimitiveType.INT8)
                    ELEMENT_TYPE_U1 -> primitive(DotNetClrPrimitiveType.UINT8)
                    ELEMENT_TYPE_I2 -> primitive(DotNetClrPrimitiveType.INT16)
                    ELEMENT_TYPE_U2 -> primitive(DotNetClrPrimitiveType.UINT16)
                    ELEMENT_TYPE_I4 -> primitive(DotNetClrPrimitiveType.INT32)
                    ELEMENT_TYPE_U4 -> primitive(DotNetClrPrimitiveType.UINT32)
                    ELEMENT_TYPE_I8 -> primitive(DotNetClrPrimitiveType.INT64)
                    ELEMENT_TYPE_U8 -> primitive(DotNetClrPrimitiveType.UINT64)
                    ELEMENT_TYPE_R4 -> primitive(DotNetClrPrimitiveType.FLOAT32)
                    ELEMENT_TYPE_R8 -> primitive(DotNetClrPrimitiveType.FLOAT64)
                    ELEMENT_TYPE_STRING -> primitive(DotNetClrPrimitiveType.STRING)
                    ELEMENT_TYPE_I -> primitive(DotNetClrPrimitiveType.NATIVE_INT)
                    ELEMENT_TYPE_U -> primitive(DotNetClrPrimitiveType.NATIVE_UINT)
                    ELEMENT_TYPE_OBJECT -> primitive(DotNetClrPrimitiveType.OBJECT)
                    ELEMENT_TYPE_TYPEDBYREF -> {
                        if (!allowTypedReference) {
                            malformed("$description contains typed-reference where a type is required")
                        }
                        DotNetClrTypeSignature.TypedReference
                    }
                    ELEMENT_TYPE_CLASS, ELEMENT_TYPE_VALUETYPE -> DotNetClrTypeSignature.Named(
                        type = readTypeDefOrRefHandle(
                            part = "named type",
                            allowTypeSpecification = false,
                        ),
                        isValueType = elementType == ELEMENT_TYPE_VALUETYPE,
                    )
                    ELEMENT_TYPE_VAR, ELEMENT_TYPE_MVAR -> DotNetClrTypeSignature.GenericParameter(
                        kind = if (elementType == ELEMENT_TYPE_VAR) {
                            DotNetClrGenericParameterKind.TYPE
                        } else {
                            DotNetClrGenericParameterKind.METHOD
                        },
                        index = readCompressedUnsigned("generic-parameter index"),
                    )
                    ELEMENT_TYPE_PTR -> DotNetClrTypeSignature.Pointer(
                        readType(
                            depth = depth + 1,
                            allowVoid = true,
                            allowByReference = false,
                            allowTypedReference = false,
                        )
                    )
                    ELEMENT_TYPE_BYREF -> {
                        if (!allowByReference) {
                            malformed("$description contains a nested by-reference type")
                        }
                        DotNetClrTypeSignature.ByReference(
                            readType(
                                depth = depth + 1,
                                allowVoid = false,
                                allowByReference = false,
                                allowTypedReference = false,
                            )
                        )
                    }
                    ELEMENT_TYPE_SZARRAY -> DotNetClrTypeSignature.SzArray(
                        readType(
                            depth = depth + 1,
                            allowVoid = false,
                            allowByReference = false,
                            allowTypedReference = false,
                        )
                    )
                    ELEMENT_TYPE_ARRAY -> readArray(depth)
                    ELEMENT_TYPE_GENERICINST -> readGenericInstance(depth)
                    ELEMENT_TYPE_FNPTR -> DotNetClrTypeSignature.FunctionPointer(
                        readMethodSignature(depth + 1, allowSentinel = true)
                    )
                    else -> malformed(
                        "$description contains unsupported element type 0x${elementType.toString(16)}"
                    )
                }
                return if (modifiers.isEmpty()) {
                    unmodified
                } else {
                    DotNetClrTypeSignature.Modified(modifiers, unmodified)
                }
            }

            private fun readArray(depth: Int): DotNetClrTypeSignature.Array {
                val elementType = readType(
                    depth = depth + 1,
                    allowVoid = false,
                    allowByReference = false,
                    allowTypedReference = false,
                )
                val rank = readCompressedUnsigned("array rank")
                if (rank == 0) malformed("$description contains an array with rank zero")
                val sizeCount = readCompressedUnsigned("array size count")
                if (sizeCount > rank) {
                    malformed("$description contains $sizeCount array sizes for rank $rank")
                }
                ensureCollectionFits(sizeCount, "array sizes")
                val sizes = List(sizeCount) { readCompressedUnsigned("array size") }
                val lowerBoundCount = readCompressedUnsigned("array lower-bound count")
                if (lowerBoundCount > rank) {
                    malformed("$description contains $lowerBoundCount array lower bounds for rank $rank")
                }
                ensureCollectionFits(lowerBoundCount, "array lower bounds")
                val lowerBounds = List(lowerBoundCount) { readCompressedSigned("array lower bound") }
                return DotNetClrTypeSignature.Array(
                    elementType = elementType,
                    shape = DotNetClrArrayShape(rank, sizes, lowerBounds),
                )
            }

            private fun readGenericInstance(depth: Int): DotNetClrTypeSignature.GenericInstance {
                val namedKind = readByte()
                if (namedKind != ELEMENT_TYPE_CLASS && namedKind != ELEMENT_TYPE_VALUETYPE) {
                    malformed(
                        "$description generic instance has invalid type kind 0x${namedKind.toString(16)}"
                    )
                }
                val genericType = DotNetClrTypeSignature.Named(
                    type = readTypeDefOrRefHandle(
                        part = "generic type",
                        allowTypeSpecification = false,
                    ),
                    isValueType = namedKind == ELEMENT_TYPE_VALUETYPE,
                )
                val argumentCount = readCompressedUnsigned("generic argument count")
                ensureCollectionFits(argumentCount, "generic arguments")
                return DotNetClrTypeSignature.GenericInstance(
                    genericType = genericType,
                    arguments = List(argumentCount) {
                        readType(
                            depth = depth + 1,
                            allowVoid = false,
                            allowByReference = false,
                            allowTypedReference = false,
                        )
                    },
                )
            }

            private fun readMethodSignature(
                depth: Int,
                allowSentinel: Boolean,
            ): DotNetClrMethodSignature {
                if (depth > MAX_SIGNATURE_DEPTH) {
                    malformed("$description exceeds the maximum signature nesting depth")
                }
                val header = readByte()
                if (header and SIGNATURE_RESERVED_MASK != 0) {
                    malformed("$description has reserved signature-header bits")
                }
                val callingConvention = when (header and SIGNATURE_CALLING_CONVENTION_MASK) {
                    SIGNATURE_DEFAULT -> DotNetClrSignatureCallingConvention.DEFAULT
                    SIGNATURE_C -> DotNetClrSignatureCallingConvention.C
                    SIGNATURE_STDCALL -> DotNetClrSignatureCallingConvention.STDCALL
                    SIGNATURE_THISCALL -> DotNetClrSignatureCallingConvention.THISCALL
                    SIGNATURE_FASTCALL -> DotNetClrSignatureCallingConvention.FASTCALL
                    SIGNATURE_VARARG -> DotNetClrSignatureCallingConvention.VARARG
                    SIGNATURE_UNMANAGED -> DotNetClrSignatureCallingConvention.UNMANAGED
                    SIGNATURE_NATIVE_VARARG -> DotNetClrSignatureCallingConvention.NATIVE_VARARG
                    else -> malformed(
                        "$description has unsupported signature calling convention " +
                                "0x${(header and SIGNATURE_CALLING_CONVENTION_MASK).toString(16)}"
                    )
                }
                val hasThis = header and SIGNATURE_HAS_THIS != 0
                val hasExplicitThis = header and SIGNATURE_EXPLICIT_THIS != 0
                if (hasExplicitThis && !hasThis) {
                    malformed("$description has explicit-this without has-this")
                }
                val genericParameterCount = if (header and SIGNATURE_GENERIC != 0) {
                    readCompressedUnsigned("method generic-parameter count").also { count ->
                        if (count == 0) {
                            malformed("$description marks a method generic with zero parameters")
                        }
                    }
                } else {
                    0
                }
                val parameterCount = readCompressedUnsigned("method parameter count")
                ensureCollectionFits(parameterCount, "method parameters")
                val returnType = readType(
                    depth = depth + 1,
                    allowVoid = true,
                    allowByReference = true,
                    allowTypedReference = true,
                )
                val parameterTypes = ArrayList<DotNetClrTypeSignature>(parameterCount)
                var varargParameterStart: Int? = null
                while (parameterTypes.size < parameterCount) {
                    if (peekByte() == ELEMENT_TYPE_SENTINEL) {
                        if (!allowSentinel) {
                            malformed("$description has a vararg sentinel in a definition signature")
                        }
                        if (callingConvention != DotNetClrSignatureCallingConvention.VARARG &&
                            callingConvention != DotNetClrSignatureCallingConvention.C &&
                            callingConvention != DotNetClrSignatureCallingConvention.NATIVE_VARARG
                        ) {
                            malformed("$description has a sentinel on a non-vararg signature")
                        }
                        if (varargParameterStart != null) {
                            malformed("$description has multiple vararg sentinels")
                        }
                        readByte()
                        varargParameterStart = parameterTypes.size
                    }
                    parameterTypes += readType(
                        depth = depth + 1,
                        allowVoid = false,
                        allowByReference = true,
                        allowTypedReference = true,
                    )
                }
                if (varargParameterStart == parameterTypes.size) {
                    malformed("$description has a trailing vararg sentinel")
                }
                return DotNetClrMethodSignature(
                    callingConvention = callingConvention,
                    hasThis = hasThis,
                    hasExplicitThis = hasExplicitThis,
                    genericParameterCount = genericParameterCount,
                    returnType = returnType,
                    parameterTypes = parameterTypes,
                    varargParameterStart = varargParameterStart,
                )
            }

            private fun readTypeDefOrRefHandle(
                part: String,
                allowTypeSpecification: Boolean,
            ): DotNetClrMetadataHandle {
                val encoded = readCompressedUnsigned("$part handle").toLong()
                val handle = decodeCodedHandle(
                    value = encoded,
                    tagBits = 2,
                    tablesByTag = intArrayOf(TYPE_DEF_TABLE, TYPE_REF_TABLE, TYPE_SPEC_TABLE),
                    metadataTables = metadataTables,
                    description = "$description $part",
                ) ?: malformed("$description $part has a nil handle")
                if (!allowTypeSpecification && handle.table == TYPE_SPEC_TABLE) {
                    malformed("$description $part refers to a TypeSpec")
                }
                return handle
            }

            private fun readCompressedUnsigned(part: String): Int =
                readCompressedUnsignedWithWidth(part, enforceUnsignedCanonicalForm = true).value

            private fun readCompressedUnsignedWithWidth(
                part: String,
                enforceUnsignedCanonicalForm: Boolean,
            ): CompressedUnsigned {
                val first = readByte()
                return when {
                    first and 0x80 == 0 -> CompressedUnsigned(first, 1)
                    first and 0xc0 == 0x80 -> {
                        val second = readByte()
                        val value = (first and 0x3f) shl 8 or second
                        if (enforceUnsignedCanonicalForm && value < 0x80) {
                            malformed("$description has a non-canonical compressed integer for $part")
                        }
                        CompressedUnsigned(value, 2)
                    }
                    first and 0xe0 == 0xc0 -> {
                        val second = readByte()
                        val third = readByte()
                        val fourth = readByte()
                        val value =
                            (first and 0x1f) shl 24 or
                                    (second shl 16) or
                                    (third shl 8) or
                                    fourth
                        if (enforceUnsignedCanonicalForm && value < 0x4000) {
                            malformed("$description has a non-canonical compressed integer for $part")
                        }
                        CompressedUnsigned(value, 4)
                    }
                    else -> malformed("$description has an invalid compressed integer for $part")
                }
            }

            private fun readCompressedSigned(part: String): Int {
                val encoded = readCompressedUnsignedWithWidth(
                    part,
                    enforceUnsignedCanonicalForm = false,
                )
                val shifted = encoded.value ushr 1
                if (encoded.value and 1 == 0) return shifted
                val signBits = when (encoded.width) {
                    1 -> -0x40
                    2 -> -0x2000
                    4 -> -0x1000_0000
                    else -> error("Unexpected compressed integer width ${encoded.width}")
                }
                val value = shifted or signBits
                if (encoded.width == 2 && value in -0x40 until 0x40 ||
                    encoded.width == 4 && value in -0x2000 until 0x2000
                ) {
                    malformed("$description has a non-canonical compressed signed integer for $part")
                }
                return value
            }

            private fun ensureCollectionFits(count: Int, part: String) {
                if (count > bytes.size - position) {
                    malformed("$description declares too many $part ($count)")
                }
            }

            private fun primitive(type: DotNetClrPrimitiveType): DotNetClrTypeSignature =
                DotNetClrTypeSignature.Primitive(type)

            private fun peekByte(): Int =
                if (position < bytes.size) bytes[position].toInt() and 0xff else -1

            private fun readByte(): Int {
                if (position >= bytes.size) malformed("$description signature is truncated")
                return bytes[position++].toInt() and 0xff
            }
        }

        private fun decodeCodedHandle(
            value: Long,
            tagBits: Int,
            tablesByTag: IntArray,
            metadataTables: MetadataStream,
            description: String,
        ): DotNetClrMetadataHandle? {
            if (value == 0L) return null
            val tagMask = (1 shl tagBits) - 1
            val tag = (value and tagMask.toLong()).toInt()
            val table = tablesByTag.getOrNull(tag)
                ?: malformed("$description has invalid tag $tag")
            return metadataHandle(table, value ushr tagBits, metadataTables, description)
        }

        private fun metadataHandle(
            table: Int,
            row: Long,
            metadataTables: MetadataStream,
            description: String,
        ): DotNetClrMetadataHandle {
            val location = locateMetadataTable(metadataTables, table)
                ?: malformed("$description refers to absent metadata table $table")
            if (row !in 1..location.rowCount) {
                malformed("$description refers to invalid row $row in metadata table $table")
            }
            if (row > 0x00ff_ffffL) malformed("$description row $row does not fit a metadata token")
            return DotNetClrMetadataHandle(table, row.toInt())
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

        private fun readBlobHeap(blobs: MetadataStream?, index: Long): ByteArray {
            if (index == 0L) return ByteArray(0)
            val entry = locateBlobHeapEntry(blobs, index)
            if (entry.size > Int.MAX_VALUE.toLong()) {
                malformed("metadata blob at index $index is too large")
            }
            return readBytes(entry.offset, entry.size.toInt())
        }

        private fun readBlobHeapSize(blobs: MetadataStream?, index: Long): Long =
            if (index == 0L) 0 else locateBlobHeapEntry(blobs, index).size

        private fun locateBlobHeapEntry(
            blobs: MetadataStream?,
            index: Long,
        ): BlobHeapEntry {
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
            return BlobHeapEntry(
                offset = offset + headerSize,
                size = contentSize,
            )
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
            NESTED_CLASS_TABLE -> tableIndexSize(TYPE_DEF_TABLE) * 2
            GENERIC_PARAM_TABLE -> 4 + typeOrMethodDefIndexSize + stringIndexSize
            METHOD_SPEC_TABLE -> methodDefOrRefIndexSize + blobIndexSize
            GENERIC_PARAM_CONSTRAINT_TABLE -> tableIndexSize(GENERIC_PARAM_TABLE) + typeDefOrRefIndexSize
            else -> error("CLR metadata reader requires unsupported metadata table $table")
        }

        val resolutionScopeIndexSize
            get() = codedIndexSize(2, 0, 26, 35, 1)
        val typeDefOrRefIndexSize
            get() = codedIndexSize(2, 2, 1, 27)
        val memberRefParentIndexSize
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
        val hasSemanticsIndexSize
            get() = codedIndexSize(1, 20, 23)
        val typeOrMethodDefIndexSize
            get() = codedIndexSize(1, TYPE_DEF_TABLE, METHOD_DEF_TABLE)
        private val methodDefOrRefIndexSize
            get() = codedIndexSize(1, 6, 10)
        private val memberForwardedIndexSize
            get() = codedIndexSize(1, 4, 6)

        fun tableIndexSize(table: Int): Int =
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

    private data class MetadataImage(
        val assemblyIdentity: DotNetManagedAssemblyIdentity,
        val sections: List<Section>,
        val tables: MetadataStream,
        val strings: MetadataStream,
        val blobs: MetadataStream?,
        val resourcesRva: Long,
        val resourcesSize: Long,
    )

    private data class MetadataStream(val offset: Long, val size: Long)

    private data class BlobHeapEntry(val offset: Long, val size: Long)

    private data class CompressedUnsigned(val value: Int, val width: Int)

    private data class ManifestResourceRow(
        val offset: Long,
        val attributes: Int,
        val implementation: Long,
    )

    private data class PropertyOwnerRun(
        val owner: DotNetClrMetadataHandle,
        val propertyStart: Long,
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
    private const val MODULE_TABLE = 0
    private const val TYPE_REF_TABLE = 1
    private const val TYPE_DEF_TABLE = 2
    private const val FIELD_TABLE = 4
    private const val METHOD_DEF_TABLE = 6
    private const val MEMBER_REF_TABLE = 10
    private const val EVENT_TABLE = 20
    private const val PROPERTY_MAP_TABLE = 21
    private const val PROPERTY_TABLE = 23
    private const val METHOD_SEMANTICS_TABLE = 24
    private const val MODULE_REF_TABLE = 26
    private const val TYPE_SPEC_TABLE = 27
    private const val MANIFEST_RESOURCE_TABLE = 40
    private const val NESTED_CLASS_TABLE = 41
    private const val GENERIC_PARAM_TABLE = 42
    private const val METHOD_SPEC_TABLE = 43
    private const val GENERIC_PARAM_CONSTRAINT_TABLE = 44
    private const val ASSEMBLY_TABLE = 32
    private const val FILE_TABLE = 38
    private const val ASSEMBLY_REF_TABLE = 35
    private const val EXPORTED_TYPE_TABLE = 39
    private const val STRING_HEAP_LARGE = 0x1
    private const val GUID_HEAP_LARGE = 0x2
    private const val BLOB_HEAP_LARGE = 0x4
    private const val ASSEMBLY_PUBLIC_KEY_FLAG = 0x1L
    private const val METHOD_ACCESS_MASK = 0x7
    private const val METHOD_STATIC_ATTRIBUTE = 0x10
    private const val FIELD_ACCESS_MASK = 0x0007
    private const val FIELD_STATIC_ATTRIBUTE = 0x0010
    private const val FIELD_INIT_ONLY_ATTRIBUTE = 0x0020
    private const val FIELD_LITERAL_ATTRIBUTE = 0x0040
    private const val FIELD_SPECIAL_NAME_ATTRIBUTE = 0x0200
    private const val FIELD_RUNTIME_SPECIAL_NAME_ATTRIBUTE = 0x0400
    private const val FIELD_ATTRIBUTE_MASK = 0xb7f7
    private const val PROPERTY_ATTRIBUTE_MASK = 0x1600
    private const val METHOD_SEMANTICS_SETTER = 0x0001
    private const val METHOD_SEMANTICS_GETTER = 0x0002
    private const val METHOD_SEMANTICS_OTHER = 0x0004
    private const val METHOD_SEMANTICS_ADD_ON = 0x0008
    private const val METHOD_SEMANTICS_REMOVE_ON = 0x0010
    private const val METHOD_SEMANTICS_FIRE = 0x0020
    private const val GENERIC_PARAMETER_VARIANCE_MASK = 0x0003
    private const val GENERIC_PARAMETER_ATTRIBUTE_MASK = 0x003f
    private const val MAX_SIGNATURE_DEPTH = 128
    private const val MAX_SIGNATURE_BLOB_SIZE = 1024 * 1024
    private const val ELEMENT_TYPE_VOID = 0x01
    private const val ELEMENT_TYPE_BOOLEAN = 0x02
    private const val ELEMENT_TYPE_CHAR = 0x03
    private const val ELEMENT_TYPE_I1 = 0x04
    private const val ELEMENT_TYPE_U1 = 0x05
    private const val ELEMENT_TYPE_I2 = 0x06
    private const val ELEMENT_TYPE_U2 = 0x07
    private const val ELEMENT_TYPE_I4 = 0x08
    private const val ELEMENT_TYPE_U4 = 0x09
    private const val ELEMENT_TYPE_I8 = 0x0a
    private const val ELEMENT_TYPE_U8 = 0x0b
    private const val ELEMENT_TYPE_R4 = 0x0c
    private const val ELEMENT_TYPE_R8 = 0x0d
    private const val ELEMENT_TYPE_STRING = 0x0e
    private const val ELEMENT_TYPE_PTR = 0x0f
    private const val ELEMENT_TYPE_BYREF = 0x10
    private const val ELEMENT_TYPE_VALUETYPE = 0x11
    private const val ELEMENT_TYPE_CLASS = 0x12
    private const val ELEMENT_TYPE_VAR = 0x13
    private const val ELEMENT_TYPE_ARRAY = 0x14
    private const val ELEMENT_TYPE_GENERICINST = 0x15
    private const val ELEMENT_TYPE_TYPEDBYREF = 0x16
    private const val ELEMENT_TYPE_I = 0x18
    private const val ELEMENT_TYPE_U = 0x19
    private const val ELEMENT_TYPE_FNPTR = 0x1b
    private const val ELEMENT_TYPE_OBJECT = 0x1c
    private const val ELEMENT_TYPE_SZARRAY = 0x1d
    private const val ELEMENT_TYPE_MVAR = 0x1e
    private const val ELEMENT_TYPE_CMOD_REQD = 0x1f
    private const val ELEMENT_TYPE_CMOD_OPT = 0x20
    private const val ELEMENT_TYPE_SENTINEL = 0x41
    private const val SIGNATURE_CALLING_CONVENTION_MASK = 0x0f
    private const val SIGNATURE_DEFAULT = 0x00
    private const val SIGNATURE_C = 0x01
    private const val SIGNATURE_STDCALL = 0x02
    private const val SIGNATURE_THISCALL = 0x03
    private const val SIGNATURE_FASTCALL = 0x04
    private const val SIGNATURE_VARARG = 0x05
    private const val SIGNATURE_FIELD = 0x06
    private const val SIGNATURE_UNMANAGED = 0x09
    private const val SIGNATURE_NATIVE_VARARG = 0x0b
    private const val SIGNATURE_GENERIC = 0x10
    private const val SIGNATURE_HAS_THIS = 0x20
    private const val SIGNATURE_EXPLICIT_THIS = 0x40
    private const val SIGNATURE_RESERVED_MASK = 0x80
    private const val SIGNATURE_PROPERTY = 0x08
    private const val SIGNATURE_PROPERTY_MASK = 0xdf
    private const val UINT16_INDEX_LIMIT = 1L shl 16
    private const val UINT16_SIZE = 2L
    private const val UINT32_SIZE = 4L
    private const val UINT64_SIZE = 8L
    private const val MAX_SECTION_COUNT = 1024
    private const val MAX_STREAM_COUNT = 64
    private const val MAX_STREAM_NAME_BYTES = 32
    private const val MAX_MANAGED_RESOURCE_SIZE = 512L * 1024 * 1024
}
