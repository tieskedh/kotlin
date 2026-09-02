/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import java.io.File
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyMetadata
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterKind
import org.jetbrains.kotlin.load.dotnet.DotNetClrMemberReferenceSignature
import org.jetbrains.kotlin.load.dotnet.DotNetClrMetadataHandle
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodBody
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodImplementation
import org.jetbrains.kotlin.load.dotnet.DotNetClrMetadataReader
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureCallingConvention
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature
import org.jetbrains.kotlin.load.dotnet.DotNetManagedAssemblyIdentity

private data class DotNetBoundPeTypeDef(
    val recorded: DotNetProducerGenericOwnerSealedTypeDef,
    val actual: DotNetClrTypeDefinition,
)

private data class DotNetBoundPeMethodDef(
    val recorded: DotNetProducerGenericOwnerSealedMethodDef,
    val actual: DotNetClrMethodDefinition,
)

/** Exact objective PE rows authenticated for one producer-recorded bounded `J` family. */
internal class DotNetGenericOwnerSealedFamilyMetadataBinding internal constructor(
    internal val assembly: DotNetClrAssemblyMetadata,
    internal val declaration: DotNetPhysicalDeclaration.GenericOwnerSealedFamily,
    internal val authority: DotNetProducerGenericOwnerSealedFamilyAuthority,
    internal val typeDefinitions:
        Map<DotNetProducerGenericOwnerSealedTypeDefRole, DotNetClrTypeDefinition>,
    internal val methodDefinitions:
        Map<DotNetProducerGenericOwnerSealedMethodDefRole, DotNetClrMethodDefinition>,
    internal val methodImplementations:
        Map<DotNetProducerGenericOwnerSealedMethodImplRole, DotNetClrMethodImplementation>,
) {
    val familyIndexKey: String
        get() = declaration.indexKey()

    /** Only these concrete MethodDefs may be selected to authenticate the referenced `K`. */
    val semanticEquivalenceMethodBodies: Set<DotNetClrMetadataHandle> = setOf(
        methodDefinitions.getValue(
            DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
        ).handle,
        methodDefinitions.getValue(
            DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
        ).handle,
    )
}

/** One `K` whose complete `J` family and exact forwarding bodies matched one read-local PE image. */
internal class DotNetGenericOwnerSemanticEquivalenceMetadataBinding internal constructor(
    internal val declaration:
        DotNetPhysicalDeclaration.GenericOwnerSemanticEquivalenceCertificate,
    internal val family: DotNetGenericOwnerSealedFamilyMetadataBinding,
    internal val authority: DotNetProducerGenericOwnerSemanticEquivalenceAuthority,
) {
    internal val certificateIndexKey: String
        get() = declaration.indexKey()
}

internal data class DotNetPeValidatedGenericOwnerCertificate(
    val certificate: DotNetPhysicalDeclaration.GenericOwnerSemanticEquivalenceCertificate,
    val family: DotNetPhysicalDeclaration.GenericOwnerSealedFamily,
)

private sealed interface DotNetGenericOwnerPeLogicalAuthority {
    data class InterfaceMember(val logicalMemberKey: String) :
        DotNetGenericOwnerPeLogicalAuthority

    data class ImplementationMember(
        val logicalOwnerKey: String,
        val logicalMemberKey: String,
    ) : DotNetGenericOwnerPeLogicalAuthority

    data class Family(val key: DotNetProducerGenericOwnerSealedFamilyKey) :
        DotNetGenericOwnerPeLogicalAuthority
}

private data class DotNetGenericOwnerPeMethodDefAuthority(
    val role: DotNetProducerGenericOwnerSealedMethodDefRole,
    val logical: DotNetGenericOwnerPeLogicalAuthority,
)

private data class DotNetGenericOwnerPeMethodImplAuthority(
    val role: DotNetProducerGenericOwnerSealedMethodImplRole,
    val logical: DotNetGenericOwnerPeLogicalAuthority,
)

private data class DotNetGenericOwnerPeRowClaim<Authority>(
    val authority: Authority,
    val handle: DotNetClrMetadataHandle,
)

internal data class DotNetGenericOwnerPeMethodDefRoleClaim(
    val familyKey: DotNetProducerGenericOwnerSealedFamilyKey,
    val role: DotNetProducerGenericOwnerSealedMethodDefRole,
    val handle: DotNetClrMetadataHandle,
)

internal data class DotNetGenericOwnerPeMethodImplRoleClaim(
    val familyKey: DotNetProducerGenericOwnerSealedFamilyKey,
    val role: DotNetProducerGenericOwnerSealedMethodImplRole,
    val handle: DotNetClrMetadataHandle,
)

/**
 * Opaque ephemeral selector over `K`/`J` declarations already authenticated against one DLL.
 *
 * The stamp is not serialized and never creates authority. The external index must rejoin every
 * entry to equal immutable declarations in the same library before it may expose the certificate.
 */
class DotNetGenericOwnerPeValidationStamp internal constructor(
    internal val assemblyIdentity: DotNetManagedAssemblyIdentity?,
    private val normalizedAssemblyFile: File?,
    entries: Map<String, DotNetPeValidatedGenericOwnerCertificate>,
) {
    private val entriesByCertificateIndexKey = entries.toMap()

    internal val certificateIndexKeys: Set<String>
        get() = entriesByCertificateIndexKey.keys

    internal fun authenticates(
        certificate: DotNetPhysicalDeclaration.GenericOwnerSemanticEquivalenceCertificate,
        family: DotNetPhysicalDeclaration.GenericOwnerSealedFamily,
    ): Boolean = entriesByCertificateIndexKey[certificate.indexKey()] ==
            DotNetPeValidatedGenericOwnerCertificate(certificate, family)

    internal fun belongsTo(assemblyFile: File): Boolean =
        normalizedAssemblyFile == assemblyFile.absoluteFile.normalize()

    init {
        require((assemblyIdentity == null) == entriesByCertificateIndexKey.isEmpty() &&
                (normalizedAssemblyFile == null) == entriesByCertificateIndexKey.isEmpty()
        ) {
            "a generic-owner PE-validation stamp is either empty or bound to one assembly"
        }
    }

    companion object {
        val EMPTY = DotNetGenericOwnerPeValidationStamp(null, null, emptyMap())
    }
}

class DotNetGenericOwnerPeValidationResult internal constructor(
    val assemblyMetadata: DotNetClrAssemblyMetadata,
    val stamp: DotNetGenericOwnerPeValidationStamp,
)

