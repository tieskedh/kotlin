/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.lower.AbstractValueUsageTransformer
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.DotNetExternalDeclarations
import org.jetbrains.kotlin.backend.dotnet.DotNetRuntimeTypes
import org.jetbrains.kotlin.backend.dotnet.dotNetUnboxedValueClassTypeOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetValueClassOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetValueClassGenericBoundarySlotOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetValueClassConstructorImplementationSourceOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetValueClassImplementationSourceOrNull
import org.jetbrains.kotlin.backend.dotnet.dotNetExternalLibraries
import org.jetbrains.kotlin.backend.dotnet.isDotNetErasedObjectResult
import org.jetbrains.kotlin.backend.dotnet.isDotNetErasedCallableInvoke
import org.jetbrains.kotlin.backend.dotnet.isDotNetErasedPropertyAccess
import org.jetbrains.kotlin.backend.dotnet.isDotNetGenericArray
import org.jetbrains.kotlin.backend.dotnet.referencesTypeParameterOf
import org.jetbrains.kotlin.descriptors.ValueClassBackendAgnosticApi
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irReinterpretCast
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irEqualsNull
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.isInlineClass
import org.jetbrains.kotlin.ir.declarations.isStaticMethodOfClass
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.defaultType as typeParameterDefaultType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrReturnableBlockSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isNullableNothing
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getInlineClassBackingField
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.IrTypeParameterRemapper
import org.jetbrains.kotlin.ir.util.remapTypes
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

internal val DOTNET_VALUE_CLASS_BOX_HELPER: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_VALUE_CLASS_BOX_HELPER")
internal val DOTNET_VALUE_CLASS_UNBOX_HELPER: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_VALUE_CLASS_UNBOX_HELPER")

internal data class DotNetValueClassBoxingHelpers(
    val box: IrSimpleFunction,
    val unbox: IrSimpleFunction,
)

/** Rebinds copied owner parameters of Common's static implementations to CLR method slots. */
internal class DotNetValueClassImplementationSignatureLowering(
    @Suppress("UNUSED_PARAMETER") context: DotNetBackendContext,
) : DeclarationTransformer {
    @OptIn(ValueClassBackendAgnosticApi::class)
    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        val implementation = declaration as? IrSimpleFunction ?: return null
        val valueClass = implementation.parent as? IrClass ?: return null
        if (!valueClass.isInlineClass(treatCompatibleFullValueClassesAsInline = true) ||
            !implementation.isGeneratedValueClassImplementation(valueClass) ||
            valueClass.typeParameters.isEmpty()
        ) {
            return null
        }
        val copiedOwnerParameters = implementation.typeParameters.take(valueClass.typeParameters.size)
        if (copiedOwnerParameters.size != valueClass.typeParameters.size) {
            error("Internal .NET backend error: value-class implementation lost copied owner parameters")
        }
        implementation.remapTypes(
            IrTypeParameterRemapper(
                valueClass.typeParameters.zip(copiedOwnerParameters).toMap()
            )
        )
        return null
    }
}

/**
 * Makes the shared value-class declaration shape explicit for CIL control flow.
 *
 * `InlineClassDeclarationLowering` moves a member body to a generated static implementation but
 * deliberately retains `IrReturn.returnTargetSymbol` pointing at the source member. JS/Wasm can
 * keep that structured target until their final code generators. A CLR `ret` can only leave the
 * current MethodDef, so retarget exactly the returns moved into the corresponding implementation
 * before any later body lowering or IL stack accounting sees them.
 */
