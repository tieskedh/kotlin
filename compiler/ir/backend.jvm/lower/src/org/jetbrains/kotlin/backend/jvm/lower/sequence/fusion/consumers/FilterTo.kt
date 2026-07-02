/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.consumers

import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.ConsumerBodyBuilder
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.FilterVersion
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.callRichFunctionReference
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.getPredicateArgument
import org.jetbrains.kotlin.backend.jvm.lower.sequence.fusion.producers.irAsNotNull
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.ir.util.functions

internal class FilterToConsumerStrategy(data: ConsumerData, expression: IrCall, val version: FilterVersion) :
    ConsumerStrategy(data, expression) {
    private var destinationVariable: IrVariable? = null
    override val returnsElement: Boolean = false

    override fun initializeState(): List<IrVariable> {
        val expression = expression as IrCall
        val destination = expression.arguments.getOrNull(1) ?: error("No destination argument for filterTo")
        destinationVariable = data.builder.scope.createTemporaryVariable(destination, "filterToDestination")
        return listOf(destinationVariable!!)
    }

    override fun getConsumerBuilder(): ConsumerBodyBuilder? {
        val addFunction = data.context.irBuiltIns.mutableCollectionClass.owner.functions.singleOrNull {
            it.name.asString() == "add" && it.parameters.size == 2
        } ?: return null
        val expression = expression as IrCall
        val predicate = if (version == FilterVersion.FilterNotNull) null else (getPredicateArgument(expression, 2) ?: return null)
        return { sequenceElement ->
            val builder = data.builder
            val parent = data.parent
            val invokeSymbol = expression.arguments.getOrNull(2)?.type?.classOrNull?.owner?.declarations
                ?.filterIsInstance<IrSimpleFunction>()
                ?.first { it.name.asString() == "invoke" }?.symbol
            with(builder) {
                val condition = when (predicate) {
                    is IrRichFunctionReference -> when (version) {
                        FilterVersion.Filter -> callRichFunctionReference(predicate, parent, irGet(sequenceElement))
                        FilterVersion.FilterNot -> irNot(callRichFunctionReference(predicate, parent, irGet(sequenceElement)))
                        FilterVersion.FilterNotNull -> error("FilterNotNullTo with a third argument: ${predicate.dump()}")
                    }
                    else -> when (version) {
                        FilterVersion.Filter -> invokeSymbol?.let {
                            irCall(it).apply {
                                dispatchReceiver = predicate!!
                                arguments[1] = irGet(sequenceElement)
                            }
                        }
                            ?: error("Didn't find invoke for the predicate argument of filterTo: ${predicate?.dump()}")
                        FilterVersion.FilterNot -> invokeSymbol?.let {
                            irNot(irCall(it).apply {
                                dispatchReceiver = predicate!!
                                arguments[1] = irGet(sequenceElement)
                            })
                        }
                            ?: error("Didn't find invoke for the predicate argument of filterTo: ${predicate?.dump()}")
                        FilterVersion.FilterNotNull -> if (predicate != null) error("FilterNotNullTo with a third argument: ${predicate.dump()}") else
                            irNot(irEquals(irGet(sequenceElement), irNull()))
                    }
                }
                val destinationAddCall = irCall(addFunction).apply {
                    arguments[0] = irGet(destinationVariable!!)
                    if (version == FilterVersion.FilterNotNull) {
                        arguments[1] = irAsNotNull(irGet(sequenceElement))
                    } else {
                        arguments[1] = irGet(sequenceElement)
                    }
                }
                /*
                if (filterCondition) {
                    destination.add(sequenceElement)
                }
                false
                 */
                irReturnableBlock(context.irBuiltIns.booleanType) {
                    +irIfThen(
                        context.irBuiltIns.unitType,
                        condition,
                        destinationAddCall
                    )
                    +IrReturnImpl(
                        startOffset = startOffset,
                        endOffset = endOffset,
                        type = context.irBuiltIns.nothingType,
                        returnTargetSymbol = returnableBlockSymbol,
                        value = irTrue()
                    )
                }
            }
        }
    }

    override fun finalizeResult(): IrExpression = data.builder.irGet(destinationVariable!!)

}
