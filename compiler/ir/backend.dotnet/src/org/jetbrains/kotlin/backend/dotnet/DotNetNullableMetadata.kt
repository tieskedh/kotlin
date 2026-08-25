/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.util.render

/** One Roslyn nullable-reference transform in CLR metadata preorder. */
enum class DotNetNullableReferenceFlag(val metadataValue: Int) {
    OBLIVIOUS(0),
    NON_NULL(1),
    NULLABLE(2),
}

/**
 * CLR nullable-reference metadata for the explicit export surface.
 *
 * This follows Roslyn's documented preorder encoding without its optional NullableContext
 * compression: 0 is oblivious, 1 is non-null, and 2 is nullable. Emitting an explicit attribute
 * for every non-empty exported type shape makes the boundary deterministic and independent of
 * surrounding Kotlin declarations, which remain outside the CLR export ABI.
 */
internal object DotNetNullableMetadata {
    const val ATTRIBUTE_FQ_NAME: String = "System.Runtime.CompilerServices.NullableAttribute"

    /** The compiler-reserved attribute shape synthesized into a module that contains exports. */
    fun attributeClassIl(coreLibraryReference: String): String = """
        |.namespace System.Runtime.CompilerServices
        |{
        |  .class private auto ansi sealed beforefieldinit NullableAttribute
        |         extends ${coreLibraryReference}System.Attribute
        |  {
        |    .field public initonly uint8[] NullableFlags
        |
        |    .method public hidebysig specialname rtspecialname instance void .ctor(uint8) cil managed
        |    {
        |      .maxstack 4
        |      ldarg.0
        |      call instance void ${coreLibraryReference}System.Attribute::.ctor()
        |      ldarg.0
        |      ldc.i4.1
        |      newarr ${coreLibraryReference}System.Byte
        |      dup
        |      ldc.i4.0
        |      ldarg.1
        |      stelem.i1
        |      stfld uint8[] System.Runtime.CompilerServices.NullableAttribute::NullableFlags
        |      ret
        |    }
        |
        |    .method public hidebysig specialname rtspecialname instance void .ctor(uint8[]) cil managed
        |    {
        |      .maxstack 2
        |      ldarg.0
        |      call instance void ${coreLibraryReference}System.Attribute::.ctor()
        |      ldarg.0
        |      ldarg.1
        |      stfld uint8[] System.Runtime.CompilerServices.NullableAttribute::NullableFlags
        |      ret
        |    }
        |  }
        |}
        |
    """.trimMargin()

    /** Nullable flags for one source type and its already-selected physical CLR representation. */
    fun flags(type: IrType, physicalType: DotNetIlValueType): List<DotNetNullableReferenceFlag> = when (physicalType) {
        DotNetIlValueType.Boolean,
        DotNetIlValueType.Int8,
        DotNetIlValueType.Int16,
        DotNetIlValueType.Int32,
        DotNetIlValueType.Int64,
        DotNetIlValueType.Float32,
        DotNetIlValueType.Float64,
        DotNetIlValueType.Char,
        is DotNetIlValueType.NullableValue,
            -> emptyList()

        DotNetIlValueType.String,
        DotNetIlValueType.Object,
        is DotNetIlValueType.UserClass,
        is DotNetIlValueType.MappedClass,
            -> listOf(type.referenceFlag())

        is DotNetIlValueType.PrimitiveArray -> listOf(type.referenceFlag())
        is DotNetIlValueType.ErasedGenericArray -> listOf(type.referenceFlag())
        is DotNetIlValueType.GenericArray -> {
            val elementType = type.singleTypeArgument("array")
            listOf(type.referenceFlag()) + flags(elementType, physicalType.elementType)
        }
        is DotNetIlValueType.GenericInstance -> {
            val typeArguments = type.typeArguments("generic type")
            if (typeArguments.size != physicalType.arguments.size) {
                dotNetUnsupported(
                    "CLR nullable metadata for '${type.render()}' has ${typeArguments.size} source " +
                            "arguments but ${physicalType.arguments.size} physical arguments"
                )
            }
            listOf(type.referenceFlag()) + typeArguments.zip(physicalType.arguments).flatMap { [argument, mapped] ->
                flags(argument, mapped)
            }
        }
        is DotNetIlValueType.TypeParameter -> listOf(
            if (type.isMarkedNullable()) DotNetNullableReferenceFlag.NULLABLE
            else DotNetNullableReferenceFlag.NON_NULL
        )
        is DotNetIlValueType.ByReference -> flags(type, physicalType.elementType)
    }