internal class DotNetValueClassReturnTargetLowering(
    private val context: DotNetBackendContext,
) : BodyLoweringPass {
    @OptIn(ValueClassBackendAgnosticApi::class)
    override fun lower(irBody: IrBody, container: IrDeclaration) {
        val implementation = container as? IrSimpleFunction ?: return
        val valueClass = implementation.parent as? IrClass ?: return
        if (!valueClass.isInlineClass(treatCompatibleFullValueClassesAsInline = true)) return

        if (!implementation.isStaticMethodOfClass) {
            val dispatchReceiver = implementation.dispatchReceiverParameter ?: return
            val underlyingField = getInlineClassBackingField(valueClass)
            irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
                override fun visitGetValue(expression: IrGetValue) =
                    if (expression.symbol == dispatchReceiver.symbol) {
                        context.createIrBuilder(implementation.symbol, expression.startOffset, expression.endOffset).run {
                            irReinterpretCast(
                                irGetField(irGet(dispatchReceiver), underlyingField),
                                expression.type,
                            )
                        }
                    } else {
                        super.visitGetValue(expression)
                    }
            })
            return
        }

        if (!implementation.isGeneratedValueClassImplementation(valueClass)) return
        val underlyingField = getInlineClassBackingField(valueClass)
        irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitGetField(expression: IrGetField) =
                if (expression.symbol.owner == underlyingField && expression.receiver != null) {
                    val receiver = expression.receiver!!.transform(this, null)
                    context.createIrBuilder(implementation.symbol, expression.startOffset, expression.endOffset).run {
                        irReinterpretCast(receiver, expression.type)
                    }
                } else {
                    super.visitGetField(expression)
                }

            override fun visitReturn(expression: IrReturn): IrReturn {
                expression.transformChildrenVoid(this)
                val sourceTarget = expression.returnTargetSymbol.owner as? IrFunction ?: return expression
                if (sourceTarget.parent == valueClass && sourceTarget.valueClassImplementationName(valueClass) == implementation.name) {
                    expression.returnTargetSymbol = implementation.symbol
                }
                return expression
            }
        })
    }

    private fun IrFunction.valueClassImplementationName(valueClass: IrClass): Name {
        val implementationName = valueClass.name.asString() + "__" + name.asString() + "-impl"
        return if (name.isSpecial) Name.special("<$implementationName>") else Name.identifier(implementationName)
    }

}

private fun IrSimpleFunction.isGeneratedValueClassImplementation(valueClass: IrClass): Boolean {
    if (parent != valueClass) return false
    return dotNetValueClassImplementationSourceOrNull() != null ||
            dotNetValueClassConstructorImplementationSourceOrNull() != null
}

/** Publishes the two producer-owned compiler-ABI transitions on each local value-class owner. */
internal class DotNetValueClassBoxingHelpersLowering(
    private val context: DotNetBackendContext,
) : DeclarationTransformer {
    @OptIn(ValueClassBackendAgnosticApi::class)
    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        val valueClass = declaration as? IrClass ?: return null
        if (!valueClass.isInlineClass(treatCompatibleFullValueClassesAsInline = true)) return null
        val helpers = context.getOrCreateDotNetValueClassBoxingHelpers(valueClass)
        if (helpers.box !in valueClass.declarations) {
            valueClass.declarations += helpers.box
            valueClass.declarations += helpers.unbox
        }
        return null
    }
}

