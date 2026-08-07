/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.AbstractFunctionReferenceLowering
import org.jetbrains.kotlin.backend.common.lower.LocalDeclarationsLowering
import org.jetbrains.kotlin.backend.common.lower.UpgradeCallableReferences
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext
import org.jetbrains.kotlin.backend.dotnet.serialization.DotNetIrMangler
import org.jetbrains.kotlin.backend.dotnet.dotNetFixedFunctionArityOrNull
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWithArguments
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameterWithClassParent
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isKFunction
import org.jetbrains.kotlin.ir.util.isKSuspendFunction
import org.jetbrains.kotlin.ir.util.isLambda
import org.jetbrains.kotlin.ir.util.invokeFun
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.util.removeProjectionsToMakeValidSuperType
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

internal val DOTNET_LAMBDA_IMPL: IrDeclarationOrigin = IrDeclarationOriginImpl("DOTNET_LAMBDA_IMPL")
internal val DOTNET_FUNCTION_REFERENCE_IMPL: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_FUNCTION_REFERENCE_IMPL")
private val DOTNET_CALLABLE_CONSTRUCTOR: IrDeclarationOrigin =
    IrDeclarationOriginImpl("DOTNET_CALLABLE_CONSTRUCTOR")

internal val IrClass.isDotNetCallableObject: Boolean
    get() = origin == DOTNET_LAMBDA_IMPL || origin == DOTNET_FUNCTION_REFERENCE_IMPL

/** Builds rich callable IR while deliberately leaving SAM conversions outside this slice. */
internal class DotNetUpgradeCallableReferences(context: DotNetBackendContext) :
    UpgradeCallableReferences(context, upgradeSamConversions = false)

/**
 * Turns a rich callable into the same local-class shape used by the common JS lowering: an Any
 * subclass implementing its fixed-arity function interface with one invoke override. Direct
 * references additionally implement the orthogonal non-generic KFunction reflection view. The
 * CLR representation is Kotlin-owned; `System.Func`/`System.Action` adapters belong at future
 * interop boundaries.
 */
