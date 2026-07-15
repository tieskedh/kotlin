/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.lower.FlattenStringConcatenationLowering
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.ir.expressions.IrConst

/**
 * [FlattenStringConcatenationLowering] with compile-time folding disabled for floating-point
 * constants.
 *
 * The JVM target folds every constant with the host `toString`, which is correct there because
 * host and target renderings coincide. On the CLR they do not always: `Double` is rendered at
 * runtime by the `DotNetRuntimeLibraryHelpers` double-formatting helper, which matches the host
 * rendering on common values but diverges on the digit-count classes documented on the helper
 * (e.g. host
 * `3.141592653589793` vs runtime `3.1415926535897931`). Folding a constant `Double` with
 * the host rendering would therefore make `"v=" + 1.2345678E7` and `"v=" + d` print differently
 * for the same value. Keeping the constant as a runtime concatenation argument routes it through
 * the same emission as non-constant values.
 *
 * `Float` is excluded for the fail-hard rule rather than for rendering: the type is deferred in
 * this backend, and folding would silently compile a `Float` constant with the host rendering
 * while every non-constant `Float` use is rejected. Unfolded, the constant reaches IL codegen
 * and fails there as unsupported.
 */
internal class DotNetFlattenStringConcatenationLowering(
    context: DotNetBackendContext,
) : FlattenStringConcatenationLowering(context) {
    override fun isFoldableConstant(const: IrConst): Boolean =
        const.value !is Double && const.value !is Float
}
