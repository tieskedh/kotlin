/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteSurfaceFixedTypeInput
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteNaturalAuthorityPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteSurfaceOwnerDecision
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteSurfaceOwnerInput
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteSurfacePolarity
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteSurfaceInventory
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteSurfacePosition
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteSurfacePropertyAccessorInventory
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteSurfaceTypeReference
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalDeclarations
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteSurfaceVarianceParameterSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteSurfaceVarianceShadowSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteSurfaceVarianceShadowStatus
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotPolarity
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceView
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalBindingResult
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeDefIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeParameterVariance
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerRehearsal
import org.jetbrains.kotlin.backend.dotnet.dotNetPhysicalValueStableName
import org.jetbrains.kotlin.backend.dotnet.hasSameFrozenAuthorityAs
import org.jetbrains.kotlin.backend.dotnet.isDotNetResolutionOnlyStdlibDeclaration
import org.jetbrains.kotlin.backend.dotnet.isReifiedByGenericOwnerRehearsal
import org.jetbrains.kotlin.backend.dotnet.planDotNetGenericInterfaceCompleteSurfaceVariance
import org.jetbrains.kotlin.backend.dotnet.referencesTypeParameterOf
import org.jetbrains.kotlin.backend.dotnet.toDotNetGenericOwnerPhysicalTypeParameterVariance
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.types.Variance

internal data class DotNetGenericInterfaceCompleteSurfaceAnalysis(
    val snapshots: List<DotNetGenericInterfaceCompleteSurfaceVarianceShadowSnapshot>,
    val authorityPlans: Map<IrClassSymbol, DotNetGenericInterfaceCompleteNaturalAuthorityPlan>,
)

/**
 * Freezes interface-only physical authority before target-generated implementation owners are
 * built. The same analysis is repeated after class planning; every plan consumed in between must
 * remain byte-for-byte equivalent at that later authority epoch.
 */
internal class DotNetEarlyGenericInterfaceCompleteSurfaceAuthorityLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    override fun lower(irModule: IrModuleFragment) {
        check(!context.preSamGenericInterfaceNaturalAuthorityAnalysisCompleted) {
            "Internal .NET backend error: pre-SAM natural-interface authority ran more than once"
        }
        check(context.earlyAdmittedGenericSamNaturalAuthorityPlans.isEmpty()) {
            "Internal .NET backend error: pre-SAM natural-interface authority had pre-existing output"
        }
        val analysis = DotNetGenericInterfaceCompleteSurfaceVarianceShadowLowering(context)
            .analyze(irModule)
        val referencedSamInterfaces = linkedSetOf<IrClassSymbol>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitTypeOperator(expression: IrTypeOperatorCall) {
                if (expression.operator == IrTypeOperator.SAM_CONVERSION) {
                    ((expression.typeOperand as? IrSimpleType)?.classifier as? IrClassSymbol)
                        ?.let(referencedSamInterfaces::add)
                }
                expression.acceptChildrenVoid(this)
            }
        })
        for (entry in analysis.authorityPlans.entries) {
            val symbol = entry.key
            val plan = entry.value
            if (symbol !in referencedSamInterfaces) continue
            val admitted = symbol.owner.dotNetDirectCallableNaturalAuthorityPlanOrNull(
                context,
                analysis.authorityPlans,
            )
            if (admitted != null) {
                check(admitted.hasSameFrozenAuthorityAs(plan)) {
                    "Internal .NET backend error: early SAM admission changed complete-natural authority"
                }
                context.earlyAdmittedGenericSamNaturalAuthorityPlans[symbol] = admitted
            }
        }
        context.preSamGenericInterfaceNaturalAuthorityAnalysisCompleted = true
    }
}

/**
 * Computes the proposed variance of one complete natural CLR interface without changing IR.
 *
 * This phase deliberately runs before the current declared/exact interface split. It observes
 * source members and direct parent edges only and translates them to the IR-free physical-surface
 * vocabulary. Public snapshots remain diagnostic-only. The parallel IR-bound plans are early
 * evidence, not admission: only a later lowering which independently validates a bounded grammar
 * may copy one into the context's explicitly admitted complete-natural plan map.
 */
