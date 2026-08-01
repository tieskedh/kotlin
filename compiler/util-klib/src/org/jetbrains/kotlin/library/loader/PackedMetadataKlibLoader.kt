/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.library.loader

import org.jetbrains.kotlin.library.KLIB_PROPERTY_ABI_VERSION
import org.jetbrains.kotlin.library.KLIB_PROPERTY_BUILTINS_PLATFORM
import org.jetbrains.kotlin.library.KLIB_PROPERTY_METADATA_VERSION
import org.jetbrains.kotlin.library.KlibAttributes
import org.jetbrains.kotlin.library.KlibComponent
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.KotlinLibraryVersioning
import org.jetbrains.kotlin.library.builtInsPlatform
import org.jetbrains.kotlin.library.components.KlibMetadataComponent
import org.jetbrains.kotlin.library.components.KlibMetadataConstants.KLIB_METADATA_FILE_EXTENSION_WITH_DOT
import org.jetbrains.kotlin.library.components.KlibIrComponent
import org.jetbrains.kotlin.library.components.KlibIrConstants.KLIB_IR_BODIES_FILE_NAME
import org.jetbrains.kotlin.library.components.KlibIrConstants.KLIB_IR_DEBUG_INFO_FILE_NAME
import org.jetbrains.kotlin.library.components.KlibIrConstants.KLIB_IR_DECLARATIONS_FILE_NAME
import org.jetbrains.kotlin.library.components.KlibIrConstants.KLIB_IR_FILE_ENTRIES_FILE_NAME
import org.jetbrains.kotlin.library.components.KlibIrConstants.KLIB_IR_FILES_FILE_NAME
import org.jetbrains.kotlin.library.components.KlibIrConstants.KLIB_IR_FOLDER_NAME
import org.jetbrains.kotlin.library.components.KlibIrConstants.KLIB_IR_INLINABLE_FUNCTIONS_FOLDER_NAME
import org.jetbrains.kotlin.library.components.KlibIrConstants.KLIB_IR_SIGNATURES_FILE_NAME
import org.jetbrains.kotlin.library.components.KlibIrConstants.KLIB_IR_STRINGS_FILE_NAME
import org.jetbrains.kotlin.library.components.KlibIrConstants.KLIB_IR_TYPES_FILE_NAME
import org.jetbrains.kotlin.library.impl.DeclarationId
import org.jetbrains.kotlin.library.impl.DeclarationIdMultiTableReader
import org.jetbrains.kotlin.library.impl.IrArrayReader
import org.jetbrains.kotlin.library.impl.IrMultiArrayReader
import org.jetbrains.kotlin.library.readKonanLibraryVersioning
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipInputStream

/**
 * Loads a metadata-only packed KLIB whose physical container is owned by another library format.
 *
 * The returned [KotlinLibrary.path] is [libraryPath], not a synthetic archive path. This lets
 * container-aware targets retain their native artifact identity while reusing the ordinary KLIB
 * metadata component and FIR deserialization machinery.
 *
 * Only the manifest and metadata component are retained. Other packed entries are validated and
 * ignored, so targets that need serialized IR or custom components must use the regular KLIB
 * loader or provide a component-complete container adapter.
 *
 * @throws IllegalArgumentException if [packedKlib] is not a canonical, bounded metadata KLIB.
 */
fun loadPackedMetadataKlib(
    libraryPath: Path,
    packedKlib: ByteArray,
): KotlinLibrary = loadPackedKlib(libraryPath, packedKlib, retainIr = false, description = "metadata KLIB")

/**
 * Loads a component-complete packed KLIB whose physical container is owned by another library format.
 *
 * In addition to the manifest and metadata retained by [loadPackedMetadataKlib], this adapter exposes
 * both the ordinary and prepared-inlinable-functions IR components when present. A present IR component
 * must contain every mandatory table; malformed partial components are rejected while the container is
 * loaded instead of failing later during IR deserialization.
 *
 * The returned [KotlinLibrary.path] remains [libraryPath]. Unknown future components are validated as ZIP
 * entries but ignored.
 *
 * @throws IllegalArgumentException if [packedKlib] is not a canonical, bounded KLIB.
 */
