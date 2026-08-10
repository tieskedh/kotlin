/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.load.dotnet.DotNetClrClasspathAssembly
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterVariance
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationCarrierVersion
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedMethodSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedPropertySource
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedMethodSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeView
import org.jetbrains.kotlin.types.Variance
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
    private val coreLibraryReference: String,
) {
    private val classInfos = IdentityHashMap<IrClass, DotNetIlClassInfo>()
    private val resolvedClassInfos = hashMapOf<DotNetClrResolvedTypeDefinition, DotNetIlClassInfo>()
    private val initializedHierarchies = hashSetOf<DotNetClrResolvedTypeDefinition>()
    private val hierarchiesInProgress = hashSetOf<DotNetClrResolvedTypeDefinition>()

    fun classInfoOrNull(irClass: IrClass): DotNetIlClassInfo? {
        classInfos[irClass]?.let {
            assemblyReferenceSink(irClass.importedClrSourceOrNull()!!.assembly)
            return it
        }
        val source = irClass.importedClrSourceOrNull() ?: return null
        source.requireSupportedCarrierVersion()
        validateAssemblyIdentity(source.assembly)
        val owner = source.declaringHierarchy.type.type
        val ownerParameters = owner.assembly.genericParameterDefinitions
            .filter { parameter -> parameter.owner == owner.definition.handle }
            .sortedBy { parameter -> parameter.number }
        if (
            ownerParameters.map { parameter -> parameter.number } != ownerParameters.indices.toList() ||
            ownerParameters.size != irClass.typeParameters.size
        ) {
            dotNetUnsupported(
                "foreign CLR TypeDef '${owner.definition.metadataName}' has a generic arity that disagrees with FIR/IR"
            )
        }
        return classInfo(owner, source).also { classInfo ->
            classInfos[irClass] = classInfo
            initializeHierarchy(owner, source)
        }
    }

    fun functionInfoOrNull(function: IrSimpleFunction): DotNetIlFunctionInfo? {
        val source =
            function.containerSource as? DotNetClrImportedDeclarationSource ?: return null
        source.requireSupportedCarrierVersion()
        val methodAndSignature: Pair<DotNetClrMethodDefinition, DotNetClrResolvedMethodSignature> = when (source) {
            is DotNetClrImportedMethodSource -> source.method to source.resolvedSignature
            is DotNetClrImportedPropertySource -> {
                val property = function.correspondingPropertySymbol?.owner
                    ?: dotNetUnsupported(
                        "foreign CLR property accessor '${function.name.asString()}' lost its IR property"
                    )
                when (function) {
                    property.getter -> source.getter to source.getterSignature
                    property.setter -> source.setter?.let { setter ->
                        setter to checkNotNull(source.setterSignature)
                    }
                        ?: dotNetUnsupported(
                            "foreign CLR property '${source.property.name}' has no retained setter"
                        )
                    else -> dotNetUnsupported(
                        "foreign CLR property '${source.property.name}' has an unknown accessor declaration"
                    )
                }
            }
        }
        val method = methodAndSignature.first
        val resolvedSignature = methodAndSignature.second
        val ownerClass = function.parent as? IrClass
            ?: dotNetUnsupported(
                "foreign CLR MethodDef '${method.name}' lost its imported interface owner"
            )
        val ownerInfo = classInfoOrNull(ownerClass)
            ?: dotNetUnsupported(
                "foreign CLR MethodDef '${method.name}' lost its exact TypeDef linkage"
            )
        val signature = resolvedSignature
        val ownerGenericParameterCount = ownerInfo.typeParameterCount
        if (signature.genericParameterCount != function.typeParameters.size) {
            dotNetUnsupported(
                "foreign CLR MethodDef '${method.name}' retained ${signature.genericParameterCount} " +
                        "method parameters but FIR/IR exposes ${function.typeParameters.size}"
            )
        }
        val returnType = when (val physicalReturn = signature.returnType) {
            DotNetClrResolvedTypeSignature.Void -> DotNetIlReturnType.Void
            else -> DotNetIlReturnType.Value(
                physicalReturn.toSupportedImportedIlTypeOrNull(
                    source,
                    ownerGenericParameterCount,
                    signature.genericParameterCount,
                )
                    ?: dotNetUnsupported(
                        "foreign CLR MethodDef '${method.name}' has a physical return type " +
                                "outside the current .NET backend value grammar"
                    )
            )
        }
        val parameterTypes = buildList {
            add(
                if (ownerGenericParameterCount == 0) {
                    DotNetIlValueType.UserClass(ownerInfo)
                } else {
                    DotNetIlValueType.GenericInstance(
                        ownerInfo,
                        List(ownerGenericParameterCount) { index ->
                            DotNetIlValueType.TypeParameter(index, isMethodParameter = false)
                        },
                    )
                }
            )
            signature.parameterTypes.mapTo(this) { physicalParameter ->
                physicalParameter.toSupportedImportedIlTypeOrNull(
                    source,
                    ownerGenericParameterCount,
                    signature.genericParameterCount,
                )
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

    private fun IrClass.importedClrSourceOrNull(): DotNetClrImportedDeclarationSource? =
        dotNetImportedClrSourceOrNull()

    private fun classInfo(
        type: DotNetClrResolvedTypeDefinition,
        source: DotNetClrImportedDeclarationSource,
    ): DotNetIlClassInfo = resolvedClassInfos.getOrPut(type) {
        val selectedAssembly = source.linkedAssembly(type)
        validateAssemblyIdentity(selectedAssembly)
        val owner = type.definition
        val parameters = type.assembly.genericParameterDefinitions
            .filter { parameter -> parameter.owner == owner.handle }
            .sortedBy { parameter -> parameter.number }
        if (parameters.map { parameter -> parameter.number } != parameters.indices.toList()) {
            dotNetUnsupported(
                "foreign CLR TypeDef '${owner.metadataName}' has invalid generic parameter numbering"
            )
        }
        val className =
            if (owner.namespaceName.isEmpty()) owner.metadataName
            else "${owner.namespaceName}.${owner.metadataName}"
        DotNetIlClassInfo(
            ilClassName = className,
            typeParameterVariances = parameters.map { parameter ->
                when (parameter.variance) {
                    DotNetClrGenericParameterVariance.INVARIANT -> Variance.INVARIANT
                    DotNetClrGenericParameterVariance.COVARIANT -> Variance.OUT_VARIANCE
                    DotNetClrGenericParameterVariance.CONTRAVARIANT -> Variance.IN_VARIANCE
                }
            },
            assemblyName = type.assembly.identity.name,
        ).also {
            assemblyReferenceSink(selectedAssembly)
        }
    }

    private fun initializeHierarchy(
        type: DotNetClrResolvedTypeDefinition,
        source: DotNetClrImportedDeclarationSource,
    ) {
        if (type in initializedHierarchies || !hierarchiesInProgress.add(type)) return
        try {
            val hierarchy = source.graph.hierarchyOrNull(type) ?: dotNetUnsupported(
                "foreign CLR TypeDef '${type.definition.metadataName}' lost its selected hierarchy"
            )
            val info = classInfo(type, source)
            info.baseType = hierarchy.baseType?.toSupportedImportedIlTypeOrNull(
                source,
                info.typeParameterCount,
            )
            info.interfaces = hierarchy.interfaces.map { implementation ->
                implementation.interfaceType.toSupportedImportedIlTypeOrNull(
                    source,
                    info.typeParameterCount,
                )
                    ?: dotNetUnsupported(
                        "foreign CLR TypeDef '${type.definition.metadataName}' has an inherited interface " +
                                "outside the retained backend grammar"
                    )
            }
            initializedHierarchies += type
            hierarchy.interfaces.forEach { implementation ->
                initializeHierarchy(implementation.interfaceType.type, source)
            }
        } finally {
            hierarchiesInProgress.remove(type)
        }
    }

    private fun DotNetClrImportedDeclarationSource.linkedAssembly(
        type: DotNetClrResolvedTypeDefinition,
    ): DotNetClrClasspathAssembly.WithoutCarrier =
        graph.assemblyOrNull(type.assembly)
            ?: dotNetUnsupported(
                "foreign CLR TypeDef '${type.definition.metadataName}' lost its selected assembly"
            )

    private fun DotNetClrResolvedTypeView.toSupportedImportedIlTypeOrNull(
        source: DotNetClrImportedDeclarationSource,
        ownerGenericParameterCount: Int,
    ): DotNetIlValueType? =
        if (arguments.isEmpty()) {
            DotNetIlValueType.UserClass(classInfo(type, source))
        } else {
            DotNetIlValueType.GenericInstance(
                classInfo(type, source),
                arguments.map { argument ->
                    argument.toSupportedImportedIlTypeOrNull(source, ownerGenericParameterCount, 0)
                        ?: return null
                },
            )
        }

    private fun DotNetClrResolvedTypeSignature.toSupportedImportedIlTypeOrNull(
        source: DotNetClrImportedDeclarationSource,
        ownerGenericParameterCount: Int,
        methodGenericParameterCount: Int,
    ): DotNetIlValueType? =
        when (this) {
            is DotNetClrResolvedTypeSignature.Primitive -> when (type) {
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
            is DotNetClrResolvedTypeSignature.GenericParameter ->
                when (kind) {
                    DotNetClrGenericParameterKind.TYPE ->
                        if (index in 0 until ownerGenericParameterCount) {
                            DotNetIlValueType.TypeParameter(index, isMethodParameter = false)
                        } else null
                    DotNetClrGenericParameterKind.METHOD ->
                        if (index in 0 until methodGenericParameterCount) {
                            DotNetIlValueType.TypeParameter(index, isMethodParameter = true)
                        } else null
                }
            is DotNetClrResolvedTypeSignature.SzArray -> {
                val physicalElement = elementType.toSupportedImportedIlTypeOrNull(
                    source,
                    ownerGenericParameterCount,
                    methodGenericParameterCount,
                ) ?: return null
                DotNetIlValueType.GenericArray(physicalElement)
            }
            is DotNetClrResolvedTypeSignature.Named ->
                DotNetIlValueType.UserClass(classInfo(type, source))
            is DotNetClrResolvedTypeSignature.GenericInstance -> {
                if (
                    source.graph.physicalCoreTypes?.systemNullable?.let(
                        genericType.type::hasSameIdentityAs
                    ) == true
                ) {
                    val physicalElement = arguments.singleOrNull()
                        ?.toSupportedImportedIlTypeOrNull(
                            source,
                            ownerGenericParameterCount,
                            methodGenericParameterCount,
                        )
                        ?: return null
                    if (!genericType.isValueType || !physicalElement.isSupportedImportedNullableElement()) {
                        return null
                    }
                    DotNetIlValueType.NullableValue(physicalElement, coreLibraryReference)
                } else {
                    val owner = classInfo(genericType.type, source)
                    val physicalArguments = arguments.map { argument ->
                        argument.toSupportedImportedIlTypeOrNull(
                            source,
                            ownerGenericParameterCount,
                            methodGenericParameterCount,
                        ) ?: return null
                    }
                    DotNetIlValueType.GenericInstance(owner, physicalArguments)
                }
            }
            else -> null
        }
}

internal fun IrClass.dotNetImportedClrSourceOrNull(): DotNetClrImportedDeclarationSource? {
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
            DotNetClrImportedDeclarationCarrierVersion.V3 -> Unit
        }
}

private fun DotNetIlValueType.isSupportedImportedNullableElement(): Boolean =
    when (this) {
        DotNetIlValueType.Boolean,
        DotNetIlValueType.Char,
        DotNetIlValueType.Int8,
        DotNetIlValueType.Int16,
        DotNetIlValueType.Int32,
        DotNetIlValueType.Int64,
        DotNetIlValueType.Float32,
        DotNetIlValueType.Float64,
        -> true

        else -> false
    }