internal fun DotNetBackendContext.getOrCreateDotNetValueClassBoxingHelpers(
    valueClass: IrClass,
): DotNetValueClassBoxingHelpers = valueClassBoxingHelpers.getOrPut(valueClass) {
    val constructor = valueClass.primaryConstructor
        ?: error("Internal .NET backend error: value class '${valueClass.name}' has no primary constructor")
    val backingField = getInlineClassBackingField(valueClass)

    fun buildHelper(origin: IrDeclarationOrigin, name: String): IrSimpleFunction = irFactory.buildFun {
        startOffset = valueClass.startOffset
        endOffset = valueClass.endOffset
        this.origin = origin
        this.name = Name.special("<$name>")
        visibility = DescriptorVisibilities.INTERNAL
        modality = Modality.FINAL
        returnType = irBuiltIns.anyNType
    }.apply {
        parent = valueClass
    }

    val box = buildHelper(DOTNET_VALUE_CLASS_BOX_HELPER, "dotnet-box-impl")
    val boxTypeParameters = box.copyTypeParametersFrom(valueClass, DOTNET_VALUE_CLASS_BOX_HELPER)
    val boxSubstitutor = IrTypeSubstitutor(
        valueClass.typeParameters.zip(boxTypeParameters).associate { pair ->
            pair.first.symbol to pair.second.typeParameterDefaultType
        },
        allowEmptySubstitution = true,
    )
    val boxedLogicalType = boxSubstitutor.substitute(valueClass.defaultType)
    val boxCarrierType = boxSubstitutor.substitute(inlineClassesUtils.getInlineClassUnderlyingType(valueClass))
    box.returnType = boxedLogicalType
    val valueToBox = box.addValueParameter("value", boxCarrierType)
    box.body = createIrBuilder(box.symbol).irBlockBody {
        +irReturn(
            irCall(constructor.symbol, boxedLogicalType).apply {
                boxTypeParameters.forEachIndexed { index, typeParameter ->
                    typeArguments[index] = typeParameter.typeParameterDefaultType
                }
                arguments[0] = irGet(valueToBox)
            }
        )
    }

    val unbox = buildHelper(DOTNET_VALUE_CLASS_UNBOX_HELPER, "dotnet-unbox-impl")
    val unboxTypeParameters = unbox.copyTypeParametersFrom(valueClass, DOTNET_VALUE_CLASS_UNBOX_HELPER)
    val unboxSubstitutor = IrTypeSubstitutor(
        valueClass.typeParameters.zip(unboxTypeParameters).associate { pair ->
            pair.first.symbol to pair.second.typeParameterDefaultType
        },
        allowEmptySubstitution = true,
    )
    val unboxLogicalType = unboxSubstitutor.substitute(valueClass.defaultType)
    val unboxCarrierType = unboxSubstitutor.substitute(inlineClassesUtils.getInlineClassUnderlyingType(valueClass))
    unbox.returnType = unboxCarrierType
    val boxToUnbox = unbox.addValueParameter("box", unboxLogicalType)
    unbox.body = createIrBuilder(unbox.symbol).irBlockBody {
        +irReturn(irGetField(irGet(boxToUnbox), backingField, unboxCarrierType))
    }

    DotNetValueClassBoxingHelpers(box, unbox)
}

/**
 * Makes every Kotlin value-class representation transition an explicit IR call.
 *
 * Common's declaration/usage lowerings preserve logical value-class types while replacing exact
 * construction and member use with underlying-carrier implementations. This pass is the target
 * boundary that distinguishes those exact occurrences from nominal object occurrences. The
 * emitter only implements the two marked operations; it does not infer boxing from a coincidental
 * carrier-to-object conversion.
 */
