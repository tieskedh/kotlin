/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.name.FqName

/**
 * The single compiler/runtime registry for Kotlin specialized primitive-array wrappers.
 *
 * A wrapper is the canonical Kotlin value. Its CLR vector is storage only and may appear on the
 * evaluation stack while implementing an intrinsic, but never substitutes for the wrapper in a
 * Kotlin declaration signature. Conversely, `Array<Int>` is a [DotNetIlValueType.GenericArray]
 * and naturally becomes `int32[]`; the two source types therefore remain physically distinct.
 * Explicit CLR projections preserve reference identity through a runtime-owned weak association;
 * ordinary Kotlin construction and array operations do not consult that interop table.
 */
internal object DotNetPrimitiveArrays {
    data class Entry(
        val kotlinFqName: FqName,
        val wrapperSimpleName: String,
        val elementType: DotNetIlValueType,
    ) {
        val wrapperTypeRef: String
            get() = "[${DotNetRuntimeLibrary.ASSEMBLY_NAME}]${"Kotlin.$wrapperSimpleName".toIlIdentifier()}"

        val storageType: DotNetIlValueType.GenericArray
            get() = DotNetIlValueType.GenericArray(elementType)

        val newStorageInstruction: String
            get() = storageType.newArrayInstruction

        val wrapStorageInstruction: String
            get() = "newobj instance void $wrapperTypeRef::.ctor(${storageType.nameInSignature})"

        val sizeCallInstruction: String
            get() = "call instance int32 $wrapperTypeRef::'GetSize'()"

        val getCallInstruction: String
            get() = "call instance ${elementType.nameInSignature} $wrapperTypeRef::'Get'(int32)"

        val setCallInstruction: String
            get() = "call instance void $wrapperTypeRef::'Set'(int32, ${elementType.nameInSignature})"

        val getStorageCallInstruction: String
            get() = "call instance ${storageType.nameInSignature} $wrapperTypeRef::'GetStorage'()"

        val wrapStorageOrNullCallInstruction: String
            get() = "call class $wrapperTypeRef $wrapperTypeRef::'WrapStorageOrNull'(${storageType.nameInSignature})"

        val projectStorageOrNullCallInstruction: String
            get() = "call ${storageType.nameInSignature} $wrapperTypeRef::'GetStorageOrNull'(class $wrapperTypeRef)"
    }

    val entries: List<Entry> = listOf(
        Entry(FqName("kotlin.IntArray"), "IntArray", DotNetIlValueType.Int32),
        Entry(FqName("kotlin.LongArray"), "LongArray", DotNetIlValueType.Int64),
        Entry(FqName("kotlin.DoubleArray"), "DoubleArray", DotNetIlValueType.Float64),
        Entry(FqName("kotlin.BooleanArray"), "BooleanArray", DotNetIlValueType.Boolean),
        Entry(FqName("kotlin.CharArray"), "CharArray", DotNetIlValueType.Char),
    )

    private val entriesByFqName = entries.associateBy(Entry::kotlinFqName)
    private val entriesByElementType = entries.associateBy(Entry::elementType)

    fun entry(kotlinFqName: FqName?): Entry? = kotlinFqName?.let(entriesByFqName::get)

    fun entry(elementType: DotNetIlValueType): Entry =
        entriesByElementType[elementType]
            ?: error("Internal .NET backend error: no primitive-array wrapper for $elementType")

