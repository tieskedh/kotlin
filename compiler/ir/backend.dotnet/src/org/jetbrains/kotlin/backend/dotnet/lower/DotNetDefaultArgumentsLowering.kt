/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.lower.DefaultArgumentStubGenerator
import org.jetbrains.kotlin.backend.common.lower.DefaultParameterCleaner
import org.jetbrains.kotlin.backend.common.lower.DefaultParameterInjector
import org.jetbrains.kotlin.backend.common.lower.MaskedDefaultArgumentFunctionFactory
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.ir.types.IrType

/**
 * The common/JVM masked-default shape for ordinary functions and member functions. Missing
 * arguments carry their CLR zero/null placeholder plus one bit per defaultable parameter, and a
 * generated `$default` function resolves the mask before calling the original declaration.
 *
 * Unlike the common factory default, parameter types do not become nullable merely to carry the
 * ignored placeholder. The mask, not the placeholder, owns absence; this is the JVM primitive
 * precedent and keeps CLR signatures stable. Reference slots can already contain null, while
 * value slots use their ordinary zero-initialized value.
 */
internal class DotNetDefaultArgumentFunctionFactory(context: DotNetBackendContext) :
    MaskedDefaultArgumentFunctionFactory(context) {
    override fun IrType.hasNullAsUndefinedValue(): Boolean = false
}

internal class DotNetDefaultArgumentStubGenerator(
    context: DotNetBackendContext,
    factory: DotNetDefaultArgumentFunctionFactory = DotNetDefaultArgumentFunctionFactory(context),
) :
    DefaultArgumentStubGenerator<DotNetBackendContext>(
        context,
        factory,
        skipExternalMethods = true,
    )

internal class DotNetDefaultParameterInjector(
    context: DotNetBackendContext,
    factory: DotNetDefaultArgumentFunctionFactory = DotNetDefaultArgumentFunctionFactory(context),
) :
    DefaultParameterInjector<DotNetBackendContext>(
        context,
        factory,
        skipExternalMethods = true,
    )

internal class DotNetDefaultParameterCleaner(
    context: DotNetBackendContext,
) : DefaultParameterCleaner(context)
