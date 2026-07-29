/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.pipeline.dotnet

import org.jetbrains.kotlin.backend.dotnet.DotNetClrAssemblyMetadata
import org.jetbrains.kotlin.backend.dotnet.DotNetClrAssemblyReference
import org.jetbrains.kotlin.backend.dotnet.DotNetClrAssemblyReferenceBinder
import org.jetbrains.kotlin.backend.dotnet.DotNetClrClasspathAssembly
import org.jetbrains.kotlin.backend.dotnet.DotNetClrCustomAttributeCoreTypes
import org.jetbrains.kotlin.backend.dotnet.DotNetClrCustomAttributeDecoder
import org.jetbrains.kotlin.backend.dotnet.DotNetClrKotlinNullabilityProjection
import org.jetbrains.kotlin.backend.dotnet.DotNetClrKotlinNullabilityProjector
import org.jetbrains.kotlin.backend.dotnet.DotNetClrKotlinNullabilityQualifier
import org.jetbrains.kotlin.backend.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.backend.dotnet.DotNetClrMethodVisibility
import org.jetbrains.kotlin.backend.dotnet.DotNetClrNullableDeclarationResolver
import org.jetbrains.kotlin.backend.dotnet.DotNetClrNullableDeclarationTarget
import org.jetbrains.kotlin.backend.dotnet.DotNetClrNullableEvidenceApplicator
import org.jetbrains.kotlin.backend.dotnet.DotNetClrNullableMetadataDecoder
import org.jetbrains.kotlin.backend.dotnet.DotNetClrNullableTypeTransformApplicator
import org.jetbrains.kotlin.backend.dotnet.DotNetClrNotNullIfNotNullMetadataDecoder
import org.jetbrains.kotlin.backend.dotnet.DotNetClrNotNullIfNotNullMetadataResolution
import org.jetbrains.kotlin.backend.dotnet.DotNetClrNotNullMetadataDecoder
import org.jetbrains.kotlin.backend.dotnet.DotNetClrNotNullMetadataResolution
import org.jetbrains.kotlin.backend.dotnet.DotNetClrNotNullWhenMetadataDecoder
import org.jetbrains.kotlin.backend.dotnet.DotNetClrNotNullWhenMetadataResolution
import org.jetbrains.kotlin.backend.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.backend.dotnet.DotNetClrResolvedMethodSignatureResolution
import org.jetbrains.kotlin.backend.dotnet.DotNetClrResolvedTypeDefinition
import org.jetbrains.kotlin.backend.dotnet.DotNetClrResolvedTypeSignature
import org.jetbrains.kotlin.backend.dotnet.DotNetClrSerializedAssemblyBinder
import org.jetbrains.kotlin.backend.dotnet.DotNetClrSerializedAssemblyName
import org.jetbrains.kotlin.backend.dotnet.DotNetClrSerializedTypeResolver
import org.jetbrains.kotlin.backend.dotnet.DotNetClrSignatureCallingConvention
import org.jetbrains.kotlin.backend.dotnet.DotNetClrSignatureResolver
import org.jetbrains.kotlin.backend.dotnet.DotNetClrTypeDefinition
import org.jetbrains.kotlin.backend.dotnet.DotNetClrTypeResolution
import org.jetbrains.kotlin.backend.dotnet.DotNetClrTypeResolver
import org.jetbrains.kotlin.backend.dotnet.DotNetClrTypeSignature
import org.jetbrains.kotlin.backend.dotnet.DotNetClrTypeVisibility
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.EffectiveVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirModuleData
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.contracts.FirEffectDeclaration
import org.jetbrains.kotlin.fir.contracts.FirResolvedContractDescription
import org.jetbrains.kotlin.fir.contracts.builder.buildResolvedContractDescription
import org.jetbrains.kotlin.fir.contracts.description.ConeConditionalEffectDeclaration
import org.jetbrains.kotlin.fir.contracts.description.ConeConditionalReturnsDeclaration
import org.jetbrains.kotlin.fir.contracts.description.ConeContractConstantValues
import org.jetbrains.kotlin.fir.contracts.description.ConeIsNullPredicate
import org.jetbrains.kotlin.fir.contracts.description.ConeReturnsEffectDeclaration
import org.jetbrains.kotlin.fir.contracts.description.ConeValueParameterReference
import org.jetbrains.kotlin.fir.contracts.toFirElement
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.builder.buildNamedFunction
import org.jetbrains.kotlin.fir.declarations.builder.buildRegularClass
import org.jetbrains.kotlin.fir.declarations.builder.buildValueParameter
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolNamesProvider
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolNamesProviderWithoutCallables
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProvider
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProviderInternals
import org.jetbrains.kotlin.fir.scopes.FirScopeProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeFlexibleType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeRigidType
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.types.withNullability
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import java.util.concurrent.ConcurrentHashMap

