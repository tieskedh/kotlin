package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.InlineClassesUtils
import org.jetbrains.kotlin.backend.common.ir.BackendSymbols
import org.jetbrains.kotlin.backend.common.ir.SharedVariablesManager
import org.jetbrains.kotlin.backend.common.lower.InnerClassesSupport
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInnerClassesSupport
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.InternalSymbolFinderAPI
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.KtDiagnosticReporterWithImplicitIrBasedContext
import org.jetbrains.kotlin.ir.classSymbol
import org.jetbrains.kotlin.ir.functionSymbol
import org.jetbrains.kotlin.ir.functionSymbolAssociatedBy
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.hasShape
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

internal data class DotNetLoweredInterfaceDefaultImplementation(
    val helper: IrSimpleFunction,
    val bodyPlacement: DotNetInterfaceDefaultBodyPlacement,
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
) : CommonBackendContext {
    override val irFactory: IrFactory = symbolTable.irFactory
    override val typeSystem: IrTypeSystemContext = IrTypeSystemContextImpl(irBuiltIns)
    override val symbols: DotNetSymbols = DotNetSymbols(irBuiltIns, irFactory, irModuleFragment)
    val exactCallableSymbols: DotNetExactCallableSymbols =
        DotNetExactCallableSymbols(irBuiltIns, irFactory, irModuleFragment)
    val typedArgumentsCallableSymbols: DotNetTypedArgumentsCallableSymbols =
        DotNetTypedArgumentsCallableSymbols(irBuiltIns, irFactory, irModuleFragment)
    val functionReferenceSymbols: DotNetFunctionReferenceSymbols =
        DotNetFunctionReferenceSymbols(irBuiltIns, irFactory, irModuleFragment)
    val propertyReferenceSymbols: DotNetPropertyReferenceSymbols =
        DotNetPropertyReferenceSymbols(irBuiltIns, irFactory, irModuleFragment)
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
    /** Final interface MethodImpl adapters which later compilations inherit as a complete view bundle. */
    val genericInterfaceViewBridges:
        MutableList<DotNetLoweredGenericInterfaceViewBridge> = mutableListOf()
    /** Final MethodImpl adapters for ordinary slots whose Kotlin override refines the return. */
    val covariantReturnBridges:
        MutableList<DotNetLoweredCovariantReturnBridge> = mutableListOf()
    /** Hidden class MethodImpls which later compilations must account for during DIM selection. */
    val interfaceDefaultClassForwarders:
        MutableList<DotNetLoweredInterfaceDefaultClassForwarder> = mutableListOf()
    /** Logical classifier to the non-generic physical owner selected for companion-block statics. */
    val companionStaticOwners: MutableMap<IrClass, IrClass> = linkedMapOf()
    /** Kotlin object declaration to the synthesized field carrying its one CLR instance. */
    val objectInstanceFields: MutableMap<IrClass, IrField> = linkedMapOf()
    /** Logical enum entry to the synthesized public static field carrying its singleton. */
    val enumEntryFields: MutableMap<IrEnumEntry, IrField> = linkedMapOf()
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
    // No inline/value class model exists in the .NET backend yet; unsupported shapes are rejected
    // by the shape gates instead of being treated as inline-like.
    override fun isClassInlineLike(klass: IrClass): Boolean = false
}

@OptIn(InternalSymbolFinderAPI::class)
internal class DotNetSymbols(
    private val irBuiltIns: IrBuiltIns,
    private val irFactory: IrFactory,
    irModuleFragment: IrModuleFragment,
) : BackendSymbols(irBuiltIns) {
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
    override val throwUninitializedPropertyAccessException: IrSimpleFunctionSymbol
        get() = unsupportedSymbol("throwUninitializedPropertyAccessException")
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
    override val coroutineContextGetter: IrSimpleFunctionSymbol
        get() = unsupportedSymbol("coroutineContextGetter")
    override val suspendCoroutineUninterceptedOrReturn: IrSimpleFunctionSymbol
        get() = unsupportedSymbol("suspendCoroutineUninterceptedOrReturn")
    override val coroutineGetContext: IrSimpleFunctionSymbol
        get() = unsupportedSymbol("coroutineGetContext")
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
    override val coroutineSuspendedGetter: IrSimpleFunctionSymbol
        get() = unsupportedSymbol("coroutineSuspendedGetter")
    override val getContinuation: IrSimpleFunctionSymbol
        get() = unsupportedSymbol("getContinuation")
    override val continuationClass: IrClassSymbol
        get() = unsupportedSymbol("continuationClass")
    override val returnIfSuspended: IrSimpleFunctionSymbol
        get() = unsupportedSymbol("returnIfSuspended")
    override val functionAdapter: IrClassSymbol
        get() = unsupportedSymbol("functionAdapter")
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