/**
 * Reads one immutable classpath DLL and authenticates all bounded `J`/`K` declarations against
 * the same open PE image.
 * This is the only cross-module stamp factory: a caller cannot associate supplied metadata from
 * one source with another file path.
 */
fun readAndValidateDotNetGenericOwnerPeMetadata(
    assemblyFile: File,
    sealedFamilies: Collection<DotNetPhysicalDeclaration.GenericOwnerSealedFamily>,
    semanticEquivalenceCertificates:
        Collection<DotNetPhysicalDeclaration.GenericOwnerSemanticEquivalenceCertificate>,
    coreLibraryAssemblyName: String,
): DotNetGenericOwnerPeValidationResult {
    val certificateFamilyKeys = semanticEquivalenceCertificates.mapTo(hashSetOf()) { certificate ->
        certificate.sealedFamilyIndexKey
    }
    val assembly = if (certificateFamilyKeys.isEmpty()) {
        DotNetClrMetadataReader.read(assemblyFile)
    } else {
        DotNetClrMetadataReader.readWithSelectedMethodBodies(assemblyFile) { headerMetadata ->
            sealedFamilies.filter { declaration ->
                declaration.indexKey() in certificateFamilyKeys
            }.flatMapTo(linkedSetOf()) { declaration ->
                validateDotNetGenericOwnerSealedFamilyAgainstClrMetadata(
                    declaration,
                    headerMetadata,
                    coreLibraryAssemblyName,
                ).semanticEquivalenceMethodBodies
            }
        }
    }
    val sealedBindings = sealedFamilies.map { declaration ->
        validateDotNetGenericOwnerSealedFamilyAgainstClrMetadata(
            declaration,
            assembly,
            coreLibraryAssemblyName,
        )
    }
    val sealedBindingsByIndexKey = sealedBindings.associateBy { binding -> binding.familyIndexKey }
    require(sealedBindingsByIndexKey.size == sealedBindings.size) {
        "generic-owner PE validation cannot repeat a J family"
    }
    val certificateBindings = semanticEquivalenceCertificates.map { declaration ->
        val family = sealedBindingsByIndexKey[declaration.sealedFamilyIndexKey]
            ?: throw IllegalArgumentException(
                "semantic-equivalence certificate for '${declaration.sealedFamilyIndexKey}' lacks its J family",
            )
        validateDotNetGenericOwnerSemanticEquivalenceCertificateAgainstClrMetadata(
            declaration,
            family,
            assembly,
        )
    }
    return DotNetGenericOwnerPeValidationResult(
        assembly,
        createDotNetGenericOwnerPeValidationStamp(
            assemblyFile,
            assembly,
            certificateBindings,
        ),
    )
}

internal fun createDotNetGenericOwnerPeValidationStamp(
    assemblyFile: File,
    assembly: DotNetClrAssemblyMetadata,
    bindings: Collection<DotNetGenericOwnerSemanticEquivalenceMetadataBinding>,
): DotNetGenericOwnerPeValidationStamp {
    if (bindings.isEmpty()) return DotNetGenericOwnerPeValidationStamp.EMPTY
    require(bindings.all { binding -> binding.family.assembly === assembly }) {
        "a generic-owner PE-validation stamp cannot combine metadata snapshots"
    }
    requireBijectiveGenericOwnerPeRoleClaims(bindings)
    val requiredMethodBodies = bindings.flatMapTo(linkedSetOf()) { binding ->
        binding.family.semanticEquivalenceMethodBodies
    }
    val selectedBodyCounts = assembly.methodBodies.groupingBy { body -> body.method }.eachCount()
    require(requiredMethodBodies.all { method -> selectedBodyCounts[method] == 1 }) {
        "a generic-owner PE-validation stamp requires each K-owned selected MethodDef body exactly once"
    }
    val entries = bindings.associate { binding ->
        binding.certificateIndexKey to DotNetPeValidatedGenericOwnerCertificate(
            binding.declaration,
            binding.family.declaration,
        )
    }
    require(entries.size == bindings.size) {
        "a generic-owner PE-validation stamp cannot repeat a certificate"
    }
    return DotNetGenericOwnerPeValidationStamp(
        assembly.identity,
        assemblyFile.absoluteFile.normalize(),
        entries,
    )
}

private fun requireBijectiveGenericOwnerPeRoleClaims(
    bindings: Collection<DotNetGenericOwnerSemanticEquivalenceMetadataBinding>,
) {
    val methodDefClaims = bindings.flatMap { binding ->
        val family = binding.family
        family.methodDefinitions.map { entry ->
            DotNetGenericOwnerPeMethodDefRoleClaim(
                family.authority.publication.key,
                entry.key,
                entry.value.handle,
            )
        }
    }
    val methodImplClaims = bindings.flatMap { binding ->
        val family = binding.family
        family.methodImplementations.map { entry ->
            DotNetGenericOwnerPeMethodImplRoleClaim(
                family.authority.publication.key,
                entry.key,
                entry.value.handle,
            )
        }
    }
    validateDotNetGenericOwnerPeRoleBijection(methodDefClaims, methodImplClaims)
}

internal fun validateDotNetGenericOwnerPeRoleBijection(
    methodDefClaims: Collection<DotNetGenericOwnerPeMethodDefRoleClaim>,
    methodImplClaims: Collection<DotNetGenericOwnerPeMethodImplRoleClaim>,
) {
    methodDefClaims.map { claim ->
        DotNetGenericOwnerPeRowClaim(
            DotNetGenericOwnerPeMethodDefAuthority(
                claim.role,
                claim.familyKey.logicalAuthority(claim.role),
            ),
            claim.handle,
        )
    }.requireBijectivePhysicalRows("MethodDef")
    methodImplClaims.map { claim ->
        DotNetGenericOwnerPeRowClaim(
            DotNetGenericOwnerPeMethodImplAuthority(
                claim.role,
                claim.familyKey.logicalAuthority(claim.role),
            ),
            claim.handle,
        )
    }.requireBijectivePhysicalRows("MethodImpl")
}

private fun DotNetProducerGenericOwnerSealedFamilyKey.logicalAuthority(
    role: DotNetProducerGenericOwnerSealedMethodDefRole,
): DotNetGenericOwnerPeLogicalAuthority {
    return when (role) {
        DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT,
        DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_SLOT,
        -> DotNetGenericOwnerPeLogicalAuthority.InterfaceMember(logicalInterfaceMemberKey)

        DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
        DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_SLOT,
        DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
        -> DotNetGenericOwnerPeLogicalAuthority.ImplementationMember(
            implementationOwnerKey,
            implementationMemberKey,
        )

        DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER ->
            DotNetGenericOwnerPeLogicalAuthority.Family(this)
    }
}

