/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturnableBlock
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.util.isFalseConst
import org.jetbrains.kotlin.ir.util.isTrueConst
import java.util.Collections
import java.util.IdentityHashMap

/** One exact call which supplies a direct initializer result on one reachable path. */
internal data class DotNetGenericOwnerPhysicalDirectResultCallSite(
    val call: IrCall,
    val hasImplicitNotNull: Boolean,
)

/**
 * Identity-bound description of every call which can supply one direct initializer result.
 *
 * This is a result-path plan, not a recursive call inventory. Conditions, receivers, and call
 * arguments execute normally but do not supply the enclosing value. Result-only blocks and
 * composites contribute no physical fact of their own; they merely preserve their sole
 * expression. Every reachable branch of an exhaustive [IrWhen] must end in an admitted call.
 */
internal class DotNetGenericOwnerPhysicalDirectResultInitializerPlan private constructor(
    private val initializer: IrExpression,
    private val resultPathNodesInTraversalOrder: List<IrExpression>,
    private val sequentialPrefixStatementsInEvaluationOrder: List<IrStatement>,
    val sequentialPrefixVariablesInEvaluationOrder: List<IrVariable>,
    val resultAfterSequentialPrefixes: IrExpression,
    val physicalResultAfterSequentialPrefixes: IrExpression?,
    val callsInEvaluationOrder: List<DotNetGenericOwnerPhysicalDirectResultCallSite>,
) {
    init {
        require(resultPathNodesInTraversalOrder.isNotEmpty()) {
            "a direct-result initializer plan requires at least one result-path node"
        }
        require(callsInEvaluationOrder.isNotEmpty()) {
            "a direct-result initializer plan requires at least one call"
        }
        require(
            sequentialPrefixVariablesInEvaluationOrder.all { variable -> !variable.isVar },
        ) {
            "a direct-result prefix plan may contain only immutable local definitions"
        }
    }

    val hasSequentialPrefixes: Boolean
        get() = sequentialPrefixStatementsInEvaluationOrder.isNotEmpty()

    /**
     * The first ordered gate: two exact aliases consumed as receiver and sole input.
     *
     * This is captured with the plan rather than recomputed from the mutable [IrCall]. A later
     * in-place receiver/argument rewrite must make [matchesLiveInitializer] fail instead of
     * silently changing what the retained plan means.
     */
    val hasSequentialReceiverAndInputPrefixPair: Boolean =
        callsInEvaluationOrder.singleOrNull()?.call?.let { call ->
            sequentialPrefixVariablesInEvaluationOrder.size == 2 &&
                    physicalResultAfterSequentialPrefixes === call &&
                    (call.dispatchReceiver as? IrGetValue)?.symbol ===
                    sequentialPrefixVariablesInEvaluationOrder[0].symbol &&
                    (call.arguments.getOrNull(1) as? IrGetValue)?.symbol ===
                    sequentialPrefixVariablesInEvaluationOrder[1].symbol
        } == true

    /** Rewalks the live tree and rejects a replaced root, spine, branch, or call identity. */
    fun matchesLiveInitializer(liveInitializer: IrExpression): Boolean {
        if (liveInitializer !== initializer) return false
        val live = liveInitializer.dotNetPhysicalDirectResultInitializerPlanOrNull()
            ?: return false
        if (live.hasSequentialReceiverAndInputPrefixPair !=
            hasSequentialReceiverAndInputPrefixPair
        ) return false
        return live.resultPathNodesInTraversalOrder.hasSameIdentitySequence(
            resultPathNodesInTraversalOrder,
        ) && live.sequentialPrefixStatementsInEvaluationOrder.hasSameIdentitySequence(
            sequentialPrefixStatementsInEvaluationOrder,
        ) && live.sequentialPrefixVariablesInEvaluationOrder.hasSameIdentitySequence(
            sequentialPrefixVariablesInEvaluationOrder,
        ) && live.resultAfterSequentialPrefixes === resultAfterSequentialPrefixes &&
                live.physicalResultAfterSequentialPrefixes ===
                physicalResultAfterSequentialPrefixes &&
                live.callsInEvaluationOrder.size == callsInEvaluationOrder.size &&
                live.callsInEvaluationOrder.indices.all { index ->
                    val expected = callsInEvaluationOrder[index]
                    val actual = live.callsInEvaluationOrder[index]
                    actual.call === expected.call &&
                            actual.hasImplicitNotNull == expected.hasImplicitNotNull
                }
    }

    companion object {
        fun createOrNull(
            initializer: IrExpression,
        ): DotNetGenericOwnerPhysicalDirectResultInitializerPlan? {
            val nodes = mutableListOf<IrExpression>()
            val prefixStatements = mutableListOf<IrStatement>()
            val prefixVariables = mutableListOf<IrVariable>()
            val calls = mutableListOf<DotNetGenericOwnerPhysicalDirectResultCallSite>()
            val seenCalls = IdentityHashMap<IrCall, Unit>()
            var resultAfterPrefixes = initializer

            fun collectPrefix(statement: IrStatement): Boolean {
                prefixStatements += statement
                return when (statement) {
                    is IrVariable -> {
                        if (statement.isVar ||
                            statement.initializer !is IrGetValue
                        ) return false
                        prefixVariables += statement
                        true
                    }
                    else -> false
                }
            }

            fun collect(
                expression: IrExpression,
                hasImplicitNotNull: Boolean,
                allowSequentialPrefixes: Boolean,
            ): Boolean {
                nodes += expression
                return when (expression) {
                    is IrCall -> {
                        if (seenCalls.put(expression, Unit) != null) return false
                        calls += DotNetGenericOwnerPhysicalDirectResultCallSite(
                            expression,
                            hasImplicitNotNull,
                        )
                        true
                    }
                    is IrTypeOperatorCall -> if (
                        expression.operator == IrTypeOperator.IMPLICIT_CAST ||
                        expression.operator == IrTypeOperator.IMPLICIT_NOTNULL
                    ) {
                        val prefixesRemainLinear = allowSequentialPrefixes &&
                                expression.operator == IrTypeOperator.IMPLICIT_CAST
                        collect(
                            expression.argument,
                            hasImplicitNotNull ||
                                    expression.operator == IrTypeOperator.IMPLICIT_NOTNULL,
                            allowSequentialPrefixes = prefixesRemainLinear,
                        )
                    } else {
                        false
                    }
                    is IrBlock -> if (expression is IrReturnableBlock) {
                        false
                    } else {
                        val result = expression.statements.lastOrNull() as? IrExpression
                            ?: return false
                        val prefixes = expression.statements.dropLast(1)
                        if (prefixes.isNotEmpty()) {
                            if (!allowSequentialPrefixes || !prefixes.all(::collectPrefix)) {
                                return false
                            }
                            resultAfterPrefixes = result
                        }
                        collect(
                            result,
                            hasImplicitNotNull,
                            allowSequentialPrefixes,
                        )
                    }
                    is IrComposite -> {
                        val result = expression.statements.lastOrNull() as? IrExpression
                            ?: return false
                        val prefixes = expression.statements.dropLast(1)
                        if (prefixes.isNotEmpty()) {
                            if (!allowSequentialPrefixes || !prefixes.all(::collectPrefix)) {
                                return false
                            }
                            resultAfterPrefixes = result
                        }
                        collect(
                            result,
                            hasImplicitNotNull,
                            allowSequentialPrefixes,
                        )
                    }
                    is IrWhen -> {
                        var hasElse = false
                        var reachableBranchCount = 0
                        for (indexedBranch in expression.branches.withIndex()) {
                            val index = indexedBranch.index
                            val branch = indexedBranch.value
                            if (branch.condition.isFalseConst()) continue
                            if (!collect(
                                    branch.result,
                                    hasImplicitNotNull,
                                    allowSequentialPrefixes = false,
                                )
                            ) return false
                            reachableBranchCount++
                            if (branch.condition.isTrueConst()) {
                                if (expression.branches.drop(index + 1)
                                        .any { later -> !later.condition.isFalseConst() }
                                ) return false
                                hasElse = true
                                break
                            }
                        }
                        hasElse && reachableBranchCount >= 2
                    }
                    else -> false
                }
            }

            if (!collect(
                    initializer,
                    hasImplicitNotNull = false,
                    allowSequentialPrefixes = true,
                )
            ) return null
            val uniquePrefixes = Collections.newSetFromMap(
                IdentityHashMap<IrVariable, Boolean>(),
            )
            if (prefixVariables.any { variable -> !uniquePrefixes.add(variable) }) return null
            val physicalResult = calls.singleOrNull()?.call?.let { call ->
                val resultOperators = nodes.filterIsInstance<IrTypeOperatorCall>()
                val outer = resultOperators.getOrNull(0)
                val inner = resultOperators.getOrNull(1)
                call.takeIf {
                    prefixVariables.isNotEmpty() && resultOperators.size == 2 &&
                            outer?.operator == IrTypeOperator.IMPLICIT_CAST &&
                            inner?.operator == IrTypeOperator.IMPLICIT_CAST &&
                            outer.type == call.type &&
                            outer.typeOperand == call.type &&
                            resultAfterPrefixes === inner &&
                            outer.argument.type.isNullableAny() &&
                            inner.type.isNullableAny() &&
                            inner.typeOperand.isNullableAny() &&
                            inner.argument === call &&
                            inner.argument.type == outer.type
                }
            }
            return DotNetGenericOwnerPhysicalDirectResultInitializerPlan(
                initializer,
                nodes,
                prefixStatements,
                prefixVariables,
                resultAfterPrefixes,
                physicalResult,
                calls,
            )
        }
    }
}

internal fun IrExpression.dotNetPhysicalDirectResultInitializerPlanOrNull():
        DotNetGenericOwnerPhysicalDirectResultInitializerPlan? =
    DotNetGenericOwnerPhysicalDirectResultInitializerPlan.createOrNull(this)

private fun <T : Any> List<T>.hasSameIdentitySequence(other: List<T>): Boolean =
    size == other.size && indices.all { index -> this[index] === other[index] }

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
