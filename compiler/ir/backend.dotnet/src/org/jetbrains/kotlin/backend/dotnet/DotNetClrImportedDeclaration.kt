/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.descriptors.SourceFile
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.serialization.deserialization.IncompatibleVersionErrorData
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerAbiStability
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource
import org.jetbrains.kotlin.serialization.deserialization.descriptors.PreReleaseInfo
import org.jetbrains.kotlin.load.dotnet.DotNetClrClasspathAssembly
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrPropertyDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature
import java.util.IdentityHashMap

sealed class DotNetClrImportedDeclarationSource(
    val assembly: DotNetClrClasspathAssembly.WithoutCarrier,
    val declaringType: DotNetClrTypeDefinition,
) : DeserializedContainerSource {
    init {
        require(assembly.metadata.typeDefinitions.any { it === declaringType }) {
            "Imported CLR TypeDef ${declaringType.handle} does not belong to '${assembly.assemblyFile}'"
        }
    }

    override val incompatibility: IncompatibleVersionErrorData<*>?
        get() = null
    override val preReleaseInfo: PreReleaseInfo
        get() = PreReleaseInfo.DEFAULT_VISIBLE
    override val abiStability: DeserializedContainerAbiStability
        get() = DeserializedContainerAbiStability.STABLE

    override fun getContainingFile(): SourceFile = SourceFile.NO_SOURCE_FILE
}

/**
 * Exact physical linkage retained on one FIR function imported from a resource-free CLR DLL.
 *
 * FIR2IR preserves [DeserializedContainerSource] on lazy external functions. Keeping the selected
 * assembly, TypeDef, and MethodDef here prevents codegen from performing a second classpath or
 * display-name lookup after Kotlin type enhancement has produced the logical declaration view.
 */
class DotNetClrImportedMethodSource(
    assembly: DotNetClrClasspathAssembly.WithoutCarrier,
    declaringType: DotNetClrTypeDefinition,
    val method: DotNetClrMethodDefinition,
) : DotNetClrImportedDeclarationSource(assembly, declaringType) {
    init {
        require(method.declaringType == declaringType.handle) {
            "Imported CLR MethodDef ${method.handle} does not belong to TypeDef ${declaringType.handle}"
        }
        require(assembly.metadata.methodDefinitions.any { it === method }) {
            "Imported CLR MethodDef ${method.handle} does not belong to '${assembly.assemblyFile}'"
        }
    }

    override val presentableString: String =
        "${assembly.identityDisplayName()} TypeDef 0x${declaringType.handle.token.toUInt().toString(16)} " +
                "MethodDef 0x${method.handle.token.toUInt().toString(16)}"
}

/**
 * One physical Property row and its exact MethodSemantics-selected accessors.
 *
 * The same source is retained on the lazy IR property, getter, and optional setter. Codegen uses
 * accessor declaration identity to select [getter] or [setter]; their names are never inferred
 * from [property].
 */
class DotNetClrImportedPropertySource(
    assembly: DotNetClrClasspathAssembly.WithoutCarrier,
    declaringType: DotNetClrTypeDefinition,
    val property: DotNetClrPropertyDefinition,
    val getter: DotNetClrMethodDefinition,
    val setter: DotNetClrMethodDefinition?,
) : DotNetClrImportedDeclarationSource(assembly, declaringType) {
    init {
        require(property.declaringType == declaringType.handle) {
            "Imported CLR Property ${property.handle} does not belong to TypeDef ${declaringType.handle}"
        }
        require(getter.declaringType == declaringType.handle) {
            "Imported CLR property getter ${getter.handle} does not belong to TypeDef ${declaringType.handle}"
        }
        require(setter == null || setter.declaringType == declaringType.handle) {
            "Imported CLR property setter ${setter?.handle} does not belong to TypeDef ${declaringType.handle}"
        }
        require(assembly.metadata.propertyDefinitions.any { it === property }) {
            "Imported CLR Property ${property.handle} does not belong to '${assembly.assemblyFile}'"
        }
        require(assembly.metadata.methodDefinitions.any { it === getter }) {
            "Imported CLR property getter ${getter.handle} does not belong to '${assembly.assemblyFile}'"
        }
        require(setter == null || assembly.metadata.methodDefinitions.any { it === setter }) {
            "Imported CLR property setter ${setter?.handle} does not belong to '${assembly.assemblyFile}'"
        }
    }

    override val presentableString: String =
        "${assembly.identityDisplayName()} TypeDef 0x${declaringType.handle.token.toUInt().toString(16)} " +
                "Property 0x${property.handle.token.toUInt().toString(16)}"
}

private fun DotNetClrClasspathAssembly.WithoutCarrier.identityDisplayName(): String =
    "${metadata.identity.name}, Version=${metadata.identity.version}, Culture=${metadata.identity.culture}"

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
            DotNetClrPrimitiveType.FLOAT64 -> DotNetIlValueType.Float64
            DotNetClrPrimitiveType.STRING -> DotNetIlValueType.String
            DotNetClrPrimitiveType.OBJECT -> DotNetIlValueType.Object
            DotNetClrPrimitiveType.UINT8,
            DotNetClrPrimitiveType.UINT16,
            DotNetClrPrimitiveType.UINT32,
            DotNetClrPrimitiveType.UINT64,
            DotNetClrPrimitiveType.FLOAT32,
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
