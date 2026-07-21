/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.defaultArgumentsOriginalFunction
import org.jetbrains.kotlin.backend.common.ir.moveBodyTo
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetBoundDefaultArgumentDispatcher
import org.jetbrains.kotlin.backend.dotnet.DotNetBoundInterfaceDefaultImplementation
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalDeclarations
import org.jetbrains.kotlin.backend.dotnet.DotNetGenericInterfaceMemberView
import org.jetbrains.kotlin.backend.dotnet.DotNetInterfaceDefaultBodyPlacement
import org.jetbrains.kotlin.backend.dotnet.DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER
import org.jetbrains.kotlin.backend.dotnet.isDotNetOwnerDependentConstraint
import org.jetbrains.kotlin.backend.dotnet.DotNetInterfaceDefaultPromotionView
import org.jetbrains.kotlin.backend.dotnet.DotNetLoweredGenericInterfaceDefault
import org.jetbrains.kotlin.backend.dotnet.DotNetLoweredGenericInterfaceDefaultSlotAdapter
import org.jetbrains.kotlin.backend.dotnet.DotNetLoweredInterfaceDefaultClassForwarder
import org.jetbrains.kotlin.backend.dotnet.DotNetLoweredInterfaceDefaultPromotion
import org.jetbrains.kotlin.backend.dotnet.DotNetRuntimeTypes
import org.jetbrains.kotlin.backend.dotnet.DotNetTarget
import org.jetbrains.kotlin.backend.dotnet.dotNetExternalLibraries
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericInterfaceCanonicalSlotId
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericInterfaceMemberViews
import org.jetbrains.kotlin.backend.dotnet.dotNetGenericInterfaceMemberView
import org.jetbrains.kotlin.backend.dotnet.dotNetTarget
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericInterfaceDeclaration
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrStatementOriginImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.AbstractIrTypeSubstitutor
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.IrTypeParameterRemapper
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.createStaticFunctionWithReceivers
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.remapTypes
import org.jetbrains.kotlin.ir.util.resolveFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.utils.addToStdlib.assignFrom

internal val DOTNET_DEFAULT_IMPLS: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_DEFAULT_IMPLS")

internal val DOTNET_INTERFACE_DEFAULT_HELPER: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_INTERFACE_DEFAULT_HELPER")

internal val DOTNET_INTERFACE_DEFAULT_FORWARDER: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_INTERFACE_DEFAULT_FORWARDER")

internal val DOTNET_INTERFACE_DEFAULT_SLOT_BRIDGE: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_INTERFACE_DEFAULT_SLOT_BRIDGE")
internal val DOTNET_GENERIC_INTERFACE_DEFAULT_FORWARDER_TARGET: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_INTERFACE_DEFAULT_FORWARDER_TARGET")

internal val DOTNET_GENERIC_INTERFACE_DEFAULT_DECLARED_BODY: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_INTERFACE_DEFAULT_DECLARED_BODY")

internal val DOTNET_GENERIC_INTERFACE_DEFAULT_EXACT_BODY: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_INTERFACE_DEFAULT_EXACT_BODY")

internal val DOTNET_GENERIC_INTERFACE_DEFAULT_ERASED_ADAPTER: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_INTERFACE_DEFAULT_ERASED_ADAPTER")

internal val DOTNET_GENERIC_INTERFACE_DEFAULT_DECLARED_ADAPTER: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_INTERFACE_DEFAULT_DECLARED_ADAPTER")

internal val DOTNET_GENERIC_INTERFACE_DEFAULT_EXACT_ADAPTER: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_INTERFACE_DEFAULT_EXACT_ADAPTER")

internal val DOTNET_GENERIC_INTERFACE_DEFAULT_DECLARED_SLOT_ADAPTER: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_INTERFACE_DEFAULT_DECLARED_SLOT_ADAPTER")

internal val DOTNET_GENERIC_INTERFACE_DEFAULT_EXACT_SLOT_ADAPTER: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_GENERIC_INTERFACE_DEFAULT_EXACT_SLOT_ADAPTER")

internal val IrDeclarationOrigin.dotNetGenericInterfaceDefaultBodyViewOrNull: DotNetGenericInterfaceMemberView?
    get() = when (this) {
        DOTNET_GENERIC_INTERFACE_DEFAULT_DECLARED_BODY -> DotNetGenericInterfaceMemberView.DECLARED
        DOTNET_GENERIC_INTERFACE_DEFAULT_EXACT_BODY -> DotNetGenericInterfaceMemberView.EXACT
        else -> null
    }

internal val IrDeclarationOrigin.dotNetGenericInterfaceDefaultAdapterViewOrNull: DotNetGenericInterfaceMemberView?
    get() = when (this) {
        DOTNET_GENERIC_INTERFACE_DEFAULT_DECLARED_ADAPTER -> DotNetGenericInterfaceMemberView.DECLARED
        DOTNET_GENERIC_INTERFACE_DEFAULT_EXACT_ADAPTER -> DotNetGenericInterfaceMemberView.EXACT
        else -> null
    }

internal val IrDeclarationOrigin.dotNetGenericInterfaceDefaultSlotAdapterViewOrNull: DotNetGenericInterfaceMemberView?
    get() = when (this) {
        DOTNET_GENERIC_INTERFACE_DEFAULT_DECLARED_SLOT_ADAPTER -> DotNetGenericInterfaceMemberView.DECLARED
        DOTNET_GENERIC_INTERFACE_DEFAULT_EXACT_SLOT_ADAPTER -> DotNetGenericInterfaceMemberView.EXACT
        else -> null
    }

internal val IrDeclarationOrigin.isDotNetGenericInterfaceDefaultPhysicalMethod: Boolean
    get() = this == DOTNET_GENERIC_INTERFACE_DEFAULT_ERASED_ADAPTER ||
            dotNetGenericInterfaceDefaultBodyViewOrNull != null ||
            dotNetGenericInterfaceDefaultAdapterViewOrNull != null ||
            dotNetGenericInterfaceDefaultSlotAdapterViewOrNull != null


internal val DOTNET_INTERFACE_DEFAULT_EXACT_CALL: IrStatementOrigin =
    IrStatementOriginImpl("DOTNET_INTERFACE_DEFAULT_EXACT_CALL")
internal val DOTNET_GENERIC_INTERFACE_DEFAULT_VIRTUAL_CALL: IrStatementOrigin =
    IrStatementOriginImpl("DOTNET_GENERIC_INTERFACE_DEFAULT_VIRTUAL_CALL")

/**
 * Selects the physical representation of Kotlin-owned interface implementations per CLR profile.
 *
 * `net48` and `netstandard2.0` keep the CLR interface slot abstract and move the Kotlin body into
 * a public, marked `<DefaultImpls>` helper. A class whose effective Kotlin implementation is that
 * helper-only default receives a private explicit MethodImpl forwarder. `net10.0` keeps the body
 * on the interface as a DIM and retains the same helper signature; the helper performs an exact
 * nonvirtual call to that DIM, so ordinary calls remain virtual while `super<I>.f()` is exact.
 *
 * Masked default-argument dispatchers remain helper-owned on every profile and perform normal
 * virtual Kotlin dispatch. Common Kotlin rejects super calls that omit default arguments; this
 * lowering preserves that rule and never turns malformed qualified-super stub IR into a virtual call.
 *
 * A Kotlin-owned generic interface still has one body. Portable profiles place it in the stable
 * generic helper. On `net10.0` the erased canonical slot owns the DIM body; declared and exact
 * typed views contain generated virtual adapters which call that canonical slot. Implementing
 * classes likewise converge their canonical and typed MethodImpl bridges on one helper-forwarding
 * target rather than receiving independently lowered copies of the body.
 */
