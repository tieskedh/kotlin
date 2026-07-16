/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli

import org.jetbrains.kotlin.backend.dotnet.DotNetIlAssembler
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

class DotNetLibraryIntegrationTest : TestCaseWithTmpdir() {
    @Test
    fun testProducesPortableUserLibraryPair() {
        assumeTrue(DotNetIlAssembler.findModernIlasm() != null, "Modern ilasm is not available")
        val source = File(tmpdir, "library.kt").apply {
            writeText(
                """
                package sample

                public fun increment(value: Int): Int = value + 1
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
        val manifest = ZipFile(metadataLibrary).use { archive ->
            Properties().apply {
                load(archive.getInputStream(archive.getEntry("default/manifest")))
            }
        }
        assertTrue(manifest.getProperty("unique_name") == "Sample.Library")
        assertTrue(manifest.getProperty("dotnet_assembly_name") == "Sample.Library")
        assertTrue(manifest.getProperty("dotnet_assembly_version") == "1.0.0.0")
        assertTrue(manifest.getProperty("dotnet_assembly_file") == "Sample.Library.dll")
        assertTrue(manifest.getProperty("dotnet_library_tfm") == "netstandard2.0")

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
        val process = ProcessBuilder(dotnetHost.path, "exec", consumerAssembly.path)
            .directory(outputDirectory)
            .redirectErrorStream(true)
            .start()
        val processOutput = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(process.waitFor() == 0) { "Portable library consumer failed:\n$processOutput" }
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
        val manifest = ZipFile(metadataLibrary).use { archive ->
            Properties().apply {
                load(archive.getInputStream(archive.getEntry("default/manifest")))
            }
        }
        assertTrue(manifest.getProperty("unique_name") == "Kotlin.Stdlib")
        assertTrue(manifest.getProperty("dotnet_assembly_file") == "Kotlin.Stdlib.dll")
        assertTrue(manifest.getProperty("dotnet_library_tfm") == "netstandard2.0")
        val il = pairDirectory.resolve("Kotlin.Stdlib.il").readText()
        assertTrue(".assembly extern netstandard" in il)
        assertTrue("System.Runtime.Versioning.TargetFrameworkAttribute" in il)
        assertTrue("[mscorlib]" !in il)
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
        File(metadataLibrary, "default/manifest").appendText(
            "\ndotnet_assembly_name=Kotlin.Stdlib" +
                    "\ndotnet_assembly_version=1.0.0.0" +
                    "\ndotnet_assembly_culture=neutral" +
                    "\ndotnet_assembly_public_key_token=null" +
                    "\ndotnet_assembly_file=Kotlin.Stdlib.dll" +
                    "\ndotnet_library_tfm=netstandard2.0\n"
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
}