private fun DotNetProducerGenericOwnerSealedFamilyKey.logicalAuthority(
    role: DotNetProducerGenericOwnerSealedMethodImplRole,
): DotNetGenericOwnerPeLogicalAuthority {
    return when (role) {
        DotNetProducerGenericOwnerSealedMethodImplRole.CLASS_SEMANTIC_CAPABILITY_IMPLEMENTATION ->
            DotNetGenericOwnerPeLogicalAuthority.ImplementationMember(
                implementationOwnerKey,
                implementationMemberKey,
            )

        DotNetProducerGenericOwnerSealedMethodImplRole.INTERFACE_SEMANTIC_CAPABILITY_IMPLEMENTATION ->
            DotNetGenericOwnerPeLogicalAuthority.Family(this)
    }
}

private fun <Authority> List<DotNetGenericOwnerPeRowClaim<Authority>>.requireBijectivePhysicalRows(
    rowKind: String,
) {
    val splitAuthority = groupBy { claim -> claim.authority }.entries.firstOrNull { entry ->
        entry.value.mapTo(hashSetOf()) { claim -> claim.handle }.size != 1
    }
    require(splitAuthority == null) {
        "generic-owner PE validation maps one logical $rowKind role authority to multiple physical rows: " +
                splitAuthority
    }
    val aliasedRow = groupBy { claim -> claim.handle }.entries.firstOrNull { entry ->
        entry.value.mapTo(hashSetOf()) { claim -> claim.authority }.size != 1
    }
    require(aliasedRow == null) {
        "generic-owner PE validation aliases one physical $rowKind row across logical role authorities: " +
                aliasedRow
    }
}

/**
 * Authenticates every TypeDef, MethodDef, GenericParam, direct edge, and MethodImpl recorded by
 * one bounded `J`. Names select candidates only; complete recorded physical facts decide a match.
 */
internal fun validateDotNetGenericOwnerSealedFamilyAgainstClrMetadata(
    declaration: DotNetPhysicalDeclaration.GenericOwnerSealedFamily,
    assembly: DotNetClrAssemblyMetadata,
    coreLibraryAssemblyName: String,
): DotNetGenericOwnerSealedFamilyMetadataBinding {
    require(coreLibraryAssemblyName.isNotEmpty()) {
        "a producer-sealed J validation requires the selected core-library AssemblyRef"
    }
    val publication = declaration.publication()
    require(publication.key.physicalIndexKey() == declaration.indexKey()) {
        "a producer-sealed J declaration is not its canonical physical-library entry"
    }
    val authority = when (val inspection = inspectDotNetProducerGenericOwnerSealedFamily(publication)) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> inspection.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            throw IllegalArgumentException(inspection.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            throw IllegalArgumentException("a claimed producer-sealed J family is unavailable")
    }

    val recordedTypesByRole = publication.body.typeDefs.associateBy { type -> type.role }
    val boundTypesByRole = recordedTypesByRole.mapValues { entry ->
        val recorded = entry.value
        DotNetBoundPeTypeDef(recorded, assembly.requireTypeDefinition(recorded.row.physicalPath))
    }
    require(boundTypesByRole.values.map { bound -> bound.actual.handle }.toSet().size ==
            boundTypesByRole.size
    ) {
        "producer DLL '${assembly.identity.name}' aliases distinct J TypeDef roles to one row"
    }
    val boundTypesByKey = boundTypesByRole.values.associateBy { bound ->
        bound.recorded.row.structural.identityKey
    }
    require(boundTypesByKey.size == boundTypesByRole.size) {
        "producer DLL '${assembly.identity.name}' cannot bind duplicate J TypeDef keys"
    }
    boundTypesByRole.forEach { entry ->
        val role = entry.key
        val bound = entry.value
        assembly.requireTypeDefMatchesJRow(
            role,
            bound.recorded.row,
            bound.actual,
            boundTypesByKey,
            coreLibraryAssemblyName,
        )
    }

    val recordedMethodsByRole = publication.body.methodDefs.associateBy { method -> method.role }
    val boundMethodsByRole = recordedMethodsByRole.mapValues { entry ->
        val role = entry.key
        val recorded = entry.value
        DotNetBoundPeMethodDef(
            recorded,
            assembly.requireMethodDefMatchesJRow(
                role,
                recorded.row,
                boundTypesByRole.getValue(role.ownerRole).actual,
                boundTypesByKey,
            ),
        )
    }
    require(boundMethodsByRole.values.map { bound -> bound.actual.handle }.toSet().size ==
            boundMethodsByRole.size
    ) {
        "producer DLL '${assembly.identity.name}' aliases distinct J MethodDef roles to one row"
    }
    val boundMethodsByKey = boundMethodsByRole.values.associateBy { bound ->
        bound.recorded.row.structural.identityKey
    }
    require(boundMethodsByKey.size == boundMethodsByRole.size) {
        "producer DLL '${assembly.identity.name}' cannot bind duplicate J MethodDef keys"
    }

    val recordedMethodImplsByRole = publication.body.methodImpls.associateBy { row -> row.role }
    val boundMethodImplsByRole = recordedMethodImplsByRole.mapValues { entry ->
        val role = entry.key
        val recorded = entry.value
        assembly.requireMethodImplMatchesJRow(
            role,
            recorded.row,
            boundTypesByKey,
            boundMethodsByKey,
        )
    }
    require(boundMethodImplsByRole.values.map { implementation -> implementation.handle }.toSet().size ==
            boundMethodImplsByRole.size
    ) {
        "producer DLL '${assembly.identity.name}' aliases distinct J MethodImpl roles to one row"
    }
    val implementationType = boundTypesByRole.getValue(
        DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS,
    ).actual
    val boundTypeHandles = boundTypesByKey.values.mapTo(hashSetOf()) { bound -> bound.actual.handle }
    val boundMethods = boundMethodsByKey.values.map { bound -> bound.actual }
    val touchingMethodImpls = assembly.methodImplementations.filter { implementation ->
        implementation.implementingType == implementationType.handle &&
                (assembly.methodDefOrRefTouchesFamily(implementation.bodyMethod, boundMethods, boundTypeHandles) ||
                        assembly.methodDefOrRefTouchesFamily(
                            implementation.declarationMethod,
                            boundMethods,
                            boundTypeHandles,
                        ))
    }
    require(touchingMethodImpls.mapTo(hashSetOf()) { row -> row.handle } ==
            boundMethodImplsByRole.values.mapTo(hashSetOf()) { row -> row.handle }
    ) {
        "producer DLL '${assembly.identity.name}' has an extra or missing MethodImpl touching J endpoints"
    }

    return DotNetGenericOwnerSealedFamilyMetadataBinding(
        assembly,
        declaration,
        authority,
        boundTypesByRole.mapValues { entry -> entry.value.actual },
        boundMethodsByRole.mapValues { entry -> entry.value.actual },
        boundMethodImplsByRole,
    )
}

