/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteNaturalAuthorityPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteSurfacePolarity
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeParameterVariance
import org.jetbrains.kotlin.backend.dotnet.dotNetDirectInterfaceTypes
import org.jetbrains.kotlin.backend.dotnet.genericOwnerDeclarationIndependentLeafPrototypeOrNull
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericInterfaceDeclaration
import org.jetbrains.kotlin.backend.dotnet.toDotNetGenericOwnerPhysicalTypeParameterVariance
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.types.Variance

/**
 * Shared bounded admission query for an ordinary natural MethodDef family. Keeping this query
 * pure lets an early generated-owner consumer and the later interface materializer rely on the
 * same proof without treating every BOUND complete-surface plan as admitted.
 */
internal fun IrClass.dotNetDirectCallableNaturalAuthorityPlanOrNull(
    context: DotNetBackendContext,
    authorityPlans: Map<IrClassSymbol, DotNetGenericInterfaceCompleteNaturalAuthorityPlan>,
): DotNetGenericInterfaceCompleteNaturalAuthorityPlan? {
    val plan = authorityPlans[symbol] ?: return null
    if (!isDotNetGenericInterfaceDeclaration || visibility != DescriptorVisibilities.PUBLIC ||
        typeParameters.size != 1 || typeParameters.single().superTypes.any { bound -> !bound.isNullableAny() } ||
        parent !is IrFile || dotNetDirectInterfaceTypes().isNotEmpty() ||
        plan.inventory.directPropertyAccessors.isNotEmpty() ||
        plan.inventory.directParentTypes != superTypes
    ) {
        return null
    }
    val members = declarations.flatMap { declaration ->
        when (declaration) {
            is IrSimpleFunction -> listOf(declaration)
            is IrProperty -> listOfNotNull(declaration.getter, declaration.setter)
            else -> emptyList()
        }
    }.filterNot(IrSimpleFunction::isFakeOverride)
    if (members.isEmpty() ||
        plan.inventory.directCallableMembers != members.map { member -> member.symbol } ||
        members.map { member -> member.name }.distinct().size != members.size ||
        members.any { member ->
            !member.isDotNetDirectOwnerInputIndependentResultMember(context, this)
        }
    ) {
        return null
    }
    val selected = plan.surfaceDecision.parameters.singleOrNull() ?: return null
    if (plan.surfaceInput.logicalMaximumVariances != listOf(
            typeParameters.single().variance.toDotNetGenericOwnerPhysicalTypeParameterVariance(),
        ) || selected.index != 0 ||
        selected.requiredPolarity != DotNetGenericInterfaceCompleteSurfacePolarity.IN ||
        selected.selectedPhysicalVariance == DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT
    ) {
        return null
    }
    return plan
}

/** One natural callable whose owner dependence occurs only in exact, non-null input slots. */
internal fun IrSimpleFunction.isDotNetDirectOwnerInputIndependentResultMember(
    context: DotNetBackendContext,
    owner: IrClass,
): Boolean {
    if (visibility != DescriptorVisibilities.PUBLIC || modality != Modality.ABSTRACT ||
        body != null || this in context.interfaceDefaultImplementations ||
        correspondingPropertySymbol != null || isSuspend || typeParameters.isNotEmpty() ||
        parameters.firstOrNull()?.kind != IrParameterKind.DispatchReceiver ||
        parameters.count { parameter -> parameter.kind == IrParameterKind.DispatchReceiver } != 1 ||
        parameters.any { parameter ->
            parameter.kind != IrParameterKind.DispatchReceiver && parameter.kind != IrParameterKind.Regular
        } || returnType.isMarkedNullable() ||
        returnType.genericOwnerDeclarationIndependentLeafPrototypeOrNull() == null
    ) {
        return false
    }
    val inputs = parameters.filter { parameter -> parameter.kind == IrParameterKind.Regular }
    if (inputs.isEmpty() || inputs.any { input ->
            input.defaultValue != null || input.varargElementType != null
        }
    ) {
        return false
    }
    var hasOwnerInput = false
    for (input in inputs) {
        val simple = input.type as? IrSimpleType
        val parameter = (simple?.classifier as? IrTypeParameterSymbol)?.owner
        if (parameter?.parent === owner && !simple.isMarkedNullable() &&
            input.type.isLegalAtOwnerVariance(owner, TypePolarity.IN)
        ) {
            hasOwnerInput = true
        } else if (input.type.genericOwnerDeclarationIndependentLeafPrototypeOrNull() == null) {
            return false
        }
    }
    return hasOwnerInput
}
