/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.dotnet

import org.jetbrains.kotlin.load.dotnet.DotNetClrAllowNullMetadataDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrAllowNullMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyMetadata
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyReference
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyReferenceBinder
import org.jetbrains.kotlin.load.dotnet.DotNetClrClasspathAssembly
import org.jetbrains.kotlin.load.dotnet.DotNetClrCustomAttributeCoreTypes
import org.jetbrains.kotlin.load.dotnet.DotNetClrCustomAttributeDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrDoesNotReturnMetadataDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrDoesNotReturnMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrDoesNotReturnIfMetadataDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrDoesNotReturnIfMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrDisallowNullMetadataDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrDisallowNullMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedMethodSource
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedPropertySource
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodSemanticsKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodVisibility
import org.jetbrains.kotlin.load.dotnet.DotNetClrMaybeNullMetadataDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrMaybeNullMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrMetadataHandle
import org.jetbrains.kotlin.load.dotnet.DotNetClrNullableDeclarationResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrNullableDeclarationTarget
import org.jetbrains.kotlin.load.dotnet.DotNetClrNullableEvidenceApplicator
import org.jetbrains.kotlin.load.dotnet.DotNetClrNullableMetadataDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrNullableTypeTransformApplicator
import org.jetbrains.kotlin.load.dotnet.DotNetClrNotNullIfNotNullMetadataDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrNotNullIfNotNullMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrNotNullMetadataDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrNotNullMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrNotNullWhenMetadataDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrNotNullWhenMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrObsoleteMetadataDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrObsoleteMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrParamArrayMetadataDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrParamArrayMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrParameterDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrPropertyDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedMethodSignatureResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedSignatureResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrSerializedAssemblyBinder
import org.jetbrains.kotlin.load.dotnet.DotNetClrSerializedAssemblyName
import org.jetbrains.kotlin.load.dotnet.DotNetClrSerializedTypeResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureCallingConvention
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeVisibility
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.EffectiveVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.FirModuleData
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.contracts.FirEffectDeclaration
import org.jetbrains.kotlin.fir.contracts.FirResolvedContractDescription
import org.jetbrains.kotlin.fir.contracts.builder.buildResolvedContractDescription
import org.jetbrains.kotlin.fir.contracts.description.ConeBooleanValueParameterReference
import org.jetbrains.kotlin.fir.contracts.description.ConeConditionalEffectDeclaration
import org.jetbrains.kotlin.fir.contracts.description.ConeConditionalReturnsDeclaration
import org.jetbrains.kotlin.fir.contracts.description.ConeContractConstantValues
import org.jetbrains.kotlin.fir.contracts.description.ConeIsNullPredicate
import org.jetbrains.kotlin.fir.contracts.description.ConeLogicalNot
import org.jetbrains.kotlin.fir.contracts.description.ConeReturnsEffectDeclaration
import org.jetbrains.kotlin.fir.contracts.description.ConeValueParameterReference
import org.jetbrains.kotlin.fir.contracts.toFirElement
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.builder.buildNamedFunction
import org.jetbrains.kotlin.fir.declarations.builder.buildProperty
import org.jetbrains.kotlin.fir.declarations.builder.buildPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.builder.buildRegularClass
import org.jetbrains.kotlin.fir.declarations.builder.buildValueParameter
import org.jetbrains.kotlin.fir.declarations.getDeprecationsProviderFromAnnotations
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotation
import org.jetbrains.kotlin.fir.expressions.builder.buildAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.builder.buildEnumEntryDeserializedAccessExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildLiteralExpression
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolNamesProvider
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolNamesProviderWithoutCallables
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProvider
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProviderInternals
import org.jetbrains.kotlin.fir.scopes.FirScopeProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertyAccessorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeFlexibleType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjectionOut
import org.jetbrains.kotlin.fir.types.ConeRigidType
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassType
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.types.createArrayType
import org.jetbrains.kotlin.fir.types.toLookupTag
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.types.withNullability
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.types.ConstantValueKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrPhysicalTypeClassifier
import org.jetbrains.kotlin.load.dotnet.DotNetClrPhysicalTypeCoreTypes
import java.util.concurrent.ConcurrentHashMap

/**
 * First closed foreign-CLR FIR slice.
 *
 * Only complete public, top-level, non-generic abstract-interface contracts over the supported
 * primitive/string/object grammar enter [candidates]. Classifier construction stays lazy. See
 * `compiler/ir/backend.dotnet/docs/programmes/clr-annotations.md`.
 */
