/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.lower.ReturnableBlockTransformer
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSymbolOwner
import org.jetbrains.kotlin.ir.expressions.IrBody

/** Replaces returnable blocks introduced by common inlining with ordinary blocks or loops. */
internal class DotNetReturnableBlockLowering(
    private val context: DotNetBackendContext,
) : BodyLoweringPass {
    override fun lower(irBody: IrBody, container: IrDeclaration) {
        container.transform(ReturnableBlockTransformer(context, (container as IrSymbolOwner).symbol), null)
    }
}
