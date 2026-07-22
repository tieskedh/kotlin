/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli

import org.jetbrains.kotlin.backend.dotnet.DotNetDefaultArgumentDispatcher
import org.jetbrains.kotlin.backend.dotnet.DotNetCompanionInitialization
import org.jetbrains.kotlin.backend.dotnet.DotNetIlAssembler
import org.jetbrains.kotlin.backend.dotnet.DotNetInterfaceDefaultBodyPlacement
import org.jetbrains.kotlin.backend.dotnet.DotNetInterfaceDefaultPromotionView
import org.jetbrains.kotlin.backend.dotnet.DotNetFriendAssemblyIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetInterfaceDefaultImplementation
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryArtifact
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryAbiCodec
import org.jetbrains.kotlin.backend.dotnet.DotNetModernCSharpToolchain
import org.jetbrains.kotlin.backend.dotnet.DotNetObjectInstance
import org.jetbrains.kotlin.backend.dotnet.DotNetPhysicalDeclaration
import org.jetbrains.kotlin.backend.dotnet.DotNetPortablePhysicalAbiDifference
import org.jetbrains.kotlin.backend.dotnet.DotNetTarget
import org.jetbrains.kotlin.cli.common.CLICompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2DotNetCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2MetadataCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.cliArgument
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.dotnet.K2DotNetCompiler
import org.jetbrains.kotlin.cli.metadata.KotlinMetadataCompiler
import org.jetbrains.kotlin.library.KLIB_PROPERTY_MANUALLY_ALTERED_LANGUAGE_FEATURES
import org.jetbrains.kotlin.library.KLIB_PROPERTY_METADATA_FLAGS
import org.jetbrains.kotlin.library.KLIB_PROPERTY_NEW_COMPANION_INITIALIZATION
import org.jetbrains.kotlin.test.TestCaseWithTmpdir
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

class DotNetLibraryIntegrationTest : TestCaseWithTmpdir() {
    @Test
    fun testGenericInterfacePhysicalViewsRoundTrip() {
        val declarations = mapOf(
            "C:sample/Producer" to DotNetPhysicalDeclaration.Class(
                ownerPath = listOf("sample.Producer"),
                declaredOwnerPath = listOf("sample.Producer`1"),
                exactOwnerPath = listOf("sample.Producer\$Exact`1"),
            ),
            "C:sample/Consumer" to DotNetPhysicalDeclaration.Class(
                ownerPath = listOf("sample.Consumer"),
                declaredOwnerPath = listOf("sample.Consumer`1"),
            ),
            "C:sample/Counter" to DotNetPhysicalDeclaration.Class(
                ownerPath = listOf("sample.Counter"),
                companionInitialization = DotNetCompanionInitialization(
                    ownerPath = listOf("sample.Counter", "<CompanionStatics>"),
                    methodName = "<EnsureCompanionInitialized>",
                ),
                objectInstance = DotNetObjectInstance(
                    ownerPath = listOf("sample.Counter", "<CompanionStatics>"),
                    fieldName = "INSTANCE",
                ),
            ),
            "F:sample/increment" to DotNetPhysicalDeclaration.Function(
                ownerPath = listOf("sample.LibraryKt"),
                methodName = "Increment",
                isInstance = false,
            ),
            "F:sample/abstractWithDefaults" to DotNetPhysicalDeclaration.Function(
                ownerPath = listOf("sample.Contract"),
                methodName = "abstractWithDefaults",
                isInstance = true,
                defaultArgumentDispatcher = DotNetDefaultArgumentDispatcher(
                    ownerPath = listOf("sample.Contract", "<DefaultImpls>"),
                    methodName = "abstractWithDefaults\$default",
                ),
            ),
            "F:sample/defaultWithDefaults" to DotNetPhysicalDeclaration.Function(
                ownerPath = listOf("sample.Contract"),
                methodName = "defaultWithDefaults",
                isInstance = true,
                interfaceDefaultImplementation = DotNetInterfaceDefaultImplementation(
                    bodyPlacement = DotNetInterfaceDefaultBodyPlacement.HELPER_ONLY,
                    helperOwnerPath = listOf("sample.Contract", "<DefaultImpls>"),
                    helperMethodName = "defaultWithDefaults",
                ),
                defaultArgumentDispatcher = DotNetDefaultArgumentDispatcher(
                    ownerPath = listOf("sample.Contract", "<DefaultImpls>"),
                    methodName = "defaultWithDefaults\$default",
                ),
            ),
            "B:C:sample/Contract:F:sample/defaultWithDefaults:CANONICAL" to
                    DotNetPhysicalDeclaration.GenericInterfaceViewBridge(
                        ownerPath = listOf("sample.Contract"),
                        ownerLogicalKey = "C:sample/Contract",
                        inheritedLogicalMemberKey = "F:sample/defaultWithDefaults",
                        physicalView = DotNetInterfaceDefaultPromotionView.CANONICAL,
                        implementationMethodName = "<GenericInterfaceCanonicalBridge-defaultWithDefaults>",
                    ),
            "R:C:sample/Contract:F:sample/baseValue" to
                    DotNetPhysicalDeclaration.CovariantReturnBridge(
                        ownerPath = listOf("sample.Contract"),
                        ownerLogicalKey = "C:sample/Contract",
                        inheritedLogicalMemberKey = "F:sample/baseValue",
                        implementationMethodName = "<CovariantReturnBridge-baseValue>",
                    ),
            "W:C:sample/Consumer:F:sample/defaultWithDefaults:CANONICAL" to
                    DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder(
                        ownerPath = listOf("sample.Consumer"),
                        ownerLogicalKey = "C:sample/Consumer",
                        inheritedLogicalMemberKey = "F:sample/defaultWithDefaults",
                        physicalView = DotNetInterfaceDefaultPromotionView.CANONICAL,
                        implementationMethodName = "<InterfaceDefaultForwarder-defaultWithDefaults>",
                    ),
        )
        val properties = Properties().apply {
            setProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY, DotNetLibraryAbiCodec.ABI_VERSION)
            putAll(DotNetLibraryAbiCodec.encode(declarations))
        }

