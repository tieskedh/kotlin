package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.InlineClassesUtils
import org.jetbrains.kotlin.backend.common.ir.BackendSymbols
import org.jetbrains.kotlin.backend.common.ir.SharedVariablesManager
import org.jetbrains.kotlin.backend.common.lower.InnerClassesSupport
import org.jetbrains.kotlin.cli.common.diagnosticsCollector
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.ir.InternalSymbolFinderAPI
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.KtDiagnosticReporterWithImplicitIrBasedContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrTypeSystemContext
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import org.jetbrains.kotlin.ir.util.SymbolTable

internal class DotNetBackendContext(
    override val irBuiltIns: IrBuiltIns,
    override val configuration: CompilerConfiguration,
    symbolTable: SymbolTable,
) : CommonBackendContext {
    override val irFactory: IrFactory = symbolTable.irFactory
    override val typeSystem: IrTypeSystemContext = IrTypeSystemContextImpl(irBuiltIns)
    override val symbols: DotNetSymbols = DotNetSymbols(irBuiltIns)
    override val sharedVariablesManager: SharedVariablesManager = DotNetSharedVariablesManager
    override val innerClassesSupport: InnerClassesSupport = DotNetInnerClassesSupport
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
internal class DotNetSymbols(irBuiltIns: IrBuiltIns) : BackendSymbols(irBuiltIns) {
    override val getProgressionLastElementByReturnType: Map<IrClassifierSymbol, IrSimpleFunctionSymbol> = emptyMap()
    override val syntheticConstructorMarker: IrClassSymbol
        get() = unsupportedSymbol("syntheticConstructorMarker")
    override val throwUninitializedPropertyAccessException: IrSimpleFunctionSymbol
        get() = unsupportedSymbol("throwUninitializedPropertyAccessException")
    override val throwUnsupportedOperationException: IrSimpleFunctionSymbol
        get() = unsupportedSymbol("throwUnsupportedOperationException")
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
    override val defaultConstructorMarker: IrClassSymbol
        get() = unsupportedSymbol("defaultConstructorMarker")

    private fun unsupportedSymbol(name: String): Nothing =
        error("DotNet backend symbol '$name' is not available yet")
}

private object DotNetSharedVariablesManager : SharedVariablesManager() {
    override fun declareSharedVariable(originalDeclaration: IrVariable): IrVariable =
        unsupportedSharedVariables()

    override fun defineSharedValue(originalDeclaration: IrVariable, sharedVariableDeclaration: IrVariable): IrStatement =
        unsupportedSharedVariables()

    override fun getSharedValue(sharedVariableSymbol: IrValueSymbol, originalGet: IrGetValue): IrExpression =
        unsupportedSharedVariables()

    override fun setSharedValue(sharedVariableSymbol: IrValueSymbol, originalSet: IrSetValue): IrExpression =
        unsupportedSharedVariables()

    private fun unsupportedSharedVariables(): Nothing =
        error("DotNet backend shared variable lowering is not available yet")
}

private object DotNetInnerClassesSupport : InnerClassesSupport {
    override fun getOuterThisField(innerClass: org.jetbrains.kotlin.ir.declarations.IrClass): IrField =
        unsupportedInnerClasses()

    override fun getInnerClassConstructorWithOuterThisParameter(innerClassConstructor: IrConstructor): IrConstructor =
        unsupportedInnerClasses()

    override fun getInnerClassOriginalPrimaryConstructorOrNull(innerClass: org.jetbrains.kotlin.ir.declarations.IrClass): IrConstructor? =
        unsupportedInnerClasses()

    private fun unsupportedInnerClasses(): Nothing =
        error("DotNet backend inner class lowering is not available yet")
}
