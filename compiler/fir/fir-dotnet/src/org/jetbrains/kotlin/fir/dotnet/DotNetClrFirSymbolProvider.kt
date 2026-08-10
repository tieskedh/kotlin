/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.dotnet

import org.jetbrains.kotlin.load.dotnet.DotNetClrAllowNullMetadataDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrAllowNullMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyMetadata
import org.jetbrains.kotlin.load.dotnet.DotNetClrClasspathAssembly
import org.jetbrains.kotlin.load.dotnet.DotNetClrCustomAttributeDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrDoesNotReturnMetadataDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrDoesNotReturnMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrDoesNotReturnIfMetadataDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrDoesNotReturnIfMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrDisallowNullMetadataDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrDisallowNullMetadataResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterContextResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterContextResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterVariance
import org.jetbrains.kotlin.load.dotnet.DotNetClrImportedDeclarationGraph
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
import org.jetbrains.kotlin.load.dotnet.DotNetClrNullableGenericParameterEvidenceResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrNullableGenericParameterEvidenceResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrNullableMetadataDecoder
import org.jetbrains.kotlin.load.dotnet.DotNetClrNullableTypeComponent
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
import org.jetbrains.kotlin.load.dotnet.DotNetClrPhysicalTypeClassifier
import org.jetbrains.kotlin.load.dotnet.DotNetClrPhysicalTypeCoreTypes
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrPropertyDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedMethodSignatureResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedGenericConstraintType
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedGenericParameterContext
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedGenericParameterContextBinding
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedInterfaceImplementation
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedMethodSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeHierarchy
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrResolvedTypeView
import org.jetbrains.kotlin.load.dotnet.DotNetClrSelectedAssemblyBinder
import org.jetbrains.kotlin.load.dotnet.DotNetClrSerializedAssemblyBinder
import org.jetbrains.kotlin.load.dotnet.DotNetClrSerializedTypeResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureCallingConvention
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeHierarchyViewResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeHierarchyViewResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeResolution
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeResolver
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeVisibility
import org.jetbrains.kotlin.load.dotnet.resolveDotNetClrCustomAttributeCoreTypes
import org.jetbrains.kotlin.load.dotnet.resolveDotNetClrSystemType
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
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.builder.buildNamedFunction
import org.jetbrains.kotlin.fir.declarations.builder.buildProperty
import org.jetbrains.kotlin.fir.declarations.builder.buildPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.builder.buildRegularClass
import org.jetbrains.kotlin.fir.declarations.builder.buildTypeParameter
import org.jetbrains.kotlin.fir.declarations.builder.buildValueParameter
import org.jetbrains.kotlin.fir.declarations.getDeprecationsProviderFromAnnotations
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
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
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeFlexibleType
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjectionOut
import org.jetbrains.kotlin.fir.types.ConeRigidType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
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
import org.jetbrains.kotlin.types.Variance
import java.util.concurrent.ConcurrentHashMap

