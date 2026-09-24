/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.config.DotNetTarget
import org.jetbrains.kotlin.load.dotnet.DotNetClrAssemblyMetadata
import org.jetbrains.kotlin.load.dotnet.DotNetClrGenericParameterDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrMethodVisibility
import org.jetbrains.kotlin.load.dotnet.DotNetClrPrimitiveType
import org.jetbrains.kotlin.load.dotnet.DotNetClrSignatureCallingConvention
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeDefinition
import org.jetbrains.kotlin.load.dotnet.DotNetClrTypeSignature

/** The two physical endpoints authenticated for one producer-recorded alternate input entry `Q`. */
data class DotNetGenericOwnerFunctionInputEntryMetadataBinding(
    val logicalFunctionKey: String,
    val declaringType: DotNetClrTypeDefinition,
    val sourceMethodDefinition: DotNetClrMethodDefinition,
    val inputMethodDefinition: DotNetClrMethodDefinition,
)

/**
 * Authenticates `Q` against its source `F` and both actual MethodDef headers. The selected inputs
 * become object; every other parameter, method constraint, and (unless independently recorded)
 * result retains the source's exact physical signature. This is an ABI check, not a proof of body
 * equivalence or a reconstruction of the logical Kotlin signature. The producer-recorded source
 * signature selects the exact overload before any input is replaced by object.
 */
fun validateDotNetGenericOwnerFunctionInputEntryAgainstClrMetadata(
    declaration: DotNetPhysicalDeclaration.GenericOwnerFunctionInputEntry,
    sourceDeclaration: DotNetPhysicalDeclaration.Function,
    assembly: DotNetClrAssemblyMetadata,
    producerTarget: DotNetTarget,
): DotNetGenericOwnerFunctionInputEntryMetadataBinding {
    require(sourceDeclaration.ownerPath == declaration.ownerPath &&
            sourceDeclaration.isInstance == declaration.isInstance &&
            sourceDeclaration.methodGenericParameterCount == declaration.methodGenericParameterCount &&
            sourceDeclaration.methodName != declaration.methodName &&
            sourceDeclaration.methodName != ".ctor"
    ) {
        "input entry '${declaration.logicalFunctionKey}' is joined to an incompatible source F"
    }
    val owner = assembly.requireTypeDefinition(declaration.ownerPath)
    fun matchesEndpoint(method: DotNetClrMethodDefinition, name: String): Boolean =
        method.declaringType == owner.handle && method.name == name &&
                method.isStatic != declaration.isInstance &&
                method.visibility == DotNetClrMethodVisibility.PUBLIC &&
                !method.isAbstract && !method.isSpecialName && !method.isRuntimeSpecialName &&
                method.signature.callingConvention == DotNetClrSignatureCallingConvention.DEFAULT &&
                method.signature.hasThis == declaration.isInstance &&
                !method.signature.hasExplicitThis && method.signature.varargParameterStart == null &&
                method.signature.genericParameterCount == declaration.methodGenericParameterCount &&
                assembly.requireContiguousGenericParameters(method.handle, "MethodDef").size ==
                declaration.methodGenericParameterCount

    val inputMethod = assembly.methodDefinitions.filter { method ->
        matchesEndpoint(method, declaration.methodName) && !method.isVirtual
    }.singleOrNull() ?: throw IllegalArgumentException(
        "producer DLL '${assembly.identity.name}' does not contain exactly one normal input MethodDef " +
                "'${declaration.ownerPath.renderPhysicalPath()}/${declaration.methodName}'",
    )
    // Q uses the backend parameter vector, whose first slot is the receiver on an instance
    // method. A CLR MethodDef signature encodes that receiver in hasThis, not parameterTypes.
    val receiverOffset = if (declaration.isInstance) 1 else 0
    require(declaration.objectParameterIndices.all { index ->
        index >= receiverOffset && index - receiverOffset in inputMethod.signature.parameterTypes.indices
    }) {
        "input entry '${declaration.logicalFunctionKey}' records a missing or receiver object parameter"
    }
    val objectIndices = declaration.objectParameterIndices.mapTo(hashSetOf()) { index -> index - receiverOffset }
    val objectType = DotNetClrTypeSignature.Primitive(DotNetClrPrimitiveType.OBJECT)
    require(objectIndices.all { index -> inputMethod.signature.parameterTypes[index] == objectType }) {
        "input entry '${declaration.logicalFunctionKey}' disagrees with its object parameter MethodDef slots"
    }
    require(declaration.returnCarrier != DotNetGenericOwnerFunctionCarrierKind.OBJECT ||
            inputMethod.signature.returnType == objectType
    ) {
        "input entry '${declaration.logicalFunctionKey}' disagrees with its object result MethodDef slot"
    }

    val sourceMethod = validateDotNetGenericOwnerNaturalMethodDefTokenAgainstClrMetadata(
        logicalMemberKey = declaration.logicalFunctionKey,
        physicalMethod = DotNetGenericOwnerPhysicalMethodIdentityRecord(
            physicalOwnerPath = sourceDeclaration.ownerPath,
            physicalMethodName = sourceDeclaration.methodName,
            signature = declaration.sourceSignature,
        ),
        assembly = assembly,
        producerTarget = producerTarget,
    ).methodDefinition
    require(matchesEndpoint(sourceMethod, sourceDeclaration.methodName)) {
        "input entry '${declaration.logicalFunctionKey}' has incompatible source MethodDef flags"
    }
    val sourceSignature = assembly.canonicalizeExactLocalTypeReferences(sourceMethod.signature)
    val inputSignature = assembly.canonicalizeExactLocalTypeReferences(inputMethod.signature)
    require(sourceSignature.parameterTypes.size == inputSignature.parameterTypes.size &&
            sourceSignature.parameterTypes.indices.all { index ->
                index in objectIndices || sourceSignature.parameterTypes[index] == inputSignature.parameterTypes[index]
            } &&
            (if (declaration.returnCarrier == null) {
                sourceSignature.returnType == inputSignature.returnType
            } else {
                // Object authority changes a value carrier, never void into a value result.
                sourceSignature.returnType != DotNetClrTypeSignature.Void
            })
    ) {
        "input entry '${declaration.logicalFunctionKey}' changes an unrecorded source MethodDef slot"
    }
    require(assembly.inputEntryGenericConstraintsMatch(sourceMethod, inputMethod)) {
        "input entry '${declaration.logicalFunctionKey}' changes source MethodDef generic constraints"
    }
    // Calling the nonvirtual twin must not bypass an override of the natural source entry.
    // A virtual source is nevertheless closed when its MethodDef is final or its owner is sealed.
    require(!sourceMethod.isVirtual || sourceMethod.isFinal || owner.isSealed) {
        "input entry '${declaration.logicalFunctionKey}' has an overridable source MethodDef"
    }
    return DotNetGenericOwnerFunctionInputEntryMetadataBinding(
        declaration.logicalFunctionKey,
        owner,
        sourceMethod,
        inputMethod,
    )
}

