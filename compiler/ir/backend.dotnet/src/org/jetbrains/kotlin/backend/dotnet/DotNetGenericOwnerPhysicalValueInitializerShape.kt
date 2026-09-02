/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrReturnableBlock
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.util.isFalseConst
import org.jetbrains.kotlin.ir.util.isTrueConst
import java.util.IdentityHashMap

/**
 * Returns the one operation carried by a control-flow arm without admitting a general block.
 *
 * FIR2IR represents an ordinary source `if` arm as a single-expression [IrBlock]. That container
 * has no storage, control-flow, conversion, or side-effect semantics beyond its sole expression,
 * so it preserves the exact [IrCall] identity. Any declaration, second statement, conversion,
 * nested branch, `try`, null, or bottom expression remains outside this bounded grammar.
 */
internal fun IrExpression.dotNetSingleSplitOperationCallOrNull(): IrCall? = when (this) {
    is IrCall -> this
    is IrBlock -> if (this is IrReturnableBlock) {
        null
    } else {
        (statements.singleOrNull() as? IrCall)?.takeIf { call -> type == call.type }
    }
    else -> null
}

/**
 * Collects the exact reachable calls of the first flat, exhaustive split-operation initializer.
 *
 * The result is ordered for emission and deduplicated by IR identity. A true condition is the
 * terminal else; non-false branches after it make the shape unavailable rather than silently
 * changing the operation plan.
 */
internal fun IrWhen.dotNetFlatExhaustiveSplitOperationCallsOrNull(): List<IrCall>? {
    val calls = mutableListOf<IrCall>()
    val seenCalls = IdentityHashMap<IrCall, Unit>()
    var hasElse = false
    for (indexedBranch in branches.withIndex()) {
        val index = indexedBranch.index
        val branch = indexedBranch.value
        if (branch.condition.isFalseConst()) continue
        val call = branch.result.dotNetSingleSplitOperationCallOrNull() ?: return null
        if (seenCalls.put(call, Unit) != null) return null
        calls += call
        if (branch.condition.isTrueConst()) {
            if (branches.drop(index + 1).any { later -> !later.condition.isFalseConst() }) {
                return null
            }
            hasElse = true
            break
        }
    }
    return calls.takeIf { hasElse && it.size >= 2 }
}
