package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.InlineClassesUtils
import org.jetbrains.kotlin.backend.common.ir.BackendSymbols
import org.jetbrains.kotlin.backend.common.ir.SharedVariablesManager
import org.jetbrains.kotlin.backend.common.lower.InnerClassesSupport
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInnerClassesSupport
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetValueClassBoxingHelpers
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.ValueClassBackendAgnosticApi
import org.jetbrains.kotlin.ir.InternalSymbolFinderAPI
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.KtDiagnosticReporterWithImplicitIrBasedContext
import org.jetbrains.kotlin.ir.classSymbol
import org.jetbrains.kotlin.ir.functionSymbol
import org.jetbrains.kotlin.ir.functionSymbolOrNull
import org.jetbrains.kotlin.ir.functionSymbolAssociatedBy
import org.jetbrains.kotlin.ir.getterSymbol
import org.jetbrains.kotlin.ir.setterSymbol
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.declarations.isInlineClass
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.hasShape
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.name.withClassId

internal data class DotNetLoweredInterfaceDefaultImplementation(
    val helper: IrSimpleFunction,
    val bodyPlacement: DotNetInterfaceDefaultBodyPlacement,
)

/**
 * Rehearsal-only dispatcher split for a concrete semantic output family. The typed and semantic
 * virtual slots normally move together for Kotlin subclasses. When only the typed slot changed,
 * an ordinary foreign subclass supplied the natural C# override and capability dispatch must use
 * it; otherwise the raw semantic hook remains authoritative. The admitted method-generic shape
 * carries one declaration-independent input and preserves that method argument on either route.
 */
internal data class DotNetGenericOwnerDirectForeignOverrideDispatch(
    val typedEntry: IrSimpleFunction,
    val semanticHook: IrSimpleFunction,
    val foreignOverrideProbe: IrSimpleFunction,
)

internal data class DotNetLoweredInterfaceDefaultPromotion(
    val owner: IrClass,
    val inheritedMember: IrSimpleFunction,
    val implementation: IrSimpleFunction,
    val inheritedDefault: DotNetBoundInterfaceDefaultImplementation,
    val physicalView: DotNetInterfaceDefaultPromotionView = DotNetInterfaceDefaultPromotionView.CANONICAL,
    val implementationView: DotNetGenericInterfaceMemberView? = null,
)

internal data class DotNetLoweredGenericInterfaceViewBridge(
    val owner: IrClass,
    val inheritedMember: IrSimpleFunction,
    val implementation: IrSimpleFunction,
    val physicalView: DotNetInterfaceDefaultPromotionView,
)

/** One exact Kotlin implementation adapted to an ordinary CLR class or interface slot. */
internal data class DotNetLoweredCovariantReturnBridge(
    val owner: IrClass,
    val inheritedMember: IrSimpleFunction,
    val target: IrSimpleFunction,
    val implementation: IrSimpleFunction,
    val requiresNewSlotOnTarget: Boolean,
)

internal data class DotNetLoweredInterfaceDefaultClassForwarder(
    val owner: IrClass,
    val inheritedMember: IrSimpleFunction,
    val implementation: IrSimpleFunction,
    val physicalView: DotNetInterfaceDefaultPromotionView = DotNetInterfaceDefaultPromotionView.CANONICAL,
)

internal data class DotNetLoweredStaticInitialization(
    val physicalOwner: IrClass,
    val entry: IrSimpleFunction,
)

internal data class DotNetLoweredStaticInitializationFailure(
    val entry: IrSimpleFunction,
    val failureState: IrField,
)

