/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Scoped proof obligation connecting a retained ordered result to its actual sealed MethodSpec.
 *
 * Placement authority is established before emission. This object neither selects nor repairs a
 * route: it merely requires the exact result call to consume the complete live call-edge seal
 * produced by ordinary emission. Calls belonging to prefix initializers or nested obligations are
 * ignored unless their exact IR identity occurs in this obligation.
 */
internal class DotNetGenericOwnerPhysicalDirectResultEmissionObligation(
    expectedCalls: List<DotNetGenericOwnerPhysicalValueBoundDirectResultCallSite>,
    expectedPrefixes: List<DotNetGenericOwnerPhysicalValueBoundSequentialPrefix> = emptyList(),
) {
    private val expectedByCall = IdentityHashMap<
            IrCall,
            DotNetGenericOwnerPhysicalValueBoundDirectResultCallSite,
            >()
    private val consumed = Collections.newSetFromMap(IdentityHashMap<IrCall, Boolean>())
    private val expectedPrefixes = expectedPrefixes.toList()
    private val expectedPrefixIdentities = Collections.newSetFromMap(
        IdentityHashMap<IrVariable, Boolean>(),
    )
    private var consumedPrefixCount = 0

    init {
        require(expectedCalls.isNotEmpty()) {
            "a direct-result emission obligation requires at least one sealed call"
        }
        expectedCalls.forEach { site ->
            require(site.boundCall.methodArgumentTypes.isNotEmpty()) {
                "a direct-result seal obligation is restricted to MethodSpec leaves"
            }
            require(expectedByCall.put(site.call, site) == null) {
                "one exact result call cannot occur twice in an emission obligation"
            }
        }
        this.expectedPrefixes.forEach { prefix ->
            require(expectedPrefixIdentities.add(prefix.variable)) {
                "one exact prefix definition cannot occur twice in an emission obligation"
            }
        }
    }

    fun expects(call: IrCall): Boolean = expectedByCall.containsKey(call)

    fun expectsPrefix(variable: IrVariable): Boolean =
        variable in expectedPrefixIdentities

    fun consumePrefix(
        variable: IrVariable,
        placement: DotNetGenericOwnerPhysicalValueRetainedProducedCarrier?,
        storageType: DotNetIlValueType,
    ) {
        val expected = expectedPrefixes.getOrNull(consumedPrefixCount)
        check(expected?.variable === variable) {
            "Internal .NET backend error: ordered direct-result prefixes emitted out of order"
        }
        check(placement === expected.placement && storageType == expected.storageType) {
            "Internal .NET backend error: an ordered direct-result prefix emitted without its " +
                    "identity-bound physical placement"
        }
        consumedPrefixCount++
    }

    fun consume(
        call: IrCall,
        route: DotNetGenericOwnerPhysicalOperationRoute,
        sealed: DotNetGenericOwnerPhysicalOperationSealedCallEdge,
    ) {
        val expected = expectedByCall[call] ?: return
        check(consumedPrefixCount == expectedPrefixes.size) {
            "Internal .NET backend error: an ordered direct-result call emitted before all prefixes"
        }
        check(route === expected.operation) {
            "Internal .NET backend error: an ordered direct-result call consumed a different " +
                    "authoritative operation"
        }
        check(consumed.add(call)) {
            "Internal .NET backend error: an ordered direct-result call consumed its sealed " +
                    "edge more than once"
        }
        val bound = expected.boundCall
        check(sealed.method.sameLocalMethodIdentityAs(bound.methodIdentity) &&
                sealed.receiverType == bound.receiverType &&
                sealed.methodArguments == bound.methodArgumentTypes &&
                sealed.parameterTypes == bound.parameterTypes &&
                sealed.returnType == DotNetIlReturnType.Value(bound.resultType) &&
                !sealed.hasSplitNullableResult
        ) {
            "Internal .NET backend error: an ordered direct-result call consumed a sealed edge " +
                    "that contradicts its retained physical call"
        }
    }

    fun requireComplete() {
        check(consumedPrefixCount == expectedPrefixes.size &&
                consumed.size == expectedByCall.size) {
            "Internal .NET backend error: ordered direct-result emission completed without " +
                    "observing every prefix and required MethodSpec call-edge seal"
        }
    }
}
