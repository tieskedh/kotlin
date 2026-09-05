/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

/** One exact pre-final-lowering write site. Copies retain the same identity and are then rejected. */
private class DotNetBoundGenericOwnerStateWriteLineage(
    val field: IrFieldSymbol,
    val producer: IrDeclaration,
    val origin: IrStatementOrigin?,
    val valueType: IrType,
    val typedValue: IrValueSymbol?,
)

private data class DotNetBoundGenericOwnerStateInitializerLineage(
    val constructor: IrConstructor,
    val parameterIndex: Int,
    val valueType: IrType,
)

/** Exact BOUND write identity retained by shared deep copies; it is not a value-provenance fact. */
private var IrSetField.dotNetBoundGenericOwnerStateWrite: DotNetBoundGenericOwnerStateWriteLineage?
        by irAttribute(copyByDefault = true)

/** The selected field survives every later pass, so it owns the immutable expected write set. */
private var IrField.dotNetBoundGenericOwnerStateWrites: List<DotNetBoundGenericOwnerStateWriteLineage>?
        by irAttribute(copyByDefault = false)

private var IrField.dotNetBoundGenericOwnerStateInitializer:
        DotNetBoundGenericOwnerStateInitializerLineage? by irAttribute(copyByDefault = false)

/**
 * Freezes the already planned writer set before later body-producing lowerings run.
 *
 * A copied write retains this exact field-symbol lineage. Later initializers have one separately
 * admitted Common shape; every other new or retargeted physical store fails closed at the final
 * boundary instead of silently changing a producer-wide FieldDef decision.
 */
internal fun DotNetLocalGenericOwnerPhysicalAuthority.markBoundGenericOwnerStateWrites(
    module: IrModuleFragment,
) {
    val typedWriteValuesByField = stateFamilies()
        .flatMap(DotNetLocalGenericOwnerPhysicalStateFamilyInput::states)
        .filter { state ->
            state.requirement == DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN
        }
        .associate { state ->
            state.field to state.typedWriterParameters
                .mapTo(linkedSetOf()) { input -> input.parameter }
        }
    val writesByField = stateFamilies()
        .flatMap(DotNetLocalGenericOwnerPhysicalStateFamilyInput::states)
        .associate { state -> state.field to mutableListOf<DotNetBoundGenericOwnerStateWriteLineage>() }
    var containingProducer: IrDeclaration? = null
    module.acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) {
            declaration.withProducer { declaration.acceptChildrenVoid(this) }
        }

        override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer) {
            declaration.withProducer { declaration.acceptChildrenVoid(this) }
        }

        override fun visitField(declaration: IrField) {
            declaration.withProducer { declaration.acceptChildrenVoid(this) }
        }

        override fun visitSetField(expression: IrSetField) {
            val state = stateOrNull(expression.symbol)
            if (state != null) {
                val previous = expression.dotNetBoundGenericOwnerStateWrite
                check(previous == null) {
                    "Internal .NET backend error: generic-owner state writes were marked more than once"
                }
                val producer = checkNotNull(containingProducer) {
                    "Internal .NET backend error: generic-owner state write has no producer"
                }
                val typedValue = if (state.requirement ==
                    DotNetGenericOwnerStateCarrierRequirement.TYPED_STORAGE_PRODUCER_GRAPH_PROVEN
                ) {
                    val value = expression.value as? IrGetValue
                        ?: error("Internal .NET backend error: BOUND typed state lost its exact input")
                    check(value.symbol in typedWriteValuesByField.getValue(expression.symbol)) {
                        "Internal .NET backend error: BOUND typed state write lacks physical parameter authority"
                    }
                    value.symbol
                } else {
                    null
                }
                val lineage = DotNetBoundGenericOwnerStateWriteLineage(
                    field = expression.symbol,
                    producer = producer,
                    origin = expression.origin,
                    valueType = expression.value.type,
                    typedValue = typedValue,
                )
                expression.dotNetBoundGenericOwnerStateWrite = lineage
                writesByField.getValue(expression.symbol) += lineage
            }
            expression.acceptChildrenVoid(this)
        }

        private inline fun IrDeclaration.withProducer(body: () -> Unit) {
            val previous = containingProducer
            containingProducer = this
            body()
            containingProducer = previous
        }
    })
    stateFamilies().flatMap(DotNetLocalGenericOwnerPhysicalStateFamilyInput::states).forEach { state ->
        val field = state.field.owner
        check(field.dotNetBoundGenericOwnerStateWrites == null &&
                field.dotNetBoundGenericOwnerStateInitializer == null) {
            "Internal .NET backend error: generic-owner state lineage was frozen more than once"
        }
        field.dotNetBoundGenericOwnerStateWrites = writesByField.getValue(state.field).toList()
        if (state.hasImplicitFieldInitializer) {
            val initializer = field.initializer?.expression as? IrGetValue
                ?: error("Internal .NET backend error: BOUND state initializer is not a constructor parameter")
            val constructor = initializer.symbol.owner.parent as? IrConstructor
                ?: error("Internal .NET backend error: BOUND state initializer has no constructor")
            val parameterIndex = constructor.parameters.indexOfFirst { parameter ->
                parameter.symbol == initializer.symbol
            }
            check(constructor.parent === field.parent && parameterIndex >= 0) {
                "Internal .NET backend error: BOUND state initializer escaped its exact owner"
            }
            field.dotNetBoundGenericOwnerStateInitializer =
                DotNetBoundGenericOwnerStateInitializerLineage(
                    constructor = constructor,
                    parameterIndex = parameterIndex,
                    valueType = initializer.type,
                )
        }
    }
}