internal class DotNetBackendContext(
    override val irBuiltIns: IrBuiltIns,
    override val configuration: CompilerConfiguration,
    val symbolTable: SymbolTable,
    irModuleFragment: IrModuleFragment,
    /** KLIB-authoritative public identities captured before mutable backend lowerings. */
    val preLoweringDeclarationKeys: Map<IrDeclaration, String> = emptyMap(),
) : CommonBackendContext {
    override val irFactory: IrFactory = symbolTable.irFactory
    override val typeSystem: IrTypeSystemContext = IrTypeSystemContextImpl(irBuiltIns)
    override val symbols: DotNetSymbols = DotNetSymbols(irBuiltIns, irFactory, irModuleFragment)
    private val externalDeclarationIndex =
        DotNetExternalDeclarationIndex(configuration.dotNetExternalLibraries)

    /**
     * Gives one lowering a resolver with fresh IR-derived caches while reusing the immutable
     * external-library indexes built once for this backend compilation.
     */
    fun externalDeclarationsForLowering(): DotNetExternalDeclarations =
        DotNetExternalDeclarations(externalDeclarationIndex)

    val functionAdapterSymbols: DotNetFunctionAdapterSymbols = symbols.functionAdapterSymbols
    val exactCallableSymbols: DotNetExactCallableSymbols =
        DotNetExactCallableSymbols(irBuiltIns, irFactory, irModuleFragment)
    val typedArgumentsCallableSymbols: DotNetTypedArgumentsCallableSymbols =
        DotNetTypedArgumentsCallableSymbols(irBuiltIns, irFactory, irModuleFragment)
    val bigArityCallableSymbols: DotNetBigArityCallableSymbols =
        DotNetBigArityCallableSymbols(irBuiltIns, irFactory, irModuleFragment)
    val functionReferenceSymbols: DotNetFunctionReferenceSymbols =
        DotNetFunctionReferenceSymbols(irBuiltIns, irFactory, irModuleFragment)
    val propertyReferenceSymbols: DotNetPropertyReferenceSymbols =
        DotNetPropertyReferenceSymbols(irBuiltIns, irFactory, irModuleFragment)
    val memberReferenceSymbols: DotNetMemberReferenceSymbols =
        DotNetMemberReferenceSymbols(irBuiltIns, irFactory, irModuleFragment)
    val callableAnnotationSymbols: DotNetCallableAnnotationSymbols =
        DotNetCallableAnnotationSymbols(irBuiltIns, irFactory, irModuleFragment)
    /** Source interface member to its profile-selected compiler-ABI helper and body placement. */
    val interfaceDefaultImplementations:
        MutableMap<IrSimpleFunction, DotNetLoweredInterfaceDefaultImplementation> = linkedMapOf()
    /** Source function to its final physical masked default-argument dispatcher. */
    val defaultArgumentDispatchers:
        MutableMap<IrSimpleFunction, IrSimpleFunction> = linkedMapOf()
    /** Synthetic static helper call targets bound directly to producer-recorded CLR identities. */
    val externalInterfaceDefaultHelpers:
        MutableMap<IrSimpleFunction, DotNetBoundInterfaceDefaultImplementation> = linkedMapOf()
    /** Synthetic calls to producer-recorded masked default-argument dispatchers. */
    val externalDefaultArgumentDispatchers:
        MutableMap<IrSimpleFunction, DotNetBoundDefaultArgumentDispatcher> = linkedMapOf()
    /** Producer-visible records for helper-only defaults promoted into DIMs by this net10 variant. */
    val interfaceDefaultPromotions:
        MutableList<DotNetLoweredInterfaceDefaultPromotion> = mutableListOf()
    /** Final interface-slot MethodImpl adapters which later compilations inherit as a complete view bundle. */
    val genericInterfaceViewBridges:
        MutableList<DotNetLoweredGenericInterfaceViewBridge> = mutableListOf()
    /** Final MethodImpl adapters for ordinary slots whose Kotlin override refines the return. */
    val covariantReturnBridges:
        MutableList<DotNetLoweredCovariantReturnBridge> = mutableListOf()
    /** Hidden class MethodImpls which later compilations must account for during DIM selection. */
    val interfaceDefaultClassForwarders:
        MutableList<DotNetLoweredInterfaceDefaultClassForwarder> = mutableListOf()
    /** Logical generic-interface default member to its local or materialized helper call target. */
    val genericInterfaceDefaultSemanticHelpers: MutableMap<IrSimpleFunction, IrSimpleFunction> =
        linkedMapOf()
    /** Logical classifier to the non-generic physical owner selected for companion-block statics. */
    val companionStaticOwners: MutableMap<IrClass, IrClass> = linkedMapOf()
    /** Kotlin object declaration to the synthesized field carrying its one CLR instance. */
    val objectInstanceFields: MutableMap<IrClass, IrField> = linkedMapOf()
    /** Logical enum entry to the synthesized public static field carrying its singleton. */
    val enumEntryFields: MutableMap<IrEnumEntry, IrField> = linkedMapOf()
    /** Logical value class to its local definition or external physical box/unbox stubs. */
    val valueClassBoxingHelpers: MutableMap<IrClass, DotNetValueClassBoxingHelpers> = linkedMapOf()
    /** Kotlin-owned generic classifiers whose selected physical ABI erases owner parameters. */
    val erasedGenericInterfaces: MutableSet<IrClass> = hashSetOf()
    val erasedGenericClasses: MutableSet<IrClass> = hashSetOf()
    /** Rehearsal-only generic interfaces whose natural CLR owner is the truthful `I<T>` TypeDef. */
    val reifiedGenericInterfaces: MutableSet<IrClass> = hashSetOf()
    /** Immutable physical-family contracts published identically to local and external consumers. */
    val publishedGenericInterfaceFamilies:
        MutableMap<IrClass, DotNetPublishedGenericInterfaceFamilyContract> = linkedMapOf()
    /** Fail-closed evidence consumed only by the atomic CLR-generic rehearsal epoch. */
    val genericOwnerArchitecturePlans: MutableMap<IrClass, DotNetGenericOwnerArchitecturePlan> = linkedMapOf()
    /** Static call-site evidence only; codegen must never consume these route requirements. */
    val genericOwnerCallRoutes: MutableList<DotNetGenericOwnerCallRoutePlan> = mutableListOf()
    /** Rehearsal-only logical owner to its materialized non-generic semantic capability interface. */
    val genericOwnerCapabilityInterfaces: MutableMap<IrClass, IrClass> = linkedMapOf()
    /** Local reified subinterface to the external logical ancestor whose capability it inherits. */
    val externalReifiedGenericInterfaceCapabilityProviders: MutableMap<IrClass, IrClass> = linkedMapOf()
    /** Local memberless capability alias to the external capability interfaces it intersects. */
    val externalGenericOwnerCapabilitySupertypeProviders: MutableMap<IrClass, List<IrClass>> = linkedMapOf()
    /** Private producer-only capability used by generated reflection thunks for private members. */
    val genericOwnerReflectionCapabilityInterfaces: MutableMap<IrClass, IrClass> = linkedMapOf()
    /** Rehearsal-only logical member to its producer-owned capability Interface MethodDef. */
    val genericOwnerCapabilitySlots: MutableMap<IrSimpleFunction, IrSimpleFunction> = linkedMapOf()
    /** Upstream special-bridge policy required by semantic adapters and foreign dispatch. */
    val genericOwnerWrongShapePolicies:
        MutableMap<IrSimpleFunction, DotNetCSharpWrongShapePolicy> = linkedMapOf()
    /** External reified-interface member to its producer-bound, un-emitted semantic slot stub. */
    val externalReifiedGenericInterfaceCapabilitySlots:
        MutableMap<IrSimpleFunction, IrSimpleFunction> = linkedMapOf()
    /** External generic-class member to its producer-bound, un-emitted capability slot stub. */
    val externalGenericOwnerCapabilitySlots:
        MutableMap<IrSimpleFunction, IrSimpleFunction> = linkedMapOf()
    /** Rehearsal-only logical member to its instance capability slot for masked defaults. */
    val genericOwnerDefaultCapabilitySlots: MutableMap<IrSimpleFunction, IrSimpleFunction> = linkedMapOf()
    /** Rehearsal-only logical member to its separately overridable semantic MethodDef. */
    val genericOwnerSemanticHooks: MutableMap<IrSimpleFunction, IrSimpleFunction> = linkedMapOf()
    /** Logical generic-owner member to its final semantic capability dispatcher. */
    val genericOwnerCapabilityDispatchers: MutableMap<IrSimpleFunction, IrSimpleFunction> = linkedMapOf()
    /** Natural function to its compiler-owned classifier-derived object-input entry. */
    val genericOwnerFunctionInputEntries: MutableMap<IrSimpleFunction, IrSimpleFunction> = linkedMapOf()
    /** Concrete admitted capability dispatchers which preserve a direct foreign typed override. */
    val genericOwnerDirectForeignOverrideDispatches:
        MutableMap<IrSimpleFunction, DotNetGenericOwnerDirectForeignOverrideDispatch> = linkedMapOf()
    /** Generated virtual probe to the exact Kotlin typed declaration it represents. */
    val genericOwnerForeignOverrideProbeTargets: MutableMap<IrSimpleFunction, IrSimpleFunction> = linkedMapOf()
    /** Synthetic external slot stubs to their producer-recorded physical families. */
    val externalGenericOwnerPhysicalSlots:
        MutableMap<IrSimpleFunction, DotNetBoundGenericOwnerPhysicalSlot> = linkedMapOf()
    /** Synthetic external input entries to their producer-recorded physical MethodDefs. */
    val externalGenericOwnerFunctionInputEntries:
        MutableMap<IrSimpleFunction, DotNetBoundGenericOwnerFunctionInputEntry> = linkedMapOf()
    /** Rehearsal-only exact call sites whose physical MethodRef targets a capability slot. */
    val genericOwnerCapabilityCallTargets: MutableMap<IrCall, IrSimpleFunction> =
        java.util.IdentityHashMap()
    /** Reified producer calls whose object carrier needs capability-or-foreign dispatch. */
    val genericOwnerForeignDispatchCallTargets: MutableMap<IrCall, IrSimpleFunction> =
        java.util.IdentityHashMap()
    /** Rehearsal-only value/field/function slots whose proven Kotlin view is wider than one C<T>. */
    val genericOwnerCapabilityDeclarations: MutableSet<IrDeclaration> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    /** Early-proven exact interface slots which a final routing rescan must never degrade. */
    val genericOwnerExactInterfaceDeclarationTypes: MutableMap<IrDeclaration, IrType> =
        java.util.IdentityHashMap()
    /** Natural C<T> slots proven to contain a Kotlin object which also implements its capability. */
    val genericOwnerCapabilityBearingDeclarations: MutableSet<IrDeclaration> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    /** Semantic producer views which admit an ordinary foreign `I<T>` object carrier. */
    val genericOwnerForeignDispatchDeclarations: MutableSet<IrDeclaration> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    /** Generated reflection values whose physical carrier is the private reflection capability. */
    val genericOwnerReflectionCapabilityDeclarations: MutableSet<IrDeclaration> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    /** Logical classifier to its stable, producer-recorded static-initialization entry. */
    val staticInitializations:
        MutableMap<IrClass, DotNetLoweredStaticInitialization> = linkedMapOf()
    /** Synthetic calls bound directly to producer-recorded static-initialization entries. */
    val externalStaticInitializations:
        MutableMap<IrSimpleFunction, DotNetBoundStaticInitialization> = linkedMapOf()
    /** External logical classifier to the synthetic IR call target bound above. */
    val externalStaticInitializationEntries:
        MutableMap<IrClass, IrSimpleFunction> = linkedMapOf()
    /** Physical class/file initializer owner to its caught-failure state and logical barrier. */
    val staticInitializationFailures:
        MutableMap<IrDeclarationParent, DotNetLoweredStaticInitializationFailure> = linkedMapOf()
    override val sharedVariablesManager: SharedVariablesManager = DotNetSharedVariablesManager(irBuiltIns, irFactory)
    override val innerClassesSupport: InnerClassesSupport = DotNetInnerClassesSupport(irFactory)
    override val diagnosticReporter: IrDiagnosticReporter = KtDiagnosticReporterWithImplicitIrBasedContext(
        configuration.diagnosticsCollector,
        configuration.languageVersionSettings,
    )
    override val inlineClassesUtils: InlineClassesUtils = DotNetInlineClassesUtils
    override var inVerbosePhase: Boolean = false
}

