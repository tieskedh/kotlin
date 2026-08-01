/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower.inline

import org.jetbrains.kotlin.backend.common.serialization.NonLinkingIrInlineFunctionDeserializer
import org.jetbrains.kotlin.backend.common.serialization.signature.PublicIdSignatureComputer
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.serialization.DotNetIrMangler
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.inline.FunctionInlining
import org.jetbrains.kotlin.ir.inline.InlineFunctionResolver
import org.jetbrains.kotlin.ir.inline.InlineMode
import org.jetbrains.kotlin.ir.overrides.isEffectivelyPrivate
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideOrSelf

/**
 * Resolves ordinary inline bodies without linking a dependency's complete IR graph.
 *
 * Prepared inline IR is authoritative when present. The main IR fallback keeps libraries
 * produced with `-Xklib-ir-inliner=disabled` consumable; it remains opt-in here so this target
 * does not silently change another KLIB backend's deserialization policy.
 */
private class DotNetInlineFunctionResolver(
    private val context: DotNetBackendContext,
    private val inlineMode: InlineMode,
) : InlineFunctionResolver() {
    private val deserializer = NonLinkingIrInlineFunctionDeserializer(
        irBuiltIns = context.irBuiltIns,
        signatureComputer = PublicIdSignatureComputer(DotNetIrMangler),
        fallbackToMainIr = true,
    )

    override fun getFunctionDeclaration(symbol: IrFunctionSymbol): IrFunction? {
        if (!symbol.isBound) return null
        val function = symbol.owner.resolveFakeOverrideOrSelf()
        if (!function.isInline || function.typeParameters.any { it.isReified }) return null
        if (inlineMode == InlineMode.PRIVATE_INLINE_FUNCTIONS && !function.isEffectivelyPrivate()) return null
        if (function.body != null || function !is IrSimpleFunction) return function
        if (inlineMode != InlineMode.ALL_INLINE_FUNCTIONS || function.isFakeOverride) return null
        return deserializer.deserializeInlineFunction(function)
    }
}

internal class DotNetPrivateFunctionInlining(context: DotNetBackendContext) : FunctionInlining(
    context,
    DotNetInlineFunctionResolver(context, InlineMode.PRIVATE_INLINE_FUNCTIONS),
)

internal class DotNetAllFunctionInlining(context: DotNetBackendContext) : FunctionInlining(
    context,
    DotNetInlineFunctionResolver(context, InlineMode.ALL_INLINE_FUNCTIONS),
)