/** Validates that no post-BOUND pass introduced or retargeted a selected physical state write. */
internal fun DotNetLocalGenericOwnerPhysicalAuthority.validateFinalGenericOwnerStateWrites(
    module: IrModuleFragment,
) {
    stateFamilies().forEach { family ->
        val owner = family.owner.owner.owner
        val finalInstanceFields = owner.directInstanceFields().mapTo(linkedSetOf(), IrField::symbol)
        check(finalInstanceFields == family.boundInstanceFields) {
            "Internal .NET backend error: post-BOUND lowering changed the complete instance-field " +
                    "set of generic-owner state '${owner.name}'"
        }
    }
    var containingConstructor: IrConstructor? = null
    var containingProducer: IrDeclaration? = null
    val writeOccurrences = linkedMapOf<DotNetBoundGenericOwnerStateWriteLineage, Int>()
    val initializerOccurrences = linkedMapOf<IrFieldSymbol, Int>()
    module.acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunction(declaration: IrFunction) {
            declaration.withProducer { declaration.acceptChildrenVoid(this) }
        }

        override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer) {
            declaration.withProducer { declaration.acceptChildrenVoid(this) }
        }

        override fun visitConstructor(declaration: IrConstructor) {
            val previous = containingConstructor
            containingConstructor = declaration
            declaration.withProducer { declaration.acceptChildrenVoid(this) }
            containingConstructor = previous
        }

        override fun visitField(declaration: IrField) {
            val state = stateOrNull(declaration.symbol)
            check(state == null || declaration.initializer == null) {
                "Internal .NET backend error: selected generic-owner state field " +
                        "'${state?.logicalFieldName}' retained or acquired a final field initializer"
            }
            declaration.withProducer { declaration.acceptChildrenVoid(this) }
        }

        override fun visitSetField(expression: IrSetField) {
            val lineage = expression.dotNetBoundGenericOwnerStateWrite
            if (lineage != null) {
                check(lineage.field == expression.symbol && lineage.producer === containingProducer &&
                        lineage.origin == expression.origin && lineage.valueType == expression.value.type &&
                        (lineage.typedValue == null ||
                                (expression.value as? IrGetValue)?.symbol == lineage.typedValue)) {
                    "Internal .NET backend error: a BOUND generic-owner state write changed target, " +
                            "producer, origin, or verifier-visible value type"
                }
                writeOccurrences[lineage] = writeOccurrences.getOrDefault(lineage, 0) + 1
            }
            val state = stateOrNull(expression.symbol)
            if (state != null && lineage == null) {
                if (!expression.isRecordedImplicitInitializer(state)) {
                    error(
                        "Internal .NET backend error: a post-BOUND lowering introduced an unauthorized " +
                                "write to generic-owner state '${state.logicalFieldName}'",
                    )
                }
                initializerOccurrences[expression.symbol] =
                    initializerOccurrences.getOrDefault(expression.symbol, 0) + 1
            }
            expression.acceptChildrenVoid(this)
        }

        private fun IrSetField.isRecordedImplicitInitializer(
            state: DotNetLocalGenericOwnerPhysicalStateInput,
        ): Boolean {
            val expected = state.field.owner.dotNetBoundGenericOwnerStateInitializer
                ?: return false
            if (origin != IrStatementOrigin.INITIALIZE_FIELD) {
                return false
            }
            val owner = (state.fieldDefinition.declaringType as?
                    DotNetGenericOwnerPhysicalTypeDefIdentity.Local)?.owner?.owner ?: return false
            val constructor = containingConstructor ?: return false
            val receiver = receiver as? IrGetValue ?: return false
            val value = value as? IrGetValue ?: return false
            return constructor === expected.constructor && constructor.parent === owner &&
                    receiver.symbol == owner.thisReceiver?.symbol &&
                    value.symbol == constructor.parameters[expected.parameterIndex].symbol &&
                    value.type == expected.valueType
        }

        private inline fun IrDeclaration.withProducer(body: () -> Unit) {
            val previous = containingProducer
            containingProducer = this
            body()
            containingProducer = previous
        }
    })
    stateFamilies().flatMap(DotNetLocalGenericOwnerPhysicalStateFamilyInput::states).forEach { state ->
        val field = state.field.owner
        val expectedWrites = checkNotNull(field.dotNetBoundGenericOwnerStateWrites) {
            "Internal .NET backend error: selected generic-owner state lost its BOUND writer set"
        }
        check(expectedWrites.all { lineage -> writeOccurrences[lineage] == 1 }) {
            "Internal .NET backend error: a BOUND generic-owner state write was removed or duplicated"
        }
        val expectedInitializers = if (field.dotNetBoundGenericOwnerStateInitializer == null) 0 else 1
        check(initializerOccurrences.getOrDefault(state.field, 0) == expectedInitializers) {
            "Internal .NET backend error: generic-owner state initializer was removed or duplicated"
        }
    }
}

private fun IrClass.directInstanceFields(): Set<IrField> =
    declarations.flatMapTo(linkedSetOf()) { declaration ->
        when (declaration) {
            is IrField -> listOf(declaration)
            is IrProperty -> listOfNotNull(declaration.backingField)
            else -> emptyList()
        }
    }.filterTo(linkedSetOf()) { field -> !field.isStatic }