private object DotNetInlineClassesUtils : InlineClassesUtils {
    @OptIn(ValueClassBackendAgnosticApi::class)
    override fun isClassInlineLike(klass: IrClass): Boolean =
        klass.isInlineClass(treatCompatibleFullValueClassesAsInline = true)
}

@OptIn(InternalSymbolFinderAPI::class)
internal class DotNetSymbols(
    private val irBuiltIns: IrBuiltIns,
    private val irFactory: IrFactory,
    irModuleFragment: IrModuleFragment,
) : BackendSymbols(irBuiltIns) {
    val functionAdapterSymbols = DotNetFunctionAdapterSymbols(irBuiltIns, irFactory, irModuleFragment)
    private val coroutineImplClassId = ClassId(FqName("kotlin.coroutines"), Name.identifier("DotNetCoroutineImpl"))
    private val dotNetCoroutineInternalPackage = FqName("kotlin.dotnet.internal")

    private fun coroutineImplMember(name: String): CallableId =
        CallableId(Name.identifier(name)).withClassId(coroutineImplClassId)

    private val kotlinInternalPackage = createEmptyExternalPackageFragment(
        irModuleFragment,
        FqName("kotlin.internal"),
    )

    private fun buildInternalFunction(
        name: String,
        returnType: IrType,
        parameters: List<Pair<String, IrType>> = emptyList(),
    ): IrSimpleFunctionSymbol =
        irFactory.buildFun {
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB
            this.name = Name.identifier(name)
            visibility = DescriptorVisibilities.INTERNAL
            modality = Modality.FINAL
            this.returnType = returnType
        }.apply {
            parent = kotlinInternalPackage
            for (parameter in parameters) {
                addValueParameter(parameter.first, parameter.second)
            }
        }.symbol

    private fun buildInternalThrowFunction(
        name: String,
        hasMessageParameter: Boolean = false,
    ): IrSimpleFunctionSymbol =
        buildInternalFunction(
            name,
            irBuiltIns.nothingType,
            if (hasMessageParameter) listOf("message" to irBuiltIns.stringType) else emptyList(),
        )

    val dotNetAnnotationFloatEquals: IrSimpleFunctionSymbol = buildInternalFunction(
        "annotationFloatEquals",
        irBuiltIns.booleanType,
        listOf("left" to irBuiltIns.floatType, "right" to irBuiltIns.floatType),
    )
    val dotNetAnnotationDoubleEquals: IrSimpleFunctionSymbol = buildInternalFunction(
        "annotationDoubleEquals",
        irBuiltIns.booleanType,
        listOf("left" to irBuiltIns.doubleType, "right" to irBuiltIns.doubleType),
    )
    override val getProgressionLastElementByReturnType: Map<IrClassifierSymbol, IrSimpleFunctionSymbol> by CallableId(
        StandardNames.KOTLIN_INTERNAL_FQ_NAME,
        Name.identifier("getProgressionLastElement"),
    ).functionSymbolAssociatedBy { function -> function.returnType.classifierOrFail }
    val signedRangeUntilFunctions: Map<Pair<IrType, IrType>, IrSimpleFunctionSymbol> by CallableId(
        FqName("kotlin.ranges"),
        Name.identifier("until"),
    ).functionSymbolAssociatedBy(
        condition = { function -> function.hasShape(extensionReceiver = true, regularParameters = 1) },
        getKey = { function -> function.parameters[0].type to function.parameters[1].type },
    )
    /** Common annotation-value equality for generic and primitive arrays. */
    val arraysContentEquals: Map<IrType, IrSimpleFunctionSymbol> by CallableId(
        StandardNames.COLLECTIONS_PACKAGE_FQ_NAME,
        Name.identifier("contentEquals"),
    ).functionSymbolAssociatedBy(
        condition = { function ->
            function.hasShape(extensionReceiver = true, regularParameters = 1) &&
                    function.parameters[0].type.isNullable()
        },
        getKey = { function -> function.parameters[0].type.makeNotNull() },
    )
    val enumEntries: IrClassSymbol by lazy {
        with(irBuiltIns) {
            ClassId(StandardClassIds.BASE_ENUMS_PACKAGE, Name.identifier("EnumEntries")).classSymbol()
        }
    }
    val createEnumEntries: IrSimpleFunctionSymbol by with(irBuiltIns) {
        CallableId(StandardClassIds.BASE_ENUMS_PACKAGE, Name.identifier("enumEntries")).functionSymbol {
            it.hasShape(regularParameters = 1) && it.parameters[0].type.classOrNull == irBuiltIns.arrayClass
        }
    }
    val dotNetCreateKType: IrSimpleFunctionSymbol by with(irBuiltIns) {
        CallableId(StandardNames.KOTLIN_REFLECT_FQ_NAME, Name.identifier("dotNetCreateKType")).functionSymbol {
            it.hasShape(regularParameters = 4)
        }
    }
    val dotNetCreateKTypeParameter: IrSimpleFunctionSymbol by with(irBuiltIns) {
        CallableId(StandardNames.KOTLIN_REFLECT_FQ_NAME, Name.identifier("dotNetCreateKTypeParameter")).functionSymbol {
            it.hasShape(regularParameters = 4)
        }
    }
    val dotNetInitializeKTypeParameterUpperBounds: IrSimpleFunctionSymbol by with(irBuiltIns) {
        CallableId(
            StandardNames.KOTLIN_REFLECT_FQ_NAME,
            Name.identifier("dotNetInitializeKTypeParameterUpperBounds"),
        ).functionSymbol { it.hasShape(regularParameters = 2) }
    }
    val dotNetKParameterFactory: IrSimpleFunctionSymbol? by with(irBuiltIns) {
        CallableId(
            StandardNames.KOTLIN_REFLECT_FQ_NAME,
            Name.identifier("dotNetKParameterFactory"),
        ).functionSymbolOrNull()
    }
    val dotNetStarKTypeProjection: IrSimpleFunctionSymbol by with(irBuiltIns) {
        CallableId(StandardNames.KOTLIN_REFLECT_FQ_NAME, Name.identifier("dotNetStarKTypeProjection")).functionSymbol {
            it.hasShape(regularParameters = 0)
        }
    }
    val dotNetInvariantKTypeProjection: IrSimpleFunctionSymbol by with(irBuiltIns) {
        CallableId(StandardNames.KOTLIN_REFLECT_FQ_NAME, Name.identifier("dotNetInvariantKTypeProjection")).functionSymbol {
            it.hasShape(regularParameters = 1)
        }
    }
    val dotNetContravariantKTypeProjection: IrSimpleFunctionSymbol by with(irBuiltIns) {
        CallableId(StandardNames.KOTLIN_REFLECT_FQ_NAME, Name.identifier("dotNetContravariantKTypeProjection")).functionSymbol {
            it.hasShape(regularParameters = 1)
        }
    }
    val dotNetCovariantKTypeProjection: IrSimpleFunctionSymbol by with(irBuiltIns) {
        CallableId(StandardNames.KOTLIN_REFLECT_FQ_NAME, Name.identifier("dotNetCovariantKTypeProjection")).functionSymbol {
            it.hasShape(regularParameters = 1)
        }
    }
    override val syntheticConstructorMarker: IrClassSymbol = run {
        val fqName = DotNetRuntimeTypes.SYNTHETIC_CONSTRUCTOR_MARKER_FQ_NAME
        val markerPackage = createEmptyExternalPackageFragment(irModuleFragment, fqName.parent())
        irFactory.buildClass {
            name = fqName.shortName()
            kind = ClassKind.CLASS
            modality = Modality.FINAL
        }.apply {
            parent = markerPackage
            superTypes = listOf(irBuiltIns.anyType)
            createThisReceiverParameter()
        }.symbol
    }
    // Common LateinitLowering keeps this as an ordinary Stdlib ABI edge, just like the other
    // KLIB targets. The declaration owns the exact Kotlin exception identity and message.
    override val throwUninitializedPropertyAccessException: IrSimpleFunctionSymbol by with(irBuiltIns) {
        CallableId(
            StandardNames.KOTLIN_INTERNAL_FQ_NAME,
            Name.identifier("throwUninitializedPropertyAccessException"),
        ).functionSymbol { function ->
            function.hasShape(regularParameters = 1) &&
                    function.parameters.single().type == irBuiltIns.stringType
        }
    }
    // Unlike compiler-only throw intrinsics, reified physical stubs must call the selected
    // Common runtime declaration so separately emitted libraries retain one ordinary ABI edge.
    override val throwUnsupportedOperationException: IrSimpleFunctionSymbol by with(irBuiltIns) {
        CallableId(
            StandardNames.KOTLIN_INTERNAL_FQ_NAME,
            Name.identifier("throwUnsupportedOperationException"),
        ).functionSymbol { function ->
            function.hasShape(regularParameters = 1) &&
                    function.parameters.single().type == irBuiltIns.stringType
        }
    }
    override val coroutineContextGetter: IrSimpleFunctionSymbol by with(irBuiltIns) {
        CallableId(StandardNames.COROUTINES_PACKAGE_FQ_NAME, Name.identifier("coroutineContext")).getterSymbol()
    }
    override val suspendCoroutineUninterceptedOrReturn: IrSimpleFunctionSymbol by with(irBuiltIns) {
        CallableId(dotNetCoroutineInternalPackage, Name.identifier("suspendCoroutineUninterceptedOrReturnDotNet"))
            .functionSymbol()
    }
    override val coroutineGetContext: IrSimpleFunctionSymbol by with(irBuiltIns) {
        CallableId(dotNetCoroutineInternalPackage, Name.identifier("getCoroutineContext")).functionSymbol()
    }
    override val throwNullPointerException: IrSimpleFunctionSymbol
        get() = unsupportedSymbol("throwNullPointerException")
    override val throwTypeCastException: IrSimpleFunctionSymbol
        get() = unsupportedSymbol("throwTypeCastException")
    override val throwKotlinNothingValueException: IrSimpleFunctionSymbol =
        buildInternalThrowFunction("throwKotlinNothingValueException")
    val captureStaticInitializationFailure: IrSimpleFunctionSymbol =
        buildInternalFunction(
            "captureStaticInitializationFailure",
            irBuiltIns.anyType,
            listOf("reason" to irBuiltIns.throwableType),
        )
    val observeStaticInitializationFailure: IrSimpleFunctionSymbol =
        buildInternalFunction(
            "observeStaticInitializationFailure",
            irBuiltIns.throwableType.makeNullable(),
            listOf("state" to irBuiltIns.anyType),
        )
    val staticInitializationFailure: IrSimpleFunctionSymbol =
        buildInternalFunction(
            "staticInitializationFailure",
            irBuiltIns.nothingType,
            listOf(
                "reason" to irBuiltIns.throwableType.makeNullable(),
                "className" to irBuiltIns.stringType.makeNullable(),
            ),
        )
    override val stringBuilder: IrClassSymbol
        get() = unsupportedSymbol("stringBuilder")
    override val coroutineSuspendedGetter: IrSimpleFunctionSymbol by with(irBuiltIns) {
        CallableId(
            StandardNames.COROUTINES_INTRINSICS_PACKAGE_FQ_NAME,
            StandardNames.COROUTINE_SUSPENDED_NAME,
        ).getterSymbol()
    }
    override val getContinuation: IrSimpleFunctionSymbol by with(irBuiltIns) {
        CallableId(dotNetCoroutineInternalPackage, Name.identifier("getContinuation")).functionSymbol()
    }
    override val continuationClass: IrClassSymbol by lazy {
        with(irBuiltIns) {
            ClassId(StandardNames.COROUTINES_PACKAGE_FQ_NAME, Name.identifier("Continuation")).classSymbol()
        }
    }
    override val returnIfSuspended: IrSimpleFunctionSymbol by with(irBuiltIns) {
        CallableId(dotNetCoroutineInternalPackage, Name.identifier("returnIfSuspended")).functionSymbol()
    }
    val coroutineImpl: IrClassSymbol by lazy {
        with(irBuiltIns) { coroutineImplClassId.classSymbol() }
    }
    val coroutineImplLabelPropertyGetter: IrSimpleFunctionSymbol by with(irBuiltIns) {
        coroutineImplMember("state").getterSymbol()
    }
    val coroutineImplLabelPropertySetter: IrSimpleFunctionSymbol by with(irBuiltIns) {
        coroutineImplMember("state").setterSymbol()
    }
    val coroutineImplExceptionStatePropertyGetter: IrSimpleFunctionSymbol by with(irBuiltIns) {
        coroutineImplMember("exceptionState").getterSymbol()
    }
    val coroutineImplExceptionStatePropertySetter: IrSimpleFunctionSymbol by with(irBuiltIns) {
        coroutineImplMember("exceptionState").setterSymbol()
    }
    val coroutineImplResultSymbolGetter: IrSimpleFunctionSymbol by with(irBuiltIns) {
        coroutineImplMember("result").getterSymbol()
    }
    val coroutineImplExceptionPropertyGetter: IrSimpleFunctionSymbol by with(irBuiltIns) {
        coroutineImplMember("exception").getterSymbol()
    }
    val coroutineImplExceptionPropertySetter: IrSimpleFunctionSymbol by with(irBuiltIns) {
        coroutineImplMember("exception").setterSymbol()
    }
    override val functionAdapter: IrClassSymbol
        get() = functionAdapterSymbols.irClass.symbol
    override val defaultConstructorMarker: IrClassSymbol = run {
        val fqName = DotNetRuntimeTypes.DEFAULT_CONSTRUCTOR_MARKER_FQ_NAME
        val markerPackage = createEmptyExternalPackageFragment(irModuleFragment, fqName.parent())
        irFactory.buildClass {
            name = fqName.shortName()
            kind = ClassKind.CLASS
            modality = Modality.FINAL
        }.apply {
            parent = markerPackage
            superTypes = listOf(irBuiltIns.anyType)
            createThisReceiverParameter()
        }.symbol
    }

    private fun unsupportedSymbol(name: String): Nothing =
        error("DotNet backend symbol '$name' is not available yet")
}