fun loadPackedKlib(
    libraryPath: Path,
    packedKlib: ByteArray,
): KotlinLibrary = loadPackedKlib(libraryPath, packedKlib, retainIr = true, description = "KLIB")

private fun loadPackedKlib(
    libraryPath: Path,
    packedKlib: ByteArray,
    retainIr: Boolean,
    description: String,
): KotlinLibrary {
    try {
        return PackedKlib(libraryPath, PackedKlibArchive(packedKlib, retainIr))
    } catch (exception: PackedMetadataKlibFormatException) {
        throw IllegalArgumentException(
            "Invalid packed $description '$libraryPath': ${exception.message}",
            exception,
        )
    } catch (exception: Exception) {
        throw IllegalArgumentException(
            "Invalid packed $description '$libraryPath': ${exception.message ?: exception::class.java.simpleName}",
            exception,
        )
    }
}

private class PackedKlib(
    override val path: Path,
    archive: PackedKlibArchive,
) : KotlinLibrary {
    override val manifestProperties: Properties = Properties().apply {
        InputStreamReader(ByteArrayInputStream(archive.manifest), Charsets.UTF_8).use(::load)
    }
    override val versions: KotlinLibraryVersioning = manifestProperties.readKonanLibraryVersioning()
    override val attributes = KlibAttributes()

    private val metadata = PackedMetadataComponent(archive.metadataEntries)
    private val mainIr = archive.mainIrEntries?.let(::PackedIrComponent)
    private val inlinableFunctionsIr = archive.inlinableFunctionsIrEntries?.let(::PackedIrComponent)

    override fun <KC : KlibComponent> getComponent(kind: KlibComponent.Kind<KC, *>): KC? {
        val component = when (kind) {
            KlibMetadataComponent -> metadata
            KlibIrComponent.Kind.Main -> mainIr
            KlibIrComponent.Kind.InlinableFunctions -> inlinableFunctionsIr
            else -> null
        }
        @Suppress("UNCHECKED_CAST")
        return component as KC?
    }

    override fun toString() = listOfNotNull(
        path,
        versions.abiVersion?.let { "$KLIB_PROPERTY_ABI_VERSION=$it" },
        versions.metadataVersion?.let { "$KLIB_PROPERTY_METADATA_VERSION=$it" },
        builtInsPlatform?.let { "$KLIB_PROPERTY_BUILTINS_PLATFORM=${it.name}" },
    ).joinToString("\n")
}

private class PackedIrComponent(entries: Map<String, ByteArray>) : KlibIrComponent {
    private val irFiles = IrArrayReader(entries.getValue(KLIB_IR_FILES_FILE_NAME))
    private val irFileEntries = entries[KLIB_IR_FILE_ENTRIES_FILE_NAME]?.let(::IrMultiArrayReader)
    private val declarations = DeclarationIdMultiTableReader(entries.getValue(KLIB_IR_DECLARATIONS_FILE_NAME))
    private val bodies = IrMultiArrayReader(entries.getValue(KLIB_IR_BODIES_FILE_NAME))
    private val types = IrMultiArrayReader(entries.getValue(KLIB_IR_TYPES_FILE_NAME))
    private val signatures = IrMultiArrayReader(entries.getValue(KLIB_IR_SIGNATURES_FILE_NAME))
    private val signatureDebugInfos = entries[KLIB_IR_DEBUG_INFO_FILE_NAME]?.let(::IrMultiArrayReader)
    private val stringLiterals = IrMultiArrayReader(entries.getValue(KLIB_IR_STRINGS_FILE_NAME))

    override val irFileCount: Int
        get() = irFiles.entryCount()

    override fun irFile(index: Int) = irFiles.tableItemBytes(index)
    override fun irFileEntry(index: Int, fileIndex: Int) = irFileEntries?.tableItemBytes(fileIndex, index)
    override fun declaration(index: Int, fileIndex: Int) = declarations.tableItemBytes(fileIndex, DeclarationId(index))
    override fun body(index: Int, fileIndex: Int) = bodies.tableItemBytes(fileIndex, index)
    override fun type(index: Int, fileIndex: Int) = types.tableItemBytes(fileIndex, index)
    override fun signature(index: Int, fileIndex: Int) = signatures.tableItemBytes(fileIndex, index)
    override fun signatureDebugInfo(index: Int, fileIndex: Int) = signatureDebugInfos?.tableItemBytes(fileIndex, index)
    override fun stringLiteral(index: Int, fileIndex: Int) = stringLiterals.tableItemBytes(fileIndex, index)