/**
 * First closed foreign-CLR FIR slice.
 *
 * Only complete public, top-level, non-generic abstract-interface contracts over the supported
 * primitive/string/object grammar enter [candidates]. Classifier construction stays lazy. See
 * `docs/review/clr-annotation-interoperability.md`.
 */
internal class DotNetClrFirSymbolProvider(
    session: FirSession,
    private val moduleData: FirModuleData,
    private val scopeProvider: FirScopeProvider,
    assemblies: List<DotNetClrClasspathAssembly.Foreign>,
) : FirSymbolProvider(session) {
    private data class Candidate(
        val assembly: DotNetClrAssemblyMetadata,
        val type: DotNetClrTypeDefinition,
        val methods: List<DotNetClrMethodDefinition>,
    )

    private val metadata = assemblies.map(DotNetClrClasspathAssembly.Foreign::metadata)
    private val annotationServices = ForeignAnnotationServices.create(metadata)
    private val candidates: Map<ClassId, Candidate> = buildCandidates()
    private val symbols = ConcurrentHashMap<ClassId, FirRegularClassSymbol>()
    private val classifierNamesByPackage: Map<FqName, Set<Name>> =
        candidates.keys.groupBy { classId -> classId.packageFqName }
            .mapValues { entry ->
                entry.value.mapTo(linkedSetOf()) { classId -> classId.shortClassName }
            }
    private val packages: Set<FqName> = buildSet {
        add(FqName.ROOT)
        for (packageName in classifierNamesByPackage.keys) {
            var current = packageName
            while (!current.isRoot) {
                add(current)
                current = current.parent()
            }
        }
    }

    override fun getClassLikeSymbolByClassId(classId: ClassId): FirClassLikeSymbol<*>? {
        val candidate = candidates[classId] ?: return null
        return symbols.computeIfAbsent(classId) {
            buildClass(classId, candidate)
        }
    }

    @FirSymbolProviderInternals
    override fun getTopLevelCallableSymbolsTo(
        destination: MutableList<FirCallableSymbol<*>>,
        packageFqName: FqName,
        name: Name,
    ) {
    }

    @FirSymbolProviderInternals
    override fun getTopLevelFunctionSymbolsTo(
        destination: MutableList<FirNamedFunctionSymbol>,
        packageFqName: FqName,
        name: Name,
    ) {
    }

    @FirSymbolProviderInternals
    override fun getTopLevelPropertySymbolsTo(
        destination: MutableList<FirPropertySymbol>,
        packageFqName: FqName,
        name: Name,
    ) {
    }

    override fun hasPackage(fqName: FqName): Boolean = fqName in packages

    override val symbolNamesProvider: FirSymbolNamesProvider =
        object : FirSymbolNamesProviderWithoutCallables() {
            override val hasSpecificClassifierPackageNamesComputation: Boolean
                get() = true

            override fun getPackageNamesWithTopLevelClassifiers(): Set<String> =
                classifierNamesByPackage.keys.mapTo(linkedSetOf(), FqName::asString)

            override fun getTopLevelClassifierNamesInPackage(packageFqName: FqName): Set<Name> =
                classifierNamesByPackage[packageFqName].orEmpty()
        }

    private fun buildCandidates(): Map<ClassId, Candidate> {
        val candidatesById = linkedMapOf<ClassId, MutableList<Candidate>>()
        for (assembly in metadata) {
            for (type in assembly.typeDefinitions) {
                val classId = type.classIdOrNull() ?: continue
                val methods = type.completeSupportedContractOrNull(assembly) ?: continue
                candidatesById.getOrPut(classId, ::mutableListOf) +=
                    Candidate(assembly, type, methods)
            }
        }
        return candidatesById.mapNotNull { entry ->
            entry.value.singleOrNull()?.let { candidate -> entry.key to candidate }
        }.toMap(linkedMapOf())
    }

    private fun DotNetClrTypeDefinition.classIdOrNull(): ClassId? {
        if (
            declaringType != null ||
            visibility != DotNetClrTypeVisibility.PUBLIC ||
            !isInterface ||
            !isAbstract ||
            isSealed ||
            baseType != null ||
            metadataName == "<Module>" ||
            !Name.isValidIdentifier(metadataName)
        ) {
            return null
        }
        val namespaceSegments = namespaceName.split('.')
        if (
            namespaceName.isNotEmpty() &&
            namespaceSegments.any { segment -> !Name.isValidIdentifier(segment) }
        ) {
            return null
        }
        return ClassId(FqName(namespaceName), Name.identifier(metadataName))
    }

    private fun DotNetClrTypeDefinition.completeSupportedContractOrNull(
        assembly: DotNetClrAssemblyMetadata,
    ): List<DotNetClrMethodDefinition>? {
        if (
            assembly.genericParameterDefinitions.any { parameter -> parameter.owner == handle } ||
            assembly.interfaceImplementations.any { implementation -> implementation.implementingType == handle } ||
            assembly.typeDefinitions.any { nested -> nested.declaringType == handle } ||
            assembly.fieldDefinitions.any { field -> field.declaringType == handle } ||
            assembly.propertyDefinitions.any { property -> property.declaringType == handle }
        ) {
            return null
        }
        val publicMethods = assembly.methodDefinitions.filter { method ->
            method.declaringType == handle &&
                    method.visibility == DotNetClrMethodVisibility.PUBLIC
        }
        if (publicMethods.isEmpty() || publicMethods.any { method -> !method.isSupportedMethod() }) {
            return null
        }
        return publicMethods
    }

    private fun DotNetClrMethodDefinition.isSupportedMethod(): Boolean =
        !isStatic &&
                isAbstract &&
                isVirtual &&
                !isFinal &&
                relativeVirtualAddress == 0L &&
                implementationAttributes == 0 &&
                !isSpecialName &&
                !isRuntimeSpecialName &&
                Name.isValidIdentifier(name) &&
                signature.callingConvention == DotNetClrSignatureCallingConvention.DEFAULT &&
                signature.hasThis &&
                !signature.hasExplicitThis &&
                signature.genericParameterCount == 0 &&
                signature.varargParameterStart == null &&
                signature.returnType.isSupportedType(allowVoid = true) &&
                signature.parameterTypes.all { type -> type.isSupportedType(allowVoid = false) }

    private fun DotNetClrTypeSignature.isSupportedType(allowVoid: Boolean): Boolean =
        when (this) {
            DotNetClrTypeSignature.Void -> allowVoid
            is DotNetClrTypeSignature.Primitive -> type in SUPPORTED_PRIMITIVES
            else -> false
        }

    private fun buildClass(
        classId: ClassId,
        candidate: Candidate,
    ): FirRegularClassSymbol {
        val classSymbol = FirRegularClassSymbol(classId)
        buildRegularClass {
            resolvePhase = FirResolvePhase.ANALYZED_DEPENDENCIES
            origin = FirDeclarationOrigin.Library
            moduleData = this@DotNetClrFirSymbolProvider.moduleData
            status = FirResolvedDeclarationStatusImpl(
                Visibilities.Public,
                Modality.ABSTRACT,
                EffectiveVisibility.Public,
            )
            classKind = ClassKind.INTERFACE
            symbol = classSymbol
            scopeProvider = this@DotNetClrFirSymbolProvider.scopeProvider
            name = classId.shortClassName
            superTypeRefs += buildResolvedTypeRef {
                coneType = session.builtinTypes.anyType.coneType
            }

            for (method in candidate.methods) {
                declarations += buildMethod(classId, classSymbol, candidate.assembly, method)
            }
        }
        return classSymbol
    }

    private fun buildMethod(
        classId: ClassId,
        classSymbol: FirRegularClassSymbol,
        assembly: DotNetClrAssemblyMetadata,
        method: DotNetClrMethodDefinition,
    ) = buildNamedFunction {
        resolvePhase = FirResolvePhase.ANALYZED_DEPENDENCIES
        origin = FirDeclarationOrigin.Library
        moduleData = this@DotNetClrFirSymbolProvider.moduleData
        status = FirResolvedDeclarationStatusImpl(
            Visibilities.Public,
            Modality.ABSTRACT,
            EffectiveVisibility.Public,
        )
        isLocal = false
        name = Name.identifier(method.name)
        val functionSymbol = FirNamedFunctionSymbol(CallableId(classId, name))
        symbol = functionSymbol
        dispatchReceiverType = classSymbol.constructType()
        returnTypeRef = buildResolvedTypeRef {
            coneType = method.signature.returnType.toKotlinType(
                annotationServices.qualifier(
                    assembly,
                    method,
                    DotNetClrNullableDeclarationTarget.MethodReturn(method),
                )
            )
        }
        method.signature.parameterTypes.forEachIndexed { index, type ->
            valueParameters += buildValueParameter {
                resolvePhase = FirResolvePhase.ANALYZED_DEPENDENCIES
                origin = FirDeclarationOrigin.Library
                moduleData = this@DotNetClrFirSymbolProvider.moduleData
                returnTypeRef = buildResolvedTypeRef {
                    coneType = type.toKotlinType(
                        annotationServices.qualifier(
                            assembly,
                            method,
                            DotNetClrNullableDeclarationTarget.MethodParameter(method, index),
                        )
                    )
                }
                name = parameterName(assembly, method, index)
                symbol = FirValueParameterSymbol()
                containingDeclarationSymbol = functionSymbol
                isCrossinline = false
                isNoinline = false
                isVararg = false
            }
        }
        contractDescription = annotationServices.contractDescription(
            assembly,
            method,
            valueParameters,
        )
    }

    private fun parameterName(
        assembly: DotNetClrAssemblyMetadata,
        method: DotNetClrMethodDefinition,
        index: Int,
    ): Name {
        val rows = assembly.parameterDefinitions.filter { parameter ->
            parameter.declaringMethod == method.handle &&
                    parameter.parameterIndex == index
        }
        val metadataName = rows.singleOrNull()?.name
        return if (metadataName != null && Name.isValidIdentifier(metadataName)) {
            Name.identifier(metadataName)
        } else {
            Name.identifier("p$index")
        }
    }

    private fun DotNetClrTypeSignature.toKotlinType(
        qualifier: DotNetClrKotlinNullabilityQualifier,
    ): ConeKotlinType =
        when (this) {
            DotNetClrTypeSignature.Void -> session.builtinTypes.unitType.coneType
            is DotNetClrTypeSignature.Primitive -> when (type) {
                DotNetClrPrimitiveType.BOOLEAN -> session.builtinTypes.booleanType.coneType
                DotNetClrPrimitiveType.CHAR -> session.builtinTypes.charType.coneType
                DotNetClrPrimitiveType.INT8 -> session.builtinTypes.byteType.coneType
                DotNetClrPrimitiveType.UINT8 -> session.builtinTypes.uByteType.coneType
                DotNetClrPrimitiveType.INT16 -> session.builtinTypes.shortType.coneType
                DotNetClrPrimitiveType.UINT16 -> session.builtinTypes.uShortType.coneType
                DotNetClrPrimitiveType.INT32 -> session.builtinTypes.intType.coneType
                DotNetClrPrimitiveType.UINT32 -> session.builtinTypes.uIntType.coneType
                DotNetClrPrimitiveType.INT64 -> session.builtinTypes.longType.coneType
                DotNetClrPrimitiveType.UINT64 -> session.builtinTypes.uLongType.coneType
                DotNetClrPrimitiveType.FLOAT32 -> session.builtinTypes.floatType.coneType
                DotNetClrPrimitiveType.FLOAT64 -> session.builtinTypes.doubleType.coneType
                DotNetClrPrimitiveType.STRING ->
                    session.builtinTypes.stringType.coneType.withQualifier(qualifier)
                DotNetClrPrimitiveType.OBJECT ->
                    session.builtinTypes.anyType.coneType.withQualifier(qualifier)
                DotNetClrPrimitiveType.NATIVE_INT,
                DotNetClrPrimitiveType.NATIVE_UINT,
                -> error("Native integers are outside the closed foreign CLR FIR slice")
            }
            else -> error("Unsupported type entered the closed foreign CLR FIR slice: $this")
        }

    private fun ConeRigidType.withQualifier(
        qualifier: DotNetClrKotlinNullabilityQualifier,
    ): ConeKotlinType =
        when (qualifier) {
            DotNetClrKotlinNullabilityQualifier.NOT_NULL ->
                withNullability(nullable = false, session.typeContext)
            DotNetClrKotlinNullabilityQualifier.NULLABLE ->
                withNullability(nullable = true, session.typeContext)
            DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY -> {
                val lower = withNullability(nullable = false, session.typeContext)
                val upper = withNullability(nullable = true, session.typeContext)
                ConeFlexibleType(
                    lowerBound = lower,
                    upperBound = upper,
                    isTrivial = true,
                )
            }
        }

    private class ForeignAnnotationServices(
        private val declarationResolver: DotNetClrNullableDeclarationResolver?,
        private val signatureResolver: DotNetClrSignatureResolver,
        private val evidenceApplicator: DotNetClrNullableEvidenceApplicator?,
        private val projector: DotNetClrKotlinNullabilityProjector,
        private val notNullDecoder: DotNetClrNotNullMetadataDecoder?,
        private val notNullIfNotNullDecoder: DotNetClrNotNullIfNotNullMetadataDecoder?,
        private val notNullWhenDecoder: DotNetClrNotNullWhenMetadataDecoder?,
    ) {
        fun qualifier(
            assembly: DotNetClrAssemblyMetadata,
            method: DotNetClrMethodDefinition,
            target: DotNetClrNullableDeclarationTarget,
        ): DotNetClrKotlinNullabilityQualifier {
            val type = when (
                val resolution = signatureResolver.resolve(assembly, method.signature)
            ) {
                is DotNetClrResolvedMethodSignatureResolution.Resolved ->
                    when (target) {
                        is DotNetClrNullableDeclarationTarget.MethodReturn ->
                            resolution.signature.returnType
                        is DotNetClrNullableDeclarationTarget.MethodParameter ->
                            resolution.signature.parameterTypes.getOrNull(target.index)
                                ?: return DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
                        else -> return DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
                    }
                is DotNetClrResolvedMethodSignatureResolution.Invalid ->
                    return DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
                is DotNetClrResolvedMethodSignatureResolution.UnresolvedType ->
                    return DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
            }
            if (!type.isReferencePrimitive()) {
                return DotNetClrKotlinNullabilityQualifier.NOT_NULL
            }
            val resolver = declarationResolver
                ?: return DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
            val applicator = evidenceApplicator
                ?: return DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
            val projection = projector.project(
                applicator.apply(type, resolver.resolve(assembly, target))
            )
            return when (projection) {
                is DotNetClrKotlinNullabilityProjection.Projected ->
                    projection.components.singleOrNull()?.qualifier
                        ?: DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
                is DotNetClrKotlinNullabilityProjection.Oblivious,
                is DotNetClrKotlinNullabilityProjection.Suppressed,
                is DotNetClrKotlinNullabilityProjection.DiagnosticFallback,
                -> DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
            }
        }

        fun contractDescription(
            assembly: DotNetClrAssemblyMetadata,
            method: DotNetClrMethodDefinition,
            valueParameters: List<FirValueParameter>,
        ): FirResolvedContractDescription? {
            val resolvedEffects = buildList<FirEffectDeclaration> {
                method.signature.parameterTypes.forEachIndexed { index, parameterType ->
                    if (!parameterType.isReferencePrimitive()) return@forEachIndexed
                    val parameterRow = assembly.parameterDefinitions.singleOrNull { parameter ->
                        parameter.declaringMethod == method.handle &&
                                parameter.parameterIndex == index
                    } ?: return@forEachIndexed
                    val parameter = valueParameters.getOrNull(index)
                        ?: return@forEachIndexed
                    val reference = ConeValueParameterReference(
                        index,
                        parameter.name.asString(),
                    )
                    val notNull = notNullDecoder?.decode(assembly, parameterRow.handle)
                    if (notNull is DotNetClrNotNullMetadataResolution.Decoded) {
                        add(
                            ConeConditionalEffectDeclaration(
                                ConeReturnsEffectDeclaration(
                                    ConeContractConstantValues.WILDCARD
                                ),
                                ConeIsNullPredicate(reference, isNegated = true),
                            ).toFirElement()
                        )
                    }
                    if (
                        method.signature.returnType ==
                        DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.BOOLEAN)
                    ) {
                        val notNullWhen =
                            notNullWhenDecoder?.decode(assembly, parameterRow.handle)
                        if (
                            notNullWhen is
                            DotNetClrNotNullWhenMetadataResolution.Decoded
                        ) {
                            add(
                                ConeConditionalEffectDeclaration(
                                    ConeReturnsEffectDeclaration(
                                        if (notNullWhen.returnValue) {
                                            ConeContractConstantValues.TRUE
                                        } else {
                                            ConeContractConstantValues.FALSE
                                        }
                                    ),
                                    ConeIsNullPredicate(reference, isNegated = true),
                                ).toFirElement()
                            )
                        }
                    }
                }
                notNullIfNotNullParameterIndices(assembly, method).forEach { index ->
                    val parameter = valueParameters.getOrNull(index)
                        ?: return@forEach
                    val reference = ConeValueParameterReference(
                        index,
                        parameter.name.asString(),
                    )
                    add(
                        ConeConditionalReturnsDeclaration(
                            ConeIsNullPredicate(reference, isNegated = true),
                            ConeReturnsEffectDeclaration(
                                ConeContractConstantValues.NOT_NULL
                            ),
                        ).toFirElement()
                    )
                }
            }
            if (resolvedEffects.isEmpty()) return null
            return buildResolvedContractDescription {
                effects += resolvedEffects
            }
        }

        private fun notNullIfNotNullParameterIndices(
            assembly: DotNetClrAssemblyMetadata,
            method: DotNetClrMethodDefinition,
        ): List<Int> {
            if (!method.signature.returnType.isReferencePrimitive()) return emptyList()
            val decoder = notNullIfNotNullDecoder ?: return emptyList()
            val returnRow = assembly.parameterDefinitions.singleOrNull { parameter ->
                parameter.declaringMethod == method.handle && parameter.isReturn
            } ?: return emptyList()
            val decoded = decoder.decode(assembly, returnRow.handle)
                as? DotNetClrNotNullIfNotNullMetadataResolution.Decoded
                ?: return emptyList()
            val indices = decoded.attributes.map { attribute ->
                val rows = assembly.parameterDefinitions.filter { parameter ->
                    parameter.declaringMethod == method.handle &&
                            !parameter.isReturn &&
                            parameter.name == attribute.parameterName
                }
                val index = rows.singleOrNull()?.parameterIndex
                    ?: return emptyList()
                if (
                    index !in method.signature.parameterTypes.indices ||
                    !method.signature.parameterTypes[index].isReferencePrimitive()
                ) {
                    return emptyList()
                }
                index
            }
            return indices.distinct()
        }

        private fun DotNetClrResolvedTypeSignature.isReferencePrimitive(): Boolean =
            this == DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.STRING) ||
                    this == DotNetClrResolvedTypeSignature.Primitive(DotNetClrPrimitiveType.OBJECT)

        private fun DotNetClrTypeSignature.isReferencePrimitive(): Boolean =
            this == DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.STRING) ||
                    this == DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.OBJECT)

        companion object {
            fun create(assemblies: List<DotNetClrAssemblyMetadata>): ForeignAnnotationServices {
                val binder = SelectedAssemblyBinder(assemblies)
                val typeResolver = DotNetClrTypeResolver(binder)
                val signatureResolver = DotNetClrSignatureResolver(typeResolver)
                val coreTypes = resolveCustomAttributeCoreTypes(assemblies, typeResolver)
                if (coreTypes == null) {
                    return ForeignAnnotationServices(
                        declarationResolver = null,
                        signatureResolver = signatureResolver,
                        evidenceApplicator = null,
                        projector = DotNetClrKotlinNullabilityProjector(),
                        notNullDecoder = null,
                        notNullIfNotNullDecoder = null,
                        notNullWhenDecoder = null,
                    )
                }
                val serializedTypeResolver = DotNetClrSerializedTypeResolver(
                    typeResolver,
                    DotNetClrSerializedAssemblyBinder {
                            _,
                            unqualifiedContextAssembly,
                            assemblyName,
                        ->
                        if (assemblyName == null) {
                            unqualifiedContextAssembly
                        } else {
                            binder.bind(assemblyName)
                        }
                    },
                )
                val customAttributeDecoder = DotNetClrCustomAttributeDecoder(
                    typeResolver,
                    serializedTypeResolver,
                    coreTypes,
                )
                val notNullDecoder =
                    DotNetClrNotNullMetadataDecoder(customAttributeDecoder)
                val notNullIfNotNullDecoder =
                    DotNetClrNotNullIfNotNullMetadataDecoder(customAttributeDecoder)
                val notNullWhenDecoder =
                    DotNetClrNotNullWhenMetadataDecoder(customAttributeDecoder)
                val systemValueType =
                    resolveSystemType(assemblies, typeResolver, "ValueType")
                        ?: return unavailable(
                            signatureResolver,
                            notNullDecoder,
                            notNullIfNotNullDecoder,
                            notNullWhenDecoder,
                        )
                val systemNullable =
                    resolveSystemType(assemblies, typeResolver, "Nullable`1")
                        ?: return unavailable(
                            signatureResolver,
                            notNullDecoder,
                            notNullIfNotNullDecoder,
                            notNullWhenDecoder,
                        )
                val declarationResolver = DotNetClrNullableDeclarationResolver(
                    DotNetClrNullableMetadataDecoder(customAttributeDecoder)
                )
                return ForeignAnnotationServices(
                    declarationResolver,
                    signatureResolver,
                    DotNetClrNullableEvidenceApplicator(
                        DotNetClrNullableTypeTransformApplicator(
                            org.jetbrains.kotlin.backend.dotnet.DotNetClrPhysicalTypeClassifier(
                                typeResolver,
                                org.jetbrains.kotlin.backend.dotnet.DotNetClrPhysicalTypeCoreTypes(
                                    systemValueType = systemValueType,
                                    systemEnum = coreTypes.systemEnum,
                                    systemNullable = systemNullable,
                                ),
                            )
                        )
                    ),
                    DotNetClrKotlinNullabilityProjector(),
                    notNullDecoder,
                    notNullIfNotNullDecoder,
                    notNullWhenDecoder,
                )
            }

            private fun unavailable(
                signatureResolver: DotNetClrSignatureResolver,
                notNullDecoder: DotNetClrNotNullMetadataDecoder,
                notNullIfNotNullDecoder: DotNetClrNotNullIfNotNullMetadataDecoder,
                notNullWhenDecoder: DotNetClrNotNullWhenMetadataDecoder,
            ): ForeignAnnotationServices =
                ForeignAnnotationServices(
                    declarationResolver = null,
                    signatureResolver = signatureResolver,
                    evidenceApplicator = null,
                    projector = DotNetClrKotlinNullabilityProjector(),
                    notNullDecoder = notNullDecoder,
                    notNullIfNotNullDecoder = notNullIfNotNullDecoder,
                    notNullWhenDecoder = notNullWhenDecoder,
                )

            private fun resolveCustomAttributeCoreTypes(
                assemblies: List<DotNetClrAssemblyMetadata>,
                resolver: DotNetClrTypeResolver,
            ): DotNetClrCustomAttributeCoreTypes? {
                val systemAttribute = resolveSystemType(assemblies, resolver, "Attribute")
                    ?: return null
                val systemEnum = resolveSystemType(assemblies, resolver, "Enum")
                    ?: return null
                val systemType = resolveSystemType(assemblies, resolver, "Type")
                    ?: return null
                return DotNetClrCustomAttributeCoreTypes(
                    systemAttribute,
                    systemEnum,
                    systemType,
                )
            }

            private fun resolveSystemType(
                assemblies: List<DotNetClrAssemblyMetadata>,
                resolver: DotNetClrTypeResolver,
                metadataName: String,
            ): DotNetClrResolvedTypeDefinition? {
                val matches = assemblies.mapNotNull { assembly ->
                    when (
                        val resolution = resolver.resolveTopLevelType(
                            assembly,
                            "System",
                            metadataName,
                        )
                    ) {
                        is DotNetClrTypeResolution.Resolved -> resolution.type
                        is DotNetClrTypeResolution.Unresolved -> null
                    }
                }.distinct()
                return matches.singleOrNull()
            }
        }
    }

    private class SelectedAssemblyBinder(
        assemblies: List<DotNetClrAssemblyMetadata>,
    ) : DotNetClrAssemblyReferenceBinder {
        private val assembliesByName =
            assemblies.groupBy { assembly -> assembly.identity.name.lowercase() }

        override fun bind(
            sourceAssembly: DotNetClrAssemblyMetadata,
            reference: DotNetClrAssemblyReference,
        ): DotNetClrAssemblyMetadata? {
            val candidates = assembliesByName[reference.name.lowercase()].orEmpty()
                .filter { assembly ->
                    assembly.identity.version == reference.version &&
                            assembly.identity.culture == reference.culture &&
                            reference.publicKeyOrToken.matches(assembly, reference)
                }
            return candidates.singleOrNull()
        }

        fun bind(name: DotNetClrSerializedAssemblyName): DotNetClrAssemblyMetadata? {
            val candidates = assembliesByName[name.name.lowercase()].orEmpty()
                .filter { assembly ->
                    name.version?.components?.joinToString(".")?.let { version ->
                        assembly.identity.version == version
                    } != false &&
                            name.cultureName?.let { culture ->
                                assembly.identity.culture ==
                                        culture.ifEmpty { "neutral" }
                            } != false &&
                            name.publicKeyOrToken?.let { key ->
                                if (name.hasPublicKey) {
                                    assembly.identity.publicKey == key
                                } else {
                                    assembly.identity.publicKeyToken == key
                                }
                            } != false
                }
            return candidates.singleOrNull()
        }

        private fun List<Int>.matches(
            assembly: DotNetClrAssemblyMetadata,
            reference: DotNetClrAssemblyReference,
        ): Boolean {
            if (isEmpty()) {
                return assembly.identity.publicKey.isEmpty() &&
                        assembly.identity.publicKeyToken.isEmpty()
            }
            val hasFullPublicKey = reference.flags and 0x0001L != 0L
            return if (hasFullPublicKey) {
                assembly.identity.publicKey == this
            } else {
                assembly.identity.publicKeyToken == this
            }
        }
    }

    private companion object {
        val SUPPORTED_PRIMITIVES = setOf(
            DotNetClrPrimitiveType.BOOLEAN,
            DotNetClrPrimitiveType.CHAR,
            DotNetClrPrimitiveType.INT8,
            DotNetClrPrimitiveType.UINT8,
            DotNetClrPrimitiveType.INT16,
            DotNetClrPrimitiveType.UINT16,
            DotNetClrPrimitiveType.INT32,
            DotNetClrPrimitiveType.UINT32,
            DotNetClrPrimitiveType.INT64,
            DotNetClrPrimitiveType.UINT64,
            DotNetClrPrimitiveType.FLOAT32,
            DotNetClrPrimitiveType.FLOAT64,
            DotNetClrPrimitiveType.STRING,
            DotNetClrPrimitiveType.OBJECT,
        )
    }
}
