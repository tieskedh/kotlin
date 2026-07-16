package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.InlineClassesUtils
import org.jetbrains.kotlin.backend.common.ir.BackendSymbols
import org.jetbrains.kotlin.backend.common.ir.SharedVariablesManager
import org.jetbrains.kotlin.backend.common.lower.InnerClassesSupport
import org.jetbrains.kotlin.backend.dotnet.lower.DotNetInnerClassesSupport
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
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class DotNetBackendContext(
    override val irBuiltIns: IrBuiltIns,
    override val configuration: CompilerConfiguration,
    symbolTable: SymbolTable,
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
    irBuiltIns: IrBuiltIns,
    irFactory: IrFactory,
    irModuleFragment: IrModuleFragment,
) : BackendSymbols(irBuiltIns) {
    override val getProgressionLastElementByReturnType: Map<IrClassifierSymbol, IrSimpleFunctionSymbol> = emptyMap()
    override val syntheticConstructorMarker: IrClassSymbol
        get() = unsupportedSymbol("syntheticConstructorMarker")
    override val throwUninitializedPropertyAccessException: IrSimpleFunctionSymbol
        get() = unsupportedSymbol("throwUninitializedPropertyAccessException")
    override val throwUnsupportedOperationException: IrSimpleFunctionSymbol = run {
        val kotlinInternalPackage = createEmptyExternalPackageFragment(
            irModuleFragment,
            FqName("kotlin.internal"),
        )
        irFactory.buildFun {
            origin = IrDeclarationOrigin.IR_BUILTINS_STUB
            name = Name.identifier("throwUnsupportedOperationException")
            visibility = DescriptorVisibilities.INTERNAL
            modality = Modality.FINAL
            returnType = irBuiltIns.nothingType
        }.apply {
            parent = kotlinInternalPackage
            addValueParameter("message", irBuiltIns.stringType)
        }.symbol
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
    override val throwKotlinNothingValueException: IrSimpleFunctionSymbol
        get() = unsupportedSymbol("throwKotlinNothingValueException")
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
            createThisReceiverParameter()
        }.symbol
    }

    private fun unsupportedSymbol(name: String): Nothing =
        error("DotNet backend symbol '$name' is not available yet")
}