    override fun irFileEntries(fileIndex: Int) = irFileEntries?.tableItemBytes(fileIndex)
    override fun declarations(fileIndex: Int) = declarations.tableItemBytes(fileIndex)
    override fun bodies(fileIndex: Int) = bodies.tableItemBytes(fileIndex)
    override fun types(fileIndex: Int) = types.tableItemBytes(fileIndex)
    override fun signatures(fileIndex: Int) = signatures.tableItemBytes(fileIndex)
    override fun stringLiterals(fileIndex: Int) = stringLiterals.tableItemBytes(fileIndex)
}

private class PackedMetadataComponent(
    private val entries: Map<String, ByteArray>,
) : KlibMetadataComponent {
    override val moduleHeaderData: ByteArray
        get() = checkNotNull(entries[MODULE_HEADER_ENTRY]) {
            "Packed metadata KLIB has no '$MODULE_HEADER_ENTRY'"
        }.copyOf()

    override fun getPackageFragmentNames(packageFqName: String): Set<String> {
        val prefix = packageFragmentDirectory(packageFqName)
        return entries.keys.asSequence()
            .filter { entryName ->
                entryName.startsWith(prefix) &&
                        '/' !in entryName.substring(prefix.length) &&
                        entryName.endsWith(KLIB_METADATA_FILE_EXTENSION_WITH_DOT)
            }
            .map { entryName ->
                entryName.substring(prefix.length)
                    .removeSuffix(KLIB_METADATA_FILE_EXTENSION_WITH_DOT)
            }
            .toSortedSet()
    }

    override fun getPackageFragment(packageFqName: String, fragmentName: String): ByteArray {
        val entryName = packageFragmentDirectory(packageFqName) +
                fragmentName + KLIB_METADATA_FILE_EXTENSION_WITH_DOT
        return checkNotNull(entries[entryName]) {
            "Packed metadata KLIB has no '$entryName'"
        }.copyOf()
    }
}

private class PackedKlibArchive(packedKlib: ByteArray, retainIr: Boolean) {
    val manifest: ByteArray
    val metadataEntries: Map<String, ByteArray>
    val mainIrEntries: Map<String, ByteArray>?
    val inlinableFunctionsIrEntries: Map<String, ByteArray>?

    init {
        val retainedEntries = linkedMapOf<String, ByteArray>()
        val seenEntryNames = hashSetOf<String>()
        val localEntryNames = mutableListOf<String>()
        val expansionBudget = expansionBudget(packedKlib.size)
        val copyBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var expandedSize = 0L
        var entryCount = 0

        try {
            ZipInputStream(ByteArrayInputStream(packedKlib)).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ENTRY_COUNT) {
                        malformed("archive contains more than $MAX_ENTRY_COUNT entries")
                    }
                    validateEntryName(entry.name, entry.isDirectory)
                    if (!seenEntryNames.add(entry.name)) {
                        malformed("archive contains duplicate entry '${entry.name}'")
                    }
                    localEntryNames += entry.name

                    val retain = !entry.isDirectory && (
                            entry.name == MANIFEST_ENTRY ||
                                    entry.name.startsWith(METADATA_DIRECTORY) ||
                                    retainIr && entry.name.startsWith(MAIN_IR_DIRECTORY) ||
                                    retainIr && entry.name.startsWith(INLINABLE_FUNCTIONS_IR_DIRECTORY)
                            )
                    val output = if (retain) ByteArrayOutputStream() else null
                    var entrySize = 0L
                    while (true) {
                        val read = archive.read(copyBuffer)
                        if (read < 0) break
                        entrySize += read
                        expandedSize += read
                        if (entrySize > expansionBudget) {
                            malformed("entry '${entry.name}' exceeds the $expansionBudget-byte expansion budget")
                        }
                        if (expandedSize > expansionBudget) {
                            malformed("archive exceeds the $expansionBudget-byte expansion budget")
                        }
                        output?.write(copyBuffer, 0, read)
                    }
                    if (entry.size >= 0 && entry.size != entrySize) {
                        malformed(
                            "entry '${entry.name}' declares ${entry.size} bytes but contains $entrySize bytes"
                        )
                    }
                    if (output != null) retainedEntries[entry.name] = output.toByteArray()
                    archive.closeEntry()
                }
            }
        } catch (exception: PackedMetadataKlibFormatException) {
            throw exception
        } catch (exception: Exception) {
            malformed(exception.message ?: "archive cannot be decoded", exception)
        }
        validateCentralDirectory(packedKlib, localEntryNames)

        manifest = retainedEntries.remove(MANIFEST_ENTRY)
            ?: malformed("archive has no '$MANIFEST_ENTRY'")
        if (MODULE_HEADER_ENTRY !in retainedEntries) {
            malformed("archive has no '$MODULE_HEADER_ENTRY'")
        }
        metadataEntries = retainedEntries.filterKeys { it.startsWith(METADATA_DIRECTORY) }
        mainIrEntries = retainedEntries.irComponentEntries(MAIN_IR_DIRECTORY)
        inlinableFunctionsIrEntries = retainedEntries.irComponentEntries(INLINABLE_FUNCTIONS_IR_DIRECTORY)
    }
}