    /** Runtime definitions for the canonical wrappers and their cross-assembly compiler ABI. */
    fun runtimeTypesIl(coreLibraryReference: String, editorBrowsableReference: String): String {
        val compilerAbiAttributes = listOf(
            DotNetCompilerAbi.markerAttributeIl(runtimeAssemblyReference = ""),
            DotNetCompilerAbi.editorBrowsableNeverAttributeIl(editorBrowsableReference),
        ).joinToString("\n              ")
        return entries.joinToString("\n\n") { entry ->
            val className = entry.wrapperSimpleName.toIlIdentifier()
            val wrapperName = "Kotlin.${entry.wrapperSimpleName}".toIlIdentifier()
            val elementName = entry.elementType.nameInSignature
            val storageName = entry.storageType.nameInSignature
            val internTableName =
                "class ${coreLibraryReference}System.Runtime.CompilerServices.ConditionalWeakTable`2" +
                        "<$storageName, class $wrapperName>"
            """
          .class public auto ansi sealed beforefieldinit $className
                 extends ${coreLibraryReference}System.Object
          {
            .field private initonly $storageName '_storage'
            .field private static initonly $internTableName '_internedByStorage'

            .method private hidebysig specialname rtspecialname static void .cctor() cil managed
            {
              .maxstack 1
              newobj instance void $internTableName::.ctor()
              stsfld $internTableName $wrapperName::'_internedByStorage'
              ret
            }

            .method public hidebysig specialname rtspecialname instance void .ctor($storageName 'storage') cil managed
            {
              $compilerAbiAttributes
              .maxstack 2
              ldarg.1
              brtrue.s IL_storageNotNull
              ldstr "storage"
              newobj instance void ${coreLibraryReference}System.ArgumentNullException::.ctor(string)
              throw
        IL_storageNotNull:
              ldarg.0
              call instance void ${coreLibraryReference}System.Object::.ctor()
              ldarg.0
              ldarg.1
              stfld $storageName $wrapperName::'_storage'
              ret
            }

            .method public hidebysig instance int32 'GetSize'() cil managed
            {
              $compilerAbiAttributes
              .maxstack 1
              ldarg.0
              ldfld $storageName $wrapperName::'_storage'
              ldlen
              conv.i4
              ret
            }

            .method public hidebysig instance $elementName 'Get'(int32 'index') cil managed
            {
              $compilerAbiAttributes
              .maxstack 2
              ldarg.0
              ldfld $storageName $wrapperName::'_storage'
              ldarg.1
              ${entry.storageType.loadElementInstruction}
              ret
            }

            .method public hidebysig instance void 'Set'(int32 'index', $elementName 'value') cil managed
            {
              $compilerAbiAttributes
              .maxstack 3
              ldarg.0
              ldfld $storageName $wrapperName::'_storage'
              ldarg.1
              ldarg.2
              ${entry.storageType.storeElementInstruction}
              ret
            }

            .method public hidebysig instance $storageName 'GetStorage'() cil managed
            {
              $compilerAbiAttributes
              .maxstack 1
              ldarg.0
              ldfld $storageName $wrapperName::'_storage'
              ret
            }

            .method public hidebysig static class $wrapperName 'WrapStorageOrNull'($storageName 'storage') cil managed
            {
              $compilerAbiAttributes
              .maxstack 3
              .locals init ([0] class $wrapperName 'result')
              ldarg.0
              brtrue.s IL_wrapStorage
              ldnull
              ret
        IL_wrapStorage:
              ldsfld $internTableName $wrapperName::'_internedByStorage'
              call void ${coreLibraryReference}System.Threading.Monitor::Enter(object)
              .try
              {
                ldsfld $internTableName $wrapperName::'_internedByStorage'
                ldarg.0
                ldloca.s 'result'
                callvirt instance bool $internTableName::TryGetValue(!0, !1&)
                brtrue.s IL_wrapperReady
                ldarg.0
                newobj instance void $wrapperName::.ctor($storageName)
                stloc.0
                ldsfld $internTableName $wrapperName::'_internedByStorage'
                ldarg.0
                ldloc.0
                callvirt instance void $internTableName::Add(!0, !1)
        IL_wrapperReady:
                leave.s IL_returnWrapper
              }
              finally
              {
                ldsfld $internTableName $wrapperName::'_internedByStorage'
                call void ${coreLibraryReference}System.Threading.Monitor::Exit(object)
                endfinally
              }
        IL_returnWrapper:
              ldloc.0
              ret
            }

            .method public hidebysig static $storageName 'GetStorageOrNull'(class $wrapperName 'value') cil managed
            {
              $compilerAbiAttributes
              .maxstack 3
              .locals init ([0] $storageName 'storage',
                            [1] class $wrapperName 'existing')
              ldarg.0
              brtrue.s IL_getStorage
              ldnull
              ret
        IL_getStorage:
              ldarg.0
              call instance $storageName $wrapperName::'GetStorage'()
              stloc.0
              ldsfld $internTableName $wrapperName::'_internedByStorage'
              call void ${coreLibraryReference}System.Threading.Monitor::Enter(object)
              .try
              {
                ldsfld $internTableName $wrapperName::'_internedByStorage'
                ldloc.0
                ldloca.s 'existing'
                callvirt instance bool $internTableName::TryGetValue(!0, !1&)
                brfalse.s IL_registerWrapper
                ldloc.1
                ldarg.0
                ceq
                brtrue.s IL_wrapperRegistered
                ldstr "Multiple Kotlin primitive-array wrappers share one CLR storage vector."
                newobj instance void ${coreLibraryReference}System.InvalidOperationException::.ctor(string)
                throw
        IL_registerWrapper:
                ldsfld $internTableName $wrapperName::'_internedByStorage'
                ldloc.0
                ldarg.0
                callvirt instance void $internTableName::Add(!0, !1)
        IL_wrapperRegistered:
                leave.s IL_returnStorage
              }
              finally
              {
                ldsfld $internTableName $wrapperName::'_internedByStorage'
                call void ${coreLibraryReference}System.Threading.Monitor::Exit(object)
                endfinally
              }
        IL_returnStorage:
              ldloc.0
              ret
            }
          }
            """.trimIndent()
        }
    }