        assertEquals("12", properties.getProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY))
        assertEquals(declarations, DotNetLibraryAbiCodec.decode(properties))
        assertEquals(
            "be089ff358019a018b5e1ce2af85aedd",
            DotNetLibraryAbiCodec.logicalIdentityDigest("F:sample/foo|-123456789[0]"),
        )
        assertEquals(
            "faa734fbe159d9bc030a0dd498584bc5",
            DotNetLibraryAbiCodec.logicalIdentityDigest("C:kotlin.collections/List"),
        )
        val friendIdentities = setOf(
            DotNetFriendAssemblyIdentity("Unsigned.Consumer"),
            DotNetFriendAssemblyIdentity("Signed.Consumer", "00112233445566778899AABBCCDDEEFF"),
        )
        assertEquals(
            friendIdentities,
            DotNetLibraryAbiCodec.decodeFriendAssemblies(
                DotNetLibraryAbiCodec.encodeFriendAssemblies(friendIdentities)
            ),
        )
    }

    @Test
    fun testPortablePhysicalAbiComparisonRejectsMissingAndChangedBindings() {
        val portable = linkedMapOf(
            "C:sample/Box" to DotNetPhysicalDeclaration.Class(listOf("sample.Box")),
            "F:sample/read" to DotNetPhysicalDeclaration.Function(
                ownerPath = listOf("sample.LibraryKt"),
                methodName = "read",
                isInstance = false,
            ),
            "F:sample/withDefaults" to DotNetPhysicalDeclaration.Function(
                ownerPath = listOf("sample.Contract"),
                methodName = "withDefaults",
                isInstance = true,
                defaultArgumentDispatcher = DotNetDefaultArgumentDispatcher(
                    ownerPath = listOf("sample.Contract", "<DefaultImpls>"),
                    methodName = "withDefaults\$default",
                ),
            ),
            "W:C:sample/Box:F:sample/withDefaults:CANONICAL" to
                    DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder(
                        ownerPath = listOf("sample.Box"),
                        ownerLogicalKey = "C:sample/Box",
                        inheritedLogicalMemberKey = "F:sample/withDefaults",
                        physicalView = DotNetInterfaceDefaultPromotionView.CANONICAL,
                        implementationMethodName = "<InterfaceDefaultForwarder-withDefaults>",
                    ),
            "R:C:sample/Box:F:sample/read" to DotNetPhysicalDeclaration.CovariantReturnBridge(
                ownerPath = listOf("sample.Box"),
                ownerLogicalKey = "C:sample/Box",
                inheritedLogicalMemberKey = "F:sample/read",
                implementationMethodName = "<CovariantReturnBridge-read>",
            ),
        )
        val compatiblePlatform = portable.filterValues {
            it !is DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder &&
                    it !is DotNetPhysicalDeclaration.CovariantReturnBridge
        } + (
                "F:sample/runtimeOnly" to DotNetPhysicalDeclaration.Function(
                    ownerPath = listOf("sample.PlatformKt"),
                    methodName = "runtimeOnly",
                    isInstance = false,
                )
                )
        assertTrue(DotNetLibraryAbiCodec.portablePhysicalAbiDifferences(portable, compatiblePlatform).isEmpty())


        val changedFunction = DotNetPhysicalDeclaration.Function(
            ownerPath = listOf("sample.ChangedKt"),
            methodName = "read",
            isInstance = false,
        )
        val changedDispatcher = DotNetPhysicalDeclaration.Function(
            ownerPath = listOf("sample.Contract"),
            methodName = "withDefaults",
            isInstance = true,
            defaultArgumentDispatcher = DotNetDefaultArgumentDispatcher(
                ownerPath = listOf("sample.Contract", "<ChangedDefaultImpls>"),
                methodName = "withDefaults\$default",
            ),
        )
        val differences = DotNetLibraryAbiCodec.portablePhysicalAbiDifferences(
            portable,
            mapOf(
                "F:sample/read" to changedFunction,
                "F:sample/withDefaults" to changedDispatcher,
            ),
        )
        assertEquals(
            listOf(
                DotNetPortablePhysicalAbiDifference(
                    logicalKey = "C:sample/Box",
                    portableDeclaration = portable.getValue("C:sample/Box"),
                    platformDeclaration = null,
                ),
                DotNetPortablePhysicalAbiDifference(
                    logicalKey = "F:sample/read",
                    portableDeclaration = portable.getValue("F:sample/read"),
                    platformDeclaration = changedFunction,
                ),
                DotNetPortablePhysicalAbiDifference(
                    logicalKey = "F:sample/withDefaults",
                    portableDeclaration = portable.getValue("F:sample/withDefaults"),
                    platformDeclaration = changedDispatcher,
                ),
            ),
            differences,
        )
    }

    @Test
    fun testGenericInterfacesAcrossLibraryBoundary() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val librarySource = File(tmpdir, "generic-interface-library.kt").apply {
            writeText(
                """
                package cross

                interface Producer<out T> {
                    fun produce(): T
                }

                interface Consumer<in T> {
                    fun consume(value: T)
                }

                interface VariantCell<out T> {
                    var value: @UnsafeVariance T
                }

                class LibraryProducer(private val result: Int) : Producer<Int> {
                    override fun produce(): Int = result
                }

                class LibraryCell(override var value: Int) : VariantCell<Int>

                class LibraryIterator(private var next: Int) : Iterator<Int> {
                    override fun hasNext(): Boolean = next < 10

                    override fun next(): Int {
                        val result = next
                        next = next + 1
                        return result
                    }
                }

                class LibraryIterable(private val start: Int) : Iterable<Int> {
                    override fun iterator(): Iterator<Int> = LibraryIterator(start)
                }

                class LibraryCollectionIterator(private val value: Int) : Iterator<Int> {
                    private var available: Boolean = true

                    override fun hasNext(): Boolean = available

                    override fun next(): Int {
                        if (!available) throw NoSuchElementException()
                        available = false
                        return value
                    }
                }

                class LibraryCollection(private val value: Int) : Collection<Int> {
                    override val size: Int get() = 1

                    override fun isEmpty(): Boolean = false

                    override fun contains(element: Int): Boolean = element == value

                    override fun iterator(): Iterator<Int> = LibraryCollectionIterator(value)

                    override fun containsAll(elements: Collection<Int>): Boolean {
                        val iterator = elements.iterator()
                        while (iterator.hasNext()) {
                            if (!contains(iterator.next())) return false
                        }
                        return true
                    }
                }

                class LibraryListIterator(
                    private val value: Int,
                    private val size: Int,
                    private var position: Int,
                ) : ListIterator<Int> {
                    override fun hasNext(): Boolean = position < size

                    override fun next(): Int {
                        if (!hasNext()) throw NoSuchElementException()
                        position = position + 1
                        return value
                    }

                    override fun hasPrevious(): Boolean = position > 0

                    override fun previous(): Int {
                        if (!hasPrevious()) throw NoSuchElementException()
                        position = position - 1
                        return value
                    }

                    override fun nextIndex(): Int = position

                    override fun previousIndex(): Int = position - 1
                }

                class LibraryList private constructor(
                    private val value: Int,
                    private val fromIndex: Int,
                    private val toIndex: Int,
                ) : List<Int> {
                    constructor(value: Int) : this(value, 0, 1)

                    override val size: Int get() = toIndex - fromIndex

                    override fun isEmpty(): Boolean = size == 0

                    override fun contains(element: Int): Boolean = size == 1 && element == value

                    override fun iterator(): Iterator<Int> = listIterator()

                    override fun containsAll(elements: Collection<Int>): Boolean {
                        val iterator = elements.iterator()
                        while (iterator.hasNext()) {
                            if (!contains(iterator.next())) return false
                        }
                        return true
                    }

                    override fun get(index: Int): Int {
                        if (index < 0 || index >= size) throw IndexOutOfBoundsException()
                        return value
                    }

                    override fun indexOf(element: Int): Int = if (contains(element)) 0 else -1

                    override fun lastIndexOf(element: Int): Int = indexOf(element)

                    override fun listIterator(): ListIterator<Int> = listIterator(0)

                    override fun listIterator(index: Int): ListIterator<Int> {
                        if (index < 0 || index > size) throw IndexOutOfBoundsException()
                        return LibraryListIterator(value, size, index)
                    }

                    override fun subList(fromIndex: Int, toIndex: Int): List<Int> {
                        if (fromIndex < 0 || toIndex > size) throw IndexOutOfBoundsException()
                        if (fromIndex > toIndex) throw IllegalArgumentException()
                        return LibraryList(value, this.fromIndex + fromIndex, this.fromIndex + toIndex)
                    }
                }

                fun libraryProducer(): Producer<Int> = LibraryProducer(41)

                fun libraryCell(): VariantCell<Int> = LibraryCell(1)

                fun libraryIterator(): Iterator<Int> = LibraryIterator(8)

                fun libraryIterable(): Iterable<Int> = LibraryIterable(8)

                fun libraryCollection(): Collection<Int> = LibraryCollection(41)

                fun libraryListIterator(): ListIterator<Int> = LibraryListIterator(41, 1, 0)

                fun libraryList(): List<Int> = LibraryList(41)

                fun consumeSeven(consumer: Consumer<Int>) {
                    consumer.consume(7)
                }

                fun increment(cell: VariantCell<Int>) {
                    cell.value = cell.value + 1
                }

                fun readAsAny(producer: Producer<Any>): Any = producer.produce()
                """.trimIndent()
            )
        }
        val libraryDirectory = File(tmpdir, "generic-interface-library")
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Cross.Library",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val metadataLibrary = libraryDirectory.resolve("Cross.Library.klib")
        val libraryIl = libraryDirectory.resolve("Cross.Library.il").readText()
        assertTrue(
            "implements [Kotlin.Runtime]'Kotlin.Collections.Iterator', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.Iterator`1'<int32>" in libraryIl
        )
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.Iterator-next-" in libraryIl)
        assertTrue("<GenericInterfaceDeclaredBridge-kotlin.collections.Iterator-next-" in libraryIl)
        assertTrue(
            "implements [Kotlin.Runtime]'Kotlin.Collections.Iterable', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.Iterable`1'<int32>" in libraryIl
        )
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.Iterable-iterator-" in libraryIl)
        assertTrue("<GenericInterfaceDeclaredBridge-kotlin.collections.Iterable-iterator-" in libraryIl)
        assertTrue(
            "static class [Kotlin.Runtime]'Kotlin.Collections.Iterator' 'libraryIterator'()" in libraryIl
        )
        assertTrue(
            "static class [Kotlin.Runtime]'Kotlin.Collections.Iterable' 'libraryIterable'()" in libraryIl
        )
        assertTrue(
            "implements [Kotlin.Runtime]'Kotlin.Collections.Collection', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.Collection__KotlinExact`1'<int32>" in libraryIl
        )
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.Collection-contains-" in libraryIl)
        assertTrue("<GenericInterfaceExactBridge-kotlin.collections.Collection-contains-" in libraryIl)
        assertTrue("::'ContainsErased'(object" in libraryIl)
        assertTrue(
            "static class [Kotlin.Runtime]'Kotlin.Collections.Collection' 'libraryCollection'()" in libraryIl
        )
        assertTrue(
            "implements [Kotlin.Runtime]'Kotlin.Collections.ListIterator', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.ListIterator`1'<int32>" in libraryIl
        )
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.ListIterator-previous-" in libraryIl)
        assertTrue("<GenericInterfaceDeclaredBridge-kotlin.collections.ListIterator-previous-" in libraryIl)
        assertTrue(
            "static class [Kotlin.Runtime]'Kotlin.Collections.ListIterator' 'libraryListIterator'()" in libraryIl
        )
        assertTrue(
            "implements [Kotlin.Runtime]'Kotlin.Collections.List', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.List__KotlinExact`1'<int32>" in libraryIl
        )
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.List-get-" in libraryIl)
        assertTrue("<GenericInterfaceDeclaredBridge-kotlin.collections.List-get-" in libraryIl)
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.List-indexOf-" in libraryIl)
        assertTrue("<GenericInterfaceExactBridge-kotlin.collections.List-indexOf-" in libraryIl)
        assertTrue("::'IndexOfErased'(object" in libraryIl)
        assertTrue("::'GetListIterator'(" in libraryIl)
        assertTrue(
            "static class [Kotlin.Runtime]'Kotlin.Collections.List' 'libraryList'()" in libraryIl
        )
        val manifest = metadataLibrary.readKlibManifest()
        val declarationIndex = DotNetLibraryAbiCodec.decode(manifest)
        val physicalDeclarations = declarationIndex.values
            .filterIsInstance<DotNetPhysicalDeclaration.Class>()
        val producer = physicalDeclarations.single { it.ownerPath.last() == "cross.Producer" }
        assertTrue(producer.declaredOwnerPath?.last() == "cross.Producer`1")
        assertTrue(producer.exactOwnerPath == null)
        val variantCell = physicalDeclarations.single { it.ownerPath.last() == "cross.VariantCell" }
        assertTrue(variantCell.declaredOwnerPath?.last() == "cross.VariantCell`1")
        assertTrue(variantCell.exactOwnerPath?.last() == "cross.VariantCell__KotlinExact`1")

        val consumerDirectory = libraryDirectory.resolve("consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package consumer

                import cross.*

                class LocalProducer : Producer<Int> {
                    override fun produce(): Int = 42
                }

                class LocalCell(override var value: Int) : VariantCell<Int>

                class LocalAnyConsumer : Consumer<Any> {
                    var seen: String = ""

                    override fun consume(value: Any) {
                        seen = value.toString()
                    }
                }

                fun main() {
                    val libraryExact = libraryProducer()
                    val libraryWide: Producer<Any> = libraryExact
                    if (libraryExact !== libraryWide) throw Error("library producer identity")
                    if (libraryExact.produce() != 41) throw Error("library exact call")
                    if (libraryWide.produce() != 41) throw Error("library erased fallback")

                    val erasedLibraryProducer: Any = libraryExact
                    @Suppress("UNCHECKED_CAST")
                    val castLibraryWide = erasedLibraryProducer as Producer<Any>
                    if (castLibraryWide !== libraryExact || castLibraryWide.produce() != 41) {
                        throw Error("cross-module canonical hard cast")
                    }
                    @Suppress("UNCHECKED_CAST")
                    val safeLibraryMismatch = erasedLibraryProducer as? Producer<String>
                    val safeLibraryIdentity: Any? = safeLibraryMismatch
                    if (safeLibraryMismatch == null || safeLibraryIdentity !== libraryExact) {
                        throw Error("cross-module canonical safe cast")
                    }
                    if (LocalAnyConsumer() as? Producer<*> != null) {
                        throw Error("cross-module safe cast mismatch")
                    }

                    val localExact: Producer<Int> = LocalProducer()
                    val localWide: Producer<Any> = localExact
                    if (localExact !== localWide) throw Error("local producer identity")
                    if (localExact.produce() != 42 || localWide.produce() != 42) {
                        throw Error("external-interface implementation")
                    }

                    val iteratorExact = libraryIterator()
                    val iteratorWide: Iterator<Any> = iteratorExact
                    if (iteratorExact !== iteratorWide) throw Error("library iterator identity")
                    if (iteratorExact.next() != 8 || iteratorWide.next() != 9) {
                        throw Error("library iterator exact/fallback")
                    }

                    val iterableExact = libraryIterable()
                    val iterableWide: Iterable<Any> = iterableExact
                    if (iterableExact !== iterableWide) throw Error("library iterable identity")
                    if (iterableExact.iterator().next() != 8 || iterableWide.iterator().next() != 8) {
                        throw Error("library iterable exact/fallback")
                    }

                    val collectionExact = libraryCollection()
                    val collectionWide: Collection<Any?> = collectionExact
                    if (collectionExact !== collectionWide) throw Error("library collection identity")
                    if (!collectionExact.contains(41) || !collectionWide.contains(41)) {
                        throw Error("library collection exact/fallback")
                    }
                    if (collectionWide.contains("wrong") || collectionWide.contains(null)) {
                        throw Error("library collection wrong-shape barrier")
                    }
                    if (collectionExact.size != 1 || collectionExact.isEmpty()) {
                        throw Error("library collection declared calls")
                    }
                    if (!collectionExact.containsAll(libraryCollection())) {
                        throw Error("library collection containsAll")
                    }
                    if (collectionExact.iterator().next() != 41) {
                        throw Error("library collection iterator")
                    }

                    val listIteratorExact = libraryListIterator()
                    val listIteratorWide: ListIterator<Any?> = listIteratorExact
                    if (listIteratorExact !== listIteratorWide) throw Error("library list iterator identity")
                    if (listIteratorExact.next() != 41 || listIteratorWide.previous() != 41) {
                        throw Error("library list iterator exact/fallback")
                    }

                    val listExact = libraryList()
                    val listWide: List<Any?> = listExact
                    if (listExact !== listWide) throw Error("library list identity")
                    if (listExact.get(0) != 41 || listWide.get(0) != 41) {
                        throw Error("library list exact/fallback get")
                    }
                    if (!listExact.contains(41) || listWide.indexOf(41) != 0 || listWide.lastIndexOf(41) != 0) {
                        throw Error("library list exact/fallback search")
                    }
                    if (listWide.contains("wrong") || listWide.indexOf("wrong") != -1 ||
                        listWide.lastIndexOf(null) != -1
                    ) {
                        throw Error("library list wrong-shape barrier")
                    }
                    if (listExact.listIterator().next() != 41 ||
                        listExact.listIterator(1).previous() != 41 ||
                        listWide.subList(0, 1).get(0) != 41
                    ) {
                        throw Error("library list nested canonical results")
                    }
                    val listAsCollection: Collection<Int> = listExact
                    if (!listAsCollection.contains(41)) throw Error("library list exact Collection super-view")

                    val localCell = LocalCell(5)
                    increment(localCell)
                    if (localCell.value != 6) throw Error("consumer exact implementation")

                    val remoteCell = libraryCell()
                    remoteCell.value = 2
                    increment(remoteCell)
                    val remoteWide: VariantCell<Any> = remoteCell
                    if (remoteCell !== remoteWide || remoteWide.value != 3) {
                        throw Error("exact property identity/fallback")
                    }
                    remoteWide.value = 4
                    if (remoteCell.value != 4) throw Error("erased setter fallback")

                    val sink = LocalAnyConsumer()
                    consumeSeven(sink)
                    if (sink.seen != "7") throw Error("contravariant callback")
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("CrossConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CrossConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )
        runDotNet(
            dotnetHost,
            consumerAssembly,
            consumerDirectory,
            "Generic-interface cross-module consumer failed",
        )

        val producerSlot = declarationIndex.values
            .filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .single {
                it.ownerPath.last() == "cross.Producer" &&
                        it.methodName.startsWith("produce__KotlinErased__")
            }
        val readAsAny = declarationIndex.values
            .filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .single { it.methodName == "readAsAny" }
        val rawConsumerIl = libraryDirectory.resolve("CanonicalOnlyConsumer.il").apply {
            writeText(
                """
                .assembly extern mscorlib {}
                .assembly extern Cross.Library
                {
                  .ver 1:0:0:0
                }
                .assembly extern Kotlin.Runtime
                {
                  .ver 1:0:0:0
                }
                .assembly CanonicalOnlyConsumer {}
                .module CanonicalOnlyConsumer.dll

                .class private auto ansi sealed beforefieldinit 'CanonicalOnlyProducer'
                       extends [mscorlib]System.Object
                       implements [Cross.Library]'cross.Producer'
                {
                  .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
                  {
                    .maxstack 1
                    ldarg.0
                    call instance void [mscorlib]System.Object::.ctor()
                    ret
                  }

                  .method private hidebysig newslot virtual final instance object '${producerSlot.methodName}'() cil managed
                  {
                    .override method instance object [Cross.Library]'cross.Producer'::'${producerSlot.methodName}'()
                    .maxstack 1
                    ldc.i4.s 73
                    box [mscorlib]System.Int32
                    ret
                  }
                }

                .method public static void Main() cil managed
                {
                  .entrypoint
                  .maxstack 3
                  .locals init (
                    class [Cross.Library]'cross.LibraryIterator' V_0,
                    class [Cross.Library]'cross.LibraryCollection' V_1,
                    class [Cross.Library]'cross.LibraryCollection' V_2,
                    class [Cross.Library]'cross.LibraryListIterator' V_3,
                    class [Cross.Library]'cross.LibraryList' V_4
                  )
                  newobj instance void 'CanonicalOnlyProducer'::.ctor()
                  call object [Cross.Library]'${readAsAny.ownerPath.single()}'::'${readAsAny.methodName}'(
                      class [Cross.Library]'cross.Producer'
                  )
                  unbox.any [mscorlib]System.Int32
                  ldc.i4.s 73
                  bne.un IL_failure
                  ldc.i4.8
                  newobj instance void [Cross.Library]'cross.LibraryIterator'::.ctor(int32)
                  stloc.0
                  ldloc.0
                  callvirt instance !0 class [Kotlin.Runtime]'Kotlin.Collections.Iterator`1'<int32>::'Next'()
                  ldc.i4.8
                  bne.un IL_failure
                  ldloc.0
                  callvirt instance object [Kotlin.Runtime]'Kotlin.Collections.Iterator'::'Next'()
                  unbox.any [mscorlib]System.Int32
                  ldc.i4.s 9
                  bne.un IL_failure
                  ldc.i4.s 41
                  newobj instance void [Cross.Library]'cross.LibraryCollection'::.ctor(int32)
                  stloc.1
                  ldc.i4.s 41
                  newobj instance void [Cross.Library]'cross.LibraryCollection'::.ctor(int32)
                  stloc.2
                  ldloc.1
                  ldc.i4.s 41
                  callvirt instance bool class [Kotlin.Runtime]'Kotlin.Collections.Collection__KotlinExact`1'<int32>::'Contains'(!0)
                  brfalse IL_failure
                  ldloc.1
                  ldc.i4.s 41
                  box [mscorlib]System.Int32
                  callvirt instance bool [Kotlin.Runtime]'Kotlin.Collections.Collection'::'ContainsErased'(object)
                  brfalse IL_failure
                  ldloc.1
                  ldstr "wrong"
                  callvirt instance bool [Kotlin.Runtime]'Kotlin.Collections.Collection'::'ContainsErased'(object)
                  brtrue IL_failure
                  ldloc.1
                  ldnull
                  callvirt instance bool [Kotlin.Runtime]'Kotlin.Collections.Collection'::'ContainsErased'(object)
                  brtrue IL_failure
                  ldloc.1
                  callvirt instance int32 class [Kotlin.Runtime]'Kotlin.Collections.Collection`1'<int32>::'get_Size'()
                  ldc.i4.1
                  bne.un IL_failure
                  ldloc.1
                  callvirt instance bool class [Kotlin.Runtime]'Kotlin.Collections.Collection`1'<int32>::'IsEmpty'()
                  brtrue IL_failure
                  ldloc.1
                  ldloc.2
                  callvirt instance bool class [Kotlin.Runtime]'Kotlin.Collections.Collection`1'<int32>::'ContainsAll'(
                    class [Kotlin.Runtime]'Kotlin.Collections.Collection'
                  )
                  brfalse IL_failure
                  ldloc.1
                  callvirt instance class [Kotlin.Runtime]'Kotlin.Collections.Iterator' class [Kotlin.Runtime]'Kotlin.Collections.Collection`1'<int32>::'GetIterator'()
                  castclass class [Kotlin.Runtime]'Kotlin.Collections.Iterator`1'<int32>
                  callvirt instance !0 class [Kotlin.Runtime]'Kotlin.Collections.Iterator`1'<int32>::'Next'()
                  ldc.i4.s 41
                  bne.un IL_failure
                  ldloc.1
                  castclass class [Kotlin.Runtime]'Kotlin.Collections.Iterable`1'<int32>
                  callvirt instance class [Kotlin.Runtime]'Kotlin.Collections.Iterator' class [Kotlin.Runtime]'Kotlin.Collections.Iterable`1'<int32>::'GetIterator'()
                  castclass class [Kotlin.Runtime]'Kotlin.Collections.Iterator`1'<int32>
                  callvirt instance !0 class [Kotlin.Runtime]'Kotlin.Collections.Iterator`1'<int32>::'Next'()
                  ldc.i4.s 41
                  bne.un IL_failure
                  ldc.i4.s 41
                  ldc.i4.1
                  ldc.i4.0
                  newobj instance void [Cross.Library]'cross.LibraryListIterator'::.ctor(int32, int32, int32)
                  stloc.3
                  ldloc.3
                  callvirt instance !0 class [Kotlin.Runtime]'Kotlin.Collections.ListIterator`1'<int32>::'Next'()
                  ldc.i4.s 41
                  bne.un IL_failure
                  ldloc.3
                  callvirt instance object [Kotlin.Runtime]'Kotlin.Collections.ListIterator'::'Previous'()
                  unbox.any [mscorlib]System.Int32
                  ldc.i4.s 41
                  bne.un IL_failure
                  ldc.i4.s 41
                  newobj instance void [Cross.Library]'cross.LibraryList'::.ctor(int32)
                  stloc.s 4
                  ldloc.s 4
                  ldc.i4.0
                  callvirt instance !0 class [Kotlin.Runtime]'Kotlin.Collections.List`1'<int32>::'Get'(int32)
                  ldc.i4.s 41
                  bne.un IL_failure
                  ldloc.s 4
                  ldc.i4.0
                  callvirt instance object [Kotlin.Runtime]'Kotlin.Collections.List'::'Get'(int32)
                  unbox.any [mscorlib]System.Int32
                  ldc.i4.s 41
                  bne.un IL_failure
                  ldloc.s 4
                  ldc.i4.s 41
                  callvirt instance int32 class [Kotlin.Runtime]'Kotlin.Collections.List__KotlinExact`1'<int32>::'IndexOf'(!0)
                  brtrue IL_failure
                  ldloc.s 4
                  ldstr "wrong"
                  callvirt instance int32 [Kotlin.Runtime]'Kotlin.Collections.List'::'IndexOfErased'(object)
                  ldc.i4.m1
                  bne.un IL_failure
                  ldloc.s 4
                  ldnull
                  callvirt instance int32 [Kotlin.Runtime]'Kotlin.Collections.List'::'LastIndexOfErased'(object)
                  ldc.i4.m1
                  bne.un IL_failure
                  ldloc.s 4
                  callvirt instance class [Kotlin.Runtime]'Kotlin.Collections.ListIterator' class [Kotlin.Runtime]'Kotlin.Collections.List`1'<int32>::'GetListIterator'()
                  castclass class [Kotlin.Runtime]'Kotlin.Collections.ListIterator`1'<int32>
                  callvirt instance !0 class [Kotlin.Runtime]'Kotlin.Collections.ListIterator`1'<int32>::'Next'()
                  ldc.i4.s 41
                  bne.un IL_failure
                  ldloc.s 4
                  ldc.i4.0
                  ldc.i4.1
                  callvirt instance class [Kotlin.Runtime]'Kotlin.Collections.List' class [Kotlin.Runtime]'Kotlin.Collections.List`1'<int32>::'SubList'(int32, int32)
                  castclass class [Kotlin.Runtime]'Kotlin.Collections.List`1'<int32>
                  ldc.i4.0
                  callvirt instance !0 class [Kotlin.Runtime]'Kotlin.Collections.List`1'<int32>::'Get'(int32)
                  ldc.i4.s 41
                  bne.un IL_failure
                  ldloc.s 4
                  ldc.i4.s 41
                  callvirt instance bool class [Kotlin.Runtime]'Kotlin.Collections.List__KotlinExact`1'<int32>::'Contains'(!0)
                  brfalse IL_failure
                  ldloc.s 4
                  ldstr "wrong"
                  callvirt instance bool [Kotlin.Runtime]'Kotlin.Collections.List'::'ContainsErased'(object)
                  brtrue IL_failure
                  ldloc.s 4
                  ldc.i4.s 41
                  callvirt instance bool class [Kotlin.Runtime]'Kotlin.Collections.Collection__KotlinExact`1'<int32>::'Contains'(!0)
                  brtrue IL_success
                IL_failure:
                  ldstr "Canonical-only generic-interface fallback returned an unexpected result."
                  newobj instance void [mscorlib]System.Exception::.ctor(string)
                  throw
                IL_success:
                  ret
                }
                """.trimIndent()
            )
        }
        val rawConsumerAssembly = libraryDirectory.resolve("CanonicalOnlyConsumer.dll")
        assertTrue(
            DotNetIlAssembler.assembleExecutable(
                rawConsumerIl,
                rawConsumerAssembly,
                DotNetTarget.NET10_0,
                MessageCollector.NONE,
            )
        )
        consumerDirectory.resolve("Kotlin.Runtime.dll")
            .copyTo(libraryDirectory.resolve("Kotlin.Runtime.dll"), overwrite = true)
        runDotNet(
            dotnetHost,
            rawConsumerAssembly,
            libraryDirectory,
            "Canonical-only generic-interface consumer failed",
        )
    }

    @Test
    fun testForeignGenericInterfaceBarriers() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val frameworkCSharp = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(frameworkCSharp != null, ".NET Framework C# compiler is not available")
        val frameworkHost = DotNetIlAssembler.findFrameworkPowerShellHost()
        requireOrAssumeToolchain(frameworkHost != null, "Windows PowerShell CLR 4 host is not available")
        val frameworkNetStandardFacade = findFrameworkNetStandardFacade()
        requireOrAssumeToolchain(
            frameworkNetStandardFacade != null,
            ".NET Framework netstandard 2.0 facade is not available",
        )
        val modernCSharp = DotNetIlAssembler.findModernCSharpCompiler()
        requireOrAssumeToolchain(
            modernCSharp != null,
            "Modern Roslyn and the net10 reference pack are not available",
        )

        val producerDirectory = File(tmpdir, "foreign-barriers").apply { mkdirs() }
        val producerSource = producerDirectory.resolve("barrier.kt").apply {
            writeText(
                """
                package barriers

                public interface UnsafeSink<out T> {
                    public fun accepts(value: @UnsafeVariance T): Boolean
                }

                public fun verifyForeign(
                    collection: Collection<Int>,
                    unsafeSink: UnsafeSink<Int>,
                ): Int {
                    if (collection.size != 1 || collection.isEmpty()) return 1
                    if (!collection.contains(42)) return 2
                    val wideCollection: Collection<Any?> = collection
                    if (wideCollection.contains("wrong") || wideCollection.contains(null)) return 3

                    if (!unsafeSink.accepts(42)) return 4
                    val wideUnsafeSink: UnsafeSink<Any?> = unsafeSink
                    try {
                        wideUnsafeSink.accepts("wrong")
                        return 5
                    } catch (_: ClassCastException) {
                        // An ordinary user unsafe member retains normal cast-failure behavior.
                    }
                    return 0
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            producerSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Foreign.Barriers",
            K2DotNetCompilerArguments::destination.cliArgument, producerDirectory.path,
        )

        val producerMetadata = producerDirectory.resolve("Foreign.Barriers.klib")
        val declarations = DotNetLibraryAbiCodec.decode(producerMetadata.readKlibManifest()).values
        val unsafeClass = declarations.filterIsInstance<DotNetPhysicalDeclaration.Class>()
            .single { declaration -> declaration.ownerPath.last() == "barriers.UnsafeSink" }
        assertEquals(listOf("barriers.UnsafeSink`1"), unsafeClass.declaredOwnerPath)
        assertEquals(listOf("barriers.UnsafeSink__KotlinExact`1"), unsafeClass.exactOwnerPath)
        val unsafeCanonicalSlot = declarations.filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .single { declaration ->
                declaration.ownerPath.last() == "barriers.UnsafeSink" &&
                        declaration.methodName.startsWith("accepts__KotlinErased__")
            }
        val verifier = declarations.filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .single { declaration -> declaration.methodName == "verifyForeign" }
        assertEquals(listOf("barriers.barrierKt"), verifier.ownerPath)

        val csharpSourceText = """
            using System;

            public sealed class ForeignCollection
                : Kotlin.Collections.Collection__KotlinExact<int>
            {
                public int Size { get { return 1; } }

                public bool IsEmpty() { return false; }

                public bool Contains(int element) { return element == 42; }

                public bool ContainsErased(object element)
                {
                    return element is int && Contains((int)element);
                }

                public Kotlin.Collections.Iterator GetIterator() { return null; }

                public bool ContainsAll(Kotlin.Collections.Collection elements) { return false; }
            }

            public sealed class ForeignUnsafeSink
                : barriers.UnsafeSink__KotlinExact<int>
            {
                public bool accepts(int value) { return value == 42; }

                public bool ${unsafeCanonicalSlot.methodName}(object value)
                {
                    return accepts((int)value);
                }
            }

            public static class Program
            {
                public static void Main()
                {
                    int result = ${verifier.ownerPath.single()}.${verifier.methodName}(
                        new ForeignCollection(),
                        new ForeignUnsafeSink());
                    if (result != 0)
                        throw new Exception("foreign generic-interface barrier " + result);
                    Console.WriteLine("OK");
                }
            }
        """.trimIndent()
        val producerAssembly = producerDirectory.resolve("Foreign.Barriers.dll")
        assertTrue(producerAssembly.isFile)
        val bootstrapSource = producerDirectory.resolve("bootstrap.kt").apply { writeText("fun main() {}") }

        val frameworkDirectory = producerDirectory.resolve("framework").apply { mkdirs() }
        compileInProcess(
            K2DotNetCompiler(),
            bootstrapSource.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net48",
            K2DotNetCompilerArguments::moduleName.cliArgument, "ForeignBarriersBootstrap",
            K2DotNetCompilerArguments::destination.cliArgument,
            frameworkDirectory.resolve("ForeignBarriersBootstrap.exe").path,
        )
        val frameworkRuntime = frameworkDirectory.resolve("Kotlin.Runtime.dll")
        assertTrue(frameworkRuntime.isFile)
        val frameworkSource = frameworkDirectory.resolve("consumer.cs").apply { writeText(csharpSourceText) }
        val frameworkApplication = frameworkDirectory.resolve("ForeignBarriers.exe")
        val frameworkCompile = runCSharpCompiler(
            checkNotNull(frameworkCSharp),
            frameworkSource,
            frameworkApplication,
            producerAssembly,
            frameworkRuntime,
            checkNotNull(frameworkNetStandardFacade),
            target = "exe",
        )
        assertEquals(0, frameworkCompile.exitCode, frameworkCompile.output)
        producerAssembly.copyTo(frameworkDirectory.resolve(producerAssembly.name), overwrite = true)
        runAssemblerPairing(
            frameworkExecutionCommand(checkNotNull(frameworkHost), frameworkApplication),
            frameworkDirectory,
            "Framework foreign generic-interface barriers",
        )

        val modernDirectory = producerDirectory.resolve("modern").apply { mkdirs() }
        compileInProcess(
            K2DotNetCompiler(),
            bootstrapSource.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "ForeignBarriersBootstrap",
            K2DotNetCompilerArguments::destination.cliArgument,
            modernDirectory.resolve("ForeignBarriersBootstrap.dll").path,
        )
        val modernRuntime = modernDirectory.resolve("Kotlin.Runtime.dll")
        assertTrue(modernRuntime.isFile)
        val modernSource = modernDirectory.resolve("consumer.cs").apply { writeText(csharpSourceText) }
        val modernApplication = modernDirectory.resolve("ForeignBarriers.dll")
        val modernCompile = runModernCSharpCompiler(
            checkNotNull(modernCSharp),
            modernSource,
            modernApplication,
            producerAssembly,
            modernRuntime,
            target = "exe",
        )
        assertEquals(0, modernCompile.exitCode, modernCompile.output)
        producerAssembly.copyTo(modernDirectory.resolve(producerAssembly.name), overwrite = true)
        modernDirectory.resolve("ForeignBarriers.runtimeconfig.json").writeText(
            """
            {
              "runtimeOptions": {
                "tfm": "net10.0",
                "framework": {
                  "name": "Microsoft.NETCore.App",
                  "version": "10.0.0"
                },
                "rollForward": "LatestMinor"
              }
            }
            """.trimIndent()
        )
        runAssemblerPairing(
            listOf(checkNotNull(modernCSharp).dotNetHost.path, "exec", modernApplication.path),
            modernDirectory,
            "CoreCLR foreign generic-interface barriers",
        )
    }

    @Test
    fun testGenericInterfaceDefaultsAcrossPortableAndNet10Assemblies() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val dotnetHost = modernDotNetHostOrSkip()

        val portableDirectory = File(tmpdir, "portable-generic-interface-default").apply { mkdirs() }
        val portableSource = portableDirectory.resolve("portable.kt").apply {
            writeText(
                """
                package genericdefaults

                public interface PortableGeneric<out T> {
                    public fun seed(): T
                    public fun value(): T = seed()
                    public fun <R : @UnsafeVariance T> echo(value: R): R = value
                    public fun same(value: @UnsafeVariance T): Boolean = seed() == value
                }

                public class PortableInt(private val current: Int) : PortableGeneric<Int> {
                    public override fun seed(): Int = current
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            portableSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Portable.GenericDefaults",
            K2DotNetCompilerArguments::destination.cliArgument, portableDirectory.path,
        )
        val portableMetadata = portableDirectory.resolve("Portable.GenericDefaults.klib")
        val portableIl = portableDirectory.resolve("Portable.GenericDefaults.il").readText()
        assertTrue("abstract virtual instance object 'value__KotlinErased__" in portableIl) { portableIl }
        assertTrue("/'<DefaultImpls>'::'value'" in portableIl) { portableIl }
        assertTrue("<GenericInterfaceCanonicalBridge-" in portableIl) { portableIl }
        assertTrue("<GenericInterfaceDeclaredBridge-" in portableIl) { portableIl }
        assertTrue("<GenericInterfaceExactBridge-" in portableIl) { portableIl }

        val promotedDirectory = File(tmpdir, "promoted-generic-interface-default").apply { mkdirs() }
        val promotedSource = promotedDirectory.resolve("promoted.kt").apply {
            writeText(
                """
                package genericdefaults

                public interface PromotedGeneric<out T> : PortableGeneric<T>
                public interface PromotedInt : PortableGeneric<Int>
                public interface OverriddenInt : PortableGeneric<Int> {
                    public override fun value(): Int = 91
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            promotedSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, portableMetadata.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Promoted.GenericDefaults",
            K2DotNetCompilerArguments::destination.cliArgument, promotedDirectory.path,
        )
        val promotedMetadata = promotedDirectory.resolve("Promoted.GenericDefaults.klib")
        val promotedDeclarations = DotNetLibraryAbiCodec.decode(promotedMetadata.readKlibManifest())
        val promotions = promotedDeclarations.values
            .filterIsInstance<DotNetPhysicalDeclaration.InterfaceDefaultPromotion>()
        assertEquals(16, promotions.size, promotions.joinToString("\n"))
        val viewBridges = promotedDeclarations.values
            .filterIsInstance<DotNetPhysicalDeclaration.GenericInterfaceViewBridge>()
        assertEquals(12, viewBridges.size, viewBridges.joinToString("\n"))
        val overriddenValueBridges = viewBridges.filter { bridge ->
            bridge.ownerPath == listOf("genericdefaults.OverriddenInt") &&
                    "/PortableGeneric.value" in bridge.inheritedLogicalMemberKey
        }
        assertEquals(2, overriddenValueBridges.size, overriddenValueBridges.joinToString("\n"))
        assertEquals(
            setOf(
                DotNetInterfaceDefaultPromotionView.CANONICAL,
                DotNetInterfaceDefaultPromotionView.DECLARED,
            ),
            overriddenValueBridges.mapTo(hashSetOf()) { it.physicalView },
        )
        assertEquals(
            setOf(
                DotNetInterfaceDefaultPromotionView.CANONICAL,
                DotNetInterfaceDefaultPromotionView.DECLARED,
                DotNetInterfaceDefaultPromotionView.EXACT,
            ),
            promotions.mapTo(hashSetOf()) { it.physicalView },
        )
        val promotedIl = promotedDirectory.resolve("Promoted.GenericDefaults.il").readText()
        assertTrue("<GenericInterfaceDefaultPromotionCanonical-" in promotedIl) { promotedIl }
        assertTrue("<GenericInterfaceDefaultPromotionDeclared-" in promotedIl) { promotedIl }
        assertTrue("<GenericInterfaceDefaultPromotionExact-" in promotedIl) { promotedIl }
        assertTrue("[Portable.GenericDefaults]" in promotedIl) { promotedIl }
        assertTrue("/'<DefaultImpls>'::'value'" in promotedIl) { promotedIl }
        assertTrue("<GenericInterfaceCanonicalBridge-genericdefaults.PortableGeneric-value-" in promotedIl) {
            "The closed override must explicitly map the inherited canonical slot:\n$promotedIl"
        }
        assertTrue("<InterfaceDefaultSlotBridge-genericdefaults.OverriddenInt-value-" !in promotedIl) {
            "An ordinary int32 bridge cannot implement the erased object slot:\n$promotedIl"
        }
        assertEquals(1, promotedIl.lineSequence().count { "ldc.i4 91" in it }) {
            "The Kotlin body must occur only in OverriddenInt.value; helpers and view adapters only forward:\n$promotedIl"
        }

        val closedImplementationDirectory =
            File(tmpdir, "closed-generic-interface-default-implementation").apply { mkdirs() }
        val closedImplementationSource = closedImplementationDirectory.resolve("closed.kt").apply {
            writeText(
                """
                package genericdefaults

                public class ClosedImplementation(private val current: Int) : PromotedInt {
                    public override fun seed(): Int = current
                }

                public class OverriddenImplementation(private val current: Int) : OverriddenInt {
                    public override fun seed(): Int = current
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            closedImplementationSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument,
            listOf(portableMetadata, promotedMetadata).joinToString(File.pathSeparator) { it.path },
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Closed.GenericDefaults",
            K2DotNetCompilerArguments::destination.cliArgument, closedImplementationDirectory.path,
        )
        val closedImplementationMetadata =
            closedImplementationDirectory.resolve("Closed.GenericDefaults.klib")
        val consumerDirectory = File(tmpdir, "generic-interface-default-consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("main.kt").apply {
            writeText(
                """
                package genericdefaults

                private class ThroughPromotion(private val current: Int) : PromotedGeneric<Int> {
                    override fun seed(): Int = current
                }

                private class ThroughPortable(private val current: Int) : PortableGeneric<Int> {
                    override fun seed(): Int = current
                }

                fun main() {
                    val promoted: PortableGeneric<Int> = ThroughPromotion(41)
                    if (promoted.value() != 41) throw Error("promoted typed result")
                    if (promoted.echo(41) != 41) throw Error("promoted method generic")
                    if (!promoted.same(41)) throw Error("promoted exact argument")
                    val widened: PortableGeneric<Any> = promoted
                    if (widened.value() != 41) throw Error("promoted erased result")
                    if (!widened.same(41)) throw Error("promoted erased exact fallback")
                    if (widened.echo("widened echo") != "widened echo") throw Error("promoted widened method constraint")

                    val closedView: PromotedInt = ClosedImplementation(42)
                    val closed: PortableGeneric<Int> = closedView
                    if (closed.value() != 42) throw Error("closed promoted typed result")
                    if (closed.echo(42) != 42) throw Error("closed promoted method generic")
                    if (!closed.same(42)) throw Error("closed promoted exact argument")
                    if (closedView.value() != 42) throw Error("closed promoted derived view")

                    val overriddenView: OverriddenInt = OverriddenImplementation(45)
                    if (overriddenView.value() != 91) throw Error("closed interface override derived view")
                    val overridden: PortableGeneric<Int> = overriddenView
                    if (overridden.value() != 91) throw Error("closed interface override typed view")
                    if (!overridden.same(45)) throw Error("closed interface inherited exact view")
                    val widenedOverride: PortableGeneric<Any> = overriddenView
                    if (widenedOverride.value() != 91) throw Error("closed interface override widened view")

                    val portable: PortableGeneric<Int> = ThroughPortable(43)
                    if (portable.value() != 43) throw Error("portable class forwarder result")
                    if (portable.echo(43) != 43) throw Error("portable class method generic")
                    if (!portable.same(43)) throw Error("portable class exact argument")

                    val producer: PortableGeneric<Int> = PortableInt(44)
                    if (producer.value() != 44) throw Error("portable producer result")
                    if (!producer.same(44)) throw Error("portable producer exact argument")
                    val widenedProducer: PortableGeneric<Any> = producer
                    if (widenedProducer.echo("producer echo") != "producer echo") throw Error("producer widened method constraint")
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("GenericDefaultConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument,
            listOf(portableMetadata, promotedMetadata, closedImplementationMetadata)
                .joinToString(File.pathSeparator) { it.path },
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "GenericDefaultConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )
        val consumerIl = consumerDirectory.resolve("GenericDefaultConsumer.il").readText()
        assertTrue("[Promoted.GenericDefaults]" in consumerIl) { consumerIl }
        assertTrue("<GenericInterfaceDefaultForwarderTarget-" in consumerIl) {
            "The direct portable implementation still requires helper-backed bridges:\n$consumerIl"
        }
        val closedImplementationIl =
            closedImplementationDirectory.resolve("Closed.GenericDefaults.il").readText()
        assertTrue("<GenericInterfaceDefaultForwarderTarget-genericdefaults.ClosedImplementation-" !in closedImplementationIl) {
            "A closed non-generic promotion supplies DIMs and must suppress class forwarders:\n$closedImplementationIl"
        }
        assertTrue("<GenericInterfaceDefaultForwarderTarget-genericdefaults.OverriddenImplementation-" !in closedImplementationIl) {
            "The selected closed override DIM must suppress helper-backed class forwarders:\n$closedImplementationIl"
        }
        assertTrue("<GenericInterfaceCanonicalBridge-genericdefaults.PortableGeneric-value-" !in closedImplementationIl) {
            "The implementor must inherit OverriddenInt's value adapters instead of duplicating them:\n$closedImplementationIl"
        }
        runDotNet(
            dotnetHost,
            consumerAssembly,
            consumerDirectory,
            "Generic interface defaults failed across portable and net10 assemblies",
        )
    }

    @Test
    fun testModernCSharpConsumesProfileAwareGenericInterfaceDefault() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val csharpToolchain = DotNetIlAssembler.findModernCSharpCompiler()
        requireOrAssumeToolchain(csharpToolchain != null, "Modern Roslyn and the net10 reference pack are not available")
        val modernCSharp = checkNotNull(csharpToolchain)

        val kotlinSourceText = """
            package genericdefaults

            public interface CSharpEcho<T> {
                public fun echo(value: T): T = value
            }

            public interface CSharpVariantEcho<out T> {
                public fun echo(value: @UnsafeVariance T): T = value
            }
        """.trimIndent()
        val portableDirectory = File(tmpdir, "csharp-portable-interface-default").apply { mkdirs() }
        val portableSource = portableDirectory.resolve("default.kt").apply { writeText(kotlinSourceText) }
        compileInProcess(
            K2DotNetCompiler(),
            portableSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CSharp.PortableDefaults",
            K2DotNetCompilerArguments::destination.cliArgument, portableDirectory.path,
        )

        val modernDirectory = File(tmpdir, "csharp-modern-interface-default").apply { mkdirs() }
        val modernSource = modernDirectory.resolve("default.kt").apply { writeText(kotlinSourceText) }
        compileInProcess(
            K2DotNetCompiler(),
            modernSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CSharp.ModernDefaults",
            K2DotNetCompilerArguments::destination.cliArgument, modernDirectory.path,
        )
        val runtimeBootstrap = modernDirectory.resolve("runtime-bootstrap.kt").apply {
            writeText("fun main() {}")
        }
        compileInProcess(
            K2DotNetCompiler(),
            runtimeBootstrap.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CSharpRuntimeBootstrap",
            K2DotNetCompilerArguments::destination.cliArgument,
            modernDirectory.resolve("CSharpRuntimeBootstrap.dll").path,
        )
        val runtimeAssembly = modernDirectory.resolve("Kotlin.Runtime.dll")
        assertTrue(runtimeAssembly.isFile) { "Runtime bootstrap did not install Kotlin.Runtime.dll" }

        val consumerText = """
            public sealed class EchoImplementation : genericdefaults.CSharpEcho<int>
            {
            }

            public sealed class VariantEchoImplementation
                : genericdefaults.CSharpVariantEcho__KotlinExact<int>
            {
            }

            public static class Program
            {
                public static int Main()
                {
                    genericdefaults.CSharpEcho<int> value = new EchoImplementation();
                    if (value.echo(73) != 73)
                        return 1;
                    genericdefaults.CSharpVariantEcho__KotlinExact<int> exact =
                        new VariantEchoImplementation();
                    return exact.echo(74) == 74 ? 0 : 2;
                }
            }
        """.trimIndent()
        val modernConsumerSource = modernDirectory.resolve("consumer.cs").apply { writeText(consumerText) }
        val modernConsumer = modernDirectory.resolve("ModernCSharpConsumer.dll")
        val modernCompile = runModernCSharpCompiler(
            modernCSharp,
            modernConsumerSource,
            modernConsumer,
            modernDirectory.resolve("CSharp.ModernDefaults.dll"),
            runtimeAssembly,
            target = "exe",
        )
        assertEquals(0, modernCompile.exitCode, modernCompile.output)
        modernDirectory.resolve("ModernCSharpConsumer.runtimeconfig.json").writeText(
            """
            {
              "runtimeOptions": {
                "tfm": "net10.0",
                "framework": {
                  "name": "Microsoft.NETCore.App",
                  "version": "10.0.0"
                },
                "rollForward": "LatestMinor"
              }
            }
            """.trimIndent()
        )
        runDotNet(
            modernCSharp.dotNetHost,
            modernConsumer,
            modernDirectory,
            "Modern C# failed to inherit the generic Kotlin DIM",
        )

        val portableConsumerSource = portableDirectory.resolve("consumer.cs").apply { writeText(consumerText) }
        val portableCompile = runModernCSharpCompiler(
            modernCSharp,
            portableConsumerSource,
            portableDirectory.resolve("PortableCSharpConsumer.dll"),
            portableDirectory.resolve("CSharp.PortableDefaults.dll"),
            runtimeAssembly,
            target = "exe",
        )
        assertTrue(portableCompile.exitCode != 0) {
            "A portable abstract interface slot must require a C# implementation:\n${portableCompile.output}"
        }
        assertTrue("CS0535" in portableCompile.output) {
            "Expected Roslyn's missing-interface-member diagnostic for the portable profile:\n${portableCompile.output}"
        }

        val frameworkCSharp = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(frameworkCSharp != null, ".NET Framework C# compiler is not available")
        val frameworkDirectory = File(tmpdir, "csharp-framework-interface-default").apply { mkdirs() }
        val frameworkSource = frameworkDirectory.resolve("default.kt").apply { writeText(kotlinSourceText) }
        compileInProcess(
            K2DotNetCompiler(),
            frameworkSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net48",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CSharp.FrameworkDefaults",
            K2DotNetCompilerArguments::destination.cliArgument, frameworkDirectory.path,
        )
        val frameworkBootstrap = frameworkDirectory.resolve("runtime-bootstrap.kt").apply {
            writeText("fun main() {}")
        }
        compileInProcess(
            K2DotNetCompiler(),
            frameworkBootstrap.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net48",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CSharpFrameworkRuntimeBootstrap",
            K2DotNetCompilerArguments::destination.cliArgument,
            frameworkDirectory.resolve("CSharpFrameworkRuntimeBootstrap.exe").path,
        )
        val frameworkConsumerSource = frameworkDirectory.resolve("consumer.cs").apply { writeText(consumerText) }
        val frameworkCompile = runCSharpCompiler(
            checkNotNull(frameworkCSharp),
            frameworkConsumerSource,
            frameworkDirectory.resolve("FrameworkCSharpConsumer.exe"),
            frameworkDirectory.resolve("CSharp.FrameworkDefaults.dll"),
            frameworkDirectory.resolve("Kotlin.Runtime.dll"),
            target = "exe",
        )
        assertTrue(frameworkCompile.exitCode != 0) {
            "A net48 abstract interface slot must require a C# implementation:\n${frameworkCompile.output}"
        }
        assertTrue("CS0535" in frameworkCompile.output) {
            "Expected the Framework compiler's missing-interface-member diagnostic:\n${frameworkCompile.output}"
        }
    }

    @Test
    fun testNet10PromotesPortableInterfaceDefaultAndSuppressesOnlyCoveredForwarders() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val dotnetHost = modernDotNetHostOrSkip()

        val portableDirectory = File(tmpdir, "portable-interface-default").apply { mkdirs() }
        val portableSource = portableDirectory.resolve("portable.kt").apply {
            writeText(
                """
                package defaults

                public interface PortableBase {
                    public fun value(): String = "portable"
                }

                public interface PortableChild : PortableBase

                public class PortableOwned : PortableChild
                public open class PortableOpen : PortableBase
                public open class PortableOpenChild : PortableOpen()
                public open class PortableExplicit : PortableBase {
                    public override fun value(): String = "class"
                }
                public class PortableQualified : PortableBase {
                    public override fun value(): String =
                        "portable-qualified:" + super<PortableBase>.value()
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            portableSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Portable.Defaults",
            K2DotNetCompilerArguments::destination.cliArgument, portableDirectory.path,
        )

        val portableMetadata = portableDirectory.resolve("Portable.Defaults.klib")
        val portableManifest = portableMetadata.readKlibManifest()
        val portableDeclarations = DotNetLibraryAbiCodec.decode(portableManifest)
        val portableDefault = portableDeclarations.values
            .filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .single { declaration -> declaration.interfaceDefaultImplementation != null }
            .interfaceDefaultImplementation
        assertEquals(DotNetInterfaceDefaultBodyPlacement.HELPER_ONLY, portableDefault?.bodyPlacement)
        assertEquals(
            2,
            portableDeclarations.values
                .filterIsInstance<DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder>()
                .size,
            portableDeclarations.values.joinToString(),
        )
        val portableIl = portableDirectory.resolve("Portable.Defaults.il").readText()
        assertEquals(
            2,
            Regex("<InterfaceDefaultForwarder-").findAll(portableIl).count(),
            portableIl,
        )
        assertTrue("abstract virtual instance string 'value'()" in portableIl) { portableIl }
        assertTrue("'PortableBase'/'<DefaultImpls>'::'value'" !in portableIl) {
            "The portable helper owns the body; it must not call an unavailable DIM:\n$portableIl"
        }

        val derivedDirectory = File(tmpdir, "promoted-interface-default").apply { mkdirs() }
        val derivedSource = derivedDirectory.resolve("promoted.kt").apply {
            writeText(
                """
                package defaults

                public interface PromotedDefault : PortableChild
                public interface PromotedInherited : PromotedDefault
                public interface PromotedLeft : PortableChild
                public interface PromotedRight : PortableChild
                public interface PromotedDiamond : PromotedLeft, PromotedRight

                public interface ReabstractedDefault : PortableChild {
                    public override fun value(): String
                }

                public interface Net10Override : PortableBase {
                    public override fun value(): String = "net10"
                }

                public class Net10QualifiedPortable : PortableBase {
                    public override fun value(): String = super<PortableBase>.value()
                }

                public interface Net10Base {
                    public fun value(): String = "net10-base"
                }

                public interface Net10Child : Net10Base {
                    public override fun value(): String = "net10-child"
                    public fun exactBase(): String = super<Net10Base>.value()
                }

                public open class Net10PortableOpen : PortableBase
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            derivedSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, portableMetadata.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Promoted.Defaults",
            K2DotNetCompilerArguments::destination.cliArgument, derivedDirectory.path,
        )

        val derivedMetadata = derivedDirectory.resolve("Promoted.Defaults.klib")
        val derivedManifest = derivedMetadata.readKlibManifest()
        val derivedDeclarations = DotNetLibraryAbiCodec.decode(derivedManifest)
        val promotion = derivedDeclarations.values
            .filterIsInstance<DotNetPhysicalDeclaration.InterfaceDefaultPromotion>()
            .filter { declaration -> declaration.implementationMethodName.contains("PromotedDefault") }
            .single()
        assertEquals(
            4,
            derivedDeclarations.values
                .filterIsInstance<DotNetPhysicalDeclaration.InterfaceDefaultPromotion>()
                .count(),
        )
        assertEquals(
            1,
            derivedDeclarations.values
                .filterIsInstance<DotNetPhysicalDeclaration.InterfaceDefaultClassForwarder>()
                .size,
            derivedDeclarations.values.joinToString(),
        )
        assertEquals("Portable.Defaults", promotion.inheritedAssemblyName)
        assertEquals("value", promotion.inheritedMethodName)
        assertTrue(promotion.implementationMethodName.startsWith("<InterfaceDefaultPromotion-"))

        val derivedIl = derivedDirectory.resolve("Promoted.Defaults.il").readText()
        assertTrue("<InterfaceDefaultPromotion-" in derivedIl) { derivedIl }
        assertTrue(
            Regex("""\.override method instance string \[Portable\.Defaults].*::'value'\(\)""")
                .containsMatchIn(derivedIl)
        ) { derivedIl }
        assertTrue(
            Regex("""call string \[Portable\.Defaults].*/'<DefaultImpls>'::'value'""")
                .containsMatchIn(derivedIl)
        ) { derivedIl }
        assertTrue("call instance string 'defaults.Net10Base'::'value'()" in derivedIl) {
            "The net10 helper must invoke its owning DIM nonvirtually:\n$derivedIl"
        }
        assertTrue("callvirt instance string 'defaults.Net10Base'::'value'()" !in derivedIl) {
            "The exact helper must not redispatch virtually:\n$derivedIl"
        }
        assertTrue("call string 'defaults.Net10Base'/'<DefaultImpls>'::'value'" in derivedIl) {
            "Qualified super must route through the exact-call helper:\n$derivedIl"
        }
        assertEquals(
            1,
            Regex("<InterfaceDefaultForwarder-").findAll(derivedIl).count(),
            derivedIl,
        )

        val consumerDirectory = File(tmpdir, "promoted-interface-default-consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("main.kt").apply {
            writeText(
                """
                package defaults

                private class ThroughPromotion : PromotedDefault
                private class ThroughInheritedPromotion : PromotedInherited
                private class ThroughPortableChild : PortableChild
                private class ThroughCompetingPromotions : PromotedLeft, PromotedRight
                private class ThroughResolvedDiamond : PromotedDiamond
                private class ThroughOverride : PromotedDefault {
                    override fun value(): String = "override"
                }

                private class ThroughReabstraction : ReabstractedDefault {
                    override fun value(): String = "reabstracted"
                }
                private class ThroughPortableBaseAndNet10Override : PortableOpenChild(), Net10Override
                private class ThroughExternalPortableBase : PortableOpenChild()
                private class ThroughExplicitBaseAndNet10Override : PortableExplicit(), Net10Override {
                    override fun value(): String = super<PortableExplicit>.value()
                }
                private class ThroughNet10PortableBaseAndNet10Override : Net10PortableOpen(), Net10Override
                private class ThroughNet10QualifiedSuper : Net10Child

                fun main() {
                    val portableOwnedAsBase: PortableBase = PortableOwned()
                    if (portableOwnedAsBase.value() != "portable") {
                        throw Error("portable producer forwarder dispatch")
                    }
                    if (PortableQualified().value() != "portable-qualified:portable") {
                        throw Error("portable qualified interface-super call")
                    }

                    if (Net10QualifiedPortable().value() != "portable") {
                        throw Error("net10 consumer qualified portable interface-super call")
                    }

                    val net10Qualified = ThroughNet10QualifiedSuper()
                    if (net10Qualified.value() != "net10-child") {
                        throw Error("ordinary net10 DIM dispatch")
                    }
                    if (net10Qualified.exactBase() != "net10-base") {
                        throw Error("exact net10 DIM super dispatch")
                    }

                    val promotedAsBase: PortableBase = ThroughPromotion()
                    if (promotedAsBase.value() != "portable") {
                        throw Error("promoted default dispatch")
                    }

                    val inheritedPromotionAsBase: PortableBase = ThroughInheritedPromotion()
                    if (inheritedPromotionAsBase.value() != "portable") {
                        throw Error("indirect promoted default through portable base")
                    }

                    val inheritedPromotionAsInterface: PromotedInherited = ThroughInheritedPromotion()
                    if (inheritedPromotionAsInterface.value() != "portable") {
                        throw Error("indirect promoted default through derived interface")
                    }

                    val overrideAsBase: PortableBase = ThroughOverride()
                    if (overrideAsBase.value() != "override") {
                        throw Error("base-typed class override dispatch")
                    }

                    val overrideAsPromoted: PromotedDefault = ThroughOverride()
                    if (overrideAsPromoted.value() != "override") {
                        throw Error("promoted-interface-typed class override dispatch")
                    }

                    val reabstractedAsBase: PortableBase = ThroughReabstraction()
                    if (reabstractedAsBase.value() != "reabstracted") {
                        throw Error("base-typed reabstracted dispatch")
                    }

                    val reabstractedAsInterface: ReabstractedDefault = ThroughReabstraction()
                    if (reabstractedAsInterface.value() != "reabstracted") {
                        throw Error("reabstracted-interface-typed dispatch")
                    }

                    val competingAsBase: PortableBase = ThroughCompetingPromotions()
                    if (competingAsBase.value() != "portable") {
                        throw Error("competing promotions through portable base")
                    }

                    val competingAsLeft: PromotedLeft = ThroughCompetingPromotions()
                    if (competingAsLeft.value() != "portable") {
                        throw Error("competing promotions through left interface")
                    }

                    val competingAsRight: PromotedRight = ThroughCompetingPromotions()
                    if (competingAsRight.value() != "portable") {
                        throw Error("competing promotions through right interface")
                    }

                    val diamondAsBase: PortableBase = ThroughResolvedDiamond()
                    if (diamondAsBase.value() != "portable") {
                        throw Error("resolved diamond promotion through portable base")
                    }

                    val diamondAsInterface: PromotedDiamond = ThroughResolvedDiamond()
                    if (diamondAsInterface.value() != "portable") {
                        throw Error("resolved diamond promotion through derived interface")
                    }

                    val portableAsBase: PortableBase = ThroughPortableChild()
                    if (portableAsBase.value() != "portable") {
                        throw Error("portable helper forwarder dispatch")
                    }

                    val inheritedForwarderAsBase: PortableBase = ThroughExternalPortableBase()
                    if (inheritedForwarderAsBase.value() != "portable") {
                        throw Error("inherited portable forwarder dispatch")
                    }

                    val maskedOverrideAsBase: PortableBase = ThroughPortableBaseAndNet10Override()
                    if (maskedOverrideAsBase.value() != "net10") {
                        throw Error("net10 default masked by portable base forwarder")
                    }

                    val maskedOverrideAsDerived: Net10Override = ThroughPortableBaseAndNet10Override()
                    if (maskedOverrideAsDerived.value() != "net10") {
                        throw Error("net10 default through derived interface")
                    }

                    val net10MaskedOverrideAsBase: PortableBase = ThroughNet10PortableBaseAndNet10Override()
                    if (net10MaskedOverrideAsBase.value() != "net10") {
                        throw Error("net10 default masked by net10-produced portable forwarder")
                    }

                    val net10MaskedOverrideAsDerived: Net10Override = ThroughNet10PortableBaseAndNet10Override()
                    if (net10MaskedOverrideAsDerived.value() != "net10") {
                        throw Error("net10 default through net10-produced base")
                    }

                    val explicitOverrideAsBase: PortableBase = ThroughExplicitBaseAndNet10Override()
                    if (explicitOverrideAsBase.value() != "class") {
                        throw Error("explicit class override through portable base")
                    }

                    val explicitOverrideAsDerived: Net10Override = ThroughExplicitBaseAndNet10Override()
                    if (explicitOverrideAsDerived.value() != "class") {
                        throw Error("explicit class override through derived interface")
                    }
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("PromotedDefaultConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument,
            listOf(portableMetadata, derivedMetadata).joinToString(File.pathSeparator) { it.path },
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "PromotedDefaultConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )

        val consumerIl = consumerDirectory.resolve("PromotedDefaultConsumer.il").readText()
        assertEquals(
            4,
            Regex("<InterfaceDefaultForwarder-").findAll(consumerIl).count(),
            consumerIl,
        )
        assertTrue("[Portable.Defaults]" in consumerIl) { consumerIl }
        assertTrue("[Promoted.Defaults]" in consumerIl) { consumerIl }
        runDotNet(
            dotnetHost,
            consumerAssembly,
            consumerDirectory,
            "Profile-aware cross-module interface-default dispatch failed",
        )
    }

    @Test
    fun testNet10PromotesPortableAccessorsAndDefaultArguments() {
        val dotnetHost = modernDotNetHostOrSkip()
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")

        val portableDirectory = File(tmpdir, "portable-interface-features").apply { mkdirs() }
        val portableSource = portableDirectory.resolve("portable.kt").apply {
            writeText(
                """
                package defaults

                public var producerState: String = ""

                public interface PortableFeatures {
                    public var observed: String
                        get() = producerState
                        set(value) {
                            producerState = value
                        }

                    public fun combine(first: String = "O", second: String = "K"): String =
                        first + second
                }

                public interface PortableAbstractDefaults {
                    public fun abstractCombine(first: String = "O", second: String = "K"): String
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            portableSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Portable.Features",
            K2DotNetCompilerArguments::destination.cliArgument, portableDirectory.path,
        )

        val portableMetadata = portableDirectory.resolve("Portable.Features.klib")
        val portableDeclarations = DotNetLibraryAbiCodec.decode(portableMetadata.readKlibManifest())
        assertTrue(
            portableDeclarations.keys.none { "<DefaultImpls>" in it || "\$default" in it },
            portableDeclarations.keys.joinToString("\n"),
        )
        val portableFunctions = portableDeclarations.values
            .filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .filter { it.defaultArgumentDispatcher != null }
        assertEquals(2, portableFunctions.size, portableFunctions.joinToString())
        assertTrue(
            portableFunctions.any {
                it.methodName == "abstractCombine" &&
                        it.interfaceDefaultImplementation == null
            }
        )

        val derivedDirectory = File(tmpdir, "promoted-interface-features").apply { mkdirs() }
        val derivedSource = derivedDirectory.resolve("promoted.kt").apply {
            writeText(
                """
                package defaults

                public interface PromotedFeatures : PortableFeatures
                public interface PromotedAbstractDefaults : PortableAbstractDefaults

                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            derivedSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, portableMetadata.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Promoted.Features",
            K2DotNetCompilerArguments::destination.cliArgument, derivedDirectory.path,
        )

        val derivedMetadata = derivedDirectory.resolve("Promoted.Features.klib")
        val promotions = DotNetLibraryAbiCodec.decode(derivedMetadata.readKlibManifest()).values
            .filterIsInstance<DotNetPhysicalDeclaration.InterfaceDefaultPromotion>()
        assertEquals(3, promotions.size, promotions.joinToString())
        assertTrue(promotions.all { it.inheritedAssemblyName == "Portable.Features" })

        val consumerDirectory = File(tmpdir, "promoted-interface-features-consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("main.kt").apply {
            writeText(
                """
                package defaults

                private class ThroughPromotion : PromotedFeatures
                private class ThroughPortableInterface : PortableFeatures

                private class ThroughOverride : PromotedFeatures {
                    private var localState: String = ""

                    override var observed: String
                        get() = "override:" + localState
                        set(value) {
                            localState = value
                        }

                    override fun combine(first: String, second: String): String =
                        "override:" + first + ":" + second
                }

                private class ThroughAbstractOverride : PromotedAbstractDefaults {
                    override fun abstractCombine(first: String, second: String): String =
                        "abstract:" + first + ":" + second
                }

                fun main() {

                    val promoted: PortableFeatures = ThroughPromotion()
                    promoted.observed = "promoted"
                    if (promoted.observed != "promoted") {
                        throw Error("promoted property accessors")
                    }
                    if (promoted.combine() != "OK") {
                        throw Error("promoted default arguments")
                    }
                    if (promoted.combine(second = "!") != "O!") {
                        throw Error("promoted named default argument")
                    }

                    val portable: PortableFeatures = ThroughPortableInterface()
                    portable.observed = "portable"
                    if (portable.observed != "portable") {
                        throw Error("portable property forwarders")
                    }
                    if (portable.combine() != "OK") {
                        throw Error("portable default-argument forwarder")
                    }

                    val overridden: PortableFeatures = ThroughOverride()
                    overridden.observed = "consumer"
                    if (overridden.observed != "override:consumer") {
                        throw Error("property override dispatch")
                    }
                    if (overridden.combine() != "override:O:K") {
                        throw Error("default-argument helper bypassed override")
                    }

                    val abstractDefaults: PortableAbstractDefaults = ThroughAbstractOverride()
                    if (abstractDefaults.abstractCombine() != "abstract:O:K") {
                        throw Error("abstract interface default arguments")
                    }
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("PromotedFeaturesConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument,
            listOf(portableMetadata, derivedMetadata).joinToString(File.pathSeparator) { it.path },
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "PromotedFeaturesConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )

        val consumerIl = consumerDirectory.resolve("PromotedFeaturesConsumer.il").readText()
        assertEquals(
            3,
            Regex("<InterfaceDefaultForwarder-").findAll(consumerIl).count(),
            consumerIl,
        )
        assertTrue("combine\$default" in consumerIl) { consumerIl }
        runDotNet(
            dotnetHost,
            consumerAssembly,
            consumerDirectory,
            "Cross-module promoted accessors/default arguments failed",
        )
    }

    @Test
    fun testGenericExternalInterfaceDefaultDispatcherPreservesOwnerTypeContext() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ILAsm is not available")
        requireOrAssumeToolchain(DotNetIlAssembler.findFrameworkIlasm() != null, "Framework ILAsm is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val frameworkHost = DotNetIlAssembler.findFrameworkPowerShellHost()
        requireOrAssumeToolchain(frameworkHost != null, "Windows PowerShell CLR 4 host is not available")

        val producerDirectory = File(tmpdir, "generic-default-dispatcher-producer").apply { mkdirs() }
        val producerSource = producerDirectory.resolve("producer.kt").apply {
            writeText(
                """
                package genericdefaults

                public interface GenericDefaults<T> {
                    public fun choose(value: T, fallback: T, useFallback: Boolean = false): T =
                        if (useFallback) fallback else value
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            producerSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Generic.Defaults",
            K2DotNetCompilerArguments::destination.cliArgument, producerDirectory.path,
        )
        val producerMetadata = producerDirectory.resolve("Generic.Defaults.klib")
        val chooseDeclaration = DotNetLibraryAbiCodec.decode(producerMetadata.readKlibManifest()).values
            .filterIsInstance<DotNetPhysicalDeclaration.Function>()
            .single { it.defaultArgumentDispatcher != null }
        assertTrue(chooseDeclaration.isInstance)

        val consumerSource = File(tmpdir, "generic-default-dispatcher-consumer.kt").apply {
            writeText(
                """
                package genericdefaults

                private class StringDefaults : GenericDefaults<String>

                fun main() {
                    val defaults: GenericDefaults<String> = StringDefaults()
                    if (defaults.choose("O", "bad") != "O") throw Error("ordinary default")
                    if (defaults.choose("bad", "K", true) != "K") throw Error("explicit argument")
                    println("OK")
                }
                """.trimIndent()
            )
        }
        for (target in listOf("net48", "net10.0")) {
            val consumerDirectory = File(tmpdir, "generic-default-dispatcher-$target").apply { mkdirs() }
            val consumerAssembly = consumerDirectory.resolve(
                if (target == "net48") "GenericDefaultsConsumer.exe" else "GenericDefaultsConsumer.dll"
            )
            compileInProcess(
                K2DotNetCompiler(),
                consumerSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, producerMetadata.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "GenericDefaultsConsumer",
                K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
            )
            val consumerIl = consumerDirectory.resolve("GenericDefaultsConsumer.il").readText()
            assertTrue("[Generic.Defaults]" in consumerIl) { consumerIl }
            assertTrue("choose\$default" in consumerIl) { consumerIl }

            if (target == "net48") {
                runAssemblerPairing(
                    frameworkExecutionCommand(checkNotNull(frameworkHost), consumerAssembly),
                    consumerDirectory,
                    "Framework generic external default dispatcher",
                )
            } else {
                runDotNet(
                    dotnetHost,
                    consumerAssembly,
                    consumerDirectory,
                    "CoreCLR generic external default dispatcher failed",
                )
            }
        }
    }

    @Test
    fun testRejectsInterfaceSuperCallsWithOmittedDefaultArguments() {
        val source = File(tmpdir, "super-call-with-default-arguments.kt").apply {
            writeText(
                """
                interface Base {
                    fun value(prefix: String = "O", suffix: String = "K"): String = prefix + suffix
                }

                class Derived : Base {
                    fun invalid(): String = super<Base>.value()
                }
                """.trimIndent()
            )
        }

        for (useLightTree in listOf(false, true)) {
            val [diagnostics, exitCode] = AbstractCliTest.executeCompilerGrabOutput(
                K2DotNetCompiler(),
                listOf(
                    source.path,
                    "-Xuse-fir-lt=$useLightTree",
                    K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
                    K2DotNetCompilerArguments::destination.cliArgument,
                    File(tmpdir, "super-call-with-default-arguments-$useLightTree.il").path,
                )
            )
            assertEquals(ExitCode.COMPILATION_ERROR, exitCode, diagnostics)
            assertTrue("super-calls with default arguments are prohibited" in diagnostics) { diagnostics }
        }
    }

    @Test
    fun testProducesCompanionLanguageMetadataContract() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val source = File(tmpdir, "library.kt").apply {
            writeText(
                """
                package sample

                public fun marker(): Unit = Unit
                """.trimIndent()
            )
        }
        val outputDirectory = File(tmpdir, "companion-metadata-library")
        compileInProcess(
            K2DotNetCompiler(),
            source.path,
            "-Xcompanion-blocks",
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Companion.Metadata.Library",
            K2DotNetCompilerArguments::destination.cliArgument, outputDirectory.path,
        )

        val manifest = outputDirectory.resolve("Companion.Metadata.Library.klib").readKlibManifest()
        assertTrue(manifest.getProperty(KLIB_PROPERTY_METADATA_FLAGS)?.toIntOrNull() != null)
        assertEquals("true", manifest.getProperty(KLIB_PROPERTY_NEW_COMPANION_INITIALIZATION))
        assertTrue(
            manifest.getProperty(KLIB_PROPERTY_MANUALLY_ALTERED_LANGUAGE_FEATURES)
                .split(' ')
                .contains("+CompanionBlocks")
        )
    }

    @Test
    fun testCompanionExtensionsUseReceiverFreeCrossModuleAbi() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val csharpToolchain = DotNetIlAssembler.findModernCSharpCompiler()
        requireOrAssumeToolchain(csharpToolchain != null, "Modern Roslyn and the net10 reference pack are not available")
        val producerDirectory = File(tmpdir, "companion-extension-producer")
        val producerSource = File(tmpdir, "library.kt").apply {
            writeText(
                """
                package companionlib

                private var targetInitialized = false

                private fun initializeTarget(): Int {
                    targetInitialized = true
                    return 40
                }

                public class Target {
                    companion {
                        public val state: Int = initializeTarget()
                        public fun blockAnswer(delta: Int): Int = state + delta
                    }
                }

                public companion fun Target.answer(value: Int): Int = value + 1
                public companion fun Target.wasInitialized(): Boolean = targetInitialized
                public companion val Target.label: String
                    get() = "receiver-free"
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            producerSource.path,
            "-Xcompanion-blocks-and-extensions",
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Companion.Extension.Library",
            K2DotNetCompilerArguments::destination.cliArgument, producerDirectory.path,
        )

        val producerIl = producerDirectory.resolve("Companion.Extension.Library.il").readText()
        assertTrue("static int32 'answer'(int32 'value')" in producerIl) { producerIl }
        assertTrue("static bool 'wasInitialized'()" in producerIl) { producerIl }
        assertTrue("static string 'get_label'()" in producerIl) { producerIl }
        assertTrue(".property string 'label'" !in producerIl) { producerIl }
        assertTrue("static int32 'state'" in producerIl) { producerIl }
        assertTrue("static int32 'blockAnswer'(int32 'delta')" in producerIl) { producerIl }

        val consumerDirectory = File(tmpdir, "companion-extension-consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package consumer

                import companionlib.Target
                import companionlib.answer
                import companionlib.label
                import companionlib.wasInitialized

                fun main() {
                    if (Target.answer(41) != 42) throw Error("answer")
                    if (Target.label != "receiver-free") throw Error("label")
                    if (Target.wasInitialized()) throw Error("extension initialized target")
                    if (Target.blockAnswer(2) != 42) throw Error("block")
                    if (!Target.wasInitialized()) throw Error("block did not initialize target")
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("CompanionExtensionConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            "-Xcompanion-blocks-and-extensions",
            K2DotNetCompilerArguments::classpath.cliArgument,
            producerDirectory.resolve("Companion.Extension.Library.klib").path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CompanionExtensionConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )

        val consumerIl = consumerDirectory.resolve("CompanionExtensionConsumer.il").readText()
        assertTrue(
            "call int32 [Companion.Extension.Library]'companionlib.libraryKt'::'answer'(int32)" in consumerIl
        ) { consumerIl }
        assertTrue(
            "call string [Companion.Extension.Library]'companionlib.libraryKt'::'get_label'()" in consumerIl
        ) { consumerIl }
        assertTrue(
            "call int32 [Companion.Extension.Library]'companionlib.Target'::'blockAnswer'(int32)" in consumerIl
        ) { consumerIl }
        runDotNet(
            modernDotNetHostOrSkip(),
            consumerAssembly,
            consumerDirectory,
            "Companion-extension cross-module consumer failed",
        )

        val csharpDirectory = File(tmpdir, "companion-static-csharp-consumer").apply { mkdirs() }
        val csharpSource = csharpDirectory.resolve("Program.cs").apply {
            writeText(
                """
                public static class Program
                {
                    public static int Main()
                    {
                        if (companionlib.Target.blockAnswer(2) != 42)
                            return 1;
                        return companionlib.Target.state == 40 ? 0 : 2;
                    }
                }
                """.trimIndent()
            )
        }
        val csharpAssembly = csharpDirectory.resolve("CompanionStaticCSharpConsumer.dll")
        val producerAssembly = producerDirectory.resolve("Companion.Extension.Library.dll")
        val runtimeAssembly = consumerDirectory.resolve("Kotlin.Runtime.dll")
        val csharpCompile = runModernCSharpCompiler(
            checkNotNull(csharpToolchain),
            csharpSource,
            csharpAssembly,
            producerAssembly,
            runtimeAssembly,
            target = "exe",
        )
        assertEquals(0, csharpCompile.exitCode, csharpCompile.output)
        producerAssembly.copyTo(csharpDirectory.resolve(producerAssembly.name), overwrite = true)
        runtimeAssembly.copyTo(csharpDirectory.resolve(runtimeAssembly.name), overwrite = true)
        csharpDirectory.resolve("CompanionStaticCSharpConsumer.runtimeconfig.json").writeText(
            """
            {
              "runtimeOptions": {
                "tfm": "net10.0",
                "framework": {
                  "name": "Microsoft.NETCore.App",
                  "version": "10.0.0"
                },
                "rollForward": "LatestMinor"
              }
            }
            """.trimIndent()
        )
        runDotNet(
            checkNotNull(csharpToolchain).dotNetHost,
            csharpAssembly,
            csharpDirectory,
            "C# companion-static consumer failed",
        )
    }

    @Test
    fun testCompanionStaticHoldersBindAcrossModules() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ilasm is not available",
        )
        val producerDirectory = File(tmpdir, "companion-holder-producer")
        val producerSource = File(tmpdir, "holder-library.kt").apply {
            writeText(
                """
                package companionholder

                public class GenericOwner<T> private constructor(public val value: Int) {
                    public fun reveal(): Int = secret()

                    companion {
                        public const val marker: Int = 7
                        public val answer: Int get() = 42
                        private fun secret(): Int = 11
                        public fun create(value: Int = 40): GenericOwner<String> = GenericOwner(value)
                        public fun <R> echo(value: R): R = value
                    }
                }

                public interface GenericInterface<T> {
                    companion {
                        public const val marker: Int = 9
                        public val answer: Int get() = 43
                        public fun <R> echo(value: R): R = value
                    }
                }

                public class DirectOwner {
                    public fun instanceValue(): Int = 1

                    companion {
                        public fun answer(value: Int = 45): Int = value
                    }
                }

                public fun topLevelAnswer(value: Int = 46): Int = value
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            producerSource.path,
            "-Xcompanion-blocks",
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Companion.Holder.Library",
            K2DotNetCompilerArguments::destination.cliArgument, producerDirectory.path,
        )

        val producerIl = producerDirectory.resolve("Companion.Holder.Library.il").readText()
        assertTrue("'companionholder.GenericOwner`1'/'<CompanionStatics>'" in producerIl) { producerIl }
        assertTrue("'companionholder.GenericInterface'/'<CompanionStatics>'" in producerIl) { producerIl }
        assertTrue("static !!0 'echo'<'R'>(!!0 'value')" in producerIl) { producerIl }
        assertTrue("'create\$default'" in producerIl) { producerIl }
        assertTrue("KotlinCompilerAbiAttribute" in producerIl) { producerIl }

        for (target in listOf("net48", "net10.0")) {
            val profileDirectory = File(tmpdir, "companion-holder-$target")
            compileInProcess(
                K2DotNetCompiler(),
                producerSource.path,
                "-Xcompanion-blocks",
                K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "Companion.Holder.Library",
                K2DotNetCompilerArguments::destination.cliArgument, profileDirectory.path,
            )
            val profileIl = profileDirectory.resolve("Companion.Holder.Library.il").readText()
            assertTrue("'companionholder.GenericOwner`1'/'<CompanionStatics>'" in profileIl) { profileIl }
            assertTrue("'companionholder.GenericInterface'/'<CompanionStatics>'" in profileIl) { profileIl }
            assertTrue("static !!0 'echo'<'R'>(!!0 'value')" in profileIl) { profileIl }
        }

        val consumerDirectory = File(tmpdir, "companion-holder-consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package consumer

                import companionholder.GenericInterface
                import companionholder.GenericOwner
                import companionholder.DirectOwner
                import companionholder.topLevelAnswer

                fun main() {
                    val owner = GenericOwner.create()
                    if (owner.value != 40) throw Error("default")
                    if (owner.reveal() != 11) throw Error("private bridge")
                    if (GenericOwner.marker != 7) throw Error("class const")
                    if (GenericOwner.answer != 42) throw Error("class property")
                    if (GenericOwner.echo("OK") != "OK") throw Error("class generic method")
                    if (GenericInterface.marker != 9) throw Error("interface const")
                    if (GenericInterface.answer != 43) throw Error("interface property")
                    if (GenericInterface.echo(44) != 44) throw Error("interface generic method")
                    val directOwner = DirectOwner()
                    if (directOwner.instanceValue() != 1) throw Error("direct class record")
                    if (DirectOwner.answer() != 45) throw Error("direct class default")
                    if (topLevelAnswer() != 46) throw Error("top-level default")
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("CompanionHolderConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            "-Xcompanion-blocks",
            K2DotNetCompilerArguments::classpath.cliArgument,
            producerDirectory.resolve("Companion.Holder.Library.klib").path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CompanionHolderConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )

        val consumerIl = consumerDirectory.resolve("CompanionHolderConsumer.il").readText()
        val classHolder =
            "[Companion.Holder.Library]'companionholder.GenericOwner`1'/'<CompanionStatics>'"
        val interfaceHolder =
            "[Companion.Holder.Library]'companionholder.GenericInterface'/'<CompanionStatics>'"
        assertTrue("$classHolder::'create\$default'(int32, int32)" in consumerIl) { consumerIl }
        assertTrue("$classHolder::'get_answer'()" in consumerIl) { consumerIl }
        assertTrue("$classHolder::'echo'<string>(!!0)" in consumerIl) { consumerIl }
        assertTrue("$interfaceHolder::'get_answer'()" in consumerIl) { consumerIl }
        assertTrue("$interfaceHolder::'echo'<int32>(!!0)" in consumerIl) { consumerIl }
        runDotNet(
            modernDotNetHostOrSkip(),
            consumerAssembly,
            consumerDirectory,
            "Companion-holder cross-module consumer failed",
        )
    }

    @Test
    fun testCompanionInitializationGraphBindsAcrossModules() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ilasm is not available",
        )
        val producerSource = File(tmpdir, "companion-initialization-library.kt").apply {
            writeText(
                """
                package companioninit

                private var initializationOrder: String = ""

                public fun recordInitialization(tag: String): String {
                    initializationOrder += tag
                    return tag
                }

                public fun currentInitializationOrder(): String = initializationOrder

                public open class Parent {
                    companion {
                        public val state: String = recordInitialization("P")
                    }
                }

                public interface SelectedDefault {
                    companion {
                        public val state: String = recordInitialization("I")
                    }

                    public fun selected(): String = "selected"
                }

                public interface AbstractOnly {
                    companion {
                        public val state: String = recordInitialization("A")
                    }

                    public fun abstractMember(): String
                }

                public open class ProducerChild : Parent(), AbstractOnly, SelectedDefault {
                    companion {
                        public val state: String = recordInitialization("C")
                    }

                    override fun abstractMember(): String = "abstract"
                }

                public object ProducerSingleton {
                    public val state: String = recordInitialization("O")
                }

                public class GenericProducer<T> {
                    companion object {
                        public val state: String = recordInitialization("G")
                    }
                }

                public open class GenericPrivateState<T> {
                    companion {
                        private val state: String = recordInitialization("H")
                    }
                }

                public class PortableMixed<T> {
                    companion {
                        public val first: String = "first"
                    }

                    companion object {
                        public val second: String = "second"
                    }

                    companion {
                        public val third: String = "third"
                    }
                }

                public interface PortableInterfaceMixed {
                    companion {
                        public val first: String = "first"
                    }

                    companion object {
                        public val second: String = "second"
                    }

                    companion {
                        public val third: String = "third"
                    }
                }
                """.trimIndent()
            )
        }

        fun compileProducer(target: String, directory: File) {
            compileInProcess(
                K2DotNetCompiler(),
                producerSource.path,
                "-Xcompanion-blocks",
                K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "Companion.Initialization.Library",
                K2DotNetCompilerArguments::destination.cliArgument, directory.path,
            )
            val il = directory.resolve("Companion.Initialization.Library.il").readText()
            assertTrue("'<EnsureCompanionInitialized>'" in il) { il }
            assertTrue("KotlinCompilerAbiAttribute" in il) { il }
        }

        val producerDirectory = File(tmpdir, "companion-initialization-producer")
        compileProducer("netstandard2.0", producerDirectory)
        compileProducer("net48", File(tmpdir, "companion-initialization-net48"))
        compileProducer("net10.0", File(tmpdir, "companion-initialization-net10"))

        val consumerDirectory = File(tmpdir, "companion-initialization-consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package consumer

                import companioninit.AbstractOnly
                import companioninit.GenericProducer
                import companioninit.GenericPrivateState
                import companioninit.ProducerChild
                import companioninit.ProducerSingleton
                import companioninit.currentInitializationOrder
                import companioninit.recordInitialization

                class ConsumerChild : ProducerChild() {
                    companion {
                        val state: String = recordInitialization("D")
                    }
                }


                class GenericPrivateConsumer : GenericPrivateState<String>() {
                    companion {
                        val state: String = recordInitialization("J")
                    }
                }

                fun main() {
                    if (ConsumerChild.state != "D") throw Error("consumer state")
                    if (currentInitializationOrder() != "PICD") {
                        throw Error("cross-module order=" + currentInitializationOrder())
                    }
                    if (AbstractOnly.state != "A") throw Error("abstract-only state")
                    if (currentInitializationOrder() != "PICDA") {
                        throw Error("abstract-only order=" + currentInitializationOrder())
                    }
                    ConsumerChild()
                    if (currentInitializationOrder() != "PICDA") throw Error("reinitialized")

                    if (ProducerSingleton.state != "O") throw Error("ordinary object state")
                    if (GenericProducer.state != "G") throw Error("generic companion state")
                    GenericProducer<String>()
                    GenericProducer<Int>()
                    if (currentInitializationOrder() != "PICDAOG") {
                        throw Error("object binding order=" + currentInitializationOrder())
                    }
                    if (GenericPrivateConsumer.state != "J") throw Error("private holder state")
                    if (currentInitializationOrder() != "PICDAOGHJ") {
                        throw Error("private holder order=" + currentInitializationOrder())
                    }
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("CompanionInitializationConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            "-Xcompanion-blocks",
            K2DotNetCompilerArguments::classpath.cliArgument,
            producerDirectory.resolve("Companion.Initialization.Library.klib").path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CompanionInitializationConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )

        val consumerIl = consumerDirectory.resolve("CompanionInitializationConsumer.il").readText()
        assertTrue(
            "call void [Companion.Initialization.Library]'companioninit.ProducerChild'::" +
                    "'<EnsureCompanionInitialized>'()" in consumerIl
        ) { consumerIl }
        assertTrue("ldsfld" in consumerIl && "'<CompanionStatics>'::'Companion'" in consumerIl) { consumerIl }
        assertTrue("'companioninit.ProducerSingleton'::'INSTANCE'" in consumerIl) { consumerIl }
        assertTrue(
            "call void [Companion.Initialization.Library]'companioninit.GenericPrivateState`1'/" +
                    "'<CompanionStatics>'::'<EnsureCompanionInitialized>'()" in consumerIl
        ) { consumerIl }
        runDotNet(
            modernDotNetHostOrSkip(),
            consumerAssembly,
            consumerDirectory,
            "Companion-initialization cross-module consumer failed",
        )
    }

    @Test
    fun testProducesPortableUserLibraryPair() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val source = File(tmpdir, "library.kt").apply {
            writeText(
                """
                package sample

                public fun increment(value: Int): Int = value + 1

                public class Counter(public val value: Int) {
                    public fun plus(delta: Int): Int = value + delta
                }
                """.trimIndent()
            )
        }
        val outputDirectory = File(tmpdir, "sample-library")
        compileInProcess(
            K2DotNetCompiler(),
            source.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Sample.Library",
            K2DotNetCompilerArguments::dotNetExports.cliArgument, "sample.increment=Increment",
            K2DotNetCompilerArguments::destination.cliArgument, outputDirectory.path,
        )

        val metadataLibrary = outputDirectory.resolve("Sample.Library.klib")
        val implementationLibrary = outputDirectory.resolve("Sample.Library.dll")
        assertTrue(metadataLibrary.isFile) { "Expected packed metadata KLIB at $metadataLibrary" }
        assertTrue(implementationLibrary.isFile) { "Expected CLR implementation at $implementationLibrary" }
        val manifest = metadataLibrary.readKlibManifest()
        assertTrue(manifest.getProperty("unique_name") == "Sample.Library")
        assertTrue(manifest.getProperty("dotnet_assembly_name") == "Sample.Library")
        assertTrue(manifest.getProperty("dotnet_assembly_version") == "1.0.0.0")
        assertTrue(manifest.getProperty("dotnet_assembly_file") == "Sample.Library.dll")
        assertEquals("netstandard2.0", manifest.getProperty("dotnet_library_tfm"))
        assertEquals(DotNetLibraryAbiCodec.ABI_VERSION, manifest.getProperty("dotnet_abi_version"))
        assertEquals("", manifest.getProperty(DotNetLibraryAbiCodec.FRIEND_ASSEMBLIES_PROPERTY))
        assertTrue(
            manifest.getProperty(DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME_PROPERTY) ==
                    DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME
        )
        assertTrue(
            manifest.getProperty(DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION_PROPERTY) ==
                    DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION
        )
        assertTrue(
            manifest.getProperty(DotNetLibraryAbiCodec.RUNTIME_SURFACE_LEVEL_PROPERTY) ==
                    DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL.toString()
        )
        assertEquals(
            DotNetLibraryAbiCodec.implementationSha256(implementationLibrary),
            manifest.getProperty(DotNetLibraryAbiCodec.IMPLEMENTATION_SHA256_PROPERTY),
        )
        assertTrue(manifest.stringPropertyNames().any { it.startsWith("dotnet_decl_") })

        val il = outputDirectory.resolve("Sample.Library.il").readText()
        assertTrue(".assembly extern netstandard" in il)
        assertTrue("System.Runtime.Versioning.TargetFrameworkAttribute" in il)
        assertTrue(".ver 1:0:0:0" in il)
        assertTrue(".module 'Sample.Library.dll'" in il)
        assertTrue("'Increment'(int32 'value')" in il)
        assertTrue(".entrypoint" !in il)
        assertTrue("[mscorlib]" !in il)

        val dotnetHost = modernDotNetHostOrSkip()
        val consumerIl = outputDirectory.resolve("LibraryConsumer.il").apply {
            writeText(
                """
                .assembly extern mscorlib {}
                .assembly extern Sample.Library
                {
                  .ver 1:0:0:0
                }
                .assembly LibraryConsumer {}
                .module LibraryConsumer.dll

                .method public static void Main() cil managed
                {
                  .entrypoint
                  .maxstack 2
                  ldc.i4.s 41
                  call int32 [Sample.Library]'sample.libraryKt'::'Increment'(int32)
                  ldc.i4.s 42
                  beq.s IL_success
                  ldstr "Portable Kotlin library returned an unexpected result."
                  newobj instance void [mscorlib]System.Exception::.ctor(string)
                  throw
                IL_success:
                  ret
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = outputDirectory.resolve("LibraryConsumer.dll")
        assertTrue(
            DotNetIlAssembler.assembleExecutable(
                consumerIl,
                consumerAssembly,
                DotNetTarget.NET10_0,
                MessageCollector.NONE,
            )
        )
        runDotNet(dotnetHost, consumerAssembly, outputDirectory, "Portable library consumer failed")

        val kotlinConsumerDirectory = outputDirectory.resolve("kotlin-consumer").apply { mkdirs() }
        val kotlinConsumerSource = kotlinConsumerDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package consumer

                import sample.Counter
                import sample.increment

                fun main() {
                    val answer = increment(Counter(40).plus(1))
                    if (answer != 42) throw Error("Kotlin library returned ${'$'}answer")
                }
                """.trimIndent()
            )
        }
        val kotlinConsumerAssembly = kotlinConsumerDirectory.resolve("KotlinConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            kotlinConsumerSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "KotlinConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, kotlinConsumerAssembly.path,
        )
        assertTrue(kotlinConsumerDirectory.resolve("Sample.Library.dll").isFile) {
            "The external CLR implementation must be packaged beside an executable consumer"
        }
        val kotlinConsumerIl = kotlinConsumerDirectory.resolve("KotlinConsumer.il").readText()
        assertTrue(".assembly extern 'Sample.Library'" in kotlinConsumerIl)
        assertTrue("[Sample.Library]" in kotlinConsumerIl)
        runDotNet(
            dotnetHost,
            kotlinConsumerAssembly,
            kotlinConsumerDirectory,
            "Kotlin cross-module library consumer failed",
        )

        val unrelatedConsumerDirectory = outputDirectory.resolve("unrelated-consumer").apply { mkdirs() }
        val unrelatedConsumerSource = unrelatedConsumerDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package unrelated

                fun main() {
                    if (40 + 2 != 42) throw Error("arithmetic")
                }
                """.trimIndent()
            )
        }
        val unrelatedConsumerAssembly = unrelatedConsumerDirectory.resolve("UnrelatedConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            unrelatedConsumerSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "UnrelatedConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, unrelatedConsumerAssembly.path,
        )
        val unrelatedConsumerIl = unrelatedConsumerDirectory.resolve("UnrelatedConsumer.il").readText()
        assertTrue(".assembly extern 'Sample.Library'" !in unrelatedConsumerIl)
        assertTrue("[Sample.Library]" !in unrelatedConsumerIl)
        assertTrue(!unrelatedConsumerDirectory.resolve("Sample.Library.dll").exists()) {
            "An unused metadata classpath entry must not become a CLR runtime dependency"
        }
        runDotNet(
            dotnetHost,
            unrelatedConsumerAssembly,
            unrelatedConsumerDirectory,
            "Consumer with an unused Kotlin/.NET classpath library failed",
        )
    }

    @Test
    fun testTargetProfilesAreExplicitAndDependencyCompatible() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        requireOrAssumeToolchain(DotNetIlAssembler.findFrameworkIlasm() != null, ".NET Framework ilasm is not available")

        fun produceLibrary(target: String, assemblyName: String): File {
            val directory = File(tmpdir, assemblyName)
            val source = File(tmpdir, "$assemblyName.kt").apply {
                writeText("package profiles\n\npublic fun answer(): Int = 42")
            }
            compileInProcess(
                K2DotNetCompiler(),
                source.path,
                K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, assemblyName,
                K2DotNetCompilerArguments::destination.cliArgument, directory.path,
            )
            val metadata = directory.resolve("$assemblyName.klib")
            assertEquals(target, metadata.readKlibManifest().getProperty("dotnet_library_tfm"))
            val il = directory.resolve("$assemblyName.il").readText()
            assertTrue("System.Runtime.Versioning.TargetFrameworkAttribute" in il) { il }
            if (target == "netstandard2.0") {
                assertTrue(".assembly extern netstandard" in il) { il }
                assertTrue("[mscorlib]" !in il) { il }
            } else {
                assertTrue(".assembly extern mscorlib" in il) { il }
            }
            return metadata
        }

        val net48Library = produceLibrary("net48", "Profile.Net48")
        val portableLibrary = produceLibrary("netstandard2.0", "Profile.Standard")
        val net10Library = produceLibrary("net10.0", "Profile.Net10")
        val consumerSource = File(tmpdir, "profile-consumer.kt").apply {
            writeText("package consumer\n\npublic fun consume(): Int = profiles.answer()")
        }

        fun compileConsumer(target: String, dependency: File, outputName: String): Pair<String, ExitCode> =
            AbstractCliTest.executeCompilerGrabOutput(
                K2DotNetCompiler(),
                listOf(
                    consumerSource.path,
                    K2DotNetCompilerArguments::classpath.cliArgument, dependency.path,
                    K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                    K2DotNetCompilerArguments::moduleName.cliArgument, outputName,
                    K2DotNetCompilerArguments::destination.cliArgument, File(tmpdir, "$outputName.il").path,
                )
            )

        for (target in listOf("net48", "net10.0")) {
            val [diagnostics, exitCode] = compileConsumer(target, portableLibrary, "PortableOn-$target")
            assertEquals(ExitCode.OK, exitCode, diagnostics)
        }
        for (entry in listOf("net48" to net10Library, "net10.0" to net48Library)) {
            val target = entry.first
            val dependency = entry.second
            val [diagnostics, exitCode] = compileConsumer(target, dependency, "RejectedOn-$target")
            assertEquals(ExitCode.COMPILATION_ERROR, exitCode, diagnostics)
            assertTrue("is not compatible with Kotlin/.NET target '$target'" in diagnostics) { diagnostics }
        }

        val [executableDiagnostics, executableExitCode] = AbstractCliTest.executeCompilerGrabOutput(
            K2DotNetCompiler(),
            listOf(
                consumerSource.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
                K2DotNetCompilerArguments::destination.cliArgument, File(tmpdir, "InvalidStandardApp.il").path,
            )
        )
        assertEquals(ExitCode.COMPILATION_ERROR, executableExitCode, executableDiagnostics)
        assertTrue("target profile 'netstandard2.0' is library-only" in executableDiagnostics) {
            executableDiagnostics
        }

        val [standardDiagnostics, standardExitCode] = AbstractCliTest.executeCompilerGrabOutput(
            K2DotNetCompiler(),
            listOf(
                consumerSource.path,
                K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
                K2DotNetCompilerArguments::classpath.cliArgument, net48Library.path,
                K2DotNetCompilerArguments::moduleName.cliArgument, "Rejected.Standard.Consumer",
                K2DotNetCompilerArguments::destination.cliArgument, File(tmpdir, "rejected-standard-consumer").path,
            )
        )
        assertEquals(ExitCode.COMPILATION_ERROR, standardExitCode, standardDiagnostics)
        assertTrue("is not compatible with Kotlin/.NET target 'netstandard2.0'" in standardDiagnostics) {
            standardDiagnostics
        }
    }

    @Test
    fun testRuntimeStdlibVariantsArePortablePhysicalAbiSupersets() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        requireOrAssumeToolchain(DotNetIlAssembler.findFrameworkIlasm() != null, ".NET Framework ilasm is not available")
        val csharpToolchain = DotNetIlAssembler.findModernCSharpCompiler()
        requireOrAssumeToolchain(
            csharpToolchain != null,
            "Modern Roslyn and the net10 reference pack are not available",
        )

        val pairDirectories = listOf("netstandard2.0", "net48", "net10.0").associateWith { target ->
            produceBoundStdlibPair(target, "portable-abi-superset")
        }
        val runtimeAssemblies = DotNetTarget.entries.associateWith { target ->
            val outputDirectory = File(tmpdir, "portable-surface-runtime-${target.flagValue}")
            val runtime = DotNetIlAssembler.assembleRuntimeForTests(
                outputDirectory,
                target,
                MessageCollector.NONE,
            )
            assertTrue(runtime?.isFile == true) { "Failed to produce ${target.flagValue} Kotlin.Runtime.dll" }
            checkNotNull(runtime)
        }
        val surfaceVerifierSource = File(
            "compiler/testData/codegen/dotnet/portableSurfaceVerifier.cs"
        ).absoluteFile
        assertTrue(surfaceVerifierSource.isFile) { "Missing CLR surface verifier: $surfaceVerifierSource" }
        val surfaceVerifierDirectory = File(tmpdir, "portable-surface-verifier").apply { mkdirs() }
        val surfaceVerifier = surfaceVerifierDirectory.resolve("PortableSurfaceVerifier.dll")
        val surfaceVerifierCompile = runModernCSharpCompiler(
            checkNotNull(csharpToolchain),
            surfaceVerifierSource,
            surfaceVerifier,
            target = "exe",
        )
        assertEquals(0, surfaceVerifierCompile.exitCode, surfaceVerifierCompile.output)
        surfaceVerifierDirectory.resolve("PortableSurfaceVerifier.runtimeconfig.json").writeText(
            """
            {
              "runtimeOptions": {
                "tfm": "net10.0",
                "framework": {
                  "name": "Microsoft.NETCore.App",
                  "version": "10.0.0"
                },
                "rollForward": "LatestMinor"
              }
            }
            """.trimIndent()
        )
        val manifests = pairDirectories.mapValues { entry ->
            entry.value.resolve("Kotlin.Stdlib.klib").readKlibManifest()
        }
        val portableManifest = manifests.getValue("netstandard2.0")
        val portableDeclarations = DotNetLibraryAbiCodec.decode(portableManifest)
        val portableRuntimeSurface = portableManifest
            .getProperty(DotNetLibraryAbiCodec.RUNTIME_SURFACE_LEVEL_PROPERTY)
            .toInt()
        assertTrue(portableDeclarations.isNotEmpty())

        for (target in listOf("net48", "net10.0")) {
            val platformManifest = manifests.getValue(target)
            assertEquals(
                portableManifest.getProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY),
                platformManifest.getProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY),
                "$target changed the physical-index schema",
            )
            assertEquals(
                portableManifest.getProperty(DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME_PROPERTY),
                platformManifest.getProperty(DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME_PROPERTY),
                "$target changed the Kotlin logical-identity scheme",
            )
            assertEquals(
                portableManifest.getProperty(DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION_PROPERTY),
                platformManifest.getProperty(DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION_PROPERTY),
                "$target changed the CLR physical-name grammar",
            )
            val platformRuntimeSurface = platformManifest
                .getProperty(DotNetLibraryAbiCodec.RUNTIME_SURFACE_LEVEL_PROPERTY)
                .toInt()
            assertTrue(platformRuntimeSurface >= portableRuntimeSurface) {
                "$target requires runtime surface $platformRuntimeSurface below the portable floor $portableRuntimeSurface"
            }

            val differences = DotNetLibraryAbiCodec.portablePhysicalAbiDifferences(
                portableDeclarations,
                DotNetLibraryAbiCodec.decode(platformManifest),
            )
            assertTrue(differences.isEmpty()) {
                buildString {
                    appendLine("$target is not a physical ABI superset of netstandard2.0:")
                    differences.forEach { difference ->
                        append("  ")
                        append(difference.logicalKey)
                        append(": portable=")
                        append(difference.portableDeclaration)
                        append(", platform=")
                        appendLine(difference.platformDeclaration ?: "<missing>")
                    }
                }
            }

            val portableDirectory = pairDirectories.getValue("netstandard2.0")
            val platformDirectory = pairDirectories.getValue(target)
            val comparison = ProcessBuilder(
                checkNotNull(csharpToolchain).dotNetHost.path,
                "exec",
                surfaceVerifier.path,
                runtimeAssemblies.getValue(DotNetTarget.NETSTANDARD_2_0).path,
                runtimeAssemblies.getValue(checkNotNull(DotNetTarget.fromFlagValue(target))).path,
                portableDirectory.resolve("Kotlin.Stdlib.dll").path,
                platformDirectory.resolve("Kotlin.Stdlib.dll").path,
            ).directory(surfaceVerifierDirectory).redirectErrorStream(true).start()
            val comparisonOutput = comparison.inputStream.bufferedReader().use { it.readText() }
            assertEquals(
                0,
                comparison.waitFor(),
                "$target is not an externally consumable CLR metadata superset of netstandard2.0:\n" +
                        comparisonOutput,
            )
            assertTrue(Regex("OK [1-9][0-9]*").matches(comparisonOutput.trim())) { comparisonOutput }
        }

    }

    @Test
    fun testVarargLogicalIdentitySurvivesLibraryLowering() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val libraryDirectory = File(tmpdir, "vararg-library")
        val librarySource = File(tmpdir, "vararg-library.kt").apply {
            writeText(
                """
                package crossvararg

                public fun sum(vararg values: Int): Int = values[0] + values[1]
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CrossVararg.Library",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val consumerDirectory = File(tmpdir, "vararg-consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package consumer

                import crossvararg.sum

                fun main() {
                    if (sum(20, 22) != 42) throw Error("cross-module vararg")
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("CrossVarargConsumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument,
            libraryDirectory.resolve("CrossVararg.Library.klib").path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "CrossVarargConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )
        val consumerIl = consumerDirectory.resolve("CrossVarargConsumer.il").readText()
        assertTrue("[CrossVararg.Library]" in consumerIl)
        runDotNet(
            dotnetHost,
            consumerAssembly,
            consumerDirectory,
            "Cross-module vararg consumer failed",
        )
    }

    @Test
    fun testFriendAuthorizationAndPublishedCompilerAbiAcrossLibraryBoundary() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val csharpCompiler = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(csharpCompiler != null, ".NET Framework C# compiler is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val producerDirectory = File(tmpdir, "friend-producer")
        val producerSource = File(tmpdir, "friendProducer.kt").apply {
            writeText(
                """
                package friendship

                internal const val INTERNAL_CONST: Int = 19
                @PublishedApi internal const val PUBLISHED_CONST: Int = 20

                internal class InternalBox(internal val value: Int)

                @PublishedApi
                internal class PublishedBox(public val value: Int)

                internal fun internalAnswer(value: Int): Int = InternalBox(value).value + INTERNAL_CONST
                @PublishedApi internal fun publishedAnswer(value: Int): Int = value + PUBLISHED_CONST
                public fun publicAnswer(value: Int): Int = value + 2
                """.trimIndent()
            )
        }
        val longPublicKey =
            "0024000004800000940000000602000000240000525341310004000001000100" +
                    "8D56C76F9E8649383049F383C44BE0EC204181822A6C31CF5EB7EF486944D032" +
                    "188EA1D3920763712CCB12D75FB77E9811149E6148E5D32FBAAB37611C1878DD" +
                    "C19E20EF135D0CB2CFF2BFEC3D115810C3D9069638FE4BE215DBF795861920E5" +
                    "AB6F7DB2E2CEEF136AC23D5DD2BF031700AEC232F6C6B1C785B4305C123B37AB"
        compileInProcess(
            K2DotNetCompiler(),
            producerSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Friend.Producer",
            "${K2DotNetCompilerArguments::dotNetFriendAssemblies.cliArgument}=Friend.Consumer",
            "${K2DotNetCompilerArguments::dotNetFriendAssemblies.cliArgument}=" +
                    "Signed.Consumer, PublicKey=$longPublicKey",
            K2DotNetCompilerArguments::destination.cliArgument, producerDirectory.path,
        )

        val producerMetadata = producerDirectory.resolve("Friend.Producer.klib")
        val producerIl = producerDirectory.resolve("Friend.Producer.il").readText()
        val producerManifest = producerMetadata.readKlibManifest()
        assertEquals(
            setOf(
                DotNetFriendAssemblyIdentity("Friend.Consumer"),
                DotNetFriendAssemblyIdentity("Signed.Consumer", longPublicKey),
            ),
            DotNetLibraryAbiCodec.decodeFriendAssemblies(
                producerManifest.getProperty(DotNetLibraryAbiCodec.FRIEND_ASSEMBLIES_PROPERTY)
            ),
        )
        assertTrue("System.Runtime.CompilerServices.InternalsVisibleToAttribute" in producerIl) { producerIl }
        assertTrue(".method assembly hidebysig static int32 'internalAnswer'" in producerIl) { producerIl }
        assertTrue(".method public hidebysig static int32 'publishedAnswer'" in producerIl) { producerIl }
        assertTrue(".field assembly static literal int32 'INTERNAL_CONST'" in producerIl) { producerIl }
        assertTrue(".field public static literal int32 'PUBLISHED_CONST'" in producerIl) { producerIl }
        assertTrue(".class private auto ansi sealed beforefieldinit 'friendship.InternalBox'" in producerIl) { producerIl }
        assertTrue(".class public auto ansi sealed beforefieldinit 'friendship.PublishedBox'" in producerIl) { producerIl }
        assertTrue("'friendship.friendProducerKt'" in producerIl) { producerIl }
        assertTrue("KotlinCompilerAbiAttribute" in producerIl) { producerIl }
        assertTrue("System.ComponentModel.EditorBrowsableAttribute" in producerIl) { producerIl }

        val consumerDirectory = producerDirectory.resolve("authorized-consumer").apply { mkdirs() }
        val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package consumer

                import friendship.InternalBox
                import friendship.PublishedBox
                import friendship.internalAnswer
                import friendship.publishedAnswer
                import friendship.publicAnswer

                fun main() {
                    val answer = internalAnswer(1) + InternalBox(1).value +
                            publishedAnswer(1) + PublishedBox(1).value + publicAnswer(1)
                    if (answer != 46) throw Error("friend result: ${'$'}answer")
                }
                """.trimIndent()
            )
        }
        val consumerAssembly = consumerDirectory.resolve("Friend.Consumer.dll")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument, producerMetadata.path,
            K2DotNetCompilerArguments::friendPaths.cliArgument, producerMetadata.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Friend.Consumer",
            K2DotNetCompilerArguments::destination.cliArgument, consumerAssembly.path,
        )
        runDotNet(
            dotnetHost,
            consumerAssembly,
            consumerDirectory,
            "Authorized Kotlin/.NET friend consumer failed",
        )

        val csharpDirectory = File(tmpdir, "friend-cs").apply { mkdirs() }
        val csharpSource = csharpDirectory.resolve("Program.cs").apply { writeText(
            """
            public static class Program
            {
                public static int Main()
                {
                    return friendship.friendProducerKt.internalAnswer(23) == 42 ? 0 : 1;
                }
            }
            """.trimIndent()
        ) }
        val frameworkProducerIl = csharpDirectory.resolve("Friend.Producer.il")
        compileInProcess(
            K2DotNetCompiler(),
            producerSource.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net48",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Friend.Producer",
            "${K2DotNetCompilerArguments::dotNetFriendAssemblies.cliArgument}=Friend.Consumer",
            K2DotNetCompilerArguments::destination.cliArgument, frameworkProducerIl.path,
        )
        val producerImplementation = csharpDirectory.resolve("Friend.Producer.dll")
        assertTrue(
            DotNetIlAssembler.assembleLibrary(
                frameworkProducerIl,
                producerImplementation,
                DotNetTarget.NET48,
                MessageCollector.NONE,
            )
        )
        val runtimeImplementation = consumerDirectory.resolve("Kotlin.Runtime.dll")
        val csharpExecutable = csharpDirectory.resolve("Friend.Consumer.exe")
        val csharpResult = runCSharpCompiler(
            checkNotNull(csharpCompiler),
            csharpSource,
            csharpExecutable,
            producerImplementation,
            runtimeImplementation,
            target = "exe",
        )
        assertEquals(0, csharpResult.exitCode, csharpResult.output)
        runtimeImplementation.copyTo(csharpDirectory.resolve(runtimeImplementation.name), overwrite = true)
        val csharpProcess = ProcessBuilder(csharpExecutable.path)
            .directory(csharpDirectory)
            .redirectErrorStream(true)
            .start()
        val csharpOutput = csharpProcess.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, csharpProcess.waitFor(), "Authorized C# friend consumer failed:\n$csharpOutput")

        val unauthorizedSource = File(tmpdir, "unauthorized-friend.kt").apply {
            writeText("package intruder\n\npublic fun answer(): Int = 42")
        }
        val [unauthorizedDiagnostics, unauthorizedExitCode] = AbstractCliTest.executeCompilerGrabOutput(
            K2DotNetCompiler(),
            listOf(
                unauthorizedSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, producerMetadata.path,
                K2DotNetCompilerArguments::friendPaths.cliArgument, producerMetadata.path,
                K2DotNetCompilerArguments::moduleName.cliArgument, "Unauthorized.Consumer",
                K2DotNetCompilerArguments::destination.cliArgument,
                File(tmpdir, "Unauthorized.Consumer.il").path,
            )
        )
        assertEquals(ExitCode.COMPILATION_ERROR, unauthorizedExitCode, unauthorizedDiagnostics)
        assertTrue("does not authorize unsigned consumer assembly 'Unauthorized.Consumer'" in unauthorizedDiagnostics) {
            unauthorizedDiagnostics
        }

        val nonFriendSource = File(tmpdir, "non-friend.kt").apply {
            writeText(
                """
                package nonfriend

                import friendship.internalAnswer

                public fun forbidden(): Int = internalAnswer(23)
                """.trimIndent()
            )
        }
        val [nonFriendDiagnostics, nonFriendExitCode] = AbstractCliTest.executeCompilerGrabOutput(
            K2DotNetCompiler(),
            listOf(
                nonFriendSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, producerMetadata.path,
                K2DotNetCompilerArguments::moduleName.cliArgument, "Friend.Consumer",
                K2DotNetCompilerArguments::destination.cliArgument, File(tmpdir, "NonFriend.il").path,
            )
        )
        assertEquals(ExitCode.COMPILATION_ERROR, nonFriendExitCode, nonFriendDiagnostics)
        assertTrue("internal" in nonFriendDiagnostics) { nonFriendDiagnostics }
    }

    @Test
    fun testProducesNet10StdlibPair() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        produceAndConsumeBoundStdlibPair("net10.0")
    }

    @Test
    fun testProducesNet48StdlibPair() {
        requireOrAssumeToolchain(DotNetIlAssembler.findFrameworkIlasm() != null, ".NET Framework ilasm is not available")
        produceAndConsumeBoundStdlibPair("net48")
    }

    @Test
    fun testPortableStdlibPairExecutesOnBothRuntimeProfiles() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        requireOrAssumeToolchain(DotNetIlAssembler.findFrameworkIlasm() != null, ".NET Framework ilasm is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val pairDirectory = produceBoundStdlibPair("netstandard2.0", "shared")
        consumeBoundStdlibPair(pairDirectory, "net48")
        consumeBoundStdlibPair(pairDirectory, "net10.0")
        consumeInstalledStdlibPair(pairDirectory, "net48", installedProfile = "netstandard2.0")
        consumeInstalledStdlibPair(pairDirectory, "net10.0", installedProfile = "netstandard2.0")
        executeBoundStdlibPair(pairDirectory, "net48", dotnetHost = null)
        executeBoundStdlibPair(pairDirectory, "net10.0", dotnetHost)
    }

    @Test
    fun testNet48AssemblerMatrix() {
        val frameworkIlasm = DotNetIlAssembler.findFrameworkIlasm()
        val modernIlasm = DotNetIlAssembler.findModernIlasm()
        requireOrAssumeToolchain(frameworkIlasm != null, ".NET Framework ILAsm is not available")
        requireOrAssumeToolchain(modernIlasm != null, "Modern ILAsm is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val frameworkHost = DotNetIlAssembler.findFrameworkPowerShellHost()
        requireOrAssumeToolchain(frameworkHost != null, "Windows PowerShell CLR 4 host is not available")

        val stdlibPair = produceBoundStdlibPair("net48", "assembler-matrix")
        val frameworkStdlib = stdlibPair.resolve("Kotlin.Stdlib.dll")
        val modernStdlib = File(tmpdir, "assembler-matrix-modern/Kotlin.Stdlib.dll")
        assertTrue(
            DotNetIlAssembler.assembleWithExplicitIlasm(
                checkNotNull(modernIlasm),
                stdlibPair.resolve("Kotlin.Stdlib.il"),
                modernStdlib,
                dll = true,
                messageCollector = MessageCollector.NONE,
            )
        )

        val applicationDirectory = File(tmpdir, "assembler-matrix-application").apply { mkdirs() }
        val applicationSource = applicationDirectory.resolve("main.kt").apply {
            writeText(
                """
                fun main() {
                    val values = Array<String>(2) { index -> if (index == 0) "O" else "K" }
                    val render = { values.asIterable().first() + values.asIterable().last() }
                    println(render())
                }
                """.trimIndent()
            )
        }
        val frameworkApplication = applicationDirectory.resolve("AssemblerMatrix.exe")
        compileInProcess(
            K2DotNetCompiler(),
            applicationSource.path,
            K2DotNetCompilerArguments::noStdlib.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, stdlibPair.resolve("Kotlin.Stdlib.klib").path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net48",
            K2DotNetCompilerArguments::moduleName.cliArgument, "AssemblerMatrix",
            K2DotNetCompilerArguments::destination.cliArgument, frameworkApplication.path,
        )
        val applicationIl = applicationDirectory.resolve("AssemblerMatrix.il")
        val modernFrameworkApplication = applicationDirectory.resolve("AssemblerMatrix-modern.exe")
        assertTrue(
            DotNetIlAssembler.assembleWithExplicitIlasm(
                checkNotNull(modernIlasm),
                applicationIl,
                modernFrameworkApplication,
                dll = false,
                messageCollector = MessageCollector.NONE,
            )
        )
        val modernCoreClrApplication = applicationDirectory.resolve("AssemblerMatrix-modern.dll")
        assertTrue(
            DotNetIlAssembler.assembleExecutable(
                applicationIl,
                modernCoreClrApplication,
                DotNetTarget.NET10_0,
                MessageCollector.NONE,
            )
        )

        val frameworkRuntime = applicationDirectory.resolve("Kotlin.Runtime.dll")
        assertTrue(frameworkRuntime.isFile)
        val modernRuntime = DotNetIlAssembler.assembleRuntimeWithExplicitIlasmForTests(
            File(tmpdir, "assembler-matrix-modern-runtime"),
            DotNetTarget.NET48,
            checkNotNull(modernIlasm),
            MessageCollector.NONE,
        )
        assertTrue(modernRuntime?.isFile == true)
        val coreClrRuntimeConfig = applicationDirectory.resolve("AssemblerMatrix-modern.runtimeconfig.json")
        assertTrue(coreClrRuntimeConfig.isFile)

        val applications = listOf(
            "f" to (frameworkApplication to frameworkApplication),
            "m" to (modernFrameworkApplication to modernCoreClrApplication),
        )
        val stdlibs = listOf(
            "f" to frameworkStdlib,
            "m" to modernStdlib,
        )
        val runtimes = listOf(
            "f" to frameworkRuntime,
            "m" to checkNotNull(modernRuntime),
        )
        for (application in applications) {
            val applicationAssembler = application.first
            val applicationFiles = application.second
            for (stdlibEntry in stdlibs) {
                val stdlibAssembler = stdlibEntry.first
                val stdlib = stdlibEntry.second
                for (runtimeEntry in runtimes) {
                    val runtimeAssembler = runtimeEntry.first
                    val runtime = runtimeEntry.second
                    val pairing = "$applicationAssembler-$stdlibAssembler-$runtimeAssembler"
                    val frameworkDirectory = File(tmpdir, "am-f-$pairing").apply { mkdirs() }
                    val frameworkExecutable = applicationFiles.first.copyTo(
                        frameworkDirectory.resolve("AssemblerMatrix.exe")
                    )
                    stdlib.copyTo(frameworkDirectory.resolve("Kotlin.Stdlib.dll"))
                    runtime.copyTo(frameworkDirectory.resolve("Kotlin.Runtime.dll"))
                    runAssemblerPairing(
                        frameworkExecutionCommand(checkNotNull(frameworkHost), frameworkExecutable),
                        frameworkDirectory,
                        "Framework host, $pairing",
                    )

                    val coreClrDirectory = File(tmpdir, "am-n-$pairing").apply { mkdirs() }
                    val coreClrExecutable = applicationFiles.second.copyTo(
                        coreClrDirectory.resolve("AssemblerMatrix.${applicationFiles.second.extension}")
                    )
                    coreClrRuntimeConfig.copyTo(coreClrDirectory.resolve("AssemblerMatrix.runtimeconfig.json"))
                    stdlib.copyTo(coreClrDirectory.resolve("Kotlin.Stdlib.dll"))
                    runtime.copyTo(coreClrDirectory.resolve("Kotlin.Runtime.dll"))
                    runAssemblerPairing(
                        listOf(dotnetHost.path, "exec", coreClrExecutable.path),
                        coreClrDirectory,
                        "CoreCLR host, $pairing",
                    )
                }
            }
        }
    }

    @Test
    fun testNet10AssemblerBoundary() {
        val frameworkIlasm = DotNetIlAssembler.findFrameworkIlasm()
        val modernIlasm = DotNetIlAssembler.findModernIlasm()
        requireOrAssumeToolchain(frameworkIlasm != null, ".NET Framework ILAsm is not available")
        requireOrAssumeToolchain(modernIlasm != null, "Modern ILAsm is not available")
        val dotnetHost = modernDotNetHostOrSkip()

        val stdlibPair = produceBoundStdlibPair("net10.0", "n10-boundary")
        val applicationDirectory = File(tmpdir, "n10-a").apply { mkdirs() }
        val applicationSource = applicationDirectory.resolve("main.kt").apply {
            writeText(
                """
                public interface Prefix {
                    public fun value(): String = "O"
                }

                public class DefaultPrefix : Prefix

                fun main() {
                    val prefix: Prefix = DefaultPrefix()
                    val suffix = Array<String>(1) { "K" }.asIterable().first()
                    println(prefix.value() + suffix)
                }
                """.trimIndent()
            )
        }
        val modernApplication = applicationDirectory.resolve("Net10Boundary.dll")
        compileInProcess(
            K2DotNetCompiler(),
            applicationSource.path,
            K2DotNetCompilerArguments::noStdlib.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, stdlibPair.resolve("Kotlin.Stdlib.klib").path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Net10Boundary",
            K2DotNetCompilerArguments::destination.cliArgument, modernApplication.path,
        )
        val applicationIl = applicationDirectory.resolve("Net10Boundary.il")
        val applicationIlText = applicationIl.readText()
        assertTrue("interface public abstract auto ansi 'Prefix'" in applicationIlText) { applicationIlText }
        assertTrue("virtual instance string 'value'() cil managed" in applicationIlText) { applicationIlText }
        assertFalse("abstract virtual instance string 'value'()" in applicationIlText) { applicationIlText }

        val rejectedFrameworkApplication = File(tmpdir, "n10-af/Net10Boundary.dll")
        assertFalse(
            DotNetIlAssembler.assembleWithExplicitIlasm(
                checkNotNull(frameworkIlasm),
                applicationIl,
                rejectedFrameworkApplication,
                dll = true,
                messageCollector = MessageCollector.NONE,
            )
        )
        assertFalse(rejectedFrameworkApplication.exists()) {
            "A failed legacy assembly attempt left a partial net10 binary: $rejectedFrameworkApplication"
        }

        val modernRuntime = applicationDirectory.resolve("Kotlin.Runtime.dll")
        assertTrue(modernRuntime.isFile)
        val modernStdlib = applicationDirectory.resolve("Kotlin.Stdlib.dll")
        assertTrue(modernStdlib.isFile)
        val runtimeConfig = applicationDirectory.resolve("Net10Boundary.runtimeconfig.json")
        assertTrue(runtimeConfig.isFile)
        runAssemblerPairing(
            listOf(dotnetHost.path, "exec", modernApplication.path),
            applicationDirectory,
            "CoreCLR net10 DIM writer boundary",
        )
    }

    @Test
    fun testLibraryPublicationFailsWhenADeclarationIsEvicted() {
        fun assertPublicationFails(
            moduleName: String,
            sourceText: String,
            vararg expectedDiagnostics: String,
        ) {
            val source = File(tmpdir, "$moduleName.kt").apply { writeText(sourceText.trimIndent()) }
            val outputDirectory = File(tmpdir, moduleName)
            val [output, exitCode] = AbstractCliTest.executeCompilerGrabOutput(
                K2DotNetCompiler(),
                listOf(
                    source.path,
                    K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
                    K2DotNetCompilerArguments::moduleName.cliArgument, moduleName,
                    K2DotNetCompilerArguments::destination.cliArgument, outputDirectory.path,
                )
            )

            assertEquals(ExitCode.COMPILATION_ERROR, exitCode, output)
            assertTrue("is not supported by the .NET backend and was skipped" in output) { output }
            expectedDiagnostics.forEach { diagnostic ->
                assertTrue(diagnostic in output) { "Missing '$diagnostic':\n$output" }
            }
            assertTrue(!outputDirectory.resolve("$moduleName.klib").exists())
            assertTrue(!outputDirectory.resolve("$moduleName.dll").exists())
        }

        assertPublicationFails(
            "Unsupported.Library",
            """
            package sample

            public fun unsupported(value: Float): Float = value
            """,
        )
        assertPublicationFails(
            "Generic.Interface.Clashes",
            """
            package sample

            public interface DeclaredAccessorClash<out T> {
                public val value: T
                public fun get_value(): T
            }

            public interface ExactAccessorClash<out T> {
                public var value: @UnsafeVariance T
                public fun set_value(value: @UnsafeVariance T)
            }

            public interface ReservedOwner<out T> {
                public fun accept(value: @UnsafeVariance T)
            }

            public interface ReservedOwner__KotlinExact<T>
            """,
            "clash on its declared CLR capability",
            "clash on its exact CLR capability",
            "maps to a duplicate canonical, declared, or exact IL type",
        )
    }

    @Test
    fun testKotlinVisibilityIsPreservedInClrMetadata() {
        val source = File(tmpdir, "visibility.kt").apply {
            writeText(
                """
                package surface

                private const val PRIVATE_CONST: Int = 1
                internal const val INTERNAL_CONST: Int = 2
                public const val PUBLIC_CONST: Int = 3

                private fun privateTop(): Int = PRIVATE_CONST
                internal fun internalTop(): Int = INTERNAL_CONST
                public fun publicTop(): Int = PUBLIC_CONST

                private class PrivateTop
                internal class InternalTop
                public open class PublicTop {
                    private fun privateMember(): Int = 1
                    internal fun internalMember(): Int = 2
                    protected fun protectedMember(): Int = 3
                    public fun publicMember(): Int = 4
                }

                public sealed class SealedTop
                """.trimIndent()
            )
        }
        val outputFile = File(tmpdir, "visibility.il")
        compileInProcess(
            K2DotNetCompiler(),
            source.path,
            K2DotNetCompilerArguments::moduleName.cliArgument, "Visibility",
            K2DotNetCompilerArguments::destination.cliArgument, outputFile.path,
        )

        val il = outputFile.readText()
        assertTrue(".class private auto ansi sealed beforefieldinit 'surface.PrivateTop'" in il) { il }
        assertTrue(".class private auto ansi sealed beforefieldinit 'surface.InternalTop'" in il) { il }
        assertTrue(".class public auto ansi beforefieldinit 'surface.PublicTop'" in il) { il }
        assertTrue(".class public abstract auto ansi beforefieldinit 'surface.SealedTop'" in il) { il }
        assertTrue(".method famandassem hidebysig specialname rtspecialname instance void .ctor()" in il) { il }
        assertTrue(".method assembly hidebysig static int32 'privateTop'()" in il) { il }
        assertTrue(".method assembly hidebysig static int32 'internalTop'()" in il) { il }
        assertTrue(".method public hidebysig static int32 'publicTop'()" in il) { il }
        assertTrue(".method private hidebysig instance int32 'privateMember'()" in il) { il }
        assertTrue(".method assembly hidebysig instance int32 'internalMember'()" in il) { il }
        assertTrue(".method family hidebysig instance int32 'protectedMember'()" in il) { il }
        assertTrue(".method public hidebysig instance int32 'publicMember'()" in il) { il }
        assertTrue(".field private static literal int32 'PRIVATE_CONST'" in il) { il }
        assertTrue(".field assembly static literal int32 'INTERNAL_CONST'" in il) { il }
        assertTrue(".field public static literal int32 'PUBLIC_CONST'" in il) { il }
    }

    @Test
    fun testCSharpCannotConsumeNonPublicKotlinSurface() {
        val frameworkIlasm = DotNetIlAssembler.findFrameworkIlasm()
        val csharpCompiler = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(frameworkIlasm != null, ".NET Framework ILAsm is not available")
        requireOrAssumeToolchain(csharpCompiler != null, ".NET Framework C# compiler is not available")
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Portable-library ILAsm is not available")

        val source = File(tmpdir, "visibilityConsumerLibrary.kt").apply {
            writeText(
                """
                package surface

                private const val PRIVATE_CONST: Int = 1
                internal const val INTERNAL_CONST: Int = 2
                public const val PUBLIC_CONST: Int = 3

                private fun privateTop(): Int = PRIVATE_CONST
                internal fun internalTop(): Int = INTERNAL_CONST
                public fun publicTop(): Int = PUBLIC_CONST
                public fun publicDefault(value: Int = 4): Int = value

                private class PrivateTop
                internal class InternalTop

                public open class PublicTop {
                    private fun privateMember(): Int = 1
                    internal fun internalMember(): Int = 2
                    protected fun protectedMember(): Int = 3
                    public fun publicMember(): Int = 4
                }

                public sealed class SealedTop
                """.trimIndent()
            )
        }
        val ilFile = File(tmpdir, "Visibility.Consumer.Library.il")
        compileInProcess(
            K2DotNetCompiler(),
            source.path,
            K2DotNetCompilerArguments::moduleName.cliArgument, "Visibility.Consumer.Library",
            K2DotNetCompilerArguments::destination.cliArgument, ilFile.path,
        )
        val implementation = File(tmpdir, "Visibility.Consumer.Library.dll")
        assertTrue(
            DotNetIlAssembler.assembleLibrary(
                ilFile,
                implementation,
                DotNetTarget.NET48,
                MessageCollector.NONE,
            )
        )
        val runtimeBootstrap = File(tmpdir, "runtimeBootstrap.kt").apply { writeText("fun main() {}") }
        compileInProcess(
            K2DotNetCompiler(),
            runtimeBootstrap.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net48",
            K2DotNetCompilerArguments::moduleName.cliArgument, "RuntimeBootstrap",
            K2DotNetCompilerArguments::destination.cliArgument, File(tmpdir, "RuntimeBootstrap.exe").path,
        )
        val runtime = File(tmpdir, "Kotlin.Runtime.dll")
        assertTrue(runtime.isFile) { "Executable compilation did not install Kotlin.Runtime.dll" }

        val publicConsumer = File(tmpdir, "PublicConsumer.cs").apply {
            writeText(
                """
                public sealed class PublicConsumer : surface.PublicTop
                {
                    public int Read()
                    {
                        return surface.visibilityConsumerLibraryKt.publicTop()
                            + surface.visibilityConsumerLibraryKt.PUBLIC_CONST
                            + protectedMember()
                            + publicMember();
                    }
                }
                """.trimIndent()
            )
        }
        val publicResult = runCSharpCompiler(
            checkNotNull(csharpCompiler),
            publicConsumer,
            File(tmpdir, "PublicConsumer.dll"),
            implementation,
            runtime,
        )
        assertEquals(0, publicResult.exitCode, publicResult.output)

        val forbiddenConsumer = File(tmpdir, "ForbiddenConsumer.cs").apply {
            writeText(
                """
                public class IllegalSealedSubclass : surface.SealedTop {}

                public sealed class ForbiddenConsumer
                {
                    public object PrivateType() { return new surface.PrivateTop(); }
                    public object InternalType() { return new surface.InternalTop(); }
                    public int PrivateTop() { return surface.visibilityConsumerLibraryKt.privateTop(); }
                    public int InternalTop() { return surface.visibilityConsumerLibraryKt.internalTop(); }
                    public int PrivateConst() { return surface.visibilityConsumerLibraryKt.PRIVATE_CONST; }
                    public int InternalConst() { return surface.visibilityConsumerLibraryKt.INTERNAL_CONST; }
                    public int PrivateMember(surface.PublicTop value) { return value.privateMember(); }
                    public int InternalMember(surface.PublicTop value) { return value.internalMember(); }
                }
                """.trimIndent()
            )
        }
        val forbiddenResult = runCSharpCompiler(
            checkNotNull(csharpCompiler),
            forbiddenConsumer,
            File(tmpdir, "ForbiddenConsumer.dll"),
            implementation,
            runtime,
        )
        assertTrue(forbiddenResult.exitCode != 0) { forbiddenResult.output }
        for (name in listOf(
            "SealedTop",
            "PrivateTop",
            "InternalTop",
            "privateTop",
            "internalTop",
            "PRIVATE_CONST",
            "INTERNAL_CONST",
            "privateMember",
            "internalMember",
        )) {
            assertTrue(name in forbiddenResult.output) {
                "Expected C# accessibility diagnostic for '$name':\n${forbiddenResult.output}"
            }
        }

        val reflectionVerifier = File(tmpdir, "VisibilityReflectionVerifier.cs").apply {
            writeText(
                """
                using System;
                using System.Reflection;

                public static class VisibilityReflectionVerifier
                {
                    private const BindingFlags DeclaredInstance =
                        BindingFlags.DeclaredOnly | BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;
                    private const BindingFlags DeclaredStatic =
                        BindingFlags.DeclaredOnly | BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic;

                    private static void Require(bool condition, string message)
                    {
                        if (!condition) throw new Exception(message);
                    }

                    private static bool HasAttribute(MemberInfo member, string fullName)
                    {
                        foreach (CustomAttributeData attribute in member.GetCustomAttributesData())
                            if (attribute.AttributeType.FullName == fullName) return true;
                        return false;
                    }

                    public static int Main()
                    {
                        Assembly assembly = typeof(surface.PublicTop).Assembly;
                        Type facade = assembly.GetType("surface.visibilityConsumerLibraryKt", true);
                        Type privateTop = assembly.GetType("surface.PrivateTop", true);
                        Type internalTop = assembly.GetType("surface.InternalTop", true);
                        Type publicTop = assembly.GetType("surface.PublicTop", true);
                        Type sealedTop = assembly.GetType("surface.SealedTop", true);

                        Require(!privateTop.IsPublic, "private top-level type became CLR-public");
                        Require(!internalTop.IsPublic, "internal top-level type became CLR-public");
                        Require(publicTop.IsPublic, "public top-level type is not CLR-public");

                        Require(facade.GetMethod("privateTop", DeclaredStatic).IsAssembly,
                            "file-private top-level function must be facade-internal");
                        Require(facade.GetMethod("internalTop", DeclaredStatic).IsAssembly,
                            "internal top-level function must be assembly-visible");
                        Require(facade.GetMethod("publicTop", DeclaredStatic).IsPublic,
                            "public top-level function must be CLR-public");

                        Require(publicTop.GetMethod("privateMember", DeclaredInstance).IsPrivate,
                            "private member must be CLR-private");
                        Require(publicTop.GetMethod("internalMember", DeclaredInstance).IsAssembly,
                            "internal member must be assembly-visible");
                        Require(publicTop.GetMethod("protectedMember", DeclaredInstance).IsFamily,
                            "protected member must be family-visible");
                        Require(publicTop.GetMethod("publicMember", DeclaredInstance).IsPublic,
                            "public member must be CLR-public");

                        Require(facade.GetField("PRIVATE_CONST", DeclaredStatic).IsPrivate,
                            "private const must be CLR-private");
                        Require(facade.GetField("INTERNAL_CONST", DeclaredStatic).IsAssembly,
                            "internal const must be assembly-visible");
                        Require(facade.GetField("PUBLIC_CONST", DeclaredStatic).IsPublic,
                            "public const must be CLR-public");

                        ConstructorInfo[] sealedConstructors = sealedTop.GetConstructors(DeclaredInstance);
                        Require(sealedConstructors.Length == 1 && sealedConstructors[0].IsFamilyAndAssembly,
                            "sealed constructor must be famandassem");

                        MethodInfo defaultBridge = facade.GetMethod("publicDefault${'$'}default", DeclaredStatic);
                        Require(defaultBridge != null && defaultBridge.IsPublic,
                            "cross-module default bridge must be CLR-public");
                        Require(HasAttribute(defaultBridge, "Kotlin.Runtime.Internal.KotlinCompilerAbiAttribute"),
                            "default bridge is missing the compiler-ABI marker");
                        Require(HasAttribute(defaultBridge, "System.ComponentModel.EditorBrowsableAttribute"),
                            "default bridge is missing EditorBrowsable(Never)");

                        Type syntheticMarker = Assembly.LoadFrom("Kotlin.Runtime.dll")
                            .GetType("Kotlin.Runtime.Internal.SyntheticConstructorMarker", true);
                        Require(syntheticMarker.IsPublic, "synthetic constructor marker must be CLR-public");
                        Require(HasAttribute(syntheticMarker, "Kotlin.Runtime.Internal.KotlinCompilerAbiAttribute"),
                            "synthetic constructor marker is missing the compiler-ABI marker");
                        Require(HasAttribute(syntheticMarker, "System.ComponentModel.EditorBrowsableAttribute"),
                            "synthetic constructor marker is missing EditorBrowsable(Never)");
                        return 0;
                    }
                }
                """.trimIndent()
            )
        }
        val reflectionExecutable = File(tmpdir, "VisibilityReflectionVerifier.exe")
        val reflectionCompile = runCSharpCompiler(
            checkNotNull(csharpCompiler),
            reflectionVerifier,
            reflectionExecutable,
            implementation,
            runtime,
            target = "exe",
        )
        assertEquals(0, reflectionCompile.exitCode, reflectionCompile.output)
        val reflectionProcess = ProcessBuilder(reflectionExecutable.path)
            .directory(tmpdir)
            .redirectErrorStream(true)
            .start()
        val reflectionOutput = reflectionProcess.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, reflectionProcess.waitFor(), reflectionOutput)
    }

    @Test
    fun testCompilerAbiMetadataResolvesOnNet10() {
        requireOrAssumeToolchain(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val csharpCompiler = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(csharpCompiler != null, ".NET Framework C# compiler is not available")

        val directory = File(tmpdir, "net10-compiler-abi-reflection").apply { mkdirs() }
        val source = directory.resolve("published.kt").apply {
            writeText(
                """
                package profileabi

                @PublishedApi
                internal class PublishedBox

                fun main() {
                    println("OK")
                }
                """.trimIndent()
            )
        }
        val application = directory.resolve("ProfileAbi.dll")
        compileInProcess(
            K2DotNetCompiler(),
            source.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "ProfileAbi",
            K2DotNetCompilerArguments::destination.cliArgument, application.path,
        )
        val applicationIl = directory.resolve("ProfileAbi.il").readText()
        assertTrue(".assembly extern System.Runtime" in applicationIl) { applicationIl }
        assertTrue(
            ".custom instance void [System.Runtime]System.ComponentModel.EditorBrowsableAttribute" in applicationIl
        ) { applicationIl }

        val verifierSource = directory.resolve("Verifier.cs").apply {
            writeText(
                """
                using System;
                using System.Reflection;

                public static class Verifier
                {
                    private static bool HasAttribute(MemberInfo member, string fullName)
                    {
                        foreach (CustomAttributeData attribute in member.GetCustomAttributesData())
                            if (attribute.AttributeType.FullName == fullName)
                                return true;
                        return false;
                    }

                    public static int Main()
                    {
                        Type marker = Assembly.LoadFrom("Kotlin.Runtime.dll")
                            .GetType("Kotlin.Runtime.Internal.KotlinCompilerAbiAttribute", true);
                        if (!HasAttribute(marker, "System.ComponentModel.EditorBrowsableAttribute"))
                            return 1;

                        Type published = Assembly.LoadFrom("ProfileAbi.dll")
                            .GetType("profileabi.PublishedBox", true);
                        if (!HasAttribute(published, "Kotlin.Runtime.Internal.KotlinCompilerAbiAttribute"))
                            return 2;
                        if (!HasAttribute(published, "System.ComponentModel.EditorBrowsableAttribute"))
                            return 3;
                        return 0;
                    }
                }
                """.trimIndent()
            )
        }
        val verifier = directory.resolve("Verifier.exe")
        val compileResult = runCSharpCompiler(
            checkNotNull(csharpCompiler),
            verifierSource,
            verifier,
            target = "exe",
        )
        assertEquals(0, compileResult.exitCode, compileResult.output)
        directory.resolve("ProfileAbi.runtimeconfig.json")
            .copyTo(directory.resolve("Verifier.runtimeconfig.json"), overwrite = true)

        val process = ProcessBuilder(dotnetHost.path, "exec", verifier.path)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
    }

    @Test
    fun testPrimitiveArrayWrappersAcrossPortableLibraryBoundary() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ILAsm is not available",
        )
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val csharpCompiler = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(csharpCompiler != null, ".NET Framework C# compiler is not available")
        val dotnetHost = modernDotNetHostOrSkip()
        val libraryDirectory = File(tmpdir, "primitive-array-wrapper-library")
        val librarySource = File(tmpdir, "primitiveArrayLibrary.kt").apply {
            writeText(
                """
                package primitivearrays

                public fun measure(values: IntArray): Int = values[0] + values[1]

                public fun measure(values: Array<Int>): Int = values[0] + values[1] + 10

                public fun makeSpecialized(): IntArray = intArrayOf(1, 2)

                public fun makeGeneric(): Array<Int> = arrayOf(1, 2)

                public fun identity(values: IntArray): IntArray = values

                private var remembered: IntArray? = null

                public fun sameIdentity(first: IntArray, second: IntArray): Boolean = first === second

                public fun rememberIdentity(values: IntArray): Boolean {
                    remembered = values
                    return true
                }

                public fun isRememberedIdentity(values: IntArray): Boolean = remembered === values

                public fun makeAndRemember(): IntArray {
                    val values = intArrayOf(20, 22)
                    remembered = values
                    return values
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "PrimitiveArray.Library",
            K2DotNetCompilerArguments::dotNetExports.cliArgument,
            "primitivearrays.identity(kotlin.IntArray)=RoundTripSpecialized",
            K2DotNetCompilerArguments::dotNetExports.cliArgument,
            "primitivearrays.makeSpecialized=MakeSpecialized",
            K2DotNetCompilerArguments::dotNetExports.cliArgument,
            "primitivearrays.sameIdentity(kotlin.IntArray,kotlin.IntArray)=SameSpecialized",
            K2DotNetCompilerArguments::dotNetExports.cliArgument,
            "primitivearrays.rememberIdentity(kotlin.IntArray)=RememberSpecialized",
            K2DotNetCompilerArguments::dotNetExports.cliArgument,
            "primitivearrays.isRememberedIdentity(kotlin.IntArray)=IsRememberedSpecialized",
            K2DotNetCompilerArguments::dotNetExports.cliArgument,
            "primitivearrays.makeAndRemember=MakeAndRemember",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val metadataLibrary = libraryDirectory.resolve("PrimitiveArray.Library.klib")
        val libraryIl = libraryDirectory.resolve("PrimitiveArray.Library.il").readText()
        assertTrue(
            "'measure'(class [Kotlin.Runtime]'Kotlin.IntArray' 'values')" in libraryIl
        ) { libraryIl }
        assertTrue("'measure'(int32[] 'values')" in libraryIl) { libraryIl }
        assertTrue(
            "class [Kotlin.Runtime]'Kotlin.IntArray' 'makeSpecialized'()" in libraryIl
        ) { libraryIl }
        assertTrue("int32[] 'makeGeneric'()" in libraryIl) { libraryIl }
        assertTrue("int32[] 'RoundTripSpecialized'(int32[] 'values')" in libraryIl) { libraryIl }
        assertTrue("int32[] 'MakeSpecialized'()" in libraryIl) { libraryIl }
        assertTrue("bool 'SameSpecialized'(int32[] 'first', int32[] 'second')" in libraryIl) { libraryIl }
        assertTrue("bool 'RememberSpecialized'(int32[] 'values')" in libraryIl) { libraryIl }
        assertTrue("bool 'IsRememberedSpecialized'(int32[] 'values')" in libraryIl) { libraryIl }
        assertTrue("int32[] 'MakeAndRemember'()" in libraryIl) { libraryIl }

        for (target in listOf("net48", "net10.0")) {
            val consumerDirectory = libraryDirectory.resolve("consumer-${target.replace('.', '-')}").apply { mkdirs() }
            val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
                writeText(
                    """
                    package primitivearrayconsumer

                    import primitivearrays.*

                    fun main() {
                        val specialized = makeSpecialized()
                        val generic = makeGeneric()
                        if (measure(specialized) != 3) throw Error("specialized overload")
                        if (measure(generic) != 13) throw Error("generic primitive substitution overload")
                        val specializedIdentity: Any = specialized
                        val genericIdentity: Any = generic
                        if (specializedIdentity === genericIdentity) throw Error("array identities collapsed")
                        specialized[0] = 40
                        generic[0] = 41
                        if (measure(specialized) != 42 || measure(generic) != 53) {
                            throw Error("cross-module mutation")
                        }
                    }
                    """.trimIndent()
                )
            }
            val application = consumerDirectory.resolve(
                if (target == "net48") "PrimitiveArrayConsumer.exe" else "PrimitiveArrayConsumer.dll"
            )
            compileInProcess(
                K2DotNetCompiler(),
                consumerSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "PrimitiveArrayConsumer",
                K2DotNetCompilerArguments::destination.cliArgument, application.path,
            )
            if (target == "net10.0") {
                runDotNet(
                    dotnetHost,
                    application,
                    consumerDirectory,
                    "Primitive-array Kotlin consumer failed for $target",
                )
            } else {
                val process = ProcessBuilder(application.path)
                    .directory(consumerDirectory)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), "Primitive-array Kotlin consumer failed for $target:\n$output")
            }

            val verifierSource = consumerDirectory.resolve("PrimitiveArrayVerifier.cs").apply {
                writeText(
                    """
                    using System;
                    using System.Reflection;
                    using System.Threading;

                    public static class PrimitiveArrayVerifier
                    {
                        private static void Require(bool condition, string message)
                        {
                            if (!condition) throw new Exception(message);
                        }

                        private static bool HasAttribute(MemberInfo member, string fullName)
                        {
                            foreach (object attribute in member.GetCustomAttributes(false))
                            {
                                if (attribute.GetType().FullName == fullName) return true;
                            }
                            return false;
                        }

                        public static int Main()
                        {
                            Assembly library = Assembly.LoadFrom("PrimitiveArray.Library.dll");
                            Type facade = library.GetType("primitivearrays.primitiveArrayLibraryKt", true);
                            MethodInfo specializedMeasure = facade.GetMethod(
                                "measure", new Type[] { Type.GetType("Kotlin.IntArray, Kotlin.Runtime", true) });
                            MethodInfo genericMeasure = facade.GetMethod("measure", new Type[] { typeof(int[]) });
                            MethodInfo makeSpecialized = facade.GetMethod("makeSpecialized", Type.EmptyTypes);
                            MethodInfo makeGeneric = facade.GetMethod("makeGeneric", Type.EmptyTypes);
                            MethodInfo roundTripExport = facade.GetMethod(
                                "RoundTripSpecialized", new Type[] { typeof(int[]) });
                            MethodInfo makeSpecializedExport = facade.GetMethod(
                                "MakeSpecialized", Type.EmptyTypes);
                            MethodInfo sameExport = facade.GetMethod(
                                "SameSpecialized", new Type[] { typeof(int[]), typeof(int[]) });
                            MethodInfo rememberExport = facade.GetMethod(
                                "RememberSpecialized", new Type[] { typeof(int[]) });
                            MethodInfo isRememberedExport = facade.GetMethod(
                                "IsRememberedSpecialized", new Type[] { typeof(int[]) });
                            MethodInfo makeAndRememberExport = facade.GetMethod(
                                "MakeAndRemember", Type.EmptyTypes);
                            Require(specializedMeasure != null, "specialized overload is not wrapper-shaped");
                            Require(genericMeasure != null, "Array<Int> overload is not int[]-shaped");
                            Require(makeSpecialized.ReturnType.FullName == "Kotlin.IntArray",
                                "specialized result leaked its vector storage");
                            Require(makeGeneric.ReturnType == typeof(int[]),
                                "generic substitution did not retain the natural CLR vector");
                            Require(roundTripExport != null && roundTripExport.ReturnType == typeof(int[]),
                                "explicit specialized-array export is not int[]-shaped");
                            Require(makeSpecializedExport != null &&
                                    makeSpecializedExport.ReturnType == typeof(int[]),
                                "explicit specialized-array result export is not int[]-shaped");
                            Require(sameExport != null && sameExport.ReturnType == typeof(bool),
                                "two-parameter identity export is not bool/int[]-shaped");
                            Require(rememberExport != null && isRememberedExport != null,
                                "cross-call identity exports are missing");
                            Require(makeAndRememberExport != null &&
                                    makeAndRememberExport.ReturnType == typeof(int[]),
                                "stored specialized-array result export is not int[]-shaped");

                            Type wrapperType = makeSpecialized.ReturnType;
                            Require(wrapperType.IsSealed, "primitive-array wrapper must be sealed");
                            FieldInfo storageField = wrapperType.GetField(
                                "_storage", BindingFlags.Instance | BindingFlags.NonPublic);
                            Require(storageField != null && storageField.IsPrivate && storageField.FieldType == typeof(int[]),
                                "wrapper storage layout is not private int[]");
                            FieldInfo internTable = wrapperType.GetField(
                                "_internedByStorage", BindingFlags.Static | BindingFlags.NonPublic);
                            Require(internTable != null && internTable.IsPrivate && internTable.IsInitOnly &&
                                    internTable.FieldType.IsGenericType &&
                                    internTable.FieldType.GetGenericTypeDefinition().FullName ==
                                        "System.Runtime.CompilerServices.ConditionalWeakTable`2",
                                "interop identity association is not runtime-owned weak interning");
                            ConstructorInfo storageConstructor = wrapperType.GetConstructor(new Type[] { typeof(int[]) });
                            MethodInfo getStorage = wrapperType.GetMethod("GetStorage", BindingFlags.Public | BindingFlags.Instance);
                            MethodInfo wrapStorageOrNull = wrapperType.GetMethod(
                                "WrapStorageOrNull", BindingFlags.Public | BindingFlags.Static);
                            Require(storageConstructor != null && getStorage != null && wrapStorageOrNull != null,
                                "cross-assembly primitive-array compiler ABI is missing");
                            Require(HasAttribute(storageConstructor,
                                    "Kotlin.Runtime.Internal.KotlinCompilerAbiAttribute"),
                                "storage constructor is not marked compiler ABI");

                            object specialized = makeSpecialized.Invoke(null, null);
                            int[] generic = (int[]) makeGeneric.Invoke(null, null);
                            Require(!Object.ReferenceEquals(specialized, generic),
                                "specialized and generic array identities collapsed");
                            int[] liveStorage = (int[]) getStorage.Invoke(specialized, null);
                            liveStorage[0] = 40;
                            Require((int) specializedMeasure.Invoke(null, new object[] { specialized }) == 42,
                                "wrapper-to-storage mutation alias was lost");
                            generic[0] = 41;
                            Require((int) genericMeasure.Invoke(null, new object[] { generic }) == 53,
                                "natural generic vector mutation failed");

                            int[] exportedInput = new int[] { 20, 22 };
                            object exportedRoundTrip = roundTripExport.Invoke(
                                null, new object[] { exportedInput });
                            Require(Object.ReferenceEquals(exportedInput, exportedRoundTrip),
                                "explicit export copied or replaced primitive-array storage");
                            Require((bool) sameExport.Invoke(
                                    null, new object[] { exportedInput, exportedInput }),
                                "one CLR vector did not project to one Kotlin wrapper within a call");
                            Require(!(bool) sameExport.Invoke(
                                    null, new object[] { exportedInput, new int[] { 20, 22 } }),
                                "distinct CLR vectors collapsed to one Kotlin wrapper");
                            Require((bool) rememberExport.Invoke(
                                    null, new object[] { exportedInput }),
                                "identity remember export failed");
                            Require((bool) isRememberedExport.Invoke(
                                    null, new object[] { exportedInput }),
                                "one CLR vector did not retain Kotlin wrapper identity across calls");
                            int[] exportedResult = (int[]) makeSpecializedExport.Invoke(null, null);
                            Require(exportedResult.Length == 2 && exportedResult[0] == 1 && exportedResult[1] == 2,
                                "explicit primitive-array result export lost contents");
                            int[] rememberedResult = (int[]) makeAndRememberExport.Invoke(null, null);
                            Require((bool) isRememberedExport.Invoke(
                                    null, new object[] { rememberedResult }),
                                "outbound Kotlin wrapper was not recovered when its vector returned inbound");

                            int[] inboundStorage = new int[] { 20, 22 };
                            object inboundWrapper = storageConstructor.Invoke(new object[] { inboundStorage });
                            Require(Object.ReferenceEquals(inboundStorage, getStorage.Invoke(inboundWrapper, null)),
                                "compiler ABI wrapper construction copied storage silently");
                            Require((int) specializedMeasure.Invoke(null, new object[] { inboundWrapper }) == 42,
                                "inbound wrapper did not alias its supplied vector");

                            int[] concurrentStorage = new int[] { 42 };
                            object[] concurrentWrappers = new object[8];
                            Exception[] concurrentErrors = new Exception[8];
                            Thread[] threads = new Thread[8];
                            for (int index = 0; index < threads.Length; index++)
                            {
                                int slot = index;
                                threads[index] = new Thread(delegate()
                                {
                                    try
                                    {
                                        concurrentWrappers[slot] = wrapStorageOrNull.Invoke(
                                            null, new object[] { concurrentStorage });
                                    }
                                    catch (Exception error)
                                    {
                                        concurrentErrors[slot] = error;
                                    }
                                });
                                threads[index].Start();
                            }
                            for (int index = 0; index < threads.Length; index++) threads[index].Join();
                            for (int index = 0; index < threads.Length; index++)
                            {
                                Require(concurrentErrors[index] == null,
                                    "concurrent vector adaptation failed");
                                Require(Object.ReferenceEquals(concurrentWrappers[0], concurrentWrappers[index]),
                                    "concurrent first conversion created multiple Kotlin wrappers");
                            }
                            return 0;
                        }
                    }
                    """.trimIndent()
                )
            }
            val verifier = consumerDirectory.resolve("PrimitiveArrayVerifier.exe")
            val compileResult = runCSharpCompiler(
                checkNotNull(csharpCompiler),
                verifierSource,
                verifier,
                target = "exe",
            )
            assertEquals(0, compileResult.exitCode, compileResult.output)
            val verifierProcess = if (target == "net10.0") {
                consumerDirectory.resolve("PrimitiveArrayConsumer.runtimeconfig.json")
                    .copyTo(consumerDirectory.resolve("PrimitiveArrayVerifier.runtimeconfig.json"), overwrite = true)
                ProcessBuilder(dotnetHost.path, "exec", verifier.path)
            } else {
                ProcessBuilder(verifier.path)
            }.directory(consumerDirectory).redirectErrorStream(true).start()
            val verifierOutput = verifierProcess.inputStream.bufferedReader().use { it.readText() }
            assertEquals(
                0,
                verifierProcess.waitFor(),
                "Primitive-array C# verifier failed for $target:\n$verifierOutput",
            )
        }
    }

    @Test
    fun testOpenNullableTypeParametersAcrossPortableLibraryBoundary() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ILAsm is not available",
        )
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val dotnetHost = modernDotNetHostOrSkip()
        val modernCSharp = DotNetIlAssembler.findModernCSharpCompiler()
        requireOrAssumeToolchain(
            modernCSharp != null,
            "Modern Roslyn and the net10 reference pack are not available",
        )
        val libraryDirectory = File(tmpdir, "open-nullable-library")
        val librarySource = File(tmpdir, "openNullableLibrary.kt").apply {
            writeText(
                """
                package nullableabi

                public class NullableHolder<T>(public var value: T?)

                public interface NullableSource<T> {
                    public fun nullableValue(): T?
                }

                public class StoredNullableSource<T>(private val stored: T?) : NullableSource<T> {
                    override fun nullableValue(): T? = stored
                }

                public fun <T> echoNullable(value: T?): T? = value

                public fun <T> requireNullable(value: T?): T = value!!

                public fun <T> readNullable(source: NullableSource<T>): T? = source.nullableValue()

                public fun <T : String> echoStringBoundNullable(value: T?): T? = value

                public fun <T : String> requireStringBoundNullable(value: T?): T = value!!
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "OpenNullable.Library",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val metadataLibrary = libraryDirectory.resolve("OpenNullable.Library.klib")
        val libraryIl = libraryDirectory.resolve("OpenNullable.Library.il").readText()
        assertTrue(".field private object 'value'" in libraryIl) { libraryIl }
        assertTrue("static object 'echoNullable'<'T'>(object 'value')" in libraryIl) { libraryIl }
        assertTrue("static !!0 'requireNullable'<'T'>(object 'value')" in libraryIl) { libraryIl }
        assertTrue("static object 'echoStringBoundNullable'<'T'>(object 'value')" in libraryIl) { libraryIl }
        assertTrue("static string 'requireStringBoundNullable'<'T'>(object 'value')" in libraryIl) { libraryIl }
        assertTrue("unbox.any !!0" in libraryIl) { libraryIl }
        assertTrue("castclass string" in libraryIl) { libraryIl }
        assertTrue(".class interface public abstract auto ansi 'nullableabi.NullableSource`1'" in libraryIl) { libraryIl }
        assertTrue("instance object 'nullableValue'()" in libraryIl) { libraryIl }
        assertTrue(
            ".override method instance object class 'nullableabi.NullableSource`1'<!0>::'nullableValue'()" in libraryIl
        ) { libraryIl }

        for (target in listOf("net48", "net10.0")) {
            val consumerDirectory = libraryDirectory.resolve("consumer-${target.replace('.', '-')}").apply { mkdirs() }
            val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
                writeText(
                    """
                    package nullableconsumer

                    import nullableabi.*

                    fun main() {
                        val numbers = NullableHolder<Int>(null)
                        if (numbers.value != null) throw Error("primitive null field")
                        numbers.value = 41
                        if (numbers.value != 41) throw Error("primitive field recovery")
                        if (echoNullable<Int>(null) != null) throw Error("primitive null call")
                        if (echoNullable(42) != 42) throw Error("primitive call recovery")
                        if (requireNullable(43) != 43) throw Error("primitive non-null recovery")
                        val numberSource: NullableSource<Int> = StoredNullableSource(44)
                        if (numberSource.nullableValue() != 44) throw Error("primitive interface recovery")
                        if (readNullable(numberSource) != 44) throw Error("primitive generic interface call")

                        val strings = NullableHolder<String>(null)
                        strings.value = "reference"
                        if (strings.value != "reference") throw Error("reference field recovery")
                        if (echoNullable<String>(null) != null) throw Error("reference null call")
                        if (requireNullable("ok") != "ok") throw Error("reference non-null recovery")
                        val stringSource: NullableSource<String> = StoredNullableSource(null)
                        if (stringSource.nullableValue() != null) throw Error("reference interface recovery")
                        if (readNullable(stringSource) != null) throw Error("reference generic interface call")

                        if (echoStringBoundNullable<String>(null) != null) {
                            throw Error("string-bound null call")
                        }
                        if (echoStringBoundNullable("bounded-call") != "bounded-call") {
                            throw Error("string-bound call recovery")
                        }
                        if (requireStringBoundNullable("bounded-required") != "bounded-required") {
                            throw Error("string-bound non-null recovery")
                        }
                    }
                    """.trimIndent()
                )
            }
            val application = consumerDirectory.resolve(
                if (target == "net48") "OpenNullableConsumer.exe" else "OpenNullableConsumer.dll"
            )
            compileInProcess(
                K2DotNetCompiler(),
                consumerSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "OpenNullableConsumer",
                K2DotNetCompilerArguments::destination.cliArgument, application.path,
            )
            if (target == "net10.0") {
                runDotNet(
                    dotnetHost,
                    application,
                    consumerDirectory,
                    "Open-nullable Kotlin consumer failed for $target",
                )
            } else {
                val process = ProcessBuilder(application.path)
                    .directory(consumerDirectory)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), "Open-nullable Kotlin consumer failed for $target:\n$output")
            }

            if (target == "net10.0") {
                val csharpSource = consumerDirectory.resolve("consumer.cs").apply {
                    writeText(
                        """
                        public static class Program
                        {
                            public static int Main()
                            {
                                var holder = new nullableabi.NullableHolder<int>(41);
                                if ((int) holder.value != 41) return 1;
                                holder.value = null;
                                if (holder.value != null) return 2;

                                nullableabi.NullableSource<int> source =
                                    new nullableabi.StoredNullableSource<int>(42);
                                if ((int) source.nullableValue() != 42) return 3;
                                if ((int) nullableabi.openNullableLibraryKt.readNullable<int>(source) != 42) return 4;

                                nullableabi.NullableSource<string> empty =
                                    new nullableabi.StoredNullableSource<string>(null);
                                if (empty.nullableValue() != null) return 5;
                                if (nullableabi.openNullableLibraryKt.echoNullable<string>(null) != null) return 6;
                                if (nullableabi.openNullableLibraryKt.requireNullable<int>(43) != 43) return 7;

                                if (nullableabi.openNullableLibraryKt.echoStringBoundNullable<string>(null) != null)
                                    return 8;
                                if ((string) nullableabi.openNullableLibraryKt
                                    .echoStringBoundNullable<string>("bounded-call") != "bounded-call") return 9;
                                if (nullableabi.openNullableLibraryKt
                                    .requireStringBoundNullable<string>("bounded-required") != "bounded-required")
                                    return 10;
                                return 0;
                            }
                        }
                        """.trimIndent()
                    )
                }
                val csharpApplication = consumerDirectory.resolve("OpenNullableCSharpConsumer.dll")
                val csharpCompile = runModernCSharpCompiler(
                    checkNotNull(modernCSharp),
                    csharpSource,
                    csharpApplication,
                    libraryDirectory.resolve("OpenNullable.Library.dll"),
                    consumerDirectory.resolve("Kotlin.Runtime.dll"),
                    target = "exe",
                )
                assertEquals(0, csharpCompile.exitCode, csharpCompile.output)
                consumerDirectory.resolve("OpenNullableConsumer.runtimeconfig.json").copyTo(
                    consumerDirectory.resolve("OpenNullableCSharpConsumer.runtimeconfig.json"),
                    overwrite = true,
                )
                runDotNet(
                    dotnetHost,
                    csharpApplication,
                    consumerDirectory,
                    "Open-nullable C# consumer failed for $target",
                )
            }
        }
    }

    @Test
    fun testCovariantReturnsAcrossPortableLibraryBoundary() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ILAsm is not available",
        )
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val frameworkCSharp = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(frameworkCSharp != null, ".NET Framework C# compiler is not available")
        val frameworkNetStandardFacade = findFrameworkNetStandardFacade()
        requireOrAssumeToolchain(
            frameworkNetStandardFacade != null,
            ".NET Framework netstandard 2.0 facade is not available",
        )
        val modernCSharp = DotNetIlAssembler.findModernCSharpCompiler()
        requireOrAssumeToolchain(
            modernCSharp != null,
            "Modern Roslyn and the net10 reference pack are not available",
        )
        val dotnetHost = modernDotNetHostOrSkip()
        val libraryDirectory = File(tmpdir, "covariant-return-library")
        val librarySource = File(tmpdir, "covariantReturnLibrary.kt").apply {
            writeText(
                """
                package covarianceabi

                public open class Animal(public val tag: String)

                public open class Cat(tag: String) : Animal(tag)

                public open class Source {
                    public open fun make(): Animal = Animal("source-method")
                    public open val item: Animal get() = Animal("source-property")
                    public open fun <T> generic(value: T): Animal = Animal("source-generic")
                }

                public interface Maker {
                    public fun make(): Animal
                }

                public interface HasItem {
                    public val item: Animal
                }

                public interface DefaultMaker {
                    public fun defaultMake(): Animal = Animal("portable-default")
                }

                public interface ValueSource<out T> {
                    public fun value(): T
                }

                public class CatValueSource : ValueSource<Cat> {
                    override fun value(): Cat = Cat("variant-value")
                }

                public open class VariantReturnBase {
                    public open fun variant(): ValueSource<Animal> = CatValueSource()
                }

                public open class Factory {
                    public open fun make(): Cat = Cat("factory-method")
                }

                public open class ItemFactory {
                    public open val item: Cat get() = Cat("factory-property")
                }

                public abstract class AbstractCatSource : Source() {
                    public abstract override fun make(): Cat
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Covariance.Library",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val metadataLibrary = libraryDirectory.resolve("Covariance.Library.klib")
        for (target in listOf("net48", "net10.0")) {
            val consumerDirectory = libraryDirectory.resolve("consumer-${target.replace('.', '-')}").apply { mkdirs() }
            val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
                writeText(
                    """
                    package covarianceconsumer

                    import covarianceabi.*

                    public class Derived : Source() {
                        override fun make(): Cat = Cat("derived-method")
                        override val item: Cat get() = Cat("derived-property")
                        override fun <T> generic(value: T): Cat = Cat("derived-generic")
                    }

                    public class Combo : Factory(), Maker

                    public class ItemCombo : ItemFactory(), HasItem

                    public class VariantReturnDerived : VariantReturnBase() {
                        override fun variant(): ValueSource<Cat> = CatValueSource()
                    }

                    public interface RefinedDefaultMaker : DefaultMaker {
                        override fun defaultMake(): Cat = Cat("refined-default")
                    }

                    public class DefaultMakerImplementation : RefinedDefaultMaker

                    public class ConcreteAbstractSource : AbstractCatSource() {
                        override fun make(): Cat = Cat("abstract-method")
                    }

                    public open class Middle : Source() {
                        override fun make(): Cat = Cat("middle-method")
                    }

                    public class Siamese(tag: String) : Cat(tag)

                    public class Leaf : Middle() {
                        override fun make(): Siamese = Siamese("leaf-method")
                    }

                    fun main() {
                        val exact = Derived()
                        val exactMethod: Cat = exact.make()
                        val exactProperty: Cat = exact.item
                        val exactGeneric: Cat = exact.generic(42)
                        if (exactMethod.tag != "derived-method") throw Error("exact method")
                        if (exactProperty.tag != "derived-property") throw Error("exact property")
                        if (exactGeneric.tag != "derived-generic") throw Error("exact generic")

                        val base: Source = exact
                        if (base.make().tag != "derived-method") throw Error("base method")
                        if (base.item.tag != "derived-property") throw Error("base property")
                        if (base.generic("value").tag != "derived-generic") throw Error("base generic")

                        val maker: Maker = Combo()
                        if (maker.make().tag != "factory-method") throw Error("inherited interface method")
                        val hasItem: HasItem = ItemCombo()
                        if (hasItem.item.tag != "factory-property") throw Error("inherited interface property")

                        val variant: VariantReturnBase = VariantReturnDerived()
                        if (variant.variant().value().tag != "variant-value") {
                            throw Error("canonical variant return")
                        }

                        val defaultMaker: DefaultMaker = DefaultMakerImplementation()
                        if (defaultMaker.defaultMake().tag != "refined-default") {
                            throw Error("covariant interface default")
                        }

                        val abstractBase: Source = ConcreteAbstractSource()
                        if (abstractBase.make().tag != "abstract-method") throw Error("abstract refinement")

                        val leaf = Leaf()
                        val leafAsRoot: Source = leaf
                        val leafAsMiddle: Middle = leaf
                        if (leafAsRoot.make().tag != "leaf-method") throw Error("root leaf dispatch")
                        if (leafAsMiddle.make().tag != "leaf-method") throw Error("middle leaf dispatch")
                    }
                    """.trimIndent()
                )
            }
            val application = consumerDirectory.resolve(
                if (target == "net48") "Covariance.Consumer.exe" else "Covariance.Consumer.dll"
            )
            compileInProcess(
                K2DotNetCompiler(),
                consumerSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "Covariance.Consumer",
                K2DotNetCompilerArguments::destination.cliArgument, application.path,
            )

            val consumerIl = consumerDirectory.resolve("Covariance.Consumer.il").readText()
            val bridgeBodies = Regex(
                "(?s)\\.method private[^\\n]*'<CovariantReturnBridge-[^']+'[^\\n]*\\n  \\{(.*?)\\n  \\}"
            ).findAll(consumerIl).map { match -> match.groupValues[1] }.toList()
            assertEquals(8, bridgeBodies.size, consumerIl)
            bridgeBodies.forEach { body ->
                assertTrue(".override method" in body) { body }
                assertTrue("callvirt instance" in body) { body }
                assertTrue("newobj" !in body) { "Covariant-return bridge copied a constructor body:\n$body" }
            }
            assertTrue("class [Covariance.Library]'covarianceabi.Animal'" in consumerIl) { consumerIl }
            assertTrue("class [Covariance.Library]'covarianceabi.Cat'" in consumerIl) { consumerIl }
            assertTrue("[Covariance.Library]'covarianceabi.Source'::'make'()" in consumerIl) { consumerIl }
            assertTrue("[Covariance.Library]'covarianceabi.Maker'::'make'()" in consumerIl) { consumerIl }
            assertTrue("<CovariantReturnBridge-covarianceabi.VariantReturnBase-variant-" !in consumerIl) {
                "A canonical split-interface return acquired an unnecessary ordinary bridge:\n$consumerIl"
            }

            if (target == "net10.0") {
                runDotNet(
                    dotnetHost,
                    application,
                    consumerDirectory,
                    "Covariant-return Kotlin consumer failed for $target",
                )
            } else {
                val process = ProcessBuilder(application.path)
                    .directory(consumerDirectory)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), "Covariant-return Kotlin consumer failed for $target:\n$output")
            }

            val csharpSource = consumerDirectory.resolve("consumer.cs").apply {
                val foreignDefaultClass = if (target == "net10.0") {
                    """
                    public sealed class ForeignDefaultMaker : covarianceconsumer.RefinedDefaultMaker
                    {
                    }
                    """.trimIndent()
                } else {
                    ""
                }
                val foreignDefaultCall = if (target == "net10.0") {
                    """
                    covarianceabi.DefaultMaker foreignDefault = new ForeignDefaultMaker();
                    Require(foreignDefault.defaultMake().tag == "refined-default", "foreign DIM");
                    """.trimIndent()
                } else {
                    ""
                }
                writeText(
                    """
                    using System;
                    using System.Linq;
                    using System.Reflection;

                    $foreignDefaultClass

                    public static class Program
                    {
                        private static void Require(bool condition, string message)
                        {
                            if (!condition) throw new Exception(message);
                        }

                        public static int Main()
                        {
                            var exact = new covarianceconsumer.Derived();
                            covarianceabi.Cat exactMethod = exact.make();
                            covarianceabi.Cat exactProperty = exact.item;
                            covarianceabi.Cat exactGeneric = exact.generic<int>(42);
                            Require(exactMethod.tag == "derived-method", "exact method");
                            Require(exactProperty.tag == "derived-property", "exact property");
                            Require(exactGeneric.tag == "derived-generic", "exact generic");

                            covarianceabi.Source baseView = exact;
                            Require(baseView.make().tag == "derived-method", "base method");
                            Require(baseView.item.tag == "derived-property", "base property");
                            Require(baseView.generic<string>("value").tag == "derived-generic", "base generic");

                            covarianceabi.Maker maker = new covarianceconsumer.Combo();
                            Require(maker.make().tag == "factory-method", "interface method");
                            covarianceabi.HasItem hasItem = new covarianceconsumer.ItemCombo();
                            Require(hasItem.item.tag == "factory-property", "interface property");
                            covarianceabi.DefaultMaker defaultMaker =
                                new covarianceconsumer.DefaultMakerImplementation();
                            Require(defaultMaker.defaultMake().tag == "refined-default", "Kotlin default");
                            $foreignDefaultCall

                            covarianceabi.Source abstractView = new covarianceconsumer.ConcreteAbstractSource();
                            Require(abstractView.make().tag == "abstract-method", "abstract refinement");

                            var leaf = new covarianceconsumer.Leaf();
                            covarianceabi.Source rootView = leaf;
                            covarianceconsumer.Middle middleView = leaf;
                            Require(rootView.make().tag == "leaf-method", "root leaf dispatch");
                            Require(middleView.make().tag == "leaf-method", "middle leaf dispatch");

                            MethodInfo[] declared = typeof(covarianceconsumer.Derived).GetMethods(
                                BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic |
                                BindingFlags.DeclaredOnly);
                            MethodInfo precise = declared.Single(method =>
                                method.Name == "make" && method.IsPublic &&
                                method.ReturnType == typeof(covarianceabi.Cat));
                            Require(precise != null, "precise public C# method missing");
                            MethodInfo[] bridges = declared.Where(method =>
                                method.Name.StartsWith("<CovariantReturnBridge-", StringComparison.Ordinal)).ToArray();
                            Require(bridges.Length >= 3, "compiler bridges missing");
                            Require(bridges.All(method => method.IsPrivate), "compiler bridge leaked as public API");
                            return 0;
                        }
                    }
                    """.trimIndent()
                )
            }
            val csharpApplication = consumerDirectory.resolve(
                if (target == "net48") "Covariance.CSharpConsumer.exe" else "Covariance.CSharpConsumer.dll"
            )
            val csharpCompile = if (target == "net48") {
                runCSharpCompiler(
                    checkNotNull(frameworkCSharp),
                    csharpSource,
                    csharpApplication,
                    application,
                    libraryDirectory.resolve("Covariance.Library.dll"),
                    consumerDirectory.resolve("Kotlin.Runtime.dll"),
                    checkNotNull(frameworkNetStandardFacade),
                    target = "exe",
                )
            } else {
                runModernCSharpCompiler(
                    checkNotNull(modernCSharp),
                    csharpSource,
                    csharpApplication,
                    application,
                    libraryDirectory.resolve("Covariance.Library.dll"),
                    consumerDirectory.resolve("Kotlin.Runtime.dll"),
                    target = "exe",
                )
            }
            assertEquals(0, csharpCompile.exitCode, csharpCompile.output)
            if (target == "net10.0") {
                consumerDirectory.resolve("Covariance.Consumer.runtimeconfig.json").copyTo(
                    consumerDirectory.resolve("Covariance.CSharpConsumer.runtimeconfig.json"),
                    overwrite = true,
                )
                runDotNet(
                    dotnetHost,
                    csharpApplication,
                    consumerDirectory,
                    "Covariant-return C# consumer failed for $target",
                )
            } else {
                val process = ProcessBuilder(csharpApplication.path)
                    .directory(consumerDirectory)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), "Covariant-return C# consumer failed for $target:\n$output")
            }
        }

        val refinedDirectory = libraryDirectory.resolve("refined-net10-library").apply { mkdirs() }
        val refinedSource = refinedDirectory.resolve("refined.kt").apply {
            writeText(
                """
                package covariancerefined

                import covarianceabi.*

                public interface RefinedDefaultMaker : DefaultMaker {
                    override fun defaultMake(): Cat = Cat("external-refined-default")
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            refinedSource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Covariance.Refined",
            K2DotNetCompilerArguments::destination.cliArgument, refinedDirectory.path,
        )
        val refinedMetadata = refinedDirectory.resolve("Covariance.Refined.klib")
        val refinedBridgeRecords = DotNetLibraryAbiCodec.decode(refinedMetadata.readKlibManifest()).values
            .filterIsInstance<DotNetPhysicalDeclaration.CovariantReturnBridge>()
        assertEquals(1, refinedBridgeRecords.size, refinedBridgeRecords.joinToString("\n"))
        assertEquals(
            listOf("covariancerefined.RefinedDefaultMaker"),
            refinedBridgeRecords.single().ownerPath,
        )

        val downstreamDirectory = refinedDirectory.resolve("downstream").apply { mkdirs() }
        val downstreamSource = downstreamDirectory.resolve("downstream.kt").apply {
            writeText(
                """
                package covariancedownstream

                import covarianceabi.DefaultMaker
                import covariancerefined.RefinedDefaultMaker

                public class KotlinDefaultMaker : RefinedDefaultMaker

                fun main() {
                    val value: DefaultMaker = KotlinDefaultMaker()
                    if (value.defaultMake().tag != "external-refined-default") {
                        throw Error("external covariant DIM")
                    }
                }
                """.trimIndent()
            )
        }
        val downstreamApplication = downstreamDirectory.resolve("Covariance.Downstream.dll")
        val downstreamClasspath = listOf(refinedMetadata, metadataLibrary)
            .joinToString(File.pathSeparator) { it.path }
        compileInProcess(
            K2DotNetCompiler(),
            downstreamSource.path,
            K2DotNetCompilerArguments::classpath.cliArgument, downstreamClasspath,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net10.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Covariance.Downstream",
            K2DotNetCompilerArguments::destination.cliArgument, downstreamApplication.path,
        )
        val downstreamIl = downstreamDirectory.resolve("Covariance.Downstream.il").readText()
        val downstreamClass = Regex(
            "(?s)\\.class public[^\\n]*'covariancedownstream.KotlinDefaultMaker'.*?^}",
            setOf(RegexOption.MULTILINE),
        ).find(downstreamIl)?.value ?: error("KotlinDefaultMaker class missing:\n$downstreamIl")
        assertTrue("<CovariantReturnBridge-" !in downstreamClass) {
            "A producer-recorded interface MethodImpl must suppress a downstream class bridge:\n$downstreamClass"
        }
        runDotNet(
            dotnetHost,
            downstreamApplication,
            downstreamDirectory,
            "Downstream Kotlin consumer of an external covariant DIM failed",
        )

        val downstreamCSharpSource = downstreamDirectory.resolve("downstream.cs").apply {
            writeText(
                """
                using System;

                public sealed class ForeignDefaultMaker : covariancerefined.RefinedDefaultMaker
                {
                }

                public static class Program
                {
                    public static int Main()
                    {
                        covarianceabi.DefaultMaker value = new ForeignDefaultMaker();
                        if (value.defaultMake().tag != "external-refined-default")
                            throw new Exception("external foreign covariant DIM");
                        return 0;
                    }
                }
                """.trimIndent()
            )
        }
        val downstreamCSharpApplication = downstreamDirectory.resolve("Covariance.Downstream.CSharp.dll")
        val downstreamCSharpCompile = runModernCSharpCompiler(
            checkNotNull(modernCSharp),
            downstreamCSharpSource,
            downstreamCSharpApplication,
            refinedDirectory.resolve("Covariance.Refined.dll"),
            libraryDirectory.resolve("Covariance.Library.dll"),
            downstreamDirectory.resolve("Kotlin.Runtime.dll"),
            target = "exe",
        )
        assertEquals(0, downstreamCSharpCompile.exitCode, downstreamCSharpCompile.output)
        downstreamDirectory.resolve("Covariance.Downstream.runtimeconfig.json").copyTo(
            downstreamDirectory.resolve("Covariance.Downstream.CSharp.runtimeconfig.json"),
            overwrite = true,
        )
        runDotNet(
            dotnetHost,
            downstreamCSharpApplication,
            downstreamDirectory,
            "Downstream C# consumer of an external covariant DIM failed",
        )
    }

    @Test
    fun testKotlinExceptionInheritanceAcrossPortableLibraryBoundary() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ILAsm is not available",
        )
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val dotnetHost = modernDotNetHostOrSkip()
        val libraryDirectory = File(tmpdir, "portable-exception-library")
        val librarySource = File(tmpdir, "portable-exception-library.kt").apply {
            writeText(
                """
                package crossfailure

                public open class LibraryRuntimeFailure(message: String) : RuntimeException(message)

                public class LibraryRuntimeChild(message: String) : LibraryRuntimeFailure(message)

                public class LibraryFatalFailure(message: String) : Error(message)

                public fun libraryRuntimeFailure(): Throwable = LibraryRuntimeChild("library-runtime")

                public fun libraryFatalFailure(): Throwable = LibraryFatalFailure("library-fatal")

                public fun classifySupplied(value: Throwable): Int = try {
                    throw value
                } catch (failure: LibraryRuntimeFailure) {
                    2
                } catch (failure: RuntimeException) {
                    3
                } catch (failure: Exception) {
                    1
                } catch (failure: Error) {
                    4
                } catch (failure: Throwable) {
                    0
                }
                """.trimIndent()
            )
        }
        compileInProcess(
            K2DotNetCompiler(),
            librarySource.path,
            K2DotNetCompilerArguments::dotNetProduceLibrary.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "netstandard2.0",
            K2DotNetCompilerArguments::moduleName.cliArgument, "Exception.Library",
            K2DotNetCompilerArguments::destination.cliArgument, libraryDirectory.path,
        )

        val metadataLibrary = libraryDirectory.resolve("Exception.Library.klib")
        val libraryIl = libraryDirectory.resolve("Exception.Library.il").readText()
        assertTrue(
            Regex(
                "'crossfailure\\.LibraryRuntimeFailure'\\s+extends " +
                        "\\[Kotlin\\.Runtime]'Kotlin\\.RuntimeException'"
            ).containsMatchIn(libraryIl)
        ) { libraryIl }
        assertTrue(
            Regex(
                "'crossfailure\\.LibraryRuntimeChild'\\s+extends " +
                        "'crossfailure\\.LibraryRuntimeFailure'"
            ).containsMatchIn(libraryIl)
        ) { libraryIl }
        assertTrue(
            Regex(
                "'crossfailure\\.LibraryFatalFailure'\\s+extends \\[Kotlin\\.Runtime]'Kotlin\\.Error'"
            ).containsMatchIn(libraryIl)
        ) { libraryIl }

        for (target in listOf("net48", "net10.0")) {
            val consumerDirectory = libraryDirectory.resolve("consumer-${target.replace('.', '-')}").apply { mkdirs() }
            val consumerSource = consumerDirectory.resolve("consumer.kt").apply {
                writeText(
                    """
                    package exceptionconsumer

                    import crossfailure.*

                    private class ConsumerRuntimeChild(message: String) : LibraryRuntimeFailure(message)

                    private fun caughtAsLibraryType(value: Throwable): Boolean = try {
                        throw value
                    } catch (failure: LibraryRuntimeFailure) {
                        failure === value
                    } catch (failure: Throwable) {
                        false
                    }

                    private fun caughtAsRuntime(value: Throwable): Boolean = try {
                        throw value
                    } catch (failure: RuntimeException) {
                        failure === value
                    } catch (failure: Throwable) {
                        false
                    }

                    fun main() {
                        val libraryRuntime = libraryRuntimeFailure()
                        if (libraryRuntime !is LibraryRuntimeChild ||
                            libraryRuntime !is LibraryRuntimeFailure ||
                            libraryRuntime !is RuntimeException ||
                            libraryRuntime !is Exception ||
                            libraryRuntime is Error
                        ) {
                            throw Error("portable library runtime classification")
                        }
                        if (!caughtAsLibraryType(libraryRuntime) || !caughtAsRuntime(libraryRuntime)) {
                            throw Error("portable library runtime catch/identity")
                        }

                        val consumerRuntime: Throwable = ConsumerRuntimeChild("consumer-runtime")
                        if (consumerRuntime !is LibraryRuntimeFailure ||
                            consumerRuntime !is RuntimeException ||
                            !caughtAsLibraryType(consumerRuntime) ||
                            !caughtAsRuntime(consumerRuntime) ||
                            classifySupplied(consumerRuntime) != 2
                        ) {
                            throw Error("consumer subclass of portable exception")
                        }

                        if (classifySupplied(IllegalStateException("mapped-runtime")) != 3) {
                            throw Error("portable library mapped-runtime classification")
                        }
                        if (classifySupplied(Exception("plain")) != 1) {
                            throw Error("portable library plain-exception classification")
                        }
                        if (classifySupplied(Error("application-fatal")) != 4) {
                            throw Error("portable library application-error classification")
                        }

                        val libraryFatal = libraryFatalFailure()
                        if (libraryFatal !is LibraryFatalFailure ||
                            libraryFatal !is Error ||
                            libraryFatal is Exception ||
                            caughtAsRuntime(libraryFatal)
                        ) {
                            throw Error("portable library error classification")
                        }
                    }
                    """.trimIndent()
                )
            }
            val application = consumerDirectory.resolve(
                if (target == "net48") "ExceptionConsumer.exe" else "ExceptionConsumer.dll"
            )
            compileInProcess(
                K2DotNetCompiler(),
                consumerSource.path,
                K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "ExceptionConsumer",
                K2DotNetCompilerArguments::destination.cliArgument, application.path,
            )
            assertTrue(consumerDirectory.resolve("Exception.Library.dll").isFile) {
                "The portable exception implementation must be packaged beside its $target consumer"
            }
            val consumerIl = consumerDirectory.resolve("ExceptionConsumer.il").readText()
            assertTrue("[Exception.Library]'crossfailure.LibraryRuntimeFailure'" in consumerIl) { consumerIl }
            assertTrue("filter" in consumerIl) { consumerIl }

            if (target == "net10.0") {
                runDotNet(
                    dotnetHost,
                    application,
                    consumerDirectory,
                    "Portable exception consumer failed for $target",
                )
            } else {
                val process = ProcessBuilder(application.path)
                    .directory(consumerDirectory)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), "Portable exception consumer failed for $target:\n$output")
            }
        }
    }

    @Test
    fun testForeignClrExceptionIdentityAndClassificationAcrossRuntimeProfiles() {
        requireOrAssumeToolchain(
            DotNetIlAssembler.findFrameworkIlasm() != null,
            ".NET Framework ILAsm is not available",
        )
        requireOrAssumeToolchain(
            DotNetIlAssembler.findModernIlasm() != null,
            "Modern ILAsm is not available",
        )
        val csharpCompiler = DotNetIlAssembler.findFrameworkCSharpCompiler()
        requireOrAssumeToolchain(csharpCompiler != null, ".NET Framework C# compiler is not available")
        val dotnetHost = modernDotNetHostOrSkip()

        for (profile in listOf(
            "net48" to "ExceptionBoundary.exe",
            "net10.0" to "ExceptionBoundary.dll",
        )) {
            val target = profile.first
            val applicationName = profile.second
            val directory = File(tmpdir, "foreign-exception-${target.replace('.', '-')}").apply { mkdirs() }
            val source = directory.resolve("exceptionBoundary.kt").apply {
                writeText(
                    """
                    package exceptionboundary

                    public fun classification(value: Throwable): Int =
                        if (value is Error) 4
                        else if (value is RuntimeException) 3
                        else if (value is Exception) 1
                        else 0

                    public fun roundTrip(value: Throwable): Throwable = try {
                        throw value
                    } catch (failure: Exception) {
                        failure
                    } catch (failure: Error) {
                        failure
                    } catch (failure: Throwable) {
                        failure
                    }

                    public fun rethrow(value: Throwable): Nothing = try {
                        throw value
                    } catch (failure: Exception) {
                        throw failure
                    }

                    public open class KotlinRuntimeFailure(message: String) : RuntimeException(message)

                    public class KotlinRuntimeChild(message: String) : KotlinRuntimeFailure(message)

                    public class KotlinFatalFailure(message: String) : Error(message)

                    fun main() {}
                    """.trimIndent()
                )
            }
            val application = directory.resolve(applicationName)
            compileInProcess(
                K2DotNetCompiler(),
                source.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::moduleName.cliArgument, "ExceptionBoundary",
                K2DotNetCompilerArguments::destination.cliArgument, application.path,
            )
            val applicationIl = directory.resolve("ExceptionBoundary.il").readText()
            assertTrue("filter" in applicationIl) { applicationIl }
            assertTrue("IsKotlinExceptionInstance" in applicationIl) { applicationIl }

            val verifierSource = directory.resolve("ForeignExceptionVerifier.cs").apply {
                writeText(
                    """
                    using System;
                    using System.Reflection;

                    public sealed class ForeignException : Exception
                    {
                        public ForeignException(string message, Exception inner) : base(message, inner) {}
                    }

                    public static class ForeignExceptionVerifier
                    {
                        private static void Require(bool condition, string message)
                        {
                            if (!condition) throw new Exception(message);
                        }

                        public static int Main()
                        {
                            Assembly kotlinAssembly = Assembly.LoadFrom("${application.name}");
                            Type facade = kotlinAssembly.GetType("exceptionboundary.exceptionBoundaryKt", true);
                            MethodInfo classification = facade.GetMethod("classification", BindingFlags.Public | BindingFlags.Static);
                            MethodInfo roundTrip = facade.GetMethod("roundTrip", BindingFlags.Public | BindingFlags.Static);
                            MethodInfo rethrow = facade.GetMethod("rethrow", BindingFlags.Public | BindingFlags.Static);
                            Require(classification != null, "Kotlin classification facade is not public");
                            Require(roundTrip != null, "Kotlin round-trip facade is not public");
                            Require(rethrow != null, "Kotlin rethrow facade is not public");

                            Type kotlinRuntimeFailureType = kotlinAssembly.GetType(
                                "exceptionboundary.KotlinRuntimeFailure", true);
                            Type kotlinRuntimeChildType = kotlinAssembly.GetType(
                                "exceptionboundary.KotlinRuntimeChild", true);
                            Type kotlinFatalFailureType = kotlinAssembly.GetType(
                                "exceptionboundary.KotlinFatalFailure", true);
                            Require(kotlinRuntimeFailureType.BaseType.FullName == "Kotlin.RuntimeException",
                                "Kotlin RuntimeException subclass has untruthful CLR ancestry");
                            Require(kotlinRuntimeFailureType.BaseType.Assembly.GetName().Name == "Kotlin.Runtime",
                                "Kotlin RuntimeException subclass is not rooted in the runtime ABI");
                            Require(kotlinRuntimeChildType.BaseType == kotlinRuntimeFailureType,
                                "ordinary Kotlin exception inheritance was flattened");
                            Require(kotlinFatalFailureType.BaseType.FullName == "Kotlin.Error",
                                "Kotlin Error subclass has untruthful CLR ancestry");
                            Require(typeof(Exception).IsAssignableFrom(kotlinRuntimeChildType),
                                "Kotlin runtime exception is not a CLR System.Exception");
                            Require(typeof(Exception).IsAssignableFrom(kotlinFatalFailureType),
                                "Kotlin error is not a CLR System.Exception");

                            Exception kotlinRuntime = (Exception) Activator.CreateInstance(
                                kotlinRuntimeChildType, new object[] { "kotlin-runtime" });
                            Require((int) classification.Invoke(null, new object[] { kotlinRuntime }) == 3,
                                "Kotlin-owned runtime subclass lost its logical categories");
                            Require(Object.ReferenceEquals(
                                    kotlinRuntime, roundTrip.Invoke(null, new object[] { kotlinRuntime })),
                                "Kotlin-owned runtime subclass identity was replaced");

                            Exception kotlinFatal = (Exception) Activator.CreateInstance(
                                kotlinFatalFailureType, new object[] { "kotlin-fatal" });
                            Require((int) classification.Invoke(null, new object[] { kotlinFatal }) == 4,
                                "Kotlin-owned error subclass lost its logical category");
                            Require(Object.ReferenceEquals(
                                    kotlinFatal, roundTrip.Invoke(null, new object[] { kotlinFatal })),
                                "Kotlin-owned error subclass identity was replaced");

                            Exception inner = new Exception("inner");
                            ForeignException foreign = new ForeignException("foreign", inner);
                            object marker = new object();
                            foreign.Data["marker"] = marker;
                            int foreignClassification = (int) classification.Invoke(null, new object[] { foreign });
                            Exception returnedForeign = (Exception) roundTrip.Invoke(null, new object[] { foreign });
                            Require(foreignClassification == 1, "unknown CLR exception must be Kotlin Exception only");
                            Require(Object.ReferenceEquals(foreign, returnedForeign), "foreign exception identity was replaced");
                            Require(returnedForeign.GetType() == typeof(ForeignException), "foreign exact CLR type was lost");
                            Require(Object.ReferenceEquals(returnedForeign.InnerException, inner), "foreign inner exception was lost");
                            Require(Object.ReferenceEquals(returnedForeign.Data["marker"], marker), "foreign exception data was lost");
                            Require(returnedForeign.Message == "foreign", "foreign exception message was lost");
                            Require(!String.IsNullOrEmpty(returnedForeign.StackTrace) &&
                                    returnedForeign.StackTrace.Contains("roundTrip"),
                                "foreign exception did not retain its CLR stack trace after Kotlin catch/return");

                            Exception rethrownForeign = null;
                            try
                            {
                                rethrow.Invoke(null, new object[] { returnedForeign });
                                throw new Exception("Kotlin rethrow unexpectedly returned");
                            }
                            catch (TargetInvocationException invocation)
                            {
                                rethrownForeign = (Exception) invocation.InnerException;
                            }
                            Require(Object.ReferenceEquals(foreign, rethrownForeign),
                                "Kotlin catch/rethrow replaced the foreign exception object");
                            Require(rethrownForeign.GetType() == typeof(ForeignException),
                                "Kotlin catch/rethrow lost the foreign exact CLR type");
                            Require(Object.ReferenceEquals(rethrownForeign.InnerException, inner),
                                "Kotlin catch/rethrow lost the foreign inner exception");
                            Require(Object.ReferenceEquals(rethrownForeign.Data["marker"], marker),
                                "Kotlin catch/rethrow lost foreign exception data");
                            Require(rethrownForeign.Message == "foreign",
                                "Kotlin catch/rethrow lost the foreign exception message");
                            Require(!String.IsNullOrEmpty(rethrownForeign.StackTrace) &&
                                    rethrownForeign.StackTrace.Contains("rethrow"),
                                "Kotlin catch/rethrow did not expose the CLR rethrow site");

                            InvalidOperationException runtime = new InvalidOperationException("runtime");
                            int runtimeClassification = (int) classification.Invoke(null, new object[] { runtime });
                            Require(runtimeClassification == 3, "mapped CLR program fault must be Exception and RuntimeException");
                            Require(Object.ReferenceEquals(runtime, roundTrip.Invoke(null, new object[] { runtime })),
                                "mapped CLR exception identity was replaced");

                            OutOfMemoryException error = new OutOfMemoryException("error");
                            int errorClassification = (int) classification.Invoke(null, new object[] { error });
                            Require(errorClassification == 4, "CLR fatal error must be Error and not Exception");
                            Require(Object.ReferenceEquals(error, roundTrip.Invoke(null, new object[] { error })),
                                "CLR error identity was replaced");
                            return 0;
                        }
                    }
                    """.trimIndent()
                )
            }
            val verifier = directory.resolve("ForeignExceptionVerifier.exe")
            val compileResult = runCSharpCompiler(
                checkNotNull(csharpCompiler),
                verifierSource,
                verifier,
                target = "exe",
            )
            assertEquals(0, compileResult.exitCode, compileResult.output)

            val process = if (target == "net10.0") {
                directory.resolve("ExceptionBoundary.runtimeconfig.json")
                    .copyTo(directory.resolve("ForeignExceptionVerifier.runtimeconfig.json"), overwrite = true)
                ProcessBuilder(dotnetHost.path, "exec", verifier.path)
            } else {
                ProcessBuilder(verifier.path)
            }.directory(directory).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor(), "Foreign exception verifier failed for $target:\n$output")
        }
    }

    private fun produceAndConsumeBoundStdlibPair(target: String) {
        val firstPairDirectory = produceBoundStdlibPair(target, "first")
        val secondPairDirectory = produceBoundStdlibPair(target, "second")
        assertArrayEquals(
            firstPairDirectory.resolve("Kotlin.Stdlib.klib").readBytes(),
            secondPairDirectory.resolve("Kotlin.Stdlib.klib").readBytes(),
            "Packed stdlib metadata must be reproducible for target $target",
        )
        assertArrayEquals(
            firstPairDirectory.resolve("Kotlin.Stdlib.il").readBytes(),
            secondPairDirectory.resolve("Kotlin.Stdlib.il").readBytes(),
            "Compiler-owned stdlib IL must be reproducible for target $target",
        )
        assertArrayEquals(
            firstPairDirectory.resolve("Kotlin.Stdlib.dll").readBytes(),
            secondPairDirectory.resolve("Kotlin.Stdlib.dll").readBytes(),
            "Deterministic ILAsm output must be reproducible for target $target",
        )
        consumeBoundStdlibPair(firstPairDirectory, target)
        consumeInstalledStdlibPair(firstPairDirectory, target)
    }

    private fun produceBoundStdlibPair(target: String, run: String): File {
        val pairDirectory = File(tmpdir, "produced-$target-stdlib-pair-$run")
        compileInProcess(
            K2DotNetCompiler(),
            K2DotNetCompilerArguments::dotNetProduceStdlib.cliArgument,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
            K2DotNetCompilerArguments::destination.cliArgument, pairDirectory.path,
        )

        val metadataLibrary = pairDirectory.resolve("Kotlin.Stdlib.klib")
        val implementationLibrary = pairDirectory.resolve("Kotlin.Stdlib.dll")
        assertTrue(metadataLibrary.isFile) { "Expected packed metadata KLIB at $metadataLibrary" }
        assertTrue(implementationLibrary.isFile) { "Expected CLR implementation at $implementationLibrary" }
        val manifest = metadataLibrary.readKlibManifest()
        assertTrue(manifest.getProperty("unique_name") == "Kotlin.Stdlib")
        assertTrue(manifest.getProperty("dotnet_assembly_file") == "Kotlin.Stdlib.dll")
        assertEquals(target, manifest.getProperty("dotnet_library_tfm"))
        assertEquals(
            DotNetLibraryAbiCodec.implementationSha256(implementationLibrary),
            manifest.getProperty(DotNetLibraryAbiCodec.IMPLEMENTATION_SHA256_PROPERTY),
        )
        val il = pairDirectory.resolve("Kotlin.Stdlib.il").readText()
        val coreLibraryReference = if (target == "netstandard2.0") "[netstandard]" else "[mscorlib]"
        val coreLibraryAssembly = if (target == "netstandard2.0") "netstandard" else "mscorlib"
        assertTrue(".assembly extern $coreLibraryAssembly" in il)
        assertTrue("System.Runtime.Versioning.TargetFrameworkAttribute" in il)
        if (target == "netstandard2.0") assertTrue("[mscorlib]" !in il)
        assertTrue(
            "implements [Kotlin.Runtime]'Kotlin.Collections.Iterator', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.Iterator`1'<!0>" in il
        )
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.Iterator-next-" in il)
        assertTrue("<GenericInterfaceDeclaredBridge-kotlin.collections.Iterator-next-" in il)
        assertTrue(
            "implements [Kotlin.Runtime]'Kotlin.Collections.Iterable', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.Iterable`1'<!0>" in il
        )
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.Iterable-iterator-" in il)
        assertTrue("<GenericInterfaceDeclaredBridge-kotlin.collections.Iterable-iterator-" in il)
        assertTrue(".class private auto ansi sealed beforefieldinit 'Kotlin.Collections.ArrayIterator`1'" in il)
        assertTrue(".class private auto ansi sealed beforefieldinit 'Kotlin.Collections.ArrayIterable`1'" in il)
        assertTrue(
            ".method public hidebysig static class [Kotlin.Runtime]'Kotlin.Collections.Iterator' " +
                    "'dotNetArrayIterator'<'T'>(!!0[] 'array')" in il
        )
        val compilerOnlyArrayIterator = il.substring(
            il.indexOf("'dotNetArrayIterator'<'T'>").also { assertTrue(it >= 0) },
            il.indexOf("  .method", il.indexOf("'dotNetArrayIterator'<'T'>") + 1)
                .takeIf { it >= 0 } ?: il.length,
        )
        assertTrue("KotlinCompilerAbiAttribute" in compilerOnlyArrayIterator) { compilerOnlyArrayIterator }
        assertTrue("EditorBrowsableAttribute" in compilerOnlyArrayIterator) { compilerOnlyArrayIterator }
        assertTrue(
            ".method public hidebysig static class [Kotlin.Runtime]'Kotlin.Collections.Iterable' " +
                    "'dotNetArrayIterable'<'T'>(!!0[] 'array')" in il
        )
        assertTrue(
            ".method public hidebysig static !!0 'first'<'T'>(" +
                    "class [Kotlin.Runtime]'Kotlin.Collections.List' '<this>')" in il
        )
        assertTrue(
            ".method public hidebysig static !!0 'last'<'T'>(" +
                    "class [Kotlin.Runtime]'Kotlin.Collections.List' '<this>')" in il
        )
        assertTrue(
            ".class private auto ansi sealed 'Kotlin.Collections.EmptyIterator'\n" +
                    "       extends ${coreLibraryReference}System.Object\n" +
                    "       implements [Kotlin.Runtime]'Kotlin.Collections.ListIterator', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.ListIterator`1'<class [Kotlin.Runtime]'Kotlin.Nothing'>" in il
        )
        assertTrue(
            ".class private auto ansi sealed 'Kotlin.Collections.EmptyList'\n" +
                    "       extends ${coreLibraryReference}System.Object\n" +
                    "       implements [Kotlin.Runtime]'Kotlin.Collections.List', 'Kotlin.Io.Serializable', " +
                    "'Kotlin.Collections.RandomAccess', class [Kotlin.Runtime]" +
                    "'Kotlin.Collections.List__KotlinExact`1'<class [Kotlin.Runtime]'Kotlin.Nothing'>" in il
        )
        assertTrue(".class interface public abstract auto ansi 'Kotlin.Collections.RandomAccess'" in il)
        assertTrue(".class interface private abstract auto ansi 'Kotlin.Io.Serializable'" in il)
        assertTrue("class [Kotlin.Runtime]'Kotlin.Nothing'" in il)
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.ListIterator-next-" in il)
        assertTrue("<GenericInterfaceDeclaredBridge-kotlin.collections.ListIterator-next-" in il)
        assertTrue("<GenericInterfaceCanonicalBridge-kotlin.collections.List-get-" in il)
        assertTrue("<GenericInterfaceDeclaredBridge-kotlin.collections.List-get-" in il)
        assertTrue("<GenericInterfaceExactBridge-kotlin.collections.List-contains-" in il)
        assertTrue(
            ".method public hidebysig static class [Kotlin.Runtime]'Kotlin.Collections.List' " +
                    "'emptyList'<'T'>()" in il
        )
        return pairDirectory
    }

    private fun consumeBoundStdlibPair(pairDirectory: File, target: String) {
        val metadataLibrary = pairDirectory.resolve("Kotlin.Stdlib.klib")
        val consumerSource = pairDirectory.resolve("consumer.kt").apply {
            writeText(
                """
                package consumer

                public fun <T> firstAndLast(values: Iterable<T>): T {
                    values.first()
                    return values.last()
                }

                public fun <T> firstAndLastList(values: List<T>): T {
                    values.first()
                    return values.last()
                }

                public fun firstArray(values: Array<String>): String = values.iterator().next()

                public fun firstArrayIterable(values: Array<String>): String = values.asIterable().first()

                public fun emptyInts(): List<Int> = emptyList()

                public fun emptyStrings(): List<String> = emptyList()

                public fun isRandomAccess(values: List<Int>): Boolean = values is RandomAccess
                """.trimIndent()
            )
        }
        val outputFile = pairDirectory.resolve("consumer.il")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::noStdlib.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
            K2DotNetCompilerArguments::moduleName.cliArgument, "Consumer",
            K2DotNetCompilerArguments::destination.cliArgument, outputFile.path,
        )
        val il = outputFile.readText()
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'first'" in il)
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'last'" in il)
        assertTrue(
            "::'first'<!!0>(class [Kotlin.Runtime]'Kotlin.Collections.List')" in il
        )
        assertTrue(
            "::'last'<!!0>(class [Kotlin.Runtime]'Kotlin.Collections.List')" in il
        )
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'dotNetArrayIterator'<string>" in il)
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'dotNetArrayIterable'<string>" in il)
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.ArrayIterator`1'" !in il)
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.ArrayIterable`1'" !in il)
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'emptyList'<int32>" in il)
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'emptyList'<string>" in il)
        assertTrue("isinst class [Kotlin.Stdlib]'Kotlin.Collections.RandomAccess'" in il)
    }

    private fun consumeInstalledStdlibPair(
        pairDirectory: File,
        target: String,
        installedProfile: String = target,
    ) {
        val kotlinHome = File(tmpdir, "kotlin-home-$target-$installedProfile")
        val installedDirectory = kotlinHome.resolve("lib/dotnet/$installedProfile").apply { mkdirs() }
        pairDirectory.resolve("Kotlin.Stdlib.klib").copyTo(installedDirectory.resolve("Kotlin.Stdlib.klib"))
        pairDirectory.resolve("Kotlin.Stdlib.dll").copyTo(installedDirectory.resolve("Kotlin.Stdlib.dll"))
        val consumerSource = File(tmpdir, "installed-consumer-$target.kt").apply {
            writeText(
                """
                package consumer

                public fun <T> installedFirstAndLast(values: Iterable<T>): T {
                    values.first()
                    return values.last()
                }

                public fun <T> installedFirstAndLastList(values: List<T>): T {
                    values.first()
                    return values.last()
                }

                public fun installedEmptyInts(): List<Int> = emptyList()

                public fun installedRandomAccess(values: List<Int>): Boolean = values is RandomAccess
                """.trimIndent()
            )
        }
        val outputFile = File(tmpdir, "installed-consumer-$target.il")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::kotlinHome.cliArgument, kotlinHome.path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
            K2DotNetCompilerArguments::moduleName.cliArgument, "InstalledConsumer",
            K2DotNetCompilerArguments::destination.cliArgument, outputFile.path,
        )
        val il = outputFile.readText()
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'first'" in il)
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'last'" in il)
        assertTrue("::'first'<!!0>(class [Kotlin.Runtime]'Kotlin.Collections.List')" in il)
        assertTrue("::'last'<!!0>(class [Kotlin.Runtime]'Kotlin.Collections.List')" in il)
        assertTrue("[Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'emptyList'<int32>" in il)
        assertTrue("isinst class [Kotlin.Stdlib]'Kotlin.Collections.RandomAccess'" in il)
        assertTrue(".class public abstract sealed auto ansi beforefieldinit 'Kotlin.Collections.CollectionsKt'" !in il)

        val forbiddenKotlinPackageSource = File(tmpdir, "installed-forbidden-kotlin-package-$target.kt").apply {
            writeText(
                """
                package kotlin.user

                public fun mustNotCompile(): Int = 42
                """.trimIndent()
            )
        }
        val [diagnostics, exitCode] = AbstractCliTest.executeCompilerGrabOutput(
            K2DotNetCompiler(),
            listOf(
                forbiddenKotlinPackageSource.path,
                K2DotNetCompilerArguments::kotlinHome.cliArgument, kotlinHome.path,
                K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
                K2DotNetCompilerArguments::destination.cliArgument,
                File(tmpdir, "installed-forbidden-kotlin-package-$target.il").path,
            )
        )
        assertEquals(ExitCode.COMPILATION_ERROR, exitCode, diagnostics)
        assertTrue("only the Kotlin standard library is allowed to use the 'kotlin' package" in diagnostics) { diagnostics }
    }

    private fun executeBoundStdlibPair(pairDirectory: File, target: String, dotnetHost: File?) {
        val directory = File(tmpdir, "portable-stdlib-execution-$target").apply { mkdirs() }
        val source = directory.resolve("main.kt").apply {
            writeText(
                """
                fun main() {
                    val values = Array<String>(2) { index -> if (index == 0) "O" else "K" }
                    println(values.asIterable().first() + values.asIterable().last())
                }
                """.trimIndent()
            )
        }
        val output = directory.resolve(if (target == "net48") "PortableStdlib.exe" else "PortableStdlib.dll")
        compileInProcess(
            K2DotNetCompiler(),
            source.path,
            K2DotNetCompilerArguments::noStdlib.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, pairDirectory.resolve("Kotlin.Stdlib.klib").path,
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, target,
            K2DotNetCompilerArguments::moduleName.cliArgument, "PortableStdlib",
            K2DotNetCompilerArguments::destination.cliArgument, output.path,
        )
        assertTrue(directory.resolve("Kotlin.Stdlib.dll").isFile)
        assertTrue(directory.resolve("Kotlin.Runtime.dll").isFile)

        val command = if (target == "net48") {
            listOf(output.path)
        } else {
            listOf(checkNotNull(dotnetHost).path, "exec", output.path)
        }
        val process = ProcessBuilder(command)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        val processOutput = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), processOutput)
        assertEquals("OK", processOutput.trim())
    }

    @Test
    fun testConsumesExternalStdlibMetadataPair() {
        val pairDirectory = File(tmpdir, "dotnet-stdlib-pair").apply { mkdirs() }
        val metadataSource = File(pairDirectory, "stdlib.kt").apply {
            writeText(
                """
                package kotlin.collections

                public fun <T> Iterable<T>.first(): T = iterator().next()
                """.trimIndent()
            )
        }
        val metadataLibrary = File(pairDirectory, "Kotlin.Stdlib.klib")
        compileInProcess(
            KotlinMetadataCompiler(),
            metadataSource.path,
            K2MetadataCompilerArguments::allowKotlinPackage.cliArgument,
            K2MetadataCompilerArguments::moduleName.cliArgument, "Kotlin.Stdlib",
            K2MetadataCompilerArguments::destination.cliArgument, metadataLibrary.path,
        )
        val physicalDeclarations = DotNetLibraryAbiCodec.encode(
            mapOf(
                "F:kotlin.collections/first|-4901127747075485546[0]" to DotNetPhysicalDeclaration.Function(
                    ownerPath = listOf("Kotlin.Collections.CollectionsKt"),
                    methodName = "first",
                    isInstance = false,
                )
            )
        )
        // IL-only compilation checks that the bound physical companion exists; executable tests
        // separately validate the real generated stdlib assembly.
        val implementationLibrary = File(pairDirectory, "Kotlin.Stdlib.dll").apply {
            writeBytes(byteArrayOf(0))
        }
        val dotNetManifestProperties = linkedMapOf(
            DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY to DotNetLibraryAbiCodec.ABI_VERSION,
            DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME_PROPERTY to DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME,
            DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION_PROPERTY to
                    DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION,
            DotNetLibraryAbiCodec.RUNTIME_SURFACE_LEVEL_PROPERTY to
                    DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL.toString(),
            DotNetLibraryAbiCodec.IMPLEMENTATION_SHA256_PROPERTY to
                    DotNetLibraryAbiCodec.implementationSha256(implementationLibrary),
            DotNetLibraryAbiCodec.FRIEND_ASSEMBLIES_PROPERTY to "",
            "dotnet_assembly_name" to "Kotlin.Stdlib",
            "dotnet_assembly_version" to "1.0.0.0",
            "dotnet_assembly_culture" to "neutral",
            "dotnet_assembly_public_key_token" to "null",
            "dotnet_assembly_file" to "Kotlin.Stdlib.dll",
            "dotnet_library_tfm" to "netstandard2.0",
        ).apply { putAll(physicalDeclarations) }
        File(metadataLibrary, "default/manifest").appendText(
            dotNetManifestProperties.entries.joinToString(prefix = "\n", separator = "\n", postfix = "\n") { (key, value) ->
                "$key=$value"
            }
        )

        val consumerSource = File(pairDirectory, "consumer.kt").apply {
            writeText(
                """
                package consumer

                public fun <T> consume(values: Iterable<T>): T = values.first()
                """.trimIndent()
            )
        }
        val outputFile = File(pairDirectory, "consumer.il")
        compileInProcess(
            K2DotNetCompiler(),
            consumerSource.path,
            K2DotNetCompilerArguments::noStdlib.cliArgument,
            K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
            K2DotNetCompilerArguments::moduleName.cliArgument, "Consumer",
            K2DotNetCompilerArguments::destination.cliArgument, outputFile.path,
        )

        val il = outputFile.readText()
        assertTrue(
            "call !!0 [Kotlin.Stdlib]'Kotlin.Collections.CollectionsKt'::'first'<!!0>" in il,
        ) { "Expected a generic call through the external stdlib assembly:\n$il" }
        assertTrue(".class public abstract sealed auto ansi beforefieldinit 'Kotlin.Collections.CollectionsKt'" !in il) {
            "The external stdlib implementation must not be regenerated in the consumer:\n$il"
        }
    }

    @Test
    fun testRejectsStaleDotNetLibraryAbiSchema() {
        val metadataLibrary = createBoundMetadataLibrary(
            assemblyName = "Stale.Schema",
            propertyOverrides = mapOf(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY to "2"),
        )
        val diagnostics = compileAgainstRejectedLibrary(metadataLibrary)
        assertTrue("uses unsupported CLR ABI index version '2'" in diagnostics) { diagnostics }
    }

    @Test
    fun testRejectsMismatchedDotNetLibraryProfile() {
        val metadataLibrary = createBoundMetadataLibrary(
            assemblyName = "Wrong.Profile",
            propertyOverrides = mapOf(
                DotNetLibraryArtifact.METADATA_LIBRARY_TARGET_FRAMEWORK_PROPERTY to "net10.0"
            ),
        )
        val diagnostics = compileAgainstRejectedLibrary(metadataLibrary)
        assertTrue("is not compatible with Kotlin/.NET target 'net48'" in diagnostics) {
            diagnostics
        }
    }

    @Test
    fun testRejectsMismatchedDotNetImplementationAssembly() {
        val metadataLibrary = createBoundMetadataLibrary(
            assemblyName = "Wrong.Implementation",
            propertyOverrides = emptyMap(),
        )
        metadataLibrary.parentFile.resolve("Wrong.Implementation.dll").writeBytes(byteArrayOf(1))
        val diagnostics = compileAgainstRejectedLibrary(metadataLibrary)
        assertTrue("but its SHA-256 is" in diagnostics && "instead of" in diagnostics) { diagnostics }
    }

    @Test
    fun testRejectsUnsupportedRuntimeSurfaceLevel() {
        val unsupportedLevel = DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL + 1
        val metadataLibrary = createBoundMetadataLibrary(
            assemblyName = "Future.Runtime.Surface",
            propertyOverrides = mapOf(
                DotNetLibraryAbiCodec.RUNTIME_SURFACE_LEVEL_PROPERTY to unsupportedLevel.toString()
            ),
        )
        val diagnostics = compileAgainstRejectedLibrary(metadataLibrary)
        assertTrue("requires unsupported Kotlin.Runtime surface level '$unsupportedLevel'" in diagnostics) {
            diagnostics
        }
    }

    private fun createBoundMetadataLibrary(
        assemblyName: String,
        propertyOverrides: Map<String, String>,
    ): File {
        val directory = File(tmpdir, assemblyName).apply { mkdirs() }
        val source = directory.resolve("library.kt").apply {
            writeText(
                """
                package fixture

                public fun published(): Int = 1
                """.trimIndent()
            )
        }
        val metadataLibrary = directory.resolve("$assemblyName.klib")
        compileInProcess(
            KotlinMetadataCompiler(),
            source.path,
            K2MetadataCompilerArguments::moduleName.cliArgument, assemblyName,
            K2MetadataCompilerArguments::destination.cliArgument, metadataLibrary.path,
        )
        val implementationLibrary = directory.resolve("$assemblyName.dll").apply {
            writeBytes(byteArrayOf(0))
        }
        val properties = linkedMapOf(
            DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY to DotNetLibraryAbiCodec.ABI_VERSION,
            DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME_PROPERTY to DotNetLibraryAbiCodec.LOGICAL_IDENTITY_SCHEME,
            DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION_PROPERTY to
                    DotNetLibraryAbiCodec.PHYSICAL_NAME_GRAMMAR_VERSION,
            DotNetLibraryAbiCodec.RUNTIME_SURFACE_LEVEL_PROPERTY to
                    DotNetLibraryAbiCodec.CURRENT_RUNTIME_SURFACE_LEVEL.toString(),
            DotNetLibraryAbiCodec.IMPLEMENTATION_SHA256_PROPERTY to
                    DotNetLibraryAbiCodec.implementationSha256(implementationLibrary),
            DotNetLibraryAbiCodec.FRIEND_ASSEMBLIES_PROPERTY to "",
            DotNetLibraryArtifact.METADATA_ASSEMBLY_NAME_PROPERTY to assemblyName,
            DotNetLibraryArtifact.METADATA_ASSEMBLY_VERSION_PROPERTY to "1.0.0.0",
            DotNetLibraryArtifact.METADATA_ASSEMBLY_CULTURE_PROPERTY to "neutral",
            DotNetLibraryArtifact.METADATA_ASSEMBLY_PUBLIC_KEY_TOKEN_PROPERTY to "null",
            DotNetLibraryArtifact.METADATA_ASSEMBLY_FILE_PROPERTY to "$assemblyName.dll",
            DotNetLibraryArtifact.METADATA_LIBRARY_TARGET_FRAMEWORK_PROPERTY to "netstandard2.0",
        ).apply { putAll(propertyOverrides) }
        File(metadataLibrary, "default/manifest").appendText(
            properties.entries.joinToString(prefix = "\n", separator = "\n", postfix = "\n") { (key, value) ->
                "$key=$value"
            }
        )
        return metadataLibrary
    }

    private fun compileAgainstRejectedLibrary(metadataLibrary: File): String {
        val source = File(tmpdir, "rejected-library-consumer-${metadataLibrary.nameWithoutExtension}.kt").apply {
            writeText("package consumer\n\npublic fun answer(): Int = 42")
        }
        val [diagnostics, exitCode] = AbstractCliTest.executeCompilerGrabOutput(
            K2DotNetCompiler(),
            listOf(
                source.path,
                K2DotNetCompilerArguments::noStdlib.cliArgument,
                K2DotNetCompilerArguments::classpath.cliArgument, metadataLibrary.path,
                K2DotNetCompilerArguments::destination.cliArgument,
                File(tmpdir, "rejected-library-consumer-${metadataLibrary.nameWithoutExtension}.il").path,
            )
        )
        assertEquals(ExitCode.COMPILATION_ERROR, exitCode, diagnostics)
        return diagnostics
    }

    private data class CSharpCompilerResult(val exitCode: Int, val output: String)

    private fun runCSharpCompiler(
        compiler: File,
        source: File,
        output: File,
        vararg references: File,
        target: String = "library",
    ): CSharpCompilerResult {
        output.delete()
        val arguments = buildList {
            add(compiler.path)
            add("/nologo")
            add("/target:$target")
            add("/out:${output.path}")
            references.forEach { add("/reference:${it.path}") }
            add(source.path)
        }
        val process = ProcessBuilder(arguments)
            .directory(tmpdir)
            .redirectErrorStream(true)
            .start()
        val compilerOutput = process.inputStream.bufferedReader().use { it.readText() }
        return CSharpCompilerResult(process.waitFor(), compilerOutput)
    }

    private fun runModernCSharpCompiler(
        toolchain: DotNetModernCSharpToolchain,
        source: File,
        output: File,
        vararg references: File,
        target: String = "library",
    ): CSharpCompilerResult {
        output.delete()
        val frameworkReferences = toolchain.referenceDirectory.listFiles { file ->
            file.isFile && file.extension.equals("dll", ignoreCase = true)
        }?.sortedBy(File::getName)
            ?: error("Modern C# reference directory is unreadable: ${toolchain.referenceDirectory}")
        val arguments = buildList {
            add(toolchain.dotNetHost.path)
            add(toolchain.compiler.path)
            add("/nologo")
            add("/noconfig")
            add("/nostdlib+")
            add("/deterministic+")
            add("/langversion:latest")
            add("/target:$target")
            add("/out:${output.path}")
            frameworkReferences.forEach { add("/reference:${it.path}") }
            references.forEach { reference ->
                assertTrue(reference.isFile) { "Missing C# reference: $reference" }
                add("/reference:${reference.path}")
            }
            add(source.path)
        }
        val process = ProcessBuilder(arguments)
            .directory(tmpdir)
            .redirectErrorStream(true)
            .start()
        val compilerOutput = process.inputStream.bufferedReader().use { it.readText() }
        return CSharpCompilerResult(process.waitFor(), compilerOutput)
    }

    private fun compileInProcess(compiler: CLICompiler<*>, vararg args: String) {
        val [output, exitCode] = AbstractCliTest.executeCompilerGrabOutput(compiler, args.toList())
        if (exitCode != ExitCode.OK) error("Failed to compile: ${args.joinToString(" ")}\nOutput:\n$output")
    }

    private fun modernDotNetHostOrSkip(): File {
        val host = DotNetIlAssembler.findModernDotNetHost()
        requireOrAssumeToolchain(host != null, "Modern dotnet host is not available")
        return checkNotNull(host)
    }

    private fun findFrameworkNetStandardFacade(): File? {
        val windowsDirectory = System.getenv("WINDIR")?.let(::File)
        val programFilesX86 = System.getenv("ProgramFiles(x86)")?.let(::File)
        val candidates = listOfNotNull(
            programFilesX86?.resolve(
                "Reference Assemblies/Microsoft/Framework/.NETFramework/v4.8/Facades/netstandard.dll"
            ),
            windowsDirectory?.resolve(
                "Microsoft.NET/assembly/GAC_MSIL/netstandard/" +
                        "v4.0_2.0.0.0__cc7b13ffcd2ddd51/netstandard.dll"
            ),
        )
        return candidates.firstOrNull { candidate -> candidate.isFile }
    }

    private fun frameworkExecutionCommand(host: File, assembly: File): List<String> {
        val escapedAssemblyPath = assembly.absolutePath.replace("'", "''")
        val command = """
            ${'$'}ErrorActionPreference = 'Stop'
            try {
                ${'$'}assembly = [Reflection.Assembly]::LoadFrom('$escapedAssemblyPath')
                ${'$'}entryPoint = ${'$'}assembly.EntryPoint
                if (${'$'}null -eq ${'$'}entryPoint) { throw 'Assembly has no managed entry point.' }
                if (${'$'}entryPoint.GetParameters().Count -eq 0) {
                    [void] ${'$'}entryPoint.Invoke(${'$'}null, ${'$'}null)
                } else {
                    [void] ${'$'}entryPoint.Invoke(${'$'}null, [object[]] @(,[string[]] @()))
                }
            } catch {
                [Console]::Error.WriteLine(${'$'}_.Exception.ToString())
                exit 1
            }
        """.trimIndent()
        return listOf(host.path, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command)
    }

    private fun requireOrAssumeToolchain(condition: Boolean, message: String) {
        if (dotNetToolchainIsRequired()) {
            assertTrue(condition) { "$message (KOTLIN_DOTNET_REQUIRE_TOOLCHAIN is enabled)" }
        } else {
            assumeTrue(condition, message)
        }
    }

    private fun dotNetToolchainIsRequired(): Boolean =
        System.getenv("KOTLIN_DOTNET_REQUIRE_TOOLCHAIN")?.let { value ->
            value == "1" || value.equals("true", ignoreCase = true)
        } == true

    private fun File.readKlibManifest(): Properties = ZipFile(this).use { archive ->
        Properties().apply {
            load(archive.getInputStream(archive.getEntry("default/manifest")))
        }
    }

    private fun runDotNet(
        dotnetHost: File,
        assembly: File,
        workingDirectory: File,
        failureMessage: String,
    ) {
        val process = ProcessBuilder(dotnetHost.path, "exec", assembly.path)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(process.waitFor() == 0) { "$failureMessage:\n$output" }
    }

    private fun runAssemblerPairing(command: List<String>, workingDirectory: File, description: String) {
        val process = ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), "$description failed:\n$output")
        assertEquals("OK", output.trim(), "$description produced unexpected output")
    }
}
