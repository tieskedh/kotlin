/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

/**
 * Metadata contract for CLR-public declarations that exist only so separately compiled Kotlin
 * code can link. The marker distinguishes that surface from Kotlin public API and explicit C#
 * exports; [System.ComponentModel.EditorBrowsableAttribute] keeps it out of ordinary completion
 * without pretending that CLR metadata can make a cross-assembly entry point non-public.
 */
internal object DotNetCompilerAbi {
    const val ATTRIBUTE_TYPE_NAME = "Kotlin.Runtime.Internal.KotlinCompilerAbiAttribute"

    fun markerAttributeIl(runtimeAssemblyReference: String = "[${DotNetRuntimeLibrary.ASSEMBLY_NAME}]"): String =
        ".custom instance void $runtimeAssemblyReference${ATTRIBUTE_TYPE_NAME.toIlIdentifier()}::.ctor() = " +
                "(01 00 00 00)"

    fun editorBrowsableNeverAttributeIl(coreLibraryReference: String): String =
        ".custom instance void ${coreLibraryReference}System.ComponentModel.EditorBrowsableAttribute::" +
                ".ctor(valuetype ${coreLibraryReference}System.ComponentModel.EditorBrowsableState) = " +
                "(01 00 01 00 00 00 00 00)"

    fun attributeTypeIl(coreLibraryReference: String, editorBrowsableReference: String): String = """
        .namespace Kotlin.Runtime.Internal
        {
          .class public auto ansi sealed beforefieldinit KotlinCompilerAbiAttribute
                 extends ${coreLibraryReference}System.Attribute
          {
            ${editorBrowsableNeverAttributeIl(editorBrowsableReference)}

            .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
            {
              .maxstack 1
              ldarg.0
              call instance void ${coreLibraryReference}System.Attribute::.ctor()
              ret
            }
          }
        }
    """.trimIndent()
}