    /**
     * Nullable transforms captured before a generic-owner prototype is detached from live IR.
     *
     * A non-null-looking unconstrained Kotlin type parameter may still have a nullable
     * substitution. Like Roslyn, its exact CLR `!n`/`!!n` value position is annotated non-null;
     * nullable substitutions remain expressible on the constructed generic type argument and no
     * CLR constraint is introduced. When such a position is deliberately projected to the shared
     * semantic `object` carrier, that fixed carrier must admit nullable substitutions and is
     * explicitly nullable instead of falsely non-null.
     */
    fun flags(
        type: IrType,
        physicalType: DotNetGenericOwnerPrototypeTypeSnapshot,
    ): List<DotNetNullableReferenceFlag> = when (physicalType.kind) {
        DotNetGenericOwnerPrototypeTypeKind.VOID,
        DotNetGenericOwnerPrototypeTypeKind.BOOLEAN,
        DotNetGenericOwnerPrototypeTypeKind.INT32,
            -> emptyList()

        DotNetGenericOwnerPrototypeTypeKind.STRING,
        DotNetGenericOwnerPrototypeTypeKind.SYSTEM_ARRAY,
            -> listOf(type.referenceFlag())

        DotNetGenericOwnerPrototypeTypeKind.OBJECT -> listOf(
            if ((type as? IrSimpleType)?.classifier is IrTypeParameterSymbol) {
                DotNetNullableReferenceFlag.NULLABLE
            } else {
                type.referenceFlag()
            }
        )

        DotNetGenericOwnerPrototypeTypeKind.OWNER_TYPE_PARAMETER,
        DotNetGenericOwnerPrototypeTypeKind.METHOD_TYPE_PARAMETER,
            -> listOf(
                if (type.isMarkedNullable()) DotNetNullableReferenceFlag.NULLABLE
                else DotNetNullableReferenceFlag.NON_NULL
            )

        DotNetGenericOwnerPrototypeTypeKind.LOGICAL_GENERIC_CLASSIFIER -> {
            val typeArguments = type.typeArguments("generic-owner classifier")
            if (typeArguments.size != physicalType.arguments.size) {
                dotNetUnsupported(
                    "CLR nullable metadata for '${type.render()}' has ${typeArguments.size} source " +
                            "arguments but ${physicalType.arguments.size} prototype arguments"
                )
            }
            listOf(type.referenceFlag()) + typeArguments.zip(physicalType.arguments).flatMap { [argument, mapped] ->
                flags(argument, mapped)
            }
        }

        DotNetGenericOwnerPrototypeTypeKind.SZ_ARRAY -> {
            val elementType = type.singleTypeArgument("generic-owner array")
            listOf(type.referenceFlag()) + flags(elementType, physicalType.arguments.single())
        }
    }

    /** Nullable flags for a Func/Action that represents one source callable type. */
    fun delegateFlags(
        callableType: IrSimpleType,
        logicalTypes: List<IrType>,
        physicalLogicalTypes: List<DotNetIlValueType>,
    ): List<DotNetNullableReferenceFlag> {
        if (logicalTypes.size != physicalLogicalTypes.size) {
            error("Internal .NET backend error: mismatched callable nullable-metadata shapes")
        }
        return listOf(callableType.referenceFlag()) +
                logicalTypes.zip(physicalLogicalTypes).flatMap { [logical, physical] -> flags(logical, physical) }
    }

    /** Complete IL custom-attribute line, including the ECMA-335 serialized value blob. */
    fun renderAttribute(flags: List<DotNetNullableReferenceFlag>): String {
        require(flags.isNotEmpty()) { "nullable metadata attribute requires at least one flag" }
        val usesScalarConstructor = flags.all { it == flags.first() }
        val constructorParameter = if (usesScalarConstructor) "uint8" else "uint8[]"
        val valueBytes = if (usesScalarConstructor) {
            listOf(flags.first().metadataValue)
        } else {
            listOf(
                flags.size and 0xff,
                (flags.size ushr 8) and 0xff,
                (flags.size ushr 16) and 0xff,
                (flags.size ushr 24) and 0xff,
            ) + flags.map { flag -> flag.metadataValue }
        }
        val blob = (listOf(0x01, 0x00) + valueBytes + listOf(0x00, 0x00))
            .joinToString(" ") { byte -> byte.toString(16).padStart(2, '0') }
        return ".custom instance void $ATTRIBUTE_FQ_NAME::.ctor($constructorParameter) = ($blob)"
    }

    private fun IrType.referenceFlag(): DotNetNullableReferenceFlag =
        if (isMarkedNullable()) DotNetNullableReferenceFlag.NULLABLE else DotNetNullableReferenceFlag.NON_NULL

    private fun IrType.singleTypeArgument(description: String): IrType =
        typeArguments(description).singleOrNull()
            ?: dotNetUnsupported(
                "CLR nullable metadata for $description '${render()}' needs one invariant type argument"
            )

    private fun IrType.typeArguments(description: String): List<IrType> {
        val simpleType = this as? IrSimpleType
            ?: dotNetUnsupported("CLR nullable metadata for $description '${render()}' needs a simple source type")
        return simpleType.arguments.map { argument ->
            (argument as? IrTypeProjection)?.type
                ?: dotNetUnsupported(
                    "CLR nullable metadata for $description '${render()}' cannot encode a star projection"
                )
        }
    }

}
