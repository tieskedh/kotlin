/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.defaultArgumentsOriginalFunction
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.SpecialBridgeMethods
import org.jetbrains.kotlin.backend.common.lower.VariableRemapper
import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.common.ir.moveBodyTo
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerArchitecturePlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerCandidateDisposition
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerConstructorArgumentMapping
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerConstructorPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerCallReceiverProvenance
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerCallRoutePlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerCallRouteRequirement
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerDirectSuperCallPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberFamilyPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberBodyPlacement
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberFamilyRole
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberAccessPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberPolicy
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerOverrideBindingPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerOverrideTargetKind
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalSlotDomain
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalBindingResult
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalGenericParameterReference
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeDefIdentity
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalTypeParameterVariance
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPrototypeMember
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPrototypeTypeSnapshot
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSemanticHookReason
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalCallableResultLayoutRecord
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStateCarrierPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStateInitializerPlan
import org.jetbrains.kotlin.backend.dotnet.mergeDotNetGenericOwnerParameterSlotDomains
import org.jetbrains.kotlin.backend.dotnet.mergeDotNetGenericOwnerSemanticObjectParameterIndices
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStateCarrierRequirement
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStateMemorySemantics
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStateWriteProvenancePlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPrototypeStateInitializerKind
import org.jetbrains.kotlin.backend.dotnet.DotNetPublishedGenericInterfaceMemberRole
import org.jetbrains.kotlin.backend.dotnet.DotNetPublishedGenericInterfaceMemberResultLayout
import org.jetbrains.kotlin.backend.dotnet.DotNetRuntimeTypes
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericInterfaceLogicalHazard
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceCompleteSurfaceTypeReference
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalAuthority
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalTypeInput
import org.jetbrains.kotlin.backend.dotnet.DotNetLocalGenericOwnerPhysicalTypeRole
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerWriteValueProvenance
import org.jetbrains.kotlin.backend.dotnet.DotNetBoundGenericOwnerMemberFamily
import org.jetbrains.kotlin.backend.dotnet.DotNetBoundGenericOwnerPhysicalSlot
import org.jetbrains.kotlin.backend.dotnet.dotNetLibraryAbiKeyOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetImportedClrTypeAuthorityOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerCallRouteTraceHooks
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerRehearsal
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerPhysicalMemberName
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerPhysicalForeignOverrideProbeName
import org.jetbrains.kotlin.backend.dotnet.dotNetDirectOwnerRelativeMethodBoundsOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetIlMethodName
import org.jetbrains.kotlin.backend.dotnet.dotNetPhysicalValueStableName
import org.jetbrains.kotlin.backend.dotnet.genericOwnerDeclarationIndependentLeafPrototypeOrNull
import org.jetbrains.kotlin.backend.dotnet.genericOwnerPrototypePhysicalGenericParameters
import org.jetbrains.kotlin.backend.dotnet.genericOwnerPrototypeStateType
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericClassDeclaration
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericInterfaceDeclaration
import org.jetbrains.kotlin.backend.dotnet.isDotNetComparableClass
import org.jetbrains.kotlin.backend.dotnet.isReifiedByGenericOwnerRehearsal
import org.jetbrains.kotlin.backend.dotnet.isDotNetResolutionOnlyStdlibDeclaration
import org.jetbrains.kotlin.backend.dotnet.referencesTypeParameterOf
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationBase
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrReturnableBlock
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.SimpleTypeNullability
import org.jetbrains.kotlin.ir.types.impl.IrStarProjectionImpl
import org.jetbrains.kotlin.ir.types.impl.buildSimpleType
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.IrTypeParameterRemapper
import org.jetbrains.kotlin.ir.util.isOriginallyLocalDeclaration
import org.jetbrains.kotlin.ir.util.isPublishedApi
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.IrTypeTransformerVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import java.util.Collections
import java.util.IdentityHashMap

private val DOTNET_GENERIC_OWNER_PROTOTYPE_MEMBER: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_OWNER_PROTOTYPE_MEMBER")

internal val DOTNET_GENERIC_OWNER_CAPABILITY_INTERFACE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_OWNER_CAPABILITY_INTERFACE")

internal val DOTNET_GENERIC_OWNER_CAPABILITY_SLOT: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_OWNER_CAPABILITY_SLOT")

internal val DOTNET_GENERIC_OWNER_CAPABILITY_DISPATCHER: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_OWNER_CAPABILITY_DISPATCHER")

internal val DOTNET_GENERIC_OWNER_SEMANTIC_HOOK: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_OWNER_SEMANTIC_HOOK")

internal val DOTNET_GENERIC_OWNER_FOREIGN_OVERRIDE_PROBE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_OWNER_FOREIGN_OVERRIDE_PROBE")

private val DOTNET_GENERIC_OWNER_VOLATILE_FQ_NAME = FqName("kotlin.concurrent.Volatile")

/** One pristine-IR `newobj` site and the CLR scopes which can provide its generic binders. */
private data class GenericOwnerConstructionSite(
    val call: IrConstructorCall,
    val enclosingFunction: IrFunction?,
    val enclosingClass: IrClass?,
)

/**
 * Records conservative proof obligations for the eventual CLR-generic Kotlin class-owner ABI.
 *
 * This pass is intentionally adjacent to, but independent from, erased generic-interface bridge
 * construction. It normally changes no IR and grants no class permission to use a reified
 * physical owner. An architecture test may explicitly supply one module-local trace recorder;
 * that separate instrumented product preserves evaluation order and records the already-derived
 * call-site index, but is never a production or performance artifact. Keeping the analysis in the
 * production pipeline makes it run over every supported semantic corpus without allowing a
 * partial ABI switch.
 */