internal class DotNetCallableReferenceLowering(context: DotNetBackendContext) :
    AbstractFunctionReferenceLowering<DotNetBackendContext>(context) {
    private val kTypeBuilder = DotNetKTypeIrBuilder(context, operation = "callable signature")

    override fun getReferenceClassName(reference: IrRichFunctionReference): Name =
        Name.identifier(if (reference.origin.isLambda) "lambda" else "functionReference")

    override fun getSuperClassType(reference: IrRichFunctionReference): IrType =
        if (reference.hasFunctionReferenceIdentity) context.functionReferenceSymbols.baseClass.defaultType
        else context.irBuiltIns.anyType

    override fun IrBuilderWithScope.generateSuperClassConstructorCall(
        constructor: IrConstructor,
        superClassType: IrType,
        functionReference: IrRichFunctionReference,
    ): IrDelegatingConstructorCall {
        if (!functionReference.hasFunctionReferenceIdentity) {
            return irDelegatingConstructorCall(
                superClassType.classOrFail.owner.primaryConstructor
                    ?: error("Internal .NET backend error: kotlin.Any has no primary constructor")
            )
        }
        val baseConstructor = this@DotNetCallableReferenceLowering.context.functionReferenceSymbols.constructor
        return irDelegatingConstructorCall(baseConstructor).apply {
            arguments[0] = irString(functionReference.stableReferenceId())
            arguments[1] = irInt(functionReference.referenceArity())
            arguments[2] = irInt(functionReference.referenceFlags())
            arguments[3] = irInt(functionReference.boundValues.size)
            arguments[4] = irString(functionReference.reflectedName())
            arguments[5] = irCall(
                functionReference.dotNetCallableAnnotationFactory
                    ?: this@DotNetCallableReferenceLowering.context.callableAnnotationSymbols.empty
            )
            val hasSignatureSurface = functionReference.type.isKFunction() &&
                    !functionReference.type.isKSuspendFunction() &&
                    this@DotNetCallableReferenceLowering.context.irBuiltIns.kCallableClass.owner.properties
                        .mapTo(linkedSetOf()) { property -> property.name.asString() }
                        .containsAll(listOf("returnType", "typeParameters"))
            arguments[6] = if (hasSignatureSurface) {
                val target = functionReference.reflectionTargetSymbol?.owner
                    ?: error("Internal .NET backend error: KFunction return type has no reflection target")
                val declaredParameters = if (target is IrConstructor) {
                    (target.parent as? IrClass)?.typeParameters
                        ?: error("Internal .NET backend error: reflected constructor has no class owner")
                } else {
                    target.typeParameters
                }
                functionReference.dotNetCallableSignature ?: kTypeBuilder.run {
                    buildCallableSignature(target.returnType, declaredParameters)
                }
            } else {
                irNull()
            }
            arguments[7] = this@DotNetCallableReferenceLowering.context.symbols.dotNetKParameterFactory
                ?.takeIf { this@DotNetCallableReferenceLowering.context.hasCallableParameterSurface }
                ?.let { factory -> irCall(factory) }
                ?: irNull()
        }
    }

    override fun getClassOrigin(reference: IrRichFunctionReference): IrDeclarationOrigin =
        if (reference.origin.isLambda) DOTNET_LAMBDA_IMPL else DOTNET_FUNCTION_REFERENCE_IMPL

    override fun getConstructorOrigin(reference: IrRichFunctionReference): IrDeclarationOrigin =
        DOTNET_CALLABLE_CONSTRUCTOR

    override fun getInvokeMethodOrigin(reference: IrRichFunctionReference): IrDeclarationOrigin =
        IrDeclarationOrigin.DEFINED

    override fun getConstructorCallOrigin(reference: IrRichFunctionReference): IrStatementOrigin? = null

    override fun getAdditionalInterfaces(reference: IrRichFunctionReference): List<IrType> {
        val executionType = reference.type.removeProjectionsToMakeValidSuperType()
        val exactType = context.exactCallableSymbols.typeFor(executionType)
        val typedArgumentsType = context.typedArgumentsCallableSymbols.typeFor(executionType)
        val reflectiveType = reference.type.takeIf { it.isKFunction() && !it.isKSuspendFunction() } as? IrSimpleType
        val erasedExecutionType = reflectiveType?.let {
            val arity = it.arguments.size - 1
            if (arity !in 0..3) null
            else context.irBuiltIns.functionN(arity).symbol.typeWithArguments(it.arguments)
        }
        return listOfNotNull(erasedExecutionType, exactType, typedArgumentsType)
    }

    override fun postprocessInvoke(invokeFunction: IrSimpleFunction, functionReference: IrRichFunctionReference) {
        val reflectiveType = functionReference.type
            .takeIf { it.isKFunction() && !it.isKSuspendFunction() } as? IrSimpleType
        if (reflectiveType != null) {
            val arity = reflectiveType.arguments.size - 1
            if (arity in 0..3) {
                val executionInvoke = context.irBuiltIns.functionN(arity).invokeFun
                    ?: error("Internal .NET backend error: kotlin.Function$arity has no invoke member")
                if (executionInvoke.symbol !in invokeFunction.overriddenSymbols) {
                    invokeFunction.overriddenSymbols += executionInvoke.symbol
                }
            }
        }

        val executionType = functionReference.type.removeProjectionsToMakeValidSuperType()
        if (context.exactCallableSymbols.typeFor(executionType) == null) return
        val arity = (executionType as IrSimpleType).arguments.size - 1
        val exactInvoke = splitExactInvokeFromErasedBridge(invokeFunction, arity)
        if (context.typedArgumentsCallableSymbols.typeFor(executionType) != null) {
            addTypedArgumentsBridge(invokeFunction.parent as IrClass, exactInvoke, arity)
        }
    }

    /**
     * Keeps the original typed body as InvokeExact and makes the Kotlin FunctionN override a
     * small erased bridge which calls it. Both slots live on the same generated object: the
     * bridge is the stable identity ABI, while InvokeExact is an optional execution capability.
     */
    private fun splitExactInvokeFromErasedBridge(invokeFunction: IrSimpleFunction, arity: Int): IrSimpleFunction {
        val functionReferenceClass = invokeFunction.parent as IrClass
        val erasedOverrides = invokeFunction.overriddenSymbols.toList()
        val originalName = invokeFunction.name
        val originalOperator = invokeFunction.isOperator
        val exactInvoke = context.exactCallableSymbols.invokeForArity(arity)

        invokeFunction.name = exactInvoke.name
        invokeFunction.isOperator = false
        invokeFunction.overriddenSymbols = listOf(exactInvoke.symbol)

        functionReferenceClass.addFunction {
            startOffset = invokeFunction.startOffset
            endOffset = invokeFunction.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = originalName
            visibility = invokeFunction.visibility
            modality = invokeFunction.modality
            returnType = invokeFunction.returnType
            isOperator = originalOperator
        }.apply bridge@{
            annotations = invokeFunction.annotations
            overriddenSymbols = erasedOverrides
            parameters += createDispatchReceiverParameterWithClassParent()
            invokeFunction.parameters.drop(1).forEach { parameter ->
                parameters += parameter.copyTo(this)
            }
            body = context.createIrBuilder(symbol).irBlockBody {
                +irReturn(irCall(invokeFunction).apply {
                    arguments[0] = irGet(this@bridge.parameters[0])
                    this@bridge.parameters.drop(1).forEachIndexed { index, parameter ->
                        arguments[index + 1] = irGet(parameter)
                    }
                })
            }
        }
        return invokeFunction
    }

    /** Boxes only the result while retaining the generated callable's exact argument slots. */
    private fun addTypedArgumentsBridge(
        functionReferenceClass: IrClass,
        exactInvoke: IrSimpleFunction,
        arity: Int,
    ) {
        val typedInvoke = context.typedArgumentsCallableSymbols.invokeForArity(arity)
        functionReferenceClass.addFunction {
            startOffset = exactInvoke.startOffset
            endOffset = exactInvoke.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = typedInvoke.name
            visibility = exactInvoke.visibility
            modality = exactInvoke.modality
            returnType = context.irBuiltIns.anyNType
        }.apply bridge@{
            overriddenSymbols = listOf(typedInvoke.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            exactInvoke.parameters.drop(1).forEach { parameter ->
                parameters += parameter.copyTo(this)
            }
            body = context.createIrBuilder(symbol).irBlockBody {
                +irReturn(irCall(exactInvoke).apply {
                    arguments[0] = irGet(this@bridge.parameters[0])
                    this@bridge.parameters.drop(1).forEachIndexed { index, parameter ->
                        arguments[index + 1] = irGet(parameter)
                    }
                })
            }
        }
    }

    override fun generateExtraMethods(functionReferenceClass: IrClass, reference: IrRichFunctionReference) {
        if (reference.hasFunctionReferenceIdentity) {
            addBoundValueAccess(functionReferenceClass)
        }
        if (!reference.type.isKFunction() || reference.type.isKSuspendFunction()) return
        if (reference.reflectionTargetSymbol == null) return
        addPositionalCall(functionReferenceClass, reference)
        val superProperty = context.irBuiltIns.kCallableClass.owner.properties
            .single { it.name.asString() == "name" }
        val superGetter = superProperty.getter
            ?: error("Internal .NET backend error: kotlin.reflect.KCallable.name has no getter")

        val nameProperty = functionReferenceClass.addProperty {
            startOffset = reference.startOffset
            endOffset = reference.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = superProperty.name
            visibility = superProperty.visibility
        }.apply {
            overriddenSymbols = listOf(superProperty.symbol)
        }
        nameProperty.addGetter {
            startOffset = reference.startOffset
            endOffset = reference.endOffset
            origin = IrDeclarationOrigin.DEFINED
            returnType = context.irBuiltIns.stringType
            visibility = superGetter.visibility
        }.apply {
            overriddenSymbols = listOf(superGetter.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            body = context.createIrBuilder(symbol).irBlockBody {
                +irReturn(irString(reference.reflectedName()))
            }
        }

        val returnTypeProperty = context.irBuiltIns.kCallableClass.owner.properties
            .singleOrNull { property -> property.name.asString() == "returnType" }
            ?: return
        val returnTypeSuperGetter = returnTypeProperty.getter
            ?: error("Internal .NET backend error: kotlin.reflect.KCallable.returnType has no getter")
        val generatedReturnTypeProperty = functionReferenceClass.addProperty {
            startOffset = reference.startOffset
            endOffset = reference.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = returnTypeProperty.name
            visibility = returnTypeProperty.visibility
        }.apply {
            overriddenSymbols = listOf(returnTypeProperty.symbol)
        }
        generatedReturnTypeProperty.addGetter {
            startOffset = reference.startOffset
            endOffset = reference.endOffset
            origin = IrDeclarationOrigin.DEFINED
            returnType = returnTypeSuperGetter.returnType
            visibility = returnTypeSuperGetter.visibility
        }.apply getter@{
            overriddenSymbols = listOf(returnTypeSuperGetter.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            body = context.createIrBuilder(symbol).irBlockBody {
                +irReturn(irCall(this@DotNetCallableReferenceLowering.context.functionReferenceSymbols.getReturnType).apply {
                    arguments[0] = irGet(this@getter.dispatchReceiverParameter!!)
                })
            }
        }

        val parametersProperty = context.irBuiltIns.kCallableClass.owner.properties
            .singleOrNull { property -> property.name.asString() == "parameters" }
        if (parametersProperty != null) {
            val parametersSuperGetter = parametersProperty.getter
                ?: error("Internal .NET backend error: kotlin.reflect.KCallable.parameters has no getter")
            val generatedParametersProperty = functionReferenceClass.addProperty {
                startOffset = reference.startOffset
                endOffset = reference.endOffset
                origin = IrDeclarationOrigin.DEFINED
                name = parametersProperty.name
                visibility = parametersProperty.visibility
            }.apply {
                overriddenSymbols = listOf(parametersProperty.symbol)
            }
            generatedParametersProperty.addGetter {
                startOffset = reference.startOffset
                endOffset = reference.endOffset
                origin = IrDeclarationOrigin.DEFINED
                returnType = parametersSuperGetter.returnType
                visibility = parametersSuperGetter.visibility
            }.apply getter@{
                overriddenSymbols = listOf(parametersSuperGetter.symbol)
                parameters += createDispatchReceiverParameterWithClassParent()
                body = context.createIrBuilder(symbol).irBlockBody {
                    val getParameters = this@DotNetCallableReferenceLowering.context.functionReferenceSymbols.getParameters
                        ?: error("Internal .NET backend error: KCallable.parameters has no runtime helper")
                    +irReturn(irCall(getParameters).apply {
                        arguments[0] = irGet(this@getter.dispatchReceiverParameter!!)
                    })
                }
            }
        }

        val typeParametersProperty = context.irBuiltIns.kCallableClass.owner.properties
            .singleOrNull { property -> property.name.asString() == "typeParameters" }
            ?: return
        val typeParametersSuperGetter = typeParametersProperty.getter
            ?: error("Internal .NET backend error: kotlin.reflect.KCallable.typeParameters has no getter")
        val generatedTypeParametersProperty = functionReferenceClass.addProperty {
            startOffset = reference.startOffset
            endOffset = reference.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = typeParametersProperty.name
            visibility = typeParametersProperty.visibility
        }.apply {
            overriddenSymbols = listOf(typeParametersProperty.symbol)
        }
        generatedTypeParametersProperty.addGetter {
            startOffset = reference.startOffset
            endOffset = reference.endOffset
            origin = IrDeclarationOrigin.DEFINED
            returnType = typeParametersSuperGetter.returnType
            visibility = typeParametersSuperGetter.visibility
        }.apply getter@{
            overriddenSymbols = listOf(typeParametersSuperGetter.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            body = context.createIrBuilder(symbol).irBlockBody {
                val getTypeParameters = this@DotNetCallableReferenceLowering.context.functionReferenceSymbols.getTypeParameters
                    ?: error("Internal .NET backend error: KCallable.typeParameters has no runtime helper")
                +irReturn(irCall(getTypeParameters).apply {
                    arguments[0] = irGet(this@getter.dispatchReceiverParameter!!)
                })
            }
        }
    }

    /** Implements KCallable.call while sharing count validation and arity dispatch in Runtime. */
    private fun addPositionalCall(functionReferenceClass: IrClass, reference: IrRichFunctionReference) {
        val superCall = context.irBuiltIns.kCallableClass.owner.functions
            .singleOrNull { function -> function.name.asString() == "call" }
            ?: return
        val argumentParameter = superCall.parameters.single { parameter ->
            parameter.kind == IrParameterKind.Regular
        }
        functionReferenceClass.addFunction {
            startOffset = reference.startOffset
            endOffset = reference.endOffset
            origin = IrDeclarationOrigin.DEFINED
            name = superCall.name
            visibility = superCall.visibility
            modality = Modality.FINAL
            returnType = reference.invokeFunction.returnType
        }.apply call@{
            overriddenSymbols = listOf(superCall.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            parameters += argumentParameter.copyTo(this)
            body = context.createIrBuilder(symbol).irBlockBody {
                val erasedCall = irCall(
                    this@DotNetCallableReferenceLowering.context.functionReferenceSymbols.callErased
                ).apply {
                    arguments[0] = irGet(this@call.dispatchReceiverParameter!!)
                    arguments[1] = irGet(this@call.parameters.single { it.kind == IrParameterKind.Regular })
                }
                +irReturn(irImplicitCast(erasedCall, this@call.returnType))
            }
        }
    }

    /** Exposes exactly the rich reference's bound values to the runtime identity base. */
    private fun addBoundValueAccess(functionReferenceClass: IrClass) {
        val fields = functionReferenceClass.declarations.filterIsInstance<IrField>()
        if (fields.isEmpty()) return
        val overridden = context.functionReferenceSymbols.boundValueAt
        functionReferenceClass.addFunction {
            origin = IrDeclarationOrigin.DEFINED
            name = overridden.name
            visibility = DescriptorVisibilities.PROTECTED
            modality = Modality.FINAL
            returnType = context.irBuiltIns.anyNType
        }.apply function@{
            overriddenSymbols = listOf(overridden.symbol)
            parameters += createDispatchReceiverParameterWithClassParent()
            parameters += overridden.parameters.single { it.kind == IrParameterKind.Regular }.copyTo(this)
            body = context.createIrBuilder(symbol).irBlockBody {
                val receiver = irGet(this@function.dispatchReceiverParameter!!)
                fun boundValue(field: IrField): IrExpression = irGetField(receiver, field)
                val result = if (fields.size == 1) {
                    boundValue(fields.single())
                } else {
                    val index = this@function.parameters.single { it.kind == IrParameterKind.Regular }
                    irWhen(
                        context.irBuiltIns.anyNType,
                        fields.dropLast(1).mapIndexed { fieldIndex, field ->
                            irBranch(irEquals(irGet(index), irInt(fieldIndex)), boundValue(field))
                        } + irElseBranch(boundValue(fields.last())),
                    )
                }
                +irReturn(result)
            }
        }
    }

    override fun postprocessClass(functionReferenceClass: IrClass, functionReference: IrRichFunctionReference) {
        functionReferenceClass.dotNetInventedLocalClassName = functionReference.dotNetInventedLocalClassName
        if (functionReference.type.isKFunction() && !functionReference.type.isKSuspendFunction()) {
            // AbstractFunctionReferenceLowering adds fake overrides after generateExtraMethods.
            // Its KFunctionN view does not recognize the concrete KCallable properties above as
            // satisfying the inherited members, so discard only those redundant fake pairs.
            // The concrete getters remain the single CLR implementations.
            val concretePropertyNames = context.irBuiltIns.kCallableClass.owner.properties
                .mapNotNullTo(linkedSetOf()) { property ->
                    property.name.takeIf { name ->
                        name.asString() in setOf("name", "returnType", "parameters", "typeParameters")
                    }
                }
            functionReferenceClass.declarations.removeAll { declaration ->
                declaration.origin == IrDeclarationOrigin.FAKE_OVERRIDE && when (declaration) {
                    is IrProperty -> declaration.name in concretePropertyNames
                    is IrSimpleFunction ->
                        declaration.name.asString() == "call" ||
                                declaration.correspondingPropertySymbol?.owner?.name
                                    ?.let { name -> name in concretePropertyNames } == true
                    else -> false
                }
            }
        }
        // Common function-reference lowering creates bound-value fields without a specialized
        // origin. Mark them as ordinary captured-value fields so the strict CLR class renderer
        // accepts exactly the same private state shape that closure conversion produces.
        functionReferenceClass.declarations.filterIsInstance<IrField>().forEach { field ->
            if (field.correspondingPropertySymbol == null && field.origin == IrDeclarationOrigin.DEFINED) {
                field.origin = LocalDeclarationsLowering.DECLARATION_ORIGIN_FIELD_FOR_CAPTURED_VALUE
            }
        }
        functionReferenceClass.dotNetLocalCaptureRejectionReason = when {
            functionReference.invokeFunction.isSuspend ->
                "is suspend; the suspend-callable ABI is deliberately reserved"
            functionReference.type.isKSuspendFunction() ->
                "requires suspend-callable reflection metadata, which is deliberately outside the callable ABI slice"
            functionReferenceClass.superTypes.none { superType ->
                superType.classOrNull?.owner?.dotNetFixedFunctionArityOrNull() != null
            } ->
                "does not use a supported fixed Function0, Function1, Function2, or Function3 interface"
            else -> null
        }
    }
}

private val IrRichFunctionReference.hasFunctionReferenceIdentity: Boolean
    get() = reflectionTargetSymbol != null

private fun IrRichFunctionReference.reflectedName(): String {
    val target = reflectionTargetSymbol?.owner
        ?: error("Internal .NET backend error: callable identity requested without a reflection target")
    return target.metadata?.name?.asString() ?: target.name.asString()
}

/**
 * Declarations with a serialized Kotlin signature use it, matching Wasm's identity source. When
 * the current IR has no signature, the containing logical file and declaration offsets
 * disambiguate otherwise identical full Kotlin mangles without embedding a machine-local path.
 */
private fun IrRichFunctionReference.stableReferenceId(): String {
    val target = reflectionTargetSymbol?.owner
        ?: error("Internal .NET backend error: callable identity requested without a reflection target")
    target.symbol.signature?.let { return "signature:$it" }
    val fileName = target.fileOrNull?.fileEntry?.name
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?: "<unknown>"
    val mangle = with(DotNetIrMangler) { target.mangleString(compatibleMode = false) }
    return "local:$fileName:${target.startOffset}:${target.endOffset}:$mangle"
}

private fun IrRichFunctionReference.referenceArity(): Int =
    invokeFunction.parameters.size - boundValues.size + if (invokeFunction.isSuspend) 1 else 0

private fun IrRichFunctionReference.referenceFlags(): Int = listOfNotNull(
    (1 shl 0).takeIf { invokeFunction.isSuspend },
    (1 shl 1).takeIf { hasVarargConversion },
    (1 shl 2).takeIf { hasSuspendConversion },
    (1 shl 3).takeIf { hasUnitConversion },
    (1 shl 4).takeIf { isFunInterfaceConstructorAdapter() },
).sum()

private fun IrRichFunctionReference.isFunInterfaceConstructorAdapter(): Boolean =
    invokeFunction.origin == IrDeclarationOrigin.ADAPTER_FOR_FUN_INTERFACE_CONSTRUCTOR

/**
 * Caches every non-capturing callable expression in a class-local static field, following the
 * JVM StaticCallableReferenceLowering identity rule. The later static-initializer sweep moves
 * the initializer into this synthetic class's `.cctor`.
 */
internal class DotNetStaticCallableReferenceLowering(private val context: DotNetBackendContext) :
    IrElementTransformerVoid(), FileLoweringPass {

    private val fields = mutableMapOf<IrClass, IrField>()

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(this)
    }

    override fun visitClass(declaration: IrClass): IrStatement {
        val field = declaration.takeIf(::isCacheable)?.let(::fieldFor)
        declaration.transformChildrenVoid(this)
        if (field != null && field !in declaration.declarations) {
            declaration.declarations.add(0, field)
        }
        return declaration
    }

    override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
        val constructedClass = expression.symbol.owner.parent as? IrClass
            ?: return super.visitConstructorCall(expression)
        if (!isCacheable(constructedClass) || expression.symbol.owner != constructedClass.primaryConstructor) {
            return super.visitConstructorCall(expression)
        }
        val field = fieldFor(constructedClass)
        return IrGetFieldImpl(expression.startOffset, expression.endOffset, field.symbol, expression.type)
    }

    private fun isCacheable(irClass: IrClass): Boolean =
        irClass.isDotNetCallableObject &&
                irClass.dotNetLocalCaptureRejectionReason == null &&
                irClass.primaryConstructor?.parameters?.isEmpty() == true

    private fun fieldFor(irClass: IrClass): IrField = fields.getOrPut(irClass) {
        val constructor = irClass.primaryConstructor
            ?: error("Internal .NET backend error: callable class '${irClass.name}' has no primary constructor")
        context.irFactory.buildField {
            name = Name.identifier("INSTANCE")
            type = irClass.defaultType
            origin = IrDeclarationOrigin.FIELD_FOR_OBJECT_INSTANCE
            visibility = DescriptorVisibilities.PUBLIC
            isFinal = true
            isStatic = true
        }.apply {
            parent = irClass
            initializer = context.irFactory.createExpressionBody(
                IrConstructorCallImpl.fromSymbolOwner(
                    startOffset,
                    endOffset,
                    irClass.defaultType,
                    constructor.symbol,
                    classTypeParametersCount = 0,
                )
            )
        }
    }
}
