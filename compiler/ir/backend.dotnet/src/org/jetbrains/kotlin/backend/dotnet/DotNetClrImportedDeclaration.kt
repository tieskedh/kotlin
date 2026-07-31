/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.load.dotnet.DotNetClrClasspathAssembly
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationCarrierVersion
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedMethodSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedPropertySource
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature
import java.util.IdentityHashMap

/**
 * Backend view of exact foreign declaration carriers.
 *
 * The closed provider admits only complete non-empty interfaces. Therefore any imported class
 * has at least one declared function whose carrier identifies its physical TypeDef. This helper
 * validates that every retained carrier agrees and caches by IR declaration identity; it never
 * resolves a ClassId, namespace, method name, or signature against the classpath.
 */
internal class DotNetClrImportedDeclarations(
    private val assemblyReferenceSink: (DotNetClrClasspathAssembly.WithoutCarrier) -> Unit,
) {
    private val classInfos = IdentityHashMap<IrClass, DotNetIlClassInfo>()

    fun classInfoOrNull(irClass: IrClass): DotNetIlClassInfo? {
        classInfos[irClass]?.let {
            assemblyReferenceSink(irClass.importedClrSourceOrNull()!!.assembly)
            return it
        }
        val source = irClass.importedClrSourceOrNull() ?: return null
        source.requireSupportedCarrierVersion()
        validateAssemblyIdentity(source.assembly)
        val owner = source.declaringType
        val className =
            if (owner.namespaceName.isEmpty()) owner.metadataName
            else "${owner.namespaceName}.${owner.metadataName}"
        return DotNetIlClassInfo(
            ilClassName = className,
            assemblyName = source.assembly.metadata.identity.name,
        ).also { classInfo ->
            classInfos[irClass] = classInfo
            assemblyReferenceSink(source.assembly)
        }
    }

    fun functionInfoOrNull(function: IrSimpleFunction): DotNetIlFunctionInfo? {
        val source =
            function.containerSource as? DotNetClrImportedDeclarationSource ?: return null
        source.requireSupportedCarrierVersion()
        val method = when (source) {
            is DotNetClrImportedMethodSource -> source.method
            is DotNetClrImportedPropertySource -> {
                val property = function.correspondingPropertySymbol?.owner
                    ?: dotNetUnsupported(
                        "foreign CLR property accessor '${function.name.asString()}' lost its IR property"
                    )
                when (function) {
                    property.getter -> source.getter
                    property.setter -> source.setter
                        ?: dotNetUnsupported(
                            "foreign CLR property '${source.property.name}' has no retained setter"
                        )
                    else -> dotNetUnsupported(
                        "foreign CLR property '${source.property.name}' has an unknown accessor declaration"
                    )
                }
            }
        }
        val ownerClass = function.parent as? IrClass
            ?: dotNetUnsupported(
                "foreign CLR MethodDef '${method.name}' lost its imported interface owner"
            )
        val ownerInfo = classInfoOrNull(ownerClass)
            ?: dotNetUnsupported(
                "foreign CLR MethodDef '${method.name}' lost its exact TypeDef linkage"
            )
        val signature = method.signature
        val returnType = when (val physicalReturn = signature.returnType) {
            DotNetClrTypeSignature.Void -> DotNetIlReturnType.Void
            else -> DotNetIlReturnType.Value(
                physicalReturn.toSupportedImportedIlTypeOrNull()
                    ?: dotNetUnsupported(
                        "foreign CLR MethodDef '${method.name}' has a physical return type " +
                                "outside the current .NET backend value grammar"
                    )
            )
        }
        val parameterTypes = buildList {
            add(DotNetIlValueType.UserClass(ownerInfo))
            signature.parameterTypes.mapTo(this) { physicalParameter ->
                physicalParameter.toSupportedImportedIlTypeOrNull()
                    ?: dotNetUnsupported(
                        "foreign CLR MethodDef '${method.name}' has a physical parameter type " +
                                "outside the current .NET backend value grammar"
                    )
            }
        }
        assemblyReferenceSink(source.assembly)
        return DotNetIlFunctionInfo(
            owner = ownerInfo,
            signature = DotNetIlMethodSignature(
                returnType = returnType,
                parameterTypes = parameterTypes,
                hasThis = true,
            ),
            physicalMethodName = method.name,
        )
    }

    private fun IrClass.importedClrSourceOrNull(): DotNetClrImportedDeclarationSource? {
        val sources = declarations.asSequence()
            .mapNotNull { declaration ->
                when (declaration) {
                    is IrSimpleFunction ->
                        declaration.containerSource as? DotNetClrImportedDeclarationSource
                    is IrProperty ->
                        declaration.containerSource as? DotNetClrImportedDeclarationSource
                    else -> null
                }
            }
            .toList()
        if (sources.isEmpty()) return null
        val first = sources.first()
        if (sources.any { source ->
                source.assembly !== first.assembly ||
                        source.declaringType.handle != first.declaringType.handle
            }
        ) {
            dotNetUnsupported(
                "foreign CLR interface '${name.asString()}' has inconsistent physical declaration carriers"
            )
        }
        return first
    }

    private fun validateAssemblyIdentity(assembly: DotNetClrClasspathAssembly.WithoutCarrier) {
        val identity = assembly.metadata.identity
        if (!identity.culture.equals("neutral", ignoreCase = true)) {
            dotNetUnsupported(
                "foreign CLR assembly '${identity.name}' uses culture '${identity.culture}'; " +
                        "non-neutral assembly references are outside the first importer call slice"
            )
        }
        if (identity.hasPublicKey && identity.publicKeyToken.size != 8) {
            dotNetUnsupported(
                "foreign CLR assembly '${identity.name}' has no exact eight-byte public-key token"
            )
        }
    }

    private fun DotNetClrImportedDeclarationSource.requireSupportedCarrierVersion() {
        when (carrierVersion) {
            DotNetClrImportedDeclarationCarrierVersion.V1 -> Unit
        }
    }
}