class DotNetClrFirSymbolProvider(
    session: FirSession,
    private val moduleData: FirModuleData,
    private val scopeProvider: FirScopeProvider,
    assemblies: List<DotNetClrClasspathAssembly.WithoutCarrier>,
) : FirSymbolProvider(session) {
    private data class Candidate(
        val assembly: DotNetClrClasspathAssembly.WithoutCarrier,
        val type: DotNetClrTypeDefinition,
        val methods: List<DotNetClrMethodDefinition>,
        val properties: List<PropertyCandidate>,
    )

    private data class PropertyCandidate(
        val property: DotNetClrPropertyDefinition,
        val getter: DotNetClrMethodDefinition,
        val setter: DotNetClrMethodDefinition?,
    )

    private data class CompleteContract(
        val methods: List<DotNetClrMethodDefinition>,
        val properties: List<PropertyCandidate>,
    )

    private data class ArrayQualifiers(
        val array: DotNetClrKotlinNullabilityQualifier,
        val element: DotNetClrKotlinNullabilityQualifier,
    )

    private data class MethodParameterView(
        val type: ConeKotlinType,
        val isVararg: Boolean,
    )

    private val foreignAssemblies = assemblies
    private val metadata = foreignAssemblies.map(DotNetClrClasspathAssembly.WithoutCarrier::metadata)
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
        for (assembly in foreignAssemblies) {
            for (type in assembly.metadata.typeDefinitions) {
                val classId = type.classIdOrNull() ?: continue
                val contract = type.completeSupportedContractOrNull(assembly.metadata) ?: continue
                candidatesById.getOrPut(classId, ::mutableListOf) +=
                    Candidate(
                        assembly,
                        type,
                        contract.methods,
                        contract.properties,
                    )
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
    ): CompleteContract? {
        if (
            assembly.genericParameterDefinitions.any { parameter -> parameter.owner == handle } ||
            assembly.interfaceImplementations.any { implementation -> implementation.implementingType == handle } ||
            assembly.typeDefinitions.any { nested -> nested.declaringType == handle } ||
            assembly.fieldDefinitions.any { field -> field.declaringType == handle }
        ) {
            return null
        }
        val properties = assembly.propertyDefinitions.filter { property ->
            property.declaringType == handle
        }
        if (
            properties.map(DotNetClrPropertyDefinition::name).distinct().size != properties.size
        ) {
            return null
        }
        val propertyCandidates = properties.map { property ->
            property.supportedPropertyOrNull(assembly) ?: return null
        }
        val accessorHandles = propertyCandidates.flatMapTo(hashSetOf()) { property ->
            listOfNotNull(property.getter.handle, property.setter?.handle)
        }
        val publicMethods = assembly.methodDefinitions.filter { method ->
            method.declaringType == handle &&
                    method.visibility == DotNetClrMethodVisibility.PUBLIC
        }
        val ordinaryMethods = publicMethods.filterNot { method -> method.handle in accessorHandles }
        if (
            publicMethods.isEmpty() ||
            ordinaryMethods.any { method -> !method.isSupportedMethod(assembly) }
        ) {
            return null
        }
        return CompleteContract(ordinaryMethods, propertyCandidates)
    }

    private fun DotNetClrMethodDefinition.isSupportedMethod(
        assembly: DotNetClrAssemblyMetadata,
    ): Boolean {
        if (
            !hasSupportedAbstractInstanceShape() ||
            isSpecialName ||
            isRuntimeSpecialName ||
            !Name.isValidIdentifier(name) ||
            !signature.returnType.isSupportedType(allowVoid = true)
        ) {
            return false
        }
        return signature.parameterTypes.withIndex().all { [index, type] ->
            val parameterRows = assembly.parameterDefinitions.filter { parameter ->
                parameter.declaringMethod == handle &&
                        !parameter.isReturn &&
                        parameter.parameterIndex == index
            }
            when (type) {
                is DotNetClrTypeSignature.Primitive ->
                    type.isSupportedType(allowVoid = false) &&
                            parameterRows.all { parameter ->
                                annotationServices.paramArray(assembly, parameter.handle) ===
                                        DotNetClrParamArrayMetadataResolution.Absent
                            }
                is DotNetClrTypeSignature.SzArray -> {
                    val element = type.elementType as? DotNetClrTypeSignature.Primitive
                    val elementType = element?.type
                    if (elementType !in ARRAY_ELEMENT_PRIMITIVES) {
                        false
                    } else {
                        val paramArrayRows = parameterRows.map { parameter ->
                            annotationServices.paramArray(assembly, parameter.handle)
                        }
                        when {
                            paramArrayRows.all { resolution ->
                                resolution === DotNetClrParamArrayMetadataResolution.Absent
                            } -> true
                            index == signature.parameterTypes.lastIndex &&
                                    elementType in REFERENCE_PRIMITIVES &&
                                    paramArrayRows.singleOrNull() is
                                    DotNetClrParamArrayMetadataResolution.Decoded -> true
                            else -> false
                        }
                    }
                }
                else -> false
            }
        }
    }

    private fun DotNetClrMethodDefinition.hasSupportedAbstractInstanceShape(): Boolean =
        !isStatic &&
                isAbstract &&
                isVirtual &&
                !isFinal &&
                relativeVirtualAddress == 0L &&
                implementationAttributes == 0 &&
                !isRuntimeSpecialName &&
                signature.callingConvention == DotNetClrSignatureCallingConvention.DEFAULT &&
                signature.hasThis &&
                !signature.hasExplicitThis &&
                signature.genericParameterCount == 0 &&
                signature.varargParameterStart == null

    private fun DotNetClrPropertyDefinition.supportedPropertyOrNull(
        assembly: DotNetClrAssemblyMetadata,
    ): PropertyCandidate? {
        if (
            !Name.isValidIdentifier(name) ||
            hasDefault ||
            !signature.hasThis ||
            signature.indexParameterTypes.isNotEmpty() ||
            !signature.propertyType.isSupportedType(allowVoid = false)
        ) {
            return null
        }
        val semantics = assembly.methodSemantics.filter { semantic ->
            semantic.association == handle
        }
        if (semantics.any { semantic ->
                semantic.kind !in setOf(
                    DotNetClrMethodSemanticsKind.GETTER,
                    DotNetClrMethodSemanticsKind.SETTER,
                )
            }
        ) {
            return null
        }
        val getterHandle = semantics.singleOrNull { semantic ->
            semantic.kind == DotNetClrMethodSemanticsKind.GETTER
        }?.method ?: return null
        val setterHandle = semantics.singleOrNull { semantic ->
            semantic.kind == DotNetClrMethodSemanticsKind.SETTER
        }?.method
        val getter = assembly.methodDefinitions.singleOrNull { method ->
            method.handle == getterHandle
        } ?: return null
        val setter = setterHandle?.let { handle ->
            assembly.methodDefinitions.singleOrNull { method -> method.handle == handle }
                ?: return null
        }
        if (
            getter.visibility != DotNetClrMethodVisibility.PUBLIC ||
            !getter.hasSupportedAbstractInstanceShape() ||
            getter.signature.returnType != signature.propertyType ||
            getter.signature.parameterTypes.isNotEmpty()
        ) {
            return null
        }
        if (
            setter != null &&
            (
                    setter.visibility != DotNetClrMethodVisibility.PUBLIC ||
                            !setter.hasSupportedAbstractInstanceShape() ||
                            setter.signature.returnType != DotNetClrTypeSignature.Void ||
                            setter.signature.parameterTypes != listOf(signature.propertyType)
                    )
        ) {
            return null
        }
        if (annotationServices.hasSplitPropertyState(assembly, this, getter, setter)) {
            return null
        }
        return PropertyCandidate(this, getter, setter)
    }

    private fun DotNetClrTypeSignature.isSupportedType(allowVoid: Boolean): Boolean =
        when (this) {
            DotNetClrTypeSignature.Void -> allowVoid
            is DotNetClrTypeSignature.Primitive -> type in SUPPORTED_PRIMITIVES
            is DotNetClrTypeSignature.SzArray ->
                (elementType as? DotNetClrTypeSignature.Primitive)?.type in
                        ARRAY_ELEMENT_PRIMITIVES
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
                declarations += buildMethod(classId, classSymbol, candidate, method)
            }
            for (property in candidate.properties) {
                declarations += buildProperty(classId, classSymbol, candidate, property)
            }
            annotationServices.obsolete(
                candidate.assembly.metadata,
                candidate.type.handle,
            )?.let { obsolete ->
                annotations += buildDeprecatedAnnotation(obsolete)
            }
        }.apply {
            replaceDeprecationsProvider(
                annotations.getDeprecationsProviderFromAnnotations(session, fromJava = true)
            )
        }
        return classSymbol
    }

    private fun buildProperty(
        classId: ClassId,
        classSymbol: FirRegularClassSymbol,
        candidate: Candidate,
        candidateProperty: PropertyCandidate,
    ) = buildProperty {
        val assembly = candidate.assembly.metadata
        val physicalProperty = candidateProperty.property
        val propertyName = Name.identifier(physicalProperty.name)
        val propertySymbol = FirRegularPropertySymbol(CallableId(classId, propertyName))
        val propertyType = physicalProperty.signature.propertyType.toKotlinType(
            annotationServices,
            assembly,
            DotNetClrNullableDeclarationTarget.Property(physicalProperty),
        )
        resolvePhase = FirResolvePhase.ANALYZED_DEPENDENCIES
        origin = FirDeclarationOrigin.Library
        moduleData = this@DotNetClrFirSymbolProvider.moduleData
        status = FirResolvedDeclarationStatusImpl(
            Visibilities.Public,
            Modality.ABSTRACT,
            EffectiveVisibility.Public,
        )
        isLocal = false
        returnTypeRef = buildResolvedTypeRef {
            coneType = propertyType
        }
        dispatchReceiverType = classSymbol.constructType()
        name = propertyName
        isVar = candidateProperty.setter != null
        symbol = propertySymbol
        containerSource = DotNetClrImportedPropertySource(
            candidate.assembly,
            candidate.type,
            physicalProperty,
            candidateProperty.getter,
            candidateProperty.setter,
        )
        getter = buildPropertyAccessor {
            resolvePhase = FirResolvePhase.ANALYZED_DEPENDENCIES
            origin = FirDeclarationOrigin.Library
            moduleData = this@DotNetClrFirSymbolProvider.moduleData
            status = FirResolvedDeclarationStatusImpl(
                Visibilities.Public,
                Modality.ABSTRACT,
                EffectiveVisibility.Public,
            )
            returnTypeRef = buildResolvedTypeRef {
                coneType = propertyType
            }
            dispatchReceiverType = classSymbol.constructType()
            symbol = FirPropertyAccessorSymbol()
            this.propertySymbol = propertySymbol
            isGetter = true
        }
        setter = candidateProperty.setter?.let {
            val setterSymbol = FirPropertyAccessorSymbol()
            buildPropertyAccessor {
                resolvePhase = FirResolvePhase.ANALYZED_DEPENDENCIES
                origin = FirDeclarationOrigin.Library
                moduleData = this@DotNetClrFirSymbolProvider.moduleData
                status = FirResolvedDeclarationStatusImpl(
                    Visibilities.Public,
                    Modality.ABSTRACT,
                    EffectiveVisibility.Public,
                )
                returnTypeRef = buildResolvedTypeRef {
                    coneType = session.builtinTypes.unitType.coneType
                }
                dispatchReceiverType = classSymbol.constructType()
                symbol = setterSymbol
                this.propertySymbol = propertySymbol
                isGetter = false
                valueParameters += buildValueParameter {
                    resolvePhase = FirResolvePhase.ANALYZED_DEPENDENCIES
                    origin = FirDeclarationOrigin.Library
                    moduleData = this@DotNetClrFirSymbolProvider.moduleData
                    returnTypeRef = buildResolvedTypeRef {
                        coneType = propertyType
                    }
                    name = Name.identifier("value")
                    symbol = FirValueParameterSymbol()
                    containingDeclarationSymbol = setterSymbol
                    isCrossinline = false
                    isNoinline = false
                    isVararg = false
                }
            }
        }
        annotationServices.obsolete(assembly, physicalProperty.handle)?.let { obsolete ->
            annotations += buildDeprecatedAnnotation(obsolete)
        }
        annotationServices.obsolete(assembly, candidateProperty.getter.handle)?.let { obsolete ->
            annotations += buildDeprecatedAnnotation(
                obsolete,
                AnnotationUseSiteTarget.PROPERTY_GETTER,
            )
        }
        candidateProperty.setter?.let { setter ->
            annotationServices.obsolete(assembly, setter.handle)?.let { obsolete ->
                annotations += buildDeprecatedAnnotation(
                    obsolete,
                    AnnotationUseSiteTarget.PROPERTY_SETTER,
                )
            }
        }
    }.apply {
        replaceDeprecationsProvider(
            annotations.getDeprecationsProviderFromAnnotations(session, fromJava = true)
        )
    }

    private fun buildMethod(
        classId: ClassId,
        classSymbol: FirRegularClassSymbol,
        candidate: Candidate,
        method: DotNetClrMethodDefinition,
    ) = buildNamedFunction {
        val assembly = candidate.assembly.metadata
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
        containerSource = DotNetClrImportedMethodSource(
            candidate.assembly,
            candidate.type,
            method,
        )
        val functionSymbol = FirNamedFunctionSymbol(CallableId(classId, name))
        symbol = functionSymbol
        dispatchReceiverType = classSymbol.constructType()
        returnTypeRef = buildResolvedTypeRef {
            coneType =
                if (annotationServices.returnsNothing(assembly, method)) {
                    session.builtinTypes.nothingType.coneType
                } else {
                    method.signature.returnType.toKotlinType(
                        annotationServices,
                        assembly,
                        DotNetClrNullableDeclarationTarget.MethodReturn(method),
                    )
                }
        }
        method.signature.parameterTypes.forEachIndexed { index, type ->
            val parameterView = methodParameterView(assembly, method, index, type)
            valueParameters += buildValueParameter {
                resolvePhase = FirResolvePhase.ANALYZED_DEPENDENCIES
                origin = FirDeclarationOrigin.Library
                moduleData = this@DotNetClrFirSymbolProvider.moduleData
                returnTypeRef = buildResolvedTypeRef {
                    coneType = parameterView.type
                }
                name = parameterName(assembly, method, index)
                symbol = FirValueParameterSymbol()
                containingDeclarationSymbol = functionSymbol
                isCrossinline = false
                isNoinline = false
                isVararg = parameterView.isVararg
            }
        }
        contractDescription = annotationServices.contractDescription(
            assembly,
            method,
            valueParameters,
        )
        annotationServices.obsolete(assembly, method.handle)?.let { obsolete ->
            annotations += buildDeprecatedAnnotation(obsolete)
        }
    }.apply {
        replaceDeprecationsProvider(annotations.getDeprecationsProviderFromAnnotations(session, fromJava = true))
    }

    private fun methodParameterView(
        assembly: DotNetClrAssemblyMetadata,
        method: DotNetClrMethodDefinition,
        index: Int,
        type: DotNetClrTypeSignature,
    ): MethodParameterView =
        when (type) {
            is DotNetClrTypeSignature.SzArray -> {
                val element = type.elementType as? DotNetClrTypeSignature.Primitive
                    ?: error("Unsupported parameter array entered the closed foreign CLR FIR slice")
                val target = DotNetClrNullableDeclarationTarget.MethodParameter(method, index)
                val qualifiers = annotationServices.arrayQualifiers(
                    assembly,
                    type,
                    target,
                )
                val isVararg = assembly.parameterDefinitions.singleOrNull { parameter ->
                    parameter.declaringMethod == method.handle &&
                            !parameter.isReturn &&
                            parameter.parameterIndex == index
                }?.let { parameter ->
                    annotationServices.paramArray(assembly, parameter.handle) is
                            DotNetClrParamArrayMetadataResolution.Decoded
                } == true
                MethodParameterView(
                    element.toKotlinArrayType(qualifiers, isVararg),
                    isVararg,
                )
            }
            else -> MethodParameterView(
                type.toKotlinType(
                    annotationServices.qualifier(
                        assembly,
                        method,
                        DotNetClrNullableDeclarationTarget.MethodParameter(method, index),
                    )
                ),
                isVararg = false,
            )
        }

    private fun DotNetClrTypeSignature.toKotlinType(
        annotationServices: ForeignAnnotationServices,
        assembly: DotNetClrAssemblyMetadata,
        target: DotNetClrNullableDeclarationTarget,
    ): ConeKotlinType =
        when (this) {
            is DotNetClrTypeSignature.SzArray -> {
                val element = elementType as? DotNetClrTypeSignature.Primitive
                    ?: error("Unsupported vector entered the closed foreign CLR FIR slice")
                element.toKotlinArrayType(
                    annotationServices.arrayQualifiers(assembly, this, target),
                    isVararg = false,
                )
            }
            else -> {
                val qualifier = when (target) {
                    is DotNetClrNullableDeclarationTarget.Property ->
                        annotationServices.propertyQualifier(assembly, target.property)
                    is DotNetClrNullableDeclarationTarget.MethodReturn ->
                        annotationServices.qualifier(
                            assembly,
                            target.method,
                            target,
                        )
                    is DotNetClrNullableDeclarationTarget.MethodParameter ->
                        annotationServices.qualifier(
                            assembly,
                            target.method,
                            target,
                        )
                    else -> error("Unsupported foreign declaration target $target")
                }
                toKotlinType(qualifier)
            }
        }

    private fun DotNetClrTypeSignature.Primitive.toKotlinArrayType(
        qualifiers: ArrayQualifiers,
        isVararg: Boolean,
    ): ConeKotlinType {
        val elementType = toKotlinType(qualifiers.element)
        val outArray = ConeKotlinTypeProjectionOut(elementType).createArrayType(
            nullable = false,
            createPrimitiveArrayTypeIfPossible = false,
        )
        if (isVararg) return outArray.withQualifier(qualifiers.array)

        val invariantArray = elementType.createArrayType(
            nullable = false,
            createPrimitiveArrayTypeIfPossible = false,
        )
        val lowerNullable = qualifiers.array == DotNetClrKotlinNullabilityQualifier.NULLABLE
        val upperNullable = qualifiers.array != DotNetClrKotlinNullabilityQualifier.NOT_NULL
        return ConeFlexibleType(
            invariantArray.withNullability(lowerNullable, session.typeContext),
            outArray.withNullability(upperNullable, session.typeContext),
            isTrivial = false,
        )
    }

    private fun buildDeprecatedAnnotation(
        obsolete: DotNetClrObsoleteMetadataResolution.Decoded,
        useSiteTarget: AnnotationUseSiteTarget? = null,
    ) = buildAnnotation {
        this.useSiteTarget = useSiteTarget
        annotationTypeRef = buildResolvedTypeRef {
            coneType =
                StandardClassIds.Annotations.Deprecated.toLookupTag().constructClassType()
        }
        argumentMapping = buildAnnotationArgumentMapping {
            mapping[StandardClassIds.Annotations.ParameterNames.deprecatedMessage] =
                buildLiteralExpression(
                    source = null,
                    kind = ConstantValueKind.String,
                    value = obsolete.message ?: "Deprecated in .NET",
                    setType = true,
                )
            mapping[StandardClassIds.Annotations.ParameterNames.deprecatedLevel] =
                buildEnumEntryDeserializedAccessExpression {
                    enumClassId = StandardClassIds.DeprecationLevel
                    enumEntryName = Name.identifier(
                        if (obsolete.isError) "ERROR" else "WARNING"
                    )
                }
        }
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
        private val allowNullDecoder: DotNetClrAllowNullMetadataDecoder?,
        private val doesNotReturnDecoder: DotNetClrDoesNotReturnMetadataDecoder?,
        private val doesNotReturnIfDecoder: DotNetClrDoesNotReturnIfMetadataDecoder?,
        private val disallowNullDecoder: DotNetClrDisallowNullMetadataDecoder?,
        private val maybeNullDecoder: DotNetClrMaybeNullMetadataDecoder?,
        private val notNullDecoder: DotNetClrNotNullMetadataDecoder?,
        private val notNullIfNotNullDecoder: DotNetClrNotNullIfNotNullMetadataDecoder?,
        private val notNullWhenDecoder: DotNetClrNotNullWhenMetadataDecoder?,
        private val obsoleteDecoder: DotNetClrObsoleteMetadataDecoder?,
        private val paramArrayDecoder: DotNetClrParamArrayMetadataDecoder?,
    ) {
        private val inputNullabilityEnhancer = DotNetClrInputNullabilityEnhancer()
        private val returnNullabilityEnhancer = DotNetClrReturnNullabilityEnhancer()

        fun obsolete(
            assembly: DotNetClrAssemblyMetadata,
            parent: DotNetClrMetadataHandle,
        ): DotNetClrObsoleteMetadataResolution.Decoded? =
            obsoleteDecoder?.decode(assembly, parent)
                as? DotNetClrObsoleteMetadataResolution.Decoded

        fun paramArray(
            assembly: DotNetClrAssemblyMetadata,
            parent: DotNetClrMetadataHandle,
        ): DotNetClrParamArrayMetadataResolution =
            paramArrayDecoder?.decode(assembly, parent)
                ?: DotNetClrParamArrayMetadataResolution.Absent

        fun returnsNothing(
            assembly: DotNetClrAssemblyMetadata,
            method: DotNetClrMethodDefinition,
        ): Boolean {
            val resolution = doesNotReturnDecoder?.decode(assembly, method.handle)
            return resolution is DotNetClrDoesNotReturnMetadataResolution.Decoded
        }

        fun hasSplitPropertyState(
            assembly: DotNetClrAssemblyMetadata,
            property: DotNetClrPropertyDefinition,
            getter: DotNetClrMethodDefinition,
            setter: DotNetClrMethodDefinition?,
        ): Boolean {
            val evidenceParents = buildList {
                add(property.handle)
                assembly.parameterDefinitions.asSequence()
                    .filter { parameter ->
                        (parameter.declaringMethod == getter.handle && parameter.isReturn) ||
                                (
                                        setter != null &&
                                                parameter.declaringMethod == setter.handle &&
                                                !parameter.isReturn &&
                                                parameter.parameterIndex == 0
                                        )
                    }
                    .mapTo(this, DotNetClrParameterDefinition::handle)
            }
            return evidenceParents.any { parent ->
                val allowNull = allowNullDecoder?.decode(assembly, parent)
                val disallowNull = disallowNullDecoder?.decode(assembly, parent)
                val notNull = notNullDecoder?.decode(assembly, parent)
                val maybeNull = maybeNullDecoder?.decode(assembly, parent)
                (allowNull != null &&
                        allowNull !== DotNetClrAllowNullMetadataResolution.Absent) ||
                        (disallowNull != null &&
                                disallowNull !== DotNetClrDisallowNullMetadataResolution.Absent) ||
                        (notNull != null &&
                                notNull !== DotNetClrNotNullMetadataResolution.Absent) ||
                        (maybeNull != null &&
                                maybeNull !== DotNetClrMaybeNullMetadataResolution.Absent)
            }
        }

        fun propertyQualifier(
            assembly: DotNetClrAssemblyMetadata,
            property: DotNetClrPropertyDefinition,
        ): DotNetClrKotlinNullabilityQualifier {
            val type = when (val signature = property.signature.propertyType) {
                is DotNetClrTypeSignature.Primitive ->
                    DotNetClrResolvedTypeSignature.Primitive(signature.type)
                else -> return DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
            }
            if (!type.isReferencePrimitive()) {
                return DotNetClrKotlinNullabilityQualifier.NOT_NULL
            }
            val resolver = declarationResolver
                ?: return DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
            val applicator = evidenceApplicator
                ?: return DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
            return when (
                val projection = projector.project(
                    applicator.apply(
                        type,
                        resolver.resolve(
                            assembly,
                            DotNetClrNullableDeclarationTarget.Property(property),
                        ),
                    )
                )
            ) {
                is DotNetClrKotlinNullabilityProjection.Projected ->
                    projection.components.singleOrNull()?.qualifier
                        ?: DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
                is DotNetClrKotlinNullabilityProjection.Oblivious,
                is DotNetClrKotlinNullabilityProjection.Suppressed,
                is DotNetClrKotlinNullabilityProjection.DiagnosticFallback,
                -> DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
            }
        }

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
            val declarationQualifier = when (projection) {
                is DotNetClrKotlinNullabilityProjection.Projected ->
                    projection.components.singleOrNull()?.qualifier
                        ?: DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
                is DotNetClrKotlinNullabilityProjection.Oblivious,
                is DotNetClrKotlinNullabilityProjection.Suppressed,
                is DotNetClrKotlinNullabilityProjection.DiagnosticFallback,
                -> DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
            }
            return when (target) {
                is DotNetClrNullableDeclarationTarget.MethodReturn ->
                    returnQualifier(assembly, method, declarationQualifier)
                is DotNetClrNullableDeclarationTarget.MethodParameter ->
                    inputQualifier(
                        assembly,
                        method,
                        target.index,
                        declarationQualifier,
                    )
            }
        }

        fun arrayQualifiers(
            assembly: DotNetClrAssemblyMetadata,
            signature: DotNetClrTypeSignature.SzArray,
            target: DotNetClrNullableDeclarationTarget,
        ): ArrayQualifiers {
            val resolved = when (val resolution = signatureResolver.resolve(assembly, signature)) {
                is DotNetClrResolvedSignatureResolution.Resolved ->
                    resolution.signature as? DotNetClrResolvedTypeSignature.SzArray
                is DotNetClrResolvedSignatureResolution.Invalid,
                is DotNetClrResolvedSignatureResolution.UnresolvedType,
                -> null
            }
            val referenceElement =
                (resolved?.elementType as? DotNetClrResolvedTypeSignature.Primitive)
                    ?.isReferencePrimitive() == true
            val fallback = ArrayQualifiers(
                array = DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY,
                element = if (referenceElement) {
                    DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
                } else {
                    DotNetClrKotlinNullabilityQualifier.NOT_NULL
                },
            )
            val type = resolved ?: return enhanceArrayInput(assembly, target, fallback)
            val resolver = declarationResolver
                ?: return enhanceArrayInput(assembly, target, fallback)
            val applicator = evidenceApplicator
                ?: return enhanceArrayInput(assembly, target, fallback)
            val projection = projector.project(
                applicator.apply(
                    type,
                    resolver.resolve(assembly, target),
                )
            )
            val expectedComponents = if (referenceElement) 2 else 1
            val declarationQualifiers =
                if (
                    projection is DotNetClrKotlinNullabilityProjection.Projected &&
                    projection.components.size == expectedComponents &&
                    projection.components.firstOrNull()?.type == type &&
                    (!referenceElement || projection.components[1].type == type.elementType)
                ) {
                    ArrayQualifiers(
                        array = projection.components[0].qualifier,
                        element = if (referenceElement) {
                            projection.components[1].qualifier
                        } else {
                            DotNetClrKotlinNullabilityQualifier.NOT_NULL
                        },
                    )
                } else {
                    fallback
                }
            return enhanceArrayInput(assembly, target, declarationQualifiers)
        }

        private fun enhanceArrayInput(
            assembly: DotNetClrAssemblyMetadata,
            target: DotNetClrNullableDeclarationTarget,
            qualifiers: ArrayQualifiers,
        ): ArrayQualifiers =
            when (target) {
                is DotNetClrNullableDeclarationTarget.MethodParameter ->
                    qualifiers.copy(
                        array = inputQualifier(
                            assembly,
                            target.method,
                            target.index,
                            qualifiers.array,
                        )
                    )
                is DotNetClrNullableDeclarationTarget.MethodReturn ->
                    qualifiers.copy(
                        array = returnQualifier(
                            assembly,
                            target.method,
                            qualifiers.array,
                        )
                    )
                is DotNetClrNullableDeclarationTarget.Property -> qualifiers
                else -> error("Unsupported array declaration target $target")
        }

        private fun inputQualifier(
            assembly: DotNetClrAssemblyMetadata,
            method: DotNetClrMethodDefinition,
            index: Int,
            declarationQualifier: DotNetClrKotlinNullabilityQualifier,
        ): DotNetClrKotlinNullabilityQualifier {
            val parameterRow = assembly.parameterDefinitions.singleOrNull { parameter ->
                parameter.declaringMethod == method.handle &&
                        !parameter.isReturn &&
                        parameter.parameterIndex == index
            } ?: return declarationQualifier
            val allowNull = allowNullDecoder?.decode(assembly, parameterRow.handle)
            val disallowNull = disallowNullDecoder?.decode(assembly, parameterRow.handle)
            return inputNullabilityEnhancer.enhance(
                declarationQualifier,
                allowNull,
                disallowNull,
            )
        }

        private fun returnQualifier(
            assembly: DotNetClrAssemblyMetadata,
            method: DotNetClrMethodDefinition,
            declarationQualifier: DotNetClrKotlinNullabilityQualifier,
        ): DotNetClrKotlinNullabilityQualifier {
            val returnRow = assembly.parameterDefinitions.singleOrNull { parameter ->
                parameter.declaringMethod == method.handle && parameter.isReturn
            } ?: return declarationQualifier
            val notNull = notNullDecoder?.decode(assembly, returnRow.handle)
            val maybeNull = maybeNullDecoder?.decode(assembly, returnRow.handle)
            return returnNullabilityEnhancer.enhance(
                declarationQualifier,
                notNull,
                maybeNull,
            )
        }

        fun contractDescription(
            assembly: DotNetClrAssemblyMetadata,
            method: DotNetClrMethodDefinition,
            valueParameters: List<FirValueParameter>,
        ): FirResolvedContractDescription? {
            val resolvedEffects = buildList<FirEffectDeclaration> {
                method.signature.parameterTypes.forEachIndexed { index, parameterType ->
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
                    if (parameterType.isReferencePrimitive()) {
                        val notNull =
                            notNullDecoder?.decode(assembly, parameterRow.handle)
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
                    if (
                        parameterType ==
                        DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.BOOLEAN)
                    ) {
                        val doesNotReturnIf =
                            doesNotReturnIfDecoder?.decode(assembly, parameterRow.handle)
                        if (
                            doesNotReturnIf is
                            DotNetClrDoesNotReturnIfMetadataResolution.Decoded
                        ) {
                            val booleanReference = ConeBooleanValueParameterReference(
                                index,
                                parameter.name.asString(),
                            )
                            add(
                                ConeConditionalEffectDeclaration(
                                    ConeReturnsEffectDeclaration(
                                        ConeContractConstantValues.WILDCARD
                                    ),
                                    if (doesNotReturnIf.parameterValue) {
                                        ConeLogicalNot(booleanReference)
                                    } else {
                                        booleanReference
                                    },
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
                        allowNullDecoder = null,
                        doesNotReturnDecoder = null,
                        doesNotReturnIfDecoder = null,
                        disallowNullDecoder = null,
                        maybeNullDecoder = null,
                        notNullDecoder = null,
                        notNullIfNotNullDecoder = null,
                        notNullWhenDecoder = null,
                        obsoleteDecoder = null,
                        paramArrayDecoder = null,
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
                val allowNullDecoder =
                    DotNetClrAllowNullMetadataDecoder(customAttributeDecoder)
                val doesNotReturnDecoder =
                    DotNetClrDoesNotReturnMetadataDecoder(customAttributeDecoder)
                val doesNotReturnIfDecoder =
                    DotNetClrDoesNotReturnIfMetadataDecoder(customAttributeDecoder)
                val disallowNullDecoder =
                    DotNetClrDisallowNullMetadataDecoder(customAttributeDecoder)
                val maybeNullDecoder =
                    DotNetClrMaybeNullMetadataDecoder(customAttributeDecoder)
                val notNullDecoder =
                    DotNetClrNotNullMetadataDecoder(customAttributeDecoder)
                val notNullIfNotNullDecoder =
                    DotNetClrNotNullIfNotNullMetadataDecoder(customAttributeDecoder)
                val notNullWhenDecoder =
                    DotNetClrNotNullWhenMetadataDecoder(customAttributeDecoder)
                val obsoleteDecoder = when (
                    val resolution = typeResolver.resolveTopLevelType(
                        coreTypes.systemAttribute.assembly,
                        "System",
                        "ObsoleteAttribute",
                    )
                ) {
                    is DotNetClrTypeResolution.Resolved ->
                        DotNetClrObsoleteMetadataDecoder(
                            customAttributeDecoder,
                            resolution.type,
                        )
                    is DotNetClrTypeResolution.Unresolved -> null
                }
                val paramArrayDecoder = when (
                    val resolution = typeResolver.resolveTopLevelType(
                        coreTypes.systemAttribute.assembly,
                        "System",
                        "ParamArrayAttribute",
                    )
                ) {
                    is DotNetClrTypeResolution.Resolved ->
                        DotNetClrParamArrayMetadataDecoder(
                            customAttributeDecoder,
                            resolution.type,
                        )
                    is DotNetClrTypeResolution.Unresolved -> null
                }
                val systemValueType =
                    resolveSystemType(assemblies, typeResolver, "ValueType")
                        ?: return unavailable(
                            signatureResolver,
                            allowNullDecoder,
                            doesNotReturnDecoder,
                            doesNotReturnIfDecoder,
                            disallowNullDecoder,
                            maybeNullDecoder,
                            notNullDecoder,
                            notNullIfNotNullDecoder,
                            notNullWhenDecoder,
                            obsoleteDecoder,
                            paramArrayDecoder,
                        )
                val systemNullable =
                    resolveSystemType(assemblies, typeResolver, "Nullable`1")
                        ?: return unavailable(
                            signatureResolver,
                            allowNullDecoder,
                            doesNotReturnDecoder,
                            doesNotReturnIfDecoder,
                            disallowNullDecoder,
                            maybeNullDecoder,
                            notNullDecoder,
                            notNullIfNotNullDecoder,
                            notNullWhenDecoder,
                            obsoleteDecoder,
                            paramArrayDecoder,
                        )
                val declarationResolver = DotNetClrNullableDeclarationResolver(
                    DotNetClrNullableMetadataDecoder(customAttributeDecoder)
                )
                return ForeignAnnotationServices(
                    declarationResolver = declarationResolver,
                    signatureResolver = signatureResolver,
                    evidenceApplicator = DotNetClrNullableEvidenceApplicator(
                        DotNetClrNullableTypeTransformApplicator(
                            DotNetClrPhysicalTypeClassifier(
                                typeResolver,
                                DotNetClrPhysicalTypeCoreTypes(
                                    systemValueType = systemValueType,
                                    systemEnum = coreTypes.systemEnum,
                                    systemNullable = systemNullable,
                                ),
                            )
                        )
                    ),
                    projector = DotNetClrKotlinNullabilityProjector(),
                    allowNullDecoder = allowNullDecoder,
                    doesNotReturnDecoder = doesNotReturnDecoder,
                    doesNotReturnIfDecoder = doesNotReturnIfDecoder,
                    disallowNullDecoder = disallowNullDecoder,
                    maybeNullDecoder = maybeNullDecoder,
                    notNullDecoder = notNullDecoder,
                    notNullIfNotNullDecoder = notNullIfNotNullDecoder,
                    notNullWhenDecoder = notNullWhenDecoder,
                    obsoleteDecoder = obsoleteDecoder,
                    paramArrayDecoder = paramArrayDecoder,
                )
            }

            private fun unavailable(
                signatureResolver: DotNetClrSignatureResolver,
                allowNullDecoder: DotNetClrAllowNullMetadataDecoder,
                doesNotReturnDecoder: DotNetClrDoesNotReturnMetadataDecoder,
                doesNotReturnIfDecoder: DotNetClrDoesNotReturnIfMetadataDecoder,
                disallowNullDecoder: DotNetClrDisallowNullMetadataDecoder,
                maybeNullDecoder: DotNetClrMaybeNullMetadataDecoder,
                notNullDecoder: DotNetClrNotNullMetadataDecoder,
                notNullIfNotNullDecoder: DotNetClrNotNullIfNotNullMetadataDecoder,
                notNullWhenDecoder: DotNetClrNotNullWhenMetadataDecoder,
                obsoleteDecoder: DotNetClrObsoleteMetadataDecoder?,
                paramArrayDecoder: DotNetClrParamArrayMetadataDecoder?,
            ): ForeignAnnotationServices =
                ForeignAnnotationServices(
                    declarationResolver = null,
                    signatureResolver = signatureResolver,
                    evidenceApplicator = null,
                    projector = DotNetClrKotlinNullabilityProjector(),
                    allowNullDecoder = allowNullDecoder,
                    doesNotReturnDecoder = doesNotReturnDecoder,
                    doesNotReturnIfDecoder = doesNotReturnIfDecoder,
                    disallowNullDecoder = disallowNullDecoder,
                    maybeNullDecoder = maybeNullDecoder,
                    notNullDecoder = notNullDecoder,
                    notNullIfNotNullDecoder = notNullIfNotNullDecoder,
                    notNullWhenDecoder = notNullWhenDecoder,
                    obsoleteDecoder = obsoleteDecoder,
                    paramArrayDecoder = paramArrayDecoder,
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
        val REFERENCE_PRIMITIVES = setOf(
            DotNetClrPrimitiveType.STRING,
            DotNetClrPrimitiveType.OBJECT,
        )

        val ARRAY_ELEMENT_PRIMITIVES = setOf(
            DotNetClrPrimitiveType.BOOLEAN,
            DotNetClrPrimitiveType.CHAR,
            DotNetClrPrimitiveType.INT8,
            DotNetClrPrimitiveType.INT16,
            DotNetClrPrimitiveType.INT32,
            DotNetClrPrimitiveType.INT64,
            DotNetClrPrimitiveType.FLOAT32,
            DotNetClrPrimitiveType.FLOAT64,
            DotNetClrPrimitiveType.STRING,
            DotNetClrPrimitiveType.OBJECT,
        )

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