private fun Map<String, ByteArray>.irComponentEntries(directory: String): Map<String, ByteArray>? {
    val entries = asSequence()
        .filter { (name, _) -> name.startsWith(directory) }
        .associate { (name, bytes) -> name.removePrefix(directory) to bytes }
    if (entries.isEmpty()) return null

    val missingFiles = MANDATORY_IR_FILES - entries.keys
    if (missingFiles.isNotEmpty()) {
        malformed("IR component '$directory' is incomplete; missing ${missingFiles.sorted().joinToString()}")
    }
    return entries
}

private fun validateCentralDirectory(
    packedKlib: ByteArray,
    localEntryNames: List<String>,
) {
    val endOffset = findEndOfCentralDirectory(packedKlib)
    val diskNumber = packedKlib.readU2(endOffset + END_DISK_NUMBER_OFFSET)
    val centralDirectoryDisk = packedKlib.readU2(endOffset + END_CENTRAL_DIRECTORY_DISK_OFFSET)
    if (diskNumber != 0 || centralDirectoryDisk != 0) {
        malformed("multi-disk ZIP archives are not supported")
    }

    val legacyEntryCountOnDisk = packedKlib.readU2(endOffset + END_ENTRY_COUNT_ON_DISK_OFFSET)
    val legacyEntryCount = packedKlib.readU2(endOffset + END_ENTRY_COUNT_OFFSET)
    val legacyCentralSize = packedKlib.readU4(endOffset + END_CENTRAL_SIZE_OFFSET)
    val legacyCentralOffset = packedKlib.readU4(endOffset + END_CENTRAL_OFFSET_OFFSET)
    val usesZip64 = legacyEntryCountOnDisk == ZIP64_U2_SENTINEL ||
            legacyEntryCount == ZIP64_U2_SENTINEL ||
            legacyCentralSize == ZIP64_U4_SENTINEL ||
            legacyCentralOffset == ZIP64_U4_SENTINEL
    val centralDirectory = if (usesZip64) {
        readZip64CentralDirectory(packedKlib, endOffset)
    } else {
        if (legacyEntryCountOnDisk != legacyEntryCount) {
            malformed("central-directory entry counts do not agree")
        }
        CentralDirectory(
            entryCount = legacyEntryCount.toLong(),
            offset = legacyCentralOffset,
            size = legacyCentralSize,
            expectedEnd = endOffset.toLong(),
        )
    }
    if (centralDirectory.entryCount > MAX_ENTRY_COUNT) {
        malformed("archive contains more than $MAX_ENTRY_COUNT entries")
    }
    if (centralDirectory.entryCount != localEntryNames.size.toLong()) {
        malformed(
            "central directory declares ${centralDirectory.entryCount} entries, " +
                    "but the archive contains ${localEntryNames.size}"
        )
    }
    val centralEnd = checkedAdd(
        centralDirectory.offset,
        centralDirectory.size,
        "central-directory range",
    )
    if (centralEnd != centralDirectory.expectedEnd) {
        malformed("central directory is not contiguous with its end record")
    }
    packedKlib.checkRange(centralDirectory.offset, centralDirectory.size, "central directory")

    var position = centralDirectory.offset
    val centralEntryNames = ArrayList<String>(centralDirectory.entryCount.toInt())
    repeat(centralDirectory.entryCount.toInt()) {
        packedKlib.checkRange(position, CENTRAL_HEADER_SIZE, "central-directory header")
        if (packedKlib.readU4(position) != CENTRAL_HEADER_SIGNATURE) {
            malformed("central directory contains an invalid entry header")
        }
        val nameLength = packedKlib.readU2(position + CENTRAL_NAME_LENGTH_OFFSET)
        val extraLength = packedKlib.readU2(position + CENTRAL_EXTRA_LENGTH_OFFSET)
        val commentLength = packedKlib.readU2(position + CENTRAL_COMMENT_LENGTH_OFFSET)
        val variableSize = nameLength.toLong() + extraLength + commentLength
        packedKlib.checkRange(
            position + CENTRAL_HEADER_SIZE,
            variableSize,
            "central-directory entry data",
        )
        val nameOffset = position + CENTRAL_HEADER_SIZE
        val name = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(packedKlib, nameOffset.toInt(), nameLength))
                .toString()
        } catch (exception: Exception) {
            malformed("central directory contains an invalid UTF-8 entry name", exception)
        }
        centralEntryNames += name
        position += CENTRAL_HEADER_SIZE + variableSize
    }
    if (position != centralEnd) {
        malformed("central-directory size does not match its entries")
    }
    if (centralEntryNames.sorted() != localEntryNames.sorted()) {
        malformed("central-directory entries do not match the local archive entries")
    }
}