internal class DotNetValueClassAutoboxingLowering(
    private val context: DotNetBackendContext,
) : BodyLoweringPass {
    private val externalDeclarations = DotNetExternalDeclarations(context.configuration.dotNetExternalLibraries)

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        val builder = context.createIrBuilder(container.symbol)
        val boundaryFunction = container as? IrSimpleFunction
        val boundarySlot = boundaryFunction?.dotNetValueClassGenericBoundarySlotOrNull()
        val boundaryOwner = boundarySlot?.parent as? IrClass
        val nominalBoundaryParameters = buildSet<IrValueParameterSymbol> {
            if (boundaryFunction != null && boundarySlot != null && boundaryOwner != null) {
                boundaryFunction.parameters.zip(boundarySlot.parameters).mapNotNullTo(this) { pair ->
                    val implementationParameter = pair.first
                    val slotParameter = pair.second
                    implementationParameter.symbol.takeIf {
                        implementationParameter.type.dotNetValueClassOrNull() != null &&
                                slotParameter.type.referencesTypeParameterOf(boundaryOwner)
                    }
                }
            }
            if (boundaryFunction?.isDotNetErasedCallableInvoke() == true ||
                boundaryFunction?.isDotNetErasedPropertyAccess() == true
            ) {
                // FunctionN.Invoke and KProperty get/set keep logical V in Common IR but their
                // universal execution ABI carries every non-receiver value as object. Mirror the
                // exact signature-mapper classifier here so a narrowed IrGetValue never mistakes
                // that object slot for V's underlying carrier.
                for (parameter in boundaryFunction.parameters) {
                    if (parameter.kind != org.jetbrains.kotlin.ir.declarations.IrParameterKind.DispatchReceiver &&
                        parameter.type.dotNetValueClassOrNull() != null
                    ) {
                        add(parameter.symbol)
                    }
                }
            }
        }
        irBody.transformChildrenVoid(object : AbstractValueUsageTransformer(context.irBuiltIns) {
            override fun useAsVarargElement(element: IrExpression, expression: IrVararg): IrExpression {
                // A CLR vector reifies its element identity. Common leaves an arrayOf/vararg
                // literal as IrVararg and deliberately delegates its element representation to
                // the target (JS uses this same hook). An exact value-class constructor therefore
                // crosses the nominal boundary before the emitter writes it to V[].
                return if (expression.type.isDotNetGenericArray() &&
                    expression.varargElementType.dotNetValueClassOrNull() != null
                ) {
                    element.useAs(context.irBuiltIns.anyNType)
                } else {
                    super.useAsVarargElement(element, expression)
                }
            }

            override fun visitCall(expression: IrCall): IrExpression {
                expression.fillValueClassImplementationOwnerTypeArguments()
                val transformed = super.visitCall(expression)
                val checkNotNull = transformed as? IrCall ?: return transformed
                if (checkNotNull.symbol != context.irBuiltIns.checkNotNullSymbol) return transformed
                val resultType = checkNotNull.type
                if ((resultType as? IrSimpleType)?.isMarkedNullable() != false ||
                    resultType.dotNetUnboxedValueClassTypeOrNull() == null
                ) {
                    return transformed
                }
                val argument = checkNotNull.arguments.singleOrNull() ?: return transformed
                if (argument.physicalValueClassCarrierOrNull() != null) return transformed

                // `x!!` first checks the nullable nominal box and only then exposes the exact
                // carrier. JVM performs the same logical transition in its inline-class
                // autoboxing/codegen path. Hiding the inner result behind Any? tells CIL
                // emission that the check itself still produces the nominal owner; the producer
                // helper is the sole nominal -> carrier edge.
                val valueClass = resultType.dotNetValueClassOrNull() ?: return transformed
                val helpers = context.getOrCreateDotNetValueClassBoxingHelpers(valueClass)
                checkNotNull.type = context.irBuiltIns.anyNType
                return builder.at(checkNotNull).irCall(helpers.unbox.symbol, resultType).apply {
                    putValueClassTypeArguments(resultType)
                    arguments[0] = checkNotNull
                }
            }

            override fun IrExpression.useAsValueArgument(
                expression: IrFunctionAccessExpression,
                parameter: IrValueParameter,
            ): IrExpression {
                val function = expression.symbol.owner
                val genericBoundarySlot = (function as? IrSimpleFunction)
                    ?.dotNetValueClassGenericBoundarySlotOrNull()
                val genericBoundaryOwner = genericBoundarySlot?.parent as? IrClass
                val parameterIndex = function.parameters.indexOf(parameter)
                val genericBoundaryParameter = genericBoundarySlot?.parameters?.getOrNull(parameterIndex)
                if (genericBoundaryOwner != null &&
                    genericBoundaryParameter?.type?.referencesTypeParameterOf(genericBoundaryOwner) == true &&
                    parameter.type.dotNetValueClassOrNull() != null
                ) {
                    // The physical MethodDef signature maps this constructed generic capability
                    // slot to V's nominal owner. Apply the same rule at every call site: an
                    // erased bridge must pass its existing box through unchanged, while an exact
                    // carrier caller boxes before entering InvokeExact. Without this mirror of
                    // dotNetSignature(), the late usage pass can insert an unbox whose result is
                    // then offered to a CLR parameter that truthfully requires the nominal V.
                    return useAs(context.irBuiltIns.anyNType)
                }
                val substitutions = buildMap<IrTypeParameterSymbol, IrType> {
                    function.typeParameters.zip(expression.typeArguments).forEach { pair ->
                        pair.second?.let { put(pair.first.symbol, it) }
                    }
                    val owner = function.parent as? IrClass
                    val receiverType = expression.arguments.firstOrNull()?.type as? IrSimpleType
                    if (owner != null &&
                        (receiverType?.classifier as? org.jetbrains.kotlin.ir.symbols.IrClassSymbol)?.owner == owner
                    ) {
                        owner.typeParameters.zip(receiverType.arguments).forEach { pair ->
                            (pair.second as? IrTypeProjection)?.type?.let { put(pair.first.symbol, it) }
                        }
                    }
                }
                if (substitutions.isEmpty()) return useAs(parameter.type)
                val expectedType = IrTypeSubstitutor(substitutions, allowEmptySubstitution = true)
                    .substitute(parameter.type)
                val directGenericSlot = (parameter.type as? IrSimpleType)?.classifier as? IrTypeParameterSymbol
                return if (directGenericSlot in substitutions && expectedType.dotNetValueClassOrNull() != null) {
                    useAs(context.irBuiltIns.anyNType)
                } else {
                    useAs(expectedType)
                }
            }

            private fun IrCall.fillValueClassImplementationOwnerTypeArguments() {
                val function = symbol.owner
                val valueClass = function.parent as? IrClass ?: return
                if (!function.isGeneratedValueClassImplementation(valueClass) || valueClass.typeParameters.isEmpty()) {
                    return
                }
                val valueClassType = sequenceOf(
                    arguments.firstOrNull()?.type,
                    type,
                ).filterNotNull().mapNotNull { it as? IrSimpleType }.firstOrNull { type ->
                    (type.classifier as? org.jetbrains.kotlin.ir.symbols.IrClassSymbol)?.owner == valueClass
                } ?: return
                val ownerArguments = valueClassType.arguments.map { argument ->
                    (argument as? IrTypeProjection)?.type
                        ?: error("Internal .NET backend error: value-class implementation call has a projected owner argument")
                }
                val ownerParameterCount = valueClass.typeParameters.size
                val methodParameterCount = function.typeParameters.size - ownerParameterCount
                if (typeArguments.size == methodParameterCount) {
                    val methodArguments = typeArguments.toList()
                    typeArguments.clear()
                    typeArguments.addAll(ownerArguments)
                    typeArguments.addAll(methodArguments)
                } else if (typeArguments.size == function.typeParameters.size) {
                    ownerArguments.forEachIndexed { index, type -> typeArguments[index] = type }
                }
            }

            override fun IrExpression.useAsReturnValue(returnTarget: IrReturnTargetSymbol): IrExpression {
                val function = (returnTarget as? IrSimpleFunctionSymbol)?.owner
                val slot = function?.dotNetValueClassGenericBoundarySlotOrNull()
                val slotOwner = slot?.parent as? IrClass
                if (function != null && slotOwner != null &&
                    slot.returnType.referencesTypeParameterOf(slotOwner) &&
                    function.returnType.dotNetValueClassOrNull() != null
                ) {
                    return useAs(context.irBuiltIns.anyNType)
                }
                return when (returnTarget) {
                    is IrSimpleFunctionSymbol -> useAs(returnTarget.owner.returnType)
                    is IrConstructorSymbol -> useAs(context.irBuiltIns.unitType)
                    is IrReturnableBlockSymbol -> useAs(returnTarget.owner.type)
                }
            }

            override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
                val transformed = super.visitTypeOperator(expression) as IrTypeOperatorCall
                val requiresNominalUnbox = transformed.operator == IrTypeOperator.CAST ||
                        (transformed.operator == IrTypeOperator.IMPLICIT_CAST &&
                                transformed.argument.physicalValueClassCarrierOrNull() == null) ||
                        (transformed.operator == IrTypeOperator.IMPLICIT_NOTNULL &&
                                transformed.argument.type.dotNetValueClassOrNull() != null &&
                                transformed.argument.type.dotNetUnboxedValueClassTypeOrNull() == null)
                if (!requiresNominalUnbox) return transformed
                val resultType = if (transformed.operator == IrTypeOperator.IMPLICIT_NOTNULL) {
                    transformed.argument.type.makeNotNull()
                } else {
                    transformed.type
                }
                if ((resultType as? IrSimpleType)?.isMarkedNullable() != false) return transformed
                if (resultType.dotNetUnboxedValueClassTypeOrNull() == null) return transformed

                val valueClass = resultType.dotNetValueClassOrNull() ?: return transformed
                val helpers = context.getOrCreateDotNetValueClassBoxingHelpers(valueClass)
                // The checked inner operation produces the nominal owner. Its logical result is
                // hidden behind Any? so the outer producer helper is the sole box -> carrier edge.
                transformed.type = context.irBuiltIns.anyNType
                return builder.at(transformed).irCall(helpers.unbox.symbol, resultType).apply {
                    putValueClassTypeArguments(resultType)
                    arguments[0] = transformed
                }
            }

            override fun IrExpression.useAs(type: IrType): IrExpression {
                if (this.type.isNothing() || this.type.isNullableNothing()) return this

                val actualClass = this.type.dotNetValueClassOrNull()
                val expectedClass = type.dotNetValueClassOrNull()
                val actualCarrier = physicalValueClassCarrierOrNull()
                val expectedCarrier = type.dotNetUnboxedValueClassTypeOrNull()
                val logicalActualCarrier = this.type.dotNetUnboxedValueClassTypeOrNull()

                if (actualClass != null && actualCarrier != null) {
                    if (actualClass == expectedClass && expectedCarrier != null) return this
                    val helpers = context.getOrCreateDotNetValueClassBoxingHelpers(actualClass)
                    if (this.type.isMarkedNullable() && actualCarrier.isMarkedNullable()) {
                        val expressionToBox = this
                        return builder.at(this).irBlock(resultType = type) {
                            val carrier = irTemporary(expressionToBox, nameHint = "<value-class-carrier>")
                            +irIfThenElse(
                                type,
                                irEqualsNull(irGet(carrier)),
                                irNull(),
                                irCall(helpers.box.symbol, type).apply {
                                    putValueClassTypeArguments(expressionToBox.type)
                                    arguments[0] = irGet(carrier)
                                },
                            )
                        }
                    }
                    return builder.at(this).irCall(helpers.box.symbol, type).apply {
                        putValueClassTypeArguments(this@useAs.type)
                        arguments[0] = this@useAs
                    }
                }

                if (actualClass != null && actualCarrier == null && expectedClass == null &&
                    logicalActualCarrier == type
                ) {
                    // Common usage lowering can expose the exact underlying parameter on a
                    // generated implementation call while retaining V on the argument read.
                    // A callable/property adapter receives that read through an erased object
                    // slot, so this is still a nominal V -> carrier transition, not a direct
                    // object -> underlying smartcast. Source Kotlin cannot create this shape by
                    // passing V to an unrelated underlying-typed declaration.
                    val helpers = context.getOrCreateDotNetValueClassBoxingHelpers(actualClass)
                    return builder.at(this).irCall(helpers.unbox.symbol, type).apply {
                        putValueClassTypeArguments(this@useAs.type)
                        arguments[0] = this@useAs
                    }
                }

                if (expectedClass != null && expectedCarrier != null && actualCarrier == null) {
                    val helpers = context.getOrCreateDotNetValueClassBoxingHelpers(expectedClass)
                    return builder.at(this).irCall(helpers.unbox.symbol, type).apply {
                        putValueClassTypeArguments(type)
                        arguments[0] = this@useAs
                    }
                }

                return this
            }

            override fun IrExpression.useInTypeOperator(
                operator: IrTypeOperator,
                typeOperand: IrType,
            ): IrExpression = when (operator) {
                IrTypeOperator.IMPLICIT_CAST -> useAs(typeOperand)
                IrTypeOperator.CAST,
                IrTypeOperator.SAFE_CAST,
                IrTypeOperator.INSTANCEOF,
                IrTypeOperator.NOT_INSTANCEOF,
                -> if (physicalValueClassCarrierOrNull() != null) {
                    useAs(context.irBuiltIns.anyNType)
                } else {
                    this
                }
                else -> this
            }

            private fun IrExpression.physicalValueClassCarrierOrNull(): IrType? {
                if (this is IrGetValue && symbol in nominalBoundaryParameters) return null
                if (this is IrGetValue &&
                    type.dotNetValueClassOrNull() != null &&
                    symbol.owner.type.dotNetUnboxedValueClassTypeOrNull() == null
                ) {
                    // A smartcast/substitution can narrow an object or open generic parameter
                    // read to logical V without changing the CLR slot that stores it. Callable
                    // adapters are the important generated shape: Invoke(object p0) reads p0 as
                    // V, but the object slot still contains V's nominal box. Trust the declared
                    // slot representation here, then insert the ordinary producer unbox helper.
                    return null
                }
                if (this is IrTypeOperatorCall &&
                    (operator == IrTypeOperator.CAST || operator == IrTypeOperator.SAFE_CAST ||
                            (operator == IrTypeOperator.IMPLICIT_CAST && type.isNullableAny())) &&
                    typeOperand.dotNetValueClassOrNull() != null
                ) {
                    return null
                }
                if (this is IrCall && symbol == context.irBuiltIns.checkNotNullSymbol) {
                    val argumentType = arguments.singleOrNull()?.type
                    if (argumentType?.dotNetValueClassOrNull() != null &&
                        argumentType.dotNetUnboxedValueClassTypeOrNull() == null
                    ) {
                        return null
                    }
                }
                if (this is IrCall && symbol.owner.hasErasedKotlinOwnerResult()) return null
                if (this is IrCall && returnsReifiedTypeParameterInstantiatedWithValueClass()) return null
                return type.dotNetUnboxedValueClassTypeOrNull()
            }

            private fun IrCall.returnsReifiedTypeParameterInstantiatedWithValueClass(): Boolean {
                val function = symbol.owner
                // This compiler-ABI helper is the explicit exception: its logical call type is
                // V for Common-IR compatibility, but its entire contract is to produce V's
                // exact underlying carrier. Treating its generic return as an ordinary CLR
                // generic result would immediately re-box/re-unbox the transition it records.
                if (function.origin == DOTNET_VALUE_CLASS_UNBOX_HELPER) return false
                // CLR-reified generic owners and methods physically return their constructed
                // argument. For Array<Foo>.get and foreign G<Foo>.value that argument is the
                // nominal Foo box selected for every CLR generic slot, never Foo's exact
                // underlying carrier. FIR already records the proven substitution on this
                // call's result type, so the representation question only needs two facts:
                // the declaration returns a type parameter, and this occurrence returns a
                // value class. This also covers an inherited/approximated receiver whose IR
                // type no longer directly names the declaring generic owner.
                return (function.returnType as? IrSimpleType)?.classifier is IrTypeParameterSymbol &&
                        type.dotNetValueClassOrNull() != null
            }

            private fun IrSimpleFunction.hasErasedKotlinOwnerResult(): Boolean {
                if (isDotNetErasedObjectResult()) return true
                val valueClassOwner = parent as? IrClass
                val valueClassSource = if (valueClassOwner != null &&
                    isGeneratedValueClassImplementation(valueClassOwner)
                ) {
                    attributeOwnerId as? IrSimpleFunction
                } else {
                    null
                }
                val sourceOwner = valueClassSource?.parent as? IrClass
                if (sourceOwner != null && valueClassSource.returnType.referencesTypeParameterOf(sourceOwner)) {
                    return true
                }
                val owner = parent as? IrClass ?: return false
                if (!returnType.referencesTypeParameterOf(owner)) return false
                return if (owner.isInterface) {
                    owner in context.erasedGenericInterfaces ||
                            DotNetRuntimeTypes.hasBuiltInGenericInterfaceMapping(owner) ||
                            externalDeclarations.hasGenericInterface(owner)
                } else {
                    owner in context.erasedGenericClasses || externalDeclarations.hasGenericClass(owner)
                }
            }

            private fun org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression<*>.putValueClassTypeArguments(
                valueClassType: IrType,
            ) {
                val arguments = (valueClassType as? IrSimpleType)?.arguments.orEmpty()
                for (index in typeArguments.indices) {
                    typeArguments[index] = (arguments.getOrNull(index) as? IrTypeProjection)?.type
                        ?: error("Internal .NET backend error: exact value-class carrier has no invariant type argument")
                }
            }
        })
    }
}
