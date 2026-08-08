/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.lower.AbstractPropertyReferenceLowering
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.dotNetCallableDeclarationFlags
import org.jetbrains.kotlin.backend.dotnet.dotNetLocalCallableDeclarationFlags
import org.jetbrains.kotlin.backend.dotnet.dotNetUnsupported
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irInt
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
            putDotNetPropertyFactoryArgument("name", propertyReferenceNameExpression(reference))
            putDotNetPropertyFactoryArgument("getter", getterReference)
            setterReference?.let { putDotNetPropertyFactoryArgument("setter", it) }
            val property = reference.reflectionTargetSymbol?.owner as? IrProperty
                ?: error("Internal .NET backend error: KProperty has no property target")
            putDotNetPropertyFactoryArgument("signature", if (hasSignatureSurface) {
                val getter = property.getter
                    ?: error("Internal .NET backend error: reflected property '${property.name}' has no getter")
                kTypeBuilder.run { buildCallableSignature(getter.returnType, getter.typeParameters) }
            } else {
                irNull()
            })
            putDotNetPropertyFactoryArgument("parameterFactory", backendContext.symbols.dotNetKParameterFactory
                ?.takeIf { backendContext.hasCallableParameterSurface }
                ?.let { factory -> irCall(factory) }
                ?: irNull())
            putDotNetPropertyFactoryArgument(
                "annotations",
                irCall(backendContext.callableAnnotationSymbols.empty),
            )
            putDotNetPropertyFactoryArgument("declarationFlags", irInt(property.dotNetCallableDeclarationFlags()))
            dotNetPropertyAnnotationOwner = property
            dotNetPropertySignatureOwner = property
            dotNetPropertyBoundReceiverCount = reference.boundValues.size
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
            putDotNetPropertyFactoryArgument("name", irString(propertyName))
            putDotNetPropertyFactoryArgument("signature", if (hasSignatureSurface) {
                kTypeBuilder.run { buildCallableSignature(valueType, emptyList()) }
            } else {
                irNull()
            })
            putDotNetPropertyFactoryArgument("parameterFactory", backendContext.symbols.dotNetKParameterFactory
                ?.takeIf { backendContext.hasCallableParameterSurface }
                ?.let { factory -> irCall(factory) }
                ?: irNull())
            putDotNetPropertyFactoryArgument(
                "annotations",
                irCall(backendContext.callableAnnotationSymbols.empty),
            )
            putDotNetPropertyFactoryArgument("declarationFlags", irInt(dotNetLocalCallableDeclarationFlags()))
            dotNetPropertyAnnotationOwner = reference.reflectionTargetSymbol?.owner as? IrAnnotationContainer
            dotNetLocalPropertySignatureType = valueType
        }
    }
}

/**
 * Binds the synthetic Runtime factory contract by IR parameter identity rather than by its
 * physical position. Several lowerings enrich the same call at different phases; adding one
 * orthogonal payload must not silently retarget every later argument.
 */
internal fun IrCall.putDotNetPropertyFactoryArgument(name: String, argument: IrExpression) {
    val parameter = symbol.owner.parameters.singleOrNull { it.name.asString() == name }
        ?: error("Internal .NET backend error: property-reference factory has no '$name' parameter")
    arguments[parameter.indexInParameters] = argument
}

/** Original property declaration retained until annotation lowering assigns its own payload. */
internal var IrCall.dotNetPropertyAnnotationOwner: IrAnnotationContainer? by irAttribute(copyByDefault = false)

/** Original property retained until parameter-annotation lowering builds the shared signature. */
internal var IrCall.dotNetPropertySignatureOwner: IrProperty? by irAttribute(copyByDefault = false)

/** Number of leading receiver descriptors captured by this exact property reference. */
internal var IrCall.dotNetPropertyBoundReceiverCount: Int? by irAttribute(copyByDefault = false)

/** Logical return type for a local delegated-property token, whose parameter list is empty. */
internal var IrCall.dotNetLocalPropertySignatureType: IrType? by irAttribute(copyByDefault = false)