private fun findEndOfCentralDirectory(packedKlib: ByteArray): Int {
    val firstCandidate = packedKlib.size - END_HEADER_SIZE
    val minimumCandidate = (packedKlib.size - END_HEADER_SIZE - MAX_ZIP_COMMENT_SIZE).coerceAtLeast(0)
    for (offset in firstCandidate downTo minimumCandidate) {
        if (packedKlib.readU4OrNull(offset) != END_HEADER_SIGNATURE) continue
        val commentLength = packedKlib.readU2(offset + END_COMMENT_LENGTH_OFFSET)
        if (offset + END_HEADER_SIZE + commentLength == packedKlib.size) return offset
    }
    malformed("archive has no valid end-of-central-directory record")
}

private fun readZip64CentralDirectory(
    packedKlib: ByteArray,
    legacyEndOffset: Int,
): CentralDirectory {
    val locatorOffset = legacyEndOffset.toLong() - ZIP64_LOCATOR_SIZE
    packedKlib.checkRange(locatorOffset, ZIP64_LOCATOR_SIZE, "ZIP64 locator")
    if (packedKlib.readU4(locatorOffset) != ZIP64_LOCATOR_SIGNATURE) {
        malformed("ZIP64 archive has no locator")
    }
    if (
        packedKlib.readU4(locatorOffset + ZIP64_LOCATOR_DISK_OFFSET) != 0L ||
        packedKlib.readU4(locatorOffset + ZIP64_LOCATOR_DISK_COUNT_OFFSET) != 1L
    ) {
        malformed("multi-disk ZIP64 archives are not supported")
    }
    val zip64EndOffset = packedKlib.readU8(locatorOffset + ZIP64_LOCATOR_END_OFFSET)
    packedKlib.checkRange(zip64EndOffset, ZIP64_END_MINIMUM_SIZE, "ZIP64 end record")
    if (packedKlib.readU4(zip64EndOffset) != ZIP64_END_SIGNATURE) {
        malformed("ZIP64 locator does not reference an end record")
    }
    val recordBodySize = packedKlib.readU8(zip64EndOffset + ZIP64_END_BODY_SIZE_OFFSET)
    val recordSize = checkedAdd(ZIP64_END_PREFIX_SIZE, recordBodySize, "ZIP64 end-record size")
    packedKlib.checkRange(zip64EndOffset, recordSize, "ZIP64 end record")
    if (checkedAdd(zip64EndOffset, recordSize, "ZIP64 end-record range") != locatorOffset) {
        malformed("ZIP64 end record is not contiguous with its locator")
    }
    if (
        packedKlib.readU4(zip64EndOffset + ZIP64_END_DISK_OFFSET) != 0L ||
        packedKlib.readU4(zip64EndOffset + ZIP64_END_CENTRAL_DISK_OFFSET) != 0L
    ) {
        malformed("multi-disk ZIP64 archives are not supported")
    }
    val entryCountOnDisk = packedKlib.readU8(zip64EndOffset + ZIP64_END_ENTRY_COUNT_ON_DISK_OFFSET)
    val entryCount = packedKlib.readU8(zip64EndOffset + ZIP64_END_ENTRY_COUNT_OFFSET)
    if (entryCountOnDisk != entryCount) {
        malformed("ZIP64 central-directory entry counts do not agree")
    }
    return CentralDirectory(
        entryCount = entryCount,
        offset = packedKlib.readU8(zip64EndOffset + ZIP64_END_CENTRAL_OFFSET_OFFSET),
        size = packedKlib.readU8(zip64EndOffset + ZIP64_END_CENTRAL_SIZE_OFFSET),
        expectedEnd = zip64EndOffset,
    )
}

