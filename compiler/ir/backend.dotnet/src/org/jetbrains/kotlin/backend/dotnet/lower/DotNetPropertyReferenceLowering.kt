/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.lower.AbstractPropertyReferenceLowering
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.dotNetUnsupported
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrRichPropertyReference
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.properties

/**
 * Follows the Native/Wasm wrapper model: a KProperty object stores ordinary lowered getter and
 * optional setter callables. The runtime identities are CLR-specific erased interfaces, so this
 * lowering never introduces a second typed callable identity.
 */
internal class DotNetPropertyReferenceLowering(context: DotNetBackendContext) :
    AbstractPropertyReferenceLowering<DotNetBackendContext>(context) {

    private val backendContext = context
    private val kTypeBuilder = DotNetKTypeIrBuilder(context, operation = "callable signature")

    private val hasSignatureSurface: Boolean
        get() = backendContext.irBuiltIns.kCallableClass.owner.properties
            .mapTo(linkedSetOf()) { property -> property.name.asString() }
            .containsAll(listOf("returnType", "typeParameters"))

    override fun functionReferenceClass(arity: Int): IrClassSymbol =
        context.irBuiltIns.functionN(arity).symbol

    override fun IrBuilderWithScope.createKProperty(
        reference: IrRichPropertyReference,
        typeArguments: List<IrType>,
        getterReference: IrRichFunctionReference,
        setterReference: IrRichFunctionReference?,
    ): IrExpression {
        val arity = typeArguments.size - 1
        val factory = backendContext.propertyReferenceSymbols.factory(arity, setterReference != null)
        val call = irCall(factory.symbol, reference.type, typeArguments) as IrCall
        return call.apply {
            arguments[0] = propertyReferenceNameExpression(reference)
            arguments[1] = getterReference
            setterReference?.let { arguments[2] = it }
            arguments[arguments.lastIndex - 1] = if (hasSignatureSurface) {
                val property = reference.reflectionTargetSymbol?.owner as? IrProperty
                    ?: error("Internal .NET backend error: KProperty return type has no property target")
                val getter = property.getter
                    ?: error("Internal .NET backend error: reflected property '${property.name}' has no getter")
                kTypeBuilder.run { buildCallableSignature(getter.returnType, getter.typeParameters) }
            } else {
                irNull()
            }
            arguments[arguments.lastIndex] = irCall(backendContext.callableAnnotationSymbols.empty)
            dotNetPropertyAnnotationOwner = reference.reflectionTargetSymbol?.owner as? IrAnnotationContainer
        }
    }

    override fun IrBuilderWithScope.createLocalKProperty(
        reference: IrRichPropertyReference,
        propertyName: String,
        propertyType: IrType,
        isMutable: Boolean,
    ): IrExpression {
        val valueType = (propertyType as? IrSimpleType)
            ?.arguments
            ?.singleOrNull()
            ?.typeOrNull
            ?: dotNetUnsupported(
                "local delegated property reference '$propertyName' has an unsupported property type"
            )
        val factory = backendContext.propertyReferenceSymbols.localFactory(isMutable)
        val call = irCall(factory.symbol, reference.type, listOf(valueType)) as IrCall
        return call.apply {
            arguments[0] = irString(propertyName)
            arguments[1] = if (hasSignatureSurface) {
                kTypeBuilder.run { buildCallableSignature(valueType, emptyList()) }
            } else {
                irNull()
            }
            arguments[2] = irCall(backendContext.callableAnnotationSymbols.empty)
            dotNetPropertyAnnotationOwner = reference.reflectionTargetSymbol?.owner as? IrAnnotationContainer
        }
    }
}

/** Original property declaration retained until annotation lowering assigns its own payload. */
internal var IrCall.dotNetPropertyAnnotationOwner: IrAnnotationContainer? by irAttribute(copyByDefault = false)
