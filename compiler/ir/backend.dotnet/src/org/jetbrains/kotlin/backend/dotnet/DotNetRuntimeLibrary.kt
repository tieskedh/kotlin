package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import java.io.File

/**
 * The first physical Kotlin/.NET runtime boundary.
 *
 * The assembly boundary was established before its first public ABI candidate types. It now owns
 * the fixed, physically erased Function0/1/2 interfaces, the orthogonal KCallable/KFunction
 * reflection view, and the singleton Unit value required when a callable result crosses the
 * object-shaped invocation boundary. Compiler support shared by generated modules lives below the
 * reserved `Kotlin.Runtime.Internal` namespace.
 * The same TFM-neutral IL source is assembled with the selected target's ILAsm, so both targets
 * produce their own PE while exposing exactly the same logical assembly identity.
 */
internal object DotNetRuntimeLibrary {
    const val ASSEMBLY_NAME = "Kotlin.Runtime"
    const val ASSEMBLY_FILE_NAME = "$ASSEMBLY_NAME.dll"
    const val ASSEMBLY_VERSION_IL = "1:0:0:0"

    fun assembleNextTo(
        executableOutput: File,
        target: DotNetTarget,
        messageCollector: MessageCollector,
    ): File? {
        val outputDirectory = executableOutput.parentFile ?: File(".")
        outputDirectory.mkdirs()
        val output = outputDirectory.resolve(ASSEMBLY_FILE_NAME)
        val ilFile = File.createTempFile("Kotlin.Runtime-", ".il", outputDirectory)
        return try {
            // ILAsm decodes BOM-less input as ANSI; keep the runtime source on the same UTF-8+BOM
            // path as generated program IL even though its current text is ASCII-only.
            ilFile.writeBytes(UTF8_BOM + IL_TEXT.toByteArray(Charsets.UTF_8))
            output.takeIf { DotNetIlAssembler.assembleLibrary(ilFile, output, target, messageCollector) }
        } finally {
            ilFile.delete()
        }
    }

    private val IL_TEXT = """
        .assembly extern mscorlib {}
        .assembly Kotlin.Runtime
        {
          .ver $ASSEMBLY_VERSION_IL
        }
        .module Kotlin.Runtime.dll

        .namespace Kotlin.Runtime
        {
          .class public abstract sealed auto ansi beforefieldinit RuntimeInfo
                 extends [mscorlib]System.Object
          {
          }
        }

        .namespace Kotlin
        {
          .class interface public abstract auto ansi Function
          {
          }

          .class interface public abstract auto ansi KCallable
          {
            .method public hidebysig specialname newslot abstract virtual instance string 'get_name'() cil managed
            {
            }
            .property instance string name()
            {
              .get instance string Kotlin.KCallable::'get_name'()
            }
          }

          .class interface public abstract auto ansi KFunction
                 implements Kotlin.KCallable, Kotlin.Function
          {
          }

          .class interface public abstract auto ansi Function0
                 implements Kotlin.Function
          {
            .method public hidebysig newslot abstract virtual instance object Invoke() cil managed
            {
            }
          }

          .class interface public abstract auto ansi Function1
                 implements Kotlin.Function
          {
            .method public hidebysig newslot abstract virtual instance object Invoke(object p1) cil managed
            {
            }
          }

          .class interface public abstract auto ansi Function2
                 implements Kotlin.Function
          {
            .method public hidebysig newslot abstract virtual instance object Invoke(object p1, object p2) cil managed
            {
            }
          }

          .class public sealed auto ansi Unit extends [mscorlib]System.Object
          {
            .field public static initonly class Kotlin.Unit INSTANCE

            .method private hidebysig specialname rtspecialname instance void .ctor() cil managed
            {
              .maxstack 1
              ldarg.0
              call instance void [mscorlib]System.Object::.ctor()
              ret
            }

            .method private hidebysig specialname rtspecialname static void .cctor() cil managed
            {
              .maxstack 1
              newobj instance void Kotlin.Unit::.ctor()
              stsfld class Kotlin.Unit Kotlin.Unit::INSTANCE
              ret
            }
          }
        }
    """.trimIndent() + "\n" + DotNetRuntimeLibraryHelpers.ilText

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