private fun ByteArray.readU2(offset: Int): Int = readU2(offset.toLong())

private fun ByteArray.readU2(offset: Long): Int {
    checkRange(offset, 2, "ZIP 16-bit value")
    val index = offset.toInt()
    return this[index].toInt() and 0xff or ((this[index + 1].toInt() and 0xff) shl 8)
}

private fun ByteArray.readU4(offset: Int): Long = readU4(offset.toLong())

private fun ByteArray.readU4(offset: Long): Long {
    checkRange(offset, 4, "ZIP 32-bit value")
    val start = offset.toInt()
    var value = 0L
    repeat(4) { index ->
        value = value or ((this[start + index].toLong() and 0xff) shl (index * 8))
    }
    return value
}

private fun ByteArray.readU4OrNull(offset: Int): Long? {
    if (offset < 0 || offset > size - 4) return null
    var value = 0L
    repeat(4) { index ->
        value = value or ((this[offset + index].toLong() and 0xff) shl (index * 8))
    }
    return value
}

private fun ByteArray.readU8(offset: Long): Long {
    checkRange(offset, 8, "ZIP 64-bit value")
    val start = offset.toInt()
    var value = 0L
    repeat(8) { index ->
        value = value or ((this[start + index].toLong() and 0xff) shl (index * 8))
    }
    if (value < 0) malformed("ZIP 64-bit value is too large")
    return value
}

private fun ByteArray.checkRange(offset: Long, length: Long, description: String) {
    if (offset < 0 || length < 0 || offset > size.toLong() || length > size.toLong() - offset) {
        malformed("$description is outside the archive")
    }
}

private fun checkedAdd(left: Long, right: Long, description: String): Long {
    if (left < 0 || right < 0 || left > Long.MAX_VALUE - right) {
        malformed("$description overflows")
    }
    return left + right
}

private data class CentralDirectory(
    val entryCount: Long,
    val offset: Long,
    val size: Long,
    val expectedEnd: Long,
)

private fun packageFragmentDirectory(packageFqName: String): String =
    METADATA_DIRECTORY +
            if (packageFqName.isEmpty()) ROOT_PACKAGE_DIRECTORY else "$PACKAGE_DIRECTORY_PREFIX$packageFqName/"

private fun validateEntryName(name: String, isDirectory: Boolean) {
    if (name.isEmpty()) malformed("archive contains an empty entry name")
    if (name.startsWith('/') || '\\' in name || '\u0000' in name) {
        malformed("archive contains non-canonical entry '$name'")
    }
    val path = if (isDirectory) name.removeSuffix("/") else name
    if (path.isEmpty() || path.split('/').any { segment -> segment.isEmpty() || segment == "." || segment == ".." }) {
        malformed("archive contains non-canonical entry '$name'")
    }
    if (isDirectory != name.endsWith('/')) {
        malformed("archive entry '$name' has inconsistent directory metadata")
    }
}

