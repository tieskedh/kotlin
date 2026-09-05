/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ScopeWithIr
import org.jetbrains.kotlin.backend.common.lower.SingleAbstractMethodLowering
import org.jetbrains.kotlin.backend.common.suspendFunction
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetPublishedGenericInterfaceMemberResultLayout
import org.jetbrains.kotlin.backend.dotnet.DotNetPublishedGenericInterfaceMemberRole
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerRehearsal
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.putClassTypeArgument
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.copyTypeParameters
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.types.Variance
import java.util.IdentityHashMap

/** Reuses Common's Kotlin fun-interface wrapper model over the ordinary .NET class pipeline. */
internal class DotNetSingleAbstractMethodLowering(
    private val dotNetContext: DotNetBackendContext,
) : SingleAbstractMethodLowering(dotNetContext) {
    private val externalDeclarations = dotNetContext.externalDeclarationsForLowering()
    private val physicalInterfacesByWrapper = IdentityHashMap<IrClass, IrClass>()

    // .NET serializes authoritative inline bodies to KLIB before target lowering. A consumer
    // therefore materializes its own private wrapper after inlining; the producer's physical
    // remainder never needs a second public wrapper ABI. Reuse the ordinary per-file cache so a
    // file containing both inline and non-inline conversions cannot create duplicate private
    // TypeDefs with Common's otherwise identical wrapper name.
    override val inInlineFunctionScope: Boolean
        get() = false

    override fun getWrapperVisibility(
        expression: IrTypeOperatorCall,
        scopes: List<ScopeWithIr>,
    ) = DescriptorVisibilities.INTERNAL

    override fun getSuperTypeForWrapper(typeOperand: IrType): IrType =
        typeOperand.classOrNull?.defaultType
            ?: error("Unsupported SAM conversion: ${typeOperand.render()}")

    override fun configureCreatedObjectProxySuperType(
        klass: IrClass,
        superType: IrType,
    ): IrType {
        val interfaceClass = physicalSamInterfaceOrNull(superType) ?: return superType
        val copiedParameters = klass.copyTypeParameters(interfaceClass.typeParameters)
        check(copiedParameters.size == 1) {
            "Internal .NET backend error: bounded generic SAM wrapper has unexpected arity"
        }
        copiedParameters.forEach { parameter ->
            parameter.variance = Variance.INVARIANT
            parameter.isReified = false
        }
        check(physicalInterfacesByWrapper.put(klass, interfaceClass) == null) {
            "Internal .NET backend error: generated SAM wrapper was physically configured twice"
        }
        check(dotNetContext.genericSamWrapperNaturalInterfaces.put(klass, interfaceClass.symbol) == null) {
            "Internal .NET backend error: generated SAM wrapper authority was recorded twice"
        }
        if (interfaceClass.symbol in dotNetContext.earlyAdmittedGenericSamNaturalAuthorityPlans) {
            dotNetContext.consumedEarlyGenericInterfaceNaturalAuthorityPlans += interfaceClass.symbol
        }
        return interfaceClass.symbol.typeWith(copiedParameters.map { parameter -> parameter.defaultType })
    }

    override fun remapCreatedObjectProxyMemberType(
        klass: IrClass,
        superType: IrType,
        type: IrType,
    ): IrType {
        val interfaceClass = physicalInterfacesByWrapper[klass] ?: return type
        return IrTypeSubstitutor(
            interfaceClass.typeParameters.map { parameter -> parameter.symbol },
            klass.typeParameters.map { parameter -> parameter.defaultType },
            allowEmptySubstitution = true,
        ).substitute(type)
    }

    override fun getCreatedObjectProxyEqualityType(
        klass: IrClass,
        superType: IrType,
    ): IrType = physicalInterfacesByWrapper[klass]?.symbol?.starProjectedType ?: superType

    override fun getCreatedObjectProxyResultType(
        klass: IrClass,
        typeOperand: IrType,
        defaultType: IrType,
    ): IrType {
        val interfaceClass = physicalInterfacesByWrapper[klass] ?: return defaultType
        exactConstructionArguments(typeOperand, interfaceClass)
        return typeOperand
    }

    override fun configureCreatedObjectProxyConstructorCall(
        call: IrConstructorCall,
        klass: IrClass,
        typeOperand: IrType,
    ) {
        val interfaceClass = physicalInterfacesByWrapper[klass] ?: return
        val arguments = exactConstructionArguments(typeOperand, interfaceClass)
        call.type = klass.symbol.typeWith(arguments)
        arguments.forEachIndexed(call::putClassTypeArgument)
    }

    // Common's temporary is a JVM-inliner code-shape constraint. A rehearsal-generic wrapper is
    // already a final .NET callable shape and can consume that expression directly. This keeps
    // the callable newobj's verifier-visible generic construction instead of asking a logically
    // raw temporary to reconstruct it. Non-generic (including production) wrappers retain
    // Common's established shape exactly.
    override fun requiresCreatedObjectProxyArgumentTemporary(
        klass: IrClass,
        invokable: IrExpression,
    ): Boolean = if (klass in physicalInterfacesByWrapper) {
        false
    } else {
        super.requiresCreatedObjectProxyArgumentTemporary(klass, invokable)
    }

    override val IrType.needEqualsHashCodeMethods: Boolean
        get() = true

    override fun getSuspendFunctionWithoutContinuation(function: IrSimpleFunction): IrSimpleFunction =
        function.suspendFunction ?: function

    private fun physicalSamInterfaceOrNull(superType: IrType): IrClass? {
        if (!dotNetContext.configuration.dotNetGenericOwnerRehearsal) return null
        val interfaceClass = superType.classOrNull?.owner ?: return null
        if (interfaceClass.typeParameters.size != 1 ||
            interfaceClass.typeParameters.single().superTypes.any { bound ->
                !bound.isNullableAny()
            }
        ) {
            return null
        }
        if (interfaceClass.symbol in dotNetContext.earlyAdmittedGenericSamNaturalAuthorityPlans) {
            return interfaceClass
        }
        val family = externalDeclarations.publishedGenericInterfaceFamilyOrNull(interfaceClass)
            ?: return null
        if (family.genericArity != 1 || family.directParents.isNotEmpty() ||
            family.declaredMembers.size != 1
        ) {
            return null
        }
        val abstractMember = interfaceClass.declarations.filterIsInstance<IrSimpleFunction>()
            .singleOrNull { member -> member.modality == Modality.ABSTRACT }
            ?: return null
        val member = externalDeclarations.publishedGenericInterfaceMemberContractOrNull(abstractMember)
            ?: return null
        return interfaceClass.takeIf {
            member == family.declaredMembers.single() &&
                    member.role == DotNetPublishedGenericInterfaceMemberRole.DIRECT_CALLABLE &&
                    member.resultLayout == DotNetPublishedGenericInterfaceMemberResultLayout.DIRECT &&
                    externalDeclarations
                        .publishedGenericInterfaceNaturalTypeParameterVariancesOrNull(interfaceClass)
                        ?.size == 1
        }
    }

    private fun exactConstructionArguments(
        type: IrType,
        interfaceClass: IrClass,
    ): List<IrType> {
        val simple = type as? IrSimpleType
            ?: error("Unsupported generic SAM construction: ${type.render()}")
        check(simple.classifier == interfaceClass.symbol &&
                simple.arguments.size == interfaceClass.typeParameters.size
        ) {
            "Generic SAM construction '${type.render()}' disagrees with '${interfaceClass.name}'"
        }
        return simple.arguments.map { argument ->
            val projection = argument as? IrTypeProjection
                ?: error("Generic SAM construction '${type.render()}' has no exact CLR type argument")
            check(projection.variance == Variance.INVARIANT &&
                    !projection.type.hasUnsupportedDotNetInvariantConstructorArgument()
            ) {
                "Generic SAM construction '${type.render()}' has no verifier-nameable invariant TypeSpec"
            }
            projection.type
        }
    }
}