private fun DotNetClrTypeSignature.toSupportedImportedIlTypeOrNull(): DotNetIlValueType? =
    when (this) {
        is DotNetClrTypeSignature.Primitive -> when (type) {
            DotNetClrPrimitiveType.BOOLEAN -> DotNetIlValueType.Boolean
            DotNetClrPrimitiveType.CHAR -> DotNetIlValueType.Char
            DotNetClrPrimitiveType.INT8 -> DotNetIlValueType.Int8
            DotNetClrPrimitiveType.INT16 -> DotNetIlValueType.Int16
            DotNetClrPrimitiveType.INT32 -> DotNetIlValueType.Int32
            DotNetClrPrimitiveType.INT64 -> DotNetIlValueType.Int64
            DotNetClrPrimitiveType.FLOAT32 -> DotNetIlValueType.Float32
            DotNetClrPrimitiveType.FLOAT64 -> DotNetIlValueType.Float64
            DotNetClrPrimitiveType.STRING -> DotNetIlValueType.String
            DotNetClrPrimitiveType.OBJECT -> DotNetIlValueType.Object
            DotNetClrPrimitiveType.UINT8,
            DotNetClrPrimitiveType.UINT16,
            DotNetClrPrimitiveType.UINT32,
            DotNetClrPrimitiveType.UINT64,
            DotNetClrPrimitiveType.NATIVE_INT,
            DotNetClrPrimitiveType.NATIVE_UINT,
                -> null
        }
        is DotNetClrTypeSignature.SzArray -> {
            val elementType = elementType as? DotNetClrTypeSignature.Primitive
                ?: return null
            when (elementType.type) {
                DotNetClrPrimitiveType.STRING -> DotNetIlValueType.GenericArray(DotNetIlValueType.String)
                DotNetClrPrimitiveType.OBJECT -> DotNetIlValueType.GenericArray(DotNetIlValueType.Object)
                else -> null
            }
        }
        else -> null
    }