private fun DotNetClrAssemblyMetadata.inputEntryGenericConstraintsMatch(
    source: DotNetClrMethodDefinition,
    input: DotNetClrMethodDefinition,
): Boolean {
    val sourceParameters = requireContiguousGenericParameters(source.handle, "MethodDef")
    val inputParameters = requireContiguousGenericParameters(input.handle, "MethodDef")
    fun constraints(parameter: DotNetClrGenericParameterDefinition): Map<DotNetClrTypeSignature, Int> =
        genericParameterConstraints.filter { constraint -> constraint.owner == parameter.handle }
            .map { constraint ->
                val signature = if (constraint.constraint.table == TYPE_SPEC_TABLE) {
                    typeSpecifications.singleOrNull { specification -> specification.handle == constraint.constraint }
                        ?.signature ?: throw IllegalArgumentException("input-entry GenericParam constraint lacks its TypeSpec")
                } else {
                    DotNetClrTypeSignature.Named(constraint.constraint, isValueType = false)
                }
                canonicalizeExactLocalTypeReferences(signature)
            }.groupingBy { signature -> signature }.eachCount()
    return sourceParameters.size == inputParameters.size && sourceParameters.indices.all { index ->
        sourceParameters[index].attributes == inputParameters[index].attributes &&
                constraints(sourceParameters[index]) == constraints(inputParameters[index])
    }
}

private const val TYPE_SPEC_TABLE = 27