/** Authenticates `K` only after its complete `J` and selected CIL bodies are objective facts. */
internal fun validateDotNetGenericOwnerSemanticEquivalenceCertificateAgainstClrMetadata(
    declaration: DotNetPhysicalDeclaration.GenericOwnerSemanticEquivalenceCertificate,
    family: DotNetGenericOwnerSealedFamilyMetadataBinding,
    assembly: DotNetClrAssemblyMetadata,
): DotNetGenericOwnerSemanticEquivalenceMetadataBinding {
    require(family.assembly === assembly) {
        "a semantic-equivalence certificate cannot combine CLR metadata snapshots"
    }
    require(declaration.sealedFamilyIndexKey == family.declaration.indexKey()) {
        "a semantic-equivalence certificate is cross-wired to another producer-sealed J family"
    }
    val certificate = declaration.certificate()
    val authority = when (val inspection = inspectDotNetProducerGenericOwnerSemanticEquivalenceCertificate(
        certificate,
        family.declaration.indexKey(),
        family.authority.publication,
    )) {
        is DotNetGenericOwnerPhysicalBindingResult.Bound -> inspection.value
        is DotNetGenericOwnerPhysicalBindingResult.Conflict ->
            throw IllegalArgumentException(inspection.reason)
        DotNetGenericOwnerPhysicalBindingResult.Unavailable ->
            throw IllegalArgumentException("a claimed semantic-equivalence certificate is unavailable")
    }
    val duplicateBody = assembly.methodBodies.groupingBy { body -> body.method }.eachCount()
        .entries.firstOrNull { entry -> entry.value != 1 }
    require(duplicateBody == null) {
        "producer DLL '${assembly.identity.name}' contains duplicate selected MethodDef bodies"
    }
    val bodies = assembly.methodBodies.associateBy { body -> body.method }
    require(assembly.hasCompleteMethodSpecifications) {
        "producer DLL '${assembly.identity.name}' lacks the complete MethodSpec projection required by K"
    }
    require(family.semanticEquivalenceMethodBodies.all(bodies::containsKey)) {
        "producer DLL '${assembly.identity.name}' lacks one exact selected K forwarding body"
    }
    certificate.roleEdges.forEach { edge ->
        val source = family.methodDefinitions.getValue(edge.source)
        val target = family.methodDefinitions.getValue(edge.target)
        assembly.requirePureForwardingBody(
            body = bodies.getValue(source.handle),
            source = source,
            target = target,
            expectedOwner = family.typeDefinitions.getValue(
                DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS,
            ),
            boxesOwnerResult = edge.source ==
                    DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
        )
    }
    return DotNetGenericOwnerSemanticEquivalenceMetadataBinding(declaration, family, authority)
}

private val DotNetProducerGenericOwnerSealedMethodDefRole.ownerRole:
        DotNetProducerGenericOwnerSealedTypeDefRole
    get() = when (this) {
        DotNetProducerGenericOwnerSealedMethodDefRole.NATURAL_INTERFACE_SLOT ->
            DotNetProducerGenericOwnerSealedTypeDefRole.NATURAL_INTERFACE
        DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_SLOT ->
            DotNetProducerGenericOwnerSealedTypeDefRole.INTERFACE_SEMANTIC_CAPABILITY
        DotNetProducerGenericOwnerSealedMethodDefRole.IMPLEMENTATION_TYPED_ENTRY,
        DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_DISPATCHER,
        DotNetProducerGenericOwnerSealedMethodDefRole.INTERFACE_SEMANTIC_CAPABILITY_DISPATCHER,
        -> DotNetProducerGenericOwnerSealedTypeDefRole.IMPLEMENTATION_CLASS
        DotNetProducerGenericOwnerSealedMethodDefRole.CLASS_SEMANTIC_CAPABILITY_SLOT ->
            DotNetProducerGenericOwnerSealedTypeDefRole.CLASS_SEMANTIC_CAPABILITY
    }

private fun DotNetClrAssemblyMetadata.requireTypeDefMatchesJRow(
    role: DotNetProducerGenericOwnerSealedTypeDefRole,
    expected: DotNetGenericOwnerSealedEmissionTypeDefRow,
    actual: DotNetClrTypeDefinition,
    allTypesByKey: Map<DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey, DotNetBoundPeTypeDef>,
    coreLibraryAssemblyName: String,
) {
    val structural = expected.structural
    require(actual.attributes == expected.flags.toClrAttributes()) {
        "producer DLL '${identity.name}' has TypeDef flags which disagree with J role $role"
    }
    val parameters = requireContiguousGenericParameters(actual.handle, "TypeDef")
    require(parameters.size == structural.genericArity &&
            structural.genericParameters.size == parameters.size
    ) {
        "producer DLL '${identity.name}' has TypeDef GenericParam arity which disagrees with J role $role"
    }
    structural.genericParameters.zip(parameters).forEach { pair ->
        val expectedParameter = pair.first
        val actualParameter = pair.second
        require(actualParameter.attributes == expectedParameter.variance.toClrAttributes() &&
                exactCarrierMultisetMatches(
                    expectedParameter.constraints,
                    genericParameterConstraints.filter { constraint ->
                        constraint.owner == actualParameter.handle
                    }.map { constraint -> toPhysicalTypeSignature(constraint.constraint) },
                    structural.identityKey,
                    methodKey = null,
                    allTypesByKey,
                )) {
            "producer DLL '${identity.name}' has GenericParam rows which disagree with J role $role"
        }
    }

    val expectedBase = structural.directEdges.filter { edge ->
        edge.kind == DotNetGenericOwnerDirectSupertypeKind.BASE_CLASS
    }
    val expectedInterfaces = structural.directEdges.filter { edge ->
        edge.kind == DotNetGenericOwnerDirectSupertypeKind.INTERFACE
    }
    require(expectedBase.size <= 1 && (actual.baseType != null) == expectedBase.isNotEmpty()) {
        "producer DLL '${identity.name}' has BaseType cardinality which disagrees with J role $role"
    }
    expectedBase.singleOrNull()?.let { edge ->
        val baseType = checkNotNull(actual.baseType)
        val isCoreObject = edge.target == DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf(
            DotNetGenericOwnerPhysicalTypeKind.OBJECT,
        ) && matchesExternalTopLevelTypeReference(
            baseType,
            listOf("System", "Object"),
            expectedGenericArity = 0,
            expectedAssemblyName = coreLibraryAssemblyName,
        )
        require(isCoreObject || matchesCarrier(
            edge.target,
            toPhysicalTypeSignature(baseType),
            structural.identityKey,
            methodKey = null,
            allTypesByKey,
        )) {
            "producer DLL '${identity.name}' has a BaseType which disagrees with J role $role"
        }
    }
    val actualInterfaces = interfaceImplementations
        .filter { implementation -> implementation.implementingType == actual.handle }
        .map { implementation -> toPhysicalTypeSignature(implementation.interfaceType) }
    require(exactCarrierMultisetMatches(
        expectedInterfaces.map { edge -> edge.target },
        actualInterfaces,
        structural.identityKey,
        methodKey = null,
        allTypesByKey,
    )) {
        "producer DLL '${identity.name}' has InterfaceImpl rows which disagree with J role $role"
    }
}

