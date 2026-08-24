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
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberFamilyRole
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberAccessPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerMemberPolicy
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerOverrideBindingPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerOverrideTargetKind
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPhysicalSlotDomain
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPrototypeMember
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerSemanticHookReason
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStateCarrierPlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStateInitializerPlan
import org.jetbrains.kotlin.backend.dotnet.mergeDotNetGenericOwnerParameterSlotDomains
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStateCarrierRequirement
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStateMemorySemantics
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerStateWriteProvenancePlan
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerPrototypeStateInitializerKind
import org.jetbrains.kotlin.backend.dotnet.DotNetRuntimeTypes
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericOwnerWriteValueProvenance
import org.jetbrains.kotlin.backend.dotnet.DotNetBoundGenericOwnerMemberFamily
import org.jetbrains.kotlin.backend.dotnet.DotNetBoundGenericOwnerPhysicalSlot
import org.jetbrains.kotlin.backend.dotnet.dotNetLibraryAbiKeyOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerCallRouteTraceHooks
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerRehearsal
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerPhysicalMemberName
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericOwnerPhysicalForeignOverrideProbeName
import org.jetbrains.kotlin.backend.dotnet.dotNetIlMethodName
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericClassDeclaration
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
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrWhen
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
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isOriginallyLocalDeclaration
import org.jetbrains.kotlin.ir.util.hasAnnotation
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
        val producerFunctions = linkedSetOf<IrFunction>()
        val producerInitializers = mutableListOf<ProducerInitializer>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB) return
                if (!declaration.isDotNetResolutionOnlyStdlibDeclaration &&
                    declaration.isDotNetGenericClassDeclaration
                ) {
                    owners += declaration
                }
                declaration.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) {
                if (declaration.origin == IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB) return
                producerFunctions += declaration
                declaration.acceptChildrenVoid(this)
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
        val producerAccesses = producerFunctions.associateWithTo(linkedMapOf()) { function ->
            function.collectDirectAccesses(producerFunctions)
        }
        val initializerAccesses = producerInitializers.associateWithTo(linkedMapOf()) { initializer ->
            initializer.element.collectDirectAccesses(producerFunctions).withImplicitWrite(initializer)
        }

        for (owner in owners) {
            context.genericOwnerArchitecturePlans[owner] = plan(owner, producerAccesses, initializerAccesses)
        }
        linkDetachedOverrideFamilies()
        val callRoutes = GenericOwnerCallRouteAnalyzer(
            producerFunctions = producerFunctions,
            producerInitializers = producerInitializers,
            producerAccesses = producerAccesses,
            initializerAccesses = initializerAccesses,
        ).analyze()
        context.genericOwnerCallRoutes += callRoutes
        if (context.configuration.dotNetGenericOwnerRehearsal) {
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
                        DotNetGenericOwnerCallRouteRequirement.SEMANTIC_RESULT_CAPABILITY
            }
            .map(DotNetGenericOwnerCallRoutePlan::calleeOwner)
            .toSet()
        val capabilityPlans = admittedPlans.filter { plan ->
            plan.disposition != DotNetGenericOwnerCandidateDisposition.RETAINED_NON_ABI_IMPLEMENTATION_OWNER ||
                    plan.owner in privateCapabilityOwners ||
                    plan.memberFamilies.values.any { family ->
                        DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER in family.roles
                    }
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
                context.genericOwnerCapabilityDeclarations += hook
                context.genericOwnerCapabilityDeclarations += hook.parameters.drop(1)
                source.parameters.drop(1).zip(hook.parameters.drop(1)).forEach { pair ->
                    val sourceParameter = pair.first
                    val hookParameter = pair.second
                    if (sourceParameter.type.containsReifiedVariantOwnerApplicationOf(owner)) {
                        // The semantic hook's object carrier may receive either a Kotlin
                        // capability or an ordinary foreign natural I<T> through the public
                        // typed entry. Calls made by the moved body must therefore keep the
                        // capability fast path but retain unique-natural foreign dispatch.
                        context.genericOwnerForeignDispatchDeclarations += hookParameter
                    }
                }
                if (source.returnType.containsReifiedVariantOwnerApplicationOf(owner)) {
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
        semanticHooksBySource.entries.forEach { entry ->
            val source = entry.key
            val owner = source.parent as IrClass
            val plan = admittedPlansByOwner.getValue(owner)
            val family = plan.memberFamilies.getValue(source)
            val supportsNoInputForeignOverrideProbe =
                source.typeParameters.isEmpty() &&
                        source.parameters.size == 1 &&
                        family.parameterSlotDomains.isEmpty()
            val supportsOwnerRelativeMethodForeignOverrideProbe =
                source.typeParameters.size == 1 &&
                        source.parameters.size == 2 &&
                        family.parameterSlotDomains == listOf(
                            DotNetGenericOwnerPhysicalSlotDomain.DECLARATION_INDEPENDENT
                        ) &&
                        DotNetGenericOwnerSemanticHookReason.OWNER_RELATIVE_METHOD_BOUND in
                        family.semanticHookReasons
            val supportsDirectForeignOverrideProbe =
                supportsNoInputForeignOverrideProbe || supportsOwnerRelativeMethodForeignOverrideProbe
            if (owner.kind == ClassKind.INTERFACE || source.modality != Modality.OPEN ||
                DescriptorVisibilities.isPrivate(source.visibility) ||
                !supportsDirectForeignOverrideProbe ||
                family.returnSlotDomain != DotNetGenericOwnerPhysicalSlotDomain.STRICT_OWNER_OUTPUT
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
        semanticHooksBySource.entries.forEach { entry ->
            val source = entry.key
            val hook = entry.value
            val owner = source.parent as IrClass
            hook.body = source.moveBodyTo(hook)?.also { body ->
                fun IrExpression?.isCurrentHookReceiver(): Boolean = when (this) {
                    is IrGetValue -> symbol.owner === hook.parameters[0]
                    is IrTypeOperatorCall ->
                        operator == IrTypeOperator.IMPLICIT_CAST && argument.isCurrentHookReceiver()
                    else -> false
                }

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
                        if (container is IrGetValue &&
                            container.symbol.owner === hook.parameters[0]
                        ) {
                            // The hook still executes on one actual C<!T>. Preserve `this` so
                            // direct state access stays on that construction; only candidate and
                            // value occurrences cross into the semantic domain. Routing `this`
                            // through its own property capability would recurse back into this
                            // hook.
                            return type
                        }
                        if (container is IrGetField) {
                            val field = container.symbol.owner
                            val fieldOwner = field.parent as? IrClass
                            val state = fieldOwner
                                ?.let(context.genericOwnerArchitecturePlans::get)
                                ?.stateCarriers
                                ?.get(field)
                            if (fieldOwner === owner && container.receiver.isCurrentHookReceiver() &&
                                state?.requirement ==
                                DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN
                            ) {
                                // Reading producer-proven C<!T>/!T state from this exact C<!T>
                                // construction does not cross a semantic boundary. Retaining the
                                // constructed carrier is required for nested owner graphs such as
                                // ArraySubList<T>.root: ArrayList<T>; mapping that read to the
                                // classifier capability would then make even element-independent
                                // inherited members (for example modCount) unreachable.
                                return type
                            }
                        }
                        if (container is IrCall && container.dispatchReceiver.isCurrentHookReceiver() &&
                            container.symbol.owner.parent === owner &&
                            container.symbol.owner.producerProvenTypedStateGetterBackingFieldOrNull() != null
                        ) {
                            // Kotlin property access can retain an IrCall even for a private
                            // backing field. It has the same exact carrier proof as IrGetField;
                            // changing only the call result to a capability would split the two
                            // physical views of one state read.
                            return type
                        }
                        @Suppress("UNCHECKED_CAST")
                        return type?.toGenericOwnerSemanticType(owner) as Type
                    }
                })
            }
            if (source.body == null) return@forEach
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
        context.genericOwnerArchitecturePlans.entries.toList().forEach { planEntry ->
            val owner = planEntry.key
            val plan = planEntry.value
            val fields = plan.stateCarriers.keys.intersect(directlyWrittenSemanticFields)
            if (fields.isEmpty()) return@forEach
            context.genericOwnerArchitecturePlans[owner] = plan.copy(
                stateCarriers = plan.stateCarriers.mapValuesTo(linkedMapOf()) { stateEntry ->
                    val state = stateEntry.value
                    state.copy(
                        requirement = if (state.field in fields &&
                            state.requirement !=
                                DotNetGenericOwnerStateCarrierRequirement.DECLARATION_INDEPENDENT_STORAGE &&
                            state.requirement !=
                                DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN
                        ) {
                            DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED
                        } else {
                            state.requirement
                        },
                    )
                },
                semanticReachableWriteFields = plan.semanticReachableWriteFields + fields,
            )
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
                context.genericOwnerCapabilityDeclarations += slot
                context.genericOwnerCapabilityDeclarations += slot.parameters.drop(1)
                val returnsForeignInterfaceConstruction =
                    source.returnType.containsReifiedVariantOwnerApplicationOf(owner)
                if (returnsForeignInterfaceConstruction) {
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
                context.genericOwnerCapabilityDeclarations += dispatcher
                context.genericOwnerCapabilityDeclarations += dispatcher.parameters.drop(1)
                if (returnsForeignInterfaceConstruction) {
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
                    val movedReceiver = helper.parameters.singleOrNull { parameter ->
                        parameter.origin == IrDeclarationOrigin.MOVED_DISPATCH_RECEIVER
                    } ?: error(
                        "Internal .NET backend error: generic-owner default helper lacks its moved receiver"
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
                        returnType = slotType(helper.returnType)
                        helper.parameters.filterNot { it === movedReceiver }.forEach { parameter ->
                            parameters += parameter.copyTo(
                                this@defaultSlot,
                                type = slotType(parameter.type),
                                varargElementType = parameter.varargElementType?.let(::slotType),
                                defaultValue = null,
                            )
                        }
                    }
                    context.genericOwnerCapabilityDeclarations += defaultSlot
                    context.genericOwnerCapabilityDeclarations += defaultSlot.parameters.drop(1)
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
                    context.genericOwnerCapabilityDeclarations += defaultDispatcher
                    context.genericOwnerCapabilityDeclarations += defaultDispatcher.parameters.drop(1)
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
                            if (source.producerProvenTypedStateGetterBackingFieldOrNull() == null) {
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
                DotNetGenericOwnerCallRouteRequirement.SEMANTIC_RESULT_CAPABILITY
            ) {
                return@forEach
            }
            val source = route.callee.let { candidate ->
                candidate.resolveFakeOverride() ?: candidate.resolveFakeOverrideMaybeAbstract() ?: candidate
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
                val prototype = createDetachedPrototypeMember(
                    owner,
                    source,
                    DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
                    routeIndex,
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
                context.genericOwnerCapabilityDeclarations += interfaceSlot
                context.genericOwnerCapabilityDeclarations += interfaceSlot.parameters.drop(1)
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
                context.genericOwnerCapabilityDeclarations += dispatcher
                context.genericOwnerCapabilityDeclarations += dispatcher.parameters.drop(1)
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
        val externalSemanticSlots = linkedMapOf<IrSimpleFunction, IrSimpleFunction>()
        fun createExternalDefaultSlot(
            owner: IrClass,
            helper: IrSimpleFunction,
            index: Int,
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
                returnType = slotType(helper.returnType)
                helper.parameters.filterNot { it === movedReceiver }.forEach { parameter ->
                    parameters += parameter.copyTo(
                        this@slot,
                        type = slotType(parameter.type),
                        varargElementType = parameter.varargElementType?.let(::slotType),
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
                source.returnType.containsReifiedVariantOwnerApplicationOf(route.calleeOwner) &&
                        binding.family.semanticHookMethodName != null
            val externalDefault = context.externalDefaultArgumentDispatchers[route.call.symbol.owner]
            if (externalDefault != null) {
                val physicalMethodName = binding.family.defaultCapabilityMethodName
                    ?: return@forEachIndexed
                val defaultSlot = externalDefaultSlots.getOrPut(route.call.symbol.owner) {
                    createExternalDefaultSlot(route.calleeOwner, route.call.symbol.owner, index).also { prototype ->
                        context.genericOwnerCapabilityDeclarations += prototype
                        context.genericOwnerCapabilityDeclarations += prototype.parameters
                        context.externalGenericOwnerPhysicalSlots[prototype] =
                            DotNetBoundGenericOwnerPhysicalSlot(
                                binding.library,
                                binding.family,
                                binding.family.ownerPath,
                                physicalMethodName,
                            )
                    }
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
                    val semanticSlot = externalSemanticSlots.getOrPut(source) {
                        createDetachedPrototypeMember(
                            owner = route.calleeOwner,
                            source = source,
                            role = DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
                            memberIndex = index,
                        ).function.also { prototype ->
                            context.genericOwnerCapabilityDeclarations += prototype
                            context.genericOwnerCapabilityDeclarations += prototype.parameters.drop(1)
                            context.externalGenericOwnerPhysicalSlots[prototype] =
                                DotNetBoundGenericOwnerPhysicalSlot(
                                    binding.library,
                                    binding.family,
                                    semanticOwnerPath,
                                    semanticMethodName,
                                )
                        }
                    }
                    context.genericOwnerCapabilityCallTargets[route.call] = semanticSlot
                    return@forEachIndexed
                }
                if (!exactResultNeedsSemanticRoute) return@forEachIndexed
            }
            val slot = externalSlots.getOrPut(source) {
                createDetachedPrototypeMember(
                    owner = route.calleeOwner,
                    source = source,
                    role = DotNetGenericOwnerMemberFamilyRole.CAPABILITY_DISPATCHER,
                    memberIndex = index,
                ).function.also { prototype ->
                    prototype.modality = Modality.ABSTRACT
                    context.genericOwnerCapabilityDeclarations += prototype
                    // Unlike a local slot, this un-emitted prototype is parented by the logical
                    // external C<T>; its implicit receiver must therefore be remapped to the
                    // producer's non-generic capability as well as its explicit value slots.
                    context.genericOwnerCapabilityDeclarations += prototype.parameters
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
        val memberFamilies = members.associateWithTo(linkedMapOf()) { member ->
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
                if (member.returnType.containsReifiedVariantOwnerApplicationOf(owner) &&
                    memberAccesses.getValue(member).transitiveReads.any(semanticStateWriteFields::contains)
                ) {
                    // A final output can need the same split as an open output. In particular,
                    // Nested<T> state may admit a covariantly widened Nested<A> carrier which is
                    // not the CLR construction Nested<!T>. Keep the body/state in the semantic
                    // hook and let the natural typed entry perform the exact CLR view cast.
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
        val disposition = when {
            conditionalSupertypes.isNotEmpty() ->
                DotNetGenericOwnerCandidateDisposition.BLOCKED_METADATA_FIXED_CONDITIONAL_SUPERTYPE
            context.inlineClassesUtils.isClassInlineLike(owner) ->
                DotNetGenericOwnerCandidateDisposition.RETAINED_VALUE_CLASS_ABI
            owner.isOriginallyLocalDeclaration || owner.origin != IrDeclarationOrigin.DEFINED ||
                    owner.name.isSpecial ->
                DotNetGenericOwnerCandidateDisposition.RETAINED_NON_ABI_IMPLEMENTATION_OWNER
            semanticStateWriteFields.isNotEmpty() && openOutputs.isNotEmpty() ->
                DotNetGenericOwnerCandidateDisposition.BLOCKED_OPEN_OUTPUT_STATE_COHERENCE
            semanticStateWriteFields.isNotEmpty() ->
                DotNetGenericOwnerCandidateDisposition.REQUIRES_SEMANTIC_STATE_PROOF
            stateCarriers.values.any { state ->
                state.requirement == DotNetGenericOwnerStateCarrierRequirement.VOLATILE_OBJECT_STORAGE_REQUIRED
            } ->
                DotNetGenericOwnerCandidateDisposition.REQUIRES_STATE_MEMORY_MODEL_PROOF
            stateCarriers.values.any { state ->
                state.requirement == DotNetGenericOwnerStateCarrierRequirement.COMPLETE_ACCESS_GRAPH_REQUIRED
            } ->
                DotNetGenericOwnerCandidateDisposition.REQUIRES_COMPLETE_FIELD_ACCESS_GRAPH
            stateCarriers.values.any { state ->
                state.requirement == DotNetGenericOwnerStateCarrierRequirement.TYPED_WRITE_VALUE_PROVENANCE_REQUIRED
            } ->
                DotNetGenericOwnerCandidateDisposition.REQUIRES_TYPED_WRITE_VALUE_PROVENANCE
            else ->
                DotNetGenericOwnerCandidateDisposition.REQUIRES_MEMBER_PHYSICALIZATION_PROOF
        }
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
                createDetachedPrototypeMember(owner, source, role, memberIndex)
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
    private fun linkDetachedOverrideFamilies() {
        val plans = context.genericOwnerArchitecturePlans
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
                        val overridden = overriddenSymbol.owner
                        val overriddenOwner = overridden.parent as? IrClass ?: return@mapNotNull null
                        plans[overriddenOwner]?.memberFamilies?.get(overridden)
                    }
                    val family = families.getValue(source)
                    val inheritsSemanticHook = overriddenFamilies.any { overriddenFamily ->
                        DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK in overriddenFamily.roles
                    } || source.overriddenSymbols.any { overridden ->
                        externalFamily(overridden.owner)?.family?.semanticHookMethodName != null
                    }
                    val mergedParameterSlotDomains = overriddenFamilies.fold(family.parameterSlotDomains) {
                            domains, overriddenFamily ->
                        mergeDotNetGenericOwnerParameterSlotDomains(
                            domains,
                            overriddenFamily.parameterSlotDomains,
                        )
                    }
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
                            family.semanticHookReasons +
                                    DotNetGenericOwnerSemanticHookReason.INHERITED_SEMANTIC_OVERRIDE
                        } else {
                            family.semanticHookReasons
                        },
                        parameterSlotDomains = mergedParameterSlotDomains,
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
        } while (changed)

        // A semantic obligation inherited after the per-owner pass must carry through the same
        // private helper graph as an obligation known during that pass. Otherwise moving only
        // the overriding body to object-domain parameters makes its first private `Nested<T>`
        // helper call reconstruct `Nested<!T>` and reject a legal widened carrier. These helpers
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
                    helper.parent === owner && DescriptorVisibilities.isPrivate(helper.visibility)
                }
            if (semanticPrivateHelpers.isEmpty()) return@planLoop
            val families = plan.memberFamilies.toMutableMap()
            semanticPrivateHelpers.forEach helperLoop@{ helper ->
                val family = families[helper] ?: return@helperLoop
                families[helper] = family.copy(
                    roles = family.roles + DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK,
                    semanticHookReasons = family.semanticHookReasons +
                            DotNetGenericOwnerSemanticHookReason.INTERNAL_SEMANTIC_REACHABILITY,
                )
            }
            if (families != plan.memberFamilies) {
                plans[owner] = plan.copy(memberFamilies = families)
            }
        }

        // An override can inherit a semantic hook only after all owners have been planned. Fold
        // that late obligation back into state selection before any physical rehearsal occurs.
        // Producer-proven typed state is the deliberate exception: inheriting a carrier boundary
        // does not add a new logical write, so the hook narrows its object input to the already
        // selected physical !T at the store. Every field whose producer graph was not proven
        // still moves to semantic object state.
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
            val stateCarriers = plan.stateCarriers.mapValuesTo(linkedMapOf()) { stateEntry ->
                val state = stateEntry.value
                val semanticReaders = state.directReaders.filterTo(linkedSetOf(), semanticProducerFunctions::contains)
                val semanticWriters = state.directWriters.filterTo(linkedSetOf(), semanticProducerFunctions::contains)
                state.copy(
                    requirement = if (state.field in semanticWriteFields &&
                        state.requirement !=
                            DotNetGenericOwnerStateCarrierRequirement.DECLARATION_INDEPENDENT_STORAGE &&
                        state.requirement !=
                            DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN
                    ) {
                        DotNetGenericOwnerStateCarrierRequirement.SEMANTIC_OBJECT_REQUIRED
                    } else {
                        state.requirement
                    },
                    semanticReachableReaders = state.semanticReachableReaders + semanticReaders,
                    semanticReachableWriters = state.semanticReachableWriters + semanticWriters,
                )
            }
            plans[owner] = plan.copy(
                stateCarriers = stateCarriers,
                semanticReachableWriteFields = plan.semanticReachableWriteFields + semanticWriteFields,
            )
        }

        plans.entries.toList().forEach { planEntry ->
            val owner = planEntry.key
            val plan = plans.getValue(owner)
            val prototypes = plan.prototypeMembers.mapValuesTo(linkedMapOf()) { entry ->
                entry.value.toMutableMap()
            }
            plan.memberFamilies.entries.forEachIndexed { memberIndex, familyEntry ->
                val source = familyEntry.key
                val family = familyEntry.value
                val roles = prototypes.getOrPut(source) { linkedMapOf() }
                family.roles.forEach { role ->
                    roles.getOrPut(role) {
                        createDetachedPrototypeMember(owner, source, role, memberIndex)
                    }
                }
            }
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
            ).function.also { prototype ->
                context.genericOwnerCapabilityDeclarations += prototype
                context.genericOwnerCapabilityDeclarations += prototype.parameters.drop(1)
                context.externalGenericOwnerPhysicalSlots[prototype] =
                    DotNetBoundGenericOwnerPhysicalSlot(
                        binding.library,
                        binding.family,
                        ownerPath,
                        methodName,
                    )
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

        plans.entries.toList().forEach { planEntry ->
            val owner = planEntry.key
            val plan = plans.getValue(owner)
            val bindings = linkedMapOf<IrSimpleFunction, MutableList<DotNetGenericOwnerOverrideBindingPlan>>()
            plan.memberFamilies.forEach { familyEntry ->
                val source = familyEntry.key
                if (source.isFakeOverride) return@forEach
                val family = familyEntry.value
                source.overriddenSymbols.forEach { overriddenSymbol ->
                    val overridden = overriddenSymbol.owner
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
            source.returnType.containsReifiedVariantOwnerApplicationOf(owner)
        ) {
            // A semantic Nested<T> result can be either Kotlin's capability-bearing object or
            // an ordinary foreign natural I<T>. Object is the only carrier shared by both. Keep
            // that decision in IR as well as metadata so generated forwarding bodies never try
            // the impossible intermediate conversion Nested<!T> -> Nested<object>.
            context.irBuiltIns.anyNType
        } else {
            prototypeType(source.returnType)
        }
        source.parameters
            .filter { parameter -> parameter.kind != IrParameterKind.DispatchReceiver }
            .forEach { parameter ->
                val carriesVariantOwnerConstruction =
                    role != DotNetGenericOwnerMemberFamilyRole.TYPED_ENTRY &&
                            parameter.type.containsReifiedVariantOwnerApplicationOf(owner)
                prototype.parameters += parameter.copyTo(
                    prototype,
                    type = if (carriesVariantOwnerConstruction) {
                        // A Kotlin-wide nested input can carry either a capability-bearing
                        // implementation or an ordinary foreign natural I<T>. Do not invent the
                        // physically unrelated I<object> construction before entering the hook.
                        context.irBuiltIns.anyNType
                    } else {
                        prototypeType(parameter.type)
                    },
                    varargElementType = if (carriesVariantOwnerConstruction) {
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

    private fun IrType.referencesGenericOwnerParameter(owner: IrClass): Boolean {
        var current: IrClass? = owner
        while (current != null) {
            if (referencesTypeParameterOf(current)) return true
            current = if (current.isInner) current.parent as? IrClass else null
        }
        return false
    }

    private fun IrType.containsReifiedVariantOwnerApplicationOf(owner: IrClass): Boolean {
        val simpleType = this as? IrSimpleType ?: return false
        val classifier = (simpleType.classifier as? IrClassSymbol)?.owner
        if (classifier != null &&
            (classifier in context.reifiedGenericInterfaces ||
                    externalDeclarations.hasReifiedGenericInterface(classifier) ||
                    DotNetRuntimeTypes.usesDeclaredViewByDefaultInRehearsal(classifier)) &&
            classifier.typeParameters.zip(simpleType.arguments).any { pair ->
                pair.first.variance != Variance.INVARIANT &&
                        (pair.second as? IrTypeProjection)?.type?.referencesGenericOwnerParameter(owner) == true
            }
        ) {
            return true
        }
        return simpleType.arguments.any { argument ->
            (argument as? IrTypeProjection)?.type?.containsReifiedVariantOwnerApplicationOf(owner) == true
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

    private fun IrField.isProducerProvenTypedGenericOwnerState(): Boolean {
        val owner = parent as? IrClass ?: return false
        val plan = context.genericOwnerArchitecturePlans[owner]
            ?.takeIf(DotNetGenericOwnerArchitecturePlan::isReifiedByGenericOwnerRehearsal)
            ?: return false
        return plan.stateCarriers[this]?.requirement ==
                DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN
    }

    private fun IrSimpleFunction.producerProvenTypedStateGetterBackingFieldOrNull(): IrField? {
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
            field.parent === owner && field.isProducerProvenTypedGenericOwnerState()
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
                        val exactResultNeedsSemanticRoute =
                            target.callee.returnType.containsReifiedVariantOwnerApplicationOf(target.owner) &&
                                    target.localFamily?.roles?.contains(
                                        DotNetGenericOwnerMemberFamilyRole.SEMANTIC_HOOK
                                    ) == true
                        val requirement = when {
                            target.localFamily == null ->
                                DotNetGenericOwnerCallRouteRequirement.EXTERNAL_FAMILY_RECORD_REQUIRED
                            provenance == DotNetGenericOwnerCallReceiverProvenance.EXACT_CONSTRUCTION &&
                                    exactResultNeedsSemanticRoute ->
                                DotNetGenericOwnerCallRouteRequirement.SEMANTIC_RESULT_CAPABILITY
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
            fun markIfWidened(
                declaration: IrDeclaration,
                type: IrType,
                emptyFlowIsExact: Boolean = false,
            ) {
                if (!type.hasRelevantGenericOwnerInAncestry()) return
                // Producer-proven typed state is stronger evidence than the general value-flow
                // fallback below. The natural field/getter remains on its exact CLR carrier;
                // widened receiver calls use separately materialized capability members.
                if (declaration is IrField && declaration.isProducerProvenTypedGenericOwnerState()) return
                if (declaration is IrSimpleFunction &&
                    declaration.producerProvenTypedStateGetterBackingFieldOrNull() != null
                ) {
                    return
                }
                val candidates = origins[declaration].orEmpty()
                val needsCapability = (candidates.isEmpty() && !emptyFlowIsExact) || candidates.any { origin ->
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
                        setOf(exact(expression.type))
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
                if (candidate == expected) return true
                if (candidate.sameInvariantTypeAs(expected)) return true
                val simple = candidate as? IrSimpleType ?: continue
                val classifier = (simple.classifier as? IrClassSymbol)?.owner ?: continue
                if (simple.arguments.size != classifier.typeParameters.size) continue
                val substitutions = classifier.typeParameters.zip(simple.arguments).mapNotNull { pair ->
                    val projection = pair.second as? IrTypeProjection ?: return@mapNotNull null
                    pair.first.symbol to projection.type
                }
                if (substitutions.size != classifier.typeParameters.size) continue
                val plan = context.genericOwnerArchitecturePlans[classifier]
                val physicalSubstitutions = if (plan?.isReifiedByGenericOwnerRehearsal == false) {
                    // A locally classified erased-only owner has no CLR !n token. Its base edge
                    // is frozen with object-domain arguments even when this logical use closes
                    // D<Int>. An external owner is deliberately different here: route analysis
                    // must retain its logical substitution until the producer family record
                    // selects an exact, semantic-capability, or erased physical route.
                    classifier.typeParameters.associate { parameter ->
                        parameter.symbol to context.irBuiltIns.anyNType
                    }
                } else {
                    substitutions.toMap()
                }
                val substitutor = IrTypeSubstitutor(physicalSubstitutions, allowEmptySubstitution = true)
                classifier.superTypes.mapTo(pending, substitutor::substitute)
            }
            return false
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

        private fun exact(type: IrType): ReceiverOrigin = ReceiverOrigin(ReceiverOriginKind.EXACT, type)

        private fun unresolved(): Set<ReceiverOrigin> = setOf(ReceiverOrigin(ReceiverOriginKind.UNRESOLVED))

        private fun node(key: Any): MutableSet<ReceiverOrigin> = origins.getOrPut(key) { linkedSetOf() }

        private fun addOrigin(key: Any, origin: ReceiverOrigin): Boolean = node(key).add(origin)

        private fun addOrigins(key: Any, additions: Set<ReceiverOrigin>): Boolean = node(key).addAll(additions)
    }

    /**
     * Traces the physical domain of field-write values through parameters, calls, local aliases,
     * assignments, returns, and casts. This is deliberately context-insensitive and fail-closed:
     * merging any object-domain producer keeps the write semantic, while an unsupported or
     * source-free path remains unresolved. In particular, a cast to an owner parameter preserves
     * its input provenance and can never create typed evidence by itself.
     */
    private inner class TypedWriteValueProvenanceAnalyzer(
        private val owner: IrClass,
        private val members: List<IrSimpleFunction>,
        private val memberPolicies: Map<IrSimpleFunction, DotNetGenericOwnerMemberPolicy>,
        private val producerAccesses: Map<IrFunction, DirectMemberAccesses>,
        private val initializerAccesses: Map<ProducerInitializer, DirectMemberAccesses>,
    ) {
        private val provenances = linkedMapOf<Any, MutableSet<DotNetGenericOwnerWriteValueProvenance>>()
        private val typedBoundaryParameters = linkedSetOf<IrValueDeclaration>()

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
                    addProvenance(function, defaultProvenance(function.returnType))
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
                            if (parameter in typedBoundaryParameters) return@forEach
                            val argument = call.arguments.getOrNull(parameter.indexInParameters)
                            val argumentProvenance = if (argument == null) {
                                setOf(DotNetGenericOwnerWriteValueProvenance.UNRESOLVED)
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
                                provenance = select(values.flatMapTo(linkedSetOf()) { value ->
                                    writeProvenance(field, value).ifEmpty {
                                        setOf(DotNetGenericOwnerWriteValueProvenance.UNRESOLVED)
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
                                provenance = select(values.flatMapTo(linkedSetOf()) { value ->
                                    writeProvenance(field, value).ifEmpty {
                                        setOf(DotNetGenericOwnerWriteValueProvenance.UNRESOLVED)
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
                if (DescriptorVisibilities.isPrivate(function.visibility) && !isOwnerConstructor) return@forEach
                val member = (function as? IrSimpleFunction)?.takeIf { candidate -> candidate in members }
                function.parameters.forEach { parameter ->
                    if (parameter.kind == IrParameterKind.DispatchReceiver) return@forEach
                    val isTypedOwnerInput = parameter.type.referencesGenericOwnerParameter(owner) && when {
                        isOwnerConstructor ->
                            !parameter.type.containsReifiedVariantOwnerApplicationOf(owner)
                        member != null ->
                            parameter.type.isLegalAtOwnerVariance(owner, TypePolarity.IN) &&
                                    !parameter.type.containsReifiedVariantOwnerApplicationOf(owner)
                        else -> false
                    }
                    addProvenance(
                        parameter,
                        if (isTypedOwnerInput) {
                            typedBoundaryParameters += parameter
                            DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED
                        } else {
                            DotNetGenericOwnerWriteValueProvenance.SEMANTIC_OBJECT
                        },
                    )
                }
            }
            check(memberPolicies.keys == members.toSet()) {
                "Internal .NET backend error: typed-write provenance lacks owner member policies"
            }
        }

        private fun provenanceOf(expression: IrExpression?): Set<DotNetGenericOwnerWriteValueProvenance> {
            if (expression == null) return setOf(DotNetGenericOwnerWriteValueProvenance.UNRESOLVED)
            return when (expression) {
                is IrConstructorCall -> if (expression.type.isPhysicallyTypedLocalGenericOwnerReference(owner)) {
                    setOf(DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED)
                } else {
                    setOf(defaultProvenance(expression.type))
                }
                is IrGetValue -> provenances[expression.symbol.owner].orEmpty()
                is IrTypeOperatorCall -> provenanceOf(expression.argument)
                is IrReturn -> provenanceOf(expression.value)
                is IrWhen -> expression.branches.flatMapTo(linkedSetOf()) { branch ->
                    provenanceOf(branch.result)
                }
                is IrContainerExpression -> {
                    val result = expression.statements.lastOrNull() as? IrExpression
                    provenanceOf(result)
                }
                is IrFunctionAccessExpression -> {
                    val target = expression.symbol.owner
                    if (expression.isPhysicallyTypedOwnerClassifierArrayAllocation(owner)) {
                        setOf(DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED)
                    } else if (target in producerAccesses) {
                        provenances[target].orEmpty()
                    } else {
                        setOf(defaultProvenance(expression.type))
                    }
                }
                is IrGetField -> setOf(defaultProvenance(expression.type))
                else -> setOf(defaultProvenance(expression.type))
            }
        }

        /**
         * `arrayOfNulls<Node<T>>()` is a typed producer when Node is a local Kotlin generic
         * classifier. The current erased owner emits Node[] and a future admitted CLR-generic
         * owner emits Node<T>[]; neither route passes through object-domain element storage.
         * Direct `arrayOfNulls<T>()`/`arrayOfNulls<T?>()` deliberately fails this test.
         */
        private fun defaultProvenance(type: IrType): DotNetGenericOwnerWriteValueProvenance =
            if (type.referencesGenericOwnerParameter(owner)) {
                DotNetGenericOwnerWriteValueProvenance.UNRESOLVED
            } else {
                DotNetGenericOwnerWriteValueProvenance.SEMANTIC_OBJECT
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
        ): Set<DotNetGenericOwnerWriteValueProvenance> =
            if (value is IrConst && value.value == null &&
                    field.type.isRepresentationNeutralNullableGenericOwnerReference(owner)
            ) {
                setOf(DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED)
            } else {
                provenanceOf(value)
            }

        private fun node(key: Any): MutableSet<DotNetGenericOwnerWriteValueProvenance> =
            provenances.getOrPut(key) { linkedSetOf() }

        private fun addProvenance(
            key: Any,
            provenance: DotNetGenericOwnerWriteValueProvenance,
        ): Boolean = node(key).add(provenance)

        private fun addProvenances(
            key: Any,
            additions: Set<DotNetGenericOwnerWriteValueProvenance>,
        ): Boolean = node(key).addAll(additions)

        private fun select(
            candidates: Set<DotNetGenericOwnerWriteValueProvenance>,
        ): DotNetGenericOwnerWriteValueProvenance = when {
            DotNetGenericOwnerWriteValueProvenance.SEMANTIC_OBJECT in candidates ->
                DotNetGenericOwnerWriteValueProvenance.SEMANTIC_OBJECT
            DotNetGenericOwnerWriteValueProvenance.UNRESOLVED in candidates ->
                DotNetGenericOwnerWriteValueProvenance.UNRESOLVED
            DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED in candidates ->
                DotNetGenericOwnerWriteValueProvenance.PHYSICALLY_TYPED
            else -> DotNetGenericOwnerWriteValueProvenance.UNRESOLVED
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
        return elementClass.origin != IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB &&
                elementClass.isDotNetGenericClassDeclaration
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

    private enum class TypePolarity {
        OUT,
        IN,
        BOTH;

        fun through(variance: Variance): TypePolarity = when {
            this == BOTH || variance == Variance.INVARIANT -> BOTH
            variance == Variance.OUT_VARIANCE -> this
            else -> if (this == OUT) IN else OUT
        }
    }

    /** Kotlin declaration/use-site variance, including the variance Kotlin permits on classes. */
    private fun IrType.isLegalAtOwnerVariance(owner: IrClass, polarity: TypePolarity): Boolean {
        val simpleType = this as? IrSimpleType ?: return true
        val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
        if (parameter?.parent == owner) {
            return when (polarity) {
                TypePolarity.OUT -> parameter.variance != Variance.IN_VARIANCE
                TypePolarity.IN -> parameter.variance != Variance.OUT_VARIANCE
                TypePolarity.BOTH -> parameter.variance == Variance.INVARIANT
            }
        }

        val classifier = (simpleType.classifier as? IrClassSymbol)?.owner ?: return true
        return simpleType.arguments.withIndex().all { indexedArgument ->
            val index = indexedArgument.index
            val argument = indexedArgument.value
            val projection = argument as? IrTypeProjection ?: return@all true
            val declarationVariance = classifier.typeParameters.getOrNull(index)?.variance
                ?: Variance.INVARIANT
            val effectiveVariance = if (projection.variance == Variance.INVARIANT) {
                declarationVariance
            } else {
                projection.variance
            }
            projection.type.isLegalAtOwnerVariance(owner, polarity.through(effectiveVariance))
        }
    }
}