internal class DotNetInterfaceDefaultArgumentsLowering(
    private val context: DotNetBackendContext,
) : ModuleLoweringPass {
    override fun lower(irModule: IrModuleFragment) {
        val collected = collectDeclarations(irModule)
        val bodyPlacement = if (context.configuration.dotNetTarget == DotNetTarget.NET10_0) {
            DotNetInterfaceDefaultBodyPlacement.DIM_WITH_HELPER
        } else {
            DotNetInterfaceDefaultBodyPlacement.HELPER_ONLY
        }
        val externalDeclarations = DotNetExternalDeclarations(context.configuration.dotNetExternalLibraries)
        val localGenericInterfaces = collected.interfaces.filterTo(hashSetOf()) {
            it.isDotNetGenericInterfaceDeclaration
        }
        fun isKotlinOwnedGenericInterface(irClass: IrClass): Boolean =
            irClass in localGenericInterfaces ||
                    DotNetRuntimeTypes.genericInterfaceInfoFor(irClass) != null ||
                    externalDeclarations.declaredClassInfoOrNull(irClass) != null
        val externalBindings = linkedMapOf<IrSimpleFunction, ExternalDefaultBinding>()
        fun externalBindingFor(member: IrSimpleFunction): ExternalDefaultBinding? {
            externalBindings[member]?.let { return it }
            val bound = externalDeclarations.interfaceDefaultImplementationOrNull(member) ?: return null
            val owner = member.parent as? IrClass ?: return null
            return createExternalDefaultBinding(owner, member, bound).also { binding ->
                externalBindings[member] = binding
            }
        }

        val externalDefaultDispatcherReplacements = linkedMapOf<IrSimpleFunction, Replacement>()
        fun externalDefaultDispatcherReplacementFor(stub: IrSimpleFunction): Replacement? {
            externalDefaultDispatcherReplacements[stub]?.let { return it }
            if (stub.origin != IrDeclarationOrigin.FUNCTION_FOR_DEFAULT_PARAMETER) return null
            val original = stub.defaultArgumentsOriginalFunction as? IrSimpleFunction ?: return null
            val selected = original.resolveFakeOverride()
                ?: original.resolveFakeOverrideMaybeAbstract()
                ?: original
            val bound = externalDeclarations.defaultArgumentDispatcherOrNull(selected) ?: return null
            return createExternalDefaultArgumentDispatcherBinding(selected.parent, stub, bound).also { replacement ->
                externalDefaultDispatcherReplacements[stub] = replacement
            }
        }

        val defaultBindings = linkedMapOf<IrSimpleFunction, LocalDefaultBinding>()
        val genericDefaults = linkedMapOf<IrSimpleFunction, org.jetbrains.kotlin.backend.dotnet.DotNetLoweredGenericInterfaceDefault>()
        val defaultStubReplacements = mutableMapOf<IrSimpleFunctionSymbol, Replacement>()
        val helperPlans = mutableListOf<HelperPlan>()

        for (irInterface in collected.interfaces) {
            val members = irInterface.memberFunctions()
            val defaults = members.filter { function ->
                !function.isFakeOverride &&
                        function.origin != IrDeclarationOrigin.FUNCTION_FOR_DEFAULT_PARAMETER &&
                        function.body != null
            }
            val defaultStubs = members.filter { function ->
                !function.isFakeOverride &&
                        function.origin == IrDeclarationOrigin.FUNCTION_FOR_DEFAULT_PARAMETER &&
                        function.body != null
            }
            if (defaults.isEmpty() && defaultStubs.isEmpty()) continue

            val helper = createHelper(irInterface)
            val plan = HelperPlan(irInterface, helper)
            helperPlans += plan

            for (member in defaults) {
                val helperFunction = createHelperFunction(
                    owner = irInterface,
                    helper = helper,
                    source = member,
                    origin = DOTNET_INTERFACE_DEFAULT_HELPER,
                )
                helper.declarations += helperFunction
                val binding = LocalDefaultBinding(irInterface, member, helperFunction, bodyPlacement)
                defaultBindings[member] = binding
                context.interfaceDefaultImplementations[member] =
                    org.jetbrains.kotlin.backend.dotnet.DotNetLoweredInterfaceDefaultImplementation(
                        helperFunction,
                        bodyPlacement,
                    )
                if (bodyPlacement == DotNetInterfaceDefaultBodyPlacement.DIM_WITH_HELPER &&
                    irInterface.isDotNetGenericInterfaceDeclaration
                ) {
                    val genericDefault = createGenericInterfaceDefaultPhysicalMethods(
                        irInterface,
                        member,
                        ::isKotlinOwnedGenericInterface,
                    )
                    genericDefaults[member] = genericDefault
                    context.genericInterfaceDefaults += genericDefault
                }
                plan.defaults += binding
            }

            for (stub in defaultStubs) {
                val ordinaryHelper = createHelperFunction(
                    owner = irInterface,
                    helper = helper,
                    source = stub,
                    origin = stub.origin,
                )
                helper.declarations += ordinaryHelper
                val original = stub.defaultArgumentsOriginalFunction as? IrSimpleFunction
                    ?: error("Internal .NET backend error: interface default dispatcher has no original function")
                val previousDispatcher = context.defaultArgumentDispatchers.put(original, ordinaryHelper)
                check(previousDispatcher == null || previousDispatcher === stub) {
                    "Internal .NET backend error: interface member has multiple default-argument dispatchers"
                }
                defaultStubReplacements[stub.symbol] = Replacement(irInterface, ordinaryHelper)

                plan.defaultStubs += DefaultStubPlan(stub, ordinaryHelper)
            }
        }

        fun callRedirector(): IrElementTransformerVoid = object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)

                val superInterface = expression.superQualifierSymbol?.owner?.takeIf(IrClass::isInterface)
                defaultStubReplacements[expression.symbol]?.let { replacement ->
                    if (superInterface != null) return expression
                    return redirectCall(expression, replacement)
                }
                if (expression.symbol.owner.origin == IrDeclarationOrigin.FUNCTION_FOR_DEFAULT_PARAMETER) {
                    if (superInterface != null) return expression
                    externalDefaultDispatcherReplacementFor(expression.symbol.owner)?.let { replacement ->
                        return redirectCall(expression, replacement)
                    }
                }
                if (superInterface == null) return expression

                val selected = expression.symbol.owner.resolveFakeOverride()
                    ?: expression.symbol.owner.resolveFakeOverrideMaybeAbstract()
                    ?: expression.symbol.owner
                val binding = defaultBindings[selected]?.asDefaultCallBinding()
                    ?: externalBindingFor(selected)?.asDefaultCallBinding()
                    ?: return expression
                check(binding.owner == superInterface || superInterface.isSubclassOfInterface(binding.owner)) {
                    "Internal .NET backend error: qualified interface-super call resolved outside its qualifier hierarchy"
                }
                return redirectCall(expression, Replacement(binding.owner, binding.helper))
            }
        }

        // Redirect original IR before helper bodies are populated. The exact DIM calls introduced
        // below must remain inside the compatibility helper rather than being redirected to it.
        irModule.transformChildrenVoid(callRedirector())

        for (plan in helperPlans) {
            for (binding in plan.defaults) {
                when (binding.bodyPlacement) {
                    DotNetInterfaceDefaultBodyPlacement.HELPER_ONLY -> {
                        binding.helper.body = binding.member.moveBodyTo(binding.helper)?.also { body ->
                            remapHelperBodyTypes(plan.owner, binding.member, binding.helper, body)
                        }
                        binding.member.body = null
                        binding.member.modality = Modality.ABSTRACT
                    }
                    DotNetInterfaceDefaultBodyPlacement.DIM_WITH_HELPER -> {
                        val genericDefault = genericDefaults[binding.member]
                        if (genericDefault == null) {
                            binding.helper.body = createExactDimForwardingBody(binding, binding.member)
                        } else {
                            genericDefault.canonicalBody.body =
                                binding.member.moveBodyTo(genericDefault.canonicalBody)?.also { body ->
                                    remapSiblingBodyTypes(binding.member, genericDefault.canonicalBody, body)
                                }
                            binding.member.body = null
                            binding.member.modality = Modality.ABSTRACT
                            binding.helper.body =
                                createExactDimForwardingBody(binding, genericDefault.canonicalBody)
                        }
                    }
                }
            }
            for (entry in plan.defaultStubs) {
                entry.ordinaryHelper.body = entry.stub.moveBodyTo(entry.ordinaryHelper)?.also { body ->
                    remapHelperBodyTypes(plan.owner, entry.stub, entry.ordinaryHelper, body)
                }
            }
            if (bodyPlacement == DotNetInterfaceDefaultBodyPlacement.DIM_WITH_HELPER) {
                plan.defaults.forEach { binding ->
                    if (binding.member.inheritedInterfaceSlots().isNotEmpty()) {
                        val genericDefault = genericDefaults[binding.member]
                        if (genericDefault == null) {
                            createInterfaceSlotBridge(binding, ::isKotlinOwnedGenericInterface)
                        } else {
                            createGenericInterfaceSlotBridges(binding, genericDefault, ::isKotlinOwnedGenericInterface)
                        }
                    }
                }
            }
            plan.owner.declarations.removeAll(plan.defaultStubs.mapTo(hashSetOf()) { it.stub })
            plan.owner.declarations += plan.helper
        }

        if (bodyPlacement == DotNetInterfaceDefaultBodyPlacement.DIM_WITH_HELPER) {
            addExternalInterfacePromotions(collected.interfaces, externalDeclarations, ::externalBindingFor)
        }
        addRequiredClassForwarders(
            collected.classes,
            defaultBindings,
            bodyPlacement,
            externalDeclarations,
            ::externalBindingFor,
        )
    }

    private fun collectDeclarations(irModule: IrModuleFragment): CollectedDeclarations {
        val interfaces = mutableListOf<IrClass>()
        val classes = mutableListOf<IrClass>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                if (declaration.isInterface) interfaces += declaration else classes += declaration
                declaration.acceptChildrenVoid(this)
            }
        })
        return CollectedDeclarations(interfaces, classes)
    }

    private fun createHelperFunction(
        owner: IrClass,
        helper: IrClass,
        source: IrSimpleFunction,
        origin: IrDeclarationOrigin,
    ): IrSimpleFunction = context.irFactory.createStaticFunctionWithReceivers(
        irParent = helper,
        name = source.name,
        oldFunction = source,
        dispatchReceiverType = owner.symbol.defaultType,
        origin = origin,
        modality = Modality.FINAL,
        visibility = DescriptorVisibilities.PUBLIC,
        isFakeOverride = false,
        typeParametersFromContext = owner.typeParameters,
    ).also { helperFunction ->
        // CLR variance is declaration-site metadata only on interfaces and delegates. The copied
        // owner slots are ordinary invariant method parameters on the static helper.
        helperFunction.typeParameters.take(owner.typeParameters.size).forEach {
            it.variance = Variance.INVARIANT
        }
        source.typeParameters
            .zip(helperFunction.typeParameters.drop(owner.typeParameters.size))
            .filter { pair ->
                pair.first.superTypes.any { it.isDotNetOwnerDependentConstraint(owner) }
            }
            .forEach { pair ->
                pair.second.origin = DOTNET_ERASED_OWNER_RELATIONAL_CONSTRAINT_TYPE_PARAMETER
            }
    }

    private fun remapHelperBodyTypes(
        owner: IrClass,
        source: IrSimpleFunction,
        helper: IrSimpleFunction,
        body: org.jetbrains.kotlin.ir.expressions.IrBody,
    ) {
        val typeParameterMap = mutableMapOf<IrTypeParameter, IrTypeParameter>()
        owner.typeParameters.zip(helper.typeParameters.take(owner.typeParameters.size)).forEach { pair ->
            typeParameterMap[pair.first] = pair.second
        }
        source.typeParameters.zip(helper.typeParameters.drop(owner.typeParameters.size)).forEach { pair ->
            typeParameterMap[pair.first] = pair.second
        }
        body.remapTypes(IrTypeParameterRemapper(typeParameterMap))
    }

    /**
     * Splits one modern generic default into one strongly typed semantic DIM and adapters.
     * The erased identity slot remains abstract. A hidden MethodImpl on the typed body owner
     * implements that erased slot by virtually invoking the canonical typed body; any secondary
     * typed view does the same. No adapter owns a lowered copy of the Kotlin body.
     */
    private fun createGenericInterfaceDefaultPhysicalMethods(
        owner: IrClass,
        source: IrSimpleFunction,
        isKotlinOwnedGenericInterface: (IrClass) -> Boolean,
    ): org.jetbrains.kotlin.backend.dotnet.DotNetLoweredGenericInterfaceDefault {
        val canonicalView = source.dotNetGenericInterfaceMemberView(owner, isKotlinOwnedGenericInterface)
        val allTypedViews = source.dotNetGenericInterfaceMemberViews(owner, isKotlinOwnedGenericInterface)
        val interfaceIdentity = owner.fqNameWhenAvailable?.asString() ?: owner.name.asString()
        val slotIdentity = source.dotNetGenericInterfaceCanonicalSlotId()

        fun createTypedSibling(
            origin: IrDeclarationOrigin,
            role: String,
        ): IrSimpleFunction = owner.addFunction {
            startOffset = source.startOffset
            endOffset = source.endOffset
            this.origin = origin
            name = Name.special(
                "<GenericInterfaceDefault$role-$interfaceIdentity-${source.name.asString()}-$slotIdentity>"
            )
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.OPEN
            returnType = source.returnType
        }.apply sibling@{
            overriddenSymbols = listOf(source.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            val siblingTypeParameters = copyTypeParametersFrom(source)
            val methodSubstitution = source.typeParameters.zip(siblingTypeParameters).associate { pair ->
                pair.first.symbol to pair.second.symbol.defaultType
            }
            val methodSubstitutor = IrTypeSubstitutor(methodSubstitution, allowEmptySubstitution = true)
            fun siblingType(type: IrType): IrType = methodSubstitutor.substitute(type)

            returnType = siblingType(source.returnType)
            source.parameters
                .dropWhile { it.kind == IrParameterKind.DispatchReceiver }
                .forEach { parameter ->
                    parameters += parameter.copyTo(
                        this,
                        type = siblingType(parameter.type),
                        defaultValue = null,
                    )
                }
        }

        val canonicalBody = createTypedSibling(
            origin = when (canonicalView) {
                DotNetGenericInterfaceMemberView.DECLARED -> DOTNET_GENERIC_INTERFACE_DEFAULT_DECLARED_BODY
                DotNetGenericInterfaceMemberView.EXACT -> DOTNET_GENERIC_INTERFACE_DEFAULT_EXACT_BODY
            },
            role = "CanonicalBody",
        )

        fun createTypedAdapter(view: DotNetGenericInterfaceMemberView): IrSimpleFunction {
            val adapter = createTypedSibling(
                origin = when (view) {
                    DotNetGenericInterfaceMemberView.DECLARED ->
                        DOTNET_GENERIC_INTERFACE_DEFAULT_DECLARED_ADAPTER
                    DotNetGenericInterfaceMemberView.EXACT ->
                        DOTNET_GENERIC_INTERFACE_DEFAULT_EXACT_ADAPTER
                },
                role = "${view.name.lowercase().replaceFirstChar(Char::uppercaseChar)}Adapter",
            )
            adapter.body = context.createIrBuilder(adapter.symbol).irBlockBody {
                val call = irCall(
                    canonicalBody.symbol,
                    adapter.returnType,
                    origin = DOTNET_GENERIC_INTERFACE_DEFAULT_VIRTUAL_CALL,
                ).apply {
                    arguments[0] = irGet(adapter.parameters[0])
                    adapter.typeParameters.forEachIndexed { index, parameter ->
                        typeArguments[index] = parameter.symbol.defaultType
                    }
                    adapter.parameters.drop(1).forEachIndexed { index, parameter ->
                        arguments[index + 1] = irGet(parameter)
                    }
                }
                +irReturn(call)
            }
            return adapter
        }

        fun IrType.referencesOwnerParameter(): Boolean {
            val simpleType = this as? IrSimpleType ?: return false
            val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
            if (parameter?.parent == owner) return true
            return simpleType.arguments.any { argument ->
                (argument as? org.jetbrains.kotlin.ir.types.IrTypeProjection)?.type?.referencesOwnerParameter() == true
            }
        }
        val canonicalSubstitutor = IrTypeSubstitutor(
            owner.typeParameters.associate { parameter ->
                parameter.symbol to context.irBuiltIns.anyNType
            },
            allowEmptySubstitution = true,
        )
        fun canonicalType(type: IrType): IrType {
            if (!type.referencesOwnerParameter()) return type
            val simpleType = type as? IrSimpleType ?: return context.irBuiltIns.anyNType
            val directParameter = simpleType.classifier as? IrTypeParameterSymbol
            if (directParameter?.owner?.parent == owner) return context.irBuiltIns.anyNType
            val carrier = (simpleType.classifier as? IrClassSymbol)?.owner
            return if (carrier?.let(isKotlinOwnedGenericInterface) == true) {
                canonicalSubstitutor.substitute(type)
            } else {
                context.irBuiltIns.anyNType
            }
        }

        val erasedAdapter = owner.addFunction {
            startOffset = source.startOffset
            endOffset = source.endOffset
            origin = DOTNET_GENERIC_INTERFACE_DEFAULT_ERASED_ADAPTER
            name = Name.special(
                "<GenericInterfaceDefaultErasedAdapter-$interfaceIdentity-${source.name.asString()}-$slotIdentity>"
            )
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            returnType = context.irBuiltIns.anyNType
        }.apply adapter@{
            overriddenSymbols = listOf(source.symbol) + source.inheritedInterfaceSlots()
            parameters += createDispatchReceiverParameterWithClassParent()
            val adapterTypeParameters = copyTypeParametersFrom(source)
            val methodSubstitution = source.typeParameters.zip(adapterTypeParameters).associate { pair ->
                pair.first.symbol to pair.second.symbol.defaultType
            }
            val methodSubstitutor = IrTypeSubstitutor(methodSubstitution, allowEmptySubstitution = true)
            fun erasedType(type: IrType): IrType = methodSubstitutor.substitute(canonicalType(type))
            fun typedType(type: IrType): IrType = methodSubstitutor.substitute(type)

            adapterTypeParameters.forEach { parameter ->
                parameter.superTypes = parameter.superTypes.map(::erasedType)
            }
            returnType = erasedType(source.returnType)
            source.parameters
                .dropWhile { it.kind == IrParameterKind.DispatchReceiver }
                .forEach { parameter ->
                    parameters += parameter.copyTo(
                        this,
                        type = erasedType(parameter.type),
                        defaultValue = null,
                    )
                }
            body = context.createIrBuilder(symbol).irBlockBody {
                val call = irCall(
                    canonicalBody.symbol,
                    typedType(source.returnType),
                    origin = DOTNET_GENERIC_INTERFACE_DEFAULT_VIRTUAL_CALL,
                ).apply {
                    arguments[0] = irGet(this@adapter.parameters[0])
                    adapterTypeParameters.forEachIndexed { index, parameter ->
                        typeArguments[index] = parameter.symbol.defaultType
                    }
                    this@adapter.parameters.drop(1).forEachIndexed { index, parameter ->
                        val argument = irGet(parameter)
                        val targetType = typedType(
                            source.parameters.dropWhile { it.kind == IrParameterKind.DispatchReceiver }[index].type
                        )
                        arguments[index + 1] = if (argument.type == targetType) {
                            argument
                        } else {
                            irImplicitCast(argument, targetType)
                        }
                    }
                }
                val result = if (call.type == this@adapter.returnType) {
                    call
                } else {
                    irImplicitCast(call, this@adapter.returnType)
                }
                +irReturn(result)
            }
        }

        val typedAdapters = allTypedViews
            .filter { it != canonicalView }
            .associateWith(::createTypedAdapter)
        return org.jetbrains.kotlin.backend.dotnet.DotNetLoweredGenericInterfaceDefault(
            source = source,
            canonicalBody = canonicalBody,
            canonicalView = canonicalView,
            erasedAdapter = erasedAdapter,
            typedAdapters = typedAdapters,
        )
    }
    private fun remapSiblingBodyTypes(
        source: IrSimpleFunction,
        target: IrSimpleFunction,
        body: org.jetbrains.kotlin.ir.expressions.IrBody,
    ) {
        val typeParameterMap = source.typeParameters.zip(target.typeParameters).associate { pair ->
            pair.first to pair.second
        }
        body.remapTypes(IrTypeParameterRemapper(typeParameterMap))
    }

    private fun createExactDimForwardingBody(
        binding: LocalDefaultBinding,
        canonicalBody: IrSimpleFunction,
    ) = context.createIrBuilder(binding.helper.symbol).irBlockBody {
            val call = irCall(
                canonicalBody,
                origin = DOTNET_INTERFACE_DEFAULT_EXACT_CALL,
                superQualifierSymbol = binding.owner.symbol,
            ).apply {
                binding.helper.typeParameters
                    .drop(binding.owner.typeParameters.size)
                    .forEachIndexed { index, parameter ->
                        typeArguments[index] = parameter.symbol.defaultType
                    }
                binding.helper.parameters.forEachIndexed { index, parameter ->
                    arguments[index] = irGet(parameter)
                }
            }
            +irReturn(call)
        }

    private fun createExternalDefaultBinding(
        owner: IrClass,
        member: IrSimpleFunction,
        bound: DotNetBoundInterfaceDefaultImplementation,
    ): ExternalDefaultBinding {
        val helperOwner = context.irFactory.buildClass {
            startOffset = member.startOffset
            endOffset = member.endOffset
            origin = DOTNET_DEFAULT_IMPLS
            name = Name.guessByFirstCharacter(bound.implementation.helperOwnerPath.last())
            kind = ClassKind.CLASS
            modality = Modality.FINAL
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            parent = owner
            createThisReceiverParameter()
        }
        val helper = createHelperFunction(
            owner = owner,
            helper = helperOwner,
            source = member,
            origin = DOTNET_INTERFACE_DEFAULT_HELPER,
        )
        context.externalInterfaceDefaultHelpers[helper] = bound
        return ExternalDefaultBinding(owner, member, helper, bound)
    }

    private fun createExternalDefaultArgumentDispatcherBinding(
        semanticParent: IrDeclarationParent,
        stub: IrSimpleFunction,
        bound: DotNetBoundDefaultArgumentDispatcher,
    ): Replacement {
        val helperOwner = context.irFactory.buildClass {
            startOffset = stub.startOffset
            endOffset = stub.endOffset
            origin = DOTNET_DEFAULT_IMPLS
            name = Name.guessByFirstCharacter(bound.dispatcher.ownerPath.last())
            kind = ClassKind.CLASS
            modality = Modality.FINAL
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            parent = semanticParent
            createThisReceiverParameter()
        }
        val helper = context.irFactory.createStaticFunctionWithReceivers(
            irParent = helperOwner,
            name = stub.name,
            oldFunction = stub,
            dispatchReceiverType = null,
            origin = stub.origin,
            modality = Modality.FINAL,
            visibility = DescriptorVisibilities.PUBLIC,
            isFakeOverride = false,
        )
        context.externalDefaultArgumentDispatchers[helper] = bound
        return Replacement(typeContextOwner = null, function = helper)
    }

    private fun addExternalInterfacePromotions(
        interfaces: List<IrClass>,
        externalDeclarations: DotNetExternalDeclarations,
        externalBindingFor: (IrSimpleFunction) -> ExternalDefaultBinding?,
    ) {
        for (irInterface in interfaces.sortedBy { it.interfaceInheritanceDepth() }) {
            for (fakeOverride in irInterface.fakeOverrideFunctions()) {
                val selected = fakeOverride.resolveFakeOverride()
                    ?: fakeOverride.resolveFakeOverrideMaybeAbstract()
                    ?: continue
                val binding = externalBindingFor(selected) ?: continue
                if (binding.bound.implementation.bodyPlacement != DotNetInterfaceDefaultBodyPlacement.HELPER_ONLY) continue
                val providers = irInterface.directInterfaces().flatMapTo(linkedSetOf()) { superInterface ->
                    promotionProvidersInHierarchy(
                        superInterface,
                        selected,
                        externalDeclarations,
                        hashSetOf(),
                    )
                }.mostSpecificInterfaceProviders()
                // Zero providers means this interface must promote the portable helper. Multiple
                // incomparable providers would be ambiguous to the CLR even though Kotlin selected
                // their common logical default, so this interface must resolve them with a new DIM.
                if (providers.size != 1) {
                    if (binding.owner.isDotNetGenericInterfaceDeclaration) {
                        context.interfaceDefaultPromotions += createExternalGenericPromotions(
                            irInterface,
                            fakeOverride,
                            binding,
                            externalDeclarations,
                        )
                    } else {
                        val bridge = createExternalPromotionBridge(irInterface, fakeOverride, binding)
                        context.interfaceDefaultPromotions += DotNetLoweredInterfaceDefaultPromotion(
                            owner = irInterface,
                            inheritedMember = binding.member,
                            implementation = bridge,
                            inheritedDefault = binding.bound,
                        )
                    }
                }
            }
        }
    }

    private fun addRequiredClassForwarders(
        classes: List<IrClass>,
        localBindings: Map<IrSimpleFunction, LocalDefaultBinding>,
        bodyPlacement: DotNetInterfaceDefaultBodyPlacement,
        externalDeclarations: DotNetExternalDeclarations,
        externalBindingFor: (IrSimpleFunction) -> ExternalDefaultBinding?,
    ) {
        val forwardedByClass = mutableMapOf<IrClass, MutableSet<IrSimpleFunction>>()
        for (irClass in classes.sortedBy { it.classInheritanceDepth() }) {
            val inherited = irClass.baseClassOrNull()
                ?.let { forwardedByClass[it] }
                ?.toMutableSet()
                ?: linkedSetOf()
            forwardedByClass[irClass] = inherited

            for (fakeOverride in irClass.fakeOverrideFunctions()) {
                val selected = fakeOverride.resolveFakeOverride()
                    ?: fakeOverride.resolveFakeOverrideMaybeAbstract()
                    ?: continue
                val selectedAncestors = selected.allOverridden().toSet()
                val inheritedForwarderMasksSelectedDefault =
                    selectedAncestors.any(inherited::contains) ||
                            hasExternalBaseClassForwarderFor(
                                irClass,
                                selectedAncestors,
                                externalDeclarations,
                            )
                val local = localBindings[selected]
                val binding = when {
                    local != null && bodyPlacement == DotNetInterfaceDefaultBodyPlacement.HELPER_ONLY ->
                        local.asDefaultCallBinding()
                    local != null ->
                        if (inheritedForwarderMasksSelectedDefault) local.asDefaultCallBinding() else null
                    else -> {
                        val external = externalBindingFor(selected) ?: continue
                        when (external.bound.implementation.bodyPlacement) {
                            DotNetInterfaceDefaultBodyPlacement.DIM_WITH_HELPER ->
                                if (inheritedForwarderMasksSelectedDefault) external.asDefaultCallBinding() else null
                            DotNetInterfaceDefaultBodyPlacement.HELPER_ONLY -> {
                                val providers = if (bodyPlacement == DotNetInterfaceDefaultBodyPlacement.DIM_WITH_HELPER) {
                                    irClass.directInterfaces().flatMapTo(linkedSetOf()) { interfaceClass ->
                                        promotionProvidersInHierarchy(
                                            interfaceClass,
                                            selected,
                                            externalDeclarations,
                                            hashSetOf(),
                                        )
                                    }.mostSpecificInterfaceProviders()
                                } else emptySet()
                                // Exactly one most-specific provider is a selected physical DIM.
                                // Zero still needs the portable helper; multiple are CLR-ambiguous
                                // and need this class to resolve Kotlin's common logical default.
                                if (providers.size == 1 && !inheritedForwarderMasksSelectedDefault) null
                                else external.asDefaultCallBinding()
                            }
                        }
                    }
                } ?: continue
                val sameDefaultAlreadyForwarded =
                    binding.member in inherited ||
                            hasExternalBaseClassForwarderFor(
                                irClass,
                                setOf(binding.member),
                                externalDeclarations,
                            )
                // A class MethodImpl for this exact logical default already executes Kotlin's
                // selected body. Only an ancestor-default MethodImpl requires the resolver path
                // selected above.
                if (sameDefaultAlreadyForwarded) continue
                if (!inherited.add(binding.member)) continue
                createClassForwarder(irClass, binding)
            }
        }
    }

    private fun hasExternalBaseClassForwarderFor(
        irClass: IrClass,
        inheritedMembers: Set<IrSimpleFunction>,
        externalDeclarations: DotNetExternalDeclarations,
    ): Boolean {
        if (inheritedMembers.isEmpty()) return false
        val visited = hashSetOf<IrClass>()
        var baseClass = irClass.baseClassOrNull()
        while (baseClass != null && visited.add(baseClass)) {
            val hasForwarder = inheritedMembers.any { inheritedMember ->
                externalDeclarations.interfaceDefaultClassForwarderOrNull(baseClass, inheritedMember) != null
            }
            if (hasForwarder) return true
            baseClass = baseClass.baseClassOrNull()
        }
        return false
    }

    private fun promotionProvidersInHierarchy(
        irInterface: IrClass,
        selected: IrSimpleFunction,
        externalDeclarations: DotNetExternalDeclarations,
        visited: MutableSet<IrClass>,
    ): Set<IrClass> {
        if (!visited.add(irInterface)) return emptySet()
        if (context.interfaceDefaultPromotions.any { promotion ->
                promotion.owner == irInterface && promotion.inheritedMember == selected
            }
        ) return setOf(irInterface)
        if (externalDeclarations.interfaceDefaultPromotionOrNull(irInterface, selected) != null) {
            return setOf(irInterface)
        }
        return irInterface.directInterfaces().flatMapTo(linkedSetOf()) { superInterface ->
            promotionProvidersInHierarchy(
                superInterface,
                selected,
                externalDeclarations,
                visited,
            )
        }
    }

    private fun Set<IrClass>.mostSpecificInterfaceProviders(): Set<IrClass> =
        filterTo(linkedSetOf()) { candidate ->
            none { other -> other != candidate && other.isSubclassOfInterface(candidate) }
        }

    /**
     * Promotes a portable generic default without copying its semantic body. Every emitted DIM is
     * a final MethodImpl adapter which calls the producer-recorded helper with the derived
     * interface's owner arguments. The canonical adapter erases only its own physical ABI; the
     * declared/exact adapters retain their strongly typed signatures.
     */
    private fun createExternalGenericPromotions(
        owner: IrClass,
        fakeOverride: IrSimpleFunction,
        binding: ExternalDefaultBinding,
        externalDeclarations: DotNetExternalDeclarations,
    ): List<DotNetLoweredInterfaceDefaultPromotion> {
        val inheritedOwner = binding.owner
        val member = binding.member
        val ownerSubstitutor = AbstractIrTypeSubstitutor.forSuperClass(
            inheritedOwner.symbol,
            owner.symbol.defaultType,
        ) ?: error(
            "Internal .NET backend error: '${owner.name}' is not a subtype of " +
                    "generic interface '${inheritedOwner.name}'"
        )
        fun isKotlinOwnedGenericInterface(candidate: IrClass): Boolean =
            candidate == owner || candidate == inheritedOwner ||
                    DotNetRuntimeTypes.genericInterfaceInfoFor(candidate) != null ||
                    externalDeclarations.declaredClassInfoOrNull(candidate) != null

        fun IrType.referencesInheritedOwnerParameter(): Boolean {
            val simpleType = this as? IrSimpleType ?: return false
            val parameter = (simpleType.classifier as? IrTypeParameterSymbol)?.owner
            if (parameter?.parent == inheritedOwner) return true
            return simpleType.arguments.any { argument ->
                (argument as? org.jetbrains.kotlin.ir.types.IrTypeProjection)
                    ?.type
                    ?.referencesInheritedOwnerParameter() == true
            }
        }
        val canonicalSubstitutor = IrTypeSubstitutor(
            inheritedOwner.typeParameters.associate { parameter ->
                parameter.symbol to context.irBuiltIns.anyNType
            },
            allowEmptySubstitution = true,
        )
        fun canonicalType(type: IrType): IrType {
            if (!type.referencesInheritedOwnerParameter()) return type
            val simpleType = type as? IrSimpleType ?: return context.irBuiltIns.anyNType
            val directParameter = simpleType.classifier as? IrTypeParameterSymbol
            if (directParameter?.owner?.parent == inheritedOwner) return context.irBuiltIns.anyNType
            val carrier = (simpleType.classifier as? IrClassSymbol)?.owner
            return if (carrier?.let(::isKotlinOwnedGenericInterface) == true) {
                canonicalSubstitutor.substitute(type)
            } else {
                context.irBuiltIns.anyNType
            }
        }

        val interfaceIdentity = owner.fqNameWhenAvailable?.asString() ?: owner.name.asString()
        val memberIdentity = member.dotNetGenericInterfaceCanonicalSlotId()
        val memberParameters = member.parameters.dropWhile { it.kind == IrParameterKind.DispatchReceiver }
        val allSlots = (listOf(member.symbol) + member.inheritedInterfaceSlots()).distinct()

        fun createAdapter(
            origin: IrDeclarationOrigin,
            role: String,
            implementationView: DotNetGenericInterfaceMemberView,
            physicalView: DotNetInterfaceDefaultPromotionView,
            overriddenSymbols: List<IrSimpleFunctionSymbol>,
            signatureTransform: (IrType) -> IrType,
        ): DotNetLoweredInterfaceDefaultPromotion {
            val adapter = owner.addFunction {
                startOffset = fakeOverride.startOffset
                endOffset = fakeOverride.endOffset
                this.origin = origin
                name = Name.special(
                    "<GenericInterfaceDefaultPromotion$role-$interfaceIdentity-" +
                            "${member.name.asString()}-$memberIdentity>"
                )
                visibility = DescriptorVisibilities.PRIVATE
                modality = Modality.FINAL
                returnType = member.returnType
            }.apply adapter@{
                this.overriddenSymbols = overriddenSymbols
                parameters += createDispatchReceiverParameterWithClassParent()
                val adapterTypeParameters = copyTypeParametersFrom(member)
                val methodSubstitution = member.typeParameters.zip(adapterTypeParameters).associate { pair ->
                    pair.first.symbol to pair.second.symbol.defaultType
                }
                val methodSubstitutor = IrTypeSubstitutor(methodSubstitution, allowEmptySubstitution = true)
                fun adapterType(type: IrType): IrType = methodSubstitutor.substitute(signatureTransform(type))
                fun targetType(type: IrType): IrType = methodSubstitutor.substitute(
                    ownerSubstitutor.substitute(type)
                )

                adapterTypeParameters.forEachIndexed { index, parameter ->
                    parameter.superTypes = member.typeParameters[index].superTypes
                        .filterNot { it.isDotNetOwnerDependentConstraint(inheritedOwner) }
                        .map(::adapterType)
                            .ifEmpty { listOf(context.irBuiltIns.anyNType) }
                }
                returnType = adapterType(member.returnType)
                memberParameters.forEach { parameter ->
                    parameters += parameter.copyTo(
                        this,
                        type = adapterType(parameter.type),
                        defaultValue = null,
                    )
                }
                val targetParameterTypes = memberParameters.map { parameter -> targetType(parameter.type) }
                val targetReturnType = targetType(member.returnType)
                body = context.createIrBuilder(symbol).irBlockBody {
                    val call = irCall(binding.helper.symbol, targetReturnType).apply {
                        check(
                            binding.helper.typeParameters.size ==
                                    inheritedOwner.typeParameters.size + adapterTypeParameters.size
                        ) {
                            "Internal .NET backend error: generic promotion helper arity mismatch"
                        }
                        inheritedOwner.typeParameters.forEachIndexed { index, parameter ->
                            typeArguments[index] = ownerSubstitutor.substitute(parameter.defaultType)
                        }
                        adapterTypeParameters.forEachIndexed { index, parameter ->
                            typeArguments[inheritedOwner.typeParameters.size + index] =
                                parameter.symbol.defaultType
                        }
                        arguments[0] = irGet(this@adapter.parameters[0])
                        this@adapter.parameters.drop(1).forEachIndexed { index, parameter ->
                            val argument = irGet(parameter)
                            arguments[index + 1] = if (argument.type == targetParameterTypes[index]) {
                                argument
                            } else {
                                irImplicitCast(argument, targetParameterTypes[index])
                            }
                        }
                    }
                    val result = if (call.type == this@adapter.returnType) {
                        call
                    } else {
                        irImplicitCast(call, this@adapter.returnType)
                    }
                    +irReturn(result)
                }
            }
            return DotNetLoweredInterfaceDefaultPromotion(
                owner = owner,
                inheritedMember = member,
                implementation = adapter,
                inheritedDefault = binding.bound,
                physicalView = physicalView,
                implementationView = implementationView,
            )
        }

        val canonicalView = member.dotNetGenericInterfaceMemberView(
            inheritedOwner,
            ::isKotlinOwnedGenericInterface,
        )
        val result = mutableListOf(
            createAdapter(
                origin = DOTNET_GENERIC_INTERFACE_DEFAULT_ERASED_ADAPTER,
                role = "Canonical",
                implementationView = canonicalView,
                physicalView = DotNetInterfaceDefaultPromotionView.CANONICAL,
                overriddenSymbols = allSlots,
                signatureTransform = ::canonicalType,
            )
        )
        for (view in member.dotNetGenericInterfaceMemberViews(
            inheritedOwner,
            ::isKotlinOwnedGenericInterface,
        )) {
            val implementationView = if (canonicalView == DotNetGenericInterfaceMemberView.EXACT) {
                DotNetGenericInterfaceMemberView.EXACT
            } else {
                view
            }
            result += createAdapter(
                origin = when (view) {
                    DotNetGenericInterfaceMemberView.DECLARED ->
                        DOTNET_GENERIC_INTERFACE_DEFAULT_DECLARED_SLOT_ADAPTER
                    DotNetGenericInterfaceMemberView.EXACT ->
                        DOTNET_GENERIC_INTERFACE_DEFAULT_EXACT_SLOT_ADAPTER
                },
                role = view.name.lowercase().replaceFirstChar(Char::uppercaseChar),
                implementationView = implementationView,
                physicalView = when (view) {
                    DotNetGenericInterfaceMemberView.DECLARED ->
                        DotNetInterfaceDefaultPromotionView.DECLARED
                    DotNetGenericInterfaceMemberView.EXACT ->
                        DotNetInterfaceDefaultPromotionView.EXACT
                },
                overriddenSymbols = listOf(member.symbol),
                signatureTransform = ownerSubstitutor::substitute,
            )
        }
        return result
    }
    private fun createExternalPromotionBridge(
        owner: IrClass,
        fakeOverride: IrSimpleFunction,
        binding: ExternalDefaultBinding,
    ): IrSimpleFunction {
        val interfaceIdentity = owner.fqNameWhenAvailable?.asString() ?: owner.name.asString()
        return owner.addFunction {
            startOffset = fakeOverride.startOffset
            endOffset = fakeOverride.endOffset
            origin = DOTNET_INTERFACE_DEFAULT_SLOT_BRIDGE
            name = Name.special(
                "<InterfaceDefaultPromotion-$interfaceIdentity-${binding.member.name.asString()}-" +
                        "${binding.member.dotNetGenericInterfaceCanonicalSlotId()}>"
            )
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            returnType = fakeOverride.returnType
        }.apply bridge@{
            overriddenSymbols = listOf(binding.member.symbol) + binding.member.inheritedInterfaceSlots()
            parameters += createDispatchReceiverParameterWithClassParent()
            fakeOverride.parameters
                .dropWhile { it.kind == IrParameterKind.DispatchReceiver }
                .forEach { parameter -> parameters += parameter.copyTo(this, defaultValue = null) }
            body = context.createIrBuilder(symbol).irBlockBody {
                val call = irCall(binding.helper.symbol, returnType).apply {
                    this@bridge.parameters.forEachIndexed { index, parameter -> arguments[index] = irGet(parameter) }
                }
                +irReturn(call)
            }
        }
    }

    private fun createClassForwarder(irClass: IrClass, binding: DefaultCallBinding) {
        val member = binding.member
        if (binding.owner.isDotNetGenericInterfaceDeclaration) {
            createGenericInterfaceDefaultForwarderTarget(irClass, binding)
            return
        }
        val interfaceIdentity = binding.owner.fqNameWhenAvailable?.asString()
            ?: binding.owner.name.asString()
        val forwarder = irClass.addFunction {
            startOffset = member.startOffset
            endOffset = member.endOffset
            origin = DOTNET_INTERFACE_DEFAULT_FORWARDER
            name = Name.special(
                "<InterfaceDefaultForwarder-$interfaceIdentity-${member.name.asString()}-" +
                        "${member.dotNetGenericInterfaceCanonicalSlotId()}>"
            )
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            returnType = member.returnType
        }.apply forwarder@{
            overriddenSymbols = listOf(member.symbol) + member.inheritedInterfaceSlots()
            parameters += createDispatchReceiverParameterWithClassParent()
            val forwarderTypeParameters = copyTypeParametersFrom(member)
            val methodSubstitution = member.typeParameters.zip(forwarderTypeParameters).associate { pair ->
                pair.first.symbol to pair.second.symbol.defaultType
            }
            val methodSubstitutor = IrTypeSubstitutor(methodSubstitution, allowEmptySubstitution = true)
            fun forwarderType(type: IrType): IrType = methodSubstitutor.substitute(type)

            returnType = forwarderType(member.returnType)
            member.parameters
                .dropWhile { it.kind == IrParameterKind.DispatchReceiver }
                .forEach { parameter ->
                    parameters += parameter.copyTo(
                        this,
                        type = forwarderType(parameter.type),
                        defaultValue = null,
                    )
                }
            body = context.createIrBuilder(symbol).irBlockBody {
                val call = irCall(binding.helper.symbol, returnType).apply {
                    forwarderTypeParameters.forEachIndexed { index, parameter ->
                        typeArguments[index] = parameter.symbol.defaultType
                    }
                    this@forwarder.parameters.forEachIndexed { index, parameter ->
                        arguments[index] = irGet(parameter)
                    }
                }
                +irReturn(call)
            }
        }
        context.interfaceDefaultClassForwarders += DotNetLoweredInterfaceDefaultClassForwarder(
            owner = irClass,
            inheritedMember = member,
            implementation = forwarder,
        )
    }

    /**
     * One hidden class-side target for a helper-backed generic-interface default. The ordinary
     * generic-interface lowering maps canonical and typed physical slots to this function; it is
     * not itself a third implementation of the Kotlin body.
     */
    private fun createGenericInterfaceDefaultForwarderTarget(
        irClass: IrClass,
        binding: DefaultCallBinding,
    ) {
        val owner = binding.owner
        val member = binding.member
        val ownerSubstitutor = AbstractIrTypeSubstitutor.forSuperClass(owner.symbol, irClass.symbol.defaultType)
            ?: error(
                "Internal .NET backend error: '${irClass.name}' is not a subtype of " +
                        "generic interface '${owner.name}'"
            )
        val interfaceIdentity = owner.fqNameWhenAvailable?.asString() ?: owner.name.asString()
        val forwarder = irClass.addFunction {
            startOffset = member.startOffset
            endOffset = member.endOffset
            origin = DOTNET_GENERIC_INTERFACE_DEFAULT_FORWARDER_TARGET
            name = Name.special(
                "<GenericInterfaceDefaultForwarderTarget-$interfaceIdentity-${member.name.asString()}-" +
                        "${member.dotNetGenericInterfaceCanonicalSlotId()}>"
            )
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            returnType = member.returnType
        }.apply forwarder@{
            overriddenSymbols = listOf(member.symbol) + member.inheritedInterfaceSlots()
            parameters += createDispatchReceiverParameterWithClassParent()
            val forwarderTypeParameters = copyTypeParametersFrom(member)
            val methodSubstitution = member.typeParameters.zip(forwarderTypeParameters).associate { pair ->
                pair.first.symbol to pair.second.symbol.defaultType
            }
            val methodSubstitutor = IrTypeSubstitutor(methodSubstitution, allowEmptySubstitution = true)
            fun forwarderType(type: IrType): IrType =
                methodSubstitutor.substitute(ownerSubstitutor.substitute(type))

            forwarderTypeParameters.forEachIndexed { index, parameter ->
                parameter.superTypes = member.typeParameters[index].superTypes
                    .filterNot { it.isDotNetOwnerDependentConstraint(owner) }
                    .map(::forwarderType)
                    .ifEmpty { listOf(context.irBuiltIns.anyNType) }
            }
            returnType = forwarderType(member.returnType)
            member.parameters
                .dropWhile { it.kind == IrParameterKind.DispatchReceiver }
                .forEach { parameter ->
                    parameters += parameter.copyTo(
                        this,
                        type = forwarderType(parameter.type),
                        defaultValue = null,
                    )
                }
            body = context.createIrBuilder(symbol).irBlockBody {
                val call = irCall(binding.helper.symbol, this@forwarder.returnType).apply {
                    arguments[0] = irGet(this@forwarder.parameters[0])
                    owner.typeParameters.forEachIndexed { index, parameter ->
                        typeArguments[index] = forwarderType(parameter.defaultType)
                    }
                    forwarderTypeParameters.forEachIndexed { index, parameter ->
                        typeArguments[owner.typeParameters.size + index] = parameter.symbol.defaultType
                    }
                    this@forwarder.parameters.drop(1).forEachIndexed { index, parameter ->
                        arguments[index + 1] = irGet(parameter)
                    }
                }
                +irReturn(call)
            }
        }
        // This canonical placeholder lets the same lowering account for inherited forwarders.
        // DotNetGenericInterfaceBridgeLowering replaces it with physical view-specific records.
        context.interfaceDefaultClassForwarders += DotNetLoweredInterfaceDefaultClassForwarder(
            owner = irClass,
            inheritedMember = member,
            implementation = forwarder,
        )
    }
    /**
     * Binds every inherited typed capability slot to the single typed semantic DIM. The erased
     * adapter already maps the source and inherited canonical identities; these view-specific
     * adapters exist only where the inherited CLR declaration has a typed physical signature.
     */
    private fun createGenericInterfaceSlotBridges(
        binding: LocalDefaultBinding,
        genericDefault: DotNetLoweredGenericInterfaceDefault,
        isKotlinOwnedGenericInterface: (IrClass) -> Boolean,
    ) {
        val owner = binding.owner
        val source = binding.member
        val sourceParameters = source.parameters.dropWhile { it.kind == IrParameterKind.DispatchReceiver }
        val interfaceIdentity = owner.fqNameWhenAvailable?.asString() ?: owner.name.asString()

        for (inheritedSymbol in source.inheritedInterfaceSlots()) {
            val inherited = inheritedSymbol.owner
            val inheritedOwner = inherited.parent as? IrClass
                ?: error("Internal .NET backend error: inherited generic interface slot has no owner")
            if (!inheritedOwner.isDotNetGenericInterfaceDeclaration ||
                !isKotlinOwnedGenericInterface(inheritedOwner)
            ) {
                continue
            }
            val ownerSubstitutor = AbstractIrTypeSubstitutor.forSuperClass(
                inheritedOwner.symbol,
                owner.symbol.defaultType,
            ) ?: error(
                "Internal .NET backend error: '${owner.name}' is not a subtype of " +
                        "generic interface '${inheritedOwner.name}'"
            )
            val inheritedIdentity = inheritedOwner.fqNameWhenAvailable?.asString()
                ?: inheritedOwner.name.asString()
            for (overriddenView in inherited.dotNetGenericInterfaceMemberViews(
                inheritedOwner,
                isKotlinOwnedGenericInterface,
            )) {
                // An exact-only Kotlin member is not part of the partial declared capability.
                // Its inherited declared-slot mapping therefore lives on the exact self view.
                val implementationView = if (
                    genericDefault.canonicalView == DotNetGenericInterfaceMemberView.EXACT
                ) {
                    DotNetGenericInterfaceMemberView.EXACT
                } else {
                    overriddenView
                }
                val adapter = owner.addFunction {
                    startOffset = source.startOffset
                    endOffset = source.endOffset
                    origin = when (overriddenView) {
                        DotNetGenericInterfaceMemberView.DECLARED ->
                            DOTNET_GENERIC_INTERFACE_DEFAULT_DECLARED_SLOT_ADAPTER
                        DotNetGenericInterfaceMemberView.EXACT ->
                            DOTNET_GENERIC_INTERFACE_DEFAULT_EXACT_SLOT_ADAPTER
                    }
                    name = Name.special(
                        "<GenericInterfaceDefault${overriddenView.name.lowercase().replaceFirstChar(Char::uppercaseChar)}" +
                                "SlotAdapter-$interfaceIdentity-$inheritedIdentity-${inherited.name.asString()}-" +
                                "${inherited.dotNetGenericInterfaceCanonicalSlotId()}>"
                    )
                    visibility = DescriptorVisibilities.PRIVATE
                    modality = Modality.FINAL
                    returnType = inherited.returnType
                }.apply adapter@{
                    overriddenSymbols = listOf(inherited.symbol)
                    parameters += createDispatchReceiverParameterWithClassParent()
                    val adapterTypeParameters = copyTypeParametersFrom(inherited)
                    val inheritedMethodSubstitution = inherited.typeParameters
                        .zip(adapterTypeParameters)
                        .associate { pair -> pair.first.symbol to pair.second.symbol.defaultType }
                    val inheritedMethodSubstitutor = IrTypeSubstitutor(
                        inheritedMethodSubstitution,
                        allowEmptySubstitution = true,
                    )
                    fun adapterType(type: IrType): IrType = inheritedMethodSubstitutor.substitute(
                        ownerSubstitutor.substitute(type)
                    )
                    adapterTypeParameters.forEach { parameter ->
                        parameter.superTypes = parameter.superTypes.map(::adapterType)
                    }
                    returnType = adapterType(inherited.returnType)
                    inherited.parameters
                        .dropWhile { it.kind == IrParameterKind.DispatchReceiver }
                        .forEach { parameter ->
                            parameters += parameter.copyTo(
                                this,
                                type = adapterType(parameter.type),
                                defaultValue = null,
                            )
                        }

                    check(source.typeParameters.size == adapterTypeParameters.size) {
                        "Internal .NET backend error: generic interface-default override changed method arity"
                    }
                    val sourceMethodSubstitution = source.typeParameters
                        .zip(adapterTypeParameters)
                        .associate { pair -> pair.first.symbol to pair.second.symbol.defaultType }
                    val sourceMethodSubstitutor = IrTypeSubstitutor(
                        sourceMethodSubstitution,
                        allowEmptySubstitution = true,
                    )
                    fun targetType(type: IrType): IrType = sourceMethodSubstitutor.substitute(type)
                    val targetParameterTypes = sourceParameters.map { parameter -> targetType(parameter.type) }
                    val targetReturnType = targetType(source.returnType)
                    check(targetParameterTypes.size == parameters.size - 1) {
                        "Internal .NET backend error: generic interface-default override changed parameter count"
                    }
                    body = context.createIrBuilder(symbol).irBlockBody {
                        val call = irCall(
                            genericDefault.canonicalBody.symbol,
                            targetReturnType,
                            origin = DOTNET_GENERIC_INTERFACE_DEFAULT_VIRTUAL_CALL,
                        ).apply {
                            arguments[0] = irGet(this@adapter.parameters[0])
                            adapterTypeParameters.forEachIndexed { index, parameter ->
                                typeArguments[index] = parameter.symbol.defaultType
                            }
                            this@adapter.parameters.drop(1).forEachIndexed { index, parameter ->
                                val argument = irGet(parameter)
                                arguments[index + 1] = if (argument.type == targetParameterTypes[index]) {
                                    argument
                                } else {
                                    irImplicitCast(argument, targetParameterTypes[index])
                                }
                            }
                        }
                        val result = if (call.type == this@adapter.returnType) {
                            call
                        } else {
                            irImplicitCast(call, this@adapter.returnType)
                        }
                        +irReturn(result)
                    }
                }
                genericDefault.inheritedSlotAdapters += DotNetLoweredGenericInterfaceDefaultSlotAdapter(
                    function = adapter,
                    implementationView = implementationView,
                )
            }
        }
    }

    /**
     * A CLR MethodImpl declared by an interface must be final. Keep the Kotlin-visible DIM
     * overridable and bind inherited physical slots through this private final adapter instead.
     */
    private fun createInterfaceSlotBridge(
        binding: LocalDefaultBinding,
        isKotlinOwnedGenericInterface: (IrClass) -> Boolean,
    ) {
        val member = binding.member
        // Split generic slots have canonical/declared/exact signatures. Their adapters are owned
        // by DotNetGenericInterfaceBridgeLowering; the ordinary bridge has the source member's
        // signature and would create an invalid MethodImpl for an erased canonical slot.
        val inheritedSlots = member.inheritedInterfaceSlots().filterNot { inherited ->
            (inherited.owner.parent as? IrClass)?.let(isKotlinOwnedGenericInterface) == true
        }
        if (inheritedSlots.isEmpty()) return
        check(binding.owner.typeParameters.isEmpty() && member.typeParameters.isEmpty()) {
            "Internal .NET backend error: generic interface default reached the non-generic slot-bridge path"
        }
        val interfaceIdentity = binding.owner.fqNameWhenAvailable?.asString()
            ?: binding.owner.name.asString()
        binding.owner.addFunction {
            startOffset = member.startOffset
            endOffset = member.endOffset
            origin = DOTNET_INTERFACE_DEFAULT_SLOT_BRIDGE
            name = Name.special(
                "<InterfaceDefaultSlotBridge-$interfaceIdentity-${member.name.asString()}-" +
                        "${member.dotNetGenericInterfaceCanonicalSlotId()}>"
            )
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            returnType = member.returnType
        }.apply bridge@{
            overriddenSymbols = inheritedSlots
            parameters += createDispatchReceiverParameterWithClassParent()
            member.parameters
                .dropWhile { it.kind == IrParameterKind.DispatchReceiver }
                .forEach { parameter ->
                    parameters += parameter.copyTo(this, defaultValue = null)
                }
            body = context.createIrBuilder(symbol).irBlockBody {
                val call = irCall(member).apply {
                    this@bridge.parameters.forEachIndexed { index, parameter ->
                        arguments[index] = irGet(parameter)
                    }
                }
                +irReturn(call)
            }
        }
    }
    private fun redirectCall(expression: IrCall, replacement: Replacement): IrCall {
        val contextOwner = replacement.typeContextOwner
        val interfaceArguments = if (contextOwner == null || contextOwner.typeParameters.isEmpty()) {
            emptyList()
        } else {
            val receiverType = expression.dispatchReceiver?.type as? IrSimpleType
                ?: error("Internal .NET backend error: interface default call has no simple receiver type")
            val substitutor = AbstractIrTypeSubstitutor.forSuperClass(contextOwner.symbol, receiverType)
                ?: error(
                    "Internal .NET backend error: default-call receiver is not a subtype of " +
                            "'${contextOwner.name.asString()}'"
                )
            contextOwner.typeParameters.map { typeParameter ->
                substitutor.substitute(typeParameter.defaultType)
            }
        }
        check(replacement.function.typeParameters.size == interfaceArguments.size + expression.typeArguments.size) {
            "Internal .NET backend error: interface default helper type-argument mismatch"
        }
        return IrCallImpl(
            expression.startOffset,
            expression.endOffset,
            expression.type,
            replacement.function.symbol,
            typeArgumentsCount = replacement.function.typeParameters.size,
            origin = expression.origin,
        ).apply {
            arguments.assignFrom(expression.arguments)
            interfaceArguments.forEachIndexed { index, argument ->
                typeArguments[index] = argument
            }
            expression.typeArguments.forEachIndexed { index, argument ->
                typeArguments[interfaceArguments.size + index] = argument
            }
        }
    }

    private fun createHelper(irInterface: IrClass): IrClass = context.irFactory.buildClass {
        startOffset = irInterface.startOffset
        endOffset = irInterface.endOffset
        origin = DOTNET_DEFAULT_IMPLS
        name = DEFAULT_IMPLS_NAME
        kind = ClassKind.CLASS
        modality = Modality.FINAL
        visibility = DescriptorVisibilities.PUBLIC
    }.apply {
        parent = irInterface
        createThisReceiverParameter()
    }

    private fun IrClass.memberFunctions(): List<IrSimpleFunction> = declarations.flatMap { declaration ->
        when (declaration) {
            is IrSimpleFunction -> listOf(declaration)
            is IrProperty -> listOfNotNull(declaration.getter, declaration.setter)
            else -> emptyList()
        }
    }

    private fun IrClass.fakeOverrideFunctions(): List<IrSimpleFunction> = declarations.flatMap { declaration ->
        when (declaration) {
            is IrSimpleFunction -> if (declaration.isFakeOverride) listOf(declaration) else emptyList()
            is IrProperty -> listOfNotNull(declaration.getter, declaration.setter).filter(IrSimpleFunction::isFakeOverride)
            else -> emptyList()
        }
    }

    private fun IrClass.baseClassOrNull(): IrClass? = superTypes.firstNotNullOfOrNull { superType ->
        val candidate = (superType as? IrSimpleType)?.classifier?.owner as? IrClass

        candidate?.takeUnless { it.isInterface || it == context.irBuiltIns.anyClass.owner }
    }

    private fun IrClass.directInterfaces(): List<IrClass> = superTypes.mapNotNull { superType ->
        ((superType as? IrSimpleType)?.classifier?.owner as? IrClass)?.takeIf(IrClass::isInterface)
    }

    private fun IrClass.interfaceInheritanceDepth(): Int {
        fun depth(irInterface: IrClass, visiting: MutableSet<IrClass>): Int {
            if (!visiting.add(irInterface)) return 0
            val result = irInterface.directInterfaces().maxOfOrNull { superInterface ->
                1 + depth(superInterface, visiting)
            } ?: 0
            visiting.remove(irInterface)
            return result
        }
        return depth(this, hashSetOf())
    }

    private fun IrSimpleFunction.inheritedInterfaceSlots(): List<IrSimpleFunctionSymbol> =
        allOverridden()
            .filter { overridden -> (overridden.parent as? IrClass)?.isInterface == true }
            .sortedWith(
                compareBy<IrSimpleFunction>(
                    { (it.parent as IrClass).fqNameWhenAvailable?.asString() ?: (it.parent as IrClass).name.asString() },
                    { it.name.asString() },
                    { it.dotNetGenericInterfaceCanonicalSlotId() },
                )
            )
            .map { it.symbol }

    private fun IrClass.classInheritanceDepth(): Int {
        val visited = hashSetOf<IrClass>()
        var depth = 0
        var current = baseClassOrNull()
        while (current != null && visited.add(current)) {
            depth++
            current = current.baseClassOrNull()
        }
        return depth
    }

    private fun IrClass.isSubclassOfInterface(candidate: IrClass): Boolean {
        val visited = hashSetOf<IrClass>()
        val queue = ArrayDeque<IrClass>()
        queue += this
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            if (current == candidate) return true
            current.superTypes.mapNotNullTo(queue) { superType ->
                (superType as? IrSimpleType)?.classifier?.owner as? IrClass
            }
        }
        return false
    }

    private data class CollectedDeclarations(
        val interfaces: List<IrClass>,
        val classes: List<IrClass>,
    )

    private data class DefaultStubPlan(
        val stub: IrSimpleFunction,
        val ordinaryHelper: IrSimpleFunction,
    )

    private data class HelperPlan(
        val owner: IrClass,
        val helper: IrClass,
        val defaults: MutableList<LocalDefaultBinding> = mutableListOf(),
        val defaultStubs: MutableList<DefaultStubPlan> = mutableListOf(),
    )

    private data class LocalDefaultBinding(
        val owner: IrClass,
        val member: IrSimpleFunction,
        val helper: IrSimpleFunction,
        val bodyPlacement: DotNetInterfaceDefaultBodyPlacement,
    )

    private fun LocalDefaultBinding.asDefaultCallBinding(): DefaultCallBinding =
        DefaultCallBinding(owner, member, helper)

    private data class ExternalDefaultBinding(
        val owner: IrClass,
        val member: IrSimpleFunction,
        val helper: IrSimpleFunction,
        val bound: DotNetBoundInterfaceDefaultImplementation,
    ) {
        fun asDefaultCallBinding(): DefaultCallBinding = DefaultCallBinding(owner, member, helper)
    }

    private data class DefaultCallBinding(
        val owner: IrClass,
        val member: IrSimpleFunction,
        val helper: IrSimpleFunction,
    )

    private data class Replacement(
        val typeContextOwner: IrClass?,
        val function: IrSimpleFunction,
    )

    private companion object {
        val DEFAULT_IMPLS_NAME: Name = Name.special("<DefaultImpls>")
    }
}