private fun DotNetClrAssemblyMetadata.requireMethodDefMatchesJRow(
    role: DotNetProducerGenericOwnerSealedMethodDefRole,
    expected: DotNetGenericOwnerSealedEmissionMethodDefRow,
    owner: DotNetClrTypeDefinition,
    allTypesByKey: Map<DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey, DotNetBoundPeTypeDef>,
): DotNetClrMethodDefinition {
    val structural = expected.structural
    val named = methodDefinitions.filter { method ->
        method.declaringType == owner.handle && method.name == expected.physicalName
    }
    require(named.isNotEmpty()) {
        "producer DLL '${identity.name}' has no same-name MethodDef for J role $role"
    }
    val matches = named.filter { actual ->
        methodSignatureMatchesJHeader(
            actual.signature,
            structural.header,
            structural.identityKey,
            allTypesByKey,
        ) &&
                parameterRowsMatchResultLayout(actual, structural.header.result)
    }
    require(matches.size == 1) {
        "producer DLL '${identity.name}' cannot uniquely bind the full MethodDef signature for J role $role"
    }
    val actual = matches.single()
    require(actual.attributes == expected.toClrAttributes() &&
            actual.implementationAttributes == 0 &&
            (actual.relativeVirtualAddress == 0L) == actual.isAbstract
    ) {
        "producer DLL '${identity.name}' has MethodDef flags/body presence which disagree with J role $role"
    }
    val genericParameters = requireContiguousGenericParameters(actual.handle, "MethodDef")
    require(genericParameters.size == structural.header.genericArity &&
            genericParameters.map { parameter -> parameter.name } == expected.physicalGenericParameterNames
    ) {
        "producer DLL '${identity.name}' has MethodDef GenericParam rows which disagree with J role $role"
    }
    structural.genericParameters.zip(genericParameters).forEach { pair ->
        val expectedParameter = pair.first
        val actualParameter = pair.second
        require(actualParameter.attributes == expectedParameter.variance.toClrAttributes() &&
                exactCarrierMultisetMatches(
                    expectedParameter.constraints,
                    genericParameterConstraints.filter { constraint ->
                        constraint.owner == actualParameter.handle
                    }.map { constraint -> toPhysicalTypeSignature(constraint.constraint) },
                    structural.header.owner,
                    structural.identityKey,
                    allTypesByKey,
                )) {
            "producer DLL '${identity.name}' has MethodDef GenericParam constraints which disagree with J role $role"
        }
    }
    return actual
}

private fun DotNetClrAssemblyMetadata.methodSignatureMatchesJHeader(
    actual: org.jetbrains.kotlin.load.dotnet.DotNetClrMethodSignature,
    expected: DotNetGenericOwnerPhysicalMethodDefEmissionHeaderShape,
    methodKey: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey,
    allTypesByKey: Map<DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey, DotNetBoundPeTypeDef>,
): Boolean {
    if (actual.callingConvention != DotNetClrSignatureCallingConvention.DEFAULT ||
        actual.hasThis != expected.isInstance || actual.hasExplicitThis ||
        actual.varargParameterStart != null || actual.genericParameterCount != expected.genericArity
    ) return false
    val expectedOrdinaryParameters = expected.ordinaryParameterCarriers.map { carrier ->
        carrier.toClrSignature(expected.owner, methodKey, allTypesByKey) ?: return false
    }
    val expectedResult = when (val result = expected.result) {
        DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Void -> DotNetClrTypeSignature.Void
        is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.Direct ->
            result.carrier.toClrSignature(expected.owner, methodKey, allTypesByKey) ?: return false
        is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable ->
            result.payload.toClrSignature(expected.owner, methodKey, allTypesByKey) ?: return false
    }
    val expectedParameters = if (
        expected.result is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable
    ) {
        expectedOrdinaryParameters + DotNetClrTypeSignature.ByReference(
            DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.BOOLEAN),
        )
    } else {
        expectedOrdinaryParameters
    }
    return canonicalizeExactLocalTypeReferences(actual.returnType) ==
            canonicalizeExactLocalTypeReferences(expectedResult) &&
            actual.parameterTypes.map(::canonicalizeExactLocalTypeReferences) ==
            expectedParameters.map(::canonicalizeExactLocalTypeReferences)
}

private fun DotNetClrAssemblyMetadata.parameterRowsMatchResultLayout(
    method: DotNetClrMethodDefinition,
    result: DotNetGenericOwnerPhysicalMethodDefEmissionResultShape,
): Boolean {
    val rows = parameterDefinitions.filter { parameter -> parameter.declaringMethod == method.handle }
    if (rows.map { parameter -> parameter.sequence }.toSet().size != rows.size ||
        rows.any { parameter -> parameter.sequence !in 0..method.signature.parameterTypes.size }
    ) return false
    val splitTailSequence = if (
        result is DotNetGenericOwnerPhysicalMethodDefEmissionResultShape.SplitNullable
    ) {
        method.signature.parameterTypes.size
    } else {
        null
    }
    return rows.all { parameter ->
        parameter.attributes == if (parameter.sequence == splitTailSequence) {
            OUT_PARAMETER_ATTRIBUTE
        } else {
            0
        }
    } && (splitTailSequence == null || rows.any { parameter ->
        parameter.sequence == splitTailSequence
    })
}