internal class DotNetGenericInterfaceCompleteSurfaceVarianceShadowLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    private val externalDeclarations: DotNetExternalDeclarations =
        context.externalDeclarationsForLowering()

    override fun lower(irModule: IrModuleFragment) {
        check(context.earlyGenericInterfaceCompleteNaturalAuthorityAnalysisCompleted) {
            "Internal .NET backend error: final complete-surface analysis preceded early authority"
        }
        check(!context.genericInterfaceCompleteSurfaceVarianceShadowAnalysisCompleted) {
            "Internal .NET backend error: complete-surface variance shadow ran more than once"
        }
        check(context.genericInterfaceCompleteSurfaceVarianceShadows.isEmpty()) {
            "Internal .NET backend error: complete-surface variance shadow had pre-existing output"
        }
        check(context.genericInterfaceCompleteSurfaceVarianceAuthorityPlans.isEmpty()) {
            "Internal .NET backend error: complete-surface variance analysis had pre-existing authority plans"
        }
        check(context.admittedGenericInterfaceCompleteNaturalAuthorityPlans.isEmpty()) {
            "Internal .NET backend error: complete-natural admission preceded complete-surface analysis"
        }

        val analysis = analyze(irModule)
        context.genericInterfaceCompleteSurfaceVarianceShadows += analysis.snapshots
        context.genericInterfaceCompleteSurfaceVarianceAuthorityPlans.putAll(analysis.authorityPlans)
        context.earlyGenericInterfaceCompleteNaturalAuthorityPlans.entries.forEach { entry ->
            val symbol = entry.key
            val earlyPlan = entry.value
            val finalPlan = analysis.authorityPlans[symbol]
            check(finalPlan != null && earlyPlan.hasSameFrozenAuthorityAs(finalPlan)) {
                "Internal .NET backend error: an early complete-natural interface plan changed " +
                        "after generic-class representation planning for '${symbol.owner.name}'"
            }
        }
        context.genericInterfaceCompleteSurfaceVarianceShadowAnalysisCompleted = true
    }

    /**
     * Runs the same dependency-closed physical-surface calculation in both authority epochs.
     * Before class planning, owner-dependent local-class constructions are deliberately
     * unavailable; after class planning the same algorithm may add those later-resolved plans.
     * Any plan present in both runs must be byte-for-byte equivalent in its frozen authority.
     */
    internal fun analyze(
        irModule: IrModuleFragment,
    ): DotNetGenericInterfaceCompleteSurfaceAnalysis {
        if (!context.configuration.dotNetGenericOwnerRehearsal) {
            return DotNetGenericInterfaceCompleteSurfaceAnalysis(emptyList(), emptyMap())
        }

        val owners = irModule.localGenericInterfaces()
        val identitiesByOwner: Map<IrClass, DotNetGenericOwnerPhysicalTypeDefIdentity> =
            owners.associateWithTo(linkedMapOf()) { owner -> owner.naturalIdentity() }
        val ownersByIdentity: Map<DotNetGenericOwnerPhysicalTypeDefIdentity, IrClass> =
            identitiesByOwner.entries.associateTo(linkedMapOf()) { entry ->
            entry.value to entry.key
        }
        val fixedTypes = linkedMapOf<
                DotNetGenericOwnerPhysicalTypeDefIdentity,
                DotNetGenericInterfaceCompleteSurfaceFixedTypeInput,
                >()
        val builder = SurfaceBuilder(
            context = context,
            externalDeclarations = externalDeclarations,
            candidateIdentities = identitiesByOwner,
            fixedTypes = fixedTypes,
        )
        val builds = owners.associateTo(linkedMapOf()) { owner ->
            identitiesByOwner.getValue(owner) to builder.buildOwner(owner)
        }

        val authorityPlans = linkedMapOf<IrClassSymbol, DotNetGenericInterfaceCompleteNaturalAuthorityPlan>()
        val snapshots = owners.map { owner ->
            val identity = identitiesByOwner.getValue(owner)
            when (val build = builds.getValue(identity)) {
                is OwnerBuild.Unavailable -> owner.snapshot(
                    DotNetGenericInterfaceCompleteSurfaceVarianceShadowStatus.UNAVAILABLE,
                    build.reason,
                )
                is OwnerBuild.Conflict -> owner.snapshot(
                    DotNetGenericInterfaceCompleteSurfaceVarianceShadowStatus.CONFLICT,
                    build.reason,
                )
                is OwnerBuild.Bound -> {
                    val closure = dependencyClosure(
                        root = identity,
                        builds = builds,
                        ownersByIdentity = ownersByIdentity,
                    )
                    when (closure) {
                        is OwnerClosure.Unavailable -> owner.snapshot(
                            DotNetGenericInterfaceCompleteSurfaceVarianceShadowStatus.UNAVAILABLE,
                            closure.reason,
                        )
                        is OwnerClosure.Conflict -> owner.snapshot(
                            DotNetGenericInterfaceCompleteSurfaceVarianceShadowStatus.CONFLICT,
                            closure.reason,
                        )
                        is OwnerClosure.Bound -> when (val result =
                            planDotNetGenericInterfaceCompleteSurfaceVariance(
                                owners = closure.inputs,
                                fixedTypes = fixedTypes.values.toList(),
                            )
                        ) {
                            DotNetGenericOwnerPhysicalBindingResult.Unavailable -> owner.snapshot(
                                DotNetGenericInterfaceCompleteSurfaceVarianceShadowStatus.UNAVAILABLE,
                                "physical variance authority is unavailable for an owner-dependent surface type",
                            )
                            is DotNetGenericOwnerPhysicalBindingResult.Conflict -> owner.snapshot(
                                DotNetGenericInterfaceCompleteSurfaceVarianceShadowStatus.CONFLICT,
                                result.reason,
                            )
                            is DotNetGenericOwnerPhysicalBindingResult.Bound -> {
                                val decision = checkNotNull(result.value.ownerOrNull(identity)) {
                                    "Internal .NET backend error: complete-surface plan omitted its root owner"
                                }
                                val authorityPlan = DotNetGenericInterfaceCompleteNaturalAuthorityPlan(
                                    owner = owner.symbol,
                                    inventory = build.inventory,
                                    surfaceInput = build.input,
                                    surfaceDecision = decision,
                                )
                                check(authorityPlans.put(owner.symbol, authorityPlan) == null) {
                                    "Internal .NET backend error: complete-surface analysis repeated one owner plan"
                                }
                                owner.snapshot(authorityPlan.surfaceDecision)
                            }
                        }
                    }
                }
            }
        }
        return DotNetGenericInterfaceCompleteSurfaceAnalysis(snapshots, authorityPlans)
    }

    private fun IrModuleFragment.localGenericInterfaces(): List<IrClass> {
        val result = mutableListOf<IrClass>()
        acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.origin != IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB &&
                    !declaration.isDotNetResolutionOnlyStdlibDeclaration &&
                    declaration.kind == ClassKind.INTERFACE &&
                    declaration.typeParameters.isNotEmpty()
                ) {
                    result += declaration
                }
                declaration.acceptChildrenVoid(this)
            }
        })
        return result.distinctBy { owner -> owner.symbol }
    }

    private fun IrClass.naturalIdentity(): DotNetGenericOwnerPhysicalTypeDefIdentity.Local =
        DotNetGenericOwnerPhysicalTypeDefIdentity.Local(symbol, DotNetGenericInterfaceView.DECLARED)

    private fun dependencyClosure(
        root: DotNetGenericOwnerPhysicalTypeDefIdentity,
        builds: Map<DotNetGenericOwnerPhysicalTypeDefIdentity, OwnerBuild>,
        ownersByIdentity: Map<DotNetGenericOwnerPhysicalTypeDefIdentity, IrClass>,
    ): OwnerClosure {
        val pending = ArrayDeque<DotNetGenericOwnerPhysicalTypeDefIdentity>()
        val visited = linkedSetOf<DotNetGenericOwnerPhysicalTypeDefIdentity>()
        pending += root
        while (pending.isNotEmpty()) {
            val identity = pending.removeFirst()
            if (!visited.add(identity)) continue
            when (val build = builds[identity]) {
                null -> return OwnerClosure.Unavailable(
                    "a local complete-surface dependency has no candidate input",
                )
                is OwnerBuild.Unavailable -> return OwnerClosure.Unavailable(
                    "dependency '${ownersByIdentity[identity]?.dotNetPhysicalValueStableName() ?: identity}' " +
                            "is unavailable: ${build.reason}",
                )
                is OwnerBuild.Conflict -> return OwnerClosure.Conflict(
                    "dependency '${ownersByIdentity[identity]?.dotNetPhysicalValueStableName() ?: identity}' " +
                            "conflicts: ${build.reason}",
                )
                is OwnerBuild.Bound -> build.candidateDependencies.forEach(pending::addLast)
            }
        }
        return OwnerClosure.Bound(visited.map { identity ->
            (builds.getValue(identity) as OwnerBuild.Bound).input
        })
    }

    private fun IrClass.snapshot(
        status: DotNetGenericInterfaceCompleteSurfaceVarianceShadowStatus,
        blocker: String,
    ) = DotNetGenericInterfaceCompleteSurfaceVarianceShadowSnapshot(
        ownerName = dotNetPhysicalValueStableName(),
        status = status,
        parameters = emptyList(),
        blocker = blocker,
    )

    private fun IrClass.snapshot(
        decision: DotNetGenericInterfaceCompleteSurfaceOwnerDecision,
    ) = DotNetGenericInterfaceCompleteSurfaceVarianceShadowSnapshot(
        ownerName = dotNetPhysicalValueStableName(),
        status = DotNetGenericInterfaceCompleteSurfaceVarianceShadowStatus.BOUND,
        parameters = decision.parameters.map { parameter ->
            DotNetGenericInterfaceCompleteSurfaceVarianceParameterSnapshot(
                index = parameter.index,
                logicalMaximumVariance = parameter.logicalMaximumVariance.toSnapshotVariance(),
                requiredPolarity = parameter.requiredPolarity.toSnapshotPolarity(),
                selectedPhysicalVariance = parameter.selectedPhysicalVariance.toSnapshotVariance(),
            )
        },
        blocker = null,
    )

    private fun DotNetGenericOwnerPhysicalTypeParameterVariance.toSnapshotVariance():
            DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance = when (this) {
        DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT ->
            DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance.INVARIANT
        DotNetGenericOwnerPhysicalTypeParameterVariance.COVARIANT ->
            DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance.COVARIANT
        DotNetGenericOwnerPhysicalTypeParameterVariance.CONTRAVARIANT ->
            DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotVariance.CONTRAVARIANT
    }

    private fun DotNetGenericInterfaceCompleteSurfacePolarity.toSnapshotPolarity():
            DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotPolarity = when (this) {
        DotNetGenericInterfaceCompleteSurfacePolarity.NONE ->
            DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotPolarity.NONE
        DotNetGenericInterfaceCompleteSurfacePolarity.OUT ->
            DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotPolarity.OUT
        DotNetGenericInterfaceCompleteSurfacePolarity.IN ->
            DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotPolarity.IN
        DotNetGenericInterfaceCompleteSurfacePolarity.BOTH ->
            DotNetGenericInterfaceCompleteSurfaceVarianceSnapshotPolarity.BOTH
    }

    private sealed interface OwnerBuild {
        data class Bound(
            val input: DotNetGenericInterfaceCompleteSurfaceOwnerInput,
            val candidateDependencies: Set<DotNetGenericOwnerPhysicalTypeDefIdentity>,
            val inventory: DotNetGenericInterfaceCompleteSurfaceInventory,
        ) : OwnerBuild

        data class Unavailable(val reason: String) : OwnerBuild

        data class Conflict(val reason: String) : OwnerBuild
    }

    private sealed interface OwnerClosure {
        data class Bound(val inputs: List<DotNetGenericInterfaceCompleteSurfaceOwnerInput>) : OwnerClosure

        data class Unavailable(val reason: String) : OwnerClosure

        data class Conflict(val reason: String) : OwnerClosure
    }

    private class SurfaceBuilder(
        private val context: DotNetBackendContext,
        private val externalDeclarations: DotNetExternalDeclarations,
        private val candidateIdentities: Map<IrClass, DotNetGenericOwnerPhysicalTypeDefIdentity>,
        private val fixedTypes: MutableMap<
                DotNetGenericOwnerPhysicalTypeDefIdentity,
                DotNetGenericInterfaceCompleteSurfaceFixedTypeInput,
                >,
    ) {
        fun buildOwner(owner: IrClass): OwnerBuild {
            val dependencies = linkedSetOf<DotNetGenericOwnerPhysicalTypeDefIdentity>()
            val positions = mutableListOf<DotNetGenericInterfaceCompleteSurfacePosition>()
            val directCallableMembers = owner.directSimpleFunctions()
            val directParentTypes = owner.superTypes.toList()
            val inventory = DotNetGenericInterfaceCompleteSurfaceInventory(
                directCallableMembers = directCallableMembers.map { member -> member.symbol },
                directPropertyAccessors = owner.declarations.filterIsInstance<IrProperty>()
                    .filter { property ->
                        listOfNotNull(property.getter, property.setter).any { accessor ->
                            !accessor.isFakeOverride
                        }
                    }
                    .map { property ->
                        DotNetGenericInterfaceCompleteSurfacePropertyAccessorInventory(
                            property = property.symbol,
                            getter = property.getter?.takeUnless(IrSimpleFunction::isFakeOverride)?.symbol,
                            setter = property.setter?.takeUnless(IrSimpleFunction::isFakeOverride)?.symbol,
                        )
                    },
                directParentTypes = directParentTypes,
            )
            for (member in directCallableMembers) {
                if (member.typeParameters.any { parameter ->
                        parameter.superTypes.any { bound -> bound.referencesTypeParameterOf(owner) }
                    }
                ) {
                    return OwnerBuild.Unavailable(
                        "method-generic constraints depending on owner parameters need a bound ECMA layout",
                    )
                }
                when (val result = member.returnType.surfaceType(owner, dependencies)) {
                    is TypeBuild.Bound -> positions += DotNetGenericInterfaceCompleteSurfacePosition(
                        DotNetGenericInterfaceCompleteSurfacePolarity.OUT,
                        result.reference,
                    )
                    is TypeBuild.Unavailable -> return OwnerBuild.Unavailable(result.reason)
                    is TypeBuild.Conflict -> return OwnerBuild.Conflict(result.reason)
                }
                for (parameter in member.parameters) {
                    if (parameter.kind == IrParameterKind.DispatchReceiver) continue
                    when (val result = parameter.type.surfaceType(owner, dependencies)) {
                        is TypeBuild.Bound -> positions += DotNetGenericInterfaceCompleteSurfacePosition(
                            DotNetGenericInterfaceCompleteSurfacePolarity.IN,
                            result.reference,
                        )
                        is TypeBuild.Unavailable -> return OwnerBuild.Unavailable(result.reason)
                        is TypeBuild.Conflict -> return OwnerBuild.Conflict(result.reason)
                    }
                }
            }
            for (parent in directParentTypes) {
                when (val result = parent.surfaceType(owner, dependencies)) {
                    is TypeBuild.Bound -> positions += DotNetGenericInterfaceCompleteSurfacePosition(
                        DotNetGenericInterfaceCompleteSurfacePolarity.OUT,
                        result.reference,
                    )
                    is TypeBuild.Unavailable -> return OwnerBuild.Unavailable(result.reason)
                    is TypeBuild.Conflict -> return OwnerBuild.Conflict(result.reason)
                }
            }
            return OwnerBuild.Bound(
                input = DotNetGenericInterfaceCompleteSurfaceOwnerInput(
                    identity = candidateIdentities.getValue(owner),
                    logicalMaximumVariances = owner.typeParameters.map { parameter ->
                        parameter.variance.toDotNetGenericOwnerPhysicalTypeParameterVariance()
                    },
                    positions = positions,
                ),
                candidateDependencies = dependencies,
                inventory = inventory,
            )
        }

        private fun IrClass.directSimpleFunctions(): List<IrSimpleFunction> =
            declarations.flatMap { declaration ->
                when (declaration) {
                    is IrSimpleFunction -> listOf(declaration)
                    is IrProperty -> listOfNotNull(declaration.getter, declaration.setter)
                    else -> emptyList()
                }
            }.filterNot(IrSimpleFunction::isFakeOverride)
                .distinctBy { function -> function.symbol }

        private fun IrType.surfaceType(
            owner: IrClass,
            dependencies: MutableSet<DotNetGenericOwnerPhysicalTypeDefIdentity>,
        ): TypeBuild {
            if (!referencesTypeParameterOf(owner)) {
                return TypeBuild.Bound(DotNetGenericInterfaceCompleteSurfaceTypeReference.Independent)
            }
            val simple = this as? IrSimpleType ?: return TypeBuild.Unavailable(
                "an owner-dependent surface type has no resolved physical simple-type carrier",
            )
            if (simple.isMarkedNullable()) {
                return TypeBuild.Unavailable(
                    "an owner-dependent nullable surface type needs a bound callable result layout",
                )
            }
            val ownerParameter = (simple.classifier as? IrTypeParameterSymbol)?.owner
            if (ownerParameter?.parent == owner) {
                val index = owner.typeParameters.indexOf(ownerParameter)
                if (index < 0) {
                    return TypeBuild.Conflict(
                        "an owner-parameter surface type escaped its declaring binder",
                    )
                }
                if (simple.arguments.isNotEmpty()) {
                    return TypeBuild.Conflict("an owner parameter unexpectedly carries type arguments")
                }
                return TypeBuild.Bound(
                    DotNetGenericInterfaceCompleteSurfaceTypeReference.OwnerParameter(index),
                )
            }

            val classifier = (simple.classifier as? IrClassSymbol)?.owner ?: return TypeBuild.Unavailable(
                "an owner-dependent surface type is not backed by a physical TypeDef",
            )
            if (simple.arguments.size != classifier.typeParameters.size) {
                return TypeBuild.Conflict(
                    "an owner-dependent construction supplies ${simple.arguments.size} arguments " +
                            "to logical arity ${classifier.typeParameters.size}",
                )
            }
            if (simple.arguments.any { argument ->
                    argument is IrStarProjection ||
                            (argument as? IrTypeProjection)?.variance != Variance.INVARIANT
                }
            ) {
                return TypeBuild.Unavailable(
                    "a star or use-site projection has no selected exact CLR construction",
                )
            }
            val arguments = mutableListOf<DotNetGenericInterfaceCompleteSurfaceTypeReference>()
            for (argument in simple.arguments) {
                val projection = argument as? IrTypeProjection ?: return TypeBuild.Unavailable(
                    "an owner-dependent construction has no exact physical type argument",
                )
                when (val result = projection.type.surfaceType(owner, dependencies)) {
                    is TypeBuild.Bound -> arguments += result.reference
                    is TypeBuild.Unavailable -> return result
                    is TypeBuild.Conflict -> return result
                }
            }
            if (simple.classifier == context.irBuiltIns.arrayClass) {
                val element = arguments.singleOrNull() ?: return TypeBuild.Conflict(
                    "the CLR SZ-array surface requires exactly one element carrier",
                )
                return TypeBuild.Bound(DotNetGenericInterfaceCompleteSurfaceTypeReference.SzArray(element))
            }

            val candidateIdentity = candidateIdentities[classifier]
            if (candidateIdentity != null) {
                dependencies += candidateIdentity
                return TypeBuild.Bound(
                    DotNetGenericInterfaceCompleteSurfaceTypeReference.Constructed(
                        candidateIdentity,
                        arguments,
                    ),
                )
            }
            val externalFixed = externalDeclarations
                .publishedGenericInterfaceNaturalFixedTypeInputOrNull(classifier)
            if (externalFixed != null) {
                val existing = fixedTypes.putIfAbsent(externalFixed.identity, externalFixed)
                if (existing != null && existing.physicalVariances != externalFixed.physicalVariances) {
                    return TypeBuild.Conflict(
                        "one producer-recorded nested TypeDef has conflicting physical variance",
                    )
                }
                return TypeBuild.Bound(
                    DotNetGenericInterfaceCompleteSurfaceTypeReference.Constructed(
                        externalFixed.identity,
                        arguments,
                    ),
                )
            }
            val localClassPlan = context.genericOwnerArchitecturePlans[classifier]
            if (localClassPlan != null) {
                if (classifier.kind != ClassKind.CLASS ||
                    !localClassPlan.isReifiedByGenericOwnerRehearsal
                ) {
                    return TypeBuild.Unavailable(
                        "a planned nested generic class has no admitted natural CLR TypeDef",
                    )
                }
                val identity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(classifier.symbol, view = null)
                fixedTypes.putIfAbsent(
                    identity,
                    DotNetGenericInterfaceCompleteSurfaceFixedTypeInput(
                        identity,
                        List(classifier.typeParameters.size) {
                            DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT
                        },
                    ),
                )
                return TypeBuild.Bound(
                    DotNetGenericInterfaceCompleteSurfaceTypeReference.Constructed(identity, arguments),
                )
            }
            return TypeBuild.Unavailable(
                "an owner-dependent nested TypeDef has no retained or producer-recorded physical variance",
            )
        }
    }

    private sealed interface TypeBuild {
        data class Bound(
            val reference: DotNetGenericInterfaceCompleteSurfaceTypeReference,
        ) : TypeBuild

        data class Unavailable(val reason: String) : TypeBuild

        data class Conflict(val reason: String) : TypeBuild
    }
}
