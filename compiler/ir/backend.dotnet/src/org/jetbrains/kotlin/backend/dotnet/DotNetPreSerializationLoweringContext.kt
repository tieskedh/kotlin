/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.backend.common.PreSerializationLoweringContext
import org.jetbrains.kotlin.backend.common.ModuleLoweringPass
import org.jetbrains.kotlin.backend.common.ir.KlibSharedVariablesManager
import org.jetbrains.kotlin.backend.common.ir.PreSerializationKlibSymbols
import org.jetbrains.kotlin.backend.common.lower.UpgradeCallableReferences
import org.jetbrains.kotlin.backend.common.phaser.createModulePhases
import org.jetbrains.kotlin.backend.dotnet.serialization.DotNetIrMangler
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.phaser.NamedCompilerPhase
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrDiagnosticReporter
import org.jetbrains.kotlin.ir.InternalSymbolFinderAPI
import org.jetbrains.kotlin.ir.classSymbolOrNull
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.inline.loweringsOfTheFirstPhase
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.util.KotlinMangler
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

@OptIn(InternalSymbolFinderAPI::class)
fun IrBuiltIns.hasDotNetPreSerializationLoweringSymbols(): Boolean = with(this) {
    fun hasKotlinInternalClass(name: String): Boolean =
        ClassId(StandardNames.KOTLIN_INTERNAL_FQ_NAME, Name.identifier(name)).classSymbolOrNull() != null

    hasKotlinInternalClass("SharedVariableBox") && hasKotlinInternalClass("SyntheticConstructorMarker")
}

class DotNetPreSerializationLoweringContext(
    irBuiltIns: IrBuiltIns,
    configuration: CompilerConfiguration,
    diagnosticReporter: IrDiagnosticReporter,
) : PreSerializationLoweringContext(irBuiltIns, configuration, diagnosticReporter) {
    override val symbols: PreSerializationKlibSymbols = DotNetPreSerializationSymbols(irBuiltIns)
    override val sharedVariablesManager = KlibSharedVariablesManager(symbols)
    override val irMangler: KotlinMangler.IrMangler = DotNetIrMangler
    override val linkInlineFunctionReferencesFromMainIr: Boolean = true
    // The .NET builtin array intrinsics are bodyless reified compiler declarations. Keep them
    // intact during KLIB production; the target-stage inliner still expands every Kotlin-owned
    // reified body, while target codegen consumes these compiler-owned calls after substitution.
    override val supportsReifiedInlineFunctions: Boolean = false
}

fun dotNetLoweringsOfTheFirstPhase(
    languageVersionSettings: LanguageVersionSettings,
): List<NamedCompilerPhase<DotNetPreSerializationLoweringContext, IrModuleFragment, IrModuleFragment>> {
    val lowerings = buildList<(DotNetPreSerializationLoweringContext) -> ModuleLoweringPass> {
        if (languageVersionSettings.supportsFeature(LanguageFeature.IrRichCallableReferencesInKlibs)) {
            add(::createUpgradeCallableReferences)
        }
        // `lateinit` remains a separately parked .NET feature: its Common lowering requires the
        // target throw-helper contract that DotNetBackendContext deliberately does not expose yet.
        addAll(loweringsOfTheFirstPhase(languageVersionSettings, includeLateinitLowering = false))
    }
    return createModulePhases(*lowerings.toTypedArray())
}

private fun createUpgradeCallableReferences(context: DotNetPreSerializationLoweringContext): UpgradeCallableReferences =
    UpgradeCallableReferences(context, upgradeSamConversions = false)

private class DotNetPreSerializationSymbols(irBuiltIns: IrBuiltIns) : PreSerializationKlibSymbols.Impl(irBuiltIns) {
    // Ordinary inlining compares every callee with these symbols. Keep non-matching sentinels;
    // the target's separate suspend-shape gate still rejects actual coroutine declarations.
    override val coroutineContextGetter: IrSimpleFunctionSymbol = IrSimpleFunctionSymbolImpl()
    override val suspendCoroutineUninterceptedOrReturn: IrSimpleFunctionSymbol = IrSimpleFunctionSymbolImpl()
    override val coroutineGetContext: IrSimpleFunctionSymbol = IrSimpleFunctionSymbolImpl()
}