    /**
     * Compiler-facing normalization used by nullable/content operations whose established
     * runtime implementation consumes `System.Array`. Null and non-array scalars return null; a
     * wrapper yields its live storage vector, and a natural generic-array vector returns itself.
     * This makes the helper useful at erased recursive array boundaries without conflating source
     * identities.
     */
    fun runtimeHelperTypeIl(coreLibraryReference: String, compilerAbiTypeAttributesIl: String): String {
        val probes = entries.mapIndexed { index, entry ->
            val nextLabel = "IL_nextPrimitiveArray_$index"
            """
              ldarg.0
              isinst ${entry.wrapperTypeRef.removePrefix("[${DotNetRuntimeLibrary.ASSEMBLY_NAME}]")}
              dup
              brfalse.s $nextLabel
              call instance ${entry.storageType.nameInSignature} ${entry.wrapperTypeRef.removePrefix("[${DotNetRuntimeLibrary.ASSEMBLY_NAME}]")}::'GetStorage'()
              ret
        $nextLabel:
              pop
            """.trimIndent()
        }.joinToString("\n")
        return """
  .class public abstract sealed auto ansi beforefieldinit PrimitiveArrays
         extends ${coreLibraryReference}System.Object
  {
    $compilerAbiTypeAttributesIl
    .method public hidebysig static class ${coreLibraryReference}System.Array 'GetStorageOrNull'(object 'value') cil managed
    {
      .maxstack 2
${probes.prependIndent("      ")}
      ldarg.0
      isinst ${coreLibraryReference}System.Array
      ret
    }
  }
        """.trimIndent()
    }

    fun getStorageFromObjectCallInstruction(coreLibraryReference: String): String =
        "call class ${coreLibraryReference}System.Array " +
                "[${DotNetRuntimeLibrary.ASSEMBLY_NAME}]" +
        "${"Kotlin.Runtime.Internal.PrimitiveArrays".toIlIdentifier()}::'GetStorageOrNull'(object)"
}