private fun expansionBudget(packedSize: Int): Long {
    val scaledSize = packedSize.toLong().coerceAtMost(MAX_EXPANDED_SIZE / MAX_EXPANSION_RATIO) *
            MAX_EXPANSION_RATIO
    return scaledSize.coerceIn(MIN_EXPANDED_SIZE, MAX_EXPANDED_SIZE)
}

private fun malformed(message: String, cause: Throwable? = null): Nothing =
    throw PackedMetadataKlibFormatException(message, cause)

private class PackedMetadataKlibFormatException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

private const val MANIFEST_ENTRY = "default/manifest"
private const val METADATA_DIRECTORY = "default/linkdata/"
private const val MAIN_IR_DIRECTORY = "default/$KLIB_IR_FOLDER_NAME/"
private const val INLINABLE_FUNCTIONS_IR_DIRECTORY = "default/$KLIB_IR_INLINABLE_FUNCTIONS_FOLDER_NAME/"
private const val MODULE_HEADER_ENTRY = "${METADATA_DIRECTORY}module"
private const val ROOT_PACKAGE_DIRECTORY = "root_package/"
private const val PACKAGE_DIRECTORY_PREFIX = "package_"
private const val MAX_ENTRY_COUNT = 100_000
private const val MAX_EXPANSION_RATIO = 128L
private const val MIN_EXPANDED_SIZE = 64L * 1024 * 1024
private const val MAX_EXPANDED_SIZE = 1024L * 1024 * 1024
private const val END_HEADER_SIGNATURE = 0x06054b50L
private const val END_HEADER_SIZE = 22
private const val END_DISK_NUMBER_OFFSET = 4
private const val END_CENTRAL_DIRECTORY_DISK_OFFSET = 6
private const val END_ENTRY_COUNT_ON_DISK_OFFSET = 8
private const val END_ENTRY_COUNT_OFFSET = 10
private const val END_CENTRAL_SIZE_OFFSET = 12
private const val END_CENTRAL_OFFSET_OFFSET = 16
private const val END_COMMENT_LENGTH_OFFSET = 20
private const val MAX_ZIP_COMMENT_SIZE = 65_535
private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
private const val CENTRAL_HEADER_SIZE = 46L
private const val CENTRAL_NAME_LENGTH_OFFSET = 28L
private const val CENTRAL_EXTRA_LENGTH_OFFSET = 30L
private const val CENTRAL_COMMENT_LENGTH_OFFSET = 32L
private const val ZIP64_U2_SENTINEL = 0xffff
private const val ZIP64_U4_SENTINEL = 0xffffffffL
private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
private const val ZIP64_LOCATOR_SIZE = 20L
private const val ZIP64_LOCATOR_DISK_OFFSET = 4L
private const val ZIP64_LOCATOR_END_OFFSET = 8L
private const val ZIP64_LOCATOR_DISK_COUNT_OFFSET = 16L
private const val ZIP64_END_SIGNATURE = 0x06064b50L
private const val ZIP64_END_MINIMUM_SIZE = 56L
private const val ZIP64_END_PREFIX_SIZE = 12L
private const val ZIP64_END_BODY_SIZE_OFFSET = 4L
private const val ZIP64_END_DISK_OFFSET = 16L
private const val ZIP64_END_CENTRAL_DISK_OFFSET = 20L
private const val ZIP64_END_ENTRY_COUNT_ON_DISK_OFFSET = 24L
private const val ZIP64_END_ENTRY_COUNT_OFFSET = 32L
private const val ZIP64_END_CENTRAL_SIZE_OFFSET = 40L
private const val ZIP64_END_CENTRAL_OFFSET_OFFSET = 48L
private val MANDATORY_IR_FILES = setOf(
    KLIB_IR_FILES_FILE_NAME,
    KLIB_IR_DECLARATIONS_FILE_NAME,
    KLIB_IR_BODIES_FILE_NAME,
    KLIB_IR_TYPES_FILE_NAME,
    KLIB_IR_SIGNATURES_FILE_NAME,
    KLIB_IR_STRINGS_FILE_NAME,
)
