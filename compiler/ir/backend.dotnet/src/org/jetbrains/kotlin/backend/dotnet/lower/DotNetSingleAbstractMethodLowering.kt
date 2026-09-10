/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ScopeWithIr
import org.jetbrains.kotlin.backend.common.lower.SingleAbstractMethodLowering
import org.jetbrains.kotlin.backend.common.suspendFunction
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetContravariantOpenNullableSamWrapperPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeParameterVariance
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
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.copyTypeParameters
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.types.Variance
import java.util.IdentityHashMap

/** Reuses Common's Kotlin fun-interface wrapper model over the ordinary .NET class pipeline. */
internal class DotNetSingleAbstractMethodLowering(
    private val dotNetContext: DotNetBackendContext,
) : SingleAbstractMethodLowering(dotNetContext) {
    private enum class GenericSamWrapperPhysicalPlan {
        NATURAL,
        SEMANTIC_ONLY,
    }

    private val externalDeclarations = dotNetContext.externalDeclarationsForLowering()
    private val naturalInterfacesByWrapper = IdentityHashMap<IrClass, IrClass>()
    private val semanticInterfacesByWrapper = IdentityHashMap<IrClass, IrClass>()

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

    override fun getCreatedObjectProxyCacheDiscriminator(
        typeOperand: IrType,
        erasedSuperType: IrType,
    ): Any? {
        val interfaceClass = physicalSamInterfaceOrNull(erasedSuperType) ?: return null
        return physicalPlan(typeOperand, interfaceClass)
    }

    override fun configureCreatedObjectProxySuperType(
        klass: IrClass,
        superType: IrType,
        typeOperand: IrType,
    ): IrType {
        val interfaceClass = physicalSamInterfaceOrNull(superType) ?: return superType
        val plan = physicalPlan(typeOperand, interfaceClass)
        val copiedParameters = klass.copyTypeParameters(interfaceClass.typeParameters)
        check(copiedParameters.size == 1) {
            "Internal .NET backend error: bounded generic SAM wrapper has unexpected arity"
        }
        copiedParameters.forEach { parameter ->
            parameter.variance = Variance.INVARIANT
            parameter.isReified = false
        }
        if (plan == GenericSamWrapperPhysicalPlan.SEMANTIC_ONLY) {
            val witnessParameter = copiedParameters.single().symbol
            check(semanticInterfacesByWrapper.put(klass, interfaceClass) == null &&
                    dotNetContext.genericSamWrapperSemanticPlans.put(
                        klass,
                        DotNetContravariantOpenNullableSamWrapperPlan(
                            logicalInterface = interfaceClass.symbol,
                            witnessParameter = witnessParameter,
                        ),
                    ) == null
            ) {
                "Internal .NET backend error: semantic-only SAM wrapper authority was recorded twice"
            }
            // The copied invariant binder is only a witness for the underlying open T in T?.
            // This generated owner must not claim an incidental I<object> construction even
            // transiently: generic-owner planning runs before capability bridging and may treat
            // every declared supertype as physical evidence. Common can still derive the SAM
            // member from the logical interface supplied to createObjectProxy; the later bridge
            // phase binds that member directly to the interface's non-generic capability.
            return dotNetContext.irBuiltIns.anyType
        }
        check(naturalInterfacesByWrapper.put(klass, interfaceClass) == null) {
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
        val interfaceClass = naturalInterfacesByWrapper[klass]
            ?: semanticInterfacesByWrapper[klass]
            ?: return type
        val arguments = if (klass in semanticInterfacesByWrapper) {
            List(interfaceClass.typeParameters.size) { dotNetContext.irBuiltIns.anyNType }
        } else {
            klass.typeParameters.map { parameter -> parameter.defaultType }
        }
        return IrTypeSubstitutor(
            interfaceClass.typeParameters.map { parameter -> parameter.symbol },
            arguments,
            allowEmptySubstitution = true,
        ).substitute(type)
    }

    override fun getCreatedObjectProxyEqualityType(
        klass: IrClass,
        superType: IrType,
    ): IrType = (naturalInterfacesByWrapper[klass] ?: semanticInterfacesByWrapper[klass])
        ?.symbol?.starProjectedType ?: superType

    override fun getCreatedObjectProxyResultType(
        klass: IrClass,
        typeOperand: IrType,
        defaultType: IrType,
    ): IrType {
        val interfaceClass = naturalInterfacesByWrapper[klass]
            ?: semanticInterfacesByWrapper[klass]
            ?: return defaultType
        if (klass in naturalInterfacesByWrapper) {
            checkNotNull(exactConstructionArgumentsOrNull(typeOperand, interfaceClass)) {
                "Generic SAM construction '${typeOperand.render()}' lost its exact CLR TypeSpec"
            }
        }
        return typeOperand
    }

    override fun configureCreatedObjectProxyConstructorCall(
        call: IrConstructorCall,
        klass: IrClass,
        typeOperand: IrType,
    ) {
        naturalInterfacesByWrapper[klass]?.let { interfaceClass ->
            val arguments = checkNotNull(exactConstructionArgumentsOrNull(typeOperand, interfaceClass)) {
                "Generic SAM construction '${typeOperand.render()}' lost its exact CLR TypeSpec"
            }
            call.type = klass.symbol.typeWith(arguments)
            arguments.forEachIndexed(call::putClassTypeArgument)
            return
        }
        val interfaceClass = semanticInterfacesByWrapper[klass] ?: return
        val witnesses = checkNotNull(semanticOpenNullableWitnessArgumentsOrNull(typeOperand, interfaceClass)) {
            "Generic SAM construction '${typeOperand.render()}' lost its open-nullable witness"
        }
        // IrConstructorCall independently carries the constructed owner arguments and the
        // expression's selected result view. The emitter therefore allocates Wrapper<!!T>, while
        // subsequent value routing sees only the logical I<T?> semantic view.
        witnesses.forEachIndexed(call::putClassTypeArgument)
        call.type = typeOperand
    }

    // Common's temporary is a JVM-inliner code-shape constraint. A rehearsal-generic wrapper is
    // already a final .NET callable shape and can consume that expression directly. This keeps
    // the callable newobj's verifier-visible generic construction instead of asking a logically
    // raw temporary to reconstruct it. Non-generic (including production) wrappers retain
    // Common's established shape exactly.
    override fun requiresCreatedObjectProxyArgumentTemporary(
        klass: IrClass,
        invokable: IrExpression,
    ): Boolean = if (klass in naturalInterfacesByWrapper || klass in semanticInterfacesByWrapper) {
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

    private fun physicalPlan(
        type: IrType,
        interfaceClass: IrClass,
    ): GenericSamWrapperPhysicalPlan {
        if (exactConstructionArgumentsOrNull(type, interfaceClass) != null) {
            return GenericSamWrapperPhysicalPlan.NATURAL
        }
        val physicalVariances = dotNetContext
            .earlyAdmittedGenericSamNaturalAuthorityPlans[interfaceClass.symbol]
            ?.selectedPhysicalVariances
            ?: externalDeclarations
                .publishedGenericInterfaceNaturalTypeParameterVariancesOrNull(interfaceClass)
        check(semanticOpenNullableWitnessArgumentsOrNull(type, interfaceClass) != null &&
                interfaceClass.typeParameters.singleOrNull()?.variance == Variance.IN_VARIANCE &&
                physicalVariances == listOf(
                    DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT,
                )
        ) {
            "Generic SAM construction '${type.render()}' has no verifier-nameable natural " +
                    "TypeSpec and no truthful semantic-only contravariant wrapper plan"
        }
        return GenericSamWrapperPhysicalPlan.SEMANTIC_ONLY
    }

    private fun semanticOpenNullableWitnessArgumentsOrNull(
        type: IrType,
        interfaceClass: IrClass,
    ): List<IrType>? {
        val simple = type as? IrSimpleType ?: return null
        if (simple.classifier != interfaceClass.symbol || simple.arguments.size != 1) return null
        val projection = simple.arguments.single() as? IrTypeProjection ?: return null
        if (projection.variance != Variance.INVARIANT) return null
        val argument = projection.type as? IrSimpleType ?: return null
        val parameter = (argument.classifier as? IrTypeParameterSymbol)?.owner
        if (argument.nullability != SimpleTypeNullability.MARKED_NULLABLE ||
            parameter == null || parameter.superTypes.isEmpty() ||
            parameter.superTypes.any { bound -> bound.isNullable() }
        ) {
            return null
        }
        val witness = argument.makeNotNull()
        return listOf(witness).takeIf {
            !witness.hasUnsupportedDotNetInvariantConstructorArgument()
        }
    }

    private fun exactConstructionArgumentsOrNull(
        type: IrType,
        interfaceClass: IrClass,
    ): List<IrType>? {
        val simple = type as? IrSimpleType
            ?: error("Unsupported generic SAM construction: ${type.render()}")
        check(simple.classifier == interfaceClass.symbol &&
                simple.arguments.size == interfaceClass.typeParameters.size
        ) {
            "Generic SAM construction '${type.render()}' disagrees with '${interfaceClass.name}'"
        }
        val result = mutableListOf<IrType>()
        for (argument in simple.arguments) {
            val projection = argument as? IrTypeProjection
                ?: return null
            if (projection.variance != Variance.INVARIANT ||
                projection.type.hasUnsupportedDotNetInvariantConstructorArgument()
            ) {
                return null
            }
            result += projection.type
        }
        return result
    }
}