private fun DotNetClrAssemblyMetadata.requireMethodImplMatchesJRow(
    role: DotNetProducerGenericOwnerSealedMethodImplRole,
    expected: DotNetGenericOwnerCompleteEmissionMethodImplRow,
    allTypesByKey: Map<DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey, DotNetBoundPeTypeDef>,
    allMethodsByKey: Map<DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey, DotNetBoundPeMethodDef>,
): DotNetClrMethodImplementation {
    val implementingType = allTypesByKey.getValue(expected.implementingTypeDefKey).actual
    val body = allMethodsByKey.getValue(expected.bodyMethodDefKey).actual
    val declaration = allMethodsByKey.getValue(expected.declarationMethodDefKey).actual
    val openImplementingOwner = allTypesByKey.getValue(expected.implementingTypeDefKey)
        .openTypeSignature()
    val declarationOwner = expected.declarationOwner.toClrSignature(
        expected.implementingTypeDefKey,
        expected.bodyMethodDefKey,
        allTypesByKey,
    ) ?: throw IllegalArgumentException("J MethodImpl $role has an unsupported declaration owner")
    val matches = methodImplementations.filter { implementation ->
        implementation.implementingType == implementingType.handle &&
                methodDefOrRefNamesExactMethod(
                    implementation.bodyMethod,
                    body,
                    openImplementingOwner,
                ) && methodDefOrRefNamesExactMethod(
                    implementation.declarationMethod,
                    declaration,
                    declarationOwner,
                )
    }
    require(matches.size == 1) {
        "producer DLL '${identity.name}' cannot uniquely bind MethodImpl row for J role $role"
    }
    return matches.single()
}

private fun DotNetBoundPeTypeDef.openTypeSignature(): DotNetClrTypeSignature {
    val named = DotNetClrTypeSignature.Named(
        actual.handle,
        recorded.row.structural.category == DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
    )
    val arity = recorded.row.structural.genericArity
    return if (arity == 0) named else DotNetClrTypeSignature.GenericInstance(
        named,
        List(arity) { index ->
            DotNetClrTypeSignature.GenericParameter(DotNetClrGenericParameterKind.TYPE, index)
        },
    )
}

private fun DotNetClrAssemblyMetadata.methodDefOrRefNamesExactMethod(
    handle: DotNetClrMetadataHandle,
    expected: DotNetClrMethodDefinition,
    expectedOwnerSignature: DotNetClrTypeSignature,
): Boolean = when (handle.table) {
    METHOD_DEF_TABLE -> handle == expected.handle
    MEMBER_REF_TABLE -> {
        val reference = memberReferences.singleOrNull { candidate -> candidate.handle == handle }
            ?: return false
        val signature = (reference.signature as? DotNetClrMemberReferenceSignature.Method)?.signature
            ?: return false
        val parentNamesExpectedOwner =
            resolveExactLocalTypeDefinition(reference.parent) == expected.declaringType ||
                    toPhysicalTypeSignature(reference.parent)
                        .let(::canonicalizeExactLocalTypeReferences) ==
                    canonicalizeExactLocalTypeReferences(expectedOwnerSignature)
        reference.name == expected.name &&
                methodSignaturesMatchModuloExactLocalTypeReferences(signature, expected.signature) &&
                parentNamesExpectedOwner
    }
    else -> false
}

private fun DotNetClrAssemblyMetadata.methodDefOrRefTouchesFamily(
    handle: DotNetClrMetadataHandle,
    methods: List<DotNetClrMethodDefinition>,
    typeHandles: Set<DotNetClrMetadataHandle>,
): Boolean = when (handle.table) {
    METHOD_DEF_TABLE -> methods.any { method -> method.handle == handle }
    MEMBER_REF_TABLE -> {
        val reference = memberReferences.singleOrNull { candidate -> candidate.handle == handle }
            ?: return false
        val signature = (reference.signature as? DotNetClrMemberReferenceSignature.Method)?.signature
            ?: return false
        val parentDefinition = localTypeDefinitionFromTypeDefOrSpec(reference.parent)
            ?: return false
        parentDefinition in typeHandles && methods.any { method ->
            reference.name == method.name &&
                    methodSignaturesMatchModuloExactLocalTypeReferences(signature, method.signature)
        }
    }
    else -> false
}

private fun DotNetClrAssemblyMetadata.localTypeDefinitionFromTypeDefOrSpec(
    handle: DotNetClrMetadataHandle,
): DotNetClrMetadataHandle? = when (handle.table) {
    TYPE_DEF_TABLE, TYPE_REF_TABLE -> resolveExactLocalTypeDefinition(handle)
    TYPE_SPEC_TABLE -> {
        val signature = typeSpecifications.singleOrNull { specification ->
            specification.handle == handle
        }?.signature ?: return null
        when (val canonical = canonicalizeExactLocalTypeReferences(signature)) {
            is DotNetClrTypeSignature.Named -> canonical.type
            is DotNetClrTypeSignature.GenericInstance -> canonical.genericType.type
            else -> null
        }
    }
    else -> null
}

private fun DotNetClrAssemblyMetadata.requirePureForwardingBody(
    body: DotNetClrMethodBody,
    source: DotNetClrMethodDefinition,
    target: DotNetClrMethodDefinition,
    expectedOwner: DotNetClrTypeDefinition,
    boxesOwnerResult: Boolean,
) {
    require(body.method == source.handle &&
            (body.isTiny || body.headerSize == STANDARD_FAT_METHOD_HEADER_SIZE) &&
            body.localVariableSignature == null && !body.hasExtraSections &&
            body.maxStack >= source.signature.parameterTypes.size + 1
    ) {
        "producer DLL '${identity.name}' has a non-pure method header for K source ${source.name}: " +
                "tiny=${body.isTiny}, headerSize=${body.headerSize}, maxStack=${body.maxStack}, " +
                "expectedStack=${source.signature.parameterTypes.size + 1}, " +
                "initLocals=${body.initLocals}, localSignature=${body.localVariableSignature}, " +
                "extraSections=${body.hasExtraSections}"
    }
    val cursor = CilCursor(body.code.toUnsignedIntList())
    require(cursor.readArgumentIndex() == 0 &&
            source.signature.parameterTypes.indices.all { index ->
                cursor.readArgumentIndex() == index + 1
            }
    ) {
        "producer DLL '${identity.name}' has a K forwarding body which changes argument flow"
    }
    require(cursor.readByte() in setOf(CIL_CALL, CIL_CALLVIRT)) {
        "producer DLL '${identity.name}' has a K forwarding body without the exact call/callvirt grammar"
    }
    val callToken = cursor.readMetadataHandle()
    require(callToken != null && callTokenNamesExactInstantiation(
        callToken,
        target,
        expectedOwner,
        source.signature.genericParameterCount,
    )) {
        "producer DLL '${identity.name}' has a K forwarding body targeting another MethodDef"
    }
    if (boxesOwnerResult) {
        require(cursor.readByte() == CIL_BOX) {
            "producer DLL '${identity.name}' has a K class dispatcher without the owner-result box"
        }
        val boxToken = cursor.readMetadataHandle()
        require(boxToken != null && boxToken.table == TYPE_SPEC_TABLE &&
                typeSpecifications.singleOrNull { specification ->
                    specification.handle == boxToken
                }?.signature == DotNetClrTypeSignature.GenericParameter(
                    DotNetClrGenericParameterKind.TYPE,
                    0,
                )) {
            "producer DLL '${identity.name}' has a K class dispatcher boxing another carrier"
        }
    }
    require(cursor.readByte() == CIL_RET && cursor.atEnd) {
        "producer DLL '${identity.name}' has extra or missing CIL in a K forwarding body"
    }
}

