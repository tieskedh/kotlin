/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli

import org.jetbrains.kotlin.backend.dotnet.DotNetIlAssembler
import org.jetbrains.kotlin.backend.dotnet.DotNetLibraryAbiCodec
import org.jetbrains.kotlin.backend.dotnet.DotNetPhysicalDeclaration
import org.jetbrains.kotlin.backend.dotnet.DotNetTarget
import org.jetbrains.kotlin.cli.common.CLICompiler
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2DotNetCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2MetadataCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.cliArgument
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.dotnet.K2DotNetCompiler
import org.jetbrains.kotlin.cli.metadata.KotlinMetadataCompiler
import org.jetbrains.kotlin.test.TestCaseWithTmpdir
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
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
            ),
            "F:sample/increment" to DotNetPhysicalDeclaration.Function(
                ownerPath = listOf("sample.LibraryKt"),
                methodName = "Increment",
                isInstance = false,
            ),
        )
        val properties = Properties().apply {
            setProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY, DotNetLibraryAbiCodec.ABI_VERSION)
            putAll(DotNetLibraryAbiCodec.encode(declarations))
        }

        assertEquals("2", properties.getProperty(DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY))
        assertEquals(declarations, DotNetLibraryAbiCodec.decode(properties))
    }

    @Test
    fun testGenericInterfacesAcrossLibraryBoundary() {
        assumeTrue(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val dotnetHost = DotNetIlAssembler.findModernDotNetHost()
            ?: return
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
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net",
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
                DotNetTarget.NET,
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
    fun testProducesPortableUserLibraryPair() {
        assumeTrue(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
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
        assertTrue(manifest.getProperty("dotnet_library_tfm") == "netstandard2.0")
        assertTrue(manifest.getProperty("dotnet_abi_version") == "2")
        assertTrue(manifest.stringPropertyNames().any { it.startsWith("dotnet_decl_") })

        val il = outputDirectory.resolve("Sample.Library.il").readText()
        assertTrue(".assembly extern netstandard" in il)
        assertTrue("System.Runtime.Versioning.TargetFrameworkAttribute" in il)
        assertTrue(".ver 1:0:0:0" in il)
        assertTrue(".module 'Sample.Library.dll'" in il)
        assertTrue("'Increment'(int32 'value')" in il)
        assertTrue(".entrypoint" !in il)
        assertTrue("[mscorlib]" !in il)

        val dotnetHost = DotNetIlAssembler.findModernDotNetHost() ?: return
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
                DotNetTarget.NET,
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
            K2DotNetCompilerArguments::dotNetTarget.cliArgument, "net",
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
    }

    @Test
    fun testProducesPortableStdlibPairForModernRuntimeSelection() {
        assumeTrue(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        produceAndConsumeBoundStdlibPair("net")
    }

    @Test
    fun testProducesPortableStdlibPairForFrameworkRuntimeSelection() {
        assumeTrue(DotNetIlAssembler.findModernIlasm() != null, "Portable-library ilasm is not available")
        produceAndConsumeBoundStdlibPair("netframework")
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
        // ILAsm currently stamps a fresh PE identity even for identical input. Its DLL bytes are
        // therefore outside this compiler-owned reproducibility gate; the manifest identity and
        // a separate consumer compilation below pin the durable assembly contract instead.
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
        assertTrue(manifest.getProperty("dotnet_library_tfm") == "netstandard2.0")
        val il = pairDirectory.resolve("Kotlin.Stdlib.il").readText()
        assertTrue(".assembly extern netstandard" in il)
        assertTrue("System.Runtime.Versioning.TargetFrameworkAttribute" in il)
        assertTrue("[mscorlib]" !in il)
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
                    "       extends [netstandard]System.Object\n" +
                    "       implements [Kotlin.Runtime]'Kotlin.Collections.ListIterator', " +
                    "class [Kotlin.Runtime]'Kotlin.Collections.ListIterator`1'<class [Kotlin.Runtime]'Kotlin.Nothing'>" in il
        )
        assertTrue(
            ".class private auto ansi sealed 'Kotlin.Collections.EmptyList'\n" +
                    "       extends [netstandard]System.Object\n" +
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

    private fun consumeInstalledStdlibPair(pairDirectory: File, target: String) {
        val kotlinHome = File(tmpdir, "kotlin-home-$target")
        val installedDirectory = kotlinHome.resolve("lib/dotnet/netstandard2.0").apply { mkdirs() }
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
        val dotNetManifestProperties = linkedMapOf(
            DotNetLibraryAbiCodec.ABI_VERSION_PROPERTY to DotNetLibraryAbiCodec.ABI_VERSION,
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
        // IL-only compilation checks that the bound physical companion exists; executable tests
        // separately validate the real generated stdlib assembly.
        File(pairDirectory, "Kotlin.Stdlib.dll").writeBytes(byteArrayOf(0))

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

    private fun compileInProcess(compiler: CLICompiler<*>, vararg args: String) {
        val [output, exitCode] = AbstractCliTest.executeCompilerGrabOutput(compiler, args.toList())
        if (exitCode != ExitCode.OK) error("Failed to compile: ${args.joinToString(" ")}\nOutput:\n$output")
    }

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
}