/**
 * First closed foreign-CLR FIR slice.
 *
 * Only complete public, top-level abstract-interface contracts over the supported primitive,
 * string, object, direct type/method-parameter, and vector grammar enter [candidates]. Classifier
 * construction stays lazy. See the foreign CLR generic-method and generic-TypeDef ADRs.
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
        val hierarchy: DotNetClrResolvedTypeHierarchy,
        val genericContext: DotNetClrResolvedGenericParameterContext,
        val methods: List<MethodCandidate>,
        val properties: List<PropertyCandidate>,
    )

    private data class MethodCandidate(
        val method: DotNetClrMethodDefinition,
        val signature: DotNetClrResolvedMethodSignature,
        val genericContext: DotNetClrResolvedGenericParameterContext,
    )

    private data class PropertyCandidate(
        val property: DotNetClrPropertyDefinition,
        val getter: DotNetClrMethodDefinition,
        val setter: DotNetClrMethodDefinition?,
        val getterSignature: DotNetClrResolvedMethodSignature,
        val setterSignature: DotNetClrResolvedMethodSignature?,
    )

    private data class CompleteContract(
        val hierarchy: DotNetClrResolvedTypeHierarchy,
        val genericContext: DotNetClrResolvedGenericParameterContext,
        val methods: List<MethodCandidate>,
        val properties: List<PropertyCandidate>,
    )

    private data class TypeParameterSymbols(
        val owner: List<FirTypeParameterSymbol>,
        val method: List<FirTypeParameterSymbol> = emptyList(),
    ) {
        fun symbol(kind: DotNetClrGenericParameterKind, index: Int): FirTypeParameterSymbol? =
            when (kind) {
                DotNetClrGenericParameterKind.TYPE -> owner.getOrNull(index)
                DotNetClrGenericParameterKind.METHOD -> method.getOrNull(index)
            }
    }

    private data class MethodParameterView(
        val type: ConeKotlinType,
        val isVararg: Boolean,
    )

    private enum class VariancePosition {
        INPUT,
        OUTPUT,
    }

    private val foreignAssemblies = assemblies
    private val metadata = foreignAssemblies.map(DotNetClrClasspathAssembly.WithoutCarrier::metadata)
    private val selectedAssemblyBinder = DotNetClrSelectedAssemblyBinder(metadata)
    private val typeResolver = DotNetClrTypeResolver(selectedAssemblyBinder)
    private val signatureResolver = DotNetClrSignatureResolver(typeResolver)
    private val genericParameterContextResolver = DotNetClrGenericParameterContextResolver(typeResolver)
    private val typeHierarchyResolver = DotNetClrTypeHierarchyViewResolver(typeResolver)
    private val annotationServices = ForeignAnnotationServices.create(metadata)
    private val candidates: Map<ClassId, Candidate> = buildCandidates()
    private val selectedHierarchies: List<DotNetClrResolvedTypeHierarchy> =
        candidates.values.map { candidate -> candidate.hierarchy }
    private val importedGraph = DotNetClrImportedDeclarationGraph(
        assemblies = buildList {
            candidates.values.forEach { candidate ->
                if (none { selected -> selected.metadata === candidate.assembly.metadata }) {
                    add(candidate.assembly)
                }
            }
            annotationServices.physicalCoreTypes?.let { coreTypes ->
                listOf(
                    coreTypes.systemValueType,
                    coreTypes.systemEnum,
                    coreTypes.systemNullable,
                ).forEach { type ->
                    val selectedAssembly = foreignAssemblies.single { assembly ->
                        assembly.metadata === type.assembly
                    }
                    if (none { selected -> selected.metadata === selectedAssembly.metadata }) {
                        add(selectedAssembly)
                    }
                }
            }
        },
        hierarchies = selectedHierarchies,
        physicalCoreTypes = annotationServices.physicalCoreTypes,
    )
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
                val classId = type.classIdOrNull(assembly.metadata) ?: continue
                val contract = type.completeSupportedContractOrNull(assembly.metadata) ?: continue
                candidatesById.getOrPut(classId, ::mutableListOf) +=
                    Candidate(
                        assembly,
                        type,
                        contract.hierarchy,
                        contract.genericContext,
                        contract.methods,
                        contract.properties,
                    )
            }
        }
        var selected: Map<ClassId, Candidate> = candidatesById.mapNotNull { entry ->
            entry.value.singleOrNull()?.let { candidate -> entry.key to candidate }
        }.toMap(linkedMapOf())
        while (true) {
            val supported = selected.filterValues { candidate ->
                val constraintsSupported = (candidate.genericContext.typeParameters.asSequence() +
                        candidate.methods.asSequence().flatMap { method ->
                            method.genericContext.methodParameters.asSequence()
                        }).all { binding ->
                        binding.constraints.all { constraint ->
                            val nominal = constraint.type as? DotNetClrResolvedGenericConstraintType.Nominal
                                ?: return@all true
                            val classId = nominal.type.definition.classIdOrNull(nominal.type.assembly)
                                ?: return@all false
                            val target = selected[classId] ?: return@all false
                            target.assembly.metadata === nominal.type.assembly &&
                                    target.type.handle == nominal.type.definition.handle
                        }
                }
                constraintsSupported && candidate.referencedTypes().all { referenced ->
                    val classId = referenced.definition.classIdOrNull(referenced.assembly)
                        ?: return@all false
                    val target = selected[classId] ?: return@all false
                    target.assembly.metadata === referenced.assembly &&
                            target.type.handle == referenced.definition.handle
                }
            }.toMap(linkedMapOf())
            if (supported.size != selected.size) {
                selected = supported
                continue
            }
            val cyclic = supported.inheritanceCycleMembers()
            if (cyclic.isEmpty()) return supported
            selected = supported.filterKeys { classId -> classId !in cyclic }
        }
    }

    private fun Candidate.referencedTypes(): Set<DotNetClrResolvedTypeDefinition> = buildSet {
        hierarchy.interfaces.forEach { implementation ->
            add(implementation.interfaceType.type)
            implementation.interfaceType.arguments.forEach { argument ->
                argument.collectReferencedTypesTo(this)
            }
        }
        methods.forEach { method ->
            method.signature.returnType.collectReferencedTypesTo(this)
            method.signature.parameterTypes.forEach { parameter ->
                parameter.collectReferencedTypesTo(this)
            }
        }
        properties.forEach { property ->
            property.getterSignature.returnType.collectReferencedTypesTo(this)
        }
    }

    private fun DotNetClrResolvedTypeSignature.collectReferencedTypesTo(
        destination: MutableSet<DotNetClrResolvedTypeDefinition>,
    ) {
        when (this) {
            is DotNetClrResolvedTypeSignature.Named -> destination += type
            is DotNetClrResolvedTypeSignature.GenericInstance -> {
                if (!isSystemNullable(annotationServices.physicalCoreTypes)) {
                    destination += genericType.type
                }
                arguments.forEach { argument -> argument.collectReferencedTypesTo(destination) }
            }
            is DotNetClrResolvedTypeSignature.SzArray ->
                elementType.collectReferencedTypesTo(destination)
            else -> Unit
        }
    }

    private fun Map<ClassId, Candidate>.inheritanceCycleMembers(): Set<ClassId> {
        val states = hashMapOf<ClassId, Int>()
        val stack = arrayListOf<ClassId>()
        val cyclic = linkedSetOf<ClassId>()

        fun visit(classId: ClassId) {
            when (states[classId]) {
                1 -> {
                    val cycleStart = stack.indexOf(classId)
                    if (cycleStart >= 0) cyclic += stack.subList(cycleStart, stack.size)
                    return
                }
                2 -> return
            }
            states[classId] = 1
            stack += classId
            val candidate = getValue(classId)
            for (implementation in candidate.hierarchy.interfaces) {
                val targetId = implementation.interfaceType.type.definition.classIdOrNull(
                    implementation.interfaceType.type.assembly,
                ) ?: continue
                if (targetId in this) visit(targetId)
            }
            stack.removeAt(stack.lastIndex)
            states[classId] = 2
        }

        keys.forEach(::visit)
        return cyclic
    }

    private fun DotNetClrTypeDefinition.classIdOrNull(
        assembly: DotNetClrAssemblyMetadata,
    ): ClassId? {
        val genericArity = assembly.genericParameterDefinitions.count { parameter ->
            parameter.owner == handle
        }
        val sourceName = metadataName.substringBefore('`')
        val expectedMetadataName = if (genericArity == 0) sourceName else "$sourceName`$genericArity"
        if (
            declaringType != null ||
            visibility != DotNetClrTypeVisibility.PUBLIC ||
            !isInterface ||
            !isAbstract ||
            isSealed ||
            baseType != null ||
            metadataName != expectedMetadataName ||
            sourceName == "<Module>" ||
            !Name.isValidIdentifier(sourceName)
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
        return ClassId(FqName(namespaceName), Name.identifier(sourceName))
    }

    private fun DotNetClrTypeDefinition.completeSupportedContractOrNull(
        assembly: DotNetClrAssemblyMetadata,
    ): CompleteContract? {
        if (
            assembly.typeDefinitions.any { nested -> nested.declaringType == handle } ||
            assembly.fieldDefinitions.any { field -> field.declaringType == handle }
        ) {
            return null
        }
        val typeParameters = assembly.genericParameterDefinitions
            .filter { parameter -> parameter.owner == handle }
            .sortedBy { parameter -> parameter.number }
        if (typeParameters.map { parameter -> parameter.number } != typeParameters.indices.toList()) {
            return null
        }
        val declaringView = DotNetClrResolvedTypeView(
            DotNetClrResolvedTypeDefinition(assembly, this),
            typeParameters.indices.map { index ->
                DotNetClrResolvedTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.TYPE,
                    index,
                )
            },
        )
        val hierarchy = when (val resolution = typeHierarchyResolver.resolve(declaringView)) {
            is DotNetClrTypeHierarchyViewResolution.Resolved -> resolution.hierarchy
            is DotNetClrTypeHierarchyViewResolution.Invalid -> return null
        }
        if (
            hierarchy.interfaces.distinctBy { implementation -> implementation.interfaceType }.size !=
            hierarchy.interfaces.size ||
            hierarchy.interfaces.any { implementation ->
                !implementation.hasSupportedInterfaceTarget()
            }
        ) {
            return null
        }
        val genericContext = when (
            val resolution = genericParameterContextResolver.resolve(declaringView)
        ) {
            is DotNetClrGenericParameterContextResolution.Resolved -> resolution.context
            is DotNetClrGenericParameterContextResolution.Invalid -> return null
        }
        if (hierarchy.interfaces.any { implementation ->
                !implementation.hasSupportedInterfaceView(assembly, genericContext)
            }
        ) {
            return null
        }
        if (!genericContext.hasSupportedOwnerParameterContract(assembly)) return null
        val properties = assembly.propertyDefinitions.filter { property ->
            property.declaringType == handle
        }
        if (
            properties.map(DotNetClrPropertyDefinition::name).distinct().size != properties.size
        ) {
            return null
        }
        val propertyCandidates = properties.map { property ->
            property.supportedPropertyOrNull(assembly, genericContext) ?: return null
        }
        val accessorHandles = propertyCandidates.flatMapTo(hashSetOf()) { property ->
            listOfNotNull(property.getter.handle, property.setter?.handle)
        }
        val publicMethods = assembly.methodDefinitions.filter { method ->
            method.declaringType == handle &&
                    method.visibility == DotNetClrMethodVisibility.PUBLIC
        }
        val ordinaryMethods = publicMethods.filterNot { method -> method.handle in accessorHandles }
        if (publicMethods.isEmpty()) return null
        val methodCandidates = ordinaryMethods.map { method ->
            method.supportedMethodOrNull(assembly, declaringView) ?: return null
        }
        val hasValidVariance = methodCandidates.all { candidate ->
            candidate.signature.returnType.hasSupportedOwnerVariance(
                genericContext,
                VariancePosition.OUTPUT,
            ) && candidate.signature.parameterTypes.all { parameterType ->
                parameterType.hasSupportedOwnerVariance(
                    genericContext,
                    VariancePosition.INPUT,
                )
            }
        } && propertyCandidates.all { candidate ->
            candidate.getterSignature.returnType.hasSupportedOwnerVariance(
                genericContext,
                VariancePosition.OUTPUT,
            ) && (candidate.setter == null ||
                    candidate.getterSignature.returnType.hasSupportedOwnerVariance(
                        genericContext,
                        VariancePosition.INPUT,
                    ))
        }
        if (!hasValidVariance) return null
        return CompleteContract(hierarchy, genericContext, methodCandidates, propertyCandidates)
    }

    private fun DotNetClrResolvedInterfaceImplementation.hasSupportedInterfaceTarget(): Boolean {
        val definition = interfaceType.type.definition
        return definition.isInterface &&
                definition.classIdOrNull(interfaceType.type.assembly) != null
    }

    private fun DotNetClrResolvedInterfaceImplementation.hasSupportedInterfaceView(
        assembly: DotNetClrAssemblyMetadata,
        genericContext: DotNetClrResolvedGenericParameterContext,
    ): Boolean {
        if (!hasSupportedInterfaceTarget()) return false
        val physicalType = interfaceType.toInterfaceSignature()
        if (!physicalType.isSupportedMethodType(genericContext, allowVoid = false)) {
            return false
        }
        val root = annotationServices.typeQualifiers(
            assembly,
            physicalType,
            DotNetClrNullableDeclarationTarget.InterfaceImplementation(row),
        ).firstOrNull() ?: return false
        return root.type == physicalType &&
                root.qualifier != DotNetClrKotlinNullabilityQualifier.NULLABLE
    }

    private fun DotNetClrResolvedTypeSignature.hasSupportedOwnerVariance(
        context: DotNetClrResolvedGenericParameterContext,
        position: VariancePosition,
    ): Boolean =
        when (this) {
            is DotNetClrResolvedTypeSignature.GenericParameter -> {
                if (kind == DotNetClrGenericParameterKind.METHOD) return true
                when (context.typeParameters.getOrNull(index)?.parameter?.variance) {
                    DotNetClrGenericParameterVariance.INVARIANT -> true
                    DotNetClrGenericParameterVariance.COVARIANT ->
                        position == VariancePosition.OUTPUT
                    DotNetClrGenericParameterVariance.CONTRAVARIANT ->
                        position == VariancePosition.INPUT
                    null -> false
                }
            }
            is DotNetClrResolvedTypeSignature.SzArray ->
                elementType.hasSupportedOwnerVariance(context, position)
            is DotNetClrResolvedTypeSignature.GenericInstance -> {
                if (isSystemNullable(annotationServices.physicalCoreTypes)) {
                    val element = arguments.singleOrNull()
                    return genericType.isValueType &&
                            element is DotNetClrResolvedTypeSignature.Primitive &&
                            element.type in NULLABLE_VALUE_PRIMITIVES
                }
                val parameters = genericType.type.assembly.genericParameterDefinitions
                    .filter { parameter -> parameter.owner == genericType.type.definition.handle }
                    .sortedBy { parameter -> parameter.number }
                parameters.size == arguments.size && arguments.indices.all { index ->
                    when (parameters[index].variance) {
                        DotNetClrGenericParameterVariance.INVARIANT ->
                            arguments[index].hasSupportedOwnerVariance(context, VariancePosition.INPUT) &&
                                    arguments[index].hasSupportedOwnerVariance(context, VariancePosition.OUTPUT)
                        DotNetClrGenericParameterVariance.COVARIANT ->
                            arguments[index].hasSupportedOwnerVariance(context, position)
                        DotNetClrGenericParameterVariance.CONTRAVARIANT ->
                            arguments[index].hasSupportedOwnerVariance(
                                context,
                                if (position == VariancePosition.INPUT) {
                                    VariancePosition.OUTPUT
                                } else {
                                    VariancePosition.INPUT
                                },
                            )
                    }
                }
            }
            else -> true
        }

    private fun DotNetClrMethodDefinition.supportedMethodOrNull(
        assembly: DotNetClrAssemblyMetadata,
        declaringView: DotNetClrResolvedTypeView,
    ): MethodCandidate? {
        if (
            !hasSupportedAbstractInstanceShape() ||
            isSpecialName ||
            isRuntimeSpecialName ||
            !Name.isValidIdentifier(name)
        ) {
            return null
        }
        val genericContext = when (
            val resolution = genericParameterContextResolver.resolve(declaringView, this)
        ) {
            is DotNetClrGenericParameterContextResolution.Resolved -> resolution.context
            is DotNetClrGenericParameterContextResolution.Invalid -> return null
        }.takeIf { context -> context.hasSupportedMethodParameterContract(assembly) }
            ?: return null
        val resolvedSignature = when (val resolution = signatureResolver.resolve(assembly, signature)) {
            is DotNetClrResolvedMethodSignatureResolution.Resolved -> resolution.signature
            is DotNetClrResolvedMethodSignatureResolution.Invalid,
            is DotNetClrResolvedMethodSignatureResolution.UnresolvedType,
            -> return null
        }
        if (!resolvedSignature.returnType.isSupportedMethodType(genericContext, allowVoid = true)) {
            return null
        }
        if (!hasSupportedGenericUseNullability(assembly, resolvedSignature)) return null
        val parametersSupported = signature.parameterTypes.withIndex().all { [index, type] ->
            val resolvedType = resolvedSignature.parameterTypes[index]
            val parameterRows = assembly.parameterDefinitions.filter { parameter ->
                parameter.declaringMethod == handle &&
                        !parameter.isReturn &&
                        parameter.parameterIndex == index
            }
            when (type) {
                is DotNetClrTypeSignature.Primitive ->
                    resolvedType.isSupportedMethodType(genericContext, allowVoid = false) &&
                            parameterRows.all { parameter ->
                                annotationServices.paramArray(assembly, parameter.handle) ===
                                        DotNetClrParamArrayMetadataResolution.Absent
                            }
                is DotNetClrTypeSignature.SzArray -> {
                    val elementSupportsParamArray = when (val element = type.elementType) {
                        is DotNetClrTypeSignature.Primitive -> element.type in REFERENCE_PRIMITIVES
                        is DotNetClrTypeSignature.GenericParameter ->
                            genericContext.binding(
                                DotNetClrResolvedTypeSignature.GenericParameter(
                                    element.kind,
                                    element.index,
                                )
                            ) != null
                        else -> false
                    }
                    if (!resolvedType.isSupportedMethodType(genericContext, allowVoid = false)) {
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
                                    elementSupportsParamArray &&
                                    paramArrayRows.singleOrNull() is
                                    DotNetClrParamArrayMetadataResolution.Decoded -> true
                            else -> false
                        }
                    }
                }
                is DotNetClrTypeSignature.GenericParameter ->
                    resolvedType.isSupportedMethodType(genericContext, allowVoid = false) &&
                            parameterRows.all { parameter ->
                                annotationServices.paramArray(assembly, parameter.handle) ===
                                        DotNetClrParamArrayMetadataResolution.Absent
                            }
                is DotNetClrTypeSignature.GenericInstance,
                is DotNetClrTypeSignature.Named,
                -> resolvedType.isSupportedMethodType(genericContext, allowVoid = false) &&
                        parameterRows.all { parameter ->
                            annotationServices.paramArray(assembly, parameter.handle) ===
                                    DotNetClrParamArrayMetadataResolution.Absent
                        }
                else -> false
            }
        }
        return MethodCandidate(this, resolvedSignature, genericContext).takeIf { parametersSupported }
    }

    private fun DotNetClrMethodDefinition.hasSupportedGenericUseNullability(
        assembly: DotNetClrAssemblyMetadata,
        resolvedSignature: DotNetClrResolvedMethodSignature,
    ): Boolean {
        fun DotNetClrResolvedTypeSignature.hasExactGenericUse(
            target: DotNetClrNullableDeclarationTarget,
        ): Boolean = annotationServices.typeQualifiers(assembly, this, target).none { component ->
            component.type is DotNetClrResolvedTypeSignature.GenericParameter &&
                    component.qualifier == DotNetClrKotlinNullabilityQualifier.NULLABLE
        }

        if (!resolvedSignature.returnType.hasExactGenericUse(
                DotNetClrNullableDeclarationTarget.MethodReturn(this),
            )
        ) {
            return false
        }
        return resolvedSignature.parameterTypes.withIndex().all { [index, type] ->
            type.hasExactGenericUse(DotNetClrNullableDeclarationTarget.MethodParameter(this, index))
        }
    }

    private fun DotNetClrResolvedGenericParameterContext.hasSupportedMethodParameterContract(
        assembly: DotNetClrAssemblyMetadata,
    ): Boolean =
        typeParameters.size == declaringType.arguments.size &&
                methodParameters.size == method?.signature?.genericParameterCount &&
                methodParameters.all { binding ->
                    val parameter = binding.parameter
                    parameter.variance == DotNetClrGenericParameterVariance.INVARIANT &&
                            !parameter.hasReferenceTypeConstraint &&
                            !parameter.hasNotNullableValueTypeConstraint &&
                            !parameter.hasDefaultConstructorConstraint &&
                            !parameter.allowsByRefLike &&
                            binding.hasSupportedKotlinBounds(this) &&
                            binding.constraints.all { constraint ->
                                annotationServices.genericConstraintQualifier(
                                    assembly,
                                    this,
                                    binding,
                                    constraint.row.handle,
                                ) != DotNetClrKotlinNullabilityQualifier.NULLABLE
                            }
                }

    private fun DotNetClrResolvedGenericParameterContext.hasSupportedOwnerParameterContract(
        assembly: DotNetClrAssemblyMetadata,
    ): Boolean =
        method == null &&
                methodParameters.isEmpty() &&
                typeParameters.size == declaringType.arguments.size &&
                typeParameters.all { binding ->
                    val parameter = binding.parameter
                    !parameter.hasReferenceTypeConstraint &&
                            !parameter.hasNotNullableValueTypeConstraint &&
                            !parameter.hasDefaultConstructorConstraint &&
                            !parameter.allowsByRefLike &&
                            binding.hasSupportedKotlinBounds(this) &&
                            binding.constraints.all { constraint ->
                                annotationServices.genericConstraintQualifier(
                                    assembly,
                                    this,
                                    binding,
                                    constraint.row.handle,
                                ) != DotNetClrKotlinNullabilityQualifier.NULLABLE
                            }
                }

    private fun DotNetClrResolvedGenericParameterContextBinding.hasSupportedKotlinBounds(
        context: DotNetClrResolvedGenericParameterContext,
    ): Boolean =
        constraints.all { constraint ->
            when (val type = constraint.type) {
                is DotNetClrResolvedGenericConstraintType.Nominal ->
                    type.type.definition.isInterface &&
                            type.type.definition.classIdOrNull(type.type.assembly) != null
                is DotNetClrResolvedGenericConstraintType.Specification ->
                    (type.type as? DotNetClrResolvedTypeSignature.GenericParameter)?.let { parameter ->
                        context.binding(parameter) != null
                    } == true
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
                signature.varargParameterStart == null

    private fun DotNetClrResolvedTypeSignature.isSupportedMethodType(
        context: DotNetClrResolvedGenericParameterContext?,
        allowVoid: Boolean,
    ): Boolean =
        when (this) {
            DotNetClrResolvedTypeSignature.Void -> allowVoid
            is DotNetClrResolvedTypeSignature.Primitive -> type in SUPPORTED_PRIMITIVES
            is DotNetClrResolvedTypeSignature.GenericParameter ->
                context?.binding(
                    DotNetClrResolvedTypeSignature.GenericParameter(kind, index)
                )?.parameter?.number == index
            is DotNetClrResolvedTypeSignature.SzArray -> when (val element = elementType) {
                is DotNetClrResolvedTypeSignature.Primitive -> element.type in ARRAY_ELEMENT_PRIMITIVES
                is DotNetClrResolvedTypeSignature.GenericParameter ->
                    element.isSupportedMethodType(context, allowVoid = false)
                else -> false
            }
            is DotNetClrResolvedTypeSignature.Named ->
                !isValueType &&
                        type.definition.isInterface &&
                        type.assembly.genericParameterDefinitions.none { parameter ->
                            parameter.owner == type.definition.handle
                        } &&
                        type.definition.classIdOrNull(type.assembly) != null
            is DotNetClrResolvedTypeSignature.GenericInstance -> {
                if (isSystemNullable(annotationServices.physicalCoreTypes)) {
                    val element = arguments.singleOrNull()
                    return genericType.isValueType &&
                            element is DotNetClrResolvedTypeSignature.Primitive &&
                            element.type in NULLABLE_VALUE_PRIMITIVES
                }
                val parameters = genericType.type.assembly.genericParameterDefinitions
                    .filter { parameter -> parameter.owner == genericType.type.definition.handle }
                    .sortedBy { parameter -> parameter.number }
                !genericType.isValueType &&
                        genericType.type.definition.isInterface &&
                        genericType.type.definition.classIdOrNull(genericType.type.assembly) != null &&
                        parameters.size == arguments.size &&
                        parameters.all { parameter ->
                            !parameter.hasReferenceTypeConstraint &&
                                    !parameter.hasNotNullableValueTypeConstraint &&
                                    !parameter.hasDefaultConstructorConstraint &&
                                    !parameter.allowsByRefLike &&
                                    genericType.type.assembly.genericParameterConstraints.none { constraint ->
                                        constraint.owner == parameter.handle
                                    }
                        } &&
                        arguments.all { argument ->
                            argument.isSupportedGenericArgument(context)
                        }
            }
            else -> false
        }

    private fun DotNetClrResolvedTypeSignature.isSupportedGenericArgument(
        context: DotNetClrResolvedGenericParameterContext?,
    ): Boolean = when (this) {
        is DotNetClrResolvedTypeSignature.Primitive -> type in SUPPORTED_PRIMITIVES
        is DotNetClrResolvedTypeSignature.GenericParameter ->
            isSupportedMethodType(context, allowVoid = false)
        is DotNetClrResolvedTypeSignature.SzArray,
        is DotNetClrResolvedTypeSignature.Named,
        is DotNetClrResolvedTypeSignature.GenericInstance,
        -> isSupportedMethodType(context, allowVoid = false)
        else -> false
    }

    private fun DotNetClrPropertyDefinition.supportedPropertyOrNull(
        assembly: DotNetClrAssemblyMetadata,
        genericContext: DotNetClrResolvedGenericParameterContext,
    ): PropertyCandidate? {
        if (
            !Name.isValidIdentifier(name) ||
            hasDefault ||
            !signature.hasThis ||
            signature.indexParameterTypes.isNotEmpty()
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
        val getterSignature = when (val resolution = signatureResolver.resolve(assembly, getter.signature)) {
            is DotNetClrResolvedMethodSignatureResolution.Resolved -> resolution.signature
            is DotNetClrResolvedMethodSignatureResolution.Invalid,
            is DotNetClrResolvedMethodSignatureResolution.UnresolvedType,
            -> return null
        }
        val setterSignature = setter?.let { physicalSetter ->
            when (val resolution = signatureResolver.resolve(assembly, physicalSetter.signature)) {
                is DotNetClrResolvedMethodSignatureResolution.Resolved -> resolution.signature
                is DotNetClrResolvedMethodSignatureResolution.Invalid,
                is DotNetClrResolvedMethodSignatureResolution.UnresolvedType,
                -> return null
            }
        }
        val propertyType = getterSignature.returnType
        if (!propertyType.isSupportedType(genericContext, allowVoid = false)) return null
        val hasExactGenericUse = annotationServices.typeQualifiers(
            assembly,
            propertyType,
            DotNetClrNullableDeclarationTarget.Property(this),
        ).none { component ->
            component.type is DotNetClrResolvedTypeSignature.GenericParameter &&
                    component.qualifier == DotNetClrKotlinNullabilityQualifier.NULLABLE
        }
        if (!hasExactGenericUse) return null
        return PropertyCandidate(
            this,
            getter,
            setter,
            getterSignature,
            setterSignature,
        )
    }

    private fun DotNetClrResolvedTypeSignature.isSupportedType(
        context: DotNetClrResolvedGenericParameterContext,
        allowVoid: Boolean,
    ): Boolean = isSupportedMethodType(context, allowVoid)

    private fun buildClass(
        classId: ClassId,
        candidate: Candidate,
    ): FirRegularClassSymbol {
        val classSymbol = FirRegularClassSymbol(classId)
        val ownerTypeParameterSymbols = List(candidate.genericContext.typeParameters.size) {
            FirTypeParameterSymbol()
        }
        val typeParameterSymbols = TypeParameterSymbols(ownerTypeParameterSymbols)
        val openReceiverType = classSymbol.constructType(
            ownerTypeParameterSymbols.map { symbol ->
                ConeTypeParameterType(symbol.toLookupTag(), isMarkedNullable = false)
            }.toTypedArray(),
        )
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
            candidate.hierarchy.interfaces.mapTo(superTypeRefs) { implementation ->
                buildResolvedTypeRef {
                    coneType = implementation.toKotlinSuperType(
                        candidate.assembly.metadata,
                        typeParameterSymbols,
                    )
                }
            }

            candidate.genericContext.typeParameters.forEachIndexed { index, binding ->
                typeParameters += buildTypeParameter {
                    resolvePhase = FirResolvePhase.ANALYZED_DEPENDENCIES
                    origin = FirDeclarationOrigin.Library
                    moduleData = this@DotNetClrFirSymbolProvider.moduleData
                    name = genericTypeParameterName(
                        candidate.genericContext.typeParameters,
                        index,
                        "T",
                    )
                    symbol = ownerTypeParameterSymbols[index]
                    containingDeclarationSymbol = classSymbol
                    variance = binding.parameter.variance.toKotlinVariance()
                    isReified = false
                    val resolvedBounds = binding.constraints.map { constraint ->
                        constraint.type.toKotlinBound(
                            candidate.assembly.metadata,
                            candidate.genericContext,
                            binding,
                            constraint.row.handle,
                            typeParameterSymbols,
                        )
                    }
                    if (resolvedBounds.isEmpty()) {
                        bounds += session.builtinTypes.nullableAnyType
                    } else {
                        resolvedBounds.mapTo(bounds) { bound ->
                            buildResolvedTypeRef { coneType = bound }
                        }
                    }
                }
            }

            for (method in candidate.methods) {
                declarations += buildMethod(
                    classId,
                    openReceiverType,
                    candidate,
                    method,
                    ownerTypeParameterSymbols,
                )
            }
            for (property in candidate.properties) {
                declarations += buildProperty(
                    classId,
                    openReceiverType,
                    candidate,
                    property,
                    typeParameterSymbols,
                )
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
        dispatchReceiverType: ConeClassLikeType,
        candidate: Candidate,
        candidateProperty: PropertyCandidate,
        typeParameterSymbols: TypeParameterSymbols,
    ): FirProperty = buildProperty {
        val assembly = candidate.assembly.metadata
        val physicalProperty = candidateProperty.property
        val propertyName = Name.identifier(physicalProperty.name)
        val propertySymbol = FirRegularPropertySymbol(CallableId(classId, propertyName))
        val propertyType = candidateProperty.getterSignature.returnType.toKotlinType(
            annotationServices,
            assembly,
            DotNetClrNullableDeclarationTarget.Property(physicalProperty),
            typeParameterSymbols,
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
        this.dispatchReceiverType = dispatchReceiverType
        name = propertyName
        isVar = candidateProperty.setter != null
        symbol = propertySymbol
        containerSource = DotNetClrImportedPropertySource(
            candidate.assembly,
            candidate.type,
            candidate.hierarchy,
            importedGraph,
            physicalProperty,
            candidateProperty.getter,
            candidateProperty.setter,
            candidateProperty.getterSignature,
            candidateProperty.setterSignature,
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
            this.dispatchReceiverType = dispatchReceiverType
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
                this.dispatchReceiverType = dispatchReceiverType
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
        dispatchReceiverType: ConeClassLikeType,
        candidate: Candidate,
        methodCandidate: MethodCandidate,
        ownerTypeParameterSymbols: List<FirTypeParameterSymbol>,
    ): FirNamedFunction = buildNamedFunction {
        val assembly = candidate.assembly.metadata
        val method = methodCandidate.method
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
            candidate.hierarchy,
            importedGraph,
            method,
            methodCandidate.signature,
        )
        val functionSymbol = FirNamedFunctionSymbol(CallableId(classId, name))
        symbol = functionSymbol
        this.dispatchReceiverType = dispatchReceiverType
        val methodTypeParameterSymbols = List(method.signature.genericParameterCount) {
            FirTypeParameterSymbol()
        }
        val typeParameterSymbols = TypeParameterSymbols(
            ownerTypeParameterSymbols,
            methodTypeParameterSymbols,
        )
        methodCandidate.genericContext.methodParameters.forEachIndexed { index, binding ->
            typeParameters += buildTypeParameter {
                resolvePhase = FirResolvePhase.ANALYZED_DEPENDENCIES
                origin = FirDeclarationOrigin.Library
                moduleData = this@DotNetClrFirSymbolProvider.moduleData
                name = genericTypeParameterName(
                    methodCandidate.genericContext.methodParameters,
                    index,
                    "P",
                )
                symbol = methodTypeParameterSymbols[index]
                containingDeclarationSymbol = functionSymbol
                variance = Variance.INVARIANT
                isReified = false
                val resolvedBounds = binding.constraints.map { constraint ->
                    constraint.type.toKotlinBound(
                        assembly,
                        methodCandidate.genericContext,
                        binding,
                        constraint.row.handle,
                        typeParameterSymbols,
                    )
                }
                if (resolvedBounds.isEmpty()) {
                    bounds += session.builtinTypes.nullableAnyType
                } else {
                    resolvedBounds.mapTo(bounds) { bound ->
                        buildResolvedTypeRef { coneType = bound }
                    }
                }
            }
        }
        returnTypeRef = buildResolvedTypeRef {
            coneType =
                if (annotationServices.returnsNothing(assembly, method)) {
                    session.builtinTypes.nothingType.coneType
                } else {
                    methodCandidate.signature.returnType.toKotlinType(
                        annotationServices,
                        assembly,
                        DotNetClrNullableDeclarationTarget.MethodReturn(method),
                        typeParameterSymbols,
                    )
                }
        }
        methodCandidate.signature.parameterTypes.forEachIndexed { index, type ->
            val parameterView = methodParameterView(
                assembly,
                method,
                index,
                type,
                typeParameterSymbols,
            )
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
        type: DotNetClrResolvedTypeSignature,
        typeParameterSymbols: TypeParameterSymbols,
    ): MethodParameterView =
        when (type) {
            is DotNetClrResolvedTypeSignature.SzArray -> {
                val element = type.elementType
                val target = DotNetClrNullableDeclarationTarget.MethodParameter(method, index)
                val isVararg = assembly.parameterDefinitions.singleOrNull { parameter ->
                    parameter.declaringMethod == method.handle &&
                            !parameter.isReturn &&
                            parameter.parameterIndex == index
                }?.let { parameter ->
                    annotationServices.paramArray(assembly, parameter.handle) is
                            DotNetClrParamArrayMetadataResolution.Decoded
                } == true
                val qualifierCursor = TypeQualifierCursor(
                    annotationServices.typeQualifiers(assembly, type, target)
                )
                val arrayQualifier = qualifierCursor.consume(type)
                MethodParameterView(
                    element.toKotlinArrayType(
                        qualifierCursor,
                        arrayQualifier,
                        isVararg,
                        typeParameterSymbols,
                    ).also { qualifierCursor.requireExhausted() },
                    isVararg,
                )
            }
            else -> MethodParameterView(
                type.toKotlinType(
                    annotationServices,
                    assembly,
                    DotNetClrNullableDeclarationTarget.MethodParameter(method, index),
                    typeParameterSymbols,
                ),
                isVararg = false,
            )
        }

    private fun DotNetClrResolvedTypeSignature.toKotlinType(
        annotationServices: ForeignAnnotationServices,
        assembly: DotNetClrAssemblyMetadata,
        target: DotNetClrNullableDeclarationTarget,
        typeParameterSymbols: TypeParameterSymbols,
    ): ConeKotlinType {
        val qualifiers = TypeQualifierCursor(
            annotationServices.typeQualifiers(assembly, this, target)
        )
        return toKotlinType(qualifiers, typeParameterSymbols).also {
            qualifiers.requireExhausted()
        }
    }

    private fun DotNetClrResolvedInterfaceImplementation.toKotlinSuperType(
        assembly: DotNetClrAssemblyMetadata,
        typeParameterSymbols: TypeParameterSymbols,
    ): ConeClassLikeType {
        val physicalType = interfaceType.toInterfaceSignature()
        val qualifiers = TypeQualifierCursor(
            annotationServices.typeQualifiers(
                assembly,
                physicalType,
                DotNetClrNullableDeclarationTarget.InterfaceImplementation(row),
            )
        )
        check(qualifiers.consume(physicalType) != DotNetClrKotlinNullabilityQualifier.NULLABLE) {
            "Nullable root entered an imported CLR InterfaceImpl supertype"
        }
        val classId = interfaceType.type.definition.classIdOrNull(interfaceType.type.assembly)
            ?: error("Unsupported inherited CLR interface entered FIR")
        val arguments = interfaceType.arguments.map { argument ->
            argument.toKotlinType(
                qualifiers,
                typeParameterSymbols,
                keepObliviousTypeParametersRigid = true,
            )
        }.toTypedArray()
        return classId.toLookupTag().constructClassType(arguments).also {
            qualifiers.requireExhausted()
        }
    }

    private fun DotNetClrResolvedTypeView.toInterfaceSignature(): DotNetClrResolvedTypeSignature =
        if (arguments.isEmpty()) {
            DotNetClrResolvedTypeSignature.Named(type, isValueType = false)
        } else {
            DotNetClrResolvedTypeSignature.GenericInstance(
                DotNetClrResolvedTypeSignature.Named(type, isValueType = false),
                arguments,
            )
        }

    private inner class TypeQualifierCursor(
        private val components: List<DotNetClrKotlinNullabilityComponent>,
    ) {
        private var index: Int = 0

        fun consume(type: DotNetClrResolvedTypeSignature): DotNetClrKotlinNullabilityQualifier {
            val component = components.getOrNull(index++)
                ?: error("Missing nullable component for supported CLR type $type")
            check(component.type == type) {
                "Misaligned nullable component for supported CLR type $type: ${component.type}"
            }
            return component.qualifier
        }

        fun requireExhausted() {
            check(index == components.size) {
                "Unused nullable components for supported CLR type: ${components.size - index}"
            }
        }
    }

    private fun genericTypeParameterName(
        bindings: List<DotNetClrResolvedGenericParameterContextBinding>,
        index: Int,
        fallbackPrefix: String,
    ): Name {
        val metadataNames = bindings.map { binding -> binding.parameter.name }
        val mayRetainMetadataNames =
            metadataNames.all(Name::isValidIdentifier) && metadataNames.distinct().size == metadataNames.size
        return Name.identifier(if (mayRetainMetadataNames) metadataNames[index] else "$fallbackPrefix$index")
    }

    private fun DotNetClrGenericParameterVariance.toKotlinVariance(): Variance =
        when (this) {
            DotNetClrGenericParameterVariance.INVARIANT -> Variance.INVARIANT
            DotNetClrGenericParameterVariance.COVARIANT -> Variance.OUT_VARIANCE
            DotNetClrGenericParameterVariance.CONTRAVARIANT -> Variance.IN_VARIANCE
        }

    private fun DotNetClrResolvedGenericConstraintType.toKotlinBound(
        assembly: DotNetClrAssemblyMetadata,
        context: DotNetClrResolvedGenericParameterContext,
        binding: DotNetClrResolvedGenericParameterContextBinding,
        constraintHandle: DotNetClrMetadataHandle,
        typeParameterSymbols: TypeParameterSymbols,
    ): ConeKotlinType {
        val qualifier = annotationServices.genericConstraintQualifier(
            assembly,
            context,
            binding,
            constraintHandle,
        )
        return when (this) {
            is DotNetClrResolvedGenericConstraintType.Nominal -> {
                val classId = type.definition.classIdOrNull(type.assembly)
                    ?: error("Unsupported nominal bound entered the foreign CLR FIR slice")
                classId.toLookupTag().constructClassType().withQualifier(qualifier)
            }
            is DotNetClrResolvedGenericConstraintType.Specification -> {
                val parameter = type as? DotNetClrResolvedTypeSignature.GenericParameter
                    ?: error("Unsupported structural bound entered the foreign CLR FIR slice")
                val symbol = typeParameterSymbols.symbol(parameter.kind, parameter.index)
                    ?: error(
                        "Foreign CLR bound references missing ${parameter.kind.name.lowercase()} " +
                                "parameter ${parameter.index}"
                    )
                ConeTypeParameterType(symbol.toLookupTag(), isMarkedNullable = false)
                    .withQualifier(qualifier)
            }
        }
    }

    private fun DotNetClrResolvedTypeSignature.toKotlinArrayType(
        qualifiers: TypeQualifierCursor,
        arrayQualifier: DotNetClrKotlinNullabilityQualifier,
        isVararg: Boolean,
        typeParameterSymbols: TypeParameterSymbols,
        keepObliviousTypeParametersRigid: Boolean = false,
    ): ConeKotlinType {
        val elementType = toKotlinType(
            qualifiers,
            typeParameterSymbols,
            keepObliviousTypeParametersRigid,
        )
        val outArray = ConeKotlinTypeProjectionOut(elementType).createArrayType(
            nullable = false,
            createPrimitiveArrayTypeIfPossible = false,
        )
        if (isVararg) return outArray.withQualifier(arrayQualifier)

        val invariantArray = elementType.createArrayType(
            nullable = false,
            createPrimitiveArrayTypeIfPossible = false,
        )
        val lowerNullable = arrayQualifier == DotNetClrKotlinNullabilityQualifier.NULLABLE
        val upperNullable = arrayQualifier != DotNetClrKotlinNullabilityQualifier.NOT_NULL
        return ConeFlexibleType(
            invariantArray.withNullability(lowerNullable, session.typeContext),
            outArray.withNullability(upperNullable, session.typeContext),
            isTrivial = false,
        )
    }

    private fun buildDeprecatedAnnotation(
        obsolete: DotNetClrObsoleteMetadataResolution.Decoded,
        useSiteTarget: AnnotationUseSiteTarget? = null,
    ): FirAnnotation = buildAnnotation {
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

    private fun DotNetClrResolvedTypeSignature.toKotlinType(
        qualifiers: TypeQualifierCursor,
        typeParameterSymbols: TypeParameterSymbols,
        keepObliviousTypeParametersRigid: Boolean = false,
    ): ConeKotlinType =
        when (this) {
            DotNetClrResolvedTypeSignature.Void -> session.builtinTypes.unitType.coneType
            is DotNetClrResolvedTypeSignature.Primitive -> when (type) {
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
                    session.builtinTypes.stringType.coneType.withQualifier(qualifiers.consume(this))
                DotNetClrPrimitiveType.OBJECT ->
                    session.builtinTypes.anyType.coneType.withQualifier(qualifiers.consume(this))
                DotNetClrPrimitiveType.NATIVE_INT,
                DotNetClrPrimitiveType.NATIVE_UINT,
                -> error("Native integers are outside the closed foreign CLR FIR slice")
            }
            is DotNetClrResolvedTypeSignature.GenericParameter -> {
                val symbol = typeParameterSymbols.symbol(kind, index)
                    ?: error("Foreign CLR ${kind.name.lowercase()} parameter $index has no FIR symbol")
                val qualifier = qualifiers.consume(this).let { qualifier ->
                    // An oblivious InterfaceImpl use is physical `T`, not `T!`: the owner
                    // construction supplies T's eventual nullability. Only an explicit 2 means T?.
                    if (
                        keepObliviousTypeParametersRigid &&
                        qualifier == DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY
                    ) {
                        DotNetClrKotlinNullabilityQualifier.NOT_NULL
                    } else {
                        qualifier
                    }
                }
                ConeTypeParameterType(symbol.toLookupTag(), isMarkedNullable = false)
                    .withQualifier(qualifier)
            }
            is DotNetClrResolvedTypeSignature.SzArray -> {
                val arrayQualifier = qualifiers.consume(this)
                elementType.toKotlinArrayType(
                    qualifiers,
                    arrayQualifier,
                    isVararg = false,
                    typeParameterSymbols,
                    keepObliviousTypeParametersRigid,
                )
            }
            is DotNetClrResolvedTypeSignature.Named -> {
                val classId = type.definition.classIdOrNull(type.assembly)
                    ?: error("Unsupported named CLR type entered FIR")
                classId.toLookupTag().constructClassType()
                    .withQualifier(qualifiers.consume(this))
            }
            is DotNetClrResolvedTypeSignature.GenericInstance -> {
                if (isSystemNullable(annotationServices.physicalCoreTypes)) {
                    val element = arguments.singleOrNull()
                        ?: error("Selected System.Nullable<T> entered FIR with invalid arity")
                    val kotlinElement = element.toKotlinType(
                        qualifiers,
                        typeParameterSymbols,
                        keepObliviousTypeParametersRigid,
                    )
                    val rigidElement = kotlinElement as? ConeRigidType
                        ?: error("Selected System.Nullable<T> entered FIR with a flexible element")
                    rigidElement.withNullability(nullable = true, session.typeContext)
                } else {
                    val classId = genericType.type.definition.classIdOrNull(genericType.type.assembly)
                        ?: error("Unsupported constructed CLR type entered FIR")
                    val qualifier = qualifiers.consume(this)
                    val arguments = arguments.map { argument ->
                        argument.toKotlinType(
                            qualifiers,
                            typeParameterSymbols,
                            keepObliviousTypeParametersRigid,
                        )
                    }.toTypedArray()
                    classId.toLookupTag().constructClassType(arguments)
                        .withQualifier(qualifier)
                }
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
        val physicalCoreTypes: DotNetClrPhysicalTypeCoreTypes?,
        private val declarationResolver: DotNetClrNullableDeclarationResolver?,
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

        fun typeQualifiers(
            assembly: DotNetClrAssemblyMetadata,
            type: DotNetClrResolvedTypeSignature,
            target: DotNetClrNullableDeclarationTarget,
        ): List<DotNetClrKotlinNullabilityComponent> {
            val fallback = type.obliviousComponents()
            val resolver = declarationResolver
            val applicator = evidenceApplicator
            val projected = if (resolver == null || applicator == null) {
                fallback
            } else {
                when (
                    val projection = projector.project(
                        applicator.apply(type, resolver.resolve(assembly, target))
                    )
                ) {
                    is DotNetClrKotlinNullabilityProjection.Projected ->
                        projection.components.takeIf { components ->
                            components.map { component -> component.type } ==
                                    fallback.map { component -> component.type }
                        } ?: fallback
                    is DotNetClrKotlinNullabilityProjection.Oblivious,
                    is DotNetClrKotlinNullabilityProjection.Suppressed,
                    is DotNetClrKotlinNullabilityProjection.DiagnosticFallback,
                    -> fallback
                }
            }
            if (projected.firstOrNull()?.type != type) return projected
            val root = projected.first()
            val enhanced = when (target) {
                is DotNetClrNullableDeclarationTarget.MethodParameter ->
                    inputQualifier(assembly, target.method, target.index, root.qualifier)
                is DotNetClrNullableDeclarationTarget.MethodReturn ->
                    returnQualifier(assembly, target.method, root.qualifier)
                is DotNetClrNullableDeclarationTarget.Property -> root.qualifier
                is DotNetClrNullableDeclarationTarget.InterfaceImplementation -> root.qualifier
                else -> error("Unsupported foreign declaration target $target")
            }
            return if (enhanced == root.qualifier) {
                projected
            } else {
                listOf(root.copy(qualifier = enhanced)) + projected.drop(1)
            }
        }

        private fun DotNetClrResolvedTypeSignature.obliviousComponents():
                List<DotNetClrKotlinNullabilityComponent> = buildList {
            fun collect(type: DotNetClrResolvedTypeSignature) {
                val consumes = when (type) {
                    is DotNetClrResolvedTypeSignature.Primitive ->
                        type.type == DotNetClrPrimitiveType.STRING ||
                                type.type == DotNetClrPrimitiveType.OBJECT
                    is DotNetClrResolvedTypeSignature.GenericParameter,
                    is DotNetClrResolvedTypeSignature.SzArray,
                    is DotNetClrResolvedTypeSignature.Named,
                    -> true
                    is DotNetClrResolvedTypeSignature.GenericInstance ->
                        !type.isSystemNullable(physicalCoreTypes)
                    else -> false
                }
                if (consumes) {
                    add(
                        DotNetClrKotlinNullabilityComponent(
                            DotNetClrNullableTypeComponent(
                                type,
                                org.jetbrains.kotlin.load.dotnet.DotNetClrNullableAnnotation.OBLIVIOUS,
                                org.jetbrains.kotlin.load.dotnet.DotNetClrNullableTypeComponentKind.NULLABILITY,
                            ),
                            DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY,
                        )
                    )
                }
                when (type) {
                    is DotNetClrResolvedTypeSignature.SzArray -> collect(type.elementType)
                    is DotNetClrResolvedTypeSignature.GenericInstance ->
                        type.arguments.forEach(::collect)
                    else -> Unit
                }
            }
            collect(this@obliviousComponents)
        }

        fun genericConstraintQualifier(
            assembly: DotNetClrAssemblyMetadata,
            context: DotNetClrResolvedGenericParameterContext,
            binding: DotNetClrResolvedGenericParameterContextBinding,
            constraintHandle: DotNetClrMetadataHandle,
        ): DotNetClrKotlinNullabilityQualifier {
            val resolver = declarationResolver
                ?: return DotNetClrKotlinNullabilityQualifier.NOT_NULL
            val applicator = evidenceApplicator
                ?: return DotNetClrKotlinNullabilityQualifier.NOT_NULL
            val resolution = DotNetClrNullableGenericParameterEvidenceResolver(
                resolver,
                applicator,
            ).resolve(
                assembly,
                context,
                DotNetClrResolvedTypeSignature.GenericParameter(
                    binding.kind,
                    binding.parameter.number,
                ),
            )
            val evidence = (resolution as? DotNetClrNullableGenericParameterEvidenceResolution.Resolved)
                ?.evidence
                ?: return DotNetClrKotlinNullabilityQualifier.NOT_NULL
            val constraint = evidence.constraints.singleOrNull { candidate ->
                candidate.constraint.row.handle == constraintHandle
            } ?: return DotNetClrKotlinNullabilityQualifier.NOT_NULL
            return when (val projection = projector.project(constraint.application)) {
                is DotNetClrKotlinNullabilityProjection.Projected ->
                    when (projection.components.singleOrNull()?.qualifier) {
                        DotNetClrKotlinNullabilityQualifier.NULLABLE ->
                            DotNetClrKotlinNullabilityQualifier.NULLABLE
                        DotNetClrKotlinNullabilityQualifier.NOT_NULL,
                        DotNetClrKotlinNullabilityQualifier.FORCE_FLEXIBILITY,
                        null,
                        -> DotNetClrKotlinNullabilityQualifier.NOT_NULL
                    }
                is DotNetClrKotlinNullabilityProjection.Oblivious,
                is DotNetClrKotlinNullabilityProjection.Suppressed,
                is DotNetClrKotlinNullabilityProjection.DiagnosticFallback,
                -> DotNetClrKotlinNullabilityQualifier.NOT_NULL
            }
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
                val binder = DotNetClrSelectedAssemblyBinder(assemblies)
                val typeResolver = DotNetClrTypeResolver(binder)
                val coreTypes = resolveDotNetClrCustomAttributeCoreTypes(assemblies, typeResolver)
                if (coreTypes == null) {
                    return ForeignAnnotationServices(
                        physicalCoreTypes = null,
                        declarationResolver = null,
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
                    resolveDotNetClrSystemType(assemblies, typeResolver, "System", "ValueType")
                        ?: return unavailable(
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
                    resolveDotNetClrSystemType(assemblies, typeResolver, "System", "Nullable`1")
                        ?: return unavailable(
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
                val physicalCoreTypes = DotNetClrPhysicalTypeCoreTypes(
                    systemValueType = systemValueType,
                    systemEnum = coreTypes.systemEnum,
                    systemNullable = systemNullable,
                )
                val declarationResolver = DotNetClrNullableDeclarationResolver(
                    DotNetClrNullableMetadataDecoder(customAttributeDecoder)
                )
                return ForeignAnnotationServices(
                    physicalCoreTypes = physicalCoreTypes,
                    declarationResolver = declarationResolver,
                    evidenceApplicator = DotNetClrNullableEvidenceApplicator(
                        DotNetClrNullableTypeTransformApplicator(
                            DotNetClrPhysicalTypeClassifier(
                                typeResolver,
                                physicalCoreTypes,
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
                    physicalCoreTypes = null,
                    declarationResolver = null,
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
            DotNetClrPrimitiveType.INT16,
            DotNetClrPrimitiveType.INT32,
            DotNetClrPrimitiveType.INT64,
            DotNetClrPrimitiveType.FLOAT32,
            DotNetClrPrimitiveType.FLOAT64,
            DotNetClrPrimitiveType.STRING,
            DotNetClrPrimitiveType.OBJECT,
        )

        val NULLABLE_VALUE_PRIMITIVES = setOf(
            DotNetClrPrimitiveType.BOOLEAN,
            DotNetClrPrimitiveType.CHAR,
            DotNetClrPrimitiveType.INT8,
            DotNetClrPrimitiveType.INT16,
            DotNetClrPrimitiveType.INT32,
            DotNetClrPrimitiveType.INT64,
            DotNetClrPrimitiveType.FLOAT32,
            DotNetClrPrimitiveType.FLOAT64,
        )
    }
}

private fun DotNetClrResolvedTypeSignature.GenericInstance.isSystemNullable(
    coreTypes: DotNetClrPhysicalTypeCoreTypes?,
): Boolean = coreTypes?.systemNullable?.let(genericType.type::hasSameIdentityAs) == true