private fun DotNetClrAssemblyMetadata.callTokenNamesExactInstantiation(
    token: DotNetClrMetadataHandle,
    expected: DotNetClrMethodDefinition,
    expectedOwner: DotNetClrTypeDefinition,
    methodGenericArity: Int,
): Boolean {
    val method: DotNetClrMetadataHandle
    val arguments: List<DotNetClrTypeSignature>
    when {
        methodGenericArity == 0 && token.table in setOf(METHOD_DEF_TABLE, MEMBER_REF_TABLE) -> {
            method = token
            arguments = emptyList()
        }
        methodGenericArity > 0 && token.table == METHOD_SPEC_TABLE -> {
            val specification = methodSpecifications.singleOrNull { candidate ->
                candidate.handle == token
            } ?: return false
            method = specification.method
            arguments = specification.typeArguments
        }
        else -> return false
    }
    if (arguments.size != methodGenericArity || arguments.indices.any { index ->
            arguments[index] != DotNetClrTypeSignature.GenericParameter(
                DotNetClrGenericParameterKind.METHOD,
                index,
            )
        }) return false
    val owner = DotNetClrTypeSignature.GenericInstance(
        DotNetClrTypeSignature.Named(expectedOwner.handle, isValueType = false),
        listOf(DotNetClrTypeSignature.GenericParameter(DotNetClrGenericParameterKind.TYPE, 0)),
    )
    return methodDefOrRefNamesExactMethod(method, expected, owner)
}

private class CilCursor(private val bytes: List<Int>) {
    private var position = 0

    val atEnd: Boolean
        get() = position == bytes.size

    fun readByte(): Int? = bytes.getOrNull(position++)

    fun readArgumentIndex(): Int? = when (val opcode = readByte()) {
        CIL_LDARG_0, CIL_LDARG_1, CIL_LDARG_2, CIL_LDARG_3 -> opcode - CIL_LDARG_0
        CIL_LDARG_S -> readByte()
        CIL_TWO_BYTE_PREFIX -> if (readByte() == CIL_LDARG) readUnsignedShort() else null
        else -> null
    }

    fun readMetadataHandle(): DotNetClrMetadataHandle? {
        val token = readUnsignedInt() ?: return null
        val table = token ushr 24
        val row = token and 0x00ff_ffff
        return if (table !in 0 until 64 || row == 0) null else DotNetClrMetadataHandle(table, row)
    }

    private fun readUnsignedShort(): Int? {
        val first = readByte() ?: return null
        val second = readByte() ?: return null
        return first or (second shl 8)
    }

    private fun readUnsignedInt(): Int? {
        var result = 0
        repeat(4) { index ->
            val byte = readByte() ?: return null
            result = result or (byte shl (index * 8))
        }
        return result
    }
}

private fun DotNetClrAssemblyMetadata.exactCarrierMultisetMatches(
    expected: List<DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape>,
    actual: List<DotNetClrTypeSignature>,
    ownerKey: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
    methodKey: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey?,
    allTypesByKey: Map<DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey, DotNetBoundPeTypeDef>,
): Boolean {
    if (expected.size != actual.size) return false
    val unmatched = actual.toMutableList()
    return expected.all { expectedCarrier ->
        val index = unmatched.indexOfFirst { actualCarrier ->
            matchesCarrier(expectedCarrier, actualCarrier, ownerKey, methodKey, allTypesByKey)
        }
        if (index < 0) false else {
            unmatched.removeAt(index)
            true
        }
    }
}

private fun DotNetClrAssemblyMetadata.matchesCarrier(
    expected: DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape,
    actual: DotNetClrTypeSignature,
    ownerKey: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
    methodKey: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey?,
    allTypesByKey: Map<DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey, DotNetBoundPeTypeDef>,
): Boolean {
    val expectedSignature = expected.toClrSignature(ownerKey, methodKey, allTypesByKey) ?: return false
    return canonicalizeExactLocalTypeReferences(actual) ==
            canonicalizeExactLocalTypeReferences(expectedSignature)
}

private fun DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.toClrSignature(
    currentOwnerKey: DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey,
    currentMethodKey: DotNetGenericOwnerPhysicalMethodDefEmissionMethodKey?,
    allTypesByKey: Map<DotNetGenericOwnerPhysicalMethodDefEmissionTypeKey, DotNetBoundPeTypeDef>,
): DotNetClrTypeSignature? {
    return when (this) {
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Leaf -> when (kind) {
            DotNetGenericOwnerPhysicalTypeKind.VOID -> DotNetClrTypeSignature.Void
            DotNetGenericOwnerPhysicalTypeKind.BOOLEAN ->
                DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.BOOLEAN)
            DotNetGenericOwnerPhysicalTypeKind.INT32 ->
                DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.INT32)
            DotNetGenericOwnerPhysicalTypeKind.STRING ->
                DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.STRING)
            DotNetGenericOwnerPhysicalTypeKind.OBJECT ->
                DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.OBJECT)
            else -> null
        }
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.OwnerParameter ->
            if (binder == currentOwnerKey) {
                DotNetClrTypeSignature.GenericParameter(DotNetClrGenericParameterKind.TYPE, index)
            } else null
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.MethodParameter ->
            if (binder == currentMethodKey) {
                DotNetClrTypeSignature.GenericParameter(DotNetClrGenericParameterKind.METHOD, index)
            } else null
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Construction -> {
            val bound = allTypesByKey[definition] ?: return null
            val arguments = arguments.map { argument ->
                argument.toClrSignature(currentOwnerKey, currentMethodKey, allTypesByKey)
                    ?: return null
            }
            if (arguments.size != bound.recorded.row.structural.genericArity) return null
            val named = DotNetClrTypeSignature.Named(
                bound.actual.handle,
                bound.recorded.row.structural.category ==
                        DotNetGenericOwnerPhysicalNamedTypeCategory.VALUE_TYPE,
            )
            if (arguments.isEmpty()) named else DotNetClrTypeSignature.GenericInstance(named, arguments)
        }
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.SzArray ->
            element.toClrSignature(currentOwnerKey, currentMethodKey, allTypesByKey)?.let(
                DotNetClrTypeSignature::SzArray,
            )
        is DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.ByReference ->
            element.toClrSignature(currentOwnerKey, currentMethodKey, allTypesByKey)?.let(
                DotNetClrTypeSignature::ByReference,
            )
        DotNetGenericOwnerPhysicalMethodDefEmissionCarrierShape.Other -> null
    }
}