internal class DotNetGenericOwnerArchitecturePlanningLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    private val specialBridgeMethods = SpecialBridgeMethods(context)
    private val externalDeclarations = context.externalDeclarationsForLowering()
    private val externalSemanticPrototypesBySource = linkedMapOf<IrSimpleFunction, IrSimpleFunction>()
    private val externalForeignOverrideProbesBySource = linkedMapOf<IrSimpleFunction, IrSimpleFunction>()

    override fun lower(irModule: IrModuleFragment) {
        check(context.genericOwnerArchitecturePlans.isEmpty()) {
            "Internal .NET backend error: generic-owner architecture planning ran more than once"
        }
        check(context.genericOwnerCallRoutes.isEmpty()) {
            "Internal .NET backend error: generic-owner call-route planning ran more than once"
        }

        val owners = mutableListOf<IrClass>()
        val localGenericInterfaces = mutableListOf<IrClass>()
        val producerFunctions = linkedSetOf<IrFunction>()
        val producerInitializers = mutableListOf<ProducerInitializer>()
        val constructionSites = mutableListOf<GenericOwnerConstructionSite>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            private var currentClass: IrClass? = null
            private var currentFunction: IrFunction? = null

            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB) return
                val previousClass = currentClass
                val previousFunction = currentFunction
                currentClass = declaration
                currentFunction = null
                if (declaration.isDotNetGenericInterfaceDeclaration) {
                    localGenericInterfaces += declaration
                }
                if (!declaration.isDotNetResolutionOnlyStdlibDeclaration &&
                    declaration.isDotNetGenericClassDeclaration
                ) {
                    owners += declaration
                }
                declaration.acceptChildrenVoid(this)
                currentClass = previousClass
                currentFunction = previousFunction
            }

            override fun visitFunction(declaration: IrFunction) {
                if (declaration.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB) return
                val previousFunction = currentFunction
                currentFunction = declaration
                producerFunctions += declaration
                declaration.acceptChildrenVoid(this)
                currentFunction = previousFunction
            }

            override fun visitConstructorCall(expression: IrConstructorCall) {
                val constructedClass = expression.symbol.owner.parent as? IrClass
                if (constructedClass?.origin != IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB &&
                    constructedClass?.isDotNetGenericClassDeclaration == true
                ) {
                    constructionSites += GenericOwnerConstructionSite(
                        call = expression,
                        enclosingFunction = currentFunction,
                        enclosingClass = currentClass,
                    )
                }
                expression.acceptChildrenVoid(this)
            }

            override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer) {
                producerInitializers += ProducerInitializer(
                    label = "<initializer:${declaration.startOffset}>",
                    element = declaration.body,
                    owner = declaration.parent as? IrClass,
                )
                declaration.acceptChildrenVoid(this)
            }

            override fun visitField(declaration: IrField) {
                declaration.initializer?.let { initializer ->
                    producerInitializers += ProducerInitializer(
                        label = "<field-initializer:${declaration.name.asString()}>",
                        element = initializer,
                        owner = declaration.parent as? IrClass,
                        implicitWrite = declaration,
                    )
                }
                declaration.acceptChildrenVoid(this)
            }
        })
        check(context.localGenericInterfaceLogicalHazards.isEmpty()) {
            "Internal .NET backend error: local generic-interface logical hazards were frozen more than once"
        }
        localGenericInterfaces.forEach { declaration ->
            val hazard = DotNetLocalGenericInterfaceLogicalHazard(
                owner = declaration.symbol,
                declaredVariances = declaration.typeParameters.map(IrTypeParameter::variance),
            )
            check(context.localGenericInterfaceLogicalHazards.put(declaration.symbol, hazard) == null) {
                "Internal .NET backend error: local generic interface '${declaration.name}' was indexed twice"
            }
        }
        check(context.earlyGenericInterfaceCompleteNaturalAuthorityPlans.isEmpty()) {
            "Internal .NET backend error: early natural-interface authority was planned more than once"
        }
        if (context.configuration.dotNetGenericOwnerRehearsal) {
            val earlyInterfaceAnalysis = DotNetGenericInterfaceCompleteSurfaceVarianceShadowLowering(context)
                .analyze(irModule)
            context.earlyGenericInterfaceCompleteNaturalAuthorityPlans.putAll(
                earlyInterfaceAnalysis.authorityPlans,
            )
        }
        val producerAccesses = producerFunctions.associateWithTo(linkedMapOf()) { function ->
            function.collectDirectAccesses(producerFunctions)
        }
        val initializerAccesses = producerInitializers.associateWithTo(linkedMapOf()) { initializer ->
            initializer.element.collectDirectAccesses(producerFunctions).withImplicitWrite(initializer)
        }

        for (owner in owners) {
            context.genericOwnerArchitecturePlans[owner] = plan(owner, producerAccesses, initializerAccesses)
        }
        linkDetachedOverrideFamilies(producerAccesses, initializerAccesses, constructionSites)
        val callRoutes = GenericOwnerCallRouteAnalyzer(
            producerFunctions = producerFunctions,
            producerInitializers = producerInitializers,
            producerAccesses = producerAccesses,
            initializerAccesses = initializerAccesses,
        ).analyze()
        context.genericOwnerCallRoutes += callRoutes
        if (context.configuration.dotNetGenericOwnerRehearsal) {
            context.localGenericOwnerPhysicalAuthority =
                DotNetLocalGenericOwnerPhysicalAuthority.bindEarly(
                    context.genericOwnerArchitecturePlans.values
                        .filter(DotNetGenericOwnerArchitecturePlan::isReifiedByGenericOwnerRehearsal)
                        .filter { plan -> plan.owner.kind == ClassKind.CLASS }
                        .mapNotNull { plan ->
                            val physicalParameters = plan.owner
                                .genericOwnerPrototypePhysicalGenericParameters()
                                ?: return@mapNotNull null
                            if (physicalParameters.any { parameter ->
                                    parameter.specialConstraints.isNotEmpty() ||
                                            parameter.typeConstraints.isNotEmpty()
                                }
                            ) return@mapNotNull null
                            DotNetLocalGenericOwnerPhysicalTypeInput(
                                identity = DotNetGenericOwnerPhysicalTypeDefIdentity.Local(
                                    plan.owner.symbol,
                                    view = null,
                                ),
                                logicalOwnerName = plan.owner.dotNetPhysicalValueStableName(),
                                genericParameters = physicalParameters.map {
                                    DotNetGenericOwnerPhysicalGenericParameterReference(
                                        DotNetGenericOwnerPhysicalTypeParameterVariance.INVARIANT,
                                        constraints = emptyList(),
                                    )
                                },
                                role = DotNetLocalGenericOwnerPhysicalTypeRole.GENERIC_CLASS,
                            )
                        },
                ).also { binding ->
                    if (binding is DotNetGenericOwnerPhysicalBindingResult.Conflict) {
                        error("Internal .NET backend error: ${binding.reason}")
                    }
                }
            materializeCapabilityFamilies(irModule, callRoutes)
        } else context.configuration.dotNetGenericOwnerCallRouteTraceHooks?.let { hooks ->
            instrumentCallRoutes(irModule, callRoutes, hooks.recorder)
        }
    }

    /**
     * Materializes the non-generic side of every admitted owner family before ordinary bridge
     * selection. The typed source member remains the sole natural C# entry. Each hidden
     * capability slot has the planner's object-domain signature and one private final dispatcher
     * on the same object. Families which require broad Kotlin behavior additionally move their
     * one authoritative body to a protected object-domain semantic hook. The natural typed entry
     * then narrows only its result, while the capability dispatcher never narrows broad inputs.
     */
    private fun materializeCapabilityFamilies(
        irModule: IrModuleFragment,
        callRoutes: List<DotNetGenericOwnerCallRoutePlan>,
    ) {
        check(context.genericOwnerCapabilityInterfaces.isEmpty() &&
                context.genericOwnerCapabilityCallTargets.isEmpty()) {
            "Internal .NET backend error: generic-owner capability materialization ran more than once"
        }
        val admittedPlans = context.genericOwnerArchitecturePlans.values
            .filter(DotNetGenericOwnerArchitecturePlan::isReifiedByGenericOwnerRehearsal)
        check(admittedPlans.none { plan ->
            plan.memberFamilies.values.any { family ->
                family.requiresUnsupportedForeignSemanticOverride(plan.owner)
            }
        }) {
            "Internal .NET backend error: an unsupported foreign semantic override " +
                    "entered generic-owner materialization"
        }
        val admittedPlanOrdinals = admittedPlans.mapIndexed { index, plan -> plan.owner to index }.toMap()
        // Every reified ordinary owner needs a non-generic classifier identity, including a
        // method-free `Marker<T>`. Kotlin star projections and runtime classifier operations are
        // about `Marker<*>`, not one invariant CLR construction such as `Marker<object>`.
        // Member slots remain demand-driven; an owner without callable slots gets a marker-only
        // capability interface.
        val privateCapabilityOwners = callRoutes.asSequence()
            .filter { route ->
                route.routeRequirement == DotNetGenericOwnerCallRouteRequirement.SEMANTIC_CAPABILITY ||
                        route.routeRequirement ==
                        DotNetGenericOwnerCallRouteRequirement.SEMANTIC_INPUT_CAPABILITY ||
                        route.routeRequirement ==
                        DotNetGenericOwnerCallRouteRequirement.SEMANTIC_RESULT_CAPABILITY
            }
            .map(DotNetGenericOwnerCallRoutePlan::calleeOwner)
            .toSet()
        // A generated callable's Kotlin classifier is FunctionN, not its private implementation
        // class. Giving that generic class a second semantic capability also projects its exact
        // FunctionN<T, R> supertype to FunctionN<object, R>, creating two incompatible
        // InvokeExact obligations on one TypeDef. Materialize such a class capability only when
        // an analyzed call actually addresses the private class itself; its ordinary erased and
        // exact FunctionN interfaces continue to carry every callable invocation.
        val capabilityPlans = admittedPlans.filter { plan ->
            plan.disposition != DotNetGenericOwnerCandidateDisposition.RETAINED_NON_ABI_IMPLEMENTATION_OWNER ||
                    plan.owner in privateCapabilityOwners ||
                    (!plan.owner.isDotNetCallableObject && plan.memberFamilies.values.any { family ->
                        DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER in family.roles
                    })
        }
        callRoutes.filter { route ->
            route.routeRequirement == DotNetGenericOwnerCallRouteRequirement.SEMANTIC_CAPABILITY
        }.forEach { route ->
            when (val receiver = route.call.dispatchReceiver) {
                is IrGetValue -> context.genericOwnerCapabilityDeclarations += receiver.symbol.owner
                is IrGetField -> context.genericOwnerCapabilityDeclarations += receiver.symbol.owner
            }
        }
        val files = irModule.files.toSet()
        fun IrClass.containingFile(): IrFile {
            var current = parent
            while (current is IrClass) current = current.parent
            return (current as? IrFile)?.takeIf(files::contains)
                ?: error("Internal .NET backend error: generic owner '${name}' has no module file")
        }
        fun DotNetGenericOwnerArchitecturePlan.capabilityIdentity(): String =
            if (disposition ==
                DotNetGenericOwnerCandidateDisposition.RETAINED_NON_ABI_IMPLEMENTATION_OWNER
            ) {
                val fileName = owner.containingFile().fileEntry.name
                // Several compiler-generated lambdas/anonymous objects can share source
                // offsets and the same synthetic name. Their capabilities are private and have
                // no cross-module ABI, so the stable outer-first plan ordinal is the final
                // collision-free component of their physical TypeDef identity.
                "local:$fileName:${owner.startOffset}:${owner.endOffset}:${owner.name.asString()}:" +
                        admittedPlanOrdinals.getValue(owner)
            } else {
                logicalBindingKey
                    ?: owner.fqNameWhenAvailable?.asString()
                    ?: "${owner.name.asString()}@${owner.startOffset}:${owner.endOffset}"
            }

        for (plan in capabilityPlans) {
            val owner = plan.owner
            val identity = plan.capabilityIdentity()
            val suffix = Integer.toUnsignedString(identity.hashCode(), 16)
            val capability = context.irFactory.buildClass {
                startOffset = owner.startOffset
                endOffset = owner.endOffset
                origin = DOTNET_GENERIC_OWNER_CAPABILITY_INTERFACE
                name = Name.identifier("I${owner.name.asString()}KotlinSemantic$suffix")
                kind = ClassKind.INTERFACE
                modality = Modality.ABSTRACT
                // Arbitrary separately compiled Kotlin consumers must be able to name this
                // producer-owned semantic ABI. It remains compiler-generated and is not a
                // second natural C# owner; ordinary interop uses the public `C<T>` TypeDef.
                visibility = if (
                    plan.disposition ==
                    DotNetGenericOwnerCandidateDisposition.RETAINED_NON_ABI_IMPLEMENTATION_OWNER
                ) {
                    // A local/compiler-generated owner has no separately nameable Kotlin ABI.
                    // Materialize a capability only when its same-compilation semantic route
                    // needs one, and keep that CLR identity private to the producer assembly.
                    DescriptorVisibilities.PRIVATE
                } else {
                    DescriptorVisibilities.PUBLIC
                }
            }.apply {
                parent = owner.containingFile()
                superTypes = listOf(context.irBuiltIns.anyType)
                createThisReceiverParameter()
            }
            (capability.parent as IrFile).declarations += capability
            context.genericOwnerCapabilityInterfaces[owner] = capability
            owner.superTypes += capability.symbol.defaultType
        }

        // A safe cast to C<X> proves only Kotlin's classifier, never X. Its physical result must
        // therefore remain the non-generic capability even when FIR gives the result local the
        // logical concrete type C<X>. Record that carrier before method locals are mapped.
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitVariable(declaration: IrVariable) {
                val cast = declaration.initializer as? IrTypeOperatorCall
                val targetOwner = ((cast?.typeOperand as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                if (cast?.operator == IrTypeOperator.SAFE_CAST &&
                    targetOwner in context.genericOwnerCapabilityInterfaces
                ) {
                    context.genericOwnerCapabilityDeclarations += declaration
                }
                declaration.acceptChildrenVoid(this)
            }
        })

        // A projected derived value must still be usable as every projected base. Mirror only
        // already-admitted generic class edges; erased-only owners retain their old canonical path.
        for (plan in capabilityPlans) {
            val capability = context.genericOwnerCapabilityInterfaces.getValue(plan.owner)
            capability.superTypes += plan.owner.superTypes.mapNotNull { superType ->
                val superOwner = ((superType as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                    ?: return@mapNotNull null
                context.genericOwnerCapabilityInterfaces[superOwner]?.symbol?.defaultType
            }
        }

        val admittedPlansByOwner = admittedPlans.associateBy(DotNetGenericOwnerArchitecturePlan::owner)

        fun overrideRoots(
            source: IrSimpleFunction,
            visiting: Set<IrSimpleFunction> = emptySet(),
        ): List<String> {
            check(source !in visiting) {
                "Internal .NET backend error: generic-owner override roots contain a cycle at '${source.name}'"
            }
            val owner = source.parent as? IrClass
                ?: error("Internal .NET backend error: generic-owner member '${source.name}' has no class owner")
            val plan = admittedPlansByOwner[owner]
            val family = plan?.memberFamilies?.get(source)
            val ownKey = family?.logicalBindingKey
                ?: context.preLoweringDeclarationKeys[source]
                ?: "private:${owner.name.asString()}:${source.startOffset}:${source.name.asString()}"
            val bindings = plan?.overrideBindings?.get(source).orEmpty()
                .filter { binding -> binding.role == DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY }
            if (bindings.isEmpty()) return listOf(ownKey)
            return bindings.flatMap { binding ->
                val overriddenOwner = binding.overriddenSource.parent as? IrClass
                if (overriddenOwner != null && overriddenOwner in admittedPlansByOwner) {
                    overrideRoots(binding.overriddenSource, visiting + source)
                } else {
                    listOf(binding.overriddenLogicalBindingKey ?: ownKey)
                }
            }.distinct().sorted()
        }

        // Materialize every semantic hook before attaching override edges or moving bodies. This
        // makes override linkage independent of declaration order and keeps one body authority:
        // the source typed entry becomes only a typed view over the object-domain hook.
        val semanticHooksBySource = linkedMapOf<IrSimpleFunction, IrSimpleFunction>()
        val semanticHooksByPrototype = linkedMapOf<IrSimpleFunction, IrSimpleFunction>()
        for (plan in admittedPlans) {
            val owner = plan.owner
            plan.memberFamilies.entries.forEachIndexed { memberIndex, entry ->
                val source = entry.key
                val family = entry.value
                if (source.isFakeOverride ||
                    source.parameters.firstOrNull()?.kind != IrParameterKind.DispatchReceiver ||
                    DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK !in family.roles
                ) return@forEachIndexed
                val prototype = checkNotNull(
                    plan.prototypeMembers[source]
                        ?.get(DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
                        ?.function
                ) { "Internal .NET backend error: semantic family lacks its planned prototype" }
                val roots = overrideRoots(source)
                val physicalName = dotNetGenericOwnerPhysicalMemberName(
                    source.dotNetIlMethodName(),
                    roots,
                    DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
                )
                val hook = owner.addFunction {
                    startOffset = source.startOffset
                    endOffset = source.endOffset
                    origin = DOTNET_GENERIC_OWNER_SEMANTIC_HOOK
                    name = Name.identifier(physicalName)
                    visibility = if (DescriptorVisibilities.isPrivate(source.visibility)) {
                        DescriptorVisibilities.PRIVATE
                    } else {
                        DescriptorVisibilities.PROTECTED
                    }
                    modality = source.modality
                    returnType = context.irBuiltIns.anyNType
                }.apply hook@{
                    parameters += createDispatchReceiverParameterWithClassParent()
                    val hookTypeParameters = copyTypeParametersFrom(prototype)
                    val prototypeMethodSubstitution = prototype.typeParameters.zip(hookTypeParameters)
                        .associate { pair -> pair.first.symbol to pair.second.symbol.defaultType }
                    val prototypeMethodSubstitutor =
                        IrTypeSubstitutor(prototypeMethodSubstitution, allowEmptySubstitution = true)
                    returnType = prototypeMethodSubstitutor.substitute(prototype.returnType)
                    prototype.parameters.drop(1).forEach { parameter ->
                        parameters += parameter.copyTo(
                            this@hook,
                            type = prototypeMethodSubstitutor.substitute(parameter.type),
                            varargElementType = parameter.varargElementType?.let(prototypeMethodSubstitutor::substitute),
                            defaultValue = null,
                        )
                    }
                }
                semanticHooksBySource[source] = hook
                context.genericOwnerSemanticHooks[source] = hook
                semanticHooksByPrototype[prototype] = hook
                markSemanticParameterCarriers(
                    owner,
                    source.parameters.drop(1),
                    hook.parameters.drop(1),
                )
                if (family.requiresSemanticResultCapability) {
                    context.genericOwnerCapabilityDeclarations += hook
                    // A semantic Nested<T> result may be either a Kotlin capability-bearing
                    // implementation or the natural I<T> returned by an ordinary C# override.
                    // Only object is an honest common carrier for both physical shapes.
                    context.genericOwnerForeignDispatchDeclarations += hook
                }
            }
        }
        semanticHooksBySource.entries.forEach { entry ->
            val source = entry.key
            val hook = entry.value
            val owner = source.parent as IrClass
            val prototype = checkNotNull(
                admittedPlansByOwner.getValue(owner).prototypeMembers[source]
                    ?.get(DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
                    ?.function
            )
            hook.overriddenSymbols = prototype.overriddenSymbols.mapNotNull { overridden ->
                semanticHooksByPrototype[overridden.owner]?.symbol
                    ?: overridden.takeIf { symbol ->
                        symbol.owner in externalSemanticPrototypesBySource.values
                    }
            }.distinct()
        }
        val foreignOverrideProbesBySource = linkedMapOf<IrSimpleFunction, IrSimpleFunction>()
        val foreignOverrideProbesByPrototype = linkedMapOf<IrSimpleFunction, IrSimpleFunction>()
        val openForeignOverrideProbeSources = semanticHooksBySource.keys.filterTo(linkedSetOf()) { source ->
            val owner = source.parent as IrClass
            val family = admittedPlansByOwner.getValue(owner).memberFamilies.getValue(source)
            owner.kind != ClassKind.INTERFACE && source.modality == Modality.OPEN &&
                    !DescriptorVisibilities.isPrivate(source.visibility) &&
                    family.supportsDirectForeignOverrideProbe() &&
                    (family.returnSlotDomain ==
                            DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT ||
                            family.requiresSemanticResultCapability)
        }
        fun IrSimpleFunction.closesForeignOverrideProbe(): Boolean = allOverridden().any { overridden ->
            overridden in openForeignOverrideProbeSources ||
                    externalDeclarations.genericOwnerMemberFamilyOrNull(overridden)
                        ?.family
                        ?.foreignOverrideProbeMethodName != null
        }
        semanticHooksBySource.entries.forEach { entry ->
            val source = entry.key
            val owner = source.parent as IrClass
            val plan = admittedPlansByOwner.getValue(owner)
            val family = plan.memberFamilies.getValue(source)
            val materializesOpenProbe = source in openForeignOverrideProbeSources
            val materializesClosingProbe = source.modality == Modality.FINAL &&
                    source.closesForeignOverrideProbe()
            if (owner.kind == ClassKind.INTERFACE ||
                (!materializesOpenProbe && !materializesClosingProbe) ||
                DescriptorVisibilities.isPrivate(source.visibility) ||
                !family.supportsDirectForeignOverrideProbe() ||
                family.returnSlotDomain != DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT &&
                !family.requiresSemanticResultCapability
            ) return@forEach
            val prototype = checkNotNull(
                plan.prototypeMembers[source]
                    ?.get(DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
                    ?.function
            )
            val roots = overrideRoots(source)
            val probe = owner.addFunction {
                startOffset = source.startOffset
                endOffset = source.endOffset
                origin = DOTNET_GENERIC_OWNER_FOREIGN_OVERRIDE_PROBE
                name = Name.identifier(dotNetGenericOwnerPhysicalForeignOverrideProbeName(
                    source.dotNetIlMethodName(),
                    roots,
                ))
                visibility = DescriptorVisibilities.PROTECTED
                modality = source.modality
                returnType = context.irBuiltIns.booleanType
            }.apply {
                parameters += createDispatchReceiverParameterWithClassParent()
                copyTypeParametersFrom(prototype)
                // The emitter owns the allocation-free ldvirtftn/ldftn comparison. Keep a valid
                // placeholder body so every ordinary IR invariant remains satisfied meanwhile.
                body = context.createIrBuilder(symbol).irBlockBody {
                    +irReturn(irCall(context.irBuiltIns.eqeqeqSymbol).apply {
                        arguments[0] = irGet(parameters[0])
                        arguments[1] = irGet(parameters[0])
                    })
                }
            }
            foreignOverrideProbesBySource[source] = probe
            foreignOverrideProbesByPrototype[prototype] = probe
            context.genericOwnerForeignOverrideProbeTargets[probe] = source
        }
        foreignOverrideProbesBySource.entries.forEach { probeEntry ->
            val source = probeEntry.key
            val probe = probeEntry.value
            val owner = source.parent as IrClass
            val prototype = checkNotNull(
                admittedPlansByOwner.getValue(owner).prototypeMembers[source]
                    ?.get(DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
                    ?.function
            )
            val externalProbeBySemanticPrototype = externalSemanticPrototypesBySource.mapNotNull { entry ->
                externalForeignOverrideProbesBySource[entry.key]?.let { probe -> entry.value to probe }
            }.toMap()
            probe.overriddenSymbols = prototype.overriddenSymbols.mapNotNull { overridden ->
                foreignOverrideProbesByPrototype[overridden.owner]?.symbol
                    ?: externalProbeBySemanticPrototype[overridden.owner]?.symbol
            }.distinct()
        }
        val exactNoInputProducerCalls = Collections.newSetFromMap(IdentityHashMap<IrCall, Boolean>())
        callRoutes.forEach { route ->
            if (route.receiverProvenance !=
                DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION ||
                route.call.superQualifierSymbol != null ||
                route.callee.parameters.any { parameter ->
                    parameter.kind != IrParameterKind.DispatchReceiver
                }
            ) {
                return@forEach
            }
            val usesExactNaturalEntry = when (route.routeRequirement) {
                DotNetGenericOwnerCallRouteRequirement.EXACT_TYPED_ENTRY -> true
                DotNetGenericOwnerCallRouteRequirement.EXTERNAL_FAMILY_RECORD_REQUIRED -> {
                    if (route.call.symbol.owner in context.externalDefaultArgumentDispatchers) {
                        return@forEach
                    }
                    val source = route.callee.let { candidate ->
                        candidate.resolveFakeOverride()
                            ?: candidate.resolveFakeOverrideMaybeAbstract()
                            ?: candidate
                    }
                    val binding = externalDeclarations.genericOwnerMemberFamilyOrNull(source)
                        ?: return@forEach
                    val exactResultNeedsSemanticRoute =
                        binding.family.requiresSemanticResultCapability
                    !exactResultNeedsSemanticRoute
                }
                else -> false
            }
            if (usesExactNaturalEntry) exactNoInputProducerCalls += route.call
        }
        semanticHooksBySource.entries.forEach { entry ->
            val source = entry.key
            val hook = entry.value
            val owner = source.parent as IrClass
            val plan = admittedPlansByOwner.getValue(owner)
            val family = plan.memberFamilies.getValue(source)
            val memberAccess = plan.memberAccesses[source]
            val pairedBodyCompatibleReasons = setOf(
                DotNetGenericOwnerSemanticHookReason.GENERAL_WIDENED_BODY,
                DotNetGenericOwnerSemanticHookReason.INTERNAL_SEMANTIC_REACHABILITY,
                DotNetGenericOwnerSemanticHookReason.OWNER_RELATIVE_METHOD_BOUND,
                DotNetGenericOwnerSemanticHookReason.RELATIVE_GENERIC_INTERFACE_INPUT,
                DotNetGenericOwnerSemanticHookReason.SEMANTIC_INTERFACE_INPUT,
            )
            var hasOnlyCloneSafeBodyDeclarations = true
            source.body?.acceptVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    if (hasOnlyCloneSafeBodyDeclarations) element.acceptChildrenVoid(this)
                }

                override fun visitDeclaration(declaration: IrDeclarationBase) {
                    if (declaration !is IrVariable) {
                        // A paired family deliberately owns two executable bodies. Variables are
                        // ordinary per-body storage, but a surviving local class/function (also
                        // the declaration embedded in a function expression or rich reference)
                        // has runtime identity. Deep-copying it would create two unrelated
                        // declarations for one Kotlin source identity. Fall back to the single
                        // semantic body plus natural wrapper until the declaration has first been
                        // lifted by the shared lowerings.
                        hasOnlyCloneSafeBodyDeclarations = false
                        return
                    }
                    declaration.acceptChildrenVoid(this)
                }
            })
            val preservesNaturalBody = source.body != null &&
                    hasOnlyCloneSafeBodyDeclarations &&
                    source.modality == Modality.FINAL &&
                    DotNetGenericOwnerSemanticHookReason.SEMANTIC_INTERFACE_INPUT in
                            family.semanticHookReasons &&
                    family.semanticHookReasons.all(pairedBodyCompatibleReasons::contains) &&
                    family.maskedDefaultDispatcher == null &&
                    family.directSuperCallCount == 0 &&
                    plan.overrideBindings[source].isNullOrEmpty() &&
                    source.overriddenSymbols.isEmpty() &&
                    memberAccess?.let { access ->
                        access.transitiveReads.isEmpty() && access.transitiveWrites.isEmpty()
                    } != false
            val semanticBody = if (preservesNaturalBody) {
                val typeParameterMapping = source.typeParameters.zip(hook.typeParameters).toMap()
                val parameterMapping = source.parameters.zip(hook.parameters).toMap()
                source.body!!.deepCopyWithSymbols(
                    initialParent = hook,
                    createTypeRemapper = { IrTypeParameterRemapper(typeParameterMapping) },
                ).transform(
                    object : VariableRemapper(parameterMapping) {
                        override fun visitReturn(expression: IrReturn): IrExpression =
                            super.visitReturn(
                                if (expression.returnTargetSymbol == source.symbol) {
                                    IrReturnImpl(
                                        expression.startOffset,
                                        expression.endOffset,
                                        expression.type,
                                        hook.symbol,
                                        expression.value,
                                    )
                                } else {
                                    expression
                                },
                            )
                    },
                    null,
                )
            } else {
                source.moveBodyTo(hook)
            }
            check(context.genericOwnerMemberBodyPlacements.put(
                source,
                if (preservesNaturalBody) {
                    DotNetGenericOwnerMemberBodyPlacement.PAIRED_NATURAL_AND_SEMANTIC
                } else {
                    DotNetGenericOwnerMemberBodyPlacement.SEMANTIC_BODY_WITH_NATURAL_WRAPPER
                },
            ) == null) {
                "Internal .NET backend error: '${owner.name}.${source.name}' selected multiple " +
                        "semantic body placements"
            }
            hook.body = semanticBody?.also { body ->
                if (context.configuration.dotNetGenericOwnerRehearsal) {
                    DotNetGenericOwnerPhysicalValueShadowAnalysis(context).captureBeforeSemanticRemap(
                        owner = owner,
                        source = source,
                        physical = hook,
                        body = body,
                    )
                }

                fun IrSimpleFunction.hasRecordedExactNaturalResultPolicy(): Boolean {
                    val declaration = resolveFakeOverride()
                        ?: resolveFakeOverrideMaybeAbstract()
                        ?: this
                    val declarationOwner = declaration.parent as? IrClass ?: return false
                    val localFamily = admittedPlansByOwner[declarationOwner]
                        ?.memberFamilies?.get(declaration)
                    if (localFamily != null) return !localFamily.requiresSemanticResultCapability
                    val externalFamily = externalDeclarations
                        .genericOwnerMemberFamilyOrNull(declaration)?.family
                        ?: return false
                    return !externalFamily.requiresSemanticResultCapability
                }

                fun IrType.typeParameterPolarities(
                    parameter: IrTypeParameter,
                    polarity: TypePolarity,
                ): Set<TypePolarity> {
                    val simple = this as? IrSimpleType ?: return emptySet()
                    if (simple.classifier == parameter.symbol) return setOf(polarity)
                    val classifier = (simple.classifier as? IrClassSymbol)?.owner ?: return emptySet()
                    return simple.arguments.withIndex().flatMapTo(linkedSetOf()) { indexedArgument ->
                        val projection = indexedArgument.value as? IrTypeProjection
                            ?: return@flatMapTo emptySet()
                        val declarationVariance = classifier.typeParameters
                            .getOrNull(indexedArgument.index)
                            ?.variance
                            ?: Variance.INVARIANT
                        val effectiveVariance = if (projection.variance == Variance.INVARIANT) {
                            declarationVariance
                        } else {
                            projection.variance
                        }
                        projection.type.typeParameterPolarities(
                            parameter,
                            polarity.through(effectiveVariance),
                        )
                    }
                }

                /**
                 * Exactness inside a semantic body is a per-value dependency fact. It is never
                 * inferred from a desired logical type: the current receiver, a producer-proven
                 * state read, an authority-recorded result, or a fully proved constructor must
                 * provide every owner parameter retained in the physical carrier.
                 */
                class SemanticBodyExactValueAnalysis {
                    private val exactLocals =
                        IdentityHashMap<IrVariable, Set<IrTypeParameterSymbol>>()
                    private val exactElements =
                        Collections.newSetFromMap(IdentityHashMap<IrElement, Boolean>())
                    private val exactHelperMethodArguments =
                        IdentityHashMap<IrCall, Map<Int, IrType>>()
                    private val exactHelperResultTypes =
                        IdentityHashMap<IrCall, IrType>()
                    private val assignedLocals =
                        Collections.newSetFromMap(IdentityHashMap<IrValueDeclaration, Boolean>())
                    private val currentReceiverDependencies =
                        hook.parameters[0].type.dotNetGenericOwnerParameterDependencies(owner)

                    init {
                        body.acceptVoid(object : IrVisitorVoid() {
                            override fun visitElement(element: IrElement) {
                                element.acceptChildrenVoid(this)
                            }

                            override fun visitClass(declaration: IrClass) = Unit

                            override fun visitFunction(declaration: IrFunction) = Unit

                            override fun visitSetValue(expression: IrSetValue) {
                                assignedLocals += expression.symbol.owner
                                expression.acceptChildrenVoid(this)
                            }
                        })
                        var changed: Boolean
                        do {
                            changed = false
                            body.acceptVoid(object : IrVisitorVoid() {
                                override fun visitElement(element: IrElement) {
                                    element.acceptChildrenVoid(this)
                                }

                                override fun visitClass(declaration: IrClass) = Unit

                                override fun visitFunction(declaration: IrFunction) = Unit

                                override fun visitVariable(declaration: IrVariable) {
                                    val initializer = declaration.initializer
                                    val required = declaration.type
                                        .dotNetGenericOwnerParameterDependencies(owner)
                                    val produced = initializer?.let(::dependenciesOrNull)
                                    if (!declaration.isVar && declaration !in assignedLocals &&
                                        initializer != null &&
                                        required.isNotEmpty() && produced == required &&
                                        initializer.type.sameInvariantTypeAs(declaration.type) &&
                                        exactLocals.put(declaration, required) != required
                                    ) {
                                        changed = true
                                    }
                                    declaration.acceptChildrenVoid(this)
                                }
                            })
                        } while (changed)
                        // Freeze the analysis before any type is remapped. Querying a partially
                        // rewritten constructor/call would make transfer order influence physical
                        // truth and could let an earlier broad rewrite invalidate a later exact
                        // component of the same value.
                        body.acceptVoid(object : IrVisitorVoid() {
                            override fun visitElement(element: IrElement) {
                                if (element is IrExpression && dependenciesOrNull(element) != null &&
                                    (element !is IrCall ||
                                            !exactHelperMethodArguments.containsKey(element))
                                ) {
                                    exactElements += element
                                }
                                element.acceptChildrenVoid(this)
                            }

                            override fun visitClass(declaration: IrClass) = Unit

                            override fun visitFunction(declaration: IrFunction) = Unit

                            override fun visitVariable(declaration: IrVariable) {
                                if (exactLocals.containsKey(declaration)) exactElements += declaration
                                declaration.acceptChildrenVoid(this)
                            }
                        })
                    }

                    fun preserves(container: IrElement): Boolean = container in exactElements

                    fun restoreExactHelperCallCarriers() {
                        exactHelperMethodArguments.entries.forEach { entry ->
                            val call = entry.key
                            val arguments = entry.value
                            arguments.entries.forEach { argumentEntry ->
                                call.typeArguments[argumentEntry.key] = argumentEntry.value
                            }
                            exactHelperResultTypes[call]?.let { resultType -> call.type = resultType }
                        }
                    }

                    private fun dependenciesOrNull(
                        expression: IrExpression,
                    ): Set<IrTypeParameterSymbol>? = when (expression) {
                        is IrGetValue -> when {
                            expression.symbol.owner === hook.parameters[0] ->
                                currentReceiverDependencies.takeIf { dependencies ->
                                    dependencies.isNotEmpty()
                                }
                            expression.symbol.owner is IrVariable ->
                                exactLocals[expression.symbol.owner as IrVariable]
                            else -> null
                        }
                        is IrTypeOperatorCall ->
                            if ((expression.operator == IrTypeOperator.IMPLICIT_CAST ||
                                    expression.operator == IrTypeOperator.IMPLICIT_NOTNULL) &&
                                expression.type.sameInvariantTypeAs(expression.argument.type)
                            ) dependenciesOrNull(expression.argument) else null
                        is IrGetField -> fieldDependenciesOrNull(expression)
                        is IrConstructorCall -> constructorDependenciesOrNull(expression)
                        is IrCall -> callDependenciesOrNull(expression)
                        is IrContainerExpression ->
                            (expression.statements.lastOrNull() as? IrExpression)
                                ?.let(::dependenciesOrNull)
                        else -> null
                    }

                    private fun fieldDependenciesOrNull(
                        expression: IrGetField,
                    ): Set<IrTypeParameterSymbol>? {
                        val field = expression.symbol.owner
                        if (field.parent !== owner) return null
                        val receiverDependencies = expression.receiver?.let(::dependenciesOrNull)
                            ?: return null
                        val state = context.genericOwnerArchitecturePlans[owner]
                            ?.stateCarriers?.get(field) ?: return null
                        if (state.requirement !in setOf(
                                DotNetGenericOwnerStateCarrierRequirement.DECLARATION_INDEPENDENT_STORAGE,
                                DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN,
                            )
                        ) return null
                        val result = field.type.dotNetGenericOwnerParameterDependencies(owner)
                        return result.takeIf { dependencies ->
                            dependencies.isNotEmpty() && receiverDependencies.containsAll(dependencies)
                        }
                    }

                    private fun constructorDependenciesOrNull(
                        expression: IrConstructorCall,
                    ): Set<IrTypeParameterSymbol>? {
                        val use = expression.dotNetExactGenericOwnerConstructorUseOrNull(owner)
                            ?: return null
                        val targetPlan = context.genericOwnerArchitecturePlans[use.constructedClass]
                            ?: return null
                        val constructorPlan = targetPlan.constructors.singleOrNull { constructor ->
                            constructor.source === expression.symbol.owner
                        }
                        if (!targetPlan.isReifiedByGenericOwnerRehearsal || constructorPlan == null ||
                            constructorPlan.parameterSlotDomains.size != expression.symbol.owner.parameters.size
                        ) return null
                        if (!use.determiningUses.all { determiningUse ->
                                val argument = expression.arguments
                                    .getOrNull(determiningUse.parameterIndex)
                                    ?: return@all false
                                determiningUse.parameterIndex in
                                        constructorPlan.semanticObjectParameterIndices ||
                                        (constructorPlan.parameterSlotDomains[
                                            determiningUse.parameterIndex
                                        ] == DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT &&
                                                dependenciesOrNull(argument) ==
                                                determiningUse.requiredOwnerParameters &&
                                                argument.type.hasAdmittedExactPhysicalView(
                                                    determiningUse.substitutedParameterType,
                                                    owner,
                                                ))
                            }
                        ) return null
                        return use.ownerParameterDependencies
                    }

                    private fun callDependenciesOrNull(
                        expression: IrCall,
                    ): Set<IrTypeParameterSymbol>? {
                        if (expression.superQualifierSymbol != null) return null
                        exactReceiverAnchoredHelperDependenciesOrNull(expression)?.let { return it }
                        val result = expression.type.dotNetGenericOwnerParameterDependencies(owner)
                        if (result.isEmpty()) return null
                        if (expression in exactNoInputProducerCalls) return result
                        val callee = expression.symbol.owner.let { candidate ->
                            candidate.resolveFakeOverride()
                                ?: candidate.resolveFakeOverrideMaybeAbstract()
                                ?: candidate
                        }
                        val receiverDependencies = expression.dispatchReceiver
                            ?.let(::dependenciesOrNull) ?: return null
                        val exactField = callee.exactTypedStateGetterBackingFieldOrNull()
                        if (callee.parent === owner && exactField != null) {
                            val state = context.genericOwnerArchitecturePlans[owner]
                                ?.stateCarriers?.get(exactField) ?: return null
                            if (state.requirement in setOf(
                                    DotNetGenericOwnerStateCarrierRequirement.DECLARATION_INDEPENDENT_STORAGE,
                                    DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN,
                                ) && receiverDependencies.containsAll(result)
                            ) return result
                        }
                        val calleeOwner = callee.parent as? IrClass ?: return null
                        if (callee.parameters.any { parameter ->
                                parameter.kind != IrParameterKind.DispatchReceiver
                            } ||
                            !DotNetRuntimeTypes.usesDeclaredViewByDefaultInRehearsal(calleeOwner) ||
                            !callee.hasRecordedExactNaturalResultPolicy() ||
                            !receiverDependencies.containsAll(result)
                        ) return null
                        return result
                    }

                    /**
                     * Selects each helper MethodSpec argument independently. An owner-dependent
                     * method parameter remains exact only when the exact extension receiver fixes
                     * it. Other owner-dependent method parameters use their semantic carrier.
                     * Inputs which consume an exact parameter must then either prove that exact
                     * physical view or use the Runtime's declaration-erased FunctionN carrier in
                     * an output-only position. Thus `<A, B>` may become `<!T, object>`; exact A
                     * never lends authority to an unrelated B merely because both were logically
                     * substituted with the same Kotlin owner parameter.
                     */
                    private fun exactReceiverAnchoredHelperDependenciesOrNull(
                        expression: IrCall,
                    ): Set<IrTypeParameterSymbol>? {
                        val callee = expression.symbol.owner
                        if (callee.parameters.any { parameter ->
                                parameter.kind == IrParameterKind.DispatchReceiver
                            }
                        ) return null
                        val receiverIndex = callee.parameters.indexOfFirst { parameter ->
                            parameter.kind == IrParameterKind.ExtensionReceiver
                        }
                        if (receiverIndex < 0) return null
                        val receiver = expression.arguments.getOrNull(receiverIndex) ?: return null
                        val receiverDependencies = dependenciesOrNull(receiver) ?: return null
                        val receiverType = callee.parameters[receiverIndex].type
                        val substitutions = callee.typeParameters.mapIndexed { index, parameter ->
                            val argument = expression.typeArguments.getOrNull(index) ?: return null
                            parameter.symbol to argument
                        }.toMap()
                        val ownerDependentIndices = callee.typeParameters.indices.filter { index ->
                            substitutions.getValue(callee.typeParameters[index].symbol)
                                .dotNetGenericOwnerParameterDependencies(owner).isNotEmpty()
                        }
                        if (ownerDependentIndices.isEmpty()) return null
                        val exactIndices = ownerDependentIndices.filterTo(linkedSetOf()) { index ->
                            val parameter = callee.typeParameters[index]
                            val argument = substitutions.getValue(parameter.symbol)
                            val dependencies = argument.dotNetGenericOwnerParameterDependencies(owner)
                            !argument.hasUnsupportedDotNetExactGenericOwnerDependency(owner) &&
                                    receiverDependencies.containsAll(dependencies) &&
                                    receiverType.typeParameterPolarities(
                                        parameter,
                                        TypePolarity.OUT,
                                    ).isNotEmpty()
                        }
                        if (exactIndices.isEmpty()) return null
                        val physicalSubstitutions = callee.typeParameters.indices.associate { index ->
                            val parameter = callee.typeParameters[index]
                            val argument = substitutions.getValue(parameter.symbol)
                            parameter.symbol to if (index in exactIndices) {
                                argument
                            } else {
                                argument.toGenericOwnerSemanticType(owner)
                            }
                        }
                        val substitutor = IrTypeSubstitutor(
                            physicalSubstitutions,
                            allowEmptySubstitution = true,
                        )
                        val expectedReceiver = substitutor.substitute(receiverType)
                        val unsupportedReceiver =
                            expectedReceiver.hasUnsupportedDotNetExactGenericOwnerDependency(owner)
                        val hasReceiverView = receiver.type.hasAdmittedExactPhysicalView(
                            expectedReceiver,
                            owner,
                        )
                        if (unsupportedReceiver || !hasReceiverView) return null

                        callee.parameters.withIndex().forEach { indexedParameter ->
                            if (indexedParameter.index == receiverIndex) return@forEach
                            val exactParameters = exactIndices.map { index ->
                                callee.typeParameters[index]
                            }.filter { parameter ->
                                indexedParameter.value.type.typeParameterPolarities(
                                    parameter,
                                    TypePolarity.IN,
                                ).isNotEmpty()
                            }
                            if (exactParameters.isEmpty()) return@forEach
                            val usesErasedOutputOnlyCallable = indexedParameter.value.type.isFunction() &&
                                    exactParameters.all { parameter ->
                                        indexedParameter.value.type.typeParameterPolarities(
                                            parameter,
                                            TypePolarity.IN,
                                        ).all { occurrence -> occurrence == TypePolarity.OUT }
                                    }
                            if (usesErasedOutputOnlyCallable) return@forEach
                            val expectedType = substitutor.substitute(indexedParameter.value.type)
                            if (expectedType.hasUnsupportedDotNetExactGenericOwnerDependency(owner)) {
                                return null
                            }
                            val argument = expression.arguments.getOrNull(indexedParameter.index)
                                ?: return null
                            val requiredDependencies = expectedType
                                .dotNetGenericOwnerParameterDependencies(owner)
                            if (dependenciesOrNull(argument) != requiredDependencies ||
                                !argument.type.hasAdmittedExactPhysicalView(expectedType, owner)
                            ) {
                                return null
                            }
                        }
                        val resultType = substitutor.substitute(callee.returnType)
                        if (resultType.hasUnsupportedDotNetExactGenericOwnerDependency(owner)) return null
                        exactHelperMethodArguments[expression] = exactIndices.associateWith { index ->
                            substitutions.getValue(callee.typeParameters[index].symbol)
                        }
                        exactHelperResultTypes[expression] = resultType
                        return resultType.dotNetGenericOwnerParameterDependencies(owner)
                            .takeIf { dependencies -> dependencies.isNotEmpty() }
                    }

                }

                val exactValueAnalysis = SemanticBodyExactValueAnalysis()

                // The moved semantic body is declaration-erased, not merely its public
                // signature. Remap every owner-dependent occurrence: generated equals bodies,
                // private `value as T` helpers, nested C<T> applications and generic intrinsic
                // arguments must all operate in the same object/capability domain. Leaving even
                // one body-local T behind would reconstruct this hook's particular CLR !T and
                // reject a candidate that already passed Kotlin's classifier-only check.
                body.acceptVoid(object : IrTypeTransformerVoid() {
                    override fun <Type : IrType?> transformTypeRecursively(
                        container: IrElement,
                        type: Type,
                    ): Type {
                        if (type?.referencesGenericOwnerParameter(owner) == true &&
                            exactValueAnalysis.preserves(container)
                        ) {
                            // Preserve only a carrier whose complete owner-parameter dependency
                            // vector was established from physical receiver/state/result/
                            // constructor facts. One broad semantic input therefore cannot erase
                            // unrelated exact receiver-derived values, while a logical cast or
                            // desired destination type can never create exactness.
                            return type
                        }
                        @Suppress("UNCHECKED_CAST")
                        return type?.toGenericOwnerSemanticType(owner) as Type
                    }
                })
                exactValueAnalysis.restoreExactHelperCallCarriers()
            }
            if (preservesNaturalBody || source.body == null) return@forEach
            source.body = context.createIrBuilder(source.symbol).irBlockBody {
                val hookMethodSubstitution = hook.typeParameters.zip(source.typeParameters)
                    .associate { pair -> pair.first.symbol to pair.second.symbol.defaultType }
                val hookMethodSubstitutor =
                    IrTypeSubstitutor(hookMethodSubstitution, allowEmptySubstitution = true)
                fun hookType(type: IrType): IrType = hookMethodSubstitutor.substitute(type)
                val call = irCall(hook.symbol, hookType(hook.returnType)).apply {
                    arguments[0] = irGet(source.parameters[0])
                    source.typeParameters.forEachIndexed { index, parameter ->
                        typeArguments[index] = parameter.symbol.defaultType
                    }
                    hook.parameters.drop(1).forEachIndexed { index, parameter ->
                        val argument = irGet(source.parameters[index + 1])
                        val targetType = hookType(parameter.type)
                        arguments[index + 1] = if (argument.type == targetType) argument
                        else irImplicitCast(argument, targetType)
                    }
                }
                val result = if (call.type == source.returnType) call
                else irImplicitCast(call, source.returnType)
                +irReturn(result)
            }
        }
        val directlyWrittenSemanticFields = linkedSetOf<IrField>()
        semanticHooksBySource.values.forEach { hook ->
            hook.body?.acceptVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitSetField(expression: IrSetField) {
                    directlyWrittenSemanticFields += expression.symbol.owner
                    expression.acceptChildrenVoid(this)
                }
            })
        }
        context.genericOwnerArchitecturePlans.values.forEach { plan ->
            val fields = plan.stateCarriers.keys.intersect(directlyWrittenSemanticFields)
            if (fields.isEmpty()) return@forEach
            check(fields.all { field ->
                plan.stateCarriers.getValue(field).requirement in setOf(
                    DotNetGenericOwnerStateCarrierRequirement.DECLARATION_INDEPENDENT_STORAGE,
                    DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN,
                    DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED,
                )
            }) {
                "Internal .NET backend error: materialized semantic hook exposed state after " +
                        "generic-owner family/state closure"
            }
        }

        val slotsBySource = linkedMapOf<IrSimpleFunction, IrSimpleFunction>()
        val defaultSlotsBySource = linkedMapOf<IrSimpleFunction, IrSimpleFunction>()
        for (plan in admittedPlans) {
            val owner = plan.owner
            val capability = context.genericOwnerCapabilityInterfaces[owner] ?: continue
            plan.memberFamilies.entries.forEachIndexed { memberIndex, entry ->
                val source = entry.key
                val family = entry.value
                if (source.isFakeOverride ||
                    source.parameters.firstOrNull()?.kind != IrParameterKind.DispatchReceiver ||
                    DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER !in family.roles
                ) return@forEachIndexed
                val prototype = checkNotNull(
                    plan.prototypeMembers[source]
                        ?.get(DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)
                        ?.function
                ) { "Internal .NET backend error: capability family lacks its planned prototype" }
                val logicalRoots = overrideRoots(source)
                val physicalName = dotNetGenericOwnerPhysicalMemberName(
                    source.dotNetIlMethodName(),
                    logicalRoots.sorted(),
                    DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
                )
                val slot = capability.addFunction {
                    startOffset = source.startOffset
                    endOffset = source.endOffset
                    origin = DOTNET_GENERIC_OWNER_CAPABILITY_SLOT
                    name = Name.identifier(physicalName)
                    visibility = DescriptorVisibilities.PUBLIC
                    modality = Modality.ABSTRACT
                    returnType = context.irBuiltIns.anyNType
                }.apply slot@{
                    parameters += createDispatchReceiverParameterWithClassParent()
                    val slotTypeParameters = copyTypeParametersFrom(prototype)
                    val methodSubstitution = prototype.typeParameters.zip(slotTypeParameters).associate { pair ->
                        pair.first.symbol to pair.second.symbol.defaultType
                    }
                    val methodSubstitutor = IrTypeSubstitutor(methodSubstitution, allowEmptySubstitution = true)
                    returnType = methodSubstitutor.substitute(prototype.returnType)
                    prototype.parameters.drop(1).forEach { parameter ->
                        parameters += parameter.copyTo(
                            this@slot,
                            type = methodSubstitutor.substitute(parameter.type),
                            varargElementType = parameter.varargElementType?.let(methodSubstitutor::substitute),
                            defaultValue = null,
                        )
                    }
                }
                markSemanticParameterCarriers(
                    owner,
                    source.parameters.drop(1),
                    slot.parameters.drop(1),
                )
                val returnsForeignInterfaceConstruction = family.requiresSemanticResultCapability
                if (returnsForeignInterfaceConstruction) {
                    context.genericOwnerCapabilityDeclarations += slot
                    context.genericOwnerForeignDispatchDeclarations += slot
                }
                slotsBySource[source] = slot
                context.genericOwnerCapabilitySlots[source] = slot

                val dispatcher = owner.addFunction {
                    startOffset = source.startOffset
                    endOffset = source.endOffset
                    origin = DOTNET_GENERIC_OWNER_CAPABILITY_DISPATCHER
                    name = Name.identifier(physicalName)
                    visibility = DescriptorVisibilities.PRIVATE
                    modality = Modality.FINAL
                    returnType = context.irBuiltIns.anyNType
                }.apply dispatcher@{
                    overriddenSymbols = listOf(slot.symbol)
                    parameters += createDispatchReceiverParameterWithClassParent()
                    val dispatcherTypeParameters = copyTypeParametersFrom(slot)
                    val slotMethodSubstitution = slot.typeParameters.zip(dispatcherTypeParameters).associate { pair ->
                        pair.first.symbol to pair.second.symbol.defaultType
                    }
                    val slotMethodSubstitutor =
                        IrTypeSubstitutor(slotMethodSubstitution, allowEmptySubstitution = true)
                    returnType = slotMethodSubstitutor.substitute(slot.returnType)
                    slot.parameters.drop(1).forEach { parameter ->
                        parameters += parameter.copyTo(
                            this@dispatcher,
                            type = slotMethodSubstitutor.substitute(parameter.type),
                            varargElementType = parameter.varargElementType?.let(slotMethodSubstitutor::substitute),
                            defaultValue = null,
                        )
                    }
                    body = context.createIrBuilder(symbol).irBlockBody {
                        val target = semanticHooksBySource[source] ?: source
                        val sourceMethodSubstitution = source.typeParameters.zip(dispatcherTypeParameters)
                            .associate { pair -> pair.first.symbol to pair.second.symbol.defaultType }
                        val sourceMethodSubstitutor =
                            IrTypeSubstitutor(sourceMethodSubstitution, allowEmptySubstitution = true)
                        val targetMethodSubstitution = target.typeParameters.zip(dispatcherTypeParameters)
                            .associate { pair -> pair.first.symbol to pair.second.symbol.defaultType }
                        val targetMethodSubstitutor =
                            IrTypeSubstitutor(targetMethodSubstitution, allowEmptySubstitution = true)
                        fun targetType(type: IrType): IrType = targetMethodSubstitutor.substitute(type)
                        specialBridgeMethods.findSpecialWithOverride(source, includeSelf = true)
                            ?.second
                            ?.let { info ->
                                val dispatcherParameters = this@dispatcher.parameters.drop(1)
                                val sourceParameters = source.parameters.drop(1)
                                if (info.argumentsToCheck > dispatcherParameters.size ||
                                    info.argumentsToCheck > sourceParameters.size
                                ) {
                                    error(
                                        "Internal .NET backend error: generic-owner special " +
                                                "dispatcher argument count mismatch"
                                    )
                                }
                                repeat(info.argumentsToCheck) { index ->
                                    val parameter = dispatcherParameters[index]
                                    val checkedType = sourceMethodSubstitutor.substitute(
                                        sourceParameters[index].type
                                    )
                                    if (parameter.type != checkedType) {
                                        +irIfThen(
                                            context.irBuiltIns.unitType,
                                            irNot(irIs(irGet(parameter), checkedType)),
                                            irReturn(info.defaultValueGenerator(this@dispatcher)),
                                        )
                                    }
                                }
                            }
                        val call = irCall(target.symbol, targetType(target.returnType)).apply {
                            arguments[0] = irGet(this@dispatcher.parameters[0])
                            dispatcherTypeParameters.forEachIndexed { index, parameter ->
                                typeArguments[index] = parameter.symbol.defaultType
                            }
                            target.parameters.drop(1).forEachIndexed { index, parameter ->
                                val argument = irGet(this@dispatcher.parameters[index + 1])
                                val parameterType = targetType(parameter.type)
                                arguments[index + 1] = if (argument.type == parameterType) argument
                                else irImplicitCast(argument, parameterType)
                            }
                        }
                        val result = if (call.type == this@dispatcher.returnType) call
                        else irImplicitCast(call, this@dispatcher.returnType)
                        +irReturn(result)
                    }
                }
                markSemanticParameterCarriers(
                    owner,
                    source.parameters.drop(1),
                    dispatcher.parameters.drop(1),
                )
                if (returnsForeignInterfaceConstruction) {
                    context.genericOwnerCapabilityDeclarations += dispatcher
                    context.genericOwnerForeignDispatchDeclarations += dispatcher
                }
                context.genericOwnerCapabilityDispatchers[source] = dispatcher
                val semanticHook = semanticHooksBySource[source]
                val foreignOverrideProbe = foreignOverrideProbesBySource[source]
                if (semanticHook != null && foreignOverrideProbe != null) {
                    // The object-domain result cannot in general pass through the typed wrapper:
                    // an incompatible value installed through @UnsafeVariance must remain readable
                    // from a widened Kotlin view. A direct C# subclass, however, overrides only the
                    // natural typed slot. The emitter calls the most-derived Kotlin probe without
                    // reflection or allocation; that probe identifies a still-later foreign typed
                    // override, while the ordinary Kotlin/base path keeps the raw semantic hook.
                    // Both branches must therefore share the object carrier promised by this
                    // specialized emitter route, even when later interface admission makes the
                    // logical result (for example Set<Any?>) CLR-reference-shaped on its own.
                    context.genericOwnerForeignDispatchDeclarations += semanticHook
                    context.genericOwnerForeignDispatchDeclarations += slot
                    context.genericOwnerForeignDispatchDeclarations += dispatcher
                    context.genericOwnerDirectForeignOverrideDispatches[dispatcher] =
                        org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerDirectForeignOverrideDispatch(
                            typedEntry = source,
                            semanticHook = semanticHook,
                            foreignOverrideProbe = foreignOverrideProbe,
                        )
                }

                family.maskedDefaultDispatcher?.let { helper ->
                    val requiresSemanticDefaultResult = family.requiresSemanticResultCapability
                    if (requiresSemanticDefaultResult) {
                        // The common masked helper still owns default-expression evaluation, but
                        // its final virtual call is routed to this family's semantic dispatcher.
                        // Give the compiler-only helper the same object result carrier so that it
                        // cannot narrow an I<int> result back to the logical I<object> between the
                        // semantic call and the capability default entry.
                        context.genericOwnerCapabilityDeclarations += helper
                        context.genericOwnerForeignDispatchDeclarations += helper
                    }
                    val movedReceiver = helper.parameters.singleOrNull { parameter ->
                        parameter.origin == IrDeclarationOrigin.MOVED_DISPATCH_RECEIVER
                    } ?: error(
                        "Internal .NET backend error: generic-owner default helper lacks its moved receiver"
                    )
                    val helperExplicitParameters = helper.parameters.filterNot { parameter ->
                        parameter === movedReceiver
                    }
                    // The common helper is itself a physical MethodDef and receives every
                    // explicit argument before evaluating the default mask. Give its parameters
                    // the same semantic carrier policy as the generated slot/dispatcher. Merely
                    // widening those later entries would leave an intermediate I<object> cast in
                    // front of a legal Kotlin I<int> value whenever another argument is omitted.
                    markSemanticParameterCarriers(
                        owner,
                        helperExplicitParameters,
                        helperExplicitParameters,
                        family.semanticObjectParameterIndices,
                    )
                    val defaultPhysicalName = dotNetGenericOwnerPhysicalMemberName(
                        helper.dotNetIlMethodName(),
                        logicalRoots.sorted(),
                        DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
                    )
                    val defaultSlot = capability.addFunction {
                        startOffset = helper.startOffset
                        endOffset = helper.endOffset
                        origin = DOTNET_GENERIC_OWNER_CAPABILITY_SLOT
                        name = Name.identifier(defaultPhysicalName)
                        visibility = DescriptorVisibilities.PUBLIC
                        modality = Modality.ABSTRACT
                        returnType = context.irBuiltIns.anyNType
                    }.apply defaultSlot@{
                        parameters += createDispatchReceiverParameterWithClassParent()
                        val slotTypeParameters = copyTypeParametersFrom(helper)
                        val helperMethodSubstitution = helper.typeParameters.zip(slotTypeParameters)
                            .associate { pair -> pair.first.symbol to pair.second.symbol.defaultType }
                        val helperMethodSubstitutor =
                            IrTypeSubstitutor(helperMethodSubstitution, allowEmptySubstitution = true)
                        fun slotType(type: IrType): IrType = helperMethodSubstitutor.substitute(
                            type.toGenericOwnerSemanticType(owner)
                        )
                        returnType = if (requiresSemanticDefaultResult) {
                            context.irBuiltIns.anyNType
                        } else {
                            slotType(helper.returnType)
                        }
                        helperExplicitParameters.forEach { parameter ->
                            parameters += parameter.copyTo(
                                this@defaultSlot,
                                type = slotType(parameter.type),
                                varargElementType = parameter.varargElementType?.let(::slotType),
                                defaultValue = null,
                            )
                        }
                    }
                    markSemanticParameterCarriers(
                        owner,
                        helperExplicitParameters,
                        defaultSlot.parameters.drop(1),
                        family.semanticObjectParameterIndices,
                    )
                    if (requiresSemanticDefaultResult) {
                        context.genericOwnerCapabilityDeclarations += defaultSlot
                        context.genericOwnerForeignDispatchDeclarations += defaultSlot
                    }
                    defaultSlotsBySource[source] = defaultSlot
                    context.genericOwnerDefaultCapabilitySlots[source] = defaultSlot

                    val defaultDispatcher = owner.addFunction {
                        startOffset = helper.startOffset
                        endOffset = helper.endOffset
                        origin = DOTNET_GENERIC_OWNER_CAPABILITY_DISPATCHER
                        name = Name.identifier(defaultPhysicalName)
                        visibility = DescriptorVisibilities.PRIVATE
                        modality = Modality.FINAL
                        returnType = context.irBuiltIns.anyNType
                    }.apply defaultDispatcher@{
                        overriddenSymbols = listOf(defaultSlot.symbol)
                        parameters += createDispatchReceiverParameterWithClassParent()
                        val dispatcherTypeParameters = copyTypeParametersFrom(defaultSlot)
                        val slotMethodSubstitution = defaultSlot.typeParameters.zip(dispatcherTypeParameters)
                            .associate { pair -> pair.first.symbol to pair.second.symbol.defaultType }
                        val slotMethodSubstitutor =
                            IrTypeSubstitutor(slotMethodSubstitution, allowEmptySubstitution = true)
                        returnType = slotMethodSubstitutor.substitute(defaultSlot.returnType)
                        defaultSlot.parameters.drop(1).forEach { parameter ->
                            parameters += parameter.copyTo(
                                this@defaultDispatcher,
                                type = slotMethodSubstitutor.substitute(parameter.type),
                                varargElementType = parameter.varargElementType?.let(slotMethodSubstitutor::substitute),
                                defaultValue = null,
                            )
                        }
                        body = context.createIrBuilder(symbol).irBlockBody {
                            val helperMethodSubstitution = helper.typeParameters.zip(dispatcherTypeParameters)
                                .associate { pair -> pair.first.symbol to pair.second.symbol.defaultType }
                            val helperMethodSubstitutor =
                                IrTypeSubstitutor(helperMethodSubstitution, allowEmptySubstitution = true)
                            fun helperType(type: IrType): IrType = helperMethodSubstitutor.substitute(type)
                            var explicitIndex = 1
                            val call = irCall(helper.symbol, helperType(helper.returnType)).apply {
                                dispatcherTypeParameters.forEachIndexed { index, parameter ->
                                    typeArguments[index] = parameter.symbol.defaultType
                                }
                                helper.parameters.forEach { parameter ->
                                    val argument = if (parameter === movedReceiver) {
                                        irGet(this@defaultDispatcher.parameters[0])
                                    } else {
                                        irGet(this@defaultDispatcher.parameters[explicitIndex++])
                                    }
                                    val targetType = helperType(parameter.type)
                                    arguments[parameter.indexInParameters] =
                                        if (argument.type == targetType) argument
                                        else irImplicitCast(argument, targetType)
                                }
                            }
                            val result = if (call.type == this@defaultDispatcher.returnType) call
                            else irImplicitCast(call, this@defaultDispatcher.returnType)
                            +irReturn(result)
                        }
                    }
                    markSemanticParameterCarriers(
                        owner,
                        helperExplicitParameters,
                        defaultDispatcher.parameters.drop(1),
                        family.semanticObjectParameterIndices,
                    )
                    if (requiresSemanticDefaultResult) {
                        context.genericOwnerCapabilityDeclarations += defaultDispatcher
                        context.genericOwnerForeignDispatchDeclarations += defaultDispatcher
                    }
                }
            }
        }

        // Moving an authoritative body from `C<T>` to its semantic hook can turn a nested exact
        // receiver parameter such as `Nested<T>` into the non-generic `Nested<*>` capability.
        // Those calls did not need a capability in the pre-move body, so select it now from the
        // remapped receiver declaration instead of retaining a stale exact route.
        semanticHooksBySource.values.forEach { hook ->
            hook.body?.acceptVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitCall(expression: IrCall) {
                    if (expression.superQualifierSymbol != null) {
                        val candidate = expression.symbol.owner
                        val source = candidate.resolveFakeOverride()
                            ?: candidate.resolveFakeOverrideMaybeAbstract()
                            ?: candidate
                        semanticHooksBySource[source]?.let { semanticHook ->
                            context.genericOwnerCapabilityCallTargets[expression] = semanticHook
                        }
                    }
                    val hasCapabilityReceiver = when (val receiver = expression.dispatchReceiver) {
                        is IrGetValue -> receiver.symbol.owner in context.genericOwnerCapabilityDeclarations
                        is IrGetField -> receiver.symbol.owner in context.genericOwnerCapabilityDeclarations
                        else -> false
                    }
                    if (hasCapabilityReceiver) {
                        val candidate = expression.symbol.owner
                        val source = candidate.resolveFakeOverride()
                            ?: candidate.resolveFakeOverrideMaybeAbstract()
                            ?: candidate
                        slotsBySource[source]?.let { slot ->
                            context.genericOwnerCapabilityCallTargets[expression] = slot
                        }
                    } else {
                        val receiver = expression.dispatchReceiver as? IrGetValue
                        val isCurrentDispatchReceiver = receiver?.symbol?.owner?.let { declaration ->
                            declaration is org.jetbrains.kotlin.ir.declarations.IrValueParameter &&
                                    declaration.kind == IrParameterKind.DispatchReceiver
                        } == true
                        if (isCurrentDispatchReceiver) {
                            val candidate = expression.symbol.owner
                            val source = candidate.resolveFakeOverride()
                                ?: candidate.resolveFakeOverrideMaybeAbstract()
                                ?: candidate
                            if (source.exactTypedStateGetterBackingFieldOrNull() == null) {
                                (semanticHooksBySource[source] ?: slotsBySource[source])?.let { semanticTarget ->
                                    context.genericOwnerCapabilityCallTargets[expression] = semanticTarget
                                }
                            }
                        }
                    }
                    expression.acceptChildrenVoid(this)
                }
            })
        }

        fun privateSemanticHook(source: IrSimpleFunction): IrSimpleFunction? =
            semanticHooksBySource[source]?.takeIf {
                DescriptorVisibilities.isPrivate(source.visibility)
            }

        callRoutes.forEach { route ->
            if (route.routeRequirement != DotNetGenericOwnerCallRouteRequirement.SEMANTIC_CAPABILITY &&
                route.routeRequirement !=
                DotNetGenericOwnerCallRouteRequirement.SEMANTIC_INPUT_CAPABILITY &&
                route.routeRequirement !=
                DotNetGenericOwnerCallRouteRequirement.SEMANTIC_RESULT_CAPABILITY
            ) {
                return@forEach
            }
            val source = route.callee.let { candidate ->
                candidate.resolveFakeOverride() ?: candidate.resolveFakeOverrideMaybeAbstract() ?: candidate
            }
            val routeReceiver = route.call.dispatchReceiver as? IrGetValue
            val readsExactCurrentState =
                routeReceiver?.symbol?.owner?.let { declaration ->
                    declaration is org.jetbrains.kotlin.ir.declarations.IrValueParameter &&
                            declaration.kind == IrParameterKind.DispatchReceiver
                } == true && source.parent === route.calleeOwner &&
                        source.exactTypedStateGetterBackingFieldOrNull() != null
            if (readsExactCurrentState) {
                // The route census was computed before semantic bodies were materialized. A
                // later semantic reachability reason may create a private hook for this getter,
                // but it cannot invalidate the producer-wide exact field decision. Preserve the
                // natural same-owner getter just as the copied-body scan above preserves it.
                return@forEach
            }
            val defaultDispatcher = context.genericOwnerArchitecturePlans[route.calleeOwner]
                ?.memberFamilies?.get(source)?.maskedDefaultDispatcher
            val target = if (route.call.symbol.owner === defaultDispatcher) {
                defaultSlotsBySource[route.callee] ?: defaultSlotsBySource[source]
            } else {
                // A class redeclaration can own the physical capability family even when
                // resolveFakeOverride() points at its logical interface/base root. Prefer that
                // exact owner member; only inherit the root slot when no redeclared family was
                // materialized locally. A private semantic family deliberately has no interface
                // slot: an exact same-owner semantic-result route calls its private hook directly
                // instead of widening visibility or passing through the typed wrapper. Do not
                // extend this fallback to non-private hooks; their missing slot is an ABI error.
                slotsBySource[route.callee] ?: slotsBySource[source]
                    ?: privateSemanticHook(route.callee) ?: privateSemanticHook(source)
            }
            target?.let {
                context.genericOwnerCapabilityCallTargets[route.call] = target
            }
        }

        val reflectionRoutes = callRoutes.filter { route ->
            route.routeRequirement == DotNetGenericOwnerCallRouteRequirement.MISSING_CAPABILITY &&
                    DescriptorVisibilities.isPrivate(route.callee.visibility) &&
                    route.callerLogicalBindingKey == null &&
                    route.callerName.contains("<InvokeMember-") &&
                    admittedPlansByOwner[route.calleeOwner]?.isReifiedByGenericOwnerRehearsal == true
        }
        reflectionRoutes.map(DotNetGenericOwnerCallRoutePlan::calleeOwner).distinct().forEach { owner ->
            val identity = admittedPlansByOwner.getValue(owner).capabilityIdentity()
            val suffix = Integer.toUnsignedString(identity.hashCode(), 16)
            val capability = context.irFactory.buildClass {
                startOffset = owner.startOffset
                endOffset = owner.endOffset
                origin = DOTNET_GENERIC_OWNER_CAPABILITY_INTERFACE
                name = Name.identifier("I${owner.name.asString()}KotlinReflection$suffix")
                kind = ClassKind.INTERFACE
                modality = Modality.ABSTRACT
                visibility = DescriptorVisibilities.PRIVATE
            }.apply {
                parent = owner.containingFile()
                superTypes = listOf(context.irBuiltIns.anyType)
                createThisReceiverParameter()
            }
            (capability.parent as IrFile).declarations += capability
            context.genericOwnerReflectionCapabilityInterfaces[owner] = capability
            owner.superTypes += capability.symbol.defaultType
        }
        val reflectionSlots = linkedMapOf<IrSimpleFunction, IrSimpleFunction>()
        reflectionRoutes.forEachIndexed { routeIndex, route ->
            val source = route.callee
            val owner = route.calleeOwner
            val capability = context.genericOwnerReflectionCapabilityInterfaces.getValue(owner)
            val slot = reflectionSlots.getOrPut(source) {
                val family = admittedPlansByOwner.getValue(owner).memberFamilies.getValue(source)
                val prototype = createDetachedPrototypeMember(
                    owner,
                    source,
                    DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
                    routeIndex,
                    requiresSemanticResultCapability = family.requiresSemanticResultCapability,
                    authoritativeSemanticObjectParameterIndices = family.semanticObjectParameterIndices,
                ).function
                val physicalName = dotNetGenericOwnerPhysicalMemberName(
                    source.dotNetIlMethodName(),
                    overrideRoots(source),
                    DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
                )
                val interfaceSlot = capability.addFunction {
                    startOffset = source.startOffset
                    endOffset = source.endOffset
                    origin = DOTNET_GENERIC_OWNER_CAPABILITY_SLOT
                    name = Name.identifier(physicalName)
                    visibility = DescriptorVisibilities.PUBLIC
                    modality = Modality.ABSTRACT
                    returnType = context.irBuiltIns.anyNType
                }.apply slot@{
                    parameters += createDispatchReceiverParameterWithClassParent()
                    val copiedTypeParameters = copyTypeParametersFrom(prototype)
                    val substitution = prototype.typeParameters.zip(copiedTypeParameters).associate { pair ->
                        pair.first.symbol to pair.second.symbol.defaultType
                    }
                    val substitutor = IrTypeSubstitutor(substitution, allowEmptySubstitution = true)
                    returnType = substitutor.substitute(prototype.returnType)
                    prototype.parameters.drop(1).forEach { parameter ->
                        parameters += parameter.copyTo(
                            this@slot,
                            type = substitutor.substitute(parameter.type),
                            varargElementType = parameter.varargElementType?.let(substitutor::substitute),
                            defaultValue = null,
                        )
                    }
                }
                markSemanticParameterCarriers(
                    owner,
                    source.parameters.drop(1),
                    interfaceSlot.parameters.drop(1),
                )
                if (family.requiresSemanticResultCapability) {
                    context.genericOwnerCapabilityDeclarations += interfaceSlot
                    context.genericOwnerForeignDispatchDeclarations += interfaceSlot
                }
                val dispatcher = owner.addFunction {
                    startOffset = source.startOffset
                    endOffset = source.endOffset
                    origin = DOTNET_GENERIC_OWNER_CAPABILITY_DISPATCHER
                    name = Name.identifier(physicalName)
                    visibility = DescriptorVisibilities.PRIVATE
                    modality = Modality.FINAL
                    returnType = context.irBuiltIns.anyNType
                }.apply dispatcher@{
                    overriddenSymbols = listOf(interfaceSlot.symbol)
                    parameters += createDispatchReceiverParameterWithClassParent()
                    val copiedTypeParameters = copyTypeParametersFrom(interfaceSlot)
                    val substitution = interfaceSlot.typeParameters.zip(copiedTypeParameters).associate { pair ->
                        pair.first.symbol to pair.second.symbol.defaultType
                    }
                    val substitutor = IrTypeSubstitutor(substitution, allowEmptySubstitution = true)
                    fun targetType(type: IrType): IrType = substitutor.substitute(type)
                    returnType = targetType(interfaceSlot.returnType)
                    interfaceSlot.parameters.drop(1).forEach { parameter ->
                        parameters += parameter.copyTo(
                            this@dispatcher,
                            type = targetType(parameter.type),
                            varargElementType = parameter.varargElementType?.let(::targetType),
                            defaultValue = null,
                        )
                    }
                    body = context.createIrBuilder(symbol).irBlockBody {
                        val call = irCall(source.symbol, source.returnType).apply {
                            arguments[0] = irGet(this@dispatcher.parameters[0])
                            source.parameters.drop(1).forEachIndexed { index, parameter ->
                                val argument = irGet(this@dispatcher.parameters[index + 1])
                                arguments[index + 1] = if (argument.type == parameter.type) argument
                                else irImplicitCast(argument, parameter.type)
                            }
                        }
                        val result = if (call.type == this@dispatcher.returnType) call
                        else irImplicitCast(call, this@dispatcher.returnType)
                        +irReturn(result)
                    }
                }
                markSemanticParameterCarriers(
                    owner,
                    source.parameters.drop(1),
                    dispatcher.parameters.drop(1),
                )
                if (family.requiresSemanticResultCapability) {
                    context.genericOwnerCapabilityDeclarations += dispatcher
                    context.genericOwnerForeignDispatchDeclarations += dispatcher
                }
                interfaceSlot
            }
            when (val receiver = route.call.dispatchReceiver) {
                is IrGetValue -> context.genericOwnerReflectionCapabilityDeclarations += receiver.symbol.owner
                is IrGetField -> context.genericOwnerReflectionCapabilityDeclarations += receiver.symbol.owner
                else -> error("Internal .NET backend error: reflected private member has no stable receiver")
            }
            context.genericOwnerCapabilityCallTargets[route.call] = slot
        }
        val missing = callRoutes.filter { route ->
            (route.routeRequirement == DotNetGenericOwnerCallRouteRequirement.SEMANTIC_CAPABILITY ||
                    route.routeRequirement ==
                    DotNetGenericOwnerCallRouteRequirement.SEMANTIC_INPUT_CAPABILITY ||
                    route.routeRequirement ==
                    DotNetGenericOwnerCallRouteRequirement.SEMANTIC_RESULT_CAPABILITY) &&
                    route.call !in context.genericOwnerCapabilityCallTargets &&
                    context.genericOwnerArchitecturePlans[route.calleeOwner]
                        ?.isReifiedByGenericOwnerRehearsal == true
        }
        check(missing.isEmpty()) {
            "Internal .NET backend error: ${missing.size} admitted generic-owner capability calls lack a slot: " +
                    missing.joinToString { route ->
                        "${route.callerName} -> ${route.calleeOwner.name.asString()}.${route.callee.name.asString()} " +
                                "(${route.receiverProvenance})"
                    }
        }

        // A consumer does not reconstruct a producer's generated MethodDef name. Materialize an
        // un-emitted semantic prototype and bind it to the family record carried by the producer
        // assembly. Codegen can then use the same call path as a local capability slot while the
        // physical owner/name remain entirely producer-authoritative.
        val externalSlots = context.externalGenericOwnerCapabilitySlots
        val externalDefaultSlots = linkedMapOf<IrSimpleFunction, IrSimpleFunction>()
        // Override-family closure may already have materialized this producer-bound stub for an
        // inherited semantic family. It is the same physical MethodDef needed by an exact
        // `super` call, so share the compilation-wide identity instead of creating a second IR
        // prototype for the same external member.
        val externalSemanticSlots = context.externalGenericOwnerSemanticHooks
        fun createExternalDefaultSlot(
            owner: IrClass,
            helper: IrSimpleFunction,
            index: Int,
            requiresSemanticResultCapability: Boolean,
            semanticObjectParameterIndices: Set<Int>,
        ): IrSimpleFunction {
            val movedReceiver = helper.parameters.singleOrNull { parameter ->
                parameter.origin == IrDeclarationOrigin.MOVED_DISPATCH_RECEIVER
            } ?: error("Internal .NET backend error: external generic-owner default helper lacks its moved receiver")
            return context.irFactory.buildFun {
                startOffset = helper.startOffset
                endOffset = helper.endOffset
                origin = DOTNET_GENERIC_OWNER_PROTOTYPE_MEMBER
                name = Name.special("<ExternalGenericOwnerDefaultCapability-$index>")
                visibility = DescriptorVisibilities.PRIVATE
                modality = Modality.ABSTRACT
                returnType = context.irBuiltIns.anyNType
            }.apply slot@{
                parent = owner
                parameters += createDispatchReceiverParameterWithClassParent()
                val slotTypeParameters = copyTypeParametersFrom(helper)
                val methodSubstitution = helper.typeParameters.zip(slotTypeParameters).associate { pair ->
                    pair.first.symbol to pair.second.symbol.defaultType
                }
                val methodSubstitutor = IrTypeSubstitutor(methodSubstitution, allowEmptySubstitution = true)
                fun slotType(type: IrType): IrType = methodSubstitutor.substitute(
                    type.toGenericOwnerSemanticType(owner)
                )
                returnType = if (requiresSemanticResultCapability) {
                    context.irBuiltIns.anyNType
                } else {
                    slotType(helper.returnType)
                }
                helper.parameters.filterNot { it === movedReceiver }.forEachIndexed { parameterIndex, parameter ->
                    parameters += parameter.copyTo(
                        this@slot,
                        type = if (parameterIndex in semanticObjectParameterIndices) {
                            context.irBuiltIns.anyNType
                        } else {
                            slotType(parameter.type)
                        },
                        varargElementType = if (parameterIndex in semanticObjectParameterIndices) {
                            null
                        } else {
                            parameter.varargElementType?.let(::slotType)
                        },
                        defaultValue = null,
                    )
                }
            }
        }
        callRoutes.filter { route ->
            route.routeRequirement == DotNetGenericOwnerCallRouteRequirement.EXTERNAL_FAMILY_RECORD_REQUIRED
        }.forEachIndexed { index, route ->
            val source = route.callee.let { candidate ->
                candidate.resolveFakeOverride() ?: candidate.resolveFakeOverrideMaybeAbstract() ?: candidate
            }
            val binding = externalDeclarations.genericOwnerMemberFamilyOrNull(source)
                ?: return@forEachIndexed
            val exactResultNeedsSemanticRoute =
                binding.family.requiresSemanticResultCapability
            val exactInputNeedsSemanticRoute =
                binding.family.semanticObjectParameterIndices.isNotEmpty()
            val externalDefault = context.externalDefaultArgumentDispatchers[route.call.symbol.owner]
            if (externalDefault != null) {
                val physicalMethodName = binding.family.defaultCapabilityMethodName
                    ?: return@forEachIndexed
                val defaultSlot = externalDefaultSlots.getOrPut(route.call.symbol.owner) {
                    createExternalDefaultSlot(
                        route.calleeOwner,
                        route.call.symbol.owner,
                        index,
                        requiresSemanticResultCapability = exactResultNeedsSemanticRoute,
                        semanticObjectParameterIndices =
                            binding.family.semanticObjectParameterIndices,
                    ).also { prototype ->
                        if (exactResultNeedsSemanticRoute) {
                            context.genericOwnerCapabilityDeclarations += prototype
                        }
                        context.genericOwnerCapabilityDeclarations += prototype.parameters.first()
                        markSemanticParameterCarriers(
                            route.calleeOwner,
                            route.call.symbol.owner.parameters.filterNot { parameter ->
                                parameter.origin == IrDeclarationOrigin.MOVED_DISPATCH_RECEIVER
                            },
                            prototype.parameters.drop(1),
                            binding.family.semanticObjectParameterIndices,
                        )
                        if (exactResultNeedsSemanticRoute) {
                            context.genericOwnerForeignDispatchDeclarations += prototype
                        }
                        context.externalGenericOwnerPhysicalSlots[prototype] =
                            DotNetBoundGenericOwnerPhysicalSlot(
                                binding.library,
                                binding.family,
                                binding.family.ownerPath,
                                physicalMethodName,
                            )
                    }
                }
                val previousDefaultSlot =
                    context.externalGenericOwnerDefaultCapabilitySlots.put(
                        route.call.symbol.owner,
                        defaultSlot,
                    )
                check(previousDefaultSlot == null || previousDefaultSlot === defaultSlot) {
                    "Internal .NET backend error: external generic-owner default helper acquired " +
                            "multiple physical capability slots"
                }
                context.genericOwnerCapabilityCallTargets[route.call] = defaultSlot
                return@forEachIndexed
            }
            if (route.receiverProvenance == DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION) {
                if (route.call.superQualifierSymbol != null) {
                    val semanticOwnerPath = binding.family.semanticHookOwnerPath
                        ?: return@forEachIndexed
                    val semanticMethodName = binding.family.semanticHookMethodName
                        ?: return@forEachIndexed
                    val expectedPhysicalSlot = DotNetBoundGenericOwnerPhysicalSlot(
                        binding.library,
                        binding.family,
                        semanticOwnerPath,
                        semanticMethodName,
                    )
                    val semanticSlot = externalSemanticSlots.getOrPut(source) {
                        createDetachedPrototypeMember(
                            owner = route.calleeOwner,
                            source = source,
                            role = DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
                            memberIndex = index,
                            requiresSemanticResultCapability =
                                binding.family.requiresSemanticResultCapability,
                            authoritativeSemanticObjectParameterIndices =
                                binding.family.semanticObjectParameterIndices,
                        ).function.also { prototype ->
                            if (binding.family.requiresSemanticResultCapability) {
                                context.genericOwnerCapabilityDeclarations += prototype
                            }
                            markSemanticParameterCarriers(
                                route.calleeOwner,
                                source.parameters.drop(1),
                                prototype.parameters.drop(1),
                                binding.family.semanticObjectParameterIndices,
                            )
                            context.externalGenericOwnerPhysicalSlots[prototype] = expectedPhysicalSlot
                        }
                    }
                    check(context.externalGenericOwnerPhysicalSlots[semanticSlot] == expectedPhysicalSlot) {
                        "Internal .NET backend error: one external generic-owner semantic hook " +
                                "resolved to incompatible producer MethodDefs"
                    }
                    val previousSemanticHook =
                        context.externalGenericOwnerSemanticHooks.put(source, semanticSlot)
                    check(previousSemanticHook == null || previousSemanticHook === semanticSlot) {
                        "Internal .NET backend error: external generic-owner member acquired " +
                                "multiple physical semantic hooks"
                    }
                    context.genericOwnerCapabilityCallTargets[route.call] = semanticSlot
                    return@forEachIndexed
                }
                if (!exactResultNeedsSemanticRoute && !exactInputNeedsSemanticRoute) {
                    return@forEachIndexed
                }
            }
            val slot = externalSlots.getOrPut(source) {
                createDetachedPrototypeMember(
                    owner = route.calleeOwner,
                    source = source,
                    role = DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
                    memberIndex = index,
                    requiresSemanticResultCapability =
                        binding.family.requiresSemanticResultCapability,
                    authoritativeSemanticObjectParameterIndices =
                        binding.family.semanticObjectParameterIndices,
                ).function.also { prototype ->
                    prototype.modality = Modality.ABSTRACT
                    if (exactResultNeedsSemanticRoute) {
                        context.genericOwnerCapabilityDeclarations += prototype
                    }
                    // Unlike a local slot, this un-emitted prototype is parented by the logical
                    // external C<T>; its implicit receiver must therefore be remapped to the
                    // producer's non-generic capability as well as its explicit value slots.
                    context.genericOwnerCapabilityDeclarations += prototype.parameters.first()
                    markSemanticParameterCarriers(
                        route.calleeOwner,
                        source.parameters.drop(1),
                        prototype.parameters.drop(1),
                        binding.family.semanticObjectParameterIndices,
                    )
                    if (exactResultNeedsSemanticRoute) {
                        // The producer's projected Nested<T> capability result is an object
                        // carrier: it may contain either Kotlin's sibling capability or an
                        // ordinary foreign natural construction. Reconstruct that same MethodRef
                        // result instead of narrowing it to the nested capability in this DLL.
                        context.genericOwnerForeignDispatchDeclarations += prototype
                    }
                    context.externalGenericOwnerPhysicalSlots[prototype] =
                        DotNetBoundGenericOwnerPhysicalSlot(
                            binding.library,
                            binding.family,
                            binding.family.ownerPath,
                            binding.family.capabilityMethodName,
                        )
                }
            }
            context.genericOwnerCapabilityCallTargets[route.call] = slot
        }
    }

    private fun instrumentCallRoutes(
        irModule: IrModuleFragment,
        callRoutes: List<DotNetGenericOwnerCallRoutePlan>,
        recorder: IrSimpleFunction,
    ) {
        check(recorder.parent in irModule.files &&
                DescriptorVisibilities.isPrivate(recorder.visibility) &&
                recorder.typeParameters.isEmpty() && !recorder.isSuspend && recorder.body != null &&
                recorder.parameters.singleOrNull()?.let { parameter ->
                    parameter.kind == IrParameterKind.Regular && parameter.type.isInt()
                } == true && recorder.returnType.isUnit()) {
            "Generic-owner route tracing requires one private module-local (Int) -> Unit recorder"
        }
        val indexByCall = IdentityHashMap<IrCall, Int>()
        callRoutes.forEach { route ->
            check(indexByCall.put(route.call, route.callSiteIndex) == null) {
                "Generic-owner route tracing encountered one call under multiple site indices"
            }
        }
        val remainingCalls = Collections.newSetFromMap(IdentityHashMap<IrCall, Boolean>()).apply {
            addAll(indexByCall.keys)
        }
        irModule.transformChildrenVoid(object : IrElementTransformerVoidWithContext() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)
                val callSiteIndex = indexByCall[expression] ?: return expression
                check(remainingCalls.remove(expression)) {
                    "Generic-owner route tracing visited one call site more than once"
                }
                val builder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol).at(expression)
                return builder.irBlock(resultType = expression.type) {
                    expression.arguments.indices.forEach { argumentIndex ->
                        val argument = expression.arguments[argumentIndex] ?: return@forEach
                        val temporary = irTemporary(argument, nameHint = "<genericOwnerRouteArgument>")
                        expression.arguments[argumentIndex] = irGet(temporary)
                    }
                    +irCall(recorder).apply {
                        arguments[0] = irInt(callSiteIndex)
                    }
                    +expression
                }
            }
        })
        check(remainingCalls.isEmpty()) {
            "Generic-owner route tracing did not instrument ${remainingCalls.size} analyzed call sites"
        }
    }

    /** Local/generated owners may be physically generic but never publish a cross-module owner ABI. */
    private fun IrClass.isNonAbiGenericOwnerImplementation(): Boolean =
        isOriginallyLocalDeclaration || origin != IrDeclarationOrigin.DEFINED || name.isSpecial

    /**
     * Produces the intentionally priority-compressed diagnostic summary for one owner.
     * Admission is decided from the final per-state requirements instead; this summary is
     * recomputed after detached-family closure so a late inherited semantic obligation cannot
     * leave a stale typed-write diagnostic behind.
     */
    private fun candidateDisposition(
        owner: IrClass,
        conditionalSupertypes: Collection<IrType>,
        constructors: Collection<DotNetGenericOwnerConstructorPlan>,
        stateCarriers: Collection<DotNetGenericOwnerStateCarrierPlan>,
        openOutputs: Collection<IrSimpleFunction>,
        memberFamilies: Collection<DotNetGenericOwnerMemberFamilyPlan>,
        hasUnboundConstructionSiteTypeSpec: Boolean = false,
    ): DotNetGenericOwnerCandidateDisposition = when {
        conditionalSupertypes.isNotEmpty() ->
            DotNetGenericOwnerCandidateDisposition.BLOCKED_METADATA_FIXED_CONDITIONAL_SUPERTYPE
        context.inlineClassesUtils.isClassInlineLike(owner) ->
            DotNetGenericOwnerCandidateDisposition.RETAINED_VALUE_CLASS_ABI
        !owner.isNonAbiGenericOwnerImplementation() && constructors.any { constructor ->
            constructor.semanticObjectParameterIndices.isNotEmpty()
        } ->
            // Constructor signatures have no portable producer record in the current rehearsal.
            // Re-deriving an object carrier from a consumer stub would make separate compilation
            // depend on both compilers repeating the same heuristic. Keep the owner erased until
            // one final emitted .ctor MethodDef signature is published, PE-validated and consumed
            // by both newobj and base/this calls.
            DotNetGenericOwnerCandidateDisposition.BLOCKED_SEMANTIC_CONSTRUCTOR_CARRIER_AUTHORITY
        stateCarriers.any { state ->
            !state.field.type.referencesGenericOwnerParameter(owner) &&
                    state.field.type.requiresSemanticInterfaceCarrierOf(owner)
        } ->
            DotNetGenericOwnerCandidateDisposition.BLOCKED_FIXED_SEMANTIC_STATE_CARRIER
        stateCarriers.any { state ->
            state.field.type.requiresUnboundNestedGenericOwnerCarrierOf(owner)
        } || constructors.any { constructor ->
            constructor.source.parameters.any { parameter ->
                parameter.type.requiresUnboundNestedGenericOwnerCarrierOf(owner)
            }
        } || memberFamilies.any { family ->
            family.source.returnType.requiresUnboundNestedGenericOwnerCarrierOf(owner) ||
                    family.source.parameters.any { parameter ->
                        parameter.kind == IrParameterKind.Regular &&
                                parameter.type.requiresUnboundNestedGenericOwnerCarrierOf(owner)
                    }
        } ->
            // The current live mapper deliberately carries an open `Box<Producer<T>>`-like
            // construction as object, while detached typed-entry/state prototypes still spell
            // the logical nested construction. Until one role-aware physical carrier query owns
            // both decisions, admitting the owner would publish contradictory MethodDef/FieldDef
            // authority. Keep the entire owner erased instead of repairing it after emission.
            DotNetGenericOwnerCandidateDisposition.BLOCKED_UNBOUND_NESTED_GENERIC_OWNER_CARRIER
        hasUnboundConstructionSiteTypeSpec ->
            DotNetGenericOwnerCandidateDisposition.BLOCKED_UNBOUND_CONSTRUCTION_SITE_TYPE_SPEC
        owner.isNonAbiGenericOwnerImplementation() ->
            DotNetGenericOwnerCandidateDisposition.RETAINED_NON_ABI_IMPLEMENTATION_OWNER
        memberFamilies.any { family ->
            family.requiresUnsupportedForeignSemanticOverride(owner)
        } ->
            DotNetGenericOwnerCandidateDisposition.BLOCKED_UNSUPPORTED_FOREIGN_SEMANTIC_OVERRIDE
        stateCarriers.any { state ->
            state.requirement == DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED
        } && openOutputs.isNotEmpty() ->
            DotNetGenericOwnerCandidateDisposition.BLOCKED_OPEN_OUTPUT_STATE_COHERENCE
        stateCarriers.any { state ->
            state.requirement == DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED
        } ->
            DotNetGenericOwnerCandidateDisposition.REQUIRES_SEMANTIC_STATE_PROOF
        stateCarriers.any { state ->
            state.requirement == DotNetGenericOwnerStateCarrierRequirement.VOLATILE_OBJECT_STORAGE_REQUIRED
        } ->
            DotNetGenericOwnerCandidateDisposition.REQUIRES_STATE_MEMORY_MODEL_PROOF
        stateCarriers.any { state ->
            state.requirement == DotNetGenericOwnerStateCarrierRequirement.COMPLETE_ACCESS_GRAPH_REQUIRED
        } ->
            DotNetGenericOwnerCandidateDisposition.REQUIRES_COMPLETE_FIELD_ACCESS_GRAPH
        stateCarriers.any { state ->
            state.requirement == DotNetGenericOwnerStateCarrierRequirement.TYPED_WRITE_VALUE_PROVENANCE_REQUIRED
        } ->
            DotNetGenericOwnerCandidateDisposition.REQUIRES_TYPED_WRITE_VALUE_PROVENANCE
        else ->
            DotNetGenericOwnerCandidateDisposition.REQUIRES_MEMBER_PHYSICALIZATION_PROOF
    }

    /**
     * Proves only that the logical class arguments of this `newobj` can be named by its actual
     * CLR scope. It never turns an unavailable Kotlin parameter into `object`: doing so would
     * create a different constructed TypeSpec and let later exact-result routing claim a view
     * which the allocated object does not implement.
     */
    private fun GenericOwnerConstructionSite.hasVerifierVisibleTypeSpec(
        target: IrClass,
        plans: Map<IrClass, DotNetGenericOwnerArchitecturePlan>,
    ): Boolean {
        val use = call.dotNetInvariantGenericOwnerConstructorUseOrNull() ?: return false
        if (use.constructedClass !== target) return false
        return use.substitutions.values.all { argument ->
            val dependencies = argument.dotNetTypeParameterDependencies()
            val isBareGenericParameter =
                (argument as? IrSimpleType)?.classifier is IrTypeParameterSymbol
            // The first admission grammar binds fixed carriers and bare `!n`/`!!n` only. An
            // open nested construction needs the same declaration-authority-aware symbolic
            // binder as the late physical-value model; rejecting it here is preferable to
            // inferring its TypeDef from logical IR.
            val hasSupportedShape = !argument.hasUnsupportedDotNetInvariantConstructorArgument()
            hasSupportedShape &&
                    (dependencies.isEmpty() || isBareGenericParameter) &&
                    dependencies.all { parameter ->
                        hasVerifierVisibleBinder(parameter, plans)
                    }
        }
    }

    /** A dependency is usable only when its declaring CLR GenericParam is in lexical scope. */
    private fun GenericOwnerConstructionSite.hasVerifierVisibleBinder(
        parameter: IrTypeParameterSymbol,
        plans: Map<IrClass, DotNetGenericOwnerArchitecturePlan>,
    ): Boolean = when (val declaration = parameter.owner.parent) {
        is IrFunction -> declaration === enclosingFunction &&
                parameter.owner in declaration.typeParameters
        // Inner-class physicalization has already copied every needed outer parameter onto the
        // actual emitted TypeDef. CLR nested types do not inherit an enclosing TypeDef's `!n`
        // namespace, so logical ancestry must never substitute for exact binder ownership here.
        is IrClass -> declaration === enclosingClass &&
                plans[declaration]?.isReifiedByGenericOwnerRehearsal == true &&
                parameter.owner in declaration.typeParameters
        else -> false
    }

    /**
     * The virtual probe compares MethodDefs and therefore does not consume the source arguments.
     * Its dispatcher may forward any number of binder-independent fixed leaves because their
     * natural and semantic carriers are identical. `DECLARATION_INDEPENDENT` alone is not such a
     * proof: a fixed covariant `I<Any?>` input is owner-independent but may still require the
     * object-domain semantic carrier. Owner-relative or broad inputs need an independent
     * conversion and MethodSpec-constraint proof. In particular a non-generic capability's
     * `!!R : object` cannot satisfy a natural `<R : !T>` slot merely because both value carriers
     * are `!!R`.
     */
    private fun DotNetGenericOwnerMemberFamilyPlan.supportsDirectForeignOverrideProbe(): Boolean {
        val mayUseSplitNullableResult = (sequenceOf(source) + source.allOverridden())
            .any { candidate ->
                val localInterfaceOwner = (candidate.parent as? IrClass)?.takeIf { owner ->
                    owner.symbol in context.localGenericInterfaceLogicalHazards
                }
                val localDirectNullableOwnerParameter = localInterfaceOwner != null &&
                        (candidate.returnType as? IrSimpleType)?.let { result ->
                            result.nullability == SimpleTypeNullability.MARKED_NULLABLE &&
                                    (result.classifier as? IrTypeParameterSymbol)?.owner?.parent ===
                                    localInterfaceOwner
                        } == true
                val producerRecordedInterfaceLayout = externalDeclarations
                    .publishedGenericInterfaceMemberContractOrNull(candidate)
                    ?.resultLayout
                val producerRecordedImplementationLayout = externalDeclarations
                    .genericOwnerImplementationMethodDefOrNull(candidate)
                    ?.declaration?.physicalMethod?.signature?.resultLayout
                localDirectNullableOwnerParameter || producerRecordedInterfaceLayout ==
                        DotNetPublishedGenericInterfaceMemberResultLayout.SPLIT_NULLABLE ||
                        producerRecordedImplementationLayout is
                        DotNetGenericOwnerPhysicalCallableResultLayoutRecord.SplitNullable
            }
        val supportsDeclarationIndependentArguments =
            !mayUseSplitNullableResult && source.typeParameters.isEmpty() &&
                    source.parameters.drop(1).size == parameterSlotDomains.size &&
                    source.parameters.drop(1).zip(parameterSlotDomains).all { pair ->
                        pair.second == DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT &&
                                pair.first.type
                                    .genericOwnerDeclarationIndependentLeafPrototypeOrNull() != null
                    }
        val methodParameter = source.typeParameters.singleOrNull()
        val explicitParameter = source.parameters.drop(1).singleOrNull()
        val overridesErasedOwnerRelativeInterfaceSlot = source.allOverridden().any { overridden ->
            val interfaceOwner = overridden.parent as? IrClass
            interfaceOwner?.kind == ClassKind.INTERFACE &&
                    overridden.dotNetDirectOwnerRelativeMethodBoundsOrNull(interfaceOwner)
                        ?.singleOrNull() != null
        }
        val supportsErasedOwnerRelativeMethodArgumentCandidate =
            !mayUseSplitNullableResult && methodParameter != null &&
                    explicitParameter != null &&
                    parameterSlotDomains == listOf(
                        DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT
                    ) &&
                    (explicitParameter.type as? IrSimpleType)?.let { type ->
                        type.nullability != SimpleTypeNullability.MARKED_NULLABLE &&
                                (type.classifier as? IrTypeParameterSymbol)?.owner === methodParameter
                    } == true &&
                    source.dotNetDirectOwnerRelativeMethodBoundsOrNull(
                        source.parent as IrClass,
                    )?.singleOrNull() != null &&
                    overridesErasedOwnerRelativeInterfaceSlot &&
                    DotNetGenericOwnerSemanticHookReason.OWNER_RELATIVE_METHOD_BOUND in
                    semanticHookReasons
        // The split-nullable natural slot has an additional `out bool` which this direct
        // dispatcher does not yet allocate or translate back to Kotlin's object-domain null.
        // Refuse the probe before admission rather than emitting a malformed call which silently
        // treats the payload as a complete result. The owner-relative case is admitted only when
        // it closes an interface slot whose relational constraint interface lowering will erase;
        // an owner-declared `<R : T>` MethodDef remains authoritative and cannot implement an
        // unconstrained capability MethodSpec. This is still an early candidate, not physical
        // authority: emission seals equality of the resulting typed/semantic binder vectors
        // before it may issue the natural MethodSpec call.
        return supportsDeclarationIndependentArguments ||
                supportsErasedOwnerRelativeMethodArgumentCandidate
    }

    /**
     * Whether an externally subclassable natural CLR slot would need a foreign-override probe
     * which this family cannot truthfully materialize. Local/generated owners have no published
     * C# subclassing surface, and a physically final owner cannot acquire a later foreign
     * override; their Kotlin override families remain covered by the ordinary semantic hook.
     */
    private fun IrClass.hasPotentialForeignClrSubclassAccess(): Boolean {
        var current: IrClass? = this
        while (current != null) {
            if (current.isOriginallyLocalDeclaration) return false
            val enclosing = current.parent as? IrClass
            val isExternallyVisible = when {
                enclosing == null ->
                    current.visibility == DescriptorVisibilities.PUBLIC || current.isPublishedApi()
                current.isCompanion -> true
                else ->
                    current.visibility == DescriptorVisibilities.PUBLIC ||
                            current.visibility == DescriptorVisibilities.PROTECTED ||
                            current.isPublishedApi()
            }
            if (!isExternallyVisible) return false
            current = enclosing
        }
        return true
    }

    private fun DotNetGenericOwnerMemberFamilyPlan.requiresUnsupportedForeignSemanticOverride(
        owner: IrClass,
    ): Boolean =
        !owner.isNonAbiGenericOwnerImplementation() &&
                owner.hasPotentialForeignClrSubclassAccess() &&
                owner.kind != ClassKind.INTERFACE &&
                (owner.modality == Modality.OPEN || owner.modality == Modality.ABSTRACT) &&
                !source.isFakeOverride &&
                DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in roles &&
                source.modality != Modality.FINAL &&
                !DescriptorVisibilities.isPrivate(source.visibility) &&
                (source.modality != Modality.OPEN || !supportsDirectForeignOverrideProbe())

    private fun plan(
        owner: IrClass,
        producerAccesses: Map<IrFunction, DirectMemberAccesses>,
        producerInitializerAccesses: Map<ProducerInitializer, DirectMemberAccesses>,
    ): DotNetGenericOwnerArchitecturePlan {
        val members = owner.directSimpleFunctions()
        val constructors = owner.declarations.filterIsInstance<IrConstructor>().map { constructor ->
            var delegatedConstructor: IrConstructor? = null
            var delegationArgumentMapping = DotNetGenericOwnerConstructorArgumentMapping.UNSUPPORTED
            constructor.body?.acceptVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitClass(declaration: IrClass) = Unit

                override fun visitFunction(declaration: IrFunction) = Unit

                override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall) {
                    check(delegatedConstructor == null) {
                        "Internal .NET backend error: generic-owner constructor has multiple delegation calls"
                    }
                    delegatedConstructor = expression.symbol.owner
                    delegationArgumentMapping = if (expression.arguments.size == constructor.parameters.size &&
                        expression.arguments.indices.all { index ->
                            (expression.arguments[index] as? IrGetValue)?.symbol == constructor.parameters[index].symbol
                        }
                    ) {
                        DotNetGenericOwnerConstructorArgumentMapping.POSITIONAL_IDENTITY
                    } else {
                        DotNetGenericOwnerConstructorArgumentMapping.UNSUPPORTED
                    }
                }
            })
            val delegated = delegatedConstructor
            val delegatedOwner = delegated?.parent as? IrClass
            DotNetGenericOwnerConstructorPlan(
                source = constructor,
                logicalBindingKey = context.preLoweringDeclarationKeys[constructor]
                    ?: constructor.dotNetLibraryAbiKeyOrNull("F"),
                parameterSlotDomains = constructor.parameters.map { parameter ->
                    if (parameter.type.referencesGenericOwnerParameter(owner)) {
                        DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT
                    } else {
                        DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT
                    }
                },
                semanticObjectParameterIndices = constructor.parameters.indices.filterTo(linkedSetOf()) { index ->
                    // A constructor executes on the natural generic owner and can therefore
                    // spell an invariant I<!T> exactly. Only a logically variant/projected
                    // interface value may arrive through another CLR construction and require
                    // the universal carrier. Semantic member hooks use the broader predicate
                    // because their non-generic owner cannot name !T at all.
                    constructor.parameters[index].type.containsPotentiallyReparameterizedInterfaceCarrier()
                },
                delegationArgumentMapping = delegationArgumentMapping,
                delegatedConstructorLogicalBindingKey = delegated?.let { target ->
                    context.preLoweringDeclarationKeys[target] ?: target.dotNetLibraryAbiKeyOrNull("F")
                },
                delegatedOwnerName = delegatedOwner?.fqNameWhenAvailable?.asString()
                    ?: delegatedOwner?.name?.asString(),
                delegatesToThis = delegatedOwner == owner,
            )
        }
        val fields = owner.directFields()
        val ownerDependentFields = fields.filterTo(linkedSetOf()) { field ->
            field.type.referencesGenericOwnerParameter(owner)
        }
        val memberPolicies = members.associateWithTo(linkedMapOf()) { member ->
            member.policyFor(owner)
        }
        fun IrSimpleFunction.isAbstractBroadPropertyGetter(): Boolean {
            val property = correspondingPropertySymbol?.owner ?: return false
            val setter = property.setter ?: return false
            val declaringOwner = property.parent as? IrClass ?: return false
            return this == property.getter && modality == Modality.ABSTRACT &&
                    setter.modality == Modality.ABSTRACT &&
                    setter.policyFor(declaringOwner) == DotNetGenericOwnerMemberPolicy.SEMANTIC_BODY
        }
        fun IrSimpleFunction.inheritsSemanticObligation(
            visiting: Set<IrSimpleFunction> = emptySet(),
        ): Boolean {
            if (this in visiting) return false
            return overriddenSymbols.any { overriddenSymbol ->
                val overridden = overriddenSymbol.owner
                val declaringOwner = overridden.parent as? IrClass ?: return@any false
                overridden.policyFor(declaringOwner) == DotNetGenericOwnerMemberPolicy.SEMANTIC_BODY ||
                        overridden.isAbstractBroadPropertyGetter() ||
                        overridden.inheritsSemanticObligation(visiting + this)
            }
        }
        val conditionalSupertypes = owner.superTypes.filter { superType ->
            superType.hasExplicitNullableParameterOf(owner)
        }
        val directAccesses = producerAccesses.mapValuesTo(linkedMapOf()) { entry ->
            entry.value.restrictTo(fields)
        }
        val initializerAccesses = producerInitializerAccesses.mapValuesTo(linkedMapOf()) { entry ->
            entry.value.restrictTo(fields)
        }
        val semanticEntries = members.filterTo(linkedSetOf()) { member ->
            memberPolicies.getValue(member) == DotNetGenericOwnerMemberPolicy.SEMANTIC_BODY ||
                    member.inheritsSemanticObligation()
        }
        val semanticReachableMembers = semanticEntries
            .flatMapTo(linkedSetOf<IrFunction>()) { member -> member.transitiveCalls(directAccesses) + member }
        val memberAccesses = members.associateWithTo(linkedMapOf()) { member ->
            val transitiveCalls = member.transitiveCalls(directAccesses)
            val reachableBodies = transitiveCalls + member
            DotNetGenericOwnerMemberAccessPlan(
                source = member,
                directCalls = directAccesses.getValue(member).calls,
                transitiveCalls = transitiveCalls,
                directReads = directAccesses.getValue(member).reads,
                directWrites = directAccesses.getValue(member).writes,
                transitiveReads = reachableBodies.flatMapTo(linkedSetOf()) { reachable ->
                    directAccesses.getValue(reachable).reads
                },
                transitiveWrites = reachableBodies.flatMapTo(linkedSetOf()) { reachable ->
                    directAccesses.getValue(reachable).writes
                },
                reachableFromSemanticEntry = member in semanticReachableMembers,
            )
        }
        val directSemanticWriteFields = semanticEntries.flatMapTo(linkedSetOf()) { member ->
            directAccesses.getValue(member).writes
        }.filterTo(linkedSetOf()) { field -> field in ownerDependentFields }
        val semanticReachableWriteFields = semanticReachableMembers.flatMapTo(linkedSetOf()) { member ->
            directAccesses.getValue(member).writes
        }.filterTo(linkedSetOf()) { field -> field in ownerDependentFields }
        val writeValueProvenances = TypedWriteValueProvenanceAnalyzer(
            owner = owner,
            members = members,
            memberPolicies = memberPolicies,
            producerAccesses = directAccesses,
            initializerAccesses = initializerAccesses,
        ).analyze(ownerDependentFields)
        val semanticValueWriteFields = writeValueProvenances
            .filterValues { writes ->
                writes.any { write ->
                    write.provenance == DotNetGenericOwnerWriteValueProvenance.SEMANTIC_OBJECT
                }
            }
            .keys
        val semanticStateWriteFields = semanticReachableWriteFields + semanticValueWriteFields
        val openOutputs = if (owner.modality == Modality.FINAL) {
            emptySet()
        } else {
            members.filterTo(linkedSetOf()) { member ->
                member.modality != Modality.FINAL && member.returnType.referencesGenericOwnerParameter(owner)
            }
        }
        val abstractBroadPropertyGetters = members.filterTo(linkedSetOf()) { member ->
            member.isAbstractBroadPropertyGetter()
        }
        val physicalSemanticReachableMembers = buildSet<IrFunction> {
            val roots = buildSet {
                addAll(semanticEntries)
                addAll(abstractBroadPropertyGetters)
                if (semanticStateWriteFields.isNotEmpty()) addAll(openOutputs)
            }
            roots.forEach { member ->
                add(member)
                addAll(member.transitiveCalls(directAccesses))
            }
        }
        var memberFamilies = members.associateWithTo(linkedMapOf()) { member ->
            val directSuperCalls = mutableListOf<DotNetGenericOwnerDirectSuperCallPlan>()
            member.body?.acceptVoid(object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitCall(expression: IrCall) {
                    expression.superQualifierSymbol?.let { superQualifier ->
                        directSuperCalls += DotNetGenericOwnerDirectSuperCallPlan(
                            target = expression.symbol.owner,
                            superQualifier = superQualifier.owner,
                        )
                    }
                    expression.acceptChildrenVoid(this)
                }
            })
            val ownerDependentInputs = member.parameters.withIndex().mapNotNull { indexedParameter ->
                val parameter = indexedParameter.value
                indexedParameter.index.takeIf {
                    parameter.kind != IrParameterKind.DispatchReceiver &&
                            parameter.type.referencesGenericOwnerParameter(owner)
                }
            }
            val hasOwnerDependentOutput = member.returnType.referencesGenericOwnerParameter(owner)
            val explicitParameters = member.parameters.filter { parameter ->
                parameter.kind != IrParameterKind.DispatchReceiver
            }
            val specialArgumentsToCheck = specialBridgeMethods
                .findSpecialWithOverride(member, includeSelf = true)
                ?.second
                ?.argumentsToCheck
                ?: 0
            val parameterSlotDomains = explicitParameters.mapIndexed { index, parameter ->
                when {
                    !parameter.type.referencesGenericOwnerParameter(owner) ->
                        DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT
                    !DescriptorVisibilities.isPrivate(member.visibility) &&
                            (index < specialArgumentsToCheck ||
                                    !parameter.type.isLegalAtOwnerVariance(owner, TypePolarity.IN)) ->
                        DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT
                    else -> DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT
                }
            }
            val semanticObjectParameterIndices = explicitParameters.indices.filterTo(linkedSetOf()) { index ->
                explicitParameters[index].type.requiresSemanticInterfaceCarrierOf(owner)
            }
            val returnSlotDomain = if (hasOwnerDependentOutput) {
                DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT
            } else {
                DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT
            }
            val hasRelativeGenericInterfaceInput = member.allOverridden().any { overridden ->
                DotNetRuntimeTypes.genericInterfaceRelativeGenericInputParameterIndex(overridden) != null
            }
            val semanticHookReasons = buildSet {
                if (memberPolicies.getValue(member) == DotNetGenericOwnerMemberPolicy.SEMANTIC_BODY) {
                    add(DotNetGenericOwnerSemanticHookReason.GENERAL_WIDENED_BODY)
                }
                if (semanticStateWriteFields.isNotEmpty() && member in openOutputs) {
                    add(DotNetGenericOwnerSemanticHookReason.PAIRED_OPEN_OUTPUT_STATE)
                }
                if (member.returnType.referencesGenericOwnerParameter(owner) &&
                    memberAccesses.getValue(member).transitiveReads.any(semanticStateWriteFields::contains)
                ) {
                    // A final output can need the same split as an open output. Direct T state
                    // may contain a value installed through a widened semantic entry, while
                    // Nested<T> may carry a covariantly widened construction which is not
                    // Nested<!T>. Keep either body/state read in the semantic hook and let only
                    // the natural typed entry perform the exact CLR view cast.
                    add(DotNetGenericOwnerSemanticHookReason.PAIRED_SEMANTIC_STATE_OUTPUT)
                }
                if (member in abstractBroadPropertyGetters) {
                    add(DotNetGenericOwnerSemanticHookReason.ABSTRACT_BROAD_PROPERTY_OBLIGATION)
                }
                if (member in physicalSemanticReachableMembers &&
                    DescriptorVisibilities.isPrivate(member.visibility)
                ) {
                    // A declaration-independent signature does not make its body independent.
                    // In particular, a private `(Any?) -> Unit` helper may contain `value as T`
                    // and then mutate semantic object state. Reusing its C<T> body would make
                    // CLR !T reject an @UnsafeVariance candidate before Kotlin's widened body.
                    add(DotNetGenericOwnerSemanticHookReason.INTERNAL_SEMANTIC_REACHABILITY)
                }
                if (member.typeParameters.any { parameter ->
                        parameter.superTypes.any { bound -> bound.referencesGenericOwnerParameter(owner) }
                    }
                ) {
                    // A capability method cannot repeat a constraint such as `<B : A>` because
                    // its non-generic owner deliberately has no CLR `!A`. Calling the natural
                    // constrained MethodDef from an unconstrained capability slot is invalid IL.
                    // Keep the exact `<B : !A>` entry for C# and route the semantic slot through
                    // a separately erased `<B : object>` body.
                    add(DotNetGenericOwnerSemanticHookReason.OWNER_RELATIVE_METHOD_BOUND)
                }
                if (hasRelativeGenericInterfaceInput) {
                    // The natural CLR slot represents `Nested<T>` as `<U : T>(Nested<U>)`, but a
                    // legal Kotlin widening such as `Collection<Int> -> Collection<Any?>` may
                    // still reach an invariant `C<Any?>` implementation. Keep that one body on
                    // the semantic carrier and let the typed bridge add only the CLR method
                    // parameter. Exact natural inputs continue to use the direct bridge.
                    add(DotNetGenericOwnerSemanticHookReason.RELATIVE_GENERIC_INTERFACE_INPUT)
                }
                if (semanticObjectParameterIndices.isNotEmpty()) {
                    // A fixed logical I<Any?> input may physically be I<int> after legal Kotlin
                    // covariance. The capability slot therefore accepts object, and the body
                    // must live on the same object-domain hook; dispatching that slot straight
                    // to the natural I<object> MethodDef would fabricate an incompatible view.
                    add(DotNetGenericOwnerSemanticHookReason.SEMANTIC_INTERFACE_INPUT)
                }
                if (member.returnType.requiresSemanticInterfaceCarrierOf(owner)) {
                    // Until an implementation-wide result proof says otherwise, a logical
                    // variant I<X> result may carry a different constructed CLR view (most
                    // visibly I<int> through I<Any?>). This remains true when X is fixed and does
                    // not mention the current owner's T. Keep the authoritative Kotlin body on
                    // the object-domain hook; the natural C<T> entry remains the checked C# view.
                    add(DotNetGenericOwnerSemanticHookReason.SEMANTIC_INTERFACE_RESULT)
                }
            }
            val roles = buildSet {
                add(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY)
                // A projected/star receiver has only the non-generic semantic capability as its
                // physical identity. It must still be able to invoke declaration-independent
                // members (`label(): String`, `size: Int`, ...), not only members whose explicit
                // signature mentions T. Therefore every externally callable instance member
                // receives a capability selector; strict typed calls continue to target the
                // natural member directly.
                if (!DescriptorVisibilities.isPrivate(member.visibility)) {
                    add(DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)
                }
                if (semanticHookReasons.isNotEmpty()) {
                    add(DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
                    if (!DescriptorVisibilities.isPrivate(member.visibility)) {
                        add(DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)
                    }
                }
            }
            DotNetGenericOwnerMemberFamilyPlan(
                source = member,
                policy = memberPolicies.getValue(member),
                ownerDependentInputIndices = ownerDependentInputs,
                hasOwnerDependentOutput = hasOwnerDependentOutput,
                returnSlotDomain = returnSlotDomain,
                parameterSlotDomains = parameterSlotDomains,
                semanticObjectParameterIndices = semanticObjectParameterIndices,
                roles = roles,
                semanticHookReasons = semanticHookReasons,
                requiresDirectSuperTargets = member.modality != Modality.FINAL,
                directSuperCallCount = directSuperCalls.size,
                directSuperCalls = directSuperCalls,
                maskedDefaultDispatcher = context.defaultArgumentDispatchers[member],
                logicalBindingKey = context.preLoweringDeclarationKeys[member].takeUnless {
                    DescriptorVisibilities.isPrivate(member.visibility)
                },
            )
        }
        val stateCarriers = fields
            .associateWithTo(linkedMapOf()) { field ->
                val directReaders = directAccesses
                    .filterTo(linkedMapOf()) { entry -> field in entry.value.reads }
                    .keys
                val directWriters = directAccesses
                    .filterTo(linkedMapOf()) { entry -> field in entry.value.writes }
                    .keys
                val semanticReachableReaders = directReaders.filterTo(linkedSetOf()) { member ->
                    member in semanticReachableMembers
                }
                val semanticReachableWriters = directWriters.filterTo(linkedSetOf()) { member ->
                    member in semanticReachableMembers
                }
                val initializationReaders = initializerAccesses
                    .filter { entry ->
                        field in entry.value.reads || entry.value.calls.any { call ->
                            field in call.transitiveFieldReads(directAccesses)
                        }
                    }
                    .keys
                    .mapTo(linkedSetOf()) { initializer -> initializer.label }
                val initializationWriters = initializerAccesses
                    .filter { entry ->
                        field in entry.value.writes || entry.value.calls.any { call ->
                            field in call.transitiveFieldWrites(directAccesses)
                        }
                    }
                    .keys
                    .mapTo(linkedSetOf()) { initializer -> initializer.label }
                val ownerDependent = field in ownerDependentFields
                val writes = if (ownerDependent) {
                    writeValueProvenances.getValue(field)
                } else {
                    buildList {
                        directAccesses.forEach { entry ->
                            if (field in entry.value.writes) {
                                add(DotNetGenericOwnerStateWriteProvenancePlan(
                                    producerName = entry.key.name.asString(),
                                    provenance = DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED,
                                ))
                            }
                        }
                        initializerAccesses.forEach { entry ->
                            if (field in entry.value.writes) {
                                add(DotNetGenericOwnerStateWriteProvenancePlan(
                                    producerName = entry.key.label,
                                    provenance = DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED,
                                ))
                            }
                        }
                    }.distinctBy { write -> write.producerName }
                }
                val hasOnlyProvenTypedWrites = writes.isNotEmpty() && writes.all { write ->
                    write.provenance == DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED
                }
                val externalAccessGraphRequired = !DescriptorVisibilities.isPrivate(field.visibility)
                val memorySemantics = if (field.hasAnnotation(DOTNET_GENERIC_OWNER_VOLATILE_FQ_NAME)) {
                    DotNetGenericOwnerStateMemorySemantics.VOLATILE
                } else {
                    DotNetGenericOwnerStateMemorySemantics.PLAIN
                }
                DotNetGenericOwnerStateCarrierPlan(
                    field = field,
                    requirement = when {
                        !ownerDependent ->
                            DotNetGenericOwnerStateCarrierRequirement.DECLARATION_INDEPENDENT_STORAGE
                        semanticReachableWriters.isNotEmpty() || field in semanticValueWriteFields ->
                            DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED
                        memorySemantics == DotNetGenericOwnerStateMemorySemantics.VOLATILE ->
                            DotNetGenericOwnerStateCarrierRequirement.VOLATILE_OBJECT_STORAGE_REQUIRED
                        externalAccessGraphRequired ->
                            DotNetGenericOwnerStateCarrierRequirement.COMPLETE_ACCESS_GRAPH_REQUIRED
                        hasOnlyProvenTypedWrites ->
                            DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN
                        else ->
                            DotNetGenericOwnerStateCarrierRequirement.TYPED_WRITE_VALUE_PROVENANCE_REQUIRED
                    },
                    memorySemantics = memorySemantics,
                    initializers = producerInitializerAccesses.keys.mapNotNull { initializer ->
                        initializer.stateInitializerPlanOrNull(field, owner)
                    },
                    writes = writes,
                    directReaders = directReaders,
                    directWriters = directWriters,
                    semanticReachableReaders = semanticReachableReaders,
                    semanticReachableWriters = semanticReachableWriters,
                    initializationReaderLabels = initializationReaders,
                    initializationWriterLabels = initializationWriters,
                    externalAccessGraphRequired = externalAccessGraphRequired,
                )
            }
        memberFamilies = memberFamilies.mapValuesTo(linkedMapOf()) { entry ->
            val family = entry.value
            if (entry.key.exactTypedStateGetterBackingFieldOrNull(stateCarriers) == null) {
                family
            } else {
                val retainedReasons = family.semanticHookReasons - setOf(
                    DotNetGenericOwnerSemanticHookReason.INTERNAL_SEMANTIC_REACHABILITY,
                    DotNetGenericOwnerSemanticHookReason.SEMANTIC_INTERFACE_RESULT,
                )
                family.copy(
                    semanticHookReasons = retainedReasons,
                    roles = if (retainedReasons.isEmpty()) {
                        family.roles - DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK -
                                DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER
                    } else {
                        family.roles
                    },
                )
            }
        }
        val disposition = candidateDisposition(
            owner = owner,
            conditionalSupertypes = conditionalSupertypes,
            constructors = constructors,
            stateCarriers = stateCarriers.values,
            openOutputs = openOutputs,
            memberFamilies = memberFamilies.values,
        )
        check(memberFamilies.keys == memberPolicies.keys) {
            "Internal .NET backend error: generic-owner member-family planning is incomplete"
        }
        check(memberAccesses.keys == memberPolicies.keys) {
            "Internal .NET backend error: generic-owner field/call planning is incomplete"
        }
        check(semanticStateWriteFields.all { field ->
            stateCarriers[field]?.requirement == DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED
        }) {
            "Internal .NET backend error: a widened semantic state write retained typed-only storage"
        }
        check(stateCarriers.values.all { state ->
            if (state.memorySemantics == DotNetGenericOwnerStateMemorySemantics.VOLATILE &&
                state.field in ownerDependentFields
            ) {
                state.requirement ==
                        DotNetGenericOwnerStateCarrierRequirement.VOLATILE_OBJECT_STORAGE_REQUIRED ||
                        state.requirement == DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED
            } else {
                state.requirement != DotNetGenericOwnerStateCarrierRequirement.VOLATILE_OBJECT_STORAGE_REQUIRED
            }
        }) {
            "Internal .NET backend error: owner-dependent volatility lost its object migration condition"
        }
        if (disposition == DotNetGenericOwnerCandidateDisposition.BLOCKED_OPEN_OUTPUT_STATE_COHERENCE) {
            check(openOutputs.all { output ->
                DotNetGenericOwnerSemanticHookReason.PAIRED_OPEN_OUTPUT_STATE in
                        memberFamilies.getValue(output).semanticHookReasons
            }) {
                "Internal .NET backend error: open output/state coherence lacks a paired semantic family"
            }
        }
        val prototypeMembers = memberFamilies.entries.mapIndexed { memberIndex, entry ->
            val source = entry.key
            val family = entry.value
            source to family.roles.associateWithTo(linkedMapOf()) { role ->
                createDetachedPrototypeMember(
                    owner,
                    source,
                    role,
                    memberIndex,
                    requiresSemanticResultCapability = family.requiresSemanticResultCapability,
                    authoritativeSemanticObjectParameterIndices =
                        family.semanticObjectParameterIndices,
                )
            }
        }.toMap(linkedMapOf())
        check(owner.declarations.none { declaration -> declaration.origin == DOTNET_GENERIC_OWNER_PROTOTYPE_MEMBER }) {
            "Internal .NET backend error: a generic-owner prototype member entered production IR"
        }
        check(prototypeMembers.all { entry ->
            val source = entry.key
            val roles = entry.value
            roles.keys == memberFamilies.getValue(source).roles &&
                    roles.values.all { prototype: DotNetGenericOwnerPrototypeMember ->
                        prototype.function !in owner.declarations
                    }
        }) {
            "Internal .NET backend error: generic-owner detached prototype family is incomplete"
        }
        return DotNetGenericOwnerArchitecturePlan(
            owner = owner,
            logicalBindingKey = context.preLoweringDeclarationKeys[owner],
            logicalDirectSupertypes = owner.superTypes.toList(),
            disposition = disposition,
            constructors = constructors,
            memberPolicies = memberPolicies,
            memberFamilies = memberFamilies,
            memberAccesses = memberAccesses,
            prototypeMembers = prototypeMembers,
            metadataFixedConditionalSupertypes = conditionalSupertypes,
            directSemanticWriteFields = directSemanticWriteFields,
            semanticReachableWriteFields = semanticReachableWriteFields,
            semanticValueWriteFields = semanticValueWriteFields,
            overrideBindings = emptyMap(),
            stateCarriers = stateCarriers,
            openOwnerOutputs = openOutputs,
        )
    }

    /**
     * Connects detached member families across Kotlin-produced generic subclasses after every
     * local owner has been planned. Typed entries override typed entries. A semantic hook is
     * inherited as a family obligation and overrides only the ancestor semantic hook; private
     * capability dispatchers remain final selectors and never form an override chain.
     *
     * An external generic base has no detached prototype in this compilation. Record its stable
     * logical member key as an explicit future binding-schema requirement instead of guessing a
     * physical MethodDef. This keeps separate compilation fail-closed while the production ABI is
     * still erased and no prototype schema is serialized.
     */
    private fun linkDetachedOverrideFamilies(
        producerAccesses: Map<IrFunction, DirectMemberAccesses>,
        initializerAccesses: Map<ProducerInitializer, DirectMemberAccesses>,
        constructionSites: List<GenericOwnerConstructionSite>,
    ) {
        val plans = context.genericOwnerArchitecturePlans
        val constructionSitesByOwner = constructionSites.groupBy { site ->
            site.call.symbol.owner.parent as? IrClass
        }
        // Admission only moves downward. Once one `newobj` site has proved that a local TypeDef's
        // logical argument cannot be named, no later semantic closure may reinterpret it as
        // `object` and readmit the TypeDef under a different construction.
        val constructionSiteBlockedOwners = linkedSetOf<IrClass>()
        fun declaringOverride(function: IrSimpleFunction): IrSimpleFunction =
            if (function.isFakeOverride) {
                function.resolveFakeOverride()
                    ?: function.resolveFakeOverrideMaybeAbstract()
                    ?: error("generic-owner external fake override has no declaring Kotlin root")
            } else {
                function
            }

        fun externalFamily(function: IrSimpleFunction) =
            externalDeclarations.genericOwnerMemberFamilyOrNull(declaringOverride(function))

        fun IrSimpleFunction.declaredInterfaceRequiresSemanticResult(): Boolean {
            val interfaceOwner = parent as? IrClass ?: return false
            if (interfaceOwner.kind != ClassKind.INTERFACE || interfaceOwner.typeParameters.isEmpty()) return false
            val localHazard = context.localGenericInterfaceLogicalHazards[interfaceOwner.symbol]
            if (localHazard != null) {
                // This is negative-only pristine-IR evidence. It can prevent an implementation
                // from claiming an exact result, but cannot admit the interface TypeDef.
                return returnType.requiresSemanticInterfaceCarrierOf(interfaceOwner)
            }
            val externalRole = externalDeclarations
                .publishedGenericInterfaceMemberContractOrNull(this)
                ?.role
            if (externalRole != null) {
                return externalRole ==
                        DotNetPublishedGenericInterfaceMemberRole.CONSTRUCTED_INTERFACE_PRODUCER
            }
            // Runtime declarations are the only non-H-recorded external Kotlin authority in the
            // rehearsal. Arbitrary imported CLR interfaces deliberately remain excluded.
            return DotNetRuntimeTypes.usesDeclaredViewByDefaultInRehearsal(interfaceOwner) &&
                    returnType.requiresSemanticInterfaceCarrierOf(interfaceOwner)
        }

        // Detached-family inheritance, private semantic reachability, state selection, and
        // semantic output pairing form one monotone closure. None of these phases may consume a
        // stale snapshot produced by an earlier phase: an inherited hook can expose a private
        // writer, that writer can move a field to object state, and that state can require a
        // semantic output hook which in turn exposes another helper. Every transition only adds
        // roles/reasons/reachability or moves unresolved state to semantic object storage.
        var changed: Boolean
        do {
            changed = false
            plans.entries.toList().forEach { planEntry ->
                val owner = planEntry.key
                val plan = plans.getValue(owner)
                val families = plan.memberFamilies.toMutableMap()
                plan.memberFamilies.forEach { familyEntry ->
                    val source = familyEntry.key
                    if (source.isFakeOverride) return@forEach
                    val overriddenFamilies = source.overriddenSymbols.mapNotNull { overriddenSymbol ->
                        // A memberless local intermediate owns only a fake override and emits no
                        // physical MethodDef. Follow it to the declaration which owns the family,
                        // exactly as the external lookup below already does.
                        val overridden = declaringOverride(overriddenSymbol.owner)
                        val overriddenOwner = overridden.parent as? IrClass ?: return@mapNotNull null
                        plans[overriddenOwner]?.memberFamilies?.get(overridden)
                    }
                    val externalOverriddenFamilies = source.overriddenSymbols.mapNotNull { overridden ->
                        externalFamily(overridden.owner)
                    }
                    val inheritsInterfaceSemanticResult = source.allOverridden().any { overridden ->
                        overridden.declaredInterfaceRequiresSemanticResult()
                    }
                    val family = families.getValue(source)
                    val inheritsSemanticHook = overriddenFamilies.any { overriddenFamily ->
                        DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in overriddenFamily.roles
                    } || externalOverriddenFamilies.any { binding ->
                        binding.family.semanticHookMethodName != null
                    } || inheritsInterfaceSemanticResult
                    val inheritsSemanticResult = overriddenFamilies.any { overriddenFamily ->
                        overriddenFamily.requiresSemanticResultCapability
                    } || externalOverriddenFamilies.any { binding ->
                        binding.family.requiresSemanticResultCapability
                    } || inheritsInterfaceSemanticResult
                    val mergedParameterSlotDomains = overriddenFamilies.fold(family.parameterSlotDomains) {
                            domains, overriddenFamily ->
                        mergeDotNetGenericOwnerParameterSlotDomains(
                            domains,
                            overriddenFamily.parameterSlotDomains,
                        )
                    }
                    val inheritedSemanticObjectParameterIndices = buildSet {
                        overriddenFamilies.forEach { overriddenFamily ->
                            addAll(overriddenFamily.semanticObjectParameterIndices)
                        }
                        externalOverriddenFamilies.forEach { binding ->
                            addAll(binding.family.semanticObjectParameterIndices)
                        }
                    }
                    val mergedSemanticObjectParameterIndices =
                        mergeDotNetGenericOwnerSemanticObjectParameterIndices(
                            family.semanticObjectParameterIndices,
                            inheritedSemanticObjectParameterIndices,
                            mergedParameterSlotDomains.size,
                        )
                    val updatedFamily = family.copy(
                        roles = if (inheritsSemanticHook) {
                            family.roles + setOf(
                                DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
                                DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
                            )
                        } else {
                            family.roles
                        },
                        semanticHookReasons = if (inheritsSemanticHook) {
                            buildSet {
                                addAll(family.semanticHookReasons)
                                add(DotNetGenericOwnerSemanticHookReason.INHERITED_SEMANTIC_OVERRIDE)
                                if (inheritsSemanticResult) {
                                    // Result policy belongs to the whole virtual family. A fixed
                                    // substitution in this source signature may no longer mention
                                    // the current owner parameter, but it cannot erase the
                                    // ancestor MethodDef's semantic-result obligation.
                                    add(DotNetGenericOwnerSemanticHookReason.SEMANTIC_INTERFACE_RESULT)
                                }
                                if (mergedSemanticObjectParameterIndices.isNotEmpty()) {
                                    // Semantic input carriers are a positional virtual-family
                                    // obligation just like the already inherited broad domain.
                                    add(DotNetGenericOwnerSemanticHookReason.SEMANTIC_INTERFACE_INPUT)
                                }
                            }
                        } else {
                            family.semanticHookReasons
                        },
                        parameterSlotDomains = mergedParameterSlotDomains,
                        semanticObjectParameterIndices = mergedSemanticObjectParameterIndices,
                    )
                    if (updatedFamily != family) {
                        families[source] = updatedFamily
                        changed = true
                    }
                }
                if (families != plan.memberFamilies) {
                    plans[owner] = plan.copy(memberFamilies = families)
                }
            }
            // A semantic obligation inherited after the per-owner pass must carry through the
            // same private helper graph as an obligation known during that pass. These helpers
            // are not new public capability entries; they receive only a private semantic body.
            plans.entries.toList().forEach planLoop@{ planEntry ->
                val owner = planEntry.key
                val plan = plans.getValue(owner)
                val semanticSources = plan.memberFamilies.filterValues { family ->
                    DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in family.roles
                }.keys
                val semanticPrivateHelpers = semanticSources
                    .flatMapTo(linkedSetOf()) { source ->
                        plan.memberAccesses.getValue(source).transitiveCalls
                    }
                    .filterIsInstance<IrSimpleFunction>()
                    .filterTo(linkedSetOf()) { helper ->
                        helper.parent === owner && DescriptorVisibilities.isPrivate(helper.visibility) &&
                                helper.exactTypedStateGetterBackingFieldOrNull() == null
                    }
                if (semanticPrivateHelpers.isEmpty()) return@planLoop
                val families = plan.memberFamilies.toMutableMap()
                semanticPrivateHelpers.forEach helperLoop@{ helper ->
                    val family = families[helper] ?: return@helperLoop
                    val updatedFamily = family.copy(
                        roles = family.roles + DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
                        semanticHookReasons = family.semanticHookReasons +
                                DotNetGenericOwnerSemanticHookReason.INTERNAL_SEMANTIC_REACHABILITY,
                    )
                    if (updatedFamily != family) {
                        families[helper] = updatedFamily
                        changed = true
                    }
                }
                if (families != plan.memberFamilies) {
                    plans[owner] = plan.copy(memberFamilies = families)
                }
            }

            // Fold every current semantic source back into state selection. A hook introduced
            // by inheritance or private-helper closure is new physical boundary information:
            // an owner-dependent parameter which used to enter as !T now enters that body as
            // object. Re-run the same complete write-value dataflow with those final boundaries
            // instead of preserving an earlier typed decision or blindly making every reachable
            // field semantic. Exact receiver-derived writes can consequently remain typed, while
            // a genuinely broad source value can never be narrowed into !T.
            plans.entries.toList().forEach { planEntry ->
                val owner = planEntry.key
                val plan = plans.getValue(owner)
                val semanticSources = plan.memberFamilies.filterValues { family ->
                    DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in family.roles
                }.keys
                val semanticProducerFunctions = buildSet<IrFunction> {
                    semanticSources.forEach { source ->
                        add(source)
                        addAll(plan.memberAccesses.getValue(source).transitiveCalls)
                    }
                }
                val semanticWriteFields = semanticSources.flatMapTo(linkedSetOf()) { source ->
                    plan.memberAccesses.getValue(source).transitiveWrites
                }
                val ownerDependentFields = plan.stateCarriers.keys.filterTo(linkedSetOf()) { field ->
                    field.type.referencesGenericOwnerParameter(owner)
                }
                val additionalSemanticBoundaryParameters = semanticSources.flatMapTo(
                    linkedSetOf(),
                ) { source ->
                    val family = plan.memberFamilies.getValue(source)
                    val explicitParameters = source.parameters.filter { parameter ->
                        parameter.kind != IrParameterKind.DispatchReceiver
                    }
                    check(explicitParameters.size == family.parameterSlotDomains.size) {
                        "Internal .NET backend error: closed semantic family changed its parameter vector"
                    }
                    explicitParameters.filterIndexed { index, parameter ->
                        // The current semantic hook is an object-domain body: direct owner
                        // parameters are erased there even when the natural slot is strict. A
                        // fixed nested semantic input is object by its producer record, and a
                        // broad slot is object by its family contract. This applies to private
                        // hooks too; their physical parameter is not !T merely because all calls
                        // are compiler-generated.
                        parameter.type.referencesGenericOwnerParameter(owner) ||
                                family.parameterSlotDomains[index] ==
                                DotNetGenericOwnerPhysicalSlotDomain.BROAD_CANDIDATE_INPUT ||
                                index in family.semanticObjectParameterIndices
                    }
                }
                val finalWriteProvenances = TypedWriteValueProvenanceAnalyzer(
                    owner = owner,
                    members = plan.memberPolicies.keys.toList(),
                    memberPolicies = plan.memberPolicies,
                    producerAccesses = producerAccesses,
                    initializerAccesses = initializerAccesses,
                    additionalSemanticBoundaryParameters = additionalSemanticBoundaryParameters,
                ).analyze(ownerDependentFields)
                val finalSemanticValueWriteFields = finalWriteProvenances
                    .filterValues { writes ->
                        writes.any { write ->
                            write.provenance == DotNetGenericOwnerWriteValueProvenance.SEMANTIC_OBJECT
                        }
                    }
                    .keys
                val stateCarriers = plan.stateCarriers.mapValuesTo(linkedMapOf()) { stateEntry ->
                    val state = stateEntry.value
                    val semanticReaders = state.directReaders.filterTo(
                        linkedSetOf(),
                        semanticProducerFunctions::contains,
                    )
                    val semanticWriters = state.directWriters.filterTo(
                        linkedSetOf(),
                        semanticProducerFunctions::contains,
                    )
                    val writes = finalWriteProvenances[state.field] ?: state.writes
                    val hasOnlyProvenTypedWrites = writes.isNotEmpty() && writes.all { write ->
                        write.provenance == DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED
                    }
                    state.copy(
                        requirement = when {
                            state.requirement ==
                                    DotNetGenericOwnerStateCarrierRequirement.DECLARATION_INDEPENDENT_STORAGE ->
                                state.requirement
                            state.requirement in setOf(
                                DotNetGenericOwnerStateCarrierRequirement.VOLATILE_OBJECT_STORAGE_REQUIRED,
                                DotNetGenericOwnerStateCarrierRequirement.COMPLETE_ACCESS_GRAPH_REQUIRED,
                            ) -> state.requirement
                            state.field in finalSemanticValueWriteFields ->
                                DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED
                            state.requirement ==
                                    DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN &&
                                    !hasOnlyProvenTypedWrites ->
                                DotNetGenericOwnerStateCarrierRequirement.TYPED_WRITE_VALUE_PROVENANCE_REQUIRED
                            state.requirement ==
                                    DotNetGenericOwnerStateCarrierRequirement.TYPED_WRITE_VALUE_PROVENANCE_REQUIRED &&
                                    hasOnlyProvenTypedWrites ->
                                DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN
                            else -> state.requirement
                        },
                        writes = writes,
                        semanticReachableReaders = state.semanticReachableReaders + semanticReaders,
                        semanticReachableWriters = state.semanticReachableWriters + semanticWriters,
                    )
                }
                val directSemanticWriteFields = semanticSources.flatMapTo(linkedSetOf()) { source ->
                    plan.memberAccesses.getValue(source).directWrites
                }
                val updatedPlan = plan.copy(
                    stateCarriers = stateCarriers,
                    directSemanticWriteFields = plan.directSemanticWriteFields + directSemanticWriteFields,
                    semanticReachableWriteFields = plan.semanticReachableWriteFields + semanticWriteFields,
                    semanticValueWriteFields = plan.semanticValueWriteFields + finalSemanticValueWriteFields,
                )
                if (updatedPlan != plan) {
                    plans[owner] = updatedPlan
                    changed = true
                }
            }

            // State which became semantic only through the preceding late closure must still
            // pair every affected output. An open owner-dependent output retains the existing
            // override-family coherence rule; any direct or nested T output which actually reads
            // semantic state needs its body on the semantic hook even when the member is final.
            plans.entries.toList().forEach { planEntry ->
                val owner = planEntry.key
                val plan = plans.getValue(owner)
                val semanticStateFields = plan.stateCarriers.values
                    .filterTo(linkedSetOf()) { state ->
                        state.requirement == DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED
                    }
                    .mapTo(linkedSetOf(), DotNetGenericOwnerStateCarrierPlan::field)
                if (semanticStateFields.isEmpty()) return@forEach
                val families = plan.memberFamilies.toMutableMap()
                plan.memberFamilies.forEach familyLoop@{ familyEntry ->
                    val source = familyEntry.key
                    val family = familyEntry.value
                    if (!source.returnType.referencesGenericOwnerParameter(owner)) return@familyLoop
                    val pairsOpenOutput = source in plan.openOwnerOutputs
                    val readsSemanticState = plan.memberAccesses.getValue(source).transitiveReads
                        .any(semanticStateFields::contains)
                    if (!pairsOpenOutput && !readsSemanticState) return@familyLoop
                    val addedReasons = buildSet {
                        if (pairsOpenOutput) {
                            add(DotNetGenericOwnerSemanticHookReason.PAIRED_OPEN_OUTPUT_STATE)
                        }
                        if (readsSemanticState) {
                            add(DotNetGenericOwnerSemanticHookReason.PAIRED_SEMANTIC_STATE_OUTPUT)
                        }
                    }
                    val updatedRoles = buildSet {
                        addAll(family.roles)
                        add(DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
                        if (!DescriptorVisibilities.isPrivate(source.visibility)) {
                            add(DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER)
                        }
                    }
                    val updatedFamily = family.copy(
                        roles = updatedRoles,
                        semanticHookReasons = family.semanticHookReasons + addedReasons,
                    )
                    if (updatedFamily != family) {
                        families[source] = updatedFamily
                        changed = true
                    }
                }
                if (families != plan.memberFamilies) {
                    plans[owner] = plan.copy(memberFamilies = families)
                }
            }

            // Recompute the priority-compressed owner result inside the same fixed point. The
            // next construction-site step asks which enclosing TypeDefs still expose real `!n`
            // binders, so it must not consume a disposition from before semantic/state closure.
            plans.entries.toList().forEach { planEntry ->
                val owner = planEntry.key
                val plan = plans.getValue(owner)
                val disposition = candidateDisposition(
                    owner = owner,
                    conditionalSupertypes = plan.metadataFixedConditionalSupertypes,
                    constructors = plan.constructors,
                    stateCarriers = plan.stateCarriers.values,
                    openOutputs = plan.openOwnerOutputs,
                    memberFamilies = plan.memberFamilies.values,
                    hasUnboundConstructionSiteTypeSpec =
                        owner in constructionSiteBlockedOwners,
                )
                if (disposition != plan.disposition) {
                    plans[owner] = plan.copy(disposition = disposition)
                    changed = true
                }
            }

            // A local/generated TypeDef is admitted only when every allocation can spell its
            // complete invariant TypeSpec from the actual enclosing CLR binders. Repeat after
            // each removal: demoting one generated/enclosing owner can remove the `!n` authority
            // used by another allocation site. Constructor *value* parameters remain governed by
            // their independent slot/provenance analysis and do not contaminate this TypeSpec test.
            plans.entries.toList().forEach { planEntry ->
                val owner = planEntry.key
                val plan = plans.getValue(owner)
                if (!owner.isNonAbiGenericOwnerImplementation() ||
                    owner in constructionSiteBlockedOwners ||
                    !plan.isReifiedByGenericOwnerRehearsal
                ) return@forEach
                val sites = constructionSitesByOwner[owner].orEmpty()
                if (sites.any { site -> !site.hasVerifierVisibleTypeSpec(owner, plans) }) {
                    constructionSiteBlockedOwners += owner
                    val disposition = candidateDisposition(
                        owner = owner,
                        conditionalSupertypes = plan.metadataFixedConditionalSupertypes,
                        constructors = plan.constructors,
                        stateCarriers = plan.stateCarriers.values,
                        openOutputs = plan.openOwnerOutputs,
                        memberFamilies = plan.memberFamilies.values,
                        hasUnboundConstructionSiteTypeSpec = true,
                    )
                    plans[owner] = plan.copy(disposition = disposition)
                    changed = true
                }
            }
        } while (changed)

        plans.entries.toList().forEach { planEntry ->
            val owner = planEntry.key
            val plan = plans.getValue(owner)
            val disposition = candidateDisposition(
                owner = owner,
                conditionalSupertypes = plan.metadataFixedConditionalSupertypes,
                constructors = plan.constructors,
                stateCarriers = plan.stateCarriers.values,
                openOutputs = plan.openOwnerOutputs,
                memberFamilies = plan.memberFamilies.values,
                hasUnboundConstructionSiteTypeSpec =
                    owner in constructionSiteBlockedOwners,
            )
            val semanticStateFields = plan.stateCarriers.values
                .filter { state ->
                    state.requirement == DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED
                }
                .mapTo(linkedSetOf(), DotNetGenericOwnerStateCarrierPlan::field)
            check(plan.stateCarriers.values.all { state ->
                state.requirement !=
                        DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN ||
                        state.writes.isNotEmpty() && state.writes.all { write ->
                            write.provenance == DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED
                        }
            }) {
                "Internal .NET backend error: final semantic closure invalidated typed state provenance"
            }
            check(plan.memberFamilies.all { familyEntry ->
                val source = familyEntry.key
                val family = familyEntry.value
                !source.returnType.referencesGenericOwnerParameter(owner) ||
                        plan.memberAccesses.getValue(source).transitiveReads.none(semanticStateFields::contains) ||
                        DotNetGenericOwnerSemanticHookReason.PAIRED_SEMANTIC_STATE_OUTPUT in
                        family.semanticHookReasons &&
                        DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in family.roles
            }) {
                "Internal .NET backend error: late semantic state lacks a paired output family"
            }
            if (disposition == DotNetGenericOwnerCandidateDisposition.BLOCKED_OPEN_OUTPUT_STATE_COHERENCE) {
                check(plan.openOwnerOutputs.all { output ->
                    DotNetGenericOwnerSemanticHookReason.PAIRED_OPEN_OUTPUT_STATE in
                            plan.memberFamilies.getValue(output).semanticHookReasons &&
                            DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in
                            plan.memberFamilies.getValue(output).roles
                }) {
                    "Internal .NET backend error: late open output/state coherence lacks a paired family"
                }
            }
            if (disposition != plan.disposition) {
                plans[owner] = plan.copy(disposition = disposition)
            }
        }

        plans.entries.toList().forEach { planEntry ->
            val owner = planEntry.key
            val plan = plans.getValue(owner)
            // Rebuild every detached prototype from the closed family policy. Retaining a
            // prototype created before override closure could preserve a typed result after the
            // family inherited a semantic-result obligation through a fixed substitution.
            val prototypes = plan.memberFamilies.entries.mapIndexed { memberIndex, familyEntry ->
                val source = familyEntry.key
                val family = familyEntry.value
                source to family.roles.associateWithTo(linkedMapOf()) { role ->
                    createDetachedPrototypeMember(
                        owner,
                        source,
                        role,
                        memberIndex,
                        requiresSemanticResultCapability = family.requiresSemanticResultCapability,
                        authoritativeSemanticObjectParameterIndices =
                            family.semanticObjectParameterIndices,
                    )
                }
            }.toMap(linkedMapOf())
            plans[owner] = plan.copy(prototypeMembers = prototypes)
        }

        fun externalSemanticPrototype(
            source: IrSimpleFunction,
            binding: DotNetBoundGenericOwnerMemberFamily,
        ): IrSimpleFunction = externalSemanticPrototypesBySource.getOrPut(source) {
            val owner = source.parent as? IrClass
                ?: error("external generic-owner semantic member has no class owner")
            val ownerPath = checkNotNull(binding.family.semanticHookOwnerPath) {
                "external generic-owner semantic family lacks its hook owner"
            }
            val methodName = checkNotNull(binding.family.semanticHookMethodName) {
                "external generic-owner semantic family lacks its hook MethodDef"
            }
            createDetachedPrototypeMember(
                owner,
                source,
                DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
                externalSemanticPrototypesBySource.size,
                requiresSemanticResultCapability =
                    binding.family.requiresSemanticResultCapability,
                authoritativeSemanticObjectParameterIndices =
                    binding.family.semanticObjectParameterIndices,
            ).function.also { prototype ->
                if (binding.family.requiresSemanticResultCapability) {
                    context.genericOwnerCapabilityDeclarations += prototype
                }
                markSemanticParameterCarriers(
                    owner,
                    source.parameters.drop(1),
                    prototype.parameters.drop(1),
                    binding.family.semanticObjectParameterIndices,
                )
                context.externalGenericOwnerPhysicalSlots[prototype] =
                    DotNetBoundGenericOwnerPhysicalSlot(
                        binding.library,
                        binding.family,
                        ownerPath,
                        methodName,
                    )
            }
        }.also { prototype ->
            val previous = context.externalGenericOwnerSemanticHooks.put(source, prototype)
            check(previous == null || previous === prototype) {
                "Internal .NET backend error: external generic-owner member acquired " +
                        "multiple physical semantic hooks"
            }
        }

        fun bindExternalForeignOverrideProbe(
            source: IrSimpleFunction,
            binding: DotNetBoundGenericOwnerMemberFamily,
            semanticPrototype: IrSimpleFunction,
        ) {
            val methodName = binding.family.foreignOverrideProbeMethodName ?: return
            externalForeignOverrideProbesBySource.getOrPut(source) {
                val owner = source.parent as? IrClass
                    ?: error("external generic-owner foreign-override probe has no class owner")
                val ownerPath = checkNotNull(binding.family.semanticHookOwnerPath) {
                    "external generic-owner foreign-override probe lacks its CLR owner"
                }
                context.irFactory.buildFun {
                    startOffset = source.startOffset
                    endOffset = source.endOffset
                    origin = DOTNET_GENERIC_OWNER_PROTOTYPE_MEMBER
                    name = Name.special(
                        "<ExternalGenericOwnerForeignOverrideProbe-${externalForeignOverrideProbesBySource.size}>"
                    )
                    visibility = DescriptorVisibilities.PROTECTED
                    modality = Modality.OPEN
                    returnType = context.irBuiltIns.booleanType
                }.apply {
                    parent = owner
                    parameters += createDispatchReceiverParameterWithClassParent()
                    copyTypeParametersFrom(semanticPrototype)
                    context.externalGenericOwnerPhysicalSlots[this] =
                        DotNetBoundGenericOwnerPhysicalSlot(
                            binding.library,
                            binding.family,
                            ownerPath,
                            methodName,
                        )
                }
            }
        }

        // A reified local class can inherit a semantic family without declaring an override.
        // Its later interface MethodImpl still needs the producer's protected semantic MethodDef;
        // a fake override is only the logical selector and owns no callable CLR declaration.
        // Materialize that producer-bound stub eagerly from the family record rather than making
        // bridge correctness depend on whether this module also happens to contain a direct call.
        plans.values.filter { plan -> plan.isReifiedByGenericOwnerRehearsal }.forEach { plan ->
            plan.memberFamilies.forEach { familyEntry ->
                val source = familyEntry.key
                if (!source.isFakeOverride ||
                    DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK !in familyEntry.value.roles
                ) return@forEach
                val declaringSource = declaringOverride(source)
                val binding = externalDeclarations.genericOwnerMemberFamilyOrNull(declaringSource)
                    ?: return@forEach
                if (binding.family.semanticHookMethodName != null) {
                    externalSemanticPrototype(declaringSource, binding)
                }
            }
        }

        plans.entries.toList().forEach { planEntry ->
            val owner = planEntry.key
            val plan = plans.getValue(owner)
            val bindings = linkedMapOf<IrSimpleFunction, MutableList<DotNetGenericOwnerOverrideBindingPlan>>()
            plan.memberFamilies.forEach { familyEntry ->
                val source = familyEntry.key
                if (source.isFakeOverride) return@forEach
                val family = familyEntry.value
                source.overriddenSymbols.forEach { overriddenSymbol ->
                    val overridden = declaringOverride(overriddenSymbol.owner)
                    val overriddenOwner = overridden.parent as? IrClass ?: return@forEach
                    if (!overriddenOwner.isDotNetGenericClassDeclaration) return@forEach
                    val overriddenPlan = plans[overriddenOwner]
                    val overriddenFamily = overriddenPlan?.memberFamilies?.get(overridden)
                    if (overriddenPlan != null && overriddenFamily != null) {
                        listOf(
                            DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
                            DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
                        ).forEach { role ->
                            if (role !in family.roles || role !in overriddenFamily.roles) return@forEach
                            val sourcePrototype = checkNotNull(plan.prototypeMembers[source]?.get(role))
                            val overriddenPrototype = checkNotNull(overriddenPlan.prototypeMembers[overridden]?.get(role))
                            sourcePrototype.function.overriddenSymbols =
                                (sourcePrototype.function.overriddenSymbols + overriddenPrototype.function.symbol).distinct()
                            bindings.getOrPut(source) { mutableListOf() } += DotNetGenericOwnerOverrideBindingPlan(
                                source = source,
                                role = role,
                                overriddenSource = overridden,
                                targetKind = DotNetGenericOwnerOverrideTargetKind.LOCAL_DETACHED_PROTOTYPE,
                                overriddenLogicalBindingKey = overriddenFamily.logicalBindingKey,
                            )
                        }
                    } else {
                        val declaringOverride = declaringOverride(overridden)
                        bindings.getOrPut(source) { mutableListOf() } += DotNetGenericOwnerOverrideBindingPlan(
                            source = source,
                            role = DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY,
                            overriddenSource = declaringOverride,
                            targetKind = DotNetGenericOwnerOverrideTargetKind.EXTERNAL_LOGICAL_BINDING_REQUIRED,
                            overriddenLogicalBindingKey = declaringOverride.dotNetLibraryAbiKeyOrNull("F"),
                        )
                        val externalBinding = externalFamily(declaringOverride)
                        if (DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in family.roles &&
                            externalBinding?.family?.semanticHookMethodName != null
                        ) {
                            val sourcePrototype = checkNotNull(
                                plan.prototypeMembers[source]
                                    ?.get(DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK)
                            )
                            val overriddenPrototype = externalSemanticPrototype(declaringOverride, externalBinding)
                            sourcePrototype.function.overriddenSymbols =
                                (sourcePrototype.function.overriddenSymbols + overriddenPrototype.symbol).distinct()
                            bindings.getOrPut(source) { mutableListOf() } +=
                                DotNetGenericOwnerOverrideBindingPlan(
                                    source = source,
                                    role = DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
                                    overriddenSource = declaringOverride,
                                    targetKind =
                                        DotNetGenericOwnerOverrideTargetKind.EXTERNAL_PHYSICAL_FAMILY_RECORD,
                                    overriddenLogicalBindingKey =
                                        declaringOverride.dotNetLibraryAbiKeyOrNull("F"),
                                )
                            bindExternalForeignOverrideProbe(
                                declaringOverride,
                                externalBinding,
                                overriddenPrototype,
                            )
                        }
                    }
                }
            }
            check(bindings.values.flatten().none { binding ->
                binding.role == DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER
            }) {
                "Internal .NET backend error: a private generic-owner capability dispatcher entered an override chain"
            }
            val needsExternalBindingSchema = bindings.values.flatten().any { binding ->
                binding.targetKind == DotNetGenericOwnerOverrideTargetKind.EXTERNAL_LOGICAL_BINDING_REQUIRED
            }
            plans[owner] = plan.copy(
                disposition = if (needsExternalBindingSchema &&
                    plan.disposition == DotNetGenericOwnerCandidateDisposition.REQUIRES_MEMBER_PHYSICALIZATION_PROOF
                ) {
                    DotNetGenericOwnerCandidateDisposition.REQUIRES_EXTERNAL_OVERRIDE_BINDING_SCHEMA
                } else {
                    plan.disposition
                },
                overrideBindings = bindings,
            )
        }
    }

    private fun createDetachedPrototypeMember(
        owner: IrClass,
        source: IrSimpleFunction,
        role: DotNetGenericOwnerMemberFamilyRole,
        memberIndex: Int,
        requiresSemanticResultCapability: Boolean,
        authoritativeSemanticObjectParameterIndices: Set<Int>? = null,
    ): DotNetGenericOwnerPrototypeMember {
        val prototype = context.irFactory.buildFun {
            startOffset = source.startOffset
            endOffset = source.endOffset
            origin = DOTNET_GENERIC_OWNER_PROTOTYPE_MEMBER
            name = Name.special("<GenericOwnerPrototype-${role.name}-$memberIndex>")
            visibility = when (role) {
                DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY -> source.visibility
                DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK ->
                    if (DescriptorVisibilities.isPrivate(source.visibility)) {
                        DescriptorVisibilities.PRIVATE
                    } else {
                        DescriptorVisibilities.PROTECTED
                    }
                DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER -> DescriptorVisibilities.PRIVATE
            }
            modality = when (role) {
                DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER -> Modality.FINAL
                else -> source.modality
            }
            returnType = context.irBuiltIns.anyNType
        }
        prototype.parent = owner
        prototype.parameters += prototype.createDispatchReceiverParameterWithClassParent()
        val copiedMethodParameters = prototype.copyTypeParametersFrom(source)
        val ownerErasure = IrTypeSubstitutor(
            owner.genericOwnerParameters().associate { parameter ->
                parameter.symbol to context.irBuiltIns.anyNType
            },
            allowEmptySubstitution = true,
        )
        if (role != DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY) {
            copiedMethodParameters.forEach { parameter ->
                parameter.superTypes = parameter.superTypes.map(ownerErasure::substitute)
            }
        }
        val methodSubstitution = source.typeParameters.zip(copiedMethodParameters).associate { pair ->
            pair.first.symbol to pair.second.symbol.defaultType
        }
        val methodSubstitutor = IrTypeSubstitutor(methodSubstitution, allowEmptySubstitution = true)
        fun prototypeType(type: IrType): IrType {
            val ownerType = if (role == DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY) {
                type
            } else {
                ownerErasure.substitute(type.projectOwnerDependentGenericApplications(owner))
            }
            return methodSubstitutor.substitute(ownerType)
        }
        prototype.returnType = if (
            role != DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY &&
            requiresSemanticResultCapability
        ) {
            // A semantic Nested<T> result can be either Kotlin's capability-bearing object or
            // an ordinary foreign natural I<T>. Object is the only carrier shared by both. Keep
            // that decision in IR as well as metadata so generated forwarding bodies never try
            // the impossible intermediate conversion Nested<!T> -> Nested<object>.
            context.irBuiltIns.anyNType
        } else {
            prototypeType(source.returnType)
        }
        val explicitParameters = source.parameters
            .filter { parameter -> parameter.kind != IrParameterKind.DispatchReceiver }
        check(authoritativeSemanticObjectParameterIndices == null ||
                authoritativeSemanticObjectParameterIndices.all(explicitParameters.indices::contains)
        ) {
            "Internal .NET backend error: producer semantic-input policy names a missing parameter"
        }
        explicitParameters.forEachIndexed { parameterIndex, parameter ->
            val carriesSemanticInterfaceConstruction =
                role != DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY &&
                        (authoritativeSemanticObjectParameterIndices?.let { indices ->
                            parameterIndex in indices
                        } ?: parameter.type.requiresSemanticInterfaceCarrierOf(owner))
            prototype.parameters += parameter.copyTo(
                prototype,
                type = if (carriesSemanticInterfaceConstruction) {
                    // A Kotlin-wide nested input can carry either a capability-bearing
                    // implementation or an ordinary foreign natural I<T>. Do not invent the
                    // physically unrelated I<object> construction before entering the hook.
                    context.irBuiltIns.anyNType
                } else {
                    prototypeType(parameter.type)
                },
                varargElementType = if (carriesSemanticInterfaceConstruction) {
                    null
                } else {
                    parameter.varargElementType?.let(::prototypeType)
                },
                defaultValue = null,
            )
        }
        return DotNetGenericOwnerPrototypeMember(source, role, prototype)
    }

    private fun IrType.projectOwnerDependentGenericApplications(owner: IrClass): IrType {
        val simpleType = this as? IrSimpleType ?: return this
        val classifier = (simpleType.classifier as? IrClassSymbol)?.owner
        if (classifier != null &&
            !classifier.isDotNetResolutionOnlyStdlibDeclaration &&
            classifier.isDotNetGenericClassDeclaration &&
            simpleType.referencesGenericOwnerParameter(owner)
        ) {
            // `Nested<T>` cannot become `Nested<object>` in the semantic family: CLR class
            // parameters are invariant, so that would be a different and generally incompatible
            // constructed owner. A star application is the IR-level marker for the non-generic
            // semantic capability which every admitted `Nested<X>` implements.
            return simpleType.buildSimpleType {
                kotlinType = null
                arguments = simpleType.arguments.map { IrStarProjectionImpl }
            }
        }
        var changed = false
        val projectedArguments = simpleType.arguments.map { argument ->
            if (argument !is IrTypeProjection) return@map argument
            val projected = argument.type.projectOwnerDependentGenericApplications(owner)
            if (projected == argument.type) argument
            else {
                changed = true
                makeTypeProjection(projected, argument.variance)
            }
        }
        return if (!changed) simpleType else simpleType.buildSimpleType {
            kotlinType = null
            arguments = projectedArguments
        }
    }

    /** Source parameters physically captured by this owner before CLR inner normalization. */
    private fun IrClass.genericOwnerParameters(): List<IrTypeParameter> = buildList {
        var current: IrClass? = this@genericOwnerParameters
        while (current != null) {
            addAll(current.typeParameters)
            current = if (current.isInner) current.parent as? IrClass else null
        }
    }

    private fun IrSimpleType.variantInterfaceDeclaredVariancesOrNull(): List<Variance>? {
        val classifier = (classifier as? IrClassSymbol)?.owner ?: return null
        // Class-state planning precedes physical interface admission. The pristine local index
        // answers only the earlier logical question: can Kotlin legally carry more than one CLR
        // construction through this boundary? It deliberately supplies no positive evidence that
        // an I<T> TypeDef exists. External Kotlin families and Runtime declarations use their
        // producer-recorded/declared authority; imported CLR interfaces never enter this branch.
        val localHazard = context.localGenericInterfaceLogicalHazards[classifier.symbol]
        val hasExternalOrRuntimeAuthority =
            externalDeclarations.hasReifiedGenericInterface(classifier) ||
                    DotNetRuntimeTypes.usesDeclaredViewByDefaultInRehearsal(classifier)
        return localHazard?.declaredVariances
            ?: classifier.typeParameters.map(IrTypeParameter::variance)
                .takeIf { hasExternalOrRuntimeAuthority }
    }

    private fun IrType.containsVariantInterfaceSemanticHazardOf(owner: IrClass): Boolean {
        val simpleType = this as? IrSimpleType ?: return false
        val declaredVariances = simpleType.variantInterfaceDeclaredVariancesOrNull()
        if (declaredVariances != null &&
            declaredVariances.zip(simpleType.arguments).any { pair ->
                val projection = pair.second as? IrTypeProjection ?: return@any false
                projection.type.referencesGenericOwnerParameter(owner) &&
                        (pair.first != Variance.INVARIANT ||
                                projection.variance != Variance.INVARIANT)
            }
        ) {
            return true
        }
        return simpleType.arguments.any { argument ->
            (argument as? IrTypeProjection)?.type?.containsVariantInterfaceSemanticHazardOf(owner) == true
        }
    }

    /**
     * Whether this logical value can denote more than one constructed CLR interface carrier.
     *
     * This is deliberately independent of the current generic owner. `I<Any?>` can physically be
     * an `I<int>` obtained through legal Kotlin covariance just as `I<T>` can. The fact is still
     * negative-only: it prevents an exact-result claim until a producer-wide proof exists, but it
     * never admits a TypeDef or fabricates a constructed CLR view.
     */
    private fun IrType.containsPotentiallyReparameterizedInterfaceCarrier(): Boolean {
        val simpleType = this as? IrSimpleType ?: return false
        val declaredVariances = simpleType.variantInterfaceDeclaredVariancesOrNull()
        if (declaredVariances != null &&
            declaredVariances.zip(simpleType.arguments).any { pair ->
                val projection = pair.second as? IrTypeProjection ?: return@any true
                pair.first != Variance.INVARIANT || projection.variance != Variance.INVARIANT
            }
        ) {
            return true
        }
        return simpleType.arguments.any { argument ->
            (argument as? IrTypeProjection)?.type
                ?.containsPotentiallyReparameterizedInterfaceCarrier() == true
        }
    }

    /**
     * Whether a non-generic semantic owner cannot truthfully name this interface result.
     *
     * A declaration-site/use-site variant (including a fixed `I<Any?>`) can denote another CLR
     * construction. A logically invariant `I<T>` has only one construction for a particular T,
     * but the semantic owner deliberately has no `!T` with which to spell it. Fixed invariant
     * constructions such as `I<Int>` remain natural and must not be contaminated by an unrelated
     * semantic member reason.
     */
    private fun IrType.requiresSemanticInterfaceCarrierOf(owner: IrClass): Boolean {
        if (containsPotentiallyReparameterizedInterfaceCarrier()) return true
        val simpleType = this as? IrSimpleType ?: return false
        if (simpleType.variantInterfaceDeclaredVariancesOrNull() != null &&
            simpleType.arguments.any { argument ->
                (argument as? IrTypeProjection)?.type
                    ?.referencesGenericOwnerParameter(owner) == true
            }
        ) {
            return true
        }
        return simpleType.arguments.any { argument ->
            (argument as? IrTypeProjection)?.type
                ?.requiresSemanticInterfaceCarrierOf(owner) == true
        }
    }

    /**
     * An invariant generic-class shell around a semantic interface view is one indivisible CLR
     * construction. The current value mapper may therefore choose `object` for the shell, while
     * the detached prototype grammar can still spell the logical `Box<Producer<!T>>`. That is an
     * authority conflict, not ordinary loss of provenance. Keep such a position outside the
     * rehearsal until declaration planning and live mapping consume one shared physical fact.
     */
    private fun IrType.requiresUnboundNestedGenericOwnerCarrierOf(owner: IrClass): Boolean {
        val type = this as? IrSimpleType ?: return false
        val classifier = (type.classifier as? IrClassSymbol)?.owner ?: return false
        if (!classifier.isDotNetGenericClassDeclaration || classifier.typeParameters.isEmpty()) return false
        return type.arguments.any { argument ->
            (argument as? IrTypeProjection)?.type
                ?.requiresSemanticInterfaceCarrierOf(owner) == true
        }
    }

    /** A projected generic-class carrier names its non-generic same-object capability. */
    private fun IrType.requiresGenericOwnerCapabilityMarker(): Boolean {
        val simpleType = this as? IrSimpleType ?: return false
        val classifier = (simpleType.classifier as? IrClassSymbol)?.owner ?: return false
        return classifier.isDotNetGenericClassDeclaration && simpleType.arguments.any { argument ->
            argument !is IrTypeProjection || argument.variance != Variance.INVARIANT
        }
    }

    private fun markSemanticParameterCarriers(
        owner: IrClass,
        logicalParameters: List<IrValueParameter>,
        physicalParameters: List<IrValueParameter>,
        authoritativeSemanticObjectParameterIndices: Set<Int>? = null,
    ) {
        check(logicalParameters.size == physicalParameters.size) {
            "Internal .NET backend error: semantic parameter-vector size changed"
        }
        check(authoritativeSemanticObjectParameterIndices == null ||
                authoritativeSemanticObjectParameterIndices.all(logicalParameters.indices::contains)
        ) {
            "Internal .NET backend error: producer semantic-input policy names a missing parameter"
        }
        logicalParameters.zip(physicalParameters).forEachIndexed { index, pair ->
            val logical = pair.first
            val physical = pair.second
            when {
                (authoritativeSemanticObjectParameterIndices?.let { indices -> index in indices }
                    ?: logical.type.requiresSemanticInterfaceCarrierOf(owner)) -> {
                    // The prototype already selected object. Retain both semantic Kotlin
                    // capability routing and the unique-natural foreign fallback for values
                    // entering through the typed C# entry.
                    context.genericOwnerCapabilityDeclarations += physical
                    context.genericOwnerForeignDispatchDeclarations += physical
                }
                physical.type.requiresGenericOwnerCapabilityMarker() -> {
                    // An owner-dependent generic class/projection is represented by its
                    // non-generic same-object capability. Fixed exact interface/class inputs
                    // deliberately do not enter this branch.
                    context.genericOwnerCapabilityDeclarations += physical
                }
            }
        }
    }

    private fun IrType.toGenericOwnerSemanticType(owner: IrClass): IrType {
        val ownerErasure = IrTypeSubstitutor(
            owner.genericOwnerParameters().associate { parameter ->
                parameter.symbol to (parameter.superTypes.firstOrNull() ?: context.irBuiltIns.anyNType)
            },
            allowEmptySubstitution = true,
        )
        return ownerErasure.substitute(projectOwnerDependentGenericApplications(owner))
    }

    private fun IrSimpleFunction.policyFor(owner: IrClass): DotNetGenericOwnerMemberPolicy {
        // Kotlin excludes private-to-owner declarations from declaration-site variance checks.
        // Such a helper is not itself callable through a widened receiver; it inherits semantic
        // reachability only through the producer call graph of an exposed broad entry.
        if (DescriptorVisibilities.isPrivate(visibility)) {
            return DotNetGenericOwnerMemberPolicy.STRICT_TYPED
        }
        if (name.asString() == "equals" &&
            returnType == context.irBuiltIns.booleanType &&
            parameters.count { parameter -> parameter.kind != IrParameterKind.DispatchReceiver } == 1 &&
            parameters.last().type.isNullableAny()
        ) {
            // Kotlin data-class and user equals implementations test only the classifier. Once
            // `C<A>` and `C<B>` are distinct constructed CLR types, running that body on the
            // natural C<T> entry would make its post-test field reads cast the candidate back to
            // this exact construction. Keep equals in the semantic domain so different type
            // arguments can still compare their values, exactly as Kotlin's erased classifier
            // contract requires.
            return DotNetGenericOwnerMemberPolicy.SEMANTIC_BODY
        }
        val hasBroadInput = parameters.any { parameter ->
            parameter.kind != IrParameterKind.DispatchReceiver &&
                    !parameter.type.isLegalAtOwnerVariance(owner, TypePolarity.IN)
        } || allOverridden().any { overridden ->
            val interfaceOwner = (overridden.parent as? IrClass)
                ?.takeIf { candidate -> candidate.kind == ClassKind.INTERFACE }
                ?: return@any false
            overridden.parameters.any { parameter ->
                parameter.kind != IrParameterKind.DispatchReceiver &&
                        !parameter.type.isLegalAtOwnerVariance(interfaceOwner, TypePolarity.IN)
            }
        }
        if (!hasBroadInput) return DotNetGenericOwnerMemberPolicy.STRICT_TYPED
        return if (specialBridgeMethods.findSpecialWithOverride(this, includeSelf = true) != null) {
            DotNetGenericOwnerMemberPolicy.TYPE_SAFE_BARRIER
        } else {
            DotNetGenericOwnerMemberPolicy.SEMANTIC_BODY
        }
    }

    private fun IrClass.directSimpleFunctions(): List<IrSimpleFunction> =
        declarations.flatMap { declaration ->
            when (declaration) {
                is IrSimpleFunction -> listOf(declaration)
                is IrProperty -> listOfNotNull(declaration.getter, declaration.setter)
                else -> emptyList()
            }
        }.distinctBy { function -> function.symbol }

    private fun IrClass.directFields(): Set<IrField> =
        declarations.flatMapTo(linkedSetOf()) { declaration ->
            when (declaration) {
                is IrField -> listOf(declaration)
                is IrProperty -> listOfNotNull(declaration.backingField)
                else -> emptyList()
            }
        }

    private fun IrField.hasExactTypedGenericOwnerStateCarrier(): Boolean {
        val owner = parent as? IrClass ?: return false
        val plan = context.genericOwnerArchitecturePlans[owner]
            ?.takeIf(DotNetGenericOwnerArchitecturePlan::isReifiedByGenericOwnerRehearsal)
            ?: return false
        return when (plan.stateCarriers[this]?.requirement) {
            DotNetGenericOwnerStateCarrierRequirement.DECLARATION_INDEPENDENT_STORAGE,
            DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN,
                -> true

            else -> false
        }
    }

    private fun IrSimpleFunction.exactTypedStateGetterBackingFieldOrNull(): IrField? {
        val owner = parent as? IrClass ?: return null
        val stateCarriers = context.genericOwnerArchitecturePlans[owner]
            ?.takeIf(DotNetGenericOwnerArchitecturePlan::isReifiedByGenericOwnerRehearsal)
            ?.stateCarriers
            ?: return null
        return exactTypedStateGetterBackingFieldOrNull(stateCarriers)
    }

    private fun IrSimpleFunction.exactTypedStateGetterBackingFieldOrNull(
        stateCarriers: Map<IrField, DotNetGenericOwnerStateCarrierPlan>,
    ): IrField? {
        val property = correspondingPropertySymbol?.owner ?: return null
        if (property.getter !== this) return null
        if (origin != IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR ||
            modality != Modality.FINAL ||
            !DescriptorVisibilities.isPrivate(visibility)
        ) {
            return null
        }
        val owner = parent as? IrClass ?: return null
        if (property.parent !== owner) return null
        return property.backingField?.takeIf { field ->
            field.parent === owner && stateCarriers[field]?.requirement in setOf(
                DotNetGenericOwnerStateCarrierRequirement.DECLARATION_INDEPENDENT_STORAGE,
                DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN,
            )
        }
    }

    /**
     * Computes static receiver evidence for every Kotlin-owned generic-class call in this module.
     * The result is deliberately more conservative than call lowering: it cannot alter IR and an
     * unsupported producer always becomes a capability or external-family proof obligation.
     */
    private enum class ReceiverOriginKind {
        EXACT,
        UNRESOLVED,
    }

    private data class ReceiverOrigin(
        val kind: ReceiverOriginKind,
        val exactType: IrType? = null,
    ) {
        init {
            require((kind == ReceiverOriginKind.EXACT) == (exactType != null)) {
                "an exact generic-owner receiver origin requires exactly one physical type"
            }
        }
    }

    private data class CallScope(
        val callerName: String,
        val callerLogicalBindingKey: String?,
        val accesses: DirectMemberAccesses,
    )

    private data class GenericOwnerCallTarget(
        val callee: IrSimpleFunction,
        val owner: IrClass,
        val logicalBindingKey: String?,
        val localFamily: DotNetGenericOwnerMemberFamilyPlan?,
        val receiver: IrExpression,
    )

    private inner class GenericOwnerCallRouteAnalyzer(
        private val producerFunctions: Set<IrFunction>,
        private val producerInitializers: List<ProducerInitializer>,
        private val producerAccesses: Map<IrFunction, DirectMemberAccesses>,
        private val initializerAccesses: Map<ProducerInitializer, DirectMemberAccesses>,
    ) {

        private val origins = linkedMapOf<Any, MutableSet<ReceiverOrigin>>()
        private val localDefaultSourcesByDispatcher = context.defaultArgumentDispatchers.entries.associate { entry ->
            entry.value to entry.key
        }

        fun analyze(): List<DotNetGenericOwnerCallRoutePlan> {
            seedBoundaries()
            val accesses = producerAccesses.values + initializerAccesses.values
            accesses.forEach { access ->
                access.valueDefinitions.keys.forEach(::node)
                access.reads.forEach(::node)
                access.writes.forEach(::node)
            }
            producerFunctions.forEach(::node)

            var changed: Boolean
            do {
                changed = false
                accesses.forEach { access ->
                    access.valueDefinitions.forEach { entry ->
                        changed = addOrigins(
                            entry.key,
                            entry.value.flatMapTo(linkedSetOf(), ::originsOf),
                        ) || changed
                    }
                    access.writeValues.forEach { entry ->
                        changed = addOrigins(
                            entry.key,
                            entry.value.flatMapTo(linkedSetOf()) { value ->
                                originsOfWrite(entry.key, value)
                            },
                        ) || changed
                    }
                }
                producerFunctions.forEach { function ->
                    changed = addOrigins(
                        function,
                        producerAccesses.getValue(function).returns.flatMapTo(linkedSetOf(), ::originsOf),
                    ) || changed
                }
                accesses.forEach { access ->
                    access.callSites.forEach { call ->
                        val target = call.symbol.owner
                        if (target !in producerFunctions || !target.hasClosedCallBoundary()) return@forEach
                        target.parameters.forEach { parameter ->
                            if (parameter.kind == IrParameterKind.DispatchReceiver) return@forEach
                            val argument = call.arguments.getOrNull(parameter.indexInParameters)
                            changed = addOrigins(
                                parameter,
                                if (argument == null) unresolved() else originsOf(argument),
                            ) || changed
                        }
                    }
                }
            } while (changed)

            markCapabilityDeclarations(accesses)

            val scopes = producerFunctions.map { function ->
                CallScope(
                    callerName = function.fqNameWhenAvailable?.asString() ?: function.name.asString(),
                    callerLogicalBindingKey = context.preLoweringDeclarationKeys[function],
                    accesses = producerAccesses.getValue(function),
                )
            } + producerInitializers.map { initializer ->
                CallScope(
                    callerName = initializer.label,
                    callerLogicalBindingKey = null,
                    accesses = initializerAccesses.getValue(initializer),
                )
            }
            return buildList {
                var relevantCallIndex = 0
                scopes.forEach { scope ->
                    scope.accesses.genericOwnerCallSites.forEach { call ->
                        val target = call.genericOwnerCallTargetOrNull() ?: return@forEach
                        val provenance = if (call.superQualifierSymbol != null) {
                            DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION
                        } else {
                            receiverProvenance(target.receiver)
                        }
                        val localOwnerPlan = context.genericOwnerArchitecturePlans[target.owner]
                        val requirement = when {
                            localOwnerPlan != null &&
                                    !localOwnerPlan.isReifiedByGenericOwnerRehearsal ->
                                // Local family shape is logical evidence only. Once owner
                                // admission has failed, neither an exact receiver origin nor its
                                // detached typed prototype can recreate the missing CLR TypeDef.
                                DotNetGenericOwnerCallRouteRequirement.PRODUCER_ERASED_OWNER
                            target.localFamily == null ->
                                DotNetGenericOwnerCallRouteRequirement.EXTERNAL_FAMILY_RECORD_REQUIRED
                            provenance == DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION &&
                                    target.callee.exactTypedStateGetterBackingFieldOrNull() != null ->
                                // A private/final trivial accessor over producer-proven exact
                                // state is an exact read even when the enclosing body is also
                                // exposed through a semantic result hook. The hook boundary may
                                // widen the returned value afterwards; it must not rewrite the
                                // authoritative field read itself.
                                DotNetGenericOwnerCallRouteRequirement.EXACT_TYPED_ENTRY
                            provenance == DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION &&
                                    target.localFamily.requiresSemanticResultCapability ->
                                DotNetGenericOwnerCallRouteRequirement.SEMANTIC_RESULT_CAPABILITY
                            provenance == DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION &&
                                    target.localFamily.requiresSemanticInterfaceInputCapability ->
                                // An exact owner construction proves the receiver, not a fixed
                                // covariant interface argument. Kotlin may pass I<int> through a
                                // logical I<Any?> parameter here, so the operation must enter the
                                // object-domain hook even though unrelated receiver-derived state
                                // and an ordinary result remain exactly typed.
                                DotNetGenericOwnerCallRouteRequirement.SEMANTIC_INPUT_CAPABILITY
                            provenance == DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION -> {
                                check(DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY in target.localFamily.roles) {
                                    "Internal .NET backend error: an exact generic-owner call lacks a typed entry"
                                }
                                DotNetGenericOwnerCallRouteRequirement.EXACT_TYPED_ENTRY
                            }
                            DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER in target.localFamily.roles ->
                                DotNetGenericOwnerCallRouteRequirement.SEMANTIC_CAPABILITY
                            else -> DotNetGenericOwnerCallRouteRequirement.MISSING_CAPABILITY
                        }
                        add(DotNetGenericOwnerCallRoutePlan(
                            callerName = scope.callerName,
                            callerLogicalBindingKey = scope.callerLogicalBindingKey,
                            callSiteIndex = relevantCallIndex++,
                            call = call,
                            callee = target.callee,
                            calleeOwner = target.owner,
                            calleeLogicalBindingKey = target.logicalBindingKey,
                            receiverProvenance = provenance,
                            routeRequirement = requirement,
                        ))
                    }
                }
            }
        }

        private fun markCapabilityDeclarations(accesses: List<DirectMemberAccesses>) {
            fun IrDeclaration.requiresClassCapabilityForPhysicalScope(type: IrType): Boolean {
                val physicalScopeOwner = parentClassOrNull ?: return false
                val scopePlan = context.genericOwnerArchitecturePlans[physicalScopeOwner] ?: return false
                if (scopePlan.isReifiedByGenericOwnerRehearsal ||
                    !type.referencesTypeParameterOf(physicalScopeOwner)
                ) {
                    return false
                }
                val valueOwner = ((type as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                    ?: return false
                if (valueOwner.kind == ClassKind.INTERFACE) return false
                return context.genericOwnerArchitecturePlans[valueOwner]
                    ?.isReifiedByGenericOwnerRehearsal == true ||
                        externalDeclarations.reifiedGenericOwnerLogicalKeyOrNull(valueOwner) != null
            }

            fun markIfWidened(
                declaration: IrDeclaration,
                type: IrType,
                emptyFlowIsExact: Boolean = false,
            ) {
                if (!type.hasRelevantGenericOwnerInAncestry()) return
                // A declaration-independent or producer-proven typed state decision is stronger
                // evidence than the general value-flow fallback below. The natural field/getter
                // remains on its exact CLR carrier; widened receiver calls use separately
                // materialized capability members.
                if (declaration is IrField && declaration.hasExactTypedGenericOwnerStateCarrier()) return
                if (declaration is IrSimpleFunction &&
                    declaration.exactTypedStateGetterBackingFieldOrNull() != null
                ) {
                    return
                }
                val candidates = origins[declaration].orEmpty()
                val needsCapability = declaration.requiresClassCapabilityForPhysicalScope(type) ||
                        (candidates.isEmpty() && !emptyFlowIsExact) || candidates.any { origin ->
                            origin.kind == ReceiverOriginKind.UNRESOLVED ||
                                    !checkNotNull(origin.exactType).hasPhysicalView(type)
                        }
                if (needsCapability) context.genericOwnerCapabilityDeclarations += declaration
            }

            producerFunctions.forEach { function ->
                function.parameters
                    .filter { parameter -> parameter.kind != IrParameterKind.DispatchReceiver }
                    .forEach { parameter -> markIfWidened(parameter, parameter.type) }
                if (function is IrSimpleFunction) {
                    // A body-owning function with an invariant exact result and an empty
                    // provenance fixpoint has no reachable non-null result (for example a
                    // generated ExactFunction adapter over `error()` or an only-null path).
                    // Its already-selected exact CLR override slot remains truthful: there is
                    // no value that could require the classifier-wide capability carrier.
                    markIfWidened(
                        function,
                        function.returnType,
                        emptyFlowIsExact = function.body != null &&
                                function.returnType.isStaticallyExactGenericOwnerView(),
                    )
                }
            }
            accesses.flatMapTo(linkedSetOf()) { access -> access.valueDefinitions.keys }
                .forEach { declaration -> markIfWidened(declaration, declaration.type) }
            buildSet {
                accesses.forEach { access ->
                    addAll(access.reads)
                    addAll(access.writes)
                }
            }.forEach { field -> markIfWidened(field, field.type) }
        }

        private fun seedBoundaries() {
            producerInitializers.mapNotNull { initializer ->
                initializer.owner
                    ?.takeIf { owner -> owner.hasRelevantGenericOwnerInAncestry() }
                    ?.thisReceiver
            }.forEach { receiver ->
                // Anonymous and field initializers execute on the newly constructed physical
                // owner. Unlike an arbitrary value entering a function boundary, their `this`
                // cannot be a projected/covariant view supplied by a caller.
                addOrigin(receiver, exact(receiver.type))
            }
            producerFunctions.forEach { function ->
                function.parameters.forEach { parameter ->
                    when {
                        parameter.kind == IrParameterKind.DispatchReceiver ->
                            addOrigin(parameter, exact(parameter.type))
                        !function.hasClosedCallBoundary() -> if (parameter.type.isStaticallyExactGenericOwnerView()) {
                            addOrigin(parameter, exact(parameter.type))
                        } else {
                            addOrigins(parameter, unresolved())
                        }
                    }
                }
                if (function.body == null) {
                    if (function.returnType.isStaticallyExactGenericOwnerView()) {
                        addOrigin(function, exact(function.returnType))
                    } else {
                        addOrigins(function, unresolved())
                    }
                }
            }
            val fields = buildSet {
                producerAccesses.values.forEach { access ->
                    addAll(access.reads)
                    addAll(access.writes)
                }
                initializerAccesses.values.forEach { access ->
                    addAll(access.reads)
                    addAll(access.writes)
                }
            }
            fields.filterNotTo(linkedSetOf()) { field -> DescriptorVisibilities.isPrivate(field.visibility) }
                .forEach { field ->
                    if (field.type.isStaticallyExactGenericOwnerView()) {
                        addOrigin(field, exact(field.type))
                    } else {
                        addOrigins(field, unresolved())
                    }
                }
        }

        private fun IrFunction.hasClosedCallBoundary(): Boolean =
            DescriptorVisibilities.isPrivate(visibility) || visibility == DescriptorVisibilities.LOCAL

        private fun IrCall.genericOwnerCallTargetOrNull(): GenericOwnerCallTarget? {
            val raw = symbol.owner
            localDefaultSourcesByDispatcher[raw]?.let { source ->
                val owner = source.parent as? IrClass ?: return null
                val family = context.genericOwnerArchitecturePlans[owner]?.memberFamilies?.get(source) ?: return null
                return GenericOwnerCallTarget(
                    callee = source,
                    owner = owner,
                    logicalBindingKey = context.preLoweringDeclarationKeys[source]
                        ?: source.dotNetLibraryAbiKeyOrNull("F"),
                    localFamily = family,
                    receiver = movedDispatchReceiverArgumentOrNull() ?: return null,
                )
            }
            context.externalDefaultArgumentDispatchers[raw]?.let { bound ->
                val receiver = movedDispatchReceiverArgumentOrNull() ?: return null
                val owner = ((receiver.type as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner ?: return null
                if (!owner.isDotNetGenericClassDeclaration) return null
                val logicalBindingKey = bound.library.declarations.entries.singleOrNull { entry ->
                    entry.value === bound.function
                }?.key ?: return null
                return GenericOwnerCallTarget(
                    callee = raw.defaultArgumentsOriginalFunction as? IrSimpleFunction ?: raw,
                    owner = owner,
                    logicalBindingKey = logicalBindingKey,
                    localFamily = null,
                    receiver = receiver,
                )
            }
            val receiver = dispatchReceiver ?: return null
            val localRawOwner = raw.parent as? IrClass
            val localRawFamily = localRawOwner
                ?.let { owner -> context.genericOwnerArchitecturePlans[owner]?.memberFamilies?.get(raw) }
            if (!raw.isFakeOverride && localRawOwner != null && localRawFamily != null) {
                return GenericOwnerCallTarget(
                    callee = raw,
                    owner = localRawOwner,
                    logicalBindingKey = context.preLoweringDeclarationKeys[raw]
                        ?: raw.dotNetLibraryAbiKeyOrNull("F"),
                    localFamily = localRawFamily,
                    receiver = receiver,
                )
            }
            val callee = if (raw.isFakeOverride) {
                raw.resolveFakeOverride() ?: raw.resolveFakeOverrideMaybeAbstract() ?: return null
            } else {
                raw
            }
            val owner = callee.parent as? IrClass ?: return null
            if (!owner.isDotNetGenericClassDeclaration) return null
            val localFamily = context.genericOwnerArchitecturePlans[owner]?.memberFamilies?.get(callee)
            val logicalBindingKey = context.preLoweringDeclarationKeys[callee]
                ?: callee.dotNetLibraryAbiKeyOrNull("F")
            if (localFamily == null && logicalBindingKey == null) return null
            return GenericOwnerCallTarget(callee, owner, logicalBindingKey, localFamily, receiver)
        }

        private fun IrCall.movedDispatchReceiverArgumentOrNull(): IrExpression? {
            val receiverParameter = symbol.owner.parameters.singleOrNull { parameter ->
                parameter.origin == IrDeclarationOrigin.MOVED_DISPATCH_RECEIVER
            } ?: return null
            return arguments.getOrNull(receiverParameter.indexInParameters)
        }

        private fun receiverProvenance(receiver: IrExpression): DotNetGenericOwnerCallReceiverProvenance {
            if (receiver.readsCapabilityDeclaration()) {
                // A stable local can retain the exact construction from which its value flowed,
                // but its declared variant/projected owner view is still the Kotlin call
                // authority. Recovering the concrete origin here would bypass a required
                // semantic dispatcher for ordinary covariance (for example C<Int> viewed as
                // an out-position C<Any?> with an @UnsafeVariance member input).
                return DotNetGenericOwnerCallReceiverProvenance.SEMANTIC_VIEW
            }
            val candidates = originsOf(receiver)
            if (candidates.isEmpty() || candidates.any { origin -> origin.kind == ReceiverOriginKind.UNRESOLVED }) {
                return DotNetGenericOwnerCallReceiverProvenance.UNRESOLVED
            }
            val exact = candidates.filter { origin -> origin.kind == ReceiverOriginKind.EXACT }
            if (exact.isEmpty() || exact.any { origin -> !checkNotNull(origin.exactType).hasPhysicalView(receiver.type) }) {
                return DotNetGenericOwnerCallReceiverProvenance.SEMANTIC_VIEW
            }
            return DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION
        }

        private fun IrExpression.readsCapabilityDeclaration(): Boolean = when (this) {
            is IrGetValue -> symbol.owner in context.genericOwnerCapabilityDeclarations
            is IrGetField -> symbol.owner in context.genericOwnerCapabilityDeclarations
            is IrTypeOperatorCall ->
                operator == IrTypeOperator.IMPLICIT_CAST && argument.readsCapabilityDeclaration()
            else -> false
        }

        private fun originsOfWrite(field: IrField, value: IrExpression?): Set<ReceiverOrigin> {
            val owner = field.parent as? IrClass
            if (owner != null && value is IrConst && value.value == null &&
                    field.type.isRepresentationNeutralNullableGenericOwnerReference(owner)
            ) {
                // Null is not a possible call receiver. Keep an only-null field originless so a
                // later read still fails closed, but do not merge an unresolved receiver into
                // the exact non-null constructions written through the same private field.
                return emptySet()
            }
            return originsOf(value)
        }

        private fun originsOf(expression: IrExpression?): Set<ReceiverOrigin> {
            if (expression == null) return unresolved()
            return when (expression) {
                is IrConstructorCall -> {
                    val owner = expression.symbol.owner.parent as? IrClass
                    if (owner?.hasRelevantGenericOwnerInAncestry() == true) {
                        val localPlan = context.genericOwnerArchitecturePlans[owner]
                        if (localPlan != null && !localPlan.isReifiedByGenericOwnerRehearsal) {
                            // The logical `C<T>` syntax survives in IR, but this allocation emits
                            // the canonical erased owner. It cannot seed an exact physical view.
                            unresolved()
                        } else {
                            setOf(exact(expression.type))
                        }
                    } else {
                        emptySet()
                    }
                }
                is IrGetValue -> origins[expression.symbol.owner].orEmpty()
                is IrGetField -> origins[expression.symbol.owner].orEmpty().ifEmpty { unresolved() }
                is IrTypeOperatorCall -> originsOf(expression.argument)
                is IrReturn -> originsOf(expression.value)
                is IrWhen -> expression.branches.flatMapTo(linkedSetOf()) { branch ->
                    originsOf(branch.result)
                }
                is IrContainerExpression -> originsOf(expression.statements.lastOrNull() as? IrExpression)
                is IrFunctionAccessExpression -> {
                    val target = expression.symbol.owner
                    if (target.returnType.isNothing()) {
                        // A bottom-typed call never contributes a value to this flow. Treating
                        // its context-coerced C<X> expression as unresolved would manufacture a
                        // semantic carrier for an exact override which can never return.
                        emptySet()
                    } else if (target in producerFunctions) {
                        origins[target].orEmpty().mapTo(linkedSetOf()) { origin ->
                            if (origin.kind == ReceiverOriginKind.EXACT &&
                                checkNotNull(origin.exactType).hasPhysicalView(target.returnType)
                            ) {
                                // The producer proof is written in its declaration parameters.
                                // Rebind that exact result to this call's already-substituted IR
                                // type (`Local<T_local>` -> `Local<T_caller>`, likewise for method
                                // parameters) before it flows into a local or a later receiver.
                                exact(expression.type)
                            } else {
                                origin
                            }
                        }
                    } else if (target is IrSimpleFunction &&
                        expression.type.hasRelevantGenericOwnerInAncestry() &&
                        externalDeclarations.hasNaturalGenericOwnerFunctionReturn(target)
                    ) {
                        // In the versioned producer ABI, an explicit carrier record replaces the
                        // natural result. A published function with no such replacement is exact
                        // evidence, so a consumer must not degrade its constructed C<T> result to
                        // the class capability merely because the producer body is unavailable.
                        setOf(exact(expression.type))
                    } else if (expression.type.hasRelevantGenericOwnerInAncestry()) {
                        unresolved()
                    } else {
                        emptySet()
                    }
                }
                else -> if (expression.type.hasRelevantGenericOwnerInAncestry()) unresolved() else emptySet()
            }
        }

        private fun IrType.hasRelevantGenericOwnerInAncestry(): Boolean {
            val owner = ((this as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner ?: return false
            return owner.hasRelevantGenericOwnerInAncestry()
        }

        private fun IrType.isStaticallyExactGenericOwnerView(): Boolean {
            val simple = this as? IrSimpleType ?: return false
            val owner = (simple.classifier as? IrClassSymbol)?.owner ?: return false
            if (!owner.hasRelevantGenericOwnerInAncestry()) return false
            // Before DotNetInnerClassTypeParametersLowering, FIR already spells
            // `Outer<A>.Inner<B>.Leaf<C>` as Leaf<C, B, A> while each declaration still owns
            // only its source parameters. Reconstruct that same own-to-outer parameter order
            // for the variance proof; deserialized declarations which already own the complete
            // captured suffix stop at their own list.
            val declarationParameters = buildList {
                var current: IrClass? = owner
                while (current != null && size < simple.arguments.size) {
                    addAll(current.typeParameters)
                    current = if (current.isInner) current.parent as? IrClass else null
                }
            }
            if (simple.arguments.size != declarationParameters.size) return false
            if (declarationParameters.any { parameter -> parameter.variance != Variance.INVARIANT }) return false
            return simple.arguments.all { argument ->
                val projection = argument as? IrTypeProjection ?: return@all false
                projection.variance == Variance.INVARIANT
            }
        }

        private fun IrClass.hasRelevantGenericOwnerInAncestry(visited: MutableSet<IrClass> = hashSetOf()): Boolean {
            if (!visited.add(this)) return false
            if (this in context.genericOwnerArchitecturePlans ||
                (isDotNetGenericClassDeclaration && dotNetLibraryAbiKeyOrNull("C") != null)
            ) {
                return true
            }
            return superTypes.any { superType ->
                val superClass = ((superType as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                superClass?.hasRelevantGenericOwnerInAncestry(visited) == true
            }
        }

        private fun IrType.hasPhysicalView(expected: IrType): Boolean {
            fun IrClass.hasNaturalPhysicalConstruction(): Boolean = when {
                dotNetImportedClrTypeAuthorityOrNull() != null -> true
                typeParameters.isEmpty() -> true
                isDotNetComparableClass() -> true
                DotNetRuntimeTypes.erasedGenericClassInfoFor(this) != null -> false
                kind == ClassKind.INTERFACE ->
                    externalDeclarations.hasReifiedGenericInterface(this) ||
                            context.configuration.dotNetGenericOwnerRehearsal &&
                            DotNetRuntimeTypes.usesDeclaredViewByDefaultInRehearsal(this)
                isDotNetGenericClassDeclaration ->
                    context.genericOwnerArchitecturePlans[this]
                        ?.isReifiedByGenericOwnerRehearsal == true ||
                            externalDeclarations.reifiedGenericOwnerLogicalKeyOrNull(this) != null
                else -> true
            }

            val pending = ArrayDeque<IrType>()
            val visited = hashSetOf<IrType>()
            pending += this
            while (pending.isNotEmpty()) {
                val candidate = pending.removeFirst()
                if (!visited.add(candidate)) continue
                // An exact construction already selected the backend's physical carrier for
                // this complete logical type. In particular, constructing
                // Box<InvariantProducer<out Any?>> closes Box<object>; the projection is not an
                // unresolved Box capability merely because the stricter invariant comparison
                // below deliberately refuses to equate projected interface views. Arbitrary
                // boundary values never reach this arm as exact origins: they are seeded as
                // unresolved and casts/widening preserve their producer provenance.
                val simple = candidate as? IrSimpleType ?: continue
                val classifier = (simple.classifier as? IrClassSymbol)?.owner ?: continue
                val hasNaturalConstruction = classifier.hasNaturalPhysicalConstruction()
                if (hasNaturalConstruction &&
                    (candidate == expected || candidate.sameInvariantTypeAs(expected))
                ) return true
                if (simple.arguments.size != classifier.typeParameters.size) continue
                val substitutions = classifier.typeParameters.zip(simple.arguments).mapNotNull { pair ->
                    val projection = pair.second as? IrTypeProjection ?: return@mapNotNull null
                    pair.first.symbol to projection.type
                }
                if (substitutions.size != classifier.typeParameters.size) continue
                if (!hasNaturalConstruction) {
                    // An erased interface emits only its canonical ancestry. An erased class can
                    // retain a fixed closed edge, but only when the target itself has a genuine
                    // natural TypeDef: a current-epoch Kotlin interface, a retained foreign CLR
                    // interface, a non-generic class, or an admitted generic class. Merely seeing
                    // a closed logical KLIB supertype is not physical evidence. In particular it
                    // must not resurrect a natural edge through another erased Kotlin interface.
                    val isLocalDemotedClass = classifier.kind != ClassKind.INTERFACE &&
                            classifier.fileOrNull != null &&
                            context.genericOwnerArchitecturePlans[classifier]
                                ?.isReifiedByGenericOwnerRehearsal == false
                    if (isLocalDemotedClass) {
                        classifier.superTypes
                            .filterNot { superType -> superType.referencesTypeParameterOf(classifier) }
                            .filter { superType ->
                                val target = ((superType as? IrSimpleType)?.classifier as? IrClassSymbol)
                                    ?.owner ?: return@filter false
                                target.hasNaturalPhysicalConstruction()
                            }
                            .forEach(pending::addLast)
                    }
                    continue
                }
                val substitutor = IrTypeSubstitutor(
                    substitutions.toMap(),
                    allowEmptySubstitution = true,
                )
                classifier.superTypes.mapTo(pending, substitutor::substitute)
            }
            return false
        }

        private fun exact(type: IrType): ReceiverOrigin = ReceiverOrigin(ReceiverOriginKind.EXACT, type)

        private fun unresolved(): Set<ReceiverOrigin> = setOf(ReceiverOrigin(ReceiverOriginKind.UNRESOLVED))

        private fun node(key: Any): MutableSet<ReceiverOrigin> = origins.getOrPut(key) { linkedSetOf() }

        private fun addOrigin(key: Any, origin: ReceiverOrigin): Boolean = node(key).add(origin)

        private fun addOrigins(key: Any, additions: Set<ReceiverOrigin>): Boolean = node(key).addAll(additions)
    }

    /**
     * Tests only views rooted in an already exact produced carrier. A direct owner binder or an
     * admitted local generic construction is authoritative. Traversal is then restricted to the
     * frozen direct edges of admitted local plans; an erased, unplanned, or foreign declaration
     * contributes no inferred edge. Final symbolic BaseType/InterfaceImpl closure remains the
     * later authority epoch.
     */
    private fun IrType.hasAdmittedExactPhysicalView(
        expected: IrType,
        genericOwner: IrClass,
    ): Boolean {
        val pending = ArrayDeque<IrType>()
        val visited = hashSetOf<IrType>()
        pending += this
        while (pending.isNotEmpty()) {
            val candidate = pending.removeFirst()
            if (!visited.add(candidate)) continue
            val simple = candidate as? IrSimpleType ?: continue
            if (candidate.sameInvariantTypeAs(expected)) {
                val parameter = simple.classifier as? IrTypeParameterSymbol
                if (parameter != null &&
                    parameter in candidate.dotNetGenericOwnerParameterDependencies(genericOwner)
                ) return true
                val classifier = (simple.classifier as? IrClassSymbol)?.owner
                if (candidate.dotNetGenericOwnerParameterDependencies(genericOwner).isEmpty() ||
                    classifier?.hasExactGenericOwnerTypeDefAuthority() == true
                ) return true
            }
            val classifier = (simple.classifier as? IrClassSymbol)?.owner ?: continue
            if (simple.arguments.size != classifier.typeParameters.size) continue
            val substitutions = classifier.typeParameters.zip(simple.arguments)
                .mapNotNull { pair ->
                    val projection = pair.second as? IrTypeProjection ?: return@mapNotNull null
                    if (projection.variance != Variance.INVARIANT) return@mapNotNull null
                    pair.first.symbol to projection.type
                }
            if (substitutions.size != classifier.typeParameters.size) continue
            val plan = context.genericOwnerArchitecturePlans[classifier]
                ?.takeIf(DotNetGenericOwnerArchitecturePlan::isReifiedByGenericOwnerRehearsal)
                ?: continue
            val substitutor = IrTypeSubstitutor(
                substitutions.toMap(),
                allowEmptySubstitution = true,
            )
            plan.logicalDirectSupertypes.forEach { superType ->
                val substituted = substitutor.substitute(superType)
                val superClass = ((substituted as? IrSimpleType)?.classifier as? IrClassSymbol)
                    ?.owner ?: return@forEach
                if (substituted.dotNetGenericOwnerParameterDependencies(genericOwner).isNotEmpty() &&
                    superClass.typeParameters.isNotEmpty() &&
                    !superClass.hasExactGenericOwnerDirectEdgeAuthority()
                ) return@forEach
                pending += substituted
            }
        }
        return false
    }

    /**
     * Existing external ABI is physical truth; a local declaration instead has only this
     * compilation's admitted representation plan at this early epoch.
     */
    private fun IrClass.hasExactGenericOwnerTypeDefAuthority(): Boolean =
        context.genericOwnerArchitecturePlans[this]
            ?.isReifiedByGenericOwnerRehearsal == true ||
                context.earlyGenericInterfaceCompleteNaturalAuthorityPlans[symbol] != null ||
                DotNetRuntimeTypes.usesDeclaredViewByDefaultInRehearsal(this) ||
                externalDeclarations.reifiedGenericOwnerLogicalKeyOrNull(this) != null

    /**
     * A local class plan may promise one later-selected local edge. An external interface edge is
     * usable only when its producer recorded the full-arity natural TypeDef; an erased KLIB stub
     * or a foreign logical supertype cannot manufacture that construction. External class-base
     * traversal remains out of this first edge grammar.
     */
    private fun IrClass.hasExactGenericOwnerDirectEdgeAuthority(): Boolean =
        context.genericOwnerArchitecturePlans[this]
            ?.isReifiedByGenericOwnerRehearsal == true ||
                (kind == ClassKind.INTERFACE &&
                        (context.earlyGenericInterfaceCompleteNaturalAuthorityPlans[symbol] != null ||
                                DotNetRuntimeTypes.usesDeclaredViewByDefaultInRehearsal(this) ||
                                externalDeclarations.hasReifiedGenericInterface(this)))

    /**
     * Intra-compilation physical carrier coordinate. Local TypeDefs use their IR identity rather
     * than a serialization key: executables and compiler-generated/private owners deliberately
     * have no published KLIB key, but can still have an admitted local CLR TypeDef.
     */
    private sealed interface TypedWriteCarrierCoordinate {
        data class Leaf(
            val kind: org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPrototypeTypeKind,
        ) : TypedWriteCarrierCoordinate

        data class OwnerParameter(val index: Int) : TypedWriteCarrierCoordinate

        data class LocalConstruction(
            val classifier: IrClassSymbol,
            val category: org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalNamedTypeCategory,
            val arguments: List<TypedWriteCarrierCoordinate>,
        ) : TypedWriteCarrierCoordinate

        data class SzArray(val element: TypedWriteCarrierCoordinate) : TypedWriteCarrierCoordinate

        val isNamedReference: Boolean
            get() = this is LocalConstruction
    }

    /** A carrier coordinate attached only after a physical entry or producer has proved it. */
    private sealed interface TypedWriteValueFact {
        data class Exact(
            val carrier: TypedWriteCarrierCoordinate,
            /** Exact producer root used only to query independently admitted physical views. */
            val provenRootType: IrType,
        ) : TypedWriteValueFact

        data object SemanticObject : TypedWriteValueFact
        data object Unresolved : TypedWriteValueFact
    }

    /**
     * Traces the physical domain of field-write values through parameters, calls, local aliases,
     * assignments, returns, and casts. This is deliberately context-insensitive and fail-closed:
     * merging any genuinely broad producer keeps the write semantic, while an unsupported or
     * source-free path remains unresolved.
     *
     * Exact evidence carries the complete path-unbound verifier coordinate (`!0`, `!1`,
     * `Box<!0>`, ...), rather than one undifferentiated "typed" bit. A cast may therefore retain
     * an already-proven matching coordinate, but can never create exactness from its target
     * spelling or turn one exact coordinate into another. Its producer root is retained only so
     * independent BaseType/InterfaceImpl authority can prove an identity-preserving destination
     * view. The coordinate is provenance, not the current stack/storage carrier: `!0 -> object ->
     * as !0` retains `Exact(!0)` through the object-shaped helper parameter without pretending
     * that parameter itself is typed.
     */
    private inner class TypedWriteValueProvenanceAnalyzer(
        private val owner: IrClass,
        private val members: List<IrSimpleFunction>,
        private val memberPolicies: Map<IrSimpleFunction, DotNetGenericOwnerMemberPolicy>,
        private val producerAccesses: Map<IrFunction, DirectMemberAccesses>,
        private val initializerAccesses: Map<ProducerInitializer, DirectMemberAccesses>,
        private val additionalSemanticBoundaryParameters: Set<IrValueDeclaration> = emptySet(),
    ) {
        private val provenances = linkedMapOf<Any, MutableSet<TypedWriteValueFact>>()

        fun analyze(
            fields: Set<IrField>,
        ): Map<IrField, List<DotNetGenericOwnerStateWriteProvenancePlan>> {
            val activeSlice = activeProducerSlice(fields)
            val activeFunctions = activeSlice.first
            val activeInitializers = activeSlice.second
            seedCallableBoundaries(activeFunctions)
            activeFunctions.forEach { function ->
                val access = producerAccesses.getValue(function)
                if (function.body == null) {
                    addProvenance(function, defaultFact(function.returnType))
                }
                access.valueDefinitions.keys.forEach { declaration -> node(declaration) }
                node(function)
            }

            val allAccesses = activeFunctions.map { function -> producerAccesses.getValue(function) } +
                    activeInitializers.map { initializer -> initializerAccesses.getValue(initializer) }
            var changed: Boolean
            do {
                changed = false
                activeFunctions.forEach { function ->
                    val access = producerAccesses.getValue(function)
                    access.valueDefinitions.forEach { entry ->
                        val declaration = entry.key
                        val definitions = entry.value
                        changed = addProvenances(declaration, definitions.flatMapTo(linkedSetOf(), ::provenanceOf)) || changed
                    }
                    changed = addProvenances(function, access.returns.flatMapTo(linkedSetOf(), ::provenanceOf)) || changed
                }
                allAccesses.forEach { access ->
                    access.callSites.forEach { call ->
                        val target = call.symbol.owner
                        target.parameters.forEach { parameter ->
                            if (parameter.kind == IrParameterKind.DispatchReceiver) return@forEach
                            val argument = call.arguments.getOrNull(parameter.indexInParameters)
                            val argumentProvenance = if (argument == null) {
                                setOf(TypedWriteValueFact.Unresolved)
                            } else {
                                provenanceOf(argument)
                            }
                            changed = addProvenances(parameter, argumentProvenance) || changed
                        }
                    }
                }
            } while (changed)

            return fields.associateWithTo(linkedMapOf()) { field ->
                buildList {
                    producerAccesses.forEach { entry ->
                        val function = entry.key
                        val access = entry.value
                        val values = access.writeValues[field]
                            ?: if (field in access.writes) listOf(null) else emptyList()
                        if (values.isNotEmpty()) {
                            add(DotNetGenericOwnerStateWriteProvenancePlan(
                                producerName = function.name.asString(),
                                provenance = select(field, values.flatMapTo(linkedSetOf()) { value ->
                                    writeProvenance(field, value).ifEmpty {
                                        setOf(TypedWriteValueFact.Unresolved)
                                    }
                                }),
                            ))
                        }
                    }
                    initializerAccesses.forEach { entry ->
                        val initializer = entry.key
                        val access = entry.value
                        val values = access.writeValues[field]
                            ?: if (field in access.writes) listOf(null) else emptyList()
                        if (values.isNotEmpty()) {
                            add(DotNetGenericOwnerStateWriteProvenancePlan(
                                producerName = initializer.label,
                                provenance = select(field, values.flatMapTo(linkedSetOf()) { value ->
                                    writeProvenance(field, value).ifEmpty {
                                        setOf(TypedWriteValueFact.Unresolved)
                                    }
                                }),
                            ))
                        }
                    }
                }
            }
        }

        private fun activeProducerSlice(
            fields: Set<IrField>,
        ): Pair<Set<IrFunction>, Set<ProducerInitializer>> {
            val functions = producerAccesses
                .filterTo(linkedMapOf()) { entry ->
                    val function = entry.key
                    val access = entry.value
                    function in members ||
                            (function is IrConstructor && function.parent == owner) ||
                            access.writes.any { field -> field in fields }
                }
                .keys
                .toMutableSet()
            val initializers = initializerAccesses
                .filterTo(linkedMapOf()) { entry ->
                    val access = entry.value
                    access.writes.any { field -> field in fields }
                }
                .keys
                .toMutableSet()
            var changed: Boolean
            do {
                changed = false
                val reachableCalls = buildSet {
                    functions.forEach { function -> addAll(producerAccesses.getValue(function).calls) }
                    initializers.forEach { initializer -> addAll(initializerAccesses.getValue(initializer).calls) }
                }
                if (functions.addAll(reachableCalls)) changed = true
                initializerAccesses.forEach { entry ->
                    val initializer = entry.key
                    val access = entry.value
                    if (initializer !in initializers && access.calls.any { function -> function in functions }) {
                        initializers += initializer
                        changed = true
                    }
                }
            } while (changed)
            return functions to initializers
        }

        private fun seedCallableBoundaries(activeFunctions: Set<IrFunction>) {
            activeFunctions.forEach { function ->
                val isOwnerConstructor = function is IrConstructor && function.parent == owner
                val member = (function as? IrSimpleFunction)?.takeIf { candidate -> candidate in members }
                function.parameters
                    .filter { parameter ->
                        parameter.kind == IrParameterKind.DispatchReceiver && function.parent == owner
                    }
                    .forEach { parameter ->
                        // A semantic body may widen its explicit values, but it still executes on
                        // one actual C<!K,...>. The current receiver is therefore exact entry
                        // evidence for receiver-derived calls; no logical argument type is used to
                        // synthesize that construction.
                        addProvenance(
                            parameter,
                            exactFact(parameter.type),
                        )
                    }
                function.parameters
                    .filter { parameter ->
                        parameter.kind != IrParameterKind.DispatchReceiver &&
                                parameter in additionalSemanticBoundaryParameters
                    }
                    .forEach { parameter ->
                        // A private source normally has no independent entry fact: its values are
                        // derived exclusively from the compiler-visible call graph below. A
                        // materialized private semantic hook is the exception. Its selected
                        // object-domain parameter is itself a physical boundary even though no
                        // user can call that hook directly.
                        addProvenance(
                            parameter,
                            TypedWriteValueFact.SemanticObject,
                        )
                    }
                if (DescriptorVisibilities.isPrivate(function.visibility) && !isOwnerConstructor) return@forEach
                function.parameters.forEach { parameter ->
                    if (parameter.kind == IrParameterKind.DispatchReceiver) return@forEach
                    val isTypedOwnerInput = parameter !in additionalSemanticBoundaryParameters &&
                            parameter.type.referencesGenericOwnerParameter(owner) && when {
                        isOwnerConstructor ->
                            !parameter.type.containsVariantInterfaceSemanticHazardOf(owner)
                        member != null ->
                            parameter.type.isLegalAtOwnerVariance(owner, TypePolarity.IN) &&
                                    !parameter.type.containsVariantInterfaceSemanticHazardOf(owner)
                        else -> false
                    }
                    addProvenance(
                        parameter,
                        if (isTypedOwnerInput) {
                            exactFact(parameter.type)
                        } else {
                            TypedWriteValueFact.SemanticObject
                        },
                    )
                }
            }
            check(memberPolicies.keys == members.toSet()) {
                "Internal .NET backend error: typed-write provenance lacks owner member policies"
            }
        }

        private fun provenanceOf(expression: IrExpression?): Set<TypedWriteValueFact> {
            if (expression == null) return setOf(TypedWriteValueFact.Unresolved)
            return when (expression) {
                is IrConstructorCall -> constructorProvenanceOf(expression)
                is IrGetValue -> provenances[expression.symbol.owner].orEmpty()
                is IrTypeOperatorCall -> {
                    val input = provenanceOf(expression.argument)
                    val preservesInputValue = expression.operator in setOf(
                        IrTypeOperator.CAST,
                        IrTypeOperator.IMPLICIT_CAST,
                        IrTypeOperator.IMPLICIT_NOTNULL,
                    )
                    val preservesVerifierCarrier = carrierCoordinate(expression.type)?.let { result ->
                        carrierCoordinate(expression.argument.type) == result
                    } == true
                    if (expression.operator == IrTypeOperator.CAST &&
                        expression.typeOperand.referencesGenericOwnerParameter(owner)
                    ) {
                        input.mapTo(linkedSetOf()) { fact ->
                            when (fact) {
                                is TypedWriteValueFact.Exact ->
                                    if (fact.canFlowIdentityPreservingTo(expression.typeOperand)) fact
                                    else TypedWriteValueFact.Unresolved
                                TypedWriteValueFact.SemanticObject -> TypedWriteValueFact.SemanticObject
                                TypedWriteValueFact.Unresolved -> TypedWriteValueFact.Unresolved
                            }
                        }
                    } else if (!preservesInputValue) {
                        // `is`, `!is`, numeric coercion, Unit coercion, SAM conversion, and a
                        // safe cast produce a new value (or may replace the input with null).
                        // Their operand provenance therefore cannot survive merely because the
                        // result is later unchecked-cast back to one owner parameter.
                        setOf(defaultFact(expression.type))
                    } else if (!preservesVerifierCarrier &&
                        expression.type.referencesGenericOwnerParameter(owner) &&
                        !expression.type.sameInvariantTypeAs(expression.argument.type)
                    ) {
                        input.mapTo(linkedSetOf()) { fact ->
                            when (fact) {
                                TypedWriteValueFact.SemanticObject -> fact
                                is TypedWriteValueFact.Exact ->
                                    if (fact.canFlowIdentityPreservingTo(expression.type)) fact
                                    else TypedWriteValueFact.Unresolved
                                TypedWriteValueFact.Unresolved,
                                    -> TypedWriteValueFact.Unresolved
                            }
                        }
                    } else {
                        input
                    }
                }
                is IrReturn -> provenanceOf(expression.value)
                is IrWhen -> expression.branches.flatMapTo(linkedSetOf()) { branch ->
                    provenanceOf(branch.result)
                }
                is IrReturnableBlock ->
                    // Returnable blocks should have been eliminated before this analysis. Do not
                    // let a future phase-order change certify only the lexical fall-through while
                    // silently ignoring an earlier return to the block.
                    setOf(TypedWriteValueFact.Unresolved)
                is IrContainerExpression -> {
                    val result = expression.statements.lastOrNull() as? IrExpression
                    provenanceOf(result)
                }
                is IrFunctionAccessExpression -> {
                    val target = expression.symbol.owner
                    val receiverProvenance = target.parameters.asSequence()
                        .filter { parameter ->
                            parameter.kind == IrParameterKind.DispatchReceiver ||
                                    parameter.kind == IrParameterKind.ExtensionReceiver
                        }
                        .mapNotNull { parameter ->
                            expression.arguments.getOrNull(parameter.indexInParameters)
                        }
                        .flatMap { receiver -> provenanceOf(receiver).asSequence() }
                        .toSet()
                    if (expression.isPhysicallyTypedOwnerClassifierArrayAllocation(owner)) {
                        setOf(exactFact(expression.type))
                    } else if (expression.type.referencesGenericOwnerParameter(owner) &&
                        TypedWriteValueFact.SemanticObject in receiverProvenance
                    ) {
                        // An owner-dependent result produced through an object-domain receiver
                        // remains in that domain. This derives chains such as
                        // broad Collection<T> -> iterator() -> next() without naming either
                        // interface. Declaration-independent ordinary arguments are deliberately
                        // ignored: an index or predicate must not contaminate an exact receiver's
                        // result.
                        setOf(TypedWriteValueFact.SemanticObject)
                    } else if (expression.type.referencesGenericOwnerParameter(owner) &&
                        receiverProvenance.isNotEmpty() &&
                        (TypedWriteValueFact.Unresolved in receiverProvenance ||
                                receiverProvenance.none { fact -> fact is TypedWriteValueFact.Exact })
                    ) {
                        // Result provenance is conditional on the actual receiver construction.
                        // An unavailable or mixed receiver cannot borrow a callee's aggregate
                        // typed-return fact merely because its logical result mentions T.
                        setOf(TypedWriteValueFact.Unresolved)
                    } else if (target in producerAccesses) {
                        provenances[target].orEmpty()
                    } else {
                        setOf(defaultFact(expression.type))
                    }
                }
                is IrGetField -> setOf(defaultFact(expression.type))
                else -> setOf(defaultFact(expression.type))
            }
        }

        private fun constructorProvenanceOf(
            expression: IrConstructorCall,
        ): Set<TypedWriteValueFact> {
            val use = expression.dotNetExactGenericOwnerConstructorUseOrNull(owner)
                ?: return setOf(defaultFact(expression.type))
            val plan = context.genericOwnerArchitecturePlans[use.constructedClass]
                ?: return setOf(TypedWriteValueFact.Unresolved)
            val constructorPlan = plan.constructors.singleOrNull { constructor ->
                constructor.source === expression.symbol.owner
            }
            if (!plan.isReifiedByGenericOwnerRehearsal || constructorPlan == null ||
                constructorPlan.parameterSlotDomains.size != expression.symbol.owner.parameters.size
            ) {
                return setOf(TypedWriteValueFact.Unresolved)
            }
            for (determiningUse in use.determiningUses) {
                if (determiningUse.parameterIndex in constructorPlan.semanticObjectParameterIndices) {
                    // This argument populates object-domain state but does not change the exact
                    // TypeSpec of the allocated object. The constructor MethodDef itself owns the
                    // conversion boundary, so broad capture provenance must not contaminate the
                    // separate exact construction result.
                    continue
                }
                if (constructorPlan.parameterSlotDomains.getOrNull(determiningUse.parameterIndex) !=
                    DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_INPUT
                ) {
                    return setOf(TypedWriteValueFact.Unresolved)
                }
                val argument = expression.arguments.getOrNull(determiningUse.parameterIndex)
                    ?: return setOf(TypedWriteValueFact.Unresolved)
                if (argument.type.dotNetGenericOwnerParameterDependencies(owner) !=
                    determiningUse.requiredOwnerParameters
                ) {
                    return setOf(TypedWriteValueFact.Unresolved)
                }
                val input = provenanceOf(argument)
                if (input.isEmpty() || input.any { fact ->
                        fact !is TypedWriteValueFact.Exact ||
                                !fact.canFlowIdentityPreservingTo(determiningUse.substitutedParameterType)
                    }
                ) {
                    return setOf(TypedWriteValueFact.Unresolved)
                }
            }
            return setOf(exactFact(use.constructedType))
        }

        /**
         * `arrayOfNulls<Node<T>>()` is a typed producer when Node is a local Kotlin generic
         * classifier. The current erased owner emits Node[] and a future admitted CLR-generic
         * owner emits Node<T>[]; neither route passes through object-domain element storage.
         * Direct `arrayOfNulls<T>()`/`arrayOfNulls<T?>()` deliberately fails this test.
         */
        private fun defaultFact(type: IrType): TypedWriteValueFact =
            if (type.referencesGenericOwnerParameter(owner)) {
                TypedWriteValueFact.Unresolved
            } else {
                TypedWriteValueFact.SemanticObject
            }

        /**
         * A null literal has no useful expression carrier (`Nothing?`), but it is the CLR zero
         * value of a proven local generic class reference such as `Node<T>?`. Treating that
         * initializer as object-domain input needlessly poisons an otherwise closed private
         * producer graph. This deliberately excludes a bare `T?`: an unconstrained owner
         * parameter can close over a CLR value type and therefore has no single nullable `!T`
         * field representation.
         */
        private fun writeProvenance(
            field: IrField,
            value: IrExpression?,
        ): Set<TypedWriteValueFact> =
            if (value is IrConst && value.value == null &&
                    field.type.isRepresentationNeutralNullableGenericOwnerReference(owner)
            ) {
                setOf(exactFact(field.type))
            } else {
                provenanceOf(value)
            }

        private fun carrierCoordinate(type: IrType): TypedWriteCarrierCoordinate? {
            type.genericOwnerDeclarationIndependentLeafPrototypeOrNull()?.let { leaf ->
                return TypedWriteCarrierCoordinate.Leaf(leaf.kind)
            }
            val simple = type as? IrSimpleType ?: return null
            val parameter = (simple.classifier as? IrTypeParameterSymbol)?.owner
            if (parameter != null) {
                if (simple.isMarkedNullable() || simple.arguments.isNotEmpty()) return null
                return owner.typeParameters.indexOf(parameter)
                    .takeIf { index -> index >= 0 }
                    ?.let(TypedWriteCarrierCoordinate::OwnerParameter)
            }
            val classifier = (simple.classifier as? IrClassSymbol)?.owner ?: return null
            if (classifier.fqNameWhenAvailable?.asString() == "kotlin.Array") {
                val projection = simple.arguments.singleOrNull() as? IrTypeProjection ?: return null
                if (projection.variance != Variance.INVARIANT) return null
                val elementSimple = projection.type as? IrSimpleType
                val elementParameter = (elementSimple?.classifier as? IrTypeParameterSymbol)?.owner
                if (elementSimple?.isMarkedNullable() == true && elementParameter in owner.typeParameters) {
                    return null
                }
                return carrierCoordinate(projection.type)?.let(TypedWriteCarrierCoordinate::SzArray)
            }
            if (classifier.isValue || classifier.typeParameters.isEmpty() ||
                simple.arguments.size != classifier.typeParameters.size ||
                (!classifier.isDotNetGenericClassDeclaration && classifier.kind != ClassKind.INTERFACE) ||
                !classifier.hasExactGenericOwnerTypeDefAuthority()
            ) return null
            val arguments = mutableListOf<TypedWriteCarrierCoordinate>()
            for (argument in simple.arguments) {
                val projection = argument as? IrTypeProjection ?: return null
                if (projection.variance != Variance.INVARIANT) return null
                arguments += carrierCoordinate(projection.type) ?: return null
            }
            return TypedWriteCarrierCoordinate.LocalConstruction(
                classifier.symbol,
                if (classifier.kind == ClassKind.INTERFACE) {
                    org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalNamedTypeCategory.INTERFACE
                } else {
                    org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalNamedTypeCategory.CLASS
                },
                arguments,
            )
        }

        /**
         * Preserves an exact fact only across a verifier-valid identity transfer. Equality is the
         * ordinary case. A different named reference carrier is admitted only when the fact's
         * already-proven root reaches that destination through the same frozen physical-view
         * authority used by semantic-body analysis. The logical destination never creates a view.
         */
        private fun TypedWriteValueFact.Exact.canFlowIdentityPreservingTo(
            expectedType: IrType,
        ): Boolean {
            val expectedCarrier = carrierCoordinate(expectedType) ?: return false
            if (carrier == expectedCarrier) return true
            if (!carrier.isNamedReference || !expectedCarrier.isNamedReference ||
                !provenRootType.isNonValueNamedReferenceType() ||
                !expectedType.isNonValueNamedReferenceType()
            ) {
                return false
            }
            return provenRootType.makeNotNull().hasAdmittedExactPhysicalView(
                expectedType.makeNotNull(),
                owner,
            )
        }

        private fun IrType.isNonValueNamedReferenceType(): Boolean {
            val classifier = ((this as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                ?: return false
            return !classifier.isValue
        }

        private fun exactFact(type: IrType): TypedWriteValueFact =
            carrierCoordinate(type)?.let { carrier ->
                TypedWriteValueFact.Exact(carrier, type)
            }
                ?: TypedWriteValueFact.Unresolved

        private fun node(key: Any): MutableSet<TypedWriteValueFact> =
            provenances.getOrPut(key) { linkedSetOf() }

        private fun addProvenance(
            key: Any,
            provenance: TypedWriteValueFact,
        ): Boolean = node(key).add(provenance)

        private fun addProvenances(
            key: Any,
            additions: Set<TypedWriteValueFact>,
        ): Boolean = node(key).addAll(additions)

        private fun select(
            field: IrField,
            candidates: Set<TypedWriteValueFact>,
        ): DotNetGenericOwnerWriteValueProvenance = when {
            TypedWriteValueFact.SemanticObject in candidates ->
                DotNetGenericOwnerWriteValueProvenance.SEMANTIC_OBJECT
            TypedWriteValueFact.Unresolved in candidates ->
                DotNetGenericOwnerWriteValueProvenance.UNRESOLVED
            candidates.isNotEmpty() && candidates.all { fact ->
                fact is TypedWriteValueFact.Exact && fact.canFlowIdentityPreservingTo(field.type)
            } -> {
                consumeEarlyNaturalInterfaceAuthorities(field.type)
                DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED
            }
            else -> DotNetGenericOwnerWriteValueProvenance.UNRESOLVED
        }

        /** Records only early plans which actually contribute to selected physical state. */
        private fun consumeEarlyNaturalInterfaceAuthorities(type: IrType) {
            val pendingTypes = ArrayDeque<IrType>()
            val pendingOwners = ArrayDeque<IrClassSymbol>()
            val visitedOwners = hashSetOf<IrClassSymbol>()
            pendingTypes += type
            while (pendingTypes.isNotEmpty()) {
                val simple = pendingTypes.removeFirst() as? IrSimpleType ?: continue
                (simple.classifier as? IrClassSymbol)?.let(pendingOwners::addLast)
                simple.arguments.forEach { argument ->
                    (argument as? IrTypeProjection)?.type?.let(pendingTypes::addLast)
                }
            }
            while (pendingOwners.isNotEmpty()) {
                val symbol = pendingOwners.removeFirst()
                if (!visitedOwners.add(symbol)) continue
                val plan = context.earlyGenericInterfaceCompleteNaturalAuthorityPlans[symbol]
                    ?: continue
                context.consumedEarlyGenericInterfaceNaturalAuthorityPlans += symbol
                plan.surfaceInput.positions.forEach { position ->
                    position.type.localNaturalInterfaceDependencies().forEach(pendingOwners::addLast)
                }
            }
        }

        private fun DotNetGenericInterfaceCompleteSurfaceTypeReference.localNaturalInterfaceDependencies():
                Set<IrClassSymbol> = when (this) {
            DotNetGenericInterfaceCompleteSurfaceTypeReference.Independent,
            is DotNetGenericInterfaceCompleteSurfaceTypeReference.OwnerParameter,
                -> emptySet()
            is DotNetGenericInterfaceCompleteSurfaceTypeReference.Constructed -> buildSet {
                (definition as? DotNetGenericOwnerPhysicalTypeDefIdentity.Local)
                    ?.owner
                    ?.let(::add)
                arguments.forEach { argument ->
                    addAll(argument.localNaturalInterfaceDependencies())
                }
            }
            is DotNetGenericInterfaceCompleteSurfaceTypeReference.SzArray ->
                element.localNaturalInterfaceDependencies()
        }
    }

    private data class DirectMemberAccesses(
        val calls: Set<IrFunction>,
        val callSites: List<IrFunctionAccessExpression>,
        val genericOwnerCallSites: List<IrCall>,
        val reads: Set<IrField>,
        val writes: Set<IrField>,
        val writeValues: Map<IrField, List<IrExpression?>>,
        val valueDefinitions: Map<IrValueDeclaration, List<IrExpression>>,
        val returns: List<IrExpression>,
    ) {
        fun restrictTo(fields: Set<IrField>): DirectMemberAccesses = copy(
            reads = reads.filterTo(linkedSetOf()) { field -> field in fields },
            writes = writes.filterTo(linkedSetOf()) { field -> field in fields },
            writeValues = writeValues.filterKeys { field -> field in fields },
        )

        fun withImplicitWrite(initializer: ProducerInitializer): DirectMemberAccesses {
            val field = initializer.implicitWrite ?: return this
            val expression = (initializer.element as? IrExpressionBody)?.expression
            return copy(
                writes = writes + field,
                writeValues = writeValues + (field to (writeValues[field].orEmpty() + expression)),
            )
        }
    }

    private data class ProducerInitializer(
        val label: String,
        val element: IrElement,
        val owner: IrClass? = null,
        val implicitWrite: IrField? = null,
    )

    private fun ProducerInitializer.stateInitializerPlanOrNull(
        field: IrField,
        owner: IrClass,
    ): DotNetGenericOwnerStateInitializerPlan? {
        if (implicitWrite != field) return null
        val expression = (element as? IrExpressionBody)?.expression
        val allocation = expression as? IrFunctionAccessExpression
        val fixedElementCount = allocation
            ?.takeIf { candidate -> candidate.isPhysicallyTypedOwnerClassifierArrayAllocation(owner) }
            ?.arguments
            ?.singleOrNull()
            ?.let { argument -> (argument as? IrConst)?.value as? Int }
        val isDefaultNullReference = expression is IrConst && expression.value == null
        val isDefaultZeroValue = expression is IrConst && when {
            field.type.isBoolean() -> expression.value == false
            field.type.isInt() -> expression.value == 0
            else -> false
        }
        val constructorParameter = (expression as? IrGetValue)?.symbol?.owner?.let { parameter ->
            owner.declarations.filterIsInstance<IrConstructor>().mapNotNull { constructor ->
                constructor.parameters.indexOf(parameter).takeIf { index -> index >= 0 }?.let { index ->
                    constructor to index
                }
            }.singleOrNull()
        }
        val constructorLogicalBindingKey = constructorParameter?.first?.let { constructor ->
            context.preLoweringDeclarationKeys[constructor] ?: constructor.dotNetLibraryAbiKeyOrNull("F")
        }
        val constructorParameterIndex = constructorParameter?.second?.takeIf {
            constructorLogicalBindingKey != null
        }
        return DotNetGenericOwnerStateInitializerPlan(
            producerName = label,
            kind = when {
                fixedElementCount != null ->
                    DotNetGenericOwnerPrototypeStateInitializerKind.FIXED_ZEROED_SZ_ARRAY
                isDefaultZeroValue ->
                    DotNetGenericOwnerPrototypeStateInitializerKind.DEFAULT_ZERO_VALUE
                isDefaultNullReference ->
                    DotNetGenericOwnerPrototypeStateInitializerKind.DEFAULT_NULL_REFERENCE
                constructorParameterIndex != null ->
                    DotNetGenericOwnerPrototypeStateInitializerKind.POSITIONAL_CONSTRUCTOR_PARAMETER
                else -> DotNetGenericOwnerPrototypeStateInitializerKind.UNSUPPORTED
            },
            fixedElementCount = fixedElementCount,
            constructorLogicalBindingKey = constructorLogicalBindingKey.takeIf {
                constructorParameterIndex != null
            },
            constructorParameterIndex = constructorParameterIndex,
        )
    }

    private fun IrFunctionAccessExpression.isPhysicallyTypedOwnerClassifierArrayAllocation(
        owner: IrClass,
    ): Boolean {
        if (symbol != context.irBuiltIns.arrayOfNulls) return false
        val arrayType = type as? IrSimpleType ?: return false
        if (arrayType.classifier != context.irBuiltIns.arrayClass) return false
        val elementProjection = arrayType.arguments.singleOrNull() as? IrTypeProjection ?: return false
        if (elementProjection.variance != Variance.INVARIANT ||
            !elementProjection.type.referencesGenericOwnerParameter(owner)
        ) {
            return false
        }
        val elementClass = ((elementProjection.type as? IrSimpleType)?.classifier as? IrClassSymbol)
            ?.owner
            ?: return false
        if (!elementClass.isDotNetGenericClassDeclaration || elementClass.kind == ClassKind.INTERFACE) return false
        return externalDeclarations.reifiedGenericOwnerLogicalKeyOrNull(elementClass) != null ||
                context.genericOwnerArchitecturePlans[elementClass]
                    ?.isReifiedByGenericOwnerRehearsal == true
    }

    private fun IrElement.collectDirectAccesses(
        producerFunctions: Set<IrFunction>,
        returnTarget: IrFunction? = null,
    ): DirectMemberAccesses {
        val calls = linkedSetOf<IrFunction>()
        val callSites = mutableListOf<IrFunctionAccessExpression>()
        val genericOwnerCallSites = mutableListOf<IrCall>()
        val reads = linkedSetOf<IrField>()
        val writes = linkedSetOf<IrField>()
        val writeValues = linkedMapOf<IrField, MutableList<IrExpression?>>()
        val valueDefinitions = linkedMapOf<IrValueDeclaration, MutableList<IrExpression>>()
        val returns = mutableListOf<IrExpression>()
        acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                // A nested declaration is an independently indexed producer body, not an effect
                // of declaring the class while this body executes.
            }

            override fun visitFunction(declaration: IrFunction) {
                // Local functions are independently indexed and become reachable only by a call.
            }

            override fun visitFunctionAccess(expression: IrFunctionAccessExpression) {
                if (expression is IrCall) genericOwnerCallSites += expression
                val target = expression.symbol.owner
                if (target in producerFunctions) {
                    calls += target
                    callSites += expression
                }
                expression.acceptChildrenVoid(this)
            }

            override fun visitVariable(declaration: IrVariable) {
                declaration.initializer?.let { initializer ->
                    valueDefinitions.getOrPut(declaration) { mutableListOf() } += initializer
                }
                declaration.acceptChildrenVoid(this)
            }

            override fun visitSetValue(expression: IrSetValue) {
                valueDefinitions.getOrPut(expression.symbol.owner) { mutableListOf() } += expression.value
                expression.acceptChildrenVoid(this)
            }

            override fun visitReturn(expression: IrReturn) {
                if (expression.returnTargetSymbol.owner == returnTarget) returns += expression.value
                expression.acceptChildrenVoid(this)
            }

            override fun visitGetField(expression: IrGetField) {
                val field = expression.symbol.owner
                reads += field
                expression.acceptChildrenVoid(this)
            }

            override fun visitSetField(expression: IrSetField) {
                val field = expression.symbol.owner
                writes += field
                writeValues.getOrPut(field) { mutableListOf() } += expression.value
                expression.acceptChildrenVoid(this)
            }
        })
        return DirectMemberAccesses(
            calls = calls,
            callSites = callSites,
            genericOwnerCallSites = genericOwnerCallSites,
            reads = reads,
            writes = writes,
            writeValues = writeValues,
            valueDefinitions = valueDefinitions,
            returns = returns,
        )
    }

    private fun IrFunction.collectDirectAccesses(
        producerFunctions: Set<IrFunction>,
    ): DirectMemberAccesses = body?.collectDirectAccesses(producerFunctions, this)?.let { access ->
        val expressionResult = (body as? IrExpressionBody)?.expression
        if (expressionResult == null) access else access.copy(returns = access.returns + expressionResult)
    } ?: DirectMemberAccesses(
            calls = emptySet(),
            callSites = emptyList(),
            genericOwnerCallSites = emptyList(),
            reads = emptySet(),
            writes = emptySet(),
            writeValues = emptyMap(),
            valueDefinitions = emptyMap(),
            returns = emptyList(),
        )

    private fun IrFunction.transitiveCalls(
        directAccesses: Map<IrFunction, DirectMemberAccesses>,
    ): Set<IrFunction> {
        val result = linkedSetOf<IrFunction>()
        val worklist = ArrayDeque(directAccesses.getValue(this).calls)
        while (worklist.isNotEmpty()) {
            val target = worklist.removeFirst()
            if (target == this || !result.add(target)) continue
            worklist.addAll(directAccesses.getValue(target).calls)
        }
        return result
    }

    private fun IrFunction.transitiveFieldReads(
        directAccesses: Map<IrFunction, DirectMemberAccesses>,
    ): Set<IrField> = (transitiveCalls(directAccesses) + this)
        .flatMapTo(linkedSetOf()) { function -> directAccesses.getValue(function).reads }

    private fun IrFunction.transitiveFieldWrites(
        directAccesses: Map<IrFunction, DirectMemberAccesses>,
    ): Set<IrField> = (transitiveCalls(directAccesses) + this)
        .flatMapTo(linkedSetOf()) { function -> directAccesses.getValue(function).writes }

    private fun IrType.isRepresentationNeutralNullableGenericOwnerReference(owner: IrClass): Boolean {
        val simple = this as? IrSimpleType ?: return false
        return simple.nullability == SimpleTypeNullability.MARKED_NULLABLE &&
                isPhysicallyTypedLocalGenericOwnerReference(owner)
    }

    /**
     * Bounded physical reference proof used only by the production-inert owner analysis. A local
     * non-value generic class with invariant, non-open-nullable arguments has one stable CLR
     * reference carrier once its complete physical family is selected. This does not admit that
     * family and deliberately rejects `C<T?>`, external classifiers, projections, and value
     * classes.
     */
    private fun IrType.isPhysicallyTypedLocalGenericOwnerReference(owner: IrClass): Boolean {
        val simple = this as? IrSimpleType ?: return false
        val classifier = (simple.classifier as? IrClassSymbol)?.owner ?: return false
        if (classifier.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB ||
                !classifier.isDotNetGenericClassDeclaration || classifier.isValue ||
                context.preLoweringDeclarationKeys[classifier] == null ||
                simple.arguments.size != classifier.typeParameters.size
        ) {
            return false
        }
        return simple.arguments.all { argument ->
            val projection = argument as? IrTypeProjection ?: return@all false
            projection.variance == Variance.INVARIANT &&
                    !projection.type.hasExplicitNullableParameterOf(owner)
        }
    }

    /** Detects the `D<T> : C<T?>` family whose TypeDef edge cannot vary per closed T. */
    private fun IrType.hasExplicitNullableParameterOf(owner: IrClass): Boolean {
        val simpleType = this as? IrSimpleType ?: return false
        val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
        if (parameter?.parent == owner && simpleType.nullability == SimpleTypeNullability.MARKED_NULLABLE) {
            return true
        }
        return simpleType.arguments.any { argument ->
            (argument as? IrTypeProjection)?.type?.hasExplicitNullableParameterOf(owner) == true
        }
    }

    private fun IrType.sameInvariantTypeAs(other: IrType): Boolean {
        val left = this as? IrSimpleType ?: return false
        val right = other as? IrSimpleType ?: return false
        if (left.classifier != right.classifier || left.nullability != right.nullability ||
            left.arguments.size != right.arguments.size
        ) {
            return false
        }
        return left.arguments.indices.all { index ->
            val leftProjection = left.arguments[index] as? IrTypeProjection ?: return@all false
            val rightProjection = right.arguments[index] as? IrTypeProjection ?: return@all false
            leftProjection.variance == Variance.INVARIANT &&
                    rightProjection.variance == Variance.INVARIANT &&
                    leftProjection.type.sameInvariantTypeAs(rightProjection.type)
        }
    }

}