private fun DotNetClrAssemblyMetadata.toPhysicalTypeSignature(
    handle: DotNetClrMetadataHandle,
): DotNetClrTypeSignature = typeSpecifications.singleOrNull { specification ->
    specification.handle == handle
}?.signature ?: DotNetClrTypeSignature.Named(handle, isValueType = false)

private fun DotNetIlRawTypeDefFlags.toClrAttributes(): Long =
    visibility.toClrAttributes() or
            (if (isInterface) INTERFACE_TYPE_ATTRIBUTE else 0L) or
            (if (isAbstract) ABSTRACT_TYPE_ATTRIBUTE else 0L) or
            (if (isSealed) SEALED_TYPE_ATTRIBUTE else 0L) or
            (if (isBeforeFieldInit) BEFORE_FIELD_INIT_TYPE_ATTRIBUTE else 0L)

private fun DotNetIlRawTypeDefVisibility.toClrAttributes(): Long = when (this) {
    DotNetIlRawTypeDefVisibility.NOT_PUBLIC -> 0L
    DotNetIlRawTypeDefVisibility.PUBLIC -> 1L
    DotNetIlRawTypeDefVisibility.NESTED_PUBLIC -> 2L
    DotNetIlRawTypeDefVisibility.NESTED_PRIVATE -> 3L
    DotNetIlRawTypeDefVisibility.NESTED_FAMILY -> 4L
    DotNetIlRawTypeDefVisibility.NESTED_ASSEMBLY -> 5L
}

private fun DotNetGenericOwnerSealedEmissionMethodDefRow.toClrAttributes(): Int =
    visibility.toClrAttributes() or
            (if (!dispatch.isInstance) STATIC_METHOD_ATTRIBUTE else 0) or
            (if (dispatch.isFinal) FINAL_METHOD_ATTRIBUTE else 0) or
            (if (dispatch.isVirtual) VIRTUAL_METHOD_ATTRIBUTE else 0) or
            (if (isHideBySig) HIDE_BY_SIG_METHOD_ATTRIBUTE else 0) or
            (if (dispatch.isNewSlot) NEW_SLOT_METHOD_ATTRIBUTE else 0) or
            (if (dispatch.isAbstract) ABSTRACT_METHOD_ATTRIBUTE else 0) or
            (if (isSpecialName) SPECIAL_NAME_METHOD_ATTRIBUTE else 0) or
            (if (isRuntimeSpecialName) RUNTIME_SPECIAL_NAME_METHOD_ATTRIBUTE else 0)

private fun DotNetIlRawMethodDefVisibility.toClrAttributes(): Int = when (this) {
    DotNetIlRawMethodDefVisibility.PRIVATE -> 1
    DotNetIlRawMethodDefVisibility.FAMILY_AND_ASSEMBLY -> 2
    DotNetIlRawMethodDefVisibility.ASSEMBLY -> 3
    DotNetIlRawMethodDefVisibility.FAMILY -> 4
    DotNetIlRawMethodDefVisibility.FAMILY_OR_ASSEMBLY -> 5
    DotNetIlRawMethodDefVisibility.PUBLIC -> 6
}

private fun DotNetGenericOwnerPhysicalTypeParameterVariance.toClrAttributes(): Int = when (this) {
    DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT -> 0
    DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT -> 1
    DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT -> 2
}

private const val TYPE_REF_TABLE = 1
private const val TYPE_DEF_TABLE = 2
private const val METHOD_DEF_TABLE = 6
private const val MEMBER_REF_TABLE = 10
private const val TYPE_SPEC_TABLE = 27
private const val METHOD_SPEC_TABLE = 43
private const val OUT_PARAMETER_ATTRIBUTE = 0x0002
private const val INTERFACE_TYPE_ATTRIBUTE = 0x0020L
private const val ABSTRACT_TYPE_ATTRIBUTE = 0x0080L
private const val SEALED_TYPE_ATTRIBUTE = 0x0100L
private const val BEFORE_FIELD_INIT_TYPE_ATTRIBUTE = 0x0010_0000L
private const val STATIC_METHOD_ATTRIBUTE = 0x0010
private const val FINAL_METHOD_ATTRIBUTE = 0x0020
private const val VIRTUAL_METHOD_ATTRIBUTE = 0x0040
private const val HIDE_BY_SIG_METHOD_ATTRIBUTE = 0x0080
private const val NEW_SLOT_METHOD_ATTRIBUTE = 0x0100
private const val ABSTRACT_METHOD_ATTRIBUTE = 0x0400
private const val SPECIAL_NAME_METHOD_ATTRIBUTE = 0x0800
private const val RUNTIME_SPECIAL_NAME_METHOD_ATTRIBUTE = 0x1000
private const val STANDARD_FAT_METHOD_HEADER_SIZE = 12
private const val CIL_LDARG_0 = 0x02
private const val CIL_LDARG_1 = 0x03
private const val CIL_LDARG_2 = 0x04
private const val CIL_LDARG_3 = 0x05
private const val CIL_LDARG_S = 0x0e
private const val CIL_RET = 0x2a
private const val CIL_BOX = 0x8c
private const val CIL_CALLVIRT = 0x6f
private const val CIL_CALL = 0x28
private const val CIL_TWO_BYTE_PREFIX = 0xfe
private const val CIL_LDARG = 0x09
